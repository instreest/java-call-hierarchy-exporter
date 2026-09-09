// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import jche.util.Log;

/**
 * plugin.folders に置かれた拡張を読み込むためのクラスローダを作る。
 *
 * <h2>なぜ .java をその場でコンパイルするのか</h2>
 * このツール自身が JBang でソースのまま動くので、拡張だけ jar を作らせるのは釣り合わない。
 * 利用者は plugin.folders のフォルダに {@code .java} を置くだけでよく、Maven も Gradle も要らない。
 * すでに jar があるなら、同じフォルダに置けばそのまま使える（コンパイルは .java があるときだけ走る）。
 *
 * <h2>コンパイル時のクラスパス</h2>
 * 拡張は {@code jche.extension.*} と JDT の AST を使うため、このツール自身のクラスパス
 * （{@code java.class.path}。JBang が組み立てたもの）をそのまま渡す。同じフォルダの jar も足す。
 *
 * <h2>クラスローダの親</h2>
 * このクラスのクラスローダを親にする。そうしないと拡張が実装する {@code TypeCandidateProvider} が
 * 別のクラスローダから読まれた同名の別クラスになり、{@code ClassCastException} になる。
 * 逆に、拡張が独自に持ち込んだ jar は親に無いのでこちらで読まれる（親優先の通常の委譲）。
 */
public final class PluginClassLoaders {

    /** 出力先（キャッシュフォルダ配下）。実行のたびに作り直す */
    private static final String CLASSES_DIR_NAME = "plugin-classes";

    /** 同じ設定で2回作らないための覚え書き。フェーズAとフェーズBで別々に読み込まれるため */
    private static final Map<String, ClassLoader> CACHE = new HashMap<>();

    private PluginClassLoaders() {
    }

    /**
     * plugin.folders から作ったクラスローダ。指定が無ければこのクラスのクラスローダ
     * （＝従来どおり、ツール自身のクラスパスだけを見る）。
     */
    public static synchronized ClassLoader forConfig(Config config) {
        if (config.pluginFolders.isEmpty()) {
            return PluginClassLoaders.class.getClassLoader();
        }
        String key = config.pluginFolders.toString() + "|" + config.cacheDir;
        ClassLoader cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        ClassLoader loader = build(config);
        CACHE.put(key, loader);
        return loader;
    }

    private static ClassLoader build(Config config) {
        List<Path> jars = new ArrayList<>();
        List<Path> sources = new ArrayList<>();
        List<Path> classDirs = new ArrayList<>();
        for (Path folder : config.pluginFolders) {
            if (!Files.isDirectory(folder)) {
                Log.warn("plugin.folders のフォルダがありません: " + folder);
                continue;
            }
            classDirs.add(folder);   // フォルダ直下に .class を置く使い方も許す
            collect(folder, jars, sources);
        }
        List<URL> urls = new ArrayList<>();
        if (!sources.isEmpty()) {
            Path out = compile(config, sources, jars);
            if (out != null) {
                urls.add(toUrl(out));
            }
        }
        for (Path dir : classDirs) {
            urls.add(toUrl(dir));
        }
        for (Path jar : jars) {
            urls.add(toUrl(jar));
        }
        urls.removeIf(u -> u == null);
        return new URLClassLoader(urls.toArray(new URL[0]), PluginClassLoaders.class.getClassLoader());
    }

    /** フォルダ配下（サブフォルダも見る）の *.jar と *.java を集める。順序は名前順で固定する */
    private static void collect(Path folder, List<Path> jars, List<Path> sources) {
        List<Path> found = new ArrayList<>();
        try {
            Files.walkFileTree(folder, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    if (name.endsWith(".jar") || name.endsWith(".java")) {
                        found.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            Log.warn("plugin.folders を読めません: " + folder + " (" + e + ")");
            return;
        }
        found.sort(Comparator.comparing(Path::toString));
        for (Path p : found) {
            (p.getFileName().toString().endsWith(".jar") ? jars : sources).add(p);
        }
    }

    /**
     * 拡張の .java をコンパイルする。
     *
     * @return クラスの出力フォルダ。コンパイラが使えない・失敗した場合は null
     */
    private static Path compile(Config config, List<Path> sources, List<Path> jars) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            // JRE で動いている場合。JBang は JDK を取ってくるので通常は起きない
            Log.warn("拡張の .java をコンパイルできません（JDK ではなく JRE で動いています）。"
                    + "コンパイル済みの .class か .jar を plugin.folders に置いてください");
            return null;
        }
        Path out = config.cacheDir.resolve(CLASSES_DIR_NAME);
        try {
            deleteRecursively(out);   // 消した .java のクラスが残らないよう、毎回作り直す
            Files.createDirectories(out);
        } catch (IOException e) {
            Log.warn("拡張のコンパイル先を作れません: " + out + " (" + e + ")");
            return null;
        }
        StringBuilder classpath = new StringBuilder(System.getProperty("java.class.path", ""));
        for (Path jar : jars) {
            classpath.append(java.io.File.pathSeparator).append(jar);
        }
        List<String> args = new ArrayList<>(List.of(
                "-classpath", classpath.toString(),
                "-d", out.toString(),
                "-encoding", "UTF-8",
                "-nowarn"));
        for (Path src : sources) {
            args.add(src.toString());
        }
        Log.info("[plugin] コンパイル: " + sources.size() + " ファイル -> " + out);
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        int code = compiler.run(null, null, err, args.toArray(new String[0]));
        if (code != 0) {
            // 黙って進むと「拡張を置いたのに効かない」ことに気づけないので、必ず出す
            Log.warn("拡張のコンパイルに失敗しました。拡張なしで続行します:");
            for (String line : err.toString(StandardCharsets.UTF_8).split("\\R")) {
                if (!line.isBlank()) {
                    Log.warn("  " + line);
                }
            }
            return null;
        }
        return out;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static URL toUrl(Path path) {
        try {
            return path.toUri().toURL();
        } catch (MalformedURLException e) {
            Log.warn("拡張のパスを URL にできません: " + path + " (" + e + ")");
            return null;
        }
    }
}
