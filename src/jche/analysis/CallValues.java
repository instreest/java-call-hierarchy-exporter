// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import jche.cache.ValueNode;

/**
 * 呼び出し箇所1件の値を、2 つのキャッシュに書く形で並べて持つ入れ物。
 *
 * <pre>
 *   recvOrigin / argOrigins … analysis 側（C 行・U 行）。上限付きの出所の文字列
 *   recvNode   / argNodes   … dataflow 側（P 行）。上限の無い値グラフのノード番号
 * </pre>
 * 同じ式から一度に作る（{@link OriginTracker#valuesOf}）ことで、2 つの表現が
 * 別々の判断から出ることを防いでいる。読み手が値グラフへ移ったあと、前者は落とす。
 *
 * @param recvOrigin レシーバの出所。無ければ null（列は空になる）
 * @param argOrigins 実引数の出所。作らない形では null
 * @param recvNode   レシーバのノード番号。無ければ {@link ValueNode#NONE}
 * @param argNodes   実引数のノード番号（{@code 位置=番号} のカンマ区切り）。無ければ空文字
 */
record CallValues(String recvOrigin, String argOrigins, int recvNode, String argNodes) {

    /** 出所もノードも無い（値を記録しない呼び出し箇所） */
    static final CallValues NONE = new CallValues(null, null, ValueNode.NONE, "");

    /** 実引数の出所だけを持つ形（コンストラクタ呼び出しなど、レシーバが型そのもののもの） */
    static CallValues ofArgs(String argOrigins, String argNodes) {
        return new CallValues(null, argOrigins, ValueNode.NONE, argNodes);
    }
}
