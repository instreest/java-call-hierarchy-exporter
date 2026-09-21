// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IMenuListener;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ColumnViewerToolTipSupport;
import org.eclipse.jface.viewers.DoubleClickEvent;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.IWorkbenchCommandConstants;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.ActionFactory;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.part.ViewPart;

import jche.eclipse.server.ServerResponse;
import jche.eclipse.server.ServerRow;
import jche.eclipse.server.ServerTree;

/**
 * 呼び出し階層ビュー。主ユースケースは「メソッドを選んで、その呼び出し元を階層で辿る」。
 *
 * <p>画面は<b>木のための場所をできるだけ広く取る</b>。常に出ているのは
 * 対象バー（どのプロジェクトを、どの設定で解析するか）と木の 2 段だけで、
 * 状態のバナーは<b>言うことがあるときしか出さない</b>
 * （docs/eclipse-plugin-ui-simplify-qa.md）。解析もフィルタも<b>子プロセス</b>で行い、
 * ここは返ってきた行を描くだけである（docs/out-of-process-analysis-design.md）。
 *
 * <p>画面は解析の前後で見た目が変わる。
 * <ul>
 *   <li><b>解析前</b>… 対象バーだけ。［解析する］を押すところまでしかできることが無いので、
 *       それ以外は出さない</li>
 *   <li><b>解析後</b>… 木をすべて展開して出す。深さやフィルタの指定は無く、
 *       {@link #MAX_ROWS} 行で打ち切る（打ち切ったときだけ、その旨をバナーに出す）</li>
 * </ul>
 *
 * <p>解析結果は<b>利用者が捨てるまで消えない</b>。ソースが変わっても解析し直さず、
 * 変わったファイルの行に ⚠ を出すだけである（{@link ProjectAnalysis}）。
 */
public class CallHierarchyView extends ViewPart implements AnalysisService.Listener {

    public static final String VIEW_ID = "io.github.instreest.jche.eclipse.callHierarchyView";

    /** 設定ページの ID（plugin.xml の preferencePages と同じ） */
    private static final String PREFERENCE_PAGE_ID = "io.github.instreest.jche.eclipse.preferences";

    /** コピーするときの1段ぶんの字下げ */
    private static final String INDENT = "  ";

    /**
     * 画面に出す行数の上限。これを超えた枝は切り、その旨をバナーに出す。
     *
     * <p>上限を置くのは、深さの指定をやめて「すべて展開」で出すようにしたからである。
     * 大きなプロジェクトの呼び出し元は数万行になることがあり、そのまま展開すると
     * 木を作る時間も画面の操作も戻ってこない。
     */
    private static final int MAX_ROWS = 1000;

    /**
     * 深さの上限。{@link #MAX_ROWS} 行で打ち切るので、1 行 1 階層でもここには届かない。
     * つまり<b>実質は無制限</b>で、深さの指定という考え方そのものを画面から無くしている。
     */
    private static final int MAX_DEPTH = 1000;

    private Combo projectCombo;
    private Label configLabel;
    private Button analyzeButton;
    private Composite banner;
    private Label bannerLabel;
    private Button bannerAction;
    private Button bannerCancel;
    private TreeViewer viewer;
    private CallersContentProvider contentProvider;
    private CallersLabelProvider labelProvider;

    private boolean callers = true;

    private ProjectAnalysis analysis;
    /** 表示しているメソッド。ID ではなくキーで持つ（解析し直しても指すものが変わらない） */
    private String targetKey;

    /** いま画面に反映してある状態。これが変わらない知らせでは、木を取り寄せ直さない */
    private ProjectAnalysis.State shownState;
    private ServerResponse shownAnalysis;

    /** プロジェクト選択の並び（{@link #projectCombo} の項目と同じ順） */
    private List<IProject> projectItems = new ArrayList<IProject>();

    /** バナーのボタンを押したときにすること。ボタンが隠れているときは null */
    private Runnable bannerActionRun;

    private Action callersAction;
    private Action calleesAction;
    private Action copyAction;
    private Action copySubtreeAction;
    private Action exportAction;

    @Override
    public void createPartControl(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        layout.verticalSpacing = 0;
        root.setLayout(layout);

        createTargetBar(root);
        createBanner(root);
        createTree(root);
        createActions();

        AnalysisService service = JchePlugin.service();
        if (service != null) {
            service.addListener(this);
        }
        reloadProjects();
        selectProjectOfActiveEditor();
        refresh();
    }

    // ------------------------------------------------------------
    // 画面の組み立て
    // ------------------------------------------------------------

