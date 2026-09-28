// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
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

import jche.analysis.CallEdgeExtractor.SourceFile;
import jche.cache.BlockChecksum;
import jche.cache.CacheFormat;
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
import jche.cache.MethodDeclFact;
import jche.cache.OverrideFact;
import jche.cache.ReturnFact;
import jche.cache.SymbolTable;
import jche.cache.TypeFact;
import jche.cache.ValueNode;
import jche.util.FileHash;
import jche.util.Messages;
import jche.util.Progress;
import jche.util.Warnings;

/**
 * 解析結果（{@link FileAnalysis}。ソースファイル 1 つ分の事実）を受け取って即座にキャッシュの 1 ブロックとして
 * 書き出し、件数と進捗を数える。1ファイル分だけをヒープに載せ、書き出したら即破棄する。
 *
 * <p>「事実をどの行にどう並べるか」（{@link #writeBlock}。R・H・D・O・V・C/U・M・A・K・J の順、記号表・ガードの
 * 番号の振り方、I 行の依存と宣言の指紋、F 行の検査値）もこのクラスが持つ。行の形式そのものは
 * {@link jche.cache.CacheFormat} と各 record の {@code toRow} が決め、ここはその並べ方と番号の振り方を決める。
 * ここを変えてブロックに書かれる内容が変われば、{@link CacheFormat#VERSION} を上げる。
 *
 * <p>差分更新（{@link CacheUpdater}）の中では、パス2（変わったファイル）とパス3・4（変わった型に触れるファイル）の
 * 解析結果の受け手として使われる。解析し直したファイルの型を「変わった型」（{@link StaleTypes}）に加えるかは
 * {@link ReanalysisCascade} で決める。
 */
final class BlockWriter implements CallEdgeExtractor.Sink {
    private final BufferedWriter cacheOut;
    private final CachePhaseResult result;
    private final Progress progress;
    /** 解析したファイルの内容ハッシュを求める（F行に書くため） */
    private final Function<SourceFile, String> hasher;
    /** 相対パス -> 旧キャッシュの自分の宣言の指紋（{@link ReanalysisCascade#WHEN_DECLARATIONS_CHANGED} の判定用） */
    private final Map<String, String> oldDeclarations;
    /**
     * 相対パス -> 旧キャッシュの型階層の指紋（{@link #hierarchyDigestOf}。今のソースにあるファイルの、型解決に失敗して
     * いなかったブロックすべて。有効でないブロックも）。解析し直した結果と違えば、差分更新をやめて残りを全件解析する
     * （{@link #hierarchyChanged}）
     */
    private final Map<String, String> oldHierarchy;
    /**
     * 解析したファイルの型階層（H 行の型の集合・親型・親クラスの連鎖・継承した実装）が旧キャッシュと違った、最初の
     * ファイル。null なら違っていない。型階層が変われば、選択（jche.graph.MethodSelection）の材料が変わり、
     * どのファイルの事実が影響を受けるかを I 行と部分型の索引から漏れなく決められる保証が無いので、
     * 差分更新をやめて残りのファイルをすべて解析し直す（docs/cache-unification-qa.md の Q131）
     */
    private String hierarchyChanged;
    /** 解析のあいだに中身が変わったファイル（{@link CacheUpdater#changedDuringRun}）を積む先 */
    private final Set<String> changedDuringRun;
    /** この実行で解析したファイル（{@link CacheUpdater#parsedThisRun}）を積む先 */
    private final Set<String> parsedThisRun;
    /** ファイルの置き場所のフォルダのパッケージ（{@link StaleTypes#packageOfUnit}。解析に失敗したファイルに使う） */
    private final Function<SourceFile, String> packageOfFile;
    /** 最後にブロックを書いたファイル（受け手の途中で失敗したときに、印のブロックを重ねて書かないため） */
    private String lastWritten;
    /** 書いた印のブロックの数（{@link #failed}。Z 行のブロック数に足す） */
    private long failedBlocks;
    /** 「変わった型」の集合。非nullのときだけ {@link #cascade} に従って型を加える（連鎖の判定にも使う） */
    StaleTypes stale;
    /** 解析したファイルが宣言する型を「変わった型」に加える条件 */
    ReanalysisCascade cascade = ReanalysisCascade.ALWAYS;
    /** 解析した理由。集計の内訳にだけ使う（UNTOUCHED は「自分が変わった・新規」。連鎖の判定には使わない） */
    ReanalysisReason countAs = ReanalysisReason.UNTOUCHED;
    /**
     * 旧キャッシュの I 行が、変わった jar のパッケージか中身の分からないパッケージ
     * （{@link StaleTypes#addOpaque}。解析に失敗したファイルの）に触れていたファイル（相対パス）。
     * どの理由で選ばれたか（{@link #countAs}）に依らず、解析し直したら宣言する型を「変わった型」に加える（Q84）。
     * 理由で決めると、同じファイルがソースの変化にも触れていたときに連鎖を落とす（Q89）
     */
    final Set<String> jarDriven = new HashSet<>();
    private long done;

