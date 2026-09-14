// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import jche.cache.LibraryFact;
import jche.util.FileHash;
import jche.util.Log;

/**
 * 旧キャッシュの依存 jar（L行）と今回のクラスパスを突き合わせ、追加・変更・削除を求める。
 *
 * <h2>同一性の見方</h2>
 * ソースファイルと同じく「<b>パスと中身</b>」で見る。更新時刻は使わない。中身と関係なく変わる
 * （git のチェックアウト、コピー、CI のたびに作り直されるワークスペース、ローカルリポジトリの
 * 取り直し）ためで、使うと「中身は同じなのに全部変わった」と判定されてしまう。
 *
 * <p>中身の指紋の取り方は、jar とクラスフォルダで変える。
 * <ul>
 *   <li><b>jar</b> … 中の一覧（エントリ名・サイズ・CRC）のハッシュ。
 *       jar の末尾にある目次（セントラルディレクトリ）を読むだけで、中身を展開も読み込みもしない。
 *       CRC は zip 形式が元から持っている値なので、内容が1バイト違えば指紋も変わる。
 *       ビルドし直して並び順が変わっただけの jar を「同じ」と見られるよう、並べ替えてから取る</li>
 *   <li><b>クラスフォルダ</b>（マルチモジュールの兄弟モジュールの target/classes 等）…
 *       {@code .class} の一覧（相対パス・サイズ・内容ハッシュ）のハッシュ。
 *       フォルダ自身の更新時刻は中のファイルの変更を反映しないので、毎回中を歩く</li>
 * </ul>
 * どちらも1回の走査で、指紋とパッケージ一覧の両方を作る。
 *
 * <p>影響範囲は型ではなくパッケージで持つ。jar の版を差し替えると型の増減があり、
 * 「旧版にあって新版に無い型」は新しい jar からは分からないため。
 * 削除された jar はもう開けないので、パッケージは旧 L 行から取る。
 *
 * <h2>並び順</h2>
 * jar の集合が同じでも、<b>クラスパス上の並びが変われば解決先が変わりうる</b>。
 * JDT は同名クラスを先勝ちで解決するので、同じ FQN が 2 つの jar に入っていると、
 * 順を入れ替えただけで別の型に解決される（{@code pom.xml} の依存の並べ替え、
 * {@code library.folders} の書き換えで普通に起きる）。
 *
 * <p>効くのは「同じパッケージが 2 つ以上の jar に入っている」ときだけなので、
 * パッケージごとに「そのパッケージを含む jar の並び」を作って突き合わせ、違うものだけを
 * 「変わったパッケージ」に入れる（{@link #addReorderedPackages}）。
 */
final class LibraryDiff {

    /** 今回のクラスパス（新キャッシュの L 行になる。クラスパス順） */
    final List<LibraryFact> current = new ArrayList<>();
    /** 追加・変更・削除された jar のパッケージ。これらの型を参照するファイルは解析し直す */
    final Set<String> changedPackages = new HashSet<>();
    int added;
    int changed;
    int removed;
    /** 並びが変わって解決先が変わりうるパッケージの数（下記「並び順」） */
    int reordered;

    private LibraryDiff() {
    }

    boolean any() {
        return added + changed + removed + reordered > 0;
    }

    /**
     * jar が増えた、または中身が変わった（型解決に失敗していた箇所が解決できるようになりうる）。
     *
     * <p>並び替えは入れない。jar の集合が同じなら「解決できる型の集合」も同じで、
     * 前回失敗した型解決が成功するようになる理由にはならないため（変わるのは、
     * 複数の jar にある同名クラスのうちどれが勝つかだけ）。
     */
    boolean anyAddedOrChanged() {
        return added + changed > 0;
    }

