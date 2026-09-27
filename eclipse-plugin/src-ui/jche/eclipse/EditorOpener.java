// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import java.nio.file.Path;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * 解析結果の「プロジェクトルートからの相対パス＋行番号」から、エディタを開いてその行を選ぶ。
 *
 * <p>解析はワークスペースの外のパスでも動く（設定ファイルの project.root は任意のフォルダ）ので、
 * まず絶対パスに直してからワークスペース内のファイルを探す。見つからなければ何もしない
 * （ワークスペースに取り込まれていないプロジェクトを解析した場合）。
 */
final class EditorOpener {

    private EditorOpener() {
    }

    /**
     * @param projectRoot 解析対象のプロジェクトルート（設定ファイルの project.root）
     * @param relativePath そこからの相対パス。null なら何もしない
     * @param line 1始まりの行番号。0以下なら行は選ばずファイルだけ開く
     * @return 開けたら true
     */
    static boolean open(IWorkbenchPage page, Path projectRoot, String relativePath, int line) {
        if (page == null || projectRoot == null || relativePath == null) {
            return false;
        }
        Path absolute = projectRoot.resolve(relativePath.replace('\\', '/')).toAbsolutePath().normalize();
        IFile file = ResourcesPlugin.getWorkspace().getRoot()
                .getFileForLocation(org.eclipse.core.runtime.Path.fromOSString(absolute.toString()));
        if (file == null || !file.exists()) {
            return false;
        }
        try {
            IEditorPart editor = IDE.openEditor(page, file, true);
            if (line > 0 && editor instanceof ITextEditor) {
                ITextEditor textEditor = (ITextEditor) editor;
                IDocument document = textEditor.getDocumentProvider()
                        .getDocument(textEditor.getEditorInput());
                if (document != null) {
                    int index = Math.min(line - 1, document.getNumberOfLines() - 1);
                    textEditor.selectAndReveal(document.getLineOffset(index),
                            document.getLineLength(index));
                }
            }
            return true;
        } catch (PartInitException | BadLocationException e) {
            JchePlugin.log(IStatus.WARNING, Messages.format("editor.openFailed", relativePath), e);
            return false;
        }
    }

    /**
     * Java の要素（フィールドの宣言など）を開いて、その名前を選ぶ。解析結果に行番号が無いもの
     * （フィールドの宣言の行。キャッシュが持たない）を開くのに使う。
     *
     * @return 開けたら true（ワークスペースのファイルでない要素は開けない）
     */
    static boolean open(IWorkbenchPage page, IMember member) {
        if (page == null || member == null || !(member.getResource() instanceof IFile)) {
            return false;
        }
        IFile file = (IFile) member.getResource();
        try {
            IEditorPart editor = IDE.openEditor(page, file, true);
            ISourceRange range = member.getNameRange();
            if (editor instanceof ITextEditor && range != null && range.getOffset() >= 0) {
                ((ITextEditor) editor).selectAndReveal(range.getOffset(), range.getLength());
            }
            return true;
        } catch (PartInitException | JavaModelException e) {
            JchePlugin.log(IStatus.WARNING, Messages.format("editor.openFailed", file.getFullPath()), e);
            return false;
        }
    }
}
