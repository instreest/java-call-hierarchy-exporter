// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cache;

/**
 * フィールドへの代入の1件（J行）。
 * 「必ずこれが入る」かどうかの判定は読み手が行う（jche.graph.FieldFacts）。
 *
 * @param typeFqn   フィールドを宣言している型
 * @param fieldName フィールド名
 * @param site      代入箇所。{@link #SITE_INITIALIZER} か、メソッド／コンストラクタの "name(paramSig)" か、
 *                  「生成のたびに必ず通るとは言えない場所」の {@link #SITE_ELSEWHERE}
 * @param node      代入された値の、同じブロックの値グラフのノード番号（{@link ValueNode}）。
 *                  入れ子（実引数・レシーバ）も付いたノードを指す。読み手はノードの頭（種別と値）だけで
 *                  比べる（jche.graph.CallGraphBuilder）。追跡できなければ {@link ValueNode#NONE}
 * @param kind      そのノードの種別（{@link Origin} の種別の文字）。追跡できなければ {@link Origin#UNKNOWN}。
 *                  node の N 行と同じ値を、値を読まない指定（N 行を読まない）の読み手のためにこの行にも持つ。
 *                  値を読まない指定でも「引数（{@link Origin#PARAM}）を入れる書き込みか」だけは要るため
 *                  （DI の注入点の判定。jche.graph.FieldFacts の ownValued、docs/spring-di-qa.md の Q15）
 */
public record FieldAssignFact(String typeFqn, String fieldName, String site, int node, char kind) {

    /** フィールド初期化子での代入を表す site */
    public static final String SITE_INITIALIZER = "<field>";

    /**
     * 生成のたびに必ず通るとは言えない代入の site。コンストラクタ・インスタンス初期化ブロックの中でも
     * 条件・ループ・ラムダ・入れ子の型の中にあるもの、{@code this} 以外のインスタンスへの代入、
     * static 初期化ブロックの中、別の型の本体（入れ子の型・外側の型・子クラス・ほかのファイルの型）から書いたもの。
     * 読み手（jche.graph.FieldFacts）はコンストラクタでも初期化子でもない site として扱い、
     * そのフィールドを「必ずこの値が入る」とはみなさない
     */
    public static final String SITE_ELSEWHERE = "?";

    public String toRow() {
        return CacheFormat.joinRow("J", typeFqn, fieldName, site, String.valueOf(node), String.valueOf(kind));
    }

    /**
     * 列が足りなければ null。ノード番号が読めなければ {@link ValueNode#NONE}（追跡できない）。
     * 種別の列が無い・空なら {@link Origin#UNKNOWN}（引数を入れる書き込みとはみなさない側）
     */
    public static FieldAssignFact fromRow(String[] cols) {
        if (cols.length < 5) {
            return null;
        }
        char kind = (cols.length < 6 || cols[5].isEmpty()) ? Origin.UNKNOWN : cols[5].charAt(0);
        return new FieldAssignFact(cols[1], cols[2], cols[3], ValueNode.intOf(cols[4], ValueNode.NONE), kind);
    }
}
