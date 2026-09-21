// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import java.util.Collections;
import java.util.List;

import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.Viewer;

import jche.eclipse.server.ServerTree;

/**
 * ツリーの中身。サーバーが返した木（{@link ServerTree}）をそのまま見せる。
 *
 * <p>ここには何の細工も無い。深さの上限で打ち切られた枝を、開いたときに取り寄せ直す仕掛けが
 * 以前はあったが、深さの指定そのものをやめた（行数の上限だけにした）ので要らなくなった
 * （docs/eclipse-plugin-ui-simplify-qa.md の Q5）。
 */
final class CallersContentProvider implements ITreeContentProvider {

    private ServerTree tree;

    @Override
    public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
        tree = (newInput instanceof ServerTree) ? (ServerTree) newInput : null;
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
        return (element instanceof ServerTree.Node) ? ((ServerTree.Node) element).parent() : null;
    }

    @Override
    public boolean hasChildren(Object element) {
        return !children(element).isEmpty();
    }

    private List<ServerTree.Node> children(Object element) {
        if (!(element instanceof ServerTree.Node)) {
            return Collections.emptyList();
        }
        return ((ServerTree.Node) element).children();
    }

    @Override
    public void dispose() {
        tree = null;
    }
}
