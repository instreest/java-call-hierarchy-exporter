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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import jche.cache.CacheFormat;
import jche.cache.CallEdgeFact;
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
import jche.util.Log;
import jche.util.Progress;

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
 *           無効・消滅したファイルのブロックが宣言していた型（H行）を「変わった型」として集める。
 *           jar が追加・変更されていれば、型解決に失敗していたファイル（F行のエラー数、
 *           U行の BINDING_FAILED）も有効から外す。追加された jar で解決できるようになりうるため。
 *   パス2 … 変更・追加されたファイルを解析して新キャッシュへ書く。
 *           そのファイルが宣言する型も「変わった型」に加える（改名・追加に備える）。
 *   パス3 … 旧キャッシュをもう一度読み、有効なブロックのうち、I行（依存する型）が
 *           「変わった型」にも「変わったパッケージ」にも触れないものだけをそのまま書き写す。
 *           触れるものは、バインディング解決の結果が変わっている可能性があるので再解析に回す。
 *   パス4 … パス3で再解析に回したファイルを解析し、追記する。
 * </pre>
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
            cacheOut.write(CacheFormat.headerFor(config.sourceLevel));
            cacheOut.newLine();
            for (LibraryFact l : libraries.current) {
                writeLine(cacheOut, l.toRow());
            }
            BlockWriter writer = new BlockWriter(cacheOut, result, progress);

            // --- パス1: 有効なブロックと「変わった型」を集める ---
            Set<String> valid = new HashSet<>();
            StaleTypes stale = new StaleTypes(libraries.changedPackages);
            Set<String> libraryAffected = new HashSet<>();   // 型解決に失敗していて、jar の追加で変わりうるファイル
            if (oldCacheUsable) {
                scanOldCache(live, valid, stale, libraries.anyAddedOrChanged(), libraryAffected);
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
            writer.countAs = BlockWriter.BY_LIBRARY;
            analyzeInBatches(extractor, unresolvedBefore, writer);

            // --- パス3: 変わった型・パッケージに依存していない有効ブロックを書き写す ---
            List<String> dependents = new ArrayList<>();
            List<String> libraryDependents = new ArrayList<>();
            if (oldCacheUsable && !valid.isEmpty()) {
                result.unresolved += copyValidBlocks(valid, stale, dependents, libraryDependents, cacheOut);
                result.reused = valid.size() - dependents.size() - libraryDependents.size();
                writer.skipped(result.reused);
            }

            // --- パス4: 依存で無効になったファイルを解析し直す ---
            writer.stale = null;
            writer.countAs = BlockWriter.BY_SOURCE;
            analyzeInBatches(extractor, filesOf(dependents, live), writer);
            writer.countAs = BlockWriter.BY_LIBRARY;
            analyzeInBatches(extractor, filesOf(libraryDependents, live), writer);
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
            int to = Math.min(files.size(), from + CallEdgeExtractor.BATCH_SIZE);
            extractor.analyzeBatch(files.subList(from, to), writer);
        }
    }

    /**
     * 解析結果を受け取って即座にキャッシュへ書き出し、件数と進捗を数える。
     * 1ファイル分だけをヒープに載せ、書き出したら即破棄する。
     */
    private static final class BlockWriter implements CallEdgeExtractor.Sink {
        /** 自分が変わった（または新規）ファイルとして数える */
        static final int BY_SELF = 0;
        /** 依存する型（ソース）が変わったための再解析として数える */
        static final int BY_SOURCE = 1;
        /** 依存 jar が変わったための再解析として数える */
        static final int BY_LIBRARY = 2;

        private final BufferedWriter cacheOut;
        private final CachePhaseResult result;
        private final Progress progress;
        /** 非nullなら、解析したファイルが宣言する型を「変わった型」に加える（パス2） */
        StaleTypes stale;
        /** 解析した理由（BY_SELF / BY_SOURCE / BY_LIBRARY）。集計の内訳に使う */
        int countAs = BY_SELF;
        private long done;

        BlockWriter(BufferedWriter cacheOut, CachePhaseResult result, Progress progress) {
            this.cacheOut = cacheOut;
            this.result = result;
            this.progress = progress;
        }

        @Override
        public void accept(SourceFile file, FileAnalysis fa) throws IOException {
            writeBlock(fa, cacheOut);
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
            if (countAs == BY_SOURCE) {
                result.dependents++;
            } else if (countAs == BY_LIBRARY) {
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
        static final int UNTOUCHED = 0;
        static final int BY_SOURCE = 1;
        static final int BY_LIBRARY = 2;

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
         * @return UNTOUCHED / BY_SOURCE / BY_LIBRARY
         */
        int touches(String depsCsv) {
            if (depsCsv.isEmpty() || (types.isEmpty() && libraryPackages.isEmpty())) {
                return UNTOUCHED;
            }
            boolean library = false;
            for (String d : depsCsv.split(",")) {
                if (d.isEmpty()) {
                    continue;
                }
                if (d.endsWith(".*")) {
                    String p = d.substring(0, d.length() - 2);
                    if (types.contains(p) || packages.contains(p)) {
                        return BY_SOURCE;
                    }
                    library |= libraryPackages.contains(p);
                } else if (types.contains(d)) {
                    return BY_SOURCE;
                } else {
                    library |= inLibraryPackage(d);
                }
            }
            return library ? BY_LIBRARY : UNTOUCHED;
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

    /** ブロックのF行が、今のソースと一致しているか（更新時刻とサイズの両方） */
    private static boolean isValidBlock(String[] f, Map<String, SourceFile> live) {
        if (f.length < 4) {
            return false;
        }
        SourceFile st = live.get(f[1]);
        try {
            return st != null && st.mtime() == Long.parseLong(f[2]) && st.size() == Long.parseLong(f[3]);
        } catch (NumberFormatException ignore) {
            return false;   // 壊れたF行 -> このブロックは破棄し、後で再解析される
        }
    }

    /**
     * パス0。旧キャッシュのヘッダを検証し、続く L 行（解析時の依存 jar）を読む。
     * 形式・ソースレベル・JDK のどれかが違えば null（旧キャッシュは使わず全件再解析）。
     */
    private List<LibraryFact> readOldLibraries() throws IOException {
        if (!Files.isRegularFile(config.cacheFile)) {
            return null;
        }
        try (BufferedReader in = Files.newBufferedReader(config.cacheFile, StandardCharsets.UTF_8)) {
            String first = in.readLine();
            if (first == null || !CacheFormat.headerFor(config.sourceLevel).equals(first.trim())) {
                // 形式が変わった場合のほか、source.level や実行 JDK が変わった場合もここで破棄する。
                // 言語バージョンやブートクラスパスが違えば同じソースでも解析結果が変わるため、
                // 更新時刻とサイズが一致していても再利用してはいけない
                Log.info("[cache] 形式・ソースレベル・JDK のいずれかが異なるため既存キャッシュを破棄します");
                return null;
            }
            List<LibraryFact> libraries = new ArrayList<>();
            String line;
            while ((line = in.readLine()) != null && CacheFormat.rowTypeOf(line) == CacheFormat.ROW_LIBRARY) {
                LibraryFact l = LibraryFact.fromRow(CacheFormat.columnsOf(line));
                if (l != null) {
                    libraries.add(l);
                }
            }
            return libraries;
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
        try (BufferedReader in = Files.newBufferedReader(config.cacheFile, StandardCharsets.UTF_8)) {
            in.readLine();   // ヘッダ行（パス0で検証済み）
            boolean staleBlock = false;
            String currentRel = null;
            String line;
            while ((line = in.readLine()) != null) {
                char rowType = CacheFormat.rowTypeOf(line);
                if (rowType == CacheFormat.ROW_FILE) {
                    String[] f = CacheFormat.columnsOf(line);
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
                    TypeFact t = TypeFact.fromRow(CacheFormat.columnsOf(line));
                    if (t != null) {
                        stale.add(t.typeFqn(), t.pkg());
                    }
                } else if (currentRel != null && librariesAddedOrChanged
                        && rowType == CacheFormat.ROW_UNRESOLVED
                        && UnresolvedCallFact.BINDING_FAILED.equals(
                                CacheFormat.columnAt(CacheFormat.columnsOf(line), 7))) {
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
     * @return 書き写したブロックに含まれる、型解決できなかった呼び出しの件数
     */
    private long copyValidBlocks(Set<String> valid, StaleTypes stale, List<String> dependents,
                                 List<String> libraryDependents, BufferedWriter cacheOut) throws IOException {
        long unresolved = 0L;
        try (BufferedReader in = Files.newBufferedReader(config.cacheFile, StandardCharsets.UTF_8)) {
            in.readLine();   // バージョン行（パス1で検証済み）
            boolean keeping = false;
            String pendingFileRow = null;   // 依存の判定待ちのF行
            String pendingRel = null;
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                char rowType = CacheFormat.rowTypeOf(line);
                if (rowType == CacheFormat.ROW_FILE) {
                    String[] f = CacheFormat.columnsOf(line);
                    keeping = false;
                    pendingFileRow = null;
                    if (f.length >= 2 && valid.contains(f[1])) {
                        pendingFileRow = line;
                        pendingRel = f[1];
                    }
                    continue;
                }
                if (pendingFileRow != null) {
                    // F行の直後。I行なら依存を判定し、無ければ依存なしとして書き写す
                    String deps = "";
                    boolean isDepsRow = (rowType == CacheFormat.ROW_DEPENDENCIES);
                    if (isDepsRow) {
                        deps = CacheFormat.columnAt(CacheFormat.columnsOf(line), 1);
                    }
                    int touched = stale.touches(deps);
                    if (touched == StaleTypes.BY_SOURCE) {
                        dependents.add(pendingRel);
                        keeping = false;
                    } else if (touched == StaleTypes.BY_LIBRARY) {
                        libraryDependents.add(pendingRel);
                        keeping = false;
                    } else {
                        keeping = true;
                        writeLine(cacheOut, pendingFileRow);
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
                        UnresolvedCallFact u = UnresolvedCallFact.fromRow(CacheFormat.columnsOf(line));
                        if (u != null && !u.hasUsableCandidate()) {
                            unresolved++;
                        }
                    }
                }
            }
        }
        return unresolved;
    }

    private static void writeLine(BufferedWriter w, String line) throws IOException {
        w.write(line);
        w.newLine();
    }

    /** 1ファイル分のブロックを書く。行の並びは {@link CacheFormat} のとおり */
    private static void writeBlock(FileAnalysis fa, BufferedWriter w) throws IOException {
        writeLine(w, CacheFormat.joinRow("F", fa.relativePath,
                String.valueOf(fa.lastModified), String.valueOf(fa.size), String.valueOf(fa.errors)));
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
