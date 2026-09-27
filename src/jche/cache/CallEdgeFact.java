// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cache;

import jche.util.Names;

/**
 * 呼び出し関係の1本の辺（C行）。
 *
 * <pre>
 *   C  呼び出し元の記号  呼び出し先の記号  callLine  calleeMods  recvKind  lambdaDepth  qualifier
 *      recv  args  guard  hints
 * </pre>
 * 記号はブロックの記号表（{@link SymbolTable}）の番号。末尾の 4 列はこの呼び出し箇所の値
 * （{@link CallSiteValues.Row}）。
 *
 * @param caller      呼び出し元
 * @param callee      呼び出し先（バインディングの宣言側）
 * @param callLine    呼び出し箇所の行番号（呼び出し元ソースのどの行で呼んでいるか）
 * @param calleeMods  呼び出し先の修飾子（事実）。静的束縛かどうかの判定は読み手が行う
 *                    （jche.graph.BindKind）。finalclass / super も含みうる
 * @param recvKind    レシーバの由来（{@link RecvKind}）。CHAで絞れなかった理由の説明に使う。
 *                    構文上の分類であって値ではない
 * @param lambdaDepth 呼び出し箇所を囲む、合成メソッドに<b>できなかった</b>ラムダ式の深さ。
 *                    合成メソッドにしたラムダの本体の中は 0（本体が自分のメソッドになるため）
 * @param qualifier   呼び出しを修飾する型（JLS 13.1 の qualifying class or interface。消去した型の名前）。
 *                    呼び出し先を宣言した型と同じなら空。{@code Plain p; p.greet()} で {@code greet} が
 *                    親の {@code Greeter} で宣言されているとき {@code Plain}。実行時に動くのは
 *                    この型の部分型の実装に限られる（JLS 15.12.4.4）ので、読み手は CHA の候補をここから引く。
 *                    式の静的な型という構文上の事実（v29）
 *
 * <p>レシーバと実引数の出所・囲む条件分岐・new の証拠は<b>値</b>なので、この record には無い。
 * {@link CallSiteValues} が持ち、同じ行の末尾に書く（{@link #toRow}）。
 */
public record CallEdgeFact(MethodRef caller, MethodRef callee, int callLine, String calleeMods,
                           char recvKind, int lambdaDepth, String qualifier) implements CallSite {

    public CallEdgeFact {
        calleeMods = (calleeMods == null) ? "" : calleeMods;
        qualifier = (qualifier == null) ? "" : qualifier;
    }

    @Override
    public String toRow(SymbolTable symbols, CallSiteValues.Row values) {
        // 呼び出し元 → 呼び出し先の順に番号を振る（読み手が intern する順と同じ）
        String callerSym = symbols.columnOf(caller);
        String calleeSym = symbols.columnOf(callee);
        String[] v = values.toColumns();
        return CacheFormat.joinRow("C", callerSym, calleeSym,
                String.valueOf(callLine), calleeMods, String.valueOf(recvKind),
                String.valueOf(lambdaDepth), qualifier, v[0], v[1], v[2], v[3]);
    }

    /**
     * 列が足りない・記号が引けなければ null。値の列（{@link CallSiteValues.Row#fromRow}）は読まない
     *
     * @param symbols ブロックの記号表（{@link SymbolTable.Reader#array}）
     */
    public static CallEdgeFact fromRow(String[] cols, MethodRef[] symbols) {
        if (cols.length < 4) {
            return null;
        }
        MethodRef caller = SymbolTable.resolve(symbols, cols[1]);
        MethodRef callee = SymbolTable.resolve(symbols, cols[2]);
        if (caller == null || callee == null) {
            return null;
        }
        int callLine;
        try {
            callLine = Integer.parseInt(cols[3]);
        } catch (NumberFormatException ignore) {
            callLine = -1;
        }
        return new CallEdgeFact(caller, callee, callLine, CacheFormat.columnAt(cols, 4),
                RecvKind.parse(CacheFormat.columnAt(cols, 5)),
                Names.parseIntOr(CacheFormat.columnAt(cols, 6), 0), CacheFormat.columnAt(cols, 7));
    }
}
