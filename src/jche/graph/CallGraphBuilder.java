// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jche.cache.CacheFormat;
import jche.cache.CacheReader;
import jche.cache.CallEdgeFact;
import jche.cache.CallSiteValues;
import jche.cache.FieldAssignFact;
import jche.cache.FieldDeclFact;
import jche.cache.FunctionalImplFact;
import jche.cache.Guard;
import jche.cache.MethodDeclFact;
import jche.cache.MethodRef;
import jche.cache.ModifierTokens;
import jche.cache.Origin;
import jche.cache.OverrideFact;
import jche.cache.ReturnFact;
import jche.cache.SymbolTable;
import jche.cache.TempFiles;
import jche.cache.TypeFact;
import jche.cache.UnresolvedCallFact;
import jche.cache.ValueNode;
import jche.util.Log;
import jche.util.Messages;
import jche.util.Names;
import jche.util.RunControl;

/**
 * キャッシュファイルをスキャンして {@link CallGraph} を構築する。
 * <pre>
 *   スキャン（1 回）… メソッドを ID 化し、呼び出し元ごとの本数を数える。型階層・フィールド注入の判定・
 *                    戻り値の出所もこの回で済ませる。エッジ 1 本ごとに、ID と呼び出し箇所の値
 *                    （出所・条件は共有プールの番号、証拠は証拠の表の番号にしたもの）を一時ファイルへ
 *                    固定長のバイナリの記録として書き出す（{@link EdgeSpill}）
 *   配置         … 数えた本数から offsets とちょうどの長さのエッジ配列を作り、一時ファイルを
 *                    先頭から読み直して、各記録を {@code cursor[呼び出し元]++} の位置に置く
 * </pre>
 * キャッシュを 2 回スキャンしていた以前の形と、ID の振られ方・エッジの並び（呼び出し元ごとにファイル上の順）・
 * 共有プールの並びはまったく同じになる（どちらもファイル上の順に処理するため）。
 * ヒープに載るのは以前と同じで、1 ブロック分の記号表（S 行）と値グラフ（N 行）と条件の表（G 行）、
 * ちょうどの長さのエッジ配列。エッジの一覧をヒープに溜めない代わりに、一時ファイル（エッジ 1 本あたり 34 バイト）を
 * 使う。一時ファイルはキャッシュと同じフォルダに作り、終われば（失敗しても）消す。
 *
 * <p>型解決に失敗した呼び出しの一覧（{@code call-hierarchy.csv} の末尾。{@code jche.report.UnresolvedReport}）
 * に出す U 行も、このスキャンで拾って {@link UnresolvedCalls} に渡す（CSV を書く側がキャッシュを
 * 読み直さなくて済むように。解析サーバーは一覧を書かないので拾わない）。
 *
 * <h2>ブロックの読み方</h2>
 * メソッドを指す列は、ブロックの記号表（S 行）の番号で書かれている（{@link SymbolTable}）。
 * S 行を読んだ時点ではメソッドを ID 化せず、参照する行を読んだときに ID 化する。
 * {@link MethodTable} の ID は初めて ID 化した順に振られ、ID 化の順は「戻り値（R 行）→ 宣言（D 行）→
 * 上書き（O 行）→ 呼び出し（C・U 行。呼び出し元、呼び出し先の順）」である（書き手もブロックの行をこの順に並べる）。
 * M 行・A 行の呼び出し元は ID 化しない。ID の順はブロックの並び（差分更新で変わる）に左右されるので、
 * 出力の並びには使わない。同じ行に並ぶ宣言の前後は、D 行がブロックの中で何番目か（宣言の順番）で決める
 * （{@link MethodTable#compareDeclarationOrder}）。
 *
 * <p>呼び出し箇所の値（レシーバ・実引数・ガードの番号・new の証拠）は C 行・U 行の末尾の列にある
 * （{@link CallSiteValues.Row}）。値はどれも同じブロックの N 行のノードを番号で指し、読み手（DataflowResolver など）が
 * 受け取る出所の文字列は、ここで読む直前に組み直す（{@link OriginRenderer}）。
 * <ul>
 *   <li>戻り値（R 行）… ノードを丸ごと組み直す。追跡できない（-1）は U。戻り値そのものが
 *       クラス名・識別子の形でない文字列リテラルなら U（暫定。{@link #unreadableLiteral}）</li>
 *   <li>フィールドへの代入（J 行）… ノードの頭（{@code 種別:値}）だけ（{@link FieldFacts} は頭で比べる）</li>
 *   <li>条件（G 行の表）… 以前の guard 列の文字列（{@link jche.cache.Guard}）に組み直す。subject は
 *       ノードの頭。{@link GuardEvaluator} はこの文字列を読む</li>
 *   <li>証拠（hints 列）… 型の並びを証拠のリストにして、エッジに直接付ける</li>
 * </ul>
 * フィールドへの代入（J 行）は同じブロックの V 行・D 行と組で判定するので、ブロックを読み終えてから渡す。
 *
 * <p>値を読まない指定（{@code dataflow.enabled=false}）のときは、N・G・R・J 行と
 * 呼び出し箇所の値の列を読まない。値が無いものとして組むので、具象クラスの解決は CHA まで、
 * 条件分岐の打ち切りは起きない
 *
 * 読み手の判断として、U行（型解決失敗）に import からの推定候補があれば、
 * それをエッジにする（クラスパス不足で階層から消えるより、未検証と分かる形で残す方針）。
 */
