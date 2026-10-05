// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.config;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.eclipse.jdt.core.JavaCore;

import jche.util.Messages;
import jche.util.UserHome;
import jche.util.Warnings;

/**
 * 設定ファイル（jche.properties）の読み込み。
 *
 * 設定できる項目とその意味は config/jche.properties（同梱の既定の設定ファイル）にコメント付きでまとめてある。
 * ファイルの読み方（項目=値。バックスラッシュはそのまま）は {@link ConfigFile}。
 * あちらを唯一の一覧として扱い、ここには複製しない（二重管理で片方が古くなるのを避けるため）。
 *
 * 相対パスの起点は項目ごとに異なる。
 * <ul>
 *   <li>project.root / library.jars / library.repositories / output.folder / cache.folder / call.rules.files /
 *       plugin.folders … この設定ファイルが置かれているディレクトリ（下の 3 項目以外はすべてこちら）</li>
 *   <li>source.folders / library.folders / external.library.folders … project.root</li>
 * </ul>
 * 設定ファイルと関連ファイルをひとまとめに配置でき、どこから実行しても同じ結果になる。
 *
 * 出力は output.folder（既定は {@code .} ＝設定ファイルと同じディレクトリ）の下に
 * 実行ごとのフォルダ（{@code <解析開始日時>_<project.root のフォルダ名>}）を
 * 作って書く（{@link #outputDir}）。CSV のファイル名は固定で、設定ファイルの複製と実行ログも同じフォルダに入る。
 * キャッシュは出力フォルダには置かず、解析対象プロジェクトごとのサイドカーとして
 * このツールのプロジェクトフォルダ内（{@code <ツールのフォルダ>/.cache/<プロジェクト名>_<パスのハッシュ>/}）に置く。
 * cache.folder を指定すると、その下に同じ形のプロジェクト別フォルダを作る。
 *
 * 相対パスは起点ディレクトリの配下だけを指せる（{@code ..} で外へ出る指定は拒否する）。
 * project.root だけは起点そのものなので制限しない。source.folders は絶対パスでも project.root の
 * 配下でなければならない。キャッシュのキーと出力の file 列を project.root からの相対パスにするため。
 */
public final class Config {

