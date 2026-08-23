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

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * java-call-hierarchy-exporter
 *
 * Eclipseプロジェクトを対象に、メソッド呼び出し階層を一括抽出してCSV出力する。
 * Eclipse IDE の起動は不要で、通常のJavaアプリとして動作する。
 *
 * 使い方:
 *   java -cp "java-call-hierarchy-exporter.jar:lib/*" \
 *        CallHierarchyExporter config.propertiesのパス
 *   （Windowsの場合、クラスパス区切りは ; ）
 *
 * ====================================================================
 * メモリ設計（OutOfMemoryError を避けるための三本柱）
 * ====================================================================
 *
 * 【1】解析結果をヒープに溜めない ── フェーズ1
 *   1ファイル解析するたびに結果をキャッシュファイルへ直接書き出して破棄する。
 *   キャッシュ更新は「旧キャッシュを先頭から読み、まだ有効なブロックだけを
 *   新キャッシュへ書き写す」ストリーミングマージで行うため、ランダムアクセスも
 *   全件保持も不要。ヒープ常駐は「ソースファイルのパス・更新時刻・サイズ」のみ。
 *
 * 【2】エッジをオブジェクトで持たない ── フェーズ2
 *   メソッドを int の ID に内部化（intern）し、呼び出し関係を
 *   CSR（Compressed Sparse Row）形式のプリミティブ配列で保持する。
 *     offsets[callerId] .. offsets[callerId+1] が、その呼び出し元のエッジ範囲
 *     calleeIds[e] / callLines[e] が各エッジの内容
 *   オブジェクト2個＋文字列8本（数百バイト）だったものが int 2個（8バイト）になる。
 *   なお現在の出力は下流のみ使うため、逆引き（呼び出し元）CSRは構築していない。
 *   必要になった場合は同じ手順で incoming 側の配列を作れば対応できる。
 *
 * 【3】ツリーを組み立てない ── フェーズ3
 *   深さ優先探索しながら1行ずつCSVへ書き出す。ツリー全体をNodeオブジェクトで
 *   materialize しない。max.depth=6 / max.children.per.node=50 の設定でも
 *   最悪 50^6 ≒ 1.5億ノードになりうるため、ここは必須の対策。
 *   探索中にヒープへ載るのは「現在の経路（深さぶんの配列）」だけ。
 */
public class CallHierarchyExporter {

    // ================================================================
    // 標準出力ログ
    // ================================================================

    /** 経過時間表示の基準点。クラス初期化（実行開始）時点に固定する */
    private static final long START_NANOS = System.nanoTime();

    /** 行頭に [分:秒.ミリ秒s] を付けて標準出力へ1行書き出す */
    private static void log(Object message) {
        System.out.println(elapsedStamp() + " " + message);
    }

    private static String elapsedStamp() {
        long ms = (System.nanoTime() - START_NANOS) / 1_000_000L;
        return String.format("[%02d:%02d.%03ds]", ms / 60000, (ms / 1000) % 60, ms % 1000);
    }

    // ================================================================
    // エントリポイント
    // ================================================================

    public static void main(String[] args) throws Exception {
    	String confitPath = (args.length > 0) ? args[0] : "config/config.properties";
        if (!(args.length > 0)) {
            System.err.println("config.propertiesのパスが指定されていません。");
            System.err.println("既定値の「config/config.properties」で実行します。");
        }

        long start = System.currentTimeMillis();
        Config config = new Config(Paths.get(confitPath));
        log("[main] 設定: " + Paths.get(confitPath).toAbsolutePath().normalize());
        log("[main] プロジェクトルート: " + config.projectRoot);

        EclipseProjectLayout layout = new EclipseProjectLayout(config);
        log("[main] ソースフォルダ: " + layout.sourceFolders);
        log("[main] クラスパス数: " + layout.classpathEntries.size());

        // --- フェーズ1: 解析とキャッシュ更新（1ファイルずつ書き出して破棄） ---
        System.out.println();
        log("=== フェーズ1/3: ソース解析 ===");
        long t1 = System.currentTimeMillis();
        CachePhaseResult phase1 = new CacheUpdater(layout, config).run();
        log("[main] 解析: 再利用=" + phase1.reused
                + " 新規解析=" + phase1.parsed + " 失敗=" + phase1.failed);
        log("[main] 未解決呼び出し: " + phase1.unresolvedCount
                + " 件 -> " + config.unresolvedCsv);
        log("[main] フェーズ1 所要 "
                + Progress.fmt((System.currentTimeMillis() - t1) / 1000.0));
        printHeap("フェーズ1完了");

        // --- フェーズ2: キャッシュを2回スキャンしてCSRグラフを構築 ---
        System.out.println();
        log("=== フェーズ2/3: グラフ構築と具象クラス解決 ===");
        long t2 = System.currentTimeMillis();
        CallGraph graph = CallGraph.buildFrom(config.cacheFile);
        List<String> sourceFolderOrder = new ArrayList<>();
        for (Path sf : layout.sourceFolders) {
            sourceFolderOrder.add(layout.relativeOf(sf));
        }
        graph.setSourceFolderOrder(sourceFolderOrder);
        List<TypeCandidateProvider> providers =
                Plugins.load(config.candidateProviderClasses, TypeCandidateProvider.class);
        for (TypeCandidateProvider pv : providers) {
            try {
                pv.init(config.raw, config.configDir);
            } catch (RuntimeException e) {
                log("[WARN] provider の初期化に失敗: "
                        + pv.getClass().getName() + " (" + e + ")");
            }
        }
        graph.setProviders(providers);
        log("[main] 型数=" + graph.typeCount()
                + " メソッド数=" + graph.methodCount()
                + " エッジ数=" + graph.edgeCount());
        ResolutionStats stats = ResolutionReport.write(graph, config);
        log("[main] 解決内訳: " + stats
                + " -> " + config.resolutionsCsv);
        log("[main] フェーズ2 所要 "
                + Progress.fmt((System.currentTimeMillis() - t2) / 1000.0));
        printHeap("フェーズ2完了");

        // --- フェーズ3: エントリポイントごとにDFSしながら1行ずつ出力 ---
        System.out.println();
        log("=== フェーズ3/3: 出力 ===");
        long t3 = System.currentTimeMillis();
        int[] entries = graph.selectEntryPoints(config);
        if (config.wholeProjectMode) {
            log("[main] 全体モード（entry.packages 未指定）");
            InventoryStats inv = InventoryReport.writeMethods(graph, config, entries);
            long edgeRows = InventoryReport.writeEdges(graph, config);
            inv.edges = edgeRows;
            log("[main] " + inv);
            log("[main] メソッド一覧: " + config.methodsCsv);
            log("[main] エッジ一覧  : " + config.edgesCsv + "（" + edgeRows + " 行）");
            log("[main] 起点候補は「呼び出し元が無いメソッド」です。");
            log("       画面入口のほかにデッドコード・テスト・リフレクション経由が");
            log("       混ざるため、methods.csv の role 列で仕分けてください。");
        }
        if (!config.externalJars.isEmpty()) {
            System.out.println();
            log("=== 外部jarからの被参照スキャン ===");
            ExternalUsageStats ex = ExternalUsageScanner.scan(graph, config);
            log("[main] " + ex);
            log("[main] 被参照一覧: " + config.externalUsageCsv);
            if (ex.unmatched > 0) {
                log("[main] 未照合 : " + config.externalUnmatchedCsv);
                log("       自分の型への参照なのにメソッドが一致しなかったものです。");
                log("       相手が古い版のjarに対してビルドされている可能性があるため、");
                log("       「使われていない」と即断せず確認してください。");
            }
        }
        log("[main] エントリポイント数: " + entries.length);
        if (entries.length == 0 && !config.wholeProjectMode) {
            log("  ※ entry.packages の指定を確認してください（パッケージ名・ワイルドカード）");
        }
        if (config.wholeProjectMode && entries.length > 1000) {
            log("  ※ 起点が多いため call-hierarchy.csv が大きくなります。");
            log("     不要なら entry.auto=false でツリー生成を止められます。");
        }

        long rows;
        CallHierarchyCsvWriter writer = new CallHierarchyCsvWriter(
                config.outputCsv, config.outputEncoding, config.outputBom, config.outputDelimiter);
        try {
            rows = new StreamingTreeWalker(graph, config, writer).walkAll(entries);
        } finally {
            writer.close();
        }
        log("[main] フェーズ3 所要 "
                + Progress.fmt((System.currentTimeMillis() - t3) / 1000.0));
        printHeap("フェーズ3完了");

        System.out.println();
        log("[main] 出力: " + config.outputCsv + "（" + rows + " 行）");
        log("[main] 完了 (" + (System.currentTimeMillis() - start) + " ms)");
    }

    /**
     * 標準出力への進捗表示。
     *
     * 解析には時間がかかるため、「今どの処理を、全体の何件中どこまで進めているか」
     * 「直近の一定件数に何秒かかったか」を出して、止まっていないことが分かるようにする。
     * 直近の所要時間を出すのは、途中で急に遅くなる箇所（巨大ファイル、ハブメソッド等）を
     * 見つけやすくするため。
     */
    static final class Progress {

        /** 何件ごとに進捗を出すか */
        static int interval = 500;

        private final String label;
        private final long total;          // 0以下なら総数不明
        private final long startNanos = System.nanoTime();
        private long lastNanos = startNanos;
        private long lastDone;
        private long done;

        Progress(String label, long total) {
            this.label = label;
            this.total = total;
            log("[進捗] " + label + " 開始"
                    + (total > 0 ? ": 対象 " + total + " 件" : ""));
        }

        void step(long current) {
            done = current;
            if (done - lastDone >= interval) {
                report();
            }
        }

        private void report() {
            long now = System.nanoTime();
            double elapsed = (now - startNanos) / 1e9;
            double recent = (now - lastNanos) / 1e9;
            long recentCount = done - lastDone;
            StringBuilder sb = new StringBuilder();
            sb.append("[進捗] ").append(label).append(' ').append(done);
            if (total > 0) {
                sb.append('/').append(total);
                sb.append(String.format(" (%.1f%%)", done * 100.0 / total));
            } else {
                sb.append("件");
            }
            sb.append(String.format("  経過 %s", fmt(elapsed)));
            sb.append(String.format("  直近%d件 %s", recentCount, fmt(recent)));
            if (total > 0 && done > 0 && done < total) {
                sb.append(String.format("  残り約 %s", fmt(elapsed / done * (total - done))));
            }
            log(sb);
            lastNanos = now;
            lastDone = done;
        }

        long finish() {
            long ms = (System.nanoTime() - startNanos) / 1000000L;
            log("[進捗] " + label + " 完了: " + done + " 件 / " + fmt(ms / 1000.0));
            return ms;
        }

        static String fmt(double sec) {
            if (sec < 60) {
                return String.format("%.1fs", sec);
            }
            long s = (long) sec;
            return String.format("%d分%02ds", s / 60, s % 60);
        }
    }

    private static void printHeap(String label) {
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMb = rt.maxMemory() / (1024 * 1024);
        log("[heap] " + label + ": 使用 " + usedMb + "MB / 上限 " + maxMb + "MB");
    }

    // ================================================================
    // 拡張ポイント（利用者がプロジェクト固有の解決手法を差し込む場所）
    // ================================================================
    //
    // 具象クラスの特定方法はプロジェクトごとに異なるため、2つのフェーズに
    // 分けた差し込み口を用意している。必要な情報が手に入るタイミングが
    // 2つに分かれているため、1つのインターフェースにはまとめられない。
    //
    //   フェーズA（抽出時）  : ASTが手元にある。呼び出し箇所の局所的な証拠を拾う
    //                         例) DaoFactory.get("USER_DAO") の文字列リテラル
    //   フェーズB（構築時）  : 全体が見える。型階層や外部ファイルを使って確定する
    //                         例) "USER_DAO" -> jp.co.xxx.dao.UserDaoImpl の対応表
    //
    // 実装クラスは設定ファイルでFQNを列挙するとリフレクションで読み込まれる。
    //   resolver.hint.collectors=jp.co.xxx.FactoryKeyCollector
    //   resolver.candidate.providers=jp.co.xxx.FactoryMapProvider

    /** フェーズAが拾った証拠。キーと値だけの汎用の箱にしてある */
    public static final class Hint {
        public final String kind;
        public final String value;

        public Hint(String kind, String value) {
            this.kind = kind;
            this.value = value;
        }

        @Override
        public String toString() {
            return kind + "=" + value;
        }
    }

    /** フェーズAの出力先 */
    public interface HintSink {
        /**
         * @param scopeKey  この証拠が結び付く対象。ローカル変数なら
         *                  IVariableBinding.getKey()、レシーバ式なら "@開始位置"。
         *                  呼び出し箇所側が記録するキーと一致させる必要がある
         */
        void add(String scopeKey, String kind, String value);
    }

    /**
     * フェーズA: ASTから任意の証拠を拾ってキャッシュに残す。
     *
     * 実装例（ファクトリメソッド）:
     *   DaoFactory.get("USER_DAO") を見つけたら、その戻り値を受けている
     *   ローカル変数のキーに対して add(varKey, "FACTORY_KEY", "USER_DAO") する。
     */
    public interface CallSiteHintCollector {
        void collect(MethodInvocation node, CompilationUnit cu,
                     String callerMethodKey, HintSink sink);
    }

    /**
     * フェーズB: 宣言型と証拠から具象型の候補を返す。
     *
     * 実装例（ファクトリの対応表）:
     *   hints に FACTORY_KEY があれば、対応表を引いて具象クラスFQNを返す。
     */
    public interface TypeCandidateProvider {
        /** 設定ファイルの内容と、その置き場所（相対パス解決の起点）を受け取る */
        default void init(Properties config, Path configDir) {
        }

        /**
         * 静的束縛（段0）と判定された呼び出しにも、この拡張を適用するか。
         *
         * Javaの言語仕様上は、private/static/final・finalクラス・コンストラクタ・
         * super呼び出しは仮想ディスパッチされないため、DIコンテナのプロキシ
         * （CGLIBはサブクラス生成、JDK動的プロキシはインターフェース実装）でも
         * 実行される本体は変わらない。よって既定では段0を確定として扱う。
         *
         * ただし、バイトコード織り込み（AspectJのCTW等）や独自フレームワークの
         * 仕掛けによって、この前提が崩れる可能性は残る。そうした環境では
         * true を返すことで、段0の呼び出しにも解決を差し込める。
         *
         * 段0で打ち切ってしまうと拡張に到達せず、呼び出し階層がそこで
         * 切れてしまうため、この逃げ道を用意している。
         */
        default boolean appliesToStaticBound() {
            return false;
        }

        /**
         * @return 具象型のFQN配列。解決できない場合は null または空配列
         */
        String[] candidates(String declaredType, String signature, List<Hint> hints);

        /** CSVの由来ラベルに出る名前。例: "CUSTOM_FACTORY" */
        String label();
    }

    // ================================================================
    // 設定
    // ================================================================

    /**
     * 設定ファイルの読み込み。
     *
     * 相対パスは「この設定ファイルが置かれているディレクトリ」を起点に解決する。
     * 設定ファイルと関連ファイルをひとまとめに配置でき、どこから実行しても
     * 同じ結果になるようにするため。
     */
    static final class Config {

        enum ExcludeMode {
            /** 除外対象のノードとその配下をまるごと切り捨てる */
            PRUNE,
            /** 除外対象のノードは出力しないが、その先は親に繋ぎ直して辿り続ける */
            SKIP
        }

        final Path configDir;
        final Path projectRoot;
        final List<String> sourceFolderOverride;
        final List<Path> extraClasspathEntries;
        final String sourceEncoding;

        final List<PackagePattern> entryPatterns;
        final Pattern entryClassNamePattern;

        final List<PackagePattern> excludePatterns;
        final ExcludeMode excludeMode;

        /** 全体モードか（entry.packages 未指定） */
        final boolean wholeProjectMode;
        /** 全体モードで、呼び出し元が無いメソッドを自動的に起点にするか */
        final boolean entryAuto;
        /** 入次数がこの値以上のメソッドを HUB とみなす */
        final int hubThreshold;

        final int maxDepth;
        final int maxChildrenPerNode;
        final long maxRowsPerEntry;

        /** CHA候補をエッジとして記録するか（漏れ防止のため既定true） */
        final boolean chaRecord;
        /** CHA候補を呼び出し階層で展開するか。展開すると候補数^深さで爆発する */
        final boolean chaExpand;
        final int chaMaxCandidates;

        final List<String> hintCollectorClasses;
        final List<String> candidateProviderClasses;
        /** 拡張の init() に渡す。プロジェクト固有のキーを自由に読ませるため */
        final Properties raw;

