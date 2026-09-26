// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cache;

/**
 * そのメソッドが返しうる値の1件（R行）。
 *
 * 追跡できない return も {@link ValueNode#NONE}（-1）として記録する。「追跡できない return が
 * 1つでもあれば戻り値は不定」という判定は読み手が行う。
 *
 * @param method return を含むメソッド
 * @param node   返す値の、同じブロックの値グラフのノード番号（{@link ValueNode}）。
 *               追跡できなければ {@link ValueNode#NONE}（「分からない」を明示する）
 */
public record ReturnFact(MethodRef method, int node) {

    /** {@code R 記号 node}。記号はブロックの記号表（{@link SymbolTable}）の番号 */
    public String toRow(SymbolTable symbols) {
        return CacheFormat.joinRow("R", symbols.columnOf(method), String.valueOf(node));
    }

    /**
     * 列が足りない・記号が引けなければ null。ノード番号が読めなければ {@link ValueNode#NONE}（追跡できない）
     *
     * @param symbols ブロックの記号表（{@link SymbolTable.Reader#array}）
     */
    public static ReturnFact fromRow(String[] cols, MethodRef[] symbols) {
        if (cols.length < 3) {
            return null;
        }
        MethodRef method = SymbolTable.resolve(symbols, cols[1]);
        return (method == null) ? null : new ReturnFact(method, ValueNode.intOf(cols[2], ValueNode.NONE));
    }
}
