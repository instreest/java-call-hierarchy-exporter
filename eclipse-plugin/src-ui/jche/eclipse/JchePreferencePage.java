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
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import jche.eclipse.server.JavaLocator;
import jche.eclipse.server.JdkDownload;

/**
 * 設定画面。「解析をどう走らせるか」だけを扱う。
 *
 * <p>解析は Eclipse とは別プロセスなので、使う JDK と JDT をここで選べる。
 * 既定（何も指定しない状態）でも動くようにしてあり、指定するのは
 * 「CLI と同じ JDK 25 で揃えたい」「閉域で新しい JDT を別に置いた」「メモリを増やしたい」
 * といったときだけでよい。
 */
public class JchePreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    private Text jdkText;
    private Label jdkStatus;
    private Text jdtText;
    private Text vmArgumentsText;
    private Spinner idleSpinner;

    @Override
    public void init(IWorkbench workbench) {
        setPreferenceStore(JchePlugin.getDefault().getPreferenceStore());
        setDescription("解析は Eclipse とは別のプロセスで走ります。ここではその走らせ方を決めます。");
    }

    @Override
    protected Control createContents(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(3, false));
        root.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        // --- 解析に使う JDK ---
        new Label(root, SWT.NONE).setText("解析に使う JDK:");
        jdkText = new Text(root, SWT.BORDER);
        jdkText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        jdkText.setText(getPreferenceStore().getString(JchePreferences.JDK));
        jdkText.setToolTipText("java の実行ファイル、または JDK のフォルダ。空なら自動で探します");
        jdkText.addModifyListener(e -> updateJdkStatus());
        Button browseJdk = new Button(root, SWT.PUSH);
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

        new Label(root, SWT.NONE);
        jdkStatus = new Label(root, SWT.WRAP);
        jdkStatus.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Button download = new Button(root, SWT.PUSH);
        download.setText("JDK " + JavaLocator.PREFERRED + " を取得…");
        download.setToolTipText("Adoptium (Eclipse Temurin) から取得して、ワークスペースの中に置きます（約 200MB）");
        download.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                downloadJdk();
            }
        });

        // --- JDT の差し替え ---
        new Label(root, SWT.NONE).setText("JDT の jar のフォルダ:");
        jdtText = new Text(root, SWT.BORDER);
        jdtText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        jdtText.setText(getPreferenceStore().getString(JchePreferences.JDT_FOLDER));
        jdtText.setToolTipText("空なら、プラグインに同梱した JDT を使います。"
                + "新しい JDT を別に置いて使いたいときだけ指定してください");
        Button browseJdt = new Button(root, SWT.PUSH);
        browseJdt.setText("参照…");
        browseJdt.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                DirectoryDialog dialog = new DirectoryDialog(getShell());
                dialog.setText("JDT の jar があるフォルダを選ぶ");
                String path = dialog.open();
                if (path != null) {
                    jdtText.setText(path);
                }
            }
        });

        // --- JVM 引数 ---
        new Label(root, SWT.NONE).setText("解析プロセスの JVM 引数:");
        vmArgumentsText = new Text(root, SWT.BORDER);
        vmArgumentsText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        vmArgumentsText.setText(getPreferenceStore().getString(JchePreferences.VM_ARGUMENTS));
        vmArgumentsText.setToolTipText("例: -Xmx4g。大きなプロジェクトはここで増やします"
                + "（Eclipse 自身のメモリとは別です）");
        new Label(root, SWT.NONE);

        // --- アイドル終了 ---
        new Label(root, SWT.NONE).setText("使われないときに終了する:");
        Composite idleRow = new Composite(root, SWT.NONE);
        GridLayout idleLayout = new GridLayout(2, false);
        idleLayout.marginWidth = 0;
        idleRow.setLayout(idleLayout);
        idleSpinner = new Spinner(idleRow, SWT.BORDER);
        idleSpinner.setMinimum(0);
        idleSpinner.setMaximum(24 * 60);
        idleSpinner.setSelection(getPreferenceStore().getInt(JchePreferences.IDLE_MINUTES));
        new Label(idleRow, SWT.NONE).setText("分後（0 なら終了しない。次の解析は最初からになります）");
        new Label(root, SWT.NONE);

        updateJdkStatus();
        return root;
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
        updateJdkStatus();
        super.performDefaults();
    }

    @Override
    public boolean performOk() {
        getPreferenceStore().setValue(JchePreferences.JDK, jdkText.getText().trim());
        getPreferenceStore().setValue(JchePreferences.JDT_FOLDER, jdtText.getText().trim());
        getPreferenceStore().setValue(JchePreferences.VM_ARGUMENTS, vmArgumentsText.getText().trim());
        getPreferenceStore().setValue(JchePreferences.IDLE_MINUTES, idleSpinner.getSelection());
        // 設定を変えたら、いま動いている解析プロセスは古い設定のままなので終わらせる。
        // 次の解析で新しい設定のものが起動する
        AnalysisService service = JchePlugin.service();
        if (service != null) {
            service.restartAll();
        }
        return true;
    }
}
