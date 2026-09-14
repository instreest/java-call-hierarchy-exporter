// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import jche.cache.CacheFormat;
import jche.cache.CacheReader;
import jche.cache.CallSite;
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
 * <h2>2 つのキャッシュを対で書く</h2>
 * 呼び出し階層のためのキャッシュ（{@code analysis-cache.tsv}）と、データフローのための
 * キャッシュ（{@code dataflow-cache.tsv}）を同じ解析結果から一緒に書く（{@link jche.cache.CacheFormat}）。
 * <b>片方だけが新しい状態は作らない。</b>両方のヘッダに同じ世代の印を書き、
 * 次回の実行で次のどれかに当たれば両方とも捨てて全件解析し直す。
 * <pre>
 *   - どちらかが無い / ヘッダの形式（版・ソースレベル・JDK・拡張の指紋）が違う
 *   - 世代の印が食い違う（前回の実行が 2 つ目を書く前に落ちた、片方だけ差し替えられた）
 * </pre>
 * そのうえで、<b>再利用するブロックは「両方のキャッシュにあるもの」だけ</b>にする（パス1b）。
 * 片方にしか無いブロックは再解析に回すので、ブロック単位のズレも残らない。
 * 書き出しはどちらもテンポラリに作ってから差し替え、analysis → dataflow の順に差し替える
 * （間で落ちれば世代が食い違い、次回は両方とも捨てられる）。
 *
 * 手順:
 * <pre>
 *   パス0 … 旧キャッシュ 2 つのヘッダを検証し（形式と世代）、analysis 側の L 行（解析時の依存 jar）を
 *           読んで今回のクラスパスと突き合わせる。
 *           追加・変更・削除された jar のパッケージを「変わったパッケージ」として集める。
 *   パス1 … 旧キャッシュ（analysis）を順に読み、更新時刻とサイズが一致するファイル（有効）を覚える。
 *           更新時刻だけが違うファイルは、サイズが同じなら内容ハッシュを取って F 行と突き合わせる
 *           （中身が同じなら有効。git のチェックアウトや CI で更新時刻が変わっても再利用できるようにするため）。
 *           無効・消滅したファイルのブロックが宣言していた型（H行）を「変わった型」として集める。
 *           jar が追加・変更されていれば、型解決に失敗していたファイル（F行のエラー数、
 *           U行の BINDING_FAILED）も有効から外す。追加された jar で解決できるようになりうるため。
 *   パス1b… 旧キャッシュ（dataflow）の F 行だけを読み、ブロックがある相対パスを集める。
 *           有効なファイルのうちここに無いものは、dataflow 側のブロックが欠けているので再解析に回す。
 *   パス2 … 変更・追加されたファイルを解析して新キャッシュ 2 つへ書く。
 *           そのファイルが宣言する型も「変わった型」に加える（改名・追加に備える）。
 *   パス3 … 旧キャッシュ（analysis）をもう一度読み、有効なブロックのうち、I行（依存する型）が
 *           「変わった型」にも「変わったパッケージ」にも触れないものだけをそのまま書き写す
 *           （F 行だけは今の更新時刻と内容ハッシュに書き直す。次回は更新時刻の一致で通るように）。
 *           触れるものは、バインディング解決の結果が変わっている可能性があるので再解析に回す。
 *   パス3b… 旧キャッシュ（dataflow）を読み、パス3 が書き写したのと同じ相対パスのブロックだけを書き写す。
 *           パス1b で「両方にあるブロック」に絞ってあるので、ここで欠けることはない。
 *   パス4 … パス3で再解析に回したファイルを解析し、両方に追記する。
 * </pre>
 * どのパスも 2 つのキャッシュへ同じ順で書くので、ブロックの並びは常に一致する。
 *
 * 更新時刻とサイズだけで再利用を決めると、別のファイルの変更（オーバーロードの追加、
 * フィールドの改名、親型の変更など）でこのファイルの解決結果が変わっても気づけない。
 * 依存は1段で足りる。ファイルAの事実はAが参照した型にだけ依存し、Aを解析し直しても
 * Aが宣言する型（Aのソース）は変わらないので、Aに依存するファイルへは波及しない。
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
        Path tmpFlowCache = config.dataflowCacheFile
                .resolveSibling(config.dataflowCacheFile.getFileName() + ".tmp");

        Progress progress = new Progress("ソース解析", javaFiles.size(), CallEdgeExtractor.BATCH_SIZE);
        CallEdgeExtractor extractor = new CallEdgeExtractor(layout, config);

        // --- パス0: 旧キャッシュ2つのヘッダを検証し、依存 jar（L行）を今回のクラスパスと突き合わせる ---
        List<LibraryFact> oldLibraries = config.cacheEnabled ? readOldLibraries() : null;
        boolean oldCacheUsable = (oldLibraries != null);
        LibraryDiff libraries = LibraryDiff.compute(layout.classpathArray(),
                oldCacheUsable ? oldLibraries : List.of(), layout.projectRoot);
        if (oldCacheUsable && libraries.any()) {
            Log.info("[cache] 依存jarの変更を検知: " + libraries
                    + "。それらのパッケージを参照するファイルと、型解決に失敗していたファイルを解析し直します");
        }

        // 2 つのキャッシュに書く同じ世代の印。次回の実行で「対で書かれたか」を判定する
        String generation = CacheFormat.newGeneration();
        try (BufferedWriter cacheOut = Files.newBufferedWriter(tmpCache, StandardCharsets.UTF_8);
             BufferedWriter flowOut = Files.newBufferedWriter(tmpFlowCache, StandardCharsets.UTF_8)) {
            writeLine(cacheOut, CacheFormat.withGeneration(
                    CacheFormat.headerFor(config.sourceLevel, config.hintPluginFingerprint), generation));
            writeLine(flowOut, CacheFormat.withGeneration(
                    CacheFormat.dataflowHeaderFor(config.sourceLevel), generation));
            for (LibraryFact l : libraries.current) {
                writeLine(cacheOut, l.toRow());
            }
            BlockWriter writer = new BlockWriter(cacheOut, flowOut, result, progress, this::hashOf);

            // --- パス1: 有効なブロックと「変わった型」を集める ---
            Set<String> valid = new HashSet<>();
            StaleTypes stale = new StaleTypes(libraries.changedPackages);
            Set<String> libraryAffected = new HashSet<>();   // 型解決に失敗していて、jar の追加で変わりうるファイル
            if (oldCacheUsable) {
                scanOldCache(live, valid, stale, libraries.anyAddedOrChanged(), libraryAffected);
                // --- パス1b: dataflow 側にブロックが無いものは有効から外す（対でないブロックを残さない） ---
                dropBlocksMissingFromDataflowCache(valid, libraryAffected);
            }

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
            writer.stale = stale;   // 解析したファイルが宣言する型も「変わった型」に加える（改名・追加に備える）
            analyzeInBatches(extractor, changed, writer);
            writer.countAs = Reason.BY_LIBRARY;
            analyzeInBatches(extractor, unresolvedBefore, writer);

            // --- パス3: 変わった型・パッケージに依存していない有効ブロックを書き写す ---
            List<String> dependents = new ArrayList<>();
            List<String> libraryDependents = new ArrayList<>();
            if (oldCacheUsable && !valid.isEmpty()) {
                Set<String> copied = new HashSet<>();
                result.unresolved += copyValidBlocks(live, valid, stale, dependents, libraryDependents,
                        cacheOut, copied);
                result.reused = valid.size() - dependents.size() - libraryDependents.size();
                // --- パス3b: 同じブロックを dataflow 側からも書き写す ---
                copyDataflowBlocks(live, copied, flowOut);
                writer.skipped(result.reused);
            }

            // --- パス4: 依存で無効になったファイルを解析し直す ---
            writer.stale = null;
            writer.countAs = Reason.BY_SOURCE;
            analyzeInBatches(extractor, filesOf(dependents, live), writer);
            writer.countAs = Reason.BY_LIBRARY;
            analyzeInBatches(extractor, filesOf(libraryDependents, live), writer);
        }
        progress.finish();

        // analysis を先に差し替える。間で落ちれば世代が食い違い、次回は両方とも捨てられる
        Files.move(tmpCache, config.cacheFile, StandardCopyOption.REPLACE_EXISTING);
        Files.move(tmpFlowCache, config.dataflowCacheFile, StandardCopyOption.REPLACE_EXISTING);
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
     * 解析結果を受け取って即座にキャッシュへ書き出し、件数と進捗を数える。
     * 1ファイル分だけをヒープに載せ、書き出したら即破棄する。
     */
    private static final class BlockWriter implements CallEdgeExtractor.Sink {
        private final BufferedWriter cacheOut;
        private final BufferedWriter flowOut;
        private final CachePhaseResult result;
        private final Progress progress;
        /** 解析したファイルの内容ハッシュを求める（F行に書くため） */
        private final Function<SourceFile, String> hasher;
        /** 非nullなら、解析したファイルが宣言する型を「変わった型」に加える（パス2） */
        StaleTypes stale;
        /** 解析した理由。集計の内訳に使う（UNTOUCHED は「自分が変わった・新規」） */
        Reason countAs = Reason.UNTOUCHED;
        private long done;

        BlockWriter(BufferedWriter cacheOut, BufferedWriter flowOut, CachePhaseResult result,
                    Progress progress, Function<SourceFile, String> hasher) {
            this.cacheOut = cacheOut;
            this.flowOut = flowOut;
            this.result = result;
            this.progress = progress;
            this.hasher = hasher;
        }

        @Override
        public void accept(SourceFile file, FileAnalysis fa) throws IOException {
            fa.hash = hasher.apply(file);
            writeBlock(fa, cacheOut, flowOut);
            result.unresolved += fa.unresolvedCount();
            result.parsed++;
            countReason();
            if (stale != null) {
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
     * パス0。旧キャッシュ 2 つのヘッダを検証し、analysis 側の L 行（解析時の依存 jar）を読む。
     *
     * 次のどれかに当たれば null を返し、<b>両方とも使わずに全件再解析</b>する。
     * <pre>
     *   - analysis / dataflow のどちらかが無い
     *   - どちらかのヘッダの形式（版・ソースレベル・JDK・フェーズAの拡張の指紋）が違う
     *   - 2 つの世代の印が食い違う（前回の実行が dataflow を書く前に落ちた、片方だけ差し替えられた）
     * </pre>
     * 片方だけを使って「呼び出し階層は再利用、データフローだけ作り直し」とはしない。
     * ブロックごとにどちらが新しいかで結果が変わると、同じソースでも出力が揺れるため。
     */
    private List<LibraryFact> readOldLibraries() throws IOException {
        if (!Files.isRegularFile(config.cacheFile)) {
            return null;
        }
        if (!Files.isRegularFile(config.dataflowCacheFile)) {
            Log.info("[cache] データフローのキャッシュ（" + Config.DATAFLOW_CACHE_FILE_NAME
                    + "）が無いため、両方を作り直します");
            return null;
        }
        String flowGeneration;
        try (CacheReader flow = CacheReader.open(config.dataflowCacheFile)) {
            if (!flow.headerMatches(CacheFormat.dataflowHeaderFor(config.sourceLevel))) {
                Log.info("[cache] データフローのキャッシュの形式・ソースレベル・JDK が異なるため、両方を作り直します");
                return null;
            }
            flowGeneration = flow.generation();
        }
        try (CacheReader in = CacheReader.open(config.cacheFile)) {
            if (!in.headerMatches(CacheFormat.headerFor(config.sourceLevel, config.hintPluginFingerprint))) {
                // 形式が変わった場合のほか、source.level や実行 JDK が変わった場合もここで破棄する。
                // 言語バージョンやブートクラスパスが違えば同じソースでも解析結果が変わるため、
                // 更新時刻とサイズが一致していても再利用してはいけない
                Log.info("[cache] 形式・ソースレベル・JDK のいずれかが異なるため既存キャッシュを破棄します");
                return null;
            }
            if (flowGeneration.isEmpty() || !flowGeneration.equals(in.generation())) {
                Log.info("[cache] 2 つのキャッシュが同じ実行で書かれたものではないため、両方を作り直します");
                return null;
            }
            List<LibraryFact> libraries = new ArrayList<>();
            while (in.next() && in.is(CacheFormat.ROW_LIBRARY)) {
                LibraryFact l = LibraryFact.fromRow(in.columns());
                if (l != null) {
                    libraries.add(l);
                }
            }
            return libraries;
        }
    }

    /**
     * パス1b。dataflow 側のキャッシュにブロックが無い相対パスを、有効な集合から外す。
     *
     * ヘッダの世代が一致していれば 2 つは同じ実行で書かれているので、普通は全部そろっている。
     * それでも突き合わせるのは、片方だけを外から消された・切り詰められた場合に
     * 「analysis だけ再利用して dataflow が欠けたブロック」を作らないため。
     * 外したファイルは通常の再解析（パス2）に回るので、対でないブロックは残らない。
     */
    private void dropBlocksMissingFromDataflowCache(Set<String> valid, Set<String> libraryAffected)
            throws IOException {
        Set<String> present = new HashSet<>();
        try (CacheReader flow = CacheReader.open(config.dataflowCacheFile)) {   // ヘッダはパス0で検証済み
            while (flow.next()) {
                if (flow.is(CacheFormat.ROW_FILE)) {
                    present.add(flow.column(1));
                }
            }
        }
        int dropped = 0;
        for (Iterator<String> it = valid.iterator(); it.hasNext();) {
            if (!present.contains(it.next())) {
                it.remove();
                dropped++;
            }
        }
        // jar の追加で解決し直す予定のファイルも、dataflow 側が無ければ同じく通常の再解析に回す
        libraryAffected.retainAll(present);
        if (dropped > 0) {
            Log.info("[cache] データフローのキャッシュにブロックが無いファイルを解析し直します: " + dropped + " 件");
        }
    }

    /**
     * パス1。旧キャッシュを読み、有効なファイルの集合と「変わった型」を集める。
     *
     * @param librariesAddedOrChanged jar が追加・変更されたか。そのときは型解決に失敗していたブロック
     *                                （F行のエラー数が 0 でない、または U 行に BINDING_FAILED がある）を
     *                                有効から外し、libraryAffected に積む
     */
    private void scanOldCache(Map<String, SourceFile> live, Set<String> valid, StaleTypes stale,
                              boolean librariesAddedOrChanged, Set<String> libraryAffected)
            throws IOException {
        try (CacheReader in = CacheReader.open(config.cacheFile)) {   // ヘッダはパス0で検証済み
            boolean staleBlock = false;
            String currentRel = null;
            while (in.next()) {
                char rowType = in.rowType();
                if (rowType == CacheFormat.ROW_FILE) {
                    String[] f = in.columns();
                    staleBlock = !isValidBlock(f, live);
                    currentRel = staleBlock ? null : f[1];
                    if (currentRel != null) {
                        if (librariesAddedOrChanged && errorsOf(f) > 0) {
                            libraryAffected.add(currentRel);   // 宣言する型はパス2の解析時に「変わった型」へ入る
                            currentRel = null;
                        } else {
                            valid.add(currentRel);
                        }
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
        }
    }

    /**
     * パス3。旧キャッシュを1行ずつ読み、有効で、かつ変わった型に依存していないブロックだけを
     * 新キャッシュへ書き写す。依存しているブロックは書かず、dependents に相対パスを積む。
     *
     * 依存はブロック先頭のI行で判定する（F行の直後に置いてあるので、先読みは1行で済む）。
     * L 行はブロックの外（先頭）にあり、ここでは書き写さない（パス0で新しいものを書いている）。
     *
     * @param copied 書き写した相対パスを受け取る（パス3b が dataflow 側で同じブロックを書き写すため）
     * @return 書き写したブロックに含まれる、型解決できなかった呼び出しの件数
     */
    private long copyValidBlocks(Map<String, SourceFile> live, Set<String> valid, StaleTypes stale,
                                 List<String> dependents, List<String> libraryDependents,
                                 BufferedWriter cacheOut, Set<String> copied) throws IOException {
        long unresolved = 0L;
        try (CacheReader in = CacheReader.open(config.cacheFile)) {   // ヘッダはパス0で検証済み
            boolean keeping = false;
            String pendingFileRow = null;   // 依存の判定待ちのF行
            String pendingRel = null;
            while (in.next()) {
                String line = in.line();
                char rowType = in.rowType();
                if (rowType == CacheFormat.ROW_FILE) {
                    String[] f = in.columns();
                    keeping = false;
                    pendingFileRow = null;
                    if (f.length >= 2 && valid.contains(f[1])) {
                        pendingFileRow = refreshedFileRow(f, live.get(f[1]));
                        pendingRel = f[1];
                    }
                    continue;
                }
                if (pendingFileRow != null) {
                    // F行の直後。I行なら依存を判定し、無ければ依存なしとして書き写す
                    String deps = "";
                    boolean isDepsRow = (rowType == CacheFormat.ROW_DEPENDENCIES);
                    if (isDepsRow) {
                        deps = in.column(1);
                    }
                    Reason touched = stale.touches(deps);
                    if (touched == Reason.BY_SOURCE) {
                        dependents.add(pendingRel);
                        keeping = false;
                    } else if (touched == Reason.BY_LIBRARY) {
                        libraryDependents.add(pendingRel);
                        keeping = false;
                    } else {
                        keeping = true;
                        writeLine(cacheOut, pendingFileRow);
                        copied.add(pendingRel);
                    }
                    pendingFileRow = null;
                    if (isDepsRow) {
                        if (keeping) {
                            writeLine(cacheOut, line);
                        }
                        continue;
                    }
                }
                if (keeping) {
                    writeLine(cacheOut, line);
                    if (rowType == CacheFormat.ROW_UNRESOLVED) {
                        UnresolvedCallFact u = UnresolvedCallFact.fromRow(in.columns());
                        if (u != null && !u.hasUsableCandidate()) {
                            unresolved++;
                        }
                    }
                }
            }
        }
        return unresolved;
    }

    /**
     * パス3b。dataflow 側のキャッシュから、パス3 が analysis 側へ書き写したのと同じ相対パスの
     * ブロックだけを書き写す。F 行はパス3 と同じ規則で今の更新時刻と内容ハッシュに書き直す。
     *
     * パス3 と同じ順で書くので、2 つのキャッシュのブロックの並びは一致したままになる
     * （どちらも旧キャッシュの並びのまま、同じ部分集合を書き写すため）。
     * 対象はパス1b で「両方にあるブロック」に絞ってあるので、ここで欠けることはない。
     */
    private void copyDataflowBlocks(Map<String, SourceFile> live, Set<String> copied,
                                    BufferedWriter flowOut) throws IOException {
        if (copied.isEmpty()) {
            return;
        }
        try (CacheReader in = CacheReader.open(config.dataflowCacheFile)) {   // ヘッダはパス0で検証済み
            boolean keeping = false;
            while (in.next()) {
                if (in.is(CacheFormat.ROW_FILE)) {
                    String[] f = in.columns();
                    keeping = f.length >= 2 && copied.contains(f[1]);
                    if (keeping) {
                        writeLine(flowOut, refreshedFileRow(f, live.get(f[1])));
                    }
                } else if (keeping) {
                    writeLine(flowOut, in.line());
                }
            }
        }
    }

    private static void writeLine(BufferedWriter w, String line) throws IOException {
        w.write(line);
        w.newLine();
    }

    /**
     * 1ファイル分のブロックを 2 つのキャッシュへ書く。行の並びは {@link CacheFormat} のとおり。
     *
     * F 行は同じ内容を両方の先頭に書く（ブロックの区切りと、再利用の判定に使う）。
     * dataflow 側にはサイドカーの解析のための事実だけを置く（今は A 行）。
     * 片方だけに書くことはしない。ブロックが対でそろっていることが再利用の前提だから
     */
    private static void writeBlock(FileAnalysis fa, BufferedWriter w, BufferedWriter flowOut)
            throws IOException {
        String fileRow = CacheFormat.joinRow("F", fa.relativePath,
                String.valueOf(fa.lastModified), String.valueOf(fa.size), String.valueOf(fa.errors), fa.hash);
        writeLine(w, fileRow);
        writeLine(flowOut, fileRow);
        for (FieldAccessFact a : fa.fieldAccesses) {
            writeLine(flowOut, a.toRow());
        }
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
        for (FieldAssignFact j : fa.fieldAssigns) {
            writeLine(w, j.toRow());
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
