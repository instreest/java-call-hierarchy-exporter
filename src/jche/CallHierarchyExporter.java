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
import jche.config.Config;
import jche.config.Plugins;
import jche.config.ProjectLayout;
import jche.config.ToolRoot;
import jche.external.ExternalUsageScanner;
import jche.graph.CallGraph;
import jche.graph.CallResolver;
import jche.graph.EntryPoints;
import jche.server.Server;
import jche.report.CallConditionsReport;
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

    /** 引数を省略したときの設定ファイル（作業ディレクトリからの相対） */
    private static final String DEFAULT_CONFIG = "config/config.properties";

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
            System.err.println("設定ファイル（config.properties）のパスが指定されていません。");
            System.err.println("既定値の「" + DEFAULT_CONFIG + "」で実行します。");
            configPaths.add(Paths.get(DEFAULT_CONFIG));
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
            Log.warn("このツールのプロジェクトフォルダ（src/jche/CallHierarchyExporter.java のある場所）を"
                    + "作業ディレクトリの上位に見つけられません。キャッシュは作業ディレクトリの下に作ります: "
                    + toolRoot.dir.resolve(Config.DEFAULT_CACHE_DIR_NAME));
        }

        List<String> summary = new ArrayList<>();
        int failed = 0;
        for (int i = 0; i < configPaths.size(); i++) {
            Path configPath = configPaths.get(i);
            if (configPaths.size() > 1) {
                Log.blank();
                Log.info("######## 設定 " + (i + 1) + "/" + configPaths.size() + ": " + configPath + " ########");
            }
            try {
                Path outputDir = runOne(configPath, toolRoot.dir);
                summary.add("OK    " + configPath + " -> " + outputDir);
                if (outputDirFile != null) {
                    writeOutputDirFile(outputDirFile, outputDir + System.lineSeparator(), true);
                }
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
            Log.warn(OUTPUT_DIR_FILE_ENV + " のファイルに書けません: " + file + " (" + e + ")");
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
        Log.info("=== 追加: 呼び出しに効いている条件（conditions.target="
                + config.conditionsTarget + "） ===");
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
        Log.info("設定: " + config.configPath);
        Log.info("プロジェクトルート: " + config.projectRoot);
        Log.info("出力フォルダ: " + config.outputDir);
        Log.info("キャッシュ: " + config.cacheDir);
        Files.copy(config.configPath, config.outputDir.resolve(config.configPath.getFileName()),
                StandardCopyOption.REPLACE_EXISTING);

        AnalysisSnapshot snapshot = Exporter.analyze(config);

        long rows = writeReports(config, snapshot.graph(), snapshot.resolver());

        if (!config.conditionsTarget.isEmpty()) {
            runConditions(config);
        }

        Log.blank();
        Log.info("呼び出し階層: " + config.outputCsv + "（" + rows + " 行）");
        Log.info("実行ログ: " + config.logFile);
        Log.info("完了 (" + (System.currentTimeMillis() - start) + " ms)");
        return config.outputDir;
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
        // methods.csv は呼び出し階層を書いた後に出す。「階層CSVに1行も出なかったメソッド」
        // （打ち切りで消えた部分木など）を inHierarchy / absentCause 列に載せるため、
        // 探索の結果が要る
        StreamingTreeWalker walker;
        try (CallHierarchyCsvWriter writer = new CallHierarchyCsvWriter(
                config.outputCsv, config.outputEncoding, config.outputBom)) {
            walker = new StreamingTreeWalker(graph, resolver, config, writer);
            rows = walker.walkAll(entries);
            if (config.dataflowEnabled && walker.anyDataflowHits()) {
                Log.info("データフローで具象クラスを特定: "
                        + "new された型から " + walker.newHits() + " 件 / "
                        + "ファクトリの戻り値から " + walker.factoryHits() + " 件 / "
                        + "呼び出し元から渡された引数から " + walker.paramHits() + " 件 / "
                        + "コンストラクタ注入されたフィールドから " + walker.fieldHits() + " 件");
            }
            if (walker.prunedCalls() > 0) {
                Log.info("条件分岐の静的解析で「その経路では呼ばれない」と判定して打ち切り: "
                        + walker.prunedCalls() + " 件");
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

        InventoryReport.Stats inventory =
                InventoryReport.writeMethods(graph, resolver, config, entries, walker);
        Log.info(inventory.toString());
        Log.info("メソッド一覧: " + config.methodsCsv);
        if (inventory.prunedOut() > 0) {
            Log.info("  ※ 条件分岐の打ち切りで階層CSVに出ないメソッドは "
                    + inventory.prunedOut() + " 件です。");
            Log.info("     methods.csv の inHierarchy / absentCause 列で一覧できます。");
        }

        Log.heap("フェーズ3完了");
        return rows;
    }
}
