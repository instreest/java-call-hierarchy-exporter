// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FileASTRequestor;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleType;

import jche.cache.FileAnalysis;
import jche.config.Config;
import jche.config.ProjectLayout;
import jche.util.Log;
import jche.util.Messages;

/**
 * ソースをASTパースし、宣言・呼び出し・フィールド・出所などの事実を抽出する。
 *
 * ワークスペースを使わない「スタンドアロンモード」で動かすため、
 * ASTParser.setEnvironment() にソースパスとクラスパスを明示的に渡す。
 *
 * <h2>一括パース</h2>
 * ファイルごとに ASTParser を作って createAST すると、JDT が呼び出しのたびにクラスパスと
 * ソースパスの名前環境を組み直し、参照先のソースも読み直すため、1ファイルあたりの時間が
 * プロジェクト規模に比例して増える（800 ファイルの実測で、一括に比べて約 14 倍）。
 * そのため {@link #BATCH_SIZE} 件ずつ {@code createASTs} でまとめてパースし、
 * JDT がバッチのファイルをすべて解決し終えたところで、1ファイル分ずつ事実を集めて {@link Sink} へ渡す
 * （{@link #analyzeBatch}）。AST は集め終えたら捨てるので、ヒープに載るのは1バッチ分に留まる。
 */
public final class CallEdgeExtractor {

    /** 一括でパースするファイル数。進捗ログの間隔もこれに揃える */
    public static final int BATCH_SIZE = 100;

    /**
     * 解析対象のソースファイル1件。
     *
     * 差分更新の同一性は「相対パス・サイズ・内容ハッシュ」で見る。更新時刻は持たない
     * （中身と関係なく変わるため。{@link jche.analysis.CacheUpdater} 参照）。
     *
     * @param path         実体のパス
     * @param relativePath project.root からの相対パス（キャッシュのキー・出力の file 列）
     * @param size         サイズ（同一性の判定の一次ふるい。違えば中身も違う）
     */
    public record SourceFile(Path path, String relativePath, long size) {
    }

    /** 解析結果の受け手。1ファイル分ずつ渡すので、受け手は書き出したら捨てられる */
    public interface Sink {
        /**
         * 解析できた1ファイル。IOException はキャッシュへの書き込み失敗で、解析全体を止める。
         * RuntimeException（書き手の誤りなど）はそのファイルの失敗として {@link #failed} に回すので、
         * 受け手は失敗したときに書きかけを残さないこと
         */
        void accept(SourceFile file, FileAnalysis analysis) throws IOException;

        /** 解析に失敗した1ファイル（読み飛ばして続行する） */
        void failed(SourceFile file, Exception error);
    }

    private final ProjectLayout layout;
    private final String encodingName;
    private final Map<String, String> compilerOptions;
    private final String[] classpath;
    private final String[] sourcepath;
    private final String[] sourcepathEncodings;

    /** 判定できない条件も guard に残すか（条件の調査用。{@link CallConditionScanner}） */
    private final boolean recordAllConditions;

    /** どのバッチにも添えるファイルなどの材料（{@link #prepare}）。呼ばれていなければ空（何も添えない） */
    private ProjectScan project = ProjectScan.EMPTY;

    public CallEdgeExtractor(ProjectLayout layout, Config config) {
        this(layout, config, false);
    }

    /**
     * @param recordAllConditions 判定できない条件も呼び出しの guard に残す。
     *                            キャッシュには書かない使い方（設定ファイルの conditions.target）でだけ true にする
     */
    public CallEdgeExtractor(ProjectLayout layout, Config config, boolean recordAllConditions) {
        this.recordAllConditions = recordAllConditions;
        this.layout = layout;
        this.encodingName = config.sourceEncoding;
        // 準拠レベル（source.level）は Config が解決済み。
        // 既定のまま使うと generics・diamond演算子・ラムダ式・enum等が
        // 軒並み構文/型解決に失敗するので、必ずこちらを使うこと
        this.compilerOptions = config.compilerOptions;
        this.classpath = layout.classpathArray();
        this.sourcepath = layout.sourcePathArray();
        this.sourcepathEncodings = new String[sourcepath.length];
        Arrays.fill(this.sourcepathEncodings, config.sourceEncoding);
    }

    /**
     * 解析するソースの全体を構文だけで読み（型は解決しない。メソッドの本体も読まない）、どのバッチにも添えるファイルと、
     * パッケージの宣言がフォルダと合わないファイルを決める（{@link ProjectScan}）。{@link #analyzeBatch} より前に 1 回呼ぶ。
     *
     * <p>全件解析でも差分更新でも、解析するファイルだけでなくソースの全体を渡す。添えるファイルがソースの中身だけで
     * 決まり、どの実行でも同じになるようにするため。読めなかったファイル（JDT の例外・スタックの溢れ）は材料にしない
     * （添えない・警告しない）。呼ばなければ何も添えない。
     *
     * @param all 解析するソースの全体（ソース一覧の並び）
     */
    public ProjectScan prepare(List<SourceFile> all) {
        Map<String, ProjectScan.Info> infos = new HashMap<>();
        Map<String, SourceFile> pending = byPath(all);
        // 構文だけの読み取りは速い（1 ファイル 1 ミリ秒を切る）が、javadoc の中までは読まない
        Map<String, String> options = new HashMap<>(compilerOptions);
        options.put(JavaCore.COMPILER_DOC_COMMENT_SUPPORT, JavaCore.DISABLED);
        while (!pending.isEmpty()) {
            String[] paths = pending.keySet().toArray(new String[0]);
            String[] fileEncodings = new String[paths.length];
            Arrays.fill(fileEncodings, encodingName);
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setKind(ASTParser.K_COMPILATION_UNIT);
            parser.setCompilerOptions(options);
            parser.setResolveBindings(false);
            parser.setIgnoreMethodBodies(true);
            try {
                parser.createASTs(paths, fileEncodings, new String[0], new FileASTRequestor() {
                    @Override
                    public void acceptAST(String sourceFilePath, CompilationUnit cu) {
                        SourceFile file = pending.remove(sourceFilePath);
                        if (file != null) {
                            infos.put(file.relativePath(), infoOf(cu));
                        }
                    }
                }, null);
            } catch (RuntimeException | StackOverflowError e) {
                // 読めなかったファイルは材料にしない（下で外して続ける）
            }
            if (!pending.isEmpty()) {
                // JDT は渡した順に読む。受け取れなかった先頭のファイルで止まったので、それを外して残りを読む
                pending.remove(pending.keySet().iterator().next());
            }
        }
        project = ProjectScan.of(all, layout, infos);
        return project;
    }

