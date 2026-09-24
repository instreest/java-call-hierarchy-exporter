// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche;

import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;

import org.eclipse.jdt.core.JavaCore;

import jche.analysis.CachePhaseResult;
import jche.analysis.CacheUpdater;
import jche.cache.CacheLock;
import jche.config.Config;
import jche.config.Plugins;
import jche.config.ProjectLayout;
import jche.dataflow.DataflowBuilder;
import jche.dataflow.DataflowFacts;
import jche.extension.TypeCandidateProvider;
import jche.graph.CallGraph;
import jche.graph.CallGraphBuilder;
import jche.graph.CallResolver;
import jche.graph.Contracts;
import jche.graph.DataflowResolver;
import jche.graph.SpringBeans;
import jche.graph.UnresolvedCalls;
import jche.util.HeapWatch;
import jche.util.Log;
import jche.util.RunControl;
import jche.util.Messages;
import jche.util.Warnings;

/**
 * フェーズ1（ソース解析とキャッシュ更新）・フェーズ2（グラフ構築とデータフローの確定・具象クラスの解決）。
 *
 * <p>ここから先（CSV の出力）は {@code CallHierarchyExporter} が受け持つ。分けてあるのは、
 * <b>結果をメモリに持ったまま何度も問い合わせたい</b>使い方があるためで、
 * Eclipse プラグインの解析サーバー（{@link jche.server.Server}）がそれにあたる。
 * どちらの経路も同じこのコードを通るので、CLI と画面で結果が食い違うことはない。
 *
 * <p>既定パッケージではなく名前付きパッケージに置いてあるのは、
 * {@code jche.server} など他のパッケージから呼べるようにするため
 * （Java では名前付きパッケージのクラスから既定パッケージのクラスを参照できない）。
 */
public final class Exporter {

    private Exporter() {
    }

    /**
     * フェーズ1・2 を実行し、結果をメモリに返す。CSV は書かない。型解決に失敗した呼び出しの一覧
     * （{@link AnalysisSnapshot#unresolvedCalls}）は拾わない（解析サーバーはこちら）。
     *
     * <p>進捗の通知と中止は {@link jche.util.RunControl} 経由。呼び出し側が受け口を付けていなければ
     * 何も起きない（CLI はこれ）。中止された場合は {@link jche.util.CancelledException} が飛ぶ。
     *
     * @param config 設定（出力フォルダは使わない。キャッシュの場所は使う）
     * @return この時点の解析結果
     */
    public static AnalysisSnapshot analyze(Config config) throws Exception {
        return analyze(config, false);
    }

    /**
     * {@link #analyze(Config)} と同じ。{@code collectUnresolved} なら、型解決に失敗した呼び出しの一覧に出す行を
     * グラフを組むときに拾っておく（CSV を書く CLI。一覧を書く側がキャッシュを読み直さなくて済む）。
     * 拾った行は一時ファイルにあるので、使い終えたら {@link AnalysisSnapshot#unresolvedCalls} を閉じること
     * （閉じると一時ファイルが消える）
     */
    public static AnalysisSnapshot analyze(Config config, boolean collectUnresolved) throws Exception {
        // ヒープの見張りの登録と解除はフェーズの出入りで持つ。解析サーバーは 1 つの JVM で
        // 解析を何度も走らせるので、解除しないと GC のリスナーが積み上がって二重に数える。
        // try-with-resources にしないのは、handle を本体で使わないため（-Xlint:try が警告する）
        HeapWatch heapWatch = HeapWatch.start();
        try {
            return analyzePhases(config, collectUnresolved);
        } finally {
            heapWatch.close();
        }
    }

