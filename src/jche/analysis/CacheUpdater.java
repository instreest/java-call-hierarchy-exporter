// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import jche.cache.CacheFormat;
import jche.cache.CacheReader;
import jche.cache.CallSite;
import jche.cache.ConstantFact;
import jche.cache.FieldAccessFact;
import jche.cache.FieldAssignFact;
import jche.cache.FieldDeclFact;
import jche.cache.FileAnalysis;
import jche.cache.FunctionalImplFact;
import jche.cache.HintFact;
import jche.cache.LibraryFact;
import jche.cache.MethodDeclFact;
import jche.cache.ReturnFact;
import jche.cache.TypeFact;
import jche.cache.UnresolvedCallFact;
import jche.analysis.CallEdgeExtractor.SourceFile;
import jche.config.Config;
import jche.config.ProjectLayout;
import jche.util.FileHash;
import jche.util.Log;
import jche.util.Progress;
import jche.util.RunControl;

/**
 * フェーズ1: 旧キャッシュを先頭から読みながら新キャッシュを書き出す、ストリーミングマージ。
 *
 * 1ファイル分の結果が出来るたびにキャッシュファイルへ直接書き出して破棄するため、
 * ランダムアクセスも全件保持も不要。ヒープ常駐は「ソースファイルの一覧＋更新時刻・サイズ」と
 * 「変わった型の集合」だけ。未解決呼び出しの件数も、この過程で同時に数える（溜め込まない）。
 * パース自体は {@link CallEdgeExtractor#BATCH_SIZE} 件ずつまとめて行う（1ファイルずつでは
 * 規模に比例して遅くなるため）。
 *
 * 手順:
 * <pre>
 *   パス0 … 旧キャッシュのヘッダと L 行（解析時の依存 jar）を読み、今回のクラスパスと突き合わせる。
 *           追加・変更・削除された jar のパッケージを「変わったパッケージ」として集める。
 *   パス1 … 旧キャッシュを順に読み、更新時刻とサイズが一致するファイル（有効）を覚える。
 *           更新時刻だけが違うファイルは、サイズが同じなら内容ハッシュを取って F 行と突き合わせる
 *           （中身が同じなら有効。git のチェックアウトや CI で更新時刻が変わっても再利用できるようにするため）。
 *           無効・消滅したファイルのブロックが宣言していた型（H行）を「変わった型」として集める。
 *           jar が追加・変更されていれば、型解決に失敗していたファイル（F行のエラー数、
 *           U行の BINDING_FAILED）も有効から外す。追加された jar で解決できるようになりうるため。
 *   パス2 … 変更・追加されたファイルを解析して新キャッシュへ書く。
 *           そのファイルが宣言する型も「変わった型」に加える（改名・追加に備える）。
 *   パス3 … 旧キャッシュをもう一度読み、有効なブロックのうち、I行（依存する型）が
 *           「変わった型」または「変わったパッケージ」に触れるものを再解析に回す。
 *           触れるものは、バインディング解決の結果が変わっている可能性があるため。
 *   パス4 … パス3で再解析に回したファイルを解析し、追記する。そのファイルが宣言する定数
 *           （K行）の値が変わっていたら、宣言する型を「変わった型」に加えてパス3へ戻る
 *           （下記「定数の連鎖」）。
 *   パス5 … 最後まで有効だったブロックをそのまま書き写す
 *           （F 行だけは今の更新時刻と内容ハッシュに書き直す。次回は更新時刻の一致で通るように）。
 * </pre>
 *
 * 更新時刻とサイズだけで再利用を決めると、別のファイルの変更（オーバーロードの追加、
 * フィールドの改名、親型の変更など）でこのファイルの解決結果が変わっても気づけない。
 *
 * <h2>定数の連鎖（依存を1段で済ませられない唯一の場合）</h2>
 * ふつうは依存を1段辿れば足りる。ファイルAの事実はAが参照した型にだけ依存し、
 * Aを解析し直してもAが宣言する型（Aのソース）は変わらないので、Aに依存するファイルへは波及しない。
 *
 * <p>ただしコンパイル時定数（{@code static final} の値）だけは別で、
 * <b>使う側のファイルに値そのものが焼き込まれる</b>（Javaの言語仕様どおり、JDTもそう解決する）。
 * <pre>
 *   P.java   static final String KIND = "ALPHA";
 *   X.java   static final String KIND = P.KIND;     ← 値 "ALPHA" が焼き込まれる
 *   C.java   if (X.KIND.equals("BETA")) { … }       ← ここにも "ALPHA" が焼き込まれる
 * </pre>
 * C.java が参照している型は X だけなので、P.java を変えても C.java の I 行には引っかからず、
 * 古い "ALPHA" が残ってしまう（条件分岐の打ち切りや、クラス名の文字列からの具象クラスの
 * 特定が、古い値のまま出る）。
 *
 * <p>そこで、宣言している定数の値を K 行（{@link jche.cache.ConstantFact}）として残しておき、
 * パス4で解析し直した結果その値が変わっていたら、そのファイルが宣言する型も「変わった型」に
 * 加えてパス3からやり直す。連鎖するのは値が実際に変わった定数を参照しているファイルだけなので、
 * 全件再解析にはならず、何も変わらなければ1周で止まる。
 *
 * 依存 jar の変更も同じ仕組みで扱う。jar の中の型は解析し直せない（ソースが無い）ので、
 * 「その jar のパッケージの型を参照しているファイル」を再解析の対象にする。
 * 型ではなくパッケージで見るのは、jar の版を差し替えたときに旧版にだけあった型を
 * 新しい jar からは知れないためで、L 行にパッケージ一覧を残すのは jar が削除された後にも
 * 影響範囲を知るため（{@link LibraryDiff}）。
 */
public final class CacheUpdater {

    private final ProjectLayout layout;
    private final Config config;
    /**
     * 相対パス -> 今のソースの内容ハッシュ。更新時刻が違ったときにだけ計算し、同じファイルを
     * 2 度読まないように覚える（パス1 の判定とパス3 の F 行の書き直しで使う）
     */
    private final Map<String, String> hashes = new HashMap<>();
    /**
     * 相対パス -> 旧キャッシュの K 行（宣言している定数）の指紋。
     * パス4で解析し直した結果と突き合わせて、定数の値が変わったかだけを見る（「定数の連鎖」）。
     * ファイルごとに 16 文字のハッシュ1つなので、ヒープに載せても軽い
     */
    private final Map<String, String> oldConstants = new HashMap<>();

