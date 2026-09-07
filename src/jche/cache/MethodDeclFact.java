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
 * メソッド宣言の1件（D行）。
 *
 * @param ref      宣言されたメソッド
 * @param declLine 宣言行
 * @param hasBody  本体を持つか。IFの抽象メソッドとデフォルトメソッドの区別に使う
 * @param mods     修飾子（{@link ModifierTokens}）。implicit / delegating も含みうる
 */
public record MethodDeclFact(MethodRef ref, int declLine, boolean hasBody, String mods) {

    public MethodDeclFact {
        mods = (mods == null) ? "" : mods;
    }

    public String toRow() {
        return CacheFormat.joinRow("D", ref.pkg(), ref.typeFqn(), ref.name(), ref.paramSig(),
                String.valueOf(declLine), hasBody ? "1" : "0", mods);
    }

    /** 列が足りなければ null */
    public static MethodDeclFact fromRow(String[] cols) {
        if (cols.length < 6) {
            return null;
        }
        MethodRef ref = MethodRef.fromColumns(cols, 1);
        if (ref == null) {
            return null;
        }
        int declLine;
        try {
            declLine = Integer.parseInt(cols[5]);
        } catch (NumberFormatException ignore) {
            declLine = -1;
        }
        boolean hasBody = (cols.length < 7) || !"0".equals(cols[6]);
        return new MethodDeclFact(ref, declLine, hasBody, CacheFormat.columnAt(cols, 7));
    }
}
