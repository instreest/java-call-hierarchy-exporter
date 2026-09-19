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
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ColumnViewerToolTipSupport;
import org.eclipse.jface.viewers.DoubleClickEvent;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeViewerListener;
import org.eclipse.jface.viewers.TreeExpansionEvent;
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
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IWorkbenchCommandConstants;
import org.eclipse.ui.actions.ActionFactory;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.part.ViewPart;

import jche.eclipse.server.ServerResponse;
import jche.eclipse.server.ServerRow;
import jche.eclipse.server.ServerTree;

/**
 * 呼び出し階層ビュー。主ユースケースは「メソッドを選んで、その呼び出し元を階層で辿る」。
 *
 * <p>画面は上から 対象バー／バナー（状態）／フィルタバー／ツリー／件数 の5段
 * （docs/eclipse-plugin-ui-design.md）。解析もフィルタも<b>子プロセス</b>で行い、
 * ここは返ってきた行を描くだけである（docs/out-of-process-analysis-design.md）。
 * 守っているのは次の3点。
 * <ul>
 *   <li>解析は待たない。解析中でも、前の結果を出したままバナーだけが動く</li>
 *   <li>フィルタは解析を起こさない。条件を変えたら木を取り寄せ直すだけ</li>
 *   <li>結果が古いことは、バナー・行の警告アイコン・ツールチップの3段階で示す</li>
 * </ul>
 *
 * <p>一番上の<b>対象バー</b>（プロジェクトと設定）は後から足したものである。以前はビューを
 * 開いただけでは何も指定されておらず、解析を始めることすらできなかった（「メソッドを選んで…」と
 * 出るだけで、ボタンも押せない）。入口がコマンドだけだったためで、
 * ビュー単体でも「プロジェクトを選ぶ → 解析する → メソッドを指す」と進めるようにした
 * （docs/eclipse-plugin-folders-qa.md の Q4）。
 */
public class CallHierarchyView extends ViewPart implements AnalysisService.Listener {

    public static final String VIEW_ID = "io.github.instreest.jche.eclipse.callHierarchyView";

    /** 設定ページの ID（plugin.xml の preferencePages と同じ） */
    private static final String PREFERENCE_PAGE_ID = "io.github.instreest.jche.eclipse.preferences";

    /** 絞り込み文字列を打ち終わるのを待つ時間（ミリ秒） */
    private static final int FILTER_DELAY_MS = 250;

    /** コピーするときの1段ぶんの字下げ */
    private static final String INDENT = "  ";

    private Combo projectCombo;
    private Label configLabel;
    private Composite banner;
    private Label bannerLabel;
    private Button bannerAction;
    private Button bannerCancel;
    private Text filterText;
    private Spinner depthSpinner;
    private TreeViewer viewer;
    private CallersContentProvider contentProvider;
    private CallersLabelProvider labelProvider;
    private Label footer;

    private final FilterSettings filters = new FilterSettings();
    private boolean callers = true;

    private ProjectAnalysis analysis;
    /** 表示しているメソッド。ID ではなくキーで持つ（解析し直しても指すものが変わらない） */
    private String targetKey;
    private String targetLabel;
    private int pendingRebuild;

    /** プロジェクト選択の並び（{@link #projectCombo} の項目と同じ順） */
    private List<IProject> projectItems = new ArrayList<IProject>();

    /** バナーのボタンを押したときにすること。ボタンが隠れているときは null */
    private Runnable bannerActionRun;

    private Action autoAnalyzeAction;
    private Action callersAction;
    private Action calleesAction;
    private Action copyAction;
    private Action copySubtreeAction;

