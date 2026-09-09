// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

/**
 * 呼び出し元ツリーの1行。
 *
 * <p>グラフの中身は int の ID なので、ノードが持つのも ID と辺の番号だけ。
 * 表示に使う文字列は {@link CallersLabelProvider} が都度その場で組み立てる
 * （ノードを開いた枝ぶんしか作らないので、文字列を溜め込まない）。
 *
 * @param methodId  このノードのメソッド
 * @param edgeIndex 親を呼んでいる辺の番号（根は -1）。呼び出している行の取得に使う
 * @param parent    親ノード（根は null）。再帰の判定と深さに使う
 * @param depth     根からの深さ（根は 0）
 * @param recursive すでに経路上にあるメソッド（ここで打ち切る）
 * @param truncated 深さの上限で打ち切った（まだ呼び出し元がある）
 */
record CallNode(int methodId, int edgeIndex, CallNode parent, int depth,
                boolean recursive, boolean truncated) {

    static CallNode root(int methodId) {
        return new CallNode(methodId, -1, null, 0, false, false);
    }

    /** 根からこのノードまでの経路に、そのメソッドが既に出ているか（再帰の打ち切り判定） */
    boolean isOnPath(int id) {
        for (CallNode n = this; n != null; n = n.parent()) {
            if (n.methodId() == id) {
                return true;
            }
        }
        return false;
    }
}
