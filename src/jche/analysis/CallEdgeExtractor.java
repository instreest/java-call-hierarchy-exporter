// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FileASTRequestor;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.NodeFinder;
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
 * 1ファイル分の AST が出来るたびに {@link Sink} へ渡す。AST は渡した直後に捨てられるので、
 * ヒープに載るのは1バッチ分に留まる。
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
    private final Charset encoding;
    private final String encodingName;
    private final Map<String, String> compilerOptions;
    private final String[] classpath;
    private final String[] sourcepath;
    private final String[] sourcepathEncodings;

    /** 判定できない条件も guard に残すか（条件の調査用。{@link CallConditionScanner}） */
    private final boolean recordAllConditions;

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
        this.encoding = Charset.forName(config.sourceEncoding);
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
     * 複数のファイルをまとめてパースし、1ファイル分ずつ sink へ渡す。
     *
     * JDT が受け付けなかったファイルや、一括パース自体が失敗したときに JDT が解析していたファイルは、
     * 1ファイルずつ {@link #analyze} で解析する。1ファイルの失敗で他を巻き込まないため。残りはもう一度まとめて解析する
     * （事実はバッチの全ファイルを JDT が解決し終えてから集める。下の {@code parseTogether}）。
     *
     * <p>スタックの溢れ（{@link StackOverflowError}。メソッド呼び出しを数千段つないだ式のように、JDT の再帰が
     * 深くなりすぎるファイル）も、そのファイルの失敗として扱う。一括パースの途中で溢れたら、そのとき JDT が解析していた
     * ファイルを 1 ファイルで解析し直し、溢れたファイルだけを失敗として数える（{@link Sink#failed}。warnings.txt の
     * 「打ち切られた」に載る）。以前は捕まえておらず、設定 1 つ分の解析がまるごと失敗していた
     * （{@code docs/cache-unification-qa.md} の Q62）。溢れたスタックは例外が外へ抜けるあいだに戻るので、捕まえたあとは続けられる。
     */
    public void analyzeBatch(List<SourceFile> files, Sink sink) throws IOException {
        List<SourceFile> rest = files;
        while (!rest.isEmpty()) {
            rest = parseTogether(rest, sink);
        }
    }

    /**
     * {@code files} をまとめてパースし、事実を集めて sink へ渡す。まとめて解析し直すファイルを返す（全部済めば空）。
     *
     * <h4>事実は、バッチの全ファイルを JDT が解決し終えてから集める</h4>
     * JDT は {@code createASTs} のファイルを順に 1 つずつ解決し（本体まで）、そのたびに AST を {@code acceptAST} で渡す。
     * 以前は受け取ったその場で事実を集めていたが、事実を集めるときのバインディングへの問い合わせ（型の全メソッド・
     * 注釈の既定値など）は、同じバッチで<b>まだ解決していない後ろのファイル</b>の型にも及ぶ。JDT はそれを、どのファイルの
     * コンパイルの中でもない状態で先に解決するので、次のことが起きていた（docs/cache-unification-qa.md の「後ろのファイルの型を
     * 先に解決させない」）。
     * <ul>
     *   <li>後ろのファイルのメソッドの引数に、jar の型が参照している無いクラスが現れると、JDT は解決を打ち切り（エラーを
     *       届ける先のファイルが無いため）、そのメソッドを引数の無いまま残す。そのファイルの番が来ると JDT の中で
     *       例外になり（{@code ArrayIndexOutOfBoundsException}・{@code NullPointerException}）、バッチの残りが 1 ファイルずつの
     *       解析になる。前に並ぶファイルが無い差分更新では起きないので、warnings.txt が全件解析とだけ違った</li>
     *   <li>注釈の型のメンバーの既定値を先に解決すると、その注釈の型のファイルの番でもう一度解決され、同じエラーが 2 回
     *       数えられる（F 行のエラーの数・I 行の解決できなかった名前が、前に使う側が並ぶかどうかで変わった）</li>
     * </ul>
     * そこで {@code acceptAST} では AST を預かるだけにし、バッチの最後のファイルを受け取ったとき（JDT がバッチの全ファイルを
     * 解決し終えたとき）に、預かった分の事実をまとめて集める。バッチのファイルの型はどれも解決済みなので、問い合わせが
     * 先に解決させることはない。預かっても、JDT は同じバッチのバインディングの表から前のファイルの AST を最後まで
     * 辿れる形で持ち続けているので、ヒープはほとんど増えない（100 ファイルのバッチで測って同じ）。
     * {@code createASTs} から戻ったあとでは、ソースパスから読んだ型への問い合わせが働かないので、最後のファイルの
     * {@code acceptAST} の中で集める。
     *
     * <p>一括パースが途中で失敗したら（JDT の例外・スタックの溢れ）、そのとき JDT が解析していたファイル（まだ受け取って
     * いない最初のファイル）を 1 ファイルで解析し、預かった分と残りはまとめて解析し直す（戻り値）。JDT が一部のファイルを
     * 渡さずに戻ったときも、渡さなかったファイルは 1 ファイルずつ、預かった分はまとめて解析し直す。どちらも 1 回ごとに
     * 少なくとも 1 ファイル減るので、繰り返しは終わる。
     */
    private List<SourceFile> parseTogether(List<SourceFile> files, Sink sink) throws IOException {
        Map<String, SourceFile> pending = new LinkedHashMap<>();
        for (SourceFile file : files) {
            pending.put(file.path().toString(), file);
        }
        String[] paths = pending.keySet().toArray(new String[0]);
        String[] fileEncodings = new String[paths.length];
        Arrays.fill(fileEncodings, encodingName);
        List<SourceFile> acceptedFiles = new ArrayList<>();
        List<CompilationUnit> acceptedUnits = new ArrayList<>();
        boolean[] collected = { false };

        try {
            newParser().createASTs(paths, fileEncodings, new String[0], new FileASTRequestor() {
                @Override
                public void acceptAST(String sourceFilePath, CompilationUnit cu) {
                    SourceFile file = pending.remove(sourceFilePath);
                    if (file == null) {
                        return;
                    }
                    acceptedFiles.add(file);
                    acceptedUnits.add(cu);
                    if (pending.isEmpty()) {
                        // バッチの最後のファイル。JDT はバッチの全ファイルを解決し終えている
                        collected[0] = true;
                        try {
                            for (int i = 0; i < acceptedFiles.size(); i++) {
                                collectAndAccept(acceptedFiles.get(i), acceptedUnits.get(i), sink);
                                acceptedUnits.set(i, null);   // 集め終えた AST は手放す
                            }
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);   // 下の catch で IOException に戻す
                        }
                    }
                }
            }, null);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        } catch (RuntimeException e) {
            if (!collected[0] && !pending.isEmpty()) {
                List<SourceFile> rest = othersThan(files, first(pending));
                Log.warn(Messages.format("analysis.batchFailed", first(pending).relativePath(), rest.size(), e));
                analyzeAlone(first(pending), sink);
                return rest;
            }
        } catch (StackOverflowError e) {
            if (!collected[0] && !pending.isEmpty()) {
                // どのファイルで溢れたかは、そのファイルを 1 ファイルで解析したときの失敗として warnings.txt に載る
                List<SourceFile> rest = othersThan(files, first(pending));
                Log.info(Messages.format("analysis.batchTooDeep", first(pending).relativePath(), rest.size()));
                analyzeAlone(first(pending), sink);
                return rest;
            }
        }
        if (collected[0]) {
            return List.of();
        }
        // JDT が一部のファイルを渡さずに戻った。渡さなかったファイルは 1 ファイルずつ、預かった分はまとめて解析し直す
        for (SourceFile file : new ArrayList<>(pending.values())) {
            analyzeAlone(file, sink);
        }
        return acceptedFiles;
    }

    /**
     * 一括パースが失敗したときに JDT が解析していたファイル（まだ受け取っていない最初のファイル。JDT は渡した順に解決し、
     * 1 つ解決するたびに渡してくる）。このファイルを 1 ファイルで解析し、それ以外（預かった分と残り）はまとめて解析し直す。
     * 1 ファイルで解析したファイルが失敗の元でなかったとしても、残りをまとめて解析し直すときに元のファイルでまた失敗し、
     * そこで同じように外れる
     */
    private static SourceFile first(Map<String, SourceFile> pending) {
        return pending.values().iterator().next();
    }

    /** {@code files} のうち {@code at} 以外（元の並び） */
    private static List<SourceFile> othersThan(List<SourceFile> files, SourceFile at) {
        List<SourceFile> rest = new ArrayList<>(files.size());
        for (SourceFile file : files) {
            if (file != at) {
                rest.add(file);
            }
        }
        return rest;
    }

    /** 1 ファイルだけを解析して sink へ渡す（一括パースの補完。test/incremental の SinkOverflowCheck も直接呼ぶ） */
    void analyzeAlone(SourceFile file, Sink sink) throws IOException {
        FileAnalysis facts;
        try {
            facts = analyze(file);
        } catch (IOException | RuntimeException e) {
            sink.failed(file, e);
            return;
        } catch (StackOverflowError e) {
            sink.failed(file, tooDeep(e));
            return;
        }
        try {
            sink.accept(file, facts);
        } catch (RuntimeException e) {
            // 一括パースの側と同じく、受け手の失敗はこのファイルの失敗として数えて続ける
            sink.failed(file, e);
        } catch (StackOverflowError e) {
            sink.failed(file, tooDeep(e));
        }
    }

    /** 一括パースで受け取った 1 ファイルの事実を集めて sink へ渡す。失敗はそのファイルの失敗として数える */
    private void collectAndAccept(SourceFile file, CompilationUnit cu, Sink sink) throws IOException {
        FileAnalysis facts;
        try {
            facts = collectFacts(file, cu);
        } catch (RuntimeException e) {
            sink.failed(file, e);
            return;
        } catch (StackOverflowError e) {
            sink.failed(file, tooDeep(e));
            return;
        }
        try {
            sink.accept(file, facts);
        } catch (RuntimeException e) {
            // 受け手の失敗（書き手の誤りなど）もこのファイルの失敗として数える。
            // ここで逃がすと一括パースごと止まり、このファイルは 1 ファイルずつの解析にも回らず、失敗とも数えられずに
            // 黙って消える
            sink.failed(file, e);
        } catch (StackOverflowError e) {
            // 受け手の中で溢れた場合も同じ（docs/cache-unification-qa.md の Q69）
            sink.failed(file, tooDeep(e));
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

    /** スタックが溢れたことを、そのファイルの失敗の理由として伝える例外（利用者が対処を選べる文言にする） */
    private static Exception tooDeep(StackOverflowError e) {
        return new IllegalStateException(Messages.get("analysis.tooDeep"), e);
    }

    /** 1ファイルだけをパースする（一括パースの補完用） */
    public FileAnalysis analyze(SourceFile file) throws IOException {
        // 読み込みは「バイト列 -> 文字列 -> char[]」と写し取らず、復号した文字列から直接 char[] を作る
        // （大きなファイルほど、要らない写しがそのままヒープの山になる）
        char[] source = charsOf(Files.readString(file.path(), encoding));
        ASTParser parser = newParser();
        parser.setUnitName(layout.unitNameOf(file.path()));  // バインディング解決に必須
        parser.setSource(source);
        CompilationUnit cu = (CompilationUnit) parser.createAST(null);
        return collectFacts(file, cu);
    }

    /**
     * 文字列を char[] にする。JDT が受け取るのは char[] なので写しは1回だけで済ませる
     * （{@code String.toCharArray()} も写しを作るが、元の文字列をここで捨てられる形にしておく）
     */
    private static char[] charsOf(String text) {
        char[] chars = new char[text.length()];
        text.getChars(0, text.length(), chars, 0);
        return chars;
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
