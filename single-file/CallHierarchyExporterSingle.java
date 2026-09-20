// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0

// ---------------------------------------------------------------------------
// java-call-hierarchy-exporter の「お試し版」。
//
// 本体（src/jche 配下・123ファイル）の中心機能である 2 本の CSV 出力
// （call-hierarchy.csv / methods.csv）だけを、既定パッケージの 1 ファイルに
// まとめたもの。このファイル 1 つと Eclipse JDT Core の jar だけでビルドして
// 動かせる。キャッシュ・差分解析・被参照スキャン・データフロー解析・対話モード・
// サーバーモード・ビルドツールからの依存解決は入っていない（本体を参照）。
//
// 仕様の出典は docs/prompt-A-minimal.md（出力の契約・既定値・解決の段）。
//
// ビルドと実行:
//   javac -encoding UTF-8 -cp "lib/*" -d bin single-file/CallHierarchyExporterSingle.java
//   java  -cp "bin:lib/*" CallHierarchyExporterSingle config/config.properties   (Windows は ; 区切り)
//
// JBang なら jar を自分で集めずに直接:
//   jbang single-file/CallHierarchyExporterSingle.java config/config.properties
// ---------------------------------------------------------------------------
//DEPS org.eclipse.jdt:org.eclipse.jdt.core:3.46.0
//JAVA 25

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionMethodReference;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FileASTRequestor;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.SuperMethodReference;
import org.eclipse.jdt.core.dom.ThisExpression;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.TypeMethodReference;

/**
 * Javaプロジェクトのメソッド呼び出し階層を一括抽出して CSV に出す、単一ファイル版。
 *
 * <pre>
 *   フェーズ1  parse    ソースをASTパースし、宣言と呼び出し箇所を集める
 *   フェーズ2  resolve  メソッドを int のIDに内部化し、具象クラスを段階的に解決する
 *   フェーズ3  report   起点ごとに深さ優先で辿りながらCSVを1行ずつ書く
 * </pre>
 *
 * 階層はツリーとして組み立てず、深さ優先で1行ずつ書き出す（ヒープに載るのは現在の経路だけ）。
 */
public final class CallHierarchyExporterSingle {

    // =======================================================================
    // 既定値（docs/prompt-A-minimal.md 「4. 設定と既定値」）
    // =======================================================================

    /** 深さ上限。これを超えたら注記を付けて打ち切る */
    private static final int DEFAULT_MAX_DEPTH = 50;

    /** 行数上限。巨大プロジェクトで出力が止まらなくなるのを防ぐ */
    private static final long DEFAULT_MAX_ROWS = 5_000_000L;

    /** 既定の除外パターン。行にはしないが、その先は親に繋ぎ直して辿り続ける */
    private static final String DEFAULT_EXCLUDES = "java.**,javax.**";

    /** ASTParser に一度に渡すファイル数。1ファイルずつ渡すと規模に対して超線形に遅くなる */
    private static final int BATCH = 100;

    /** ソースフォルダを推定するときに見る相対パス（上から順に、存在するものを採用） */
    private static final String[] SOURCE_FOLDER_GUESSES = {
            "src/main/java", "src/main/scala", "src/java", "src", "source", "java",
    };

    private CallHierarchyExporterSingle() {
    }

    // =======================================================================
    // エントリポイント
    // =======================================================================

