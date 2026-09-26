// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche;

// ---------------------------------------------------------------------------
// JBang 用の指示行（jbang で実行するときだけ意味を持つ。javac / java には単なるコメント）
//
// DEPS: 依存はこの1行だけ。推移的な依存（org.eclipse.platform.* 等）は
//       Maven Central の POM から自動で解決される。JDTの版を変えるときはここを書き換える。
//         3.46.0 … JDK 17以上で動作。ソースは Java 26 まで解析可
//         3.33.0 … JDK 11以上で動作。ソースは Java 19 まで解析可
//       解析対象ソースのJavaバージョンは、この版とは別に設定ファイル（config/config.properties）の
//       source.level で指定する（未指定なら、この版が対応する最大値）。
// JAVA: このツール自身を動かすJDK。25 に固定するのは、JDTが「自分が動いている
//       JVMのブートクラスパス」を解析対象のクラスパスに含めるため、実行JDKが
//       変わると解析結果が変わるから。手元に25が無ければ jbang が取得する。
// SOURCES: 本体は src/jche 配下のサブパッケージに分かれている。jbang はこの指定で
//       それらも一緒にコンパイルする（このファイルからの相対。* が直下、**/ が下の階層）。
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
//SOURCES *.java **/*.java

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.core.JavaCore;

import jche.analysis.CachePhaseResult;
import jche.analysis.CallConditionScanner;
import jche.analysis.CacheUpdater;
import jche.cli.ConfigCatalog;
import jche.config.Config;
import jche.config.Plugins;
import jche.config.ProjectLayout;
import jche.config.ToolRoot;
import jche.external.ExternalUsageScanner;
import jche.graph.CallGraph;
import jche.graph.CallResolver;
import jche.graph.EntryPoints;
import jche.graph.UnresolvedCalls;
import jche.server.Server;
import jche.report.CallConditionsReport;
import jche.report.CallHierarchyCsvWriter;
import jche.report.ContractSuggestions;
import jche.report.InventoryReport;
import jche.report.StreamingTreeWalker;
import jche.report.UnresolvedReport;
import jche.util.HeapWatch;
import jche.util.Log;
import jche.util.Messages;
import jche.util.Warnings;

/**
 * java-call-hierarchy-exporter のエントリポイント。
 *
 * Javaプロジェクトを対象に、メソッド呼び出し階層を一括抽出してCSV出力する。
 * Eclipse IDE の起動は不要で、通常のJavaアプリとして動作する。
 *
 * 使い方（設定ファイルのパスを引数で渡す。複数渡せば順に処理する。省略時は config/config.properties）:
 * <pre>
 *   jbang src/jche/CallHierarchyExporter.java config/config.properties
 *   jbang src/jche/CallHierarchyExporter.java config/projA.properties config/projB.properties
 *   java -cp "bin;lib/*" jche.CallHierarchyExporter config/config.properties
 * </pre>
 * 対話モード（メニューで設定ファイルを選んで実行する）はプロジェクト直下の {@code java-call-hierarchy-exporter.sh} / {@code java-call-hierarchy-exporter.cmd} から
 * 起動する（{@code src/jche/Jche.java}）。解析の処理そのものは同じで、{@link #runAll} を共有する。
 * 設定ファイルごとに、その設定ファイルのフォルダを起点にした output.folder（既定 . ＝設定ファイルと同じフォルダ）の下へ
 * {@code <解析開始日時>_<プロジェクト名>/} を作り、CSV・設定ファイルの複製・実行ログ（run.log）を書く。
 * キャッシュは出力フォルダではなく、このツールのプロジェクトフォルダの .cache/ の下に
 * 解析対象プロジェクトごとに置く（{@link jche.config.ToolRoot}、{@link Config}）。
 * 1つの設定が失敗しても残りは処理し、最後にまとめて結果を出す。1つでも失敗すれば終了コードは 1。
 * 環境変数 {@code JCHE_OUTPUT_DIR_FILE} にファイルを指定すると、成功した設定の出力フォルダを
 * そのファイルへ1行ずつ書く（CI から実行結果の場所を受け取るための出口。{@link #OUTPUT_DIR_FILE_ENV}）。
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
 *       全件保持も不要。ヒープ常駐は「ソースファイルのパス・サイズ」のみ。</li>
 *   <li><b>エッジをオブジェクトで持たない</b>（フェーズ2）
 *       メソッドを int の ID に内部化し、呼び出し関係を CSR 形式のプリミティブ配列で持つ。
 *       オブジェクト2個＋文字列8本（数百バイト）だったものが int 2個（8バイト）になる。</li>
 *   <li><b>ツリーを組み立てない</b>（フェーズ3）
 *       深さ優先探索しながら1行ずつCSVへ書き出す。探索中にヒープへ載るのは
 *       「現在の経路（深さぶんの配列）」だけ。</li>
 * </ol>
 */
