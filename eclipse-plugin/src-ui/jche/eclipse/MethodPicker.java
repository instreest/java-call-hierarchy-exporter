// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbenchPage;

/**
 * 「いま利用者が指しているメソッド」を取り出す。
 *
 * <p>元は {@link ShowCallersHandler} の中にあった処理だが、入口がコマンドだけではなくなった
 * （ビューの［カーソル位置のメソッド］ボタンからも使う）ので、両方から呼べる形に分けた。
 * コマンドは {@code ExecutionEvent} を、ビューは {@code IWorkbenchPage} を持っているだけで、
 * 見たいものは同じ「選択かカーソル位置」である。
 *
 * <p>探す順は<b>選択が先、カーソル位置が後</b>。パッケージ・エクスプローラーやアウトラインで
 * メソッドを選んでいるなら、それが利用者の指したものだからである。
 */
final class MethodPicker {

    private MethodPicker() {
    }

    /**
     * 選択とエディタのカーソル位置から、いま指されているメソッドを探す。
     *
     * @param page      いまのページ（エディタを見るため）。無ければ null でよい
     * @param selection いまの選択。無ければ null でよい
     * @return メソッド。特定できなければ null
     */
    static IMethod pick(IWorkbenchPage page, ISelection selection) {
        IMethod fromSelection = methodIn(selection);
        if (fromSelection != null) {
            return fromSelection;
        }
        IEditorPart editor = (page == null) ? null : page.getActiveEditor();
        if (editor == null) {
            return null;
        }
        ISelection editorSelection = (editor.getSite().getSelectionProvider() == null)
                ? null : editor.getSite().getSelectionProvider().getSelection();
        IMethod fromEditor = methodIn(editorSelection);
        if (fromEditor != null) {
            return fromEditor;
        }
        if (!(editorSelection instanceof ITextSelection)) {
            return null;
        }
        ICompilationUnit unit = compilationUnitOf(editor.getEditorInput());
        return MethodKeys.methodAt(unit, ((ITextSelection) editorSelection).getOffset());
    }

    /** 構造化された選択（エクスプローラー・アウトライン）からメソッドを取り出す。無ければ null */
    private static IMethod methodIn(ISelection selection) {
        if (!(selection instanceof IStructuredSelection) || ((IStructuredSelection) selection).isEmpty()) {
            return null;
        }
        return MethodKeys.methodOf(((IStructuredSelection) selection).getFirstElement());
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
