// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ColumnViewerToolTipSupport;
import org.eclipse.jface.viewers.DoubleClickEvent;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeViewerListener;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.TreeExpansionEvent;
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
import org.eclipse.ui.dialogs.ElementListSelectionDialog;
import org.eclipse.ui.part.ViewPart;

import jche.eclipse.server.ServerResponse;
import jche.eclipse.server.ServerRow;
import jche.eclipse.server.ServerTree;

/**
 * 呼び出し階層ビュー。主ユースケースは「メソッドを選んで、その呼び出し元を階層で辿る」。
 *
 * <p>画面は上から バナー（状態）／フィルタバー／ツリー／件数 の4段
 * （docs/eclipse-plugin-ui-design.md）。解析もフィルタも<b>子プロセス</b>で行い、
 * ここは返ってきた行を描くだけである（docs/out-of-process-analysis-design.md）。
 * 守っているのは次の3点。
 * <ul>
 *   <li>解析は待たない。解析中でも、前の結果を出したままバナーだけが動く</li>
 *   <li>フィルタは解析を起こさない。条件を変えたら木を取り寄せ直すだけ</li>
 *   <li>結果が古いことは、バナー・行の警告アイコン・ツールチップの3段階で示す</li>
 * </ul>
 */
public class CallHierarchyView extends ViewPart implements AnalysisService.Listener {

    public static final String VIEW_ID = "io.github.instreest.jche.eclipse.callHierarchyView";

    /** 絞り込み文字列を打ち終わるのを待つ時間（ミリ秒） */
    private static final int FILTER_DELAY_MS = 250;

    private Composite banner;
    private Label bannerLabel;
    private Button bannerAction;
    private Button bannerCancel;
    private Text filterText;
    private Spinner depthSpinner;
    private TreeViewer viewer;
    private CallersContentProvider contentProvider;
    private Label footer;

    private final FilterSettings filters = new FilterSettings();
    private boolean callers = true;

    private ProjectAnalysis analysis;
    /** 表示しているメソッド。ID ではなくキーで持つ（解析し直しても指すものが変わらない） */
    private String targetKey;
    private String targetLabel;
    private int pendingRebuild;

    private Action autoAnalyzeAction;

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
        viewer.setContentProvider(contentProvider);
        viewer.setLabelProvider(new CallersLabelProvider(this));
        viewer.setUseHashlookup(true);
        ColumnViewerToolTipSupport.enableFor(viewer);
        viewer.addDoubleClickListener(this::openSelected);
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

