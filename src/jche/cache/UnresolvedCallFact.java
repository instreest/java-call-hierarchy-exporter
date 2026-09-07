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
 * 型解決（バインディング）に失敗した呼び出しの記録（U行）。
 * 黙って読み飛ばすと「静かに漏れる」ため、必ず記録して call-hierarchy.csv に出力する。
 *
 * 記録するのは事実だけ。理由はコードで持ち、文言は読み手が付ける。
 * import から推定した候補も「候補」として持つだけで、エッジにするかは読み手が決める。
 *
 * @param line        呼び出し箇所の行
 * @param caller      呼び出し元。特定できなければ null
 * @param expression  ソースに書かれていた式（メソッド名）
 * @param reason      理由コード（{@link #BINDING_FAILED} / {@link #OUTSIDE_METHOD}）
 * @param candidate   レシーバの単純名と一致する単一型 import のFQN（テキストからの推定）。無ければ空
 * @param recvKey     {@link CallEdgeFact#recvKey()} と同じ
 * @param recvKind    {@link CallEdgeFact#recvKind()} と同じ
 * @param recvOrigin  {@link CallEdgeFact#recvOrigin()} と同じ
 * @param argOrigins  {@link CallEdgeFact#argOrigins()} と同じ
 * @param lambdaDepth {@link CallEdgeFact#lambdaDepth()} と同じ
 */
public record UnresolvedCallFact(int line, MethodRef caller, String expression, String reason,
                                 String candidate, String recvKey, char recvKind,
                                 String recvOrigin, String argOrigins, int lambdaDepth)
        implements CallSite {

    /** 呼び出し先の型解決に失敗した */
    public static final String BINDING_FAILED = "BINDING_FAILED";
    /** 呼び出し元（囲みメソッド・型）を特定できない */
    public static final String OUTSIDE_METHOD = "OUTSIDE_METHOD";

    public UnresolvedCallFact {
        candidate = (candidate == null) ? "" : candidate;
        recvKey = (recvKey == null) ? "" : recvKey;
        recvOrigin = (recvOrigin == null) ? "" : recvOrigin;
        argOrigins = (argOrigins == null) ? "" : argOrigins;
    }

    @Override
    public String toRow() {
        String[] c = (caller == null) ? MethodRef.emptyColumns() : caller.toColumns();
        return CacheFormat.joinRow("U", String.valueOf(line), c[0], c[1], c[2], c[3],
                CacheFormat.clean(expression), reason, candidate,
                recvKey, String.valueOf(recvKind), recvOrigin, argOrigins,
                String.valueOf(lambdaDepth));
    }

    /**
     * 候補付きで呼び出し元も分かるか（＝読み手がエッジにできるU行か）。
     * 候補の無い呼び出しだけを「型解決できなかった件数」に数える。
     */
    public boolean hasUsableCandidate() {
        return !candidate.isEmpty() && caller != null;
    }

    /** 列が足りなければ null */
    public static UnresolvedCallFact fromRow(String[] cols) {
        if (cols.length < 8) {
            return null;
        }
        return new UnresolvedCallFact(CallEdgeFact.parseIntOr(cols[1], -1),
                MethodRef.fromColumns(cols, 2), cols[6], cols[7], CacheFormat.columnAt(cols, 8),
                CacheFormat.columnAt(cols, 9), RecvKind.parse(CacheFormat.columnAt(cols, 10)),
                CacheFormat.columnAt(cols, 11), CacheFormat.columnAt(cols, 12),
                CallEdgeFact.parseIntOr(CacheFormat.columnAt(cols, 13), 0));
    }
}