    /** 構文だけで読んだ 1 ファイルの、パッケージとトップレベルの型の名前 */
    private static ProjectScan.Info infoOf(CompilationUnit cu) {
        String pkg = (cu.getPackage() == null) ? "" : cu.getPackage().getName().getFullyQualifiedName();
        List<String> types = new ArrayList<>();
        for (Object t : cu.types()) {
            types.add(((AbstractTypeDeclaration) t).getName().getIdentifier());
        }
        return new ProjectScan.Info(pkg, List.copyOf(types));
    }

    /**
     * 複数のファイルをまとめてパースし、1ファイル分ずつ sink へ渡す。
     *
     * <h4>どのファイルといっしょに JDT に渡すか</h4>
     * 事実がバッチの組み方（全件解析は {@link #BATCH_SIZE} 件ずつ、差分更新は変わったファイルだけ）に依らないよう、
     * 次のファイルを「添えるファイル」として解析するファイルの後ろに並べて渡す（事実は書かない）。
     * <ul>
     *   <li>名前の違うファイルで宣言したトップレベルの型を持つファイル（{@link ProjectScan#context}。{@link #prepare}）</li>
     *   <li>jar の型が参照していた、ソースの入れ子の型（{@code app.Outer$Inner}）を宣言するファイル。JDT は jar の
     *       クラスファイルから {@code app/Outer$Inner} という名前で型を探し、ソースパスからは見つけられない
     *       （{@code app.Outer} をすでに読んでいれば、その入れ子の型として見つかる）。見つからないと、その型の名前が
     *       {@code $} のまま事実（I 行）に残るので、それを見て、宣言するファイル（{@code app/Outer.java}）を添えて
     *       解析し直す（{@link #memberTypeFilesOf}）。同じファイルを同じバッチで解析していれば初めから見つかるので、
     *       どちらでも同じ事実になる</li>
     * </ul>
     * JDT は渡した順にファイルを解析して 1 つずつ返すので、解析するファイルを受け取り終えたら止める
     * （添えたファイルは型を作るところまでで、本体は読まない）。
     *
     * <p>{@code module-info.java} は、ほかのファイルと同じバッチに入れない。JDT はソースのモジュール宣言を、
     * それが同じバッチにいるときだけ知っているので、{@code import module app;}（JEP 511）がエラーになるかどうかが
     * バッチの組み方で変わった。いつも別にしておけば、ほかのファイルの事実は module-info.java に依らない
     * （ソースのモジュールの import はいつも解決できない。JDT は同じバッチにいてもその import から型を解決しない）。
     *
     * <h4>事実は、解析するファイルをすべて JDT が解決し終えてから集める</h4>
     * JDT は {@code createASTs} のファイルを順に 1 つずつ解決し（本体まで）、そのたびに AST を {@code acceptAST} で渡す。
     * 事実を集めるときのバインディングへの問い合わせ（型の全メソッド・注釈の既定値など）は、同じバッチで<b>まだ解決して
     * いない後ろのファイル</b>の型にも及び、JDT はそれを、どのファイルのコンパイルの中でもない状態で先に解決する。
     * 後ろのファイルのメソッドの引数に jar の型が参照している無いクラスが現れると、JDT は解決を打ち切ってそのメソッドを
     * 引数の無いまま残し、そのファイルの番で JDT の中の例外になった。注釈の型のメンバーの既定値を先に解決すると、その
     * ファイルの番でもう一度解決され、同じエラーが 2 回数えられた（docs/cache-unification-qa.md の「後ろのファイルの型を
     * 先に解決させない」）。そこで {@code acceptAST} では AST を預かるだけにし、解析するファイルの最後を受け取ったとき
     * （JDT が解析するファイルをすべて解決し終えたとき）に、預かった分の事実をまとめて集める（{@link Batch#parse}）。
     * 預かっても、JDT は同じバッチのバインディングの表から前のファイルの AST を最後まで辿れる形で持ち続けているので、
     * ヒープはほとんど増えない。{@code createASTs} から戻ったあとでは、ソースパスから読んだ型への問い合わせが働かない
     * ので、最後のファイルの {@code acceptAST} の中で集める。
     *
     * <p>添えるファイルは、この決まりを崩さない。添えるファイルは解析するファイルの後ろに並べ、解析するファイルの最後を
     * 受け取った直後に JDT を止める（JDT は {@code acceptAST} から戻ったところで取り消しを見る）ので、JDT が添えたファイルを
     * 解決することはない。事実を集めるときの問い合わせが添えたファイルの型に及んでも、それは解析するファイルがすべて
     * 解決し終わったあとで、そのあと JDT がそのファイルを解決し直すこと（2 回数え・引数の無いメソッドでの例外）は無い。
     * 添えたファイルの事実は集めない（エラーも数えない）。
     *
     * <h4>JDT が途中で止まったとき</h4>
     * JDT は、1 つのファイルの解析で例外（スタックの溢れ・JDT の内部の誤り）を出すと、そのバッチの残りを返さない。
     * 例外を出さずに残りを返さずに戻ることもある（依存 jar に無いクラスを、ソースパスから読んだ型の中で参照して
     * いると、JDT は解析をまるごと打ち切る）。どちらのときも、それまでに受け取ったファイルの事実はまだ集めていない
     * （上の決まり）ので、それらも解析し直す。どう解析し直すかは {@link Batch#analyzeAll}。以前は残りを 1 ファイルずつ
     * 自前で読み直しており、読み方（文字コードの誤りの扱い・BOM・コンパイル単位の名前）も組の扱いもバッチと違い、
     * 例外の無い打ち切りでは何も言わずに残りを 1 ファイルずつにしていた。今は解析し直すファイルも、1 つだけで解析する
     * ファイルも、同じ {@code createASTs} で読む。
     *
     * <p>スタックの溢れ（{@link StackOverflowError}。メソッド呼び出しを数千段つないだ式のように、JDT の再帰が
     * 深くなりすぎるファイル）も、そのファイルの失敗として扱う（{@code docs/cache-unification-qa.md} の Q62）。
     * 溢れたスタックは例外が外へ抜けるあいだに戻るので、捕まえたあとは続けられる。
     */
    public void analyzeBatch(List<SourceFile> files, Sink sink) throws IOException {
        List<SourceFile> modules = new ArrayList<>();
        List<SourceFile> others = new ArrayList<>();
        for (SourceFile file : files) {
            (ProjectScan.isModuleInfo(file) ? modules : others).add(file);
        }
        new Batch(sink, true).run(others, false);
        new Batch(sink, false).run(modules, false);
    }