    public CacheUpdater(ProjectLayout layout, Config config) {
        this.layout = layout;
        this.config = config;
    }

    public CachePhaseResult run() throws IOException {
        CachePhaseResult result = new CachePhaseResult();

        List<Path> javaFiles = layout.listJavaFiles();
        Log.info("Javaファイル数: " + javaFiles.size());

        // 相対パス -> ソースファイルの実体情報（これだけはヒープに載せる）
        Map<String, SourceFile> live = new LinkedHashMap<>();
        for (Path f : javaFiles) {
            String rel = layout.relativeOf(f);
            live.put(rel, new SourceFile(f, rel, Files.getLastModifiedTime(f).toMillis(), Files.size(f)));
        }

        Path parent = config.cacheFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmpCache = config.cacheFile.resolveSibling(config.cacheFile.getFileName() + ".tmp");
        // 前回が途中で終わっていれば、その一時ファイルを退避して「パースの使い回し」に使う
        Path partialCache = takeOverPartial(tmpCache);

        Progress progress = new Progress("ソース解析", javaFiles.size(), CallEdgeExtractor.BATCH_SIZE);
        CallEdgeExtractor extractor = new CallEdgeExtractor(layout, config);

        // --- パス0: 旧キャッシュの依存 jar（L行）と今回のクラスパスを突き合わせる ---
        List<LibraryFact> oldLibraries = config.cacheEnabled ? readOldLibraries() : null;
        boolean oldCacheUsable = (oldLibraries != null);
        LibraryDiff libraries = LibraryDiff.compute(layout.classpathArray(),
                oldCacheUsable ? oldLibraries : List.of(), layout.projectRoot);
        if (oldCacheUsable && libraries.any()) {
            Log.info("[cache] 依存jarの変更を検知: " + libraries
                    + "。それらのパッケージを参照するファイルと、型解決に失敗していたファイルを解析し直します");
        }

        try (BufferedWriter cacheOut = Files.newBufferedWriter(tmpCache, StandardCharsets.UTF_8)) {
            cacheOut.write(CacheFormat.headerFor(config.sourceLevel, config.sourceEncoding,
                    config.hintPluginFingerprint));
            cacheOut.newLine();
            for (LibraryFact l : libraries.current) {
                writeLine(cacheOut, l.toRow());
            }
            BlockWriter writer = new BlockWriter(cacheOut, result, progress, this::hashOf, oldConstants);

            // --- パス1: 有効なブロックと「変わった型」を集める ---
            Set<String> valid = new HashSet<>();
            StaleTypes stale = new StaleTypes(libraries.changedPackages);
            Set<String> libraryAffected = new HashSet<>();   // 型解決に失敗していて、jar の追加で変わりうるファイル
            if (oldCacheUsable && !scanOldCache(live, valid, stale, libraries.anyAddedOrChanged(),
                    libraryAffected)) {
                // 途中で切れている・読めないキャッシュ。中途半端に再利用すると呼び出しが静かに欠けるので、
                // 丸ごと捨てて全件解析し直す（ヘッダが違ったときと同じ扱い）
                valid.clear();
                libraryAffected.clear();
            }
            // ソース一覧の指紋（T行）。パス1 の後に書くのは、有効なブロックの F 行に記録された
            // ハッシュを使い回して、ソースを読み直さずに済ませるため
            writeLine(cacheOut, CacheFormat.sourcesRow(fingerprintOf(live)));

            // --- パス2: 変更・追加されたファイルを解析 ---
            List<SourceFile> changed = new ArrayList<>();
            List<SourceFile> unresolvedBefore = new ArrayList<>();
            for (Map.Entry<String, SourceFile> en : live.entrySet()) {
                if (libraryAffected.contains(en.getKey())) {
                    unresolvedBefore.add(en.getValue());
                } else if (!valid.contains(en.getKey())) {
                    changed.add(en.getValue());
                }
            }
            writer.stale = stale;
            // ファイル自身が変わっているので、宣言する型は無条件に「変わった型」へ（改名・追加に備える）
            writer.cascade = Cascade.ALWAYS;
            if (partialCache != null) {
                changed = salvageFrom(partialCache, changed, live, cacheOut, stale, result);
                writer.skipped(result.salvaged);
            }
            analyzeInBatches(extractor, changed, writer);
            writer.countAs = Reason.BY_LIBRARY;
            analyzeInBatches(extractor, unresolvedBefore, writer);

            // --- パス3・パス4: 依存で無効になったファイルを解析し直す（定数が絡むと連鎖するので不動点まで） ---
            writer.cascade = Cascade.WHEN_CONSTANTS_CHANGED;
            if (oldCacheUsable && !valid.isEmpty()) {
                reanalyzeDependents(extractor, writer, live, valid, stale);

                // --- パス5: 最後まで有効だったブロックを書き写す ---
                copyValidBlocks(live, valid, cacheOut, result);
                writer.skipped(result.reused);
            }

            // 最終行。次回、ここまで書き終えたキャッシュかどうかを見分けるための印
            writeLine(cacheOut, CacheFormat.trailerFor(result.parsed + result.reused + result.salvaged));
        }
        progress.finish();

        Files.move(tmpCache, config.cacheFile, StandardCopyOption.REPLACE_EXISTING);
        return result;
    }

    private static List<SourceFile> filesOf(List<String> relativePaths, Map<String, SourceFile> live) {
        List<SourceFile> files = new ArrayList<>();
        for (String rel : relativePaths) {
            SourceFile file = live.get(rel);
            if (file != null) {
                files.add(file);
            }
        }
        return files;
    }

    /** BATCH_SIZE 件ずつまとめてパースし、1ファイル分ずつ writer に渡す */
    private static void analyzeInBatches(CallEdgeExtractor extractor, List<SourceFile> files,
                                         BlockWriter writer) throws IOException {
        for (int from = 0; from < files.size(); from += CallEdgeExtractor.BATCH_SIZE) {
            // 中止の確認はバッチの切れ目で行う。ここで抜けてもキャッシュはテンポラリのままなので壊れない
            RunControl.checkCancelled();
            RunControl.progress("ソース解析", from, files.size());
            int to = Math.min(files.size(), from + CallEdgeExtractor.BATCH_SIZE);
            extractor.analyzeBatch(files.subList(from, to), writer);
        }
    }

