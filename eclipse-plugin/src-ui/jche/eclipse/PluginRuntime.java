// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import java.io.File;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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

import jche.eclipse.server.JavaLocator;

/**
 * 子プロセスを起動するのに要るもの（解析本体の jar と、走らせる JDK）をそろえる。
 *
 * <p>解析はバンドルに同梱した {@code lib/jche-core.jar} と {@code lib/jdt/*.jar} で行う。
 * これらは <b>Bundle-ClassPath に載っていない</b>ので Eclipse は読み込まない。
 * ここで実体のパスを取り出し、子プロセスの {@code -cp} にだけ渡す
 * （docs/out-of-process-analysis-design.md §5）。
 */
final class PluginRuntime {

    private PluginRuntime() {
    }

    /**
     * 子プロセスに渡すクラスパス（解析本体＋JDT 一式）。
     *
     * <p>JDT は既定では同梱のものを使う。設定でフォルダが指定されていればそちらを使う
     * （閉域で新しい JDT を置いたときなど）。
     */
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
        File configured = JchePreferences.jdtFolder();
        File jdtDir = (configured != null) ? configured : fileOf(bundle, "lib/jdt");
        if (configured != null && !configured.isDirectory()) {
            throw new IOException("設定で指定された JDT のフォルダがありません: " + configured);
        }
        File[] jars = (jdtDir == null) ? null : jdtDir.listFiles(new java.io.FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(".jar");
            }
        });
        if (jars == null || jars.length == 0) {
            throw new IOException((configured != null)
                    ? "指定されたフォルダに jar がありません: " + configured
                    : "同梱の JDT（lib/jdt/*.jar）がバンドルに入っていません");
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
     * <p>探す順は「設定 → JAVA_HOME → 取得した JDK → Eclipse を動かしている JVM → PATH の java」。
     * 25 を優先し、無ければ 17 以上で一番新しいものを使う（{@link JavaLocator}）。
     * どれも駄目なら null を返し、呼び出し側が取得を促す。
     *
     * @param explicit 設定より優先して試すもの。無ければ null
     */
    static JavaLocator.Found findJava(File explicit) {
        List<File> candidates = new ArrayList<>();
        if (explicit != null) {
            candidates.add(explicit);
        }
        File configured = JchePreferences.jdk();
        if (configured != null) {
            candidates.add(configured);
        }
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.trim().isEmpty()) {
            candidates.add(new File(javaHome));
        }
        // 取得した JDK（設定に残っていなくても拾えるように）
        File downloaded = JdkDownloads.latestIn(new File(stateLocation(), "jdk"));
        if (downloaded != null) {
            candidates.add(downloaded);
        }
        String running = System.getProperty("java.home");
        if (running != null && !running.trim().isEmpty()) {
            candidates.add(new File(running));
        }
        candidates.add(new File(JavaLocator.isWindows() ? "java.exe" : "java"));
        return JavaLocator.choose(candidates);
    }

    /** 解析プロセスへ渡す JVM 引数（設定そのまま） */
    static List<String> vmArguments() {
        return JchePreferences.vmArguments();
    }

    /** プラグインの状態フォルダ（キャッシュと一時ファイルの置き場所） */
    static File stateLocation() {
        return Platform.getStateLocation(Platform.getBundle(JchePlugin.PLUGIN_ID)).toFile();
    }

    static void logWarning(String message, Throwable cause) {
        JchePlugin.log(IStatus.WARNING, message, cause);
    }
}