    /**
     * 1 ファイルだけを解析して sink へ渡す。一括パースで脇に置いたファイルを最後に解析するのと同じ経路
     * （1 ファイルの {@code createASTs}。添えるファイルは付ける）。test/incremental の SinkOverflowCheck も直接呼ぶ
     */
    void analyzeAlone(SourceFile file, Sink sink) throws IOException {
        new Batch(sink, !ProjectScan.isModuleInfo(file)).run(List.of(file), true);
    }

    /** 相対パスではなく、JDT に渡す絶対パスの文字列を鍵にした一覧（並びはそのまま） */
    private static Map<String, SourceFile> byPath(List<SourceFile> files) {
        Map<String, SourceFile> map = new LinkedHashMap<>();
        for (SourceFile file : files) {
            map.put(file.path().toString(), file);
        }
        return map;
    }

    /** 1 回の {@link #analyzeBatch} の中の状態 */
    private final class Batch {
        private final Sink sink;
        /** {@link ProjectScan#context} を添えるか。module-info.java だけのバッチでは添えない */
        private final boolean withContext;
        /** jar の型が参照していたソースの入れ子の型を宣言するファイル（{@link #memberTypeFilesOf}）。増えるだけ */
        private final List<SourceFile> memberTypeFiles = new ArrayList<>();
        /**
         * JDT が止まったファイルに関わるファイル（{@link #relatedFiles}。同じフォルダのファイルと、名前を書いた型のファイル）。
         * 止まってから後の解析（同じバッチの残りも）にずっと添える。増えるだけ
         */
        private final List<SourceFile> stopContext = new ArrayList<>();

        Batch(Sink sink, boolean withContext) {
            this.sink = sink;
            this.withContext = withContext;
        }

        /**
         * @param aloneOnly どのファイルも 1 つだけで解析する（{@link #analyzeAlone}）
         */
        void run(List<SourceFile> files, boolean aloneOnly) throws IOException {
            List<SourceFile> todo = files;
            while (!todo.isEmpty()) {
                Map<SourceFile, List<SourceFile>> deferred = new LinkedHashMap<>();
                if (aloneOnly) {
                    for (SourceFile f : todo) {
                        alone(f, null, deferred);
                    }
                } else {
                    analyzeAll(todo, deferred);
                }
                // 足りなかったファイルを添えて、受け取らずにおいたファイルを解析し直す。受け取らずにおくのは、添えた
                // ファイルに無いものが要るときだけなので、添えるファイルは周回ごとに増え、いつかは終わる
                for (List<SourceFile> needs : deferred.values()) {
                    for (SourceFile f : needs) {
                        if (!memberTypeFiles.contains(f)) {
                            memberTypeFiles.add(f);
                        }
                    }
                }
                todo = new ArrayList<>(deferred.keySet());
            }
        }

