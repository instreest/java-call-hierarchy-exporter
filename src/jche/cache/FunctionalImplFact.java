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
 * ラムダ／メソッド参照の1箇所（M行）。
 * その関数型インターフェースのメソッドに「ソース上に見えない実装がある」ことを表す。
 *
 * @param line           出現行
 * @param caller         囲みメソッド。特定できなければ null
 * @param ifaceMethodKey 実装している関数型インターフェースのメソッドキー typeFqn#method(paramSig)
 * @param kind           {@link #LAMBDA} / {@link #METHOD_REF} / {@link #CTOR_REF}
 */
public record FunctionalImplFact(int line, MethodRef caller, String ifaceMethodKey, String kind) {

    public static final String LAMBDA = "lambda";
    public static final String METHOD_REF = "methodref";
    public static final String CTOR_REF = "ctorref";

    public String toRow() {
        String[] c = (caller == null) ? MethodRef.emptyColumns() : caller.toColumns();
        return CacheFormat.joinRow("M", String.valueOf(line), c[0], c[1], c[2], c[3],
                ifaceMethodKey, kind);
    }

    /** 列が足りなければ null */
    public static FunctionalImplFact fromRow(String[] cols) {
        if (cols.length < 7) {
            return null;
        }
        return new FunctionalImplFact(CallEdgeFact.parseIntOr(cols[1], -1),
                MethodRef.fromColumns(cols, 2), cols[6], CacheFormat.columnAt(cols, 7));
    }
}