    @Override
    public void createPartControl(Composite parent) {
        loadFilters();
        Composite root = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        layout.verticalSpacing = 0;
        root.setLayout(layout);

        createTargetBar(root);
        createBanner(root);
        createFilterBar(root);
        createTree(root);
        createFooter(root);
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
     * 対象バー（どのプロジェクトを、どの設定で解析するか）。
     *
     * <p>ここが無いと、ビューを開いただけの利用者には何もできることが無い。
     * 「解析する」は<b>プロジェクトが決まって初めて意味を持つ</b>ので、その指定を最初に置く。
     */
    private void createTargetBar(Composite parent) {
        Composite bar = new Composite(parent, SWT.NONE);
        bar.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        GridLayout layout = new GridLayout(4, false);
        layout.marginHeight = 3;
        bar.setLayout(layout);

        new Label(bar, SWT.NONE).setText("対象プロジェクト:");
        projectCombo = new Combo(bar, SWT.READ_ONLY | SWT.DROP_DOWN);
        projectCombo.setToolTipText("解析するプロジェクトを選びます"
                + "（Java プロジェクト、または設定ファイルのあるプロジェクト）");
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
        configButton.setText("解析に使う設定…");
        configButton.setToolTipText("いま何を起点に、どこをソースフォルダとして解析するかを見て、選び直します");
        configButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                openConfigDialog();
            }
        });
    }

    private void createBanner(Composite parent) {
        banner = new Composite(parent, SWT.NONE);
        banner.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        GridLayout layout = new GridLayout(3, false);
        layout.marginHeight = 3;
        banner.setLayout(layout);

        bannerLabel = new Label(banner, SWT.WRAP);
        bannerLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        bannerAction = new Button(banner, SWT.PUSH);
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
        bannerCancel.setText("中止");
        bannerCancel.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                if (analysis != null) {
                    analysis.cancel();
                }
            }
        });
    }

    private void createFilterBar(Composite parent) {
        Composite bar = new Composite(parent, SWT.NONE);
        bar.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        GridLayout layout = new GridLayout(5, false);
        layout.marginHeight = 3;
        bar.setLayout(layout);

        filterText = new Text(bar, SWT.SEARCH | SWT.ICON_SEARCH | SWT.ICON_CANCEL);
        filterText.setMessage("型名・メソッド名で絞り込む（解析は走りません）");
        filterText.setText(filters.text);
        filterText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        filterText.addModifyListener(e -> {
            filters.text = filterText.getText();
            scheduleReload();
        });

        new Label(bar, SWT.NONE).setText("深さ:");
        depthSpinner = new Spinner(bar, SWT.BORDER);
        depthSpinner.setMinimum(1);
        depthSpinner.setMaximum(50);
        depthSpinner.setSelection(filters.maxDepth);
        depthSpinner.addModifyListener(e -> {
            filters.maxDepth = depthSpinner.getSelection();
            scheduleReload();
        });

        Button more = new Button(bar, SWT.PUSH);
        more.setText("フィルタ…");
        more.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                if (new FilterDialog(getSite().getShell(), filters).open()
                        == org.eclipse.jface.window.Window.OK) {
                    reload();
                }
            }
        });

        Button export = new Button(bar, SWT.PUSH);
        export.setText("CSV出力");
        export.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                exportCsv();
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
        viewer.addSelectionChangedListener(e -> updateCopyActions());
        viewer.addTreeListener(new ITreeViewerListener() {
            @Override
            public void treeExpanded(TreeExpansionEvent event) {
                loadContinuation(event.getElement());
            }

            @Override
            public void treeCollapsed(TreeExpansionEvent event) {
                // 何もしない
            }
        });
        getSite().setSelectionProvider(viewer);
    }

    private void createFooter(Composite parent) {
        footer = new Label(parent, SWT.NONE);
        footer.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));
    }

    private void createActions() {
        Action showAtCursorAction = new Action("カーソル位置のメソッド") {
            @Override
            public void run() {
                showMethodAtCursor();
            }
        };
        showAtCursorAction.setToolTipText("エディタでカーソルを置いているメソッド（または選んでいるメソッド）を表示する");

        Action reanalyzeAction = new Action("再解析") {
            @Override
            public void run() {
                if (analysis != null) {
                    analysis.reanalyze();
                }
            }
        };
        reanalyzeAction.setToolTipText("いま解析し直す（別プロセスで走ります）");

        autoAnalyzeAction = new Action("自動再解析", Action.AS_CHECK_BOX) {
            @Override
            public void run() {
                if (analysis != null) {
                    analysis.setAutoAnalyze(isChecked());
                }
            }
        };
        autoAnalyzeAction.setToolTipText("ソースが変わったら、ビルド後に自動で解析し直す");
        autoAnalyzeAction.setChecked(true);

        // 向きは「押すと切り替わるトグル1つ」ではなく、機能ごとに1つずつ置く。
        // トグルだと、いまどちら向きの木を見ているのかがボタンの押下状態でしか分からず、
        // ラベル（「呼び出し先を見る」）も「いまの向き」なのか「押したらなる向き」なのか読めない
        // （docs/eclipse-plugin-folders-qa.md の Q6）
        callersAction = new Action("呼び出し元を表示", Action.AS_RADIO_BUTTON) {
            @Override
            public void run() {
                if (isChecked()) {
                    setDirection(true);
                }
            }
        };
        callersAction.setToolTipText("このメソッドを呼んでいる側をたどる（影響調査の既定）");
        calleesAction = new Action("呼び出し先を表示", Action.AS_RADIO_BUTTON) {
            @Override
            public void run() {
                if (isChecked()) {
                    setDirection(false);
                }
            }
        };
        calleesAction.setToolTipText("このメソッドが呼んでいる側をたどる");
        callersAction.setChecked(callers);
        calleesAction.setChecked(!callers);

        Action expandAction = new Action("すべて展開") {
            @Override
            public void run() {
                viewer.expandAll();
            }
        };
        Action collapseAction = new Action("折りたたむ") {
            @Override
            public void run() {
                viewer.collapseAll();
            }
        };

        createCopyActions();

        Action configAction = new Action("解析に使う設定…") {
            @Override
            public void run() {
                openConfigDialog();
            }
        };
        Action preferencesAction = new Action("設定（JDK・置き場所）…") {
            @Override
            public void run() {
                PreferencesUtil.createPreferenceDialogOn(getSite().getShell(),
                        PREFERENCE_PAGE_ID, new String[] {PREFERENCE_PAGE_ID}, null).open();
            }
        };
        preferencesAction.setToolTipText("解析に使う JDK と、キャッシュ・ログ・CSV の置き場所");
        Action openLogAction = new Action("解析ログのフォルダを開く") {
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
        toolbar.add(autoAnalyzeAction);
        toolbar.add(new Separator());
        toolbar.add(expandAction);
        toolbar.add(collapseAction);
    }

    /**
     * コピーと、その右クリックメニュー。
     *
     * <p>見えている木は「影響調査の結果そのもの」なので、報告書や課題票へ貼れないと使いにくい。
     * 階層が分からなくなると意味が変わってしまうため、<b>字下げを付けて</b>持ち出す
     * （docs/eclipse-plugin-folders-qa.md の Q7）。
     */
    private void createCopyActions() {
        copyAction = new Action("コピー") {
            @Override
            public void run() {
                copyToClipboard(false);
            }
        };
        copyAction.setToolTipText("選んだ行を、階層の字下げを付けてコピーする");
        copyAction.setActionDefinitionId(IWorkbenchCommandConstants.EDIT_COPY);

        copySubtreeAction = new Action("この行から下をコピー") {
            @Override
            public void run() {
                copyToClipboard(true);
            }
        };
        copySubtreeAction.setToolTipText("選んだ行と、その下にある行（畳んでいるものも含む）をまとめてコピーする");

        Action openAction = new Action("呼び出している行を開く") {
            @Override
            public void run() {
                openSelectedRow();
            }
        };

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
            }
        });
        Tree tree = viewer.getTree();
        tree.setMenu(context.createContextMenu(tree));
        updateCopyActions();
    }

    private void updateCopyActions() {
        if (copyAction == null) {
            return;   // 画面の組み立て中（選択はまだ起きないが、順序に頼らない）
        }
        boolean any = viewer.getTree().getSelectionCount() > 0;
        copyAction.setEnabled(any);
        copySubtreeAction.setEnabled(any);
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
        targetLabel = null;
        autoAnalyzeAction.setChecked(picked.isAutoAnalyze());
        syncProjectSelection();
        refresh();
    }

    /** このメソッドの階層を出す。解析がまだでも受け付け、状態はバナーで伝える */
    public void showMethod(ProjectAnalysis target, IMethod method) {
        this.analysis = target;
        this.targetKey = MethodKeys.keyOf(method);
        this.targetLabel = method.getElementName();
        autoAnalyzeAction.setChecked(target.isAutoAnalyze());
        reloadProjects();
        syncProjectSelection();
        refresh();
    }

    /** ツールバーの［カーソル位置のメソッド］。コマンドと同じ道（{@link MethodPicker}）を通る */
    private void showMethodAtCursor() {
        IMethod method = MethodPicker.pick(getSite().getPage(),
                getSite().getWorkbenchWindow().getSelectionService().getSelection());
        if (method == null) {
            MessageDialog.openInformation(getSite().getShell(), "影響調査",
                    "メソッドが特定できませんでした。エディタでメソッドの中にカーソルを置くか、"
                            + "パッケージ・エクスプローラーでメソッドを選んでから押してください。");
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
        updateContentDescription();
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
        runOnUi(this::refresh);
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
        updateContentDescription();
        updateConfigLabel();
        updateBanner();
        reload();
    }

    private void updateContentDescription() {
        if (analysis == null) {
            setContentDescription("");
        } else if (targetKey == null) {
            setContentDescription(analysis.project().getName() + "（メソッド未選択）");
        } else {
            setContentDescription(targetLabel + " の" + (callers ? "呼び出し元" : "呼び出し先"));
        }
    }

    /** 対象バーの「設定: …」。何を見て解析するかを、いつでも出しておく */
    private void updateConfigLabel() {
        if (analysis == null) {
            configLabel.setText("");
            configLabel.setToolTipText(null);
        } else {
            ConfigSource source = analysis.configSource();
            String label = (source == null) ? "解析できません" : source.label();
            configLabel.setText("設定: " + label);
            configLabel.setToolTipText("設定: " + label
                    + "\n中身は［解析に使う設定…］で確認できます。");
        }
        configLabel.getParent().layout();
    }

    /** 入力が続いている間は取り寄せ直さない（打鍵ごとに子プロセスへ聞かない） */
    private void scheduleReload() {
        pendingRebuild++;
        int generation = pendingRebuild;
        viewer.getControl().getDisplay().timerExec(FILTER_DELAY_MS, () -> {
            if (!viewer.getControl().isDisposed() && generation == pendingRebuild) {
                reload();
            }
        });
    }

    /** いまの条件で木を取り寄せ、届いたら描き直す */
    private void reload() {
        if (analysis == null || targetKey == null || !analysis.isAnalyzed()) {
            viewer.setInput(null);
            updateFooter(0, "");
            return;
        }
        saveFilters();
        final String requestedKey = targetKey;
        analysis.requestTree(targetKey, callers, filters.toWords(), (response, error) -> runOnUi(() -> {
            if (!requestedKey.equals(targetKey)) {
                return;   // 待っている間に別のメソッドへ切り替わった
            }
            applyTree(response, error);
        }));
    }

    private void applyTree(ServerResponse response, String error) {
        if (error != null) {
            viewer.setInput(null);
            setBanner("✖ 解析サーバーとやりとりできませんでした: " + error, "再解析", this::reanalyze, false);
            updateFooter(0, "");
            return;
        }
        if (!response.isOk()) {
            viewer.setInput(null);
            if ("not-found".equals(response.reason())) {
                setBanner("このメソッドは今の解析結果にありません（解析後に追加された可能性があります）。",
                        "再解析", this::reanalyze, false);
            } else if ("not-analyzed".equals(response.reason())) {
                setBanner("このプロジェクトはまだ解析していません。", "解析する", this::reanalyze, false);
            } else {
                setBanner("✖ 木を取得できませんでした: " + response.reason(),
                        "再解析", this::reanalyze, false);
            }
            updateFooter(0, "");
            return;
        }
        ServerTree tree = ServerTree.of(response.rows());
        viewer.setInput(tree);
        viewer.expandToLevel(2);
        int direct = (tree.root() == null) ? 0 : tree.root().children().size();
        updateFooter(direct, "全 " + response.rows().size() + " 行");
        updateBanner();
    }

    /** 深さ上限で打ち切られた節点が開かれたら、その先を取り寄せる */
    private void loadContinuation(Object element) {
        if (analysis == null || !(element instanceof ServerTree.Node)) {
            return;
        }
        final ServerTree.Node node = (ServerTree.Node) element;
        if (!node.isTruncated()) {
            return;
        }
        String key = node.row().key();
        if (contentProvider.hasContinuation(key)) {
            return;
        }
        analysis.requestTree(key, callers, filters.toWords(), (response, error) -> runOnUi(() -> {
            if (error != null || response == null || !response.isOk()) {
                return;
            }
            contentProvider.addContinuation(key, ServerTree.of(response.rows()));
            viewer.refresh(node);
            viewer.expandToLevel(node, 1);
        }));
    }

    private void updateFooter(int direct, String extra) {
        String what = callers ? "呼び出し元" : "呼び出し先";
        footer.setText((targetKey == null) ? ""
                : "直接の" + what + " " + direct + " 件" + (extra.isEmpty() ? "" : "／" + extra));
        footer.getParent().layout();
    }

    /**
     * バナーの書き換え。状態は1行に畳んである（docs/eclipse-plugin-ui-design.md §2）。
     *
     * <p>プロジェクトが決まっていない・決まったがメソッドがまだ、という段階もここで案内する。
     * 「次に何をすればよいか」が書いていない画面は、利用者にとって行き止まりと同じだからである。
     */
    private void updateBanner() {
        if (analysis == null) {
            if (projectItems.isEmpty()) {
                setBanner("解析できるプロジェクトがありません"
                        + "（Java プロジェクト、または設定ファイルのあるプロジェクトが要ります）。",
                        null, null, false);
            } else {
                setBanner("上の［対象プロジェクト］でプロジェクトを選んでください。",
                        null, null, false);
            }
            return;
        }
        ProjectAnalysis.State state = analysis.state();
        ServerResponse last = analysis.lastAnalysis();
        String at = (last == null) ? "" : shortTime(last.field("at"));
        switch (state) {
            case NO_CONFIG:
                setBanner("このプロジェクトは解析できません（Java プロジェクトではなく、設定ファイルもありません）。",
                        "解析に使う設定…", this::openConfigDialog, false);
                break;
            case NOT_ANALYZED:
                setBanner("このプロジェクトはまだ解析していません。", "解析する", this::reanalyze, false);
                break;
            case ANALYZING:
                setBanner("解析中です（別プロセス）…", null, null, true);
                break;
            case UPDATING:
                setBanner("更新中です。表示は " + at + " 時点のものです。", null, null, true);
                break;
            case STALE:
                setBanner("⚠ " + analysis.changedCount() + " ファイルが変更されています。表示は "
                        + at + " 時点のものです。", "再解析", this::reanalyze, false);
                break;
            case FAILED:
                setBanner("✖ 解析に失敗しました: " + analysis.errorMessage()
                        + "（［解析に使う設定…］で、起点とソースフォルダを確かめられます）",
                        "再試行", this::reanalyze, false);
                break;
            case READY:
                if (targetKey == null) {
                    setBanner(at + " 時点の解析結果"
                            + (last == null ? "" : "（メソッド " + last.field("methods") + " 件）")
                            + "。調べたいメソッドを指定してください。",
                            "カーソル位置のメソッド", this::showMethodAtCursor, false);
                } else {
                    setBanner(at + " 時点の解析結果"
                            + (last == null ? "" : "（メソッド " + last.field("methods") + " 件）"),
                            null, null, false);
                }
                break;
            default:
                setBanner("", null, null, false);
                break;
        }
    }

    private void reanalyze() {
        if (analysis != null) {
            analysis.reanalyze();
        }
    }

    /** {@code 2026-09-12T10:31:04.123} のうち時刻だけを出す */
    private static String shortTime(String stamp) {
        int t = stamp.indexOf('T');
        if (t < 0) {
            return stamp;
        }
        String time = stamp.substring(t + 1);
        int dot = time.indexOf('.');
        return (dot < 0) ? time : time.substring(0, dot);
    }

    private void setBanner(String message, String actionLabel, Runnable action, boolean cancellable) {
        bannerLabel.setText(message);
        bannerLabel.setToolTipText(environmentTooltip());
        bannerActionRun = action;
        bannerAction.setText(actionLabel == null ? "" : actionLabel);
        bannerAction.setVisible(actionLabel != null);
        ((GridData) layoutDataOf(bannerAction)).exclude = (actionLabel == null);
        bannerCancel.setVisible(cancellable);
        ((GridData) layoutDataOf(bannerCancel)).exclude = !cancellable;
        banner.layout(true, true);
        banner.getParent().layout(true, true);
    }

    /**
     * バナーのツールチップ。「どの設定で」「何の上で」解析し、「どこにファイルを作ったか」を出す。
     * 解析は別プロセスなので、Eclipse を動かしている JDK とは別のものが使われる。
     */
    private String environmentTooltip() {
        StringBuilder sb = new StringBuilder();
        if (analysis != null) {
            ConfigSource source = analysis.configSource();
            sb.append("設定: ").append(source == null ? "なし" : source.label()).append('\n');
            ServerResponse info = analysis.serverInfo();
            if (info != null) {
                sb.append("解析プロセス: JDK ").append(info.field("jvm"))
                        .append(" / JDT ").append(info.field("jdt"))
                        .append("（解析できる Java: ").append(info.field("maxJava")).append(" まで）\n");
            }
            ServerResponse last = analysis.lastAnalysis();
            if (last != null) {
                sb.append("解析した Java の版: ").append(last.field("sourceLevel")).append('\n');
            }
        }
        sb.append("キャッシュ: ").append(PluginFolders.cacheRoot().getAbsolutePath()).append('\n');
        sb.append("ログ: ").append(PluginFolders.logFolder().getAbsolutePath()).append('\n');
        sb.append("解析は Eclipse とは別のプロセス・別の JDK で走ります。");
        return sb.toString();
    }

    private static Object layoutDataOf(Button button) {
        if (!(button.getLayoutData() instanceof GridData)) {
            button.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
        }
        return button.getLayoutData();
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
            setBanner("この行にはソースがありません（依存 jar のメソッドです）。", null, null, false);
            return;
        }
        ServerResponse last = analysis.lastAnalysis();
        String projectRoot = (last == null) ? "" : last.field("root");
        boolean opened = !projectRoot.isEmpty() && EditorOpener.open(getSite().getPage(),
                java.nio.file.Paths.get(projectRoot), row.file(), row.line());
        if (!opened) {
            setBanner("ソースを開けませんでした（ワークスペースの外にあるファイルです）。", null, null, false);
        }
    }

    // ------------------------------------------------------------
    // コピー
    // ------------------------------------------------------------

    /**
     * 選んだ行をクリップボードへ。字下げは<b>画面に見えているとおりの深さ</b>で付ける。
     *
     * <p>行の深さ（{@code ServerRow#depth}）を使わないのは、深さ上限で打ち切った先を
     * 取り寄せ直した枝（継続）では、その枝の中で深さが 0 から振り直されるためである。
     * ツリーの項目（{@code TreeItem}）の親をたどれば、見えているとおりの段数になる。
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

    private void exportCsv() {
        if (analysis == null || targetKey == null || !analysis.isAnalyzed()) {
            return;
        }
        FileDialog dialog = new FileDialog(getSite().getShell(), SWT.SAVE);
        dialog.setFilterExtensions(new String[] {"*.csv"});
        dialog.setFileName("call-hierarchy-view.csv");
        dialog.setFilterPath(PluginFolders.outputRoot().getAbsolutePath());
        dialog.setOverwrite(true);
        String path = dialog.open();
        if (path == null) {
            return;
        }
        List<String> words = new ArrayList<>();
        words.add("EXPORT");
        words.add(targetKey);
        words.add(callers ? "callers" : "callees");
        words.add(path);
        for (String filter : filters.toWords()) {
            words.add(filter);
        }
        analysis.request("呼び出し階層をCSVに出力", (response, error) -> runOnUi(() -> {
            if (error != null || response == null || !response.isOk()) {
                MessageDialog.openError(getSite().getShell(), "影響調査",
                        "CSV の出力に失敗しました: "
                                + (error != null ? error : response.reason()));
            } else {
                setBanner(path + " に " + response.field("rows") + " 行を書き出しました。",
                        null, null, false);
            }
        }), 300_000L, words.toArray(new String[0]));
    }

    /** 「解析に使う設定」ダイアログ。設定を選び直したら、その場で表示に反映する */
    private void openConfigDialog() {
        if (analysis == null) {
            MessageDialog.openInformation(getSite().getShell(), "影響調査",
                    "先に、上の［対象プロジェクト］でプロジェクトを選んでください。");
            return;
        }
        new ConfigDialog(getSite().getShell(), analysis).open();
        refresh();
    }

    private void openLogFolder() {
        java.io.File folder = AnalysisLog.folder();
        if (!folder.isDirectory() && !folder.mkdirs()) {
            MessageDialog.openInformation(getSite().getShell(), "影響調査",
                    "ログフォルダを作れませんでした: " + folder.getAbsolutePath());
            return;
        }
        if (!org.eclipse.swt.program.Program.launch(folder.getAbsolutePath())) {
            MessageDialog.openInformation(getSite().getShell(), "影響調査", folder.getAbsolutePath());
        }
    }

    /** ラベルプロバイダから使う。そのファイルが解析後に変わっているか */
    boolean isChangedSinceAnalysis(String relativePath) {
        return analysis != null && analysis.isChangedSinceAnalysis(relativePath);
    }

    /**
     * フィルタの保存先。
     *
     * <p>{@code AbstractUIPlugin#getDialogSettings()} を使う。新しい Eclipse には
     * {@code PlatformUI.getDialogSettingsProvider} があるが、そちらは 2022 年（4.24）からで、
     * 古い Eclipse では存在しない。このプラグインは古い Eclipse でも動かすので、
     * 両方にある古い方を使う（test/plugin-api/run.sh が古い jar でのコンパイルを検査する）。
     */
    @SuppressWarnings("deprecation")
    private IDialogSettings dialogSettings() {
        JchePlugin plugin = JchePlugin.getDefault();
        if (plugin == null) {
            return null;
        }
        IDialogSettings root = plugin.getDialogSettings();
        if (root == null) {
            return null;
        }
        IDialogSettings section = root.getSection(VIEW_ID);
        return (section != null) ? section : root.addNewSection(VIEW_ID);
    }

    private void loadFilters() {
        IDialogSettings settings = dialogSettings();
        if (settings != null) {
            filters.load(settings);
        }
    }

    private void saveFilters() {
        IDialogSettings settings = dialogSettings();
        if (settings != null) {
            filters.save(settings);
        }
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
        saveFilters();
        super.dispose();
    }
}
