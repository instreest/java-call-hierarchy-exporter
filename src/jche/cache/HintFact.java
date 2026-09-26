// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cache;

/**
 * 証拠の1件（書き手がメモリ上で持つ形。キャッシュの行にはならない）。呼び出し箇所の解決に使う局所的な材料。
 *
 * 種別は {@link #KIND_NEW}（同一メソッド内で new された型）だけ。
 * ファクトリに渡されたキーのような証拠は、ここではなくデータフローの値グラフから読む
 * （{@code jche.graph.FactoryCalls}）。呼び出し箇所を走査して任意の証拠を残せる
 * 外部拡張は廃止した（docs/instance-analysis-plugin-qa.md の Q28）。
 *
 * <p>以前はキャッシュの X 行として書き、読み手がキャッシュ全体から「呼び出し元＋変数のキー」で集めて
 * 呼び出し箇所（C 行・U 行の recvKey）に結びつけていた。結びつけは同じファイルの中で閉じる
 * （変数のキーはそのファイルの宣言を指す）ので、今は書き手がブロックを書くときに結びつけ、
 * 結果を C 行・U 行の hints 列（{@link CallSiteValues.Row#hints}）に書く。
 *
 * @param callerKey 呼び出し元メソッドのキー typeFqn#method(paramSig)
 * @param scopeKey  結び付く対象（ローカル変数のキー。{@link CallSiteValues#recvKey} と同じ作り方）
 * @param kind      種別
 * @param value     値（new された型の FQN）
 */
public record HintFact(String callerKey, String scopeKey, String kind, String value) {

    public static final String KIND_NEW = "NEW";
}
