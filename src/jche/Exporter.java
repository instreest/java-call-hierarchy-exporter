// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.core.JavaCore;

import jche.analysis.CachePhaseResult;
import jche.analysis.CacheUpdater;
import jche.config.Config;
import jche.config.Plugins;
import jche.config.ProjectLayout;
import jche.config.ToolRoot;
import jche.extension.TypeCandidateProvider;
import jche.external.ExternalUsageScanner;
import jche.graph.CallGraph;
import jche.graph.CallGraphBuilder;
import jche.graph.CallResolver;
import jche.graph.DataflowResolver;
import jche.graph.EntryPoints;
import jche.report.CallHierarchyCsvWriter;
import jche.report.InventoryReport;
import jche.report.StreamingTreeWalker;
import jche.report.UnresolvedReport;
import jche.util.Log;

/**
 * 解析の本体。設定ファイルを受け取り、フェーズ1〜3を回してCSVを出力する。
 *
 * <p>コマンドラインの入口は既定パッケージの {@code CallHierarchyExporter} で、引数を解釈して
 * {@link #run(List)} を呼ぶだけになっている。本体をここ（名前付きパッケージ）に置いてあるのは、
 * Eclipse プラグイン（{@code eclipse-plugin/}）のように別パッケージのコードから呼べるようにするため。
 * Java では名前付きパッケージのクラスから既定パッケージのクラスは参照できない
 * （docs/eclipse-plugin-qa.md の Q3）。
 *
 * <p>設定ファイルごとに、その設定ファイルのフォルダの output.folder（既定 ./output）の下へ
 * {@code <解析開始日時>_<プロジェクト名>/} を作り、CSV・設定ファイルの複製・実行ログ（run.log）を書く。
 * キャッシュは出力フォルダではなく、このツールのプロジェクトフォルダの .cache/ の下に
 * 解析対象プロジェクトごとに置く（{@link jche.config.ToolRoot}、{@link Config}）。
 * 1つの設定が失敗しても残りは処理する。
 *
 * <h2>処理の流れ（パッケージ構成と対応する）</h2>
 * <pre>
 *   フェーズ1  jche.analysis  ソースをASTパースし、事実をキャッシュ（jche.cache）へ書き出す
 *   フェーズ2  jche.graph     キャッシュからCSR形式の呼び出しグラフを組み、具象クラスを解決する
 *   フェーズ3  jche.report    起点ごとに深さ優先で辿りながらCSVを1行ずつ書く
 *              jche.external  外部jarからの被参照を同じCSVに追記する
 * </pre>
 *
 * <h2>メモリ設計（OutOfMemoryError を避けるための三本柱）</h2>
 * <ol>
 *   <li><b>解析結果をヒープに溜めない</b>（フェーズ1）
 *       1ファイル解析するたびに結果をキャッシュファイルへ直接書き出して破棄する。
 *       キャッシュ更新はストリーミングマージで行うため、ランダムアクセスも
 *       全件保持も不要。ヒープ常駐は「ソースファイルのパス・更新時刻・サイズ」のみ。</li>
 *   <li><b>エッジをオブジェクトで持たない</b>（フェーズ2）
 *       メソッドを int の ID に内部化し、呼び出し関係を CSR 形式のプリミティブ配列で持つ。
 *       オブジェクト2個＋文字列8本（数百バイト）だったものが int 2個（8バイト）になる。</li>
 *   <li><b>ツリーを組み立てない</b>（フェーズ3）
 *       深さ優先探索しながら1行ずつCSVへ書き出す。探索中にヒープへ載るのは
 *       「現在の経路（深さぶんの配列）」だけ。</li>
 * </ol>
 */
public final class Exporter {

    private Exporter() {
    }

