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

// ---------------------------------------------------------------------------
// JBang 用の指示行（jbang で実行するときだけ意味を持つ。javac / java には単なるコメント）
//
// DEPS: 依存はこの1行だけ。推移的な依存（org.eclipse.platform.* 等）は
//       Maven Central の POM から自動で解決される。JDTの版を変えるときはここを書き換える。
//         3.46.0 … JDK 17以上で動作。ソースは Java 26 まで解析可
//         3.33.0 … JDK 11以上で動作。ソースは Java 19 まで解析可
//       解析対象ソースのJavaバージョンは、この版とは別に config.properties の
//       source.level で指定する（未指定なら、この版が対応する最大値）。
// JAVA: このツール自身を動かすJDK。25 に固定するのは、JDTが「自分が動いている
//       JVMのブートクラスパス」を解析対象のクラスパスに含めるため、実行JDKが
//       変わると解析結果が変わるから。手元に25が無ければ jbang が取得する。
// SOURCES: 本体は src/jche 配下のパッケージに分かれている。jbang はこの指定で
//       それらも一緒にコンパイルする。
//
// 標準出力の文字コードは指定しない（//JAVA_OPTIONS を置かない）。JDK 19以降、
// System.out はコンソール自身の文字コードで書き出すため、指定しないのが最も
// 確実に読める。UTF-8に固定すると、MS932のままのWindowsコンソールで
// ログだけが文字化けする。端末側を chcp でUTF-8に切り替える方法も採らない。
// 日本語Windowsではコードページの切り替え自体が画面を消去してしまう。
// CSV等のファイル入出力は常に明示的な文字コードを使うので、いずれの影響も受けない。
// ---------------------------------------------------------------------------
//DEPS org.eclipse.jdt:org.eclipse.jdt.core:3.46.0
//JAVA 25
//SOURCES jche/**/*.java

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.core.JavaCore;

import jche.analysis.CachePhaseResult;
import jche.analysis.CacheUpdater;
import jche.config.Config;
import jche.config.Plugins;
import jche.config.ProjectLayout;
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
 * java-call-hierarchy-exporter のエントリポイント。
 *
 * Javaプロジェクトを対象に、メソッド呼び出し階層を一括抽出してCSV出力する。
 * Eclipse IDE の起動は不要で、通常のJavaアプリとして動作する。
 *
 * 使い方（設定ファイルのパスを第1引数で渡す。省略時は config/config.properties）:
 * <pre>
 *   jbang src/CallHierarchyExporter.java config/config.properties
 *   java -cp "bin;lib/*" CallHierarchyExporter config/config.properties
 * </pre>
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
public class CallHierarchyExporter {

    public static void main(String[] args) throws Exception {
        // 設定ファイルのパスは第1引数で受け取る。jbang はスクリプト名より後ろの
        // 引数をそのまま渡してくるので、jbang 経由でも java 直接実行でも同じ形
        String configPath = (args.length > 0) ? args[0] : "config/config.properties";
        if (args.length == 0) {
            System.err.println("config.propertiesのパスが指定されていません。");
            System.err.println("既定値の「config/config.properties」で実行します。");
        }

        long start = System.currentTimeMillis();
        Config config = new Config(Paths.get(configPath));
        Log.info("設定: " + Paths.get(configPath).toAbsolutePath().normalize());
        Log.info("プロジェクトルート: " + config.projectRoot);

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
        Log.info("完了 (" + (System.currentTimeMillis() - start) + " ms)");
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
