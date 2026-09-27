// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
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
 *
 * <p>フィールドの木（docs/field-callers-qa.md）の深さ 1 の行は、理由の列に参照の種類
 * （read / write / read/write）が英語のまま来る。サーバーの行は CSV にも使うデータなので、
 * 表示するときにここで訳す。キーの無い行（宣言の初期化子・囲むメソッドの無い参照）も、ここで名前を付ける。
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
        StringBuilder sb = new StringBuilder(labelOf(row));
        if (!row.file().isEmpty()) {
            sb.append("   ").append(fileNameOf(row.file()));
            if (row.line() > 0) {
                sb.append(':').append(row.line());
            }
        }
        if (!row.reason().isEmpty()) {
            sb.append("  \u00ab").append(reasonOf(row)).append('\u00bb');
        }
        if (row.hasFlag(ServerRow.FLAG_RECURSIVE)) {
            sb.append("  ").append(Messages.get("row.recursive"));
        }
        return sb.toString();
    }

    @Override
    public String getToolTipText(Object element) {
        ServerRow row = rowOf(element);
        if (row == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(row.key().isEmpty() ? labelOf(row) : row.key());
        if (row.file().isEmpty()) {
            sb.append('\n').append(Messages.get("row.noSourceTip"));
        } else {
            sb.append('\n').append(row.file()).append(':').append(row.line());
            if (view.isChangedSinceAnalysis(row.file())) {
                sb.append('\n').append(Messages.get("row.changedTip"));
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
    public Color getForeground(Object element) {
        ServerRow row = rowOf(element);
        if (row != null && (row.hasFlag(ServerRow.FLAG_RECURSIVE)
                || row.hasFlag(ServerRow.FLAG_GUESSED))) {
            return Display.getDefault().getSystemColor(SWT.COLOR_DARK_GRAY);
        }
        return null;
    }

    /** 行の名前。キーの無い行（初期化子・囲むメソッドの無い参照）は、サーバーが名前を付けないのでここで付ける */
    private static String labelOf(ServerRow row) {
        if (row.hasFlag(ServerRow.FLAG_INITIALIZER)) {
            return Messages.get("row.fieldInitializer");
        }
        if (row.hasFlag(ServerRow.FLAG_NO_METHOD)) {
            return Messages.get("row.noMethod");
        }
        return row.label();
    }

    /** 理由の列。フィールドの参照の種類だけ訳す（ほかの理由は DATAFLOW_FACTORY のような符号のまま） */
    private static String reasonOf(ServerRow row) {
        if (!row.hasFlag(ServerRow.FLAG_ACCESS)) {
            return row.reason();
        }
        switch (row.reason()) {
            case ServerRow.ACCESS_READ:
                return Messages.get("row.access.read");
            case ServerRow.ACCESS_WRITE:
                return Messages.get("row.access.write");
            case ServerRow.ACCESS_READ_WRITE:
                return Messages.get("row.access.readWrite");
            default:
                return row.reason();
        }
    }

    private static String fileNameOf(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return (slash >= 0) ? path.substring(slash + 1) : path;
    }
}
