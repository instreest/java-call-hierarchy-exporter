// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cache;

/**
 * フィールドへの代入の1件（J行）。
 * 「必ずこれが入る」かどうかの判定は読み手が行う（jche.graph.FieldFacts）。
 *
 * @param typeFqn   フィールドを宣言している型
 * @param fieldName フィールド名
 * @param site      代入箇所。{@link #SITE_INITIALIZER} か、メソッド／コンストラクタの "name(paramSig)"
 * @param origin    代入された値の出所（{@link Origin}）。追跡できなければ U
 */
public record FieldAssignFact(String typeFqn, String fieldName, String site, String origin) {

    /** フィールド初期化子での代入を表す site */
    public static final String SITE_INITIALIZER = "<field>";

    public FieldAssignFact {
        origin = Origin.isUnknown(origin) ? Origin.UNKNOWN_S : origin;
    }

    public String toRow() {
        return CacheFormat.joinRow("J", typeFqn, fieldName, site, origin);
    }

    /** 列が足りなければ null */
    public static FieldAssignFact fromRow(String[] cols) {
        if (cols.length < 5) {
            return null;
        }
        return new FieldAssignFact(cols[1], cols[2], cols[3], cols[4]);
    }
}