    private static AnalysisSnapshot analyzePhases(Config config, boolean collectUnresolved) throws Exception {
        ProjectLayout layout = new ProjectLayout(config);
        logAnalysisSettings(config, layout);

        UnresolvedCalls unresolved = collectUnresolved ? new UnresolvedCalls(config.cacheFile) : null;
        try {
            // キャッシュを読み書きするあいだ（フェーズ1 とフェーズ2 のグラフの構築）は、同じキャッシュのフォルダを使う
            // ほかの実行を待たせる（jche.cache.CacheLock。docs/cache-unification-qa.md の Q51）。
            // try-with-resources にしないのは、錠を本体で使わないため（-Xlint:try が警告する）
            int syntaxErrorFiles;
            CallGraph graph;
            CacheLock lock = CacheLock.acquire(config.cacheFile);
            try {
                syntaxErrorFiles = analyzeSources(config, layout);
                graph = buildGraph(config, layout, unresolved);
            } finally {
                lock.close();
            }
            Log.info(Messages.format("exporter.graphCounts", graph.typeCount(), graph.methodCount(),
                    graph.edgeCount()));
            DataflowFacts facts = buildDataflowFacts(config, graph);
            DataflowResolver dataflow =
                    new DataflowResolver(graph, facts, config.dataflowEnabled, config.dataflowMaxDepth);
            // 契約表の読み込みとプラグインの初期化。件数では測れないので「やっている最中」だけを出す
            RunControl.progress(Messages.get("exporter.progress.resolvePrep"), 0, 1);
            Contracts.Loaded contracts = Contracts.load(config, graph, dataflow);
            CallResolver resolver = new CallResolver(graph, dataflow, loadProviders(config),
                    contracts.callbacks(), contracts.entries(), contracts.types());
            RunControl.progress(Messages.get("exporter.progress.resolvePrep"), 1, 1);
            Log.heap(Messages.get("exporter.heap.phase2"));
            return new AnalysisSnapshot(config, layout, graph, resolver, syntaxErrorFiles, unresolved);
        } catch (Exception | Error e) {
            // 結果を返せなかった。拾った行の一時ファイルは受け取る側がいないので、ここで消す
            if (unresolved != null) {
                unresolved.close();
            }
            throw e;
        }
    }

    private static void logAnalysisSettings(Config config, ProjectLayout layout) {
        Log.info(Messages.format("exporter.sourceFolders", layout.sourceFolders));
        Log.info(Messages.format("exporter.sourceEncoding", config.sourceEncoding,
                config.sourceEncodingAuto ? Messages.get("exporter.sourceEncoding.auto") : ""));
        // どの言語バージョンとして解析したかで結果が変わるため、必ず残す
        Log.info(Messages.format("exporter.sourceLevel", config.sourceLevel,
                config.sourceLevelAuto ? Messages.get("exporter.sourceLevel.auto")
                        : Messages.format("exporter.sourceLevel.requested", config.sourceLevelRequested),
                JavaCore.latestSupportedJavaVersion()));
        if (!config.sourceLevelAuto && !config.sourceLevelRequested.equals(config.sourceLevel)) {
            // JDTが指定値を黙って丸めた。指定が効いていないことを見えるようにする
            Log.info(Messages.format("exporter.sourceLevel.tooOld", config.sourceLevelRequested,
                    config.sourceLevel));
            Log.info(Messages.get("exporter.sourceLevel.tooOld2"));
        }
        // 依存jarは library.folders でフォルダごと指定できる。ここで出すのは
        // 実際にJDTへ渡す「*.jar に展開した後」の一覧なので、
        // フォルダ指定がjar単位に展開されているかを確認できる
        String[] classpath = layout.classpathArray();
        Log.info(Messages.format("exporter.classpathCount", classpath.length));
        for (String cp : classpath) {
            Log.info("  " + cp);
        }
    }