        final boolean cacheEnabled;
        final Path cacheFile;

        final Path outputCsv;
        final Path unresolvedCsv;
        final Path resolutionsCsv;
        /** 全体モードの出力: メソッド一覧と全エッジ一覧 */
        final Path methodsCsv;
        final Path edgesCsv;
        /** 他チームのjar（自分のコードを呼んでいる側）。ファイルでもディレクトリでも可 */
        final List<Path> externalJars;
        final Path externalUsageCsv;
        final Path externalUnmatchedCsv;

        /** CSVの出力文字コード。Excelでそのまま開けるよう既定はMS932(Shift_JIS) */
        final Charset outputEncoding;
        /** output.encoding=UTF-8-BOM のとき、ファイル先頭にBOMを書くか */
        final boolean outputBom;
        /** 出力ファイルの区切り文字（既定はカンマ=CSV）。TABにするとタブ区切りになる */
        final String outputDelimiter;

        Config(Path configPath) throws IOException {
            Path abs = configPath.toAbsolutePath().normalize();
            Path dir = abs.getParent();
            this.configDir = (dir == null) ? Paths.get(".").toAbsolutePath().normalize() : dir;

            Properties p = new Properties();
            InputStreamReader r = new InputStreamReader(Files.newInputStream(abs), StandardCharsets.UTF_8);
            try {
                p.load(r);
            } finally {
                r.close();
            }

            this.projectRoot = resolvePath(require(p, "project.root"));
            this.sourceFolderOverride = splitList(p.getProperty("source.folders", ""));

            List<Path> extras = new ArrayList<>();
            for (String s : splitList(p.getProperty("extra.classpath.entries", ""))) {
                extras.add(resolvePath(s));
            }
            this.extraClasspathEntries = extras;

            this.sourceEncoding = p.getProperty("source.encoding", "UTF-8").trim();

            // entry.packages が空の場合は「全体モード」に入る。
            // 起点を指定せず、ソース上の全メソッドの呼び出し状況を一覧化する。
            this.entryPatterns = PackagePattern.parseAll(splitList(p.getProperty("entry.packages", "")));
            String namePat = p.getProperty("entry.class.name.pattern", "").trim();
            this.entryClassNamePattern = namePat.isEmpty() ? null : Pattern.compile(namePat);

            this.excludePatterns = PackagePattern.parseAll(splitList(p.getProperty("exclude.packages", "")));
            this.excludeMode = ExcludeMode.valueOf(
                    p.getProperty("exclude.mode", "PRUNE").trim().toUpperCase());

            this.wholeProjectMode = this.entryPatterns.isEmpty();
            this.entryAuto = Boolean.parseBoolean(p.getProperty("entry.auto", "true").trim());
            this.hubThreshold = Integer.parseInt(p.getProperty("hub.threshold", "20").trim());
            Progress.interval = Integer.parseInt(p.getProperty("progress.interval", "500").trim());

            this.maxDepth = Integer.parseInt(p.getProperty("max.depth", "6").trim());
            this.maxChildrenPerNode = Integer.parseInt(p.getProperty("max.children.per.node", "50").trim());
            // 1エントリポイントあたりの出力行数の上限。組合せ爆発でCSVが
            // 際限なく肥大化するのを防ぐ最後の砦（0以下で無制限）
            this.maxRowsPerEntry = Long.parseLong(p.getProperty("max.rows.per.entry", "200000").trim());

            this.chaRecord = Boolean.parseBoolean(p.getProperty("cha.record", "true").trim());
            this.chaExpand = Boolean.parseBoolean(p.getProperty("cha.expand", "false").trim());
            this.chaMaxCandidates = Integer.parseInt(p.getProperty("cha.max.candidates", "20").trim());

            this.hintCollectorClasses = splitList(p.getProperty("resolver.hint.collectors", ""));
            this.candidateProviderClasses = splitList(p.getProperty("resolver.candidate.providers", ""));
            this.raw = p;

            this.cacheEnabled = Boolean.parseBoolean(p.getProperty("cache.enabled", "true").trim());
            this.cacheFile = resolvePath(p.getProperty("cache.file", "./.cache/analysis-cache.tsv"));

            this.outputCsv = resolvePath(p.getProperty("output.csv", "./output/call-hierarchy.csv"));
            this.unresolvedCsv = resolvePath(p.getProperty("unresolved.csv", "./output/unresolved-calls.csv"));
            this.resolutionsCsv = resolvePath(p.getProperty("resolutions.csv", "./output/resolutions.csv"));
            this.methodsCsv = resolvePath(p.getProperty("methods.csv", "./output/methods.csv"));
            this.edgesCsv = resolvePath(p.getProperty("edges.csv", "./output/edges.csv"));
            List<Path> ex = new ArrayList<>();
            for (String v : splitList(p.getProperty("external.jars", ""))) {
                ex.add(resolvePath(v));
            }
            this.externalJars = ex;
            this.externalUsageCsv =
                    resolvePath(p.getProperty("external.usage.csv", "./output/external-usage.csv"));
            this.externalUnmatchedCsv =
                    resolvePath(p.getProperty("external.unmatched.csv", "./output/external-unmatched.csv"));
            String encRaw = p.getProperty("output.encoding", "UTF-8-BOM").trim();
            if ("UTF-8-BOM".equalsIgnoreCase(encRaw)) {
                this.outputEncoding = StandardCharsets.UTF_8;
                this.outputBom = true;
            } else {
                this.outputEncoding = Charset.forName(encRaw);
                this.outputBom = false;
            }

            String delimRaw = p.getProperty("output.delimiter", "COMMA").trim().toUpperCase();
            if ("TAB".equals(delimRaw)) {
                this.outputDelimiter = "\t";
            } else if ("COMMA".equals(delimRaw)) {
                this.outputDelimiter = ",";
            } else {
                throw new IllegalArgumentException(
                        "output.delimiter は COMMA か TAB を指定してください: " + delimRaw);
            }
        }

        /** 相対パスは設定ファイルのあるディレクトリを起点に解決する */
        private Path resolvePath(String raw) {
            Path p = Paths.get(raw.trim());
            return (p.isAbsolute() ? p : configDir.resolve(p)).normalize();
        }

        private static List<String> splitList(String raw) {
            List<String> out = new ArrayList<>();
            if (raw == null || raw.trim().isEmpty()) {
                return out;
            }
            for (String s : raw.split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
            return out;
        }

        private static String require(Properties p, String key) {
            String v = p.getProperty(key);
            if (v == null || v.trim().isEmpty()) {
                throw new IllegalArgumentException("設定ファイルに必須項目がありません: " + key);
            }
            return v.trim();
        }
    }

    /**
     * エントリポイント指定・除外指定で使うパターン。
     *
     *   jp.co.xxx.action.*                  … そのパッケージ直下のクラス全部
     *   jp.co.xxx.action.**                 … そのパッケージ配下（サブパッケージ含む）全部
     *   jp.co.xxx.action.UserAction         … クラス指定（内部クラスは Outer.Inner）
     *   jp.co.xxx.action.UserAction#execute … メソッド指定
     */
    static final class PackagePattern {

        private enum Kind { PKG_DIRECT, PKG_RECURSIVE, TYPE, METHOD }

        private final Kind kind;
        private final String value;
        private final String methodName;

        private PackagePattern(Kind kind, String value, String methodName) {
            this.kind = kind;
            this.value = value;
            this.methodName = methodName;
        }

        static PackagePattern parse(String raw) {
            String s = raw.trim();
            if (s.endsWith(".**")) {
                return new PackagePattern(Kind.PKG_RECURSIVE, s.substring(0, s.length() - 3), null);
            }
            if (s.endsWith(".*")) {
                return new PackagePattern(Kind.PKG_DIRECT, s.substring(0, s.length() - 2), null);
            }
            int hash = s.indexOf('#');
            if (hash >= 0) {
                return new PackagePattern(Kind.METHOD, s.substring(0, hash), s.substring(hash + 1));
            }
            return new PackagePattern(Kind.TYPE, s, null);
        }

        static List<PackagePattern> parseAll(List<String> raws) {
            List<PackagePattern> out = new ArrayList<>();
            for (String s : raws) {
                if (!s.trim().isEmpty()) {
                    out.add(parse(s));
                }
            }
            return out;
        }

        boolean matches(String pkg, String typeFqn, String method) {
            switch (kind) {
                // パッケージ名で判定するため、内部クラス（a.b.Outer.Inner）も正しく直下扱いになる
                case PKG_DIRECT:
                    return pkg.equals(value);
                case PKG_RECURSIVE:
                    return pkg.equals(value) || pkg.startsWith(value + ".");
                case TYPE:
                    return typeFqn.equals(value);
                case METHOD:
                    return typeFqn.equals(value) && method.equals(methodName);
                default:
                    return false;
            }
        }

