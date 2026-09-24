// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
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
}
