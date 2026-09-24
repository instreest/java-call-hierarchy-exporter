// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import jche.cache.BlockChecksum;
import jche.cache.CacheFormat;
import jche.cache.CacheReader;
import jche.cache.CallSite;
import jche.cache.CallSiteValues;
import jche.cache.ConstantFact;
import jche.cache.FieldAccessFact;
import jche.cache.FieldAssignFact;
import jche.cache.FieldDeclFact;
import jche.cache.FileAnalysis;
import jche.cache.FunctionalImplFact;
import jche.cache.Guard;
import jche.cache.HintFact;
import jche.cache.LibraryFact;
import jche.cache.MethodDeclFact;
import jche.cache.OverrideFact;
import jche.cache.ReturnFact;
import jche.cache.SymbolTable;
import jche.cache.TempFiles;
import jche.cache.TypeFact;
import jche.cache.UnresolvedCallFact;
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
 * 全件保持は不要。ヒープ常駐は「ソースファイルの一覧＋サイズ」「変わった型の集合」と、
 * 書き写すブロックの位置（ブロックあたり数十バイトの配列）だけ。未解決呼び出しの件数も、
 * この過程で同時に数える（溜め込まない）。
 * パース自体は {@link CallEdgeExtractor#BATCH_SIZE} 件ずつまとめて行う（1ファイルずつでは
 * 規模に比例して遅くなるため）。
 *
 * <h2>キャッシュは 1 ファイル、壊れたかどうかはブロックごとに見る</h2>
 * キャッシュ（{@code analysis-cache.tsv}）は、ソースファイル 1 つにつき 1 ブロックで、構造と値を
 * 同じブロックに持つ（{@link jche.cache.CacheFormat}）。一時ファイルに書いてから本物に差し替える。
 * F 行にはブロックの検査値（crc）を書き、パス1 はブロックごとに計算し直して突き合わせる。
 * 合わないブロック（書き換え・文字化け）はそのファイルだけ解析し直し、ほかのブロックは再利用する。
 * 最終行（Z 行）のブロック数が合わない・読めないキャッシュは、丸ごと捨てて全件解析し直す。
 * 以前の形式が残した {@code dataflow-cache.tsv}（とその一時ファイル）は、実行の最初に消す。
 *
 * 手順:
 * <pre>
 *   パス0 … 旧キャッシュのヘッダ（形式・ソースレベル・文字コード・JDK・JDT）を検証し、
 *           L 行（解析時の依存 jar）を読んで今回のクラスパスと突き合わせる。
 *           追加・変更・削除された jar のパッケージを「変わったパッケージ」として集める。
 *   パス1 … 旧キャッシュを順に読み、サイズと内容ハッシュが一致し、検査値も合うファイル（有効）を覚える。
 *           無効・消滅したファイルのブロックが宣言していた型（H行）を「変わった型」として集める。
 *           jar が追加・変更されていれば、型解決に失敗していたファイル（F行のエラー数、
 *           U行の BINDING_FAILED）も有効から外す。追加された jar で解決できるようになりうるため。
 *           定数の連鎖のために、各ブロックの K 行の指紋もここで覚える。
 *           旧キャッシュを行として読むのはこの 1 回だけ。有効なブロックの依存（I 行）は一時ファイル
 *           （依存の索引）に書き、ファイル上の範囲と F 行の件数は配列に覚えておく（パス3・パス5 が使う）。
 *   （何も変わっていなければ、ここで終わる。下記「何も変わっていないとき」）
 *   パス2 … 変更・追加されたファイルを解析して新キャッシュへ書く。
 *           そのファイルが宣言する型も「変わった型」に加える（改名・追加に備える）。
 *   パス3 … 依存の索引を読み、有効なブロックのうち、I行（依存する型）が
 *           「変わった型」または「変わったパッケージ」に触れるものを再解析に回す。
 *           触れるものは、バインディング解決の結果が変わっている可能性があるため。
 *   パス4 … パス3で再解析に回したファイルを解析し、追記する。そのファイルが宣言する定数
 *           （K行）の値が変わっていたら、宣言する型を「変わった型」に加えてパス3へ戻る
 *           （下記「定数の連鎖」）。
 *   パス5 … 最後まで有効だったブロックを、F 行ごとそのまま書き写す（行に戻さず、バイトの範囲のまま）。
 * </pre>
 *
 * <h2>何も変わっていないとき</h2>
 * 解析するファイルが 1 つも無く（変更・追加・削除・壊れたブロック・jar の変化が無い）、先頭の行
 * （ヘッダ・L 行・T 行）も同じなら、書き直しても旧キャッシュとまったく同じバイト列になる。
 * そのときは書き直さず、旧キャッシュのファイルをそのまま残す（{@link #canKeepAsIs}）。件数の数え方は
 * 書き直したときと同じ。旧キャッシュが書き手の書くとおりの形でない（空行・CRLF を含む）ときは、
 * 書き直すとバイト列が変わるので残さない。
 *
 * <h2>一時ファイル</h2>
 * キャッシュ本体の一時ファイル（{@code .tmp}。中断からの引き継ぎに使う）のほかに、パス1 が書いて
 * パス3 が読む依存の索引（{@link DepsIndex}。{@link TempFiles}）を使う。索引は実行の終わりに（失敗しても）消し、
 * 強制終了（SIGINT / SIGTERM）のときは JVM の終了フックが消し、それでも残ったものは実行の最初に消す。
 * 索引を書けない（ディスクの空きが無い・権限が無い）ときは、パス3 は旧キャッシュから I 行を読む
 * （索引を使う前と同じ読み方。結果は変わらない）。
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
    /**
     * 検査値が合わなかったブロックのうち、そのファイルを今回解析し直すものの数
     * （旧キャッシュと引き継ぎの一時ファイルの合計。ログに 1 回だけ出す）。
     * 今のソースに無いファイルのブロックと、旧キャッシュを丸ごと捨てたときのブロックは数えない
     * （「それらのファイルは解析し直す」という案内が事実と食い違うため）
     */
    private int damagedBlocks;

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
        deleteLegacyFiles();
        TempFiles.deleteLeftovers(config.cacheFile);
        Path tmpCache = config.cacheFile.resolveSibling(config.cacheFile.getFileName() + ".tmp");
        // 前回が途中で終わっていれば、その一時ファイルを退避して「パースの使い回し」に使う
        Path partialCache = takeOverPartial(config.cacheFile, tmpCache);

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

        // パス1 が書き、パス3 が読む依存の索引（有効なブロックの I 行）。何があっても最後に消す
        try (DepsIndex deps = new DepsIndex(config.cacheFile)) {
            // --- パス1: 有効なブロックと「変わった型」を集める ---
            Set<String> valid = new HashSet<>();
            StaleTypes stale = new StaleTypes(libraries.changedPackages);
            Set<String> libraryAffected = new HashSet<>();   // 型解決に失敗していて、jar の追加で変わりうるファイル
            OldCache old = oldCacheUsable
                    ? scanOldCache(live, valid, stale, libraries.anyAddedOrChanged(), libraryAffected, deps)
                    : null;
            if (old == null) {
                // 途中で切れている・読めないキャッシュ。中途半端に再利用すると呼び出しが静かに欠けるので、
                // 丸ごと捨てて全件解析し直す（ヘッダが違ったときと同じ扱い）
                valid.clear();
                libraryAffected.clear();
                oldConstants.clear();
            }
            // ソース一覧の指紋（T行）。ハッシュはパス1 と共通で、1ファイル 1 回しか読まない（hashOf）
            String sources = fingerprintOf(live);
            // ヘッダ・L 行・T 行。この並びは形式で決まっている
            List<String> head = headLinesOf(libraries.current, sources);

            // --- パス2 で解析するファイル ---
            List<SourceFile> changed = new ArrayList<>();
            List<SourceFile> unresolvedBefore = new ArrayList<>();
            for (Map.Entry<String, SourceFile> en : live.entrySet()) {
                if (libraryAffected.contains(en.getKey())) {
                    unresolvedBefore.add(en.getValue());
                } else if (!valid.contains(en.getKey())) {
                    changed.add(en.getValue());
                }
            }

            if (canKeepAsIs(old, changed, unresolvedBefore, stale, head)) {
                // 何も変わっていない。書き直しても同じバイト列になるので、旧キャッシュをそのまま残す
                if (partialCache != null) {
                    salvageFrom(partialCache, changed, live, sources, libraries.current, null, stale, result);
                }
                deletePartial(partialCache);
                keepAsIs(old, result);
                progress.step(result.reused);
                progress.finish();
                return result;
            }

            FileChannel outChannel = FileChannel.open(tmpCache, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            // 閉じると outChannel も閉じる（Channels.newOutputStream の close は元のチャネルを閉じる）
            try (BufferedWriter cacheOut = new BufferedWriter(new OutputStreamWriter(
                    Channels.newOutputStream(outChannel), StandardCharsets.UTF_8.newEncoder()))) {
                for (String line : head) {
                    writeLine(cacheOut, line);
                }
                BlockWriter writer = new BlockWriter(cacheOut, result, progress, this::hashOf, oldConstants);

                // --- パス2: 変更・追加されたファイルを解析 ---
                writer.stale = stale;
                // ファイル自身が変わっているので、宣言する型は無条件に「変わった型」へ（改名・追加に備える）
                writer.cascade = Cascade.ALWAYS;
                if (partialCache != null) {
                    changed = salvageFrom(partialCache, changed, live, sources, libraries.current, cacheOut,
                            stale, result);
                    writer.skipped(result.salvaged);
                }
                deletePartial(partialCache);
                if (damagedBlocks > 0) {
                    // 検査値の合わないブロックは再利用も引き継ぎもしていない（そのファイルは解析し直す）
                    Log.info(Messages.format("analysis.cache.damagedBlocks", damagedBlocks));
                }
                analyzeInBatches(extractor, changed, writer);
                writer.countAs = Reason.BY_LIBRARY;
                analyzeInBatches(extractor, unresolvedBefore, writer);

                // --- パス3・パス4: 依存で無効になったファイルを解析し直す（定数が絡むと連鎖するので不動点まで） ---
                writer.cascade = Cascade.WHEN_CONSTANTS_CHANGED;
                if (old != null && !valid.isEmpty()) {
                    reanalyzeDependents(extractor, writer, live, valid, stale, deps, old);

                    // --- パス5: 最後まで有効だったブロックを書き写す ---
                    copyValidBlocks(old, valid, outChannel, cacheOut, result);
                    writer.skipped(result.reused);
                }

                // 最終行。次回、ここまで書き終えたキャッシュかどうか（とブロックの数）を見分けるための印
                writeLine(cacheOut, CacheFormat.trailerFor(result.parsed + result.reused + result.salvaged));
            }
        }
        progress.finish();

        Files.move(tmpCache, config.cacheFile, StandardCopyOption.REPLACE_EXISTING);
        return result;
    }

    /** キャッシュの先頭の行（ヘッダ行・依存 jar の L 行・ソース一覧の T 行）。書くときも、旧キャッシュと比べるときも使う */
    private List<String> headLinesOf(List<LibraryFact> libraries, String sources) {
        List<String> lines = new ArrayList<>(libraries.size() + 2);
        lines.add(CacheFormat.headerFor(config.sourceLevel, config.sourceEncoding, JdtVersion.current()));
        for (LibraryFact l : libraries) {
            lines.add(l.toRow());
        }
        lines.add(CacheFormat.sourcesRow(sources));
        return lines;
    }

    /**
     * 旧キャッシュを書き直さずにそのまま残してよいか。書き直した結果が旧キャッシュとバイト単位で同じになるときだけ
     * true にする（残しても書き直しても、次の実行とフェーズ2 が読むものは同じ）。
     * <ul>
     *   <li>解析するファイルが無い（変更・追加・jar の追加で解析し直すファイルが無い。依存で解析し直すファイルも
     *       無い＝「変わった型」も「変わった jar のパッケージ」も無い）</li>
     *   <li>旧キャッシュのどのブロックも有効（消えたファイル・壊れたブロックが無い）</li>
     *   <li>先頭の行（ヘッダ・L 行の中身と並び・T 行）が今回書くものと同じ</li>
     *   <li>旧キャッシュが書き手の書くとおりの形（空行・CRLF が無く、最終行の Z 行が 1 つだけ）。
     *       そうでなければ、書き直すと形が整うぶんだけバイト列が変わる</li>
     * </ul>
     * 中断した前回の実行から引き継ぐものも無い（解析するファイルが無ければ引き継ぎもしない）。
     */
    private boolean canKeepAsIs(OldCache old, List<SourceFile> changed, List<SourceFile> unresolvedBefore,
                                StaleTypes stale, List<String> head) throws IOException {
        if (old == null || !old.allKept || !old.writtenAsIs || !changed.isEmpty() || !unresolvedBefore.isEmpty()
                || !stale.isEmpty()) {
            return false;
        }
        StringBuilder sb = new StringBuilder();
        for (String line : head) {
            sb.append(line).append('\n');
        }
        byte[] expected = sb.toString().getBytes(StandardCharsets.UTF_8);
        if (old.blocksStart != expected.length) {
            return false;
        }
        byte[] actual;
        try (InputStream in = Files.newInputStream(config.cacheFile)) {
            actual = in.readNBytes(expected.length);
        }
        return Arrays.equals(actual, expected);
    }

    /**
     * 旧キャッシュをそのまま残すときの集計。書き直したとき（パス5 で全ブロックを書き写したとき）と同じ数え方にする
     */
    private static void keepAsIs(OldCache old, CachePhaseResult result) {
        Log.info(Messages.get("analysis.cache.unchanged"));
        for (int i = 0; i < old.size; i++) {
            result.reused++;
            // 前の実行でエラーだったファイルは、今回もエラーのままである（数えないと警告が消える）
            result.countErrors(old.paths[i], old.errors[i], old.syntaxErrors[i]);
            result.unresolved += old.unresolved[i];
        }
    }

    /**
     * 以前の形式（キャッシュが 2 ファイルだった版）が残した {@code dataflow-cache.tsv} と、
     * その一時ファイル・退避ファイルを消す。
     *
     * 今の形式は読まないので、残しておくとディスクを食うだけでなく、GitHub Actions のキャッシュのように
     * フォルダごと保存する使い方では保存のたびに持ち越される。利用者が対処することは無いので、
     * 消したことは {@code Log.info} で知らせるだけにする（{@code warnings.txt} には載せない）
     */
    private void deleteLegacyFiles() {
        Path legacy = config.cacheFile.resolveSibling(Config.LEGACY_DATAFLOW_CACHE_FILE_NAME);
        for (Path p : List.of(legacy, legacy.resolveSibling(legacy.getFileName() + ".tmp"),
                legacy.resolveSibling(legacy.getFileName() + ".partial"))) {
            try {
                if (Files.deleteIfExists(p)) {
                    Log.info(Messages.format("analysis.cache.legacyDeleted", p.getFileName()));
                }
            } catch (IOException e) {
                Log.info(Messages.format("analysis.cache.legacyNotDeleted", p.getFileName(), e));
            }
        }
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
            // writeBlock はブロックをメモリ上で組み終えてから書くので、途中で例外が出ても書きかけは残らない
            // （例外は呼び出し元がこのファイルの失敗として数える）。書けたら直後に数え、Z 行の数と揃える
            writeBlock(fa, cacheOut);
            result.parsed++;
            result.unresolved += fa.unresolvedCount();
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
     * パス0。旧キャッシュのヘッダを検証し、L 行（解析時の依存 jar）を読む。
     *
     * 次のどれかに当たれば null（旧キャッシュを使わずに全件再解析）。
     * <pre>
     *   - キャッシュが無い
     *   - ヘッダの形式（版・ソースレベル・文字コード・JDK・JDT）が違う
     *   - 読めない
     * </pre>
     * ここで読むのはヘッダと L 行・T 行だけ（最初の F 行で打ち切る）。<b>キャッシュ全体は走らない。</b>
     * 毎回の実行で通る経路なので、ここでフルスキャンすると差分更新の利点をそのぶん削ってしまう。
     * 途中で切れている・壊れているかは、全体を読むパス1（{@link #scanOldCache}）が見る
     */
    private List<LibraryFact> readOldLibraries() {
        if (!Files.isRegularFile(config.cacheFile)) {
            return null;
        }
        try {
            CacheHead head = headOf(config.cacheFile);
            if (head == null) {
                // 形式が変わった場合のほか、source.level・ソースの文字コード・実行 JDK・JDT が
                // 変わった場合もここで破棄する。言語バージョン・文字コード・ブートクラスパスが違えば
                // 同じソースでも解析結果が変わるため、F 行の同一性が一致していても再利用してはいけない
                Log.info(Messages.get("analysis.cache.incompatible"));
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
     *   <li>ヘッダ（形式・ソースレベル・文字コード・JDK・JDT）が今回と一致する</li>
     *   <li>ソース一覧（T行。パス・サイズ・内容ハッシュ）が当時と丸ごと同じ。
     *       ブロックは他のファイルの内容にも依存するため</li>
     *   <li>依存 jar が当時から変わっていない。ブロックは当時のクラスパスでのバインディング解決の
     *       結果なので、jar が変われば同じソースでも呼び出し先や親型が変わりうる</li>
     *   <li>ファイルのサイズと内容ハッシュが一致する（{@link #isValidBlock}）</li>
     *   <li>ブロックの検査値が合う（旧キャッシュのパス1と同じ）</li>
     *   <li>ブロックが最後まで書けている。次の F 行か、最後まで書き終えた印（Z 行）に
     *       出会ったブロックだけを使う。最後の F 行から始まるブロックは、書き込みバッファの
     *       途中で切れている可能性があるので使わない</li>
     * </ul>
     *
     * @param sources   今回のソース一覧の指紋（T 行）
     * @param libraries 今回の依存 jar（L 行。{@link LibraryDiff#current}）
     * @param cacheOut  書き写す先。解析するファイルが無いとき（{@code toAnalyze} が空）は書かないので null でもよい
     * @return まだ解析が必要なファイル（引き継げたぶんを除いたもの）
     */
    private List<SourceFile> salvageFrom(Path partial, List<SourceFile> toAnalyze,
                                         Map<String, SourceFile> live, String sources,
                                         List<LibraryFact> libraries, BufferedWriter cacheOut,
                                         StaleTypes stale, CachePhaseResult result) throws IOException {
        Set<String> taken = new HashSet<>();
        if (isPartialUsable(partial, sources, libraries)) {
            Set<String> wanted = pathsOf(toAnalyze);
            if (!wanted.isEmpty()) {
                copySalvageable(partial, wanted, live, cacheOut, stale, result, taken);
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
     * ヘッダ（形式・ソースレベル・文字コード・JDK・JDT）・依存 jar・<b>ソース一覧</b>が
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
     *
     * <p>依存 jar は、今回の L 行（パス0 で旧キャッシュと突き合わせた {@link LibraryDiff#current}）と比べる。
     * クラスパスを走査し直さない（クラスフォルダなら {@code .class} をすべて読み直すことになる）。
     * 比べ方は {@link LibraryDiff#unchangedSince} を参照（走査し直して突き合わせたときと同じ答えになる）。
     */
    private boolean isPartialUsable(Path partial, String sources, List<LibraryFact> libraries) {
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
        if (!head.sources().equals(sources)) {
            Log.info(Messages.get("analysis.resume.sourcesChanged"));
            return false;
        }
        LibraryDiff diff = LibraryDiff.unchangedSince(head.libraries(), libraries);
        if (diff.any()) {
            Log.info(Messages.format("analysis.resume.librariesChanged", diff));
            return false;
        }
        return true;
    }

    /**
     * 引き継げるブロックを新キャッシュへ書き写す。書き写せたファイルを taken に積む。
     * 検査値の合わないブロックは書き写さない（そのファイルは解析し直す）
     */
    private void copySalvageable(Path partial, Set<String> wanted, Map<String, SourceFile> live,
                                 BufferedWriter cacheOut, StaleTypes stale, CachePhaseResult result,
                                 Set<String> taken) throws IOException {
        try (CacheReader in = CacheReader.open(partial)) {
            List<String> block = new ArrayList<>();   // 直前の F 行から始まる、判定待ちのブロック
            BlockChecksum checksum = new BlockChecksum();
            String[] fileRow = null;                  // 判定待ちのブロックの F 行（引き継がないなら null）
            while (in.next()) {
                char rowType = in.rowType();
                if (rowType != CacheFormat.ROW_FILE && rowType != CacheFormat.ROW_END) {
                    if (fileRow != null) {
                        block.add(in.line());
                        in.addTo(checksum);
                    }
                    continue;
                }
                // 次のブロックが始まった、または最後まで書き終えた印に出会った。
                // どちらでも、ここまでのブロックは書き終えている
                if (fileRow != null) {
                    if (checksum.hex().equals(CacheFormat.crcOf(fileRow))) {
                        flushSalvaged(fileRow, block, cacheOut, stale, result, taken);
                    } else {
                        damagedBlocks++;
                    }
                }
                fileRow = null;
                block.clear();
                checksum.reset();
                if (rowType == CacheFormat.ROW_END) {
                    continue;
                }
                String[] f = in.columns();
                if (f.length >= 2 && wanted.contains(f[1]) && !taken.contains(f[1])
                        && isValidBlock(f, live)) {
                    fileRow = f;
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
     * 未解決の件数は F 行の列から取る（U 行は読まない）
     *
     * @param fileRow ブロックの F 行の列
     * @param block   F 行を含むブロックの行（ファイルに書かれたまま）
     */
    private static void flushSalvaged(String[] fileRow, List<String> block, BufferedWriter cacheOut,
                                      StaleTypes stale, CachePhaseResult result, Set<String> taken)
            throws IOException {
        String rel = fileRow[1];
        // 引き継いだファイルも、解析したファイルと同じくエラーを数える（数えないと警告が消える）
        result.countErrors(rel, CacheFormat.errorsOf(fileRow), CacheFormat.syntaxErrorsOf(fileRow));
        result.unresolved += CacheFormat.unresolvedOf(fileRow);
        for (String line : block) {
            writeLine(cacheOut, line);
            if (CacheFormat.rowTypeOf(line) == CacheFormat.ROW_TYPE) {
                TypeFact t = TypeFact.fromRow(CacheFormat.columnsOf(line));
                if (t != null) {
                    stale.add(t.typeFqn(), t.pkg());
                }
            }
        }
        result.salvaged++;
        taken.add(rel);
    }

    /**
     * キャッシュのヘッダの直後にある情報。
     *
     * @param libraries  解析時の依存 jar（L行）
     * @param sources    解析開始時のソース一覧の指紋（T行）。無ければ空文字
     */
    private record CacheHead(List<LibraryFact> libraries, String sources) {
    }

    /**
     * ヘッダが今回と一致すれば、続く T 行・L 行を返す。一致しなければ null。
     * 既存キャッシュ（パス0）と、中断した前回の実行の一時ファイル（引き継ぎ）で共通。
     */
    private CacheHead headOf(Path cacheFile) throws IOException {
        try (CacheReader in = CacheReader.open(cacheFile)) {
            if (!in.headerMatches(CacheFormat.headerFor(
                    config.sourceLevel, config.sourceEncoding, JdtVersion.current()))) {
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
     * パス1 で読んでいる途中の旧キャッシュのブロック 1 つ。ブロックの終わり（次の F 行・Z 行・
     * ファイルの終わり）で {@link #finishOldBlock} が判定する。検査値はブロックを読み終えるまで
     * 分からないので、判定に要るものをここに溜めておく（1 ブロック分だけ）
     */
    private static final class OldBlock {
        /** F 行の相対パス。F 行が壊れていれば null */
        final String rel;
        /** そのファイルが今のソースにあるか（無ければ、壊れていても解析し直さないので数えない） */
        final boolean inSources;
        /** F 行のサイズと内容ハッシュが今のソースと一致するか */
        final boolean identical;
        /** F 行のエラー数・構文エラー数・未解決数 */
        final int errors;
        final int syntaxErrors;
        final int unresolved;
        final String expectedCrc;
        /** F 行の先頭のファイル上の位置（バイト） */
        final long start;
        /** F 行を読んだ時点の {@link CacheReader#irregularities}（ブロックが書き手の書くとおりの形かを見る） */
        final long irregularAtStart;
        final BlockChecksum checksum = new BlockChecksum();
        /** H 行（ファイルに書かれたまま）。無効なブロックだったときだけ読んで「変わった型」に加える */
        final List<String> typeRows = new ArrayList<>();
        /** K 行の指紋（定数の連鎖。{@link #rememberConstants}） */
        final List<String> constants = new ArrayList<>();
        /** 理由が BINDING_FAILED の U 行があったか（jar が追加・変更されたときだけ見る） */
        boolean bindingFailed;
        /** F 行の直後の行をまだ読んでいないか */
        boolean firstRow = true;
        /** I 行（F 行の直後）の依存。I 行が無ければ空 */
        String deps = "";

        OldBlock(String[] f, boolean inSources, boolean identical, long start, long irregularAtStart) {
            this.rel = (f.length >= 2) ? f[1] : null;
            this.inSources = inSources;
            this.identical = identical;
            this.errors = CacheFormat.errorsOf(f);
            this.syntaxErrors = CacheFormat.syntaxErrorsOf(f);
            this.unresolved = CacheFormat.unresolvedOf(f);
            this.expectedCrc = CacheFormat.crcOf(f);
            this.start = start;
            this.irregularAtStart = irregularAtStart;
        }
    }

    /**
     * パス1 で読んだ旧キャッシュのうち、パス5 で書き写す候補（有効だったブロック）と、ファイル全体の形。
     *
     * <p>ブロックごとに持つのは、パス（今のソース一覧 {@code live} の文字列そのもの）・ファイル上の範囲
     * （F 行の先頭から、次の F 行・Z 行の手前まで）・F 行の件数（エラー数・構文エラー数・未解決数）だけで、
     * 有効なブロックの数に比例する小さな配列に持つ。パス5 はこの範囲をバイトのまま書き写し、件数もここから数える
     * （旧キャッシュを行として読み直さない）。
     */
    private static final class OldCache {
        String[] paths = new String[64];
        long[] starts = new long[64];
        long[] ends = new long[64];
        int[] errors = new int[64];
        int[] syntaxErrors = new int[64];
        int[] unresolved = new int[64];
        /** 書き手の書くとおりの形でないブロック（空行・CRLF を含む）。書き写すときは行に戻して書き直す */
        final BitSet irregular = new BitSet();
        int size;

        /** F 行の数 */
        long blocks;
        /** どのブロックも有効だったか（書き写す候補になったか） */
        boolean allKept = true;
        /** 最初の F 行（ブロックが無ければ Z 行）の位置。ここより前はヘッダ・L 行・T 行 */
        long blocksStart = -1;
        /** ファイル全体が書き手の書くとおりの形か（空行・CRLF・改行で終わらない行が無く、Z 行が 1 つだけ） */
        boolean writtenAsIs;

        void add(String path, long start, long end, int errorCount, int syntaxErrorCount, int unresolvedCount,
                 boolean irregularBlock) {
            if (size == paths.length) {
                int grown = size + (size >> 1) + 16;
                paths = Arrays.copyOf(paths, grown);
                starts = Arrays.copyOf(starts, grown);
                ends = Arrays.copyOf(ends, grown);
                errors = Arrays.copyOf(errors, grown);
                syntaxErrors = Arrays.copyOf(syntaxErrors, grown);
                unresolved = Arrays.copyOf(unresolved, grown);
            }
            paths[size] = path;
            starts[size] = start;
            ends[size] = end;
            errors[size] = errorCount;
            syntaxErrors[size] = syntaxErrorCount;
            unresolved[size] = unresolvedCount;
            if (irregularBlock) {
                irregular.set(size);
            }
            size++;
        }
    }

    /**
     * パス1。旧キャッシュを読み、有効なファイルの集合と「変わった型」を集める。
     *
     * <p>ブロックごとに検査値（F 行の crc）を計算し直して突き合わせる。合わないブロックは
     * 書き換えられた・化けたもので、中身を信用できないので有効にしない（そのファイルは
     * パス2 で解析し直す。ほかのブロックはそのまま再利用する）。
     *
     * <p>最後まで書き終えたキャッシュか（最終行の印。{@link CacheFormat#trailerFor}）もここで見る。
     * 途中で切れたキャッシュは、切れた場所より前のブロックが「サイズもハッシュも一致する」ように
     * 見えるため、そのまま再利用すると呼び出しが静かに欠ける。印が無い・ブロック数が合わなければ
     * null を返して丸ごと捨てさせる。読めない（文字が壊れている）ときも同じ。
     *
     * <p>定数の連鎖のために、各ブロックの K 行（宣言している定数の値）の指紋もここで覚える
     * （{@link #oldConstants}）。
     *
     * <p>旧キャッシュを行として読むのは実行ごとにこの 1 回だけにする。あとで要るものはここで取っておく。
     * <ul>
     *   <li>パス3 が見る依存（I 行）… 有効なブロックのぶんを一時ファイル（{@link DepsIndex}）に書く。
     *       ヒープには持たない（依存の型名はブロックの数に比例して多い）。書けなくても続ける
     *       （パス3 が旧キャッシュから読む）</li>
     *   <li>パス5 が書き写すブロックの範囲と F 行の件数 … {@link OldCache} に持つ</li>
     * </ul>
     *
     * @param librariesAddedOrChanged jar が追加・変更されたか。そのときは型解決に失敗していたブロック
     *                                （F行のエラー数が 0 でない、または U 行に BINDING_FAILED がある）を
     *                                有効から外し、libraryAffected に積む
     * @param deps                    有効なブロックの依存を書く索引（依存の無いブロックは書かない）
     * @return 旧キャッシュをそのまま使ってよければ、書き写す候補。途中で切れている・読めないなら null
     */
    private OldCache scanOldCache(Map<String, SourceFile> live, Set<String> valid, StaleTypes stale,
                                  boolean librariesAddedOrChanged, Set<String> libraryAffected, DepsIndex deps) {
        OldCache old = new OldCache();
        int damaged = 0;   // 丸ごと捨てるときは数えないので、読み終えるまで damagedBlocks に足さない
        int trailers = 0;
        String lastLine = "";
        // 索引への書き込みの失敗はここでは受けない（索引の側で受けて、パス3 を旧キャッシュから読む形に切り替える）。
        // 受けると「既存キャッシュを読めない」と取り違えて、読めているキャッシュを丸ごと捨ててしまう
        try (CacheReader in = CacheReader.open(config.cacheFile)) {   // ヘッダはパス0で検証済み
            OldBlock block = null;
            while (in.next()) {
                char rowType = in.rowType();
                lastLine = in.line();
                if (rowType == CacheFormat.ROW_FILE || rowType == CacheFormat.ROW_END) {
                    if (old.blocksStart < 0) {
                        old.blocksStart = in.lineStart();
                    }
                    if (block != null) {
                        damaged += finishOldBlock(block, in.lineStart(), in.irregularities(), live, valid, stale,
                                librariesAddedOrChanged, libraryAffected, old, deps);
                        block = null;
                    }
                    if (rowType == CacheFormat.ROW_FILE) {
                        old.blocks++;
                        String[] f = in.columns();
                        boolean inSources = f.length >= 2 && live.containsKey(f[1]);
                        block = new OldBlock(f, inSources, isValidBlock(f, live), in.lineStart(),
                                in.irregularities());
                    } else {
                        trailers++;
                    }
                    continue;
                }
                if (block == null) {
                    continue;   // 最初のブロックより前（L 行・T 行）
                }
                if (block.firstRow) {
                    // F 行の直後。I 行なら依存（パス3 が見る）。無ければ依存なしとみなす
                    block.firstRow = false;
                    if (rowType == CacheFormat.ROW_DEPENDENCIES) {
                        block.deps = in.column(1);
                    }
                }
                in.addTo(block.checksum);
                if (rowType == CacheFormat.ROW_TYPE) {
                    block.typeRows.add(in.line());
                } else if (rowType == CacheFormat.ROW_CONSTANT) {
                    ConstantFact k = ConstantFact.fromRow(in.columns());
                    if (k != null) {
                        block.constants.add(k.fingerprint());
                    }
                } else if (librariesAddedOrChanged && rowType == CacheFormat.ROW_UNRESOLVED
                        && UnresolvedCallFact.BINDING_FAILED.equals(
                                UnresolvedCallFact.reasonColumn(in.columns()))) {
                    // エラーとしては報告されなかったが呼び出し先が解決できなかった。jar の追加で変わりうる
                    block.bindingFailed = true;
                }
            }
            if (block != null) {
                damaged += finishOldBlock(block, in.nextLineStart(), in.irregularities(), live, valid, stale,
                        librariesAddedOrChanged, libraryAffected, old, deps);
            }
            old.writtenAsIs = (in.irregularities() == 0 && trailers == 1);
        } catch (IOException | RuntimeException e) {
            Log.warn(Messages.format("analysis.cache.unreadable", e));
            return null;
        }
        if (!lastLine.equals(CacheFormat.trailerFor(old.blocks))) {
            Log.info(Messages.format("analysis.cache.truncated", old.blocks));
            return null;
        }
        damagedBlocks += damaged;
        deps.finishWriting();
        return old;
    }

    /**
     * パス1 で 1 ブロックを読み終えたときの判定。
     *
     * <ul>
     *   <li>検査値が合い、サイズと内容ハッシュも一致する … 有効（jar が追加・変更されていて、
     *       型解決に失敗していたブロックなら libraryAffected）。有効なら書き写す候補（{@code old}）に積み、
     *       依存（I 行）を索引（{@code deps}）に書く</li>
     *   <li>それ以外 … 無効。宣言していた型（H 行）を「変わった型」に加える
     *       （改名・削除された型を参照していたファイルを解析し直すため）</li>
     * </ul>
     *
     * @param end              ブロックの終わり（次の F 行・Z 行の先頭）のファイル上の位置
     * @param irregularAtEnd   そのときの {@link CacheReader#irregularities}
     * @return 検査値が合わず、そのファイルを解析し直すブロックなら 1（ログの件数に数える）。それ以外は 0
     */
    private int finishOldBlock(OldBlock block, long end, long irregularAtEnd, Map<String, SourceFile> live,
                               Set<String> valid, StaleTypes stale, boolean librariesAddedOrChanged,
                               Set<String> libraryAffected, OldCache old, DepsIndex deps) {
        boolean intact = block.checksum.hex().equals(block.expectedCrc);
        if (intact && block.rel != null) {
            rememberConstants(block.rel, block.constants);
        }
        if (intact && block.identical) {
            // 有効なブロックは今のソースにある（isValidBlock）。パスは今のソース一覧の文字列を使い回す
            String rel = live.get(block.rel).relativePath();
            if (librariesAddedOrChanged && (block.errors > 0 || block.bindingFailed)) {
                libraryAffected.add(rel);   // 宣言する型はパス2の解析時に「変わった型」へ入る
                old.allKept = false;
            } else {
                valid.add(rel);
                old.add(rel, block.start, end, block.errors, block.syntaxErrors, block.unresolved,
                        irregularAtEnd != block.irregularAtStart);
                if (!block.deps.isEmpty()) {
                    deps.add(rel, block.deps);
                }
            }
            return 0;
        }
        old.allKept = false;
        for (String row : block.typeRows) {
            TypeFact t = TypeFact.fromRow(CacheFormat.columnsOf(row));
            if (t != null) {
                stale.add(t.typeFqn(), t.pkg());
            }
        }
        // 今のソースに無いファイルのブロックは、壊れていても解析し直さないので数えない
        return (!intact && block.inSources) ? 1 : 0;
    }

    /** 1ブロック分の K 行の指紋をまとめて覚える */
    private void rememberConstants(String rel, List<String> fingerprints) {
        if (!fingerprints.isEmpty()) {
            oldConstants.put(rel, digestOf(fingerprints));
        }
    }

    /**
     * パス1 が書き、パス3 が読む依存の索引（一時ファイル。{@link TempFiles#DEPS}）。1 行に 1 ブロック、
     * {@code パス 依存}（{@link CacheFormat#joinRow} で符号化）を旧キャッシュのブロックの順に書く。
     * 最初の行を書くときにファイルを作る（書くものが無ければ作らない）。
     *
     * <p>名前は実行ごとに違い（{@link TempFiles#create}）、書くのも読むのも作ったときに開いた 1 つのチャネルで行う
     * （パス3 の周回ごとに先頭へ戻す。名前で開き直さない）。同じキャッシュのフォルダを使う別の実行が
     * 残り物として消しても読めるし、前の実行の残り物を読むこともない。書いた行数を数えておき、読んだ行数が
     * 違えば例外にする（解析は失敗として終わる。依存を読み落として再解析を静かに飛ばすよりよい）。
     *
     * <p>書けなかった（ディスクの空きが無い・権限が無い）ときは、索引を捨てて {@link #unwritable} を立てる。
     * 旧キャッシュは読めているので、キャッシュを捨てたりせず、パス3 は旧キャッシュから I 行を読む
     * （{@link CacheUpdater#selectDependentsFromCache}）。利用者が対処することではないので {@code Log.info} で知らせる。
     * {@link #close} で閉じて消す
     */
    private static final class DepsIndex implements Closeable {
        private final Path cacheFile;
        private Path file;
        private FileChannel channel;
        private BufferedWriter out;
        /** 書いた行の数 */
        private long lines;
        /** 書けなかった。パス3 は旧キャッシュから読む */
        private boolean unwritable;

        DepsIndex(Path cacheFile) {
            this.cacheFile = cacheFile;
        }

        /** 1 ブロックの依存を書く。書けなければ索引を捨てる（例外は投げない） */
        void add(String rel, String deps) {
            if (unwritable) {
                return;
            }
            try {
                if (out == null) {
                    file = TempFiles.create(cacheFile, TempFiles.DEPS);
                    channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
                    // 閉じない（閉じるとチャネルも閉じる）。書き終えたら flush だけする
                    out = new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(channel),
                            StandardCharsets.UTF_8.newEncoder()));
                }
                writeLine(out, CacheFormat.joinRow(rel, deps));
                lines++;
            } catch (IOException | RuntimeException e) {
                giveUp(e);
            }
        }

        /** パス1 を読み終えた。溜めた分をファイルへ出す（チャネルは開いたまま。パス3 が読む） */
        void finishWriting() {
            if (out == null || unwritable) {
                return;
            }
            try {
                out.flush();
            } catch (IOException | RuntimeException e) {
                giveUp(e);
            }
        }

        /** 書けなかった。索引を捨て、以後は書かない（パス3 は旧キャッシュから読む） */
        private void giveUp(Exception e) {
            Log.info(Messages.format("analysis.cache.depsIndexUnwritable",
                    file != null ? file : cacheFile.toAbsolutePath().getParent(), e));
            unwritable = true;
            out = null;
            close();
        }

        /** 書けたか（false なら、パス3 は旧キャッシュから読む） */
        boolean usable() {
            return !unwritable;
        }

        /**
         * 書いた行を先頭から順に渡す（パス3 の 1 周ぶん）。1 行も書いていなければ何も渡さない。
         *
         * @throws IOException 読めない、または読んだ行数が書いた行数と違う
         */
        void forEach(DepsConsumer each) throws IOException {
            if (channel == null) {
                if (lines != 0) {
                    throw new IOException(Messages.format("analysis.cache.depsIndexMismatch", file, lines, 0));
                }
                return;
            }
            channel.position(0);
            // 閉じない（閉じるとチャネルも閉じる。次の周回でまた読む）
            BufferedReader in = new BufferedReader(new InputStreamReader(Channels.newInputStream(channel),
                    StandardCharsets.UTF_8.newDecoder()));
            long read = 0;
            String line;
            while ((line = in.readLine()) != null) {
                read++;
                String[] cols = CacheFormat.columnsOf(line);
                each.accept(cols[0], CacheFormat.columnAt(cols, 1));
            }
            if (read != lines) {
                throw new IOException(Messages.format("analysis.cache.depsIndexMismatch", file, lines, read));
            }
        }

        /** 閉じて消す */
        @Override
        public void close() {
            try {
                if (channel != null) {
                    channel.close();
                }
            } catch (IOException e) {
                Log.info(Messages.format("cache.tempNotDeleted", file, e));
            }
            channel = null;
            TempFiles.delete(file);
        }
    }

    /** 依存の索引の 1 行（パスと依存）を受け取る */
    @FunctionalInterface
    private interface DepsConsumer {
        void accept(String rel, String deps);
    }

    /**
     * パス3・パス4。「変わった型」に触れる有効ブロックを再解析に回し、解析し直した結果として
     * 「変わった型」が増えていたら（定数の連鎖。{@link CacheUpdater} のクラスコメント参照）もう一周する。
     *
     * ふつうは1周で止まる。周回が続くのは、定数を宣言しているファイルが数珠つなぎになっている
     * ときだけ。1周ごとに valid は減るだけで増えないので、必ず止まる。
     *
     * <p>パス3 は、パス1 が書いた依存の索引を先頭から読み、まだ有効なブロックのうち「変わった型」または
     * 「変わった jar のパッケージ」に触れるものを valid から外し、再解析の一覧に積む（旧キャッシュのブロックの順）。
     * 索引に無いブロックは依存が無い（どの型にも触れない）。旧キャッシュは読み直さない（索引を書けなかったときだけ
     * 読む。{@link #selectDependentsFromCache}）。ここでは何も書き出さない。書き写しは、連鎖が止まってから
     * {@link #copyValidBlocks} で行う。
     *
     * @param deps 依存の索引
     * @param old  パス1 で覚えたブロックの位置（索引を書けなかったときに、旧キャッシュから I 行を読むのに使う）
     */
    private void reanalyzeDependents(CallEdgeExtractor extractor, BlockWriter writer,
                                     Map<String, SourceFile> live, Set<String> valid, StaleTypes stale,
                                     DepsIndex deps, OldCache old)
            throws IOException {
        if (stale.isEmpty()) {
            return;   // 触れる先が無いので、読み直すだけ無駄
        }
        int mark;
        do {
            mark = stale.mark();
            List<String> dependents = new ArrayList<>();
            List<String> libraryDependents = new ArrayList<>();
            DepsConsumer select = (rel, depsCsv) -> {
                if (!valid.contains(rel)) {
                    return;   // すでに再解析に回した
                }
                Reason touched = stale.touches(depsCsv);
                if (touched == Reason.BY_SOURCE) {
                    valid.remove(rel);
                    dependents.add(rel);
                } else if (touched == Reason.BY_LIBRARY) {
                    valid.remove(rel);
                    libraryDependents.add(rel);
                }
            };
            if (deps.usable()) {
                deps.forEach(select);
            } else {
                selectDependentsFromCache(old, select);
            }
            writer.countAs = Reason.BY_SOURCE;
            analyzeInBatches(extractor, filesOf(dependents, live), writer);
            writer.countAs = Reason.BY_LIBRARY;
            analyzeInBatches(extractor, filesOf(libraryDependents, live), writer);
        } while (stale.mark() != mark && !valid.isEmpty());
    }

    /**
     * パス3 で依存の索引が使えない（書けなかった）ときの代わり。旧キャッシュを読み、パス1 で書き写す候補にした
     * ブロック（{@code old} に覚えた F 行の位置）の I 行を、索引に書くはずだったのと同じ順に渡す
     * （索引を使う前の読み方。ブロックの位置で照らすので、同じパスのブロックが 2 つある壊れたキャッシュでも
     * 索引と同じものを読む）。
     *
     * @throws IOException 読めない、または覚えた位置に F 行が無い（実行の途中で旧キャッシュが書き換えられた）
     */
    private void selectDependentsFromCache(OldCache old, DepsConsumer select) throws IOException {
        if (old.size == 0) {
            return;
        }
        int k = 0;
        String pending = null;   // F 行を読んだ、依存の判定待ちのブロック
        try (CacheReader in = CacheReader.openAt(config.cacheFile, old.starts[0])) {
            while (in.next()) {
                char rowType = in.rowType();
                boolean blockStart = rowType == CacheFormat.ROW_FILE || rowType == CacheFormat.ROW_END;
                if (pending != null) {
                    // F 行の直後。I 行なら依存、無ければ依存なし（パス1 と同じ見方）
                    select.accept(pending, (!blockStart && rowType == CacheFormat.ROW_DEPENDENCIES)
                            ? in.column(1) : "");
                    pending = null;
                }
                if (k == old.size) {
                    break;
                }
                if (rowType == CacheFormat.ROW_FILE && in.lineStart() == old.starts[k]) {
                    pending = old.paths[k++];
                }
            }
        }
        if (pending != null) {
            select.accept(pending, "");
        }
        if (k != old.size) {
            // 依存を読み落としたまま進めると、解析し直すべきファイルを静かに再利用してしまう
            throw new IOException(Messages.format("analysis.cache.changedWhileReading", config.cacheFile));
        }
    }

    /**
     * パス5。最後まで有効だったブロックを、旧キャッシュでの順のまま新キャッシュへ書き写す。
     *
     * F 行も含めてそのまま書き写す。F 行の中身（パス・サイズ・内容ハッシュ・検査値）は、有効だと
     * 判定した時点で今のファイルと一致しているので、書き直す必要がない。
     * L 行はブロックの外（先頭）にあり、ここでは書き写さない（新しいものを先頭に書いている）。
     *
     * <p>パス1 で覚えたブロックの範囲を、行に戻さずバイトのまま写す（{@link FileChannel#transferTo}。
     * 隣り合うブロックはまとめて 1 回で写す）。書き手の書くとおりの形でないブロック（空行・CRLF を含む。
     * 手で直した・改行を変換したキャッシュ）だけは行に戻し、{@code '\n'} で書き直す（行として読んで
     * 書き直していたときと同じバイト列にするため）。
     *
     * 書き写したブロックの数を {@code result.reused} に、そこに含まれる型解決できなかった
     * 呼び出しの件数（F 行の未解決数）を {@code result.unresolved} に足す。数は最終行
     * （{@link CacheFormat#trailerFor}）にも出すので、valid の件数ではなく実際に書いた数を数える。
     */
    private void copyValidBlocks(OldCache old, Set<String> valid, FileChannel out, BufferedWriter cacheOut,
                                 CachePhaseResult result) throws IOException {
        try (FileChannel in = FileChannel.open(config.cacheFile, StandardOpenOption.READ)) {
            long pendingStart = -1;   // まだ写していない、隣り合うブロックをまとめた範囲
            long pendingEnd = -1;
            for (int i = 0; i < old.size; i++) {
                String rel = old.paths[i];
                if (!valid.contains(rel)) {
                    continue;   // パス3 で解析し直した
                }
                result.reused++;
                // 前の実行でエラーだったファイルは、書き写した今回もエラーのままである。
                // ここで数えないと、2回目以降の実行で警告が消えてしまう
                result.countErrors(rel, old.errors[i], old.syntaxErrors[i]);
                result.unresolved += old.unresolved[i];
                if (old.irregular.get(i)) {
                    transfer(in, pendingStart, pendingEnd, out, cacheOut);
                    pendingStart = -1;
                    pendingEnd = -1;
                    rewriteLines(old.starts[i], old.ends[i], cacheOut);
                } else if (pendingEnd == old.starts[i]) {
                    pendingEnd = old.ends[i];
                } else {
                    transfer(in, pendingStart, pendingEnd, out, cacheOut);
                    pendingStart = old.starts[i];
                    pendingEnd = old.ends[i];
                }
            }
            transfer(in, pendingStart, pendingEnd, out, cacheOut);
        }
    }

    /**
     * 旧キャッシュの {@code [start, end)} を新キャッシュの今の位置へバイトのまま写す（{@code start < 0} なら何もしない）。
     * それまでに {@code cacheOut} へ書いた行を先に吐き出してから写す（同じファイルの続きに並ぶように）
     */
    private void transfer(FileChannel in, long start, long end, FileChannel out, BufferedWriter cacheOut)
            throws IOException {
        if (start < 0 || end <= start) {
            return;
        }
        cacheOut.flush();
        long at = out.position();
        long done = 0;
        long count = end - start;
        while (done < count) {
            long n = in.transferTo(start + done, count - done, out);
            if (n <= 0) {
                // パス1 で読んだ範囲が読めない（実行の途中で旧キャッシュが書き換えられた）
                throw new EOFException(config.cacheFile + " @" + (start + done));
            }
            done += n;
            // 書いた先の位置を明示しておく（続きの行の書き出しと次の転送が、写した範囲の直後から始まるように）
            out.position(at + done);
        }
    }

    /** 旧キャッシュの {@code [start, end)} を行に戻し、空行を除いて {@code '\n'} で書き直す */
    private void rewriteLines(long start, long end, BufferedWriter cacheOut) throws IOException {
        try (CacheReader in = CacheReader.openAt(config.cacheFile, start)) {
            while (in.next() && in.lineStart() < end) {
                writeLine(cacheOut, in.line());
            }
        }
    }

    /**
     * 1 行を書く。行の区切りは OS によらず {@code '\n'}（{@code BufferedWriter.newLine()} は使わない。
     * どの OS で書いても同じバイト列になり、ブロックの検査値も OS によらず同じになる）
     */
    private static void writeLine(BufferedWriter w, String line) throws IOException {
        w.write(line);
        w.write('\n');
    }

    /**
     * 1ファイル分のブロックを書く。行の並びは {@link CacheFormat} のとおり。
     *
     * <p>ブロックはメモリ上で組んでから書く。記号表（S 行）は参照する行より前に置くが、記号の番号は
     * 参照する行を組みながら振るので先に書けないのと、F 行に書く検査値はブロックの残りの行が
     * 揃ってから決まるため。記号は行を書く順（R・D・O・C/U・M・A）に初めて現れた順に振る
     * （{@link SymbolTable}）。条件の表（G 行）も同じで、ガードの番号は C 行・U 行を組みながら、
     * 初めて使った順に振る（同じアトムの並びは同じ番号）。
     *
     * <p>new の証拠（{@link FileAnalysis#hints}）は行にせず、ここで呼び出し箇所に結びつけて C 行・U 行の
     * hints 列に書く（{@link #hintsByScope}）。
     */
    private static void writeBlock(FileAnalysis fa, BufferedWriter w) throws IOException {
        if (fa.callSites.size() != fa.callSiteValues.size()) {
            // 呼び出し箇所と値は同じ位置どうしで 1 行にする。数が違えば書き手の誤り
            throw new IllegalStateException(Messages.format("analysis.cache.valuesMismatch",
                    fa.relativePath, fa.callSites.size(), fa.callSiteValues.size()));
        }
        SymbolTable symbols = new SymbolTable();
        // 記号を参照する行を、記号を振る順（R・D・O・C/U・M・A）に先に組む。
        // 読み手（CallGraphBuilder）がメソッドを ID 化する順（R → D → O → C/U）もこれと同じ
        List<String> returns = new ArrayList<>(fa.returns.size());
        for (ReturnFact r : fa.returns) {
            returns.add(r.toRow(symbols));
        }
        List<String> declarations = new ArrayList<>(fa.declarations.size());
        for (MethodDeclFact d : fa.declarations) {
            declarations.add(d.toRow(symbols));
        }
        List<String> overrides = new ArrayList<>(fa.overrides.size());
        for (OverrideFact o : fa.overrides) {
            overrides.add(o.toRow(symbols));
        }
        // 呼び出し箇所（解決できたものも失敗したものも）はソース上の順のまま書く。
        // 読み手が import 推定の候補をエッジにしたとき、元の呼び出しの並びが保たれる。
        // 値（レシーバ・実引数・ガードの番号・new の証拠）は同じ位置の callSiteValues から作り、同じ行の末尾に書く
        Map<List<Guard.Atom>, Integer> guardIds = new HashMap<>();
        List<String> guards = new ArrayList<>();
        Map<String, String> hints = hintsByScope(fa);
        List<String> calls = new ArrayList<>(fa.callSites.size());
        for (int i = 0; i < fa.callSites.size(); i++) {
            CallSite site = fa.callSites.get(i);
            CallSiteValues v = fa.callSiteValues.get(i);
            int guard = guardIdOf(v.guard(), guardIds, guards);
            String hint = (site.caller() == null || v.recvKey().isEmpty())
                    ? "" : hints.getOrDefault(scopeOf(site.caller().key(), v.recvKey()), "");
            calls.add(site.toRow(symbols, new CallSiteValues.Row(v.recv(), v.args(), guard, hint)));
        }
        List<String> functionals = new ArrayList<>(fa.functionalImpls.size());
        for (FunctionalImplFact m : fa.functionalImpls) {
            functionals.add(m.toRow(symbols));
        }
        List<String> accesses = new ArrayList<>(fa.fieldAccesses.size());
        for (FieldAccessFact a : fa.fieldAccesses) {
            accesses.add(a.toRow(symbols));
        }

        List<String> body = new ArrayList<>();
        // I行はF行の直後に置く（差分更新で、ブロックを読み進める前に依存を判定するため）
        body.add(CacheFormat.joinRow("I", String.join(",", dependenciesOf(fa))));
        body.addAll(symbols.rows());
        // 値グラフ（N行）は番号順。参照する行（G・R・C/U・J 行）より前にあれば、読み手は 1 回で取り込める
        for (ValueNode n : fa.valueNodes) {
            body.add(n.toRow());
        }
        // 条件の表（G 行）は N 行の直後。subject はノードを指し、C 行・U 行はガードの番号で指す
        body.addAll(guards);
        // return は全部書く（追跡できないものも -1 として。読み手には U）。
        // 「追跡できない return が1つでもあれば戻り値は不定」という判定は読み手が行う。
        // D 行より前に置く（読み手はここでメソッドを ID 化する。以前の形式と同じ順にするため）
        body.addAll(returns);
        for (TypeFact t : fa.types) {
            body.add(t.toRow());
        }
        body.addAll(declarations);
        // O行はD行の直後。読み手は宣言をID化してから上書き関係を引くので、この順でなければならない
        body.addAll(overrides);
        for (FieldDeclFact v : fa.fieldDecls) {
            body.add(v.toRow());
        }
        body.addAll(calls);
        body.addAll(functionals);
        body.addAll(accesses);
        // K行は指紋の順に並べる。同じソースならいつ解析しても同じ並びになり、
        // 旧キャッシュとの突き合わせ（定数の連鎖）が並び順に振り回されない
        body.addAll(sortedConstantRows(fa));
        // フィールドへの代入は、同じブロックの V 行（フィールド宣言）と組で判定する（読み手はブロックの終わりで渡す）
        for (FieldAssignFact j : fa.fieldAssigns) {
            body.add(j.toRow());
        }

        BlockChecksum checksum = new BlockChecksum();
        for (String line : body) {
            checksum.add(line);
        }
        writeLine(w, CacheFormat.fileRow(fa.relativePath, fa.size, fa.errors, fa.hash, fa.syntaxErrors,
                fa.unresolvedCount(), checksum.hex()));
        for (String line : body) {
            writeLine(w, line);
        }
    }

    /**
     * 条件（アトムの並び）のガード番号。無ければ -1。初めて出てきた並びなら番号を振り、G 行を足す
     * （番号は 0 から詰めて、初めて使った順。1 つのガードのアトムは同じ番号で続けて並ぶ）
     */
    private static int guardIdOf(List<Guard.Atom> atoms, Map<List<Guard.Atom>, Integer> ids, List<String> rows) {
        if (atoms.isEmpty()) {
            return -1;
        }
        Integer known = ids.get(atoms);
        if (known != null) {
            return known;
        }
        int id = ids.size();
        ids.put(atoms, id);
        for (Guard.Atom atom : atoms) {
            rows.add(atom.toRow(id));
        }
        return id;
    }

    /**
     * new の証拠を「呼び出し元＋変数のキー」（{@link #scopeOf}）でまとめ、型の FQN をカンマ区切りにしたもの。
     *
     * <p>以前は X 行として書き、読み手がキャッシュ全体から同じ鍵で集めていた。変数のキー（バインディングキー）は
     * そのファイルの宣言を指すので、結びつけは同じファイルの中で閉じる。1 つだけ違うのは、同じメソッドキーが
     * 2 つのファイルにある（同じクラスが重複している）場合で、以前は両方のファイルの証拠が混ざっていたが、
     * 今はそれぞれのファイルの証拠がそれぞれのファイルの呼び出し箇所にだけ付く（その方が正しい）
     */
    private static Map<String, String> hintsByScope(FileAnalysis fa) {
        if (fa.hints.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<String>> types = new LinkedHashMap<>();
        for (HintFact h : fa.hints) {
            if (HintFact.KIND_NEW.equals(h.kind())) {
                types.computeIfAbsent(scopeOf(h.callerKey(), h.scopeKey()), k -> new LinkedHashSet<>())
                        .add(h.value());
            }
        }
        Map<String, String> joined = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : types.entrySet()) {
            joined.put(e.getKey(), String.join(",", e.getValue()));
        }
        return joined;
    }

    /** 証拠を引く鍵（呼び出し元のメソッドキーと変数のキー） */
    private static String scopeOf(String callerKey, String variableKey) {
        return callerKey + '\u0000' + variableKey;
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