    /**
     * 対象バー（どのプロジェクトを、どの設定で解析するか、そして［解析する］）。
     *
     * <p>解析前に要るのはこの 4 つだけである。プロジェクトが決まらなければ解析は始められず、
     * 解析が終わらなければ木は出せないので、それ以外を先に見せても選びようがない
     * （docs/eclipse-plugin-ui-simplify-qa.md の Q3）。
     */
    private void createTargetBar(Composite parent) {
        Composite bar = new Composite(parent, SWT.NONE);
        bar.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        GridLayout layout = new GridLayout(5, false);
        layout.marginHeight = 3;
        bar.setLayout(layout);

        new Label(bar, SWT.NONE).setText(Messages.get("view.project"));
        projectCombo = new Combo(bar, SWT.READ_ONLY | SWT.DROP_DOWN);
        projectCombo.setToolTipText(Messages.get("view.projectTip"));
        projectCombo.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                int index = projectCombo.getSelectionIndex();
                if (index >= 0 && index < projectItems.size()) {
                    selectProject(projectItems.get(index));
                }
            }
        });

        configLabel = new Label(bar, SWT.NONE);
        configLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button configButton = new Button(bar, SWT.PUSH);
        configButton.setText(Messages.get("view.configButton"));
        configButton.setToolTipText(Messages.get("view.configButtonTip"));
        configButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                openConfigDialog();
            }
        });

        analyzeButton = new Button(bar, SWT.PUSH);
        analyzeButton.setText(Messages.get("view.analyzeButton"));
        analyzeButton.setToolTipText(Messages.get("view.analyzeButtonTip"));
        analyzeButton.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
        analyzeButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                reanalyze();
            }
        });
    }

    /**
     * 状態のバナー。<b>言うことがあるときだけ</b>現れ、無いときは行そのものを畳む。
     * 「10:31:04 時点の解析結果」のような、読んでも次にすることが変わらない知らせは出さない。
     */
    private void createBanner(Composite parent) {
        banner = new Composite(parent, SWT.NONE);
        banner.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        GridLayout layout = new GridLayout(3, false);
        layout.marginHeight = 3;
        banner.setLayout(layout);

        bannerLabel = new Label(banner, SWT.WRAP);
        bannerLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        bannerAction = new Button(banner, SWT.PUSH);
        bannerAction.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
        bannerAction.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                Runnable action = bannerActionRun;
                if (action != null) {
                    action.run();
                }
            }
        });

        bannerCancel = new Button(banner, SWT.PUSH);
        bannerCancel.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
        bannerCancel.setText(Messages.get("banner.cancel"));
        bannerCancel.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                if (analysis != null) {
                    analysis.cancel();
                }
            }
        });
    }

    private void createTree(Composite parent) {
        viewer = new TreeViewer(parent, SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL);
        viewer.getTree().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        contentProvider = new CallersContentProvider();
        labelProvider = new CallersLabelProvider(this);
        viewer.setContentProvider(contentProvider);
        viewer.setLabelProvider(labelProvider);
        viewer.setUseHashlookup(true);
        ColumnViewerToolTipSupport.enableFor(viewer);
        viewer.addDoubleClickListener(this::openSelected);
        viewer.addSelectionChangedListener(e -> updateRowActions());
        getSite().setSelectionProvider(viewer);
    }

    /**
     * ツールバーと［▽］メニュー。
     *
     * <p>押せるものはすべて<b>アイコン</b>にして、文字のボタンを画面から無くした。
     * Eclipse 標準の「呼び出し階層」ビューと同じ並べ方で、木の場所をボタンに食わせない
     * （docs/eclipse-plugin-ui-simplify-qa.md の Q4）。
     */
    private void createActions() {
        Action showAtCursorAction = new Action(Messages.get("action.methodAtCursor")) {
            @Override
            public void run() {
                showMethodAtCursor();
            }
        };
        showAtCursorAction.setToolTipText(Messages.get("action.methodAtCursorTip"));
        showAtCursorAction.setImageDescriptor(ViewIcons.of(ViewIcons.AT_CURSOR));

        Action reanalyzeAction = new Action(Messages.get("action.reanalyze")) {
            @Override
            public void run() {
                reanalyze();
            }
        };
        reanalyzeAction.setToolTipText(Messages.get("action.reanalyzeTip"));
        // 再解析（⟳）だけは Eclipse 本体の絵を借りる。同じものを下手に描き直さない
        reanalyzeAction.setImageDescriptor(PlatformUI.getWorkbench().getSharedImages()
                .getImageDescriptor(ISharedImages.IMG_ELCL_SYNCED));

        Action resetAction = new Action(Messages.get("action.reset")) {
            @Override
            public void run() {
                resetAnalysis();
            }
        };
        resetAction.setToolTipText(Messages.get("action.resetTip"));
        resetAction.setImageDescriptor(ViewIcons.of(ViewIcons.RESET));

        // 向きは「押すと切り替わるトグル1つ」ではなく、機能ごとに1つずつ置く。
        // トグルだと、いまどちら向きの木を見ているのかがボタンの押下状態でしか分からず、
        // ラベル（「呼び出し先を見る」）も「いまの向き」なのか「押したらなる向き」なのか読めない
        // （docs/eclipse-plugin-folders-qa.md の Q6）
        callersAction = new Action(Messages.get("action.showCallers"), Action.AS_RADIO_BUTTON) {
            @Override
            public void run() {
                if (isChecked()) {
                    setDirection(true);
                }
            }
        };
        callersAction.setToolTipText(Messages.get("action.showCallersTip"));
        callersAction.setImageDescriptor(ViewIcons.of(ViewIcons.CALLERS));
        calleesAction = new Action(Messages.get("action.showCallees"), Action.AS_RADIO_BUTTON) {
            @Override
            public void run() {
                if (isChecked()) {
                    setDirection(false);
                }
            }
        };
        calleesAction.setToolTipText(Messages.get("action.showCalleesTip"));
        calleesAction.setImageDescriptor(ViewIcons.of(ViewIcons.CALLEES));
        callersAction.setChecked(callers);
        calleesAction.setChecked(!callers);

        Action expandAction = new Action(Messages.get("action.expandAll")) {
            @Override
            public void run() {
                viewer.expandAll();
            }
        };
        expandAction.setToolTipText(Messages.get("action.expandAll"));
        expandAction.setImageDescriptor(ViewIcons.of(ViewIcons.EXPAND_ALL));
        Action collapseAction = new Action(Messages.get("action.collapseAll")) {
            @Override
            public void run() {
                viewer.collapseAll();
            }
        };
        collapseAction.setToolTipText(Messages.get("action.collapseAll"));
        collapseAction.setImageDescriptor(ViewIcons.of(ViewIcons.COLLAPSE_ALL));

        createRowActions();

        Action configAction = new Action(Messages.get("view.configButton")) {
            @Override
            public void run() {
                openConfigDialog();
            }
        };
        Action preferencesAction = new Action(Messages.get("action.preferences")) {
            @Override
            public void run() {
                PreferencesUtil.createPreferenceDialogOn(getSite().getShell(),
                        PREFERENCE_PAGE_ID, new String[] {PREFERENCE_PAGE_ID}, null).open();
            }
        };
        preferencesAction.setToolTipText(Messages.get("action.preferencesTip"));
        Action openLogAction = new Action(Messages.get("action.openLogFolder")) {
            @Override
            public void run() {
                openLogFolder();
            }
        };

        IMenuManager menu = getViewSite().getActionBars().getMenuManager();
        menu.add(configAction);
        menu.add(new Separator());
        menu.add(preferencesAction);
        menu.add(openLogAction);

        IToolBarManager toolbar = getViewSite().getActionBars().getToolBarManager();
        toolbar.add(callersAction);
        toolbar.add(calleesAction);
        toolbar.add(new Separator());
        toolbar.add(showAtCursorAction);
        toolbar.add(reanalyzeAction);
        toolbar.add(resetAction);
        toolbar.add(new Separator());
        toolbar.add(expandAction);
        toolbar.add(collapseAction);
    }

    /**
     * 行に対してできること（コピー・開く・CSV 出力）と、その右クリックメニュー。
     *
     * <p>見えている木は「影響調査の結果そのもの」なので、報告書や課題票へ貼れないと使いにくい。
     * 階層が分からなくなると意味が変わってしまうため、<b>字下げを付けて</b>持ち出す
     * （docs/eclipse-plugin-folders-qa.md の Q7）。CSV 出力も、たまにしか使わないので
     * ボタンを常に置かず、ここに入れてある。
     */
    private void createRowActions() {
        copyAction = new Action(Messages.get("action.copy")) {
            @Override
            public void run() {
                copyToClipboard(false);
            }
        };
        copyAction.setToolTipText(Messages.get("action.copyTip"));
        copyAction.setActionDefinitionId(IWorkbenchCommandConstants.EDIT_COPY);

        copySubtreeAction = new Action(Messages.get("action.copySubtree")) {
            @Override
            public void run() {
                copyToClipboard(true);
            }
        };
        copySubtreeAction.setToolTipText(Messages.get("action.copySubtreeTip"));

        final Action openAction = new Action(Messages.get("action.openCallSite")) {
            @Override
            public void run() {
                openSelectedRow();
            }
        };

        exportAction = new Action(Messages.get("action.exportCsv")) {
            @Override
            public void run() {
                exportCsv();
            }
        };
        exportAction.setToolTipText(Messages.get("action.exportCsvTip"));

        // Ctrl+C。ビューに焦点があるときだけ効く（Eclipse の共通のやり方）
        getViewSite().getActionBars().setGlobalActionHandler(
                ActionFactory.COPY.getId(), copyAction);
        getViewSite().getActionBars().updateActionBars();

        MenuManager context = new MenuManager();
        context.setRemoveAllWhenShown(true);
        context.addMenuListener(new IMenuListener() {
            @Override
            public void menuAboutToShow(IMenuManager manager) {
                manager.add(copyAction);
                manager.add(copySubtreeAction);
                manager.add(new Separator());
                manager.add(openAction);
                manager.add(new Separator());
                manager.add(exportAction);
            }
        });
        Tree tree = viewer.getTree();
        tree.setMenu(context.createContextMenu(tree));
        updateRowActions();
    }

    private void updateRowActions() {
        if (copyAction == null) {
            return;   // 画面の組み立て中（選択はまだ起きないが、順序に頼らない）
        }
        boolean any = viewer.getTree().getSelectionCount() > 0;
        copyAction.setEnabled(any);
        copySubtreeAction.setEnabled(any);
        exportAction.setEnabled(targetKey != null && analysis != null && analysis.isAnalyzed());
    }

    // ------------------------------------------------------------
    // 対象の指定（プロジェクト・メソッド）
    // ------------------------------------------------------------

    /**
     * プロジェクトの一覧を作り直す。中身が変わっていなければ何もしない
     * （選択が飛んだり、ちらついたりするのを避ける）。
     */
    private void reloadProjects() {
        List<IProject> found = ProjectAnalysis.analyzableProjects();
        if (sameProjects(found, projectItems)) {
            return;
        }
        projectItems = found;
        String[] names = new String[found.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = found.get(i).getName();
        }
        projectCombo.setItems(names);
        syncProjectSelection();
    }

    private static boolean sameProjects(List<IProject> a, List<IProject> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).equals(b.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** いま表示しているプロジェクトに、コンボの選択を合わせる */
    private void syncProjectSelection() {
        int index = (analysis == null) ? -1 : projectItems.indexOf(analysis.project());
        if (index >= 0) {
            projectCombo.select(index);
        } else {
            projectCombo.deselectAll();
        }
    }

    /**
     * ビューを開いたときの当て推量。エディタで開いているファイルのプロジェクトを選ぶ。
     * それも無く、解析できるプロジェクトが1つしか無ければ、それを選ぶ。
     */
    private void selectProjectOfActiveEditor() {
        if (analysis != null) {
            return;
        }
        IProject guess = projectOfActiveEditor();
        if (guess == null && projectItems.size() == 1) {
            guess = projectItems.get(0);
        }
        if (guess != null && projectItems.contains(guess)) {
            selectProject(guess);
        }
    }

    private IProject projectOfActiveEditor() {
        if (getSite().getPage() == null || getSite().getPage().getActiveEditor() == null) {
            return null;
        }
        org.eclipse.ui.IEditorInput input = getSite().getPage().getActiveEditor().getEditorInput();
        if (!(input instanceof org.eclipse.ui.IFileEditorInput)) {
            return null;
        }
        IFile file = ((org.eclipse.ui.IFileEditorInput) input).getFile();
        return (file == null) ? null : file.getProject();
    }

    /** 対象プロジェクトを変える。表示していたメソッドは持ち越さない（別のプロジェクトのものだから） */
    private void selectProject(IProject project) {
        AnalysisService service = JchePlugin.service();
        if (service == null || project == null) {
            return;
        }
        ProjectAnalysis picked = service.analysisFor(project);
        if (picked == analysis) {
            return;
        }
        analysis = picked;
        targetKey = null;
        syncProjectSelection();
        refresh();
    }

    /** このメソッドの階層を出す。解析がまだでも受け付け、状態はバナーで伝える */
    public void showMethod(ProjectAnalysis target, IMethod method) {
        this.analysis = target;
        this.targetKey = MethodKeys.keyOf(method);
        reloadProjects();
        syncProjectSelection();
        refresh();
    }

    /** ツールバーの［カーソル位置のメソッド］。コマンドと同じ道（{@link MethodPicker}）を通る */
    private void showMethodAtCursor() {
        IMethod method = MethodPicker.pick(getSite().getPage(),
                getSite().getWorkbenchWindow().getSelectionService().getSelection());
        if (method == null) {
            MessageDialog.openInformation(getSite().getShell(), Messages.get("dialog.title"),
                    Messages.get("method.notIdentifiedInView"));
            return;
        }
        IProject project = (method.getResource() != null)
                ? method.getResource().getProject() : method.getJavaProject().getProject();
        AnalysisService service = JchePlugin.service();
        if (service == null || project == null) {
            return;
        }
        showMethod(service.analysisFor(project), method);
    }

    private void setDirection(boolean toCallers) {
        if (callers == toCallers) {
            return;
        }
        callers = toCallers;
        reload();
    }

    // ------------------------------------------------------------
    // 状態の反映
    // ------------------------------------------------------------

    @Override
    public void analysisChanged(ProjectAnalysis changed) {
        if (changed != analysis) {
            return;
        }
        runOnUi(() -> {
            if (analysis != null && analysis.state() == shownState
                    && analysis.lastAnalysis() == shownAnalysis) {
                // 変わったのは「解析後に触られたファイル」だけ。行の ⚠ を付け替えれば足りる。
                // ここで木を取り寄せ直すと、保存のたびに子プロセスへ問い合わせることになる
                viewer.refresh();
                return;
            }
            refresh();
        });
    }

    private void runOnUi(Runnable action) {
        Display display = viewer.getControl().getDisplay();
        display.asyncExec(() -> {
            if (!viewer.getControl().isDisposed()) {
                action.run();
            }
        });
    }

    private void refresh() {
        shownState = (analysis == null) ? null : analysis.state();
        shownAnalysis = (analysis == null) ? null : analysis.lastAnalysis();
        updateConfigLabel();
        updateAnalyzeButton();
        updateBanner();
        reload();
    }

    /**
     * 対象バーの「設定: …」。何を見て解析するかを、いつでも出しておく。
     * どの JDK で・どこにファイルを作ったかは、この吹き出しにまとめる。
     */
    private void updateConfigLabel() {
        if (analysis == null) {
            configLabel.setText("");
            configLabel.setToolTipText(null);
        } else {
            ConfigSource source = analysis.configSource();
            String label = (source == null) ? Messages.get("config.unavailable") : source.label();
            configLabel.setText(Messages.format("view.configLabel", label));
            configLabel.setToolTipText(Messages.get("view.configLabelTip")
                    + "\n\n" + environmentTooltip());
        }
        configLabel.getParent().layout();
    }

    /** ［解析する］は解析前にだけ出す。解析のやり直しはツールバーの⟳ */
    private void updateAnalyzeButton() {
        boolean needed = analysis != null && !analysis.isAnalyzed() && !analysis.isAnalyzing()
                && analysis.configSource() != null;
        show(analyzeButton, needed);
        analyzeButton.getParent().layout();
    }

    /** いまの条件で木を取り寄せ、届いたら描き直す */
    private void reload() {
        updateRowActions();
        if (analysis == null || targetKey == null || !analysis.isAnalyzed()) {
            viewer.setInput(null);
            return;
        }
        if (analysis.isAnalyzing() && viewer.getInput() != null) {
            // 解析し直している間は、前の木をそのまま見せる。子プロセスとのやりとりは
            // 1 件ずつ（ServerConnection）なので、ここで聞いても解析が終わるまで返らない
            return;
        }
        final String requestedKey = targetKey;
        analysis.requestTree(targetKey, callers, treeWords(true), (response, error) -> runOnUi(() -> {
            if (!requestedKey.equals(targetKey)) {
                return;   // 待っている間に別のメソッドへ切り替わった
            }
            applyTree(response, error);
        }));
    }

    /**
     * 木を切り出す条件。画面から選べるものは何も無い（深さもフィルタも廃止した）。
     *
     * <p>落とすのは設定ファイルの {@code exclude.packages} だけで、テストからの呼び出しも
     * 推測で特定した呼び出しも出す。画面に切り替えが無い以上、<b>黙って落とさない</b>ほうを選ぶ
     * （docs/eclipse-plugin-ui-simplify-qa.md の Q5）。
     *
     * @param limited 画面に出すための行数の上限を付けるか（CSV 出力では付けない）
     */
    private static String[] treeWords(boolean limited) {
        List<String> words = new ArrayList<String>();
        words.add("depth=" + MAX_DEPTH);
        words.add("tests=1");
        words.add("guessed=1");
        words.add("exclude=1");
        words.add("dedupe=1");
        if (limited) {
            words.add("max=" + MAX_ROWS);
        }
        return words.toArray(new String[0]);
    }

    private void applyTree(ServerResponse response, String error) {
        if (error != null) {
            viewer.setInput(null);
            setBanner(Messages.format("banner.serverError", error),
                    Messages.get("banner.reanalyze"), this::reanalyze, false);
            return;
        }
        if (!response.isOk()) {
            viewer.setInput(null);
            if ("not-found".equals(response.reason())) {
                setBanner(Messages.get("banner.methodNotFound"),
                        Messages.get("banner.reanalyze"), this::reanalyze, false);
            } else if ("not-analyzed".equals(response.reason())) {
                setBanner(Messages.get("state.notAnalyzed"),
                        Messages.get("view.analyzeButton"), this::reanalyze, false);
            } else {
                setBanner(Messages.format("banner.treeError", response.reason()),
                        Messages.get("banner.reanalyze"), this::reanalyze, false);
            }
            return;
        }
        viewer.setInput(ServerTree.of(response.rows()));
        // 「すべて展開」した姿で出す。畳んだ木から毎回開き直すのが手間だったため
        viewer.expandAll();
        updateBanner();
        if (response.rows().size() >= MAX_ROWS) {
            // 打ち切りは黙らない。出ていない呼び出しがあることは、必ず画面で言う
            setBanner(Messages.format("banner.rowLimit", Integer.valueOf(MAX_ROWS)),
                    null, null, false);
        }
    }

    /**
     * バナーの書き換え。
     *
     * <p>出すのは「次に何かしないと先へ進めないこと」と「黙ってはいけないこと」だけである。
     * 解析が終わって木が出ているときは何も出さない（その行ぶん木が広くなる）。
     */
    private void updateBanner() {
        if (analysis == null) {
            setBanner(projectItems.isEmpty()
                    ? Messages.get("banner.noProjects") : Messages.get("banner.pickProject"),
                    null, null, false);
            return;
        }
        ProjectAnalysis.State state = analysis.state();
        switch (state) {
            case NO_CONFIG:
                setBanner(Messages.get("state.noConfig"),
                        Messages.get("view.configButton"), this::openConfigDialog, false);
                break;
            case NOT_ANALYZED:
                // 案内は要らない。対象バーの［解析する］が、そのまま次にすることである
                clearBanner();
                break;
            case ANALYZING:
            case UPDATING:
                setBanner(Messages.get("state.analyzing"), null, null, true);
                break;
            case FAILED:
                setBanner(Messages.format("state.failed", analysis.errorMessage()),
                        Messages.get("banner.retry"), this::reanalyze, false);
                break;
            case READY:
                readyBanner();
                break;
            default:
                clearBanner();
                break;
        }
    }

    /**
     * 解析済みのときのバナー。ふつうは何も出さない。
     *
     * <p>出すのは2つだけ。メソッドをまだ選んでいないとき（次にすることがある）と、
     * 構文エラーで読めなかったファイルがあるとき（木に出ていない呼び出しがある）である。
     */
    private void readyBanner() {
        String note = syntaxErrorNote(analysis.lastAnalysis());
        if (targetKey == null) {
            String message = Messages.get("banner.pickMethod");
            setBanner(note.isEmpty() ? message : message + " " + note,
                    Messages.get("action.methodAtCursor"), this::showMethodAtCursor, false);
        } else if (!note.isEmpty()) {
            setBanner(note, null, null, false);
        } else {
            clearBanner();
        }
    }

    /**
     * 構文エラーで本体を読めなかったファイルがあれば、その断り。無ければ空。
     *
     * <p>木に出ていないのは「呼び出し元が無い」からではなく「読めていない」からだ、と
     * 言い分ける唯一の場所である。解析側の警告はログにしか出ないので、ここで拾って画面に出す。
     */
    private static String syntaxErrorNote(ServerResponse last) {
        if (last == null) {
            return "";
        }
        String count = last.field("syntaxErrors");
        if (count.isEmpty() || "0".equals(count)) {
            return "";
        }
        return Messages.format("state.syntaxErrors", count);
    }

    private void reanalyze() {
        if (analysis != null) {
            analysis.reanalyze();
        }
    }

    /**
     * 解析をリセットする（手動）。持っている解析結果を捨て、解析前の画面に戻す。
     *
     * <p>確かめてから捨てるのは、作り直すのに時間がかかるからである。
     * 消すのは<b>メモリの中の解析結果だけ</b>で、ディスクのキャッシュは残す（作り直しが速く済む）。
     * ディスクのファイルごと消したいときは設定画面の［解析キャッシュを削除］
     * （docs/eclipse-plugin-ui-simplify-qa.md の Q8）。
     */
    private void resetAnalysis() {
        if (analysis == null || !analysis.isAnalyzed()) {
            return;
        }
        if (!MessageDialog.openConfirm(getSite().getShell(), Messages.get("dialog.title"),
                Messages.format("dialog.resetConfirm", analysis.project().getName()))) {
            return;
        }
        targetKey = null;
        analysis.clearAnalysis();
    }

    private void clearBanner() {
        setBanner("", null, null, false);
    }

    /** バナーの書き換え。message が空なら、バナーの行そのものを畳む */
    private void setBanner(String message, String actionLabel, Runnable action, boolean cancellable) {
        bannerLabel.setText(message);
        bannerActionRun = action;
        bannerAction.setText(actionLabel == null ? "" : actionLabel);
        show(bannerAction, actionLabel != null);
        show(bannerCancel, cancellable);
        show(banner, !message.isEmpty());
        banner.layout(true, true);
        banner.getParent().layout(true, true);
    }

    /** 部品を出す・引っ込める。引っ込めるときは場所も空けさせる（exclude） */
    private static void show(Control control, boolean visible) {
        control.setVisible(visible);
        Object data = control.getLayoutData();
        if (data instanceof GridData) {
            ((GridData) data).exclude = !visible;
        }
    }

    /**
     * 「どの設定で」「何の上で」解析し、「どこにファイルを作ったか」。
     * 解析は別プロセスなので、Eclipse を動かしている JDK とは別のものが使われる。
     */
    private String environmentTooltip() {
        StringBuilder sb = new StringBuilder();
        if (analysis != null) {
            ServerResponse info = analysis.serverInfo();
            if (info != null) {
                sb.append(Messages.format("tip.serverInfo",
                        info.field("jvm"), info.field("jdt"), info.field("maxJava"))).append('\n');
            }
            ServerResponse last = analysis.lastAnalysis();
            if (last != null) {
                sb.append(Messages.format("tip.sourceLevel", last.field("sourceLevel"))).append('\n');
            }
        }
        sb.append(Messages.format("tip.cacheAt", PluginFolders.cacheRoot().getAbsolutePath()))
                .append('\n');
        sb.append(Messages.format("tip.logAt", PluginFolders.logFolder().getAbsolutePath()))
                .append('\n');
        sb.append(Messages.get("tip.outOfProcess"));
        return sb.toString();
    }

    // ------------------------------------------------------------
    // 操作
    // ------------------------------------------------------------

    /** ダブルクリック。{@code event} は使わないが、リスナの形に合わせて受け取る */
    private void openSelected(DoubleClickEvent event) {
        openSelectedRow();
    }

    /** 選んだ行の「呼び出している行」をエディタで開く */
    private void openSelectedRow() {
        if (analysis == null || !(viewer.getSelection() instanceof IStructuredSelection)) {
            return;
        }
        IStructuredSelection selection = (IStructuredSelection) viewer.getSelection();
        if (!(selection.getFirstElement() instanceof ServerTree.Node)) {
            return;
        }
        ServerRow row = ((ServerTree.Node) selection.getFirstElement()).row();
        if (row.file().isEmpty()) {
            setBanner(Messages.get("banner.rowNoSource"), null, null, false);
            return;
        }
        ServerResponse last = analysis.lastAnalysis();
        String projectRoot = (last == null) ? "" : last.field("root");
        boolean opened = !projectRoot.isEmpty() && EditorOpener.open(getSite().getPage(),
                java.nio.file.Paths.get(projectRoot), row.file(), row.line());
        if (!opened) {
            setBanner(Messages.get("banner.openFailed"), null, null, false);
        }
    }

    // ------------------------------------------------------------
    // コピー
    // ------------------------------------------------------------

    /**
     * 選んだ行をクリップボードへ。字下げは<b>画面に見えているとおりの深さ</b>で付ける。
     *
     * @param withDescendants その行の下にある行も含めるか（畳んでいるものも含む）
     */
    private void copyToClipboard(boolean withDescendants) {
        TreeItem[] selected = viewer.getTree().getSelection();
        if (selected.length == 0) {
            return;
        }
        Set<TreeItem> selectedSet = new HashSet<TreeItem>();
        int base = Integer.MAX_VALUE;
        for (TreeItem item : selected) {
            selectedSet.add(item);
            base = Math.min(base, depthOf(item));
        }
        String newline = System.getProperty("line.separator", "\n");
        StringBuilder sb = new StringBuilder();
        for (TreeItem item : selected) {
            // 親も選ばれているなら、その親からの書き出しに含まれるので重ねない
            if (withDescendants && hasSelectedAncestor(item, selectedSet)) {
                continue;
            }
            append(sb, item.getData(), depthOf(item) - base, withDescendants, newline);
        }
        setClipboardText(sb.toString());
    }

    /** 1行ぶん（と、求められていれば配下）を書き出す */
    private void append(StringBuilder sb, Object element, int level, boolean withDescendants,
                        String newline) {
        if (!(element instanceof ServerTree.Node)) {
            return;
        }
        for (int i = 0; i < level; i++) {
            sb.append(INDENT);
        }
        sb.append(labelProvider.getText(element)).append(newline);
        if (!withDescendants) {
            return;
        }
        for (Object child : contentProvider.getChildren(element)) {
            append(sb, child, level + 1, true, newline);
        }
    }

    /** ツリーの項目の段数（根が 0） */
    private static int depthOf(TreeItem item) {
        int depth = 0;
        for (TreeItem parent = item.getParentItem(); parent != null; parent = parent.getParentItem()) {
            depth++;
        }
        return depth;
    }

    private static boolean hasSelectedAncestor(TreeItem item, Set<TreeItem> selected) {
        for (TreeItem parent = item.getParentItem(); parent != null; parent = parent.getParentItem()) {
            if (selected.contains(parent)) {
                return true;
            }
        }
        return false;
    }

    private void setClipboardText(String text) {
        if (text.isEmpty()) {
            return;
        }
        Clipboard clipboard = new Clipboard(viewer.getControl().getDisplay());
        try {
            clipboard.setContents(new Object[] {text}, new Transfer[] {TextTransfer.getInstance()});
        } finally {
            clipboard.dispose();
        }
    }

    // ------------------------------------------------------------

    /**
     * いま見ている木を CSV に書き出す（右クリックから）。
     *
     * <p>画面と違って<b>行数の上限は付けない</b>。画面の上限は「読める大きさに収める」ための
     * もので、ファイルに書き出すなら全部あったほうがよいからである。
     */
    private void exportCsv() {
        if (analysis == null || targetKey == null || !analysis.isAnalyzed()) {
            return;
        }
        FileDialog dialog = new FileDialog(getSite().getShell(), SWT.SAVE);
        dialog.setFilterExtensions(new String[] {"*.csv"});
        dialog.setFileName("call-hierarchy-view.csv");
        dialog.setFilterPath(PluginFolders.outputRoot().getAbsolutePath());
        dialog.setOverwrite(true);
        final String path = dialog.open();
        if (path == null) {
            return;
        }
        List<String> words = new ArrayList<String>();
        words.add("EXPORT");
        words.add(targetKey);
        words.add(callers ? "callers" : "callees");
        words.add(path);
        for (String word : treeWords(false)) {
            words.add(word);
        }
        analysis.request(Messages.get("export.jobName"), (response, error) -> runOnUi(() -> {
            if (error != null || response == null || !response.isOk()) {
                MessageDialog.openError(getSite().getShell(), Messages.get("dialog.title"),
                        Messages.format("export.failed",
                                (error != null) ? error : response.reason()));
            } else {
                setBanner(Messages.format("export.done", path, response.field("rows")),
                        null, null, false);
            }
        }), 300_000L, words.toArray(new String[0]));
    }

    /** 「解析に使う設定」ダイアログ。設定を選び直したら、その場で表示に反映する */
    private void openConfigDialog() {
        if (analysis == null) {
            MessageDialog.openInformation(getSite().getShell(), Messages.get("dialog.title"),
                    Messages.get("banner.pickProject"));
            return;
        }
        new ConfigDialog(getSite().getShell(), analysis).open();
        refresh();
    }

    private void openLogFolder() {
        java.io.File folder = AnalysisLog.folder();
        if (!folder.isDirectory() && !folder.mkdirs()) {
            MessageDialog.openInformation(getSite().getShell(), Messages.get("dialog.title"),
                    Messages.format("prefs.folderNotCreated", folder.getAbsolutePath()));
            return;
        }
        if (!org.eclipse.swt.program.Program.launch(folder.getAbsolutePath())) {
            MessageDialog.openInformation(getSite().getShell(), Messages.get("dialog.title"),
                    folder.getAbsolutePath());
        }
    }

    /** ラベルプロバイダから使う。そのファイルが解析後に変わっているか */
    boolean isChangedSinceAnalysis(String relativePath) {
        return analysis != null && analysis.isChangedSinceAnalysis(relativePath);
    }

    @Override
    public void setFocus() {
        // 開いている間にプロジェクトが増えることもある。戻ってきたときに拾い直す
        reloadProjects();
        viewer.getControl().setFocus();
    }

    @Override
    public void dispose() {
        AnalysisService service = JchePlugin.service();
        if (service != null) {
            service.removeListener(this);
        }
        super.dispose();
    }
}