        Action directionAction = new Action("呼び出し先を見る", Action.AS_CHECK_BOX) {
            @Override
            public void run() {
                callers = !isChecked();
                reload();
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

        Action chooseConfigAction = new Action("使う設定ファイルを選ぶ…") {
            @Override
            public void run() {
                chooseConfigFile();
            }
        };
        Action saveConfigAction = new Action("設定を config.properties に保存") {
            @Override
            public void run() {
                saveGeneratedConfig();
            }
        };
        saveConfigAction.setToolTipText("いま使っている設定を保存して、手で調整できるようにする");

        IMenuManager menu = getViewSite().getActionBars().getMenuManager();
        menu.add(chooseConfigAction);
        menu.add(saveConfigAction);

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
        this.targetKey = MethodKeys.keyOf(method);
        this.targetLabel = method.getElementName();
        autoAnalyzeAction.setChecked(target.isAutoAnalyze());
        setContentDescription(targetLabel + " の" + (callers ? "呼び出し元" : "呼び出し先"));
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
        updateBanner();
        reload();
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
            setBanner("✖ 解析サーバーとやりとりできませんでした: " + error, "再解析", false);
            updateFooter(0, "");
            return;
        }
        if (!response.isOk()) {
            viewer.setInput(null);
            if ("not-found".equals(response.reason())) {
                setBanner("このメソッドは今の解析結果にありません（解析後に追加された可能性があります）。",
                        "再解析", false);
            } else if ("not-analyzed".equals(response.reason())) {
                setBanner("このプロジェクトはまだ解析していません。", "解析する", false);
            } else {
                setBanner("✖ 木を取得できませんでした: " + response.reason(), "再解析", false);
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
        if (analysis == null || !(element instanceof ServerTree.Node node) || !node.isTruncated()) {
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

    private void updateBanner() {
        if (analysis == null) {
            setBanner("メソッドを選んで「呼び出し元階層を表示」を実行してください。", null, false);
            return;
        }
        ProjectAnalysis.State state = analysis.state();
        ServerResponse last = analysis.lastAnalysis();
        String at = (last == null) ? "" : shortTime(last.field("at"));
        switch (state) {
            case NO_CONFIG -> setBanner(
                    "このプロジェクトは解析できません（Java プロジェクトではなく、設定ファイルもありません）。",
                    null, false);
            case NOT_ANALYZED -> setBanner("このプロジェクトはまだ解析していません。", "解析する", false);
            case ANALYZING -> setBanner("解析中です（別プロセス）…", null, true);
            case UPDATING -> setBanner("更新中です。表示は " + at + " 時点のものです。", null, true);
            case STALE -> setBanner("⚠ " + analysis.changedCount()
                    + " ファイルが変更されています。表示は " + at + " 時点のものです。", "再解析", false);
            case FAILED -> setBanner("✖ 解析に失敗しました: " + analysis.errorMessage(), "再試行", false);
            case READY -> setBanner(at + " 時点の解析結果"
                    + (last == null ? "" : "（メソッド " + last.field("methods") + " 件）"), null, false);
            default -> setBanner("", null, false);
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

    private void setBanner(String message, String actionLabel, boolean cancellable) {
        bannerLabel.setText(message);
        bannerLabel.setToolTipText(environmentTooltip());
        bannerAction.setText(actionLabel == null ? "" : actionLabel);
        bannerAction.setVisible(actionLabel != null);
        ((GridData) layoutDataOf(bannerAction)).exclude = (actionLabel == null);
        bannerCancel.setVisible(cancellable);
        ((GridData) layoutDataOf(bannerCancel)).exclude = !cancellable;
        banner.layout(true, true);
        banner.getParent().layout(true, true);
    }

    /**
     * バナーのツールチップ。「どの設定で」「何の上で」解析したかを出す。
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

    private void openSelected(DoubleClickEvent event) {
        if (analysis == null || !(event.getSelection() instanceof IStructuredSelection selection)) {
            return;
        }
        if (!(selection.getFirstElement() instanceof ServerTree.Node node)) {
            return;
        }
        ServerRow row = node.row();
        if (row.file().isEmpty()) {
            setBanner("この行にはソースがありません（依存 jar のメソッドです）。", null, false);
            return;
        }
        ServerResponse last = analysis.lastAnalysis();
        String projectRoot = (last == null) ? "" : last.field("root");
        boolean opened = !projectRoot.isEmpty() && EditorOpener.open(getSite().getPage(),
                java.nio.file.Paths.get(projectRoot), row.file(), row.line());
        if (!opened) {
            setBanner("ソースを開けませんでした（ワークスペースの外にあるファイルです）。", null, false);
        }
    }

    private void exportCsv() {
        if (analysis == null || targetKey == null || !analysis.isAnalyzed()) {
            return;
        }
        FileDialog dialog = new FileDialog(getSite().getShell(), SWT.SAVE);
        dialog.setFilterExtensions(new String[] {"*.csv"});
        dialog.setFileName("call-hierarchy-view.csv");
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
                MessageDialog.openError(getSite().getShell(), "呼び出し階層",
                        "CSV の出力に失敗しました: "
                                + (error != null ? error : response.reason()));
            } else {
                setBanner(path + " に " + response.field("rows") + " 行を書き出しました。", null, false);
            }
        }), 300_000L, words.toArray(new String[0]));
    }

    /** いま使っている設定（自動生成ぶん）をプロジェクト直下の config.properties に書き出す */
    private void saveGeneratedConfig() {
        if (analysis == null) {
            return;
        }
        ConfigSource source = analysis.configSource();
        if (source == null || source.kind() != ConfigSource.Kind.GENERATED) {
            MessageDialog.openInformation(getSite().getShell(), "呼び出し階層",
                    "すでに設定ファイルを使っています: " + (source == null ? "（なし）" : source.label()));
            return;
        }
        IFile target = analysis.project().getFile("config.properties");
        if (target.exists()) {
            MessageDialog.openInformation(getSite().getShell(), "呼び出し階層",
                    "config.properties はすでにあります。そちらが使われます。");
            return;
        }
        try {
            byte[] bytes = EclipseProjectConfig.toFileText(source.generatedProperties())
                    .getBytes(StandardCharsets.UTF_8);
            target.create(new ByteArrayInputStream(bytes), false, null);
            analysis.setConfigFile(target);
        } catch (CoreException | IOException e) {
            MessageDialog.openError(getSite().getShell(), "呼び出し階層",
                    "設定ファイルを保存できませんでした: " + e.getMessage());
        }
    }

    /** 使う設定ファイルを選ぶ（選ばなければ自動判定に戻す） */
    private void chooseConfigFile() {
        if (analysis == null) {
            return;
        }
        List<IFile> candidates = analysis.findConfigFiles();
        if (candidates.isEmpty()) {
            MessageDialog.openInformation(getSite().getShell(), "呼び出し階層",
                    "プロジェクト直下に設定ファイル（*.properties）がありません。"
                            + "設定はプロジェクトの構成から自動生成します。");
            return;
        }
        ElementListSelectionDialog dialog = new ElementListSelectionDialog(
                getSite().getShell(), new LabelProvider() {
                    @Override
                    public String getText(Object element) {
                        return ((IFile) element).getProjectRelativePath().toString();
                    }
                });
        dialog.setTitle("使う設定ファイル");
        dialog.setMessage("解析に使う設定ファイルを選んでください（キャンセルで自動判定に戻ります）");
        dialog.setElements(candidates.toArray());
        if (dialog.open() == org.eclipse.jface.window.Window.OK
                && dialog.getFirstResult() instanceof IFile chosen) {
            analysis.setConfigFile(chosen);
        } else {
            analysis.setConfigFile(null);
        }
        refresh();
    }

    // ------------------------------------------------------------

    /** ラベルプロバイダから使う。そのファイルが解析後に変わっているか */
    boolean isChangedSinceAnalysis(String relativePath) {
        return analysis != null && analysis.isChangedSinceAnalysis(relativePath);
    }

    private IDialogSettings dialogSettings() {
        JchePlugin plugin = JchePlugin.getDefault();
        if (plugin == null) {
            return null;
        }
        IDialogSettings root = PlatformUI.getDialogSettingsProvider(plugin.getBundle()).getDialogSettings();
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
