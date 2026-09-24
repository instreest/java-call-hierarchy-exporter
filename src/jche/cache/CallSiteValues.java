// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cache;

import java.util.List;

/**
 * 1つの呼び出し箇所の値（書き手がメモリ上で持つ形）。
 * その呼び出しのレシーバと実引数が、値グラフ（{@link ValueNode}）のどのノードかを指し、
 * 呼び出しを囲む条件分岐をアトム（{@link Guard.Atom}）で持つ。
 *
 * <p>キャッシュでは C 行・U 行の末尾の 4 列（{@link Row}。{@link CacheFormat#CALL_VALUES_COLUMN} から）になる。
 * 条件は行に書き並べず、ブロックの G 行の表（{@link Guard.Atom#toRow}）の番号で指す。
 * レシーバの識別キー（{@link #recvKey}）は書かない。new の証拠を呼び出し箇所に結びつけるためだけに
 * メモリ上で持ち、書き手が結びつけた結果（{@link Row#hints}）を書く。
 *
 * <p>書き手は {@link FileAnalysis#callSites} と {@link FileAnalysis#callSiteValues} を
 * 同じ位置どうしで組にして 1 行にする（2 つは同じ数・同じ順で並ぶ）。
 * C 行は呼び出し元が複数（インスタンス初期化子など）あると呼び出し元ごとに1本できる。
 * 値もその数だけ作り、1 対 1 にしている。
 *
 * @param recv    レシーバのノード番号。無ければ {@link ValueNode#NONE}
 * @param args    実引数のノード番号（{@code 位置=番号} をカンマ区切り）。無ければ空文字
 * @param recvKey レシーバのローカル変数のキー（{@code jche.analysis.HintKeys}）。無ければ空。
 *                同じメソッドの中で new された型の証拠（{@link FileAnalysis#hints}）を引くのに使う
 * @param guard   呼び出し箇所を囲む条件分岐のアトム（論理積）。無ければ空
 */
public record CallSiteValues(int recv, String args, String recvKey, List<Guard.Atom> guard) {

    public CallSiteValues {
        args = (args == null) ? "" : args;
        recvKey = (recvKey == null) ? "" : recvKey;
        guard = (guard == null) ? List.of() : List.copyOf(guard);
    }

    /** 値を持たない（呼び出し箇所に対応する値が無い）ときの既定 */
    public static final CallSiteValues NONE = new CallSiteValues(ValueNode.NONE, "", "", List.of());

    /**
     * C 行・U 行の末尾の 4 列（書いた形・読む形）。
     *
     * <pre>
     *   recv  args  guard  hints
     * </pre>
     *
     * @param recv  レシーバのノード番号。無ければ {@link ValueNode#NONE}
     * @param args  実引数のノード番号（{@code 位置=番号} をカンマ区切り）。無ければ空文字
     * @param guard 呼び出しを囲む条件分岐。ブロックの G 行のガード番号。無ければ -1
     * @param hints レシーバの変数に代入された値が<b>すべて new</b> のとき、その型の FQN のカンマ区切り
     *              （{@code jche.graph.CallResolver} の段 2）。無ければ空。書き手が同じファイルの中で、
     *              呼び出し元とレシーバの変数を結びつけて求める
     */
    public record Row(int recv, String args, int guard, String hints) {

        public Row {
            args = (args == null) ? "" : args;
            hints = (hints == null) ? "" : hints;
        }

        /** 値を持たない行（値を読まない指定のときも、読み手はこれを使う） */
        public static final Row NONE = new Row(ValueNode.NONE, "", -1, "");

        /** 行の末尾に足す 4 列（recv・args・guard・hints）。生の値のまま返す（符号化は joinRow が行う） */
        String[] toColumns() {
            return new String[] {String.valueOf(recv), args, String.valueOf(guard), hints};
        }

        /**
         * C 行・U 行の {@link CacheFormat#CALL_VALUES_COLUMN} 以降から読む。列が無ければ {@link #NONE}
         *
         * @param cols 行の列（{@link CacheFormat#columnsOf} で戻したもの）
         */
        public static Row fromRow(String[] cols) {
            int from = CacheFormat.CALL_VALUES_COLUMN;
            if (cols.length <= from) {
                return NONE;
            }
            return new Row(ValueNode.intOf(cols[from], ValueNode.NONE),
                    CacheFormat.columnAt(cols, from + 1),
                    ValueNode.intOf(CacheFormat.columnAt(cols, from + 2), -1),
                    CacheFormat.columnAt(cols, from + 3));
        }
    }
}