public final class CallGraphBuilder {

    private final CallGraph graph = new CallGraph();
    private final MethodTable methods = graph.methods;
    private final Path cacheFile;
    /** 値（N・G・R・J 行と呼び出し箇所の値）を読むか（{@code dataflow.enabled}） */
    private final boolean readValues;
    /** 型解決に失敗した呼び出しの一覧に出す U 行の置き場。拾わないなら null（解析サーバー） */
    private final UnresolvedCalls unresolved;

    /** スキャンで数える、呼び出し元ごとのエッジ数 */
    private final IntArray outDegree = new IntArray(1 << 16);
    private final Map<Integer, List<String>> returnsById = new HashMap<>();
    private final FieldFacts fields = new FieldFacts();
    private long edgeCount;

    /** ブロックの外を指す番号に出会ったことを警告したか（1度だけ出す） */
    private boolean warnedAboutReference;

    private CallGraphBuilder(Path cacheFile, boolean readValues, UnresolvedCalls unresolved) {
        this.cacheFile = cacheFile;
        this.readValues = readValues;
        this.unresolved = unresolved;
    }

    /**
     * 型解決に失敗した呼び出しを拾わずに組む（{@link #build(Path, boolean, List, SpringBeans, UnresolvedCalls)}
     * の最後を null にしたもの）
     */
    public static CallGraph build(Path cacheFile, boolean readValues,
                                 List<String> sourceFolderOrder, SpringBeans beans)
            throws IOException {
        return build(cacheFile, readValues, sourceFolderOrder, beans, null);
    }

    /**
     * @param readValues        値（値グラフ・戻り値の出所・フィールドへの代入・証拠・呼び出し箇所の値）を
     *                          読むか。{@code dataflow.enabled=false} なら false
     * @param sourceFolderOrder 起点の並び替えに使うソースフォルダの順（プロジェクトルートからの相対パス）
     * @param beans             DIコンテナのBean定義の取り込み先（使わないなら {@link SpringBeans#DISABLED}）
     * @param unresolved        型解決に失敗した呼び出しの一覧に出す U 行の置き場。拾わないなら null
     */
    public static CallGraph build(Path cacheFile, boolean readValues,
                                 List<String> sourceFolderOrder, SpringBeans beans,
                                 UnresolvedCalls unresolved)
            throws IOException {
        CallGraphBuilder b = new CallGraphBuilder(cacheFile, readValues, unresolved);
        b.graph.sourceFolderOrder = sourceFolderOrder;
        b.graph.beans = beans;
        try (EdgeSpill spill = new EdgeSpill(cacheFile)) {
            b.scan(spill);
            RunControl.checkCancelled();
            b.allocateEdges();
            b.placeEdges(spill);
        }
        b.graph.finishBuild();
        return b.graph;
    }

    // ------------------------------------------------------------
    // スキャン: ID化と本数カウント、エッジの記録を一時ファイルへ
    // ------------------------------------------------------------

