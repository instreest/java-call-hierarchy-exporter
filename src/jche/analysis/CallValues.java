// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import jche.cache.ValueNode;

/**
 * 呼び出し箇所1件の値（キャッシュの C 行・U 行の末尾の recv・args 列に書く分）。
 *
 * 値グラフ（{@link ValueNode}）のどのノードかを指すだけで、値そのものは持たない。
 * 読み手はノードを値の表（{@code jche.graph.ValueStore}）に取り込んで読む。
 *
 * <p>以前は同じ呼び出し箇所について「上限付きの出所の文字列」も並べて持っていたが、
 * 読み手が値グラフへ移ったので落とした（{@code docs/cache-split-qa.md} の Q22）。
 *
 * @param recvNode レシーバのノード番号。無ければ {@link ValueNode#NONE}
 * @param argNodes 実引数のノード番号（{@code 位置=番号} のカンマ区切り）。無ければ空文字
 */
record CallValues(int recvNode, String argNodes) {

    /** ノードが無い（値を記録しない呼び出し箇所） */
    static final CallValues NONE = new CallValues(ValueNode.NONE, "");
}