public class CallHierarchyExporter {

    /**
     * 引数を省略したときの設定ファイル（作業ディレクトリからの相対）。
     *
     * 実際に使うのは {@link ConfigCatalog#defaultConfig}（{@code config/config.properties} が無ければ
     * {@code config/jche.properties}）。この定数は、どちらも無いときにメッセージへ出す名前でもある。
     */
    private static final String DEFAULT_CONFIG =
            ConfigCatalog.CONFIGS_DIR_NAME + "/" + ConfigCatalog.DEFAULT_CONFIG_NAME;

    /**
     * 出力フォルダの場所を書き出すファイルを指す環境変数。
     *
     * CI（GitHub Actions の {@code action.yml} 等）のように、実行後に「結果がどこにできたか」を
     * 機械的に受け取りたい呼び出し元のための出口。設定ファイルごとの出力フォルダの絶対パスを
     * 成功した順に1行ずつ、UTF-8 で追記する。指定が無ければ何もしない。
     * ログを読ませないのは、標準出力の文字コードが環境依存（コンソール依存）で、
     * メッセージも日本語のため、外部から機械的に読む先としては不安定なため。
     */
    private static final String OUTPUT_DIR_FILE_ENV = "JCHE_OUTPUT_DIR_FILE";

    /** サーバーモードで起動するときの第1引数（{@link jche.server.Server}） */
    private static final String SERVER_OPTION = "--server";

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && SERVER_OPTION.equals(args[0])) {
            // サーバーモード。Eclipse プラグインが別プロセス・別 JDK で解析させるために使う。
            // キャッシュの置き場所は引数で指定でき、省略時はツールのフォルダの下
            Path cacheRoot = (args.length > 1)
                    ? Paths.get(args[1])
                    : ToolRoot.locate(CallHierarchyExporter.class).dir;
            System.exit(Server.run(cacheRoot));
        }

        // 設定ファイルのパスは引数で受け取る（複数可）。jbang はスクリプト名より後ろの
        // 引数をそのまま渡してくるので、jbang 経由でも java 直接実行でも同じ形
        List<Path> configPaths = new ArrayList<>();
        for (String a : args) {
            configPaths.add(Paths.get(a));
        }
        if (configPaths.isEmpty()) {
            Path defaultConfig = ConfigCatalog.defaultConfig(Paths.get(""));
            System.err.println(Messages.format("exporter.noConfigArg", DEFAULT_CONFIG));
            System.err.println(Messages.format("exporter.useDefaultConfig", defaultConfig));
            configPaths.add(defaultConfig);
        }

        int failed = runAll(configPaths, ToolRoot.locate(CallHierarchyExporter.class));
        if (failed > 0) {
            System.exit(1);
        }
    }

    /**
     * 設定ファイルを順に処理する。対話モード（{@code src/jche/Jche.java}）からも同じ処理を呼ぶため、
     * {@link #main} から切り出してある。ここでは {@code System.exit} しない。
     *
     * 設定ファイルごとに独立して処理する。1つが失敗しても残りは続け、最後にまとめて報告する。
     * {@link #OUTPUT_DIR_FILE_ENV} が指定されていれば、成功した設定の出力フォルダをそこへ書き出す
     * （対話モードから呼んだときも同じ）。
     *
     * @param configPaths 設定ファイル（渡した順に処理する）
     * @param toolRoot    このツールのプロジェクトフォルダ（キャッシュの置き場所）
     * @return 失敗した設定の数
     */
    public static int runAll(List<Path> configPaths, ToolRoot toolRoot) {
        Path outputDirFile = outputDirFile();
        if (outputDirFile != null) {
            // 前回の実行の内容が残っていると、失敗した実行の後に古い出力フォルダを掴んでしまう
            writeOutputDirFile(outputDirFile, "", false);
        }

        if (!toolRoot.found) {
            Log.warn(Messages.format("exporter.toolRootNotFound",
                    toolRoot.dir.resolve(Config.DEFAULT_CACHE_DIR_NAME)));
        }

        List<String> summary = new ArrayList<>();
        int failed = 0;
        for (int i = 0; i < configPaths.size(); i++) {
            Path configPath = configPaths.get(i);
            if (configPaths.size() > 1) {
                Log.blank();
                Log.info(Messages.format("exporter.configHeader", i + 1, configPaths.size(), configPath));
            }
            try {
                Path outputDir = runOne(configPath, toolRoot.dir);
                summary.add("OK    " + configPath + " -> " + outputDir);
                if (outputDirFile != null) {
                    writeOutputDirFile(outputDirFile, outputDir + System.lineSeparator(), true);
                }
            } catch (Throwable t) {
                failed++;
                Warnings.error(Warnings.Topic.FAILED, Messages.format("exporter.configFailed", configPath), t);
                summary.add("FAIL  " + configPath + " : " + t);
            } finally {
                // 確認してほしいことがあれば warnings.txt に書き、最後に目立つよう知らせる。
                // 失敗した実行でも、出力フォルダを作れていれば書く
                Path warnings = Warnings.end();
                if (warnings != null) {
                    Log.warn(Messages.format("exporter.warnings.written", warnings));
                }
                Log.detachFile();
            }
        }

        if (configPaths.size() > 1) {
            Log.blank();
            Log.info(Messages.format("exporter.results", configPaths.size() - failed, configPaths.size()));
            for (String line : summary) {
                Log.info("  " + line);
            }
        }
        return failed;
    }

    /** {@link #OUTPUT_DIR_FILE_ENV} で指定されたファイル。指定が無ければ null */
    private static Path outputDirFile() {
        String raw = System.getenv(OUTPUT_DIR_FILE_ENV);
        return (raw == null || raw.trim().isEmpty()) ? null : Paths.get(raw.trim()).toAbsolutePath();
    }

    /**
     * 出力フォルダの一覧を書き出す。書けなくても解析そのものは成功しているので、警告にとどめて続ける
     * （CSV は出来ているのに、受け渡し用のファイルが書けないことだけで実行を失敗にはしない）。
     *
     * @param append false なら作り直す（実行の最初に空にする）、true なら1行追記する
     */
    private static void writeOutputDirFile(Path file, String text, boolean append) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (append) {
                Files.writeString(file, text, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } else {
                Files.writeString(file, text, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            Log.warn(Messages.format("exporter.outputDirFileFailed", OUTPUT_DIR_FILE_ENV, file, e));
        }
    }

    /**
     * 設定に {@code conditions.target} があるときに、<b>通常の出力に追加で</b>
     * 呼び出しに効いている条件の一覧（{@code call-conditions.csv}）を書く
     * （{@code docs/call-conditions.md}）。
     *
     * 通常の解析（キャッシュの更新・CSV の出力）はそのまま行った後に呼ぶ。モードを増やさず、
     * 出力が1つ増えるだけにするための並び。打ち切りの判定に使う条件だけでなく、
     * 判定できない条件も並べるので、対象のファイルだけを記録用モードでもう一度パースする
     * （キャッシュには書かない。理由は {@code docs/call-conditions-qa.md} の Q1・Q2）。
     *
     * 対象が見つからない・該当する呼び出しが無いときは警告にとどめる。解析そのものは
     * 成功しており、追加の出力が空振りしただけなので、実行を失敗にはしない。
     */
    private static void runConditions(Config config) throws Exception {
        Log.blank();
        Log.info(Messages.format("exporter.conditionsHeader", config.conditionsTarget));
        ProjectLayout layout = new ProjectLayout(config);
        CallConditionScanner.Result result =
                CallConditionScanner.scan(config, layout, config.conditionsTarget);
        CallConditionsReport.write(config, config.conditionsTarget, result);
    }

    /**
     * 設定ファイル1つ分の処理。出力フォルダを作り、設定ファイルの複製と実行ログをそこに置いてから解析する。
     *
     * 設定に {@code conditions.target} があるときは、通常の出力に<b>追加で</b>
     * 呼び出しに効いている条件の一覧を書く（{@link #runConditions}）。解析そのものは変わらない。
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
        Warnings.begin(config.outputDir.resolve(Warnings.FILE_NAME));
        Log.info(Messages.format("exporter.config", config.configPath));
        Log.info(Messages.format("exporter.projectRoot", config.projectRoot));
        Log.info(Messages.format("exporter.outputDir", config.outputDir));
        Log.info(Messages.format("exporter.cacheDir", config.cacheDir));
        Files.copy(config.configPath, config.outputDir.resolve(config.configPath.getFileName()),
                StandardCopyOption.REPLACE_EXISTING);

        // ヒープの見張りは設定ファイル1つ分の処理を丸ごと囲む。フェーズ1・2 の中
        // （{@link Exporter#analyze}）でも始めているが、入れ子は素通りするので、
        // ここが解除の責任を持ち、フェーズ3（CSV 出力）まで見張りが続く
        HeapWatch heapWatch = HeapWatch.start();
        try {
            // 型解決に失敗した呼び出しの一覧に出す行も、グラフを組むときに拾っておく（一時ファイル）。
            // CSV を書き終えたら（失敗しても）閉じて消す
            AnalysisSnapshot snapshot = Exporter.analyze(config, true);
            long rows;
            try (UnresolvedCalls unresolved = snapshot.unresolvedCalls()) {
                rows = writeReports(config, snapshot.graph(), snapshot.resolver(), unresolved);
            }

            if (!config.conditionsTarget.isEmpty()) {
                runConditions(config);
            }

            Log.blank();
            Log.info(Messages.format("exporter.callHierarchy", config.outputCsv, rows));
            Log.info(Messages.format("exporter.logFile", config.logFile));
            Log.info(Messages.format("exporter.done", System.currentTimeMillis() - start));
        } finally {
            heapWatch.close();
        }
        return config.outputDir;
    }

    /**
     * フェーズ3: methods.csv と call-hierarchy.csv を書く。
     * 呼び出し階層・型解決に失敗した呼び出し・外部jarからの被参照は、同じ call-hierarchy.csv に出す。
     *
     * @param unresolved 型解決に失敗した呼び出しの一覧に出す行（グラフを組むときに拾ったもの）
     * @return call-hierarchy.csv に書いた行数
     */
    private static long writeReports(Config config, CallGraph graph, CallResolver resolver,
                                     UnresolvedCalls unresolved)
            throws Exception {
        Log.blank();
        Log.info(Messages.get("exporter.phase3"));
        int[] entries = EntryPoints.select(graph, resolver, config);

        Log.info(Messages.format("exporter.entryCount", entries.length));
        if (entries.length == 0 && !config.wholeProjectMode) {
            Log.info(Messages.get("exporter.entryCheck"));
        }
        if (config.wholeProjectMode) {
            Log.info(Messages.get("exporter.entryNote1"));
            Log.info(Messages.get("exporter.entryNote2"));
            Log.info(Messages.get("exporter.entryNote3"));
        }

        long rows;
        // methods.csv は呼び出し階層を書いた後に出す。「階層CSVに1行も出なかったメソッド」
        // （打ち切りで消えた部分木など）を inHierarchy / absentCause 列に載せるため、
        // 探索の結果が要る
        StreamingTreeWalker walker;
        try (CallHierarchyCsvWriter writer = new CallHierarchyCsvWriter(
                config.outputCsv, config.outputEncoding, config.outputBom)) {
            walker = new StreamingTreeWalker(graph, resolver, config, writer);
            rows = walker.walkAll(entries);
            if (config.dataflowEnabled && walker.anyDataflowHits()) {
                Log.info(Messages.format("exporter.dataflowHits", walker.newHits(), walker.factoryHits(),
                        walker.paramHits(), walker.fieldHits()));
            }
            if (walker.callbackHits() > 0) {
                Log.info(Messages.format("exporter.callbackHits", walker.callbackHits()));
            }
            if (walker.prunedCalls() > 0) {
                Log.info(Messages.format("exporter.prunedCalls", walker.prunedCalls()));
            }
            if (walker.reflectionHits() > 0) {
                Log.info(Messages.format("exporter.reflectionHits", walker.reflectionHits()));
            }

            // 型解決に失敗した呼び出しも、抜け落ちた事実が分かるよう行として残す
            rows += UnresolvedReport.write(graph, unresolved, writer);

            if (!config.externalLibraryFolders.isEmpty()) {
                Log.blank();
                Log.info(Messages.get("exporter.externalScan"));
                ExternalUsageScanner.Stats ex = ExternalUsageScanner.scan(graph, config, writer);
                Log.info(ex.toString());
                rows += ex.hits + ex.implicitCtors;
                if (ex.unmatched > 0) {
                    Log.info(Messages.format("exporter.externalUnmatched", ex.unmatched));
                    Log.info(Messages.get("exporter.externalUnmatched2"));
                    Log.info(Messages.get("exporter.externalUnmatched3"));
                }
            }
        }

        InventoryReport.Stats inventory =
                InventoryReport.writeMethods(graph, resolver, config, entries, walker);
        Log.info(inventory.toString());
        Log.info(Messages.format("exporter.methodsCsv", config.methodsCsv));
        if (inventory.prunedOut() > 0) {
            Log.info(Messages.format("exporter.prunedOut", inventory.prunedOut()));
            Log.info(Messages.get("exporter.prunedOut2"));
        }

        // 契約表・対応表が効いたかを知らせる。methods.csv の出力でグラフ全体を走査し終えた
        // ここで初めて「一度も当たらなかった」と言える
        resolver.reportUsage();

        // 絞れなかった呼び出しは、そのまま貼れる契約表の行にしておく。
        // 「候補N件」と言われても何をどこに書けば絞れるかは出力から分からないため
        writeContractSuggestions(config, walker);

        Log.heap(Messages.get("exporter.heap.phase3"));
        return rows;
    }

    /** 絞れなかった呼び出しから契約表のひな形を書く。1 件も無ければ何も書かない */
    private static void writeContractSuggestions(Config config, StreamingTreeWalker walker) {
        ContractSuggestions suggestions = walker.suggestions();
        if (suggestions.isEmpty()) {
            return;
        }
        try {
            int lines = suggestions.write(config.contractsSuggestedFile);
            Log.info(Messages.format("report.suggestions.written",
                    config.contractsSuggestedFile, lines));
            Log.info(Messages.get("report.suggestions.how1"));
            Log.info(Messages.get("report.suggestions.how2"));
        } catch (IOException e) {
            // ひな形が書けなくても解析の結果は正しい。出せなかったことだけ知らせる
            Log.warn(Messages.format("report.suggestions.failed",
                    config.contractsSuggestedFile, e));
        }
    }
}
