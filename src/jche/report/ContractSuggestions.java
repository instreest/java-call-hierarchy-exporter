// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.report;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import jche.graph.CallGraph;
import jche.graph.DataflowContext;
import jche.graph.DataflowResolver;
import jche.graph.MethodTable;
import jche.graph.TypeContracts;
import jche.util.Messages;

/**
 * 絞れなかった呼び出し（{@code [UNEXPANDED:CHA]}）から、契約表の種類 C のひな形を作る。
 *
 * <h2>なぜ要るか</h2>
 * 「候補2件: ローカル変数」と言われても、<b>何をどこに書けば絞れるのか</b>は出力からは分からない。
 * 設定の書き方を docs で調べるところから始めることになり、いちばん助けが要る人
 * （CHA で枝分かれして困っている人）が、機能があることにすら気づけない。
 * そのまま貼れる行を出力フォルダに置けば、書式を覚えずに「選んでコメントを外す」だけで済む。
 *
 * <h2>何を出すか</h2>
 * 呼び出し箇所ごとではなく、<b>それを直す契約の行ごと</b>にまとめる。同じ
 * {@code Dao#find} が 100 か所で絞れていないなら、必要な行は 1 行だからである。
 * レシーバがファクトリの戻り値なら、ファクトリとキーを書いた形（C-3）を出す。
 *
 * <pre>
 *   # 3 か所  例) at fxp.App.factoryCall(App.java:17)
 *   #   候補: fxp.AbstractDao / fxp.OrderDaoImpl / fxp.UserDaoImpl
 *   # fxp.Dao#find =&gt; ??
 * </pre>
 *
 * <p>左辺の組み立ては {@link TypeContracts#factoryLeftSidesOf} と同じものを使う。
 * 書ける形が増えたときに、ひな形と実際に引ける形が食い違わないようにするため。
 */
public final class ContractSuggestions {

    /** ひな形として出す行数の上限。多すぎると「選ぶ」作業にならない */
    static final int MAX_LINES = 200;

    /** 右辺に入れる目印。利用者はここを具象型の FQN に置き換える */
    private static final String PLACEHOLDER = "??";

    /** 1 つの契約行に対して集めたもの */
    private static final class Entry {
        /** その呼び出しで CHA が並べた候補（具象型の FQN） */
        private final Set<String> candidates = new TreeSet<>();
        /** 最初に見つかった呼び出し箇所（例として 1 つだけ出す） */
        private String where = "";
        /** その行で直る呼び出し箇所の数 */
        private int sites;
        /** ファクトリのキーが決まらず、型単位の広い行になったか */
        private boolean typeWide;
    }

    private final Map<String, Entry> byLeftSide = new LinkedHashMap<>();
    /** 同じ辺は経路の数だけ現れるので、呼び出し箇所として数えるのは 1 回だけにする */
    private final Set<Integer> countedEdges = new HashSet<>();
    /** 上限に達して足すのをやめたか */
    private boolean capped;

    /**
     * 絞れなかった呼び出しを 1 件足す。
     *
     * @param edgeIndex      その呼び出し（辺）
     * @param callerId       呼び出し元のメソッド（例に出す場所）
     * @param declaredCallee 呼び出し先の宣言（型とシグネチャ）
     * @param targets        CHA が並べた候補
     */
    void add(CallGraph graph, DataflowResolver dataflow, DataflowContext ctx, int edgeIndex,
             int callerId, int declaredCallee, int[] targets) {
        if (!countedEdges.add(edgeIndex)) {
            return;
        }
        MethodTable methods = graph.methods();
        Left left = leftSideFor(graph, dataflow, ctx, edgeIndex, methods, declaredCallee);
        if (left == null) {
            return;
        }
        Entry entry = byLeftSide.get(left.text());
        if (entry == null) {
            if (byLeftSide.size() >= MAX_LINES) {
                capped = true;
                return;
            }
            entry = new Entry();
            entry.typeWide = !left.fromFactory();
            entry.where = CallHierarchyCsvWriter.stackTrace(methods, callerId,
                    graph.callLineOf(edgeIndex));
            byLeftSide.put(left.text(), entry);
        }
        entry.sites++;
        for (int target : targets) {
            entry.candidates.add(methods.typeFqn(target));
        }
    }

    /** 契約の左辺と、それがファクトリとキーの形（C-3）かどうか */
    private record Left(String text, boolean fromFactory) {
    }

