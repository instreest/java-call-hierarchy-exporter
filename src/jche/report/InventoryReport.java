// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.report;

import java.io.BufferedWriter;
import java.io.IOException;

import jche.cache.RecvKind;
import jche.config.Config;
import jche.graph.CallGraph;
import jche.graph.CallResolver;
import jche.graph.MethodTable;
import jche.graph.Resolution;
import jche.graph.SourceOrder;

/**
 * methods.csv の出力。
 *
 * 呼び出し階層（call-hierarchy.csv）は起点からの経路を展開するため
 * 分岐^深さで膨らむが、こちらはメソッド数に比例した線形サイズで収まる。
 * 「どのメソッドが誰からも呼ばれていないか」「どこがハブか」を
 * 俯瞰したいときはこちらを見る。
 */
public final class InventoryReport {

    /** methods.csv の集計 */
    public static final class Stats {
        long methods;
        long entryCandidates;
        long isolated;
        long leaves;
        long unreachable;
        long notInHierarchy;
        long prunedOut;
        long constructors;
        long withUnresolved;

        /** 条件分岐の打ち切りが理由で階層CSVに出なかったメソッドの数 */
        public long prunedOut() {
            return prunedOut;
        }

        @Override
        public String toString() {
            return "メソッド=" + methods
                    + " 起点候補=" + entryCandidates
                    + " 孤立=" + isolated
                    + " 末端=" + leaves
                    + " 未到達=" + unreachable
                    + " 階層CSVに出ない=" + notInHierarchy
                    + "（うち条件分岐で打ち切った先=" + prunedOut + "）"
                    + " 未解決の呼び出しを含む=" + withUnresolved
                    + "（コンストラクタ " + constructors + " 個は出力対象外）";
        }
    }

    /** 1メソッド分の「絞れなかった呼び出し」の件数と理由 */
    private record Unresolved(int count, String cause) {
    }

    private InventoryReport() {
    }

