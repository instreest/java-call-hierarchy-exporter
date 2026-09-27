// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cache;

import jche.util.Names;

/**
 * フィールドの参照箇所の1件（A行）。他の型のフィールドも含む。
 *
 * 読み手は解析サーバーの「フィールドの呼び出し元」（{@code jche.server.FieldAccesses}）だけで、
 * 要求されたときにキャッシュを走査して、そのフィールドの行だけを拾う。呼び出し階層の出力
 * （{@code jche.graph.CallGraphBuilder}）は読まない（{@code docs/field-callers-qa.md}）。
 *
 * @param line         参照箇所の行
 * @param caller       囲みメソッド。特定できなければ null
 * @param ownerTypeFqn フィールドを宣言している型
 * @param fieldName    フィールド名
 * @param access       read / write（代入の左辺）/ readwrite（複合代入・++/--）
 * @param mods         そのフィールドの修飾子（他の型のフィールドでもバインディングから分かる）
 * @param lambdaDepth  参照箇所を囲む、合成メソッドにできなかったラムダ式の深さ
 *                     （合成メソッドにしたラムダの本体の中は 0）
 */
public record FieldAccessFact(int line, MethodRef caller, String ownerTypeFqn, String fieldName,
                              String access, String mods, int lambdaDepth) {

    public static final String READ = "read";
    public static final String WRITE = "write";
    public static final String READ_WRITE = "readwrite";

    /**
     * {@code A line 呼び出し元の記号 ownerTypeFqn fieldName access mods lambdaDepth}。
     * 記号はブロックの記号表（{@link SymbolTable}）の番号で、囲みメソッドが無ければ {@link SymbolTable#NO_SYMBOL}
     */
    public String toRow(SymbolTable symbols) {
        return CacheFormat.joinRow("A", String.valueOf(line), symbols.columnOf(caller),
                ownerTypeFqn, fieldName, access, mods, String.valueOf(lambdaDepth));
    }

    /**
     * 列が足りなければ null。呼び出し元の記号が {@link SymbolTable#NO_SYMBOL} なら呼び出し元 null として読む
     * （範囲の外を指す壊れた記号も null になる。壊れているかを区別したいときは
     * {@link SymbolTable#refsInRange} で先に確かめる）
     *
     * @param symbols ブロックの記号表（{@link SymbolTable.Reader#array}）
     */
    public static FieldAccessFact fromRow(String[] cols, MethodRef[] symbols) {
        if (cols.length < 6) {
            return null;
        }
        return new FieldAccessFact(Names.parseIntOr(cols[1], -1), SymbolTable.resolve(symbols, cols[2]),
                cols[3], cols[4], CacheFormat.columnAt(cols, 5), CacheFormat.columnAt(cols, 6),
                Names.parseIntOr(CacheFormat.columnAt(cols, 7), 0));
    }
}