    /**
     * ファイルを解析し直す理由（集計の内訳）。{@link StaleTypes#touches} の判定結果と
     * {@link BlockWriter#countAs} の両方で使う（以前は別々の定数で同じ意味を表していた）
     */
    private enum Reason {
        /** 変わった型にも jar にも触れていない（再解析しない）。自分が変わった（または新規）ファイルの集計にも使う */
        UNTOUCHED,
        /** 依存する型（ソース）が変わった */
        BY_SOURCE,
        /** 依存 jar が変わった */
        BY_LIBRARY
    }

    /**
     * 解析し直したファイルが宣言する型を「変わった型」に加えるかどうか。
     *
     * 加えると、その型を参照しているファイルがもう一周で再解析に回る（{@link CacheUpdater} の
     * 「定数の連鎖」）。無条件に加えると、解析し直すたびに参照元へ芋づる式に広がって
     * 差分更新の意味が無くなるので、必要な場合だけに絞る。
     */
    private enum Cascade {
        /** 常に加える（パス2。ファイル自身が変わっているので、型の改名・追加がありうる） */
        ALWAYS,
        /**
         * 宣言している定数（K行）の値が旧キャッシュと違うときだけ加える（パス4）。
         *
         * コンパイル時定数の値は、それを使っている側のファイルに焼き込まれる。
         * このファイルを解析し直した結果その値が変わっていたなら、使っている側にも
         * 古い値が残っているので解析し直す必要がある。値が変わっていなければ、
         * 使っている側の事実は変わらないので連鎖させない（ここが「案3」との違いで、
         * 不要な再解析が増えないようにしている）
         */
        WHEN_CONSTANTS_CHANGED
    }

    /**
     * 解析結果を受け取って即座にキャッシュへ書き出し、件数と進捗を数える。
     * 1ファイル分だけをヒープに載せ、書き出したら即破棄する。
     */
    private static final class BlockWriter implements CallEdgeExtractor.Sink {
        private final BufferedWriter cacheOut;
        private final CachePhaseResult result;
        private final Progress progress;
        /** 解析したファイルの内容ハッシュを求める（F行に書くため） */
        private final Function<SourceFile, String> hasher;
        /** 相対パス -> 旧キャッシュの定数の指紋（{@link Cascade#WHEN_CONSTANTS_CHANGED} の判定用） */
        private final Map<String, String> oldConstants;
        /** 「変わった型」の集合。非nullのときだけ {@link #cascade} に従って型を加える */
        StaleTypes stale;
        /** 解析したファイルが宣言する型を「変わった型」に加える条件 */
        Cascade cascade = Cascade.ALWAYS;
        /** 解析した理由。集計の内訳に使う（UNTOUCHED は「自分が変わった・新規」） */
        Reason countAs = Reason.UNTOUCHED;
        private long done;

        BlockWriter(BufferedWriter cacheOut, CachePhaseResult result, Progress progress,
                    Function<SourceFile, String> hasher, Map<String, String> oldConstants) {
            this.cacheOut = cacheOut;
            this.result = result;
            this.progress = progress;
            this.hasher = hasher;
            this.oldConstants = oldConstants;
        }

        /** 解析したファイルが宣言する型を「変わった型」に加えるか */
        private boolean shouldCascade(SourceFile file, FileAnalysis fa) {
            if (cascade == Cascade.ALWAYS) {
                return true;
            }
            String before = oldConstants.getOrDefault(file.relativePath(), "");
            return !before.equals(constantsDigestOf(fa));
        }

        @Override
        public void accept(SourceFile file, FileAnalysis fa) throws IOException {
            fa.hash = hasher.apply(file);
            writeBlock(fa, cacheOut);
            result.unresolved += fa.unresolvedCount();
            result.parsed++;
            countReason();
            if (stale != null && shouldCascade(file, fa)) {
                for (TypeFact t : fa.types) {
                    stale.add(t.typeFqn(), t.pkg());
                }
            }
            progress.step(++done);
        }

        @Override
        public void failed(SourceFile file, Exception error) {
            result.failed++;
            countReason();
            Log.warn("解析失敗（スキップ）: " + file.relativePath() + " (" + error.getMessage() + ")");
            progress.step(++done);
        }

        private void countReason() {
            if (countAs == Reason.BY_SOURCE) {
                result.dependents++;
            } else if (countAs == Reason.BY_LIBRARY) {
                result.libraryDependents++;
            }
        }

        /** 再利用したぶんを進捗に足す */
        void skipped(long count) {
            done += count;
            progress.step(done);
        }
    }

    /**
     * 変更・削除されたファイルが宣言していた型とそのパッケージ、
     * および追加・変更・削除された jar のパッケージ
     */
    private static final class StaleTypes {

        private final Set<String> types = new HashSet<>();
        private final Set<String> packages = new HashSet<>();
        private final Set<String> libraryPackages;

        StaleTypes(Set<String> libraryPackages) {
            this.libraryPackages = libraryPackages;
        }

        void add(String typeFqn, String pkg) {
            types.add(typeFqn);
            packages.add(pkg == null ? "" : pkg);
        }

        /** 「変わった型」も「変わった jar のパッケージ」も無い（＝どのブロックも再解析に回らない） */
        boolean isEmpty() {
            return types.isEmpty() && libraryPackages.isEmpty();
        }

        /**
         * これまでに加えた型の数。連鎖の打ち切りに使う。
         * 1周しても増えていなければ、もう一周しても同じ結果になる（型は増える一方で減らない）
         */
        int mark() {
            return types.size();
        }

        /**
         * I行（依存する型のカンマ区切り）が、変わった型または変わった jar のパッケージに触れているか。
         * "pkg.*"（オンデマンド import）は、そのパッケージの型が1つでも変わっていれば触れているとみなす。
         * ソースの変更に触れていればそちらを理由として返す（集計の内訳のため）。
         *
         */
        Reason touches(String depsCsv) {
            if (depsCsv.isEmpty() || (types.isEmpty() && libraryPackages.isEmpty())) {
                return Reason.UNTOUCHED;
            }
            boolean library = false;
            for (String d : depsCsv.split(",")) {
                if (d.isEmpty()) {
                    continue;
                }
                if (d.endsWith(".*")) {
                    String p = d.substring(0, d.length() - 2);
                    if (types.contains(p) || packages.contains(p)) {
                        return Reason.BY_SOURCE;
                    }
                    library |= libraryPackages.contains(p);
                } else if (types.contains(d)) {
                    return Reason.BY_SOURCE;
                } else {
                    library |= inLibraryPackage(d);
                }
            }
            return library ? Reason.BY_LIBRARY : Reason.UNTOUCHED;
        }

