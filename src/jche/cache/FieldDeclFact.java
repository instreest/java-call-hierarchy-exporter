/*
 * Copyright 2026 the java-call-hierarchy-exporter authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jche.cache;

/**
 * フィールド宣言の1件（V行）。
 *
 * @param typeFqn   宣言している型
 * @param fieldName フィールド名
 * @param mods      修飾子（{@link ModifierTokens}）
 * @param declType  宣言型のFQN（消去型）。配列は要素型に "[]" を付けた形
 */
public record FieldDeclFact(String typeFqn, String fieldName, String mods, String declType) {

    public FieldDeclFact {
        declType = (declType == null) ? "" : declType;
    }

    public String toRow() {
        return CacheFormat.joinRow("V", typeFqn, fieldName, mods, declType);
    }

    /** 列が足りなければ null */
    public static FieldDeclFact fromRow(String[] cols) {
        if (cols.length < 3) {
            return null;
        }
        return new FieldDeclFact(cols[1], cols[2], CacheFormat.columnAt(cols, 3),
                CacheFormat.columnAt(cols, 4));
    }
}
