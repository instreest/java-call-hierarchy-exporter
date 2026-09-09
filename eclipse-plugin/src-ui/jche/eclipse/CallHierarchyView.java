// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import java.time.format.DateTimeFormatter;

import org.eclipse.jdt.core.IMethod;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.viewers.ColumnViewerToolTipSupport;
import org.eclipse.jface.viewers.DoubleClickEvent;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.ViewPart;

import jche.AnalysisSnapshot;
import jche.graph.MethodTable;

/**
 * 呼び出し階層ビュー。主ユースケースは「メソッドを選んで、その呼び出し元を階層で辿る」。
 *
 * <p>画面は上から バナー（状態）／フィルタバー／ツリー／件数 の4段
 * （docs/eclipse-plugin-ui-design.md）。守っているのは次の3点。
 * <ul>
 *   <li>解析は待たない。解析中でも、前の結果を出したままバナーだけが動く</li>
 *   <li>フィルタは解析を起こさない。スナップショットを読むときの絞り込みなので即座に効く</li>
 *   <li>結果が古いことは、バナー・行の警告アイコン・ツールチップの3段階で示す</li>
 * </ul>
 */
public class CallHierarchyView extends ViewPart implements AnalysisService.Listener {

    public static final String VIEW_ID = "io.github.instreest.jche.eclipse.callHierarchyView";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 絞り込み文字列を打ち終わるのを待つ時間（ミリ秒） */
    private static final int FILTER_DELAY_MS = 250;

    private Composite banner;
    private Label bannerLabel;
    private Button bannerAction;
    private Button bannerCancel;
    private Text filterText;
    private Spinner depthSpinner;
    private TreeViewer viewer;
    private Label footer;

    private final FilterSettings filters = new FilterSettings();
    private CallersModel.Direction direction = CallersModel.Direction.CALLERS;

    private ProjectAnalysis analysis;
    /** 表示している根のメソッド。解析し直すとIDが変わるので、Eclipse 側の要素のまま持つ */
    private IMethod targetMethod;
    private CallersModel model;
    private CallNode rootNode;
    /** 遅延して作り直すときの世代番号。最後の1回だけを実行するために使う */
    private int pendingRebuild;

    private Action reanalyzeAction;
    private Action autoAnalyzeAction;
    private Action directionAction;

    @Override
    public void createPartControl(Composite parent) {
        loadFilters();
        Composite root = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        layout.verticalSpacing = 0;
        root.setLayout(layout);

        createBanner(root);
        createFilterBar(root);
        createTree(root);
        createFooter(root);
        createActions();

        AnalysisService service = JchePlugin.service();
        if (service != null) {
            service.addListener(this);
        }
        refresh();
    }

    // ------------------------------------------------------------
    // 画面の組み立て
    // ------------------------------------------------------------

    private void createBanner(Composite parent) {
        banner = new Composite(parent, SWT.NONE);
        banner.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        GridLayout layout = new GridLayout(3, false);
        layout.marginHeight = 3;
        banner.setLayout(layout);

        bannerLabel = new Label(banner, SWT.WRAP);
        bannerLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        bannerAction = new Button(banner, SWT.PUSH);
        bannerAction.setText("解析する");
        bannerAction.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                if (analysis != null) {
                    analysis.reanalyze();
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
            // 1文字ごとに作り直さない。絞り込みの下ごしらえ（一致した枝の割り出し）は
            // グラフ全体を舐めるので、打ち終わるのを少し待つ
            scheduleRebuild();
        });

        new Label(bar, SWT.NONE).setText("深さ:");
        depthSpinner = new Spinner(bar, SWT.BORDER);
        depthSpinner.setMinimum(1);
        depthSpinner.setMaximum(50);
        depthSpinner.setSelection(filters.maxDepth);
        depthSpinner.addModifyListener(e -> {
            filters.maxDepth = depthSpinner.getSelection();
            rebuildModel();
        });

        Button more = new Button(bar, SWT.PUSH);
        more.setText("フィルタ…");
        more.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                if (new FilterDialog(getSite().getShell(), filters).open() == org.eclipse.jface.window.Window.OK) {
                    rebuildModel();
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
        viewer.setContentProvider(new CallersContentProvider());
        viewer.setLabelProvider(new CallersLabelProvider(this));
        viewer.setUseHashlookup(true);
        ColumnViewerToolTipSupport.enableFor(viewer);
        viewer.addDoubleClickListener(this::openSelected);
        getSite().setSelectionProvider(viewer);
    }