    /**
     * フェーズ1: 解析とキャッシュ更新（1ファイルずつ書き出して破棄）。
     *
     * @return 構文エラーで本体を読めなかったファイル数（画面に出すため呼び出し側へ返す）
     */
    private static int analyzeSources(Config config, ProjectLayout layout) throws Exception {
        Log.blank();
        Log.info(Messages.get("exporter.phase1"));
        CachePhaseResult result = new CacheUpdater(layout, config).run();
        Log.info(Messages.format("exporter.parseSummary", result.reused, result.parsed,
                reanalysisBreakdown(result), result.failed,
                (result.syntaxErrorFiles > 0)
                        ? Messages.format("exporter.parseSummary.syntaxErrors", result.syntaxErrorFiles) : "",
                (result.salvaged > 0)
                        ? Messages.format("exporter.parseSummary.salvaged", result.salvaged) : ""));
        reportCompileErrors(result);
        reportSyntaxErrors(config, result);
        if (result.unresolved > 0) {
            Warnings.warn(Warnings.Topic.BUILD, Messages.format("exporter.unresolved", result.unresolved));
            Log.info(Messages.get("exporter.unresolved2"));
            if (!config.libraryFolders.isEmpty()) {
                Log.info(Messages.get("exporter.unresolved3"));
            }
            Log.info(Messages.get("exporter.unresolved4"));
            Log.info(Messages.get("exporter.unresolved5"));
            Log.info(Messages.get("exporter.unresolved6"));
        }
        Log.heap(Messages.get("exporter.heap.phase1"));
        return result.syntaxErrorFiles;
    }

    /**
     * 構文エラーで本体を読めなかったファイルを報告する。
     *
     * <p><b>これを黙っていてはいけない。</b>型が見つからない類のエラー（jar 不足）とは違い、
     * 構文エラーが出たファイルは本体そのものを読めていないので、そこに書かれた呼び出しは
     * まるごと出力に出ない。影響調査の結果が静かに欠けるということで、
     * このツールがいちばんしてはいけないことである（docs/syntax-error-report-qa.md）。
     *
     * <p>よくある原因は2つ。どちらも「JDT とソースの版が合っていない」という同じ形をしている。
     * <ul>
     *   <li>ソースが JDT より新しい文法を使っている（source.level を上げても、JDT が知らなければ読めない）</li>
     *   <li>ソースが JDT より古い（Java 5 より前の {@code enum} / {@code assert} を識別子に使っている等。
     *       いまの JDT は 1.8 未満の source.level を受け付けず 1.8 として読むため、構文エラーになる）</li>
     * </ul>
     */
    private static void reportSyntaxErrors(Config config, CachePhaseResult result) {
        if (result.syntaxErrorFiles == 0) {
            return;
        }
        Warnings.warn(Warnings.Topic.BUILD, Messages.format("exporter.syntaxErrors", result.syntaxErrorFiles));
        Warnings.warn(Warnings.Topic.BUILD, Messages.get("exporter.syntaxErrors2"));
        for (String path : result.syntaxErrorPaths) {
            Warnings.warn(Warnings.Topic.BUILD, "   - " + path);
        }
        if (result.syntaxErrorFiles > result.syntaxErrorPaths.size()) {
            Warnings.warn(Warnings.Topic.BUILD, Messages.format("exporter.syntaxErrors.more",
                    result.syntaxErrorFiles - result.syntaxErrorPaths.size()));
        }
        Warnings.warn(Warnings.Topic.BUILD, Messages.format("exporter.syntaxErrors.level", config.sourceLevel,
                JavaCore.latestSupportedJavaVersion()));
        Warnings.warn(Warnings.Topic.BUILD, Messages.get("exporter.syntaxErrors.level2"));
    }