        /**
         * {@code files} をまとめて解析する。JDT が途中で止まったら、止まった 1 回ごとに次のどれか 1 つをして、
         * 解析し直すバッチを作業の列に積む（{@code work}。元の並びで、前のものから）。
         * <ol>
         *   <li><b>関わるファイルを添える</b>（スタックの溢れのときは除く）。止まったファイル（受け取れなかった最初の
         *       ファイルと、その同じ名前の組。{@link SameUnitFiles}）に関わるファイル（{@link #relatedFiles}）のうち、
         *       まだ JDT に渡していないものがあれば、それを以後ずっと添えて（{@link #stopContext}）解析し直す。
         *       例外の無い打ち切りは、ソースパスから読んだ型の中の、依存 jar に無いクラスで起き、その型を同じバッチに
         *       入れれば起きない。その型はたいてい止まったファイルと同じパッケージか、名前を書いた型なので、添えれば
         *       全件解析で同じバッチにいたときと同じ事実になる。添えたものはバッチの残りにも添えるので、同じ原因で
         *       多くのファイルが止まっても、止まるのは 1 回で済む。スタックの溢れはファイルの深さで起き、添えても
         *       直らないので、この段を飛ばす</li>
         *   <li><b>最初の組だけで試し、止まらなければ半分に分ける</b>（まだ 1 つも受け取っていず、組が 2 つ以上あるとき）。
         *       JDT は本体を解決する前に、バッチの全ファイルの構文解析・型の束縛・親型のつなぎを済ませる（深く入れ子に
         *       した型の宣言はそこで溢れる）ので、1 つも受け取っていなければ、どのファイルで止まったかは分からない。
         *       ふつうは最初のファイルの本体の解決で止まっている（深い式・打ち切り）ので、最初の組だけを 1 回の
         *       {@code createASTs} で試す（{@link #stopsAlone}。事実は集めない）。それでも止まれば、その組が原因なので
         *       次の「脇に置く」に進む（残りは元のまま 1 つのバッチで続く）。止まらなければ原因はバッチの後ろにあるので、
         *       前後の半分に分けて（同じ名前の組は分けない）それぞれ解析し直す。最初のファイルを確かめずに外すと、原因が
         *       バッチの後ろにあるとき、前から 1 つずつ関係の無いファイルを外しながらバッチ全体を何十回も解析し直し、
         *       関係の無いファイルの名前を挙げることになる。半分に分ければ log2(件数) 段で原因のファイルに行き着き、
         *       原因でないファイルはまとめて解析される。はじめから半分に分けないのは、分けるとバッチの大きさ（全件解析と
         *       差分更新とで違う）で JDT の呼び出しの組み方が変わり、呼び出しの組み方に依ることが分かっている事実
         *       （見つからないクラスのエラーが最初に出会ったファイルにだけ付く。docs/cache-unification-qa.md の
         *       「打ち切ったファイルの失敗の理由」の Q の残るもの）が、ふつうの形（最初のファイルが原因）でまで
         *       全件解析と差分更新とで食い違うため</li>
         *   <li><b>止まったファイルを脇に置く</b>（1 つでも受け取ったあと、組が 1 つだけのとき、または最初の組だけでも
         *       止まったとき）。JDT は準備を済ませて渡した順に解決しているので、受け取れなかった最初のファイルで止まって
         *       いる。その組を脇に置き、
         *       最後に 1 つずつ（添えるファイル {@link #stopContext} は付けずに）解析する（{@link #alone}）。
         *       それでも受け取れなければ失敗として数える（{@link Sink#failed}。warnings.txt の「打ち切られた」に載る）</li>
         * </ol>
         * どの場合も、止まる前に受け取ったファイル（事実はまだ集めていない）は、止まったファイルからあとに同じ名前の組の
         * もう片方がいるものを除いて、それだけで 1 つのバッチとして解析し直し、止まったファイルからあとは別のバッチにする。
         * 一度 JDT が最後まで解決できたファイルの並びなので、ふつうはそのまま通る。止まったところで分けずに全部をまとめ
         * 直すと、止まるファイルがいくつもあるバッチでは、前のほうのファイルを止まるたびに解析し直すことになる（止まる数 ×
         * バッチの大きさ）。分けておけば、どのファイルも解析し直すのは高々数回で済む。
         *
         * <p>繰り返しは終わる。添えるたびに {@link #stopContext} が増え（ソースのファイルの数で頭打ち）、
         * それ以外のときは作業の列に積むバッチが元より小さい（分けた・脇に置いた）。
         */
        private void analyzeAll(List<SourceFile> files, Map<SourceFile, List<SourceFile>> deferred)
                throws IOException {
            Deque<List<SourceFile>> work = new ArrayDeque<>();
            work.add(files);
            Map<SourceFile, Throwable> aside = new LinkedHashMap<>();
            while (!work.isEmpty()) {
                List<SourceFile> todo = work.poll();
                Map<String, SourceFile> pending = byPath(todo);
                List<SourceFile> finished = new ArrayList<>();
                Throwable stop = parse(pending, stopContext, deferred, finished);
                if (pending.isEmpty()) {
                    continue;   // 事実を集めて渡し終えた
                }
                List<SourceFile> unit = firstUnit(pending);
                String at = unit.get(0).relativePath();
                // 1 つも受け取らないうちに止まり、組が 2 つ以上あれば、どのファイルで止まったかは分からない
                List<List<SourceFile>> halves = finished.isEmpty() ? halves(todo) : List.of(todo);
                boolean unknown = halves.size() == 2;
                logStop(stop, at, unknown, todo.size());

                // 1. 関わるファイルを添える
                if (!(stop instanceof StackOverflowError) && addRelated(unit, todo)) {
                    Log.info(Messages.format("analysis.batchRetry", todo.size(), at));
                    splitAt(todo, finished, pending, List.of(), at, work);
                    continue;
                }
                // 2. どのファイルで止まったか分からなければ、最初の組だけで試し、止まらなければ半分に分ける
                if (unknown && !stopsAlone(unit)) {
                    Log.info(Messages.format("analysis.batchSplit", todo.size()));
                    work.push(halves.get(1));
                    work.push(halves.get(0));
                    continue;
                }
                // 3. 止まったファイル（の組）を脇に置き、最後に 1 つだけで解析する
                for (SourceFile f : unit) {
                    aside.put(f, stop);
                }
                Log.info(Messages.format("analysis.batchSetAside", at, todo.size() - unit.size()));
                splitAt(todo, finished, pending, unit, at, work);
            }
            for (Map.Entry<SourceFile, Throwable> e : aside.entrySet()) {
                alone(e.getKey(), e.getValue(), deferred);
            }
        }

        /**
         * 止まったことをログに書く。どのファイルで止まったか分からない（1 つも受け取らないうちに止まり、組が 2 つ以上
         * ある）なら、関係の無いファイルを原因のように挙げないよう、名前を挙げない
         */
        private void logStop(Throwable stop, String at, boolean unknown, int size) {
            if (stop instanceof StackOverflowError) {
                Log.info(unknown ? Messages.format("analysis.batchTooDeepEarly", size)
                        : Messages.format("analysis.batchTooDeep", at));
            } else if (stop != null) {
                Log.info(unknown ? Messages.format("analysis.batchFailedEarly", size, stop)
                        : Messages.format("analysis.batchFailed", at, stop));
            } else {
                Log.info(unknown ? Messages.format("analysis.batchStoppedEarly", size)
                        : Messages.format("analysis.batchStopped", at));
            }
        }

        /**
         * 止まったファイルの組（{@code unit}）に関わるファイルを {@link #stopContext} に足す。今回 JDT に渡して
         * いなかったもの（{@code todo} に無いもの）が 1 つでも増えたら true（添えて解析し直すと結果が変わりうる）。
         * {@code todo} の中のファイルも足しておく（止まったところで分けたあとのバッチでは、渡していないファイルになる）。
         * module-info.java だけのバッチには何も添えない（analyzeBatch。ほかのファイルと同じ呼び出しに入れない）
         */
        private boolean addRelated(List<SourceFile> unit, List<SourceFile> todo) {
            if (!withContext) {
                return false;
            }
            Set<String> given = byPath(todo).keySet();
            boolean added = false;
            for (SourceFile f : unit) {
                for (SourceFile r : relatedFiles(f)) {
                    if (!unit.contains(r) && !stopContext.contains(r)) {
                        stopContext.add(r);
                        added |= !given.contains(r.path().toString());
                    }
                }
            }
            return added;
        }