        static boolean matchesAny(List<PackagePattern> patterns,
                                   String pkg, String typeFqn, String method) {
            for (int i = 0; i < patterns.size(); i++) {
                if (patterns.get(i).matches(pkg, typeFqn, method)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 設定に書かれたFQNから拡張クラスを読み込む */
    static final class Plugins {

        static <T> List<T> load(List<String> classNames, Class<T> type) {
            List<T> out = new ArrayList<>();
            for (String cn : classNames) {
                try {
                    Object o = Class.forName(cn).getDeclaredConstructor().newInstance();
                    out.add(type.cast(o));
                    log("[plugin] 読み込み: " + cn + " (" + type.getSimpleName() + ")");
                } catch (Exception e) {
                    // 拡張の読み込み失敗は致命的ではないが、黙って無視すると
                    // 「設定したのに効いていない」ことに気づけないため必ず出力する
                    log("[WARN] 拡張の読み込みに失敗: " + cn + " (" + e + ")");
                }
            }
            return out;
        }
    }

    // ================================================================
    // プロジェクト構成の読み取り
    // ================================================================

    /**
     * Eclipseプロジェクトルートから、解析に必要な構成を読み取る。
     *
     * - .classpath の kind="src" からソースフォルダ
     * - .classpath の kind="lib" からjar（他社製の共通化クラス等）
     * - kind="con"（JREコンテナ等）は解決しない。JDK標準クラスは
     *   setEnvironment の includeRunningVMBootclasspath=true で
     *   実行中のJVMから解決させる
     *
     * kind="var" やリンクリソース、ユーザーライブラリコンテナは未対応。
     * 必要な場合は extra.classpath.entries で明示的に追加すること。
     */
    static final class EclipseProjectLayout {

        final Path projectRoot;
        final List<Path> sourceFolders = new ArrayList<>();
        final List<Path> classpathEntries = new ArrayList<>();

        EclipseProjectLayout(Config config) throws IOException {
            this.projectRoot = config.projectRoot;

            if (!Files.isDirectory(projectRoot)) {
                throw new IOException("project.root がディレクトリとして存在しません: " + projectRoot);
            }

            for (String rel : config.sourceFolderOverride) {
                sourceFolders.add(projectRoot.resolve(rel).normalize());
            }

            Path dotClasspath = projectRoot.resolve(".classpath");
            if (Files.isRegularFile(dotClasspath)) {
                readDotClasspath(dotClasspath);
            } else if (sourceFolders.isEmpty()) {
                throw new IOException(
                        ".classpath が見つからず、source.folders の指定もありません: " + dotClasspath);
            }

            classpathEntries.addAll(config.extraClasspathEntries);

            if (sourceFolders.isEmpty()) {
                throw new IOException("ソースフォルダを特定できませんでした: " + projectRoot);
            }
        }

        private void readDotClasspath(Path dotClasspath) throws IOException {
            try {
                DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
                // 外部エンティティ参照を無効化（XXE対策）
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                Document doc = f.newDocumentBuilder().parse(dotClasspath.toFile());

                NodeList entries = doc.getElementsByTagName("classpathentry");
                for (int i = 0; i < entries.getLength(); i++) {
                    Element e = (Element) entries.item(i);
                    String kind = e.getAttribute("kind");
                    String path = e.getAttribute("path");
                    if (path == null || path.trim().isEmpty()) {
                        continue;
                    }
                    if ("src".equals(kind)) {
                        // 他プロジェクトへの参照（path が "/" 始まり）は未対応
                        if (path.startsWith("/")) {
                            log("[WARN] 他プロジェクト参照はスキップします: " + path);
                            continue;
                        }
                        Path sf = projectRoot.resolve(path).normalize();
                        if (Files.isDirectory(sf) && !sourceFolders.contains(sf)) {
                            sourceFolders.add(sf);
                        }
                    } else if ("lib".equals(kind)) {
                        Path raw = Paths.get(path);
                        Path jar = raw.isAbsolute() ? raw.normalize() : projectRoot.resolve(path).normalize();
                        if (Files.exists(jar)) {
                            classpathEntries.add(jar);
                        } else {
                            log("[WARN] .classpath のlibが見つかりません: " + jar);
                        }
                    }
                    // con / output / var は無視
                }
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException(".classpath の解析に失敗しました: " + dotClasspath, e);
            }
        }

        /** ソースフォルダ配下の .java を全列挙する */
        List<Path> listJavaFiles() throws IOException {
            Set<Path> files = new LinkedHashSet<>();
            for (Path sf : sourceFolders) {
                Stream<Path> w = Files.walk(sf);
                try {
                    Iterator<Path> it = w.iterator();
                    while (it.hasNext()) {
                        Path p = it.next();
                        if (Files.isRegularFile(p) && p.toString().endsWith(".java")) {
                            files.add(p);
                        }
                    }
                } finally {
                    w.close();
                }
            }
            return new ArrayList<>(files);
        }

        String[] sourcePathArray() {
            String[] a = new String[sourceFolders.size()];
            for (int i = 0; i < a.length; i++) {
                a[i] = sourceFolders.get(i).toString();
            }
            return a;
        }

        String[] classpathArray() {
            String[] a = new String[classpathEntries.size()];
            for (int i = 0; i < a.length; i++) {
                a[i] = classpathEntries.get(i).toString();
            }
            return a;
        }

        /** ASTParser.setUnitName に渡すための、ソースフォルダからの相対パス */
        String unitNameOf(Path javaFile) {
            for (Path sf : sourceFolders) {
                if (javaFile.startsWith(sf)) {
                    return sf.relativize(javaFile).toString().replace('\\', '/');
                }
            }
            return javaFile.getFileName().toString();
        }

        /** キャッシュのキー・出力表示に使う、プロジェクトルートからの相対パス */
        String relativeOf(Path javaFile) {
            return projectRoot.relativize(javaFile).toString().replace('\\', '/');
        }
    }

    // ================================================================
    // フェーズ1: 解析とキャッシュのストリーミング更新
    // ================================================================

    /**
     * キャッシュファイルの形式（タブ区切り。外部ライブラリ不要でデバッグしやすい）
     *
     *   F  相対パス  更新時刻  サイズ
     *   H  typeFqn  kind(I=IF/A=抽象/C=具象)  親型をカンマ区切り
     *   D  pkg  typeFqn  method  paramSig  declLine  hasBody(1/0)
     *   C  callerPkg callerType callerMethod callerParams
     *      calleePkg calleeType calleeMethod calleeParams  callLine  bindKind  recvKey
     *      bindKind: V=仮想 / P=private / T=static / F=finalメソッド
     *                L=finalクラス / C=コンストラクタ / U=super呼び出し（V以外は静的束縛）
     *   X  callerMethodキー  scopeKey  種別  値      （フェーズAが拾った証拠）
     *   U  行  呼び出し元メソッドキー  式  理由
     *
     * F行が現れるたびに、以降のH/D/C/U行はそのファイルに属する。
     *
     * H行は「単一実装ショートカット」と「CHA」に必須。これが無いと
     * インターフェース・抽象クラスの実装クラスを特定できない。
     * D行のhasBodyは、インターフェースの抽象メソッド（本体なし）と
     * デフォルトメソッド（本体あり）を区別するために必要。
     */
    static final class CacheFormat {
        static final String SEP = "\t";
        /** 形式を変更した場合はここを上げる。旧キャッシュは自動的に破棄される */
        static final String VERSION = "jche-cache-v1";

        /** タブ・改行が値に混ざると形式が壊れるため除去する */
        static String clean(String s) {
            if (s == null) {
                return "";
            }
            return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
        }
    }

    static final class CachePhaseResult {
        int reused;
        int parsed;
        int failed;
        long unresolvedCount;
    }

    /**
     * 旧キャッシュを先頭から読みながら新キャッシュを書き出す、ストリーミングマージ。
     *
     * 手順:
     *   パス1 … 旧キャッシュを順に読み、まだ有効なファイルのブロックは
     *           そのまま新キャッシュへ書き写す（解析し直さない）。
     *           無効・消滅したファイルのブロックは読み飛ばす。
     *   パス2 … パス1で書き写されなかったソースファイルだけを解析し、追記する。
     *
     * ランダムアクセスも全件保持も不要で、ヒープ常駐は
     * 「ソースファイルの一覧＋更新時刻・サイズ」だけ。
     * 未解決呼び出しCSVも、この過程で同時に書き出す（溜め込まない）。
     */
    static final class CacheUpdater {

        private final EclipseProjectLayout layout;
        private final Config config;

        CacheUpdater(EclipseProjectLayout layout, Config config) {
            this.layout = layout;
            this.config = config;
        }

        CachePhaseResult run() throws IOException {
            CachePhaseResult result = new CachePhaseResult();

            List<Path> javaFiles = layout.listJavaFiles();
            log("[main] Javaファイル数: " + javaFiles.size());

            // 相対パス -> ソースファイルの実体情報（これだけはヒープに載せる）
            Map<String, FileStat> live = new LinkedHashMap<>();
            for (Path f : javaFiles) {
                live.put(layout.relativeOf(f),
                        new FileStat(f, Files.getLastModifiedTime(f).toMillis(), Files.size(f)));
            }

            Path parent = config.cacheFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmpCache = config.cacheFile.resolveSibling(config.cacheFile.getFileName() + ".tmp");

            Set<String> copied = new LinkedHashSet<>();

            BufferedWriter cacheOut = Files.newBufferedWriter(tmpCache, StandardCharsets.UTF_8);
            BufferedWriter unresolvedOut =
                    Csv.writer(config.unresolvedCsv, config.outputEncoding, config.outputBom);
            try {
                cacheOut.write(CacheFormat.VERSION);
                cacheOut.newLine();
                unresolvedOut.write(String.join(config.outputDelimiter,
                        "file", "line", "callerMethod", "expression", "reason"));
                unresolvedOut.newLine();

                // --- パス1: 旧キャッシュのストリーミングコピー ---
                if (config.cacheEnabled) {
                    result.unresolvedCount += copyValidBlocks(live, copied, cacheOut, unresolvedOut);
                }
                result.reused = copied.size();

                // --- パス2: 未処理のファイルだけ解析 ---
                CallEdgeExtractor extractor = new CallEdgeExtractor(layout, config);
                int done = result.reused;
                for (Map.Entry<String, FileStat> en : live.entrySet()) {
                    String rel = en.getKey();
                    if (copied.contains(rel)) {
                        continue;
                    }
                    FileStat st = en.getValue();
                    try {
                        // 1ファイル分だけをヒープに載せ、書き出したら即破棄する
                        FileAnalysis fa = extractor.analyze(st.path, rel, st.mtime, st.size);
                        writeBlock(fa, cacheOut);
                        result.unresolvedCount += writeUnresolved(fa, unresolvedOut, config.outputDelimiter);
                        result.parsed++;
                    } catch (Exception e) {
                        result.failed++;
                        log("[WARN] 解析失敗（スキップ）: " + rel + " (" + e.getMessage() + ")");
                    }
                    done++;
                    if (done % 200 == 0) {
                        log("  ... " + done + "/" + live.size());
                    }
                }
            } finally {
                cacheOut.close();
                unresolvedOut.close();
            }

            Files.move(tmpCache, config.cacheFile, StandardCopyOption.REPLACE_EXISTING);
            return result;
        }

        /** 旧キャッシュを1行ずつ読み、まだ有効なブロックだけを新キャッシュへ書き写す */
        private long copyValidBlocks(Map<String, FileStat> live, Set<String> copied,
                                      BufferedWriter cacheOut, BufferedWriter unresolvedOut)
                throws IOException {
            if (!Files.isRegularFile(config.cacheFile)) {
                return 0L;
            }
            long unresolved = 0L;
            BufferedReader in = Files.newBufferedReader(config.cacheFile, StandardCharsets.UTF_8);
            try {
                String first = in.readLine();
                if (first == null || !CacheFormat.VERSION.equals(first.trim())) {
                    log("[cache] 形式が異なるため既存キャッシュを破棄します");
                    return 0L;
                }
                boolean keeping = false;
                String currentPath = null;
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.isEmpty()) {
                        continue;
                    }
                    char t = line.charAt(0);
                    if (t == 'F') {
                        String[] f = line.split(CacheFormat.SEP, -1);
                        keeping = false;
                        currentPath = null;
                        if (f.length >= 4) {
                            FileStat st = live.get(f[1]);
                            try {
                                if (st != null && st.mtime == Long.parseLong(f[2])
                                        && st.size == Long.parseLong(f[3])) {
                                    keeping = true;
                                    currentPath = f[1];
                                    copied.add(currentPath);
                                }
                            } catch (NumberFormatException ignore) {
                                // 壊れたF行 -> このブロックは破棄し、後で再解析される
                                keeping = false;
                            }
                        }
                        if (keeping) {
                            cacheOut.write(line);
                            cacheOut.newLine();
                        }
                    } else if (keeping) {
                        cacheOut.write(line);
                        cacheOut.newLine();
                        if (t == 'U') {
                            String[] f = line.split(CacheFormat.SEP, -1);
                            if (f.length >= 5) {
                                unresolvedOut.write(String.join(config.outputDelimiter,
                                        Csv.esc(currentPath), f[1],
                                        Csv.esc(f[2]), Csv.esc(f[3]), Csv.esc(f[4])));
                                unresolvedOut.newLine();
                                unresolved++;
                            }
                        }
                    }
                }
            } finally {
                in.close();
            }
            return unresolved;
        }

        private static void writeBlock(FileAnalysis fa, BufferedWriter w) throws IOException {
            w.write(String.join(CacheFormat.SEP, "F", fa.relativePath,
                    String.valueOf(fa.lastModified), String.valueOf(fa.size)));
            w.newLine();
            for (TypeInfo t : fa.types) {
                w.write(String.join(CacheFormat.SEP, "H", t.typeFqn,
                        String.valueOf(t.kind), String.join(",", t.superTypes)));
                w.newLine();
            }
            for (MethodDecl d : fa.declarations) {
                w.write(String.join(CacheFormat.SEP, "D", d.pkg, d.typeFqn,
                        d.methodName, d.paramSig, String.valueOf(d.declLine),
                        d.hasBody ? "1" : "0"));
                w.newLine();
            }
            for (CallEdgeRec c : fa.edges) {
                w.write(String.join(CacheFormat.SEP, "C",
                        c.callerPkg, c.callerType, c.callerMethod, c.callerParams,
                        c.calleePkg, c.calleeType, c.calleeMethod, c.calleeParams,
                        String.valueOf(c.callLine), String.valueOf(c.bindKind), c.recvKey));
                w.newLine();
            }
            for (HintRec h : fa.hints) {
                w.write(String.join(CacheFormat.SEP, "X", h.callerKey, h.scopeKey, h.kind, h.value));
                w.newLine();
            }
            for (UnresolvedCall u : fa.unresolved) {
                w.write(String.join(CacheFormat.SEP, "U", String.valueOf(u.line),
                        CacheFormat.clean(u.callerMethodKey),
                        CacheFormat.clean(u.expression),
                        CacheFormat.clean(u.reason)));
                w.newLine();
            }
        }

        private static long writeUnresolved(FileAnalysis fa, BufferedWriter w, String delimiter)
                throws IOException {
            for (UnresolvedCall u : fa.unresolved) {
                w.write(String.join(delimiter, Csv.esc(fa.relativePath), String.valueOf(u.line),
                        Csv.esc(u.callerMethodKey), Csv.esc(u.expression), Csv.esc(u.reason)));
                w.newLine();
            }
            return fa.unresolved.size();
        }

        static final class FileStat {
            final Path path;
            final long mtime;
            final long size;

            FileStat(Path path, long mtime, long size) {
                this.path = path;
                this.mtime = mtime;
                this.size = size;
            }
        }
    }

    // ================================================================
    // 1ファイル分の解析結果（書き出したら即破棄する一時オブジェクト）
    // ================================================================

    static final class MethodDecl {
        final String pkg;
        final String typeFqn;
        final String methodName;
        final String paramSig;
        final int declLine;
        /** 本体を持つか。IFの抽象メソッドとデフォルトメソッドの区別に使う */
        final boolean hasBody;

        MethodDecl(String pkg, String typeFqn, String methodName, String paramSig,
                   int declLine, boolean hasBody) {
            this.pkg = pkg;
            this.typeFqn = typeFqn;
            this.methodName = methodName;
            this.paramSig = paramSig;
            this.declLine = declLine;
            this.hasBody = hasBody;
        }
    }

    /** 型階層の1件（H行の元データ） */
    static final class TypeInfo {
        final String typeFqn;
        /** I=インターフェース / A=抽象クラス / C=具象クラス */
        final char kind;
        final List<String> superTypes;

        TypeInfo(String typeFqn, char kind, List<String> superTypes) {
            this.typeFqn = typeFqn;
            this.kind = kind;
            this.superTypes = superTypes;
        }
    }

    /**
     * 呼び出し関係の1本の辺（キャッシュ書き出し用の一時表現）。
     * callLine は「呼び出し元ソースのどの行で呼んでいるか」＝呼び出し箇所の行番号。
     */
    static final class CallEdgeRec {
        final String callerPkg;
        final String callerType;
        final String callerMethod;
        final String callerParams;
        final String calleePkg;
        final String calleeType;
        final String calleeMethod;
        final String calleeParams;
        final int callLine;
        /** S=静的束縛が保証される呼び出し / V=仮想呼び出し */
        final char bindKind;
        /** レシーバの識別キー（ローカル変数のバインディングキー、または "@位置"）。無ければ空 */
        final String recvKey;

        CallEdgeRec(String callerPkg, String callerType, String callerMethod, String callerParams,
                    String calleePkg, String calleeType, String calleeMethod, String calleeParams,
                    int callLine, char bindKind, String recvKey) {
            this.callerPkg = callerPkg;
            this.callerType = callerType;
            this.callerMethod = callerMethod;
            this.callerParams = callerParams;
            this.calleePkg = calleePkg;
            this.calleeType = calleeType;
            this.calleeMethod = calleeMethod;
            this.calleeParams = calleeParams;
            this.callLine = callLine;
            this.bindKind = bindKind;
            this.recvKey = (recvKey == null) ? "" : recvKey;
        }
    }

    /**
     * 型解決（バインディング）に失敗した呼び出しの記録。
     * 黙って読み飛ばすと「静かに漏れる」ため、必ず記録して別CSVに出力する。
     */
    static final class UnresolvedCall {
        final int line;
        final String callerMethodKey;
        final String expression;
        final String reason;

        UnresolvedCall(int line, String callerMethodKey, String expression, String reason) {
            this.line = line;
            this.callerMethodKey = callerMethodKey;
            this.expression = expression;
            this.reason = reason;
        }
    }

    /** フェーズAが拾った証拠の1件（X行の元データ） */
    static final class HintRec {
        final String callerKey;
        final String scopeKey;
        final String kind;
        final String value;

        HintRec(String callerKey, String scopeKey, String kind, String value) {
            this.callerKey = callerKey;
            this.scopeKey = scopeKey;
            this.kind = kind;
            this.value = value;
        }
    }

    /** ソース1ファイルから抽出した解析結果。書き出したら破棄される */
    static final class FileAnalysis {
        final String relativePath;
        final long lastModified;
        final long size;

        final List<TypeInfo> types = new ArrayList<>();
        final List<HintRec> hints = new ArrayList<>();
        final List<MethodDecl> declarations = new ArrayList<>();
        final List<CallEdgeRec> edges = new ArrayList<>();
        final List<UnresolvedCall> unresolved = new ArrayList<>();

        FileAnalysis(String relativePath, long lastModified, long size) {
            this.relativePath = relativePath;
            this.lastModified = lastModified;
            this.size = size;
        }
    }

    /**
     * ソース1ファイルをASTパースし、メソッド宣言と呼び出しエッジを抽出する。
     *
     * ワークスペースを使わない「スタンドアロンモード」で動かすため、
     * ASTParser.setEnvironment() にソースパスとクラスパスを明示的に渡す。
     * ASTは1ファイルごとに生成して捨てるため、ヒープには残らない。
     */
    static final class CallEdgeExtractor {

        private final EclipseProjectLayout layout;
        private final Charset encoding;
        private final Map<String, String> compilerOptions;
        private final String[] classpath;
        private final String[] sourcepath;
        private final String[] encodings;

        private final List<CallSiteHintCollector> collectors;

        CallEdgeExtractor(EclipseProjectLayout layout, Config config) {
            this.collectors = Plugins.load(config.hintCollectorClasses, CallSiteHintCollector.class);
            this.layout = layout;
            this.encoding = Charset.forName(config.sourceEncoding);
            this.compilerOptions = JavaCore.getOptions();
            this.classpath = layout.classpathArray();
            this.sourcepath = layout.sourcePathArray();
            this.encodings = new String[sourcepath.length];
            Arrays.fill(this.encodings, config.sourceEncoding);
        }

        FileAnalysis analyze(Path javaFile, String rel, long mtime, long size) throws IOException {
            FileAnalysis result = new FileAnalysis(rel, mtime, size);
            char[] source = new String(Files.readAllBytes(javaFile), encoding).toCharArray();

            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setKind(ASTParser.K_COMPILATION_UNIT);
            parser.setCompilerOptions(compilerOptions);
            parser.setResolveBindings(true);
            parser.setBindingsRecovery(true);
            // ワークスペース非依存で型解決するための環境設定
            parser.setEnvironment(classpath, sourcepath, encodings, true);
            parser.setUnitName(layout.unitNameOf(javaFile));  // バインディング解決に必須
            parser.setSource(source);

            IProgressMonitor noMonitor = null;
            CompilationUnit cu = (CompilationUnit) parser.createAST(noMonitor);
            cu.accept(new Visitor(cu, result, collectors));
            return result;
        }

        /**
         * ASTを走査して宣言とエッジを拾う。
         *
         * 「現在囲まれているメソッド」はスタックで保持する。
         * 匿名クラス・ローカルクラスの MethodDeclaration は、囲みメソッドの
         * MethodDeclaration の内側にネストして現れる。単一スロットで保持すると
         * 内側のメソッドを抜けた時点で囲みメソッドの情報が失われ、
         * 「匿名クラスより後ろにある呼び出しがすべてメソッド外として未解決に落ちる」
         * という静かな欠落が起きる。スタックにすることでこれを防ぐ。
         *
         * ラムダ式は MethodDeclaration ではないため、その中の呼び出しは
         * 自動的に囲みメソッドへ帰属する（ソース上の見え方と一致する）。
         */
        static final class Visitor extends ASTVisitor {

            /** 解決できないメソッド宣言を表す番兵（ArrayDequeはnullを保持できないため） */
            private static final String[] UNKNOWN = new String[0];

            private final CompilationUnit cu;
            private final FileAnalysis out;
            private final List<CallSiteHintCollector> collectors;
            private final ArrayDeque<String[]> methodStack = new ArrayDeque<>();

            Visitor(CompilationUnit cu, FileAnalysis out) {
                this(cu, out, java.util.Collections.<CallSiteHintCollector>emptyList());
            }

            Visitor(CompilationUnit cu, FileAnalysis out, List<CallSiteHintCollector> collectors) {
                this.cu = cu;
                this.out = out;
                this.collectors = collectors;
            }

            /** 現在囲まれているメソッドのキー（typeFqn#name(params)）。不明なら null */
            private String currentKey() {
                String[] c = current();
                return (c == null) ? null : (c[1] + "#" + c[2] + "(" + c[3] + ")");
            }

            // ------------------------------------------------------------
            // 段2: 同一メソッド内の new 追跡
            //
            // フロー依存解析（分岐やループを厳密に追う）はコストが高いので、
            // 「そのメソッド内でその変数に代入される new を全部集める」という
            // フロー非依存・安全側の方針を取る。
            //   1件   -> LOCAL_NEW（確定）
            //   複数件 -> LOCAL_NEW_MULTI（候補集合。CHAよりはるかに狭い）
            //
            // 変数の同定は名前ではなく IVariableBinding.getKey() で行う。
            // 名前で照合すると、同名変数がスコープ違いで複数ある場合に誤解決する。
            // ------------------------------------------------------------

            @Override
            public boolean visit(VariableDeclarationFragment node) {
                Expression init = node.getInitializer();
                if (init instanceof ClassInstanceCreation) {
                    IVariableBinding vb = node.resolveBinding();
                    if (vb != null) {
                        addNewHint(vb.getKey(), (ClassInstanceCreation) init);
                    }
                }
                return true;
            }

            @Override
            public boolean visit(Assignment node) {
                Expression lhs = node.getLeftHandSide();
                Expression rhs = node.getRightHandSide();
                if ((rhs instanceof ClassInstanceCreation) && (lhs instanceof SimpleName)) {
                    IBinding b = ((SimpleName) lhs).resolveBinding();
                    if (b instanceof IVariableBinding) {
                        addNewHint(((IVariableBinding) b).getKey(), (ClassInstanceCreation) rhs);
                    }
                }
                return true;
            }

            private void addNewHint(String varKey, ClassInstanceCreation cic) {
                String callerKey = currentKey();
                if (callerKey == null || varKey == null) {
                    return;
                }
                String type = createdTypeOf(cic);
                if (type != null) {
                    out.hints.add(new HintRec(callerKey, varKey, "NEW", type));
                }
            }

            /** new された具象型。匿名クラスの場合は匿名型そのもの */
            private String createdTypeOf(ClassInstanceCreation cic) {
                ITypeBinding tb = cic.resolveTypeBinding();
                if (tb == null) {
                    IMethodBinding cb = cic.resolveConstructorBinding();
                    if (cb != null && cb.getMethodDeclaration() != null) {
                        tb = cb.getMethodDeclaration().getDeclaringClass();
                    }
                }
                if (tb == null) {
                    return null;
                }
                ITypeBinding er = tb.getErasure();
                return typeNameOf(er != null ? er : tb);
            }

            /**
             * レシーバの識別キー。
             * ローカル変数なら変数のバインディングキー、そうでなければ "@開始位置"。
             * 後者にしておくと、変数を介さない呼び出し
             * （DaoFactory.get("X").execute(...) など）にも拡張が証拠を結び付けられる。
             */
            private String recvKeyOf(MethodInvocation n) {
                Expression ex = n.getExpression();
                if (ex == null) {
                    return "";
                }
                if (ex instanceof SimpleName) {
                    IBinding b = ((SimpleName) ex).resolveBinding();
                    if (b instanceof IVariableBinding) {
                        String k = ((IVariableBinding) b).getKey();
                        if (k != null && !k.isEmpty()) {
                            return k.replaceAll("\\s", "_");
                        }
                    }
                }
                return "@" + ex.getStartPosition();
            }

            @Override
            public boolean visit(TypeDeclaration node) {
                recordType(node.resolveBinding());
                return true;
            }

            @Override
            public boolean visit(AnonymousClassDeclaration node) {
                // 匿名クラスも型階層に載せる。載せないとオーバーライド候補から漏れる
                recordType(node.resolveBinding());
                return true;
            }

            /** 型階層（H行）を記録する */
            private void recordType(ITypeBinding tb) {
                if (tb == null) {
                    return;
                }
                ITypeBinding er = tb.getErasure();
                if (er == null) {
                    er = tb;
                }
                String fqn = typeNameOf(er);
                if (fqn == null) {
                    return;
                }
                char kind = er.isInterface() ? 'I'
                        : (Modifier.isAbstract(er.getModifiers()) ? 'A' : 'C');

                List<String> supers = new ArrayList<>();
                ITypeBinding sc = er.getSuperclass();
                if (sc != null) {
                    String n2 = typeNameOf(sc.getErasure() != null ? sc.getErasure() : sc);
                    // java.lang.Object は候補計算に寄与しないので除外（無駄に巨大化させない）
                    if (n2 != null && !"java.lang.Object".equals(n2)) {
                        supers.add(n2);
                    }
                }
                ITypeBinding[] ifs = er.getInterfaces();
                if (ifs != null) {
                    for (ITypeBinding i : ifs) {
                        String n2 = typeNameOf(i.getErasure() != null ? i.getErasure() : i);
                        if (n2 != null) {
                            supers.add(n2);
                        }
                    }
                }
                out.types.add(new TypeInfo(fqn, kind, supers));
            }

            @Override
            public boolean visit(MethodDeclaration node) {
                String[] r = toRef(node.resolveBinding());
                if (r != null) {
                    int line = cu.getLineNumber(node.getName().getStartPosition());
                    out.declarations.add(new MethodDecl(r[0], r[1], r[2], r[3], line,
                            node.getBody() != null));
                    methodStack.push(r);
                } else {
                    methodStack.push(UNKNOWN);
                }
                return true;
            }

            @Override
            public void endVisit(MethodDeclaration node) {
                // visit で必ず push しているため、ここで必ず pop して対応を保つ。
                // （JDTは visit が false を返した場合も endVisit を呼ぶ）
                if (!methodStack.isEmpty()) {
                    methodStack.pop();
                }
            }

            /** 現在囲まれているメソッド。特定できない場合は null */
            private String[] current() {
                String[] top = methodStack.peek();
                return (top == null || top.length == 0) ? null : top;
            }

            @Override
            public boolean visit(MethodInvocation n) {
                IMethodBinding b = n.resolveMethodBinding();
                record(b, n, n.getName().getIdentifier(), bindKindOf(b), recvKeyOf(n));

                // フェーズAの拡張に、この呼び出し箇所を見せる
                final String callerKey = currentKey();
                if (callerKey != null && !collectors.isEmpty()) {
                    HintSink sink = new HintSink() {
                        @Override
                        public void add(String scopeKey, String kind, String value) {
                            if (scopeKey == null || kind == null || value == null) {
                                return;
                            }
                            out.hints.add(new HintRec(callerKey,
                                    CacheFormat.clean(scopeKey),
                                    CacheFormat.clean(kind), CacheFormat.clean(value)));
                        }
                    };
                    for (int i = 0; i < collectors.size(); i++) {
                        try {
                            collectors.get(i).collect(n, cu, callerKey, sink);
                        } catch (RuntimeException e) {
                            // 拡張の失敗で解析全体を止めない
                            log("[WARN] hint collector 失敗: "
                                    + collectors.get(i).getClass().getName() + " (" + e + ")");
                        }
                    }
                }
                return true;
            }

            @Override
            public boolean visit(SuperMethodInvocation n) {
                // super.m() は静的束縛（オーバーライドの影響を受けない）
                record(n.resolveMethodBinding(), n, n.getName().getIdentifier(), 'U', "");
                return true;
            }

            @Override
            public boolean visit(ClassInstanceCreation n) {
                record(n.resolveConstructorBinding(), n, "<init>", 'C', "");
                return true;
            }

            @Override
            public boolean visit(ConstructorInvocation n) {
                record(n.resolveConstructorBinding(), n, "<init>", 'C', "");
                return true;
            }

            /**
             * 段0の判定。実際に走る実装が一意に定まる（＝仮想ディスパッチされない）
             * 呼び出しかどうかを、修飾子から判定する。
             *
             * 宣言型が具象クラスであることは根拠にならない。
             *   Base b = new Derived(); b.m();  // 実際に走るのは Derived.m
             * 正しい軸は「静的束縛か仮想呼び出しか」。
             */
            private char bindKindOf(IMethodBinding b) {
                if (b == null) {
                    return 'V';
                }
                IMethodBinding d = b.getMethodDeclaration();
                if (d == null) {
                    d = b;
                }
                int mm = d.getModifiers();
                // 理由まで記録しておく。後から
                // 「この確定は本当に妥当か」を resolutions.csv で監査できるようにするため
                if (Modifier.isPrivate(mm)) {
                    return 'P';   // オーバーライド不可
                }
                if (Modifier.isStatic(mm)) {
                    return 'T';   // 動的束縛されない
                }
                if (Modifier.isFinal(mm)) {
                    return 'F';   // オーバーライド不可（CGLIBもインターセプトできない）
                }
                ITypeBinding owner = d.getDeclaringClass();
                if (owner != null && Modifier.isFinal(owner.getModifiers())) {
                    return 'L';   // finalクラス。サブクラスを作れない
                }
                return 'V';
            }

            private void record(IMethodBinding binding, ASTNode node, String displayName,
                                 char bindKind, String recvKey) {
                int line = cu.getLineNumber(node.getStartPosition());
                String[] caller = current();
                if (caller == null) {
                    // フィールド初期化子・staticイニシャライザ等、メソッド外からの呼び出し。
                    // 呼び出し元メソッドを特定できないため未解決として記録する
                    out.unresolved.add(new UnresolvedCall(line,
                            "(メソッド外)", displayName, "メソッド本体の外からの呼び出し"));
                    return;
                }
                String[] callee = toRef(binding);
                if (callee == null) {
                    out.unresolved.add(new UnresolvedCall(line,
                            caller[1] + "#" + caller[2] + "(" + caller[3] + ")", displayName,
                            "型解決に失敗（クラスパス不足・動的呼び出し等の可能性）"));
                    return;
                }
                out.edges.add(new CallEdgeRec(caller[0], caller[1], caller[2], caller[3],
                        callee[0], callee[1], callee[2], callee[3], line, bindKind, recvKey));
            }

            /**
             * 型の識別名を得る。
             *
             * 匿名クラスは getQualifiedName() が空文字を返す。以前はそこで
             * スキップしていたため、匿名クラスによるオーバーライドと、その内部の
             * 呼び出しが丸ごと欠落していた。JDTは匿名クラスにも識別子を持っているので
             * 順に切り替えて拾う。
             */
            private static String typeNameOf(ITypeBinding t) {
                if (t == null) {
                    return null;
                }
                String n = t.getQualifiedName();
                if (n == null || n.isEmpty()) {
                    n = t.getBinaryName();               // 例: jp.co.xxx.Outer$1
                }
                if (n == null || n.isEmpty()) {
                    String key = t.getKey();             // JDT内部の一意キー（最終手段）
                    // キャッシュはタブ区切りのため、空白類が混ざると形式が壊れる
                    n = (key == null) ? null : key.replaceAll("\\s", "_");
                }
                return (n == null || n.isEmpty()) ? null : n;
            }

            /** @return {pkg, typeFqn, methodName, paramSig} または null */
            private String[] toRef(IMethodBinding binding) {
                if (binding == null) {
                    return null;
                }
                // ジェネリクスの実体化された型ではなく宣言側を基準にする
                IMethodBinding decl = binding.getMethodDeclaration();
                if (decl == null) {
                    return null;
                }
                ITypeBinding type = decl.getDeclaringClass();
                if (type == null) {
                    return null;
                }
                ITypeBinding erased = type.getErasure();
                if (erased == null) {
                    return null;
                }
                String typeFqn = typeNameOf(erased);
                if (typeFqn == null) {
                    return null;
                }
                String pkg = (erased.getPackage() != null) ? erased.getPackage().getName() : "";

                StringBuilder params = new StringBuilder();
                ITypeBinding[] pts = decl.getParameterTypes();
                if (pts != null) {
                    for (int i = 0; i < pts.length; i++) {
                        if (i > 0) {
                            params.append(",");
                        }
                        ITypeBinding pe = pts[i].getErasure();
                        params.append(pe != null ? pe.getQualifiedName() : pts[i].getQualifiedName());
                    }
                }
                String name = decl.isConstructor() ? "<init>" : decl.getName();
                return new String[]{pkg, typeFqn, name, params.toString()};
            }
        }
    }

    // ================================================================
    // フェーズ2: CSR形式の呼び出しグラフ
    // ================================================================

    /** growableな int 配列（プリミティブのまま扱うことでボックス化を避ける） */
    static final class IntArray {
        int[] a;
        int n;

        IntArray(int cap) {
            a = new int[Math.max(4, cap)];
        }

        void add(int v) {
            if (n == a.length) {
                a = Arrays.copyOf(a, a.length + (a.length >> 1) + 8);
            }
            a[n++] = v;
        }

        int get(int i) {
            return a[i];
        }

        void set(int i, int v) {
            a[i] = v;
        }

        int size() {
            return n;
        }
    }

    /**
     * メソッドを int の ID に内部化する表。
     *
     * 保持するのはメソッドごとに文字列2本（キーとパッケージ名）と、
     * 宣言ファイル・宣言行だけ。型名・メソッド名はキーから切り出せるので持たない。
     * キー形式: typeFqn#methodName(paramSig)
     */
    static final class MethodTable {

        private final HashMap<String, Integer> idByKey = new HashMap<>(1 << 16);
        private final ArrayList<String> keys = new ArrayList<>();
        private final ArrayList<String> pkgs = new ArrayList<>();

        /** 宣言情報（ソースがあるメソッドのみ設定される） */
        private final ArrayList<String> declFiles = new ArrayList<>();
        private final IntArray declLines = new IntArray(1 << 16);
        /**
         * 本体を持つか。既定はtrue（＝候補になりうる）。
         * D行が無いメソッド（jar内のメソッド等）はソースが無く展開もできないため、
         * 安全側に倒して候補から落とさない。
         */
        private final ArrayList<Boolean> hasBody = new ArrayList<>();

        int intern(String pkg, String typeFqn, String method, String params) {
            String key = typeFqn + "#" + method + "(" + params + ")";
            Integer id = idByKey.get(key);
            if (id != null) {
                return id.intValue();
            }
            int newId = keys.size();
            idByKey.put(key, Integer.valueOf(newId));
            keys.add(key);
            pkgs.add(pkg == null ? "" : pkg);
            declFiles.add(null);
            declLines.add(-1);
            hasBody.add(Boolean.TRUE);
            return newId;
        }

        /** キーからIDを引く。未登録なら -1 */
        int idOf(String key) {
            Integer id = idByKey.get(key);
            return (id == null) ? -1 : id.intValue();
        }

        void setDeclaration(int id, String file, int line, boolean body) {
            declFiles.set(id, file);
            declLines.set(id, line);
            hasBody.set(id, Boolean.valueOf(body));
        }

        boolean hasBody(int id) {
            return hasBody.get(id).booleanValue();
        }

        /** キーのうち "#" 以降（methodName(paramSig)）。同名同引数の照合に使う */
        String signature(int id) {
            String k = keys.get(id);
            return k.substring(k.indexOf('#') + 1);
        }

        int size() {
            return keys.size();
        }

        String pkg(int id) {
            return pkgs.get(id);
        }

        String typeFqn(int id) {
            String k = keys.get(id);
            return k.substring(0, k.indexOf('#'));
        }

        String methodName(int id) {
            String k = keys.get(id);
            return k.substring(k.indexOf('#') + 1, k.indexOf('('));
        }

        /** クラスの単純名（内部クラスは Outer.Inner の形を保つ） */
        String simpleTypeName(int id) {
            String t = typeFqn(id);
            String p = pkgs.get(id);
            if (p.isEmpty()) {
                // パッケージが無いので、typeFqn全体がそのままクラスの入れ子構造を表す
                // （lastIndexOf('.')で末尾だけ切り出すと、デフォルトパッケージ上の
                //   内部クラスで外側のクラス名が失われてしまう）
                return t;
            }
            if (t.startsWith(p + ".")) {
                return t.substring(p.length() + 1);
            }
            int i = t.lastIndexOf('.');
            return (i >= 0) ? t.substring(i + 1) : t;
        }

        /** CSVのcallHierarchy列で使う簡潔表記 */
        String shortLabel(int id) {
            return simpleTypeName(id) + "." + methodName(id);
        }

        String declFile(int id) {
            return declFiles.get(id);
        }

        int declLine(int id) {
            return declLines.get(id);
        }
    }

    /**
     * CSR（Compressed Sparse Row）形式の呼び出しグラフ。
     *
     * offsets[callerId] .. offsets[callerId + 1] が、その呼び出し元のエッジ範囲。
     * その範囲の calleeIds[] / callLines[] が各エッジの内容。
     *
     * エッジ1本あたり int 2個で済むため、オブジェクトで持つ場合に比べ桁違いに省メモリ。
     *
     * キャッシュファイルを2回スキャンして構築する:
     *   1回目 … メソッドをID化し、呼び出し元ごとの本数を数える
     *   2回目 … 数えた本数から offsets を作り、実際のエッジを流し込む
     * どちらもストリーミングなので、キャッシュ全体をヒープに載せない。
     *
     * 現在の出力は下流（呼び出し先）のみ使うため、逆引きCSRは構築していない。
     */
    static final class CallGraph {

        final MethodTable methods = new MethodTable();
        int[] offsets;      // 長さ methods.size() + 1
        int[] calleeIds;    // 長さ = エッジ数
        int[] callLines;    // 長さ = エッジ数
        byte[] bindKinds;   // 長さ = エッジ数。'S'=静的束縛 / 'V'=仮想

        /** 型階層: 親型 -> 直接の子型 */
        private final HashMap<String, List<String>> directSubtypes = new HashMap<>();
        /** 型 -> 種別（I/A/C） */
        private final HashMap<String, Character> typeKind = new HashMap<>();
        /** 解決結果のメモ（メソッドIDごと。仮想呼び出しのみ対象） */
        private int[][] resolvedTargets;
        private String[] resolvedLabel;

        /** エッジごとの証拠。-1 なら証拠なし。値は hintsPerEdge のインデックス */
        int[] edgeHint;
        private final ArrayList<List<Hint>> hintTable = new ArrayList<>();
        /** callerKey + "|" + scopeKey -> 証拠のリスト */
        private final HashMap<String, List<Hint>> hintsByScope = new HashMap<>();
        /** フェーズBの拡張 */
        private List<TypeCandidateProvider> providers = new ArrayList<>();

        void setProviders(List<TypeCandidateProvider> p) {
            this.providers = p;
        }

        /**
         * 全体モードの起点の並び替え用。.classpath に書かれたソースフォルダの順
         * （プロジェクトルートからの相対パス。例: "src/main/java"）。
         * main/test 等のソースフォルダが混在して出力されるのを避けるために使う。
         */
        private List<String> sourceFolderOrder = Collections.emptyList();

        void setSourceFolderOrder(List<String> order) {
            this.sourceFolderOrder = order;
        }

        /** declFile が属するソースフォルダの、sourceFolderOrder 上のインデックス */
        private int sourceFolderIndexOf(String declFile) {
            if (declFile == null) {
                return Integer.MAX_VALUE;
            }
            String norm = declFile.replace('\\', '/');
            int bestIndex = Integer.MAX_VALUE;
            int bestLen = -1;
            for (int i = 0; i < sourceFolderOrder.size(); i++) {
                String prefix = sourceFolderOrder.get(i);
                boolean matches = prefix.isEmpty()
                        || norm.equals(prefix) || norm.startsWith(prefix + "/");
                if (matches && prefix.length() > bestLen) {
                    bestLen = prefix.length();
                    bestIndex = i;
                }
            }
            return bestIndex;
        }

        static CallGraph buildFrom(Path cacheFile) throws IOException {
            CallGraph g = new CallGraph();

            // --- 1回目: ID化と本数カウント ---
            IntArray outDegree = new IntArray(1 << 16);
            long edgeCount = 0;
            long fileCount = 0;
            Progress pg1 = new Progress("グラフ構築 1/2（型階層とメソッドの収集）", 0);
            BufferedReader in = open(cacheFile);
            try {
                String currentFile = null;
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.isEmpty()) {
                        continue;
                    }
                    char t = line.charAt(0);
                    if (t == 'F') {
                        String[] f = line.split(CacheFormat.SEP, -1);
                        currentFile = (f.length >= 2) ? f[1] : null;
                        pg1.step(++fileCount);
                    } else if (t == 'H') {
                        String[] f = line.split(CacheFormat.SEP, -1);
                        if (f.length >= 3) {
                            g.typeKind.put(f[1], Character.valueOf(f[2].isEmpty() ? 'C' : f[2].charAt(0)));
                            if (f.length >= 4 && !f[3].isEmpty()) {
                                for (String sup : f[3].split(",")) {
                                    if (sup.isEmpty()) {
                                        continue;
                                    }
                                    List<String> subs = g.directSubtypes.get(sup);
                                    if (subs == null) {
                                        subs = new ArrayList<>();
                                        g.directSubtypes.put(sup, subs);
                                    }
                                    if (!subs.contains(f[1])) {
                                        subs.add(f[1]);
                                    }
                                }
                            }
                        }
                    } else if (t == 'X') {
                        String[] f = line.split(CacheFormat.SEP, -1);
                        if (f.length >= 5) {
                            String k = f[1] + "|" + f[2];
                            List<Hint> hs = g.hintsByScope.get(k);
                            if (hs == null) {
                                hs = new ArrayList<>();
                                g.hintsByScope.put(k, hs);
                            }
                            hs.add(new Hint(f[3], f[4]));
                        }
                    } else if (t == 'D') {
                        String[] f = line.split(CacheFormat.SEP, -1);
                        if (f.length >= 6) {
                            int id = g.methods.intern(f[1], f[2], f[3], f[4]);
                            ensure(outDegree, id);
                            int declLine;
                            try {
                                declLine = Integer.parseInt(f[5]);
                            } catch (NumberFormatException ignore) {
                                declLine = -1;
                            }
                            boolean body = (f.length < 7) || !"0".equals(f[6]);
                            g.methods.setDeclaration(id, currentFile, declLine, body);
                        }
                    } else if (t == 'C') {
                        String[] f = line.split(CacheFormat.SEP, -1);
                        if (f.length >= 10) {
                            int caller = g.methods.intern(f[1], f[2], f[3], f[4]);
                            int callee = g.methods.intern(f[5], f[6], f[7], f[8]);
                            ensure(outDegree, caller);
                            ensure(outDegree, callee);
                            outDegree.set(caller, outDegree.get(caller) + 1);
                            edgeCount++;
                        }
                    }
                }
            } finally {
                in.close();
            }
            pg1.finish();
            log("[main] 収集: 型 " + g.typeKind.size()
                    + " / メソッド " + g.methods.size() + " / エッジ " + edgeCount);

            if (edgeCount > Integer.MAX_VALUE) {
                throw new IOException("エッジ数が多すぎます: " + edgeCount);
            }

            // --- offsets（累積和）を作る ---
            int n = g.methods.size();
            g.offsets = new int[n + 1];
            for (int i = 0; i < n; i++) {
                int d = (i < outDegree.size()) ? outDegree.get(i) : 0;
                g.offsets[i + 1] = g.offsets[i] + d;
            }
            g.calleeIds = new int[(int) edgeCount];
            g.callLines = new int[(int) edgeCount];
            g.bindKinds = new byte[(int) edgeCount];
            g.edgeHint = new int[(int) edgeCount];
            Arrays.fill(g.edgeHint, -1);

            // --- 2回目: エッジを流し込む ---
            int[] cursor = Arrays.copyOf(g.offsets, n == 0 ? 0 : n);
            Progress pg2 = new Progress("グラフ構築 2/2（呼び出し関係の展開）", fileCount);
            long seenFiles = 0;
            in = open(cacheFile);
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.isEmpty()) {
                        continue;
                    }
                    if (line.charAt(0) == 'F') {
                        pg2.step(++seenFiles);
                        continue;
                    }
                    if (line.charAt(0) != 'C') {
                        continue;
                    }
                    String[] f = line.split(CacheFormat.SEP, -1);
                    if (f.length < 10) {
                        continue;
                    }
                    int caller = g.methods.intern(f[1], f[2], f[3], f[4]);
                    int callee = g.methods.intern(f[5], f[6], f[7], f[8]);
                    int pos = cursor[caller]++;
                    g.calleeIds[pos] = callee;
                    try {
                        g.callLines[pos] = Integer.parseInt(f[9]);
                    } catch (NumberFormatException ignore) {
                        g.callLines[pos] = -1;
                    }
                    g.bindKinds[pos] = (byte) ((f.length >= 11 && !f[10].isEmpty())
                            ? f[10].charAt(0) : 'V');

