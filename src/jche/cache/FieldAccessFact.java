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
 * フィールドの参照箇所の1件（A行）。他の型のフィールドも含む。
 *
 * 現在の読み手は使わないが、参照の有無を問う機能を足すときに再解析せずに済むよう
 * 事実として残している。
 *
 * @param line         参照箇所の行
 * @param caller       囲みメソッド。特定できなければ null
 * @param ownerTypeFqn フィールドを宣言している型
 * @param fieldName    フィールド名
 * @param access       read / write（代入の左辺）/ readwrite（複合代入・++/--）
 * @param mods         そのフィールドの修飾子（他の型のフィールドでもバインディングから分かる）
 * @param lambdaDepth  参照箇所を囲むラムダ式の深さ（0=ラムダの外）
 */
public record FieldAccessFact(int line, MethodRef caller, String ownerTypeFqn, String fieldName,
                              String access, String mods, int lambdaDepth) {

    public static final String READ = "read";
    public static final String WRITE = "write";
    public static final String READ_WRITE = "readwrite";

    public String toRow() {
        String[] c = (caller == null) ? MethodRef.emptyColumns() : caller.toColumns();
        return CacheFormat.joinRow("A", String.valueOf(line), c[0], c[1], c[2], c[3],
                ownerTypeFqn, fieldName, access, mods, String.valueOf(lambdaDepth));
    }
}
