// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cache;

/**
 * 呼び出し箇所を囲む条件分岐（ガード）。C行・U行の列として持つ「事実」。
 *
 * <h2>何のためにあるか</h2>
 * 呼び出しがソースに書かれていても、その経路では条件が成立せず実行されないことがある。
 * <pre>
 *     void run(boolean debug) { if (debug) { dump(); } }
 *     ...
 *     run(false);          // ← この経路の run から dump() は呼ばれない
 * </pre>
 * ガードは「この呼び出しに到達するには、どの式がどの値でなければならないか」を
 * 呼び出しごとに記録したもの。実際に成立しないと言い切れるかの判定は、経路ごとの
 * 引数の値が分かる読み手（jche.graph.GuardEvaluator）が行う。
 * <b>キャッシュには事実（条件と、その式の出所）だけを置き、判断は読み手に置く</b>という
 * {@link CacheFormat} の原則どおりの分担。
 *
 * <h2>形式</h2>
 * 条件（アトム）を {@link #ATOM_SEP} で並べたもの。全て成立して初めて呼び出しに到達する
 * （論理積）。1つのアトムは {@link #FIELD_SEP} 区切りの4項目。
 * <pre>
 *   op    判定の種別（{@link #EQ} / {@link #NE} / {@link #IN} / {@link #NOT_IN}）
 *   origin 判定される式の出所（{@link Origin}。A:引数位置 か V:定数 だけ）
 *   value  比較する値。IN / NOT_IN は {@link #VALUE_SEP} 区切りで複数
 *   text   ソースに書かれていた条件式（注記に出すためだけの文字列。判定には使わない）
 * </pre>
 * 区切りに制御文字を使うのは、条件式のテキストに現れうる文字（{@code & | ~ ^ , ; =}）を
 * 避けるため。エスケープを持たずに済み、タブ区切りのキャッシュ形式とも衝突しない。
 *
 * <h2>安全側の方針</h2>
 * 判定できる形（引数・定数と、定数との比較）だけをアトムにする。分からない条件は
 * <b>アトムにしない</b>ので、「条件が分からないから呼ばれない」と誤って結論することはない。
 * 逆に、成立しないと言い切れる条件だけが階層の打ち切りに使われる。
 */
public final class Guard {

    /** アトムの区切り（論理積） */
    public static final char ATOM_SEP = '\u0001';
    /** アトムの中の項目の区切り */
    public static final char FIELD_SEP = '\u0002';
    /** IN / NOT_IN の値の区切り */
    public static final char VALUE_SEP = '\u0003';

    /** 値が一致すること */
    public static final String EQ = "EQ";
    /** 値が一致しないこと */
    public static final String NE = "NE";
    /** いずれかの値と一致すること（switch の case） */
    public static final String IN = "IN";
    /** どの値とも一致しないこと（switch の default） */
    public static final String NOT_IN = "NI";

    /** 条件式のテキストの上限（注記に出すだけなので長さを抑える） */
    public static final int MAX_TEXT = 60;

    private Guard() {
    }

    /** アトム1件を文字列にする（value は {@link #clean} 済み。IN / NOT_IN は {@link #values} で並べる） */
    public static String atom(String op, String origin, String value, String text) {
        return op + FIELD_SEP + origin + FIELD_SEP + value + FIELD_SEP + clean(text);
    }

    /** IN / NOT_IN の値を並べる */
    public static String values(java.util.List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (sb.length() > 0) {
                sb.append(VALUE_SEP);
            }
            sb.append(clean(v));
        }
        return sb.toString();
    }

    /** アトムを並べてガード1件にする */
    public static String join(java.util.List<String> atoms) {
        return String.join(String.valueOf(ATOM_SEP), atoms);
    }

    /** ガードをアトムに分解する。空なら空配列 */
    public static String[] atomsOf(String guard) {
        return (guard == null || guard.isEmpty()) ? new String[0] : guard.split(String.valueOf(ATOM_SEP), -1);
    }

    /** アトムの項目。範囲外なら空文字 */
    public static String fieldOf(String atom, int index) {
        String[] f = atom.split(String.valueOf(FIELD_SEP), -1);
        return (index < f.length) ? f[index] : "";
    }

    public static String[] valuesOf(String field) {
        return field.split(String.valueOf(VALUE_SEP), -1);
    }

    /** 区切り文字とタブ・改行を落とす（エスケープを持たない形式のため） */
    public static String clean(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append((c < ' ') ? ' ' : c);
        }
        return sb.toString();
    }
}
