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

import jche.graph.MethodTable;

/**
 * ツリー1行の見た目。
 *
 * <p>1行に出すのは「メソッド」「呼び出している場所」「解決の理由」の3つ。
 * 解析後に変わったファイルのメソッドには警告アイコンを付け、内容が古いかもしれないことを行単位で示す
 * （全体の状態はバナーが出す。docs/eclipse-plugin-ui-design.md の §2）。
 */
final class CallersLabelProvider extends ColumnLabelProvider {

    private final CallHierarchyView view;

    CallersLabelProvider(CallHierarchyView view) {
        this.view = view;
    }

    @Override
    public String getText(Object element) {
        CallersModel model = view.model();
        if (model == null || !(element instanceof CallNode node)) {
            return String.valueOf(element);
        }
        MethodTable methods = model.methods();
        StringBuilder sb = new StringBuilder(methods.displayLabel(node.methodId()));
        String site = model.callSiteFile(node);
        int line = model.callSiteLine(node);
        if (site != null && line > 0) {
            sb.append("   ").append(fileNameOf(site)).append(':').append(line);
        } else if (site != null) {
            sb.append("   ").append(fileNameOf(site));
        }
        String reason = model.resolutionLabel(node.edgeIndex());
        if (reason != null) {
            sb.append("  «").append(reason).append('»');
        }
        if (node.recursive()) {
            sb.append("  （再帰）");
        }
        if (node.truncated()) {
            sb.append("  … （深さ上限。フィルタの深さを増やすと続きが出ます）");
        }
        return sb.toString();
    }

    @Override
    public String getToolTipText(Object element) {
        CallersModel model = view.model();
        if (model == null || !(element instanceof CallNode node)) {
            return null;
        }
        MethodTable methods = model.methods();
        StringBuilder sb = new StringBuilder(methods.key(node.methodId()));
        String file = methods.declFile(node.methodId());
        if (file != null) {
            sb.append('\n').append(file).append(':').append(methods.declLine(node.methodId()));
            if (view.isChangedSinceAnalysis(file)) {
                sb.append("\n⚠ このファイルは解析後に変更されています。再解析すると内容が変わる可能性があります");
            }
        } else {
            sb.append("\nソースがありません（依存 jar のメソッド）");
        }
        return sb.toString();
    }

    @Override
    public Image getImage(Object element) {
        CallersModel model = view.model();
        if (model == null || !(element instanceof CallNode node)) {
            return null;
        }
        ISharedImages images = PlatformUI.getWorkbench().getSharedImages();
        String file = model.methods().declFile(node.methodId());
        if (file != null && view.isChangedSinceAnalysis(file)) {
            return images.getImage(ISharedImages.IMG_OBJS_WARN_TSK);
        }
        if (file == null) {
            return images.getImage(ISharedImages.IMG_OBJ_FILE);
        }
        return images.getImage(ISharedImages.IMG_OBJ_ELEMENT);
    }

    @Override
    public Font getFont(Object element) {
        CallersModel model = view.model();
        if (model != null && element instanceof CallNode node && model.isDirectMatch(node.methodId())) {
            // 絞り込み文字列に直接一致した行を太字にする（一致した子孫のために残した枝と区別する）
            return JFaceResources.getFontRegistry().getBold(JFaceResources.DEFAULT_FONT);
        }
        return null;
    }

    @Override
    public Color getForeground(Object element) {
        CallersModel model = view.model();
        if (model != null && element instanceof CallNode node
                && (node.recursive() || node.truncated() || model.isGuessed(node.edgeIndex()))) {
            return Display.getDefault().getSystemColor(SWT.COLOR_DARK_GRAY);
        }
        return null;
    }

    private static String fileNameOf(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return (slash >= 0) ? path.substring(slash + 1) : path;
    }
}