        /**
         * 止まる前に受け取ったファイル（事実はまだ集めていない）を 1 つのバッチに、止まったファイルからあと
         * （{@code aside} を除く）をもう 1 つのバッチにして、作業の列に積む（前のものから解析する）。
         * 同じ名前の組は分けない（受け取ったファイルでも、組のもう片方がまだなら、あとのバッチに入れる）。
         * 受け取ったファイルが無ければ、残り全部を 1 つのバッチにする
         */
        private void splitAt(List<SourceFile> todo, List<SourceFile> finished, Map<String, SourceFile> pending,
                             List<SourceFile> aside, String at, Deque<List<SourceFile>> work) {
            Set<String> pendingUnits = new HashSet<>();
            for (SourceFile f : pending.values()) {
                pendingUnits.add(layout.unitNameOf(f.path()));
            }
            Set<SourceFile> before = new LinkedHashSet<>();
            for (SourceFile f : finished) {
                if (!pendingUnits.contains(layout.unitNameOf(f.path()))) {
                    before.add(f);
                }
            }
            List<SourceFile> after = new ArrayList<>();
            for (SourceFile f : todo) {
                if (!before.contains(f) && !aside.contains(f)) {
                    after.add(f);
                }
            }
            if (!after.isEmpty()) {
                work.push(after);
            }
            if (!before.isEmpty()) {
                Log.info(Messages.format("analysis.batchRedoFinished", before.size(), at));
                work.push(new ArrayList<>(before));
            }
        }

        /**
         * バッチを前後の半分に分ける（元の並びのまま。同じ名前の組は分けない）。組が 1 つしか無ければ分けられないので、
         * 元のバッチ 1 つだけを返す
         */
        private List<List<SourceFile>> halves(List<SourceFile> files) {
            Map<String, Integer> group = new LinkedHashMap<>();
            for (SourceFile f : files) {
                group.putIfAbsent(layout.unitNameOf(f.path()), group.size());
            }
            if (group.size() < 2) {
                return List.of(files);
            }
            int mid = group.size() / 2;
            List<SourceFile> front = new ArrayList<>();
            List<SourceFile> back = new ArrayList<>();
            for (SourceFile f : files) {
                (group.get(layout.unitNameOf(f.path())) < mid ? front : back).add(f);
            }
            return List.of(front, back);
        }

        /** 受け取れなかった最初のファイルと、それと同じコンパイル単位の名前のファイル（組。pending の並び） */
        private List<SourceFile> firstUnit(Map<String, SourceFile> pending) {
            String name = layout.unitNameOf(pending.values().iterator().next().path());
            List<SourceFile> unit = new ArrayList<>();
            for (SourceFile f : pending.values()) {
                if (layout.unitNameOf(f.path()).equals(name)) {
                    unit.add(f);
                }
            }
            return unit;
        }

        /**
         * 脇に置いたファイルを、1 つだけで（組でも分ける。組のもう片方で止まっていることがあるため。{@link #stopContext}
         * も付けない）解析し直す。それでも受け取れなければ失敗として数える（{@link Sink#failed}。warnings.txt の
         * 「打ち切られた」に理由とともに載る）
         *
         * @param stop 脇に置いたときに JDT が投げた例外。無ければ null
         */
        private void alone(SourceFile file, Throwable stop, Map<SourceFile, List<SourceFile>> deferred)
                throws IOException {
            Map<String, SourceFile> one = byPath(List.of(file));
            Throwable again = parse(one, List.of(), deferred, new ArrayList<>());
            if (!one.isEmpty()) {
                sink.failed(file, reasonOf(file, (again != null) ? again : stop));
            }
        }

        /**
         * 止まったファイルの組（{@code unit}）だけを 1 回の createASTs に渡して、JDT がまた止まるか（受け取れないファイルが
         * 残るか）を確かめる。事実は集めない（止まらなければ、組はバッチを分けたあとでふつうに解析する）。添えるファイルは
         * 止まったときのバッチと同じにする。どのファイルで止まったか分からないときに、最初の組が原因かを見分けるのに使う
         * （{@link #analyzeAll}）
         */
        private boolean stopsAlone(List<SourceFile> unit) throws IOException {
            Map<String, SourceFile> pending = byPath(unit);
            parse(pending, stopContext, null, new ArrayList<>());
            return !pending.isEmpty();
        }

        /**
         * pending のファイルを 1 回の createASTs で解析し、受け取ったものを pending から外して {@code finished} に足す。
         * 最後のファイルを受け取ったら（pending が空になったら）、受け取ったファイルの事実をまとめて集めて sink へ渡す
         * （{@link CallEdgeExtractor#analyzeBatch} の「事実は、解析するファイルをすべて JDT が解決し終えてから集める」）。
         * 途中で止まったら事実は集めず、{@code finished} に受け取った分が残る（解析し直す）。
         *
         * @param extra    添えるファイル（JDT が止まったファイルに関わるファイル。{@link #stopContext}）
         * @param deferred 入れ子の型を宣言するファイルを添えて解析し直すファイル（{@link #memberTypeFilesOf}）の置き場。
         *                 null なら事実を集めない（{@link #stopsAlone}。止まるかどうかだけを見る）
         * @return JDT が投げた例外。例外なしに戻った（止まったかどうかは pending を見る）なら null
         */
        private Throwable parse(Map<String, SourceFile> pending, List<SourceFile> extra,
                                Map<SourceFile, List<SourceFile>> deferred, List<SourceFile> finished)
                throws IOException {
            List<SourceFile> candidates = new ArrayList<>();
            if (withContext) {
                candidates.addAll(project.context);
            }
            candidates.addAll(memberTypeFiles);
            candidates.addAll(extra);
            List<SourceFile> context = project.sorted(candidates, pending.keySet());
            context.removeIf(ProjectScan::isModuleInfo);

            List<String> all = new ArrayList<>(pending.keySet());
            for (SourceFile f : context) {
                all.add(f.path().toString());
            }
            Set<String> present = new HashSet<>(all);
            String[] paths = all.toArray(new String[0]);
            String[] fileEncodings = new String[paths.length];
            Arrays.fill(fileEncodings, encodingName);
            // 解析するファイルを受け取り、事実を集め終えたら止める（後ろに並べた添えるファイルは、型を作るだけで本体を
            // 読まない）。JDT は acceptAST から戻るたびに取り消しを見るほか、型を探すたびにも見る（取り消されていれば
            // 探す途中で打ち切りの例外を投げる）。そのため、最後のファイルを受け取っただけで取り消し扱いにすると、
            // そのあと事実を集めるあいだの型の問い合わせ（ソースパスの型を読む）がすべて打ち切られ、ファイルの失敗に
            // なる。事実を集め終えるまでは取り消さない
            boolean[] done = { false };
            IProgressMonitor stopWhenDone = new NullProgressMonitor() {
                @Override
                public boolean isCanceled() {
                    return done[0];
                }
            };
            List<CompilationUnit> units = new ArrayList<>();
            try {
                newParser().createASTs(paths, fileEncodings, new String[0], new FileASTRequestor() {
                    @Override
                    public void acceptAST(String sourceFilePath, CompilationUnit cu) {
                        SourceFile file = pending.remove(sourceFilePath);
                        if (file == null) {
                            return;
                        }
                        finished.add(file);
                        units.add(cu);
                        if (!pending.isEmpty()) {
                            return;   // 事実はまだ集めない（後ろのファイルの型を先に解決させない）
                        }
                        // 解析するファイルの最後。JDT は解析するファイルをすべて解決し終えている
                        if (deferred != null) {   // null なら止まるかどうかだけを見る（stopsAlone）
                            for (int i = 0; i < finished.size(); i++) {
                                collectAndDeliver(finished.get(i), units.get(i), present, deferred);
                                units.set(i, null);   // 集め終えた AST は手放す
                            }
                        }
                        done[0] = true;
                    }
                }, stopWhenDone);
                return null;
            } catch (UncheckedIOException e) {
                throw e.getCause();
            } catch (OperationCanceledException e) {
                return pending.isEmpty() ? null : e;
            } catch (RuntimeException | StackOverflowError e) {
                return e;
            }
        }

