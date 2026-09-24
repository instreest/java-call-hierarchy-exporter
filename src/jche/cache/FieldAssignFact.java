// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cache;

/**
 * フィールドへの代入の1件（J行）。
 * 「必ずこれが入る」かどうかの判定は読み手が行う（jche.graph.FieldFacts）。
 *
 * @param typeFqn   フィールドを宣言している型
 * @param fieldName フィールド名
 * @param site      代入箇所。{@link #SITE_INITIALIZER} か、メソッド／コンストラクタの "name(paramSig)"
 * @param node      代入された値の、同じブロックの値グラフのノード番号（{@link ValueNode}）。
 *                  入れ子（実引数・レシーバ）も付いたノードを指す。読み手はノードの頭（種別と値）だけで
 *                  比べる（jche.graph.CallGraphBuilder）。追跡できなければ {@link ValueNode#NONE}
 */
public record FieldAssignFact(String typeFqn, String fieldName, String site, int node) {

    /** フィールド初期化子での代入を表す site */
    public static final String SITE_INITIALIZER = "<field>";

    public String toRow() {
        return CacheFormat.joinRow("J", typeFqn, fieldName, site, String.valueOf(node));
    }

    /** 列が足りなければ null。ノード番号が読めなければ {@link ValueNode#NONE}（追跡できない） */
    public static FieldAssignFact fromRow(String[] cols) {
        if (cols.length < 5) {
            return null;
        }
        return new FieldAssignFact(cols[1], cols[2], cols[3], ValueNode.intOf(cols[4], ValueNode.NONE));
    }
}
