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

import jche.cache.LibraryFact;
import jche.util.Log;

/**
 * 旧キャッシュの依存 jar（L行）と今回のクラスパスを突き合わせ、追加・変更・削除を求める。
 *
 * 同じパスでサイズと更新時刻が一致する jar は変わっていないとみなし、パッケージ一覧も
 * 旧 L 行から引き継ぐ（jar を開き直さない）。追加・変更された jar は開いてパッケージを集める。
 * 削除された jar はもう開けないので、パッケージは旧 L 行から取る。
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
     * @param classpath   JDT に渡すクラスパス（jar のパス）
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
            try {
                size = Files.size(jar);
                mtime = Files.getLastModifiedTime(jar).toMillis();
            } catch (IOException e) {
                Log.warn("依存jarの情報を読み取れません（変更検知の対象外）: " + jar + " (" + e + ")");
                continue;
            }
            LibraryFact prev = oldByPath.get(key);
            if (prev != null && prev.size() == size && prev.mtime() == mtime) {
                diff.current.add(prev);
                continue;
            }
            List<String> packages = packagesOf(jar);
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
                if (!name.endsWith(".class") || name.startsWith("META-INF/")) {
                    continue;
                }
                int slash = name.lastIndexOf('/');
                if (slash > 0) {
                    packages.add(name.substring(0, slash).replace('/', '.'));
                }
            }
        } catch (IOException e) {
            Log.warn("依存jarを読み取れません（このjarの変更は型解決失敗のあったファイルにだけ反映）: "
                    + jar + " (" + e + ")");
        }
        return new ArrayList<>(packages);
    }
}