        /**
         * 型名が、変わった jar のパッケージのものか。
         * 名前だけではどこまでがパッケージか（内部クラスかどうか）分からないので、
         * "." で区切った前方部分を全部試す
         */
        private boolean inLibraryPackage(String typeFqn) {
            if (libraryPackages.isEmpty()) {
                return false;
            }
            for (int i = typeFqn.indexOf('.'); i > 0; i = typeFqn.indexOf('.', i + 1)) {
                if (libraryPackages.contains(typeFqn.substring(0, i))) {
                    return true;
                }
            }
            return false;
        }
    }

    /** F行のエラー数（v11 で追加した列。無ければ 0） */
    private static int errorsOf(String[] f) {
        try {
            return Integer.parseInt(CacheFormat.columnAt(f, 4));
        } catch (NumberFormatException ignore) {
            return 0;
        }
    }

    /**
     * ブロックのF行が、今のソースと一致しているか。
     * 更新時刻とサイズの両方が一致すれば一致。更新時刻だけが違うときは、サイズが同じで、
     * F行に内容ハッシュがあり、今のファイルの内容ハッシュと一致すれば一致とみなす
     * （旧形式の F 行にはハッシュが無いので、その場合は従来どおり不一致）。
     */
    private boolean isValidBlock(String[] f, Map<String, SourceFile> live) {
        if (f.length < 4) {
            return false;
        }
        SourceFile st = live.get(f[1]);
        if (st == null) {
            return false;
        }
        try {
            if (st.size() != Long.parseLong(f[3])) {
                return false;
            }
            if (st.mtime() == Long.parseLong(f[2])) {
                return true;
            }
        } catch (NumberFormatException ignore) {
            return false;   // 壊れたF行 -> このブロックは破棄し、後で再解析される
        }
        String recorded = CacheFormat.columnAt(f, 5);
        return !recorded.isEmpty() && recorded.equals(hashOf(st));
    }

    /** 今のソースの内容ハッシュ（計算は 1 ファイル 1 回）。読めなければ空文字 */
    private String hashOf(SourceFile file) {
        String h = hashes.get(file.relativePath());
        if (h == null) {
            try {
                h = FileHash.of(file.path());
            } catch (IOException e) {
                Log.warn("ソースのハッシュを取れません（更新時刻とサイズだけで判定）: " + file.relativePath() + " (" + e + ")");
                h = "";
            }
            hashes.put(file.relativePath(), h);
        }
        return h;
    }

    /**
     * パス3で書き写すF行。今の更新時刻と内容ハッシュに置き換える。
     * 更新時刻が一致していれば旧行のハッシュをそのまま使う（無ければ計算して補う）。
     * 更新時刻が違っていた（ハッシュで通した）ブロックは、次回は更新時刻の一致で通るようになる
     */
    private String refreshedFileRow(String[] f, SourceFile st) {
        String hash = CacheFormat.columnAt(f, 5);
        if (hash.isEmpty() || st.mtime() != Long.parseLong(f[2])) {
            hash = hashOf(st);
        }
        return CacheFormat.joinRow("F", f[1], String.valueOf(st.mtime()), String.valueOf(st.size()),
                String.valueOf(errorsOf(f)), hash);
    }

    /**
     * パス0。旧キャッシュのヘッダを検証し、続く L 行（解析時の依存 jar）を読む。
     * 形式・ソースレベル・JDK・フェーズAの拡張のどれかが違えば null（旧キャッシュは使わず全件再解析）。
     */
    private List<LibraryFact> readOldLibraries() {
        if (!Files.isRegularFile(config.cacheFile)) {
            return null;
        }
        try {
            CacheHead head = headOf(config.cacheFile);
            List<LibraryFact> libraries = (head == null) ? null : head.libraries();
            if (libraries == null) {
                // 形式が変わった場合のほか、source.level・ソースの文字コード・実行 JDK が
                // 変わった場合もここで破棄する。言語バージョン・文字コード・ブートクラスパスが違えば
                // 同じソースでも解析結果が変わるため、更新時刻とサイズが一致していても再利用してはいけない
                Log.info("[cache] 形式・ソースレベル・文字コード・JDK のいずれかが異なるため既存キャッシュを破棄します");
            }
            return libraries;
        } catch (IOException | RuntimeException e) {
            // 読めない・文字が壊れているキャッシュ。全件解析し直せば済むので、解析ごと失敗させない
            Log.warn("[cache] 既存キャッシュを読めないため破棄して全件解析します: " + e);
            return null;
        }
    }

    // ------------------------------------------------------------
    // 中断した前回の実行からの引き継ぎ
    // ------------------------------------------------------------

    /**
     * 前回の実行が途中で終わったときに残る一時ファイルを、引き継ぎ用に退避する。
     *
     * キャッシュは一時ファイルへ書いてから本物に差し替えるので、フェーズ1の途中で実行が終わると
     * 一時ファイルだけが残る。これを退避しておき、これから解析するファイルのぶんは
     * パースし直さずに書き写す（{@link #salvageFrom}）。
     * 退避しておくのは、これから書く一時ファイルと名前がぶつかるため。
     * ついでに、残りっぱなしになっていた一時ファイルの掃除にもなる。
     *
     * @return 引き継ぎに使うファイル。残っていない・引き継がない設定なら null
     */
    private Path takeOverPartial(Path tmpCache) {
        Path partial = config.cacheFile.resolveSibling(config.cacheFile.getFileName() + ".partial");
        try {
            // cache.enabled=false は「前の結果を使わない」指定なので、引き継ぎもしない
            if (!config.cacheEnabled) {
                Files.deleteIfExists(partial);
                return null;
            }
            if (Files.isRegularFile(tmpCache)) {
                Files.move(tmpCache, partial, StandardCopyOption.REPLACE_EXISTING);
            }
            // 一時ファイルが無くても、前回が退避した直後に落ちていれば退避先が残っている
            return Files.isRegularFile(partial) ? partial : null;
        } catch (IOException e) {
            Log.warn("[cache] 中断した前回の実行の一時ファイルを退避できません（引き継ぎません）: " + e);
            return null;
        }
    }

