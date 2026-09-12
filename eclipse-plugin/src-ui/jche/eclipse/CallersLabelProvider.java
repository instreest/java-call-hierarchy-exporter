// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;

import jche.eclipse.server.ServerRow;
import jche.eclipse.server.ServerTree;

/**
 * ツリー1行の見た目。材料はサーバーが返した行（{@link ServerRow}）だけで、
 * 解析結果そのものは Eclipse 側に無い。
 *
 * <p>解析後に変わったファイルのメソッドには警告アイコンを付け、内容が古いかもしれないことを
 * 行単位で示す（全体の状態はバナーが出す。docs/eclipse-plugin-ui-design.md §2）。
 */
final class CallersLabelProvider extends ColumnLabelProvider {

    private final CallHierarchyView view;

    CallersLabelProvider(CallHierarchyView view) {
        this.view = view;
    }

    private static ServerRow rowOf(Object element) {
        return (element instanceof ServerTree.Node) ? ((ServerTree.Node) element).row() : null;
    }

    @Override
    public String getText(Object element) {
        ServerRow row = rowOf(element);
        if (row == null) {
            return String.valueOf(element);
        }
        StringBuilder sb = new StringBuilder(row.label());
        if (!row.file().isEmpty()) {
            sb.append("   ").append(fileNameOf(row.file()));
            if (row.line() > 0) {
                sb.append(':').append(row.line());
            }
        }
        if (!row.reason().isEmpty()) {
            sb.append("  «").append(row.reason()).append('»');
        }
        if (row.hasFlag(ServerRow.FLAG_RECURSIVE)) {
            sb.append("  （再帰）");
        }
        if (row.hasFlag(ServerRow.FLAG_TRUNCATED)) {
            sb.append("  … （深さ上限。開くと続きを取り寄せます）");
        }
        return sb.toString();
    }

    @Override
    public String getToolTipText(Object element) {
        ServerRow row = rowOf(element);
        if (row == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(row.key());
        if (row.file().isEmpty()) {
            sb.append("\nソースがありません（依存 jar のメソッド）");
        } else {
            sb.append('\n').append(row.file()).append(':').append(row.line());
            if (view.isChangedSinceAnalysis(row.file())) {
                sb.append("\n⚠ このファイルは解析後に変更されています。再解析すると内容が変わる可能性があります");
            }
        }
        return sb.toString();
    }

    @Override
    public Image getImage(Object element) {
        ServerRow row = rowOf(element);
        if (row == null) {
            return null;
        }
        ISharedImages images = PlatformUI.getWorkbench().getSharedImages();
        if (!row.file().isEmpty() && view.isChangedSinceAnalysis(row.file())) {
            return images.getImage(ISharedImages.IMG_OBJS_WARN_TSK);
        }
        if (row.hasFlag(ServerRow.FLAG_NO_SOURCE)) {
            return images.getImage(ISharedImages.IMG_OBJ_FILE);
        }
        return images.getImage(ISharedImages.IMG_OBJ_ELEMENT);
    }

    @Override
    public Font getFont(Object element) {
        ServerRow row = rowOf(element);
        if (row != null && row.hasFlag(ServerRow.FLAG_MATCH)) {
            // 絞り込み文字列に直接一致した行を太字にする（一致した子孫のために残した枝と区別する）
            return JFaceResources.getFontRegistry().getBold(JFaceResources.DEFAULT_FONT);
        }
        return null;
    }

    @Override
    public Color getForeground(Object element) {
        ServerRow row = rowOf(element);
        if (row != null && (row.hasFlag(ServerRow.FLAG_RECURSIVE)
                || row.hasFlag(ServerRow.FLAG_TRUNCATED) || row.hasFlag(ServerRow.FLAG_GUESSED))) {
            return Display.getDefault().getSystemColor(SWT.COLOR_DARK_GRAY);
        }
        return null;
    }

    private static String fileNameOf(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return (slash >= 0) ? path.substring(slash + 1) : path;
    }
}
