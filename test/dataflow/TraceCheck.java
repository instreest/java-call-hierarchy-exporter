// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import jche.AnalysisSnapshot;
import jche.config.Config;
import jche.dataflow.DataflowFacts;
import jche.graph.CallGraph;
import jche.graph.CallResolver;
import jche.graph.CallbackContracts;
import jche.graph.EntryPoints;
import jche.graph.MethodTable;
import jche.graph.StoreInspector;
import jche.report.CallHierarchyCsvWriter;
import jche.report.StreamingTreeWalker;
import jche.report.TraceProbe;

/**
 * 値の読み手を文字列から値の表へ移す間（stage B）、読み手の判断が 1 つも変わらないことの検査。
 *
 * <p>{@link CheckProjects#STRICT} の各プロジェクトを CLI と同じ経路で解析して呼び出し階層を歩き、次の 2 つを
 * 文字列にして、記録（{@code test/dataflow/trace/<名前>.txt}）と 1 文字ずつ突き合わせる。
 * <pre>
 *   経路ごと（{@link TraceProbe}）… 辺ごとの解決・経路の値・打ち切りの理由・ファクトリのキー・ひな形の左辺、
 *                                  候補ごとの束縛した値、呼び戻し
 *   経路によらない事実            … メソッドごとのファクトリの戻り値・引数を使うか・戻り値の出所、
 *                                  注入されたフィールドの出所、DI の登録、辺ごとの解決と呼び戻し、入次数
 * </pre>
 * 食い違えば、最初に違う行と前後 5 行を出して終了コード 1。記録は、文字列の側が変わっていないコミットで
 * {@code --record} を付けて作り直す（読み手を移すコミットで作り直してはいけない）。
 * 使い方: test/dataflow/run.sh を参照
 */
public final class TraceCheck {

    private TraceCheck() {
    }

    public static void main(String[] args) throws Exception {
        boolean record = args.length > 0 && "--record".equals(args[0]);
        Path dir = CheckProjects.root().resolve("test/dataflow/trace");
        boolean ok = true;
        for (CheckProjects.Project p : CheckProjects.STRICT) {
            String text = trace(p);
            Path golden = dir.resolve(p.name() + ".txt");
            long lines = text.chars().filter(c -> c == '\n').count();
            if (record) {
                Files.createDirectories(dir);
                Files.writeString(golden, text, StandardCharsets.UTF_8);
                System.out.println("REC  " + p.name() + ": " + lines + " 行を記録した（" + golden + "）");
                continue;
            }
            if (!Files.isRegularFile(golden)) {
                System.out.println("NG   " + p.name() + ": 記録がありません（" + golden + "）");
                ok = false;
                continue;
            }
            String expected = Files.readString(golden, StandardCharsets.UTF_8);
            if (expected.equals(text)) {
                System.out.println("OK   " + p.name() + ": 経路の記録 " + lines + " 行が一致");
            } else {
                ok = false;
                System.out.println("NG   " + p.name() + ": 経路の記録が食い違う");
                showFirstDifference(expected, text);
                Path actual = CheckProjects.root().resolve("test/dataflow/work").resolve(p.name() + ".trace.txt");
                Files.writeString(actual, text, StandardCharsets.UTF_8);
                System.out.println("       今回の記録: " + actual);
            }
        }
        if (!ok) {
            System.exit(1);
        }
    }

    /** 解析して歩き、記録の文字列を作る */
    private static String trace(CheckProjects.Project p) throws Exception {
        Config config = CheckProjects.configOf(p);
        AnalysisSnapshot snapshot = CheckProjects.analyze(config);
        CallGraph graph = snapshot.graph();
        CallResolver resolver = snapshot.resolver();
        int[] entries = EntryPoints.select(graph, resolver, config);
        Files.createDirectories(config.outputDir);
        TraceProbe probe = TraceProbe.install(graph, resolver.dataflow());
        try (CallHierarchyCsvWriter writer = new CallHierarchyCsvWriter(
                config.outputCsv, config.outputEncoding, config.outputBom)) {
            new StreamingTreeWalker(graph, resolver, config, writer).walkAll(entries);
        } finally {
            TraceProbe.uninstall();
        }
        StringBuilder out = new StringBuilder();
        out.append("# ").append(p.name()).append(" の経路の記録（test/dataflow/TraceCheck.java。stage B の間だけ置く）\n");
        out.append("# --- 経路 ---\n").append(probe.text());
        out.append("# --- 経路によらない事実 ---\n");
        staticFacts(out, graph, resolver);
        return out.toString();
    }