    /**
     * 中断した前回の実行から、これから解析するファイルのブロックを書き写す。
     *
     * <p>退避した一時ファイルは<b>正しいキャッシュではない</b>（再利用ぶんのブロックが入っておらず、
     * 依存の判定も通っていない）。そのため「どのブロックが有効か」の判断には使わず、
     * <b>パースの使い回し</b>にだけ使う。引き継いだファイルは、解析したファイルとまったく同じ扱いで
     * 「変わったファイル」のまま（宣言する型は「変わった型」に入り、依存の判定も連鎖も回る）。
     * 変わるのは「JDT でパースするか、書き写すか」だけなので、差分更新の正しさの理屈には触れない。
     *
     * <p>引き継ぐ条件（{@link #isPartialUsable}）:
     * <ul>
     *   <li>ヘッダ（形式・ソースレベル・文字コード・JDK・拡張の指紋）が今回と一致する</li>
     *   <li>ソース一覧（T行。パス・サイズ・内容ハッシュ）が当時と丸ごと同じ。
     *       ブロックは他のファイルの内容にも依存するため。更新時刻は見ないので、
     *       git のチェックアウトなどで更新時刻だけが変わっていても引き継げる</li>
     *   <li>依存 jar が当時から変わっていない。ブロックは当時のクラスパスでのバインディング解決の
     *       結果なので、jar が変われば同じソースでも呼び出し先や親型が変わりうる</li>
     *   <li>ファイルの更新時刻とサイズ（または内容ハッシュ）が一致する（{@link #isValidBlock}）</li>
     *   <li>ブロックが最後まで書けている。次の F 行か、最後まで書き終えた印（Z 行）に
     *       出会ったブロックだけを使う。最後の F 行から始まるブロックは、書き込みバッファの
     *       途中で切れている可能性があるので使わない</li>
     * </ul>
     *
     * @return まだ解析が必要なファイル（引き継げたぶんを除いたもの）
     */
    private List<SourceFile> salvageFrom(Path partial, List<SourceFile> toAnalyze,
                                         Map<String, SourceFile> live, BufferedWriter cacheOut,
                                         StaleTypes stale, CachePhaseResult result) throws IOException {
        Set<String> taken = new HashSet<>();
        if (isPartialUsable(partial, live)) {
            Set<String> wanted = new HashSet<>();
            for (SourceFile f : toAnalyze) {
                wanted.add(f.relativePath());
            }
            copySalvageable(partial, wanted, live, cacheOut, stale, result, taken);
        }
        try {
            Files.deleteIfExists(partial);
        } catch (IOException e) {
            Log.warn("[cache] 引き継ぎに使った一時ファイルを消せません: " + e);
        }
        if (taken.isEmpty()) {
            return toAnalyze;
        }
        Log.info("[cache] 中断した前回の実行から " + taken.size()
                + " ファイルぶんの解析結果を引き継ぎました（解析し直しません）");
        List<SourceFile> rest = new ArrayList<>();
        for (SourceFile f : toAnalyze) {
            if (!taken.contains(f.relativePath())) {
                rest.add(f);
            }
        }
        return rest;
    }

    /**
     * 退避した一時ファイルを引き継いでよいか。
     *
     * ヘッダ（形式・ソースレベル・文字コード・JDK・拡張の指紋）・依存 jar・<b>ソース一覧</b>が
     * どれも当時と同じときだけ引き継ぐ。
     *
     * <p>ソース一覧まで見るのは、引き継ぐブロックが「そのファイルの内容」だけでなく
     * 「他のファイルの内容」にも依存するため。呼び出し先・親型・コンパイル時定数の値は
     * バインディング解決の結果なので、<b>別のファイルが変わっていれば、そのファイル自身が
     * 変わっていなくてもブロックは古い</b>。差分更新はこれを I 行の依存で見分けるが、
     * 引き継ぎのブロックは「今このファイルを解析した結果」として書き込むので依存の判定を通らない。
     * 判定を通す作りにもできるが（退避した一時ファイルを既存キャッシュと同じ扱いで読む）、
     * 引き継ぎが効いてほしい場面は「中断してすぐ同じソースで実行し直す」なので、
     * 一覧が丸ごと同じときだけに絞る簡単な形にした。違えば引き継がないだけで、
     * 差分更新はこれまでどおり動く。
     */
    private boolean isPartialUsable(Path partial, Map<String, SourceFile> live) {
        CacheHead head;
        try {
            head = headOf(partial);
        } catch (IOException | RuntimeException e) {
            Log.warn("[cache] 中断した前回の実行の一時ファイルを読めません（引き継ぎません）: " + e);
            return false;
        }
        if (head == null) {
            Log.info("[cache] 中断した前回の実行とは形式・ソースレベル・文字コード・JDK のいずれかが"
                    + "異なるため引き継ぎません");
            return false;
        }
        if (!head.sources().equals(fingerprintOf(live))) {
            Log.info("[cache] 中断した前回の実行からソースの内容が変わっているため引き継ぎません"
                    + "（変わっていないファイルの解析結果も、他のファイルの変更で変わりうるため）");
            return false;
        }
        LibraryDiff diff = LibraryDiff.compute(layout.classpathArray(), head.libraries(), layout.projectRoot);
        if (diff.any()) {
            Log.info("[cache] 中断した前回の実行から依存jarが変わっているため引き継ぎません: " + diff);
            return false;
        }
        return true;
    }

