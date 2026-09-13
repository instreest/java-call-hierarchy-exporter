// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    static final class Declared {
        final Map<String, Dependency> external = new LinkedHashMap<>();
        final List<String> platforms = new ArrayList<>();
        final List<String> projectPaths = new ArrayList<>();
        final List<Path> files = new ArrayList<>();
        final List<String> notes = new ArrayList<>();

        void add(Dependency d) {
            external.putIfAbsent(d.managementKey(), d);
        }
    }

    private static final Pattern LINE = Pattern.compile("^\\s*([A-Za-z]\\w*)\\b(.*)$");
    static final Pattern QUOTED = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"|'((?:[^'\\\\]|\\\\.)*)'");
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
        this.projectDirs = GradleSettings.parseSettings(root);
        this.properties = new LinkedHashMap<>();
        this.catalogs = GradleSettings.parseCatalogs(root);
    }

    /**
     * dir のプロジェクトの依存 jar を集める。
     *
     * @param projectRoot project.root。settings.gradle がこの外で見つかったときに注記を残す
     */
    static DependencyCollector.Result resolve(Path dir, Path projectRoot, LocalRepositories repos, MavenModels models) {
        Path root = GradleSettings.settingsRoot(dir);
        GradleBuild build = new GradleBuild(root, repos, models);
        GradleSettings.loadProperties(root, dir, build.properties);

        Declared all = new Declared();
        if (!root.startsWith(projectRoot.toAbsolutePath().normalize())) {
            all.notes.add("settings.gradle を project.root の外で見つけたので、そこをビルドのルートとして扱う: " + root
                    + "（意図しない上位フォルダなら、project.root をビルドのルートに合わせるか library.folders を指定する）");
        }
        List<DependencyCollector.Entry> projectEntries = new ArrayList<>();
        boolean fromLockfile = GradleLockfile.read(dir, all);
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
        visited.add(GradleSettings.projectPath(root, build.projectDirs, dir));
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
        Map<String, Dependency> managed = new LinkedHashMap<>();
        for (String gav : all.platforms) {
            String[] p = gav.split(":");
            MavenProject bom = (p.length >= 3) ? models.fromRepository(p[0], p[1], p[2]) : null;
            if (bom == null) {
                all.notes.add("platform の BOM がローカルリポジトリにありません: " + gav);
                continue;
            }
            for (Map.Entry<String, Dependency> e : bom.managed.entrySet()) {
                managed.putIfAbsent(e.getKey(), e.getValue());
            }
        }
        // 版の無い依存で BOM にも無いものは、ローカルにある最も新しい版を使う（Gradle の解決結果に最も近い）
        List<Dependency> direct = new ArrayList<>();
        for (Dependency d : all.external.values()) {
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
        for (Dependency d : from.external.values()) {
            into.add(d);
        }
        into.platforms.addAll(from.platforms);
        into.projectPaths.addAll(from.projectPaths);
        into.files.addAll(from.files);
        into.notes.addAll(from.notes);
    }

    // ---- ビルドファイルの読み取り ------------------------------------------------------------

    /**
     * dir のビルドファイルの依存の宣言。サブプロジェクトなら、ルートのビルドファイルの
     * allprojects / subprojects ブロックの分も足す（ルート自身は subprojects ブロックを除く）。
     */
    private Declared declarations(Path dir) {
        Declared out = new Declared();
        String own = GradleScripts.readScript(dir, "build.gradle", "build.gradle.kts");
        own = GradleScripts.removeBlocks(own, "buildscript");
        own = GradleScripts.removeBlocks(own, "constraints");
        boolean isRoot = dir.toAbsolutePath().normalize().equals(root.toAbsolutePath().normalize());
        if (isRoot) {
            own = GradleScripts.removeBlocks(own, "subprojects");
        }
        scan(own, dir, out);
        if (!isRoot) {
            String rootText = GradleScripts.removeBlocks(
                    GradleScripts.readScript(root, "build.gradle", "build.gradle.kts"), "buildscript");
            for (String name : new String[] {"allprojects", "subprojects"}) {
                for (String block : GradleScripts.blocks(rootText, name)) {
                    scan(GradleScripts.removeBlocks(block, "constraints"), dir, out);
                }
            }
        }
        return out;
    }

    /** テキストの各行から依存の宣言を拾う。括弧が閉じていない行は先に次の行と結合する（複数行の呼び出し） */
    private void scan(String text, Path dir, Declared out) {
        for (String line : GradleScripts.joinLogicalLines(text).split("\\R")) {
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
                out.projectPaths.add(GradleSettings.normalizeProjectPath(pr.group(1)));
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
                for (Dependency d : notations(platform.group(1), dir, out, scope)) {
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
            List<Dependency> found = notations(rest, dir, out, scope);
            if (found.isEmpty() && !rest.trim().isEmpty()) {
                out.notes.add("読めない依存の宣言: " + line.trim());
            }
            for (Dependency d : found) {
                out.add(d);
            }
        }
    }

    /** 1 行の引数部分から座標を取り出す。文字列の座標、map 形式、版カタログの参照 */
    private List<Dependency> notations(String args, Path dir, Declared out, String scope) {
        List<Dependency> found = new ArrayList<>();
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
            Dependency d = coordinates(literal, scope);
            if (d != null) {
                found.add(d);
            }
        }
        return found;
    }

    /** "g:a:v[:classifier][@ext]" を依存にする。座標の形でなければ null */
    static Dependency coordinates(String literal, String scope) {
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

    private static Dependency dependency(String group, String name, String version, String classifier,
                                                  String ext, String scope) {
        String type = ext.isEmpty() ? "jar" : ext;
        return new Dependency(group, name, version, type, classifier, scope, false, List.of(), "");
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
}
