// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.jdt.core.IField;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * 「影響調査: フィールドの呼び出し元を表示」コマンドのハンドラ。
 *
 * <p>エディタのカーソル位置（フィールドの宣言か参照の上）、またはエクスプローラー／アウトラインの選択から
 * フィールドを取り出し（{@link FieldPicker}）、{@link CallHierarchyView} に渡す。ビューは、そのフィールドを
 * 読み書きしているメソッドと、その呼び出し元を 1 つの木で出す（docs/field-callers-qa.md）。
 *
 * <p>メソッドのコマンド（{@link ShowCallersHandler}）とは分けた。1 つのコマンドにすると、メソッドの本体の中で
 * フィールドの参照にカーソルがあるとき、どちらを見たいのかを決められないため。
 */
public class ShowFieldCallersHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) {
        IWorkbenchPage page = HandlerUtil.getActiveWorkbenchWindow(event).getActivePage();
        IField field = FieldPicker.pick(page, HandlerUtil.getCurrentSelection(event));
        if (field == null) {
            MessageDialog.openInformation(HandlerUtil.getActiveShell(event),
                    Messages.get("dialog.title"), Messages.get("field.notIdentified"));
            return null;
        }
        IProject project = field.getResource() != null
                ? field.getResource().getProject()
                : field.getJavaProject().getProject();
        if (JchePlugin.service() == null || project == null) {
            return null;
        }
        try {
            CallHierarchyView view = (CallHierarchyView) page.showView(CallHierarchyView.VIEW_ID);
            // 表示中の対象の workspace.projects に入っているプロジェクトなら、対象を切り替えずにその解析から引く
            ProjectAnalysis analysis = view.analysisForMemberProject(project);
            if (analysis == null) {
                return null;
            }
            view.showField(analysis, field);
        } catch (PartInitException e) {
            JchePlugin.log(IStatus.ERROR, Messages.get("view.openFailed"), e);
        }
        return null;
    }
}