    @Override
    public String toString() {
        return "追加=" + added + " 変更=" + changed + " 削除=" + removed + " 並び替え=" + reordered
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
        Set<String> unchanged = new HashSet<>();   // 並び順の突き合わせに使う（addReorderedPackages）
        for (String cp : classpath) {
            Path entry = Paths.get(cp);
            String key = keyOf(entry, projectRoot);
            if (!seen.add(key)) {
                continue;   // 同じ jar が2度渡されても1件として扱う
            }
            LibraryFact now = scan(entry, key);
            LibraryFact prev = oldByPath.get(key);
            // 指紋が取れなかったものは同一性を判定できない。毎回「変わった」に倒す（安全側）
            if (prev != null && now.known() && prev.fingerprint().equals(now.fingerprint())) {
                diff.current.add(prev);
                unchanged.add(key);
                continue;
            }
            diff.current.add(now);
            diff.changedPackages.addAll(now.packages());
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
        diff.addReorderedPackages(old, unchanged);
        return diff;
    }

    /**
     * クラスパスの並びが変わったことで解決先が変わりうるパッケージを、changedPackages へ足す。
     *
     * <p>パッケージごとに「そのパッケージを含む jar の、クラスパス順の並び」を作り、
     * 旧 L 行から作ったものと突き合わせる。並びが違えば、そのパッケージの同名クラスは
     * 別の jar のものに解決されうるので、そのパッケージの型を参照するファイルを解析し直す。
     *
     * <p>突き合わせるのは<b>新旧に共通していて、中身も変わっていない jar だけ</b>。
     * そうしないと、jar を 1 本足した・消した・差し替えただけで「並びが変わった」と数えてしまう
     * （追加・変更・削除はすでにそれぞれ数えていて、パッケージも changedPackages に入っている）。
     * 絞ることで、ここが数えるのは「純粋な並び替え」だけになる。
     *
     * <p>1 つの jar にしかないパッケージは、どこに並んでいても解決先が変わらないので見ない。
     * 同じパッケージを 2 本以上の jar が持つ構成でなければ、突き合わせるものが無く、
     * 実質そのまま戻る。
     *
     * @param unchanged 新旧で指紋まで一致した jar のパス
     */
    private void addReorderedPackages(List<LibraryFact> old, Set<String> unchanged) {
        Map<String, List<String>> before = duplicatedPackages(old, unchanged);
        if (before.isEmpty()) {
            return;
        }
        Map<String, List<String>> after = duplicatedPackages(current, unchanged);
        for (Map.Entry<String, List<String>> en : before.entrySet()) {
            if (!en.getValue().equals(after.get(en.getKey()))) {
                reordered++;
                changedPackages.add(en.getKey());
            }
        }
    }

    /**
     * 2 つ以上の jar に入っているパッケージだけを「パッケージ -> jar のクラスパス順の並び」にする。
     *
     * @param keep 数える jar のパス（新旧で指紋まで一致したものだけを渡す）
     */
    private static Map<String, List<String>> duplicatedPackages(List<LibraryFact> facts, Set<String> keep) {
        Map<String, List<String>> byPackage = new LinkedHashMap<>();
        for (LibraryFact l : facts) {
            if (!keep.contains(l.path())) {
                continue;
            }
            for (String pkg : l.packages()) {
                byPackage.computeIfAbsent(pkg, k -> new ArrayList<>()).add(l.path());
            }
        }
        byPackage.values().removeIf(jars -> jars.size() < 2);
        return byPackage;
    }

    /** クラスパスの1件を1回走査して、指紋とパッケージ一覧を作る。読めなければ指紋は空文字 */
    private static LibraryFact scan(Path entry, String key) {
        try {
            return Files.isDirectory(entry) ? scanClassFolder(entry, key) : scanJar(entry, key);
        } catch (IOException | RuntimeException e) {
            Log.warn("依存jarを読み取れません（毎回「変わった」とみなします）: " + entry + " (" + e + ")");
            return new LibraryFact(key, "", List.of());
        }
    }

    /**
     * jar の目次（セントラルディレクトリ）だけを読んで、指紋とパッケージを作る。
     * 中身の展開も読み込みもしないので、大きな jar でも件数に比例するだけで済む。
     */
    private static LibraryFact scanJar(Path jar, String key) throws IOException {
        TreeSet<String> packages = new TreeSet<>();
        List<String> lines = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (Enumeration<? extends ZipEntry> it = zip.entries(); it.hasMoreElements();) {
                ZipEntry e = it.nextElement();
                lines.add(e.getName() + "\t" + e.getSize() + "\t" + e.getCrc());
                addPackageOf(packages, e.getName());
            }
        }
        return new LibraryFact(key, fingerprintOf(lines), new ArrayList<>(packages));
    }

    /** クラスフォルダを1回歩いて、指紋とパッケージを作る */
    private static LibraryFact scanClassFolder(Path dir, String key) throws IOException {
        TreeSet<String> packages = new TreeSet<>();
        List<String> lines = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                String rel = dir.relativize(p).toString().replace('\\', '/');
                if (!rel.endsWith(".class")) {
                    continue;
                }
                lines.add(rel + "\t" + Files.size(p) + "\t" + FileHash.of(p));
                addPackageOf(packages, rel);
            }
        }
        return new LibraryFact(key, fingerprintOf(lines), new ArrayList<>(packages));
    }

    /** 一覧を並べ替えてハッシュにする。並び順に振り回されないため */
    private static String fingerprintOf(List<String> lines) {
        Collections.sort(lines);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        return FileHash.ofText(sb.toString());
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
     * "a/b/C.class" のようなエントリ名からパッケージ "a.b" を集める（jar とクラスフォルダで共通）。
     * META-INF 配下（マルチリリース jar の版別クラス等）と、デフォルトパッケージのクラス
     * （他パッケージのソースから参照できない）は含めない。
     */
    private static void addPackageOf(Set<String> packages, String entryName) {
        if (!entryName.endsWith(".class") || entryName.startsWith("META-INF/")) {
            return;
        }
        int slash = entryName.lastIndexOf('/');
        if (slash > 0) {
            packages.add(entryName.substring(0, slash).replace('/', '.'));
        }
    }
}