        /** 受け取った 1 ファイルの事実を集めて sink へ渡す。失敗はそのファイルの失敗として数える */
        private void collectAndDeliver(SourceFile file, CompilationUnit cu, Set<String> present,
                                       Map<SourceFile, List<SourceFile>> deferred) {
            FileAnalysis facts;
            try {
                facts = collectFacts(file, cu);
            } catch (RuntimeException e) {
                // 型の解決は遅れて行われるので、事実を集めるあいだに JDT が文言の無い例外（見つからない
                // クラスでの打ち切り）を投げることがある
                sink.failed(file, explained(e));
                return;
            } catch (StackOverflowError e) {
                sink.failed(file, tooDeep(e));
                return;
            }
            List<SourceFile> needs = memberTypeFilesOf(facts, present);
            if (!needs.isEmpty()) {
                deferred.put(file, needs);   // 宣言するファイルを添えて解析し直す（run）
                return;
            }
            deliver(file, facts);
        }

        private void deliver(SourceFile file, FileAnalysis facts) {
            try {
                sink.accept(file, facts);
            } catch (IOException e) {
                throw new UncheckedIOException(e);   // parse の catch で IOException に戻す
            } catch (RuntimeException e) {
                // 受け手の失敗（書き手の誤りなど）もこのファイルの失敗として数える。
                // ここで逃がすと一括パースごと止まり、pending から外したこのファイルは
                // 解析し直しにも回らず、失敗とも数えられずに黙って消える
                sink.failed(file, explained(e));
            } catch (StackOverflowError e) {
                // 受け手の中で溢れた場合も同じ。外の catch まで抜けると、このファイルは pending から
                // 外してあるので解析し直しにも回らず、黙って消える（docs/cache-unification-qa.md の Q69）
                sink.failed(file, tooDeep(e));
            }
        }
    }

    /**
     * jar の型が参照していたのに見つからなかった、ソースの入れ子の型（事実に {@code app.Outer$Inner} の形で残る）を
     * 宣言するファイル（{@code app/Outer.java}。同じ名前の組ごと）のうち、今回 JDT に渡していないもの。
     *
     * <p>{@code $} を含む名前は、見つからなかった型の名前（JDT がクラスファイルの名前のまま作る）か、無名クラス・
     * ローカルクラスの名前（{@code app.A$1}）。後者は自分や、渡したファイルの型なので当たらない。ソースの型の名前に
     * {@code $} を書いていれば余分に当たるが、添えるファイルが増えるだけで事実は変わらない
     */
    private List<SourceFile> memberTypeFilesOf(FileAnalysis facts, Set<String> present) {
        List<SourceFile> needs = new ArrayList<>();
        for (Set<String> names : List.of(facts.referencedTypes, facts.unresolvedNames)) {
            for (String name : names) {
                int dollar = name.indexOf('$');
                if (dollar <= 0) {
                    continue;
                }
                List<SourceFile> declaring = project.unit(name.substring(0, dollar).replace('.', '/') + ".java");
                boolean given = false;
                for (SourceFile f : declaring) {
                    given |= present.contains(f.path().toString());
                }
                if (!given) {
                    for (SourceFile f : declaring) {
                        if (!needs.contains(f)) {
                            needs.add(f);
                        }
                    }
                }
            }
        }
        return needs;
    }

    /**
     * JDT が止まったファイルに関わるファイル（構文だけで読む）。同じフォルダのファイル（同じパッケージ）と、ファイルに
     * 名前を書いた型のファイル。{@code import a.b.C;}・{@code import static a.b.C.m;}・式や型に書いた完全修飾名
     * {@code a.b.C.run()} は、名前の頭の部分のどれかが型なので、{@code a/b/C/run.java}・{@code a/b/C.java}・
     * {@code a/b.java}… のうちソースにあるもの。{@code import a.b.*;} は、そのうえで {@code a/b} のフォルダのファイル。
     * 名前だけでは型かどうか（変数・パッケージか）を決めないので、余分に当たることがあるが、添えるファイルが増えるだけ。
     * 読めなければ同じフォルダのファイルだけ
     *
     * <p>例外なしの打ち切りは、ソースパスから読んだ型（B）の中の、依存 jar に無いクラスで起きる。B を同じバッチに
     * 入れれば起きない（全件解析で止まったファイルと B が同じバッチにいれば、ふつうに解析できる）。B はたいてい、
     * 止まったファイルと同じパッケージか、そのファイルが名前を書いた型である。書いた名前の型のシグネチャを通して
     * たどり着く型（ファイルが C だけを書き、C のメソッドの引数が B）は拾わない（拾うにはソースの全体を添えることに
     * なる）。そのときは止まったファイルを失敗として数える（受け入れた限界）
     */
    private List<SourceFile> relatedFiles(SourceFile file) {
        List<SourceFile> files = new ArrayList<>(project.folder(layout.unitNameOf(file.path())));
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setCompilerOptions(compilerOptions);
        parser.setResolveBindings(false);
        try {
            parser.createASTs(new String[] {file.path().toString()}, new String[] {encodingName}, new String[0],
                    new FileASTRequestor() {
                        @Override
                        public void acceptAST(String sourceFilePath, CompilationUnit cu) {
                            for (Object o : cu.imports()) {
                                ImportDeclaration imp = (ImportDeclaration) o;
                                if (Modifier.isModule(imp.getModifiers())) {
                                    continue;
                                }
                                String path = imp.getName().getFullyQualifiedName().replace('.', '/');
                                if (imp.isOnDemand()) {
                                    files.addAll(project.folder(path + "/x.java"));
                                }
                                addPrefixFiles(path, files);
                            }
                            cu.accept(new ASTVisitor() {
                                @Override
                                public boolean visit(QualifiedName node) {
                                    addPrefixFiles(node.getFullyQualifiedName().replace('.', '/'), files);
                                    return false;   // 頭の部分は addPrefixFiles が見る
                                }
                            });
                        }
                    }, null);
        } catch (RuntimeException | StackOverflowError e) {
            // 読めなければ名前を書いた型のファイルは添えない（同じフォルダのファイルだけで解析し直す）
        }
        return project.sorted(files, Set.of());
    }