    private static void staticFacts(StringBuilder out, CallGraph graph, CallResolver resolver) {
        MethodTable methods = graph.methods();
        DataflowFacts facts = resolver.dataflow().facts();
        TreeMap<String, Integer> byKey = new TreeMap<>();
        for (int id = 0; id < methods.size(); id++) {
            byKey.put(methods.key(id), id);
        }
        for (Map.Entry<String, Integer> m : byKey.entrySet()) {
            int id = m.getValue();
            String factory = facts.factoryOrigin(id);
            boolean uses = facts.usesParameters(id);
            String[] returns = graph.returnOriginsOf(id);
            if (factory == null && !uses && returns == null) {
                continue;
            }
            out.append("M ").append(m.getKey()).append(" fo=").append(TraceProbe.esc(factory))
                    .append(" up=").append(uses ? 1 : 0).append(" R=").append(TraceProbe.arr(returns)).append('\n');
        }
        for (Map.Entry<String, String> f : new TreeMap<>(StoreInspector.fieldOrigins(graph)).entrySet()) {
            out.append("F ").append(f.getKey()).append(' ').append(TraceProbe.esc(f.getValue())).append('\n');
        }
        TreeMap<String, String> beans = new TreeMap<>();
        for (Map.Entry<?, ?> b : StoreInspector.beanNames(graph.beans()).entrySet()) {
            beans.put(String.valueOf(b.getKey()), String.valueOf(b.getValue()));
        }
        for (Map.Entry<String, String> b : beans.entrySet()) {
            out.append("B ").append(b.getKey()).append(' ').append(b.getValue()).append('\n');
        }
        for (Map.Entry<String, Integer> m : byKey.entrySet()) {
            int caller = m.getValue();
            for (int e = graph.edgeStart(caller); e < graph.edgeEnd(caller); e++) {
                out.append("E ").append(m.getKey()).append(" @").append(graph.callLineOf(e)).append(" -> ")
                        .append(methods.key(graph.calleeOf(e))).append(" | ")
                        .append(TraceProbe.describe(methods, resolver.resolve(e)));
                for (CallbackContracts.Match c : resolver.callbackTargets(e, null)) {
                    out.append(" | CB ").append(methods.key(c.target())).append(" [")
                            .append(TraceProbe.esc(c.contract())).append("] ").append(c.candidates());
                }
                out.append('\n');
            }
        }
        int[] in = resolver.inDegrees();
        for (Map.Entry<String, Integer> m : byKey.entrySet()) {
            int id = m.getValue();
            if (id < in.length && in[id] > 0) {
                out.append("I ").append(m.getKey()).append(' ').append(in[id]).append('\n');
            }
        }
    }

    /** 最初に違う行と前後 5 行を出す */
    private static void showFirstDifference(String expected, String actual) {
        List<String> a = lines(expected);
        List<String> b = lines(actual);
        int i = 0;
        while (i < a.size() && i < b.size() && a.get(i).equals(b.get(i))) {
            i++;
        }
        System.out.println("       " + (i + 1) + " 行目から違う");
        for (int k = Math.max(0, i - 5); k < i; k++) {
            System.out.println("         " + a.get(k));
        }
        for (int k = i; k < Math.min(a.size(), i + 6); k++) {
            System.out.println("       - " + a.get(k));
        }
        for (int k = i; k < Math.min(b.size(), i + 6); k++) {
            System.out.println("       + " + b.get(k));
        }
    }

    private static List<String> lines(String s) {
        return new ArrayList<>(List.of(s.split("\n", -1)));
    }
}