    private void scan(EdgeSpill spill) throws IOException {
        String label = Messages.get("graph.progress.build");
        long size = Files.size(cacheFile);
        // 進捗はブロックの切れ目で、読んだバイト数がおよそ 2% 進むごとに出す（解析サーバーは通知を 1 行ずつ送るため）
        long step = Math.max(size / 50, 1L << 16);
        long nextReport = 0;
        try (CacheReader in = CacheReader.open(cacheFile)) {
            String currentFile = null;
            SymbolTable.Reader symbols = new SymbolTable.Reader();
            BlockNodes nodes = new BlockNodes();
            BlockGuards guards = new BlockGuards(nodes);
            // ブロックの中で次に読む D 行の位置（ファイルの中の宣言の順番）
            int declOrdinal = 0;
            while (in.next()) {
                switch (in.rowType()) {
                    case CacheFormat.ROW_FILE -> {
                        // 中止の受け付けと進捗はブロックの切れ目で（途中で抜けてもキャッシュは読むだけなので壊れない）
                        RunControl.checkCancelled();
                        if (in.lineStart() >= nextReport) {
                            RunControl.progress(label, in.lineStart(), size);
                            nextReport = in.lineStart() + step;
                        }
                        // ファイル単位で完結する判定（フィールド注入）を、読み終えた前のブロックについて確定する。
                        // 代入（J行）はブロックの後ろにあるので、宣言（V行・D行）が揃ったこの時点で渡す
                        applyPendingAssigns();
                        fields.flushInto(graph.fieldOrigins);
                        currentFile = in.filePath();
                        symbols.clear();
                        nodes.clear();
                        guards.clear();
                        declOrdinal = 0;
                        if (unresolved != null) {
                            unresolved.beginBlock(currentFile);
                        }
                    }
                    case CacheFormat.ROW_SYMBOL -> symbols.add(in.columns());
                    case CacheFormat.ROW_VALUE_NODE -> {
                        if (readValues) {
                            nodes.add(ValueNode.fromRow(in.columns()));
                        }
                    }
                    case CacheFormat.ROW_GUARD -> {
                        if (readValues) {
                            guards.add(in.columns());
                        }
                    }
                    case CacheFormat.ROW_RETURN -> {
                        // D 行より前に並ぶので、戻り値のあるメソッドは宣言より先に ID 化される
                        if (readValues && inRange(in.columns(), symbols.array())) {
                            readReturn(ReturnFact.fromRow(in.columns(), symbols.array()), nodes);
                        }
                    }
                    case CacheFormat.ROW_TYPE -> {
                        TypeFact t = TypeFact.fromRow(in.columns());
                        if (t != null) {
                            graph.hierarchy.add(t);
                            graph.beans.type(t);
                        }
                    }
                    case CacheFormat.ROW_METHOD_DECL -> {
                        // 読めない行も数える（順番は書き手が D 行を並べた位置そのもの。どの行が読めたかに左右させない）
                        int ordinal = declOrdinal++;
                        MethodDeclFact d = inRange(in.columns(), symbols.array())
                                ? MethodDeclFact.fromRow(in.columns(), symbols.array()) : null;
                        if (d != null) {
                            int id = methods.intern(d.ref());
                            ensure(outDegree, id);
                            methods.setDeclaration(id, currentFile, d.declLine(), d.endLine(),
                                    d.hasBody(), ordinal);
                            if (ModifierTokens.has(d.mods(), ModifierTokens.LAMBDA)) {
                                methods.markLambdaBody(id);
                            }
                            methods.setDeclarationDetails(id, d.annotations(), d.mods());
                            fields.declaration(d);
                            graph.beans.method(id, d);
                        }
                    }
                    case CacheFormat.ROW_OVERRIDE -> {
                        // O行はD行の直後に並ぶので、ここで intern すれば宣言の情報は揃っている
                        OverrideFact o = inRange(in.columns(), symbols.array())
                                ? OverrideFact.fromRow(in.columns(), symbols.array()) : null;
                        if (o != null) {
                            graph.overrides.add(o, methods.intern(o.ref()));
                        }
                    }
                    case CacheFormat.ROW_CALL -> addCall(in.columns(), symbols.array(), nodes, guards, spill);
                    case CacheFormat.ROW_UNRESOLVED ->
                            addUnresolved(in.columns(), symbols.array(), nodes, guards, spill);
                    case CacheFormat.ROW_FIELD_DECL -> {
                        FieldDeclFact v = FieldDeclFact.fromRow(in.columns());
                        if (v != null) {
                            fields.field(v);
                            graph.beans.field(v);
                        }
                    }
                    case CacheFormat.ROW_FUNCTIONAL_IMPL -> {
                        // 使うのは関数型インターフェースのメソッドキーだけで、呼び出し元は使わない。
                        // 呼び出し元の記号が壊れていても行は捨てない（呼び出し元 null として読む）
                        FunctionalImplFact m = FunctionalImplFact.fromRow(in.columns(), symbols.array());
                        if (m != null && !m.ifaceMethodKey().isEmpty()) {
                            graph.functionalImpls.add(m.ifaceMethodKey());
                        }
                    }
                    case CacheFormat.ROW_FIELD_ASSIGN -> {
                        // 代入はフィールドの宣言（V 行）が揃ってからでないと拾われないので、
                        // ここでは溜めるだけにして、ブロックを読み終えてから渡す。
                        // 値はノードの頭（種別:値）に直しておく（N 行はブロックの先頭にあるので揃っている）
                        FieldAssignFact j = readValues ? FieldAssignFact.fromRow(in.columns()) : null;
                        if (j != null) {
                            pendingAssigns.add(new PendingAssign(j, nodes.headOf(j.node())));
                        }
                    }
                    default -> {
                        // I行は差分更新のためだけの行、A行・K行は今の読み手は使わない
                    }
                }
            }
            applyPendingAssigns();
            fields.flushInto(graph.fieldOrigins);
            RunControl.progress(label, size, size);
        }
        spill.finishWriting();
        if (unresolved != null) {
            unresolved.finishWriting();
        }
        graph.hierarchy.sortForDeterminism();
        Log.info(Messages.format("graph.collected", graph.hierarchy.size(), methods.size(), edgeCount));
        if (edgeCount > Integer.MAX_VALUE) {
            throw new IOException(Messages.format("graph.tooManyEdges", edgeCount));
        }
    }

