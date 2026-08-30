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
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.Initializer;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

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
        log("設定: " + Paths.get(confitPath).toAbsolutePath().normalize());
        log("プロジェクトルート: " + config.projectRoot);

        ProjectLayout layout = new ProjectLayout(config);
        log("ソースフォルダ: " + layout.sourceFolders);
        log("ソース文字コード: " + config.sourceEncoding);
        // どの言語バージョンとして解析したかで結果が変わるため、必ず残す
        log("ソースレベル: " + config.sourceLevel
                + (config.sourceLevelAuto
                        ? "（source.level 未指定のため、JDTが対応する最大値）"
                        : "（source.level=" + config.sourceLevelRequested + " の指定による）")
                + " / このJDTの対応上限: " + JavaCore.latestSupportedJavaVersion());
        if (!config.sourceLevelAuto
                && !config.sourceLevelRequested.equals(config.sourceLevel)) {
            // JDTが指定値を黙って丸めた。指定が効いていないことを見えるようにする
            log("※ source.level=" + config.sourceLevelRequested
                    + " はこのJDTでは扱えないため " + config.sourceLevel + " として解析します。");
            log("   より古いレベルが要る場合は、古い版のJDTを使ってください。");
        }
        // 依存jarは library.folders でフォルダごと指定できる。ここで出すのは
        // 実際にJDTへ渡す「*.jar に展開した後」の一覧なので、
        // フォルダ指定がjar単位に展開されているかを確認できる
        String[] classpath = layout.classpathArray();
        log("依存jar: " + classpath.length + " 件");
        for (String cp : classpath) {
            log("  " + cp);
        }

        // --- フェーズ1: 解析とキャッシュ更新（1ファイルずつ書き出して破棄） ---
        System.out.println();
        log("=== フェーズ1/3: ソース解析 ===");
        CachePhaseResult phase1 = new CacheUpdater(layout, config).run();
        log("ソース解析: 再利用=" + phase1.reused
                + " 新規解析=" + phase1.parsed + " 失敗=" + phase1.failed);
        if (phase1.unresolved > 0) {
            log("※ 型解決できなかった呼び出しが " + phase1.unresolved + " 件あります。");
            log("   多い場合は library.folders の設定漏れ（依存jar不足）が疑われます。");
            log("   解決できた呼び出しだけが call-hierarchy.csv に出るため、");
            log("   件数が多いまま使うと呼び出し階層に抜けが出ます。");
        }
        printHeap("フェーズ1完了");

        // --- フェーズ2: キャッシュを2回スキャンしてCSRグラフを構築 ---
        System.out.println();
        log("=== フェーズ2/3: グラフ構築と具象クラス解決 ===");
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
        graph.setDataflow(config.dataflowEnabled, config.dataflowMaxDepth);
        log("型数=" + graph.typeCount()
                + " メソッド数=" + graph.methodCount()
                + " エッジ数=" + graph.edgeCount());
        printHeap("フェーズ2完了");

        // --- フェーズ3: エントリポイントごとにDFSしながら1行ずつ出力 ---
        System.out.println();
        log("=== フェーズ3/3: 出力 ===");
        int[] entries = graph.selectEntryPoints(config);

        InventoryStats inv = InventoryReport.writeMethods(graph, config, entries);
        log(inv.toString());
        log("メソッド一覧: " + config.methodsCsv);

        log("エントリポイント数: " + entries.length);
        if (entries.length == 0 && !config.wholeProjectMode) {
            log("  ※ entry.packages の指定を確認してください（パッケージ名・ワイルドカード）");
        }
        if (config.wholeProjectMode) {
            log("  ※ 起点候補は「呼び出し元が無いメソッド」です。画面入口のほかに");
            log("     デッドコード・テスト・リフレクション経由が混ざるため、");
            log("     methods.csv の inDegree / outDegree / role 列で仕分けてください。");
        }

        // 呼び出し階層と被参照スキャンは同じ call-hierarchy.csv に出す
        long rows;
        CallHierarchyCsvWriter writer = new CallHierarchyCsvWriter(
                config.outputCsv, config.outputEncoding, config.outputBom);
        try {
            StreamingTreeWalker walker = new StreamingTreeWalker(graph, config, writer);
            rows = walker.walkAll(entries);
            if (config.dataflowEnabled && (walker.factoryHits() > 0 || walker.paramHits() > 0
                    || walker.fieldHits() > 0 || walker.newHits() > 0)) {
                log("データフローで具象クラスを特定: "
                        + "new された型から " + walker.newHits() + " 件 / "
                        + "ファクトリの戻り値から " + walker.factoryHits() + " 件 / "
                        + "呼び出し元から渡された引数から " + walker.paramHits() + " 件 / "
                        + "コンストラクタ注入されたフィールドから " + walker.fieldHits() + " 件");
            }

            // 型解決に失敗した呼び出しも、抜け落ちた事実が分かるよう行として残す
            rows += UnresolvedReport.write(graph, config, writer);

            if (!config.externalLibraryFolders.isEmpty()) {
                System.out.println();
                log("=== 外部jarからの被参照スキャン ===");
                ExternalUsageStats ex = ExternalUsageScanner.scan(graph, config, writer);
                log(ex.toString());
                rows += ex.hits + ex.implicitCtors;
                if (ex.unmatched > 0) {
                    log("※ 自分の型への参照なのにメソッドが一致しなかったものが "
                            + ex.unmatched + " 件あります。");
                    log("   相手が古い版のjarに対してビルドされている可能性があるため、");
                    log("   「使われていない」と即断せず確認してください。");
                }
            }
        } finally {
            writer.close();
        }
        printHeap("フェーズ3完了");

        System.out.println();
        log("呼び出し階層: " + config.outputCsv + "（" + rows + " 行）");
        log("完了 (" + (System.currentTimeMillis() - start) + " ms)");
    }

    /**
     * 標準出力への進捗表示。
     *
     * 解析には時間がかかるため、「今どの処理を、全体の何件中どこまで進めているか」
     * 「直近の一定件数に何秒かかったか」を出して、止まっていないことが分かるようにする。
     * 直近の所要時間を出すのは、途中で急に遅くなる箇所（巨大ファイル、ハブメソッド等）を
     * 見つけやすくするため。
     *
     * 使うのはフェーズ1（ソース解析）だけ。以降のフェーズは1件あたりが十分速く、
     * 件数だけが多いので、進捗を出すとログが流れて肝心の警告が埋もれる。
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
        }

        void step(long current) {
            done = current;
            if (done - lastDone >= interval) {
                report();
            }
        }

        private void report() {
            long now = System.nanoTime();
            long recentCount = done - lastDone;
            StringBuilder sb = new StringBuilder();
            sb.append(label).append(' ').append(done);
            if (total > 0) {
                sb.append('/').append(total);
            } else {
                sb.append("件");
            }
            sb.append(String.format(" （直近%d件: %s）", recentCount, fmt((now - lastNanos) / 1e9)));
            log(sb);
            lastNanos = now;
            lastDone = done;
        }

        /** 最後の端数ぶんも1行出して締める */
        long finish() {
            if (done > lastDone) {
                report();
            }
            return (System.nanoTime() - startNanos) / 1000000L;
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
        log("=== " + label + ": [heap] 使用 " + usedMb + "MB / 上限 " + maxMb + "MB");
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

        /** CHA候補を呼び出し階層で展開する際の候補数の上限 */
        static final int CHA_MAX_CANDIDATES = 20;
        /** キャッシュフォルダ内に置くインデックスファイルの名前 */
        static final String CACHE_FILE_NAME = "analysis-cache.tsv";

        final Path configDir;
        final Path projectRoot;
        /** ソースフォルダ（project.root からの相対）。空欄なら .classpath の kind="src" を使う */
        final List<Path> sourceFolders;
        /** 依存jarを集めたフォルダ（project.root からの相対）。.classpath の kind="lib" があれば合算する */
        final List<Path> libraryFolders;
        final String sourceEncoding;
        /** 実際に効いた解析対象ソースのJavaバージョン（JDTから読み戻した値） */
        final String sourceLevel;
        /** 設定ファイルで要求された値。未指定なら空 */
        final String sourceLevelRequested;
        /** source.level が未指定で、JDTが対応する最大値を採用したか */
        final boolean sourceLevelAuto;
        /** 解析に使うJDTのコンパイラ設定（source.level を反映済み） */
        final Map<String, String> compilerOptions;

        final List<PackagePattern> entryPatterns;
        final List<PackagePattern> excludePatterns;

        /** 全体モードか（entry.packages 未指定） */
        final boolean wholeProjectMode;

        final int maxDepth;
        /** 出力行数の上限（0以下で無制限） */
        final long maxRows;

        /** 拡張の init() に渡す。プロジェクト固有のキーを自由に読ませるため */
        final Properties raw;
        final List<String> hintCollectorClasses;
        final List<String> candidateProviderClasses;

        final boolean cacheEnabled;
        /** データフロー解析（ファクトリの戻り値・引数から具象クラスを特定）を使うか */
        final boolean dataflowEnabled;
        /** ファクトリの委譲（return create();）を何段まで辿るか */
        final int dataflowMaxDepth;
        final Path cacheFile;

        /** 他チームのjar（自分のコードを呼んでいる側）。ファイルでもディレクトリでも可 */
        final List<Path> externalLibraryFolders;

        final Path outputCsv;
        final Path methodsCsv;

        /** CSVの出力文字コード。既定はUTF-8-BOM（Excelでそのまま開ける） */
        final Charset outputEncoding;
        /** output.encoding=UTF-8-BOM のとき、ファイル先頭にBOMを書くか */
        final boolean outputBom;

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

            // source.folders / library.folders は project.root からの相対
            List<Path> srcs = new ArrayList<>();
            for (String s : splitList(p.getProperty("source.folders", ""))) {
                srcs.add(resolveUnder(projectRoot, s));
            }
            this.sourceFolders = srcs;

            List<Path> libs = new ArrayList<>();
            for (String s : splitList(p.getProperty("library.folders", ""))) {
                libs.add(resolveUnder(projectRoot, s));
            }
            this.libraryFolders = libs;

            this.sourceEncoding = p.getProperty("source.encoding", "UTF-8").trim();
            this.sourceLevelRequested = p.getProperty("source.level", "").trim();
            this.sourceLevelAuto = this.sourceLevelRequested.isEmpty();
            this.compilerOptions = buildCompilerOptions(this.sourceLevelRequested);
            this.sourceLevel = this.compilerOptions.get(JavaCore.COMPILER_SOURCE);

            // entry.packages が空の場合は「全体モード」に入る。
            // 起点を指定せず、呼び出し元が無いメソッドを自動的に起点にする。
            this.entryPatterns = PackagePattern.parseAll(splitList(p.getProperty("entry.packages", "")));
            this.excludePatterns = PackagePattern.parseAll(splitList(p.getProperty("exclude.packages", "")));
            this.wholeProjectMode = this.entryPatterns.isEmpty();

            this.maxDepth = Integer.parseInt(p.getProperty("max.depth", "50").trim());
            this.maxRows = Long.parseLong(p.getProperty("max.rows", "5000000").trim());

            // 拡張（フェーズA/B）は設定ファイルには載せていない。
            // 使う場合はこのキーを足せば読み込まれる
            this.hintCollectorClasses = splitList(p.getProperty("resolver.hint.collectors", ""));
            this.candidateProviderClasses = splitList(p.getProperty("resolver.candidate.providers", ""));
            this.raw = p;

            this.cacheEnabled = Boolean.parseBoolean(p.getProperty("cache.enabled", "true").trim());
            this.dataflowEnabled =
                    Boolean.parseBoolean(p.getProperty("dataflow.enabled", "true").trim());
            this.dataflowMaxDepth =
                    Integer.parseInt(p.getProperty("dataflow.max.depth", "5").trim());
            this.cacheFile = resolvePath(p.getProperty("cache.folders", "./.cache"))
                    .resolve(CACHE_FILE_NAME);

            // 被参照スキャンの対象は「解析対象プロジェクトの外の世界」なので、
            // ソースや依存jarと同じく project.root からの相対で書けるようにする
            List<Path> ex = new ArrayList<>();
            for (String v : splitList(p.getProperty("external.library.folders", ""))) {
                ex.add(resolveUnder(projectRoot, v));
            }
            this.externalLibraryFolders = ex;

            this.outputCsv = resolvePath(p.getProperty("output.csv", "./output/call-hierarchy.csv"));
            this.methodsCsv = resolvePath(p.getProperty("methods.csv", "./output/methods.csv"));

            String encRaw = p.getProperty("output.encoding", "UTF-8-BOM").trim();
            if ("UTF-8-BOM".equalsIgnoreCase(encRaw)) {
                this.outputEncoding = StandardCharsets.UTF_8;
                this.outputBom = true;
            } else {
                this.outputEncoding = Charset.forName(encRaw);
                this.outputBom = false;
            }
        }

        /**
         * 解析対象ソースのJavaバージョンをJDTのコンパイラ設定に反映する。
         *
         * 未指定なら、クラスパスに入っているJDTが対応する最大値を使う。
         * JDTの既定（古い版では source=1.3 相当）のままだと、generics・
         * diamond演算子・ラムダ式・enum等が軒並み構文/型解決に失敗し、
         * 呼び出しが大量に抜け落ちる。最大値にしておけば
         * 「新しい言語機能を許可する」だけで、対象コードが実際にそれを
         * 使うかどうかには影響しない。
         *
         * 明示指定が要るのは、新しい版では意味が変わる書き方
         * （{@code var}・{@code record}・{@code sealed} 等が識別子として
         * 使われている古いコード）を解析するとき。
         *
         * 設定した値は必ず読み戻す。JDTは対応範囲外の値を例外にせず黙って
         * 丸める（新しい版は 1.7 以下の指定を 1.8 に引き上げる）ため、
         * 指定した値がそのまま効いたとは限らない。
         */
        private static Map<String, String> buildCompilerOptions(String requested) {
            String latest = JavaCore.latestSupportedJavaVersion();
            if (!requested.isEmpty() && !JavaCore.isSupportedJavaVersion(requested)) {
                throw new IllegalArgumentException(
                        "source.level=" + requested + " は、このJDTでは対応していません。"
                        + "指定できる値: " + JavaCore.getAllVersions()
                        + "（未指定なら最大の " + latest + " で動作します）");
            }
            Map<String, String> options = JavaCore.getOptions();
            JavaCore.setComplianceOptions(requested.isEmpty() ? latest : requested, options);
            return options;
        }

        /** 相対パスは設定ファイルのあるディレクトリを起点に解決する */
        private Path resolvePath(String raw) {
            Path p = Paths.get(raw.trim());
            return (p.isAbsolute() ? p : configDir.resolve(p)).normalize();
        }

        /** 相対パスを指定の基準ディレクトリ（project.root）から解決する */
        private static Path resolveUnder(Path base, String raw) {
            Path p = Paths.get(raw.trim());
            return (p.isAbsolute() ? p : base.resolve(p)).normalize();
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
     * プロジェクトの構成（ソースフォルダ・依存jar）を読み取る。
     *
     * source.folders / library.folders が主たる指定方法。Eclipseの .classpath が
     * 無いプロジェクト（GradleやMaven単体の構成等）でも使える。
     *   - source.folders が空なら、.classpath があれば kind="src" から読む
     *   - library.folders は、.classpath の kind="lib"（あれば）と合算する
     *
     * .classpath の kind="con"（Gradle/Mavenのクラスパス・コンテナ等）は
     * 解決しない。JDK標準クラスは setEnvironment の
     * includeRunningVMBootclasspath=true で実行中のJVMから解決させる。
     * kind="var" やリンクリソース、ユーザーライブラリコンテナも未対応のため、
     * 必要な場合は library.folders で明示的に追加すること。
     */
    static final class ProjectLayout {

        final Path projectRoot;
        final List<Path> sourceFolders = new ArrayList<>();
        final List<Path> classpathEntries = new ArrayList<>();

        ProjectLayout(Config config) throws IOException {
            this.projectRoot = config.projectRoot;

            if (!Files.isDirectory(projectRoot)) {
                throw new IOException("project.root がディレクトリとして存在しません: " + projectRoot);
            }

            for (Path sf : config.sourceFolders) {
                if (!Files.isDirectory(sf)) {
                    log("[WARN] source.folders のフォルダが見つかりません: " + sf);
                    continue;
                }
                if (!sourceFolders.contains(sf)) {
                    sourceFolders.add(sf);
                }
            }

            Path dotClasspath = projectRoot.resolve(".classpath");
            if (Files.isRegularFile(dotClasspath)) {
                readDotClasspath(dotClasspath);
            } else if (sourceFolders.isEmpty()) {
                throw new IOException(
                        ".classpath が見つからず、source.folders の指定もありません: " + dotClasspath);
            }

            for (Path lib : config.libraryFolders) {
                if (!Files.exists(lib)) {
                    log("[WARN] library.folders のフォルダが見つかりません: " + lib);
                    continue;
                }
                classpathEntries.add(lib);
            }

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

        /**
         * classpathEntries をクラスパス文字列配列にする。
         *
         * .classpath は kind="con"（Gradle/Mavenのクラスパス・コンテナ等）を
         * 解決できないため、そういったプロジェクトでは .classpath だけでは
         * 依存jarが1つも分からない。その場合は、依存jarを集めたフォルダ
         * （Gradleの application/distribution プラグインが作る lib フォルダ、
         * 手動で集めた lib フォルダ等）を library.folders に指定すれば、
         * ここで直下の *.jar を自動的に展開してクラスパスに加える。
         * （.classpath の kind="lib" と両方指定された場合は単純に合算する）
         */
        String[] classpathArray() {
            List<String> expanded = new ArrayList<>();
            for (Path p : classpathEntries) {
                if (Files.isDirectory(p)) {
                    List<Path> jars = listJarsIn(p);
                    if (jars.isEmpty()) {
                        log("[WARN] クラスパスのディレクトリにjarが見つかりません: " + p);
                    }
                    for (Path jar : jars) {
                        expanded.add(jar.toString());
                    }
                } else {
                    expanded.add(p.toString());
                }
            }
            return expanded.toArray(new String[0]);
        }

        /** ディレクトリ直下（サブフォルダは見ない）の *.jar を、ファイル名順で列挙する */
        private static List<Path> listJarsIn(Path dir) {
            List<Path> jars = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.jar")) {
                for (Path p : ds) {
                    jars.add(p);
                }
            } catch (IOException e) {
                log("[WARN] クラスパスのディレクトリを読み取れません: " + dir + " (" + e + ")");
                return jars;
            }
            jars.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
            return jars;
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

    /*
     * キャッシュファイルの形式（タブ区切り。外部ライブラリ不要でデバッグしやすい）
     *
     *   F  相対パス  更新時刻  サイズ
     *   H  typeFqn  kind(I=IF/A=抽象/C=具象)  親型をカンマ区切り
     *   D  pkg  typeFqn  method  paramSig  declLine  hasBody(1/0)
     *   C  callerPkg callerType callerMethod callerParams
     *      calleePkg calleeType calleeMethod calleeParams  callLine  bindKind  recvKey  recvKind
     *      recvOrigin  argOrigins
     *      bindKind: V=仮想 / P=private / T=static / F=finalメソッド
     *                L=finalクラス / C=コンストラクタ / U=super呼び出し（V以外は静的束縛）
     *      recvKind: レシーバの由来（RecvKind参照）。CHAで絞れない理由の説明に使う
     *      recvOrigin: レシーバの出所（Origin参照）。データフローで具象型を追うのに使う
     *      argOrigins: 実引数の出所。"0=T:jp.co.X;2=A:1" のように 位置=出所 を;で並べる。
     *                  追跡できない引数は載せない（載せないこと自体が「不明」を意味する）
     *   R  pkg  typeFqn  method  paramSig  origin   （そのメソッドが返しうる値の出所）
     *   J  typeFqn  fieldName  origin                （コンストラクタ注入されたフィールド）
     *   X  callerMethodキー  scopeKey  種別  値      （フェーズAが拾った証拠）
     *   U  行  呼び出し元メソッドキー  式  理由
     *
     * F行が現れるたびに、以降のH/D/C/R/J/U行はそのファイルに属する。
     *
     * H行は「単一実装ショートカット」と「CHA」に必須。これが無いと
     * インターフェース・抽象クラスの実装クラスを特定できない。
     * D行のhasBodyは、インターフェースの抽象メソッド（本体なし）と
     * デフォルトメソッド（本体あり）を区別するために必要。
     *
     * R行は「1つでも追跡できないreturnがあれば、そのメソッドの戻り値は特定しない」と
     * 判定するために、追跡できないreturnも U として書き出す。分かった分だけを
     * 書いて残りを黙って捨てると、実際には複数の型を返しうるメソッドを
     * 1つに決め打ちしてしまう。ただし全てのreturnが U のメソッドは
     * 判定に寄与しないので、行そのものを書かない（キャッシュを膨らませないため）。
     */
    /**
     * レシーバ（呼び出しの受け手）の由来。
     *
     * CHAで実装を1つに絞れなかったとき、「なぜ絞れないのか」を説明するために使う。
     * 絞れない理由はレシーバがどこから来たかでほぼ決まる。
     */
    static final class RecvKind {
        /** メソッドの戻り値（ファクトリメソッド等） */
        static final char RETURN = 'M';
        /** 呼び出し元メソッドの引数（メソッド外からインスタンスが渡される） */
        static final char PARAM = 'P';
        /** フィールド変数 */
        static final char FIELD = 'F';
        /** ローカル変数（同一メソッド内の new は追跡済み。それでも絞れなかったもの） */
        static final char LOCAL = 'L';
        /** レシーバなし（this / 暗黙） */
        static final char THIS = 'T';
        /** 型名（static呼び出し） */
        static final char TYPE = 'S';
        /** 配列要素・キャスト式・条件式など、上記に当てはまらないもの */
        static final char OTHER = 'O';

        /** 出力に載せる説明。CHAで絞れなかった理由として使う */
        static String describe(char kind) {
            switch (kind) {
                case RETURN: return "戻り値（ファクトリメソッド等）";
                case PARAM:  return "引数（メソッド外から渡される）";
                case FIELD:  return "フィールド変数";
                case LOCAL:  return "ローカル変数";
                case THIS:   return "自クラス（this）";
                case TYPE:   return "型名（static）";
                default:     return "レシーバ不明";
            }
        }
    }

    /**
     * 式の「出所」。データフロー解析で具象クラスを特定するための最小の表現。
     *
     * {@link RecvKind} が「絞れなかった理由の説明」なのに対し、こちらは
     * 「追跡するための材料」。1つの文字列に詰めてキャッシュへ書き出す。
     *
     *   T:jp.co.xxx.UserDaoImpl   new された具象型（その場で確定）
     *   A:2                       囲みメソッドの3番目の引数（呼び出し元まで遡って初めて分かる）
     *   M:jp.co.xxx.Factory#create()  メソッドの戻り値（その宣言のreturnを見れば分かる）
     *   U                         追跡できない
     *
     * 種別ごとに「次にどこを見れば確定するか」が違うので、この3種を分けている。
     */
    static final class Origin {
        /** new された具象型。値はFQN */
        static final char NEW = 'T';
        /** 囲みメソッドの引数。値は0始まりの引数位置 */
        static final char PARAM = 'A';
        /** メソッドの戻り値。値はメソッドキー（typeFqn#method(params)） */
        static final char RETURN = 'M';
        /** フィールド変数。値は typeFqn#fieldName */
        static final char FIELD = 'F';
        /** 文字列リテラル（またはコンパイル時定数）。値はその文字列 */
        static final char LITERAL = 'L';
        /** Class.forName(引数) で名前指定された型。値は0始まりの引数位置 */
        static final char REFLECT = 'C';
        /** 追跡できない。「分からない」を明示的に持つのが重要（後述） */
        static final char UNKNOWN = 'U';

        static final String UNKNOWN_S = "U";

        /**
         * 実引数リストの区切り。
         *
         *   T:jp.co.Service|0=T:jp.co.UserDaoImpl
         *   M:jp.co.Factory#create(java.lang.String)|0=L:jp.co.UserDaoImpl
         *
         * new やメソッド呼び出しの出所には、その呼び出しの実引数の出所も付ける。
         * コンストラクタ注入されたフィールドや、クラス名の文字列を受け取る
         * ファクトリを追うのに必要なため。
         *
         * '(' ではなく '|' で区切るのは、値の側（メソッドキー）が既に括弧を
         * 含んでいて、括弧だと対応の判定が必要になるから。'|' はFQNにも
         * メソッドキーにも現れない。
         *
         * 引数の出所は入れ子にしない（付いていたら剥がす）。段数を増やすほど
         * 「どの推測が結論に効いたか」が追えなくなるうえ、文字列も長くなる。
         */
        static final char ARGS = '|';

        private Origin() {
        }

        static boolean isUnknown(String origin) {
            return origin == null || origin.isEmpty() || origin.charAt(0) == UNKNOWN;
        }

        static char kindOf(String origin) {
            return isUnknown(origin) ? UNKNOWN : origin.charAt(0);
        }

        /** "T:jp.co.X|0=..." の "jp.co.X" の部分（実引数リストは含まない） */
        static String valueOf(String origin) {
            int i = (origin == null) ? -1 : origin.indexOf(':');
            if (i < 0) {
                return "";
            }
            int bar = origin.indexOf(ARGS, i);
            return (bar < 0) ? origin.substring(i + 1) : origin.substring(i + 1, bar);
        }

        /** "T:jp.co.X|0=..." の "0=..." の部分。無ければ null */
        static String argsOf(String origin) {
            int bar = (origin == null) ? -1 : origin.indexOf(ARGS);
            return (bar < 0) ? null : origin.substring(bar + 1);
        }

        /** 実引数リストを落とした形。引数の出所を入れ子にしないために使う */
        static String head(String origin) {
            int bar = (origin == null) ? -1 : origin.indexOf(ARGS);
            return (bar < 0) ? origin : origin.substring(0, bar);
        }

        static String of(char kind, String value) {
            return kind + ":" + value;
        }

        static String of(char kind, String value, String args) {
            return (args == null || args.isEmpty())
                    ? of(kind, value) : (kind + ":" + value + ARGS + args);
        }

        /** "0=T:jp.co.X;2=A:1" から指定位置の出所を取り出す。無ければ null */
        static String argAt(String args, int index) {
            if (args == null || args.isEmpty()) {
                return null;
            }
            String prefix = index + "=";
            for (String entry : args.split(";")) {
                if (entry.startsWith(prefix)) {
                    return entry.substring(prefix.length());
                }
            }
            return null;
        }
    }

    static final class CacheFormat {
        static final String SEP = "\t";
        /** 形式を変更した場合はここを上げる。旧キャッシュは自動的に破棄される */
        static final String VERSION = "jche-cache-v4";

        /**
         * キャッシュの1行目。形式のバージョンに加えてソースレベルも入れる。
         *
         * 同じソースでも、どの言語バージョンとして解析したかで結果が変わる
         * （古いレベルだと新しい構文が解析できず、呼び出しが抜ける）。
         * 更新時刻とサイズだけを見ていると、設定を変えたのに古い結果を
         * 再利用してしまうため、1行目に含めて丸ごと突き合わせる。
         */
        static String headerFor(String sourceLevel) {
            return VERSION + SEP + "source=" + sourceLevel;
        }

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
        /** 型解決できなかった呼び出しの件数。クラスパス不足の検知に使う */
        long unresolved;
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

        private final ProjectLayout layout;
        private final Config config;

        CacheUpdater(ProjectLayout layout, Config config) {
            this.layout = layout;
            this.config = config;
        }

        CachePhaseResult run() throws IOException {
            CachePhaseResult result = new CachePhaseResult();

            List<Path> javaFiles = layout.listJavaFiles();
            log("Javaファイル数: " + javaFiles.size());

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
            Progress pg = new Progress("ソース解析", javaFiles.size());

            BufferedWriter cacheOut = Files.newBufferedWriter(tmpCache, StandardCharsets.UTF_8);
            try {
                cacheOut.write(CacheFormat.headerFor(config.sourceLevel));
                cacheOut.newLine();

                // --- パス1: 旧キャッシュのストリーミングコピー ---
                if (config.cacheEnabled) {
                    result.unresolved += copyValidBlocks(live, copied, cacheOut);
                }
                result.reused = copied.size();

                // --- パス2: 未処理のファイルだけ解析 ---
                CallEdgeExtractor extractor = new CallEdgeExtractor(layout, config);
                int done = result.reused;
                pg.step(done);
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
                        result.unresolved += fa.unresolved.size();
                        result.parsed++;
                    } catch (Exception e) {
                        result.failed++;
                        log("[WARN] 解析失敗（スキップ）: " + rel + " (" + e.getMessage() + ")");
                    }
                    done++;
                    pg.step(done);
                }
            } finally {
                cacheOut.close();
            }
            pg.finish();

            Files.move(tmpCache, config.cacheFile, StandardCopyOption.REPLACE_EXISTING);
            return result;
        }

        /**
         * 旧キャッシュを1行ずつ読み、まだ有効なブロックだけを新キャッシュへ書き写す。
         *
         * @return 書き写したブロックに含まれる、型解決できなかった呼び出しの件数
         */
        private long copyValidBlocks(Map<String, FileStat> live, Set<String> copied,
                                      BufferedWriter cacheOut)
                throws IOException {
            long unresolved = 0L;
            if (!Files.isRegularFile(config.cacheFile)) {
                return unresolved;
            }
            BufferedReader in = Files.newBufferedReader(config.cacheFile, StandardCharsets.UTF_8);
            try {
                String first = in.readLine();
                if (first == null
                        || !CacheFormat.headerFor(config.sourceLevel).equals(first.trim())) {
                    // 形式が変わった場合のほか、source.level が変わった場合もここで破棄する。
                    // 言語バージョンが違えば同じソースでも解析結果が変わるため、
                    // 更新時刻とサイズが一致していても再利用してはいけない
                    log("[cache] 形式またはソースレベルが異なるため既存キャッシュを破棄します");
                    return unresolved;
                }
                boolean keeping = false;
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.isEmpty()) {
                        continue;
                    }
                    char t = line.charAt(0);
                    if (t == 'F') {
                        String[] f = line.split(CacheFormat.SEP, -1);
                        keeping = false;
                        if (f.length >= 4) {
                            FileStat st = live.get(f[1]);
                            try {
                                if (st != null && st.mtime == Long.parseLong(f[2])
                                        && st.size == Long.parseLong(f[3])) {
                                    keeping = true;
                                    copied.add(f[1]);
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
                            unresolved++;
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
                        String.valueOf(c.callLine), String.valueOf(c.bindKind), c.recvKey,
                    String.valueOf(c.recvKind), c.recvOrigin, c.argOrigins));
                w.newLine();
            }
            writeReturns(fa, w);
            for (FieldInjectionRec j : fa.fieldInjections) {
                w.write(String.join(CacheFormat.SEP, "J",
                        j.typeFqn, j.fieldName, j.origin));
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

        /**
         * R行を書き出す。
         *
         * 全てのreturnが「追跡できない」メソッドは書かない。書いても解決には
         * 使えないうえ、returnを持つメソッドは大量にあるためキャッシュだけが膨らむ。
         * 逆に、1つでも追跡できたメソッドは U のreturnも含めて全部書く
         * （分かった分だけを書くと、複数の型を返しうるメソッドを1つに決め打ちしてしまう）。
         */
        private static void writeReturns(FileAnalysis fa, BufferedWriter w) throws IOException {
            if (fa.returns.isEmpty()) {
                return;
            }
            Set<String> useful = new HashSet<>();
            for (ReturnRec r : fa.returns) {
                if (!Origin.isUnknown(r.origin)) {
                    useful.add(r.methodKey());
                }
            }
            if (useful.isEmpty()) {
                return;
            }
            for (ReturnRec r : fa.returns) {
                if (!useful.contains(r.methodKey())) {
                    continue;
                }
                w.write(String.join(CacheFormat.SEP, "R",
                        r.pkg, r.typeFqn, r.methodName, r.paramSig, r.origin));
                w.newLine();
            }
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
        /** レシーバの由来（{@link RecvKind}）。CHAで絞れなかった理由の説明に使う */
        final char recvKind;
        /** レシーバの出所（{@link Origin}）。データフローで具象型を追うのに使う。無ければ空 */
        final String recvOrigin;
        /** 実引数の出所。"位置=出所" を ; で並べたもの。無ければ空 */
        final String argOrigins;

        CallEdgeRec(String callerPkg, String callerType, String callerMethod, String callerParams,
                    String calleePkg, String calleeType, String calleeMethod, String calleeParams,
                    int callLine, char bindKind, String recvKey, char recvKind,
                    String recvOrigin, String argOrigins) {
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
            this.recvKind = recvKind;
            this.recvOrigin = (recvOrigin == null) ? "" : recvOrigin;
            this.argOrigins = (argOrigins == null) ? "" : argOrigins;
        }
    }

    /**
     * コンストラクタ注入されたフィールド1件（J行の元データ）。
     * 「このフィールドには必ずこれが入る」と言い切れるものだけが作られる。
     */
    static final class FieldInjectionRec {
        final String typeFqn;
        final String fieldName;
        final String origin;

        FieldInjectionRec(String typeFqn, String fieldName, String origin) {
            this.typeFqn = typeFqn;
            this.fieldName = fieldName;
            this.origin = origin;
        }
    }

    /** そのメソッドが返しうる値の出所1件（R行の元データ） */
    static final class ReturnRec {
        final String pkg;
        final String typeFqn;
        final String methodName;
        final String paramSig;
        final String origin;

        ReturnRec(String pkg, String typeFqn, String methodName, String paramSig, String origin) {
            this.pkg = pkg;
            this.typeFqn = typeFqn;
            this.methodName = methodName;
            this.paramSig = paramSig;
            this.origin = origin;
        }

        String methodKey() {
            return typeFqn + "#" + methodName + "(" + paramSig + ")";
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
        final List<ReturnRec> returns = new ArrayList<>();
        final List<FieldInjectionRec> fieldInjections = new ArrayList<>();
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

        private final ProjectLayout layout;
        private final Charset encoding;
        private final Map<String, String> compilerOptions;
        private final String[] classpath;
        private final String[] sourcepath;
        private final String[] encodings;

        private final List<CallSiteHintCollector> collectors;

        CallEdgeExtractor(ProjectLayout layout, Config config) {
            this.collectors = Plugins.load(config.hintCollectorClasses, CallSiteHintCollector.class);
            this.layout = layout;
            this.encoding = Charset.forName(config.sourceEncoding);
            // 準拠レベル（source.level）は Config が解決済み。
            // 既定のまま使うと generics・diamond演算子・ラムダ式・enum等が
            // 軒並み構文/型解決に失敗するので、必ずこちらを使うこと
            this.compilerOptions = config.compilerOptions;
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

            /** 呼び出し元を特定できないことを表す番兵（ArrayDequeはnullを保持できないため） */
            private static final List<String[]> UNKNOWN = java.util.Collections.emptyList();

            private final CompilationUnit cu;
            private final FileAnalysis out;
            private final List<CallSiteHintCollector> collectors;

            /**
             * 現在の呼び出し元のスタック。通常は要素1件（そのメソッド自身）だが、
             * インスタンスフィールド初期化子・インスタンス初期化ブロックの中では
             * 「そのクラスの、this(...)委譲していない全コンストラクタ」が
             * 複数件入る（コンパイル後、実際にそれら全部に複製されるため）。
             */
            private final ArrayDeque<List<String[]>> methodStack = new ArrayDeque<>();

            /** 現在囲まれている型ごとの状態（{@link TypeContext} 参照） */
            private final ArrayDeque<TypeContext> typeContextStack = new ArrayDeque<>();

            /**
             * 現在のメソッドの「変数の出所」（IVariableBinding.getKey() -> {@link Origin}）。
             * methodStack と対で push/pop する。
             */
            private final ArrayDeque<Map<String, String>> originScopes = new ArrayDeque<>();

            /**
             * 現在のラムダ式の入れ子の深さ。
             *
             * ラムダ式の中の return は、囲みメソッドの return ではなくラムダ自身の
             * 戻り値。これを囲みメソッドの戻り値として記録すると、ファクトリメソッドの
             * 戻り値型を誤って狭めてしまうため、0 のときだけ R行を記録する。
             * （呼び出しの帰属はこれまで通り囲みメソッドのままでよい。
             *   ラムダの中の呼び出しは、実際にその囲みメソッドの一部として書かれている）
             */
            private int lambdaDepth;
            /** MethodDeclaration をまたぐときに lambdaDepth を退避するスタック */
            private final ArrayDeque<Integer> lambdaDepthStack = new ArrayDeque<>();

            Visitor(CompilationUnit cu, FileAnalysis out) {
                this(cu, out, java.util.Collections.<CallSiteHintCollector>emptyList());
            }

            Visitor(CompilationUnit cu, FileAnalysis out, List<CallSiteHintCollector> collectors) {
                this.cu = cu;
                this.out = out;
                this.collectors = collectors;
            }

            // ------------------------------------------------------------
            // データフロー解析（引数・ファクトリの戻り値から具象クラスを特定する）
            //
            // メソッドに入る直前に、そのメソッド本体を1回だけ先読みして
            // 「変数 -> 出所」の表を作る（scanOrigins）。走査しながら作らないのは、
            // 同じ変数への代入が後ろにある場合に取りこぼすため。
            //
            //     X x = new A();
            //     for (...) { x.m(); x = new B(); }
            //
            // 走査順に作ると x.m() の時点では x は A に見えるが、2周目は B。
            // 先読みして「複数の出所があれば U（不明）」に倒すことで、
            // 具象クラスを誤って1つに決め打ちすることを防ぐ。
            // 段2の new 追跡と同じく、フロー非依存・安全側の方針。
            // ------------------------------------------------------------

            /** 引数を出所として登録した、メソッド用の初期スコープ */
            private Map<String, String> paramScopeOf(MethodDeclaration node) {
                Map<String, String> scope = new HashMap<>();
                List<?> params = node.parameters();
                for (int i = 0; i < params.size(); i++) {
                    Object o = params.get(i);
                    if (!(o instanceof SingleVariableDeclaration)) {
                        continue;
                    }
                    IVariableBinding vb = ((SingleVariableDeclaration) o).resolveBinding();
                    if (vb != null && vb.getKey() != null) {
                        scope.put(vb.getKey(), Origin.of(Origin.PARAM, String.valueOf(i)));
                    }
                }
                return scope;
            }

            /**
             * 本体を先読みして、ローカル変数の出所を集める。
             *
             * 同じ変数に出所の違う代入が複数あれば U（不明）にする。
             * ローカル変数どうしの別名付け（{@code Y y = x;}）は、先読みが1回のため
             * x が y より後ろで宣言されていると追えない。安全側（U）に倒れるだけなので
             * 実害は「解決できない」に留まる。
             */
            private Map<String, String> scanOrigins(ASTNode body, final Map<String, String> scope) {
                if (body == null) {
                    return scope;
                }
                // 先読み中は originOf() が参照するスコープを差し替える
                enterScope(scope);
                try {
                    body.accept(new ASTVisitor() {
                        @Override
                        public boolean visit(VariableDeclarationFragment n) {
                            IVariableBinding vb = n.resolveBinding();
                            if (vb != null && !vb.isField()) {
                                mergeOrigin(scope, vb.getKey(), n.getInitializer());
                            }
                            return true;
                        }

                        @Override
                        public boolean visit(Assignment n) {
                            Expression lhs = n.getLeftHandSide();
                            if (!(lhs instanceof SimpleName)) {
                                return true;
                            }
                            IBinding b = ((SimpleName) lhs).resolveBinding();
                            if (b instanceof IVariableBinding && !((IVariableBinding) b).isField()) {
                                mergeOrigin(scope, ((IVariableBinding) b).getKey(),
                                        n.getRightHandSide());
                            }
                            return true;
                        }
                    });
                } finally {
                    leaveScope();
                }
                return scope;
            }

            /** 同じ変数に別の出所が現れたら U（不明）に落とす */
            private void mergeOrigin(Map<String, String> scope, String varKey, Expression value) {
                if (varKey == null) {
                    return;
                }
                String origin = originOf(value);
                if (origin == null) {
                    origin = Origin.UNKNOWN_S;
                }
                String prev = scope.get(varKey);
                scope.put(varKey, (prev == null || prev.equals(origin))
                        ? origin : Origin.UNKNOWN_S);
            }

            private void enterScope(Map<String, String> scope) {
                originScopes.push(scope);
            }

            private void leaveScope() {
                if (!originScopes.isEmpty()) {
                    originScopes.pop();
                }
            }

            /**
             * 式の出所（{@link Origin}）。追跡できなければ null。
             *
             * ここで返せるのは「どこから来たか」までで、具象型が確定するとは限らない。
             * A（引数）は呼び出し元、M（戻り値）はその宣言のreturnを見て初めて決まる。
             */
            private String originOf(Expression ex) {
                Expression e = unwrap(ex);
                if (e == null) {
                    return null;
                }
                if (e instanceof ClassInstanceCreation) {
                    ClassInstanceCreation cic = (ClassInstanceCreation) e;
                    String t = createdTypeOf(cic);
                    // 実引数も付ける。コンストラクタ注入されたフィールドを追うのに要る
                    return (t == null) ? null
                            : Origin.of(Origin.NEW, t, argOriginsOf(cic.arguments()));
                }
                if (e instanceof MethodInvocation) {
                    MethodInvocation mi = (MethodInvocation) e;
                    String reflected = reflectiveOriginOf(mi);
                    if (reflected != null) {
                        return reflected;
                    }
                    String[] r = toRef(mi.resolveMethodBinding());
                    // 実引数も付ける。クラス名の文字列を受け取るファクトリを追うのに要る
                    return (r == null) ? null
                            : Origin.of(Origin.RETURN, r[1] + "#" + r[2] + "(" + r[3] + ")",
                                    argOriginsOf(mi.arguments()));
                }
                if (e instanceof StringLiteral) {
                    return classNameLiteral(((StringLiteral) e).getLiteralValue());
                }
                if (e instanceof SimpleName || e instanceof QualifiedName) {
                    IBinding b = (e instanceof SimpleName)
                            ? ((SimpleName) e).resolveBinding()
                            : ((QualifiedName) e).resolveBinding();
                    if (b instanceof IVariableBinding) {
                        return variableOriginOf((IVariableBinding) b);
                    }
                }
                if (e instanceof FieldAccess) {
                    IVariableBinding vb = ((FieldAccess) e).resolveFieldBinding();
                    if (vb != null) {
                        return variableOriginOf(vb);
                    }
                }
                return null;
            }

            /** ローカル変数・引数はスコープ表から、フィールドは宣言型から出所を決める */
            private String variableOriginOf(IVariableBinding vb) {
                if (!vb.isField()) {
                    return localOriginOf(vb);
                }
                // static final String などのコンパイル時定数は、その文字列そのもの。
                // Factory.create(Names.USER_DAO) のような書き方を追えるようにする
                Object cv = vb.getConstantValue();
                if (cv instanceof String) {
                    return classNameLiteral((String) cv);
                }
                ITypeBinding owner = vb.getDeclaringClass();
                if (owner == null) {
                    return null;
                }
                String ownerFqn = typeNameOf(owner.getErasure() != null
                        ? owner.getErasure() : owner);
                return (ownerFqn == null) ? null
                        : Origin.of(Origin.FIELD, ownerFqn + "#" + vb.getName());
            }

            /**
             * ローカル変数・引数の出所。
             *
             * まず今のメソッドのスコープを見る。無ければ外側のメソッドのスコープへ辿る。
             * 匿名クラス・ローカルクラスのメソッドは MethodDeclaration なので独自の
             * スコープを持つが、その中から囲みメソッドの変数を参照できる（捕捉）。
             *
             *     void run() {
             *         Dao dao = new UserDaoImpl();
             *         exec(new Task() {
             *             public void run() { dao.select(); }   // ← ここ
             *         });
             *     }
             *
             * 外側の値を持ち込めるのは、**捕捉できる変数が final か実質的final
             * （effectively final）だと言語仕様が保証しているから**。捕捉した後で
             * 中身が別のインスタンスに差し替わることはないので、囲みメソッドで
             * 分かった出所がそのまま通用する。実質的finalでない変数はそもそも
             * 捕捉できずコンパイルが通らないが、判断の根拠を実装にも残すため明示的に確認する。
             */
            private String localOriginOf(IVariableBinding vb) {
                String key = vb.getKey();
                boolean enclosing = false;
                for (Map<String, String> scope : originScopes) {
                    String origin = scope.get(key);
                    if (origin == null) {
                        enclosing = true;   // 今のメソッドには無い。1つ外へ
                        continue;
                    }
                    if (Origin.isUnknown(origin)) {
                        return null;
                    }
                    if (!enclosing) {
                        return origin;
                    }
                    return isEffectivelyFinal(vb) ? frameIndependent(origin) : null;
                }
                return null;
            }

            /** final または実質的final（＝もう中身が変わらないと言い切れる） */
            private boolean isEffectivelyFinal(IVariableBinding vb) {
                return vb.isEffectivelyFinal() || Modifier.isFinal(vb.getModifiers());
            }

            /**
             * 別のメソッドの中へ持ち込んでも意味が変わらない出所だけを残す。
             *
             * T（newされた具象型）とM（メソッドの戻り値）は、どこから見ても同じものを指す。
             * 一方 A（引数）とF（フィールド）は「今実行しているメソッドの引数」
             * 「今のオブジェクトのフィールド」という相対的な意味なので、匿名クラスの中へ
             * 持ち込むと別物を指してしまう（匿名クラスの run() には引数が無い、など）。
             * 捕捉された引数を追うには匿名クラスの生成箇所まで遡る必要があり、
             * それは現在の経路の持ち方では表現できないため、ここで落とす。
             */
            private String frameIndependent(String origin) {
                char kind = Origin.kindOf(origin);
                return (kind == Origin.NEW || kind == Origin.RETURN) ? origin : null;
            }

            /**
             * 文字列リテラルのうち、完全修飾クラス名の形をしたものだけ出所にする。
             *
             * ログの文言やSQLまで記録すると、キャッシュが文字列で埋まる割に
             * 何の役にも立たない。「ドットを含み、各要素が識別子で、最後の要素が
             * 英大文字で始まる」を条件にする。誤って拾っても、解決時に
             * その型がプロジェクトに無ければ使われないだけで害はない。
             */
            private String classNameLiteral(String value) {
                if (value == null || value.isEmpty() || value.indexOf('.') < 0) {
                    return null;
                }
                int last = 0;
                for (int i = 0; i <= value.length(); i++) {
                    if (i < value.length() && value.charAt(i) != '.') {
                        char c = value.charAt(i);
                        if (!Character.isJavaIdentifierPart(c) && c != '$') {
                            return null;
                        }
                        continue;
                    }
                    if (i == last) {
                        return null;   // 空の要素（先頭・末尾・連続するドット）
                    }
                    if (!Character.isJavaIdentifierStart(value.charAt(last))) {
                        return null;
                    }
                    last = i + 1;
                }
                int dot = value.lastIndexOf('.');
                return Character.isUpperCase(value.charAt(dot + 1))
                        ? Origin.of(Origin.LITERAL, value) : null;
            }

            /**
             * {@code Class.forName(x).newInstance()} 系の生成を出所にする。
             *
             * 対応する形:
             *   Class.forName(x).newInstance()
             *   Class.forName(x).getDeclaredConstructor().newInstance()
             *   Class.forName(x).getConstructor().newInstance()
             *
             * x が文字列リテラルなら型が確定するので T、
             * 囲みメソッドの引数なら「その引数で名前指定された型」として C を返す。
             * C は、そのメソッドを呼んでいる側の実引数を見て初めて確定する。
             */
            private String reflectiveOriginOf(MethodInvocation mi) {
                if (!"newInstance".equals(mi.getName().getIdentifier())) {
                    return null;
                }
                Expression recv = unwrap(mi.getExpression());
                // getDeclaredConstructor() / getConstructor() を挟む形を1段だけ剥がす
                if (recv instanceof MethodInvocation) {
                    String n = ((MethodInvocation) recv).getName().getIdentifier();
                    if ("getDeclaredConstructor".equals(n) || "getConstructor".equals(n)) {
                        recv = unwrap(((MethodInvocation) recv).getExpression());
                    }
                }
                if (!(recv instanceof MethodInvocation)) {
                    return null;
                }
                MethodInvocation forName = (MethodInvocation) recv;
                if (!"forName".equals(forName.getName().getIdentifier())) {
                    return null;
                }
                IMethodBinding fb = forName.resolveMethodBinding();
                if (fb == null || fb.getDeclaringClass() == null
                        || !"java.lang.Class".equals(fb.getDeclaringClass().getQualifiedName())) {
                    return null;
                }
                if (forName.arguments().isEmpty()) {
                    return null;
                }
                Expression arg = unwrap((Expression) forName.arguments().get(0));
                String argOrigin = originOf(arg);
                if (Origin.kindOf(argOrigin) == Origin.LITERAL) {
                    // クラス名が determined。生成される型そのものが分かる
                    return Origin.of(Origin.NEW, Origin.valueOf(argOrigin));
                }
                if (Origin.kindOf(argOrigin) == Origin.PARAM) {
                    return Origin.of(Origin.REFLECT, Origin.valueOf(argOrigin));
                }
                return null;
            }

            /** 括弧とキャストを剥がす。どちらも実体のインスタンスは変えない */
            private Expression unwrap(Expression ex) {
                Expression e = ex;
                for (int guard = 0; guard < 8; guard++) {
                    if (e instanceof ParenthesizedExpression) {
                        e = ((ParenthesizedExpression) e).getExpression();
                    } else if (e instanceof CastExpression) {
                        e = ((CastExpression) e).getExpression();
                    } else {
                        return e;
                    }
                }
                return e;
            }

            /** 実引数の出所を "位置=出所;位置=出所" にまとめる。追跡できない引数は載せない */
            private String argOriginsOf(List<?> args) {
                if (args == null || args.isEmpty()) {
                    return "";
                }
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < args.size(); i++) {
                    Object o = args.get(i);
                    if (!(o instanceof Expression)) {
                        continue;
                    }
                    // 引数の出所は入れ子にしない（実引数リストが付いていたら剥がす）
                    String origin = Origin.head(originOf((Expression) o));
                    if (origin == null) {
                        continue;
                    }
                    if (sb.length() > 0) {
                        sb.append(';');
                    }
                    sb.append(i).append('=').append(origin);
                }
                return sb.toString();
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
                List<String[]> callers = current();
                if (callers == null || varKey == null) {
                    return;
                }
                String type = createdTypeOf(cic);
                if (type == null) {
                    return;
                }
                // 呼び出し元が複数（インスタンス初期化子等）でも全件に紐づける。
                // 一部にしか付けないと、その呼び出し元経由の解決だけ証拠を見つけられなくなる。
                for (String[] c : callers) {
                    out.hints.add(new HintRec(c[1] + "#" + c[2] + "(" + c[3] + ")", varKey, "NEW", type));
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

            /**
             * レシーバがどこから来たかを判定する（{@link RecvKind}）。
             *
             * CHAで実装を絞れなかったときに「なぜ絞れないのか」を出力へ載せるため。
             * 例: 戻り値ならファクトリメソッド、引数ならメソッド外から渡されている、
             * という具合に、利用者が次に何を調べるべきかが変わる。
             */
            private char recvKindOf(Expression ex) {
                if (ex == null) {
                    return RecvKind.THIS;
                }
                if (ex instanceof MethodInvocation) {
                    return RecvKind.RETURN;
                }
                if (ex instanceof ClassInstanceCreation) {
                    return RecvKind.LOCAL;   // new した直後に呼ぶ形。型は確定している
                }
                if (ex instanceof FieldAccess) {
                    return RecvKind.FIELD;
                }
                if (ex instanceof SimpleName || ex instanceof QualifiedName) {
                    IBinding b = (ex instanceof SimpleName)
                            ? ((SimpleName) ex).resolveBinding()
                            : ((QualifiedName) ex).resolveBinding();
                    if (b instanceof ITypeBinding) {
                        return RecvKind.TYPE;
                    }
                    if (b instanceof IVariableBinding) {
                        IVariableBinding vb = (IVariableBinding) b;
                        if (vb.isField()) {
                            return RecvKind.FIELD;
                        }
                        if (vb.isParameter()) {
                            return RecvKind.PARAM;
                        }
                        return RecvKind.LOCAL;
                    }
                }
                return RecvKind.OTHER;
            }

            @Override
            public boolean visit(TypeDeclaration node) {
                ITypeBinding tb = node.resolveBinding();
                recordType(tb);
                pushTypeContext(tb, node.bodyDeclarations(),
                        cu.getLineNumber(node.getName().getStartPosition()));
                return true;
            }

            @Override
            public void endVisit(TypeDeclaration node) {
                popTypeContext();
            }

            @Override
            public boolean visit(AnonymousClassDeclaration node) {
                // 匿名クラスも型階層に載せる。載せないとオーバーライド候補から漏れる
                ITypeBinding tb = node.resolveBinding();
                recordType(tb);
                // 匿名クラスには名前が無いため、本体の開始位置を代わりに使う
                pushTypeContext(tb, node.bodyDeclarations(), cu.getLineNumber(node.getStartPosition()));
                return true;
            }

            @Override
            public void endVisit(AnonymousClassDeclaration node) {
                popTypeContext();
            }

            /**
             * 型ごとの合成メソッド（{@code <clinit>}・暗黙のデフォルトコンストラクタ）の状態。
             * これらはソース上に対応するAST宣言が無いため、初めて呼び出し元として
             * 使われた時点で1回だけ methods.csv 用の宣言（D行相当）を合成する。
             * 常に合成すると、静的初期化子もフィールド初期化子も持たない大多数の
             * クラスにまで {@code <clinit>} 等が現れてノイズになるため。
             */
            private static final class TypeContext {
                final ITypeBinding binding;
                final List<String[]> rootConstructors;
                final int declLine;
                boolean clinitDeclared;

                TypeContext(ITypeBinding binding, List<String[]> rootConstructors, int declLine) {
                    this.binding = binding;
                    this.rootConstructors = rootConstructors;
                    this.declLine = declLine;
                }
            }

            private void pushTypeContext(ITypeBinding tb, List<?> bodyDeclarations, int declLine) {
                TypeContext ctx = buildTypeContext(tb, bodyDeclarations, declLine);
                typeContextStack.push(ctx);
                collectFieldInjections(tb, bodyDeclarations, ctx.rootConstructors);
            }

            // ------------------------------------------------------------
            // コンストラクタ注入されたフィールド（J行）
            //
            // 「このフィールドには、必ずコンストラクタの何番目の引数が入る」と
            // 言い切れるフィールドだけを記録する。呼び出し階層を降りるときに
            // new の実引数と突き合わせて具象クラスを決めるのに使う。
            //
            // 言い切るには次を全部満たす必要がある。1つでも崩れたら記録しない。
            //   (a) private または final（クラスの外から代入されない）
            //   (b) 代入がコンストラクタの本体か、フィールド初期化子の中だけにある
            //       （setterや他のメソッドで後から差し替わらない）
            //   (c) 初期化子を持つか、this(...)委譲していない全てのコンストラクタで
            //       代入されている（代入されない生成経路が無い）
            //   (d) それらの代入の出所が全て一致する
            // ------------------------------------------------------------

            /** 集計中の1フィールド分の状態 */
            private static final class FieldInjection {
                String origin;                 // 一致している出所。食い違ったら null
                boolean broken;                // 上の条件を満たさなくなった
                boolean hasInitializer;
                final Set<String> assignedIn = new HashSet<>();   // 代入したコンストラクタ

                void merge(String o) {
                    if (o == null || (origin != null && !origin.equals(o))) {
                        broken = true;
                        return;
                    }
                    origin = o;
                }
            }

            private void collectFieldInjections(ITypeBinding tb, List<?> bodyDeclarations,
                                                 List<String[]> rootConstructors) {
                if (tb == null || bodyDeclarations.isEmpty()) {
                    return;
                }
                ITypeBinding erased = (tb.getErasure() != null) ? tb.getErasure() : tb;
                final String typeFqn = typeNameOf(erased);
                if (typeFqn == null) {
                    return;
                }
                Map<String, FieldInjection> fields = new LinkedHashMap<>();
                Set<String> rootKeys = new HashSet<>();
                for (String[] r : rootConstructors) {
                    rootKeys.add(r[2] + "(" + r[3] + ")");
                }

                for (Object o : bodyDeclarations) {
                    if (o instanceof FieldDeclaration) {
                        scanFieldInitializers((FieldDeclaration) o, typeFqn, fields);
                    } else if (o instanceof MethodDeclaration) {
                        MethodDeclaration md = (MethodDeclaration) o;
                        String key = keyOf(md);
                        boolean isRootCtor = md.isConstructor() && rootKeys.contains(key);
                        scanAssignments(md, typeFqn, fields, isRootCtor ? key : null);
                    }
                }
                emitFieldInjections(typeFqn, fields, rootKeys.size());
            }

            private String keyOf(MethodDeclaration md) {
                String[] ref = toRef(md.resolveBinding());
                return (ref == null) ? "?" : ref[2] + "(" + ref[3] + ")";
            }

            /** フィールド初期化子（private Dao dao = new UserDao();）を拾う */
            private void scanFieldInitializers(FieldDeclaration fd, String typeFqn,
                                                Map<String, FieldInjection> fields) {
                for (Object f : fd.fragments()) {
                    if (!(f instanceof VariableDeclarationFragment)) {
                        continue;
                    }
                    VariableDeclarationFragment frag = (VariableDeclarationFragment) f;
                    IVariableBinding vb = frag.resolveBinding();
                    if (vb == null || !isTrackableField(vb, typeFqn)) {
                        continue;
                    }
                    FieldInjection fi = injectionOf(fields, vb.getName());
                    if (frag.getInitializer() == null) {
                        continue;
                    }
                    fi.hasInitializer = true;
                    fi.merge(Origin.head(originOf(frag.getInitializer())));
                }
            }

            /**
             * メソッド本体の中のフィールドへの代入を拾う。
             *
             * @param ctorKey 根のコンストラクタならそのキー。それ以外（setter・
             *                通常のメソッド・this()委譲するコンストラクタ）なら null。
             *                null の場合、代入されたフィールドは追跡対象から外す
             */
            private void scanAssignments(MethodDeclaration md, final String typeFqn,
                                          final Map<String, FieldInjection> fields,
                                          final String ctorKey) {
                Block body = md.getBody();
                if (body == null) {
                    return;
                }
                enterScope(paramScopeOf(md));
                try {
                    body.accept(new ASTVisitor() {
                        @Override
                        public boolean visit(Assignment n) {
                            IVariableBinding vb = assignedFieldOf(n.getLeftHandSide());
                            if (vb == null || !isTrackableField(vb, typeFqn)) {
                                return true;
                            }
                            FieldInjection fi = injectionOf(fields, vb.getName());
                            if (ctorKey == null) {
                                fi.broken = true;   // 生成後に差し替わりうる
                                return true;
                            }
                            fi.assignedIn.add(ctorKey);
                            fi.merge(Origin.head(originOf(n.getRightHandSide())));
                            return true;
                        }
                    });
                } finally {
                    leaveScope();
                }
            }

            /** 代入先がこの型のフィールドなら、そのバインディング */
            private IVariableBinding assignedFieldOf(Expression lhs) {
                Expression e = unwrap(lhs);
                if (e instanceof FieldAccess) {
                    return ((FieldAccess) e).resolveFieldBinding();
                }
                IBinding b = null;
                if (e instanceof SimpleName) {
                    b = ((SimpleName) e).resolveBinding();
                } else if (e instanceof QualifiedName) {
                    b = ((QualifiedName) e).resolveBinding();
                }
                return (b instanceof IVariableBinding && ((IVariableBinding) b).isField())
                        ? (IVariableBinding) b : null;
            }

            /**
             * 追跡対象のフィールドか。
             *
             * この型自身のインスタンスフィールドで、private または final のものだけ。
             * それ以外は、このファイルを読んだだけでは代入箇所を数え上げられない。
             */
            private boolean isTrackableField(IVariableBinding vb, String typeFqn) {
                if (!vb.isField() || Modifier.isStatic(vb.getModifiers())) {
                    return false;
                }
                int mods = vb.getModifiers();
                if (!Modifier.isPrivate(mods) && !Modifier.isFinal(mods)) {
                    return false;
                }
                ITypeBinding owner = vb.getDeclaringClass();
                if (owner == null) {
                    return false;
                }
                ITypeBinding er = (owner.getErasure() != null) ? owner.getErasure() : owner;
                return typeFqn.equals(typeNameOf(er));
            }

            private FieldInjection injectionOf(Map<String, FieldInjection> fields, String name) {
                FieldInjection fi = fields.get(name);
                if (fi == null) {
                    fi = new FieldInjection();
                    fields.put(name, fi);
                }
                return fi;
            }

            private void emitFieldInjections(String typeFqn, Map<String, FieldInjection> fields,
                                              int rootConstructorCount) {
                for (Map.Entry<String, FieldInjection> e : fields.entrySet()) {
                    FieldInjection fi = e.getValue();
                    if (fi.broken || fi.origin == null) {
                        continue;
                    }
                    // 代入されない生成経路があると、そのフィールドの中身は別物になる
                    boolean coveredByCtors = fi.assignedIn.size() >= rootConstructorCount;
                    if (!fi.hasInitializer && !coveredByCtors) {
                        continue;
                    }
                    out.fieldInjections.add(
                            new FieldInjectionRec(typeFqn, e.getKey(), fi.origin));
                }
            }

            private void popTypeContext() {
                if (!typeContextStack.isEmpty()) {
                    typeContextStack.pop();
                }
            }

            /**
             * その型の、this(...)委譲していないコンストラクタ一覧を集計する。
             * インスタンスフィールド初期化子・インスタンス初期化ブロックは、
             * コンパイル後これら全部の先頭（super(...)の直後）に複製される。
             * this(...)委譲するコンストラクタには複製されない
             * （委譲先で二重に初期化されるのを防ぐルールのため）。
             *
             * 明示コンストラクタが1つも無ければ、暗黙のデフォルトコンストラクタが
             * 1つ存在する。匿名クラスは明示コンストラクタを書けない言語仕様のため、
             * 常にこちらに倒れる（曖昧さは生じない）。
             */
            private TypeContext buildTypeContext(ITypeBinding tb, List<?> bodyDeclarations, int declLine) {
                List<String[]> roots = new ArrayList<>();
                boolean anyConstructor = false;
                for (Object o : bodyDeclarations) {
                    if (!(o instanceof MethodDeclaration)) {
                        continue;
                    }
                    MethodDeclaration md = (MethodDeclaration) o;
                    if (!md.isConstructor()) {
                        continue;
                    }
                    anyConstructor = true;
                    if (delegatesToThis(md)) {
                        continue;
                    }
                    String[] ref = toRef(md.resolveBinding());
                    if (ref != null) {
                        roots.add(ref);
                    }
                }
                if (!anyConstructor) {
                    // 明示コンストラクタが無い型には、暗黙のデフォルトコンストラクタが
                    // 1つ存在する。ソース上に宣言が無いのでここで合成しておく。
                    // new B() のような生成はこの <init> を呼ぶため、宣言を作っておかないと
                    // 「ソースなし（展開不可）」の未知メソッド扱いになってしまう
                    String[] implicitRef = implicitConstructorRef(tb);
                    if (implicitRef != null) {
                        roots.add(implicitRef);
                        out.declarations.add(new MethodDecl(implicitRef[0], implicitRef[1],
                                implicitRef[2], implicitRef[3], declLine, true));
                    }
                }
                return new TypeContext(tb, roots, declLine);
            }

            /** コンストラクタ本体の先頭文が this(...) か（=他のコンストラクタへの委譲か） */
            private boolean delegatesToThis(MethodDeclaration md) {
                Block body = md.getBody();
                if (body == null || body.statements().isEmpty()) {
                    return false;
                }
                return body.statements().get(0) instanceof ConstructorInvocation;
            }

            /** 明示コンストラクタが無い型の、暗黙のデフォルトコンストラクタの参照を合成する */
            private String[] implicitConstructorRef(ITypeBinding typeBinding) {
                if (typeBinding == null) {
                    return null;
                }
                ITypeBinding erased = typeBinding.getErasure();
                if (erased == null) {
                    erased = typeBinding;
                }
                String typeFqn = typeNameOf(erased);
                if (typeFqn == null) {
                    return null;
                }
                String pkg = (erased.getPackage() != null) ? erased.getPackage().getName() : "";
                return new String[]{pkg, typeFqn, "<init>", ""};
            }

            /**
             * 現在の型の {@code <clinit>}（静的初期化子）への参照を1件だけ含むリスト。
             * この型で初めて使う場合は、methods.csv 等に載るようD行も合成する。
             */
            private List<String[]> clinitContext() {
                TypeContext ctx = typeContextStack.peek();
                if (ctx == null || ctx.binding == null) {
                    return UNKNOWN;
                }
                ITypeBinding erased = ctx.binding.getErasure();
                if (erased == null) {
                    erased = ctx.binding;
                }
                String typeFqn = typeNameOf(erased);
                if (typeFqn == null) {
                    return UNKNOWN;
                }
                String pkg = (erased.getPackage() != null) ? erased.getPackage().getName() : "";
                if (!ctx.clinitDeclared) {
                    ctx.clinitDeclared = true;
                    out.declarations.add(new MethodDecl(pkg, typeFqn, "<clinit>", "", ctx.declLine, true));
                }
                return java.util.Collections.singletonList(new String[]{pkg, typeFqn, "<clinit>", ""});
            }

            /** 現在の型の、this(...)委譲していないコンストラクタ一覧（インスタンス初期化子用） */
            private List<String[]> instanceInitContext() {
                TypeContext ctx = typeContextStack.peek();
                if (ctx == null || ctx.rootConstructors.isEmpty()) {
                    return UNKNOWN;
                }
                return ctx.rootConstructors;
            }

            @Override
            public boolean visit(FieldDeclaration node) {
                methodStack.push(isStaticField(node) ? clinitContext() : instanceInitContext());
                enterScope(scanOrigins(node, new HashMap<String, String>()));
                return true;
            }

            @Override
            public void endVisit(FieldDeclaration node) {
                if (!methodStack.isEmpty()) {
                    methodStack.pop();
                }
                leaveScope();
            }

            @Override
            public boolean visit(Initializer node) {
                boolean isStatic = Modifier.isStatic(node.getModifiers());
                methodStack.push(isStatic ? clinitContext() : instanceInitContext());
                enterScope(scanOrigins(node.getBody(), new HashMap<String, String>()));
                return true;
            }

            @Override
            public void endVisit(Initializer node) {
                if (!methodStack.isEmpty()) {
                    methodStack.pop();
                }
                leaveScope();
            }

            /**
             * フィールドがstaticかどうか。構文上のキーワードでなくバインディングを見るのは、
             * インターフェースのフィールドが暗黙にstaticになる（キーワードが無くても）
             * ケースを正しく扱うため。
             */
            private boolean isStaticField(FieldDeclaration node) {
                List<?> fragments = node.fragments();
                if (!fragments.isEmpty() && fragments.get(0) instanceof VariableDeclarationFragment) {
                    IVariableBinding vb = ((VariableDeclarationFragment) fragments.get(0)).resolveBinding();
                    if (vb != null) {
                        return Modifier.isStatic(vb.getModifiers());
                    }
                }
                return Modifier.isStatic(node.getModifiers());
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
                    methodStack.push(java.util.Collections.singletonList(r));
                } else {
                    methodStack.push(UNKNOWN);
                }
                enterScope(scanOrigins(node.getBody(), paramScopeOf(node)));
                // 匿名クラスのメソッドはラムダ式の中に現れうる。その中の return は
                // ラムダではなくこのメソッドの return なので、深さを一旦0に戻す
                lambdaDepthStack.push(Integer.valueOf(lambdaDepth));
                lambdaDepth = 0;
                return true;
            }

            @Override
            public void endVisit(MethodDeclaration node) {
                // visit で必ず push しているため、ここで必ず pop して対応を保つ。
                // （JDTは visit が false を返した場合も endVisit を呼ぶ）
                if (!methodStack.isEmpty()) {
                    methodStack.pop();
                }
                leaveScope();
                if (!lambdaDepthStack.isEmpty()) {
                    lambdaDepth = lambdaDepthStack.pop().intValue();
                }
            }

            @Override
            public boolean visit(LambdaExpression node) {
                lambdaDepth++;
                return true;
            }

            @Override
            public void endVisit(LambdaExpression node) {
                if (lambdaDepth > 0) {
                    lambdaDepth--;
                }
            }

            /**
             * このメソッドの return が返しうる値の出所を記録する（R行）。
             *
             * ファクトリメソッド（{@code Factory.create()}）の戻り値に対する呼び出しを
             * 具象クラスまで辿るために使う。追跡できない return も U として
             * 記録するのが重要で、そうしないと「実は複数の型を返しうるメソッド」を
             * 分かった分だけで1つに決め打ちしてしまう。
             */
            @Override
            public boolean visit(ReturnStatement node) {
                Expression ex = node.getExpression();
                if (ex == null || lambdaDepth > 0) {
                    // void の return、またはラムダ式自身の戻り値
                    return true;
                }
                ITypeBinding tb = ex.resolveTypeBinding();
                if (tb != null && (tb.isPrimitive() || tb.isArray()
                        || "java.lang.String".equals(tb.getQualifiedName()))) {
                    // 具象クラスの絞り込みに使えない戻り値。記録しても嵩むだけ
                    return true;
                }
                List<String[]> callers = current();
                if (callers == null) {
                    return true;
                }
                String origin = originOf(ex);
                if (origin == null) {
                    origin = Origin.UNKNOWN_S;
                }
                for (String[] c : callers) {
                    out.returns.add(new ReturnRec(c[0], c[1], c[2], c[3], origin));
                }
                return true;
            }

            /**
             * 現在の呼び出し元一覧。特定できない場合は null。
             * 通常は要素1件だが、インスタンス初期化子の中では複数件になりうる
             * （{@link #computeRootConstructors} 参照）。
             */
            private List<String[]> current() {
                List<String[]> top = methodStack.peek();
                return (top == null || top.isEmpty()) ? null : top;
            }

            @Override
            public boolean visit(MethodInvocation n) {
                IMethodBinding b = n.resolveMethodBinding();
                record(b, n, n.getName().getIdentifier(), bindKindOf(b), recvKeyOf(n),
                        recvKindOf(n.getExpression()), externalGuessRef(n),
                        originOf(n.getExpression()), argOriginsOf(n.arguments()));

                // フェーズAの拡張に、この呼び出し箇所を見せる。
                // 呼び出し元が複数（インスタンス初期化子等）ある場合は、その全員に対して
                // 見せる。一部にしか見せないと、その呼び出し元経由の解決だけ証拠を
                // 見つけられなくなるため。CallSiteHintCollector のインターフェースは
                // 呼び出し元1件を前提にしているため、呼び出し元ごとに1回ずつ呼ぶ。
                List<String[]> callers = current();
                if (callers != null && !collectors.isEmpty()) {
                    for (String[] c : callers) {
                        final String callerKey = c[1] + "#" + c[2] + "(" + c[3] + ")";
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
                }
                return true;
            }

            @Override
            public boolean visit(SuperMethodInvocation n) {
                // super.m() は静的束縛（オーバーライドの影響を受けない）
                record(n.resolveMethodBinding(), n, n.getName().getIdentifier(), 'U', "",
                        RecvKind.THIS, null, null, argOriginsOf(n.arguments()));
                return true;
            }

            @Override
            public boolean visit(ClassInstanceCreation n) {
                record(n.resolveConstructorBinding(), n, "<init>", 'C', "", RecvKind.TYPE, null,
                        null, argOriginsOf(n.arguments()));
                return true;
            }

            @Override
            public boolean visit(ConstructorInvocation n) {
                record(n.resolveConstructorBinding(), n, "<init>", 'C', "", RecvKind.TYPE, null,
                        null, argOriginsOf(n.arguments()));
                return true;
            }

            /**
             * バインディング解決が完全に失敗した場合の最後の手段。
             * レシーバの単純名が、このファイルの単一型インポート（{@code import a.b.C;}）と
             * 一致すれば、そのFQNを型として採用する。あくまでソース上のテキストからの
             * 推定であり、JDTによる検証済みの型解決ではない
             * （メンバの実在・オーバーロードの妥当性までは確認できない）。
             * ワイルドカードimport・static import・型不明のレシーバでは使わない。
             */
            private String[] externalGuessRef(MethodInvocation n) {
                Expression recv = n.getExpression();
                if (!(recv instanceof SimpleName)) {
                    return null;
                }
                String simple = ((SimpleName) recv).getIdentifier();
                String fqn = null;
                for (Object o : cu.imports()) {
                    ImportDeclaration imp = (ImportDeclaration) o;
                    if (imp.isOnDemand() || imp.isStatic()) {
                        continue;
                    }
                    String name = imp.getName().getFullyQualifiedName();
                    if (name.equals(simple) || name.endsWith("." + simple)) {
                        fqn = name;
                        break;
                    }
                }
                if (fqn == null) {
                    return null;
                }
                int dot = fqn.lastIndexOf('.');
                String pkg = (dot >= 0) ? fqn.substring(0, dot) : "";
                return new String[]{pkg, fqn, n.getName().getIdentifier(), ""};
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

            /**
             * @param externalGuess バインディング解決が失敗した場合の代替の呼び出し先。
             *                      null なら従来通りunresolved-calls.csvに記録する。
             *                      非null なら「外部ライブラリ（import推定・未検証）」の
             *                      注記付きでcall-hierarchy.csv側へ記録する
             *                      （{@link #externalGuessRef} 参照）
             */
            private void record(IMethodBinding binding, ASTNode node, String displayName,
                                 char bindKind, String recvKey, char recvKind,
                                 String[] externalGuess, String recvOrigin, String argOrigins) {
                int line = cu.getLineNumber(node.getStartPosition());
                List<String[]> callers = current();
                if (callers == null) {
                    // 呼び出し元の型・コンストラクタ自体を特定できないケース
                    // （型のバインディング解決に失敗した等）。未解決として記録する
                    out.unresolved.add(new UnresolvedCall(line,
                            "(メソッド外)", displayName, "メソッド本体の外からの呼び出し"));
                    return;
                }
                String[] callee = toRef(binding);
                if (callee == null) {
                    if (externalGuess != null) {
                        // クラスパス不足で消えるより、未検証と分かる形で残す方針。
                        // bindKind='G' は resolveEdge() 側で「候補は常にこの1件」として
                        // 扱われ、CHA展開の対象にはしない（型階層情報を持たないため）
                        for (String[] caller : callers) {
                            out.edges.add(new CallEdgeRec(caller[0], caller[1], caller[2], caller[3],
                                    externalGuess[0], externalGuess[1], externalGuess[2],
                                    externalGuess[3], line, 'G', recvKey, recvKind,
                                    recvOrigin, argOrigins));
                        }
                        return;
                    }
                    // 呼び出し先の型解決に失敗したケース。呼び出し元が複数あっても
                    // 原因は呼び出し先側なので、1件だけ記録すれば足りる
                    String[] caller = callers.get(0);
                    out.unresolved.add(new UnresolvedCall(line,
                            caller[1] + "#" + caller[2] + "(" + caller[3] + ")", displayName,
                            "型解決に失敗（クラスパス不足・動的呼び出し等の可能性）"));
                    return;
                }
                // 呼び出し元が複数（インスタンス初期化子等）でも全件をエッジにする。
                // 実際にコンパイル後それぞれから1回ずつ呼ばれるため、これは近似ではない
                for (String[] caller : callers) {
                    out.edges.add(new CallEdgeRec(caller[0], caller[1], caller[2], caller[3],
                            callee[0], callee[1], callee[2], callee[3], line, bindKind,
                            recvKey, recvKind, recvOrigin, argOrigins));
                }
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

        /** 引数型略名が衝突しているラベル。初回の displayLabel() で一度だけ作る */
        private Set<String> ambiguous;

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

        /**
         * キーのうち括弧内（完全修飾の引数型をカンマ区切りにしたもの）。
         *
         * 開き括弧は "#" より後ろから探す。型名に括弧が混ざる可能性があるのは
         * typeNameOf() が最終手段でJDT内部キーを使った場合だけだが、そこで
         * 引数リストの切り出しがずれると別メソッドと同一視されてしまう。
         */
        private String rawParams(int id) {
            String k = keys.get(id);
            return k.substring(k.indexOf('(', k.indexOf('#')) + 1, k.lastIndexOf(')'));
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

        /**
         * 表示用のメソッド名。
         *
         * コンストラクタは内部的には <init> だが、ソース上の名前はクラスの単純名。
         * 利用者が読むのはソースなので、表示は単純名に寄せる。
         * 暗黙のデフォルトコンストラクタも、補完されるとクラス名になるので同じ扱い。
         */
        String displayMethodName(int id) {
            String m = methodName(id);
            if (!"<init>".equals(m)) {
                return m;
            }
            String simple = simpleTypeName(id);
            int dot = simple.lastIndexOf('.');
            return (dot >= 0) ? simple.substring(dot + 1) : simple;
        }

        String shortLabel(int id) {
            return simpleTypeName(id) + "." + displayMethodName(id);
        }

        /**
         * 単純クラス名 + 表示用メソッド名 + 引数型略名。オーバーロードを識別できる短い表記。
         * 略名が衝突している場合は callee 列と同じ理由で完全修飾の引数に戻す。
         */
        String shortLabelWithParams(int id) {
            String params = ambiguousLabels().contains(plainDisplayLabel(id))
                    ? rawParams(id) : shortParams(id);
            return simpleTypeName(id) + "." + displayMethodName(id) + "(" + params + ")";
        }

        /** 完全修飾クラス名 + 表示用メソッド名 + 完全修飾の引数リスト */
        String fullSignature(int id) {
            return typeFqn(id) + "." + displayMethodName(id) + "(" + rawParams(id) + ")";
        }

        /**
         * call-hierarchy.csv の callee 列の表記。
         * 完全修飾クラス名 + 表示用メソッド名 + 引数型略名。
         *
         * 引数型を略名にするのは読みやすさのためだが、略した結果
         * java.util.List と other.List のように別物が同じ表記になることがある。
         * 「識別できる表記にする」のが目的の列でそれが起きては本末転倒なので、
         * 衝突した組だけ完全修飾の引数リストに戻す（下の ambiguousLabels()）。
         */
        String displayLabel(int id) {
            String label = plainDisplayLabel(id);
            return ambiguousLabels().contains(label) ? fullSignature(id) : label;
        }

        private String plainDisplayLabel(int id) {
            return typeFqn(id) + "." + displayMethodName(id) + "(" + shortParams(id) + ")";
        }

        /**
         * 引数型略名が衝突しているラベルの集合。
         *
         * 一度だけ全メソッドを走査して作る。走査用のSetは作業後に捨て、
         * 残すのは衝突したラベルだけ（通常は0件）なので、常時のメモリは増えない。
         * キーは重複しないので、同じラベルが2回出た時点で必ず引数が違う。
         */
        private Set<String> ambiguousLabels() {
            if (ambiguous != null) {
                return ambiguous;
            }
            Set<String> seen = new HashSet<>(keys.size() * 2);
            Set<String> dup = new LinkedHashSet<>();
            for (int id = 0; id < keys.size(); id++) {
                String label = plainDisplayLabel(id);
                if (!seen.add(label)) {
                    dup.add(label);
                }
            }
            ambiguous = dup;
            return ambiguous;
        }

        /** 完全修飾の引数リストを、型ごとに略名へ置き換えたもの */
        private String shortParams(int id) {
            String raw = rawParams(id);
            if (raw.isEmpty()) {
                return "";
            }
            // 引数型は toRef() で消去済み（getErasure）なので、ジェネリクスの
            // 山括弧が入ることはない。よってカンマで素直に分割できる
            StringBuilder sb = new StringBuilder(raw.length());
            int start = 0;
            while (start <= raw.length()) {
                int comma = raw.indexOf(',', start);
                int end = (comma < 0) ? raw.length() : comma;
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(simpleParamName(raw.substring(start, end)));
                if (comma < 0) {
                    break;
                }
                start = comma + 1;
            }
            return sb.toString();
        }

        /**
         * 引数型1つぶんの略名。java.lang.String → String、java.lang.String[] → String[]。
         *
         * 内部クラス（fn.Outer.Inner）は末尾だけを取って Inner になる。名前だけでは
         * どこまでがパッケージでどこからが外側クラスか決められないため（大文字小文字の
         * 慣習に頼ると、その慣習に従っていないコードで誤る）。
         * これで別物が同じ表記になった場合は displayLabel() が完全修飾に戻す。
         */
        private static String simpleParamName(String fq) {
            int arr = fq.indexOf('[');
            String base = (arr < 0) ? fq : fq.substring(0, arr);
            String suffix = (arr < 0) ? "" : fq.substring(arr);
            int dot = base.lastIndexOf('.');
            return ((dot >= 0) ? base.substring(dot + 1) : base) + suffix;
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
        byte[] recvKinds;   // 長さ = エッジ数。レシーバの由来（RecvKind）

        /**
         * エッジごとのレシーバ・実引数の出所（{@link Origin}）。
         * 値は originPool のインデックスで、-1 なら情報なし。
         *
         * 文字列の配列をエッジ数ぶん持つとメモリ設計が崩れるため、
         * 実体は共有プールに1つずつだけ置き、エッジ側は int で参照する
         * （出所の文字列は "A:0" や型名なので、実際には激しく重複する）。
         */
        int[] recvOriginIds;
        int[] argOriginIds;
        private final ArrayList<String> originPool = new ArrayList<>();
        private final HashMap<String, Integer> originPoolIndex = new HashMap<>();

        /** メソッドIDごとの「返しうる値の出所」。null は情報なし */
        private String[][] returnOrigins;
        /** "typeFqn#fieldName" -> 出所。コンストラクタ注入されたフィールドだけが入る */
        private final HashMap<String, String> fieldOrigins = new HashMap<>();

        /** 型階層: 親型 -> 直接の子型 */
        private final HashMap<String, List<String>> directSubtypes = new HashMap<>();
        /** 型階層: 子型 -> 直接の親型。具象型からメソッド実装を探すのに使う */
        private final HashMap<String, List<String>> directSupertypes = new HashMap<>();
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
            HashMap<Integer, List<String>> returnsById = new HashMap<>();
            long edgeCount = 0;

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
                                    List<String> sups = g.directSupertypes.get(f[1]);
                                    if (sups == null) {
                                        sups = new ArrayList<>();
                                        g.directSupertypes.put(f[1], sups);
                                    }
                                    if (!sups.contains(sup)) {
                                        sups.add(sup);
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
                    } else if (t == 'J') {
                        String[] f = line.split(CacheFormat.SEP, -1);
                        if (f.length >= 4) {
                            g.fieldOrigins.put(f[1] + "#" + f[2], f[3]);
                        }
                    } else if (t == 'R') {
                        String[] f = line.split(CacheFormat.SEP, -1);
                        if (f.length >= 6) {
                            int id = g.methods.intern(f[1], f[2], f[3], f[4]);
                            ensure(outDegree, id);
                            List<String> os = returnsById.get(Integer.valueOf(id));
                            if (os == null) {
                                os = new ArrayList<>(2);
                                returnsById.put(Integer.valueOf(id), os);
                            }
                            if (!os.contains(f[5])) {
                                os.add(f[5]);
                            }
                        }
                    }
                }
            } finally {
                in.close();
            }
            log("収集: 型 " + g.typeKind.size()
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
            g.recvKinds = new byte[(int) edgeCount];
            g.edgeHint = new int[(int) edgeCount];
            Arrays.fill(g.edgeHint, -1);
            g.recvOriginIds = new int[(int) edgeCount];
            Arrays.fill(g.recvOriginIds, -1);
            g.argOriginIds = new int[(int) edgeCount];
            Arrays.fill(g.argOriginIds, -1);

            // R行（戻り値の出所）をメソッドIDの配列に移す。
            // 1回目のスキャンで全メソッドがID化されているのでここで確定できる
            g.returnOrigins = new String[n][];
            for (Map.Entry<Integer, List<String>> e : returnsById.entrySet()) {
                int id = e.getKey().intValue();
                if (id < n) {
                    g.returnOrigins[id] = e.getValue().toArray(new String[0]);
                }
            }

            // --- 2回目: エッジを流し込む ---
            int[] cursor = Arrays.copyOf(g.offsets, n == 0 ? 0 : n);

            in = open(cacheFile);
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.isEmpty()) {
                        continue;
                    }
                    if (line.charAt(0) == 'F') {
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
                    g.recvKinds[pos] = (byte) ((f.length >= 13 && !f[12].isEmpty())
                            ? f[12].charAt(0) : RecvKind.OTHER);
                    String recvKey = (f.length >= 12) ? f[11] : "";
                    if (!recvKey.isEmpty()) {
                        String callerKey = f[2] + "#" + f[3] + "(" + f[4] + ")";
                        List<Hint> hs = g.hintsByScope.get(callerKey + "|" + recvKey);
                        if (hs != null && !hs.isEmpty()) {
                            g.hintTable.add(hs);
                            g.edgeHint[pos] = g.hintTable.size() - 1;
                        }
                    }
                    g.recvOriginIds[pos] = g.internOrigin((f.length >= 14) ? f[13] : "");
                    g.argOriginIds[pos] = g.internOrigin((f.length >= 15) ? f[14] : "");
                }
            } finally {
                in.close();
            }
            return g;
        }

        // ================================================================
        // データフロー（Issue #17 ファクトリの戻り値 / #18 引数）
        // ================================================================

        /** 出所の文字列を共有プールに入れてインデックスを返す。空なら -1 */
        int internOrigin(String origin) {
            if (origin == null || origin.isEmpty()) {
                return -1;
            }
            Integer i = originPoolIndex.get(origin);
            if (i != null) {
                return i.intValue();
            }
            int id = originPool.size();
            originPool.add(origin);
            originPoolIndex.put(origin, Integer.valueOf(id));
            return id;
        }

        /** エッジのレシーバの出所。無ければ null */
        String recvOrigin(int edgeIndex) {
            int i = recvOriginIds[edgeIndex];
            return (i < 0) ? null : originPool.get(i);
        }

        /** エッジの実引数の出所（"位置=出所;..."）。無ければ null */
        String argOrigins(int edgeIndex) {
            int i = argOriginIds[edgeIndex];
            return (i < 0) ? null : originPool.get(i);
        }

        private boolean dataflowEnabled;
        private int dataflowMaxDepth = 5;

        void setDataflow(boolean enabled, int maxDepth) {
            this.dataflowEnabled = enabled;
            this.dataflowMaxDepth = (maxDepth > 0) ? maxDepth : 1;
        }

        boolean dataflowEnabled() {
            return dataflowEnabled;
        }

        /** factoryReturnOrigin のメモ。未計算と「計算したが不明」を区別する */
        private String[] factoryOrigin;
        private byte[] factoryOriginState;   // 0=未計算 / 1=計算中 / 2=計算済み

        /**
         * そのメソッドが必ず返す値の出所。特定できなければ null。
         *
         * 「1つでも追跡できない return があれば null」「複数の出所を返すなら null」。
         * 委譲（{@code return create();}）は dataflowMaxDepth まで辿って畳む。
         *
         * 返すのは具象型（{@code T:}）とは限らない。クラス名の文字列を受け取る
         * ファクトリは {@code C:引数位置}、引数をそのまま返すメソッドは {@code A:引数位置}
         * になる。これらは**そのファクトリを呼んでいる箇所の実引数**を見て初めて
         * 確定するので、ここではそのまま返して呼び出し側で解決する。
         */
        String factoryReturnOrigin(int methodId) {
            if (returnOrigins == null || methodId < 0 || methodId >= returnOrigins.length) {
                return null;
            }
            if (factoryOrigin == null) {
                factoryOrigin = new String[returnOrigins.length];
                factoryOriginState = new byte[returnOrigins.length];
            }
            return factoryReturnOrigin(methodId, 0);
        }

        private String factoryReturnOrigin(int methodId, int depth) {
            if (factoryOriginState[methodId] == 2) {
                return factoryOrigin[methodId];
            }
            if (factoryOriginState[methodId] == 1) {
                return null;   // 委譲が循環している
            }
            String[] origins = returnOrigins[methodId];
            if (origins == null || origins.length == 0) {
                return null;
            }
            factoryOriginState[methodId] = 1;
            String found = null;
            for (String o : origins) {
                String reduced = reduceReturnOrigin(o, depth);
                // 1つでも畳めない return があれば、このメソッドの戻り値は決められない。
                // 分かった分だけで決め打ちすると、別の型を返す経路を取りこぼす
                if (reduced == null || (found != null && !found.equals(reduced))) {
                    factoryOriginState[methodId] = 2;
                    factoryOrigin[methodId] = null;
                    return null;
                }
                found = reduced;
            }
            factoryOriginState[methodId] = 2;
            factoryOrigin[methodId] = found;
            return found;
        }

        /** return 1件の出所を、具象型か「呼び出し箇所依存の形」まで畳む */
        private String reduceReturnOrigin(String origin, int depth) {
            char kind = Origin.kindOf(origin);
            if (kind == Origin.NEW || kind == Origin.REFLECT || kind == Origin.PARAM) {
                return Origin.head(origin);
            }
            if (kind != Origin.RETURN || depth >= dataflowMaxDepth) {
                return null;
            }
            // 別のファクトリへの委譲。委譲先の出所を、この return が書いている
            // 実引数で解決する（return create("jp.co.X"); のような形を畳むため）
            int delegate = methods.idOf(Origin.valueOf(origin));
            if (delegate < 0) {
                return null;
            }
            String inner = factoryReturnOrigin(delegate, depth + 1);
            if (Origin.kindOf(inner) == Origin.NEW) {
                return inner;
            }
            String fqn = applyInvocationArgs(inner, Origin.argsOf(origin), null);
            return (fqn == null) ? null : Origin.of(Origin.NEW, fqn);
        }

        /**
         * 呼び出し箇所依存の戻り値の出所（C/A）を、その呼び出しの実引数で解決する。
         *
         * @param args 呼び出し箇所の実引数の出所（"0=L:jp.co.X" など）
         * @param ctx  実引数がさらに外側に依存する場合に使う文脈。無ければ null
         */
        private String applyInvocationArgs(String returnOrigin, String args, DataflowContext ctx) {
            char kind = Origin.kindOf(returnOrigin);
            if (kind == Origin.NEW) {
                return Origin.valueOf(returnOrigin);
            }
            int index = parseIndex(Origin.valueOf(returnOrigin));
            if (index < 0) {
                return null;
            }
            String arg = Origin.argAt(args, index);
            if (kind == Origin.REFLECT) {
                // Class.forName(引数) 形式。実引数がクラス名の文字列なら型が決まる
                if (Origin.kindOf(arg) != Origin.LITERAL) {
                    return null;
                }
                String fqn = Origin.valueOf(arg);
                // 解析対象に存在しない型名は使わない（文字列の見た目だけで決めない）
                return typeKind.containsKey(fqn) ? fqn : null;
            }
            if (kind == Origin.PARAM) {
                return concreteTypeOf(arg, ctx);
            }
            return null;
        }

        /**
         * 具象型 typeFqn で、シグネチャ sig の実装を持つメソッドIDを返す。無ければ -1。
         *
         * その型自身に宣言が無くても、親クラスから継承していれば親の実装が動く。
         * 親を辿らないと「ファクトリが UserDaoImpl を返すと分かったのに、
         * selectById は AbstractDao で宣言されているので見つからない」となる。
         */
        int implementationIn(String typeFqn, String sig) {
            if (typeFqn == null || typeFqn.isEmpty()) {
                return -1;
            }
            ArrayDeque<String> queue = new ArrayDeque<>();
            Set<String> seen = new HashSet<>();
            queue.add(typeFqn);
            seen.add(typeFqn);
            while (!queue.isEmpty()) {
                String t = queue.poll();
                int id = methods.idOf(t + "#" + sig);
                if (id >= 0 && methods.hasBody(id)) {
                    return id;
                }
                List<String> sups = directSupertypes.get(t);
                if (sups == null) {
                    continue;
                }
                for (String sup : sups) {
                    if (seen.add(sup)) {
                        queue.add(sup);
                    }
                }
            }
            return -1;
        }

        /**
         * 経路から確定している情報。どれも「1本の経路に対して」の値であり、
         * 別の経路では別の値になる。
         */
        static final class DataflowContext {
            /** 囲みメソッドの引数の具象型 */
            final String[] paramTypes;
            /** レシーバのオブジェクトの、コンストラクタ実引数の具象型 */
            final String[] ctorArgs;
            /** ctorArgs が属する型。取り違え防止のため必ず突き合わせる */
            final String ctorOwner;

            DataflowContext(String[] paramTypes, String[] ctorArgs, String ctorOwner) {
                this.paramTypes = paramTypes;
                this.ctorArgs = ctorArgs;
                this.ctorOwner = ctorOwner;
            }

            static DataflowContext of(String[] paramTypes, String[] ctorArgs, String ctorOwner) {
                return (paramTypes == null && ctorArgs == null)
                        ? null : new DataflowContext(paramTypes, ctorArgs, ctorOwner);
            }
        }

        /** 出所から具象型を求める。経路に依存する部分は ctx から取る */
        String concreteTypeOf(String origin, DataflowContext ctx) {
            char kind = Origin.kindOf(origin);
            if (kind == Origin.NEW) {
                return Origin.valueOf(origin);
            }
            if (kind == Origin.RETURN) {
                int factory = methods.idOf(Origin.valueOf(origin));
                if (factory < 0) {
                    return null;
                }
                return applyInvocationArgs(factoryReturnOrigin(factory),
                        Origin.argsOf(origin), ctx);
            }
            if (kind == Origin.PARAM && ctx != null && ctx.paramTypes != null) {
                int idx = parseIndex(Origin.valueOf(origin));
                if (idx >= 0 && idx < ctx.paramTypes.length) {
                    return ctx.paramTypes[idx];
                }
                return null;
            }
            if (kind == Origin.FIELD) {
                return fieldTypeOf(Origin.valueOf(origin), ctx);
            }
            return null;
        }

        /**
         * コンストラクタ注入されたフィールドの具象型。
         *
         * J行が「このフィールドには必ずコンストラクタのn番目の引数が入る」と
         * 言っているので、経路上で分かっているコンストラクタの実引数と突き合わせる。
         * 引数ではなく初期化子の new で決まっているフィールドは、経路を見ずに決まる。
         */
        private String fieldTypeOf(String fieldKey, DataflowContext ctx) {
            String origin = fieldOrigins.get(fieldKey);
            if (origin == null) {
                return null;
            }
            if (Origin.kindOf(origin) == Origin.NEW) {
                return Origin.valueOf(origin);
            }
            if (Origin.kindOf(origin) != Origin.PARAM || ctx == null || ctx.ctorArgs == null) {
                return null;
            }
            // このコンストラクタ実引数が、本当にこのフィールドを持つ型のものか。
            // 親クラスのフィールドにサブクラスのコンストラクタ実引数を当てないため
            String owner = fieldKey.substring(0, fieldKey.indexOf('#'));
            if (!owner.equals(ctx.ctorOwner)) {
                return null;
            }
            int idx = parseIndex(Origin.valueOf(origin));
            return (idx >= 0 && idx < ctx.ctorArgs.length) ? ctx.ctorArgs[idx] : null;
        }

        /** その型がコンストラクタ注入されたフィールドを持つか */
        boolean hasInjectedFields(String typeFqn) {
            if (typeFqn == null || fieldOrigins.isEmpty()) {
                return false;
            }
            if (typesWithInjectedFields == null) {
                typesWithInjectedFields = new HashSet<>();
                for (String key : fieldOrigins.keySet()) {
                    typesWithInjectedFields.add(key.substring(0, key.indexOf('#')));
                }
            }
            return typesWithInjectedFields.contains(typeFqn);
        }

        private Set<String> typesWithInjectedFields;

        /**
         * そのメソッドに経路の情報を渡す意味があるか。
         *
         * 意味が無いメソッドには渡さないことで、データフロー解析を
         * 「必要な場合のみ」に絞る。次のいずれかなら意味がある。
         *   ・引数をレシーバとして使う、または引数をそのまま次へ渡す
         *   ・コンストラクタ注入されたフィールドを持つ型のメソッド
         *     （自分のメソッドを呼び合った先でフィールドを使うことがある）
         */
        boolean usesContext(int methodId) {
            if (!dataflowEnabled || methodId < 0) {
                return false;
            }
            if (usesParameters == null) {
                usesParameters = computeUsesParameters();
            }
            if (methodId < usesParameters.length && usesParameters[methodId]) {
                return true;
            }
            return hasInjectedFields(methods.typeFqn(methodId));
        }

        private boolean[] usesParameters;

        private boolean[] computeUsesParameters() {
            boolean[] flags = new boolean[methods.size()];
            for (int caller = 0; caller < flags.length; caller++) {
                for (int e = edgeStart(caller); e < edgeEnd(caller); e++) {
                    if (Origin.kindOf(recvOrigin(e)) == Origin.PARAM
                            || mentionsParam(argOrigins(e))) {
                        flags[caller] = true;
                        break;
                    }
                }
            }
            return flags;
        }

        /** 実引数の出所の中に「囲みメソッドの引数」が含まれるか（引数の受け渡し） */
        private static boolean mentionsParam(String argOrigins) {
            if (argOrigins == null) {
                return false;
            }
            // "0=A:1;2=T:jp.co.X" のような形。"=A:" があれば引数を渡している
            return argOrigins.indexOf("=" + Origin.PARAM + ":") >= 0;
        }

        private static int parseIndex(String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return -1;
            }
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

            // --- importからの推定（未検証の外部ライブラリ呼び出し） ---
            // 型階層情報を一切持たない合成メソッドのため、CHA拡張の対象にはしない
            if (bindKind == 'G') {
                return new Resolution(new int[]{calleeId}, "EXTERNAL_GUESS");
            }

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

            // --- 段4: ファクトリメソッドの戻り値（Issue #17） ---
            // レシーバがメソッドの戻り値なら、その宣言の return を見て具象型を決める。
            // 呼び出し箇所の実引数までは出所に含まれているので、クラス名の文字列を
            // 受け取るファクトリもここで決まる。経路に依存しないのでメモ化できる。
            // （引数・フィールド由来は経路依存なので StreamingTreeWalker 側で解決する）
            if (dataflowEnabled) {
                String recv = recvOrigin(edgeIndex);
                int resolved = dataflowTarget(recv, calleeId, null);
                if (resolved >= 0) {
                    return new Resolution(new int[]{resolved}, dataflowLabel(recv));
                }
            }

            // --- 段5: CHA ---
            return base;
        }

        /**
         * どの材料で具象クラスを決めたかを表すラベル。
         *
         * 何を根拠に絞ったかで、利用者が結果をどれだけ信用してよいかが変わるため、
         * 出所の種別ごとに分ける（注記に「解決:ラベル」として出る）。
         */
        static String dataflowLabel(String recvOrigin) {
            switch (Origin.kindOf(recvOrigin)) {
                case Origin.FIELD:  return "DATAFLOW_FIELD";
                case Origin.PARAM:  return "DATAFLOW_PARAM";
                case Origin.NEW:    return "DATAFLOW_NEW";
                default:            return "DATAFLOW_FACTORY";
            }
        }

        /**
         * レシーバの出所から具象クラスを決め、その実装のメソッドIDを返す。無ければ -1。
         *
         * @param ctx 経路から確定した引数・コンストラクタ実引数の具象型（無ければ null）
         */
        int dataflowTarget(String recvOrigin, int calleeId, DataflowContext ctx) {
            if (!dataflowEnabled || recvOrigin == null) {
                return -1;
            }
            String fqn = concreteTypeOf(recvOrigin, ctx);
            if (fqn == null) {
                return -1;
            }
            // 結果が宣言型のままでも、候補が1つに定まったこと自体が成果なので返す
            return implementationIn(fqn, methods.signature(calleeId));
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
                // 全体モード: 呼び出し元が無いメソッドを自動的に起点にする
                return autoEntryPoints();
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

    /** methods.csv の集計 */
    static final class InventoryStats {
        long methods;
        long entryCandidates;
        long isolated;
        long leaves;
        long unreachable;
        long constructors;
        long withUnresolved;

        @Override
        public String toString() {
            return "メソッド=" + methods
                    + " 起点候補=" + entryCandidates
                    + " 孤立=" + isolated
                    + " 末端=" + leaves
                    + " 未到達=" + unreachable
                    + " 未解決の呼び出しを含む=" + withUnresolved
                    + "（コンストラクタ " + constructors + " 個は出力対象外）";
        }
    }

    /**
     * methods.csv の出力。
     *
     * 呼び出し階層（call-hierarchy.csv）は起点からの経路を展開するため
     * 分岐^深さで膨らむが、こちらはメソッド数に比例した線形サイズで収まる。
     * 「どのメソッドが誰からも呼ばれていないか」「どこがハブか」を
     * 俯瞰したいときはこちらを見る。
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
         *   NORMAL          上記以外
         *
         * コンストラクタ（<init>）は出力しない。call-hierarchy.csv 側でも
         * 行にしていないため、両方の一覧で扱いを揃える。
         *
         * method 列は「単純クラス名.メソッド名(引数型略名)」。完全修飾クラス名は
         * declaringType 列にあるため重複させず、引数だけを足してオーバーロードを
         * 見分けられるようにしている（付けないと、行番号以外まったく同じ行が並ぶ）。
         *
         * unresolvedCalls / unresolvedCause は「このメソッドの中に、
         * 具象クラスを1つに絞れなかった呼び出しがいくつあり、その理由は何か」。
         * call-hierarchy.csv の note と同じ判定を使っているので、
         * まずここで穴のあるメソッドを絞ってから階層を追う、という使い方ができる。
         */
        static InventoryStats writeMethods(CallGraph g, Config config, int[] roots)
                throws IOException {
            InventoryStats st = new InventoryStats();
            int[] in = g.inDegrees();
            boolean[] reachable = g.reachableFrom(roots);

            BufferedWriter w = Csv.writer(config.methodsCsv, config.outputEncoding, config.outputBom);
            try {
                w.write(String.join(Csv.DELIM, "method", "declaringType", "typeKind",
                        "file", "line", "hasBody", "inDegree", "outDegree", "role", "reachable",
                        "unresolvedCalls", "unresolvedCause"));
                w.newLine();
                for (int id = 0; id < g.methodCount(); id++) {
                    // ソースが無いメソッド（jar内など）は一覧の対象外。
                    // 呼ばれている事実は call-hierarchy.csv 側に残る
                    if (g.methods.declFile(id) == null) {
                        continue;
                    }
                    // コンストラクタは call-hierarchy.csv でも行にしていないので揃える
                    if ("<init>".equals(g.methods.methodName(id))) {
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
                    Unresolved un = unresolvedOf(g, id);
                    if (un.count > 0) {
                        st.withUnresolved++;
                    }
                    w.write(String.join(Csv.DELIM,
                            Csv.esc(g.methods.shortLabelWithParams(id)),
                            Csv.esc(g.methods.typeFqn(id)),
                            String.valueOf(g.kindOf(g.methods.typeFqn(id))),
                            Csv.esc(g.methods.declFile(id)),
                            String.valueOf(g.methods.declLine(id)),
                            g.methods.hasBody(id) ? "1" : "0",
                            String.valueOf(in[id]),
                            String.valueOf(out),
                            role,
                            reachable[id] ? "1" : "0",
                            String.valueOf(un.count),
                            Csv.esc(un.cause)));
                    w.newLine();
                }
            } finally {
                w.close();
            }
            return st;
        }

        /** 1メソッド分の「絞れなかった呼び出し」の件数と理由 */
        private static final class Unresolved {
            int count;
            String cause = "";
        }

        /**
         * そのメソッドが出している呼び出しのうち、具象クラスを1つに絞れなかったものを数え、
         * レシーバの由来（RecvKind）で理由を並べる。
         *
         * 判定は call-hierarchy.csv の note と同じ resolveEdge を使う。
         * 結果はメモ化されるので、階層展開と二重に解決コストがかかることはない。
         */
        private static Unresolved unresolvedOf(CallGraph g, int id) {
            Unresolved u = new Unresolved();
            StringBuilder sb = new StringBuilder();
            int from = g.edgeStart(id);
            int to = g.edgeEnd(id);
            for (int e = from; e < to; e++) {
                CallGraph.Resolution res = g.resolveEdge(e, (char) g.bindKinds[e]);
                boolean multi = res.targets.length > 1;
                boolean noImpl = "NO_IMPL".equals(res.label);
                if (!multi && !noImpl) {
                    continue;
                }
                u.count++;
                String d = noImpl ? "実装なし（宣言のまま）"
                        : RecvKind.describe((char) g.recvKinds[e]);
                // 同じ理由は1回だけ並べる。件数はcount側で分かる
                if (sb.indexOf(d) < 0) {
                    if (sb.length() > 0) {
                        sb.append(';');
                    }
                    sb.append(d);
                }
            }
            u.cause = sb.toString();
            return u;
        }

    }

    /**
     * 型解決に失敗した呼び出しを call-hierarchy.csv に書き出す。
     *
     * これらは呼び出し先の型が特定できていないため、呼び出し階層としては辿れない。
     * しかし「解決できなかったせいで階層から抜け落ちている」こと自体が
     * 重要な情報（依存jarの不足を示す）なので、静かに消さずに行として残す。
     *
     * キャッシュのU行を読み直して出力する。件数ぶんをヒープに載せないための
     * ストリーミング処理。
     */
    static final class UnresolvedReport {

        static long write(CallGraph g, Config config, CallHierarchyCsvWriter out)
                throws IOException {
            if (!Files.isRegularFile(config.cacheFile)) {
                return 0L;
            }
            long rows = 0L;
            BufferedReader in = Files.newBufferedReader(config.cacheFile, StandardCharsets.UTF_8);
            try {
                in.readLine();   // バージョン行
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
                        continue;
                    }
                    if (t != 'U') {
                        continue;
                    }
                    String[] f = line.split(CacheFormat.SEP, -1);
                    if (f.length < 5) {
                        continue;
                    }
                    int callLine;
                    try {
                        callLine = Integer.parseInt(f[1]);
                    } catch (NumberFormatException ignore) {
                        callLine = -1;
                    }
                    // 呼び出し元メソッドのキーはD行と同じ形式なので、そのままIDを引ける。
                    // 引ければ caller 列をスタックトレース形式にでき、Eclipseから飛べる
                    int callerId = g.methods.idOf(f[2]);
                    String location = (currentFile == null)
                            ? f[2] : currentFile + ":" + callLine;
                    out.writeUnresolvedRow(g.methods, callerId, location,
                            callLine, f[3], f[4]);
                    rows++;
                }
            } finally {
                in.close();
            }
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
        long selfClasses;
        long hits;
        long implicitCtors;
        long unmatched;
        long usedMethods;

        @Override
        public String toString() {
            return "jar=" + jars + " クラス=" + classes
                    + " 被参照=" + hits + "件（自分のメソッド " + usedMethods + " 個）"
                    + " 暗黙コンストラクタ=" + implicitCtors
                    + " 未照合=" + unmatched
                    + " 自プロジェクトクラスを除外=" + selfClasses;
        }
    }

    /**
     * 他チームのjarを走査し、自分のメソッドがどこから参照されているかを出力する。
     *
     * 用途は改修時の影響調査。「このメソッドを直すと誰に影響するか」に答える。
     */
    static final class ExternalUsageScanner {

        static ExternalUsageStats scan(CallGraph g, Config config,
                                        CallHierarchyCsvWriter out) throws IOException {
            ExternalUsageStats st = new ExternalUsageStats();

            List<Path> jars = collectJars(config.externalLibraryFolders);
            log("外部jar: " + jars.size() + " 件");
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

            {
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
                            // 自プロジェクトのクラスが混ざったjar（自分のビルド成果物が
                            // 同じフォルダにある等）は「他リポジトリからの被参照」ではない。
                            // 自分自身からの呼び出しを被参照として出さないよう読み飛ばす
                            if (isOurType(ourTypes, refs.thisClass)) {
                                st.selfClasses++;
                                continue;
                            }
                            st.classes++;
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
                                    out.writeExternalUsageRow(refs.thisClass,
                                            g.methods.displayLabel(id),
                                            g.methods.shortLabel(id), jarName, kind);
                                    if (refCount[id]++ == 0) {
                                        st.usedMethods++;
                                    }
                                    st.hits++;
                                } else if ("<init>".equals(r[1])) {
                                    // 暗黙のデフォルトコンストラクタ。ソース上に宣言が無いため
                                    // 照合先が存在しないが、これは版の食い違いではない。
                                    // 「誰がこのクラスを生成しているか」は影響調査で有用なので
                                    // 被参照として記録する。
                                    String simple = simpleOf(owner);
                                    out.writeExternalUsageRow(refs.thisClass,
                                            normalize(owner) + "." + simple + "()",
                                            simple + "." + simple,
                                            jarName, "IMPLICIT_CTOR");
                                    st.implicitCtors++;
                                } else {
                                    // 自分の型への参照なのに一致するメソッドが無い。
                                    // 相手が古い版のjarに対してビルドされている可能性がある。
                                    // 「使われていない」と即断しないよう件数だけ残す
                                    st.unmatched++;
                                }
                            }
                        }
                    } finally {
                        jf.close();
                    }
                    st.jars++;
                }
            }
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
         *
         * シグネチャは classファイルのディスクリプタから作るため、引数の内部クラスが
         * Outer$Inner の形で入る。JDT側は Outer.Inner なので、そのままでは
         * 「内部クラスを引数に取るオーバーロード」だけが照合できず、未照合に落ちる。
         * まず生の形で引き、外れたら $ を . に直した形でもう一度引く
         * （クラス名に $ を含む型を誤って読み替えないよう、生の形を先に試す）。
         */
        private static int resolveRef(CallGraph g, Map<String, List<Integer>> bySignature,
                                       String owner, String sig) {
            int id = lookupRef(g, bySignature, owner, sig);
            if (id >= 0) {
                return id;
            }
            String normSig = normalize(sig);
            return normSig.equals(sig) ? -1 : lookupRef(g, bySignature, owner, normSig);
        }

        /**
         * 完全一致で見つからない場合、継承したメソッドの呼び出し
         * （呼び出し側は子クラスを owner として記録する）を考慮して親を探す。
         */
        private static int lookupRef(CallGraph g, Map<String, List<Integer>> bySignature,
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
     * - max.depth  … 深さ制限（0以下で無制限だが、循環検出があるため止まる）
     * - max.rows   … 出力行数の上限（組合せ爆発への最後の砦。0以下で無制限）
     * - 循環検出   … 「現在の経路（rootからそのノードまでの祖先）」に同じメソッドが
     *                既にあれば、その辺を1行だけ出力してそこから先へは降りない。
     *                判定は経路単位なので、別の経路で同じ呼び出しが現れた場合は
     *                そちらでも改めて出力する（グローバルな訪問済み集合は持たない）
     * - 除外パッケージ … 除外対象のノード自身は出力しないが、その先は親に
     *                    繋ぎ直して辿り続ける
     */
    static final class StreamingTreeWalker {

        /**
         * max.depth が 0以下（無制限指定）のときに使う実効上限。
         *
         * 探索は再帰なので、本当に無制限にするとスタックオーバーフローになる。
         * 循環は経路単位で検出して打ち切るため深さは「相異なるメソッド数」で
         * 頭打ちになるが、大規模プロジェクトではそれでも数千に達しうる。
         */
        private static final int DEPTH_HARD_CAP = 512;
        /** 経路上で既に呼んでいるメソッドへ戻る辺の印 */
        static final String CYCLE_MARK = "[CYCLE]";

        private final CallGraph graph;
        private final Config config;
        private final CallHierarchyCsvWriter writer;
        private final int maxDepth;

        // 現在の経路（深さぶんだけ確保）
        private final int[] pathMethod;
        private final int[] pathCallLine;
        private final String[] pathNote;
        /**
         * 経路上の各メソッドの引数の具象型（Issue #18）。
         * pathParamTypes[d][i] は、深さ d のメソッドの i 番目の引数に
         * 「この経路では」何が渡ってきているか。分からない引数は null。
         *
         * 経路ごとに前向き（root -> 葉）に伝えるだけで、呼び出し元の候補を
         * 遡って探索することはしない。同じメソッドでも経路が違えば別の値になる
         * ——それがこの解析の意味であり、メモ化できない理由でもある。
         */
        private final String[][] pathParamTypes;
        /**
         * 経路上の各深さで、「今メソッドを実行しているオブジェクト」の
         * コンストラクタ実引数の具象型（Issue #17/#18 のフィールドへの延長）。
         * コンストラクタ注入されたフィールドは、これと突き合わせて具象型が決まる。
         */
        private final String[][] pathCtorArgs;
        /** pathCtorArgs が属する型。親クラスのフィールドに取り違えて当てないため */
        private final String[] pathCtorOwner;
        /** データフローで具象クラスを特定した件数（ログ用） */
        private long paramHits;
        private long factoryHits;
        private long fieldHits;
        private long newHits;

        private int rootId;
        private long totalRows;
        private boolean limitWarned;

        StreamingTreeWalker(CallGraph graph, Config config, CallHierarchyCsvWriter writer) {
            this.graph = graph;
            this.config = config;
            this.writer = writer;
            this.maxDepth = (config.maxDepth > 0) ? config.maxDepth : DEPTH_HARD_CAP;
            int cap = Math.max(2, this.maxDepth + 2);
            this.pathMethod = new int[cap];
            this.pathCallLine = new int[cap];
            this.pathNote = new String[cap];
            this.pathParamTypes = new String[cap][];
            this.pathCtorArgs = new String[cap][];
            this.pathCtorOwner = new String[cap];
        }

        long paramHits() {
            return paramHits;
        }

        long factoryHits() {
            return factoryHits;
        }

        long fieldHits() {
            return fieldHits;
        }

        long newHits() {
            return newHits;
        }

        long walkAll(int[] entries) throws IOException {
            for (int i = 0; i < entries.length; i++) {
                rootId = entries[i];
                pathMethod[0] = rootId;
                pathCallLine[0] = -1;
                pathNote[0] = null;
                // 起点メソッドの引数も、そのオブジェクトの生成箇所も、
                // 経路の中に無いので分からない
                pathParamTypes[0] = null;
                pathCtorArgs[0] = null;
                pathCtorOwner[0] = null;
                descend(0);
                if (isRowLimitReached()) {
                    break;
                }
            }
            return totalRows;
        }

        /** depth のノードから、その呼び出し先を辿る */
        private void descend(int depth) throws IOException {
            if (depth >= maxDepth || depth + 1 >= pathMethod.length) {
                return;
            }
            int callerId = pathMethod[depth];
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

                // Issue #18: 絞れなかった呼び出しだけ、この経路で渡ってきた
                // 引数の具象型を使って解決を試みる。「必要なときだけ」にするのは、
                // 全呼び出しで試すと解析コストが呼び出し数に比例して効いてくるため
                if (res.targets.length > 1 && graph.dataflowEnabled()) {
                    String recvOrigin = graph.recvOrigin(e);
                    int viaPath = graph.dataflowTarget(recvOrigin, declaredCallee, contextAt(depth));
                    if (viaPath >= 0) {
                        String label = CallGraph.dataflowLabel(recvOrigin);
                        res = new CallGraph.Resolution(new int[]{viaPath}, label);
                        countDataflow(label);
                    }
                } else if (res.label.startsWith("DATAFLOW_")) {
                    countDataflow(res.label);
                }

                // CHAで候補が複数になった呼び出しは、候補の数だけ展開すると
                // 候補数^深さ で爆発する。宣言型のまま1行だけ残して先へは降りない
                int[] targets = res.targets;
                boolean expand = (targets.length == 1);
                int limit = Math.min(targets.length, Config.CHA_MAX_CANDIDATES);

                for (int ti = 0; ti < limit; ti++) {
                    if (isRowLimitReached()) {
                        return;
                    }
                    int target = targets[ti];

                    String[] targetParams = bindArguments(e, depth, target);
                    String[] targetCtorArgs = bindConstructorArguments(e, depth, target);

                    if (isExcluded(target)) {
                        // 除外対象のノード自身は出力しないが、その先は親に繋ぎ直して辿る
                        if (!onCurrentPath(target, depth)) {
                            skipThrough(depth, target, targetParams, targetCtorArgs);
                        }
                        continue;
                    }

                    boolean cycle = onCurrentPath(target, depth);
                    push(depth + 1, target, callLine,
                            noteFor(target, declaredCallee, res, depth, cycle,
                                    (char) graph.recvKinds[e]));
                    pathParamTypes[depth + 1] = targetParams;
                    pathCtorArgs[depth + 1] = targetCtorArgs;
                    pathCtorOwner[depth + 1] = (targetCtorArgs == null)
                            ? null : graph.methods.typeFqn(target);

                    // コンストラクタ呼び出しそのものは行にしない。
                    // 「new したこと」自体より「その先で何を呼んでいるか」が知りたいため。
                    // 経路には積むので、コンストラクタ内からの呼び出しは
                    // call-hierarchy 列に <init> を含んだ形で出力される
                    if (!isConstructor(target)) {
                        emit(depth + 1);
                    }

                    // 循環（この経路上で既に呼んでいるメソッドへ戻る辺）はここで打ち切る
                    if (expand && !cycle) {
                        descend(depth + 1);
                    }
                }
            }
        }

        /**
         * この呼び出しで渡す実引数の具象型を求め、呼び出し先の引数の環境を作る（Issue #18）。
         *
         * 経路の1つ上（呼び出し元）の環境しか見ないので、rootからの1本の経路に対して
         * 決定的に決まる。呼び出し元の候補を遡って集めることはしない。
         *
         * 何も分からない場合や、呼び出し先が引数を使い回さない場合は null を返す。
         * null を返せば以降の深さでは何もしないので、解析コストが必要な箇所だけに絞れる。
         */
        private String[] bindArguments(int edgeIndex, int depth, int target) {
            if (!graph.dataflowEnabled() || !graph.usesContext(target)) {
                return null;
            }
            return resolveArgs(graph.argOrigins(edgeIndex), depth);
        }

        /**
         * 呼び出し先のオブジェクトが、この経路でどう生成されたかを求める。
         *
         * レシーバが {@code new X(...)} なら、その実引数の具象型が
         * コンストラクタ注入されたフィールドの中身になる。
         * レシーバが無い（this への呼び出し）場合は、同じオブジェクトの
         * 別のメソッドを呼んでいるので、今の環境をそのまま引き継ぐ。
         */
        private String[] bindConstructorArguments(int edgeIndex, int depth, int target) {
            if (!graph.dataflowEnabled()) {
                return null;
            }
            String targetType = graph.methods.typeFqn(target);
            if (!graph.hasInjectedFields(targetType)) {
                return null;   // 注入されたフィールドを持たない型には渡す意味が無い
            }
            String recvOrigin = graph.recvOrigin(edgeIndex);
            if (recvOrigin == null) {
                // レシーバなし = this。同じ型のメソッドを呼んでいる間だけ引き継ぐ
                return targetType.equals(pathCtorOwner[depth]) ? pathCtorArgs[depth] : null;
            }
            if (Origin.kindOf(recvOrigin) != Origin.NEW
                    || !targetType.equals(Origin.valueOf(recvOrigin))) {
                // new 以外（引数・フィールド・戻り値）から来たオブジェクトは、
                // どのコンストラクタ実引数で作られたかがこの経路では分からない
                return null;
            }
            return resolveArgs(Origin.argsOf(recvOrigin), depth);
        }

        /** "位置=出所;..." を、この経路で分かっている具象型の配列に変換する */
        private String[] resolveArgs(String spec, int depth) {
            if (spec == null || spec.isEmpty()) {
                return null;
            }
            CallGraph.DataflowContext ctx = contextAt(depth);
            String[] bound = null;
            for (String entry : spec.split(";")) {
                int eq = entry.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                int index;
                try {
                    index = Integer.parseInt(entry.substring(0, eq));
                } catch (NumberFormatException ignore) {
                    continue;
                }
                String fqn = graph.concreteTypeOf(entry.substring(eq + 1), ctx);
                if (fqn == null) {
                    continue;
                }
                if (bound == null) {
                    bound = new String[index + 1];
                } else if (index >= bound.length) {
                    bound = Arrays.copyOf(bound, index + 1);
                }
                bound[index] = fqn;
            }
            return bound;
        }

        private void countDataflow(String label) {
            if ("DATAFLOW_FIELD".equals(label)) {
                fieldHits++;
            } else if ("DATAFLOW_PARAM".equals(label)) {
                paramHits++;
            } else if ("DATAFLOW_NEW".equals(label)) {
                newHits++;
            } else {
                factoryHits++;
            }
        }

        /** この深さで経路から分かっていること */
        private CallGraph.DataflowContext contextAt(int depth) {
            return CallGraph.DataflowContext.of(
                    pathParamTypes[depth], pathCtorArgs[depth], pathCtorOwner[depth]);
        }

        /** コンストラクタか（this(...)/super(...)/new いずれも呼び出し先は <init>） */
        private boolean isConstructor(int id) {
            return "<init>".equals(graph.methods.methodName(id));
        }

        private boolean isExcluded(int id) {
            return PackagePattern.matchesAny(config.excludePatterns,
                    graph.methods.pkg(id), graph.methods.typeFqn(id),
                    graph.methods.methodName(id));
        }

        /**
         * 除外されたノード自身は出力せず、
         * その呼び出し先を「1つ上の親の子」として辿り直す。
         */
        private void skipThrough(int parentDepth, int skippedId,
                                 String[] skippedParams, String[] skippedCtorArgs)
                throws IOException {
            int saved = pathMethod[parentDepth];
            String[] savedParams = pathParamTypes[parentDepth];
            String[] savedCtorArgs = pathCtorArgs[parentDepth];
            String savedOwner = pathCtorOwner[parentDepth];
            pathMethod[parentDepth] = skippedId;   // 一時的に呼び出し元を差し替える
            // 経路の環境も一緒に差し替える。元のまま残すと、除外されたメソッドの中の
            // 呼び出しに、その呼び出し元の引数を当ててしまう
            pathParamTypes[parentDepth] = skippedParams;
            pathCtorArgs[parentDepth] = skippedCtorArgs;
            pathCtorOwner[parentDepth] = (skippedCtorArgs == null)
                    ? null : graph.methods.typeFqn(skippedId);
            try {
                descend(parentDepth);
            } finally {
                pathMethod[parentDepth] = saved;
                pathParamTypes[parentDepth] = savedParams;
                pathCtorArgs[parentDepth] = savedCtorArgs;
                pathCtorOwner[parentDepth] = savedOwner;
            }
        }

        private void push(int depth, int methodId, int callLine, String note) {
            pathMethod[depth] = methodId;
            pathCallLine[depth] = callLine;
            pathNote[depth] = note;
        }

        /**
         * ノードに付ける注記。call-hierarchy列の最後の要素として出す。
         *
         * 独立した列にすると call-hierarchy より後ろに列ができてしまい、
         * 「可変長の階層を最終列に置く」という構成が崩れるため、
         * 階層の末尾に追記する形にしている（そのぶん行末grepは効かなくなる）。
         */
        private String noteFor(int target, int declaredCallee,
                                CallGraph.Resolution res, int depth, boolean cycle,
                                char recvKind) {
            StringBuilder sb = new StringBuilder();
            if (cycle) {
                // この経路上で既に呼んでいるメソッドへ戻る辺。ここから先へは降りない
                sb.append(CYCLE_MARK);
            } else if ("EXTERNAL_GUESS".equals(res.label)) {
                // クラスパス不足でバインディング解決自体ができなかった呼び出し。
                // importの単一型インポートから型名を推定しただけで、JDTによる
                // 検証は経ていない（メンバの実在・オーバーロードは未確認）
                sb.append("外部ライブラリ（import推定・未検証）");
            } else if (graph.methods.declFile(target) == null) {
                sb.append("ソースなし（展開不可）");
            } else if (depth + 1 >= maxDepth) {
                sb.append("深さ制限(").append(maxDepth).append(")のため打ち切り");
            }
            if (res.targets.length > 1) {
                if (sb.length() > 0) {
                    sb.append(" / ");
                }
                // 「なぜ絞れないのか」まで出す。レシーバの由来で次に調べる場所が変わる
                sb.append("CHA候補").append(res.targets.length).append("件（未展開）: ")
                        .append(RecvKind.describe(recvKind));
            } else if ("NO_IMPL".equals(res.label)) {
                if (sb.length() > 0) {
                    sb.append(" / ");
                }
                // 本体を持つ実装がソース上に1つも無い。宣言のまま出しているだけで、
                // 実行時に何が動くかはこのツールでは分からない
                sb.append("実装なし（宣言のまま）: ").append(RecvKind.describe(recvKind));
            } else if (target != declaredCallee || res.label.startsWith("DATAFLOW_")) {
                if (sb.length() > 0) {
                    sb.append(" / ");
                }
                // データフローで決めた場合は、宣言型と同じ結論でも「CHAで諦めずに
                // 絞れた」ことに意味があるので必ず出す
                sb.append("解決:").append(res.label);
            }
            return (sb.length() == 0) ? null : sb.toString();
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
            if (config.maxRows <= 0 || totalRows < config.maxRows) {
                return false;
            }
            if (!limitWarned) {
                limitWarned = true;
                log("[WARN] 出力行数の上限(" + config.maxRows + ")に達したため打ち切りました");
            }
            return true;
        }

        /** 1行を即座に書き出す（溜め込まない） */
        private void emit(int depth) throws IOException {
            writer.writeRow(graph.methods, rootId, pathMethod, pathCallLine, pathNote, depth);
            totalRows++;
        }
    }

    /**
     * 呼び出し階層のCSVを1行ずつ書き出す。
     *
     * ヘッダー:
     *   caller,callee,root,call-hierarchy...
     *
     * callee は「完全修飾クラス名.メソッド名(引数型略名)」。パッケージ違いの同名
     * クラスとオーバーロードを、この1列だけで見分けられるようにするため。
     *
     * - 呼び出し1件につき1行（起点自身は呼び出し元が無いため出力しない）
     * - caller は Eclipse の Java Stack Trace Console が認識する
     *   "at Class.method(File.java:行)" 形式。貼り付けるだけでソースへ飛べる
     * - call-hierarchy 以降は起点の次のノードから現ノードまでを1ノード1列で
     *   展開するため、ヘッダー行とデータ行の列数は一致しない（意図した仕様）
     * - call-hierarchy より後ろに列を追加してはならない（行末マッチが壊れるため）
     *
     * 行番号の使い分け（実際のスタックトレースと同じ考え方）:
     * - caller … 呼び出し元が「このノードを呼んでいる行」＝呼び出し箇所
     */
    static final class CallHierarchyCsvWriter {

        /** 型解決に失敗した行の root 列。起点が無いことを示す固定マーカー */
        static final String UNRESOLVED_ROOT = "(型解決失敗)";

        private final BufferedWriter writer;
        private final StringBuilder buf = new StringBuilder(512);

        CallHierarchyCsvWriter(Path outputCsv, Charset encoding, boolean bom)
                throws IOException {
            this.writer = Csv.writer(outputCsv, encoding, bom);
            writer.write(String.join(Csv.DELIM,
                    "caller", "callee", "root", "call-hierarchy"));
            writer.newLine();
        }

        void writeRow(MethodTable mt, int rootId, int[] pathMethod, int[] pathCallLine,
                      String[] pathNote, int depth) throws IOException {
            buf.setLength(0);

            // caller: 呼び出し元が「このノードを呼んでいる行」を指すスタックトレース形式。
            // Eclipse の Java Stack Trace Console に貼ればソースへ飛べる。
            // 起点自身（depth==0）は出力しないため、depth は必ず1以上。
            int parent = pathMethod[depth - 1];
            buf.append(Csv.esc(stackTrace(mt, parent, pathCallLine[depth]))).append(Csv.DELIM);

            // callee: 完全修飾クラス名 + メソッド名 + 引数型略名。
            // Excelのフィルタで選べるよう、行番号は含めない安定した表記にする
            // （行番号を混ぜるとフィルタの選択肢が呼び出し箇所ごとに散らばる）。
            buf.append(Csv.esc(mt.displayLabel(pathMethod[depth]))).append(Csv.DELIM);

            // root: 起点メソッド。これもフィルタで使えるよう短縮表記にする
            buf.append(Csv.esc(mt.shortLabel(rootId)));

            // call-hierarchy: 起点の次のノードから現ノードまでを1ノード1列で展開。
            // 必ず最終列に置く（後ろに固定列を足すと可変長の階層が途中で切れるため）。
            for (int i = 1; i <= depth; i++) {
                buf.append(Csv.DELIM).append(Csv.esc(mt.shortLabel(pathMethod[i])));
            }
            // 注記（[CYCLE]・深さ制限・CHA候補・import推定 等）は階層の最後に付ける
            if (pathNote[depth] != null) {
                buf.append(Csv.DELIM).append(Csv.esc(pathNote[depth]));
            }
            writer.write(buf.toString());
            writer.newLine();
        }

        /**
         * 型解決に失敗した呼び出しの1行。
         *
         * 呼び出し「元」はソース上のメソッドなので分かるが、呼び出し「先」の型が
         * 特定できていない。よって callee にはソースに書かれていた式（メソッド名）を
         * そのまま置き、root には起点が無いことを示す固定マーカーを入れる。
         * root でフィルタすれば、型解決に失敗した箇所だけをまとめて見られる。
         *
         * @param mt         呼び出し元の解決に使うメソッド表
         * @param callerId   呼び出し元メソッドのID。-1 なら特定できていない
         * @param location   callerId が -1 のときに caller 列へ出す位置情報
         * @param line       呼び出し箇所の行番号
         * @param expression ソースに書かれていた呼び出しの式（メソッド名）
         * @param reason     失敗の理由
         */
        void writeUnresolvedRow(MethodTable mt, int callerId, String location,
                                 int line, String expression, String reason) throws IOException {
            buf.setLength(0);
            String caller = (callerId >= 0) ? stackTrace(mt, callerId, line) : location;
            buf.append(Csv.esc(caller)).append(Csv.DELIM);
            buf.append(Csv.esc(expression)).append(Csv.DELIM);
            buf.append(Csv.esc(UNRESOLVED_ROOT));
            buf.append(Csv.DELIM).append(Csv.esc(expression));
            buf.append(Csv.DELIM).append(Csv.esc(reason));
            writer.write(buf.toString());
            writer.newLine();
        }

        /**
         * 被参照スキャンの1行。呼び出し階層とは意味が違うため専用の詰め方をする。
         *
         * classファイルの定数プールしか読まないため、呼び出し元のメソッドも行番号も
         * 分からない。よって caller はスタックトレース形式にはせず、参照している
         * クラス名をそのまま置く。起点も呼び出し階層も無いので、root には
         * 「どのjarから参照されているか」を入れる。
         *
         * @param referencingClass 参照している側のクラス（外部jar内）
         * @param callee           参照されている自分のメソッド（callee列と同じ表記）
         * @param shortCallee      階層列に置く短縮表記
         * @param jarName          参照元のjar名
         * @param note             照合の種類（EXACT / INHERITED / IMPLICIT_CTOR）
         */
        void writeExternalUsageRow(String referencingClass, String callee,
                                    String shortCallee, String jarName, String note)
                throws IOException {
            buf.setLength(0);
            buf.append(Csv.esc(referencingClass)).append(Csv.DELIM);
            buf.append(Csv.esc(callee)).append(Csv.DELIM);
            buf.append(Csv.esc(jarName));
            buf.append(Csv.DELIM).append(Csv.esc(shortCallee));
            buf.append(Csv.DELIM).append(Csv.esc("被参照:" + note));
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

        /** 出力の区切り文字。CSV固定（Excelでそのまま開ける形にするため） */
        static final String DELIM = ",";

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
 * 設定ファイル（config.properties）について
 * --------------------------------------------------------------------
 * 設定できる項目とその意味は config/config.properties にコメント付きで
 * まとめてあります。あちらを唯一の一覧として扱い、ここには複製しません
 * （二重管理になって片方が古くなるのを避けるため）。
 *
 * 実行時は設定ファイルのパスを引数で渡します。省略した場合は
 * config/config.properties を使います。
 *
 *     java -cp "bin;lib/*" CallHierarchyExporter config/config.properties
 *
 * 相対パスの起点は項目ごとに異なります。
 *   - project.root / cache.folders / output.csv / methods.csv
 *     … この設定ファイルが置かれているディレクトリ
 *   - source.folders / library.folders / external.library.folders … project.root
 * ==================================================================== */
