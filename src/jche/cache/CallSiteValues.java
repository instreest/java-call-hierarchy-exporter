// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cache;

/**
 * 1つの呼び出し箇所の値。C 行・U 行の末尾の 4 列（{@link CacheFormat#CALL_VALUES_COLUMN} から）。
 * その呼び出しのレシーバと実引数が、値グラフ（{@link ValueNode}）のどのノードかを指す。
 *
 * <p>値は呼び出し箇所の行そのものに持つので、行と値を結びつける鍵は要らない。
 * 書き手は {@link FileAnalysis#callSites} と {@link FileAnalysis#callSiteValues} を
 * 同じ位置どうしで組にして 1 行にする（2 つは同じ数・同じ順で並ぶ）。
 *
 * <p>C 行は呼び出し元が複数（インスタンス初期化子など）あると呼び出し元ごとに1本できる。
 * 値もその数だけ作り、1 対 1 にしている。
 *
 * @param recv       レシーバのノード番号。無ければ {@link ValueNode#NONE}
 * @param args       実引数のノード番号（{@code 位置=番号} をカンマ区切り）。無ければ空文字
 * @param recvKey    レシーバの識別キー（ローカル変数のバインディングキー、または "@位置"）。無ければ空。
 *                   同じメソッドの中で new された型の証拠（X 行）を引くのに使う
 * @param guard      呼び出し箇所を囲む条件分岐（{@link Guard}）。無ければ空
 */
public record CallSiteValues(int recv, String args, String recvKey, String guard) {

    public CallSiteValues {
        args = (args == null) ? "" : args;
        recvKey = (recvKey == null) ? "" : recvKey;
        guard = (guard == null) ? "" : guard;
    }

    /** 値を持たない（呼び出し箇所に対応する値が無い）ときの既定 */
    public static final CallSiteValues NONE = new CallSiteValues(ValueNode.NONE, "", "", "");

    /** 行の末尾に足す 4 列（recv・args・recvKey・guard）。生の値のまま返す（符号化は joinRow が行う） */
    String[] toColumns() {
        return new String[] {String.valueOf(recv), args, recvKey, guard};
    }

    /**
     * C 行・U 行の {@link CacheFormat#CALL_VALUES_COLUMN} 以降から読む。列が無ければ {@link #NONE}
     *
     * @param cols 行の列（{@link CacheFormat#columnsOf} で戻したもの）
     */
    public static CallSiteValues fromRow(String[] cols) {
        int from = CacheFormat.CALL_VALUES_COLUMN;
        if (cols.length <= from) {
            return NONE;
        }
        return new CallSiteValues(ValueNode.intOf(cols[from], ValueNode.NONE),
                CacheFormat.columnAt(cols, from + 1),
                CacheFormat.columnAt(cols, from + 2),
                CacheFormat.columnAt(cols, from + 3));
    }
}