    /** J 行 1 件と、その値（ノードの頭。{@link BlockNodes#headOf}） */
    private record PendingAssign(FieldAssignFact fact, String origin) {
    }

    /** 今のブロックのフィールドへの代入（J行）。宣言が揃ってから {@link #fields} に渡す */
    private final List<PendingAssign> pendingAssigns = new ArrayList<>();

    /** 溜めた代入を渡して捨てる。{@code fields.flushInto} の直前に呼ぶ */
    private void applyPendingAssigns() {
        for (PendingAssign a : pendingAssigns) {
            fields.assignment(a.fact(), a.origin());
        }
        pendingAssigns.clear();
    }

    /**
     * R 行（戻り値）1 件。メソッドを ID 化し、出所を集める。
     * 出所は同じブロックの値グラフから丸ごと組み直す（{@link OriginRenderer}）。追跡できない（-1）・
     * 組み直せない（ブロックの外を指す）ものは U（「1 つでも U があれば戻り値は不定」の判定に効く）。
     * 戻り値の出所はメソッドの鍵で集約するだけなので、どのブロックで出会っても同じ結果になる
     *
     * <p><b>暫定（stage B で値を正確に読むようになったら外す）:</b> 戻り値そのものが文字列リテラル
     * （ノードの種別が {@link Origin#LITERAL}）で、クラス名・識別子の形（{@link Origin#isNameShaped}）で
     * ないものは U として渡す（{@link #unreadableLiteral}）。
     */
    private void readReturn(ReturnFact r, BlockNodes nodes) {
        if (r == null) {
            return;
        }
        int id = methods.intern(r.method());
        ensure(outDegree, id);
        String origin = null;
        if (r.node() != ValueNode.NONE) {
            if (!nodes.inRange(r.node(), false)) {
                warnBadReference();
            } else if (!unreadableLiteral(nodes.at(r.node()))) {
                origin = nodes.renderer().originOf(r.node());
            }
        }
        if (origin == null) {
            origin = Origin.UNKNOWN_S;
        }
        List<String> origins = returnsById.computeIfAbsent(id, k -> new ArrayList<>(2));
        if (!origins.contains(origin)) {
            origins.add(origin);
        }
    }

    /**
     * 戻り値として今の読み手に渡すと読み違える文字列リテラルか。
     *
     * <p><b>暫定（stage B で値を正確に読むようになったら外す）。</b>今の読み手（{@link DataflowResolver} の
     * 戻り値の文字列・契約表のキー、{@link GuardEvaluator} に渡る経路の値）は、組み直した出所の文字列から
     * {@link Origin#valueOf} で値を取り出すので、値が出所の文法の文字（{@code | ; { }}）を含むと途中で切れる。
     * {@code Object key() { return (Object) "a|b"; }} を渡した経路では値が {@code "a"} に見え、
     * {@code !s.equals("a")} の呼び出しや {@code switch} の {@code default} を誤って {@code [UNREACHABLE]} にし、
     * 契約表の {@code Fac#get("A")} に誤って当てて {@code "B"} の側の実装を落とす。
     * 以前の書き手（形式 v32 まで）は、この形でない文字列を R 行に U として書いていたので、読むところで
     * その振る舞いに戻す。判定は以前の書き手と同じ {@link Origin#isNameShaped}（64 文字以内）。
     *
     * <p>対象は戻り値そのもの（木の頂点）だけで、実引数やレシーバの入れ子の中の文字列はそのまま渡す
     * （呼び出し箇所の値と同じ扱い）。test/pruning の {@code NePipeRet} / {@code PipeRetLocal} /
     * {@code FacPipeRet} と、docs/cache-unification-qa.md の Q9 が対になっているので、外すときは一緒に直す
     */
    private static boolean unreadableLiteral(ValueNode n) {
        return n != null && n.kind() == Origin.LITERAL && !Origin.isNameShaped(n.value());
    }