    /**
     * その呼び出しを直す契約の左辺。
     *
     * <p>レシーバがファクトリの戻り値なら、ファクトリとキーの形（C-3）を優先する。そちらのほうが
     * 狭く、同じ型を返す他の呼び出しを巻き込まないため。キーが決まらなければ宣言型とメソッド名
     * （C-2）に落ちるが、それは<b>その型のそのメソッドを全部同じ実装に決める</b>広い行なので、
     * ひな形でもそうと分かるようにする。
     */
    private static Left leftSideFor(CallGraph graph, DataflowResolver dataflow, DataflowContext ctx,
                                    int edgeIndex, MethodTable methods, int declaredCallee) {
        if (dataflow.enabled()) {
            List<String> factories =
                    TypeContracts.factoryLeftSidesOf(graph.recvNode(edgeIndex), dataflow, ctx);
            if (!factories.isEmpty()) {
                return new Left(factories.get(0), true);
            }
        }
        String type = methods.typeFqn(declaredCallee);
        String name = methods.methodName(declaredCallee);
        return (type == null || type.isEmpty() || name == null || name.isEmpty())
                ? null : new Left(type + "#" + name, false);
    }

    public boolean isEmpty() {
        return byLeftSide.isEmpty();
    }

    /** ひな形の行数（見出しのコメントは数えない） */
    public int size() {
        return byLeftSide.size();
    }

    /**
     * ひな形を書き出す。
     *
     * <p>文字コードは <b>UTF-8（BOM 無し）で固定</b>する。{@code output.encoding} に合わせないのは、
     * このファイルの中身をそのまま貼る先（{@code contracts.files} の表）が UTF-8 固定で読まれるため。
     *
     * @return 書いたひな形の行数
     */
    public int write(Path file) throws IOException {
        if (byLeftSide.isEmpty()) {
            return 0;
        }
        List<Map.Entry<String, Entry>> rows = new ArrayList<>(byLeftSide.entrySet());
        // 件数の多い順。同数なら左辺の綴り順にして、環境によらない並びにする
        rows.sort(Comparator.<Map.Entry<String, Entry>>comparingInt(e -> -e.getValue().sites)
                .thenComparing(Map.Entry::getKey));
        // キーの値はキャッシュから戻したソースの文字列で、対になっていないサロゲートを含みうる。
        // Files.newBufferedWriter はそこで例外にするので、CSV と同じ置換する書き手を使う
        try (BufferedWriter out = Csv.writer(file, StandardCharsets.UTF_8, false)) {
            writeHeader(out, rows.size());
            for (Map.Entry<String, Entry> row : rows) {
                Entry e = row.getValue();
                comment(out, Messages.format("report.suggestions.sites", e.sites, e.where));
                comment(out, Messages.format("report.suggestions.candidates",
                        String.join(" / ", e.candidates)));
                if (e.typeWide) {
                    // 広い行だと分かるようにする。呼び出し箇所ごとに実装が違うなら、そのまま
                    // 貼ると誤った 1 件に確定してしまう
                    comment(out, Messages.get("report.suggestions.typeWide1"));
                    comment(out, Messages.get("report.suggestions.typeWide2"));
                }
                comment(out, row.getKey() + " => " + PLACEHOLDER);
                out.newLine();
            }
        }
        return rows.size();
    }

    private void writeHeader(BufferedWriter out, int lines) throws IOException {
        List<String> header = new ArrayList<>(List.of(
                Messages.get("report.suggestions.head.what1"),
                Messages.get("report.suggestions.head.what2"),
                "",
                Messages.get("report.suggestions.head.usage"),
                Messages.get("report.suggestions.head.usage1"),
                Messages.get("report.suggestions.head.usage2"),
                Messages.get("report.suggestions.head.usage3"),
                "",
                Messages.get("report.suggestions.head.syntax1"),
                Messages.get("report.suggestions.head.syntax2"),
                Messages.get("report.suggestions.head.syntax3"),
                "",
                Messages.format("report.suggestions.head.count", lines)));
        if (capped) {
            header.add(Messages.format("report.suggestions.head.capped", MAX_LINES));
        }
        for (String line : header) {
            comment(out, line);
        }
        out.newLine();
    }

    /** ひな形はまるごとコメントにして出す。そのまま貼っても壊れないようにするため */
    private static void comment(BufferedWriter out, String text) throws IOException {
        out.write(text.isEmpty() ? "#" : "# " + text);
        out.newLine();
    }
}
