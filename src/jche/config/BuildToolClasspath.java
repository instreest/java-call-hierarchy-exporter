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

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import jche.util.Log;

/**
 * library.folders が空欄のとき、Maven / Gradle を実行して「解決済みの依存 jar のクラスパス」を取得する。
 *
 * 依存の解決（推移的依存の展開・版の決定・リポジトリからの取得）はこのツールでは行わず、
 * ビルドツールにやらせる。受け取るのはビルドツールがコンパイル時に使うクラスパスそのもので、
 * jar はローカルリポジトリ（~/.m2/repository、~/.gradle/caches）に置かれたままのパスで受け取る。
 * コピーはしない。
 * <ul>
 *   <li>Maven … maven-dependency-plugin の build-classpath ゴールを、モジュールごとに 1 ファイルへ書かせる</li>
 *   <li>Gradle … 初期化スクリプト（--init-script）で全プロジェクトに小さなタスクを足し、
 *       各ソースセットの compileClasspath を 1 行 1 エントリで書かせる</li>
 * </ul>
 * ビルドツールの標準出力・標準エラーはそのまま画面に流す（失敗の理由をそのまま見せるため）。
 * 書かせたファイルは cache.folders の下（build-classpath/）に残し、何が渡ったかを後から確認できる。
 *
 * ビルドツールは、project.root にビルドファイル（pom.xml / build.gradle）があればそこで実行する。
 * 無ければ各ソースフォルダから上位へ辿って最初にビルドファイルが見つかったディレクトリで実行する
 * （project.root が複数プロジェクトを束ねたフォルダのとき）。
 *
 * 取得したクラスパスのうち、存在する jar とクラスフォルダ（Maven のリアクタや Gradle の
 * プロジェクト依存が指す target/classes、build/classes/java/main）だけを使う。
 */
public final class BuildToolClasspath {

    /** cache.folders の下に作る作業フォルダ */
    static final String WORK_DIR_NAME = "build-classpath";
    /** Gradle の初期化スクリプトで登録するタスク名 */
    static final String GRADLE_TASK = "jcheCompileClasspath";
    static final String GRADLE_INIT_SCRIPT = "jche-classpath.init.gradle";
    static final String GRADLE_OUTPUT = "gradle-classpath.txt";
    static final String MAVEN_OUTPUT_DIR = "maven";
    /** Maven にモジュールごとのファイル名を作らせる式（Maven が ${...} を展開する） */
    static final String MAVEN_OUTPUT_NAME = "${project.groupId}-${project.artifactId}.txt";

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    private BuildToolClasspath() {
    }

    /**
     * @param config        設定（library.build.tool / library.maven.args / library.gradle.args / cache.folders）
     * @param projectRoot   project.root
     * @param sourceFolders 解析対象のソースフォルダ（project.root にビルドファイルが無いとき、
     *                      各ソースフォルダの上位からビルドファイルを探す）
     * @return 存在する jar とクラスフォルダ（ビルドツールが並べた順）。取得できなければ空
     */
    public static List<Path> resolve(Config config, Path projectRoot, List<Path> sourceFolders) {
        if ("none".equals(config.libraryBuildTool)) {
            Log.info("依存jar: library.folders は空欄ですが library.build.tool=none のため、"
                    + "ビルドツールからの自動取得はしません");
            return List.of();
        }
        List<Path> candidates = candidateDirs(projectRoot, sourceFolders);
        if (candidates.isEmpty()) {
            Log.info("依存jar: library.folders が空欄で、pom.xml / build.gradle も見つからないため、"
                    + "依存jar無しで解析します（探した場所: " + projectRoot + " とソースフォルダの上位）");
            return List.of();
        }
        Log.info("依存jar: library.folders が空欄のため、ビルドツールが解決したクラスパスを取得します");
        Set<Path> entries = new LinkedHashSet<>();
        for (Path dir : candidates) {
            BuildTool.Detection detection = select(config, dir);
            if (detection != null) {
                entries.addAll(new Run(config, detection, dir).execute());
            }
        }
        return new ArrayList<>(entries);
    }