    /** 引き継げるブロックを新キャッシュへ書き写す。書き写せたファイルを taken に積む */
    private void copySalvageable(Path partial, Set<String> wanted, Map<String, SourceFile> live,
                                 BufferedWriter cacheOut, StaleTypes stale, CachePhaseResult result,
                                 Set<String> taken) throws IOException {
        try (CacheReader in = CacheReader.open(partial)) {
            List<String> block = new ArrayList<>();   // 直前の F 行から始まる、判定待ちのブロック
            String rel = null;
            while (in.next()) {
                char rowType = in.rowType();
                if (rowType != CacheFormat.ROW_FILE && rowType != CacheFormat.ROW_END) {
                    if (rel != null) {
                        block.add(in.line());
                    }
                    continue;
                }
                // 次のブロックが始まった、または最後まで書き終えた印に出会った。
                // どちらでも、ここまでのブロックは書き終えている
                flushSalvaged(rel, block, cacheOut, stale, result, taken);
                rel = null;
                block.clear();
                if (rowType == CacheFormat.ROW_END) {
                    continue;
                }
                String[] f = in.columns();
                if (f.length >= 2 && wanted.contains(f[1]) && !taken.contains(f[1])
                        && isValidBlock(f, live)) {
                    rel = f[1];
                    block.add(refreshedFileRow(f, live.get(rel)));
                }
            }
            // 最後の F 行から始まるブロックは、途中で切れている可能性があるので使わない
        } catch (IOException | RuntimeException e) {
            Log.warn("[cache] 中断した前回の実行の一時ファイルを読めません（そこまでで引き継ぎを打ち切ります）: " + e);
        }
    }

    /**
     * 引き継ぐブロック1件を書き写し、宣言する型と未解決の件数を数える。
     * 数え方は解析したときと同じにする（{@link Cascade#ALWAYS} 相当）。
     */
    private static void flushSalvaged(String rel, List<String> block, BufferedWriter cacheOut,
                                      StaleTypes stale, CachePhaseResult result, Set<String> taken)
            throws IOException {
        if (rel == null || block.isEmpty()) {
            return;
        }
        for (String line : block) {
            writeLine(cacheOut, line);
            switch (CacheFormat.rowTypeOf(line)) {
                case CacheFormat.ROW_TYPE -> {
                    TypeFact t = TypeFact.fromRow(CacheFormat.columnsOf(line));
                    if (t != null) {
                        stale.add(t.typeFqn(), t.pkg());
                    }
                }
                case CacheFormat.ROW_UNRESOLVED -> {
                    UnresolvedCallFact u = UnresolvedCallFact.fromRow(CacheFormat.columnsOf(line));
                    if (u != null && !u.hasUsableCandidate()) {
                        result.unresolved++;
                    }
                }
                default -> {
                    // ほかの行はそのまま書き写すだけ
                }
            }
        }
        result.salvaged++;
        taken.add(rel);
    }

    /**
     * キャッシュのヘッダの直後にある情報。
     *
     * @param libraries 解析時の依存 jar（L行）
     * @param sources   解析開始時のソース一覧の指紋（T行）。無ければ空文字
     */
    private record CacheHead(List<LibraryFact> libraries, String sources) {
    }

    /**
     * ヘッダが今回と一致すれば、続く T 行・L 行を返す。一致しなければ null。
     * 既存キャッシュ（パス0）と、中断した前回の実行の一時ファイル（引き継ぎ）で共通。
     */
    private CacheHead headOf(Path cacheFile) throws IOException {
        try (CacheReader in = CacheReader.open(cacheFile)) {
            if (!in.headerMatches(CacheFormat.headerFor(config.sourceLevel, config.sourceEncoding,
                    config.hintPluginFingerprint))) {
                return null;
            }
            List<LibraryFact> libraries = new ArrayList<>();
            String sources = "";
            while (in.next()) {
                if (in.is(CacheFormat.ROW_LIBRARY)) {
                    LibraryFact l = LibraryFact.fromRow(in.columns());
                    if (l != null) {
                        libraries.add(l);
                    }
                } else if (in.is(CacheFormat.ROW_SOURCES)) {
                    sources = in.column(1);
                } else {
                    break;   // ブロックが始まった
                }
            }
            return new CacheHead(libraries, sources);
        }
    }

    /**
     * 解析対象のソース一覧の指紋（相対パス・サイズ・内容ハッシュ）。
     *
     * 引き継ぎの判定に使う。1ファイルぶんの更新時刻とサイズが一致していても、他のファイルが
     * 変わっていればそのブロックの解決結果は古いので、一覧が丸ごと同じときだけ引き継ぐ。
     *
     * <p><b>更新時刻は入れない</b>。中身と関係なく変わる（git のチェックアウト、コピー、
     * CI のたびに作り直されるワークスペース）ので、入れると「中身は同じなのに引き継げない」が
     * 頻発する。差分更新の判定が内容ハッシュで救っているのと同じ考え方
     * （{@link #isValidBlock}、docs/actions-analysis-cache-qa.md）。
     *
     * <p>全ファイルのハッシュが要るが、ほとんどは読み直さずに済む。更新時刻とサイズが
     * 旧キャッシュの F 行と一致するファイルは、記録されているハッシュがそのまま
     * 「今の内容のハッシュ」なので、パス1 でそれを覚えておく（{@link #rememberHash}）。
     * 残るのは「これから解析するファイル」だけで、そのハッシュはどのみち F 行に書くために計算する。
     */
    private String fingerprintOf(Map<String, SourceFile> live) {
        List<String> lines = new ArrayList<>(live.size());
        for (SourceFile f : live.values()) {
            lines.add(f.relativePath() + "\t" + f.size() + "\t" + hashOf(f));
        }
        Collections.sort(lines);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        return FileHash.ofText(sb.toString());
    }

    /**
     * 旧キャッシュの F 行に記録されていたハッシュを「今の内容のハッシュ」として覚える。
     *
     * 更新時刻とサイズが一致するブロックでだけ呼ぶ。中身が同じと判定した以上、記録された
     * ハッシュは今の内容のものでもあるので、ソースを読み直さずに済む
     */
    private void rememberHash(SourceFile file, String hash) {
        if (file != null && !hash.isEmpty()) {
            hashes.putIfAbsent(file.relativePath(), hash);
        }
    }