    /** C 行を読む。記号がブロックの外を指す・列が足りなければ null */
    private CallEdgeFact callOf(String[] cols, MethodRef[] symbols) {
        return inRange(cols, symbols) ? CallEdgeFact.fromRow(cols, symbols) : null;
    }

    /** U 行を読む。{@link #callOf} と同じく、記号がブロックの外を指す・列が足りなければ null */
    private UnresolvedCallFact unresolvedOf(String[] cols, MethodRef[] symbols) {
        return inRange(cols, symbols) ? UnresolvedCallFact.fromRow(cols, symbols) : null;
    }

    /**
     * 行の記号がどれもブロックの記号表の読めた S 行を指すか（{@link SymbolTable#refsInRange}）。
     * 範囲の外、または読めなかった S 行を指していれば 1 度だけ警告して false（その行は使わない）。
     *
     * <p>差分更新（{@code CacheUpdater} のパス1）がブロックの検査値を確かめているので、ここで外れることは
     * 事実上ない。外れたときに黙って読み飛ばすと呼び出しが静かに消えるので、利用者に知らせる
     */
    private boolean inRange(String[] cols, MethodRef[] symbols) {
        if (SymbolTable.refsInRange(cols, symbols)) {
            return true;
        }
        warnBadReference();
        return false;
    }

    private void warnBadReference() {
        if (!warnedAboutReference) {
            warnedAboutReference = true;
            Path dir = cacheFile.toAbsolutePath().getParent();
            Log.warn(Messages.format("cache.badReference", cacheFile.getFileName(),
                    (dir == null) ? cacheFile : dir));
        }
    }

    private void countEdge(int caller, int callee) {
        ensure(outDegree, caller);
        ensure(outDegree, callee);
        outDegree.set(caller, outDegree.get(caller) + 1);
        edgeCount++;
    }

    /**
     * C 行 1 行をエッジにする。本数を数え、エッジの中身を一時ファイルへ書く。
     * 出所・条件・修飾する型を共有プールに入れる順（修飾する型 → レシーバ → 実引数 → 条件）は、
     * キャッシュを 2 回スキャンしていたときの 2 回目と同じ
     */
    private void addCall(String[] cols, MethodRef[] symbols, BlockNodes nodes, BlockGuards guards,
                         EdgeSpill spill) throws IOException {
        CallEdgeFact c = callOf(cols, symbols);
        if (c == null) {
            return;
        }
        int caller = methods.intern(c.caller());
        int callee = methods.intern(c.callee());
        countEdge(caller, callee);
        CallSiteValues.Row values = valuesOf(cols, nodes, guards);
        int qualifier = graph.internOrigin(c.qualifier());
        OriginRenderer renderer = nodes.renderer();
        int recv = graph.internOrigin(renderer.originOf(values.recv()));
        int args = graph.internOrigin(renderer.argOriginsOf(values.args()));
        int guard = graph.internOrigin(guards.guardOf(values.guard()));
        spill.write(caller, callee, c.callLine(), (byte) BindKind.of(c.callee().name(), c.calleeMods()),
                (byte) c.recvKind(), qualifier, recv, args, guard, graph.internHints(values.hints()));
    }

    /**
     * U 行 1 行を、import からの推定候補があればエッジにする。
     * 型解決に失敗した呼び出しの一覧に出す行（使える候補が無い行）なら {@link #unresolved} に渡す
     */
    private void addUnresolved(String[] cols, MethodRef[] symbols, BlockNodes nodes, BlockGuards guards,
                               EdgeSpill spill) throws IOException {
        UnresolvedCallFact u = unresolvedOf(cols, symbols);
        if (u != null && u.hasUsableCandidate()) {
            int caller = methods.intern(u.caller());
            int callee = internGuessedCallee(u);
            countEdge(caller, callee);
            CallSiteValues.Row values = valuesOf(cols, nodes, guards);
            OriginRenderer renderer = nodes.renderer();
            int recv = graph.internOrigin(renderer.originOf(values.recv()));
            int args = graph.internOrigin(renderer.argOriginsOf(values.args()));
            int guard = graph.internOrigin(guards.guardOf(values.guard()));
            spill.write(caller, callee, u.line(), (byte) BindKind.GUESSED, (byte) u.recvKind(),
                    -1, recv, args, guard, graph.internHints(values.hints()));
        }
        if (unresolved != null) {
            // 一覧には、呼び出し元の記号が壊れていても行を捨てず、呼び出し元不明として出す（OUTSIDE_METHOD の行と同じ）。
            // グラフの側はその行を警告して使わないので、ここで落とすと黙って消える
            UnresolvedCallFact r = UnresolvedCallFact.fromRowKeepingUnknownCaller(cols, symbols);
            // import 推定でエッジになっている行は、call-hierarchy.csv 側に
            // 「[EXTERNAL] import から型名を推定（未検証）」の注記付きで出ているので、一覧には出さない
            // （F 行の未解決数と同じ定義。呼び出し元が引けなかった行はエッジになっていないので出す）
            if (r != null && !r.hasUsableCandidate()) {
                unresolved.add(r.line(), (r.caller() == null) ? "" : r.caller().key(),
                        r.expression(), r.reason());
            }
        }
    }