                    // 呼び出し箇所（呼び出し元メソッド＋レシーバ）に紐づく証拠を引き当てる
                    String recvKey = (f.length >= 12) ? f[11] : "";
                    if (!recvKey.isEmpty()) {
                        String callerKey = f[2] + "#" + f[3] + "(" + f[4] + ")";
                        List<Hint> hs = g.hintsByScope.get(callerKey + "|" + recvKey);
                        if (hs != null && !hs.isEmpty()) {
                            g.hintTable.add(hs);
                            g.edgeHint[pos] = g.hintTable.size() - 1;
                        }
                    }
                }
            } finally {
                in.close();
            }
            pg2.finish();
            return g;
        }

        private static BufferedReader open(Path cacheFile) throws IOException {
            BufferedReader in = Files.newBufferedReader(cacheFile, StandardCharsets.UTF_8);
            in.readLine();  // バージョン行を読み飛ばす
            return in;
        }

        private static void ensure(IntArray a, int id) {
            while (a.size() <= id) {
                a.add(0);
            }
        }

        // ================================================================
        // 解決パイプライン
        // ================================================================

        /**
         * エッジ単位の解決。段0→段1→段2→段3→段4 の順に試し、確定したら止める。
         *
         *   段0 STATIC_BOUND               仮想ディスパッチされない呼び出し
         *   段1 NO_OVERRIDE / SINGLE_IMPL  オーバーライド候補が1つに定まる
         *   段2 LOCAL_NEW(_MULTI)          同一メソッド内で new された型
         *   段3 CUSTOM_*                   拡張（ファクトリ・DI設定・外部リスト等）
         *   段4 CHA                        候補が複数のまま（低確度）
         *
         * 段1で確定するならそれが最も確実なので、証拠より先に採用する。
         */
        Resolution resolveEdge(int edgeIndex, char bindKind) {
            int calleeId = calleeIds[edgeIndex];

            // --- 段0: 静的束縛 ---
            if (bindKind != 'V') {
                // 既定では確定として扱うが、ここで打ち切ると拡張に到達せず
                // 呼び出し階層が切れてしまう。opt-inした拡張には必ず声をかける。
                Resolution custom = askProviders(edgeIndex, calleeId, true);
                if (custom != null) {
                    return custom;
                }
                return new Resolution(new int[]{calleeId},
                        "STATIC_BOUND:" + staticBoundReason(bindKind));
            }

            Resolution base = resolve(calleeId, bindKind);
            if (!"CHA".equals(base.label)) {
                return base;   // 段1までで確定
            }

            List<Hint> hints = (edgeHint[edgeIndex] >= 0)
                    ? hintTable.get(edgeHint[edgeIndex]) : java.util.Collections.<Hint>emptyList();
            String sig = methods.signature(calleeId);

            // --- 段2: new された型 ---
            IntArray fromNew = new IntArray(2);
            for (int i = 0; i < hints.size(); i++) {
                Hint h = hints.get(i);
                if (!"NEW".equals(h.kind)) {
                    continue;
                }
                int id = methods.idOf(h.value + "#" + sig);
                if (id >= 0 && !contains(fromNew, id)) {
                    fromNew.add(id);
                }
            }
            if (fromNew.size() > 0) {
                return new Resolution(Arrays.copyOf(fromNew.a, fromNew.size()),
                        fromNew.size() == 1 ? "LOCAL_NEW" : "LOCAL_NEW_MULTI");
            }

            // --- 段3: 拡張 ---
            Resolution custom = askProviders(edgeIndex, calleeId, false);
            if (custom != null) {
                return custom;
            }

            // --- 段4: CHA ---
            return base;
        }

        /**
         * 拡張（フェーズB）に候補を尋ねる。
         *
         * @param staticBoundOnly true なら appliesToStaticBound() が true の拡張だけに尋ねる
         * @return 解決できた場合のみ Resolution。できなければ null
         */
        private Resolution askProviders(int edgeIndex, int calleeId, boolean staticBoundOnly) {
            if (providers.isEmpty()) {
                return null;
            }
            List<Hint> hints = (edgeHint[edgeIndex] >= 0)
                    ? hintTable.get(edgeHint[edgeIndex])
                    : java.util.Collections.<Hint>emptyList();
            String declType = methods.typeFqn(calleeId);
            String sig = methods.signature(calleeId);

            for (int i = 0; i < providers.size(); i++) {
                TypeCandidateProvider pv = providers.get(i);
                if (staticBoundOnly && !pv.appliesToStaticBound()) {
                    continue;
                }
                String[] cands;
                try {
                    cands = pv.candidates(declType, sig, hints);
                } catch (RuntimeException e) {
                    log("[WARN] candidate provider 失敗: "
                            + pv.getClass().getName() + " (" + e + ")");
                    continue;
                }
                if (cands == null || cands.length == 0) {
                    continue;
                }
                IntArray ids = new IntArray(cands.length);
                for (String c : cands) {
                    int id = methods.idOf(c + "#" + sig);
                    if (id >= 0 && !contains(ids, id)) {
                        ids.add(id);
                    }
                }
                if (ids.size() > 0) {
                    return new Resolution(Arrays.copyOf(ids.a, ids.size()), pv.label());
                }
            }
            return null;
        }

        /** 静的束縛と判定した理由。resolutions.csv で監査できるように残す */
        static String staticBoundReason(char bindKind) {
            switch (bindKind) {
                case 'P': return "PRIVATE";
                case 'T': return "STATIC";
                case 'F': return "FINAL_METHOD";
                case 'L': return "FINAL_CLASS";
                case 'C': return "CTOR";
                case 'U': return "SUPER";
                default:  return "OTHER";
            }
        }

        private static boolean contains(IntArray a, int v) {
            for (int i = 0; i < a.size(); i++) {
                if (a.get(i) == v) {
                    return true;
                }
            }
            return false;
        }

        /** 解決結果 */
        static final class Resolution {
            final int[] targets;
            final String label;

            Resolution(int[] targets, String label) {
                this.targets = targets;
                this.label = label;
            }
        }

        /**
         * 呼び出し先の具象候補を求める。
         *
         *  段0 STATIC_BOUND … private/static/final、finalクラス、コンストラクタ、super呼び出し
         *                      → 仮想ディスパッチされないので宣言のまま確定
         *  段1 NO_OVERRIDE   … オーバーライドしている型が1つも無い → 宣言のまま確定
         *      SINGLE_IMPL   … 候補が1つだけ（IFに実装が1つ等） → その実装で確定
         *  段4 CHA           … 候補が複数。ここは低確度
         *      NO_IMPL       … 本体を持つ候補が皆無（ソース外の実装等）。宣言のまま扱う
         *
         * 重要: 候補数は「サブタイプ数」ではなく「そのメソッドをオーバーライドしている
         * 宣言の数」。サブクラスが多くてもオーバーライドが1件なら候補は1件のまま。
         */
        Resolution resolve(int calleeId, char bindKind) {
            if (bindKind == 'S') {
                return new Resolution(new int[]{calleeId}, "STATIC_BOUND");
            }
            if (resolvedTargets == null) {
                resolvedTargets = new int[methods.size()][];
                resolvedLabel = new String[methods.size()];
            }
            if (resolvedTargets[calleeId] != null) {
                return new Resolution(resolvedTargets[calleeId], resolvedLabel[calleeId]);
            }

            String declType = methods.typeFqn(calleeId);
            String sig = methods.signature(calleeId);

            IntArray cands = new IntArray(4);
            if (methods.hasBody(calleeId)) {
                cands.add(calleeId);   // 宣言型自身の実装（IFの抽象メソッドは除外される）
            }
            for (String sub : transitiveSubtypes(declType)) {
                int id = methods.idOf(sub + "#" + sig);
                if (id >= 0 && id != calleeId) {
                    cands.add(id);
                }
            }

            int[] targets;
            String label;
            if (cands.size() == 0) {
                targets = new int[]{calleeId};
                label = "NO_IMPL";
            } else if (cands.size() == 1) {
                targets = new int[]{cands.get(0)};
                label = (cands.get(0) == calleeId) ? "NO_OVERRIDE" : "SINGLE_IMPL";
            } else {
                targets = Arrays.copyOf(cands.a, cands.size());
                label = "CHA";
            }
            resolvedTargets[calleeId] = targets;
            resolvedLabel[calleeId] = label;
            return new Resolution(targets, label);
        }

        private final HashMap<String, List<String>> transitiveCache = new HashMap<>();

        /** 推移的なサブタイプ。循環があっても止まるように訪問済みを持つ */
        List<String> transitiveSubtypes(String type) {
            List<String> cached = transitiveCache.get(type);
            if (cached != null) {
                return cached;
            }
            List<String> out = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            ArrayDeque<String> stack = new ArrayDeque<>();
            stack.push(type);
            while (!stack.isEmpty()) {
                String cur = stack.pop();
                List<String> subs = directSubtypes.get(cur);
                if (subs == null) {
                    continue;
                }
                for (String sub : subs) {
                    if (seen.add(sub)) {
                        out.add(sub);
                        stack.push(sub);
                    }
                }
            }
            transitiveCache.put(type, out);
            return out;
        }

        char kindOf(String typeFqn) {
            Character k = typeKind.get(typeFqn);
            return (k == null) ? '?' : k.charValue();
        }

        int typeCount() {
            return typeKind.size();
        }

        int methodCount() {
            return methods.size();
        }

        int edgeCount() {
            return calleeIds.length;
        }

        int edgeStart(int callerId) {
            return offsets[callerId];
        }

        int edgeEnd(int callerId) {
            return offsets[callerId + 1];
        }

        // ================================================================
        // 全体モード用の集計
        // ================================================================

        /** 解決後の入次数。宣言型ではなく解決先に対して数える */
        private int[] inDegree;

        /**
         * 入次数を数える。
         *
         * 重要: 宣言型の呼び出し先ではなく「解決後の候補」に対して数える。
         * そうしないと、IF経由でしか呼ばれない実装クラス（DAO実装など）が
         * すべて入次数0となり、真の入口と区別がつかなくなる。
         */
        int[] inDegrees() {
            if (inDegree != null) {
                return inDegree;
            }
            inDegree = new int[methods.size()];
            for (int caller = 0; caller < methods.size(); caller++) {
                for (int e = edgeStart(caller); e < edgeEnd(caller); e++) {
                    Resolution r = resolveEdge(e, (char) bindKinds[e]);
                    for (int t : r.targets) {
                        inDegree[t]++;
                    }
                }
            }
            return inDegree;
        }

        int outDegree(int id) {
            return edgeEnd(id) - edgeStart(id);
        }

        /** 起点集合から解決後のエッジを辿って到達できるメソッドに印を付ける */
        boolean[] reachableFrom(int[] roots) {
            boolean[] seen = new boolean[methods.size()];
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            for (int r : roots) {
                if (!seen[r]) {
                    seen[r] = true;
                    queue.add(Integer.valueOf(r));
                }
            }
            while (!queue.isEmpty()) {
                int cur = queue.poll().intValue();
                for (int e = edgeStart(cur); e < edgeEnd(cur); e++) {
                    Resolution r = resolveEdge(e, (char) bindKinds[e]);
                    for (int t : r.targets) {
                        if (!seen[t]) {
                            seen[t] = true;
                            queue.add(Integer.valueOf(t));
                        }
                    }
                }
            }
            return seen;
        }

        /**
         * 全体モードの起点。呼び出し元が1件も無く、ソース上に本体を持つメソッド。
         *
         * これは「真の入口」ではない点に注意。実際には次のものが混ざる。
         *   - 独自フレームワークがディスパッチする画面入口（本来ほしいもの）
         *   - デッドコード・旧版の残骸
         *   - テストクラスのメソッド
         *   - リフレクション経由でのみ呼ばれるもの
         * methods.csv の role 列で仕分けできるようにしてある。
         */
        int[] autoEntryPoints() {
            int[] in = inDegrees();
            IntArray hits = new IntArray(256);
            for (int id = 0; id < methods.size(); id++) {
                if (in[id] == 0 && methods.declFile(id) != null && methods.hasBody(id)) {
                    hits.add(id);
                }
            }
            Integer[] boxed = new Integer[hits.size()];
            for (int i = 0; i < boxed.length; i++) {
                boxed[i] = Integer.valueOf(hits.get(i));
            }
            // 出力順: 1) ソースフォルダの宣言順（.classpath の記載順。main/testの混在を防ぐ）
            //         2) 型FQN順（'.'は英数字よりコード上小さいため、文字列比較だけで
            //            「パッケージ自身 -> そのサブパッケージ -> 次のパッケージ」の順になる）
            //         3) 同じ型内では、ソースファイル上の宣言順
            Arrays.sort(boxed, (x, y) -> {
                int fx = sourceFolderIndexOf(methods.declFile(x.intValue()));
                int fy = sourceFolderIndexOf(methods.declFile(y.intValue()));
                if (fx != fy) {
                    return Integer.compare(fx, fy);
                }
                int t = methods.typeFqn(x.intValue()).compareTo(methods.typeFqn(y.intValue()));
                if (t != 0) {
                    return t;
                }
                return Integer.compare(methods.declLine(x.intValue()), methods.declLine(y.intValue()));
            });
            int[] result = new int[boxed.length];
            for (int i = 0; i < result.length; i++) {
                result[i] = boxed[i].intValue();
            }
            return result;
        }

        /** 設定にマッチする、ソース上に宣言のあるメソッドをエントリポイントとして選ぶ */
        int[] selectEntryPoints(Config config) {
            if (config.entryPatterns.isEmpty()) {
                return config.entryAuto ? autoEntryPoints() : new int[0];
            }
            IntArray hits = new IntArray(256);
            for (int id = 0; id < methods.size(); id++) {
                if (methods.declFile(id) == null) {
                    continue;   // ソースが無いメソッドは起点にしない
                }
                String pkg = methods.pkg(id);
                String type = methods.typeFqn(id);
                String name = methods.methodName(id);
                if (!PackagePattern.matchesAny(config.entryPatterns, pkg, type, name)) {
                    continue;
                }
                if (config.entryClassNamePattern != null
                        && !config.entryClassNamePattern.matcher(methods.simpleTypeName(id)).matches()) {
                    continue;
                }
                hits.add(id);
            }
            // 出力順を安定させる（実行のたびに行順が変わらないように）
            final MethodTable mt = methods;
            Integer[] boxed = new Integer[hits.size()];
            for (int i = 0; i < boxed.length; i++) {
                boxed[i] = Integer.valueOf(hits.get(i));
            }
            Arrays.sort(boxed, new java.util.Comparator<Integer>() {
                @Override
                public int compare(Integer x, Integer y) {
                    String a = mt.typeFqn(x.intValue()) + "#" + mt.methodName(x.intValue());
                    String b = mt.typeFqn(y.intValue()) + "#" + mt.methodName(y.intValue());
                    return a.compareTo(b);
                }
            });
            int[] result = new int[boxed.length];
            for (int i = 0; i < result.length; i++) {
                result[i] = boxed[i].intValue();
            }
            return result;
        }
    }

    /** 解決の内訳（どの段でどれだけ確定できたかの集計） */
    static final class ResolutionStats {
        long staticBound;
        long noOverride;
        long singleImpl;
        long noImpl;
        long localNew;
        long custom;
        long cha;
        long chaCandidatesMax;

        @Override
        public String toString() {
            return "静的束縛=" + staticBound
                    + " オーバーライドなし=" + noOverride
                    + " 単一実装=" + singleImpl
                    + " 実装なし=" + noImpl
                    + " new追跡=" + localNew
                    + " 拡張=" + custom
                    + " CHA=" + cha + "(最大候補" + chaCandidatesMax + "件)";
        }
    }

    /**
     * 全エッジについて解決を実行し、内訳を集計しつつCSVに出力する。
     *
     * CHAになった箇所＝「静的には絞りきれなかった箇所」なので、
     * カスタム解決（ファクトリ解析・DI設定など）を作る際の投資判断は
     * このファイルの件数を見て決めるとよい。
     */
    static final class ResolutionReport {

        static ResolutionStats write(CallGraph g, Config config) throws IOException {
            ResolutionStats st = new ResolutionStats();
            // 同じ呼び出し先は何度も現れるので、メソッド単位で1行にまとめる
            boolean[] done = new boolean[g.methodCount()];
            Progress pg = new Progress("具象クラス解決", g.methodCount());
            BufferedWriter w = Csv.writer(config.resolutionsCsv, config.outputEncoding, config.outputBom);
            try {
                w.write(String.join(config.outputDelimiter,
                        "declaredMethod", "bindKind", "label", "candidateCount", "candidates"));
                w.newLine();
                for (int caller = 0; caller < g.methodCount(); caller++) {
                    pg.step(caller + 1);
                    for (int e = g.edgeStart(caller); e < g.edgeEnd(caller); e++) {
                        int callee = g.calleeIds[e];
                        char bk = (char) g.bindKinds[e];
                        CallGraph.Resolution r = g.resolveEdge(e, bk);

                        if (r.label.startsWith("STATIC_BOUND")) {
                            st.staticBound++;
                        } else if ("NO_OVERRIDE".equals(r.label)) {
                            st.noOverride++;
                        } else if ("SINGLE_IMPL".equals(r.label)) {
                            st.singleImpl++;
                        } else if ("NO_IMPL".equals(r.label)) {
                            st.noImpl++;
                        } else if (r.label.startsWith("LOCAL_NEW")) {
                            st.localNew++;
                        } else if (!"CHA".equals(r.label)) {
                            st.custom++;   // 拡張が返したラベル
                        } else {
                            st.cha++;
                            if (r.targets.length > st.chaCandidatesMax) {
                                st.chaCandidatesMax = r.targets.length;
                            }
                        }

                        // 静的束縛の行も出力する。ここを省くと
                        // 「段0で確定して階層が切れた箇所」を後から監査できなくなる
                        if (done[callee]) {
                            continue;
                        }
                        done[callee] = true;
                        StringBuilder cands = new StringBuilder();
                        for (int i = 0; i < r.targets.length; i++) {
                            if (i > 0) {
                                cands.append(" / ");
                            }
                            cands.append(g.methods.typeFqn(r.targets[i]));
                        }
                        w.write(String.join(config.outputDelimiter,
                                Csv.esc(g.methods.typeFqn(callee) + "#" + g.methods.signature(callee)),
                                String.valueOf(bk), r.label,
                                String.valueOf(r.targets.length), Csv.esc(cands.toString())));
                        w.newLine();
                    }
                }
            } finally {
                w.close();
            }
            pg.finish();
            return st;
        }
    }

    /** 全体モードの集計 */
    static final class InventoryStats {
        long methods;
        long entryCandidates;
        long isolated;
        long leaves;
        long hubs;
        long unreachable;
        long edges;

        @Override
        public String toString() {
            return "メソッド=" + methods
                    + " 起点候補=" + entryCandidates
                    + " 孤立=" + isolated
                    + " 末端=" + leaves
                    + " ハブ=" + hubs
                    + " 未到達=" + unreachable
                    + " エッジ=" + edges;
        }
    }

    /**
     * 全体モードの出力。
     *
     * エントリポイントを指定しない場合、呼び出しルートを全部展開すると
     * 分岐^深さで爆発する。一方でエッジ一覧とメソッド一覧はエッジ数・メソッド数に
     * 比例した線形サイズで収まり、しかも任意の起点からのルートを後から
     * 再構成できる。よって全体モードでは「一覧」を一次成果物とする。
     */
    static final class InventoryReport {

        /**
         * methods.csv … ソース上の全メソッドと、その呼び出し状況。
         *
         * role の意味:
         *   ENTRY_CANDIDATE 呼び出し元が無い。画面入口・バッチ・デッドコード・
         *                   テスト・リフレクション経由が混ざる（要仕分け）
         *   ISOLATED        呼び出し元も呼び出し先も無い。デッドコードの疑いが濃い
         *   LEAF            呼び出し先が無い。末端処理
         *   HUB             入次数が hub.threshold 以上。共通処理。
         *                   改修時の影響範囲が広い箇所
         *   NORMAL          上記以外
         */
        static InventoryStats writeMethods(CallGraph g, Config config, int[] roots)
                throws IOException {
            InventoryStats st = new InventoryStats();
            int[] in = g.inDegrees();
            boolean[] reachable = g.reachableFrom(roots);

            Progress pg = new Progress("メソッド一覧の出力", g.methodCount());
            BufferedWriter w = Csv.writer(config.methodsCsv, config.outputEncoding, config.outputBom);
            try {
                w.write(String.join(config.outputDelimiter, "method", "declaringType", "typeKind",
                        "file", "line", "hasBody", "inDegree", "outDegree", "role", "reachable"));
                w.newLine();
                for (int id = 0; id < g.methodCount(); id++) {
                    pg.step(id + 1);
                    // ソースが無いメソッド（jar内など）は一覧の対象外。
                    // 呼ばれている事実は edges.csv 側に残る
                    if (g.methods.declFile(id) == null) {
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
                    } else if (in[id] >= config.hubThreshold) {
                        role = "HUB";
                        st.hubs++;
                    } else if (out == 0) {
                        role = "LEAF";
                        st.leaves++;
                    } else {
                        role = "NORMAL";
                    }
                    if (!reachable[id]) {
                        st.unreachable++;
                    }
                    w.write(String.join(config.outputDelimiter,
                            Csv.esc(g.methods.shortLabel(id)),
                            Csv.esc(g.methods.typeFqn(id)),
                            String.valueOf(g.kindOf(g.methods.typeFqn(id))),
                            Csv.esc(g.methods.declFile(id)),
                            String.valueOf(g.methods.declLine(id)),
                            g.methods.hasBody(id) ? "1" : "0",
                            String.valueOf(in[id]),
                            String.valueOf(out),
                            role,
                            reachable[id] ? "1" : "0"));
                    w.newLine();
                }
            } finally {
                w.close();
            }
            pg.finish();
            return st;
        }

        /**
         * edges.csv … 解決後の全呼び出し関係。
         *
         * 1行 = 1つの呼び出し先候補。CHAで候補が複数になった呼び出しは
         * 候補の数だけ行が出る（resolution 列で由来が分かる）。
         * この一覧があれば、任意のメソッドを起点にしたルートを後から再構成できる。
         */
        static long writeEdges(CallGraph g, Config config) throws IOException {
            long rows = 0;
            Progress pg = new Progress("エッジ一覧の出力", g.methodCount());
            BufferedWriter w = Csv.writer(config.edgesCsv, config.outputEncoding, config.outputBom);
            try {
                w.write(String.join(config.outputDelimiter, "caller", "callee", "callerFile",
                        "callLine", "bindKind", "resolution", "candidateCount", "declaredCallee"));
                w.newLine();
                for (int caller = 0; caller < g.methodCount(); caller++) {
                    pg.step(caller + 1);
                    String callerFile = g.methods.declFile(caller);
                    for (int e = g.edgeStart(caller); e < g.edgeEnd(caller); e++) {
                        char bk = (char) g.bindKinds[e];
                        CallGraph.Resolution r = g.resolveEdge(e, bk);
                        int declared = g.calleeIds[e];
                        for (int t : r.targets) {
                            w.write(String.join(config.outputDelimiter,
                                    Csv.esc(g.methods.shortLabel(caller)),
                                    Csv.esc(g.methods.shortLabel(t)),
                                    Csv.esc(callerFile == null ? "" : callerFile),
                                    String.valueOf(g.callLines[e]),
                                    String.valueOf(bk),
                                    r.label,
                                    String.valueOf(r.targets.length),
                                    Csv.esc(g.methods.typeFqn(declared)
                                            + "#" + g.methods.signature(declared))));
                            w.newLine();
                            rows++;
                        }
                    }
                }
            } finally {
                w.close();
            }
            pg.finish();
            return rows;
        }
    }

    // ================================================================
    // 外部jarからの被参照スキャン（レベル1: 定数プール）
    // ================================================================

    /**
     * classファイルの定数プールを読み、参照しているメソッドを列挙する。
     *
     * 命令列（Code属性）は読まないので「どのメソッドから呼んでいるか」までは
     * 分からないが、「どのクラスが参照しているか」は分かる。
     * 改修時の影響調査にはこの粒度で足りることが多く、
     * ASMやBCELといった外部ライブラリを持ち込まずに実装できる利点がある。
     */
    static final class ClassFileRefs {

        /** 参照している自分のクラス名（FQN） */
        final String thisClass;
        /** 参照先メソッド。要素は {ownerFqn, name, "paramFqn,paramFqn"} */
        final List<String[]> methodRefs = new ArrayList<>();

        private ClassFileRefs(String thisClass) {
            this.thisClass = thisClass;
        }

        static ClassFileRefs parse(InputStream raw) throws IOException {
            DataInputStream in = new DataInputStream(raw);
            if (in.readInt() != 0xCAFEBABE) {
                throw new IOException("classファイルではありません");
            }
            in.readUnsignedShort();     // minor
            in.readUnsignedShort();     // major
            int count = in.readUnsignedShort();

            int[] tags = new int[count];
            String[] utf8 = new String[count];
            int[] refA = new int[count];   // Class:name_index / Ref:class_index / NameAndType:name_index
            int[] refB = new int[count];   // Ref:name_and_type_index / NameAndType:descriptor_index

            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                tags[i] = tag;
                switch (tag) {
                    case 1:  utf8[i] = in.readUTF(); break;                       // Utf8
                    case 7:  refA[i] = in.readUnsignedShort(); break;             // Class
                    case 8:  in.readUnsignedShort(); break;                       // String
                    case 16: in.readUnsignedShort(); break;                       // MethodType
                    case 19: case 20: in.readUnsignedShort(); break;              // Module / Package
                    case 15: in.readUnsignedByte(); in.readUnsignedShort(); break;// MethodHandle
                    case 3: case 4: in.readInt(); break;                          // Integer / Float
                    case 5: case 6:                                               // Long / Double
                        in.readLong();
                        i++;   // 8バイト定数は2スロット占有する（ここを飛ばさないと全体がずれる）
                        break;
                    case 9: case 10: case 11:                                     // Field/Method/InterfaceMethodref
                    case 12:                                                      // NameAndType
                    case 17: case 18:                                             // Dynamic / InvokeDynamic
                        refA[i] = in.readUnsignedShort();
                        refB[i] = in.readUnsignedShort();
                        break;
                    default:
                        throw new IOException("未知の定数プールタグ: " + tag);
                }
            }

            in.readUnsignedShort();                 // access_flags
            int thisClassIdx = in.readUnsignedShort();
            String thisName = (thisClassIdx > 0 && thisClassIdx < count)
                    ? internalToFqn(utf8[refA[thisClassIdx]]) : "(不明)";

            ClassFileRefs out = new ClassFileRefs(thisName);
            for (int i = 1; i < count; i++) {
                // Methodref / InterfaceMethodref のみ（Fieldrefは対象外）
                if (tags[i] != 10 && tags[i] != 11) {
                    continue;
                }
                int classIdx = refA[i];
                int natIdx = refB[i];
                if (classIdx <= 0 || natIdx <= 0 || classIdx >= count || natIdx >= count) {
                    continue;
                }
                String owner = internalToFqn(utf8[refA[classIdx]]);
                String name = utf8[refA[natIdx]];
                String desc = utf8[refB[natIdx]];
                if (owner == null || name == null || desc == null) {
                    continue;
                }
                out.methodRefs.add(new String[]{owner, name, String.join(",", parseParams(desc))});
            }
            return out;
        }

        static String internalToFqn(String internal) {
            return (internal == null) ? null : internal.replace('/', '.');
        }

        /** ディスクリプタ "(Ljava/lang/String;I[Z)V" から引数型のFQN列を取り出す */
        static List<String> parseParams(String desc) {
            List<String> out = new ArrayList<>();
            if (desc == null) {
                return out;
            }
            int i = desc.indexOf('(');
            if (i < 0) {
                return out;
            }
            i++;
            while (i < desc.length() && desc.charAt(i) != ')') {
                int arr = 0;
                while (i < desc.length() && desc.charAt(i) == '[') {
                    arr++;
                    i++;
                }
                if (i >= desc.length()) {
                    break;
                }
                String t;
                char c = desc.charAt(i);
                if (c == 'L') {
                    int e = desc.indexOf(';', i);
                    if (e < 0) {
                        break;
                    }
                    t = desc.substring(i + 1, e).replace('/', '.');
                    i = e + 1;
                } else {
                    switch (c) {
                        case 'B': t = "byte"; break;
                        case 'C': t = "char"; break;
                        case 'D': t = "double"; break;
                        case 'F': t = "float"; break;
                        case 'I': t = "int"; break;
                        case 'J': t = "long"; break;
                        case 'S': t = "short"; break;
                        case 'Z': t = "boolean"; break;
                        default: return out;
                    }
                    i++;
                }
                StringBuilder sb = new StringBuilder(t);
                for (int k = 0; k < arr; k++) {
                    sb.append("[]");
                }
                out.add(sb.toString());
            }
            return out;
        }
    }

    /** 外部jarからの被参照スキャンの集計 */
    static final class ExternalUsageStats {
        long jars;
        long classes;
        long hits;
        long implicitCtors;
        long unmatched;
        long usedMethods;

        @Override
        public String toString() {
            return "jar=" + jars + " クラス=" + classes
                    + " 被参照=" + hits + "件（自分のメソッド " + usedMethods + " 個）"
                    + " 暗黙コンストラクタ=" + implicitCtors
                    + " 未照合=" + unmatched;
        }
    }

    /**
     * 他チームのjarを走査し、自分のメソッドがどこから参照されているかを出力する。
     *
     * 用途は改修時の影響調査。「このメソッドを直すと誰に影響するか」に答える。
     */
    static final class ExternalUsageScanner {

        static ExternalUsageStats scan(CallGraph g, Config config) throws IOException {
            ExternalUsageStats st = new ExternalUsageStats();

            List<Path> jars = collectJars(config.externalJars);
            log("[main] 外部jar: " + jars.size() + " 件");
            if (jars.isEmpty()) {
                return st;
            }

            // 自分の型かどうかの判定に使う（H行から得た、ソース上に宣言のある型）
            Set<String> ourTypes = new java.util.HashSet<>(g.typeKind.keySet());
            // 継承したメソッドの照合用: "name(params)" -> 宣言しているメソッドID
            Map<String, List<Integer>> bySignature = new HashMap<>();
            for (int id = 0; id < g.methodCount(); id++) {
                if (g.methods.declFile(id) == null) {
                    continue;
                }
                String sig = g.methods.signature(id);
                List<Integer> l = bySignature.get(sig);
                if (l == null) {
                    l = new ArrayList<>();
                    bySignature.put(sig, l);
                }
                l.add(Integer.valueOf(id));
            }
            int[] refCount = new int[g.methodCount()];

            Progress pg = new Progress("外部jarの被参照スキャン", jars.size());
            BufferedWriter hit = Csv.writer(config.externalUsageCsv, config.outputEncoding, config.outputBom);
            BufferedWriter miss = Csv.writer(config.externalUnmatchedCsv, config.outputEncoding, config.outputBom);
            try {
                hit.write(String.join(config.outputDelimiter,
                        "method", "declaringType", "jar", "referencingClass", "matchKind"));
                hit.newLine();
                miss.write(String.join(config.outputDelimiter,
                        "jar", "referencingClass", "ownerType", "method", "params", "reason"));
                miss.newLine();

                for (int j = 0; j < jars.size(); j++) {
                    Path jarPath = jars.get(j);
                    String jarName = jarPath.getFileName().toString();
                    JarFile jf = new JarFile(jarPath.toFile());
                    try {
                        java.util.Enumeration<JarEntry> en = jf.entries();
                        while (en.hasMoreElements()) {
                            JarEntry e = en.nextElement();
                            if (e.isDirectory() || !e.getName().endsWith(".class")) {
                                continue;
                            }
                            st.classes++;
                            ClassFileRefs refs;
                            InputStream is = jf.getInputStream(e);
                            try {
                                refs = ClassFileRefs.parse(is);
                            } catch (Exception ex) {
                                log("[WARN] class解析に失敗（スキップ）: "
                                        + jarName + "!" + e.getName() + " (" + ex.getMessage() + ")");
                                continue;
                            } finally {
                                is.close();
                            }
                            for (String[] r : refs.methodRefs) {
                                String owner = r[0];
                                if (!isOurType(ourTypes, owner)) {
                                    continue;   // JDKや第三者ライブラリへの参照は対象外
                                }
                                String sig = r[1] + "(" + r[2] + ")";
                                int id = resolveRef(g, bySignature, owner, sig);
                                if (id >= 0) {
                                    String kind = g.methods.typeFqn(id).equals(normalize(owner))
                                            ? "EXACT" : "INHERITED";
                                    hit.write(String.join(config.outputDelimiter,
                                            Csv.esc(g.methods.shortLabel(id)),
                                            Csv.esc(g.methods.typeFqn(id)),
                                            Csv.esc(jarName),
                                            Csv.esc(refs.thisClass),
                                            kind));
                                    hit.newLine();
                                    if (refCount[id]++ == 0) {
                                        st.usedMethods++;
                                    }
                                    st.hits++;
                                } else if ("<init>".equals(r[1])) {
                                    // 暗黙のデフォルトコンストラクタ。ソース上に宣言が無いため
                                    // 照合先が存在しないが、これは版の食い違いではない。
                                    // 「誰がこのクラスを生成しているか」は影響調査で有用なので
                                    // 被参照として記録する。
                                    hit.write(String.join(config.outputDelimiter,
                                            Csv.esc(simpleOf(owner) + ".<init>"),
                                            Csv.esc(owner), Csv.esc(jarName),
                                            Csv.esc(refs.thisClass), "IMPLICIT_CTOR"));
                                    hit.newLine();
                                    st.implicitCtors++;
                                } else {
                                    // 自分の型への参照なのに一致するメソッドが無い。
                                    // 相手が古い版のjarに対してビルドされている可能性がある。
                                    // 「使われていない」と即断しないための記録。
                                    miss.write(String.join(config.outputDelimiter,
                                            Csv.esc(jarName), Csv.esc(refs.thisClass),
                                            Csv.esc(owner), Csv.esc(r[1]), Csv.esc(r[2]),
                                            "自分の型だが一致するメソッドが無い（版differ等）"));
                                    miss.newLine();
                                    st.unmatched++;
                                }
                            }
                        }
                    } finally {
                        jf.close();
                    }
                    st.jars++;
                    pg.step(j + 1);
                }
            } finally {
                hit.close();
                miss.close();
            }
            pg.finish();
            return st;
        }

        private static String simpleOf(String fqn) {
            int i = fqn.lastIndexOf('.');
            return (i >= 0) ? fqn.substring(i + 1) : fqn;
        }

        /** 内部クラスは bytecode が Outer$Inner、JDT側が Outer.Inner なので両方で照合する */
        private static String normalize(String owner) {
            return owner.replace('$', '.');
        }

        private static boolean isOurType(Set<String> ourTypes, String owner) {
            return ourTypes.contains(owner) || ourTypes.contains(normalize(owner));
        }

        /**
         * 参照を自分のメソッドIDに解決する。
         * 完全一致で見つからない場合、継承したメソッドの呼び出し
         * （呼び出し側は子クラスを owner として記録する）を考慮して親を探す。
         */
        private static int resolveRef(CallGraph g, Map<String, List<Integer>> bySignature,
                                       String owner, String sig) {
            int id = g.methods.idOf(owner + "#" + sig);
            if (id >= 0 && g.methods.declFile(id) != null) {
                return id;
            }
            String norm = normalize(owner);
            id = g.methods.idOf(norm + "#" + sig);
            if (id >= 0 && g.methods.declFile(id) != null) {
                return id;
            }
            List<Integer> cands = bySignature.get(sig);
            if (cands == null) {
                return -1;
            }
            for (Integer c : cands) {
                String declType = g.methods.typeFqn(c.intValue());
                if (g.transitiveSubtypes(declType).contains(norm)
                        || g.transitiveSubtypes(declType).contains(owner)) {
                    return c.intValue();
                }
            }
            return -1;
        }

        private static List<Path> collectJars(List<Path> roots) throws IOException {
            Set<Path> out = new LinkedHashSet<>();
            for (Path r : roots) {
                if (Files.isRegularFile(r) && r.toString().endsWith(".jar")) {
                    out.add(r);
                } else if (Files.isDirectory(r)) {
                    Stream<Path> w = Files.walk(r);
                    try {
                        Iterator<Path> it = w.iterator();
                        while (it.hasNext()) {
                            Path p = it.next();
                            if (Files.isRegularFile(p) && p.toString().endsWith(".jar")) {
                                out.add(p);
                            }
                        }
                    } finally {
                        w.close();
                    }
                } else {
                    log("[WARN] external.jars の指定が見つかりません: " + r);
                }
            }
            return new ArrayList<>(out);
        }
    }

    // ================================================================
    // フェーズ3: DFSしながら1行ずつ出力（ツリーを組み立てない）
    // ================================================================

    /**
     * 呼び出し階層を深さ優先で辿りながら、CSVを1行ずつ書き出す。
     *
     * ヒープに載るのは「現在の経路（深さぶんの配列）」だけ。
     * ツリー全体をオブジェクトで組み立てないため、探索が広くても
     * メモリ使用量は深さに比例した一定量にとどまる。
     *
     * 安全策:
     * - maxDepth            … 深さ制限
     * - maxChildrenPerNode  … 共通ユーティリティ等「ハブ」メソッドでの爆発防止
     * - maxRowsPerEntry     … 1起点あたりの出力行数の上限（組合せ爆発への最後の砦）
     * - 循環検出 [CYCLE]    … 「現在の経路（rootからそのノードまでの祖先）」に
     *                          同じメソッドが既にあれば、その辺を [CYCLE] として1行出力し、
     *                          そこから先へは降りない。
     *                          判定は経路単位なので、別の経路で同じ呼び出しが
     *                          現れた場合はそちらでも改めて出力する
     *                          （グローバルな訪問済み集合は持たない）
     * - 除外パッケージ      … PRUNE=ノードと配下を切り捨て / SKIP=ノードは出さず配下を
     *                          親に繋ぎ直して辿り続ける
     */
    static final class StreamingTreeWalker {

        private final CallGraph graph;
        private final Config config;
        private final CallHierarchyCsvWriter writer;

        // 現在の経路（深さぶんだけ確保）
        private final int[] pathMethod;
        private final int[] pathCallLine;
        private final String[] pathNote;

        /** 経路上で既に呼んでいるメソッドへ戻る辺の印 */
        static final String CYCLE_MARK = "[CYCLE]";

        private int rootId;
        private long rowsForEntry;
        private long totalRows;
        private boolean limitWarned;

        StreamingTreeWalker(CallGraph graph, Config config, CallHierarchyCsvWriter writer) {
            this.graph = graph;
            this.config = config;
            this.writer = writer;
            int cap = Math.max(2, config.maxDepth + 2);
            this.pathMethod = new int[cap];
            this.pathCallLine = new int[cap];
            this.pathNote = new String[cap];
        }

        long walkAll(int[] entries) throws IOException {
            Progress pg = new Progress("呼び出し階層の展開", entries.length);
            for (int i = 0; i < entries.length; i++) {
                rootId = entries[i];
                rowsForEntry = 0;
                limitWarned = false;

                pathMethod[0] = rootId;
                pathCallLine[0] = -1;
                pathNote[0] = (graph.methods.declFile(rootId) == null) ? "ソースなし（展開不可）" : null;
                descend(0);
                pg.step(i + 1);
            }
            pg.finish();
            log("[main] 出力行数: " + totalRows);
            return totalRows;
        }

        /** depth のノードから、その呼び出し先を辿る */
        private void descend(int depth) throws IOException {
            if (depth >= config.maxDepth || depth + 1 >= pathMethod.length) {
                return;
            }
            int callerId = pathMethod[depth];
            int emitted = 0;
            int from = graph.edgeStart(callerId);
            int to = graph.edgeEnd(callerId);

            for (int e = from; e < to; e++) {
                if (isRowLimitReached()) {
                    return;
                }
                int declaredCallee = graph.calleeIds[e];
                int callLine = graph.callLines[e];
                CallGraph.Resolution res =
                        graph.resolveEdge(e, (char) graph.bindKinds[e]);

                // --- CHA候補が複数、かつ展開しない設定 ---
                // 記録（線形）と展開（候補数^深さ）を分離する。ここでは
                // 「解決できなかった」事実を1行だけ残し、静かに消さない。
                if (res.targets.length > 1 && !config.chaExpand) {
                    if (!config.chaRecord || isExcluded(declaredCallee)) {
                        continue;
                    }
                    if (emitted >= config.maxChildrenPerNode) {
                        emitOverflow(depth, declaredCallee, callLine, to - e);
                        return;
                    }
                    push(depth + 1, declaredCallee, callLine,
                            "CHA候補" + res.targets.length + "件（未展開）");
                    emit(depth + 1);
                    emitted++;
                    continue;
                }

                int limit = config.chaExpand
                        ? Math.min(res.targets.length, config.chaMaxCandidates)
                        : res.targets.length;

                for (int ti = 0; ti < limit; ti++) {
                    if (isRowLimitReached()) {
                        return;
                    }
                    int target = res.targets[ti];

                    if (isExcluded(target)) {
                        if (config.excludeMode == Config.ExcludeMode.PRUNE) {
                            continue;   // ノードごと切り捨て、配下も辿らない
                        }
                        // SKIP: このノードは出力しないが、その先は親に繋ぎ直して辿る
                        if (!onCurrentPath(target, depth)) {
                            skipThrough(depth, target);
                        }
                        continue;
                    }

                    if (emitted >= config.maxChildrenPerNode) {
                        emitOverflow(depth, target, callLine, to - e);
                        return;
                    }

                    if (onCurrentPath(target, depth)) {
                        // [CYCLE]: この経路上で既に呼んでいるメソッドへ戻る辺。
                        // 辺自体は1行として出力し、そこから先へは降りない。
                        push(depth + 1, target, callLine, CYCLE_MARK);
                        emit(depth + 1);
                        emitted++;
                        continue;
                    }

                    push(depth + 1, target, callLine, noteFor(target, declaredCallee, res, depth));
                    emit(depth + 1);
                    emitted++;
                    descend(depth + 1);
                }
            }
        }

        private boolean isExcluded(int id) {
            return PackagePattern.matchesAny(config.excludePatterns,
                    graph.methods.pkg(id), graph.methods.typeFqn(id),
                    graph.methods.methodName(id));
        }

        private void emitOverflow(int depth, int id, int callLine, int remaining)
                throws IOException {
            push(depth + 1, id, callLine,
                    "上限(" + config.maxChildrenPerNode + "件)超過のため以降省略。残り"
                            + remaining + "件");
            emit(depth + 1);
        }

        /** ノードに付ける注記。解決の由来もここに載せる（CSVの列構成は変えない） */
        private String noteFor(int target, int declaredCallee,
                                CallGraph.Resolution res, int depth) {
            StringBuilder sb = new StringBuilder();
            if (graph.methods.declFile(target) == null) {
                sb.append("ソースなし（展開不可）");
            } else if (depth + 1 >= config.maxDepth) {
                sb.append("深さ制限(").append(config.maxDepth).append(")のため打ち切り");
            }
            if (res.targets.length > 1) {
                if (sb.length() > 0) {
                    sb.append(" / ");
                }
                sb.append("CHA候補").append(res.targets.length).append("件中");
            } else if (target != declaredCallee) {
                if (sb.length() > 0) {
                    sb.append(" / ");
                }
                sb.append("解決:").append(res.label);
            }
            return (sb.length() == 0) ? null : sb.toString();
        }

        /**
         * SKIPモード用。除外されたノード自身は出力せず、
         * その呼び出し先を「1つ上の親の子」として辿り直す。
         */
        private void skipThrough(int parentDepth, int skippedId) throws IOException {
            int saved = pathMethod[parentDepth];
            pathMethod[parentDepth] = skippedId;   // 一時的に呼び出し元を差し替える
            try {
                descend(parentDepth);
            } finally {
                pathMethod[parentDepth] = saved;
            }
        }

        private void push(int depth, int methodId, int callLine, String note) {
            pathMethod[depth] = methodId;
            pathCallLine[depth] = callLine;
            pathNote[depth] = note;
        }

        /**
         * そのメソッドが「現在の経路」に既に現れているか。
         *
         * 見るのは root から depth までの祖先だけで、探索済みの他の経路は見ない。
         * これにより、別経路で同じ呼び出しがあっても独立して出力される
         * （ダイヤモンド状の依存を潰さない）。
         */
        private boolean onCurrentPath(int methodId, int depth) {
            for (int i = 0; i <= depth; i++) {
                if (pathMethod[i] == methodId) {
                    return true;
                }
            }
            return false;
        }

        private boolean isRowLimitReached() {
            if (config.maxRowsPerEntry <= 0 || rowsForEntry < config.maxRowsPerEntry) {
                return false;
            }
            if (!limitWarned) {
                limitWarned = true;
                log("[WARN] 出力行数の上限("
                        + config.maxRowsPerEntry + ")に達したため打ち切りました: "
                        + graph.methods.shortLabel(rootId));
            }
            return true;
        }

        /** 1行を即座に書き出す（溜め込まない） */
        private void emit(int depth) throws IOException {
            writer.writeRow(graph.methods, rootId, pathMethod, pathCallLine, pathNote, depth);
            rowsForEntry++;
            totalRows++;
        }
    }

    /**
     * 呼び出し階層のCSVを1行ずつ書き出す。
     *
     * ヘッダー:
     *   caller,callee,note,callHierarchy...
     *
     * - 呼び出し1件につき1行（起点自身は呼び出し元が無いため出力しない）
     * - caller / callee は Eclipse の Java Stack Trace Console が認識する
     *   "at Class.method(File.java:行)" 形式。貼り付けるだけでソースへ飛べる
     * - callHierarchy 以降は root から現ノードまでを1ノード1列で展開するため、
     *   ヘッダー行とデータ行の列数は一致しない（意図した仕様。
     *   grep の行末マッチのしやすさを優先している）
     * - callHierarchy より後ろに列を追加してはならない（行末マッチが壊れるため）
     *
     * 行番号の使い分け（実際のスタックトレースと同じ考え方）:
     * - callerClass_Method … 呼び出し元が「このノードを呼んでいる行」＝呼び出し箇所
     * - calleeClass_Method … そのメソッド自身の「宣言行」
     */
    static final class CallHierarchyCsvWriter {

        private final BufferedWriter writer;
        private final StringBuilder buf = new StringBuilder(512);
        private final String delim;

        CallHierarchyCsvWriter(Path outputCsv, Charset encoding, boolean bom, String delimiter)
                throws IOException {
            this.writer = Csv.writer(outputCsv, encoding, bom);
            this.delim = delimiter;
            writer.write(String.join(delim, "caller", "callee", "note", "callHierarchy"));
            writer.newLine();
        }

        void writeRow(MethodTable mt, int rootId, int[] pathMethod, int[] pathCallLine,
                      String[] pathNote, int depth) throws IOException {
            buf.setLength(0);

            // caller: 呼び出し元が「このノードを呼んでいる行」を指すスタックトレース形式。
            // Eclipse の Java Stack Trace Console に貼ればソースへ飛べる。
            // 起点自身（depth==0）は出力しないため、depth は必ず1以上。
            int parent = pathMethod[depth - 1];
            buf.append(Csv.esc(stackTrace(mt, parent, pathCallLine[depth]))).append(delim);

            // callee: Excelのフィルタで選べるよう、行番号を含まない安定した表記にする。
            // 行番号を混ぜるとフィルタの選択肢が呼び出し箇所ごとに散らばって使えなくなる。
            int self = pathMethod[depth];
            buf.append(Csv.esc(mt.shortLabel(self))).append(delim);

            // note: 打ち切り理由や解決の由来。callee列に混ぜるとフィルタが壊れ、
            // callHierarchyの末尾に付けると行末grepが壊れるため独立した列に置く。
            buf.append(Csv.esc(pathNote[depth] == null ? "" : pathNote[depth]));

            // callHierarchy: rootから現ノードまでを1ノード1列で展開。
            // 必ず最終列に置く（grep "メソッド名$" の行末マッチを成立させるため）。
            for (int i = 0; i <= depth; i++) {
                buf.append(delim).append(Csv.esc(mt.shortLabel(pathMethod[i])));
            }
            writer.write(buf.toString());
            writer.newLine();
        }

        /**
         * Java のスタックトレースと同じ "at バイナリ名.メソッド(ファイル:行)" 形式。
         *
         * typeFqn() はソース上の正規名（内部クラスも Outer.Inner のようにドット区切り）
         * を返すが、実際のJVMスタックトレースやEclipseの「Javaスタック・トレース・
         * コンソール」が期待するのは内部クラスを $ で区切ったバイナリ名（Outer$Inner）。
         * ドットのままだと内部クラスのメソッドへのジャンプが解決できない。
         */
        private static String stackTrace(MethodTable mt, int id, int line) {
            String file = mt.declFile(id);
            if (file == null || line < 0) {
                return mt.shortLabel(id) + " (unknown)";
            }
            String fileName = file.substring(file.lastIndexOf('/') + 1);
            String pkg = mt.pkg(id);
            String binaryType = mt.simpleTypeName(id).replace('.', '$');
            if (!pkg.isEmpty()) {
                binaryType = pkg + "." + binaryType;
            }
            return "at " + binaryType + "." + mt.methodName(id)
                    + "(" + fileName + ":" + line + ")";
        }

        void close() throws IOException {
            writer.close();
        }
    }

    /** CSVエスケープと、Excel向け出力ライタの生成 */
    static final class Csv {

        /**
         * 指定文字コードでCSVを書くライタを作る。
         *
         * MS932(Shift_JIS)に変換できない文字（一部のUnicode文字や、匿名クラスの
         * 内部キーに紛れ込む記号など）が来ても落ちないよう、置換動作にしている。
         * 既定の Files.newBufferedWriter は変換不可文字で例外を投げるため、
         * ここでエンコーダを明示的に組み立てている。
         *
         * bom=true のときは、ExcelがUTF-8と正しく認識できるよう
         * ファイル先頭にUTF-8のBOM（EF BB BF）を書く。
         */
        static BufferedWriter writer(Path path, Charset cs, boolean bom) throws IOException {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            CharsetEncoder enc = cs.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
            OutputStream os = Files.newOutputStream(path);
            if (bom) {
                os.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            }
            return new BufferedWriter(new OutputStreamWriter(os, enc));
        }

        /**
         * CSV/TSVエスケープ。区切り文字がカンマ・タブのどちらであっても安全なように、
         * カンマ・タブ・ダブルクォート・改行のいずれかを含む場合はダブルクォートで囲む。
         */
        static String esc(String s) {
            if (s == null) {
                return "";
            }
            if (s.indexOf(',') >= 0 || s.indexOf('\t') >= 0
                    || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0) {
                return "\"" + s.replace("\"", "\"\"") + "\"";
            }
            return s;
        }
    }
}

