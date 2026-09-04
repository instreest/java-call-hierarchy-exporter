/*
 * Copyright 2026 the java-call-hierarchy-exporter authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jche.cache;

/**
 * 呼び出し関係の1本の辺（C行）。
 *
 * @param caller      呼び出し元
 * @param callee      呼び出し先（バインディングの宣言側）
 * @param callLine    呼び出し箇所の行番号（呼び出し元ソースのどの行で呼んでいるか）
 * @param calleeMods  呼び出し先の修飾子（事実）。静的束縛かどうかの判定は読み手が行う
 *                    （jche.graph.BindKind）。finalclass / super も含みうる
 * @param recvKey     レシーバの識別キー（ローカル変数のバインディングキー、または "@位置"）。無ければ空
 * @param recvKind    レシーバの由来（{@link RecvKind}）。CHAで絞れなかった理由の説明に使う
 * @param recvOrigin  レシーバの出所（{@link Origin}）。データフローで具象型を追うのに使う。無ければ空
 * @param argOrigins  実引数の出所。"位置=出所" を ; で並べたもの。無ければ空
 * @param lambdaDepth 呼び出し箇所を囲むラムダ式の深さ。0 ならラムダの外
 */
public record CallEdgeFact(MethodRef caller, MethodRef callee, int callLine, String calleeMods,
                           String recvKey, char recvKind, String recvOrigin, String argOrigins,
                           int lambdaDepth) implements CallSite {

    public CallEdgeFact {
        calleeMods = (calleeMods == null) ? "" : calleeMods;
        recvKey = (recvKey == null) ? "" : recvKey;
        recvOrigin = (recvOrigin == null) ? "" : recvOrigin;
        argOrigins = (argOrigins == null) ? "" : argOrigins;
    }

    @Override
    public String toRow() {
        String[] c = caller.toColumns();
        String[] t = callee.toColumns();
        return CacheFormat.joinRow("C", c[0], c[1], c[2], c[3], t[0], t[1], t[2], t[3],
                String.valueOf(callLine), calleeMods, recvKey, String.valueOf(recvKind),
                recvOrigin, argOrigins, String.valueOf(lambdaDepth));
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
                CacheFormat.columnAt(cols, 11), RecvKind.parse(CacheFormat.columnAt(cols, 12)),
                CacheFormat.columnAt(cols, 13), CacheFormat.columnAt(cols, 14),
                parseIntOr(CacheFormat.columnAt(cols, 15), 0));
    }

    static int parseIntOr(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ignore) {
            return fallback;
        }
    }
}