    /**
     * ビルドツールを実行するディレクトリ。project.root にビルドファイルがあればそこだけ
     * （マルチモジュールのルートならモジュールもまとめて解決される）。無ければ各ソースフォルダの
     * 上位で最初にビルドファイルが見つかった場所（重複は除く）。
     */
    static List<Path> candidateDirs(Path projectRoot, List<Path> sourceFolders) {
        if (BuildTool.hasBuildFile(projectRoot)) {
            return List.of(projectRoot);
        }
        Set<Path> found = new LinkedHashSet<>();
        for (Path sf : sourceFolders) {
            for (Path dir = sf; dir != null && dir.startsWith(projectRoot); dir = dir.getParent()) {
                if (BuildTool.hasBuildFile(dir)) {
                    found.add(dir);
                    break;
                }
            }
        }
        return new ArrayList<>(found);
    }

    /** library.build.tool の明示があればそれ、無ければビルドファイルから検出する */
    private static BuildTool.Detection select(Config config, Path dir) {
        String forced = config.libraryBuildTool;
        if ("maven".equals(forced) || "gradle".equals(forced)) {
            BuildTool tool = BuildTool.valueOf(forced.toUpperCase(Locale.ROOT));
            if (!tool.hasBuildFileIn(dir)) {
                Log.warn("依存jar: library.build.tool=" + forced + " ですが、" + dir + " に "
                        + tool.buildFileNames + " がありません。このディレクトリは飛ばします");
                return null;
            }
            return new BuildTool.Detection(tool, "library.build.tool=" + forced + " の指定による");
        }
        return BuildTool.detect(dir);
    }

    /** 1 つのディレクトリに対する 1 回のビルドツール実行 */
    private static final class Run {

        private final Config config;
        private final BuildTool tool;
        private final String reason;
        /** ビルドツールを実行するディレクトリ（pom.xml / build.gradle のある場所） */
        private final Path dir;
        /** ビルドツールにファイルを書かせる作業フォルダ（cache.folders/build-classpath） */
        private final Path workDir;

        Run(Config config, BuildTool.Detection detection, Path dir) {
            this.config = config;
            this.tool = detection.tool();
            this.reason = detection.reason();
            this.dir = dir;
            this.workDir = config.cacheFile.toAbsolutePath().getParent().resolve(WORK_DIR_NAME);
        }

        List<Path> execute() {
            Log.info("  " + tool.displayName + ": " + dir + "（" + reason + "）");
            try {
                List<String> command = (tool == BuildTool.MAVEN) ? mavenCommand() : gradleCommand();
                if (command == null) {
                    return List.of();    // 実行ファイルが無い。理由は出力済み
                }
                Log.info("  実行: " + String.join(" ", command));
                long start = System.nanoTime();
                int exit = run(command);
                double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
                List<String> raw = (tool == BuildTool.MAVEN) ? readMavenOutputs() : readGradleOutput();
                List<Path> entries = keepExisting(raw);
                if (exit != 0) {
                    if (entries.isEmpty()) {
                        Log.warn("依存jar: " + tool.displayName + " の実行に失敗しました（終了コード " + exit
                                + "、" + String.format(Locale.ROOT, "%.1f", seconds) + " 秒）。上の出力を確認してください");
                        printHelp();
                        return List.of();
                    }
                    Log.warn("依存jar: " + tool.displayName + " の実行は失敗しました（終了コード " + exit
                            + "）が、取得できた分のクラスパスは使います。失敗したモジュールの依存は不足しているかもしれません");
                    printHelp();
                }
                Log.info("  取得: " + summarize(entries) + "（" + tool.displayName + "、"
                        + String.format(Locale.ROOT, "%.1f", seconds) + " 秒。一覧は " + workDir + "）");
                return entries;
            } catch (IOException e) {
                Log.warn("依存jar: " + tool.displayName + " を実行できませんでした: " + e);
                printHelp();
                return List.of();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.warn("依存jar: " + tool.displayName + " の実行が中断されました");
                return List.of();
            }
        }

        private void printHelp() {
            Log.info("   依存jarを手で集める場合は、そのフォルダを library.folders に指定してください");
            Log.info("   （Maven なら mvn dependency:copy-dependencies -DoutputDirectory=lib で集められます）。");
            Log.info("   閉域ネットワークでは library.maven.args=-o / library.gradle.args=--offline を検討してください");
        }

        // ---- Maven ------------------------------------------------------------------