    BlockWriter(BufferedWriter cacheOut, CachePhaseResult result, Progress progress,
                Function<SourceFile, String> hasher, Map<String, String> oldDeclarations,
                Map<String, String> oldHierarchy, Set<String> changedDuringRun, Set<String> parsedThisRun,
                Function<SourceFile, String> packageOfFile) {
        this.cacheOut = cacheOut;
        this.result = result;
        this.progress = progress;
        this.hasher = hasher;
        this.oldDeclarations = oldDeclarations;
        this.oldHierarchy = oldHierarchy;
        this.changedDuringRun = changedDuringRun;
        this.parsedThisRun = parsedThisRun;
        this.packageOfFile = packageOfFile;
    }

    /** 書いた印のブロックの数 */
    long failedBlocks() {
        return failedBlocks;
    }

    /**
     * 解析したファイルの型階層が旧キャッシュと違ったか（違った最初のファイルの相対パス。違っていなければ null）。
     * 立ったら、{@link CacheUpdater} は依存で選ぶのをやめて、残りの有効なブロックのファイルをすべて解析し直す
     */
    String hierarchyChanged() {
        return hierarchyChanged;
    }

    /**
     * 型階層の指紋。ブロックの H 行のうち、ほかのファイルから名前で参照できる型（無名・ローカルの型
     * {@code Main$1}・{@code Main$1Local} を除く）の、型・種別・親型・パッケージ・親クラスの連鎖・継承した実装を
     * 並べたハッシュ。アノテーションは入れない（階層ではない）。型が 1 つも無ければ空文字。
     * 旧キャッシュの H 行からも同じ関数で求める（{@link CacheUpdater#finishOldBlock}）
     */
    static String hierarchyDigestOf(List<TypeFact> types) {
        List<String> lines = new ArrayList<>(types.size());
        for (TypeFact t : types) {
            if (isLocalOrAnonymous(t.typeFqn())) {
                continue;
            }
            lines.add(new TypeFact(t.typeFqn(), t.kind(), t.superTypes(), t.pkg(), "", t.superclasses(),
                    t.inheritedImpls()).toRow());
        }
        return digestOf(lines);
    }

