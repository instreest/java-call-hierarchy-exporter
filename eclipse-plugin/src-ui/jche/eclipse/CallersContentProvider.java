// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.Viewer;

import jche.eclipse.server.ServerTree;

/**
 * ツリーの中身。サーバーが返した木（{@link ServerTree}）をそのまま見せる。
 *
 * <p>深さの上限で打ち切られた節点は、開かれたときにその節点を根として取り寄せ直す。
 * 取り寄せた木はここに覚えておき、同じ節点を開き直しても問い合わせない。
 */
final class CallersContentProvider implements ITreeContentProvider {

    /** 打ち切られた節点の続き（メソッドのキー → その節点を根にした木） */
    private final Map<String, ServerTree> continuations = new HashMap<>();
    private ServerTree tree;

    @Override
    public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
        tree = (newInput instanceof ServerTree input) ? input : null;
        continuations.clear();
    }

    /** 打ち切られた節点の続きを覚える。覚えたら呼び出し側がツリーを更新する */
    void addContinuation(String methodKey, ServerTree subtree) {
        continuations.put(methodKey, subtree);
    }

    boolean hasContinuation(String methodKey) {
        return continuations.containsKey(methodKey);
    }

    @Override
    public Object[] getElements(Object inputElement) {
        return (tree == null || tree.root() == null) ? new Object[0] : new Object[] {tree.root()};
    }

    @Override
    public Object[] getChildren(Object parentElement) {
        return children(parentElement).toArray();
    }

    @Override
    public Object getParent(Object element) {
        return (element instanceof ServerTree.Node node) ? node.parent() : null;
    }

    @Override
    public boolean hasChildren(Object element) {
        if (!(element instanceof ServerTree.Node node)) {
            return false;
        }
        // 打ち切られた節点は「まだ先がある」ので、開ける形にしておく
        return node.isTruncated() || !node.children().isEmpty();
    }

    private List<ServerTree.Node> children(Object element) {
        if (!(element instanceof ServerTree.Node node)) {
            return List.of();
        }
        if (node.isTruncated()) {
            ServerTree continuation = continuations.get(node.row().key());
            if (continuation != null && continuation.root() != null) {
                return continuation.root().children();
            }
            return List.of();
        }
        return node.children();
    }

    @Override
    public void dispose() {
        continuations.clear();
    }
}
