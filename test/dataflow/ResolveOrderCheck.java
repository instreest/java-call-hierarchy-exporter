// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import jche.analysis.CacheUpdater;
import jche.config.Config;
import jche.config.ProjectLayout;
import jche.config.ToolRoot;
import jche.graph.CallGraph;
import jche.graph.CallGraphBuilder;
import jche.graph.CallResolver;
import jche.graph.MethodTable;
import jche.graph.Resolution;
import jche.graph.SpringBeans;
import jche.util.Log;

/**
 * CallResolver.resolve が「呼ぶ順」「呼ぶ回数」に依存しないことの検査（Issue #80）。
 *
 * 設定ファイルのプロジェクトを解析してグラフを作り、次の 3 通りで全エッジを解決して結果を突き合わせる。
 * <pre>
 *   1周目 … エッジ番号の昇順（CLI と同じ順）
 *   2周目 … 同じ CallResolver でもう一度昇順（メモが育っていれば結果が変わる）
 *   逆順  … 新しい CallResolver でエッジ番号の降順（処理順そのものを変える）
 * </pre>
 * 1 件でも食い違えば、そのエッジと 3 通りの結果を出して終了コード 1 で終わる。
 * 使い方: test/dataflow/run.sh を参照
 */
public final class ResolveOrderCheck {

    private ResolveOrderCheck() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("使い方: ResolveOrderCheck <設定ファイル>");
            System.exit(2);
        }
        Path configPath = Paths.get(args[0]).toAbsolutePath();
        ToolRoot toolRoot = ToolRoot.locate(ResolveOrderCheck.class);
        Config config = new Config(configPath, toolRoot.dir, LocalDateTime.now());
        ProjectLayout layout = new ProjectLayout(config);
        new CacheUpdater(layout, config).run();

        List<String> sourceFolderOrder = new ArrayList<>();
        for (Path sourceFolder : layout.sourceFolders) {
            sourceFolderOrder.add(layout.relativeOf(sourceFolder));
        }
        SpringBeans beans = SpringBeans.of(config.springDiEnabled, config.springDiAnnotations);
        CallGraph graph = CallGraphBuilder.build(config.cacheFile, sourceFolderOrder, beans);
        int edges = graph.edgeCount();
        Log.info("エッジ数=" + edges + " を 3 通りの順で解決して比較します");

        CallResolver forward = ResolverFactory.create(graph, config);
        Resolution[] first = new Resolution[edges];
        for (int e = 0; e < edges; e++) {
            first[e] = forward.resolve(e);
        }
        Resolution[] second = new Resolution[edges];
        for (int e = 0; e < edges; e++) {
            second[e] = forward.resolve(e);
        }
        CallResolver backward = ResolverFactory.create(graph, config);
        Resolution[] reversed = new Resolution[edges];
        for (int e = edges - 1; e >= 0; e--) {
            reversed[e] = backward.resolve(e);
        }

        int mismatches = 0;
        MethodTable mt = graph.methods();
        for (int e = 0; e < edges; e++) {
            if (same(first[e], second[e]) && same(first[e], reversed[e])) {
                continue;
            }
            mismatches++;
            int caller = callerOf(graph, e);
            System.out.println("edge " + e + "  " + mt.key(caller) + " -> " + mt.key(graph.calleeOf(e)));
            System.out.println("  1周目 = " + describe(mt, first[e]));
            System.out.println("  2周目 = " + describe(mt, second[e]));
            System.out.println("  逆順  = " + describe(mt, reversed[e]));
        }
        if (mismatches > 0) {
            System.out.println("NG   解決結果が呼ぶ順・回数で変わったエッジ: " + mismatches + " 件");
            System.exit(1);
        }
        System.out.println("OK   全 " + edges + " エッジの解決結果が呼ぶ順・回数によらず一致");
    }

    private static boolean same(Resolution a, Resolution b) {
        return Arrays.equals(a.targets(), b.targets()) && a.label().equals(b.label());
    }

    private static int callerOf(CallGraph graph, int edge) {
        for (int m = 0; m < graph.methodCount(); m++) {
            if (edge >= graph.edgeStart(m) && edge < graph.edgeEnd(m)) {
                return m;
            }
        }
        return -1;
    }

    private static String describe(MethodTable mt, Resolution r) {
        List<String> names = new ArrayList<>();
        for (int t : r.targets()) {
            names.add(mt.key(t));
        }
        return names + "  " + r.label();
    }
}
