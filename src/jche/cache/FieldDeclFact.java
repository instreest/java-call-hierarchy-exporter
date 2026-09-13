// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cache;

/**
 * フィールド宣言の1件（V行）。
 *
 * @param typeFqn   宣言している型
 * @param fieldName フィールド名
 * @param mods      修飾子（{@link ModifierTokens}）
 * @param declType  宣言型のFQN（消去型）。配列は要素型に "[]" を付けた形
 * @param annotations フィールドに付いていたアノテーション（{@link AnnotationTokens}。v13 で追加）
 */
public record FieldDeclFact(String typeFqn, String fieldName, String mods, String declType,
                            String annotations) {

    public FieldDeclFact {
        declType = (declType == null) ? "" : declType;
        annotations = (annotations == null) ? "" : annotations;
    }

    public String toRow() {
        return CacheFormat.joinRow("V", typeFqn, fieldName, mods, declType, annotations);
    }

    /** 列が足りなければ null */
    public static FieldDeclFact fromRow(String[] cols) {
        if (cols.length < 3) {
            return null;
        }
        return new FieldDeclFact(cols[1], cols[2], CacheFormat.columnAt(cols, 3),
                CacheFormat.columnAt(cols, 4), CacheFormat.columnAt(cols, 5));
    }
}