    /**
     * パス1。旧キャッシュを読み、有効なファイルの集合と「変わった型」を集める。
     *
     * 最後まで書き終えたキャッシュか（最終行の印。{@link CacheFormat#trailerFor}）もここで見る。
     * 途中で切れたキャッシュは、切れた場所より前のブロックが「更新時刻もサイズも一致する」ように
     * 見えるため、そのまま再利用すると呼び出しが静かに欠ける。印が無ければ false を返して
     * 丸ごと捨てさせる。
     *
     * @param librariesAddedOrChanged jar が追加・変更されたか。そのときは型解決に失敗していたブロック
     *                                （F行のエラー数が 0 でない、または U 行に BINDING_FAILED がある）を
     *                                有効から外し、libraryAffected に積む
     * @return 旧キャッシュをそのまま使ってよければ true。途中で切れている・読めないなら false
     */
    private boolean scanOldCache(Map<String, SourceFile> live, Set<String> valid, StaleTypes stale,
                                 boolean librariesAddedOrChanged, Set<String> libraryAffected) {
        long blocks = 0;
        String lastLine = "";
        try (CacheReader in = CacheReader.open(config.cacheFile)) {   // ヘッダはパス0で検証済み
            boolean staleBlock = false;
            String currentRel = null;
            String blockRel = null;                       // 有効・無効によらずブロックのファイル
            List<String> blockConstants = new ArrayList<>();
            while (in.next()) {
                char rowType = in.rowType();
                lastLine = in.line();
                if (rowType == CacheFormat.ROW_FILE) {
                    blocks++;
                    rememberConstants(blockRel, blockConstants);
                    String[] f = in.columns();
                    blockRel = (f.length >= 2) ? f[1] : null;
                    staleBlock = !isValidBlock(f, live);
                    if (!staleBlock) {
                        // 中身が同じと判定できたので、記録されたハッシュは今の内容のものでもある
                        rememberHash(live.get(f[1]), CacheFormat.columnAt(f, 5));
                    }
                    currentRel = staleBlock ? null : f[1];
                    if (currentRel != null) {
                        if (librariesAddedOrChanged && errorsOf(f) > 0) {
                            libraryAffected.add(currentRel);   // 宣言する型はパス2の解析時に「変わった型」へ入る
                            currentRel = null;
                        } else {
                            valid.add(currentRel);
                        }
                    }
                } else if (rowType == CacheFormat.ROW_CONSTANT) {
                    ConstantFact k = ConstantFact.fromRow(in.columns());
                    if (k != null) {
                        blockConstants.add(k.fingerprint());
                    }
                } else if (staleBlock && rowType == CacheFormat.ROW_TYPE) {
                    TypeFact t = TypeFact.fromRow(in.columns());
                    if (t != null) {
                        stale.add(t.typeFqn(), t.pkg());
                    }
                } else if (currentRel != null && librariesAddedOrChanged
                        && rowType == CacheFormat.ROW_UNRESOLVED
                        && UnresolvedCallFact.BINDING_FAILED.equals(in.column(7))) {
                    // エラーとしては報告されなかったが呼び出し先が解決できなかった。jar の追加で変わりうる
                    valid.remove(currentRel);
                    libraryAffected.add(currentRel);
                    currentRel = null;
                }
            }
            rememberConstants(blockRel, blockConstants);   // 最後のブロック
        } catch (IOException | RuntimeException e) {
            Log.warn("[cache] 既存キャッシュを読めないため破棄して全件解析します: " + e);
            return false;
        }
        if (!lastLine.equals(CacheFormat.trailerFor(blocks))) {
            Log.info("[cache] 既存キャッシュが途中で切れているため破棄して全件解析します"
                    + "（ファイル " + blocks + " 件ぶんを読みましたが、最後まで書き終えた印がありません）");
            return false;
        }
        return true;
    }

    /** 1ブロック分の K 行の指紋をまとめて覚え、次のブロックのために溜めた分を捨てる */
    private void rememberConstants(String rel, List<String> fingerprints) {
        if (rel != null && !fingerprints.isEmpty()) {
            oldConstants.put(rel, digestOf(fingerprints));
        }
        fingerprints.clear();
    }

    /**
     * パス3・パス4。「変わった型」に触れる有効ブロックを再解析に回し、解析し直した結果として
     * 「変わった型」が増えていたら（定数の連鎖。{@link CacheUpdater} のクラスコメント参照）もう一周する。
     *
     * ふつうは1周で止まる。周回が続くのは、定数を宣言しているファイルが数珠つなぎになっている
     * ときだけ。1周ごとに valid は減るだけで増えないので、必ず止まる。
     */
    private void reanalyzeDependents(CallEdgeExtractor extractor, BlockWriter writer,
                                     Map<String, SourceFile> live, Set<String> valid, StaleTypes stale)
            throws IOException {
        if (stale.isEmpty()) {
            return;   // 触れる先が無いので、読み直すだけ無駄
        }
        int mark;
        do {
            mark = stale.mark();
            List<String> dependents = new ArrayList<>();
            List<String> libraryDependents = new ArrayList<>();
            selectDependents(valid, stale, dependents, libraryDependents);
            writer.countAs = Reason.BY_SOURCE;
            analyzeInBatches(extractor, filesOf(dependents, live), writer);
            writer.countAs = Reason.BY_LIBRARY;
            analyzeInBatches(extractor, filesOf(libraryDependents, live), writer);
        } while (stale.mark() != mark && !valid.isEmpty());
    }

    /**
     * パス3。旧キャッシュを1行ずつ読み、有効なブロックのうち「変わった型」または
     * 「変わった jar のパッケージ」に触れるものを valid から外し、再解析の一覧に積む。
     *
     * 依存はブロック先頭のI行で判定する（F行の直後に置いてあるので、先読みは1行で済む）。
     * ここでは何も書き出さない。書き写しは、連鎖が止まってから {@link #copyValidBlocks} で行う。
     */
    private void selectDependents(Set<String> valid, StaleTypes stale,
                                  List<String> dependents, List<String> libraryDependents)
            throws IOException {
        try (CacheReader in = CacheReader.open(config.cacheFile)) {   // ヘッダはパス0で検証済み
            String pendingRel = null;   // 依存の判定待ちのファイル
            while (in.next()) {
                char rowType = in.rowType();
                if (rowType == CacheFormat.ROW_FILE) {
                    String[] f = in.columns();
                    pendingRel = (f.length >= 2 && valid.contains(f[1])) ? f[1] : null;
                    continue;
                }
                if (pendingRel == null) {
                    continue;
                }
                // F行の直後。I行なら依存を判定し、無ければ依存なしとみなす
                String deps = (rowType == CacheFormat.ROW_DEPENDENCIES) ? in.column(1) : "";
                Reason touched = stale.touches(deps);
                if (touched == Reason.BY_SOURCE) {
                    valid.remove(pendingRel);
                    dependents.add(pendingRel);
                } else if (touched == Reason.BY_LIBRARY) {
                    valid.remove(pendingRel);
                    libraryDependents.add(pendingRel);
                }
                pendingRel = null;
            }
        }
    }

