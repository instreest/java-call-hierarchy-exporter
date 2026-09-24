// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cache;

/**
 * 呼び出し箇所を囲む条件分岐（ガード）。ブロックの G 行の表に置き、C行・U行が番号で指す「事実」。
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
 * 条件（アトム。{@link Atom}）を並べたもの。全て成立して初めて呼び出しに到達する（論理積）。
 * 1つのアトムは 4 項目。
 * <pre>
 *   op      判定の種別（{@link #EQ} / {@link #NE} / {@link #IN} / {@link #NOT_IN}。
 *           条件の調査（conditions.target）ではこれに加えて {@link #UNKNOWN} / {@link #MORE}）
 *   subject 判定される式の値グラフのノード（{@link ValueNode}。種別 A:引数位置 か V:定数 だけ）
 *   values  比較する値。EQ / NE は 1 つ、IN / NOT_IN は 1 つ以上。定数の値そのもの（切り詰めない）
 *   text    ソースに書かれていた条件式（注記に出すためだけの文字列。判定には使わない）
 * </pre>
 *
 * <h3>キャッシュでは G 行の表（ブロックごと）</h3>
 * ブロックの N 行の直後に、アトム 1 つにつき 1 行（{@link Atom#toRow}）を置く。
 * <pre>
 *   G  ガード番号  op  subject（ノード番号）  text  値1  値2 …
 * </pre>
 * ガード番号はブロック内の 0 始まりで、C 行・U 行が初めて使った順に振る（同じアトムの並びは同じ番号）。
 * 1 つのガードのアトムは、番号が同じ行としてアトムの順に続けて並ぶ。C 行・U 行は番号で指す（無ければ -1）。
 * 同じ分岐の中の呼び出しは同じガードを持つので、呼び出しごとに条件を書き並べずに済む。
 * 値は後ろの列に 1 つずつ置くので、値の中の文字（{@code |} など）で区切りが崩れることはない。
 *
 * <h3>読み手（{@code jche.graph.GuardTable}）</h3>
 * 読み手は G 行を条件の表（{@code jche.graph.GuardTable}）に写し、アトムの種別・判定される式（値の表の葉）・
 * 値を列で引く（{@code jche.graph.CallGraphBuilder}）。値を 1 つの文字列につないで読み戻すことはしないので、
 * 値が {@code |} などを含んでも切れない（以前は制御文字で区切った 1 つの文字列を読み手に渡していた。
 * {@code docs/cache-unification-qa.md} の「読み手が値の表を読む」）。
 *
 * <h2>安全側の方針</h2>
 * 判定できる形（引数・定数と、定数との比較）だけをアトムにする。分からない条件は
 * <b>アトムにしない</b>ので、「条件が分からないから呼ばれない」と誤って結論することはない。
 * 逆に、成立しないと言い切れる条件だけが階層の打ち切りに使われる。
 */
public final class Guard {

    /**
     * 値を 1 つの項目に並べるときの区切り（{@link #values}）。値は {@link #clean} を通すので、区切りと
     * 取り違えない。条件の一覧（{@code jche.analysis.CallConditionScanner}）の EQ / NE の表記と、手で書き換えた
     * キャッシュの値の並びをそろえるとき（{@code jche.graph.GuardTableBuilder}）に使う
     */
    public static final char VALUE_SEP = '\u0003';

    /** 値が一致すること */
    public static final String EQ = "EQ";
    /** 値が一致しないこと */
    public static final String NE = "NE";
    /** いずれかの値と一致すること（switch の case） */
    public static final String IN = "IN";
    /** どの値とも一致しないこと（switch の default） */
    public static final String NOT_IN = "NI";
    /**
     * 判定できない条件（条件があることだけが分かっている）。
     *
     * 打ち切りの判定には使えないので、キャッシュ（G 行）には入れない。
     * 「この呼び出しに効いている条件を漏れなく見たい」条件の調査
     * （設定ファイルの conditions.target。jche.analysis.CallConditionScanner）だけがこの種別を作る。
     * 読み手は知らない種別として読み飛ばすので、混ざっても打ち切りの結論は変わらない。
     */
    public static final String UNKNOWN = "UK";
    /** 上限に達して記録しきれなかった条件がまだあることの印（{@link #UNKNOWN} と同じく判定には使わない） */
    public static final String MORE = "MORE";

    /** 条件式のテキストの上限（注記に出すだけなので長さを抑える） */
    public static final int MAX_TEXT = 60;

    private Guard() {
    }

    /**
     * アトム 1 つ（書き手がメモリ上で持つ形。キャッシュでは G 行 1 行）。
     *
     * @param op      判定の種別（{@link #EQ} など）
     * @param subject 判定される式の値グラフのノード番号（{@link ValueNode}。A か V の種別）。
     *                条件の調査だけが作る {@link #UNKNOWN} / {@link #MORE} では {@link ValueNode#NONE}
     * @param values  比較する値（定数の値そのもの）。{@link #UNKNOWN} / {@link #MORE} では空
     * @param text    ソースに書かれていた条件式（注記用）
     */
    public record Atom(String op, int subject, java.util.List<String> values, String text) {

        public Atom {
            values = java.util.List.copyOf(values);
            text = (text == null) ? "" : text;
        }

        /** {@code G ガード番号 op subject text 値…}（符号化は {@link CacheFormat#joinRow} が行う） */
        public String toRow(int guardId) {
            String[] cols = new String[5 + values.size()];
            cols[0] = String.valueOf(CacheFormat.ROW_GUARD);
            cols[1] = String.valueOf(guardId);
            cols[2] = op;
            cols[3] = String.valueOf(subject);
            cols[4] = text;
            for (int i = 0; i < values.size(); i++) {
                cols[5 + i] = values.get(i);
            }
            return CacheFormat.joinRow(cols);
        }

        /** G 行のガード番号。読めなければ -1 */
        public static int guardIdOf(String[] cols) {
            return (cols.length < 2) ? -1 : ValueNode.intOf(cols[1], -1);
        }

        /** G 行のアトム。列が足りなければ null。列は {@link CacheFormat#columnsOf} で符号化を戻したもの */
        public static Atom fromRow(String[] cols) {
            if (cols.length < 5) {
                return null;
            }
            java.util.List<String> values = new java.util.ArrayList<>(cols.length - 5);
            for (int i = 5; i < cols.length; i++) {
                values.add(cols[i]);
            }
            return new Atom(cols[2], ValueNode.intOf(cols[3], ValueNode.NONE), values, cols[4]);
        }
    }

    /** 値を {@link #VALUE_SEP} で並べて 1 つの項目にする（それぞれ {@link #clean} を通す） */
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

    /** 制御文字（区切り文字とタブ・改行）を空白にする（注記・CSV の 1 セルに収め、{@link #VALUE_SEP} と取り違えないため） */
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
