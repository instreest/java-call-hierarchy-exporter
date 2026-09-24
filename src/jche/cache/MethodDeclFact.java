// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cache;

/**
 * メソッド宣言の1件（D行）。
 * <pre>
 *   D  記号  declLine  hasBody(1/0)  mods  アノテーション  endLine
 * </pre>
 * 記号はブロックの記号表（{@link SymbolTable}）の番号。
 *
 * @param ref      宣言されたメソッド
 * @param declLine 宣言行
 * @param hasBody  本体を持つか。IFの抽象メソッドとデフォルトメソッドの区別に使う
 * @param mods     修飾子（{@link ModifierTokens}）。implicit / delegating も含みうる
 * @param annotations メソッドに付いていたアノテーション（{@link AnnotationTokens}。v13 で追加）
 * @param endLine  宣言の終了行（本体の閉じ括弧の行。v20 で追加）。
 *                 分からなければ declLine と同じ値。カーソル位置から囲むメソッドを引くのに使う
 */
public record MethodDeclFact(MethodRef ref, int declLine, boolean hasBody, String mods,
                             String annotations, int endLine) {

    public MethodDeclFact(MethodRef ref, int declLine, boolean hasBody, String mods) {
        this(ref, declLine, hasBody, mods, "", declLine);
    }

    public MethodDeclFact(MethodRef ref, int declLine, boolean hasBody, String mods,
                          String annotations) {
        this(ref, declLine, hasBody, mods, annotations, declLine);
    }

    public MethodDeclFact {
        mods = (mods == null) ? "" : mods;
        annotations = (annotations == null) ? "" : annotations;
        // 終了行が開始行より手前になることはない。壊れた値は開始行に丸めて、
        // 「その行だけの範囲」として扱えるようにする
        endLine = (endLine < declLine) ? declLine : endLine;
    }

    public String toRow(SymbolTable symbols) {
        return CacheFormat.joinRow("D", symbols.columnOf(ref),
                String.valueOf(declLine), hasBody ? "1" : "0", mods, annotations,
                String.valueOf(endLine));
    }

    /**
     * 列が足りない・記号が引けなければ null
     *
     * @param symbols ブロックの記号表（{@link SymbolTable.Reader#array}）
     */
    public static MethodDeclFact fromRow(String[] cols, MethodRef[] symbols) {
        if (cols.length < 3) {
            return null;
        }
        MethodRef ref = SymbolTable.resolve(symbols, cols[1]);
        if (ref == null) {
            return null;
        }
        int declLine;
        try {
            declLine = Integer.parseInt(cols[2]);
        } catch (NumberFormatException ignore) {
            declLine = -1;
        }
        boolean hasBody = (cols.length < 4) || !"0".equals(cols[3]);
        return new MethodDeclFact(ref, declLine, hasBody, CacheFormat.columnAt(cols, 4),
                CacheFormat.columnAt(cols, 5), parseLine(CacheFormat.columnAt(cols, 6), declLine));
    }

    /** 終了行の列。空や壊れた値なら宣言行（＝その行だけの範囲）に倒す */
    private static int parseLine(String col, int fallback) {
        if (col == null || col.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(col);
        } catch (NumberFormatException ignore) {
            return fallback;
        }
    }
}
