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
import jche.cache.ConstantFact;
import jche.cache.FieldAccessFact;
import jche.cache.FieldAssignFact;
import jche.cache.FieldDeclFact;
import jche.cache.FileAnalysis;
import jche.cache.FunctionalImplFact;
import jche.cache.HintFact;
import jche.cache.LibraryFact;
import jche.cache.MethodDeclFact;
import jche.cache.OverrideFact;
import jche.cache.ReturnFact;
import jche.cache.TypeFact;
import jche.cache.UnresolvedCallFact;
import jche.cache.CallSiteValues;
import jche.cache.ValueNode;
import jche.analysis.CallEdgeExtractor.SourceFile;
import jche.config.Config;
import jche.config.ProjectLayout;
import jche.util.FileHash;
import jche.util.Log;
import jche.util.Progress;
import jche.util.RunControl;
import jche.util.Messages;
import jche.util.Warnings;

/**
 * フェーズ1: 旧キャッシュを先頭から読みながら新キャッシュを書き出す、ストリーミングマージ。
 *
 * 1ファイル分の結果が出来るたびにキャッシュファイルへ直接書き出して破棄するため、
 * ランダムアクセスも全件保持も不要。ヒープ常駐は「ソースファイルの一覧＋サイズ」と
 * 「変わった型の集合」だけ。未解決呼び出しの件数も、この過程で同時に数える（溜め込まない）。
 * パース自体は {@link CallEdgeExtractor#BATCH_SIZE} 件ずつまとめて行う（1ファイルずつでは
 * 規模に比例して遅くなるため）。
 *
 * <h2>2 つのキャッシュを対で書く</h2>
 * 呼び出し階層のためのキャッシュ（{@code analysis-cache.tsv}）と、データフローのための
 * キャッシュ（{@code dataflow-cache.tsv}）を同じ解析結果から一緒に書く（{@link jche.cache.CacheFormat}）。
 * <b>片方だけが新しい状態は作らない。</b>両方のヘッダに同じ世代の印を書き、
 * 次のどれかに当たれば両方とも捨てて全件解析し直す。
 * <pre>
 *   - どちらかが無い / ヘッダの形式（版・ソースレベル・文字コード・JDK・拡張の指紋）が違う
 *   - 世代の印が食い違う（前回の実行が 2 つ目を書く前に落ちた、片方だけ差し替えられた）
 *   - 最終行の印（Z 行）のブロック数が食い違う（どちらかが途中で切れている）
 * </pre>
 * そのうえで、<b>再利用するブロックは「両方のキャッシュにあるもの」だけ</b>にする（パス1b）。
 * 片方にしか無いブロックは再解析に回すので、ブロック単位のズレも残らない。
 * 中断した実行からの引き継ぎも同じで、両方の一時ファイルにそろっているブロックだけを引き継ぐ。
 * 書き出しはどちらも一時ファイルに作ってから analysis → dataflow の順で差し替える
 * （間で落ちれば世代が食い違い、次回は両方とも捨てられる）。
 *
 * 手順:
 * <pre>
 *   パス0 … 旧キャッシュ 2 つのヘッダを検証し（形式・世代・ブロック数）、analysis 側の
 *           L 行（解析時の依存 jar）を読んで今回のクラスパスと突き合わせる。
 *           追加・変更・削除された jar のパッケージを「変わったパッケージ」として集める。
 *   パス1 … 旧キャッシュを順に読み、サイズと内容ハッシュが一致するファイル（有効）を覚える。
 *           無効・消滅したファイルのブロックが宣言していた型（H行）を「変わった型」として集める。
 *           jar が追加・変更されていれば、型解決に失敗していたファイル（F行のエラー数、
 *           U行の BINDING_FAILED）も有効から外す。追加された jar で解決できるようになりうるため。
 *   パス1b… 旧キャッシュ（dataflow）の F 行だけを読み、ブロックがある相対パスを集める。
 *           有効なファイルのうちここに無いものは、dataflow 側が欠けているので再解析に回す。
 *   パス2 … 変更・追加されたファイルを解析して新キャッシュ 2 つへ書く。
 *           そのファイルが宣言する型も「変わった型」に加える（改名・追加に備える）。
 *   パス3 … 旧キャッシュをもう一度読み、有効なブロックのうち、I行（依存する型）が
 *           「変わった型」または「変わったパッケージ」に触れるものを再解析に回す。
 *           触れるものは、バインディング解決の結果が変わっている可能性があるため。
 *   パス4 … パス3で再解析に回したファイルを解析し、追記する。そのファイルが宣言する定数
 *           （K行）の値が変わっていたら、宣言する型を「変わった型」に加えてパス3へ戻る
 *           （下記「定数の連鎖」）。
 *   パス5 … 最後まで有効だったブロックを、F 行ごとそのまま書き写す。
 *   パス5b… パス5 が書き写したのと同じ相対パスのブロックを、dataflow 側からも書き写す。
 * </pre>
 * どのパスも 2 つのキャッシュへ同じ順で書くので、ブロックの並びと数は常に一致する。
 *
 * <h2>同一性（何をもって「同じファイル」とみなすか）</h2>
 * <b>相対パス・サイズ・内容ハッシュ</b>の3つで見る。更新時刻は記録も参照もしない。
 * 更新時刻は中身と関係なく変わる（git のチェックアウト、コピー、CI のたびに作り直される
 * ワークスペース）ので、当てにすると「中身は同じなのにキャッシュを捨てる」が起きる。
 * 逆に、バージョン管理が更新時刻を復元する設定だと「中身が違うのに再利用する」も起きうる。
 * どちらも内容ハッシュなら起きない。同じ考え方を、依存 jar（L行、{@link LibraryDiff}）と
 * ソース一覧の指紋（T行、{@link #fingerprintOf}）にも通している。
 *
 * <p>そのファイル自身の同一性が一致しても、それだけでは再利用できない。別のファイルの変更
 * （オーバーロードの追加、フィールドの改名、親型の変更など）でこのファイルの解決結果が
 * 変わりうるため、下の依存（I行）の突き合わせが要る。
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
     * 相対パス -> 今のソースの内容ハッシュ。同じファイルを 2 度読まないように覚える
     * （パス1 の再利用の判定、T 行のソース一覧の指紋、F 行に書く値で使う）
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
        Log.info(Messages.format("analysis.javaFileCount", javaFiles.size()));

        // 相対パス -> ソースファイルの実体情報（これだけはヒープに載せる）
        Map<String, SourceFile> live = new LinkedHashMap<>();
        for (Path f : javaFiles) {
            String rel = layout.relativeOf(f);
            live.put(rel, new SourceFile(f, rel, Files.size(f)));
        }

        Path parent = config.cacheFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmpCache = config.cacheFile.resolveSibling(config.cacheFile.getFileName() + ".tmp");
        Path tmpFlowCache = config.dataflowCacheFile
                .resolveSibling(config.dataflowCacheFile.getFileName() + ".tmp");
        // 前回が途中で終わっていれば、その一時ファイルを退避して「パースの使い回し」に使う。
        // 2 つとも退避する（引き継ぐのは両方にそろっているブロックだけ）
        Path partialCache = takeOverPartial(config.cacheFile, tmpCache);
        Path partialFlowCache = takeOverPartial(config.dataflowCacheFile, tmpFlowCache);

        Progress progress = new Progress(Messages.get("analysis.progress.parse"), javaFiles.size(),
                CallEdgeExtractor.BATCH_SIZE);
        CallEdgeExtractor extractor = new CallEdgeExtractor(layout, config);

        // --- パス0: 旧キャッシュの依存 jar（L行）と今回のクラスパスを突き合わせる ---
        List<LibraryFact> oldLibraries = config.cacheEnabled ? readOldLibraries() : null;
        boolean oldCacheUsable = (oldLibraries != null);
        LibraryDiff libraries = LibraryDiff.compute(layout.classpathArray(),
                oldCacheUsable ? oldLibraries : List.of(), layout.projectRoot);
        if (oldCacheUsable && libraries.any()) {
            Log.info(Messages.format("analysis.libraryChanged", libraries));
        }

        // 2 つのキャッシュに書く同じ世代の印。次回の実行で「対で書かれたか」を判定する
        String generation = CacheFormat.newGeneration();
        try (BufferedWriter cacheOut = Files.newBufferedWriter(tmpCache, StandardCharsets.UTF_8);
             BufferedWriter flowOut = Files.newBufferedWriter(tmpFlowCache, StandardCharsets.UTF_8)) {
            writeLine(cacheOut, CacheFormat.withGeneration(
                    CacheFormat.headerFor(config.sourceLevel, config.sourceEncoding), generation));
            writeLine(flowOut, CacheFormat.withGeneration(CacheFormat.dataflowHeaderFor(
                    config.sourceLevel, config.sourceEncoding), generation));
            for (LibraryFact l : libraries.current) {
                writeLine(cacheOut, l.toRow());
            }
            BlockWriter writer = new BlockWriter(cacheOut, flowOut, result, progress, this::hashOf,
                    oldConstants);

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
            } else if (oldCacheUsable
                    && !dropBlocksMissingFromDataflowCache(valid, libraryAffected)) {
                // --- パス1b: dataflow 側が読めない。中途半端に再利用すると値が欠けるので丸ごと捨てる ---
                valid.clear();
                libraryAffected.clear();
                oldConstants.clear();
            }
            // ソース一覧の指紋（T行）。L 行の直後という位置は形式で決まっている。
            // ハッシュはパス1 と共通で、1ファイル 1 回しか読まない（hashOf）
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
            if (partialCache != null && partialFlowCache != null) {
                changed = salvageFrom(partialCache, partialFlowCache, changed, live,
                        cacheOut, flowOut, stale, result);
                writer.skipped(result.salvaged);
            }
            deletePartial(partialCache);
            deletePartial(partialFlowCache);
            analyzeInBatches(extractor, changed, writer);
            writer.countAs = Reason.BY_LIBRARY;
            analyzeInBatches(extractor, unresolvedBefore, writer);

            // --- パス3・パス4: 依存で無効になったファイルを解析し直す（定数が絡むと連鎖するので不動点まで） ---
            writer.cascade = Cascade.WHEN_CONSTANTS_CHANGED;
            if (oldCacheUsable && !valid.isEmpty()) {
                reanalyzeDependents(extractor, writer, live, valid, stale);

                // --- パス5: 最後まで有効だったブロックを書き写す ---
                Set<String> copied = copyValidBlocks(valid, cacheOut, result);
                // --- パス5b: 同じブロックを dataflow 側からも書き写す ---
                copyDataflowBlocks(config.dataflowCacheFile, copied, flowOut);
                writer.skipped(result.reused);
            }

            // 最終行。次回、ここまで書き終えたキャッシュかどうかを見分けるための印。
            // 2 つのキャッシュのブロック数は同じなので同じ行を書き、次回はこの数も突き合わせる
            String trailer = CacheFormat.trailerFor(result.parsed + result.reused + result.salvaged);
            writeLine(cacheOut, trailer);
            writeLine(flowOut, trailer);
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
            RunControl.progress(Messages.get("analysis.progress.parse"), from, files.size());
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
        private final BufferedWriter flowOut;
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

        BlockWriter(BufferedWriter cacheOut, BufferedWriter flowOut, CachePhaseResult result, Progress progress,
                    Function<SourceFile, String> hasher, Map<String, String> oldConstants) {
            this.cacheOut = cacheOut;
            this.flowOut = flowOut;
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
            writeBlock(fa, cacheOut, flowOut);
            result.unresolved += fa.unresolvedCount();
            result.parsed++;
            result.countErrors(file.relativePath(), fa.errors, fa.syntaxErrors);
            if (fa.syntaxErrors > 0) {
                // 本体を読めていないので、このファイルの呼び出しは出力に出ない。黙って落とさない
                Warnings.warn(Warnings.Topic.BUILD,
                        Messages.format("analysis.syntaxError", file.relativePath(), fa.syntaxErrors));
            }
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
            Warnings.warn(Warnings.Topic.INCOMPLETE,
                    Messages.format("analysis.fileFailed", file.relativePath(), error.getMessage()));
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

    /** F行のエラー数（無ければ 0） */
    private static int errorsOf(String[] f) {
        try {
            return Integer.parseInt(CacheFormat.columnAt(f, 3));
        } catch (NumberFormatException ignore) {
            return 0;
        }
    }

    /**
     * ブロックのF行が、今のソースと一致しているか。サイズと内容ハッシュの両方で見る。
     * 更新時刻は見ない（クラスの説明「同一性」のとおり）。
     *
     * <p>サイズを先に見るのは、違えば中身も違うと分かり、ファイルを読まずに済むため。
     * ハッシュが記録されていない F 行（読み取りに失敗したなど）は不一致とみなす。
     */
    private boolean isValidBlock(String[] f, Map<String, SourceFile> live) {
        if (f.length < 3) {
            return false;
        }
        SourceFile st = live.get(f[1]);
        if (st == null) {
            return false;
        }
        try {
            if (st.size() != Long.parseLong(f[2])) {
                return false;   // サイズ違いは中身も違う。ハッシュを取るまでもない
            }
        } catch (NumberFormatException ignore) {
            return false;   // 壊れたF行 -> このブロックは破棄し、後で再解析される
        }
        String recorded = CacheFormat.columnAt(f, 4);
        return !recorded.isEmpty() && recorded.equals(hashOf(st));
    }

    /** 今のソースの内容ハッシュ（計算は 1 ファイル 1 回）。読めなければ空文字 */
    private String hashOf(SourceFile file) {
        String h = hashes.get(file.relativePath());
        if (h == null) {
            try {
                h = FileHash.of(file.path());
            } catch (IOException e) {
                Log.warn(Messages.format("analysis.hashFailed", file.relativePath(), e));
                h = "";
            }
            hashes.put(file.relativePath(), h);
        }
        return h;
    }

    /**
     * パス0。旧キャッシュ 2 つのヘッダを検証し、analysis 側の L 行（解析時の依存 jar）を読む。
     *
     * 次のどれかに当たれば null（<b>両方とも</b>使わずに全件再解析）。
     * <pre>
     *   - analysis / dataflow のどちらかが無い
     *   - どちらかのヘッダの形式（版・ソースレベル・文字コード・JDK・拡張の指紋）が違う
     *   - 2 つの世代の印が食い違う（前回が dataflow を書く前に落ちた、片方だけ差し替えられた）
     *   - 2 つの最終行（Z 行）のブロック数が食い違う、または dataflow 側に最終行が無い
     * </pre>
     * 片方だけを使って「呼び出し階層は再利用、データフローだけ作り直し」とはしない。
     * ブロックごとにどちらが新しいかで結果が変わると、同じソースでも出力が揺れるため。
     *
     * <p>ここで読むのは、analysis 側のヘッダと L 行・T 行（最初の F 行で打ち切る）、
     * dataflow 側のヘッダ、それに 2 つの最終行（{@link #trailerOf} が末尾だけを読む）。
     * <b>どちらのキャッシュも全体を走らない。</b>毎回の実行で通る経路なので、
     * ここでフルスキャンすると差分更新の利点をそのぶん削ってしまう
     */
    private List<LibraryFact> readOldLibraries() {
        if (!Files.isRegularFile(config.cacheFile)) {
            return null;
        }
        if (!Files.isRegularFile(config.dataflowCacheFile)) {
            Log.info(Messages.format("analysis.cache.noDataflow", Config.DATAFLOW_CACHE_FILE_NAME));
            return null;
        }
        try {
            // analysis 側を先に読む。ヘッダ（世代の印も）と L 行・T 行が 1 回の走査で取れるので、
            // 対の判定のために開き直さずに済む
            CacheHead head = headOf(config.cacheFile);
            if (head == null) {
                // 形式が変わった場合のほか、source.level・ソースの文字コード・実行 JDK が
                // 変わった場合もここで破棄する。言語バージョン・文字コード・ブートクラスパスが違えば
                // 同じソースでも解析結果が変わるため、F 行の同一性が一致していても再利用してはいけない
                Log.info(Messages.get("analysis.cache.incompatible"));
                return null;
            }
            if (!dataflowCachePairsWith(head.generation())) {
                return null;
            }
            return head.libraries();
        } catch (IOException | RuntimeException e) {
            // 読めない・文字が壊れているキャッシュ。全件解析し直せば済むので、解析ごと失敗させない
            Log.warn(Messages.format("analysis.cache.unreadable", e));
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
    private Path takeOverPartial(Path cacheFile, Path tmpCache) {
        Path partial = cacheFile.resolveSibling(cacheFile.getFileName() + ".partial");
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
            Log.warn(Messages.format("analysis.resume.cannotStash", e));
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
     *       ブロックは他のファイルの内容にも依存するため</li>
     *   <li>依存 jar が当時から変わっていない。ブロックは当時のクラスパスでのバインディング解決の
     *       結果なので、jar が変われば同じソースでも呼び出し先や親型が変わりうる</li>
     *   <li>ファイルのサイズと内容ハッシュが一致する（{@link #isValidBlock}）</li>
     *   <li>ブロックが最後まで書けている。次の F 行か、最後まで書き終えた印（Z 行）に
     *       出会ったブロックだけを使う。最後の F 行から始まるブロックは、書き込みバッファの
     *       途中で切れている可能性があるので使わない</li>
     * </ul>
     *
     * <p>2 つのキャッシュに分かれているので、<b>両方の退避ファイルにそろっているブロックだけ</b>を
     * 引き継ぐ。片方にしか無いものを引き継ぐと、対になっていないブロックが生まれる。
     * dataflow 側にブロックが無いファイルは、単に引き継がずに解析し直すだけ。
     *
     * @return まだ解析が必要なファイル（引き継げたぶんを除いたもの）
     */
    private List<SourceFile> salvageFrom(Path partial, Path partialFlow, List<SourceFile> toAnalyze,
                                         Map<String, SourceFile> live, BufferedWriter cacheOut,
                                         BufferedWriter flowOut, StaleTypes stale,
                                         CachePhaseResult result) throws IOException {
        Set<String> taken = new HashSet<>();
        if (isPartialUsable(partial, live)) {
            // dataflow 側の退避ファイルにブロックがあるファイルだけを引き継ぎの候補にする
            Set<String> wanted = blockPathsOf(partialFlow);
            wanted.retainAll(pathsOf(toAnalyze));
            if (!wanted.isEmpty()) {
                copySalvageable(partial, wanted, live, cacheOut, stale, result, taken);
                // analysis 側に引き継いだのと同じブロックを dataflow 側にも書き写す
                copyDataflowBlocks(partialFlow, taken, flowOut);
            }
        }
        if (taken.isEmpty()) {
            return toAnalyze;
        }
        Log.info(Messages.format("analysis.resume.taken", taken.size()));
        List<SourceFile> rest = new ArrayList<>();
        for (SourceFile f : toAnalyze) {
            if (!taken.contains(f.relativePath())) {
                rest.add(f);
            }
        }
        return rest;
    }

    private static Set<String> pathsOf(List<SourceFile> files) {
        Set<String> paths = new HashSet<>(files.size() * 2);
        for (SourceFile f : files) {
            paths.add(f.relativePath());
        }
        return paths;
    }

    /**
     * キャッシュに含まれるブロック（F行）の相対パス。読めない行があればそこまでで打ち切る。
     *
     * 最後の F 行から始まるブロックは書き込みバッファの途中で切れている可能性があるので含めない
     * （引き継ぎの候補を絞るのに使うので、{@link #copySalvageable} の判定と揃える）。
     */
    private static Set<String> blockPathsOf(Path cacheFile) {
        Set<String> paths = new HashSet<>();
        String pending = null;
        try (CacheReader in = CacheReader.open(cacheFile)) {
            while (in.next()) {
                char rowType = in.rowType();
                if (rowType == CacheFormat.ROW_FILE) {
                    if (pending != null) {
                        paths.add(pending);   // 次の F 行に出会った ＝ 前のブロックは書き終えている
                    }
                    pending = in.column(1);
                } else if (rowType == CacheFormat.ROW_END && pending != null) {
                    paths.add(pending);       // 最後まで書き終えた印に出会った
                    pending = null;
                }
            }
        } catch (IOException | RuntimeException e) {
            Log.warn(Messages.format("analysis.resume.readFailedPartial", e));
        }
        return paths;
    }

    /** 引き継ぎに使った（あるいは使えなかった）退避ファイルを消す */
    private static void deletePartial(Path partial) {
        if (partial == null) {
            return;
        }
        try {
            Files.deleteIfExists(partial);
        } catch (IOException e) {
            Log.warn(Messages.format("analysis.resume.cannotDelete", e));
        }
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
            Log.warn(Messages.format("analysis.resume.readFailed", e));
            return false;
        }
        if (head == null) {
            Log.info(Messages.get("analysis.resume.incompatible"));
            return false;
        }
        if (!head.sources().equals(fingerprintOf(live))) {
            Log.info(Messages.get("analysis.resume.sourcesChanged"));
            return false;
        }
        LibraryDiff diff = LibraryDiff.compute(layout.classpathArray(), head.libraries(), layout.projectRoot);
        if (diff.any()) {
            Log.info(Messages.format("analysis.resume.librariesChanged", diff));
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
                    block.add(in.line());   // F 行の中身（パス・サイズ・ハッシュ）は今と一致している
                }
            }
            // 最後の F 行から始まるブロックは、途中で切れている可能性があるので使わない
        } catch (IOException | RuntimeException e) {
            Log.warn(Messages.format("analysis.resume.readFailedPartial", e));
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
                case CacheFormat.ROW_FILE -> {
                    // 引き継いだファイルも、解析したファイルと同じくエラーを数える（数えないと警告が消える）
                    String[] f = CacheFormat.columnsOf(line);
                    result.countErrors(rel, CacheFormat.errorsOf(f), CacheFormat.syntaxErrorsOf(f));
                }
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
     * 2 つのキャッシュが対になっているか（同じ実行で書かれ、どちらも最後まで書けているか）。
     *
     * 世代の印（ヘッダ）とブロック数（最終行の Z 行）の両方を突き合わせる。
     * 世代は「同じ実行で書かれたか」、ブロック数は「どちらも途中で切れていないか」を見る。
     * dataflow 側のヘッダの形式もここで検証する。
     *
     * @return 対になっていれば true。なっていなければ理由をログに出して false
     */
    private boolean dataflowCachePairsWith(String generation) throws IOException {
        try (CacheReader flow = CacheReader.open(config.dataflowCacheFile)) {
            if (!flow.headerMatches(CacheFormat.dataflowHeaderFor(
                    config.sourceLevel, config.sourceEncoding))) {
                Log.info(Messages.get("analysis.cache.dataflowIncompatible"));
                return false;
            }
            String flowGeneration = flow.generation();
            if (flowGeneration.isEmpty() || !flowGeneration.equals(generation)) {
                Log.info(Messages.get("analysis.cache.differentGeneration"));
                return false;
            }
        }
        String flowTrailer = trailerOf(config.dataflowCacheFile);
        String trailer = trailerOf(config.cacheFile);
        if (flowTrailer == null || !flowTrailer.equals(trailer)) {
            Log.info(Messages.get("analysis.cache.blockCountMismatch"));
            return false;
        }
        return true;
    }

    /**
     * ファイルの最終行が {@link CacheFormat#trailerFor} の Z 行なら、その行。違えば null。
     *
     * ファイルの末尾だけを読む（{@link CacheReader#lastLineOf}）。ここは毎回の実行で
     * 2 つのキャッシュに対して呼ぶので、先頭から読むとファイル全体の走査が 2 本増えてしまう。
     * 最終行が Z 行でない（途中で切れている・別の行で終わっている）ときは、
     * 突き合わせに失敗させて両方作り直す
     */
    private static String trailerOf(Path file) throws IOException {
        String last = CacheReader.lastLineOf(file);
        return (last != null && CacheFormat.rowTypeOf(last) == CacheFormat.ROW_END) ? last : null;
    }

    /**
     * キャッシュのヘッダの直後にある情報。
     *
     * @param libraries  解析時の依存 jar（L行）
     * @param sources    解析開始時のソース一覧の指紋（T行）。無ければ空文字
     * @param generation ヘッダの世代の印。dataflow 側と対になっているかの判定に使う
     */
    private record CacheHead(List<LibraryFact> libraries, String sources, String generation) {
    }

    /**
     * ヘッダが今回と一致すれば、続く T 行・L 行を返す。一致しなければ null。
     * 既存キャッシュ（パス0）と、中断した前回の実行の一時ファイル（引き継ぎ）で共通。
     */
    private CacheHead headOf(Path cacheFile) throws IOException {
        try (CacheReader in = CacheReader.open(cacheFile)) {
            if (!in.headerMatches(CacheFormat.headerFor(config.sourceLevel, config.sourceEncoding))) {
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
            return new CacheHead(libraries, sources, in.generation());
        }
    }

    /**
     * 解析対象のソース一覧の指紋（相対パス・サイズ・内容ハッシュ）。
     *
     * 引き継ぎの判定に使う。1ファイルぶんの同一性が一致していても、他のファイルが
     * 変わっていればそのブロックの解決結果は古いので、一覧が丸ごと同じときだけ引き継ぐ。
     *
     * <p>中身は差分更新の同一性（{@link #isValidBlock}）と同じ3つ立て。更新時刻は入れない
     * （クラスの説明「同一性」、docs/cache-identity-qa.md）。
     *
     * <p>全ファイルのハッシュが要るが、読むのは 1 ファイル 1 回だけ（{@link #hashOf}）。
     * 差分更新の判定（パス1 の {@link #isValidBlock}）と、これから解析するファイルの F 行と、
     * この指紋とで同じハッシュを使い回す。
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
     * パス1。旧キャッシュを読み、有効なファイルの集合と「変わった型」を集める。
     *
     * 最後まで書き終えたキャッシュか（最終行の印。{@link CacheFormat#trailerFor}）もここで見る。
     * 途中で切れたキャッシュは、切れた場所より前のブロックが「サイズもハッシュも一致する」ように
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
            while (in.next()) {
                char rowType = in.rowType();
                lastLine = in.line();
                if (rowType == CacheFormat.ROW_FILE) {
                    blocks++;
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
        } catch (IOException | RuntimeException e) {
            Log.warn(Messages.format("analysis.cache.unreadable", e));
            return false;
        }
        if (!lastLine.equals(CacheFormat.trailerFor(blocks))) {
            Log.info(Messages.format("analysis.cache.truncated", blocks));
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
     * F 行も含めてそのまま書き写す。F 行の中身（パス・サイズ・内容ハッシュ）は、有効だと
     * 判定した時点で今のファイルと一致しているので、書き直す必要がない。
     * L 行はブロックの外（先頭）にあり、ここでは書き写さない（パス0で新しいものを書いている）。
     *
     * 書き写したブロックの数を {@code result.reused} に、そこに含まれる型解決できなかった
     * 呼び出しの件数を {@code result.unresolved} に足す。数は最終行（{@link CacheFormat#trailerFor}）
     * にも出すので、valid の件数ではなく実際に書いた数を数える。
     *
     * @return 書き写した相対パス（パス5b が dataflow 側で同じブロックを書き写すため）
     */
    private Set<String> copyValidBlocks(Set<String> valid, BufferedWriter cacheOut,
                                        CachePhaseResult result) throws IOException {
        Set<String> copied = new HashSet<>();
        try (CacheReader in = CacheReader.open(config.cacheFile)) {   // ヘッダはパス0で検証済み
            boolean keeping = false;
            while (in.next()) {
                char rowType = in.rowType();
                if (rowType == CacheFormat.ROW_FILE) {
                    String[] f = in.columns();
                    keeping = (f.length >= 2 && valid.contains(f[1]));
                    if (keeping) {
                        writeLine(cacheOut, in.line());
                        copied.add(f[1]);
                        result.reused++;
                        // 前の実行でエラーだったファイルは、書き写した今回もエラーのままである。
                        // ここで数えないと、2回目以降の実行で警告が消えてしまう
                        result.countErrors(f[1], CacheFormat.errorsOf(f), CacheFormat.syntaxErrorsOf(f));
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
        return copied;
    }

    /**
     * パス5b。dataflow 側のキャッシュから、パス5 が analysis 側へ書き写したのと同じ相対パスの
     * ブロックを書き写す。
     *
     * パス5 と同じ順（旧キャッシュの並び）で同じ部分集合を書き写すので、
     * 2 つのキャッシュのブロックの並びと数は一致したままになる。
     * 対象はパス1b で「両方にあるブロック」に絞ってあるので、ここで欠けることはない。
     *
     * @param flowCache 読み込み元（既存のキャッシュ、または引き継ぎ用に退避した一時ファイル）
     */
    private void copyDataflowBlocks(Path flowCache, Set<String> copied, BufferedWriter flowOut)
            throws IOException {
        if (copied.isEmpty()) {
            return;
        }
        try (CacheReader in = CacheReader.open(flowCache)) {   // ヘッダはパス0で検証済み
            boolean keeping = false;
            while (in.next()) {
                char rowType = in.rowType();
                if (rowType == CacheFormat.ROW_FILE) {
                    String[] f = in.columns();
                    keeping = (f.length >= 2 && copied.contains(f[1]));
                    if (keeping) {
                        writeLine(flowOut, in.line());
                    }
                } else if (rowType == CacheFormat.ROW_END) {
                    keeping = false;   // 旧キャッシュの最終行。新しいものを書き終わりに1行だけ書く
                } else if (keeping) {
                    writeLine(flowOut, in.line());
                }
            }
        }
    }

    /**
     * パス1b。dataflow 側のキャッシュにブロックが無い相対パスを、有効な集合から外す。
     *
     * ヘッダの世代とブロック数が一致していれば 2 つは同じ実行で書かれているので、普通は全部そろっている。
     * それでも突き合わせるのは、片方だけを外から消された・書き換えられた場合に
     * 「analysis だけ再利用して dataflow が欠けたブロック」を作らないため。
     * 外したファイルは通常の再解析（パス2）に回るので、対でないブロックは残らない。
     *
     * <p>ついでに K 行（宣言している定数の値）の指紋もここで集める。定数は値なので dataflow 側にあり、
     * <b>定数の連鎖の判断も dataflow 側を読んで行う</b>（{@code docs/cache-split-qa.md} の Q11）。
     * ブロックの走査はどうせ1回するので、同じ走査で済ませている。
     *
     * <p>ここは<b>dataflow 側を最初に丸ごと読む場所</b>なので、文字が壊れている・読めないことに
     * 気づくのもここになる（パス0 は末尾の数百バイトしか読まない）。読めなければ例外を投げずに
     * false を返し、呼び出し側に両方とも捨てさせる。1 つのキャッシュが壊れているだけで
     * 解析そのものを失敗させてはいけない（{@link #scanOldCache} が false を返すときと同じ扱い）。
     *
     * @return そのまま使ってよければ true。読めないなら false（両方捨てて全件解析する）
     */
    private boolean dropBlocksMissingFromDataflowCache(Set<String> valid, Set<String> libraryAffected) {
        Set<String> present = new HashSet<>();
        try (CacheReader in = CacheReader.open(config.dataflowCacheFile)) {   // ヘッダはパス0で検証済み
            String blockRel = null;                       // 有効・無効によらずブロックのファイル
            List<String> blockConstants = new ArrayList<>();
            while (in.next()) {
                if (in.is(CacheFormat.ROW_FILE)) {
                    rememberConstants(blockRel, blockConstants);
                    blockRel = in.column(1);
                    present.add(blockRel);
                } else if (in.is(CacheFormat.ROW_CONSTANT)) {
                    ConstantFact k = ConstantFact.fromRow(in.columns());
                    if (k != null) {
                        blockConstants.add(k.fingerprint());
                    }
                }
            }
            rememberConstants(blockRel, blockConstants);   // 最後のブロック
        } catch (IOException | RuntimeException e) {
            Log.warn(Messages.format("analysis.cache.dataflowUnreadable", e));
            return false;
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
            Log.info(Messages.format("analysis.cache.dataflowMissingBlocks", dropped));
        }
        return true;
    }

    private static void writeLine(BufferedWriter w, String line) throws IOException {
        w.write(line);
        w.newLine();
    }

    /** 1ファイル分のブロックを書く。行の並びは {@link CacheFormat} のとおり */
    private static void writeBlock(FileAnalysis fa, BufferedWriter w, BufferedWriter flowOut)
            throws IOException {
        String fileRow = CacheFormat.joinRow("F", fa.relativePath,
                String.valueOf(fa.size), String.valueOf(fa.errors), fa.hash,
                String.valueOf(fa.syntaxErrors));
        writeLine(w, fileRow);
        writeLine(flowOut, fileRow);
        // dataflow 側にはサイドカーの解析のための事実だけを置く。片方だけに書くことはしない
        for (FieldAccessFact a : fa.fieldAccesses) {
            writeLine(flowOut, a.toRow());
        }
        // 値グラフ（N行）は、参照される前に並んでいる必要は無いが、番号順に書く（読みやすさと決定性のため）
        for (ValueNode n : fa.valueNodes) {
            writeLine(flowOut, n.toRow());
        }
        // 呼び出し箇所ごとの値（P行）。analysis 側の C 行・U 行と同じ数・同じ順に並ぶ
        for (CallSiteValues v : fa.callSiteValues) {
            writeLine(flowOut, v.toRow());
        }
        // I行はF行の直後に置く（差分更新で、ブロックを読み進める前に依存を判定するため）
        writeLine(w, CacheFormat.joinRow("I", String.join(",", dependenciesOf(fa))));
        for (TypeFact t : fa.types) {
            writeLine(w, t.toRow());
        }
        for (MethodDeclFact d : fa.declarations) {
            writeLine(w, d.toRow());
        }
        // O行はD行の直後。読み手は宣言をID化してから上書き関係を引くので、この順でなければならない
        for (OverrideFact o : fa.overrides) {
            writeLine(w, o.toRow());
        }
        for (FieldDeclFact v : fa.fieldDecls) {
            writeLine(w, v.toRow());
        }
        // K行は指紋の順に並べる。同じソースならいつ解析しても同じ並びになり、
        // 旧キャッシュとの突き合わせ（定数の連鎖）が並び順に振り回されない。
        // 値なので dataflow 側に置く（定数の連鎖の判断もそちらを読んで行う）
        for (String row : sortedConstantRows(fa)) {
            writeLine(flowOut, row);
        }
        // フィールドへの代入は「どこから来た値か」なので dataflow 側。
        // 同じブロックの V 行（フィールド宣言）と組で判定するので、ブロックの対応が要る
        for (FieldAssignFact j : fa.fieldAssigns) {
            writeLine(flowOut, j.toRow());
        }
        // 呼び出し箇所（解決できたものも失敗したものも）はソース上の順のまま書く。
        // 読み手が import 推定の候補をエッジにしたとき、元の呼び出しの並びが保たれる
        for (CallSite site : fa.callSites) {
            writeLine(w, site.toRow());
        }
        // return は全部書く（追跡できないものも U として）。
        // 「追跡できない return が1つでもあれば戻り値は不定」という判定は読み手が行う。
        // 戻り値の出所は値なので dataflow 側
        for (ReturnFact r : fa.returns) {
            writeLine(flowOut, r.toRow());
        }
        for (FunctionalImplFact m : fa.functionalImpls) {
            writeLine(w, m.toRow());
        }
        // フェーズAの拡張が拾った証拠も値なので dataflow 側（ヘッダの hints= も同じ側に置く）
        for (HintFact h : fa.hints) {
            writeLine(flowOut, h.toRow());
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
     * 自分が宣言する型を除いたもの（自分の変更は F 行の同一性で検知できる）
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