    /**
     * methods.csv … ソース上の全メソッドと、その呼び出し状況。
     *
     * role の意味:
     * <pre>
     *   ENTRY_CANDIDATE 呼び出し元が無い。画面入口・バッチ・デッドコード・
     *                   テスト・リフレクション経由が混ざる（要仕分け）
     *   ISOLATED        呼び出し元も呼び出し先も無い。デッドコードの疑いが濃い
     *   LEAF            呼び出し先が無い。末端処理
     *   NORMAL          上記以外
     * </pre>
     * コンストラクタ（{@code <init>}）は出力しない。call-hierarchy.csv 側でも
     * 行にしていないため、両方の一覧で扱いを揃える。
     *
     * method 列は「単純クラス名.メソッド名(引数型略名)」。完全修飾クラス名は
     * declaringType 列にあるため重複させず、引数だけを足してオーバーロードを
     * 見分けられるようにしている（付けないと、行番号以外まったく同じ行が並ぶ）。
     *
     * unresolvedCalls / unresolvedCause は「このメソッドの中に、
     * 具象クラスを1つに絞れなかった呼び出しがいくつあり、その理由は何か」。
     * call-hierarchy.csv の注記と同じ判定を使っているので、
     * まずここで穴のあるメソッドを絞ってから階層を追う、という使い方ができる。
     */
    public static Stats writeMethods(CallGraph g, CallResolver resolver, Config config, int[] roots,
                                     StreamingTreeWalker walker) throws IOException {
        MethodTable methods = g.methods();
        Stats st = new Stats();
        int[] in = resolver.inDegrees();
        boolean[] reachable = resolver.reachableFrom(roots);

        try (BufferedWriter w = Csv.writer(config.methodsCsv, config.outputEncoding, config.outputBom)) {
            w.write(String.join(Csv.DELIM, "method", "declaringType", "typeKind",
                    "file", "line", "hasBody", "inDegree", "outDegree", "role", "reachable",
                    "unresolvedCalls", "unresolvedCause", "inHierarchy", "absentCause"));
            w.newLine();
            // ソースが無いメソッド（jar内など）は一覧の対象外。
            // 呼ばれている事実は call-hierarchy.csv 側に残る。
            // 行順はソースの並び（ソースフォルダ順 → ファイル順 → 宣言行順）
            for (int id : SourceOrder.declaredMethodsInSourceOrder(g)) {
                // コンストラクタは call-hierarchy.csv でも行にしていないので揃える
                if (methods.isConstructor(id)) {
                    st.constructors++;
                    continue;
                }
                st.methods++;
                int out = g.outDegree(id);
                String role;
                if (in[id] == 0 && out == 0) {
                    role = "ISOLATED";
                    st.isolated++;
                } else if (in[id] == 0) {
                    role = "ENTRY_CANDIDATE";
                    st.entryCandidates++;
                } else if (out == 0) {
                    role = "LEAF";
                    st.leaves++;
                } else {
                    role = "NORMAL";
                }
                if (!reachable[id]) {
                    st.unreachable++;
                }
                Unresolved un = unresolvedOf(g, resolver, id);
                if (un.count() > 0) {
                    st.withUnresolved++;
                }
                // 呼び出し階層CSVに1行も出なかったメソッド。打ち切りで階層から
                // 消えた部分木は、ここでしか見えない
                boolean inTree = walker.inHierarchy(id);
                String absent = "";
                if (!inTree) {
                    st.notInHierarchy++;
                    absent = walker.absentCauseOf(id);
                    if (StreamingTreeWalker.PRUNED_SUBTREE_CAUSE.equals(absent)) {
                        st.prunedOut++;
                    }
                }
                w.write(String.join(Csv.DELIM,
                        Csv.esc(methods.shortLabelWithParams(id)),
                        Csv.esc(methods.typeFqn(id)),
                        String.valueOf(g.hierarchy().kindOf(methods.typeFqn(id))),
                        Csv.esc(methods.declFile(id)),
                        String.valueOf(methods.declLine(id)),
                        methods.hasBody(id) ? "1" : "0",
                        String.valueOf(in[id]),
                        String.valueOf(out),
                        role,
                        reachable[id] ? "1" : "0",
                        String.valueOf(un.count()),
                        Csv.esc(un.cause()),
                        inTree ? "1" : "0",
                        Csv.esc(absent)));
                w.newLine();
            }
        }
        return st;
    }

    /**
     * そのメソッドが出している呼び出しのうち、具象クラスを1つに絞れなかったものを数え、
     * レシーバの由来（RecvKind）で理由を並べる。
     *
     * 判定は call-hierarchy.csv の注記と同じ resolve を使う。
     * 結果はメモ化されるので、階層展開と二重に解決コストがかかることはない。
     */
    private static Unresolved unresolvedOf(CallGraph g, CallResolver resolver, int id) {
        int count = 0;
        StringBuilder causes = new StringBuilder();
        for (int e = g.edgeStart(id); e < g.edgeEnd(id); e++) {
            Resolution res = resolver.resolve(e);
            boolean multi = res.isMultiple();
            boolean noImpl = Resolution.NO_IMPL.equals(res.label());
            boolean fnImpl = g.hasFunctionalImpl(g.calleeOf(e));
            if (!multi && !noImpl && !fnImpl) {
                continue;
            }
            count++;
            String cause = noImpl ? "実装なし（宣言のまま）"
                    : fnImpl && !multi ? "ラムダ/メソッド参照の実装あり"
                    : RecvKind.describe(g.recvKindOf(e));
            // 同じ理由は1回だけ並べる。件数はcount側で分かる
            if (causes.indexOf(cause) < 0) {
                if (causes.length() > 0) {
                    causes.append(';');
                }
                causes.append(cause);
            }
        }
        return new Unresolved(count, causes.toString());
    }
}
