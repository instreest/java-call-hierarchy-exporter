// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
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
 * <p>そこでここでは 4 つだけできるようにした。
 * <ol>
 *   <li>いま使っている設定の<b>中身をそのまま見る</b>（自動生成でもファイルでも同じ見え方）</li>
 *   <li>プロジェクトの中にある設定ファイルへ<b>切り替える</b>（自動判定に戻すのも同じ場所）</li>
 *   <li>自動生成の内容を<b>ファイルとして保存して、手で直せるようにする</b></li>
 *   <li>ワークスペースの参照元・依存先のプロジェクトを<b>設定ファイルの {@code workspace.projects} に書き足す</b>
 *       （{@link WorkspaceProjectsDialog}）。解析はファイルだけを読むので、画面の状態で解析の範囲は変わらない</li>
 * </ol>
 *
 * <p>ここで値を直接編集させることはしない。項目は解析側の jche.properties と同じで数が多く、
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
    private Button workspaceButton;

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

        workspaceButton = new Button(area, SWT.PUSH);
        workspaceButton.setText(Messages.get("configDialog.workspace"));
        workspaceButton.setToolTipText(Messages.get("configDialog.workspaceTip"));
        workspaceButton.setEnabled(EclipseProjectConfig.javaProjectOf(analysis.project()) != null);
        span(workspaceButton);
        workspaceButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                addWorkspaceProjects();
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
        if (saveGeneratedFile() != null) {
            setMessage(Messages.get("configDialog.saved"));
        }
    }

    /**
     * 自動生成の内容を config/jche.properties として書き出し、その場で選択済みにする（保存したのに使われない、を避ける）。
     * 既にあれば案内して null。書けなければエラーを出して null
     */
    private IFile saveGeneratedFile() {
        ConfigSource source = ProjectAnalysis.autoConfigSourceOf(analysis.project());
        if (source == null || source.kind() != ConfigSource.Kind.GENERATED) {
            return null;
        }
        IFile target = analysis.project().getFile(ProjectAnalysis.PREFERRED_CONFIG_PATH);
        if (target.exists()) {
            MessageDialog.openInformation(getShell(), Messages.get("dialog.title"),
                    Messages.format("configDialog.alreadyExists", ProjectAnalysis.PREFERRED_CONFIG_PATH));
            return null;
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
            return null;
        }
        selectFile(target);
        return target;
    }

    /** そのファイルを一覧に足して（無ければ）選択済みにし、中身を出す */
    private void selectFile(IFile target) {
        if (!candidates.contains(target)) {
            candidates.add(target);
            fileCombo.add(target.getProjectRelativePath().toString());
        }
        fileCombo.select(candidates.indexOf(target));
        fileCombo.setEnabled(true);
        fileButton.setEnabled(true);
        fileButton.setSelection(true);
        autoButton.setSelection(false);
        chosen = target;
        updatePreview();
    }

    /**
     * ワークスペースの参照元・依存先を集めて選ばせ、設定ファイルの {@code workspace.projects} に書き足す。
     *
     * <p>書く先は、いま選んでいる設定ファイル。自動生成を使っているときは config/jche.properties で、無ければ
     * ［保存して編集］と同じ手順で先に作る。解析はファイルだけを読むので、ここで書いたものが次の解析から効く
     * （自動生成の設定には workspace.projects を入れない。ワークスペースを開いただけで全体の解析が走る経路を
     * 作らないため。docs/workspace-callers-design.md の Q11）。設定ファイルに既にある指定のうち、ワークスペースの
     * プロジェクトに結び付かないもの（手で書いた外のパス）はそのまま残す
     */
    private void addWorkspaceProjects() {
        List<WorkspaceReferences.Candidate> found = WorkspaceReferences.collect(analysis.project());
        if (found.isEmpty()) {
            MessageDialog.openInformation(getShell(), Messages.get("dialog.title"),
                    Messages.format("workspaceDialog.none", analysis.project().getName()));
            return;
        }
        // 書く先は、解析が実際に読むファイル: 選んだファイル → 自動判定で見つかるファイル → config/jche.properties（作る）
        IFile target = chosen;
        if (target == null) {
            ConfigSource auto = ProjectAnalysis.autoConfigSourceOf(analysis.project());
            target = (auto != null && auto.kind() == ConfigSource.Kind.FILE)
                    ? auto.file() : analysis.project().getFile(ProjectAnalysis.PREFERRED_CONFIG_PATH);
        }
        List<WorkspaceProjectsConfig.Entry> existing = new ArrayList<>();
        Set<IProject> already = new HashSet<>();
        if (target.exists()) {
            try {
                existing = WorkspaceProjectsConfig.resolve(target);
            } catch (CoreException | IOException e) {
                MessageDialog.openError(getShell(), Messages.get("dialog.title"),
                        Messages.format("workspaceDialog.readFailed", target.getProjectRelativePath(), e.getMessage()));
                return;
            }
            for (WorkspaceProjectsConfig.Entry entry : existing) {
                if (entry.project != null) {
                    already.add(entry.project);
                }
            }
        }
        WorkspaceProjectsDialog dialog = new WorkspaceProjectsDialog(getShell(), analysis.project(), found, already);
        if (dialog.open() != OK) {
            return;
        }
        if (!target.exists()) {
            target = saveGeneratedFile();
            if (target == null) {
                return;
            }
        }
        List<IProject> picked = new ArrayList<>(dialog.selected());
        List<String> entries = new ArrayList<>();
        for (WorkspaceProjectsConfig.Entry entry : existing) {
            if (entry.project == null) {
                entries.add(entry.raw);              // ワークスペースの外を指す指定。触らない
            } else if (picked.remove(entry.project)) {
                entries.add(entry.raw);              // 既にあって、まだ選ばれている
            }
        }
        for (IProject project : picked) {
            entries.add(WorkspaceProjectsConfig.entryFor(target, project));
        }
        try {
            WorkspaceProjectsConfig.write(target, entries);
        } catch (CoreException | IOException e) {
            MessageDialog.openError(getShell(), Messages.get("dialog.title"),
                    Messages.format("workspaceDialog.writeFailed", target.getProjectRelativePath(), e.getMessage()));
            return;
        }
        selectFile(target);
        setMessage(Messages.format("workspaceDialog.written", Integer.valueOf(entries.size()),
                target.getProjectRelativePath()));
    }

    @Override
    protected void okPressed() {
        analysis.setConfigFile(chosen);
        super.okPressed();
    }
}