    /**
     * 設定ファイルをまとめて処理する。コマンドラインからも Eclipse プラグイン
     * （{@code eclipse-plugin/}）からも、入口はここになる。
     *
     * <p>{@code CallHierarchyExporter.main} と違って {@link System#exit} を呼ばない。プラグインのように
     * 呼び出し側の JVM を落とせない場所から使うため、失敗は戻り値で返す。
     * 1つの設定が失敗しても残りは処理する。
     *
     * @param configPaths 設定ファイルのパス（1件以上）
     * @return 失敗した設定ファイルの件数。0 なら全件成功
     */
    public static int run(List<Path> configPaths) {
        ToolRoot toolRoot = ToolRoot.locate(Exporter.class);
        if (!toolRoot.found) {
            Log.warn("このツールのプロジェクトフォルダ（src/CallHierarchyExporter.java のある場所）を"
                    + "作業ディレクトリの上位に見つけられません。キャッシュは作業ディレクトリの下に作ります: "
                    + toolRoot.dir.resolve(Config.DEFAULT_CACHE_DIR_NAME));
        }
        return run(configPaths, toolRoot.dir);
    }

    /**
     * キャッシュの置き場所を呼び出し側が決める版。
     *
     * <p>Eclipse プラグインから使う。プラグインは jar の中で動くので
     * {@link ToolRoot} の目印（{@code src/CallHierarchyExporter.java}）が見つからず、
     * 何もしないと作業ディレクトリ（Eclipse のインストール先）にキャッシュを作ってしまう
     * （docs/eclipse-plugin-qa.md の Q6）。
     *
     * @param configPaths 設定ファイルのパス（1件以上）
     * @param cacheRoot   キャッシュ（{@code .cache/}）を作るフォルダ
     * @return 失敗した設定ファイルの件数。0 なら全件成功
     */
    public static int run(List<Path> configPaths, Path cacheRoot) {
        // 設定ファイルごとに独立して処理する。1つが失敗しても残りは続け、最後にまとめて報告する
        List<String> summary = new ArrayList<>();
        int failed = 0;
        for (int i = 0; i < configPaths.size(); i++) {
            Path configPath = configPaths.get(i);
            if (configPaths.size() > 1) {
                Log.blank();
                Log.info("######## 設定 " + (i + 1) + "/" + configPaths.size() + ": " + configPath + " ########");
            }
            try {
                Path outputDir = runOne(configPath, cacheRoot);
                summary.add("OK    " + configPath + " -> " + outputDir);
            } catch (Throwable t) {
                failed++;
                Log.error("設定 " + configPath + " の処理に失敗しました", t);
                summary.add("FAIL  " + configPath + " : " + t);
            } finally {
                Log.detachFile();
            }
        }

        if (configPaths.size() > 1) {
            Log.blank();
            Log.info("=== 実行結果（" + (configPaths.size() - failed) + "/" + configPaths.size() + " 件成功）===");
            for (String line : summary) {
                Log.info("  " + line);
            }
        }
        return failed;
    }

    /**
     * 設定ファイル1つ分の処理。出力フォルダを作り、設定ファイルの複製と実行ログをそこに置いてから解析する。
     *
     * @return この実行の出力フォルダ
     */
    private static Path runOne(Path configPath, Path toolRoot) throws Exception {
        Log.resetClock();
        long start = System.currentTimeMillis();
        Config config = new Config(configPath, toolRoot, LocalDateTime.now());

        // 出力フォルダは解析より前に作る。設定ファイルの複製と実行ログを、解析が途中で落ちても残すため
        Files.createDirectories(config.outputDir);
        Log.attachFile(config.logFile);
        Log.info("設定: " + config.configPath);
        Log.info("プロジェクトルート: " + config.projectRoot);
        Log.info("出力フォルダ: " + config.outputDir);
        Log.info("キャッシュ: " + config.cacheDir);
        Files.copy(config.configPath, config.outputDir.resolve(config.configPath.getFileName()),
                StandardCopyOption.REPLACE_EXISTING);

        ProjectLayout layout = new ProjectLayout(config);
        logAnalysisSettings(config, layout);

        analyzeSources(config, layout);

        CallGraph graph = buildGraph(config, layout);
        CallResolver resolver = new CallResolver(graph,
                new DataflowResolver(graph, config.dataflowEnabled, config.dataflowMaxDepth),
                loadProviders(config));
        Log.info("型数=" + graph.typeCount()
                + " メソッド数=" + graph.methodCount()
                + " エッジ数=" + graph.edgeCount());
        Log.heap("フェーズ2完了");

        long rows = writeReports(config, graph, resolver);

        Log.blank();
        Log.info("呼び出し階層: " + config.outputCsv + "（" + rows + " 行）");
        Log.info("実行ログ: " + config.logFile);
        Log.info("完了 (" + (System.currentTimeMillis() - start) + " ms)");
        return config.outputDir;
    }