    /** {@code a/b/C/m} の頭の部分のどれか（{@code a/b/C/m.java}・{@code a/b/C.java}・{@code a/b.java}・{@code a.java}）に当たるソースのファイル */
    private void addPrefixFiles(String path, List<SourceFile> out) {
        for (String p = path; !p.isEmpty(); p = p.substring(0, Math.max(0, p.lastIndexOf('/')))) {
            out.addAll(project.unit(p + ".java"));
        }
    }

    /** 解析し直しても受け取れなかったファイルの、失敗の理由 */
    private static Exception reasonOf(SourceFile file, Throwable stop) {
        if (stop instanceof StackOverflowError e) {
            return tooDeep(e);
        }
        if (stop instanceof Exception e) {
            return explained(e);
        }
        try (InputStream in = Files.newInputStream(file.path())) {
            // 読める（1 バイト読んでみる）。JDT が理由を言わずに打ち切った
            in.read();
            return new IllegalStateException(Messages.get("analysis.stopped"));
        } catch (IOException e) {
            return e;
        }
    }

    /**
     * JDT がこの実行のクラスパス・ソースパス（ソースフォルダと依存 jar。{@link #newParser}）を受け付けるか。
     * 受け付けなければ、JDT がその理由を返す（{@code invalid environment settings} など）。受け付けるなら null。
     *
     * <p>JDT は解析のたびに、渡されたパスからクラスパスを組み立て、組み立てられないもの（無いフォルダ・jar でも
     * フォルダでもないもの。JDT は {@code \} もパスの区切りとして読むので、Linux で名前に {@code \} を含むフォルダは
     * 見つからない）があると、どのファイルも解析せずに例外を投げる。ファイルに依らない失敗なので、空のファイルの一覧で
     * 確かめられる。差分更新はこれを見て、受け付けられなければ旧キャッシュを使わない
     * （{@code CacheUpdater}。docs/cache-unification-qa.md の Q73）
     */
    public String environmentProblem() {
        try {
            newParser().createASTs(new String[0], new String[0], new String[0], new FileASTRequestor() { }, null);
            return null;
        } catch (RuntimeException e) {
            return String.valueOf(e.getMessage());
        }
    }

    /**
     * 失敗の理由として伝える例外。JDT の例外には文言の無いもの（見つからないクラスでの打ち切り {@code AbortCompilation}
     * など）があり、そのままでは warnings.txt の行が「()」だけになって何が起きたか分からないので、例外の名前を添えた
     * 文言にする
     */
    private static Exception explained(Exception e) {
        if (e.getMessage() == null || e.getMessage().isBlank()) {
            return new IllegalStateException(Messages.format("analysis.stoppedBy", e.getClass().getName()), e);
        }
        return e;
    }

    /** スタックが溢れたことを、そのファイルの失敗の理由として伝える例外（利用者が対処を選べる文言にする） */
    private static Exception tooDeep(StackOverflowError e) {
        return new IllegalStateException(Messages.get("analysis.tooDeep"), e);
    }

    /** ワークスペース非依存で型解決するための設定を済ませたパーサ */
    private ASTParser newParser() {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setCompilerOptions(compilerOptions);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        parser.setEnvironment(classpath, sourcepath, sourcepathEncodings, true);
        return parser;
    }

    private FileAnalysis collectFacts(SourceFile file, CompilationUnit cu) {
        FileAnalysis result = new FileAnalysis(file.relativePath(), file.size());
        // 型が見つからない等のエラーは「解決が不完全」の印。依存 jar が増えたら解析し直せるよう数を残す。
        // そのうち構文エラーだけは別に数える。構文エラーの出たファイルは本体を読めていないので、
        // 「jar を足せば直る」ものとは意味が違う（FileAnalysis#syntaxErrors）
        for (IProblem problem : cu.getProblems()) {
            if (!problem.isError()) {
                continue;
            }
            result.errors++;
            if (isSyntaxError(problem.getID())) {
                result.syntaxErrors++;
            }
            namesOf(problem.getArguments(), writtenNameAt(cu, problem), result.unresolvedNames);
        }
        collectImports(cu, result);
        cu.accept(new FactVisitor(cu, result, recordAllConditions));
        return result;
    }