    /**
     * コンパイルエラーのあったファイルを報告する。
     *
     * <p>このツールは「ビルドが通る」ことを正常な状態としている。エラーの多くは依存 jar の不足・
     * Java の版の食い違い・ビルド時に生成されるソースの欠け（Lombok、アノテーション処理）で、
     * どれもその箇所の呼び出しが型解決に失敗して階層から抜ける。解析は続けるが、黙ってはいけない
     * （warnings.txt の「ソースにコンパイルエラーがある」の項目になる。{@code docs/output-files-simplify-qa.md} の Q6）。
     * 構文エラーはこの一部で、影響がより重いので {@link #reportSyntaxErrors} で別に言う。
     */
    private static void reportCompileErrors(CachePhaseResult result) {
        if (result.compileErrorFiles == 0) {
            return;
        }
        Warnings.warn(Warnings.Topic.BUILD, Messages.format("exporter.compileErrors", result.compileErrorFiles));
        for (String path : result.compileErrorPaths) {
            Warnings.warn(Warnings.Topic.BUILD, "   - " + path);
        }
        if (result.compileErrorFiles > result.compileErrorPaths.size()) {
            Warnings.warn(Warnings.Topic.BUILD, Messages.format("exporter.syntaxErrors.more",
                    result.compileErrorFiles - result.compileErrorPaths.size()));
        }
    }

    /** 「新規解析」のうち、自分は変わっていないのに解析し直した件数の内訳 */
    private static String reanalysisBreakdown(CachePhaseResult result) {
        List<String> parts = new ArrayList<>();
        if (result.dependents > 0) {
            parts.add(Messages.format("exporter.reanalysis.dependents", result.dependents));
        }
        if (result.libraryDependents > 0) {
            parts.add(Messages.format("exporter.reanalysis.libraries", result.libraryDependents));
        }
        return parts.isEmpty() ? "" : Messages.format("exporter.reanalysis.wrap",
                String.join(Messages.get("exporter.reanalysis.sep"), parts));
    }

    /**
     * フェーズ2: キャッシュを 1 回スキャンして CSR グラフを構築する
     *
     * @param unresolved 型解決に失敗した呼び出しの一覧に出す行を拾う先。拾わないなら null
     */
    private static CallGraph buildGraph(Config config, ProjectLayout layout, UnresolvedCalls unresolved)
            throws Exception {
        Log.blank();
        Log.info(Messages.get("exporter.phase2"));
        List<String> sourceFolderOrder = new ArrayList<>();
        for (Path sourceFolder : layout.sourceFolders) {
            sourceFolderOrder.add(layout.relativeOf(sourceFolder));
        }
        SpringBeans beans = SpringBeans.of(config.springDiEnabled, config.springDiAnnotations);
        // dataflow.enabled=false のときは値（値グラフ・戻り値・代入・証拠・呼び出し箇所の値）を読まない
        // （具象クラスの解決は CHA まで、条件分岐の打ち切りは起きない）
        CallGraph graph = CallGraphBuilder.build(config.cacheFile, config.dataflowEnabled,
                sourceFolderOrder, beans, unresolved);
        if (beans.enabled()) {
            Log.info(Messages.format("exporter.diBeans", beans.beanCount(),
                    (beans.beanCount() == 0) ? Messages.get("exporter.diBeans.none") : ""));
        }
        return graph;
    }

    /**
     * フェーズ2b: データフローの事実（ファクトリの戻り値の畳み込み等）をグラフ全体から一括で確定する。
     * 解決（CallResolver）より前に確定させておくことで、解決の結果がエッジの処理順に依存しなくなる
     */
    private static DataflowFacts buildDataflowFacts(Config config, CallGraph graph) {
        DataflowFacts facts = DataflowBuilder.build(graph, config.dataflowEnabled);
        if (config.dataflowEnabled) {
            Log.info(Messages.format("exporter.factories", facts.factoriesDecided(),
                    (facts.factoriesCutOff() > 0)
                            ? Messages.format("exporter.factories.cutOff", facts.factoriesCutOff()) : ""));
        }
        return facts;
    }

    /** フェーズBの拡張（具象クラスの候補を返すもの）を読み込む。init は Plugins が済ませる */
    private static List<TypeCandidateProvider> loadProviders(Config config) {
        return Plugins.load(config, config.candidateProviderClasses, TypeCandidateProvider.class);
    }
}
