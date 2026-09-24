// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cache;

import jche.util.Names;

/**
 * 呼び出し関係の1本の辺（C行）。
 *
 * @param caller      呼び出し元
 * @param callee      呼び出し先（バインディングの宣言側）
 * @param callLine    呼び出し箇所の行番号（呼び出し元ソースのどの行で呼んでいるか）
 * @param calleeMods  呼び出し先の修飾子（事実）。静的束縛かどうかの判定は読み手が行う
 *                    （jche.graph.BindKind）。finalclass / super も含みうる
 * @param recvKind    レシーバの由来（{@link RecvKind}）。CHAで絞れなかった理由の説明に使う。
 *                    構文上の分類であって値ではないので、こちら（analysis 側）に残す
 * @param lambdaDepth 呼び出し箇所を囲む、合成メソッドに<b>できなかった</b>ラムダ式の深さ。
 *                    合成メソッドにしたラムダの本体の中は 0（本体が自分のメソッドになるため）
 * @param qualifier   呼び出しを修飾する型（JLS 13.1 の qualifying class or interface。消去した型の名前）。
 *                    呼び出し先を宣言した型と同じなら空。{@code Plain p; p.greet()} で {@code greet} が
 *                    親の {@code Greeter} で宣言されているとき {@code Plain}。実行時に動くのは
 *                    この型の部分型の実装に限られる（JLS 15.12.4.4）ので、読み手は CHA の候補をここから引く。
 *                    式の静的な型という構文上の事実なので、こちら（analysis 側）に置く（v29）
 *
 * <p>レシーバと実引数の出所・識別キー・囲む条件分岐は<b>値</b>なので、この行には無い。
 * dataflow 側の P 行（{@link CallSiteValues}）が持ち、読み手がブロック単位で突き合わせる
 * （{@code docs/cache-split-qa.md} の Q11・Q20）。
 */
public record CallEdgeFact(MethodRef caller, MethodRef callee, int callLine, String calleeMods,
                           char recvKind, int lambdaDepth, String qualifier) implements CallSite {

    public CallEdgeFact {
        calleeMods = (calleeMods == null) ? "" : calleeMods;
        qualifier = (qualifier == null) ? "" : qualifier;
    }

    @Override
    public String toRow() {
        String[] c = caller.toColumns();
        String[] t = callee.toColumns();
        return CacheFormat.joinRow("C", c[0], c[1], c[2], c[3], t[0], t[1], t[2], t[3],
                String.valueOf(callLine), calleeMods, String.valueOf(recvKind),
                String.valueOf(lambdaDepth), qualifier);
    }

    /** 列が足りなければ null */
    public static CallEdgeFact fromRow(String[] cols) {
        if (cols.length < 10) {
            return null;
        }
        MethodRef caller = MethodRef.fromColumns(cols, 1);
        MethodRef callee = MethodRef.fromColumns(cols, 5);
        if (caller == null || callee == null) {
            return null;
        }
        int callLine;
        try {
            callLine = Integer.parseInt(cols[9]);
        } catch (NumberFormatException ignore) {
            callLine = -1;
        }
        return new CallEdgeFact(caller, callee, callLine, CacheFormat.columnAt(cols, 10),
                RecvKind.parse(CacheFormat.columnAt(cols, 11)),
                Names.parseIntOr(CacheFormat.columnAt(cols, 12), 0), CacheFormat.columnAt(cols, 13));
    }
}
