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
import jche.cache.MethodDeclFact;
import jche.cache.ReturnFact;
import jche.cache.TypeFact;
import jche.cache.UnresolvedCallFact;
import jche.config.Config;
import jche.config.ProjectLayout;
import jche.util.Log;
import jche.util.Progress;

/**
 * フェーズ1: 旧キャッシュを先頭から読みながら新キャッシュを書き出す、ストリーミングマージ。
 *
 * 1ファイル解析するたびに結果をキャッシュファイルへ直接書き出して破棄するため、
 * ランダムアクセスも全件保持も不要。ヒープ常駐は「ソースファイルの一覧＋更新時刻・サイズ」と
 * 「変わった型の集合」だけ。未解決呼び出しの件数も、この過程で同時に数える（溜め込まない）。
 *
 * 手順:
 * <pre>
 *   パス1 … 旧キャッシュを順に読み、更新時刻とサイズが一致するファイル（有効）を覚える。
 *           無効・消滅したファイルのブロックが宣言していた型（H行）を「変わった型」として集める。
 *   パス2 … 変更・追加されたファイルを解析して新キャッシュへ書く。
 *           そのファイルが宣言する型も「変わった型」に加える（改名・追加に備える）。
 *   パス3 … 旧キャッシュをもう一度読み、有効なブロックのうち、I行（依存する型）が
 *           「変わった型」に触れないものだけをそのまま書き写す。
 *           触れるものは、バインディング解決の結果が変わっている可能性があるので再解析に回す。
 *   パス4 … パス3で再解析に回したファイルを解析し、追記する。
 * </pre>
 *
 * 更新時刻とサイズだけで再利用を決めると、別のファイルの変更（オーバーロードの追加、
 * フィールドの改名、親型の変更など）でこのファイルの解決結果が変わっても気づけない。
 * 依存は1段で足りる。ファイルAの事実はAが参照した型にだけ依存し、Aを解析し直しても
 * Aが宣言する型（Aのソース）は変わらないので、Aに依存するファイルへは波及しない。
 */
public final class CacheUpdater {

    /** ソースファイルの実体情報。差分判定に使う（これだけはヒープに載せる） */
    private record FileStat(Path path, long mtime, long size) {
    }

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

        Map<String, FileStat> live = new LinkedHashMap<>();
        for (Path f : javaFiles) {
            live.put(layout.relativeOf(f),
                    new FileStat(f, Files.getLastModifiedTime(f).toMillis(), Files.size(f)));
        }

        Path parent = config.cacheFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmpCache = config.cacheFile.resolveSibling(config.cacheFile.getFileName() + ".tmp");

        Progress progress = new Progress("ソース解析", javaFiles.size());
        CallEdgeExtractor extractor = new CallEdgeExtractor(layout, config);

        try (BufferedWriter cacheOut = Files.newBufferedWriter(tmpCache, StandardCharsets.UTF_8)) {
            cacheOut.write(CacheFormat.headerFor(config.sourceLevel));
            cacheOut.newLine();

            // --- パス1: 有効なブロックと「変わった型」を集める ---
            Set<String> valid = new HashSet<>();
            StaleTypes stale = new StaleTypes();
            if (config.cacheEnabled) {
                scanOldCache(live, valid, stale);
            }

            // --- パス2: 変更・追加されたファイルを解析 ---
            int done = 0;
            for (Map.Entry<String, FileStat> en : live.entrySet()) {
                if (valid.contains(en.getKey())) {
                    continue;
                }
                analyzeAndWrite(extractor, en.getKey(), en.getValue(), cacheOut, result, stale);
                done++;
                progress.step(done);
            }

            // --- パス3: 変わった型に依存していない有効ブロックを書き写す ---
            List<String> dependents = new ArrayList<>();
            if (config.cacheEnabled && !valid.isEmpty()) {
                result.unresolved += copyValidBlocks(valid, stale, dependents, cacheOut);
                result.reused = valid.size() - dependents.size();
                done += result.reused;
                progress.step(done);
            }

            // --- パス4: 依存で無効になったファイルを解析し直す ---
            for (String rel : dependents) {
                FileStat st = live.get(rel);
                if (st == null) {
                    continue;
                }
                analyzeAndWrite(extractor, rel, st, cacheOut, result, null);
                result.dependents++;
                done++;
                progress.step(done);
            }
        }
        progress.finish();

