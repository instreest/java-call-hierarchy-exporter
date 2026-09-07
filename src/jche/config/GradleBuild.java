// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Gradle のビルドファイルを実行せずに読んで、依存を集める。
 *
 * build.gradle はプログラムなので、読めるのは宣言的に書かれた依存だけである。
 * <ul>
 *   <li>{@code implementation 'g:a:v'}、{@code implementation("g:a:v")}、{@code group: 'g', name: 'a', version: 'v'}
 *       の形（api / compileOnly / runtimeOnly / testImplementation など、ソースセット付きの構成名も）</li>
 *   <li>{@code $var} / {@code ${var}} は gradle.properties と、ビルドファイルの {@code ext} / {@code def} / {@code val} の
 *       文字列代入から埋める</li>
 *   <li>版カタログ {@code libs.foo.bar}（{@link GradleCatalog}）と {@code libs.bundles.x}</li>
 *   <li>{@code platform(...)} / {@code enforcedPlatform(...)} は BOM として、版の無い依存の版を決める</li>
 *   <li>{@code project(':x')} は同じビルドの他プロジェクト。クラスフォルダ（build/classes、Buildship の bin/main）と、
 *       そのビルドファイルの依存を辿る</li>
 *   <li>{@code files(...)} / {@code fileTree(...)} の jar</li>
 *   <li>gradle.lockfile があれば、そこに解決済みの全依存（推移的なものも）があるのでそれを使う</li>
 * </ul>
 * 推移的な依存は、ローカルリポジトリの POM から {@link DependencyCollector} が辿る（版は高い方が勝つ）。
 */
final class GradleBuild {

    /** ビルドファイルから読み取った宣言 */
    private static final class Declared {
        final Map<String, MavenPom.Dependency> external = new LinkedHashMap<>();
        final List<String> platforms = new ArrayList<>();
        final List<String> projectPaths = new ArrayList<>();
        final List<Path> files = new ArrayList<>();
        final List<String> notes = new ArrayList<>();

        void add(MavenPom.Dependency d) {
            external.putIfAbsent(d.managementKey(), d);
        }
    }

