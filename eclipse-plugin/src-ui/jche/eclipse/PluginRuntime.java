// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

/**
 * 子プロセスを起動するのに要るもの（解析本体の jar と、走らせる JDK）をそろえる。
 *
 * <p>解析はバンドルに同梱した {@code lib/jche-core.jar} と {@code lib/jdt/*.jar} で行う。
 * これらは <b>Bundle-ClassPath に載っていない</b>ので Eclipse は読み込まない。
 * ここで実体のパスを取り出し、子プロセスの {@code -cp} にだけ渡す
 * （docs/out-of-process-analysis-design.md §5）。
 */
final class PluginRuntime {

    /** 解析に使いたい JDK の版。CLI（//JAVA 25）と結果を揃えるため */
    static final int PREFERRED_JAVA = 25;
    /** 動かせる下限。これ未満は解析本体（release 17 でコンパイル）が動かない */
    static final int MINIMUM_JAVA = 17;

    private PluginRuntime() {
    }

    /** 子プロセスに渡すクラスパス（解析本体＋同梱の JDT 一式） */
    static List<File> analysisClasspath() throws IOException {
        Bundle bundle = Platform.getBundle(JchePlugin.PLUGIN_ID);
        if (bundle == null) {
            throw new IOException("プラグインのバンドルが見つかりません");
        }
        List<File> classpath = new ArrayList<>();
        File core = fileOf(bundle, "lib/jche-core.jar");
        if (core == null) {
            throw new IOException("解析本体（lib/jche-core.jar）がバンドルに入っていません");
        }
        File jdtDir = fileOf(bundle, "lib/jdt");
        File[] jars = (jdtDir == null) ? null : jdtDir.listFiles((dir, name) -> name.endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            throw new IOException("同梱の JDT（lib/jdt/*.jar）がバンドルに入っていません");
        }
        Arrays.sort(jars);
        Collections.addAll(classpath, jars);
        classpath.add(core);
        return classpath;
    }

    /** バンドル内のパスを、ファイルシステム上の実体に直す。jar のままなら展開される */
    private static File fileOf(Bundle bundle, String path) throws IOException {
        URL url = FileLocator.find(bundle, new org.eclipse.core.runtime.Path(path), null);
        if (url == null) {
            return null;
        }
        URL resolved = FileLocator.toFileURL(url);
        return new File(resolved.getPath());
    }

    /**
     * 解析に使う java を決める。
     *
     * <p>探索順は「JAVA_HOME → Eclipse を動かしている JVM → PATH の java」。
     * 25 が見つからなくても 17 以上なら使う（結果が CLI と少しずれる可能性はログに残す）。
     * どれも 17 未満なら null を返し、呼び出し側が取得を促す。
     */
    static File findJava() {
        List<File> candidates = new ArrayList<>();
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            candidates.add(new File(javaHome));
        }
        String running = System.getProperty("java.home");
        if (running != null && !running.isBlank()) {
            candidates.add(new File(running));
        }
        File best = null;
        int bestVersion = 0;
        for (File home : candidates) {
            File java = executableIn(home);
            int version = versionOf(java);
            if (version >= MINIMUM_JAVA && version > bestVersion) {
                best = java;
                bestVersion = version;
                if (version >= PREFERRED_JAVA) {
                    break;
                }
            }
        }
        if (best != null) {
            return best;
        }
        File onPath = new File(isWindows() ? "java.exe" : "java");
        return (versionOf(onPath) >= MINIMUM_JAVA) ? onPath : null;
    }

    private static File executableIn(File javaHome) {
        File bin = new File(javaHome, "bin");
        return new File(bin, isWindows() ? "java.exe" : "java");
    }

    /** その java の主要バージョン。分からなければ 0 */
    static int versionOf(File executable) {
        if (executable == null) {
            return 0;
        }
        try {
            Process process = new ProcessBuilder(executable.getPath(), "-version")
                    .redirectErrorStream(true).start();
            String text;
            try (InputStream in = process.getInputStream()) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            process.waitFor();
            return parseVersion(text);
        } catch (IOException e) {
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }

    /** {@code openjdk version "25.0.3"} のような出力から 25 を取り出す */
    static int parseVersion(String text) {
        Matcher matcher = Pattern.compile("version \"(\\d+)(?:\\.(\\d+))?").matcher(text);
        if (!matcher.find()) {
            return 0;
        }
        int major = Integer.parseInt(matcher.group(1));
        if (major == 1 && matcher.group(2) != null) {
            return Integer.parseInt(matcher.group(2));   // 1.8 形式
        }
        return major;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** プラグインの状態フォルダ（キャッシュと一時ファイルの置き場所） */
    static File stateLocation() {
        return Platform.getStateLocation(Platform.getBundle(JchePlugin.PLUGIN_ID)).toFile();
    }

    static void logWarning(String message, Throwable cause) {
        JchePlugin.log(IStatus.WARNING, message, cause);
    }
}
