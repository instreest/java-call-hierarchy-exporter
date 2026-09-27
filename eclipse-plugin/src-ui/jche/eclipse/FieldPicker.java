// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbenchPage;

/**
 * 「いま利用者が指しているフィールド」を取り出し、解析側のキーにする（{@link MethodPicker} のフィールド版）。
 *
 * <p>探す順は<b>選択が先、エディタが後</b>（{@link MethodPicker} と同じ）。エディタでは、カーソルが
 * フィールドの<b>参照</b>（{@code this.status} の {@code status}）の上にあっても、そのフィールドを引く
 * （{@link ICompilationUnit#codeSelect}）。宣言の上なら宣言そのもの。影響調査では、読んでいるコードの途中で
 * 「このフィールドはどこで書かれるのか」と引きたくなるため、宣言まで移らなくてよいようにする
 * （docs/field-callers-qa.md）。
 */
final class FieldPicker {

    private FieldPicker() {
    }

    /**
     * 解析側のフィールドのキー（{@code 型FQN#フィールド名}）。
     * 型の綴りはメソッドのキー（{@link MethodKeys#keyOf}）と同じく、内部クラスを {@code .} でつないだ形。
     * 組み立てられなければ null
     */
    static String keyOf(IField field) {
        IType type = field.getDeclaringType();
        if (type == null) {
            return null;
        }
        return type.getFullyQualifiedName('.') + "#" + field.getElementName();
    }

    /**
     * 選択とエディタのカーソル位置から、いま指されているフィールドを探す。
     *
     * @param page      いまのページ（エディタを見るため）。無ければ null でよい
     * @param selection いまの選択。無ければ null でよい
     * @return フィールド。特定できなければ null
     */
    static IField pick(IWorkbenchPage page, ISelection selection) {
        IField fromSelection = fieldIn(selection);
        if (fromSelection != null) {
            return fromSelection;
        }
        IEditorPart editor = (page == null) ? null : page.getActiveEditor();
        if (editor == null) {
            return null;
        }
        ISelection editorSelection = (editor.getSite().getSelectionProvider() == null)
                ? null : editor.getSite().getSelectionProvider().getSelection();
        IField fromEditor = fieldIn(editorSelection);
        if (fromEditor != null) {
            return fromEditor;
        }
        if (!(editorSelection instanceof ITextSelection)) {
            return null;
        }
        ITextSelection text = (ITextSelection) editorSelection;
        return fieldAt(compilationUnitOf(editor.getEditorInput()), text.getOffset(), text.getLength());
    }

    /** 構造化された選択（エクスプローラー・アウトライン）からフィールドを取り出す。無ければ null */
    private static IField fieldIn(ISelection selection) {
        if (!(selection instanceof IStructuredSelection) || ((IStructuredSelection) selection).isEmpty()) {
            return null;
        }
        Object first = ((IStructuredSelection) selection).getFirstElement();
        return (first instanceof IField) ? (IField) first : null;
    }

    /**
     * エディタの位置にあるフィールド。参照の上ならそのフィールド（{@link ICompilationUnit#codeSelect}）、
     * 宣言の上なら宣言。どちらでもなければ null
     */
    static IField fieldAt(ICompilationUnit unit, int offset, int length) {
        if (unit == null) {
            return null;
        }
        try {
            for (IJavaElement element : unit.codeSelect(offset, length)) {
                if (element instanceof IField) {
                    return (IField) element;
                }
            }
            IJavaElement enclosing = unit.getElementAt(offset);
            return (enclosing instanceof IField) ? (IField) enclosing : null;
        } catch (JavaModelException e) {
            return null;
        }
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