        /**
         * Maven のコマンド行。
         * <pre>
         *   mvn -B -q -fae [library.maven.args] org.apache.maven.plugins:maven-dependency-plugin:build-classpath
         *       -Dmdep.outputFile=(作業フォルダ)/maven/${project.groupId}-${project.artifactId}.txt
         * </pre>
         * -fae は、兄弟モジュールが未インストールで一部のモジュールが解決できなくても
         * 残りのモジュールを続けるため。プラグインはグループとアーティファクトを明示し、版は書かない
         * （Maven 自身の super POM の pluginManagement にある版が使われる。プレフィックス解決のための
         * メタデータ取得が要らないので、オフラインでも動きやすい）。
         * outputFile の ${...} は Maven がモジュールごとに展開する（appendOutput はコマンド行から
         * 指定できず、1 ファイルに書かせるとモジュールごとに上書きされるため、ファイルを分ける）。
         */
        private List<String> mavenCommand() throws IOException {
            List<String> cmd = executable(findMavenWrapperRoot(dir), "mvnw", "mvn");
            if (cmd == null) {
                return null;
            }
            Path outDir = workDir.resolve(MAVEN_OUTPUT_DIR);
            Files.createDirectories(outDir);
            clearTextFiles(outDir);
            cmd.add("-B");
            cmd.add("-q");
            cmd.add("-fae");
            cmd.addAll(config.mavenArgs);
            cmd.add("org.apache.maven.plugins:maven-dependency-plugin:build-classpath");
            cmd.add("-Dmdep.outputFile=" + outDir.resolve(MAVEN_OUTPUT_NAME));
            return cmd;
        }

        /**
         * mvnw を探す。実行ディレクトリから、pom.xml のあるディレクトリが続く限り上位へ辿る
         * （マルチモジュールの親を辿る。ラッパーは通常ルートにある）。無ければ null
         */
        private static Path findMavenWrapperRoot(Path start) {
            for (Path d = start; d != null && BuildTool.hasPom(d); d = d.getParent()) {
                if (firstExisting(d, "mvnw", "mvnw.cmd", "mvnw.bat") != null) {
                    return d;
                }
            }
            return null;
        }

        /** モジュールごとのファイルを読み、パス区切り（; または :）で分ける */
        private List<String> readMavenOutputs() throws IOException {
            List<String> out = new ArrayList<>();
            List<Path> files = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(workDir.resolve(MAVEN_OUTPUT_DIR), "*.txt")) {
                for (Path p : ds) {
                    files.add(p);
                }
            }
            files.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
            for (Path file : files) {
                for (String line : decode(Files.readAllBytes(file)).split("\\R")) {
                    for (String entry : line.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                        if (!entry.trim().isEmpty()) {
                            out.add(entry.trim());
                        }
                    }
                }
            }
            return out;
        }

        // ---- Gradle -----------------------------------------------------------------

        /**
         * Gradle のコマンド行。
         * <pre>
         *   gradle -q --init-script (作業フォルダ)/jche-classpath.init.gradle [library.gradle.args] jcheCompileClasspath
         * </pre>
         * 初期化スクリプトは毎回書き直す（出力先のパスを埋め込むため）。
         */
        private List<String> gradleCommand() throws IOException {
            Path root = findGradleRoot(dir);
            List<String> cmd = executable(root, "gradlew", "gradle");
            if (cmd == null) {
                return null;
            }
            Files.createDirectories(workDir);
            Path output = workDir.resolve(GRADLE_OUTPUT);
            Files.deleteIfExists(output);
            Path init = workDir.resolve(GRADLE_INIT_SCRIPT);
            Files.writeString(init, initScript(output), StandardCharsets.UTF_8);
            cmd.add("-q");
            cmd.add("--init-script");
            cmd.add(init.toString());
            cmd.addAll(config.gradleArgs);
            cmd.add(GRADLE_TASK);
            return cmd;
        }

        /**
         * gradlew のあるディレクトリ。実行ディレクトリから上位へ、settings.gradle(.kts) か gradlew が
         * 見つかるまで辿る（Gradle 自身が settings を探すのと同じ動き）。見つからなければ実行ディレクトリ
         */
        private static Path findGradleRoot(Path start) {
            for (Path d = start; d != null; d = d.getParent()) {
                if (firstExisting(d, "gradlew", "gradlew.bat") != null || BuildTool.hasGradleSettings(d)) {
                    return d;
                }
            }
            return start;
        }