    /** 無名クラス・ローカルクラスの名前か（{@code $} の直後が数字。JDT の 2 進の名前の決まり） */
    private static boolean isLocalOrAnonymous(String typeFqn) {
        for (int i = typeFqn.indexOf('$'); i >= 0; i = typeFqn.indexOf('$', i + 1)) {
            if (i + 1 < typeFqn.length() && Character.isDigit(typeFqn.charAt(i + 1))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析したファイルが宣言する型を「変わった型」に加えるか。
     * パス4 では、旧キャッシュでそのファイルが変わった jar のパッケージに触れていたとき（{@link #jarDriven}）と、
     * sealed な型かアノテーション型を宣言しているとき（{@link FileAnalysis#cascadesWhenReanalysed}）と、
     * 自分の宣言の指紋が旧キャッシュと違うときだけ加える。
     * このファイルの旧キャッシュと解析結果だけで決まり、ほかのファイルをどの順に解析し直したか
     * （「変わった型」がその時点で何を含むか）にも、どの理由で選ばれたかにも依らない
     */
    private boolean shouldCascade(SourceFile file, FileAnalysis fa) {
        return cascade == ReanalysisCascade.ALWAYS || jarDriven.contains(file.relativePath())
                || fa.cascadesWhenReanalysed
                || !oldDeclarations.getOrDefault(file.relativePath(), "").equals(declarationsDigestOf(fa));
    }

    @Override
    public void accept(SourceFile file, FileAnalysis fa) throws IOException {
        parsedThisRun.add(file.relativePath());
        fa.hash = hashAfterParse(file);
        // writeBlock はブロックをメモリ上で組み終えてから書くので、途中で例外が出ても書きかけは残らない
        // （例外は呼び出し元がこのファイルの失敗として数える）。書けたら直後に数え、Z 行の数と揃える
        writeBlock(fa, cacheOut);
        lastWritten = file.relativePath();
        result.parsed++;
        result.unresolved += fa.unresolvedCount();
        result.countErrors(file.relativePath(), fa.errors, fa.syntaxErrors);
        if (fa.syntaxErrors > 0) {
            // 本体を読めていないので、このファイルの呼び出しは出力に出ない。黙って落とさない
            Warnings.warn(Warnings.Topic.BUILD,
                    Messages.format("analysis.syntaxError", file.relativePath(), fa.syntaxErrors));
        }
        countReason();
        if (stale != null) {
            // 今回の親型の関係も部分型の索引に足す（親型の連鎖）。パッケージは今のソースのもの
            for (TypeFact t : fa.types) {
                stale.register(t);
                stale.packageNow(t.pkg());
            }
            // 型階層が旧キャッシュと違えば、差分更新をやめる印（旧キャッシュに無いファイル＝新しいファイルと、型解決に
            // 失敗していた・しているファイルは比べない。CacheUpdater の「型階層が変わったとき」）
            String before = oldHierarchy.get(file.relativePath());
            if (hierarchyChanged == null && before != null && !fa.resolutionFailed()
                    && !before.equals(hierarchyDigestOf(fa.types))) {
                hierarchyChanged = file.relativePath();
            }
        }
        if (stale != null && shouldCascade(file, fa)) {
            for (TypeFact t : fa.types) {
                if (cascade == ReanalysisCascade.ALWAYS && countAs == ReanalysisReason.UNTOUCHED) {
                    // ファイル自身が変わった・増えた。新しい型かも見る（jar の追加で解析し直すファイルは
                    // 中身が変わっていないので、宣言する型も前回と同じ）
                    stale.addDeclared(t.typeFqn(), t.pkg());
                } else {
                    stale.add(t.typeFqn(), t.pkg());
                }
            }
        }
        progress.step(++done);
    }

    /**
     * F 行に書く内容ハッシュ。解析の前（パス1 で求めたもの）と、JDT が読み終えた今とで中身が同じなら、そのハッシュ。
     * 違えば空文字（JDT が読んだのが前と後のどちらの中身か分からない。空のハッシュはどの中身とも一致しないので、
     * 次の実行で必ず解析し直す）。前のハッシュのまま書くと、解析のあいだに書き換えて元に戻したファイルが、
     * 書き換えた中身の事実のまま再利用され続ける（クラスの説明「実行中に書き換えられたソース」）
     */
    private String hashAfterParse(SourceFile file) {
        String before = hasher.apply(file);
        String now;
        try {
            now = FileHash.of(file.path());
        } catch (IOException e) {
            now = "";
        }
        if (!before.isEmpty() && before.equals(now)) {
            return before;
        }
        if (!before.isEmpty()) {
            changedDuringRun.add(file.relativePath());
        }
        return "";
    }

    /**
     * 解析に失敗したファイル（JDT のスタックが溢れた・受け手の失敗。docs/cache-unification-qa.md の Q62・Q69）。
     *
     * <p>事実は書けないが、そのファイルの型は JDT がソースパスから読むので、ほかのファイルの解決には効いている。
     * 以前は何も残さなかったので、そのファイルを書き換えても・消しても、その型を使うファイルを解析し直さなかった。
     * <ul>
     *   <li>印のブロック（F 行と空の I 行だけ。内容ハッシュは空で、次の実行でも必ず解析し直す）を書く。消したときに、旧キャッシュに
     *       ファイルがあったことが分かる（パス1 は型を宣言しないブロックの置き場所のパッケージを中身の分からない
     *       パッケージにする。{@link CacheUpdater#finishOldBlock}）。ブロックを書いたあとの受け手の失敗なら書かない</li>
     *   <li>置き場所のパッケージを中身の分からないパッケージにする（{@link StaleTypes#addOpaque}）。宣言する型は
     *       分からないので、変わった jar のパッケージと同じ決まりで、そのパッケージの型を使うファイルを解析し直す。
     *       失敗が続くあいだは実行のたびに解析し直す（安全側の費用）</li>
     * </ul>
     */
    @Override
    public void failed(SourceFile file, Exception error) {
        result.failed++;
        countReason();
        Warnings.warn(Warnings.Topic.INCOMPLETE,
                Messages.format("analysis.fileFailed", file.relativePath(), error.getMessage()));
        String rel = file.relativePath();
        parsedThisRun.add(rel);
        if (!rel.equals(lastWritten)) {
            // F 行と空の I 行（F 行の直後は必ず I 行。CacheFormat）。検査値は writeBlock と同じ求め方
            String deps = CacheFormat.joinRow("I", "", "");
            BlockChecksum checksum = new BlockChecksum();
            checksum.addWithoutLastColumn(CacheFormat.fileRow(rel, file.size(), 0, "", 0, 0, ""));
            checksum.add(deps);
            try {
                CacheUpdater.writeLine(cacheOut, CacheFormat.fileRow(rel, file.size(), 0, "", 0, 0, checksum.hex()));
                CacheUpdater.writeLine(cacheOut, deps);
            } catch (IOException e) {
                throw new UncheckedIOException(e);   // 書けなければ実行ごと失敗させる（解析の呼び出し元が戻す）
            }
            failedBlocks++;
        }
        if (stale != null && !CacheUpdater.declaresNoType(rel)) {
            stale.addOpaque(packageOfFile.apply(file));
        }
        progress.step(++done);
    }

    private void countReason() {
        if (countAs == ReanalysisReason.BY_SOURCE) {
            result.dependents++;
        } else if (countAs == ReanalysisReason.BY_LIBRARY) {
            result.libraryDependents++;
        }
    }

    /** 再利用したぶんを進捗に足す */
    void skipped(long count) {
        done += count;
        progress.step(done);
    }

    // --- 1 ファイル分の解析結果（FileAnalysis）をブロックの行にする ---

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
        // I行はF行の直後に置く（差分更新で、ブロックを読み進める前に依存を判定するため）。
        // 依存・自分の宣言の指紋の 2 列
        body.add(CacheFormat.joinRow("I", String.join(",", dependenciesOf(fa)), declarationsDigestOf(fa)));
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
        // 旧キャッシュとの突き合わせ（宣言の連鎖）が並び順に振り回されない
        body.addAll(sortedConstantRows(fa));
        // フィールドへの代入は、同じブロックの V 行（フィールド宣言）と組で判定する（読み手はブロックの終わりで渡す）
        for (FieldAssignFact j : fa.fieldAssigns) {
            body.add(j.toRow());
        }

        // 検査値は F 行（crc 列を空にした形）から始める。F 行の件数も守る（CacheFormat の「ブロックの検査値」）
        BlockChecksum checksum = new BlockChecksum();
        checksum.addWithoutLastColumn(CacheFormat.fileRow(fa.relativePath, fa.size, fa.errors, fa.hash,
                fa.syntaxErrors, fa.unresolvedCount(), ""));
        for (String line : body) {
            checksum.add(line);
        }
        CacheUpdater.writeLine(w, CacheFormat.fileRow(fa.relativePath, fa.size, fa.errors, fa.hash, fa.syntaxErrors,
                fa.unresolvedCount(), checksum.hex()));
        for (String line : body) {
            CacheUpdater.writeLine(w, line);
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
     * 自分の宣言の指紋（I 行の指紋の列）。宣言の鍵と修飾子（{@link FileAnalysis#declarationKeys}）と、宣言している
     * 定数の値（K 行の指紋）を並べてハッシュにしたもの。どちらも無ければ空文字。
     *
     * 旧キャッシュの同じ列（{@link #oldDeclarations}）と突き合わせて、「宣言か定数の値が変わったか」だけを見る
     * （{@link ReanalysisCascade#WHEN_DECLARATIONS_CHANGED}）。中身をヒープに持たないよう、ファイルごとに
     * ハッシュ1つ（16文字）だけ覚える
     */
    private static String declarationsDigestOf(FileAnalysis fa) {
        List<String> lines = new ArrayList<>(fa.declarationKeys.size() + fa.constants.size());
        lines.addAll(fa.declarationKeys);
        for (ConstantFact k : fa.constants) {
            lines.add("K " + k.fingerprint());
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
