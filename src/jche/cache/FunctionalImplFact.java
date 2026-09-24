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

    /** {@code M line 呼び出し元の記号 ifaceMethodKey kind}。記号はブロックの記号表（{@link SymbolTable}）の番号 */
    public String toRow(SymbolTable symbols) {
        return CacheFormat.joinRow("M", String.valueOf(line), symbols.columnOf(caller),
                ifaceMethodKey, kind);
    }

    /**
     * 列が足りなければ null。呼び出し元の記号が引けなければ呼び出し元 null として読む
     *
     * @param symbols ブロックの記号表（{@link SymbolTable.Reader#array}）
     */
    public static FunctionalImplFact fromRow(String[] cols, MethodRef[] symbols) {
        if (cols.length < 4) {
            return null;
        }
        return new FunctionalImplFact(Names.parseIntOr(cols[1], -1),
                SymbolTable.resolve(symbols, cols[2]), cols[3], CacheFormat.columnAt(cols, 4));
    }
}