        /**
         * 初期化スクリプト。java-base が適用された全プロジェクトにタスクを登録し、各ソースセットの
         * compileClasspath 構成を解決して出力ファイルへ 1 行 1 エントリで追記する。
         * 解決は設定時に済ませて文字列だけを持つ（実行時に Project に触らないので、
         * 構成キャッシュ（configuration cache）が有効なビルドでも動く）。
         * 解決できない依存があってもそこで止めず、解決できた分を書く（lenient）。
         * 出力先は ASCII だけで埋め込む（非 ASCII は \\uXXXX。スクリプトの文字コードに依存しないため）。
         */
        static String initScript(Path output) {
            return String.join("\n",
                    "// Generated by java-call-hierarchy-exporter. Writes the compile classpath of every",
                    "// Java source set to a file, one entry per line (UTF-8). Safe to delete.",
                    "allprojects { p ->",
                    "    p.plugins.withId('java-base') {",
                    "        p.tasks.register('" + GRADLE_TASK + "') { t ->",
                    "            def paths = new LinkedHashSet<String>()",
                    "            p.sourceSets.each { ss ->",
                    "                def conf = p.configurations.findByName(ss.compileClasspathConfigurationName)",
                    "                if (conf == null) { return }",
                    "                def files",
                    "                try {",
                    "                    files = conf.incoming.artifactView { it.lenient(true) }.files.files",
                    "                } catch (Throwable e) {",
                    "                    System.err.println('jche: lenient resolution failed for ' + conf.name + ': ' + e)",
                    "                    files = conf.files",
                    "                }",
                    "                files.each { paths.add(it.absolutePath) }",
                    "            }",
                    "            def out = new File(" + groovyString(output.toString()) + ")",
                    "            t.doLast {",
                    "                out.append(paths.join('\\n') + '\\n', 'UTF-8')",
                    "            }",
                    "        }",
                    "    }",
                    "}",
                    "");
        }

