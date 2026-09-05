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
package jche.config;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.eclipse.jdt.core.JavaCore;

/**
 * 設定ファイル（config.properties）の読み込み。
 *
 * 設定できる項目とその意味は config/config.properties にコメント付きでまとめてある。
 * あちらを唯一の一覧として扱い、ここには複製しない（二重管理で片方が古くなるのを避けるため）。
 *
 * 相対パスの起点は項目ごとに異なる。
 * <ul>
 *   <li>project.root / cache.folders / output.csv / methods.csv … この設定ファイルが置かれているディレクトリ</li>
 *   <li>source.folders / library.folders / external.library.folders … project.root</li>
 * </ul>
 * 設定ファイルと関連ファイルをひとまとめに配置でき、どこから実行しても同じ結果になる。
 *
 * 相対パスは起点ディレクトリの配下だけを指せる（{@code ..} で外へ出る指定は拒否する）。
 * project.root だけは起点そのものなので制限しない。source.folders は絶対パスでも project.root の
 * 配下でなければならない。キャッシュのキーと出力の file 列を project.root からの相対パスにするため。
 */
public final class Config {

    /** CHA候補を呼び出し階層で展開する際の候補数の上限 */
    public static final int CHA_MAX_CANDIDATES = 20;
    /** キャッシュフォルダ内に置くインデックスファイルの名前 */
    public static final String CACHE_FILE_NAME = "analysis-cache.tsv";

    public final Path configDir;
    public final Path projectRoot;
    /** ソースフォルダ（project.root からの相対）。空欄なら .classpath の kind="src" を使う */
    public final List<Path> sourceFolders;
    /** 依存jarを集めたフォルダ（project.root からの相対）。.classpath の kind="lib" があれば合算する */
    public final List<Path> libraryFolders;
    public final String sourceEncoding;
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
    public final List<String> hintCollectorClasses;
    public final List<String> candidateProviderClasses;

    public final boolean cacheEnabled;
    /** データフロー解析（ファクトリの戻り値・引数から具象クラスを特定）を使うか */
    public final boolean dataflowEnabled;
    /** ファクトリの委譲（return create();）を何段まで辿るか */
    public final int dataflowMaxDepth;
    public final Path cacheFile;

    /** 他チームのjar（自分のコードを呼んでいる側）。ファイルでもディレクトリでも可 */
    public final List<Path> externalLibraryFolders;

    public final Path outputCsv;
    public final Path methodsCsv;

    /** CSVの出力文字コード。既定はUTF-8-BOM（Excelでそのまま開ける） */
    public final Charset outputEncoding;
    /** output.encoding=UTF-8-BOM のとき、ファイル先頭にBOMを書くか */
    public final boolean outputBom;

    public Config(Path configPath) throws IOException {
        Path abs = configPath.toAbsolutePath().normalize();
        Path dir = abs.getParent();
        this.configDir = (dir == null) ? Paths.get(".").toAbsolutePath().normalize() : dir;

        Properties p = new Properties();
        try (Reader r = new InputStreamReader(Files.newInputStream(abs), StandardCharsets.UTF_8)) {
            p.load(r);
        }

        // project.root は他の項目の起点そのものなので、設定ファイルのディレクトリの外を指してよい
        this.projectRoot = resolveFromConfigDir(require(p, "project.root"));

        // source.folders / library.folders / external.library.folders は project.root からの相対
        this.sourceFolders = resolveAllUnderProject("source.folders",
                splitList(p.getProperty("source.folders", "")), true);
        this.libraryFolders = resolveAllUnderProject("library.folders",
                splitList(p.getProperty("library.folders", "")), false);

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

        this.maxDepth = intOf(p, "max.depth", 50);
        this.maxRows = longOf(p, "max.rows", 5_000_000L);

        // 拡張（フェーズA/B）は設定ファイルには載せていない。
        // 使う場合はこのキーを足せば読み込まれる
        this.hintCollectorClasses = splitList(p.getProperty("resolver.hint.collectors", ""));
        this.candidateProviderClasses = splitList(p.getProperty("resolver.candidate.providers", ""));
        this.raw = p;

        this.cacheEnabled = Boolean.parseBoolean(p.getProperty("cache.enabled", "true").trim());
        this.dataflowEnabled = Boolean.parseBoolean(p.getProperty("dataflow.enabled", "true").trim());
        this.dataflowMaxDepth = intOf(p, "dataflow.max.depth", 5);
        this.cacheFile = resolveUnderConfigDir("cache.folders", p.getProperty("cache.folders", "./.cache"))
                .resolve(CACHE_FILE_NAME);

        // 被参照スキャンの対象は「解析対象プロジェクトの外の世界」なので、
        // ソースや依存jarと同じく project.root からの相対で書けるようにする
        this.externalLibraryFolders = resolveAllUnderProject("external.library.folders",
                splitList(p.getProperty("external.library.folders", "")), false);

        this.outputCsv = resolveUnderConfigDir("output.csv",
                p.getProperty("output.csv", "./output/call-hierarchy.csv"));
        this.methodsCsv = resolveUnderConfigDir("methods.csv",
                p.getProperty("methods.csv", "./output/methods.csv"));

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

    /** 相対パスは設定ファイルのあるディレクトリを起点に解決する（配下の制限なし。project.root 用） */
    private Path resolveFromConfigDir(String raw) {
        Path p = Paths.get(raw.trim());
        return (p.isAbsolute() ? p : configDir.resolve(p)).normalize();
    }

    /** 設定ファイルのあるディレクトリを起点に解決する。相対パスはその配下に限る */
    private Path resolveUnderConfigDir(String key, String raw) {
        return resolveUnder(key, configDir, "設定ファイルのフォルダ", raw, false);
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
            throw new IllegalArgumentException("設定 " + key + " の相対パス '" + raw.trim()
                    + "' が " + baseName + "（" + base + "）の外を指しています。"
                    + "相対パスは " + baseName + " の配下だけ指定できます。外を指す場合は絶対パスで書いてください");
        }
        if (absoluteMustBeInside) {
            throw new IllegalArgumentException("設定 " + key + " のパス '" + raw.trim()
                    + "' は " + baseName + "（" + base + "）の配下でなければなりません。"
                    + "キャッシュのキーと出力の file 列を " + baseName + " からの相対パスにするためです");
        }
        return resolved;
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
                    "設定 " + key + " の値が整数ではありません: '" + raw.trim() + "'");
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
                    "設定 " + key + " の値が整数ではありません: '" + raw.trim() + "'");
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
            throw new IllegalArgumentException("設定ファイルに必須項目がありません: " + key);
        }
        return v.trim();
    }
}