    /**
     * U行の候補（レシーバの単純名と一致する単一型 import）を呼び出し先としてID化する。
     * パッケージ名は FQN の最後のドットまで、とみなす（推定なので厳密ではない）
     */
    private int internGuessedCallee(UnresolvedCallFact u) {
        String fqn = u.candidate();
        return methods.intern(Names.packageOf(fqn), fqn, u.expression(), "");
    }

    private static void ensure(IntArray a, int id) {
        while (a.size() <= id) {
            a.add(0);
        }
    }

    // ------------------------------------------------------------
    // offsets（累積和）とエッジ配列の確保
    // ------------------------------------------------------------

    private void allocateEdges() {
        int n = methods.size();
        graph.offsets = new int[n + 1];
        for (int i = 0; i < n; i++) {
            int d = (i < outDegree.size()) ? outDegree.get(i) : 0;
            graph.offsets[i + 1] = graph.offsets[i] + d;
        }
        int edges = (int) edgeCount;
        graph.calleeIds = new int[edges];
        graph.callLines = new int[edges];
        graph.bindKinds = new byte[edges];
        graph.recvKinds = new byte[edges];
        graph.edgeHint = new int[edges];
        Arrays.fill(graph.edgeHint, -1);
        graph.recvOriginIds = new int[edges];
        graph.argOriginIds = new int[edges];
        graph.guardIds = new int[edges];
        graph.qualifierIds = new int[edges];

        // R行（戻り値の出所）をメソッドIDの配列に移す。
        // スキャンで全メソッドがID化されているのでここで確定できる。
        // 追跡できない return（U）も含めて持ち、「1つでも不明なら不定」の判定は
        // DataflowResolver.factoryReturnOrigin() が行う
        graph.returnOrigins = new String[n][];
        for (Map.Entry<Integer, List<String>> e : returnsById.entrySet()) {
            int id = e.getKey();
            if (id < n) {
                graph.returnOrigins[id] = e.getValue().toArray(new String[0]);
            }
        }
        // @Bean メソッドが登録する型は、R行（戻り値の出所）が揃って初めて決まる
        graph.beans.resolveBeanMethods(graph);
    }

    // ------------------------------------------------------------
    // 配置: 一時ファイルのエッジを CSR の位置に置く
    // ------------------------------------------------------------

    /**
     * 一時ファイルの記録を先頭から読み、{@code cursor[呼び出し元]++} の位置に置く。
     * 記録はキャッシュ上の順に並んでいるので、呼び出し元ごとのエッジの並びはファイル上の順になる
     */
    private void placeEdges(EdgeSpill spill) throws IOException {
        int n = methods.size();
        int[] cursor = Arrays.copyOf(graph.offsets, n);
        int edges = (int) edgeCount;
        try (DataInputStream in = spill.reader()) {
            for (int placed = 0; placed < edges; placed++) {
                if ((placed & 0xFFFF) == 0) {
                    RunControl.checkCancelled();
                }
                int caller = in.readInt();
                int pos = cursor[caller]++;
                graph.calleeIds[pos] = in.readInt();
                graph.callLines[pos] = in.readInt();
                graph.bindKinds[pos] = in.readByte();
                graph.recvKinds[pos] = in.readByte();
                graph.qualifierIds[pos] = in.readInt();
                graph.recvOriginIds[pos] = in.readInt();
                graph.argOriginIds[pos] = in.readInt();
                graph.guardIds[pos] = in.readInt();
                graph.edgeHint[pos] = in.readInt();
            }
            if (in.read() >= 0) {
                // 数えた本数と書いた記録の数が食い違う（書き手の誤り）。黙って配列の外にずれないよう止める
                throw new IOException(Messages.format("graph.spillMismatch", edges));
            }
        } catch (EOFException e) {
            throw new IOException(Messages.format("graph.spillMismatch", edges), e);
        }
    }