    /** CHA候補を呼び出し階層で展開する際の候補数の上限 */
    public static final int CHA_MAX_CANDIDATES = 20;
    /**
     * キャッシュフォルダ内に置く、解析結果のキャッシュの名前（構造とバインディングと値の事実。
     * ソースファイルごとのブロックに全部入る。{@code jche.cache.CacheFormat}）
     */
    public static final String CACHE_FILE_NAME = "analysis-cache.tsv";
    /**
     * 以前の形式（キャッシュが 2 ファイルだった版）が値の事実を置いていたファイルの名前。
     * 今は読みも書きもしない。残っていれば {@code jche.analysis.CacheUpdater} が一時ファイルごと消す
     * （{@code docs/cache-unification-qa.md}）
     */
    public static final String LEGACY_DATAFLOW_CACHE_FILE_NAME = "dataflow-cache.tsv";
    /** cache.folder が空欄のときの置き場所（このツールのプロジェクトフォルダからの相対） */
    public static final String DEFAULT_CACHE_DIR_NAME = ".cache";
    /** 出力フォルダ内のファイル名（固定） */
    public static final String CALL_HIERARCHY_CSV_NAME = "call-hierarchy.csv";
    public static final String METHODS_CSV_NAME = "methods.csv";
    /** conditions.target を指定したときだけ追加で出す、条件の一覧 */
    public static final String CALL_CONDITIONS_CSV_NAME = "call-conditions.csv";
    /** 絞れなかった呼び出しから作るライブラリ呼び出し規則のひな形。貼る先が UTF-8 固定なので、これも UTF-8 で書く */
    public static final String RULES_SUGGESTED_NAME = "call-rules-suggested.txt";
    /** 出力フォルダに残す実行ログ（標準出力と同じ内容、UTF-8） */
    public static final String LOG_FILE_NAME = "run.log";
    /** 出力フォルダ名の日時の書式 */
    private static final DateTimeFormatter FOLDER_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * 設定ファイル（絶対パス）。設定をメモリ上で組み立てた場合は null
     * （Eclipse プラグインがプロジェクトの構成から作る場合。{@link #Config(Properties, Path, Path, LocalDateTime)}）
     */
    public final Path configPath;
    public final Path configDir;
    public final Path projectRoot;
    /** 解析対象プロジェクトの名前（project.root のフォルダ名）。出力フォルダ名とキャッシュフォルダ名に使う */
    public final String projectName;
    /** この設定の解析開始日時（出力フォルダ名に使う） */
    public final LocalDateTime startedAt;
    /** ソースフォルダ（project.root からの相対）。空欄なら .classpath の kind="src" を使う */
    public final List<Path> sourceFolders;
    /**
     * 依存jarを集めたフォルダ（project.root からの相対）。直下の *.jar に展開する。
     * .classpath の kind="lib"（jar かクラスフォルダの 1 件ずつ。展開しない）があれば合算する
     */
    public final List<Path> libraryFolders;
    /**
     * 依存 jar（またはクラスフォルダ）を1件ずつ指定するもの（library.jars）。フォルダ単位で書けない構成のための
     * 逃げ道で、Eclipse プラグインが IJavaProject の解決済みクラスパス（依存プロジェクトの出力フォルダを含む）を
     * 渡すのに使う。フォルダはクラスフォルダとしてそのまま JDT に渡し、中の jar には展開しない
     * （{@link ProjectLayout#classpathArray}）。置き場所はどこでもよい（~/.m2 の下など、プロジェクトの外が普通）
     */
    public final List<Path> libraryJars;
    /**
     * library.folders が空欄のときに読むビルドファイルの種類。
     * "auto"（pom.xml / build.gradle から検出）/ "maven" / "gradle" / "none"（自動取得しない）
     */
    public final String libraryBuildTool;
    /**
     * ローカルリポジトリ（ダウンロード済みの jar と POM の置き場所）。空なら ~/.m2/repository と
     * ~/.gradle/caches/modules-2/files-2.1 の既定。相対パスは設定ファイルのフォルダ起点で、外を指してもよい
     */
    public final List<Path> libraryRepositories;
    public final String sourceEncoding;
    /** source.encoding が空欄で、project.root から決めたか */
    public final boolean sourceEncodingAuto;
    /** 実際に効いた解析対象ソースのJavaバージョン（JDTから読み戻した値） */
    public final String sourceLevel;
    /** 設定ファイルで要求された値。未指定なら空 */
    public final String sourceLevelRequested;
    /** source.level が未指定で、JDTが対応する最大値を採用したか */
    public final boolean sourceLevelAuto;
    /** 解析に使うJDTのコンパイラ設定（source.level を反映済み） */
    public final Map<String, String> compilerOptions;

    public final List<PackagePattern> entryPatterns;
    public final List<PackagePattern> excludePatterns;

    /** 全体モードか（entry.packages 未指定） */
    public final boolean wholeProjectMode;

    public final int maxDepth;
    /** 出力行数の上限（0以下で無制限） */
    public final long maxRows;

    /** 拡張の init() に渡す。プロジェクト固有のキーを自由に読ませるため */
    public final Properties raw;
    /**
     * 読まれない項目（このクラスが読まず、拡張の接頭辞でもない）→ 綴りの近い項目名（無ければ空）。
     * 構築では警告せず（warnings.txt を集め始める前なので）、{@link #warnUnknownKeys} で出す
     */
    public final Map<String, String> unknownKeys;
    public final List<String> candidateProviderClasses;
    /** ライブラリ呼び出し規則のファイル（call.rules.files。設定ファイルのフォルダからの相対） */
    public final List<Path> ruleFiles;
    /** 規則を返す拡張のクラス（call.rules.providers） */
    public final List<String> ruleProviderClasses;
    /** 同梱のライブラリ呼び出し規則を使うか（call.rules.builtin。既定 true） */
    public final boolean builtinRules;
    /**
     * 拡張クラスの置き場所（設定ファイルのフォルダからの相対）。.java / .class / .jar を置く。
     *
     * <p>ここに置く拡張はグラフを組むときにだけ動き、キャッシュには何も書かない。
     * そのため<b>拡張を足しても外してもキャッシュは捨てられない</b>
     * （ライブラリ呼び出し規則と同じ性質。docs/instance-analysis-plugin-qa.md の Q28）
     */
    public final List<Path> pluginFolders;

    public final boolean cacheEnabled;
    /** データフロー解析（ファクトリの戻り値・引数から具象クラスを特定）を使うか */
    public final boolean dataflowEnabled;
    /** ファクトリの委譲（return create();）を何段まで辿るか */
    public final int dataflowMaxDepth;
    /** DIコンテナ（Spring）のBean定義で候補を絞るか */
    public final boolean springDiEnabled;
    /** 追加でBean登録の印とみなす注釈（独自のステレオタイプ注釈。FQNでも単純名でもよい） */
    public final List<String> springDiAnnotations;
    /**
     * 条件分岐の静的解析で「その経路では呼ばれない」呼び出しの先を辿らないか。
     *
     * 打ち切った呼び出し自体は理由付きで1行出力する（呼び出しが書かれている事実は消さない）。
     * 経路ごとの引数の値は dataflow.enabled の仕組みで運ぶ。dataflow.enabled=false のときは
     * 条件の表（G 行）と呼び出し箇所の条件の列を読まないので、コンパイル時定数の条件も含めて
     * どの条件も判定せず、この設定は効かない（打ち切りは起きない）。
     */
    public final boolean branchPruningEnabled;
    /**
     * 呼び出しに効いている条件を調べる対象（{@code conditions.target}）。空欄なら調べない。
     *
     * 指定しても通常の解析（キャッシュの更新と CSV の出力）はそのまま行い、
     * <b>そのうえで追加で</b>条件の一覧（{@link #conditionsCsv}）を書く。
     * モードを増やさず、出力が1つ増えるだけにするための決まり。
     * 指定できる形は {@code src/foo/Bar.java} / {@code src/foo/Bar.java:120} /
     * {@code foo.Bar} / {@code foo.Bar#method}（{@code docs/call-conditions.md}）。
     */
    public final String conditionsTarget;
    /** この解析対象プロジェクトのキャッシュフォルダ（プロジェクト別のサイドカー） */
    public final Path cacheDir;
    /** 解析結果のキャッシュ（{@link #CACHE_FILE_NAME}） */
    public final Path cacheFile;

    /** 他チームのjar（自分のコードを呼んでいる側）。ファイルでもディレクトリでも可 */
    public final List<Path> externalLibraryFolders;

    /**
     * 一緒に解析するワークスペースの他のプロジェクト（{@code workspace.projects}）。各要素はそのプロジェクトの
     * 設定ファイルか、プロジェクトのフォルダ（設定ファイルのフォルダからの相対パス、または絶対パス。project.root の外でよい）。
     * 相手は<b>相手自身の設定（クラスパス・文字コード）で別々に解析</b>され、そのキャッシュをこの実行のグラフに
     * 名前で結合する（{@link #forWorkspaceProject}。docs/workspace-callers-design.md）
     */
    public final List<Path> workspaceProjects;
    /**
     * {@code workspace.scope=callers}（既定）か。真なら、他のプロジェクトのメソッドは project.root のメソッドに届く経路の
     * 上にあるものだけを CSV に出す。偽（{@code all}）なら絞らない
     */
    public final boolean workspaceScopeCallers;
    /** このツールのプロジェクトフォルダ（cache.folder が空欄のときのキャッシュの置き場所の親）。相手の設定を組むのに要る */
    public final Path toolRoot;
    /** cache.folder に書かれた置き場所（絶対パス）。空欄なら null（{@link #toolRoot} の .cache/） */
    private final Path cacheBase;

    /** output.folder（実行ごとのフォルダの親） */
    public final Path outputFolder;
    /** この実行の出力フォルダ。CSV・設定ファイルの複製・実行ログをここに置く */
    public final Path outputDir;
    public final Path outputCsv;
    public final Path methodsCsv;
    /** conditions.target を指定したときだけ書く、条件の一覧（通常の出力に追加する） */
    public final Path conditionsCsv;
    /** 絞れなかった呼び出しから作るライブラリ呼び出し規則のひな形（{@link #RULES_SUGGESTED_NAME}） */
    public final Path rulesSuggestedFile;
    public final Path logFile;

    /** CSVの出力文字コード。既定はUTF-8-BOM（Excelでそのまま開ける） */
    public final Charset outputEncoding;
    /** output.encoding=UTF-8-BOM のとき、ファイル先頭にBOMを書くか */
    public final boolean outputBom;

    /**
     * 設定ファイルに書かれた表示言語（{@code message.language}）。未指定なら空。
     *
     * <p>実際に何語で出るかは {@link Messages#language()} が持つ。環境変数 {@code JCHE_LANG} と
     * システムプロパティ {@code jche.lang} のほうが強いので、この値がそのまま使われるとは限らない
     * （{@code docs/nls-qa.md}）。CSV の中身は言語によらず英語で固定なので、ここは影響しない
     */
    public final String messageLanguage;

    /**
     * @param configPath 設定ファイル
     * @param toolRoot   このツールのプロジェクトフォルダ（cache.folder が空欄のときのキャッシュの置き場所）
     * @param startedAt  解析開始日時（出力フォルダ名に使う）
     */
    public Config(Path configPath, Path toolRoot, LocalDateTime startedAt) throws IOException {
        this(load(configPath), configPath.toAbsolutePath().normalize(), null, toolRoot, startedAt);
    }

    /**
     * 設定ファイルを介さず、メモリ上の設定から作る。
     *
     * <p>Eclipse プラグインが、開いているプロジェクトの構成（ソースフォルダ・クラスパス・文字コード・
     * コンパイラー準拠レベル）から設定を組み立てて渡すために使う。利用者に
     * jche.properties を書かせずに解析できるようにするのが目的で、項目の意味は
     * 設定ファイルで書いたときとまったく同じ。
     *
     * @param properties 設定。キーと値は jche.properties と同じ
     * @param configDir  相対パスの起点（設定ファイルを置いたフォルダに相当。ふつうはプロジェクトの場所）
     * @param toolRoot   cache.folder が空欄のときのキャッシュの置き場所の親
     * @param startedAt  解析開始日時（出力フォルダ名に使う）
     */
    public Config(Properties properties, Path configDir, Path toolRoot, LocalDateTime startedAt)
            throws IOException {
        this(properties, null, configDir, toolRoot, startedAt);
    }

    private static Properties load(Path configPath) throws IOException {
        Path abs = configPath.toAbsolutePath().normalize();
        try {
            // Properties#load ではなく自前の読み方（バックスラッシュをそのまま読む。ConfigFile）
            return ConfigFile.read(abs);
        } catch (ConfigFile.SyntaxException e) {
            // 何行目の何が悪いのかを、表示言語で言う
            String message = (e.problem() == ConfigFile.Problem.BAD_KEY)
                    ? Messages.format("config.line.badKey", abs, e.lineNumber(), e.lineText())
                    : Messages.format("config.line.noSeparator", abs, e.lineNumber(), e.lineText());
            throw new IOException(message, e);
        } catch (MalformedInputException e) {
            // UTF-8 でないファイル（Shift_JIS で保存した等）。JDK の文言は "Input length = 1" だけで何をすればよいか分からない
            throw new IOException(Messages.format("config.read.notUtf8", abs), e);
        } catch (IOException e) {
            throw new IOException(Messages.format("config.read.failed", abs, e.getMessage()), e);
        }
    }

    /**
     * このクラスが読む項目名。config/jche.properties（唯一の一覧）に並ぶ項目と同じで、
     * 読む項目を足したらここにも足す。ここに無い項目は読まれないので、綴りの誤りを黙って無視しないよう
     * {@link #warnUnknownKeys} が知らせる（{@code docs/config-file-format-qa.md} の Q11）
     */
    static final Set<String> KNOWN_KEYS = Set.of(
            "project.root", "source.folders", "library.folders", "library.jars", "library.build.tool",
            "library.repositories", "source.encoding", "external.library.folders", "source.level",
            "entry.packages", "exclude.packages", "cache.enabled", "cache.folder", "max.depth", "max.rows",
            "dataflow.enabled", "dataflow.max.depth", "spring.di.enabled", "spring.di.bean.annotations",
            "plugin.folders", "resolver.candidate.providers", "call.rules.files", "call.rules.providers",
            "call.rules.builtin", "workspace.projects", "workspace.scope", "branch.pruning.enabled",
            "conditions.target", "output.encoding", "output.folder", "message.language");

    /**
     * 拡張が読む項目の接頭辞（同梱の {@code jche.builtin.TypeMappingProvider} の {@code plugin.mapping.*} など）。
     * このツールは読まないが知らない項目でもないので、警告しない
     */
    private static final String EXTENSION_KEY_PREFIX = "plugin.";

    /**
     * 読まれない項目（{@link #KNOWN_KEYS} に無く、拡張の接頭辞でもない）と、綴りの近い項目名（無ければ空）。
     * 設定に書かれた順ではなく名前順。
     *
     * <p>拡張（resolver.candidate.providers / call.rules.providers）を書いた設定では、拡張が {@code init(Properties, Path)} で
     * 独自の項目（{@code demo.di.file} など）を読むので、知らない項目をすべて警告すると正しい設定に警告が出る。
     * そのときは綴りの近い項目があるもの（誤りの疑いが濃いもの）だけに絞る
     */
    private static Map<String, String> unknownKeysOf(Properties p) {
        boolean extensions = !splitList(p.getProperty("resolver.candidate.providers", "")).isEmpty()
                || !splitList(p.getProperty("call.rules.providers", "")).isEmpty();
        List<String> keys = new ArrayList<>(p.stringPropertyNames());
        keys.sort(null);
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : keys) {
            if (KNOWN_KEYS.contains(key) || key.startsWith(EXTENSION_KEY_PREFIX)) {
                continue;
            }
            String nearest = nearestKnownKey(key);
            if (nearest == null && extensions) {
                continue;
            }
            out.put(key, (nearest == null) ? "" : nearest);
        }
        return out;
    }

