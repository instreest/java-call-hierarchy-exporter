// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.config;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import jche.util.Messages;
import jche.util.Warnings;

/**
 * Gradle のロックファイルを読む。あれば解決済みの依存（推移的なものも）がそのまま揃っているので、
 * ビルドファイルの宣言より優先して使う。
 *
 * <p>優先するのは<b>クラスパスの構成（compileClasspath / runtimeClasspath）がロックされているとき</b>だけ。
 * gradle.lockfile は構成ごとにロックするので、ほかの構成（annotationProcessor やビルドスクリプトの classpath）
 * しか載っていないファイルもある。そのファイルを「ロックファイルがある」と扱うと、build.gradle の宣言を捨てた
 * うえで依存が 1 件も入らず、依存 jar が黙って空になる。そのときは警告して宣言のほうを使う。
 */
final class GradleLockfile {

    private GradleLockfile() {
    }

    /**
     * gradle.lockfile（Gradle 7 以降の 1 ファイル形式）か gradle/dependency-locks/*.lockfile（構成ごと）。
     * コンパイル・実行時のクラスパスの構成のものを取る。
     *
     * @return クラスパスの構成のロックを読んだか（依存の行か、依存が無いことを示す {@code empty=} の行）。
     *         ロックファイルはあるがクラスパスの構成が無いときは警告して false
     */
    static boolean read(Path dir, GradleBuild.Declared into) {
        boolean found = false;
        List<Path> present = new ArrayList<>();
        Path single = dir.resolve("gradle.lockfile");
        if (Files.isRegularFile(single)) {
            present.add(single);
            for (String line : GradleScripts.readLines(single)) {
                String l = line.trim();
                int eq = l.indexOf('=');
                if (l.isEmpty() || l.startsWith("#") || eq < 0) {
                    continue;
                }
                if (l.startsWith("empty=")) {
                    // 依存の無い構成の一覧。クラスパスの構成が載っていれば「依存が無い」が解決済みの結果
                    if (isClasspathConfiguration(l.substring(eq + 1))) {
                        found = true;
                    }
                    continue;
                }
                if (isClasspathConfiguration(l.substring(eq + 1))) {
                    found = true;
                    addCoordinates(into, l.substring(0, eq), "compile");
                }
            }
        }
        Path locks = dir.resolve("gradle").resolve("dependency-locks");
        if (Files.isDirectory(locks)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(locks, "*.lockfile")) {
                for (Path f : ds) {
                    present.add(f);
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
        if (!found && !present.isEmpty()) {
            // 宣言を捨てて依存が空になるより、宣言を使うほうが安全側。何が起きたかは warnings.txt に載せる
            Warnings.warn(Warnings.Topic.DEPENDENCIES,
                    Messages.format("config.gradle.lockfileNoClasspath", present.get(0)));
        }
        return found;
    }

    /** ロックファイルの 1 行（g:a:v）を宣言に足す */
    private static void addCoordinates(GradleBuild.Declared into, String coordinates, String scope) {
        Dependency d = GradleBuild.coordinates(coordinates.trim(), scope);
        if (d != null && !d.version().isEmpty()) {
            into.add(d);
        } else {
            into.warnings.add(Messages.format("config.gradle.lockfileBadRow", coordinates.trim()));
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
