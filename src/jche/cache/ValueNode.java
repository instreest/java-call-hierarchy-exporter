// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cache;

/**
 * 値グラフのノード1件（dataflow-cache.tsv の N 行）。「この式の値はどこから来たか」を1つ表す。
 *
 * <h2>{@link Origin}（analysis 側の出所）との違い</h2>
 * {@link Origin} は入れ子を1本の文字列に展開する。読むのは簡単だが、深さ d・引数 k で
 * 文字列が k^d に膨らむため、「実引数は1段」「レシーバは3段」という上限が構造的に必要だった。
 * こちらは<b>1つの式を1ノードとして1回だけ書き、参照はノード番号で行う</b>ので、
 * 大きさがソースの式の数に比例する（深さは関係しない）。だから上限を設けなくて済む。
 *
 * <h2>番号はブロック内ローカル</h2>
 * {@link #id} は 1 ファイル（キャッシュの1ブロック）の中だけで通じる 0 始まりの連番で、
 * AST を辿った順に振る。ブロックをまるごと書き写す差分更新（{@code CacheUpdater} のパス5b）で
 * 参照が壊れないよう、ブロックの外を指すことは無い。同じソースなら同じ番号になる（決定的）。
 *
 * <h2>値の長さに上限が無い</h2>
 * {@link #value} は SQL やログ文言のような長い文字列もそのまま持つ。
 * 行形式を壊す文字は {@link CacheFormat#escape} で符号化して書く。
 *
 * @param id       ブロック内の番号（0 始まり）
 * @param kind     種別。{@link Origin} の種別と同じ文字を使う（{@link Origin#NEW} 等）
 * @param value    種別ごとの値。型のFQN、メソッドキー、引数位置、文字列そのもの、など
 * @param recv     レシーバのノード番号。無ければ {@link #NONE}
 * @param args     実引数のノード番号（{@code 位置=番号} をカンマ区切り）。無ければ空文字
 * @param argCount 実引数の数。分からなければ -1（メソッド参照など、呼び出しの形になっていない場合）
 * @param staticRecv メソッド呼び出しを<b>ソースに書いたときのレシーバの型</b>（FQN）。
 *                   宣言元と同じか、分からなければ空文字。
 *                   {@code DaoFactory.get(...)} の {@code get} が親の {@code BaseFactory} で
 *                   宣言されていると、{@link #value} のメソッドキーは親になる。契約表や拡張で
 *                   「ソースに書いてある型」を指定できるように、書かれた型も持っておく
 */
public record ValueNode(int id, char kind, String value, int recv, String args, int argCount,
                        String staticRecv) {

    /** レシーバが無い・参照先が無いことを表す番号 */
    public static final int NONE = -1;

    /** 実引数の区切り（{@code 0=3,1=7}） */
    public static final char ARG_SEP = ',';

    public String toRow() {
        return CacheFormat.joinRow("N", String.valueOf(id), String.valueOf(kind),
                CacheFormat.escape(value), String.valueOf(recv), args, String.valueOf(argCount),
                CacheFormat.escape(staticRecv));
    }

    /** 列が足りなければ null */
    public static ValueNode fromRow(String[] cols) {
        if (cols.length < 6) {
            return null;
        }
        String kind = CacheFormat.columnAt(cols, 2);
        if (kind.isEmpty()) {
            return null;
        }
        return new ValueNode(intOf(CacheFormat.columnAt(cols, 1), -1), kind.charAt(0),
                CacheFormat.unescape(CacheFormat.columnAt(cols, 3)),
                intOf(CacheFormat.columnAt(cols, 4), NONE),
                CacheFormat.columnAt(cols, 5),
                intOf(CacheFormat.columnAt(cols, 6), -1),
                CacheFormat.unescape(CacheFormat.columnAt(cols, 7)));
    }

    /** 数字でなければ {@code fallback}。行から読む値はすべてここを通す */
    public static int intOf(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ignore) {
            return fallback;
        }
    }

    /**
     * 同じ構造のノードを 1 つにまとめるための鍵。
     * ファイルの中で同じ式が何度も現れても、ノードは1つで済む
     */
    public String dedupeKey() {
        return kind + "\u0000" + value + "\u0000" + recv + "\u0000" + args + "\u0000" + argCount
                + "\u0000" + staticRecv;
    }
}
