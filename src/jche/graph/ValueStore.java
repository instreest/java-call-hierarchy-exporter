// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import jche.cache.Origin;
import jche.util.Names;

/**
 * 値の表。キャッシュの値グラフ（N 行）を、グラフ全体で通じる番号（参照）で引ける列の形にしたもの。
 * 構築は {@link ValueStoreBuilder}（{@link CallGraphBuilder} のスキャンの中で、呼び出し箇所・戻り値・
 * フィールドへの代入・条件から辿れるノードだけを取り込む）。作った後は変わらない。
 *
 * <h2>なぜ文字列ではなく表か</h2>
 * 読み手は、値グラフを出所の文字列（{@link Origin}）に組み直したものを受け取っていた
 * （{@link OriginRenderer}）。文字列の文法は {@code | ; { }} を区切りに使うので、値そのもの
 * （文字列リテラル・定数）がそれらの文字を含むと、読み手が途中で切って別の値に読み違える。
 * 表はノードの構造（種別・値・実引数・レシーバ）を列で持つので、値を区切りと取り違えることが無い。
 * 同じ部分式は 1 つの参照で指すので、文字列のように入れ子を何度も展開することも無い。
 *
 * <h2>ノードの形</h2>
 * 1 つのノードは種別（{@link Origin} の種別の文字。実引数リストだけは {@link #ARG_LIST}）と値
 * （{@link StringPool} の番号）と、項目の並びを持つ。項目は次の順に並ぶ（{@link OriginRenderer} が
 * 文字列に書き出す順と同じ）。
 * <pre>
 *   位置=参照 …   実引数（位置の昇順。出所の分からない実引数は無い）
 *   n=数          実引数の数（M と、レシーバを束縛した Z だけ。分からなければ無い）
 *   r=参照        レシーバ（M と、レシーバを束縛した Z だけ）
 *   s=文字列      ソースに書かれたレシーバの型（M と、レシーバを束縛した Z だけ。宣言元と違うときだけ）
 * </pre>
 * new（T）は実引数だけを持つ。項目の無いノードは「葉」で、葉は種別と値の組ごとに 1 つだけ
 * （同じ葉は同じ参照）。項目を持つノードの子の参照は、必ず親の参照より小さい。
 *
 * <h2>呼び出し箇所の実引数</h2>
 * 呼び出し箇所の実引数の並びも、種別 {@link #ARG_LIST} のノード（実引数の項目だけを持つ）にする。
 * new の実引数・メソッド呼び出しの実引数と同じ引き方（{@link #argAt} など）で読める。
 *
 * <p>配列は外に出さない。すべて番号で引く（後で列をメモリ上に置かない形に変えるときに、
 * 変えるのがこのクラスの中だけで済むように）。
 */
public final class ValueStore {

    /** 参照が無い（追跡できない）ことを表す参照。{@link #kind} は {@link Origin#UNKNOWN} */
    public static final int NONE = -1;
    /** 呼び出し箇所の実引数の並びを表すノードの種別 */
    public static final char ARG_LIST = '(';

    /** 項目の鍵: 実引数の数（{@code n=}） */
    static final short ENTRY_COUNT = -1;
    /** 項目の鍵: レシーバ（{@code r=}） */
    static final short ENTRY_RECEIVER = -2;
    /** 項目の鍵: ソースに書かれたレシーバの型（{@code s=}） */
    static final short ENTRY_STATIC = -3;

    private final StringPool strings;
    private final MethodTable methods;
    /** ノードごとの種別（{@link Origin} の種別の文字か {@link #ARG_LIST}） */
    private final byte[] kind;
    /** ノードごとの値（{@link StringPool} の番号。{@link #ARG_LIST} は -1） */
    private final int[] value;
    /** ノードごとの項目の範囲（{@code entryOff[参照]} から {@code entryOff[参照 + 1]} の手前まで） */
    private final int[] entryOff;
    /** 項目の鍵（0 以上は実引数の位置。負は {@link #ENTRY_COUNT} / {@link #ENTRY_RECEIVER} / {@link #ENTRY_STATIC}） */
    private final short[] entryKey;
    /** 項目の値（実引数とレシーバは参照、実引数の数はその数、書かれた型は {@link StringPool} の番号） */
    private final int[] entryVal;

    ValueStore(StringPool strings, MethodTable methods, byte[] kind, int[] value, int[] entryOff,
               short[] entryKey, int[] entryVal) {
        this.strings = strings;
        this.methods = methods;
        this.kind = kind;
        this.value = value;
        this.entryOff = entryOff;
        this.entryKey = entryKey;
        this.entryVal = entryVal;
    }

    /** ノードの数（参照は 0 から {@code size() - 1}） */
    public int size() {
        return kind.length;
    }

