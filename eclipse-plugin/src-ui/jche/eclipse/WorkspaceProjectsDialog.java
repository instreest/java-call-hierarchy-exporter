// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;

/**
 * 設定ファイルの {@code workspace.projects} に書き足すプロジェクトを選ぶダイアログ。
 *
 * <p>対象を参照している（参照元）・対象が参照している（依存先）プロジェクト（{@link WorkspaceReferences}）を
 * チェック付きで並べ、［OK］で選んだものを設定ファイルに書く（書くのは {@link ConfigDialog}）。
 * 既に設定ファイルに書いてあるものは最初からチェックされている。ワークスペース全体の解析は費用が大きいので、
 * 自動では足さず、ここで利用者が選んだものだけを書く（docs/workspace-callers-design.md の Q11）。
 */
final class WorkspaceProjectsDialog extends TitleAreaDialog {

    private final IProject target;
    private final List<WorkspaceReferences.Candidate> candidates;
    private final Set<IProject> preselected;
    private Table table;
    private final List<IProject> selected = new ArrayList<>();

    WorkspaceProjectsDialog(Shell shell, IProject target, List<WorkspaceReferences.Candidate> candidates,
                            Set<IProject> preselected) {
        super(shell);
        this.target = target;
        this.candidates = candidates;
        this.preselected = preselected;
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText(Messages.get("workspaceDialog.title"));
    }

    @Override
    protected boolean isResizable() {
        return true;
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        setTitle(Messages.format("workspaceDialog.heading", target.getName()));
        setMessage(Messages.get("workspaceDialog.message"));

        Composite area = new Composite((Composite) super.createDialogArea(parent), SWT.NONE);
        area.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 12;
        layout.marginHeight = 8;
        area.setLayout(layout);

        Label note = new Label(area, SWT.WRAP);
        note.setText(Messages.get("workspaceDialog.note"));
        note.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        table = new Table(area, SWT.CHECK | SWT.BORDER | SWT.V_SCROLL);
        GridData tableData = new GridData(SWT.FILL, SWT.FILL, true, true);
        tableData.heightHint = 200;
        tableData.widthHint = 560;
        table.setLayoutData(tableData);
        for (WorkspaceReferences.Candidate candidate : candidates) {
            TableItem item = new TableItem(table, SWT.NONE);
            item.setText(labelOf(candidate));
            item.setData(candidate.project);
            item.setChecked(preselected.contains(candidate.project));
        }
        return area;
    }

    /** 「名前 — 参照元／依存先 — 場所」。何を足すのかを 1 行で分かるように */
    private static String labelOf(WorkspaceReferences.Candidate candidate) {
        String role = candidate.referencing && candidate.referenced
                ? Messages.get("workspaceDialog.both")
                : candidate.referencing ? Messages.get("workspaceDialog.referencing")
                : Messages.get("workspaceDialog.referenced");
        IPath location = candidate.project.getLocation();
        return Messages.format("workspaceDialog.item", candidate.project.getName(), role,
                (location == null) ? "" : location.toOSString());
    }

    @Override
    protected void okPressed() {
        selected.clear();
        for (TableItem item : table.getItems()) {
            if (item.getChecked()) {
                selected.add((IProject) item.getData());
            }
        }
        super.okPressed();
    }

    /** ［OK］で選んだプロジェクト（並びは一覧の順） */
    List<IProject> selected() {
        return selected;
    }
}
