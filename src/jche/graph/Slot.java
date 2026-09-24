// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import jche.cache.Origin;

/**
 * 経路の環境（{@link DataflowContext}）の 1 つの枠に入るもの。{@code long} 1 つに、何が入っているか（札）と
 * その中身（値の番号か値の表の参照）を詰めたもの。
 *
 * <pre>
 *   札          中身                                     以前の文字列の形
 *   TYPE        具象型の FQN（{@link StringPool} の番号）    jp.co.X
 *   LITERAL     文字列の値（{@link StringPool} の番号）      L:文字列
 *   CLASS       クラスの FQN（{@link StringPool} の番号）    K:jp.co.X
 *   CONST       定数の値（{@link StringPool} の番号）        V:定数
 *   FUNCTIONAL  ラムダ／メソッド参照（{@link ValueStore} の参照。束縛したレシーバ r= を持ったまま）  Z:…
 * </pre>
 * {@link #NONE}（0）は「この経路では分からない」。以前は、具象型（{@code ':'} を含まない）と値（{@code ':'} を含む）を
 * 同じ文字列の枠に入れ、{@code ':'} の有無で見分けていた。札で見分けるので、値を文字列に組み直すことも、
 * 経路を歩くたびに {@code "K:" + クラス名} のような文字列を作ることも無い。中身はどれも値の表を作ったときに
 * 置き場に入っている文字列か、値の表の参照である。
 */
public final class Slot {

    /** 分からない */
    public static final long NONE = 0L;
    /** 具象型（中身は型の FQN の番号） */
    public static final int TYPE = 1;
    /** 文字列リテラル・文字列の定数の値（中身は値の番号） */
    public static final int LITERAL = 2;
    /** クラスリテラルなどが表すクラス（中身はクラスの FQN の番号） */
    public static final int CLASS = 3;
    /** 条件の判定に使う定数（中身は値の番号） */
    public static final int CONST = 4;
    /** ラムダ／メソッド参照（中身は値の表の参照） */
    public static final int FUNCTIONAL = 5;

    private Slot() {
    }

    /** 札と中身を詰める */
    public static long of(int tag, int payload) {
        return ((long) tag << 32) | (payload & 0xFFFFFFFFL);
    }

    /** 札（{@link #NONE} なら 0） */
    public static int tag(long s) {
        return (int) (s >>> 32);
    }

    /** 中身 */
    public static int payload(long s) {
        return (int) s;
    }

    /** 具象型か（以前の「{@code ':'} を含まない」） */
    public static boolean isType(long s) {
        return tag(s) == TYPE;
    }

    /** 値か（以前の「{@code ':'} を含む」。{@link #LITERAL} から {@link #FUNCTIONAL} まで） */
    public static boolean isValue(long s) {
        int t = tag(s);
        return t >= LITERAL && t <= FUNCTIONAL;
    }

    /**
     * 値の表の葉（文字列リテラル・クラス・定数）を枠に入れる形にする。それ以外の種別なら {@link #NONE}
     *
     * @param kind    値の種別（{@link Origin#LITERAL} / {@link Origin#CLASS} / {@link Origin#CONST}）
     * @param valueId 値の番号（{@link StringPool}）
     */
    public static long ofValueKind(char kind, int valueId) {
        return switch (kind) {
            case Origin.LITERAL -> of(LITERAL, valueId);
            case Origin.CLASS -> of(CLASS, valueId);
            case Origin.CONST -> of(CONST, valueId);
            default -> NONE;
        };
    }

    /**
     * 定数として比べられる値（文字列リテラル・クラス・定数）の文字列。ほか（具象型・ラムダ・分からない）は null。
     * 条件の判定（{@link GuardEvaluator}）が使う
     */
    public static String constantValue(long s, StringPool strings) {
        int id = constantValueId(s);
        return (id < 0) ? null : strings.get(id);
    }

    /** {@link #constantValue} の値の番号（{@link StringPool}。同じ中身なら同じ番号）。定数でなければ -1 */
    public static int constantValueId(long s) {
        int t = tag(s);
        return (t == LITERAL || t == CLASS || t == CONST) ? payload(s) : -1;
    }
}