    /** 値の文字列の置き場（{@link GuardTable} と共有する） */
    public StringPool strings() {
        return strings;
    }

    /** ノードの種別。{@link #NONE} なら {@link Origin#UNKNOWN} */
    public char kind(int ref) {
        return (ref < 0) ? Origin.UNKNOWN : (char) (kind[ref] & 0xFF);
    }

    /** 値の番号（{@link StringPool}）。{@link #NONE} と {@link #ARG_LIST} は -1 */
    public int valueId(int ref) {
        return (ref < 0) ? -1 : value[ref];
    }

    /** 値の文字列（切り詰めない）。値が無ければ空文字 */
    public String value(int ref) {
        int id = valueId(ref);
        return (id < 0) ? "" : strings.get(id);
    }

    /** 値を位置（A・E・C の引数位置）として読んだもの。数でなければ -1 */
    public int index(int ref) {
        return Names.parseIntOr(value(ref), -1);
    }

    /** 値をメソッドキーとして引いたメソッド ID（M・Z）。無ければ -1 */
    public int methodId(int ref) {
        return (ref < 0) ? -1 : methods.idOf(value(ref));
    }

    /** 2 つのノードの頭（種別と値）が同じか */
    public boolean sameHead(int a, int b) {
        return kind(a) == kind(b) && valueId(a) == valueId(b);
    }

    /** 実引数の項目の始まり（{@link #argPos} / {@link #argRef} に渡す番号）。{@link #NONE} なら空 */
    public int argBegin(int ref) {
        return (ref < 0) ? 0 : entryOff[ref];
    }

    /** 実引数の項目の終わり（この手前まで）。実引数の項目は先頭に並ぶので、後ろの n= r= s= を除く */
    public int argEnd(int ref) {
        if (ref < 0) {
            return 0;
        }
        int begin = entryOff[ref];
        int end = entryOff[ref + 1];
        while (end > begin && entryKey[end - 1] < 0) {
            end--;
        }
        return end;
    }

    /** 実引数の項目の位置（0 始まり） */
    public int argPos(int k) {
        return entryKey[k];
    }

    /** 実引数の項目の参照 */
    public int argRef(int k) {
        return entryVal[k];
    }

    /** その位置の実引数の参照（最初に当たったもの）。無ければ {@link #NONE} */
    public int argAt(int ref, int position) {
        for (int k = argBegin(ref), end = argEnd(ref); k < end; k++) {
            if (entryKey[k] == position) {
                return entryVal[k];
            }
        }
        return NONE;
    }

    /** 実引数の数（{@code n=}）。無ければ -1 */
    public int argCount(int ref) {
        int k = tailEntry(ref, ENTRY_COUNT);
        return (k < 0) ? -1 : entryVal[k];
    }

    /** レシーバ（{@code r=}）の参照。無ければ {@link #NONE} */
    public int receiver(int ref) {
        int k = tailEntry(ref, ENTRY_RECEIVER);
        return (k < 0) ? NONE : entryVal[k];
    }

    /** ソースに書かれたレシーバの型（{@code s=}）。無ければ null */
    public String staticReceiver(int ref) {
        int k = tailEntry(ref, ENTRY_STATIC);
        return (k < 0) ? null : strings.get(entryVal[k]);
    }

    /** 後ろに並ぶ項目（n= r= s=）のうち、その鍵のものの番号。無ければ -1 */
    private int tailEntry(int ref, short key) {
        if (ref < 0) {
            return -1;
        }
        int begin = entryOff[ref];
        for (int k = entryOff[ref + 1] - 1; k >= begin && entryKey[k] < 0; k--) {
            if (entryKey[k] == key) {
                return k;
            }
        }
        return -1;
    }

    // --- 検査・計測用（パッケージの中だけ） ---

    /** 項目の総数 */
    int entryCount() {
        return entryKey.length;
    }

    /** そのノードの項目の範囲の始まり（n= r= s= を含む） */
    int entryBegin(int ref) {
        return entryOff[ref];
    }

    /** そのノードの項目の範囲の終わり（n= r= s= を含む） */
    int entryEnd(int ref) {
        return entryOff[ref + 1];
    }

    /** 項目の鍵（0 以上は位置、負は {@link #ENTRY_COUNT} など） */
    short entryKey(int k) {
        return entryKey[k];
    }

    /** 項目の値 */
    int entryValue(int k) {
        return entryVal[k];
    }

    /** 列が使っているおおよそのバイト数（文字列の置き場は含まない。配列の中身だけ） */
    long columnBytes() {
        return (long) kind.length + 4L * value.length + 4L * entryOff.length
                + 2L * entryKey.length + 4L * entryVal.length;
    }
}