        /** Groovy の単一引用符文字列リテラル。非 ASCII は \\uXXXX に逃がす */
        static String groovyString(String s) {
            StringBuilder sb = new StringBuilder("'");
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\\' || c == '\'') {
                    sb.append('\\').append(c);
                } else if (c < 0x20 || c > 0x7e) {
                    sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                } else {
                    sb.append(c);
                }
            }
            return sb.append('\'').toString();
        }

        private List<String> readGradleOutput() throws IOException {
            Path output = workDir.resolve(GRADLE_OUTPUT);
            List<String> out = new ArrayList<>();
            if (!Files.isRegularFile(output)) {
                return out;
            }
            for (String line : decode(Files.readAllBytes(output)).split("\\R")) {
                if (!line.trim().isEmpty()) {
                    out.add(line.trim());
                }
            }
            return out;
        }

        // ---- 共通 -------------------------------------------------------------------

        /**
         * 実行するコマンドの先頭部分。ラッパー（wrapperDir 配下の mvnw / gradlew）があればそれ、
         * 無ければ PATH 上のコマンド。どちらも無ければ警告して null。
         *
         * Windows では cmd.exe /c 経由で起動する（ラッパーも PATH 上のものも .cmd / .bat のため）。
         * ラッパーは実行ディレクトリからの相対パスで渡す。ラッパーは実行ディレクトリ自身か
         * その上位にあるので相対パスは ".." だけでできており、cmd.exe が引用符付きの先頭トークンを
         * 誤って解釈する問題（パスに空白があるとき）を避けられる。
         */
        private List<String> executable(Path wrapperDir, String wrapperName, String commandName) {
            List<String> cmd = new ArrayList<>();
            if (WINDOWS) {
                Path wrapper = (wrapperDir == null) ? null
                        : firstExisting(wrapperDir, wrapperName + ".cmd", wrapperName + ".bat");
                cmd.add("cmd.exe");
                cmd.add("/c");
                if (wrapper != null) {
                    cmd.add(dir.relativize(wrapper).toString());
                    return cmd;
                }
                if (findOnPath(commandName) != null) {
                    cmd.add(commandName);
                    return cmd;
                }
            } else {
                Path wrapper = (wrapperDir == null) ? null : firstExisting(wrapperDir, wrapperName);
                if (wrapper != null) {
                    if (!Files.isExecutable(wrapper)) {
                        cmd.add("sh");    // 実行権限が落ちていても動かす（Windows で checkout したリポジトリ等）
                    }
                    cmd.add(wrapper.toString());
                    return cmd;
                }
                if (findOnPath(commandName) != null) {
                    cmd.add(commandName);
                    return cmd;
                }
            }
            Log.warn("依存jar: " + wrapperName + " も PATH 上の " + commandName + " も見つからないため、"
                    + tool.displayName + " から依存jarを取得できません（" + dir + "）");
            printHelp();
            return null;
        }

        private int run(List<String> command) throws IOException, InterruptedException {
            ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile());
            // 出力はそのまま画面へ。失敗の理由（依存が見つからない、ネットワークに出られない等）を
            // ツール側で言い換えずに見せる
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            Map<String, String> env = pb.environment();
            String javaHome = env.get("JAVA_HOME");
            if (javaHome == null || javaHome.trim().isEmpty()) {
                // jbang 経由では JAVA_HOME が無いことがある。ビルドツールは JAVA_HOME か PATH の java を
                // 使うので、無ければこのツールを動かしている JDK を渡す（設定済みなら触らない）
                env.put("JAVA_HOME", System.getProperty("java.home"));
                Log.info("  JAVA_HOME が未設定のため、このツールを実行中の JDK を使います: "
                        + System.getProperty("java.home"));
            }
            System.out.flush();
            Process process = pb.start();
            process.getOutputStream().close();
            return process.waitFor();
        }

        /**
         * 存在する jar（.jar / .zip）とディレクトリ（クラスフォルダ）だけを残す。
         * 無いもの（ビルドしていないモジュールの target/classes 等）はログに出して除く。
         */
        private List<Path> keepExisting(List<String> raw) {
            List<Path> out = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            List<String> skipped = new ArrayList<>();
            Set<Path> seen = new LinkedHashSet<>();
            for (String s : raw) {
                Path p;
                try {
                    p = Paths.get(s);
                } catch (InvalidPathException e) {
                    skipped.add(s);
                    continue;
                }
                p = (p.isAbsolute() ? p : dir.resolve(p)).normalize();
                if (!seen.add(p)) {
                    continue;
                }
                if (Files.isDirectory(p)) {
                    out.add(p);
                } else if (Files.isRegularFile(p)) {
                    String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (name.endsWith(".jar") || name.endsWith(".zip")) {
                        out.add(p);
                    } else {
                        skipped.add(s);
                    }
                } else {
                    missing.add(s);
                }
            }
            if (!missing.isEmpty()) {
                Log.info("  存在しないため除外: " + missing.size() + " 件（ビルドしていないモジュールの出力先など）");
                for (String m : missing) {
                    Log.info("    " + m);
                }
            }
            if (!skipped.isEmpty()) {
                Log.info("  jar でもフォルダでもないため除外: " + skipped.size() + " 件");
                for (String m : skipped) {
                    Log.info("    " + m);
                }
            }
            return out;
        }

        private static String summarize(List<Path> entries) {
            int jars = 0;
            int folders = 0;
            for (Path p : entries) {
                if (Files.isDirectory(p)) {
                    folders++;
                } else {
                    jars++;
                }
            }
            return "jar " + jars + " 件" + (folders > 0 ? "、クラスフォルダ " + folders + " 件" : "");
        }

        private static void clearTextFiles(Path outDir) throws IOException {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(outDir, "*.txt")) {
                for (Path p : ds) {
                    Files.delete(p);
                }
            }
        }

        private static Path firstExisting(Path dir, String... names) {
            for (String name : names) {
                Path p = dir.resolve(name);
                if (Files.isRegularFile(p)) {
                    return p;
                }
            }
            return null;
        }

        /** PATH 上のコマンド。Windows では .cmd / .bat / .exe も見る */
        private static Path findOnPath(String command) {
            String path = System.getenv("PATH");
            if (path == null) {
                return null;
            }
            String[] suffixes = WINDOWS ? new String[] {".cmd", ".bat", ".exe", ""} : new String[] {""};
            for (String entry : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (entry.trim().isEmpty()) {
                    continue;
                }
                for (String suffix : suffixes) {
                    try {
                        Path p = Paths.get(entry.trim(), command + suffix);
                        if (Files.isRegularFile(p) && (WINDOWS || Files.isExecutable(p))) {
                            return p;
                        }
                    } catch (InvalidPathException ignore) {
                        // PATH の壊れた要素は飛ばす
                    }
                }
            }
            return null;
        }

        /**
         * ビルドツールが書いたファイルの文字コードは版と JDK で違う（Maven のプラグインは
         * JDK 18 以降なら UTF-8、それより前ならプラットフォームの既定）。UTF-8 として正しく
         * 読めればそれを使い、読めなければ実行環境のネイティブ文字コードで読む
         */
        static String decode(byte[] bytes) {
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException e) {
                return new String(bytes, nativeCharset());
            }
        }

        private static Charset nativeCharset() {
            String name = System.getProperty("native.encoding");
            if (name != null) {
                try {
                    return Charset.forName(name);
                } catch (RuntimeException ignore) {
                    // 未知の名前なら既定へ
                }
            }
            return Charset.defaultCharset();
        }
    }
}