    private static void logAnalysisSettings(Config config, ProjectLayout layout) {
        Log.info("ソースフォルダ: " + layout.sourceFolders);
        Log.info("ソース文字コード: " + config.sourceEncoding);
        // どの言語バージョンとして解析したかで結果が変わるため、必ず残す
        Log.info("ソースレベル: " + config.sourceLevel
                + (config.sourceLevelAuto
                        ? "（source.level 未指定のため、JDTが対応する最大値）"
                        : "（source.level=" + config.sourceLevelRequested + " の指定による）")
                + " / このJDTの対応上限: " + JavaCore.latestSupportedJavaVersion());
        if (!config.sourceLevelAuto && !config.sourceLevelRequested.equals(config.sourceLevel)) {
            // JDTが指定値を黙って丸めた。指定が効いていないことを見えるようにする
            Log.info("※ source.level=" + config.sourceLevelRequested
                    + " はこのJDTでは扱えないため " + config.sourceLevel + " として解析します。");
            Log.info("   より古いレベルが要る場合は、古い版のJDTを使ってください。");
        }
        // 依存jarは library.folders でフォルダごと指定できる。ここで出すのは
        // 実際にJDTへ渡す「*.jar に展開した後」の一覧なので、
        // フォルダ指定がjar単位に展開されているかを確認できる
        String[] classpath = layout.classpathArray();
        Log.info("依存jar: " + classpath.length + " 件");
        for (String cp : classpath) {
            Log.info("  " + cp);
        }
    }

    /** フェーズ1: 解析とキャッシュ更新（1ファイルずつ書き出して破棄） */
    private static void analyzeSources(Config config, ProjectLayout layout) throws Exception {
        Log.blank();
        Log.info("=== フェーズ1/3: ソース解析 ===");
        CachePhaseResult result = new CacheUpdater(layout, config).run();
        Log.info("ソース解析: 再利用=" + result.reused
                + " 新規解析=" + result.parsed + reanalysisBreakdown(result)
                + " 失敗=" + result.failed);
        if (result.unresolved > 0) {
            Log.info("※ 型解決できなかった呼び出しが " + result.unresolved + " 件あります。");
            Log.info("   多い場合は library.folders の設定漏れ（依存jar不足）が疑われます。");
            if (!config.libraryFolders.isEmpty()) {
                Log.info("   Maven / Gradle のプロジェクトなら、library.folders を空欄にすると pom.xml / build.gradle から自動取得します。");
            }
            Log.info("   jar を足せば、次回の実行で影響するファイルだけが解析し直されます。");
            Log.info("   解決できた呼び出しだけが call-hierarchy.csv に出るため、");
            Log.info("   件数が多いまま使うと呼び出し階層に抜けが出ます。");
        }
        Log.heap("フェーズ1完了");
    }

    /** 「新規解析」のうち、自分は変わっていないのに解析し直した件数の内訳 */
    private static String reanalysisBreakdown(CachePhaseResult result) {
        List<String> parts = new ArrayList<>();
        if (result.dependents > 0) {
            parts.add("依存先の変更による再解析=" + result.dependents);
        }
        if (result.libraryDependents > 0) {
            parts.add("依存jarの変更による再解析=" + result.libraryDependents);
        }
        return parts.isEmpty() ? "" : "（うち" + String.join("、", parts) + "）";
    }

    /** フェーズ2: キャッシュを2回スキャンしてCSRグラフを構築 */
    private static CallGraph buildGraph(Config config, ProjectLayout layout) throws Exception {
        Log.blank();
        Log.info("=== フェーズ2/3: グラフ構築と具象クラス解決 ===");
        List<String> sourceFolderOrder = new ArrayList<>();
        for (Path sourceFolder : layout.sourceFolders) {
            sourceFolderOrder.add(layout.relativeOf(sourceFolder));
        }
        return CallGraphBuilder.build(config.cacheFile, sourceFolderOrder);
    }