    private void createFooter(Composite parent) {
        footer = new Label(parent, SWT.NONE);
        footer.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));
    }

    private void createActions() {
        reanalyzeAction = new Action("再解析") {
            @Override
            public void run() {
                if (analysis != null) {
                    analysis.reanalyze();
                }
            }
        };
        reanalyzeAction.setToolTipText("いま解析し直す");

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

        directionAction = new Action("呼び出し先を見る", Action.AS_CHECK_BOX) {
            @Override
            public void run() {
                direction = isChecked() ? CallersModel.Direction.CALLEES : CallersModel.Direction.CALLERS;
                rebuildModel();
            }
        };
        directionAction.setToolTipText("木の向きを、呼び出し元 ⇄ 呼び出し先 で切り替える");

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

        IToolBarManager toolbar = getViewSite().getActionBars().getToolBarManager();
        toolbar.add(directionAction);
        toolbar.add(new Separator());
        toolbar.add(reanalyzeAction);
        toolbar.add(autoAnalyzeAction);
        toolbar.add(new Separator());
        toolbar.add(expandAction);
        toolbar.add(collapseAction);
    }

    // ------------------------------------------------------------
    // 表示するメソッドの指定
    // ------------------------------------------------------------

    /** このメソッドの階層を出す。解析がまだでも受け付け、状態はバナーで伝える */
    public void showMethod(ProjectAnalysis target, IMethod method) {
        this.analysis = target;
        this.targetMethod = method;
        autoAnalyzeAction.setChecked(target.isAutoAnalyze());
        setContentDescription(method.getElementName() + " の"
                + (direction == CallersModel.Direction.CALLERS ? "呼び出し元" : "呼び出し先"));
        refresh();
    }

    // ------------------------------------------------------------
    // 状態の反映
    // ------------------------------------------------------------

    @Override
    public void analysisChanged(ProjectAnalysis changed) {
        if (changed != analysis) {
            return;
        }
        Display display = viewer.getControl().getDisplay();
        display.asyncExec(() -> {
            if (!viewer.getControl().isDisposed()) {
                refresh();
            }
        });
    }

    /** 入力が続いている間はモデルを作り直さない（打鍵ごとの再計算を避ける） */
    private void scheduleRebuild() {
        pendingRebuild++;
        int generation = pendingRebuild;
        viewer.getControl().getDisplay().timerExec(FILTER_DELAY_MS, () -> {
            if (!viewer.getControl().isDisposed() && generation == pendingRebuild) {
                rebuildModel();
            }
        });
    }

    private void refresh() {
        updateBanner();
        rebuildModel();
    }

    /**
     * モデルを作り直してツリーへ流し込む。スナップショットは1回だけ読み、
     * それを握ったまま木を作る（途中で解析が終わっても、この木は一貫したまま）。
     */
    private void rebuildModel() {
        AnalysisSnapshot snapshot = (analysis == null) ? null : analysis.snapshot();
        if (snapshot == null || targetMethod == null) {
            model = null;
            rootNode = null;
            viewer.setInput(new CallersInput(null, null));
            updateFooter(0, 0);
            updateBanner();
            return;
        }
        model = new CallersModel(snapshot, direction, filters);
        int methodId = MethodKeys.find(snapshot.graph().methods(), targetMethod);
        rootNode = (methodId >= 0) ? CallNode.root(methodId) : null;
        viewer.setInput(new CallersInput(model, rootNode));
        if (rootNode != null) {
            viewer.expandToLevel(2);
            int shown = model.childrenOf(rootNode).size();
            updateFooter(shown, model.totalNeighbourCount(methodId));
        } else {
            updateFooter(0, 0);
        }
        updateBanner();
        saveFilters();
    }

    private void updateFooter(int shown, int total) {
        if (rootNode == null) {
            footer.setText("");
        } else {
            int hidden = Math.max(0, total - shown);
            String what = (direction == CallersModel.Direction.CALLERS) ? "呼び出し元" : "呼び出し先";
            footer.setText("直接の" + what + " 表示 " + shown + " 件 / 全 " + total + " 件"
                    + (hidden > 0 ? "（フィルタで " + hidden + " 件を非表示）" : ""));
        }
        footer.getParent().layout();
    }

    private void updateBanner() {
        if (analysis == null) {
            setBanner("メソッドを選んで「呼び出し元階層を表示」を実行してください。", null, false);
            return;
        }
        ProjectAnalysis.State state = analysis.state();
        AnalysisSnapshot snapshot = analysis.snapshot();
        String at = (snapshot == null) ? "" : snapshot.analyzedAt().format(TIME);
        switch (state) {
            case NO_CONFIG -> setBanner(
                    "このプロジェクトに設定ファイル（*.properties）がありません。", null, false);
            case NOT_ANALYZED -> setBanner(
                    "このプロジェクトはまだ解析していません。", "解析する", false);
            case ANALYZING -> setBanner("解析中です…（終わるまで表示できません）", null, true);
            case UPDATING -> setBanner(
                    "更新中です。表示は " + at + " 時点のものです。", null, true);
            case STALE -> setBanner("⚠ " + analysis.changedCount()
                    + " ファイルが変更されています。表示は " + at + " 時点のものです。", "再解析", false);
            case FAILED -> setBanner("✖ 解析に失敗しました: " + analysis.errorMessage(), "再試行", false);
            case READY -> {
                if (rootNode == null && targetMethod != null && snapshot != null) {
                    setBanner("このメソッドは " + at + " 時点の解析結果にありません。", "再解析", false);
                } else {
                    MethodTable methods = (snapshot == null) ? null : snapshot.graph().methods();
                    setBanner(at + " 時点の解析結果"
                            + (methods == null ? "" : "（メソッド " + methods.size() + " 件）"), null, false);
                }
            }
            default -> setBanner("", null, false);
        }
    }

    private void setBanner(String message, String actionLabel, boolean cancellable) {
        bannerLabel.setText(message);
        bannerAction.setText(actionLabel == null ? "" : actionLabel);
        bannerAction.setVisible(actionLabel != null);
        ((GridData) getOrCreateData(bannerAction)).exclude = (actionLabel == null);
        bannerCancel.setVisible(cancellable);
        ((GridData) getOrCreateData(bannerCancel)).exclude = !cancellable;
        banner.layout(true, true);
        banner.getParent().layout(true, true);
    }

    private static Object getOrCreateData(Button button) {
        if (!(button.getLayoutData() instanceof GridData)) {
            button.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
        }
        return button.getLayoutData();
    }

    // ------------------------------------------------------------
    // 操作
    // ------------------------------------------------------------

    private void openSelected(DoubleClickEvent event) {
        if (model == null || !(event.getSelection() instanceof IStructuredSelection selection)) {
            return;
        }
        if (!(selection.getFirstElement() instanceof CallNode node)) {
            return;
        }
        // 宣言ではなく「呼び出している行」を開く。どちらのファイルに行があるかは向きで変わる
        boolean opened = EditorOpener.open(getSite().getPage(),
                model.snapshot().config().projectRoot,
                model.callSiteFile(node), model.callSiteLine(node));
        if (!opened) {
            setBanner("ソースを開けませんでした（ワークスペースの外にあるか、jar のメソッドです）。", null, false);
        }
    }

    private void exportCsv() {
        if (model == null || rootNode == null) {
            return;
        }
        FileDialog dialog = new FileDialog(getSite().getShell(), SWT.SAVE);
        dialog.setFilterExtensions(new String[] {"*.csv"});
        dialog.setFileName("call-hierarchy-view.csv");
        dialog.setOverwrite(true);
        String path = dialog.open();
        if (path != null) {
            TreeCsvExporter.export(model, rootNode, path);
        }
    }

    // ------------------------------------------------------------

    /** ラベルプロバイダから使う。そのファイルが解析後に変わっているか */
    boolean isChangedSinceAnalysis(String relativePath) {
        return analysis != null && analysis.isChangedSinceAnalysis(relativePath);
    }

    CallersModel model() {
        return model;
    }

    private IDialogSettings dialogSettings() {
        JchePlugin plugin = JchePlugin.getDefault();
        if (plugin == null) {
            return null;
        }
        IDialogSettings root = PlatformUI.getDialogSettingsProvider(
                plugin.getBundle()).getDialogSettings();
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
