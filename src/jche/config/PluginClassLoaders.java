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
import java.util.WeakHashMap;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import jche.util.Log;
import jche.util.Messages;

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
 *
 * <h2>クラスローダは 1 回の解析ごとに作る</h2>
 * 覚えておくのは同じ {@link Config}（＝1 回の解析。フェーズBで契約表の拡張と具象クラスの候補の拡張が
 * 別々に読み込む）の中だけで、次の解析は plugin.folders をディスクから読み直してコンパイルし直す。
 * 1 つの JVM で解析を続けて走らせる使い方（解析サーバーの ANALYZE・対話モードで解析を繰り返す・
 * 引数に設定を複数渡す）でも、その時点の拡張が効き、拡張の置き場所の警告（フォルダが無い・コンパイルの失敗）が
 * 設定ごとの run.log と warnings.txt に載る。以前は JVM の中で使い回していたので、拡張を直しても
 * 解析サーバーを起動し直すまで古い拡張が動き続け（候補を狭めた古い拡張のせいで呼び出しが黙って落ちる）、
 * 2 つ目以降の設定には警告が出なかった。
 *
 * <h2>コンパイルしたクラスはメモリに持つ</h2>
 * {@code .java} は使い捨てのフォルダ（OS の一時フォルダ）へコンパイルし、できたクラスをメモリに読み込んでから
 * フォルダを消す（{@link PluginLoader}）。解析サーバーは次の ANALYZE が失敗したら前の結果を使い続け、
 * その結果の拡張はクラスを遅れて読み込みうる。決まったフォルダへコンパイルし直すと、前の結果の拡張が
 * 新しいクラス（や、コンパイルに失敗して空になったフォルダ）を読んでしまう。メモリに持てば、
 * どのクラスローダも作ったときのクラスだけを読む。
 */
public final class PluginClassLoaders {

    /**
     * 以前の版がキャッシュフォルダに残したコンパイル結果のフォルダの名前の前半（後半は plugin.folders のハッシュ）。
     * いまは使い捨てのフォルダへコンパイルするので、見つけたら消すだけ
     */
    private static final String LEGACY_CLASSES_DIR_NAME = "plugin-classes";

    /**
     * 同じ解析の中で 2 回作らないための覚え書き（契約表の拡張と具象クラスの候補の拡張が別々に読み込むため）。
     * 鍵は {@link Config} のインスタンスそのもの（{@code Config} は {@code equals} を持たないので同一性で比べる）で、
     * 解析ごとに作り直される。弱参照なので、解析結果を手放せば一緒に消える
     */
    private static final Map<Config, ClassLoader> CACHE = new WeakHashMap<>();

    private PluginClassLoaders() {
    }

    /**
     * plugin.folders から作ったクラスローダ。指定が無ければこのクラスのクラスローダ
     * （＝従来どおり、ツール自身のクラスパスだけを見る）。
     * 同じ {@code config}（同じ解析）には同じものを返し、別の {@code config} には作り直したものを返す。
     */
    public static synchronized ClassLoader forConfig(Config config) {
        if (config.pluginFolders.isEmpty()) {
            return PluginClassLoaders.class.getClassLoader();
        }
        ClassLoader cached = CACHE.get(config);
        if (cached != null) {
            return cached;
        }
        ClassLoader loader = build(config);
        CACHE.put(config, loader);
        return loader;
    }

    private static ClassLoader build(Config config) {
        List<Path> jars = new ArrayList<>();
        List<Path> sources = new ArrayList<>();
        List<Path> classDirs = new ArrayList<>();
        for (Path folder : config.pluginFolders) {
            if (!Files.isDirectory(folder)) {
                Log.warn(Messages.format("config.plugin.folderMissing", folder));
                continue;
            }
            classDirs.add(folder);   // フォルダ直下に .class を置く使い方も許す
            collect(folder, jars, sources);
        }
        deleteLegacyClassesDir(config);
        Map<String, byte[]> compiled = sources.isEmpty() ? Map.of() : compile(sources, jars);
        List<URL> urls = new ArrayList<>();
        for (Path dir : classDirs) {
            urls.add(toUrl(dir));
        }
        for (Path jar : jars) {
            urls.add(toUrl(jar));
        }
        urls.removeIf(u -> u == null);
        return new PluginLoader(urls.toArray(new URL[0]), PluginClassLoaders.class.getClassLoader(), compiled);
    }