        Files.move(tmpCache, config.cacheFile, StandardCopyOption.REPLACE_EXISTING);
        return result;
    }

    /**
     * 1ファイルを解析して書き出す。1ファイル分だけをヒープに載せ、書き出したら即破棄する。
     *
     * @param stale 非nullなら、このファイルが宣言する型を「変わった型」に加える
     */
    private void analyzeAndWrite(CallEdgeExtractor extractor, String rel, FileStat st,
                                 BufferedWriter cacheOut, CachePhaseResult result,
                                 StaleTypes stale) {
        try {
            FileAnalysis fa = extractor.analyze(st.path(), rel, st.mtime(), st.size());
            writeBlock(fa, cacheOut);
            result.unresolved += fa.unresolvedCount();
            result.parsed++;
            if (stale != null) {
                for (TypeFact t : fa.types) {
                    stale.add(t.typeFqn(), t.pkg());
                }
            }
        } catch (Exception e) {
            result.failed++;
            Log.warn("解析失敗（スキップ）: " + rel + " (" + e.getMessage() + ")");
        }
    }

    /** 変更・削除されたファイルが宣言していた型と、そのパッケージ */
    private static final class StaleTypes {
        private final Set<String> types = new HashSet<>();
        private final Set<String> packages = new HashSet<>();

        void add(String typeFqn, String pkg) {
            types.add(typeFqn);
            packages.add(pkg == null ? "" : pkg);
        }

        /**
         * I行（依存する型のカンマ区切り）が、変わった型に触れているか。
         * "pkg.*"（オンデマンド import）は、そのパッケージの型が1つでも変わっていれば触れているとみなす
         */
        boolean touches(String depsCsv) {
            if (depsCsv.isEmpty() || types.isEmpty()) {
                return false;
            }
            for (String d : depsCsv.split(",")) {
                if (d.isEmpty()) {
                    continue;
                }
                if (d.endsWith(".*")) {
                    String p = d.substring(0, d.length() - 2);
                    if (types.contains(p) || packages.contains(p)) {
                        return true;
                    }
                } else if (types.contains(d)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** ブロックのF行が、今のソースと一致しているか（更新時刻とサイズの両方） */
    private static boolean isValidBlock(String[] f, Map<String, FileStat> live) {
        if (f.length < 4) {
            return false;
        }
        FileStat st = live.get(f[1]);
        try {
            return st != null && st.mtime() == Long.parseLong(f[2]) && st.size() == Long.parseLong(f[3]);
        } catch (NumberFormatException ignore) {
            return false;   // 壊れたF行 -> このブロックは破棄し、後で再解析される
        }
    }

    /**
     * パス1。旧キャッシュを読み、有効なファイルの集合と「変わった型」を集める。
     * 形式またはソースレベルが違えば何も有効にしない（全件再解析）。
     */
    private void scanOldCache(Map<String, FileStat> live, Set<String> valid, StaleTypes stale)
            throws IOException {
        if (!Files.isRegularFile(config.cacheFile)) {
            return;
        }
        try (BufferedReader in = Files.newBufferedReader(config.cacheFile, StandardCharsets.UTF_8)) {
            String first = in.readLine();
            if (first == null || !CacheFormat.headerFor(config.sourceLevel).equals(first.trim())) {
                // 形式が変わった場合のほか、source.level が変わった場合もここで破棄する。
                // 言語バージョンが違えば同じソースでも解析結果が変わるため、
                // 更新時刻とサイズが一致していても再利用してはいけない
                Log.info("[cache] 形式またはソースレベルが異なるため既存キャッシュを破棄します");
                return;
            }
            boolean staleBlock = false;
            String line;
            while ((line = in.readLine()) != null) {
                char rowType = CacheFormat.rowTypeOf(line);
                if (rowType == CacheFormat.ROW_FILE) {
                    String[] f = CacheFormat.columnsOf(line);
                    staleBlock = !isValidBlock(f, live);
                    if (!staleBlock) {
                        valid.add(f[1]);
                    }
                } else if (staleBlock && rowType == CacheFormat.ROW_TYPE) {
                    TypeFact t = TypeFact.fromRow(CacheFormat.columnsOf(line));
                    if (t != null) {
                        stale.add(t.typeFqn(), t.pkg());
                    }
                }
            }
        }
    }

    /**
     * パス3。旧キャッシュを1行ずつ読み、有効で、かつ変わった型に依存していないブロックだけを
     * 新キャッシュへ書き写す。依存しているブロックは書かず、dependents に相対パスを積む。
     *
     * 依存はブロック先頭のI行で判定する（F行の直後に置いてあるので、先読みは1行で済む）。
     *
     * @return 書き写したブロックに含まれる、型解決できなかった呼び出しの件数
     */
    private long copyValidBlocks(Set<String> valid, StaleTypes stale, List<String> dependents,
                                 BufferedWriter cacheOut) throws IOException {
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
                    if (stale.touches(deps)) {
                        dependents.add(pendingRel);
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
                String.valueOf(fa.lastModified), String.valueOf(fa.size)));
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
