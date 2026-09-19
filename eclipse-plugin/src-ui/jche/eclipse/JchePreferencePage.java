// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import jche.eclipse.server.JavaLocator;
import jche.eclipse.server.JdkDownload;

/**
 * 設定画面。扱うのは「解析をどう走らせるか」と「作ったファイルをどこへ置くか」の2つ。
 *
 * <p>解析は Eclipse とは別プロセスなので、使う JDK と JDT をここで選べる。
 * 既定（何も指定しない状態）でも動くようにしてあり、指定するのは
 * 「CLI と同じ JDK 25 で揃えたい」「閉域で新しい JDT を別に置いた」「メモリを増やしたい」
 * といったときだけでよい。
 *
 * <p>置き場所（キャッシュ・ログ・CSV）は<b>空欄でも実際のパスを出す</b>。
 * プラグインの状態フォルダは {@code <ワークスペース>/.metadata/.plugins/...} という
 * 探しに行きにくい場所なので、「どこに何ができるか」が分からないままになるのを避ける
 * （docs/eclipse-plugin-folders-qa.md の Q3）。ビューの［▽］メニューからも開ける。
 */
public class JchePreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    /** 入力欄と説明の幅（ピクセル）。長いパスで設定画面が横に伸びないように決め打つ */
    private static final int TEXT_WIDTH = 420;

    private Text jdkText;
    private Label jdkStatus;
    private Text jdtText;
    private Text vmArgumentsText;
    private Spinner idleSpinner;
    private Button logToFileCheck;
    private Text cacheFolderText;
    private Text logFolderText;
    private Text outputFolderText;
    private Label cacheFolderStatus;
    private Label logFolderStatus;
    private Label outputFolderStatus;

    @Override
    public void init(IWorkbench workbench) {
        setPreferenceStore(JchePlugin.getDefault().getPreferenceStore());
        setDescription("解析は Eclipse とは別のプロセスで走ります。ここではその走らせ方を決めます。");
    }

    @Override
    protected Control createContents(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        GridLayout rootLayout = new GridLayout(1, false);
        rootLayout.marginWidth = 0;
        rootLayout.marginHeight = 0;
        root.setLayout(rootLayout);
        root.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createProcessGroup(root);
        createFolderGroup(root);

        updateJdkStatus();
        return root;
    }

    /** 解析プロセスの走らせ方（JDK・JDT・メモリ・常駐） */
    private void createProcessGroup(Composite root) {
        Group group = group(root, "解析プロセス");

        // --- 解析に使う JDK ---
        new Label(group, SWT.NONE).setText("解析に使う JDK:");
        jdkText = new Text(group, SWT.BORDER);
        jdkText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        jdkText.setText(getPreferenceStore().getString(JchePreferences.JDK));
        jdkText.setToolTipText("java の実行ファイル、または JDK のフォルダ。空なら自動で探します");
        jdkText.addModifyListener(e -> updateJdkStatus());
        Button browseJdk = new Button(group, SWT.PUSH);
        browseJdk.setText("参照…");
        browseJdk.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                FileDialog dialog = new FileDialog(getShell(), SWT.OPEN);
                dialog.setText("解析に使う java を選ぶ");
                String path = dialog.open();
                if (path != null) {
                    jdkText.setText(path);
                }
            }
        });

        new Label(group, SWT.NONE);
        jdkStatus = new Label(group, SWT.WRAP);
        jdkStatus.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Button download = new Button(group, SWT.PUSH);
        download.setText("JDK " + JavaLocator.PREFERRED + " を取得…");
        download.setToolTipText("Adoptium (Eclipse Temurin) から取得して、ワークスペースの中に置きます（約 200MB）");
        download.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                downloadJdk();
            }
        });

        // --- JDT の差し替え ---
        new Label(group, SWT.NONE).setText("JDT の jar のフォルダ:");
        jdtText = new Text(group, SWT.BORDER);
        jdtText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        jdtText.setText(getPreferenceStore().getString(JchePreferences.JDT_FOLDER));
        jdtText.setToolTipText("空なら、プラグインに同梱した JDT を使います。"
                + "新しい JDT を別に置いて使いたいときだけ指定してください");
        browseFolder(group, jdtText, "JDT の jar があるフォルダを選ぶ");

        // --- JVM 引数 ---
        new Label(group, SWT.NONE).setText("解析プロセスの JVM 引数:");
        vmArgumentsText = new Text(group, SWT.BORDER);
        vmArgumentsText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        vmArgumentsText.setText(getPreferenceStore().getString(JchePreferences.VM_ARGUMENTS));
        vmArgumentsText.setToolTipText("例: -Xmx4g。大きなプロジェクトはここで増やします"
                + "（Eclipse 自身のメモリとは別です）");
        new Label(group, SWT.NONE);

        // --- アイドル終了 ---
        new Label(group, SWT.NONE).setText("使われないときに終了する:");
        Composite idleRow = new Composite(group, SWT.NONE);
        GridLayout idleLayout = new GridLayout(2, false);
        idleLayout.marginWidth = 0;
        idleRow.setLayout(idleLayout);
        idleSpinner = new Spinner(idleRow, SWT.BORDER);
        idleSpinner.setMinimum(0);
        idleSpinner.setMaximum(24 * 60);
        idleSpinner.setSelection(getPreferenceStore().getInt(JchePreferences.IDLE_MINUTES));
        new Label(idleRow, SWT.NONE).setText("分後（0 なら終了しない。次の解析は最初からになります）");
        new Label(group, SWT.NONE);
    }

    /**
     * プラグインが作るファイルの置き場所。
     *
     * <p>3 つとも空欄のままでよい（既定＝ワークスペースの中）。空欄のときも実際のパスを
     * 行の下に出すので、「どこに何ができるか」は見れば分かる。
     */
    private void createFolderGroup(Composite root) {
        Group group = group(root, "置き場所");

        Label note = new Label(group, SWT.WRAP);
        note.setText("空欄なら、ワークスペースの中（.metadata/.plugins/" + JchePlugin.PLUGIN_ID
                + "/）に作ります。閉域の共有フォルダに置きたいときや、"
                + "ワークスペースを作り直してもキャッシュを残したいときだけ指定してください。");
        GridData noteData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        noteData.horizontalSpan = 3;
        // 折り返す Label は widthHint を与えないと1行ぶんの幅を要求し、設定画面が横に伸びる
        noteData.widthHint = TEXT_WIDTH;
        note.setLayoutData(noteData);

        cacheFolderText = folderRow(group, "解析キャッシュ:", JchePreferences.CACHE_FOLDER,
                "解析結果の使い回しに使うファイルです。消しても次の解析で作り直します"
                        + "（時間はかかります）。この下の .cache/<プロジェクト名>_<識別子>/ に作られます");
        cacheFolderStatus = folderStatus(group);

        logFolderText = folderRow(group, "解析ログ:", JchePreferences.LOG_FOLDER,
                "解析の進捗と作業ログ（analysis-<日時>.log、新しいものから10世代）。"
                        + "解析が返ってこないときに、どこまで進んでいたかが分かります");
        logFolderStatus = folderStatus(group);

        outputFolderText = folderRow(group, "CSV の出力:", JchePreferences.OUTPUT_FOLDER,
                "ビューの［CSV出力］の初期フォルダです。保存先はそのつど選べます");
        outputFolderStatus = folderStatus(group);

        // --- ログを残すかどうか ---
        new Label(group, SWT.NONE).setText("解析のログ:");
        logToFileCheck = new Button(group, SWT.CHECK);
        logToFileCheck.setText("進捗と作業ログをファイルにも残す");
        logToFileCheck.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        logToFileCheck.setToolTipText("解析が返ってこないときは、このログを見ると、どこまで進んでいたかが分かります");
        logToFileCheck.setSelection(getPreferenceStore().getBoolean(JchePreferences.LOG_TO_FILE));
        new Label(group, SWT.NONE);

        updateFolderStatus();
    }

    private Group group(Composite parent, String title) {
        Group group = new Group(parent, SWT.NONE);
        group.setText(title);
        group.setLayout(new GridLayout(3, false));
        group.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        return group;
    }

    /** 「ラベル / 入力欄 / 参照…」の1行。入力欄を返す */
    private Text folderRow(Group group, String label, String key, String tooltip) {
        new Label(group, SWT.NONE).setText(label);
        Text text = new Text(group, SWT.BORDER);
        GridData textData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        textData.widthHint = TEXT_WIDTH;
        text.setLayoutData(textData);
        text.setText(getPreferenceStore().getString(key));
        text.setToolTipText(tooltip);
        text.addModifyListener(e -> updateFolderStatus());
        browseFolder(group, text, label.replace(":", "") + " のフォルダを選ぶ");
        return text;
    }

    /** 入力欄の下に出す「実際の場所」と［開く］ */
    private Label folderStatus(Group group) {
        new Label(group, SWT.NONE);
        Label status = new Label(group, SWT.NONE);
        // パスは長くなりうる。幅を決めておかないと、そのぶん設定画面が横に伸びてしまう
        // （全文はツールチップに出す）
        GridData statusData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        statusData.widthHint = TEXT_WIDTH;
        status.setLayoutData(statusData);
        Button open = new Button(group, SWT.PUSH);
        open.setText("開く");
        open.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                openFolder(String.valueOf(status.getData()));
            }
        });
        return status;
    }

    /** 「参照…」ボタン。選んだフォルダを text に入れる */
    private void browseFolder(Composite parent, Text text, String title) {
        Button browse = new Button(parent, SWT.PUSH);
        browse.setText("参照…");
        browse.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                DirectoryDialog dialog = new DirectoryDialog(getShell());
                dialog.setText(title);
                String current = text.getText().trim();
                if (!current.isEmpty()) {
                    dialog.setFilterPath(current);
                }
                String path = dialog.open();
                if (path != null) {
                    text.setText(path);
                }
            }
        });
    }

    /**
     * 入力欄の下の「実際の場所」を書き直す。
     *
     * <p>ここは設定を保存する前の入力欄の値を見る。保存してからでないと分からない、では
     * 「指定したつもりの場所」と「実際の場所」がずれたことに気づけないためである。
     */
    private void updateFolderStatus() {
        showFolder(cacheFolderStatus, cacheFolderText, PluginFolders.defaultCacheRoot());
        showFolder(logFolderStatus, logFolderText, PluginFolders.defaultLogFolder());
        showFolder(outputFolderStatus, outputFolderText, PluginFolders.defaultOutputRoot());
        cacheFolderStatus.getParent().layout();
    }

    private static void showFolder(Label status, Text text, File byDefault) {
        String typed = text.getText().trim();
        File folder = typed.isEmpty() ? byDefault : new File(typed);
        String path = folder.getAbsolutePath();
        status.setData(path);
        status.setText(typed.isEmpty() ? "既定: " + path : path);
        status.setToolTipText(path);
    }

    /** フォルダを OS のファイラで開く。まだ無ければ作る */
    private void openFolder(String path) {
        File folder = new File(path);
        if (!folder.isDirectory() && !folder.mkdirs()) {
            MessageDialog.openInformation(getShell(), "影響調査",
                    "フォルダを作れませんでした: " + folder.getAbsolutePath());
            return;
        }
        if (!Program.launch(folder.getAbsolutePath())) {
            // 開けない環境（閉域の Linux 等）でも、場所が分かれば自分で開ける
            MessageDialog.openInformation(getShell(), "影響調査", folder.getAbsolutePath());
        }
    }

    /** いま指定されている（または自動で見つかる）JDK の版を出す */
    private void updateJdkStatus() {
        String text = jdkText.getText().trim();
        if (text.isEmpty()) {
            JavaLocator.Found found = PluginRuntime.findJava(null);
            jdkStatus.setText(found == null
                    ? "⚠ 解析に使える JDK（" + JavaLocator.MINIMUM + " 以上）が見つかりません。取得するか、場所を指定してください。"
                    : "自動で見つけた JDK: " + found + (found.isOlderThanPreferred()
                            ? "（" + JavaLocator.PREFERRED + " ではないため、CLI と結果が少しずれることがあります）" : ""));
            return;
        }
        File file = new File(text);
        File executable = file.isDirectory() ? JavaLocator.executableIn(file) : file;
        int version = JavaLocator.versionOf(executable);
        if (version == 0) {
            jdkStatus.setText("⚠ この場所では java を動かせません。");
        } else if (version < JavaLocator.MINIMUM) {
            jdkStatus.setText("⚠ Java " + version + " は古すぎます（" + JavaLocator.MINIMUM + " 以上が要ります）。");
        } else {
            jdkStatus.setText("Java " + version + " を使います。");
        }
        jdkStatus.getParent().layout();
    }

    /** JDK を取得する。閉域では失敗するので、その旨を出して終わる（異常ではない） */
    private void downloadJdk() {
        if (!MessageDialog.openConfirm(getShell(), "JDK の取得",
                "Adoptium (Eclipse Temurin) から JDK " + JavaLocator.PREFERRED
                        + " を取得します。約 200MB をダウンロードし、ワークスペースの中に置きます。\n\n"
                        + "続けますか？")) {
            return;
        }
        final File[] result = new File[1];
        final IOException[] failure = new IOException[1];
        try {
            new ProgressMonitorDialog(getShell()).run(true, true, new IRunnableWithProgress() {
                @Override
                public void run(final IProgressMonitor monitor) {
                    monitor.beginTask("JDK " + JavaLocator.PREFERRED + " を取得しています", 100);
                    try {
                        result[0] = JdkDownload.install(JavaLocator.PREFERRED,
                                new File(PluginRuntime.stateLocation(), "jdk"),
                                new JdkDownload.Progress() {
                                    private int reported;

                                    @Override
                                    public void received(long done, long total) {
                                        if (total <= 0) {
                                            return;
                                        }
                                        int percent = (int) (done * 100 / total);
                                        if (percent > reported) {
                                            monitor.worked(percent - reported);
                                            reported = percent;
                                        }
                                    }

                                    @Override
                                    public boolean isCancelled() {
                                        return monitor.isCanceled();
                                    }
                                });
                    } catch (IOException e) {
                        failure[0] = e;
                    } finally {
                        monitor.done();
                    }
                }
            });
        } catch (InvocationTargetException e) {
            failure[0] = new IOException(String.valueOf(e.getCause()), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (failure[0] != null) {
            MessageDialog.openError(getShell(), "JDK の取得",
                    "取得できませんでした: " + failure[0].getMessage()
                            + "\n\n閉域ネットワークなどで取得できない場合は、"
                            + "すでにある JDK の場所を「参照…」から指定してください。");
            return;
        }
        if (result[0] != null) {
            jdkText.setText(result[0].getAbsolutePath());
            updateJdkStatus();
        }
    }

    @Override
    protected void performDefaults() {
        jdkText.setText("");
        jdtText.setText("");
        vmArgumentsText.setText("");
        idleSpinner.setSelection(JchePreferences.DEFAULT_IDLE_MINUTES);
        logToFileCheck.setSelection(true);
        cacheFolderText.setText("");
        logFolderText.setText("");
        outputFolderText.setText("");
        updateJdkStatus();
        updateFolderStatus();
        super.performDefaults();
    }

    @Override
    public boolean performOk() {
        getPreferenceStore().setValue(JchePreferences.JDK, jdkText.getText().trim());
        getPreferenceStore().setValue(JchePreferences.JDT_FOLDER, jdtText.getText().trim());
        getPreferenceStore().setValue(JchePreferences.VM_ARGUMENTS, vmArgumentsText.getText().trim());
        getPreferenceStore().setValue(JchePreferences.IDLE_MINUTES, idleSpinner.getSelection());
        getPreferenceStore().setValue(JchePreferences.LOG_TO_FILE, logToFileCheck.getSelection());
        getPreferenceStore().setValue(JchePreferences.CACHE_FOLDER, cacheFolderText.getText().trim());
        getPreferenceStore().setValue(JchePreferences.LOG_FOLDER, logFolderText.getText().trim());
        getPreferenceStore().setValue(JchePreferences.OUTPUT_FOLDER, outputFolderText.getText().trim());
        // 書きかけのログは古いフォルダを掴んだままなので閉じる。次の1行で新しい場所に作り直す
        AnalysisLog.get().close();
        // 設定を変えたら、いま動いている解析プロセスは古い設定のままなので終わらせる。
        // 次の解析で新しい設定のものが起動する（キャッシュの場所も起動時に渡している）
        AnalysisService service = JchePlugin.service();
        if (service != null) {
            service.restartAll();
        }
        return true;
    }
}