    private static final Pattern LINE = Pattern.compile("^\\s*([A-Za-z]\\w*)\\b(.*)$");
    private static final Pattern QUOTED = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"|'((?:[^'\\\\]|\\\\.)*)'");
    private static final Pattern PROJECT_REF = Pattern.compile(
            "project\\s*\\(\\s*(?:path\\s*[:=]\\s*)?['\"]([^'\"]+)['\"]");
    private static final Pattern TYPESAFE_PROJECT = Pattern.compile("\\bprojects\\.([A-Za-z][\\w.]*)");
    private static final Pattern MAP_ENTRY = Pattern.compile("\\b(group|name|version|classifier|ext)\\s*[:=]\\s*['\"]([^'\"]*)['\"]");
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{?([A-Za-z_][\\w.]*(?:\\(['\"]\\w+['\"]\\))?)\\}?");
    private static final String[] CONFIGURATION_SUFFIXES = {
        "Implementation", "Api", "CompileOnlyApi", "CompileOnly", "RuntimeOnly", "Compile", "Runtime",
        "implementation", "api", "compileOnlyApi", "compileOnly", "runtimeOnly", "compile", "runtime",
        "providedCompile", "providedRuntime"
    };

    private final Path root;
    private final Map<String, Path> projectDirs;
    private final Map<String, String> properties;
    private final Map<String, GradleCatalog> catalogs;
    private final LocalRepositories repos;
    private final MavenModels models;

    private GradleBuild(Path root, LocalRepositories repos, MavenModels models) {
        this.root = root;
        this.repos = repos;
        this.models = models;
        this.projectDirs = parseSettings(root);
        this.properties = new LinkedHashMap<>();
        this.catalogs = parseCatalogs(root);
    }

    /** dir のプロジェクトの依存 jar を集める */
    static DependencyCollector.Result resolve(Path dir, LocalRepositories repos, MavenModels models) {
        Path root = settingsRoot(dir);
        GradleBuild build = new GradleBuild(root, repos, models);
        build.loadProperties(dir);

        Declared all = new Declared();
        List<DependencyCollector.Entry> projectEntries = new ArrayList<>();
        boolean fromLockfile = build.readLockfile(dir, all);
        Declared own = build.declarations(dir);
        if (fromLockfile) {
            all.notes.add("gradle.lockfile があるので、解決済みの依存はそこから取る（build.gradle の依存の宣言は project() と files() だけ見る）");
            own.external.clear();
            own.platforms.clear();
        }
        merge(all, own);

        // 他プロジェクトへの依存は、そのクラスフォルダと、そのプロジェクトの依存を（再帰的に）足す
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(own.projectPaths);
        visited.add(build.projectPath(dir));
        while (!queue.isEmpty()) {
            String path = queue.poll();
            if (!visited.add(path)) {
                continue;
            }
            Path projectDir = build.projectDirs.getOrDefault(path, root.resolve(path.replace(':', '/').replaceFirst("^/", "")));
            if (!Files.isDirectory(projectDir)) {
                all.notes.add("project('" + path + "') のディレクトリが見つかりません: " + projectDir);
                continue;
            }
            List<Path> classes = classFolders(projectDir);
            if (classes.isEmpty()) {
                all.notes.add("project('" + path + "') はビルドされていないため、そのクラスは解決できません（build/classes も bin/main も無い）: " + projectDir);
            }
            for (Path c : classes) {
                projectEntries.add(new DependencyCollector.Entry(c, "project('" + path + "')", "build.gradle"));
            }
            Declared sub = build.declarations(projectDir);
            merge(all, sub);
            queue.addAll(sub.projectPaths);
        }

        // platform（BOM）は版の無い依存の版を決める
        Map<String, MavenPom.Dependency> managed = new LinkedHashMap<>();
        for (String gav : all.platforms) {
            String[] p = gav.split(":");
            MavenProject bom = (p.length >= 3) ? models.fromRepository(p[0], p[1], p[2]) : null;
            if (bom == null) {
                all.notes.add("platform の BOM がローカルリポジトリにありません: " + gav);
                continue;
            }
            for (Map.Entry<String, MavenPom.Dependency> e : bom.managed.entrySet()) {
                managed.putIfAbsent(e.getKey(), e.getValue());
            }
        }
        // 版の無い依存で BOM にも無いものは、ローカルにある最も新しい版を使う（Gradle の解決結果に最も近い）
        List<MavenPom.Dependency> direct = new ArrayList<>();
        for (MavenPom.Dependency d : all.external.values()) {
            if (d.version().isEmpty() && !managed.containsKey(d.managementKey())) {
                String latest = Versions.select("+", repos.versions(d.groupId(), d.artifactId()));
                if (latest != null) {
                    all.notes.add(d.ga() + " は版の指定が無いので、ローカルにある最も新しい版 " + latest + " を使う");
                    d = d.withVersion(latest);
                }
            }
            direct.add(d);
        }

        DependencyCollector collector = new DependencyCollector(repos, models, null,
                DependencyCollector.Strategy.HIGHEST, managed);
        DependencyCollector.Result result = collector.collect(direct, "build.gradle", !fromLockfile);
        result.entries.addAll(0, projectEntries);
        for (Path f : all.files) {
            result.entries.add(new DependencyCollector.Entry(f, f.getFileName().toString(), "files() / fileTree()"));
        }
        result.notes.addAll(all.notes);
        return result;
    }

    private static void merge(Declared into, Declared from) {
        for (MavenPom.Dependency d : from.external.values()) {
            into.add(d);
        }
        into.platforms.addAll(from.platforms);
        into.projectPaths.addAll(from.projectPaths);
        into.files.addAll(from.files);
        into.notes.addAll(from.notes);
    }

    // ---- 設定ファイル・プロパティ・カタログ ----------------------------------------------

    /** settings.gradle(.kts) のあるディレクトリ。Gradle 自身と同じく上位へ探す。無ければ dir */
    static Path settingsRoot(Path dir) {
        for (Path d = dir; d != null; d = d.getParent()) {
            if (BuildTool.hasGradleSettings(d)) {
                return d;
            }
        }
        return dir;
    }

    /** settings の include から、プロジェクトのパス（:a:b）→ ディレクトリ */
    private static Map<String, Path> parseSettings(Path root) {
        Map<String, Path> dirs = new LinkedHashMap<>();
        String text = readScript(root, "settings.gradle", "settings.gradle.kts");
        Matcher include = Pattern.compile("\\binclude\\s*\\(?((?:\\s*['\"][^'\"]+['\"]\\s*,?)+)").matcher(text);
        while (include.find()) {
            Matcher q = QUOTED.matcher(include.group(1));
            while (q.find()) {
                String path = normalizeProjectPath(q.group(1) != null ? q.group(1) : q.group(2));
                dirs.put(path, root.resolve(path.substring(1).replace(':', '/')).normalize());
            }
        }
        Matcher custom = Pattern.compile(
                "project\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)\\s*\\.projectDir\\s*=\\s*(?:new\\s+File|file)\\s*\\(\\s*['\"]([^'\"]+)['\"]")
                .matcher(text);
        while (custom.find()) {
            dirs.put(normalizeProjectPath(custom.group(1)), root.resolve(custom.group(2)).normalize());
        }
        return dirs;
    }

    private static String normalizeProjectPath(String raw) {
        String p = raw.trim();
        return p.startsWith(":") ? p : ":" + p;
    }

    /** このディレクトリのプロジェクトパス（ルートからの相対。ルート自身は ":"） */
    private String projectPath(Path dir) {
        for (Map.Entry<String, Path> e : projectDirs.entrySet()) {
            if (e.getValue().equals(dir.toAbsolutePath().normalize())) {
                return e.getKey();
            }
        }
        Path rel = root.toAbsolutePath().normalize().relativize(dir.toAbsolutePath().normalize());
        return ":" + rel.toString().replace('\\', '/').replace('/', ':');
    }

    /**
     * gradle.properties（~/.gradle、ルート、プロジェクト。後のものが勝つ）と、
     * ビルドファイルの文字列代入（ext { x = 'v' }、ext.x = 'v'、def / val / var x = 'v'、extra["x"] = "v"）
     */
    private void loadProperties(Path dir) {
        Path userHome = Paths.get(System.getProperty("user.home"));
        String gradleUserHome = System.getenv("GRADLE_USER_HOME");
        Path userGradle = (gradleUserHome == null || gradleUserHome.trim().isEmpty())
                ? userHome.resolve(".gradle") : Paths.get(gradleUserHome.trim());
        for (Path f : new Path[] {userGradle.resolve("gradle.properties"), root.resolve("gradle.properties"),
                                  dir.resolve("gradle.properties")}) {
            if (Files.isRegularFile(f)) {
                Properties p = new Properties();
                try (InputStream in = Files.newInputStream(f)) {
                    p.load(in);
                } catch (IOException ignore) {
                    continue;
                }
                for (String name : p.stringPropertyNames()) {
                    properties.put(name, p.getProperty(name).trim());
                }
            }
        }
        for (Path d : new Path[] {root, dir}) {
            String text = readScript(d, "build.gradle", "build.gradle.kts");
            Matcher m = Pattern.compile(
                    "(?m)^\\s*(?:ext\\.|(?:def|val|var)\\s+)?([A-Za-z_]\\w*)\\s*=\\s*['\"]([^'\"]*)['\"]\\s*$").matcher(text);
            while (m.find()) {
                properties.put(m.group(1), m.group(2));
            }
            Matcher extra = Pattern.compile(
                    "(?:extra\\s*\\[\\s*['\"](\\w+)['\"]\\s*\\]\\s*=|val\\s+(\\w+)\\s+by\\s+extra\\s*\\(|set\\s*\\(\\s*['\"](\\w+)['\"]\\s*,)\\s*['\"]([^'\"]*)['\"]")
                    .matcher(text);
            while (extra.find()) {
                String name = extra.group(1) != null ? extra.group(1) : extra.group(2) != null ? extra.group(2) : extra.group(3);
                properties.put(name, extra.group(4));
            }
        }
    }

    /** 既定の gradle/libs.versions.toml と、settings の versionCatalogs で from(files(...)) されたもの */
    private static Map<String, GradleCatalog> parseCatalogs(Path root) {
        Map<String, GradleCatalog> out = new LinkedHashMap<>();
        Map<String, Path> files = new LinkedHashMap<>();
        Path defaultCatalog = root.resolve("gradle").resolve("libs.versions.toml");
        if (Files.isRegularFile(defaultCatalog)) {
            files.put("libs", defaultCatalog);
        }
        String settings = readScript(root, "settings.gradle", "settings.gradle.kts");
        Matcher m = Pattern.compile(
                "(?:create\\s*\\(\\s*['\"](\\w+)['\"]\\s*\\)|\\b(\\w+))\\s*\\{[^{}]*?from\\s*\\(\\s*files\\s*\\(\\s*['\"]([^'\"]+)['\"]")
                .matcher(settings);
        while (m.find()) {
            String name = m.group(1) != null ? m.group(1) : m.group(2);
            files.put(name, root.resolve(m.group(3)).normalize());
        }
        for (Map.Entry<String, Path> e : files.entrySet()) {
            try {
                out.put(e.getKey(), GradleCatalog.read(e.getValue()));
            } catch (IOException ignore) {
                // 読めないカタログは無いものとして扱う（参照は「読めない宣言」として残る）
            }
        }
        return out;
    }

    // ---- ロックファイル ----------------------------------------------------------------------

    /**
     * gradle.lockfile（Gradle 7 以降の 1 ファイル形式）か gradle/dependency-locks/*.lockfile（構成ごと）。
     * コンパイル・実行時のクラスパスの構成のものを取る。
     *
     * @return ロックファイルがあったか
     */
    private boolean readLockfile(Path dir, Declared into) {
        Path single = dir.resolve("gradle.lockfile");
        boolean found = false;
        if (Files.isRegularFile(single)) {
            found = true;
            for (String line : readLines(single)) {
                String l = line.trim();
                int eq = l.indexOf('=');
                if (l.isEmpty() || l.startsWith("#") || eq < 0 || l.startsWith("empty=")) {
                    continue;
                }
                if (isClasspathConfiguration(l.substring(eq + 1))) {
                    addCoordinates(into, l.substring(0, eq), "compile");
                }
            }
        }
        Path locks = dir.resolve("gradle").resolve("dependency-locks");
        if (Files.isDirectory(locks)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(locks, "*.lockfile")) {
                for (Path f : ds) {
                    String configuration = f.getFileName().toString().replace(".lockfile", "");
                    if (!isClasspathConfiguration(configuration)) {
                        continue;
                    }
                    found = true;
                    for (String line : readLines(f)) {
                        String l = line.trim();
                        if (!l.isEmpty() && !l.startsWith("#")) {
                            addCoordinates(into, l, "compile");
                        }
                    }
                }
            } catch (IOException ignore) {
                // 読めなければ無いものとして扱う
            }
        }
        return found;
    }

    /** ロックファイルの 1 行（g:a:v）を宣言に足す */
    private static void addCoordinates(Declared into, String coordinates, String scope) {
        MavenPom.Dependency d = coordinates(coordinates.trim(), scope);
        if (d != null && !d.version().isEmpty()) {
            into.add(d);
        } else {
            into.notes.add("ロックファイルの読めない行: " + coordinates.trim());
        }
    }

    private static boolean isClasspathConfiguration(String configurations) {
        for (String c : configurations.split(",")) {
            String name = c.trim().toLowerCase(Locale.ROOT);
            if (name.endsWith("compileclasspath") || name.endsWith("runtimeclasspath")) {
                return true;
            }
        }
        return false;
    }

    // ---- ビルドファイルの読み取り ------------------------------------------------------------

    /**
     * dir のビルドファイルの依存の宣言。サブプロジェクトなら、ルートのビルドファイルの
     * allprojects / subprojects ブロックの分も足す（ルート自身は subprojects ブロックを除く）。
     */
    private Declared declarations(Path dir) {
        Declared out = new Declared();
        String own = readScript(dir, "build.gradle", "build.gradle.kts");
        own = removeBlocks(own, "buildscript");
        own = removeBlocks(own, "constraints");
        boolean isRoot = dir.toAbsolutePath().normalize().equals(root.toAbsolutePath().normalize());
        if (isRoot) {
            own = removeBlocks(own, "subprojects");
        }
        scan(own, dir, out);
        if (!isRoot) {
            String rootText = removeBlocks(readScript(root, "build.gradle", "build.gradle.kts"), "buildscript");
            for (String name : new String[] {"allprojects", "subprojects"}) {
                for (String block : blocks(rootText, name)) {
                    scan(removeBlocks(block, "constraints"), dir, out);
                }
            }
        }
        return out;
    }

    /** テキストの各行から依存の宣言を拾う */
    private void scan(String text, Path dir, Declared out) {
        for (String line : text.split("\\R")) {
            Matcher m = LINE.matcher(line);
            if (!m.find() || !isDependencyConfiguration(m.group(1))) {
                continue;
            }
            String scope = scopeOf(m.group(1));
            // "${property("x")}" のように文字列の中に引用符が入る形は、先にプロパティで置き換えておく
            // （置き換えないと文字列リテラルの切り出しが内側の引用符で止まる）
            String rest = expandPropertyCalls(m.group(2));

            Matcher pr = PROJECT_REF.matcher(rest);
            while (pr.find()) {
                out.projectPaths.add(normalizeProjectPath(pr.group(1)));
            }
            Matcher ts = TYPESAFE_PROJECT.matcher(rest);
            while (ts.find()) {
                StringBuilder path = new StringBuilder();
                for (String seg : ts.group(1).split("\\.")) {
                    path.append(':').append(seg.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT));
                }
                out.projectPaths.add(path.toString());
            }
            if (pr.reset().find() || ts.reset().find()) {
                continue;
            }

            Matcher platform = Pattern.compile("\\b(?:enforcedPlatform|platform)\\s*\\(([^()]*)\\)").matcher(rest);
            if (platform.find()) {
                for (MavenPom.Dependency d : notations(platform.group(1), dir, out, scope)) {
                    if (!d.version().isEmpty()) {
                        out.platforms.add(d.groupId() + ":" + d.artifactId() + ":" + d.version());
                    } else {
                        out.notes.add("platform の版が決まりません: " + line.trim());
                    }
                }
                continue;
            }
            Matcher files = Pattern.compile("\\bfiles\\s*\\(([^()]*)\\)").matcher(rest);
            if (files.find()) {
                Matcher q = QUOTED.matcher(files.group(1));
                while (q.find()) {
                    Path p = dir.resolve(expand(q.group(1) != null ? q.group(1) : q.group(2))).normalize();
                    if (Files.exists(p)) {
                        out.files.add(p);
                    } else {
                        out.notes.add("files() のファイルがありません: " + p);
                    }
                }
                continue;
            }
            Matcher tree = Pattern.compile("\\bfileTree\\s*\\(([^()]*)\\)").matcher(rest);
            if (tree.find()) {
                Matcher dirArg = Pattern.compile("dir\\s*[:=]\\s*['\"]([^'\"]+)['\"]").matcher(tree.group(1));
                Matcher q = QUOTED.matcher(tree.group(1));
                String base = dirArg.find() ? dirArg.group(1) : (q.find() ? (q.group(1) != null ? q.group(1) : q.group(2)) : null);
                if (base != null) {
                    Path p = dir.resolve(expand(base)).normalize();
                    if (Files.isDirectory(p)) {
                        out.files.addAll(jarsUnder(p));
                    } else {
                        out.notes.add("fileTree() のフォルダがありません: " + p);
                    }
                }
                continue;
            }
            List<MavenPom.Dependency> found = notations(rest, dir, out, scope);
            if (found.isEmpty() && !rest.trim().isEmpty()) {
                out.notes.add("読めない依存の宣言: " + line.trim());
            }
            for (MavenPom.Dependency d : found) {
                out.add(d);
            }
        }
    }

    /** 1 行の引数部分から座標を取り出す。文字列の座標、map 形式、版カタログの参照 */
    private List<MavenPom.Dependency> notations(String args, Path dir, Declared out, String scope) {
        List<MavenPom.Dependency> found = new ArrayList<>();
        Matcher map = MAP_ENTRY.matcher(args);
        Map<String, String> entries = new LinkedHashMap<>();
        while (map.find()) {
            entries.put(map.group(1), expand(map.group(2)));
        }
        if (entries.containsKey("group") && entries.containsKey("name")) {
            found.add(dependency(entries.get("group"), entries.get("name"), entries.getOrDefault("version", ""),
                    entries.getOrDefault("classifier", ""), entries.getOrDefault("ext", ""), scope));
            return found;
        }
        StringBuilder names = new StringBuilder();
        for (String name : catalogs.keySet()) {
            names.append(names.length() == 0 ? "" : "|").append(Pattern.quote(name));
        }
        if (names.length() > 0) {
            Matcher ref = Pattern.compile("\\b(" + names + ")\\.((?:bundles\\.)?[A-Za-z][\\w.]*)").matcher(args);
            while (ref.find()) {
                GradleCatalog catalog = catalogs.get(ref.group(1));
                String accessor = ref.group(2);
                if (accessor.startsWith("versions.") || accessor.startsWith("plugins.")) {
                    continue;
                }
                List<GradleCatalog.Library> libs = new ArrayList<>();
                if (accessor.startsWith("bundles.")) {
                    libs.addAll(catalog.bundle(accessor.substring("bundles.".length())));
                } else {
                    GradleCatalog.Library lib = catalog.library(accessor);
                    if (lib != null) {
                        libs.add(lib);
                    }
                }
                if (libs.isEmpty()) {
                    out.notes.add("版カタログに無い参照: " + ref.group(0));
                }
                for (GradleCatalog.Library lib : libs) {
                    found.add(dependency(lib.group(), lib.name(), lib.version(), "", "", scope));
                }
            }
            if (!found.isEmpty()) {
                return found;
            }
        }
        Matcher q = QUOTED.matcher(args);
        while (q.find()) {
            String literal = expand(q.group(1) != null ? q.group(1) : q.group(2));
            MavenPom.Dependency d = coordinates(literal, scope);
            if (d != null) {
                found.add(d);
            }
        }
        return found;
    }

    /** "g:a:v[:classifier][@ext]" を依存にする。座標の形でなければ null */
    private static MavenPom.Dependency coordinates(String literal, String scope) {
        String s = literal.trim();
        if (s.isEmpty() || s.contains(" ") || s.contains("/") || s.contains("\\") || s.contains("${") || s.contains("$")) {
            return null;
        }
        String ext = "";
        int at = s.indexOf('@');
        if (at > 0) {
            ext = s.substring(at + 1);
            s = s.substring(0, at);
        }
        String[] parts = s.split(":", -1);
        if (parts.length < 2 || parts.length > 4 || parts[0].isEmpty() || parts[1].isEmpty()) {
            return null;
        }
        return dependency(parts[0], parts[1], parts.length > 2 ? parts[2] : "", parts.length > 3 ? parts[3] : "", ext, scope);
    }

    private static MavenPom.Dependency dependency(String group, String name, String version, String classifier,
                                                  String ext, String scope) {
        String type = ext.isEmpty() ? "jar" : ext;
        return new MavenPom.Dependency(group, name, version, type, classifier, scope, false, List.of(), "");
    }

    /** {@code ${property("x")}} {@code ${findProperty("x")}} {@code ${project.property("x")}} を値に置き換える */
    private String expandPropertyCalls(String s) {
        if (s.indexOf('$') < 0) {
            return s;
        }
        Matcher m = Pattern.compile(
                "\\$\\{\\s*(?:project\\.|rootProject\\.)?(?:property|findProperty)\\s*\\(\\s*[\"']([\\w.]+)[\"']\\s*\\)\\s*\\}")
                .matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = properties.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : m.group(0)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** {@code $var} {@code ${var}} {@code ${project.var}} {@code ${property('var')}} をプロパティで埋める */
    private String expand(String s) {
        if (s.indexOf('$') < 0) {
            return s;
        }
        Matcher m = VARIABLE.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            Matcher call = Pattern.compile("^(?:property|findProperty)\\(['\"](\\w+)['\"]\\)$").matcher(key);
            if (call.find()) {
                key = call.group(1);
            }
            key = key.replaceFirst("^(?:rootProject\\.|project\\.)?(?:ext\\.|extra\\.)?", "");
            String value = properties.get(key);
            m.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : m.group(0)));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static boolean isDependencyConfiguration(String name) {
        if (name.equals("classpath") || name.startsWith("kapt") || name.startsWith("ksp")
                || name.startsWith("annotationProcessor")) {
            return false;
        }
        for (String suffix : CONFIGURATION_SUFFIXES) {
            if (name.equals(suffix)) {
                return true;
            }
            if (name.endsWith(suffix) && Character.isUpperCase(suffix.charAt(0)) && name.length() > suffix.length()) {
                return true;
            }
        }
        return false;
    }

    private static String scopeOf(String configuration) {
        String lower = configuration.toLowerCase(Locale.ROOT);
        if (lower.startsWith("test")) {
            return "test";
        }
        if (lower.endsWith("compileonly") || lower.endsWith("compileonlyapi") || lower.startsWith("provided")) {
            return "provided";
        }
        if (lower.endsWith("runtimeonly") || lower.endsWith("runtime")) {
            return "runtime";
        }
        return "compile";
    }

    /** 他プロジェクトのコンパイル結果。Gradle の build/classes、Buildship の bin/main、素の Eclipse の bin、無ければ build/libs の jar */
    private static List<Path> classFolders(Path projectDir) {
        List<Path> out = new ArrayList<>();
        for (String lang : new String[] {"java", "kotlin", "groovy", "scala"}) {
            Path p = projectDir.resolve("build").resolve("classes").resolve(lang).resolve("main");
            if (Files.isDirectory(p)) {
                out.add(p);
            }
        }
        if (!out.isEmpty()) {
            return out;
        }
        for (String eclipse : new String[] {"bin/main", "bin"}) {
            Path p = projectDir.resolve(eclipse);
            if (Files.isDirectory(p)) {
                out.add(p);
                return out;
            }
        }
        Path libs = projectDir.resolve("build").resolve("libs");
        for (Path jar : jarsUnder(libs)) {
            String n = jar.getFileName().toString();
            if (!n.endsWith("-sources.jar") && !n.endsWith("-javadoc.jar") && !n.endsWith("-tests.jar")) {
                out.add(jar);
            }
        }
        return out;
    }

    private static List<Path> jarsUnder(Path dir) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".jar")).forEach(out::add);
        } catch (IOException ignore) {
            // 読めないフォルダは無いものとして扱う
        }
        out.sort((a, b) -> a.toString().compareTo(b.toString()));
        return out;
    }

    // ---- テキストの小道具 --------------------------------------------------------------------

    /** 最初に見つかったファイルをコメントを除いて読む。無ければ空 */
    private static String readScript(Path dir, String... names) {
        for (String name : names) {
            Path f = dir.resolve(name);
            if (Files.isRegularFile(f)) {
                try {
                    return stripComments(Files.readString(f, StandardCharsets.UTF_8));
                } catch (IOException ignore) {
                    return "";
                }
            }
        }
        return "";
    }

    private static List<String> readLines(Path f) {
        try {
            return Files.readAllLines(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of();
        }
    }

    /** ブロックコメントと行コメントを除く（URL の "://" は行コメントではない） */
    static String stripComments(String text) {
        String noBlock = text.replaceAll("(?s)/\\*.*?\\*/", "");
        StringBuilder out = new StringBuilder();
        for (String line : noBlock.split("\\R", -1)) {
            out.append(line.replaceFirst("(?<![:\\w])//.*$", "")).append('\n');
        }
        return out.toString();
    }

    /** {@code name {} } ブロックの中身（入れ子は数える。文字列の中の括弧は見ない） */
    static List<String> blocks(String text, String name) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*(?:\\([^)]*\\)\\s*)?\\{").matcher(text);
        while (m.find()) {
            int end = closingBrace(text, m.end() - 1);
            if (end < 0) {
                break;
            }
            out.add(text.substring(m.end(), end));
        }
        return out;
    }

    static String removeBlocks(String text, String name) {
        StringBuilder out = new StringBuilder(text);
        Matcher m = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*(?:\\([^)]*\\)\\s*)?\\{").matcher(out);
        while (m.find()) {
            int end = closingBrace(out, m.end() - 1);
            if (end < 0) {
                break;
            }
            out.delete(m.start(), end + 1);
            m.reset(out);
        }
        return out.toString();
    }

    private static int closingBrace(CharSequence text, int open) {
        int depth = 0;
        boolean inString = false;
        char quote = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    inString = false;
                }
            } else if (c == '"' || c == '\'') {
                inString = true;
                quote = c;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }
}
