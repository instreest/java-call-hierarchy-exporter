// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * 「呼び出し元階層を表示」コマンドのハンドラ。主ユースケースの入口。
 *
 * <p>エディタのカーソル位置、またはパッケージ・エクスプローラー／アウトラインの選択から
 * メソッドを取り出し、{@link CallHierarchyView} に渡す。解析がまだでも受け付ける
 * （ビューが状態を出し、そこから解析を始められる）。ここで解析を待たせない。
 */
public class ShowCallersHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) {
        IWorkbenchPage page = HandlerUtil.getActiveWorkbenchWindow(event).getActivePage();
        IMethod method = methodOf(event, page);
        if (method == null) {
            MessageDialog.openInformation(HandlerUtil.getActiveShell(event), "呼び出し階層",
                    "メソッドが特定できませんでした。メソッドの中にカーソルを置くか、"
                            + "メソッドを選んでから実行してください。");
            return null;
        }
        IProject project = method.getResource() != null
                ? method.getResource().getProject()
                : method.getJavaProject().getProject();
        AnalysisService service = JchePlugin.service();
        if (service == null || project == null) {
            return null;
        }
        ProjectAnalysis analysis = service.analysisFor(project);
        try {
            CallHierarchyView view = (CallHierarchyView) page.showView(CallHierarchyView.VIEW_ID);
            view.showMethod(analysis, method);
        } catch (PartInitException e) {
            JchePlugin.log(IStatus.ERROR, "呼び出し階層ビューを開けませんでした", e);
        }
        return null;
    }

    /** 選択（エクスプローラー・アウトライン）を優先し、無ければエディタのカーソル位置から探す */
    private static IMethod methodOf(ExecutionEvent event, IWorkbenchPage page) {
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        if (selection instanceof IStructuredSelection && !((IStructuredSelection) selection).isEmpty()) {
            IMethod method = MethodKeys.methodOf(((IStructuredSelection) selection).getFirstElement());
            if (method != null) {
                return method;
            }
        }
        IEditorPart editor = (page == null) ? null : page.getActiveEditor();
        if (editor == null) {
            return null;
        }
        ISelection editorSelection = editor.getSite().getSelectionProvider() == null
                ? null : editor.getSite().getSelectionProvider().getSelection();
        if (editorSelection instanceof IStructuredSelection
                && !((IStructuredSelection) editorSelection).isEmpty()) {
            IMethod method = MethodKeys.methodOf(
                    ((IStructuredSelection) editorSelection).getFirstElement());
            if (method != null) {
                return method;
            }
        }
        if (!(editorSelection instanceof ITextSelection)) {
            return null;
        }
        ICompilationUnit unit = compilationUnitOf(editor.getEditorInput());
        return MethodKeys.methodAt(unit, ((ITextSelection) editorSelection).getOffset());
    }

    private static ICompilationUnit compilationUnitOf(IEditorInput input) {
        if (!(input instanceof IFileEditorInput)) {
            return null;
        }
        IFile file = ((IFileEditorInput) input).getFile();
        IJavaElement element = JavaCore.create(file);
        return (element instanceof ICompilationUnit) ? (ICompilationUnit) element : null;
    }
}
