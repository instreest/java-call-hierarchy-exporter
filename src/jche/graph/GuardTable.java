// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import java.util.BitSet;

import jche.cache.Guard;

/**
 * 条件の表。呼び出し箇所を囲む条件分岐（キャッシュの G 行。{@link Guard}）を、グラフ全体で通じる
 * ガードの番号で引ける列の形にしたもの。構築は {@link GuardTableBuilder}。作った後は変わらない。
 *
 * <p>1 つのガードはアトム（条件 1 つ）の並びで、全部が成立して初めて呼び出しに届く（論理積）。
 * アトムは判定の種別・判定される式（{@link ValueStore} の葉の参照）・比べる値（切り詰めない）・
 * ソースの条件式のテキストを持つ。知らない種別のアトムも {@link #OTHER} として残す（判定には使わない）。
 * 比べる値は {@link #EQ} / {@link #NE} なら必ず 1 つ、ほかは 1 つ以上（形の崩れた G 行もこの形にそろえてある。
 * {@link GuardTableBuilder}）。
 */
public final class GuardTable {

    /** ガードが無い（条件なし）ことを表す番号 */
    public static final int NONE = -1;

    /** 知らない種別（判定しない） */
    public static final byte OTHER = 0;
    /** 値が一致すること */
    public static final byte EQ = 1;
    /** 値が一致しないこと */
    public static final byte NE = 2;
    /** いずれかの値と一致すること（switch の case） */
    public static final byte IN = 3;
    /** どの値とも一致しないこと（switch の default） */
    public static final byte NOT_IN = 4;

    private final StringPool strings;
    /** ガードごとのアトムの範囲（{@code atomOff[g]} から {@code atomOff[g + 1]} の手前まで） */
    private final int[] atomOff;
    private final byte[] op;
    /** アトムごとの判定される式（{@link ValueStore} の葉の参照。無ければ {@link ValueStore#NONE}） */
    private final int[] subject;
    /** アトムごとの条件式のテキスト（{@link StringPool} の番号） */
    private final int[] text;
    /** アトムごとの値の範囲（{@code valueOff[a]} から {@code valueOff[a + 1]} の手前まで） */
    private final int[] valueOff;
    /** 値（{@link StringPool} の番号） */
    private final int[] values;
    /** 判定される式が囲みメソッドの引数（A）のアトムを持つガード */
    private final BitSet onParam;

    GuardTable(StringPool strings, int[] atomOff, byte[] op, int[] subject, int[] text,
               int[] valueOff, int[] values, BitSet onParam) {
        this.strings = strings;
        this.atomOff = atomOff;
        this.op = op;
        this.subject = subject;
        this.text = text;
        this.valueOff = valueOff;
        this.values = values;
        this.onParam = onParam;
    }

    /** 判定の種別の文字列（G 行の op 列）を番号にする。知らない種別は {@link #OTHER} */
    static byte opOf(String op) {
        if (op == null) {
            return OTHER;
        }
        return switch (op) {
            case Guard.EQ -> EQ;
            case Guard.NE -> NE;
            case Guard.IN -> IN;
            case Guard.NOT_IN -> NOT_IN;
            default -> OTHER;
        };
    }

    /** ガードの数（番号は 0 から {@code size() - 1}） */
    public int size() {
        return atomOff.length - 1;
    }

    /** アトムの始まり。{@link #NONE} なら空 */
    public int atomBegin(int g) {
        return (g < 0) ? 0 : atomOff[g];
    }

    /** アトムの終わり（この手前まで）。{@link #NONE} なら空 */
    public int atomEnd(int g) {
        return (g < 0) ? 0 : atomOff[g + 1];
    }

    /** アトムの判定の種別（{@link #EQ} など） */
    public byte op(int a) {
        return op[a];
    }

    /** アトムの判定される式（{@link ValueStore} の葉の参照）。無ければ {@link ValueStore#NONE} */
    public int subject(int a) {
        return subject[a];
    }

    /** アトムの条件式のテキスト（注記用） */
    public String text(int a) {
        return strings.get(text[a]);
    }

    /** アトムの値の始まり（{@link #value} に渡す番号） */
    public int valueBegin(int a) {
        return valueOff[a];
    }

    /** アトムの値の終わり（この手前まで） */
    public int valueEnd(int a) {
        return valueOff[a + 1];
    }

    /** 値（切り詰めない） */
    public String value(int k) {
        return strings.get(values[k]);
    }

    /** 値の番号（{@link StringPool}。同じ値なら同じ番号） */
    public int valueId(int k) {
        return values[k];
    }

    /** そのガードに、囲みメソッドの引数を判定するアトムがあるか。{@link #NONE} なら false */
    public boolean onParam(int g) {
        return g >= 0 && onParam.get(g);
    }

    /** 列が使っているおおよそのバイト数（文字列の置き場は含まない） */
    long columnBytes() {
        return 4L * atomOff.length + op.length + 4L * subject.length + 4L * text.length
                + 4L * valueOff.length + 4L * values.length + onParam.size() / 8;
    }
}
