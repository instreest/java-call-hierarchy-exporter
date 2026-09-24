// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cache;

import jche.util.Names;

/**
 * 型解決（バインディング）に失敗した呼び出しの記録（U行）。
 * 黙って読み飛ばすと「静かに漏れる」ため、必ず記録して call-hierarchy.csv に出力する。
 *
 * 記録するのは事実だけ。理由はコードで持ち、文言は読み手が付ける。
 * <pre>
 *   U  line  呼び出し元の記号  expr  reason  candidate  recvKind  lambdaDepth  recv  args  recvKey  guard
 * </pre>
 * 呼び出し元の記号はブロックの記号表（{@link SymbolTable}）の番号で、特定できなければ
 * {@link SymbolTable#NO_SYMBOL}。末尾の 4 列はこの呼び出し箇所の値（{@link CallSiteValues}）。
 * import から推定した候補も「候補」として持つだけで、エッジにするかは読み手が決める。
 *
 * @param line        呼び出し箇所の行
 * @param caller      呼び出し元。特定できなければ null
 * @param expression  ソースに書かれていた式（メソッド名）
 * @param reason      理由コード（{@link #BINDING_FAILED} / {@link #OUTSIDE_METHOD}）
 * @param candidate   レシーバの単純名と一致する単一型 import のFQN（テキストからの推定）。無ければ空
 * @param recvKind    {@link CallEdgeFact#recvKind()} と同じ
 * @param lambdaDepth {@link CallEdgeFact#lambdaDepth()} と同じ
 *
 * <p>出所・識別キー・囲む条件分岐は {@link CallEdgeFact} と同じく {@link CallSiteValues} が持ち、
 * 同じ行の末尾に書く。
 */
public record UnresolvedCallFact(int line, MethodRef caller, String expression, String reason,
                                 String candidate, char recvKind,
                                 int lambdaDepth) implements CallSite {

    /** 呼び出し先の型解決に失敗した */
    public static final String BINDING_FAILED = "BINDING_FAILED";
    /** 呼び出し元（囲みメソッド・型）を特定できない */
    public static final String OUTSIDE_METHOD = "OUTSIDE_METHOD";

    public UnresolvedCallFact {
        candidate = (candidate == null) ? "" : candidate;
    }

    @Override
    public String toRow(SymbolTable symbols, CallSiteValues values) {
        String[] v = values.toColumns();
        return CacheFormat.joinRow("U", String.valueOf(line), symbols.columnOf(caller),
                expression, reason, candidate,
                String.valueOf(recvKind), String.valueOf(lambdaDepth), v[0], v[1], v[2], v[3]);
    }

    /**
     * 候補付きで呼び出し元も分かるか（＝読み手がエッジにできるU行か）。
     * 候補の無い呼び出しだけを「型解決できなかった件数」に数える（F 行の未解決数も同じ定義）。
     */
    public boolean hasUsableCandidate() {
        return !candidate.isEmpty() && caller != null;
    }

    /** 行の理由コードの列（記号表を引かずに読む）。無ければ空文字 */
    public static String reasonColumn(String[] cols) {
        return CacheFormat.columnAt(cols, 4);
    }

    /**
     * 列が足りない・呼び出し元の記号が引けなければ null（呼び出し元が {@link SymbolTable#NO_SYMBOL} なら
     * 呼び出し元 null の行として読む）。値の列（{@link CallSiteValues#fromRow}）は読まない
     *
     * @param symbols ブロックの記号表（{@link SymbolTable.Reader#array}）
     */
    public static UnresolvedCallFact fromRow(String[] cols, MethodRef[] symbols) {
        return read(cols, symbols, false);
    }

    /**
     * {@link #fromRow} と同じだが、呼び出し元の記号が引けない（壊れた参照）ときも行を捨てず、
     * 呼び出し元 null として読む。列が足りなければ null。
     *
     * <p>型解決できなかった呼び出しを一覧に出す側（{@code UnresolvedReport}）が使う。呼び出し元が
     * 分からなくても、ファイル・行・式・理由は出せるので、呼び出し元の外の U 行（{@link #OUTSIDE_METHOD}）と
     * 同じく「呼び出し元不明」として出す（黙って消さない）。呼び出し元 null の行は
     * {@link #hasUsableCandidate()} が false なので、エッジにはならない
     *
     * @param symbols ブロックの記号表（{@link SymbolTable.Reader#array}）
     */
    public static UnresolvedCallFact fromRowKeepingUnknownCaller(String[] cols, MethodRef[] symbols) {
        return read(cols, symbols, true);
    }

    private static UnresolvedCallFact read(String[] cols, MethodRef[] symbols, boolean keepUnknownCaller) {
        if (cols.length < 5) {
            return null;
        }
        MethodRef caller = null;
        if (!SymbolTable.isNone(cols[2])) {
            caller = SymbolTable.resolve(symbols, cols[2]);
            if (caller == null && !keepUnknownCaller) {
                return null;
            }
        }
        return new UnresolvedCallFact(Names.parseIntOr(cols[1], -1),
                caller, cols[3], cols[4], CacheFormat.columnAt(cols, 5),
                RecvKind.parse(CacheFormat.columnAt(cols, 6)),
                Names.parseIntOr(CacheFormat.columnAt(cols, 7), 0));
    }
}
