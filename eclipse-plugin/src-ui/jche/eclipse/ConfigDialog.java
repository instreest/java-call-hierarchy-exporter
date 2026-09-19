// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * 「解析に使う設定」ダイアログ。<b>いま何を見て解析しているのか</b>を見せ、その場で選び直せるようにする。
 *
 * <p>なぜ要るか: このプラグインは設定ファイルが無くてもプロジェクトの構成から設定を組み立てて動く。
 * 楽な代わりに、失敗したときに何が起点で、どこをソースフォルダだと思っていたのかが分からない。
 * 「設定ファイルの source.folders に指定してください」と言われても、そもそも設定ファイルを
 * 書いたことのない利用者には手の出しようがなかった（docs/eclipse-plugin-folders-qa.md の Q5）。
 *
 * <p>そこでここでは 3 つだけできるようにした。
 * <ol>
 *   <li>いま使っている設定の<b>中身をそのまま見る</b>（自動生成でもファイルでも同じ見え方）</li>
 *   <li>プロジェクトの中にある設定ファイルへ<b>切り替える</b>（自動判定に戻すのも同じ場所）</li>
 *   <li>自動生成の内容を<b>ファイルとして保存して、手で直せるようにする</b></li>
 * </ol>
 *
 * <p>ここで値を直接編集させることはしない。項目は解析側の config.properties と同じで数が多く、
 * 中途半端な編集欄を作るよりは「保存してエディタで開く」ほうが分かりやすい。
 */
final class ConfigDialog extends TitleAreaDialog {

    private final ProjectAnalysis analysis;

    /** 選べる設定ファイル（プロジェクト直下と config/ の *.properties） */
    private final List<IFile> candidates;

    private Button autoButton;
    private Button fileButton;
    private Combo fileCombo;
    private Text preview;
    private Button saveButton;

    /** OK されたときに適用する選択。null なら自動判定 */
    private IFile chosen;

    ConfigDialog(Shell shell, ProjectAnalysis analysis) {
        super(shell);
        this.analysis = analysis;
        this.candidates = analysis.findConfigFiles();
        ConfigSource current = analysis.configSource();
        this.chosen = (current != null && current.kind() == ConfigSource.Kind.FILE) ? current.file() : null;
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText(Messages.get("configDialog.title"));
    }

