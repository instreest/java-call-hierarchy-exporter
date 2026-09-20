// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.report;

/**
 * call-hierarchy.csv の {@code resolved-by} 列（解決方法）の語彙。
 *
 * <p>値は「接頭辞 + 解決に使った段のラベル（{@link jche.graph.Resolution}）」の形で、
 * 接頭辞が確度、後半が手法を表す。
 * <pre>
 *   RESOLVED:DATAFLOW_FIELD      … 呼び出し先を1件に確定した（手法はデータフロー・フィールド）
 *   UNEXPANDED:CHA               … 候補のまま絞れなかった（手法はCHA）
 *   UNRESOLVED:BINDING_FAILED    … 型解決に失敗した行
 *   EXTERNAL_USAGE:EXACT         … 被参照スキャンの行
 * </pre>
 *
 * <p>後半は原則ラベルそのままだが、ラベルだけでは誤読させる1ケースだけ言い換える。
 * ラムダ／メソッド参照が実装している関数型インターフェースの呼び出しは、
 * ソース上の実装が1件でも（ラベルは {@code SINGLE_IMPL} 等の確定系でも）
 * 実際にどれが走るかは未特定なので {@link #LAMBDA} を使う。
 *
 * <p>注記（call-hierarchy 列の最後の要素）とは同じ判定から作るので、両者が食い違うことはない。
 * 列と完全に重複する裸の {@code [RESOLVED:*]} は注記に出さない
 * （{@code docs/call-hierarchy-columns-qa.md}）。
 */
final class ResolvedBy {

    /** 呼び出し先を1件に確定した */
    static final String RESOLVED = "RESOLVED:";
    /** 候補のまま絞れず、そこから先へ降りなかった */
    static final String UNEXPANDED = "UNEXPANDED:";
    /** 呼び出し先の型を特定できなかった行（U行）。後半は理由コード */
    static final String UNRESOLVED = "UNRESOLVED:";
    /** 被参照スキャンの行。後半は照合の種類（EXACT / INHERITED / IMPLICIT_CTOR） */
    static final String EXTERNAL_USAGE = "EXTERNAL_USAGE:";

    /**
     * ラムダ／メソッド参照による実装があり、どれが実行されるかは未特定。
     * ラベル（{@code SINGLE_IMPL} 等）をそのまま出すと確定したように見えるため言い換える
     */
    static final String LAMBDA = "LAMBDA";

    private ResolvedBy() {
    }
}
