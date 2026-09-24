// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cache;

/**
 * そのメソッドが返しうる値の出所の1件（R行）。
 *
 * 追跡できない return も U として記録する。「追跡できない return が1つでもあれば
 * 戻り値は不定」という判定は読み手が行う。
 *
 * @param method return を含むメソッド
 * @param origin 返す値の出所（{@link Origin}）
 */
public record ReturnFact(MethodRef method, String origin) {

    /** {@code R 記号 origin}。記号はブロックの記号表（{@link SymbolTable}）の番号 */
    public String toRow(SymbolTable symbols) {
        return CacheFormat.joinRow("R", symbols.columnOf(method), origin);
    }

    /**
     * 列が足りない・記号が引けなければ null
     *
     * @param symbols ブロックの記号表（{@link SymbolTable.Reader#array}）
     */
    public static ReturnFact fromRow(String[] cols, MethodRef[] symbols) {
        if (cols.length < 3) {
            return null;
        }
        MethodRef method = SymbolTable.resolve(symbols, cols[1]);
        return (method == null) ? null : new ReturnFact(method, cols[2]);
    }
}