    /**
     * エッジの記録を置く一時ファイル（キャッシュと同じフォルダ。{@link TempFiles}）。
     *
     * <p>1 本の記録は、呼び出し元・呼び出し先の ID、行、束縛の種別、レシーバの由来、修飾する型・
     * レシーバの出所・実引数の出所・条件の共有プールの番号と、証拠の表の番号（{@link CallGraph#internHints}）
     * （どれも無ければ -1）。固定長（34 バイト）。
     *
     * <p>最初の記録を書くときにファイルを作る（エッジが 1 本も無ければ作らない）。書くのも読むのも
     * 作ったときに開いた 1 つのチャネルで行い、名前で開き直さない（別の実行が残り物として消しても読める）。
     * {@link #close} で閉じて消す
     */
    private static final class EdgeSpill implements Closeable {

        private final Path cacheFile;
        private Path file;
        private FileChannel channel;
        private DataOutputStream out;

        EdgeSpill(Path cacheFile) {
            this.cacheFile = cacheFile;
        }

        void write(int caller, int callee, int line, byte bindKind, byte recvKind,
                   int qualifier, int recvOrigin, int argOrigins, int guard, int hints)
                throws IOException {
            if (out == null) {
                file = TempFiles.create(cacheFile, TempFiles.EDGES);
                channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
                out = new DataOutputStream(new BufferedOutputStream(Channels.newOutputStream(channel), 1 << 16));
            }
            out.writeInt(caller);
            out.writeInt(callee);
            out.writeInt(line);
            out.writeByte(bindKind);
            out.writeByte(recvKind);
            out.writeInt(qualifier);
            out.writeInt(recvOrigin);
            out.writeInt(argOrigins);
            out.writeInt(guard);
            out.writeInt(hints);
        }

        /** 書き終えた（読み直す前に、溜めた分をファイルへ出す。チャネルは開いたまま） */
        void finishWriting() throws IOException {
            if (out != null) {
                out.flush();
            }
        }

        /** 書いた記録を先頭から読む。1 本も書いていなければ空。閉じるとチャネルも閉じる */
        DataInputStream reader() throws IOException {
            if (channel == null) {
                return new DataInputStream(InputStream.nullInputStream());
            }
            channel.position(0);
            return new DataInputStream(new BufferedInputStream(Channels.newInputStream(channel), 1 << 16));
        }

        @Override
        public void close() {
            try {
                if (channel != null) {
                    channel.close();
                }
            } catch (IOException e) {
                Log.info(Messages.format("cache.tempNotDeleted", file, e));
            }
            TempFiles.delete(file);
        }
    }

    /**
     * 呼び出し箇所の値。値を読まない指定なら {@link CallSiteValues.Row#NONE}。
     * ノード番号・ガード番号がブロックの外を指していれば 1 度だけ警告する（その値は組み直せないので使われない。
     * 条件なら「条件なし」になるので、打ち切りには使われない）
     */
    private CallSiteValues.Row valuesOf(String[] cols, BlockNodes nodes, BlockGuards guards) {
        if (!readValues) {
            return CallSiteValues.Row.NONE;
        }
        CallSiteValues.Row values = CallSiteValues.Row.fromRow(cols);
        if (!nodes.inRange(values.recv(), true) || !nodes.argsInRange(values.args())
                || !guards.inRange(values.guard())) {
            warnBadReference();
        }
        return values;
    }

    /**
     * 1 ブロック分の値グラフ（N 行）。番号は並びの位置。
     * 出所の組み直し（{@link OriginRenderer}）はブロックの最初の呼び出し箇所で作る
     * （N 行は C 行・U 行より前に並ぶので、そのときには揃っている）。
     */
    private final class BlockNodes {

        private final List<ValueNode> nodes = new ArrayList<>();
        private OriginRenderer renderer;

        void clear() {
            nodes.clear();
            renderer = null;
        }

        /**
         * N 行を 1 つ足す。番号は並びの位置なので、読めない行や番号が並びと食い違う行でも
         * <b>読み飛ばさず</b>「追跡できない」ノードで場所を埋める。読み飛ばすと以降の番号が
         * 1 つずつずれ、別の値を別の呼び出し箇所に結びつけてしまう（ブロックの crc があるので
         * 実際には起きないが、起きたら 1 度だけ警告する）
         */
        void add(ValueNode n) {
            if (n == null || n.id() != nodes.size()) {
                warnBadReference();
                n = new ValueNode(nodes.size(), Origin.UNKNOWN, "", ValueNode.NONE, "", -1, "");
            }
            nodes.add(n);
            renderer = null;
        }

        /** このブロックの組み直し。作るときに、ノードどうしの参照がブロックに収まるかを 1 度だけ確かめる */
        OriginRenderer renderer() {
            if (renderer == null) {
                for (ValueNode n : nodes) {
                    if (!inRange(n.recv(), true) || !argsInRange(n.args())) {
                        warnBadReference();
                        break;
                    }
                }
                renderer = new OriginRenderer(nodes);
            }
            return renderer;
        }