    public static void main(String[] args) throws Exception {
        Path configPath = Paths.get(args.length > 0 ? args[0] : "config/config.properties");
        if (!Files.isRegularFile(configPath)) {
            System.err.println("設定ファイルが見つかりません: " + configPath.toAbsolutePath());
            System.err.println("使い方: java CallHierarchyExporterSingle <設定ファイル>");
            System.exit(2);
        }
        try {
            new Run(configPath).execute();
        } catch (Exception e) {
            System.err.println("解析に失敗しました: " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    // =======================================================================
    // 設定
    // =======================================================================

    /** 設定ファイルの内容。パスはすべて設定ファイルのあるフォルダを起点に解決する */
    static final class Config {
        final Path configPath;
        final Path configDir;
        final Path projectRoot;
        final List<Path> sourceFolders = new ArrayList<>();
        final List<Path> libraryJars = new ArrayList<>();
        final Charset sourceEncoding;
        final String sourceLevel;
        final Path outputParent;
        final List<Pattern> excludes = new ArrayList<>();
        final List<Pattern> entryPatterns = new ArrayList<>();
        final int maxDepth;
        final long maxRows;

        Config(Path configPath) throws IOException {
            this.configPath = configPath.toAbsolutePath().normalize();
            Path dir = this.configPath.getParent();
            this.configDir = (dir != null) ? dir : Paths.get("").toAbsolutePath();

            Properties p = new Properties();
            // 設定ファイルは UTF-8（日本語のコメントとパスを書けるようにするため）
            try (var in = Files.newBufferedReader(this.configPath, StandardCharsets.UTF_8)) {
                p.load(in);
            }

            String root = value(p, "project.root", "");
            if (root.isEmpty()) {
                throw new IOException("project.root が空です（解析対象プロジェクトのフォルダを指定してください）: " + configPath);
            }
            this.projectRoot = resolve(configDir, root);
            if (!Files.isDirectory(projectRoot)) {
                throw new IOException("project.root がフォルダではありません: " + projectRoot);
            }

            for (String s : list(value(p, "source.folders", ""))) {
                Path f = resolve(projectRoot, s);
                if (Files.isDirectory(f)) {
                    sourceFolders.add(f);
                } else {
                    log("警告: source.folders に無いフォルダがあります（無視します）: " + f);
                }
            }
            if (sourceFolders.isEmpty()) {
                sourceFolders.addAll(guessSourceFolders(projectRoot));
            }
            if (sourceFolders.isEmpty()) {
                throw new IOException("解析するソースフォルダがありません: " + projectRoot);
            }

            for (String s : list(value(p, "library.folders", ""))) {
                Path f = resolve(projectRoot, s);
                if (!Files.isDirectory(f)) {
                    log("警告: library.folders に無いフォルダがあります（無視します）: " + f);
                    continue;
                }
                try (Stream<Path> w = Files.walk(f)) {
                    w.filter(Files::isRegularFile)
                            .filter(q -> q.getFileName().toString().endsWith(".jar"))
                            .sorted(Comparator.comparing(q -> q.toAbsolutePath().toString().replace('\\', '/')))
                            .forEach(libraryJars::add);
                }
            }

            this.sourceEncoding = Charset.forName(value(p, "source.encoding", "UTF-8"));
            this.sourceLevel = value(p, "source.level", JavaCore.latestSupportedJavaVersion());
            this.outputParent = resolve(configDir, value(p, "output.folder", "."));
            this.maxDepth = intValue(p, "max.depth", DEFAULT_MAX_DEPTH);
            this.maxRows = longValue(p, "max.rows", DEFAULT_MAX_ROWS);

            for (String s : list(value(p, "exclude.callees", DEFAULT_EXCLUDES))) {
                excludes.add(new Pattern(s));
            }
            for (String s : list(value(p, "entry.points", ""))) {
                entryPatterns.add(new Pattern(s));
            }
        }

        private static String value(Properties p, String key, String fallback) {
            String v = p.getProperty(key);
            if (v == null) {
                return fallback;
            }
            v = v.trim();
            return v.isEmpty() ? fallback : v;
        }

        private static int intValue(Properties p, String key, int fallback) {
            try {
                return Integer.parseInt(value(p, key, String.valueOf(fallback)));
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        private static long longValue(Properties p, String key, long fallback) {
            try {
                return Long.parseLong(value(p, key, String.valueOf(fallback)));
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        private static List<String> list(String csv) {
            List<String> out = new ArrayList<>();
            for (String s : csv.split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
            return out;
        }

        private static Path resolve(Path base, String s) {
            Path p = Paths.get(s);
            return (p.isAbsolute() ? p : base.resolve(p)).normalize();
        }

        /** source.folders が空のときの推定。よくある配置を上から見て、最初に見つかったものを採る */
        private static List<Path> guessSourceFolders(Path root) throws IOException {
            List<Path> found = new ArrayList<>();
            for (String g : SOURCE_FOLDER_GUESSES) {
                Path p = root.resolve(g);
                if (Files.isDirectory(p)) {
                    found.add(p.normalize());
                }
            }
            if (!found.isEmpty()) {
                // 上位のものだけ採る（src と src/main/java の両方を入れると同じファイルを二重に解析する）
                List<Path> picked = new ArrayList<>();
                for (Path p : found) {
                    boolean under = picked.stream().anyMatch(q -> p.startsWith(q));
                    if (!under) {
                        picked.add(p);
                    }
                }
                return picked;
            }
            // 最後の手段。ルート直下に .java があればルート自身をソースフォルダとみなす
            try (Stream<Path> w = Files.walk(root, 3)) {
                if (w.anyMatch(q -> q.getFileName().toString().endsWith(".java"))) {
                    return List.of(root);
                }
            }
            return List.of();
        }
    }

    /**
     * 呼び出し先のパターン。{@code pkg.*} / {@code pkg.**} / {@code pkg.Class} /
     * {@code pkg.Class#method} の4形式。
     */
    static final class Pattern {
        private final String typePart;
        private final String methodPart; // null なら全メソッド

        Pattern(String raw) {
            int hash = raw.indexOf('#');
            if (hash >= 0) {
                this.typePart = raw.substring(0, hash).trim();
                this.methodPart = raw.substring(hash + 1).trim();
            } else {
                this.typePart = raw.trim();
                this.methodPart = null;
            }
        }

        boolean matches(String typeFqn, String methodName) {
            if (methodPart != null && !methodPart.equals(methodName)) {
                return false;
            }
            if (typePart.endsWith(".**")) {
                String prefix = typePart.substring(0, typePart.length() - 2); // "pkg."
                return typeFqn.startsWith(prefix);
            }
            if (typePart.endsWith(".*")) {
                String prefix = typePart.substring(0, typePart.length() - 1); // "pkg."
                if (!typeFqn.startsWith(prefix)) {
                    return false;
                }
                // 直下だけ（さらに下の階層のパッケージは含めない。内部クラスは同じ型とみなす）
                String rest = typeFqn.substring(prefix.length());
                return rest.indexOf('.') < 0 || Character.isUpperCase(rest.charAt(0));
            }
            return typeFqn.equals(typePart) || typeFqn.startsWith(typePart + ".");
        }
    }

    // =======================================================================
    // モデル（フェーズ2で int のIDに内部化する）
    // =======================================================================

    /** メソッド1つ。ソース上のものと、ライブラリ側のもの（呼び出し先としてだけ現れる）の両方 */
    static final class M {
        final int id;
        final String key;          // JDT のバインディングキー（同一性の判定に使う）
        final String typeBinary;   // Outer$Inner 形式（caller 列のスタックトレース用）
        final String typeFqn;      // Outer.Inner 形式（callee 列・フィルタ用）
        final String typeSimple;
        final String name;         // コンストラクタは <init>
        final String paramsShort;  // 表示用の引数型略名
        final String paramsErased; // 突き合わせ用の引数型（消去後の完全修飾）
        String file = "";          // project.root からの相対パス（/区切り）
        int line;
        boolean hasBody;
        boolean inSource;
        boolean isStatic;
        boolean isPrivate;
        boolean isFinal;      // メソッドが final、または宣言型が final
        boolean isCtor;
        char typeKind = 'C';  // I=インターフェース A=抽象クラス C=具象クラス
        int sourceOrder = Integer.MAX_VALUE; // 出力順の決定に使う（ソースフォルダ順→パス順→宣言行順）

        M(int id, String key, String typeBinary, String typeFqn, String name,
                String paramsShort, String paramsErased) {
            this.id = id;
            this.key = key;
            this.typeBinary = typeBinary;
            this.typeFqn = typeFqn;
            this.typeSimple = simpleOf(typeFqn);
            this.name = name;
            this.paramsShort = paramsShort;
            this.paramsErased = paramsErased;
            this.isCtor = "<init>".equals(name);
        }

        /** callee 列。完全修飾クラス名.メソッド名(引数型略名)。行番号は混ぜない */
        String callee() {
            return typeFqn + "." + name + "(" + paramsShort + ")";
        }

        /** call-hierarchy 列・root 列。クラス単純名.メソッド名 */
        String shortLabel() {
            return typeSimple + "." + name;
        }

        /** 同一シグネチャ判定用（オーバーライドの突き合わせ） */
        String signature() {
            return name + "(" + paramsErased + ")";
        }
    }

    /** 呼び出し箇所1つ。呼び出し元メソッドの中に、ソース上の並び順で並ぶ */
    static final class Edge {
        final int callerId;
        final int calleeId;   // 宣言型で見た呼び出し先
        final int line;       // 呼び出し箇所の行
        final boolean staticBound; // 仮想ディスパッチされない（段0）
        final boolean isNew;  // new。行にはしないが、経路には積む
        final String recvKind; // 絞れなかった理由の分類（レシーバの由来）

        Edge(int callerId, int calleeId, int line, boolean staticBound, boolean isNew, String recvKind) {
            this.callerId = callerId;
            this.calleeId = calleeId;
            this.line = line;
            this.staticBound = staticBound;
            this.isNew = isNew;
            this.recvKind = recvKind;
        }
    }

    /** 型解決に失敗した呼び出し。黙って捨てず、CSVの末尾とログの両方に出す */
    static final class Unresolved {
        final String callerBinary;
        final String callerName;
        final String fileName;
        final int line;
        final String text;
        final String cause;
        final String sortKey;

        Unresolved(String callerBinary, String callerName, String fileName, int line,
                String text, String cause, String sortKey) {
            this.callerBinary = callerBinary;
            this.callerName = callerName;
            this.fileName = fileName;
            this.line = line;
            this.text = text;
            this.cause = cause;
            this.sortKey = sortKey;
        }
    }

    // =======================================================================
    // 1回の解析
    // =======================================================================

    static final class Run {
        private final Config config;

        /** バインディングキー → メソッドID */
        private final Map<String, Integer> methodIds = new HashMap<>();
        private final List<M> methods = new ArrayList<>();

        /** 呼び出し元ID → 呼び出し箇所（ソース上の並び順） */
        private final Map<Integer, List<Edge>> edges = new LinkedHashMap<>();

        /** ソース上の型 FQN → その型が宣言するメソッド（シグネチャ → ID） */
        private final Map<String, Map<String, Integer>> declaredByType = new HashMap<>();

        /** 型 FQN → 直接の下位型（ソース上の型だけ） */
        private final Map<String, Set<String>> subtypes = new HashMap<>();

        private final List<Unresolved> unresolved = new ArrayList<>();
        private final List<String> logLines = new ArrayList<>();

        /** 解決結果のメモ化（呼び出し先ID＋段0か → 具象の候補） */
        private final Map<Long, int[]> resolveCache = new HashMap<>();

        private long rows;
        private long choppedByDepth;
        private long cycles;
        private long chaRows;
        private int parseErrors;

        Run(Path configPath) throws IOException {
            this.config = new Config(configPath);
        }

        void execute() throws IOException {
            LocalDateTime startedAt = LocalDateTime.now();
            Path outputDir = config.outputParent.resolve(
                    startedAt.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                            + "_" + config.projectRoot.getFileName());
            Files.createDirectories(outputDir);

            say("設定ファイル: " + config.configPath);
            say("project.root: " + config.projectRoot);
            say("ソースフォルダ: " + config.sourceFolders);
            say("依存jar: " + config.libraryJars.size() + " 個");
            say("出力フォルダ: " + outputDir);

            List<Path> files = collectSources();
            say("解析対象: " + files.size() + " ファイル");

            parseAll(files);
            say("フェーズ1 完了: メソッド宣言 " + methods.stream().filter(m -> m.inSource).count()
                    + " 件 / 呼び出し " + edges.values().stream().mapToInt(List::size).sum() + " 件"
                    + " / 型解決失敗 " + unresolved.size() + " 件");

            int[] inDegree = computeInDegrees();
            List<Integer> entries = entryPoints(inDegree);
            say("フェーズ2 完了: 起点 " + entries.size() + " 件");

            Set<Integer> reachable = new HashSet<>();
            writeCallHierarchy(outputDir.resolve("call-hierarchy.csv"), entries, reachable);
            writeMethods(outputDir.resolve("methods.csv"), inDegree, reachable);

            Files.copy(config.configPath, outputDir.resolve(config.configPath.getFileName()));
            report();
            writeLog(outputDir.resolve("run.log"));
            say("完了: " + outputDir);
            // say の後に書くと run.log に残らないので、最後の1行だけは標準出力のみ
        }

        // -------------------------------------------------------------------
        // フェーズ1: パース
        // -------------------------------------------------------------------

        /** 出力を決定的にするため、ソースフォルダ順 →「/区切りの相対パス文字列」順に並べる */
        private List<Path> collectSources() throws IOException {
            List<Path> all = new ArrayList<>();
            for (Path folder : config.sourceFolders) {
                List<Path> inFolder = new ArrayList<>();
                try (Stream<Path> w = Files.walk(folder)) {
                    w.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().endsWith(".java"))
                            .filter(p -> !p.getFileName().toString().equals("package-info.java"))
                            .forEach(inFolder::add);
                }
                inFolder.sort(Comparator.comparing(p -> folder.relativize(p).toString().replace('\\', '/')));
                all.addAll(inFolder);
            }
            return all;
        }

        private void parseAll(List<Path> files) {
            String[] classpath = config.libraryJars.stream()
                    .map(p -> p.toAbsolutePath().toString()).toArray(String[]::new);
            String[] sourcepath = config.sourceFolders.stream()
                    .map(p -> p.toAbsolutePath().toString()).toArray(String[]::new);
            String[] sourceEncodings = new String[sourcepath.length];
            Arrays.fill(sourceEncodings, config.sourceEncoding.name());

            int[] order = {0};
            for (int from = 0; from < files.size(); from += BATCH) {
                int to = Math.min(from + BATCH, files.size());
                List<Path> batch = files.subList(from, to);
                String[] paths = batch.stream().map(p -> p.toAbsolutePath().toString()).toArray(String[]::new);
                String[] encodings = new String[paths.length];
                Arrays.fill(encodings, config.sourceEncoding.name());

                ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
                Map<String, String> options = JavaCore.getOptions();
                JavaCore.setComplianceOptions(config.sourceLevel, options);
                parser.setCompilerOptions(options);
                parser.setResolveBindings(true);
                parser.setBindingsRecovery(true);
                parser.setStatementsRecovery(true);
                // includeRunningVMBootclasspath=true。JDK の標準クラスを解析対象の
                // クラスパスに含める（実行JDKが変わると解析結果が変わるのはこのため）
                parser.setEnvironment(classpath, sourcepath, sourceEncodings, true);

                try {
                    parser.createASTs(paths, encodings, new String[0], new FileASTRequestor() {
                        @Override
                        public void acceptAST(String sourceFilePath, CompilationUnit ast) {
                            try {
                                collect(Paths.get(sourceFilePath), ast, order);
                            } catch (RuntimeException e) {
                                // 1ファイルの失敗で全体を止めない（落ちないこと > 完全であること）
                                parseErrors++;
                                say("警告: 解析に失敗したのでスキップします: " + sourceFilePath + " (" + e + ")");
                            }
                        }
                    }, null);
                } catch (RuntimeException e) {
                    parseErrors++;
                    say("警告: バッチの解析に失敗しました（" + batch.size() + " ファイル）: " + e);
                }
                say("  parse " + to + "/" + files.size());
            }
        }

        private void collect(Path file, CompilationUnit ast, int[] order) {
            String relative = relativize(file);
            ast.accept(new ASTVisitor() {

                /** いま解析しているメソッド（匿名クラス等で入れ子になるのでスタックで持つ） */
                private final Deque<Integer> current = new ArrayDeque<>();

                @Override
                public boolean visit(TypeDeclaration node) {
                    recordType(node.resolveBinding());
                    return true;
                }

                @Override
                public boolean visit(AnonymousClassDeclaration node) {
                    recordType(node.resolveBinding());
                    return true;
                }

                @Override
                public boolean visit(MethodDeclaration node) {
                    IMethodBinding b = node.resolveBinding();
                    if (b == null) {
                        current.push(-1);
                        return true;
                    }
                    int id = idOf(b);
                    M m = methods.get(id);
                    m.inSource = true;
                    m.file = relative;
                    m.line = ast.getLineNumber(node.getName().getStartPosition());
                    m.hasBody = node.getBody() != null;
                    m.sourceOrder = order[0]++;
                    int mods = b.getModifiers();
                    m.isStatic = Modifier.isStatic(mods);
                    m.isPrivate = Modifier.isPrivate(mods);
                    ITypeBinding decl = b.getDeclaringClass();
                    m.isFinal = Modifier.isFinal(mods)
                            || (decl != null && Modifier.isFinal(decl.getModifiers()));
                    if (decl != null) {
                        m.typeKind = kindOf(decl);
                        declaredByType
                                .computeIfAbsent(fqnOf(decl), k -> new LinkedHashMap<>())
                                .putIfAbsent(m.signature(), id);
                    }
                    current.push(id);
                    return true;
                }

                @Override
                public void endVisit(MethodDeclaration node) {
                    current.pop();
                }

                @Override
                public boolean visit(MethodInvocation node) {
                    IMethodBinding b = node.resolveMethodBinding();
                    if (b == null) {
                        recordUnresolved(node, node.getName().getIdentifier() + "(...)", "メソッドのバインディング解決に失敗");
                        return true;
                    }
                    addEdge(node, b, staticBound(b, false), false, recvKindOf(node.getExpression()));
                    return true;
                }

                @Override
                public boolean visit(SuperMethodInvocation node) {
                    IMethodBinding b = node.resolveMethodBinding();
                    if (b == null) {
                        recordUnresolved(node, "super." + node.getName().getIdentifier() + "(...)",
                                "メソッドのバインディング解決に失敗");
                        return true;
                    }
                    // super.m() は仮想ディスパッチされない（段0）
                    addEdge(node, b, true, false, "super");
                    return true;
                }

                @Override
                public boolean visit(ClassInstanceCreation node) {
                    IMethodBinding b = node.resolveConstructorBinding();
                    if (b == null) {
                        recordUnresolved(node, "new " + node.getType() + "(...)", "コンストラクタのバインディング解決に失敗");
                        return true;
                    }
                    // new そのものは行にしない。経路には積んで、コンストラクタ内の呼び出しは出す
                    addEdge(node, b, true, true, "new");
                    return true;
                }

                @Override
                public boolean visit(ConstructorInvocation node) {
                    IMethodBinding b = node.resolveConstructorBinding();
                    if (b != null) {
                        addEdge(node, b, true, true, "this()");
                    }
                    return true;
                }

                @Override
                public boolean visit(SuperConstructorInvocation node) {
                    IMethodBinding b = node.resolveConstructorBinding();
                    if (b != null) {
                        addEdge(node, b, true, true, "super()");
                    }
                    return true;
                }

                @Override
                public boolean visit(ExpressionMethodReference node) {
                    methodRef(node, node.resolveMethodBinding(), node.getName());
                    return true;
                }

                @Override
                public boolean visit(TypeMethodReference node) {
                    methodRef(node, node.resolveMethodBinding(), node.getName());
                    return true;
                }

                @Override
                public boolean visit(SuperMethodReference node) {
                    methodRef(node, node.resolveMethodBinding(), node.getName());
                    return true;
                }

                private void methodRef(ASTNode node, IMethodBinding b, SimpleName name) {
                    // メソッド参照（Foo::bar）も呼び出しとして出す。漏れないこと > 正確であること
                    if (b == null) {
                        recordUnresolved(node, "::" + name.getIdentifier(), "メソッド参照のバインディング解決に失敗");
                        return;
                    }
                    addEdge(node, b, staticBound(b, false), b.isConstructor(), "メソッド参照");
                }

                private void addEdge(ASTNode node, IMethodBinding b, boolean staticBound,
                        boolean isNew, String recvKind) {
                    Integer caller = current.peek();
                    if (caller == null || caller < 0) {
                        // フィールド初期化子・static初期化子の中。呼び出し元メソッドが無いので落とす
                        return;
                    }
                    int callee = idOf(b.getMethodDeclaration());
                    int line = ast.getLineNumber(node.getStartPosition());
                    edges.computeIfAbsent(caller, k -> new ArrayList<>())
                            .add(new Edge(caller, callee, line, staticBound, isNew, recvKind));
                }

                private void recordUnresolved(ASTNode node, String text, String cause) {
                    Integer caller = current.peek();
                    M m = (caller != null && caller >= 0) ? methods.get(caller) : null;
                    int line = ast.getLineNumber(node.getStartPosition());
                    unresolved.add(new Unresolved(
                            m != null ? m.typeBinary : "(不明)",
                            m != null ? m.name : "(不明)",
                            file.getFileName().toString(),
                            line,
                            text,
                            cause,
                            relative + ":" + String.format("%09d", Math.max(line, 0))));
                }
            });
        }

        /** 型の上下関係を控える。呼び出し先の具象クラスを探すときに使う */
        private void recordType(ITypeBinding type) {
            if (type == null) {
                return;
            }
            String fqn = fqnOf(type);
            ITypeBinding superclass = type.getSuperclass();
            if (superclass != null) {
                subtypes.computeIfAbsent(fqnOf(superclass), k -> new LinkedHashSet<>()).add(fqn);
            }
            for (ITypeBinding itf : type.getInterfaces()) {
                subtypes.computeIfAbsent(fqnOf(itf), k -> new LinkedHashSet<>()).add(fqn);
            }
        }

        /** バインディングからメソッドIDを引く（無ければ作る）。ライブラリ側のメソッドも作る */
        private int idOf(IMethodBinding raw) {
            IMethodBinding b = raw.getMethodDeclaration();
            String key = b.getKey();
            Integer known = methodIds.get(key);
            if (known != null) {
                return known;
            }
            ITypeBinding decl = b.getDeclaringClass();
            String typeFqn = (decl != null) ? fqnOf(decl) : "(不明)";
            String typeBinary = (decl != null) ? binaryOf(decl) : "(不明)";
            String name = b.isConstructor() ? "<init>" : b.getName();

            StringBuilder shortParams = new StringBuilder();
            StringBuilder erasedParams = new StringBuilder();
            ITypeBinding[] ps = b.getParameterTypes();
            for (int i = 0; i < ps.length; i++) {
                if (i > 0) {
                    shortParams.append(", ");
                    erasedParams.append(",");
                }
                ITypeBinding e = ps[i].getErasure();
                shortParams.append(simpleOf(fqnOf(e)));
                erasedParams.append(fqnOf(e));
            }

            int id = methods.size();
            M m = new M(id, key, typeBinary, typeFqn, name, shortParams.toString(), erasedParams.toString());
            if (decl != null) {
                m.typeKind = kindOf(decl);
                m.isStatic = Modifier.isStatic(b.getModifiers());
                m.isPrivate = Modifier.isPrivate(b.getModifiers());
                m.isFinal = Modifier.isFinal(b.getModifiers()) || Modifier.isFinal(decl.getModifiers());
            }
            methods.add(m);
            methodIds.put(key, id);
            return id;
        }

        // -------------------------------------------------------------------
        // フェーズ2: 具象クラスの解決（段の並び。先に確定した段で打ち切る）
        // -------------------------------------------------------------------

        /**
         * 呼び出し先を具象クラスへ解決する。
         *
         * <pre>
         *   段0 STATIC_BOUND  private / static / final / コンストラクタ / super … 仮想ディスパッチされない
         *   段1 NO_OVERRIDE / SINGLE_IMPL / NO_IMPL … 候補が1つに定まる
         *   段2 CHA          候補が複数のまま。候補を1件ずつ行にして、先へは降りない
         * </pre>
         *
         * 「宣言型が具象クラスだから確定」は誤り（{@code Base b = new Derived(); b.m();}）なので、
         * 判定軸は常に「静的束縛か仮想呼び出しか」。
         */
        private int[] resolve(Edge e) {
            long cacheKey = ((long) e.calleeId << 1) | (e.staticBound ? 1L : 0L);
            int[] cached = resolveCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
            int[] result = resolveUncached(e);
            resolveCache.put(cacheKey, result);
            return result;
        }

        private int[] resolveUncached(Edge e) {
            M declared = methods.get(e.calleeId);
            if (e.staticBound || declared.isStatic || declared.isPrivate
                    || declared.isFinal || declared.isCtor) {
                return new int[] {e.calleeId}; // 段0
            }
            List<Integer> candidates = new ArrayList<>();
            if (declared.hasBody) {
                candidates.add(declared.id);
            }
            for (String sub : allSubtypes(declared.typeFqn)) {
                Map<String, Integer> declaredThere = declaredByType.get(sub);
                if (declaredThere == null) {
                    continue;
                }
                Integer override = declaredThere.get(declared.signature());
                if (override != null && methods.get(override).hasBody && !candidates.contains(override)) {
                    candidates.add(override);
                }
            }
            if (candidates.isEmpty()) {
                // 本体を持つ候補が皆無。行としては宣言型のまま出し、先へは降りない
                return new int[] {e.calleeId};
            }
            int[] out = new int[candidates.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = candidates.get(i);
            }
            return out;
        }

        /** 下位型を推移的に集める（決定的な順序にするため LinkedHashSet） */
        private final Map<String, List<String>> subtypeCache = new HashMap<>();

        private List<String> allSubtypes(String typeFqn) {
            List<String> cached = subtypeCache.get(typeFqn);
            if (cached != null) {
                return cached;
            }
            List<String> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            Deque<String> queue = new ArrayDeque<>();
            queue.add(typeFqn);
            while (!queue.isEmpty()) {
                Set<String> direct = subtypes.get(queue.poll());
                if (direct == null) {
                    continue;
                }
                List<String> sorted = new ArrayList<>(direct);
                sorted.sort(Comparator.naturalOrder()); // 完全修飾クラス名順（環境非依存）
                for (String s : sorted) {
                    if (seen.add(s)) {
                        out.add(s);
                        queue.add(s);
                    }
                }
            }
            subtypeCache.put(typeFqn, out);
            return out;
        }

        /** 具象クラスに解決した後の被呼び出し数。宣言型で数えると実装がすべて0になる */
        private int[] computeInDegrees() {
            int[] in = new int[methods.size()];
            for (List<Edge> list : edges.values()) {
                for (Edge e : list) {
                    for (int t : resolve(e)) {
                        in[t]++;
                    }
                }
            }
            return in;
        }

        /** 起点。指定が無ければ「解決後の呼び出し元が0で、ソースに本体があるメソッド」全部 */
        private List<Integer> entryPoints(int[] inDegree) {
            List<M> picked = new ArrayList<>();
            for (M m : methods) {
                if (!m.inSource || !m.hasBody) {
                    continue;
                }
                if (config.entryPatterns.isEmpty()) {
                    if (inDegree[m.id] == 0) {
                        picked.add(m);
                    }
                } else if (config.entryPatterns.stream().anyMatch(p -> p.matches(m.typeFqn, m.name))) {
                    picked.add(m);
                }
            }
            picked.sort(Comparator.comparingInt(m -> m.sourceOrder));
            List<Integer> ids = new ArrayList<>(picked.size());
            for (M m : picked) {
                ids.add(m.id);
            }
            return ids;
        }

        // -------------------------------------------------------------------
        // フェーズ3: 出力（深さ優先で1行ずつ。ツリーは組み立てない）
        // -------------------------------------------------------------------

        private void writeCallHierarchy(Path out, List<Integer> entries, Set<Integer> reachable)
                throws IOException {
            try (BufferedWriter w = writer(out)) {
                w.write("caller,callee,root,call-hierarchy");
                w.newLine();
                for (int entry : entries) {
                    reachable.add(entry);
                    List<Integer> path = new ArrayList<>();
                    path.add(entry);
                    walk(w, entry, methods.get(entry), path, 0, reachable);
                }
                writeUnresolvedRows(w);
            }
        }

        /**
         * 深さ優先。ヒープに載るのは現在の経路（path）だけ。
         * 循環は経路単位で見る（グローバルな訪問済み集合は持たない。ダイヤモンド型の依存を潰すと
         * 影響調査に使えなくなる）。
         */
        private void walk(BufferedWriter w, int callerId, M root, List<Integer> path, int depth,
                Set<Integer> reachable) throws IOException {
            List<Edge> list = edges.get(callerId);
            if (list == null) {
                return;
            }
            for (Edge e : list) {
                if (rows >= config.maxRows) {
                    return;
                }
                int[] targets = resolve(e);
                boolean cha = targets.length > 1;
                for (int t : targets) {
                    M callee = methods.get(t);
                    reachable.add(t);

                    if (isExcluded(callee)) {
                        // 除外された呼び出し先は行にしないが、その先は親に繋ぎ直して辿り続ける
                        if (callee.inSource && callee.hasBody && !path.contains(t) && depth < config.maxDepth) {
                            path.add(t);
                            walk(w, t, root, path, depth + 1, reachable);
                            path.remove(path.size() - 1);
                        }
                        continue;
                    }

                    boolean cycle = path.contains(t);
                    boolean tooDeep = depth + 1 >= config.maxDepth;
                    String note = null;
                    if (cycle) {
                        // 注記は本体（src/jche）と同じタグを使う。読み手が同じ grep を書ける
                        note = "[UNEXPANDED:CYCLE] returns to a method already on this path";
                        cycles++;
                    } else if (tooDeep) {
                        note = "[UNEXPANDED:DEPTH] depth limit (" + config.maxDepth + ") reached";
                        choppedByDepth++;
                    } else if (cha) {
                        note = "[UNEXPANDED:CHA] " + targets.length + " candidates: " + e.recvKind;
                        chaRows++;
                    } else if (!callee.hasBody) {
                        note = "[UNEXPANDED:NO_IMPL] no implementation with a body in the source";
                    }

                    path.add(t);
                    if (!e.isNew) {
                        // new そのものは行にしない（「newしたこと」より「その中で何を呼ぶか」）
                        writeRow(w, methods.get(callerId), e, callee, root, path, note);
                    }
                    if (!cycle && !tooDeep && !cha && callee.inSource && callee.hasBody) {
                        walk(w, t, root, path, depth + 1, reachable);
                    }
                    path.remove(path.size() - 1);
                }
            }
        }

        private void writeRow(BufferedWriter w, M caller, Edge e, M callee, M root,
                List<Integer> path, String note) throws IOException {
            StringBuilder sb = new StringBuilder();
            sb.append(esc("at " + caller.typeBinary + "." + caller.name
                    + "(" + fileNameOf(caller) + ":" + e.line + ")"));
            sb.append(',').append(esc(callee.callee()));
            sb.append(',').append(esc(root.shortLabel()));
            // call-hierarchy は起点の次から現ノードまで。可変長で必ず最終列
            for (int i = 1; i < path.size(); i++) {
                sb.append(',').append(esc(methods.get(path.get(i)).shortLabel()));
            }
            if (note != null) {
                sb.append(',').append(esc(note)); // 注記は列を増やさず最後の要素として足す
            }
            w.write(sb.toString());
            w.newLine();
            rows++;
        }

        /** 型解決に失敗した呼び出しも行として足す。root を (型解決失敗) にして区別する */
        private void writeUnresolvedRows(BufferedWriter w) throws IOException {
            List<Unresolved> sorted = new ArrayList<>(unresolved);
            sorted.sort(Comparator.comparing((Unresolved u) -> u.sortKey));
            for (Unresolved u : sorted) {
                w.write(esc("at " + u.callerBinary + "." + u.callerName + "(" + u.fileName + ":" + u.line + ")")
                        + "," + esc(u.text)
                        + "," + esc("(unresolved)")
                        + "," + esc(u.cause));
                w.newLine();
                rows++;
            }
        }

        private void writeMethods(Path out, int[] inDegree, Set<Integer> reachable) throws IOException {
            List<M> sorted = new ArrayList<>();
            for (M m : methods) {
                if (m.inSource && !m.isCtor) {
                    sorted.add(m);
                }
            }
            sorted.sort(Comparator.comparingInt(m -> m.sourceOrder));

            try (BufferedWriter w = writer(out)) {
                w.write("method,declaringType,typeKind,file,line,hasBody,inDegree,outDegree,role,reachable");
                w.newLine();
                for (M m : sorted) {
                    int outDegree = edges.getOrDefault(m.id, List.of()).size();
                    int in = inDegree[m.id];
                    String role = (in == 0 && outDegree == 0) ? "ISOLATED"
                            : (in == 0) ? "ENTRY_CANDIDATE"
                            : (outDegree == 0) ? "LEAF"
                            : "NORMAL";
                    w.write(esc(m.typeSimple + "." + m.name + "(" + m.paramsShort + ")")
                            + "," + esc(m.typeFqn)
                            + "," + m.typeKind
                            + "," + esc(m.file)
                            + "," + m.line
                            + "," + (m.hasBody ? 1 : 0)
                            + "," + in
                            + "," + outDegree
                            + "," + role
                            + "," + (reachable.contains(m.id) ? 1 : 0));
                    w.newLine();
                }
            }
        }

        /** 除外された呼び出し先は行にしない（既定 java.** / javax.**） */
        private boolean isExcluded(M callee) {
            for (Pattern p : config.excludes) {
                if (p.matches(callee.typeFqn, callee.name)) {
                    return true;
                }
            }
            return false;
        }

        // -------------------------------------------------------------------
        // 集計とログ
        // -------------------------------------------------------------------

        private void report() {
            say("出力行数: " + rows);
            say("注記の内訳: [CYCLE] " + cycles + " / 深さ上限 " + choppedByDepth + " / CHA未展開 " + chaRows);
            say("型解決に失敗した呼び出し: " + unresolved.size() + " 件");
            if (!unresolved.isEmpty()) {
                say("  → 依存jar（library.folders）の指定漏れが疑われます。"
                        + "call-hierarchy.csv の root 列が「(unresolved)」の行と同数です。");
                Map<String, Integer> byCause = new LinkedHashMap<>();
                for (Unresolved u : unresolved) {
                    byCause.merge(u.cause, 1, Integer::sum);
                }
                byCause.forEach((k, v) -> say("  " + k + ": " + v + " 件"));
            }
            if (parseErrors > 0) {
                say("解析をスキップしたファイル/バッチ: " + parseErrors + " 件");
            }
            if (rows >= config.maxRows) {
                say("警告: 行数上限（max.rows=" + config.maxRows + "）に達したため打ち切りました。");
            }
        }

        private void say(String line) {
            logLines.add(line);
            System.out.println(line);
        }

        private void writeLog(Path path) throws IOException {
            Files.write(path, logLines, StandardCharsets.UTF_8);
        }

        private String relativize(Path file) {
            Path abs = file.toAbsolutePath().normalize();
            if (abs.startsWith(config.projectRoot)) {
                return config.projectRoot.relativize(abs).toString().replace('\\', '/');
            }
            return abs.toString().replace('\\', '/');
        }

        private static String fileNameOf(M m) {
            if (m.file.isEmpty()) {
                return "Unknown Source";
            }
            int slash = m.file.lastIndexOf('/');
            return (slash < 0) ? m.file : m.file.substring(slash + 1);
        }
    }

    // =======================================================================
    // 小物
    // =======================================================================

    /** 内部クラスを Outer.Inner 形式で（callee 列・フィルタ用） */
    private static String fqnOf(ITypeBinding type) {
        ITypeBinding erasure = type.getErasure();
        String name = erasure.getQualifiedName();
        if (name == null || name.isEmpty()) {
            // 匿名クラス・ローカルクラスは修飾名を持たない。バイナリ名で代用する
            String binary = erasure.getBinaryName();
            return (binary != null) ? binary : String.valueOf(erasure.getName());
        }
        return name;
    }

    /** 内部クラスを Outer$Inner 形式で（caller 列のスタックトレース用） */
    private static String binaryOf(ITypeBinding type) {
        ITypeBinding erasure = type.getErasure();
        String binary = erasure.getBinaryName();
        return (binary != null) ? binary : fqnOf(erasure);
    }

    private static char kindOf(ITypeBinding type) {
        if (type.isInterface()) {
            return 'I';
        }
        return Modifier.isAbstract(type.getModifiers()) ? 'A' : 'C';
    }

    /** private / static / final は仮想ディスパッチされない（段0） */
    private static boolean staticBound(IMethodBinding b, boolean superCall) {
        if (superCall || b.isConstructor()) {
            return true;
        }
        int mods = b.getModifiers();
        if (Modifier.isPrivate(mods) || Modifier.isStatic(mods) || Modifier.isFinal(mods)) {
            return true;
        }
        ITypeBinding decl = b.getDeclaringClass();
        return decl != null && Modifier.isFinal(decl.getModifiers());
    }

    /** 候補を絞れなかった理由を、レシーバの由来で分類する（次にどの解析を足せば効くかの手掛かり） */
    private static String recvKindOf(Expression expr) {
        if (expr == null || expr instanceof ThisExpression) {
            return "this";
        }
        if (expr instanceof MethodInvocation) {
            return "return value";
        }
        if (expr instanceof ClassInstanceCreation) {
            return "new";
        }
        if (expr instanceof FieldAccess) {
            return "field";
        }
        if (expr instanceof Name name) {
            IBinding b = name.resolveBinding();
            if (b instanceof IVariableBinding v) {
                if (v.isField()) {
                    return "field";
                }
                return v.isParameter() ? "parameter" : "local variable";
            }
            if (b instanceof ITypeBinding) {
                return "type name";
            }
            if (name instanceof QualifiedName) {
                return "field";
            }
        }
        return "other";
    }

    private static String simpleOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return (dot < 0) ? fqn : fqn.substring(dot + 1);
    }

    /**
     * CSVエスケープ。カンマ・ダブルクォート・改行のいずれかを含むならダブルクォートで囲む。
     */
    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    /**
     * BOM付きUTF-8のライタ。Excelでダブルクリックして開けるようにする。
     * 変換できない文字は例外にせず {@code ?} に置き換える（落ちないこと > 完全であること）。
     */
    private static BufferedWriter writer(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        CharsetEncoder enc = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        OutputStream os = Files.newOutputStream(path);
        os.write(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        return new BufferedWriter(new OutputStreamWriter(os, enc));
    }

    private static void log(String s) {
        System.out.println(s);
    }
}
