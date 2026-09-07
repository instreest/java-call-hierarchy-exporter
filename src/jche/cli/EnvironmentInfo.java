// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cli;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.jdt.core.JavaCore;

/**
 * 実行環境の状態を表示するための読み取り（実行中の JDK、JDT の jar、フォルダの大きさ）。
 * 表示のためだけのものなので、失敗しても例外にせず「不明」で済ませる。
 */
public final class EnvironmentInfo {

    private EnvironmentInfo() {
    }

    public static String javaVersion() {
        return System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")";
    }

    public static Path javaHome() {
        return Paths.get(System.getProperty("java.home")).toAbsolutePath().normalize();
    }

    /** JDT（org.eclipse.jdt.core）の jar の場所。分からなければ null */
    public static Path jdtJar() {
        try {
            CodeSource cs = JavaCore.class.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) {
                return null;
            }
            return Paths.get(cs.getLocation().toURI()).toAbsolutePath().normalize();
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    public static long maxHeapMb() {
        return Runtime.getRuntime().maxMemory() / (1024 * 1024);
    }

    /** フォルダの合計サイズ（バイト）。無ければ -1 */
    public static long sizeOf(Path dir) {
        if (!Files.isDirectory(dir)) {
            return -1;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    public static String humanSize(long bytes) {
        if (bytes < 0) {
            return "（無し）";
        }
        if (bytes < 1024L * 1024) {
            return (bytes + 1023) / 1024 + " KB";
        }
        if (bytes < 1024L * 1024 * 1024) {
            return bytes / (1024 * 1024) + " MB";
        }
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /** JBang の置き場所にある取得済み JDK の版（{@code cache/jdks/<版>}）。無ければ空 */
    public static List<String> installedJdks(Path jbangDir) {
        List<String> out = new ArrayList<>();
        Path jdks = jbangDir.resolve("cache").resolve("jdks");
        String cacheEnv = System.getenv("JBANG_CACHE_DIR");
        if (cacheEnv != null && !cacheEnv.isEmpty()) {
            jdks = Paths.get(cacheEnv).resolve("jdks");
        }
        if (!Files.isDirectory(jdks)) {
            return out;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(jdks)) {
            for (Path p : ds) {
                if (Files.isDirectory(p) && !p.getFileName().toString().endsWith(".tmp")) {
                    out.add(p.getFileName().toString());
                }
            }
        } catch (IOException e) {
            // 表示できないだけ
        }
        out.sort(null);
        return out;
    }

    /** 解析キャッシュ（{@code .cache/<プロジェクト名>_<ハッシュ>/}）の一覧。フォルダ名とサイズ */
    public static List<String> cacheEntries(Path cacheRoot) {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(cacheRoot)) {
            return out;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(cacheRoot)) {
            for (Path p : ds) {
                if (Files.isDirectory(p)) {
                    out.add(p.getFileName() + "  " + humanSize(sizeOf(p)));
                }
            }
        } catch (IOException e) {
            // 表示できないだけ
        }
        out.sort(null);
        return out;
    }
}
