// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import jche.cache.LibraryFact;
import jche.util.Log;

/**
 * 旧キャッシュの依存 jar（L行）と今回のクラスパスを突き合わせ、追加・変更・削除を求める。
 *
 * 同じパスでサイズと更新時刻が一致する jar は変わっていないとみなし、パッケージ一覧も
 * 旧 L 行から引き継ぐ（jar を開き直さない）。追加・変更された jar は開いてパッケージを集める。
 * 削除された jar はもう開けないので、パッケージは旧 L 行から取る。
 *
 * クラスパスにはクラスフォルダ（マルチモジュールの兄弟モジュールの target/classes 等）も来る。
 * フォルダは「.class ファイルの数」をサイズ、「最も新しい .class の更新時刻」を更新時刻として
 * 同じ判定にかける（フォルダ自身の更新時刻は中のファイルの変更を反映しないため、毎回中を歩く）。
 *
 * 影響範囲は型ではなくパッケージで持つ。jar の版を差し替えると型の増減があり、
 * 「旧版にあって新版に無い型」は新しい jar からは分からないため。
 */
final class LibraryDiff {

    /** 今回のクラスパス（新キャッシュの L 行になる。クラスパス順） */
    final List<LibraryFact> current = new ArrayList<>();
    /** 追加・変更・削除された jar のパッケージ。これらの型を参照するファイルは解析し直す */
    final Set<String> changedPackages = new HashSet<>();
    int added;
    int changed;
    int removed;

    private LibraryDiff() {
    }

    boolean any() {
        return added + changed + removed > 0;
    }

    /** jar が増えた、または中身が変わった（型解決に失敗していた箇所が解決できるようになりうる） */
    boolean anyAddedOrChanged() {
        return added + changed > 0;
    }

    @Override
    public String toString() {
        return "追加=" + added + " 変更=" + changed + " 削除=" + removed
                + "（影響するパッケージ " + changedPackages.size() + " 件）";
    }

    /**
     * @param classpath   JDT に渡すクラスパス（jar のパス、またはクラスフォルダ）
     * @param old         旧キャッシュの L 行。キャッシュが無ければ空
     * @param projectRoot L 行のパスを相対にする基準
     */
    static LibraryDiff compute(String[] classpath, List<LibraryFact> old, Path projectRoot) {
        Map<String, LibraryFact> oldByPath = new HashMap<>();
        for (LibraryFact l : old) {
            oldByPath.put(l.path(), l);
        }
        LibraryDiff diff = new LibraryDiff();
        Set<String> seen = new HashSet<>();
        for (String cp : classpath) {
            Path jar = Paths.get(cp);
            String key = keyOf(jar, projectRoot);
            if (!seen.add(key)) {
                continue;   // 同じ jar が2度渡されても1件として扱う
            }
            long size;
            long mtime;
            ClassFolder folder = null;
            try {
                if (Files.isDirectory(jar)) {
                    folder = ClassFolder.scan(jar);
                    size = folder.classFiles;
                    mtime = folder.newestMtime;
                } else {
                    size = Files.size(jar);
                    mtime = Files.getLastModifiedTime(jar).toMillis();
                }
            } catch (IOException e) {
                Log.warn("依存jarの情報を読み取れません（変更検知の対象外）: " + jar + " (" + e + ")");
                continue;
            }
            LibraryFact prev = oldByPath.get(key);
            if (prev != null && prev.size() == size && prev.mtime() == mtime) {
                diff.current.add(prev);
                continue;
            }
            List<String> packages = (folder != null) ? new ArrayList<>(folder.packages) : packagesOf(jar);
            diff.current.add(new LibraryFact(key, size, mtime, packages));
            diff.changedPackages.addAll(packages);
            if (prev == null) {
                diff.added++;
            } else {
                diff.changed++;
                diff.changedPackages.addAll(prev.packages());   // 旧版にだけあったパッケージも影響する
            }
        }
        for (LibraryFact l : old) {
            if (!seen.contains(l.path())) {
                diff.removed++;
                diff.changedPackages.addAll(l.packages());
            }
        }
        return diff;
    }

    /** project.root 配下なら相対パス（プロジェクトを移動しても同じ jar と分かる）、外なら絶対パス */
    private static String keyOf(Path jar, Path projectRoot) {
        Path abs = jar.toAbsolutePath().normalize();
        if (abs.startsWith(projectRoot)) {
            return projectRoot.relativize(abs).toString().replace('\\', '/');
        }
        return abs.toString().replace('\\', '/');
    }

    /**
     * jar が含むクラスのパッケージ。エントリ名の親ディレクトリを "." 区切りにしたもの。
     * META-INF 配下（マルチリリース jar の版別クラス等）と、デフォルトパッケージのクラス
     * （他パッケージのソースから参照できない）は含めない。
     */
    static List<String> packagesOf(Path jar) {
        TreeSet<String> packages = new TreeSet<>();
        try (JarFile jf = new JarFile(jar.toFile(), false)) {
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                addPackageOf(packages, name);
            }
        } catch (IOException e) {
            Log.warn("依存jarを読み取れません（このjarの変更は型解決失敗のあったファイルにだけ反映）: "
                    + jar + " (" + e + ")");
        }
        return new ArrayList<>(packages);
    }

    /** "a/b/C.class" のようなエントリ名からパッケージ "a.b" を集める（jar とクラスフォルダで共通） */
    private static void addPackageOf(Set<String> packages, String entryName) {
        if (!entryName.endsWith(".class") || entryName.startsWith("META-INF/")) {
            return;
        }
        int slash = entryName.lastIndexOf('/');
        if (slash > 0) {
            packages.add(entryName.substring(0, slash).replace('/', '.'));
        }
    }

    /** クラスフォルダを 1 回歩いて集めた、.class の数・最新の更新時刻・パッケージ */
    private static final class ClassFolder {
        long classFiles;
        long newestMtime;
        final TreeSet<String> packages = new TreeSet<>();

        static ClassFolder scan(Path dir) throws IOException {
            ClassFolder result = new ClassFolder();
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path p : (Iterable<Path>) walk::iterator) {
                    if (!Files.isRegularFile(p)) {
                        continue;
                    }
                    String rel = dir.relativize(p).toString().replace('\\', '/');
                    if (!rel.endsWith(".class")) {
                        continue;
                    }
                    result.classFiles++;
                    result.newestMtime = Math.max(result.newestMtime, Files.getLastModifiedTime(p).toMillis());
                    addPackageOf(result.packages, rel);
                }
            }
            return result;
        }
    }
}