    /**
     * plugin.folders の {@code .java} をコンパイルしたクラスを、メモリから定義するクラスローダ。
     * それ以外（フォルダ直下の {@code .class}・jar）は {@link URLClassLoader} として読む。
     * コンパイルしたクラスを先に探すのは、以前コンパイル結果のフォルダをクラスパスの先頭に置いていたのと同じ並び。
     * コンパイルしたクラスは {@code getResource} では引けない（拡張が自分の {@code .class} をリソースとして
     * 読むことは想定しない）
     */
    private static final class PluginLoader extends URLClassLoader {
        /** 2 進名 → クラスファイルの中身。定義したものから外す */
        private final Map<String, byte[]> compiled;

        PluginLoader(URL[] urls, ClassLoader parent, Map<String, byte[]> compiled) {
            super(urls, parent);
            this.compiled = new HashMap<>(compiled);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes;
            synchronized (compiled) {
                bytes = compiled.remove(name);
            }
            if (bytes != null) {
                return defineClass(name, bytes, 0, bytes.length);
            }
            return super.findClass(name);
        }
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
            Log.warn(Messages.format("config.plugin.folderUnreadable", folder, e));
            return;
        }
        found.sort(Comparator.comparing(Path::toString));
        for (Path p : found) {
            (p.getFileName().toString().endsWith(".jar") ? jars : sources).add(p);
        }
    }

    /**
     * 拡張の .java をコンパイルし、できたクラスを読み込んで返す。
     * 出力先は使い捨てのフォルダ（OS の一時フォルダ）で、読み込んだら消す。毎回空のフォルダへ
     * コンパイルするので、消した .java のクラスが残って動き続けることも無い
     *
     * @return 2 進名 → クラスファイルの中身。コンパイラが使えない・失敗した場合は空
     */
    private static Map<String, byte[]> compile(List<Path> sources, List<Path> jars) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            // JRE で動いている場合。JBang は JDK を取ってくるので通常は起きない
            Log.warn(Messages.get("config.plugin.noCompiler"));
            return Map.of();
        }
        Path out;
        try {
            out = Files.createTempDirectory("jche-plugin-classes-");
        } catch (IOException e) {
            Log.warn(Messages.format("config.plugin.noOutDir", System.getProperty("java.io.tmpdir"), e));
            return Map.of();
        }
        try {
            return compileInto(compiler, out, sources, jars);
        } finally {
            deleteQuietly(out);
        }
    }

    private static Map<String, byte[]> compileInto(JavaCompiler compiler, Path out, List<Path> sources,
                                                   List<Path> jars) {
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
        Log.info(Messages.format("config.plugin.compiling", sources.size(), out));
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        int code = compiler.run(null, null, err, args.toArray(new String[0]));
        if (code != 0) {
            // 黙って進むと「拡張を置いたのに効かない」ことに気づけないので、必ず出す
            Log.warn(Messages.get("config.plugin.compileFailed"));
            for (String line : err.toString(StandardCharsets.UTF_8).split("\\R")) {
                if (!line.isBlank()) {
                    Log.warn("  " + line);
                }
            }
            return Map.of();
        }
        try {
            return readClasses(out);
        } catch (IOException | UncheckedIOException e) {
            // 読めなければ拡張なしで続ける。コンパイルの失敗と同じく、黙って進まない
            Log.warn(Messages.format("config.plugin.readFailed", out, e));
            return Map.of();
        }
    }

    /** 出力フォルダの .class を 2 進名（{@code a.b.C$D}）ごとに読み込む */
    private static Map<String, byte[]> readClasses(Path out) throws IOException {
        Map<String, byte[]> classes = new HashMap<>();
        try (var walk = Files.walk(out)) {
            for (Path file : (Iterable<Path>) walk::iterator) {
                String rel = out.relativize(file).toString();
                if (!rel.endsWith(".class") || !Files.isRegularFile(file)) {
                    continue;
                }
                String name = rel.substring(0, rel.length() - ".class".length())
                        .replace(file.getFileSystem().getSeparator(), ".");
                classes.put(name, Files.readAllBytes(file));
            }
        }
        return classes;
    }

    /** 以前の版がキャッシュフォルダに残したコンパイル結果を消す（あれば。消せなくても解析には関わらない） */
    private static void deleteLegacyClassesDir(Config config) {
        deleteQuietly(config.cacheDir.resolve(
                LEGACY_CLASSES_DIR_NAME + "_" + Config.shortHash(config.pluginFolders.toString())));
    }

    /** フォルダを消す。消せなくても解析の結果には関わらないので、{@code Log.info} で知らせるだけにする */
    private static void deleteQuietly(Path dir) {
        try {
            deleteRecursively(dir);
        } catch (IOException e) {
            Log.info(Messages.format("config.plugin.notDeleted", dir, e));
        }
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
            Log.warn(Messages.format("config.plugin.badUrl", path, e));
            return null;
        }
    }
}
