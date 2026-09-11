// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.config;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Gradle のロックファイルを読む。あれば解決済みの依存（推移的なものも）がそのまま揃っているので、
 * ビルドファイルの宣言より優先して使う。
 */
final class GradleLockfile {

    private GradleLockfile() {
    }

    /**
     * gradle.lockfile（Gradle 7 以降の 1 ファイル形式）か gradle/dependency-locks/*.lockfile（構成ごと）。
     * コンパイル・実行時のクラスパスの構成のものを取る。
     *
     * @return ロックファイルがあったか
     */
    static boolean read(Path dir, GradleBuild.Declared into) {
        Path single = dir.resolve("gradle.lockfile");
        boolean found = false;
        if (Files.isRegularFile(single)) {
            found = true;
            for (String line : GradleScripts.readLines(single)) {
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
                    for (String line : GradleScripts.readLines(f)) {
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
    private static void addCoordinates(GradleBuild.Declared into, String coordinates, String scope) {
        Dependency d = GradleBuild.coordinates(coordinates.trim(), scope);
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
}
