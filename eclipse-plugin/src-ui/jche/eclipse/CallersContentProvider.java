// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.Viewer;

/**
 * ツリーの中身を {@link CallersModel} から取り出す。
 *
 * <p>子は開いたときに計算し、そのノードぶんだけ覚えておく（同じノードの再計算を避けるため）。
 * モデルが差し替わったら（解析し直し、フィルタ変更）覚えていたものは全部捨てる。
 */
final class CallersContentProvider implements ITreeContentProvider {

    private final Map<CallNode, List<CallNode>> childrenCache = new HashMap<>();
    private CallersModel model;

    @Override
    public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
        childrenCache.clear();
        model = (newInput instanceof CallersInput input) ? input.model() : null;
    }

    @Override
    public Object[] getElements(Object inputElement) {
        if (inputElement instanceof CallersInput input && input.root() != null) {
            return new Object[] {input.root()};
        }
        return new Object[0];
    }

    @Override
    public Object[] getChildren(Object parentElement) {
        return children(parentElement).toArray();
    }

    @Override
    public Object getParent(Object element) {
        return (element instanceof CallNode node) ? node.parent() : null;
    }

    @Override
    public boolean hasChildren(Object element) {
        return !children(element).isEmpty();
    }

    private List<CallNode> children(Object element) {
        if (model == null || !(element instanceof CallNode node)) {
            return List.of();
        }
        return childrenCache.computeIfAbsent(node, model::childrenOf);
    }

    @Override
    public void dispose() {
        childrenCache.clear();
    }
}
