// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cache;

import jche.util.Names;

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
        return new FunctionalImplFact(Names.parseIntOr(cols[1], -1),
                MethodRef.fromColumns(cols, 2), cols[6], CacheFormat.columnAt(cols, 7));
    }
}
