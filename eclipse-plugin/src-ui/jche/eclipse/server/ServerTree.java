// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * サーバーが返した行（深さ優先の並び）を木に組み直したもの。
 *
 * <p>行には深さしか入っていないが、深さ優先で並んでいるので「直前に出た1つ浅い行」が親になる。
 * 画面はこの木をそのまま描く。
 */
public final class ServerTree {

    /** 木の節点。子は読み取り専用 */
    public static final class Node {
        private final ServerRow row;
        private final Node parent;
        private final List<Node> children = new ArrayList<Node>();

        Node(ServerRow row, Node parent) {
            this.row = row;
            this.parent = parent;
        }

        public ServerRow row() {
            return row;
        }

        public Node parent() {
            return parent;
        }

        public List<Node> children() {
            return Collections.unmodifiableList(children);
        }

        /** 深さの上限で打ち切られた節点。開くときに、ここを根にして問い合わせ直す */
        public boolean isTruncated() {
            return row.hasFlag(ServerRow.FLAG_TRUNCATED);
        }
    }

    private final Node root;

    private ServerTree(Node root) {
        this.root = root;
    }

    /** 行の並びから木を作る。行が無ければ根は null */
    public static ServerTree of(List<ServerRow> rows) {
        Node root = null;
        Node previous = null;
        for (ServerRow row : rows) {
            if (previous == null) {
                root = new Node(row, null);
                previous = root;
                continue;
            }
            Node parent = previous;
            // 直前の行から、深さが1つ浅くなるまでさかのぼる
            while (parent != null && parent.row().depth() >= row.depth()) {
                parent = parent.parent();
            }
            if (parent == null) {
                continue;   // 行が壊れている。捨てて先へ進む
            }
            Node node = new Node(row, parent);
            parent.children.add(node);
            previous = node;
        }
        return new ServerTree(root);
    }

    public Node root() {
        return root;
    }

    /** 節点の総数（画面下の件数表示に使う） */
    public int size() {
        return (root == null) ? 0 : count(root);
    }

    private static int count(Node node) {
        int total = 1;
        for (Node child : node.children()) {
            total += count(child);
        }
        return total;
    }
}