    /** 綴りの近い項目名（編集距離が 2 以内。長い名前は 6 文字につき 1 まで許す）。無ければ null */
    private static String nearestKnownKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        int limit = Math.max(2, lower.length() / 6);
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String known : KNOWN_KEYS) {
            int d = editDistance(lower, known);
            if (d < bestDistance || (d == bestDistance && best != null && known.compareTo(best) < 0)) {
                best = known;
                bestDistance = d;
            }
        }
        return (bestDistance <= limit) ? best : null;
    }

    /** レーベンシュタイン距離（1 文字の挿入・削除・置換の最少回数） */
    static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int subst = prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                cur[j] = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), subst);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    /**
     * 読まれない項目（{@link #unknownKeys}）を警告する。実行は止めない（書き足した項目で解析が動かなくなるより、
     * 知らせて進むほうが穏当）。warnings.txt に載せるため、呼ぶ側は {@link Warnings#begin} のあとに呼ぶ
     */
    public void warnUnknownKeys() {
        for (Map.Entry<String, String> e : unknownKeys.entrySet()) {
            Warnings.warn(Warnings.Topic.CONFIG, Messages.format("config.unknownKey", e.getKey(),
                    e.getValue().isEmpty() ? "" : Messages.format("config.unknownKey.suggest", e.getValue())));
        }
    }

    private Config(Properties p, Path configPathOrNull, Path configDirHint, Path toolRoot,
                   LocalDateTime startedAt) throws IOException {
        this.configPath = configPathOrNull;
        Path dir = (configPathOrNull != null) ? configPathOrNull.getParent() : configDirHint;
        this.configDir = (dir == null) ? Paths.get(".").toAbsolutePath().normalize()
                : dir.toAbsolutePath().normalize();
        this.startedAt = startedAt;

        // 表示言語は、以降の検証が出すエラーの言語も決めるので真っ先に反映する。
        // 環境変数 JCHE_LANG / システムプロパティ jche.lang があればそちらが優先される
        // （Messages.applyConfigured は、その場合は何もしない）。空欄なら OS の言語に戻す。
        // 同じ JVM で前に読んだ設定（引数の前の設定・対話モードの前の実行・サーバーの前の ANALYZE）の言語を引き継がない
        this.messageLanguage = p.getProperty("message.language", "").trim();
        Messages.applyConfigured(this.messageLanguage);

        rejectRemovedKeys(p);
        this.unknownKeys = unknownKeysOf(p);

        // project.root は他の項目の起点そのものなので、設定ファイルのディレクトリの外を指してよい
        this.projectRoot = resolveFromConfigDir(require(p, "project.root"));
        this.projectName = projectNameOf(this.projectRoot);

        // source.folders / library.folders / external.library.folders は project.root からの相対
        this.sourceFolders = resolveAllUnderProject("source.folders",
                splitList(p.getProperty("source.folders", "")), true);
        this.libraryFolders = resolveAllUnderProject("library.folders",
                splitList(p.getProperty("library.folders", "")), false);
        // jar を1件ずつ指定する形。プロジェクトの外（~/.m2 等）を指すのが普通なので配下の制限は掛けない
        this.libraryJars = new ArrayList<>();
        for (String raw : splitList(p.getProperty("library.jars", ""))) {
            this.libraryJars.add(resolveFromConfigDir(UserHome.expand(raw)));
        }
        this.libraryBuildTool = buildToolOf(p.getProperty("library.build.tool", "auto"));
        this.libraryRepositories = repositoriesOf(p);

        // source.encoding が空欄なら project.root から決める（pom.xml の project.build.sourceEncoding、無ければ UTF-8）
        String enc = p.getProperty("source.encoding", "").trim();
        this.sourceEncodingAuto = enc.isEmpty();
        // 正規名にそろえる。"utf-8" と "UTF-8" のような表記の揺れでキャッシュの鍵が
        // 変わってしまうと、設定を変えていないのに全件解析し直しになる
        this.sourceEncoding = this.sourceEncodingAuto
                ? charsetOf(Messages.get("config.pomEncodingLabel"),
                        ProjectDetector.sourceEncoding(this.projectRoot)).name()
                : charsetOf("source.encoding", enc).name();
        this.sourceLevelRequested = p.getProperty("source.level", "").trim();
        this.sourceLevelAuto = this.sourceLevelRequested.isEmpty();
        this.compilerOptions = buildCompilerOptions(this.sourceLevelRequested);
        this.sourceLevel = this.compilerOptions.get(JavaCore.COMPILER_SOURCE);

        // entry.packages が空の場合は「全体モード」に入る。
        // 起点を指定せず、呼び出し元が無いメソッドを自動的に起点にする。
        this.entryPatterns = PackagePattern.parseAll(splitList(p.getProperty("entry.packages", "")));
        this.excludePatterns = PackagePattern.parseAll(splitList(p.getProperty("exclude.packages", "")));
        this.wholeProjectMode = this.entryPatterns.isEmpty();

        this.maxDepth = intOf(p, "max.depth", 50);
        this.maxRows = longOf(p, "max.rows", 5_000_000L);

        // 拡張は設定ファイルのひな形には載せていない。使う場合はこのキーを足せば読み込まれる
        this.candidateProviderClasses = splitList(p.getProperty("resolver.candidate.providers", ""));
        this.pluginFolders = pluginFoldersOf(p);
        this.ruleFiles = ruleFilesOf(p);
        this.ruleProviderClasses = splitList(p.getProperty("call.rules.providers", ""));
        this.builtinRules = Boolean.parseBoolean(p.getProperty("call.rules.builtin", "true").trim());
        this.raw = p;

        this.cacheEnabled = Boolean.parseBoolean(p.getProperty("cache.enabled", "true").trim());
        this.dataflowEnabled = Boolean.parseBoolean(p.getProperty("dataflow.enabled", "true").trim());
        this.dataflowMaxDepth = intOf(p, "dataflow.max.depth", 5);
        this.springDiEnabled = Boolean.parseBoolean(p.getProperty("spring.di.enabled", "true").trim());
        this.springDiAnnotations = splitList(p.getProperty("spring.di.bean.annotations", ""));
        this.branchPruningEnabled =
                Boolean.parseBoolean(p.getProperty("branch.pruning.enabled", "true").trim());
        this.conditionsTarget = p.getProperty("conditions.target", "").trim();
        this.toolRoot = toolRoot.toAbsolutePath().normalize();
        this.cacheBase = cacheBaseOf(p);
        this.cacheDir = cacheDirOf();
        this.cacheFile = this.cacheDir.resolve(CACHE_FILE_NAME);

        // 一緒に解析するワークスペースの他のプロジェクト。相手の設定ファイルかフォルダで、project.root と同じく
        // 設定ファイルのフォルダからの相対（配下の制限なし。プロジェクトの外を指すのが普通）
        this.workspaceProjects = new ArrayList<>();
        for (String raw : splitList(p.getProperty("workspace.projects", ""))) {
            this.workspaceProjects.add(resolveFromConfigDir(UserHome.expand(raw)));
        }
        String scope = p.getProperty("workspace.scope", "callers").trim();
        if (scope.isEmpty()) {
            scope = "callers";
        }
        if (!scope.equals("callers") && !scope.equals("all")) {
            throw new IllegalArgumentException(Messages.format("config.workspace.badScope", scope));
        }
        this.workspaceScopeCallers = scope.equals("callers");

        // 被参照スキャンの対象は「解析対象プロジェクトの外の世界」なので、
        // ソースや依存jarと同じく project.root からの相対で書けるようにする
        this.externalLibraryFolders = resolveAllUnderProject("external.library.folders",
                splitList(p.getProperty("external.library.folders", "")), false);

        // 出力は実行ごとのフォルダに分ける。フォルダ名に解析開始日時とプロジェクト名を入れ、
        // 「いつ・どのプロジェクトを解析した結果か」がフォルダ名だけで分かるようにする。
        // 既定は「.」＝設定ファイルと同じフォルダ。設定ファイルとその実行結果が 1 か所にまとまる
        this.outputFolder = resolveUnderConfigDir("output.folder", p.getProperty("output.folder", "."));
        this.outputDir = uniqueOutputDir(this.outputFolder,
                startedAt.format(FOLDER_TIMESTAMP) + "_" + projectName);
        this.outputCsv = this.outputDir.resolve(CALL_HIERARCHY_CSV_NAME);
        this.methodsCsv = this.outputDir.resolve(METHODS_CSV_NAME);
        this.conditionsCsv = this.outputDir.resolve(CALL_CONDITIONS_CSV_NAME);
        this.rulesSuggestedFile = this.outputDir.resolve(RULES_SUGGESTED_NAME);
        this.logFile = this.outputDir.resolve(LOG_FILE_NAME);

        String encRaw = p.getProperty("output.encoding", "UTF-8-BOM").trim();
        this.outputBom = "UTF-8-BOM".equalsIgnoreCase(encRaw);
        this.outputEncoding = this.outputBom ? StandardCharsets.UTF_8 : charsetOf("output.encoding", encRaw);
    }

    /** library.repositories。ローカルリポジトリは設定ファイルやプロジェクトの外にあるのが普通なので、配下の制限は掛けない */
    private List<Path> repositoriesOf(Properties p) {
        List<Path> out = new ArrayList<>();
        for (String raw : splitList(p.getProperty("library.repositories", ""))) {
            out.add(resolveFromConfigDir(UserHome.expand(raw)));
        }
        return out;
    }

    /** ライブラリ呼び出し規則のファイル。設定ファイルのフォルダからの相対パス（plugin.folders と同じ起点） */
    private List<Path> ruleFilesOf(Properties p) {
        List<Path> files = new ArrayList<>();
        for (String raw : splitList(p.getProperty("call.rules.files", ""))) {
            files.add(resolveUnderConfigDir("call.rules.files", raw));
        }
        return List.copyOf(files);
    }

    /** plugin.folders（設定ファイルのフォルダからの相対） */
    private List<Path> pluginFoldersOf(Properties p) {
        List<Path> folders = new ArrayList<>();
        for (String raw : splitList(p.getProperty("plugin.folders", ""))) {
            folders.add(resolveUnderConfigDir("plugin.folders", raw));
        }
        return List.copyOf(folders);
    }

    /**
     * キャッシュフォルダ。解析対象プロジェクトごとのサイドカーで、既定はこのツールのプロジェクトフォルダの .cache/ の下。
     * cache.folder を指定したときも、その下にプロジェクト別のフォルダを切る（複数の設定が同じプロジェクトを
     * 指すなら同じキャッシュを共有し、別のプロジェクトなら混ざらない）
     */
    private Path cacheDirOf() {
        Path base = (cacheBase != null) ? cacheBase : toolRoot.resolve(DEFAULT_CACHE_DIR_NAME);
        return base.resolve(projectName + "_" + shortHash(projectRoot.toString()));
    }

    /** cache.folder（設定ファイルのフォルダからの相対）。空欄なら null */
    private Path cacheBaseOf(Properties p) {
        String cacheFolderRaw = p.getProperty("cache.folder", "").trim();
        return cacheFolderRaw.isEmpty() ? null : resolveUnderConfigDir("cache.folder", cacheFolderRaw);
    }

    /**
     * ワークスペースの他のプロジェクト（{@code workspace.projects} の 1 件）の設定を組む。
     *
     * <p>相手は相手自身の設定で解析する（クラスパスの和集合を作らない。docs/workspace-callers-design.md の 3.1 節）ので、
     * 値が設定ファイルならそれをそのまま読む。フォルダなら {@code project.root} だけを書いた設定として読み、
     * ソースフォルダ・依存 jar・文字コードは相手のフォルダの中身から決める（{@link ProjectDetector}・{@link ProjectLayout}）。
     * フォルダの形では、キャッシュの置き場所（{@code cache.folder}）・ローカルリポジトリ・ビルドツールの指定だけを
     * この設定から引き継ぐ（相手の設定に無いものを、この設定の決め方で補う）。
     *
     * <p>相手の設定ファイルの {@code message.language} は読み終えたら元に戻す（設定を読む副作用で表示言語が
     * 切り替わらないように）。相手の {@code workspace.projects} は辿らない（相手の相手までは結合しない。
     * 要るなら自分の設定に並べる）
     *
     * @param entry 設定ファイルかプロジェクトのフォルダ（{@link #workspaceProjects} の要素）
     */
    public Config forWorkspaceProject(Path entry) throws IOException {
        try {
            if (Files.isRegularFile(entry)) {
                return new Config(entry, toolRoot, startedAt);
            }
            Properties p = new Properties();
            p.setProperty("project.root", entry.toAbsolutePath().normalize().toString());
            if (cacheBase != null) {
                p.setProperty("cache.folder", cacheBase.toString());
            }
            if (!libraryRepositories.isEmpty()) {
                List<String> repos = new ArrayList<>();
                for (Path r : libraryRepositories) {
                    repos.add(r.toString());
                }
                p.setProperty("library.repositories", String.join(",", repos));
            }
            p.setProperty("library.build.tool", libraryBuildTool);
            p.setProperty("message.language", messageLanguage);
            return new Config(p, entry, toolRoot, startedAt);
        } finally {
            Messages.applyConfigured(this.messageLanguage);
        }
    }

    /** 文字コードの設定値。名前が不正なら、どの項目かが分かる例外にする（intOf と同じ流儀） */
    private static Charset charsetOf(String key, String raw) {
        try {
            return Charset.forName(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(Messages.format("config.badCharset", key, raw.trim()));
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
                    Messages.format("config.badSourceLevel", requested,
                            JavaCore.getAllVersions(), latest));
        }
        Map<String, String> options = JavaCore.getOptions();
        JavaCore.setComplianceOptions(requested.isEmpty() ? latest : requested, options);
        return options;
    }

    /**
     * 以前の版の項目が残っていれば、新しい書き方を示して止める。
     * 黙って無視すると、出力やキャッシュが以前とは別の場所にできて気づきにくい。
     */
    private static void rejectRemovedKeys(Properties p) {
        Map<String, String> removed = Map.of(
                "output.csv", Messages.format("config.removed.outputCsv", CALL_HIERARCHY_CSV_NAME),
                "methods.csv", Messages.format("config.removed.outputCsv", METHODS_CSV_NAME),
                "cache.folders", Messages.format("config.removed.cacheFolders", DEFAULT_CACHE_DIR_NAME));
        for (Map.Entry<String, String> e : removed.entrySet()) {
            if (p.containsKey(e.getKey())) {
                throw new IllegalArgumentException(Messages.format("config.removed", e.getKey(), e.getValue()));
            }
        }
    }

    /** project.root のフォルダ名。ドライブやルート直下のようにフォルダ名が無ければ "project" */
    private static String projectNameOf(Path projectRoot) {
        Path name = projectRoot.getFileName();
        String s = (name == null) ? "" : name.toString().trim();
        return s.isEmpty() ? "project" : s;
    }

    /**
     * 同じ名前のフォルダが既にあれば _2, _3 … を足す。
     * 同じ秒に同じプロジェクトを 2 回解析する（同じ設定フォルダの別設定を続けて動かす等）と名前がぶつかるため
     */
    private static Path uniqueOutputDir(Path base, String name) {
        Path dir = base.resolve(name);
        for (int n = 2; Files.exists(dir); n++) {
            dir = base.resolve(name + "_" + n);
        }
        return dir;
    }

    /**
     * キャッシュフォルダ名に添える短いハッシュ（SHA-256 の先頭 8 桁）。
     * 別の場所にある同名のプロジェクト（例: ブランチごとのチェックアウト）のキャッシュが混ざらないようにする
     */
    static String shortHash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 相対パスは設定ファイルのあるディレクトリを起点に解決する（配下の制限なし。project.root 用） */
    private Path resolveFromConfigDir(String raw) {
        Path p = Paths.get(raw.trim());
        return (p.isAbsolute() ? p : configDir.resolve(p)).normalize();
    }

    /** 設定ファイルのあるディレクトリを起点に解決する。相対パスはその配下に限る */
    private Path resolveUnderConfigDir(String key, String raw) {
        return resolveUnder(key, configDir, Messages.get("config.baseName.configDir"), raw, false);
    }

    /**
     * project.root を起点に解決する。相対パスは project.root の配下に限る。
     *
     * @param absoluteMustBeInside 絶対パスで指定された場合も配下を要求するか（source.folders）
     */
    private List<Path> resolveAllUnderProject(String key, List<String> raws, boolean absoluteMustBeInside) {
        List<Path> out = new ArrayList<>();
        for (String raw : raws) {
            out.add(resolveUnder(key, projectRoot, "project.root", raw, absoluteMustBeInside));
        }
        return out;
    }

    /**
     * 起点ディレクトリからパスを解決し、配下に収まっていることを確認する。
     *
     * 相対パスで {@code ..} を使って起点の外へ出る指定は、出力やキャッシュが意図しない場所に
     * 書かれたり、project.root からの相対パスが作れなくなったりするので拒否する。
     */
    private static Path resolveUnder(String key, Path base, String baseName, String raw,
                                     boolean absoluteMustBeInside) {
        Path p = Paths.get(raw.trim());
        Path resolved = (p.isAbsolute() ? p : base.resolve(p)).normalize();
        if (resolved.startsWith(base)) {
            return resolved;
        }
        if (!p.isAbsolute()) {
            throw new IllegalArgumentException(
                    Messages.format("config.outsideBase", key, raw.trim(), baseName, base));
        }
        if (absoluteMustBeInside) {
            throw new IllegalArgumentException(
                    Messages.format("config.mustBeUnderBase", key, raw.trim(), baseName, base));
        }
        return resolved;
    }

    /** library.build.tool の値。空欄は auto。それ以外の綴りは、どの項目かが分かる例外にする */
    private static String buildToolOf(String raw) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        switch (value) {
            case "":
                return "auto";
            case "auto":
            case "maven":
            case "gradle":
            case "none":
                return value;
            default:
                throw new IllegalArgumentException(Messages.format("config.badBuildTool", raw.trim()));
        }
    }

    /** 整数の設定値。空欄なら既定値。書式が誤っていれば、どの項目かが分かる例外にする */
    private static int intOf(Properties p, String key, int fallback) {
        String raw = p.getProperty(key);
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    Messages.format("config.notAnInteger", key, raw.trim()));
        }
    }

    private static long longOf(Properties p, String key, long fallback) {
        String raw = p.getProperty(key);
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    Messages.format("config.notAnInteger", key, raw.trim()));
        }
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
            throw new IllegalArgumentException(Messages.format("config.missingRequired", key));
        }
        return v.trim();
    }
}
