// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import jche.cache.LibraryFact;
import jche.config.ProjectLayout;
import jche.util.FileHash;
import jche.util.FileTree;
import jche.util.Log;
import jche.util.Messages;

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
 *       ビルドし直して並び順が変わっただけの jar を「同じ」と見られるよう、エントリ名で並べ替えてから取る。
 *       ただし同じ名前のエントリが 2 つあれば、その 2 つの目次の順は残す（JDK と JDT は後ろのものを使うので、
 *       入れ替われば JDT が読むクラスが変わる。{@link #fingerprintOf}）。
 *       目次は {@link ZipDirectory} でファイルのバイトから読む（{@link java.util.zip.ZipFile} はプロセスの中で
 *       前の目次を返しうる。下の「同じプロセスでの解析の繰り返し」）。
 *       jmod（{@code *.jmod}）のクラスは {@code classes/} の下にあり、JDT はその下をパッケージとして読むので、
 *       パッケージは {@code classes/} を外した名前から取る</li>
 *   <li><b>クラスフォルダ</b>（マルチモジュールの兄弟モジュールの target/classes 等）…
 *       {@code .class} と {@code .java} の一覧（相対パス・サイズ・内容ハッシュ）のハッシュ。
 *       JDT はクラスパスのフォルダをソースとしても読む（{@code X.class} が無いか、{@code X.java} の更新時刻の方が
 *       新しければ {@code X.java} を使う）ので、{@code .java} も数え、同じ名前の {@code .class} があるときは
 *       どちらが新しいかも指紋に入れる（更新時刻そのものは入れない）。
 *       フォルダ自身の更新時刻は中のファイルの変更を反映しないので、毎回中を歩く。
 *       JDT と同じくシンボリックリンクをたどって歩く（{@link FileTree}）</li>
 * </ul>
 * どちらも1回の走査で、指紋とパッケージ一覧の両方を作る。
 *
 * <h2>解析のあいだの書き換え</h2>
 * 指紋はパス0 で 1 回だけ取り、JDT はそのあとバッチごとに jar とクラスフォルダを読む。そのあいだに書き換えられ
 * （兄弟モジュールの clean ビルドなど）、終わったあとで同じ中身に戻されると、指紋は一致するのに、書き換えた中身で
 * 解析したブロックが残り続ける。そこで走査するときに各件の見かけ（jar は大きさ・更新時刻・fileKey、クラスフォルダは
 * 中の {@code .class} / {@code .java} の一覧と大きさ・更新時刻）を覚えておき、書き終えたときに見比べる
 * （{@link #changedSinceScan}）。変わっていれば、呼び出し側（CacheUpdater）がこの実行で解析したファイルの
 * 内容ハッシュを空にし、次の実行で解析し直させる。見かけはキャッシュに書かない（同一性は中身で見る）。
 *
 * <h2>同じプロセスでの解析の繰り返し</h2>
 * JDT は開いた jar を閉じない（ガベージコレクションまで開いたまま）。開いているあいだ、JDK は同じ jar
 * （inode と更新時刻が同じもの）の目次をプロセスの中で共有するので、同じ更新時刻のまま上書きされた jar を、
 * 解析サーバーの次の解析の JDT は前の目次で読む。目次は自分で読む（{@link ZipDirectory}）ので変化は見えるが、
 * JDT にも今の中身を読ませる必要がある。このプロセスで前に走査したときと inode・更新時刻が同じなのに指紋が違う
 * jar があれば、JDK の見ている目次が今の中身と同じになるまでガベージコレクションを促して待つ
 * （{@link #releaseStaleView}）。待ちきれなければ警告し、この実行で解析したファイルを次の実行で解析し直させる
 * （{@link #staleInProcess}）。
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
    /**
     * 走査したときの各件の見かけ（パス -> 見かけ）。書き終えたときに見比べる（クラスの説明「解析のあいだの書き換え」）。
     * キャッシュには書かない
     */
    private final Map<Path, String> stamps = new LinkedHashMap<>();
    /**
     * JDK がこのプロセスの中で前の中身の目次を見せ続けている jar があった（クラスの説明「同じプロセスでの解析の
     * 繰り返し」）。JDT が前の中身を読んだかもしれないので、この実行で解析したファイルは次の実行で解析し直す
     */
    boolean staleInProcess;

    /**
     * このプロセスで前に走査した jar の、JDK が目次を共有する鍵（更新時刻と fileKey）と指紋（絶対パス -> {鍵, 指紋}）。
     * 解析サーバーは同じプロセスで解析を繰り返すので、プロセスの中で持ち越す
     */
    private static final Map<Path, String[]> seenInProcess = new ConcurrentHashMap<>();
    /** JDK の目次が今の中身と同じになるのを待つ回数と間隔（{@link #releaseStaleView}） */
    private static final int RELEASE_TRIES = 50;
    private static final long RELEASE_WAIT_MILLIS = 100;
    /** 見かけを取れなかったもの（消えていた・読めなかった）の見かけ（{@link #stamps}） */
    private static final String UNREADABLE = "-";
    /** jmod の中でクラスを置くところ（JDT はこの下をクラスパスの根として読む） */
    private static final String JMOD_CLASSES = "classes/";

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
        return Messages.format("analysis.libraryDiff", added, changed, removed, reordered,
                changedPackages.size());
    }

    /**
     * @param classpath   JDT に渡すクラスパス（jar のパス、またはクラスフォルダ）
     * @param old         旧キャッシュの L 行。キャッシュが無ければ空
     * @param projectRoot L 行のパスを相対にする基準
     */
    static LibraryDiff compute(String[] classpath, List<LibraryFact> old, Path projectRoot) {
        List<LibraryFact> scanned = new ArrayList<>();
        Map<Path, String> stamps = new LinkedHashMap<>();
        boolean[] stale = {false};
        Set<String> seen = new HashSet<>();
        for (String cp : classpath) {
            Path entry = Paths.get(cp);
            String key = keyOf(entry, projectRoot);
            if (!seen.add(key)) {
                continue;   // 同じ jar が2度渡されても1件として扱う
            }
            scanned.add(scan(entry, key, stamps, stale));
        }
        LibraryDiff diff = diff(scanned, old);
        diff.stamps.putAll(stamps);
        diff.staleInProcess = stale[0];
        return diff;
    }

    /**
     * 走査したときから見かけの変わった jar・クラスフォルダ（パス。クラスパス順）。空なら変わっていない。
     * 読めなくなったもの（消えた）も、走査したときに読めなかったのに読めるようになったものも、変わったものに数える。
     * クラスの説明「解析のあいだの書き換え」
     */
    List<Path> changedSinceScan() {
        List<Path> changed = new ArrayList<>();
        for (Map.Entry<Path, String> en : stamps.entrySet()) {
            String now;
            try {
                now = stampOf(en.getKey());
            } catch (IOException | RuntimeException e) {
                now = UNREADABLE;
            }
            if (!en.getValue().equals(now)) {
                changed.add(en.getKey());
            }
        }
        return changed;
    }

    /**
     * 記録してある依存 jar（{@code recorded}。中断した実行の一時ファイルの L 行）から、今回の依存 jar
     * （{@code current}。{@link #compute} で作った {@link #current}）までの差分。クラスパスを走査し直さない。
     *
     * <p>{@link #compute}（クラスパスを走査し直して {@code recorded} と突き合わせる）と同じ答えになる。
     * {@code current} は走査した結果（{@code scanned}）そのもので（{@link #diff} は中身の変わらない jar にも旧キャッシュの
     * 要素ではなく走査した結果を残す）、{@link #diff} が見るのはそれと {@code recorded} だけだからである。
     */
    static LibraryDiff unchangedSince(List<LibraryFact> recorded, List<LibraryFact> current) {
        return diff(current, recorded);
    }

    /**
     * @param scanned 今回のクラスパスを走査した結果（クラスパス順、パスの重複なし）
     * @param old     突き合わせる相手の L 行
     */
    private static LibraryDiff diff(List<LibraryFact> scanned, List<LibraryFact> old) {
        Map<String, LibraryFact> oldByPath = new HashMap<>();
        for (LibraryFact l : old) {
            oldByPath.put(l.path(), l);
        }
        LibraryDiff diff = new LibraryDiff();
        Set<String> seen = new HashSet<>();
        Set<String> unchanged = new HashSet<>();   // 並び順の突き合わせに使う（addReorderedPackages）
        for (LibraryFact now : scanned) {
            String key = now.path();
            seen.add(key);
            LibraryFact prev = oldByPath.get(key);
            // 指紋が取れなかったものは同一性を判定できない。毎回「変わった」に倒す（安全側）
            if (prev != null && now.known() && prev.fingerprint().equals(now.fingerprint())) {
                // 旧キャッシュの要素ではなく走査した結果を残す。指紋が同じならふつうはパッケージも同じだが、
                // パッケージの取り方を直したとき（jmod の classes/ など）に、古い取り方のパッケージを持ち越さない
                diff.current.add(now);
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

    /**
     * クラスパスの1件を1回走査して、指紋とパッケージ一覧を作る。読めなければ指紋は空文字。
     * あわせて見かけを {@code stamps} に入れ、JDK が前の中身の目次を見せ続けていて解き放てなければ
     * {@code stale[0]} を真にする。
     *
     * <p>読めなかったもの（zip でない・書きかけ・パス0 の時点で消えていた）の見かけも入れる（取れなければ
     * {@link #UNREADABLE}）。読めなかったものは L 行にパッケージが無いので、あとで消えても変わっても、それを
     * 使うファイルを解析し直す手がかりが無い。解析のあいだに読める中身になって JDT がそれを読んだときに、書き終えたときの
     * 見比べ（{@link #changedSinceScan}）で気づけるようにする
     */
    private static LibraryFact scan(Path entry, String key, Map<Path, String> stamps, boolean[] stale) {
        String stamp = null;
        try {
            if (Files.isDirectory(entry)) {
                return scanClassFolder(entry, key, stamps);
            }
            // 見かけは中を読む前に取る（読んでいるあいだの書き換えも、書き終えたときに見比べて気づけるように）
            stamp = stampOfFile(entry);
            LibraryFact fact = scanJar(entry, key, stale);
            stamps.put(entry, stamp);
            return fact;
        } catch (IOException | RuntimeException e) {
            Log.warn(Messages.format("analysis.libraryUnreadable", entry, e));
            stamps.put(entry, (stamp != null) ? stamp : UNREADABLE);
            return new LibraryFact(key, "", List.of());
        }
    }

    /**
     * jar の目次（セントラルディレクトリ）だけを読んで、指紋とパッケージを作る。
     * 中身の展開も読み込みもしないので、大きな jar でも件数に比例するだけで済む。
     */
    private static LibraryFact scanJar(Path jar, String key, boolean[] stale) throws IOException {
        // JDT と同じ見分け方（拡張子。org.eclipse.jdt.internal.compiler.util.Util#archiveFormat）
        boolean jmod = jar.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jmod");
        TreeSet<String> packages = new TreeSet<>();
        List<Line> lines = new ArrayList<>();
        for (ZipDirectory.Entry e : ZipDirectory.read(jar)) {
            lines.add(new Line(e.name(), e.size() + "\t" + e.crc()));
            if (!jmod) {
                addPackageOf(packages, e.name());
            } else if (e.name().startsWith(JMOD_CLASSES)) {
                addPackageOf(packages, e.name().substring(JMOD_CLASSES.length()));
            }
        }
        String fingerprint = fingerprintOf(lines);
        checkSharedView(jar, fingerprint, stale);
        return new LibraryFact(key, fingerprint, new ArrayList<>(packages));
    }

    /**
     * このプロセスで前に走査したときと JDK の鍵（更新時刻と fileKey）が同じなのに指紋が違えば、JDK が前の目次を
     * 共有し続けているかもしれない。今の中身と同じになるまで解き放つ。解き放てなければ警告し、{@code stale[0]} を真にする
     * （クラスの説明「同じプロセスでの解析の繰り返し」）。
     *
     * <p>{@link #seenInProcess} は「JDK が見せている（と考える）目次の指紋」を持つ。解き放てなかったときは前の指紋を
     * 残す。今の指紋に置き換えると、次の解析では指紋が一致して確かめず、JDK が前の目次を見せたままなのに、
     * この実行で内容ハッシュを空にしたファイルを前の目次で解析し直して、正しいものとして書いてしまう
     */
    private static void checkSharedView(Path jar, String fingerprint, boolean[] stale) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(jar, BasicFileAttributes.class);
        String jdkKey = attrs.lastModifiedTime().to(TimeUnit.NANOSECONDS) + "\t" + attrs.fileKey();
        Path abs = jar.toAbsolutePath().normalize();
        String[] before = seenInProcess.get(abs);
        if (before != null && before[0].equals(jdkKey) && !before[1].equals(fingerprint)
                && !releaseStaleView(jar, fingerprint)) {
            stale[0] = true;
            Log.warn(Messages.format("analysis.libraryStaleInProcess", jar));
            return;   // 前の指紋を残す（次の解析でもまた確かめる）
        }
        seenInProcess.put(abs, new String[] {jdkKey, fingerprint});
    }

    /**
     * JDK（{@link ZipFile}）の見せる目次から作った指紋が、ファイルのバイトから読んだ指紋と同じになるまで、
     * ガベージコレクションを促して待つ（JDT が閉じずに捨てた {@link ZipFile} が片付けば、JDK は目次を読み直す）。
     *
     * @return 同じになったか
     */
    private static boolean releaseStaleView(Path jar, String fingerprint) {
        for (int i = 0; i < RELEASE_TRIES; i++) {
            if (fingerprint.equals(sharedViewFingerprintOf(jar))) {
                return true;
            }
            System.gc();
            try {
                Thread.sleep(RELEASE_WAIT_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return fingerprint.equals(sharedViewFingerprintOf(jar));
    }

    /** JDK（{@link ZipFile}）の見せる目次から作った指紋。読めなければ空文字 */
    private static String sharedViewFingerprintOf(Path jar) {
        List<Line> lines = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (Enumeration<? extends ZipEntry> it = zip.entries(); it.hasMoreElements();) {
                ZipEntry e = it.nextElement();
                lines.add(new Line(e.getName(), e.getSize() + "\t" + e.getCrc()));
            }
        } catch (IOException | RuntimeException e) {
            return "";
        }
        return fingerprintOf(lines);
    }

    /**
     * クラスフォルダを1回歩いて、指紋とパッケージを作る。見かけ（中の {@code .class} / {@code .java} の一覧と
     * 大きさ・更新時刻）も同じ走査で {@code stamps} に入れる。各ファイルの見かけは中を読む前に取る
     */
    private static LibraryFact scanClassFolder(Path dir, String key, Map<Path, String> stamps) throws IOException {
        TreeSet<String> packages = new TreeSet<>();
        List<Line> lines = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        Map<String, Long> classTimes = new HashMap<>();    // 拡張子を除いた相対パス -> .class の更新時刻（ミリ秒）
        Map<String, Long> sourceTimes = new HashMap<>();   // 拡張子を除いた相対パス -> .java の更新時刻（ミリ秒）
        Map<String, Line> sources = new HashMap<>();
        FileTree.forEachFile(dir, (p, attrs) -> {
            String rel = relativeOf(dir, p);
            boolean isClass = rel.endsWith(".class");
            if (!isClass && !rel.endsWith(".java")) {
                return;
            }
            seen.add(stampLineOf(rel, attrs));
            Line line = new Line(rel, attrs.size() + "\t" + FileHash.of(p));
            String base = rel.substring(0, rel.lastIndexOf('.'));
            // JDT は .java もそのパッケージの型として読む。パッケージの決め方は .class と同じにする
            addPackageOf(packages, base + ".class");
            if (isClass) {
                lines.add(line);
                classTimes.put(base, attrs.lastModifiedTime().toMillis());
            } else {
                sourceTimes.put(base, attrs.lastModifiedTime().toMillis());
                sources.put(base, line);
            }
        });
        for (Map.Entry<String, Line> en : sources.entrySet()) {
            // JDT（ClasspathDirectory）は X.class があっても、X.java の更新時刻（ミリ秒）の方が新しければ X.java を読む。
            // どちらを読むかだけを指紋に入れる（更新時刻そのものを入れると、チェックアウトのたびに変わる）
            Long classTime = classTimes.get(en.getKey());
            String which = (classTime == null) ? "source-only"
                    : (sourceTimes.get(en.getKey()) > classTime) ? "source" : "class";
            lines.add(new Line(en.getValue().name(), en.getValue().rest() + "\t" + which));
        }
        stamps.put(dir, stampOfLines(seen));
        return new LibraryFact(key, fingerprintOf(lines), new ArrayList<>(packages));
    }

    /** 見かけ（書き終えたときに見比べる）。jar は大きさ・更新時刻・fileKey、フォルダは中の一覧と大きさ・更新時刻 */
    private static String stampOf(Path entry) throws IOException {
        if (!Files.isDirectory(entry)) {
            return stampOfFile(entry);
        }
        List<String> seen = new ArrayList<>();
        FileTree.forEachFile(entry, (p, attrs) -> {
            String rel = relativeOf(entry, p);
            if (rel.endsWith(".class") || rel.endsWith(".java")) {
                seen.add(stampLineOf(rel, attrs));
            }
        });
        return stampOfLines(seen);
    }

    private static String stampOfFile(Path file) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
        return attrs.size() + "\t" + attrs.lastModifiedTime().to(TimeUnit.NANOSECONDS) + "\t" + attrs.fileKey();
    }

    private static String stampLineOf(String rel, BasicFileAttributes attrs) {
        return rel + "\t" + attrs.size() + "\t" + attrs.lastModifiedTime().to(TimeUnit.NANOSECONDS);
    }

    private static String stampOfLines(List<String> lines) {
        Collections.sort(lines);
        return FileHash.ofText(String.join("\n", lines));
    }

    private static String relativeOf(Path dir, Path file) {
        return dir.relativize(file).toString().replace('\\', '/');
    }

    /** 指紋の一覧の 1 行（エントリ名と、TAB で区切った残りの列） */
    private record Line(String name, String rest) {
    }

    /**
     * 一覧をエントリ名で並べ替えてハッシュにする。並び順に振り回されないため。
     *
     * <p>並べ替えは名前だけで、同じ名前の行は元の順（jar の目次の順）のまま残す（安定な並べ替え）。JDK と JDT は
     * 同じ名前のエントリのうち目次の後ろのものを使うので、その順が入れ替わると JDT が読むクラスが変わる。行全体で
     * 並べ替えていたときは、入れ替えても指紋が同じで、古い事実を再利用していた。名前がどれも違えば、行全体で
     * 並べ替えたときと同じ並びになる（名前に TAB より小さい文字が無い限り。指紋は以前と変わらない）
     */
    private static String fingerprintOf(List<Line> lines) {
        lines.sort(Comparator.comparing(Line::name));
        StringBuilder sb = new StringBuilder();
        for (Line line : lines) {
            sb.append(line.name()).append('\t').append(line.rest()).append('\n');
        }
        return FileHash.ofText(sb.toString());
    }

    /**
     * project.root 配下なら相対パス（プロジェクトを移動しても同じ jar と分かる）、外なら絶対パス。
     * 綴りは {@link ProjectLayout#pathKeyOf}（名前の中の {@code \} を区切りと取り違えない）
     */
    private static String keyOf(Path jar, Path projectRoot) {
        Path abs = jar.toAbsolutePath().normalize();
        if (abs.startsWith(projectRoot)) {
            return ProjectLayout.pathKeyOf(projectRoot.relativize(abs));
        }
        return ProjectLayout.pathKeyOf(abs);
    }

    /**
     * "a/b/C.class" のようなエントリ名からパッケージ "a.b" を集める（jar とクラスフォルダで共通）。
     * META-INF 配下（マルチリリース jar の版別クラス等）は含めない。
     *
     * <p>根にあるクラス（デフォルトパッケージのクラス {@code Base.class}）は {@link LibraryFact#UNNAMED_PACKAGE} にする。
     * ほかのパッケージのソースからは参照できないが、無名パッケージのソースからは参照できる（JLS 7.4.2）。以前は除いて
     * いたので、そのクラスを変えた・足した・消した・並びを変えた jar でも、それを使う無名パッケージのソースを解析し直さな
     * かった。{@code module-info.class} は型ではない（モジュールの jar の根に必ずある）ので除く
     */
    private static void addPackageOf(Set<String> packages, String entryName) {
        if (!entryName.endsWith(".class") || entryName.startsWith("META-INF/")) {
            return;
        }
        int slash = entryName.lastIndexOf('/');
        if (slash > 0) {
            packages.add(entryName.substring(0, slash).replace('/', '.'));
        } else if (slash < 0 && !entryName.equals("module-info.class")) {
            packages.add(LibraryFact.UNNAMED_PACKAGE);
        }
    }
}