    /**
     * エラーの引数から、点区切りの識別子（{@code Foo}・{@code org.missing}・{@code p.Outer.Inner}）を取り出す。
     *
     * <p>無い型・import・名前のエラー（「Foo cannot be resolved」「The import org.missing cannot be resolved」）の
     * 引数には、解決できなかった名前が書いたとおりに入る。差分更新は新しい型ができたとき、この名前に当たる
     * ブロックだけを解析し直す（{@link CacheUpdater} の「新しい型」）。エラーの種類では絞らず、どのエラーの
     * 引数も拾う（型の名前が入りうるものを取りこぼさないため。余分に拾っても解析し直すファイルが増えるだけ）
     *
     * <p>ただし、パスの区切り（{@code /} か {@code \}）を含む引数は拾わない。型が重複しているエラー
     * （{@code The type Dup is already defined}）などは、引数にソースファイルのパスを入れる。一括で解析するときの
     * パスは絶対パスなので、そのまま拾うと {@code home}・{@code user} のようなチェックアウトの場所のフォルダ名が
     * キャッシュ（I 行の 2 列目）に入り、同じソースでも置き場所によってキャッシュの事実が変わる。パスの中の名前は
     * フォルダとファイルの名前で、型の名前は同じエラーの別の引数に入る。型の名前・パッケージの名前の引数は点で
     * 区切るのでパスの区切りを含まない。演算子の引数（{@code /}）は識別子を含まないので、落としても何も失わない
     * （docs/cache-unification-qa.md の Q64）
     *
     * <p>拾った名前が、エラーの位置に書かれた名前（{@code written}）の頭の部分なら、書かれた名前全体に置き換える。
     * 依存 jar が無いとき、式の中の {@code org.missing.pkg.Type.run()} のエラーは、同じバッチで先に別のファイルが
     * 同じ名前を型の文脈で解決しようとしたかどうかで、{@code org.missing cannot be resolved} にも
     * {@code org.missing.pkg.Type cannot be resolved to a type} にもなる（JDT は回復のために作った型をバッチの中で
     * 使い回す）。どちらも書かれた名前 {@code org.missing.pkg.Type} にそろえ、キャッシュの事実がバッチの組み方に
     * 依らないようにする（docs/cache-unification-qa.md の Q79）
     *
     * @param written エラーの位置に書かれた名前（{@link #writtenNameAt}）。無ければ null
     */
    static void namesOf(String[] arguments, String written, java.util.Set<String> out) {
        if (arguments == null) {
            return;
        }
        for (String a : arguments) {
            if (a == null || a.indexOf('/') >= 0 || a.indexOf('\\') >= 0) {
                continue;
            }
            int i = 0;
            int n = a.length();
            while (i < n) {
                if (!Character.isJavaIdentifierStart(a.charAt(i))) {
                    i++;
                    continue;
                }
                int start = i;
                int end = i;
                // 識別子を点でつないだ並び（末尾の点は含めない）
                while (i < n && Character.isJavaIdentifierPart(a.charAt(i))) {
                    i++;
                    end = i;
                    if (i + 1 < n && a.charAt(i) == '.' && Character.isJavaIdentifierStart(a.charAt(i + 1))) {
                        i++;
                    }
                }
                String name = a.substring(start, end);
                boolean head = written != null && (written.equals(name) || written.startsWith(name + "."));
                out.add(head ? written : name);
            }
        }
    }

    /**
     * エラーの位置に書かれた名前（点でつないだ名前全体。{@code org.missing.pkg.Type.run()} の
     * {@code org.missing} の位置なら {@code org.missing.pkg.Type}）。位置が名前の中でなければ null
     */
    static String writtenNameAt(CompilationUnit cu, IProblem problem) {
        int start = problem.getSourceStart();
        int end = problem.getSourceEnd();
        if (start < 0 || end < start) {
            return null;
        }
        ASTNode node = NodeFinder.perform(cu, start, end - start + 1);
        if (node instanceof SimpleType t) {
            node = t.getName();
        }
        if (!(node instanceof Name)) {
            return null;
        }
        while (node.getParent() instanceof Name) {
            node = node.getParent();
        }
        return ((Name) node).getFullyQualifiedName();
    }

    /**
     * 本体を読めていない構文エラーか。
     *
     * JDT は {@code var} の使い方の誤り（JLS 14.4.1・JLS 3.9。{@code class var}、
     * 初期化子の無い {@code var}、{@code var} の配列など）にも {@link IProblem#Syntax} の印を付けるが、
     * これらは構文を最後まで読んだあとで検査されるもので、本体の呼び出しはすべて AST に残っている。
     * 構文エラーに数えると「このファイルの呼び出しは出力に出ない」と事実と違う警告になるので外す
     * （エラーとしては {@link FileAnalysis#errors} に数えたまま）。
     * Java 10 より前のコードで {@code var} を型名に使っているときに、source.level を指定しないと出る
     * （{@code docs/syntax-error-report-qa.md} の Q7）。
     *
     * <p>switch 式の検査（網羅していない・default が無い・switch 式の外への break / continue / return）も同じで、
     * {@link IProblem#Syntax} の印が付くが、構文を読み終えたあとのフロー解析で出るもので、本体は AST に残っている
     * （{@code docs/cache-unification-qa.md} の Q63）。網羅していないパターンの switch は、sealed の許可リストに
     * 型を足したのに switch を直していないときによく出る。
     * 外す印は、本体が AST に残ることを確かめたものだけにする。確かめていないものは構文エラーに数えたままにする
     * （数えすぎても警告が余計に出るだけだが、数え落とすと本体を読めていないファイルを黙って通してしまう）
     */
    static boolean isSyntaxError(int problemId) {
        if ((problemId & IProblem.Syntax) == 0) {
            return false;
        }
        return switch (problemId) {
            case IProblem.VarLocalMultipleDeclarators, IProblem.VarLocalCannotBeArray,
                 IProblem.VarLocalReferencesItself, IProblem.VarLocalWithoutInitizalier,
                 IProblem.VarIsReserved, IProblem.VarIsReservedInFuture, IProblem.VarIsNotAllowedHere,
                 IProblem.VarCannotBeMixedWithNonVarParams, IProblem.VarCannotBeUsedWithTypeArguments,
                 IProblem.SwitchExpressionsYieldMissingDefaultCase,
                 IProblem.SwitchExpressionsYieldMissingEnumConstantCase,
                 IProblem.SwitchExpressionsBreakOutOfSwitchExpression,
                 IProblem.SwitchExpressionsContinueOutOfSwitchExpression,
                 IProblem.SwitchExpressionsReturnWithinSwitchExpression -> false;
            default -> true;
        };
    }

    /**
     * import 文の型も依存に数える。解決に失敗した import（jar不足）が後から
     * 解決できるようになったときに、このファイルを解析し直せるようにするため
     */
    private static void collectImports(CompilationUnit cu, FileAnalysis result) {
        for (Object o : cu.imports()) {
            ImportDeclaration imp = (ImportDeclaration) o;
            String name = imp.getName().getFullyQualifiedName();
            if (imp.isOnDemand()) {
                result.imports.add(name + ".*");
                continue;
            }
            result.imports.add(name);
            if (imp.isStatic()) {
                // import static a.B.c; の a.B（メンバではなく型）も依存
                int dot = name.lastIndexOf('.');
                if (dot > 0) {
                    result.imports.add(name.substring(0, dot));
                }
            }
        }
    }
}