    /** フェーズBの拡張（具象クラスの候補を返すもの）を読み込んで初期化する */
    private static List<TypeCandidateProvider> loadProviders(Config config) {
        List<TypeCandidateProvider> providers =
                Plugins.load(config.candidateProviderClasses, TypeCandidateProvider.class);
        for (TypeCandidateProvider provider : providers) {
            try {
                provider.init(config.raw, config.configDir);
            } catch (RuntimeException e) {
                Log.warn("provider の初期化に失敗: " + provider.getClass().getName() + " (" + e + ")");
            }
        }
        return providers;
    }

    /**
     * フェーズ3: methods.csv と call-hierarchy.csv を書く。
     * 呼び出し階層・型解決に失敗した呼び出し・外部jarからの被参照は、同じ call-hierarchy.csv に出す。
     *
     * @return call-hierarchy.csv に書いた行数
     */
    private static long writeReports(Config config, CallGraph graph, CallResolver resolver)
            throws Exception {
        Log.blank();
        Log.info("=== フェーズ3/3: 出力 ===");
        int[] entries = EntryPoints.select(graph, resolver, config);

        InventoryReport.Stats inventory = InventoryReport.writeMethods(graph, resolver, config, entries);
        Log.info(inventory.toString());
        Log.info("メソッド一覧: " + config.methodsCsv);

        Log.info("エントリポイント数: " + entries.length);
        if (entries.length == 0 && !config.wholeProjectMode) {
            Log.info("  ※ entry.packages の指定を確認してください（パッケージ名・ワイルドカード）");
        }
        if (config.wholeProjectMode) {
            Log.info("  ※ 起点候補は「呼び出し元が無いメソッド」です。画面入口のほかに");
            Log.info("     デッドコード・テスト・リフレクション経由が混ざるため、");
            Log.info("     methods.csv の inDegree / outDegree / role 列で仕分けてください。");
        }

        long rows;
        try (CallHierarchyCsvWriter writer = new CallHierarchyCsvWriter(
                config.outputCsv, config.outputEncoding, config.outputBom)) {
            StreamingTreeWalker walker = new StreamingTreeWalker(graph, resolver, config, writer);
            rows = walker.walkAll(entries);
            if (config.dataflowEnabled && walker.anyDataflowHits()) {
                Log.info("データフローで具象クラスを特定: "
                        + "new された型から " + walker.newHits() + " 件 / "
                        + "ファクトリの戻り値から " + walker.factoryHits() + " 件 / "
                        + "呼び出し元から渡された引数から " + walker.paramHits() + " 件 / "
                        + "コンストラクタ注入されたフィールドから " + walker.fieldHits() + " 件");
            }
            if (walker.reflectionHits() > 0) {
                Log.info("リフレクション（Class.forName / getMethod / Method.invoke / newInstance）の"
                        + "呼び出し先を特定: " + walker.reflectionHits() + " 件");
            }

            // 型解決に失敗した呼び出しも、抜け落ちた事実が分かるよう行として残す
            rows += UnresolvedReport.write(graph, config, writer);

            if (!config.externalLibraryFolders.isEmpty()) {
                Log.blank();
                Log.info("=== 外部jarからの被参照スキャン ===");
                ExternalUsageScanner.Stats ex = ExternalUsageScanner.scan(graph, config, writer);
                Log.info(ex.toString());
                rows += ex.hits + ex.implicitCtors;
                if (ex.unmatched > 0) {
                    Log.info("※ 自分の型への参照なのにメソッドが一致しなかったものが "
                            + ex.unmatched + " 件あります。");
                    Log.info("   相手が古い版のjarに対してビルドされている可能性があるため、");
                    Log.info("   「使われていない」と即断せず確認してください。");
                }
            }
        }
        Log.heap("フェーズ3完了");
        return rows;
    }
}
