// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gradle ビルドの「構成」を読む: settings.gradle の include（プロジェクトパス → ディレクトリ）、
 * gradle.properties とビルドファイルの文字列代入（{@code $var} を埋める材料）、版カタログ。
 * 依存の宣言そのものは {@link GradleBuild} が読む。
 */
final class GradleSettings {

    private GradleSettings() {
    }

    /**
     * settings.gradle(.kts) のあるディレクトリ。Gradle 自身と同じく上位へ探す。無ければ dir。
     *
     * project.root で打ち切らないのは、project.root にサブプロジェクト（{@code app/}）を指定し、
     * settings.gradle はその親にある使い方が普通にあるため（回帰テストの gradle ケースがそれ）。
     * ただし project.root の外で見つかったときは、無関係な上位フォルダの settings を
     * 拾っている可能性があるので、呼び出し側（{@link GradleBuild#resolve}）が注記に残す
     */
    static Path settingsRoot(Path dir) {
        for (Path d = dir.toAbsolutePath().normalize(); d != null; d = d.getParent()) {
            if (BuildTool.hasGradleSettings(d)) {
                return d;
            }
        }
        return dir;
    }

    /** settings の include から、プロジェクトのパス（:a:b）→ ディレクトリ */
    static Map<String, Path> parseSettings(Path root) {
        Map<String, Path> dirs = new LinkedHashMap<>();
        String text = GradleScripts.readScript(root, "settings.gradle", "settings.gradle.kts");
        Matcher include = Pattern.compile("\\binclude\\s*\\(?((?:\\s*['\"][^'\"]+['\"]\\s*,?)+)").matcher(text);
        while (include.find()) {
            Matcher q = GradleBuild.QUOTED.matcher(include.group(1));
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

    static String normalizeProjectPath(String raw) {
        String p = raw.trim();
        return p.startsWith(":") ? p : ":" + p;
    }

    /** このディレクトリのプロジェクトパス（ルートからの相対。ルート自身は ":"） */
    static String projectPath(Path root, Map<String, Path> projectDirs, Path dir) {
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
    static void loadProperties(Path root, Path dir, Map<String, String> properties) {
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
            String text = GradleScripts.readScript(d, "build.gradle", "build.gradle.kts");
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
    static Map<String, GradleCatalog> parseCatalogs(Path root) {
        Map<String, GradleCatalog> out = new LinkedHashMap<>();
        Map<String, Path> files = new LinkedHashMap<>();
        Path defaultCatalog = root.resolve("gradle").resolve("libs.versions.toml");
        if (Files.isRegularFile(defaultCatalog)) {
            files.put("libs", defaultCatalog);
        }
        String settings = GradleScripts.readScript(root, "settings.gradle", "settings.gradle.kts");
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
}