        /**
         * ノードの頭（{@code 種別:値}。実引数リストは付けない）。{@link ValueNode#NONE} なら U。
         * ブロックの外を指していれば 1 度だけ警告して U（「追跡できない」は安全側）
         */
        String headOf(int id) {
            if (id == ValueNode.NONE) {
                return Origin.UNKNOWN_S;
            }
            if (!inRange(id, false)) {
                warnBadReference();
                return Origin.UNKNOWN_S;
            }
            ValueNode n = nodes.get(id);
            return Origin.of(n.kind(), n.value());
        }

        /** ノード番号のノード。ブロックに収まらなければ null */
        ValueNode at(int id) {
            return inRange(id, false) ? nodes.get(id) : null;
        }

        /** ノード番号がブロックに収まるか。{@code allowNone} なら {@link ValueNode#NONE} も収まるとみなす */
        boolean inRange(int id, boolean allowNone) {
            return (allowNone && id == ValueNode.NONE) || (id >= 0 && id < nodes.size());
        }

        /** 実引数の列（{@code 位置=番号} のカンマ区切り）の番号がどれもブロックに収まるか */
        boolean argsInRange(String args) {
            if (args.isEmpty()) {
                return true;
            }
            for (String entry : args.split(String.valueOf(ValueNode.ARG_SEP))) {
                int eq = entry.indexOf('=');
                if (eq < 0 || !inRange(ValueNode.intOf(entry.substring(eq + 1), Integer.MIN_VALUE), false)) {
                    return false;
                }
            }
            return true;
        }
    }
    /**
     * 1 ブロック分の条件の表（G 行）。ガード番号は並びの位置（0 から詰めて振ってある）。
     *
     * <p>読み手（{@link GuardEvaluator}・{@code jche.dataflow.DataflowBuilder}）はまだ以前の guard 列の文字列
     * （{@link Guard}。アトムを {@link Guard#ATOM_SEP} で、項目を {@link Guard#FIELD_SEP} で区切る）を受け取るので、
     * C 行・U 行が使うたびにその形へ組み直す（同じガードは 1 度だけ）。subject はノードの頭
     * （{@code A:0} / {@code V:true}）で、以前の書き手が持っていた出所の頭（{@link Origin#head}）と同じ形にする。
     * 期待値は切り詰めずに渡し、以前の切り詰めの再現は {@link GuardEvaluator} が行う。
     *
     * <p>壊れた行（番号が並びと食い違う・列が足りない）は 1 度だけ警告して、そのアトムを使わない。
     * アトムが減ったガードは条件が弱くなるだけなので、打ち切りが増えることはない（安全側）
     */
    private final class BlockGuards {

        private final BlockNodes nodes;
        private final List<List<Guard.Atom>> guards = new ArrayList<>();
        /** 組み直した文字列（番号 -&gt; 文字列。まだなら null） */
        private final List<String> rendered = new ArrayList<>();

        BlockGuards(BlockNodes nodes) {
            this.nodes = nodes;
        }

        void clear() {
            guards.clear();
            rendered.clear();
        }

        /** G 行を 1 つ足す。番号は今のガード（続きのアトム）か、次のガード（新しい番号）のどちらか */
        void add(String[] cols) {
            int id = Guard.Atom.guardIdOf(cols);
            Guard.Atom atom = Guard.Atom.fromRow(cols);
            if (atom == null || id < 0 || id > guards.size()) {
                warnBadReference();
                return;
            }
            if (id == guards.size()) {
                guards.add(new ArrayList<>(2));
                rendered.add(null);
            } else if (id != guards.size() - 1) {
                warnBadReference();   // 前のガードに戻っている。並びが崩れているので使わない
                return;
            }
            guards.get(id).add(atom);
        }

        /** ガード番号がブロックに収まるか（-1 は「条件なし」で、収まるとみなす） */
        boolean inRange(int id) {
            return id == -1 || (id >= 0 && id < guards.size());
        }

        /** ガード番号の条件を、読み手に渡す文字列にする。条件なし・ブロックの外なら空文字 */
        String guardOf(int id) {
            if (id < 0 || id >= guards.size()) {
                return "";
            }
            String known = rendered.get(id);
            if (known != null) {
                return known;
            }
            List<String> atoms = new ArrayList<>(guards.get(id).size());
            for (Guard.Atom a : guards.get(id)) {
                // subject はノードの頭。ノードが無い・ブロックの外なら U（読み手は判定しない）
                String subject = Origin.head(nodes.headOf(a.subject()));
                atoms.add(Guard.atom(a.op(), subject, Guard.values(a.values()), a.text()));
            }
            String guard = Guard.join(atoms);
            rendered.set(id, guard);
            return guard;
        }
    }
}