    @Override
    protected boolean isResizable() {
        return true;
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        setTitle(Messages.format("configDialog.heading", analysis.project().getName()));
        setMessage(Messages.get("configDialog.message"));

        Composite area = new Composite((Composite) super.createDialogArea(parent), SWT.NONE);
        area.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        GridLayout layout = new GridLayout(2, false);
        layout.marginWidth = 12;
        layout.marginHeight = 8;
        area.setLayout(layout);

        autoButton = new Button(area, SWT.RADIO);
        autoButton.setText(Messages.get("configDialog.auto"));
        autoButton.setToolTipText(Messages.get("configDialog.autoTip"));
        span(autoButton);
        autoButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                chosen = null;
                updatePreview();
            }
        });

        fileButton = new Button(area, SWT.RADIO);
        fileButton.setText(Messages.get("configDialog.useFile"));
        fileButton.setEnabled(!candidates.isEmpty());
        fileButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                chosen = selectedFile();
                updatePreview();
            }
        });

        fileCombo = new Combo(area, SWT.READ_ONLY);
        fileCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        for (IFile candidate : candidates) {
            fileCombo.add(candidate.getProjectRelativePath().toString());
        }
        fileCombo.setEnabled(!candidates.isEmpty());
        fileCombo.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                fileButton.setSelection(true);
                autoButton.setSelection(false);
                chosen = selectedFile();
                updatePreview();
            }
        });

        if (candidates.isEmpty()) {
            Label none = new Label(area, SWT.WRAP);
            none.setText(Messages.get("configDialog.noCandidates"));
            span(none);
        }

        Label previewLabel = new Label(area, SWT.NONE);
        previewLabel.setText(Messages.get("configDialog.contents"));
        span(previewLabel);

        preview = new Text(area, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL);
        GridData previewData = new GridData(SWT.FILL, SWT.FILL, true, true);
        previewData.horizontalSpan = 2;
        previewData.heightHint = 220;
        previewData.widthHint = 620;
        preview.setLayoutData(previewData);

        saveButton = new Button(area, SWT.PUSH);
        saveButton.setText(Messages.format("configDialog.save", ProjectAnalysis.PREFERRED_CONFIG_PATH));
        saveButton.setToolTipText(Messages.get("configDialog.saveTip"));
        span(saveButton);
        saveButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                saveGenerated();
            }
        });

        int index = (chosen == null) ? -1 : candidates.indexOf(chosen);
        autoButton.setSelection(index < 0);
        fileButton.setSelection(index >= 0);
        fileCombo.select(Math.max(0, index));
        updatePreview();
        return area;
    }

    private static void span(Control control) {
        GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
        data.horizontalSpan = 2;
        control.setLayoutData(data);
    }

    private IFile selectedFile() {
        int index = fileCombo.getSelectionIndex();
        return (index < 0 || index >= candidates.size()) ? null : candidates.get(index);
    }

    /**
     * いまの選択の中身を出す。
     *
     * <p>組み立てに失敗したとき（ビルド・パスにソースフォルダが無い等）も、例外の文言を
     * そのまま出す。<b>解析を走らせる前に</b>、直すべきところが分かるようにするため。
     */
    private void updatePreview() {
        ConfigSource source = (chosen != null)
                ? ConfigSource.ofFile(chosen) : ProjectAnalysis.autoConfigSourceOf(analysis.project());
        saveButton.setEnabled(source != null && source.kind() == ConfigSource.Kind.GENERATED);
        if (source == null) {
            preview.setText(Messages.get("state.noConfig"));
            return;
        }
        try {
            preview.setText(source.text());
        } catch (IOException | CoreException | RuntimeException e) {
            preview.setText(Messages.format("configDialog.buildFailed", e.getMessage()));
        }
    }

    /** 自動生成の内容を config/jche.properties として保存し、以降そちらを使う */
    private void saveGenerated() {
        ConfigSource source = ProjectAnalysis.autoConfigSourceOf(analysis.project());
        if (source == null || source.kind() != ConfigSource.Kind.GENERATED) {
            return;
        }
        IFile target = analysis.project().getFile(ProjectAnalysis.PREFERRED_CONFIG_PATH);
        if (target.exists()) {
            MessageDialog.openInformation(getShell(), Messages.get("dialog.title"),
                    Messages.format("configDialog.alreadyExists", ProjectAnalysis.PREFERRED_CONFIG_PATH));
            return;
        }
        try {
            byte[] bytes = EclipseProjectConfig.toFileText(source.generatedProperties())
                    .getBytes(StandardCharsets.UTF_8);
            IFolder folder = analysis.project().getFolder("config");
            if (!folder.exists()) {
                folder.create(false, true, null);
            }
            target.create(new ByteArrayInputStream(bytes), false, null);
        } catch (CoreException | IOException e) {
            MessageDialog.openError(getShell(), Messages.get("dialog.title"),
                    Messages.format("configDialog.saveFailed", e.getMessage()));
            return;
        }
        // 保存したファイルを、この場で選択済みにする（保存したのに使われない、を避ける）
        candidates.add(target);
        fileCombo.add(target.getProjectRelativePath().toString());
        fileCombo.select(fileCombo.getItemCount() - 1);
        fileCombo.setEnabled(true);
        fileButton.setEnabled(true);
        fileButton.setSelection(true);
        autoButton.setSelection(false);
        chosen = target;
        updatePreview();
        setMessage(Messages.get("configDialog.saved"));
    }

    @Override
    protected void okPressed() {
        analysis.setConfigFile(chosen);
        super.okPressed();
    }
}