/* ====================================================================
 * 付録: config.properties のサンプル
 * --------------------------------------------------------------------
 * 下記を config.properties として保存し、実行時に引数で渡してください。
 * 相対パスは「この設定ファイルが置かれているディレクトリ」が起点です。
 * ====================================================================

# --- 解析対象 -------------------------------------------------------
# Eclipseプロジェクトのルート（.classpath / .project があるディレクトリ）
project.root=../my-legacy-project

# ソースフォルダを .classpath から読まず明示指定する場合（project.root からの相対）
# 空欄なら .classpath の kind="src" を使用
source.folders=

# .classpath に載っていないjarを追加する場合（カンマ区切り、この設定ファイルからの相対可）
extra.classpath.entries=

source.encoding=UTF-8

# --- モード切り替え -------------------------------------------------
# entry.packages を空にすると「全体モード」になり、起点を指定せずに
# ソース上の全メソッドの呼び出し状況を一覧化する。
#   methods.csv … 全メソッドと入次数・出次数・役割（role）
#   edges.csv   … 解決後の全呼び出し関係（線形サイズ。任意起点のルートを後から再構成可能）
#
# 全体モードでは、呼び出し元が無いメソッドを自動的に起点にして
# call-hierarchy.csv も生成する。不要なら entry.auto=false にする。
entry.auto=true

# 入次数がこの値以上のメソッドを HUB とみなす（改修時の影響範囲が広い箇所）
hub.threshold=20

# --- エントリポイント（複数指定可・カンマ区切り） -----------------------
#   jp.co.xxx.action.*                  … そのパッケージ直下のクラス全部
#   jp.co.xxx.action.**                 … そのパッケージ配下（サブパッケージ含む）全部
#   jp.co.xxx.action.UserAction         … クラス指定
#   jp.co.xxx.action.UserAction#execute … メソッド指定
entry.packages=jp.co.xxx.action.*, jp.co.xxx.batch.**

# 上記に加えクラス名（単純名）で絞り込む正規表現。空欄なら未使用
entry.class.name.pattern=

# --- 出力から除外するパッケージ（書式は entry.packages と同じ） -----------
exclude.packages=java.**, javax.**, jp.co.xxx.common.util.**

# PRUNE … 除外対象のノードとその配下をまるごと出力しない
# SKIP  … 除外対象のノードは出力しないが、その先は親に繋ぎ直して辿り続ける
#         （共通基底クラス経由でDAOを呼ぶ場合など、PRUNEだと先が見えなくなるとき）
exclude.mode=PRUNE

# --- 探索の安全策 ---------------------------------------------------
max.depth=6
max.children.per.node=50

# 1エントリポイントあたりの出力行数の上限（0以下で無制限）。
# 組合せ爆発でCSVが際限なく肥大化するのを防ぐ最後の砦。
max.rows.per.entry=200000

# --- 具象クラスの解決（CHA） ---------------------------------------
# 解決の順序:
#   段0 STATIC_BOUND … private/static/final、finalクラス、コンストラクタ、super呼び出し
#   段1 NO_OVERRIDE  … オーバーライドしている型が無い
#       SINGLE_IMPL  … 候補が1つだけ（IFに実装が1つ等）
#   段4 CHA          … 候補が複数（低確度）
#
# CHA候補を記録するか。falseにすると「解決できなかった箇所」が
# 出力から消えるため、既定はtrue（漏れ防止）。
cha.record=true

# CHA候補を呼び出し階層で展開するか。
# trueにすると 候補数^深さ で爆発するため既定はfalse。
# falseのときは「CHA候補N件（未展開）」という葉を1行だけ出力する。
cha.expand=false
cha.max.candidates=20

# --- 拡張ポイント（プロジェクト固有の具象クラス特定手法） -------------
# 具象クラスの特定方法はプロジェクトごとに異なるため、2フェーズの差し込み口を
# 用意している。実装クラスのFQNをカンマ区切りで指定するとリフレクションで読み込む。
#
#   フェーズA CallSiteHintCollector … ASTから証拠を拾う（抽出時）
#     例) DaoFactory.get("USER_DAO") の文字列リテラルを、その戻り値を受けている
#         ローカル変数のキーに紐づけて記録する
#   フェーズB TypeCandidateProvider … 証拠から具象型を返す（グラフ構築時）
#     例) "USER_DAO" -> jp.co.xxx.dao.UserDaoImpl の対応表を引く
#         SpringのDI設定を読む / 外部の紐付けリストを読む など
#
# 拡張クラスは init(Properties, Path) でこの設定ファイルの内容と置き場所を
# 受け取れるので、独自の設定キーを自由に追加してよい。
resolver.hint.collectors=
resolver.candidate.providers=

# 段0（静的束縛）について
#   private / static / final メソッド、finalクラス、コンストラクタ、super呼び出しは
#   仮想ディスパッチされないため、既定では確定として扱う。
#   DIコンテナのプロキシ（CGLIBはサブクラス生成、JDK動的プロキシはインターフェース実装）は
#   オーバーライドで実現されるため、これらを書き換えることはできない。
#
#   ただしバイトコード織り込み（AspectJのCTW等）や独自フレームワークの仕掛けで
#   前提が崩れる場合は、TypeCandidateProvider#appliesToStaticBound() に true を返す
#   実装を用意すれば、段0の呼び出しにも解決を差し込める。
#   （段0で打ち切ると拡張に到達せず、呼び出し階層がそこで切れてしまうため）
#
#   判定理由は resolutions.csv の label 列に STATIC_BOUND:PRIVATE のように出力されるので、
#   「この確定は妥当か」を後から監査できる。

# --- キャッシュ -----------------------------------------------------
# 差分判定は「最終更新時刻」と「ファイルサイズ」の両方が一致するかで行う。
# バージョン管理がタイムスタンプを復元する設定だと検出漏れの可能性があるため、
# 疑わしいときは false にするかキャッシュファイルを削除すること。
cache.enabled=true
cache.file=./.cache/analysis-cache.tsv

# --- 出力 -----------------------------------------------------------
# 出力ファイルの文字コード。既定はUTF-8-BOM（BOM付きUTF-8）。
#   UTF-8-BOM  … BOM付きUTF-8。既定。ExcelがUTF-8と正しく認識して開ける
#   UTF-8      … BOM無しのUTF-8。Excelで直接開くと文字化けする点に注意
#   MS932      … Shift_JIS。MS932に変換できない文字は '?' に置換される（例外にはしない）
output.encoding=UTF-8-BOM

# 出力ファイルの区切り文字。既定はカンマ区切り（CSV）。
#   COMMA … 通常のCSV（既定）
#   TAB   … タブ区切り。フィールドにカンマを含むデータが多い場合や、
#           MS932とExcelの相性問題を避けたい場合に有効。
#           ダブルクリックでExcelに開かせたい場合は、拡張子を.csvのままにせず
#           .txtにしてください（.csvはOSの「リスト区切り記号」設定でカンマ区切り
#           として解釈されるため、拡張子を変えないとタブ区切りとして開かれません）
output.delimiter=COMMA

output.csv=./output/call-hierarchy.csv
unresolved.csv=./output/unresolved-calls.csv

# 具象クラス解決の内訳。CHAになった箇所＝静的に絞りきれなかった箇所なので、
# カスタム解決を作るかどうかの投資判断はこのファイルの件数を見て決める。
resolutions.csv=./output/resolutions.csv

# 全体モードの出力
methods.csv=./output/methods.csv
edges.csv=./output/edges.csv

# --- 他チーム・他リポジトリからの被参照スキャン -----------------------
# 自分のコードを呼んでいる側のjarを指定する（カンマ区切り。ファイルでもディレクトリでも可）。
# これらは自分の .classpath には現れない点に注意（依存の向きが逆のため）。
# 共有フォルダやNexusから集めて、まとめてディレクトリ指定するのが簡単。
#
# classファイルの定数プールだけを読むため、外部ライブラリは不要。
# 「どのjar・どのクラスが参照しているか」までが分かる（呼び出し元メソッドまでは分からない）。
external.jars=

# 被参照一覧。matchKind は EXACT / INHERITED / IMPLICIT_CTOR
external.usage.csv=./output/external-usage.csv

# 自分の型を参照しているのにメソッドが一致しなかったもの。
# 相手のjarが古い版に対してビルドされている可能性があるため、
# 「使われていない」と判断する前にここを確認する。
external.unmatched.csv=./output/external-unmatched.csv

 * ==================================================================== */