    /**
     * パス5。旧キャッシュを1行ずつ読み、最後まで有効だったブロックをそのまま新キャッシュへ書き写す。
     *
     * F 行だけは今の更新時刻と内容ハッシュに書き直す（次回は更新時刻の一致で通るように）。
     * L 行はブロックの外（先頭）にあり、ここでは書き写さない（パス0で新しいものを書いている）。
     *
     * 書き写したブロックの数を {@code result.reused} に、そこに含まれる型解決できなかった
     * 呼び出しの件数を {@code result.unresolved} に足す。数は最終行（{@link CacheFormat#trailerFor}）
     * にも出すので、valid の件数ではなく実際に書いた数を数える。
     */
    private void copyValidBlocks(Map<String, SourceFile> live, Set<String> valid,
                                 BufferedWriter cacheOut, CachePhaseResult result) throws IOException {
        try (CacheReader in = CacheReader.open(config.cacheFile)) {   // ヘッダはパス0で検証済み
            boolean keeping = false;
            while (in.next()) {
                char rowType = in.rowType();
                if (rowType == CacheFormat.ROW_FILE) {
                    String[] f = in.columns();
                    keeping = (f.length >= 2 && valid.contains(f[1]));
                    if (keeping) {
                        writeLine(cacheOut, refreshedFileRow(f, live.get(f[1])));
                        result.reused++;
                    }
                    continue;
                }
                if (rowType == CacheFormat.ROW_END) {
                    keeping = false;   // 旧キャッシュの最終行。新しいものを書き終わりに1行だけ書く
                    continue;
                }
                if (keeping) {
                    writeLine(cacheOut, in.line());
                    if (rowType == CacheFormat.ROW_UNRESOLVED) {
                        UnresolvedCallFact u = UnresolvedCallFact.fromRow(in.columns());
                        if (u != null && !u.hasUsableCandidate()) {
                            result.unresolved++;
                        }
                    }
                }
            }
        }
    }

    private static void writeLine(BufferedWriter w, String line) throws IOException {
        w.write(line);
        w.newLine();
    }

    /** 1ファイル分のブロックを書く。行の並びは {@link CacheFormat} のとおり */
    private static void writeBlock(FileAnalysis fa, BufferedWriter w) throws IOException {
        writeLine(w, CacheFormat.joinRow("F", fa.relativePath,
                String.valueOf(fa.lastModified), String.valueOf(fa.size), String.valueOf(fa.errors), fa.hash));
        // I行はF行の直後に置く（差分更新で、ブロックを読み進める前に依存を判定するため）
        writeLine(w, CacheFormat.joinRow("I", String.join(",", dependenciesOf(fa))));
        for (TypeFact t : fa.types) {
            writeLine(w, t.toRow());
        }
        for (MethodDeclFact d : fa.declarations) {
            writeLine(w, d.toRow());
        }
        for (FieldDeclFact v : fa.fieldDecls) {
            writeLine(w, v.toRow());
        }
        // K行は指紋の順に並べる。同じソースならいつ解析しても同じ並びになり、
        // 旧キャッシュとの突き合わせ（定数の連鎖）が並び順に振り回されない
        for (String row : sortedConstantRows(fa)) {
            writeLine(w, row);
        }
        for (FieldAssignFact j : fa.fieldAssigns) {
            writeLine(w, j.toRow());
        }
        for (FieldAccessFact a : fa.fieldAccesses) {
            writeLine(w, a.toRow());
        }
        // 呼び出し箇所（解決できたものも失敗したものも）はソース上の順のまま書く。
        // 読み手が import 推定の候補をエッジにしたとき、元の呼び出しの並びが保たれる
        for (CallSite site : fa.callSites) {
            writeLine(w, site.toRow());
        }
        // return は全部書く（追跡できないものも U として）。
        // 「追跡できない return が1つでもあれば戻り値は不定」という判定は読み手が行う
        for (ReturnFact r : fa.returns) {
            writeLine(w, r.toRow());
        }
        for (FunctionalImplFact m : fa.functionalImpls) {
            writeLine(w, m.toRow());
        }
        for (HintFact h : fa.hints) {
            writeLine(w, h.toRow());
        }
    }

    /** K行を指紋の順に並べたもの */
    private static List<String> sortedConstantRows(FileAnalysis fa) {
        List<ConstantFact> sorted = new ArrayList<>(fa.constants);
        sorted.sort(Comparator.comparing(ConstantFact::fingerprint));
        List<String> rows = new ArrayList<>(sorted.size());
        for (ConstantFact k : sorted) {
            rows.add(k.toRow());
        }
        return rows;
    }

    /**
     * 解析結果が宣言している定数の指紋（K行の指紋を並べてハッシュにしたもの）。定数が無ければ空文字。
     *
     * 旧キャッシュ側の同じ形（{@link #rememberConstants}）と突き合わせて、
     * 「値が変わったか」だけを見る。値そのものをヒープに持たないよう、ファイルごとに
     * ハッシュ1つ（16文字）だけ覚える
     */
    private static String constantsDigestOf(FileAnalysis fa) {
        if (fa.constants.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>(fa.constants.size());
        for (ConstantFact k : fa.constants) {
            lines.add(k.fingerprint());
        }
        return digestOf(lines);
    }

    private static String digestOf(List<String> fingerprints) {
        if (fingerprints.isEmpty()) {
            return "";
        }
        Collections.sort(fingerprints);
        StringBuilder sb = new StringBuilder();
        for (String f : fingerprints) {
            sb.append(f).append('\n');
        }
        return FileHash.ofText(sb.toString());
    }

    /**
     * I行の内容。バインディング解決で参照した型と import の型から、
     * 自分が宣言する型を除いたもの（自分の変更は更新時刻とサイズで検知できる）
     */
    private static List<String> dependenciesOf(FileAnalysis fa) {
        Set<String> own = new HashSet<>();
        for (TypeFact t : fa.types) {
            own.add(t.typeFqn());
        }
        TreeSet<String> deps = new TreeSet<>();
        for (String t : fa.referencedTypes) {
            if (!own.contains(t)) {
                deps.add(t);
            }
        }
        for (String t : fa.imports) {
            if (!own.contains(t)) {
                deps.add(t);
            }
        }
        return new ArrayList<>(deps);
    }
}
