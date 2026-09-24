// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import java.io.IOException;
import java.nio.file.Path;
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
import jche.cache.HintFact;
import jche.cache.MethodDeclFact;
import jche.cache.MethodRef;
import jche.cache.ModifierTokens;
import jche.cache.Origin;
import jche.cache.OverrideFact;
import jche.cache.ReturnFact;
import jche.cache.SymbolTable;
import jche.cache.TypeFact;
import jche.cache.UnresolvedCallFact;
import jche.cache.ValueNode;
import jche.extension.Hint;
import jche.util.Log;
import jche.util.Messages;
import jche.util.Names;
import jche.util.RunControl;

/**
 * キャッシュファイルをスキャンして {@link CallGraph} を構築する。
 * <pre>
 *   1回目 … メソッドをID化し、呼び出し元ごとの本数を数える。
 *           型階層・フィールド注入の判定・戻り値の出所・証拠（X 行）もこの回で済ませる
 *   2回目 … 数えた本数から offsets を作り、実際のエッジを流し込む
 * </pre>
 * どちらもストリーミングなので、キャッシュ全体をヒープに載せない。ヒープに載るのは
 * 1 ブロック分の記号表（S 行）と値グラフ（N 行）だけ。
 *
 * <h2>ブロックの読み方</h2>
 * メソッドを指す列は、ブロックの記号表（S 行）の番号で書かれている（{@link SymbolTable}）。
 * S 行を読んだ時点ではメソッドを ID 化せず、参照する行を読んだときに ID 化する。
 * {@link MethodTable} の ID は初めて ID 化した順に振られ、ID の順は出力の並びの同点決着に使われるので、
 * ID 化の順は「戻り値（R 行）→ 宣言（D 行）→ 上書き（O 行）→ 呼び出し（C・U 行。呼び出し元、呼び出し先の順）」
 * で固定している（書き手もブロックの行をこの順に並べる）。M 行・A 行の呼び出し元は ID 化しない。
 *
 * <p>呼び出し箇所の値（レシーバ・実引数・識別キー・ガード）は C 行・U 行の末尾の列にある
 * （{@link CallSiteValues}）。ノード番号は同じブロックの N 行を指し、出所の文字列は
 * {@link OriginRenderer} がそこから組み直す。フィールドへの代入（J 行）は同じブロックの
 * V 行・D 行と組で判定するので、ブロックを読み終えてから渡す。
 *
 * <p>値を読まない指定（{@code dataflow.enabled=false}）のときは、N・R・J・X 行と
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
    /** 値（N・R・J・X 行と呼び出し箇所の値）を読むか（{@code dataflow.enabled}） */
    private final boolean readValues;

    /** 1回目のスキャンで数える、呼び出し元ごとのエッジ数 */
    private final IntArray outDegree = new IntArray(1 << 16);
    private final Map<Integer, List<String>> returnsById = new HashMap<>();
    private final FieldFacts fields = new FieldFacts();
    private long edgeCount;

    /** 2回目のスキャンで、呼び出し元ごとに次に書く位置 */
    private int[] cursor;
    /** ブロックの外を指す番号に出会ったことを警告したか（1度だけ出す） */
    private boolean warnedAboutReference;

    private CallGraphBuilder(Path cacheFile, boolean readValues) {
        this.cacheFile = cacheFile;
        this.readValues = readValues;
    }

    /**
     * @param readValues        値（値グラフ・戻り値の出所・フィールドへの代入・証拠・呼び出し箇所の値）を
     *                          読むか。{@code dataflow.enabled=false} なら false
     * @param sourceFolderOrder 起点の並び替えに使うソースフォルダの順（プロジェクトルートからの相対パス）
     * @param beans             DIコンテナのBean定義の取り込み先（使わないなら {@link SpringBeans#DISABLED}）
     */
    public static CallGraph build(Path cacheFile, boolean readValues,
                                 List<String> sourceFolderOrder, SpringBeans beans)
            throws IOException {
        CallGraphBuilder b = new CallGraphBuilder(cacheFile, readValues);
        b.graph.sourceFolderOrder = sourceFolderOrder;
        b.graph.beans = beans;
        RunControl.progress(Messages.get("graph.progress.build"), 0, 2);
        b.firstPass();
        RunControl.checkCancelled();
        b.allocateEdges();
        RunControl.progress(Messages.get("graph.progress.build"), 1, 2);
        b.secondPass();
        b.graph.finishBuild();
        RunControl.progress(Messages.get("graph.progress.build"), 2, 2);
        return b.graph;
    }

    // ------------------------------------------------------------
    // 1回目: ID化と本数カウント
    // ------------------------------------------------------------

    private void firstPass() throws IOException {
        try (CacheReader in = CacheReader.open(cacheFile)) {
            String currentFile = null;
            SymbolTable.Reader symbols = new SymbolTable.Reader();
            while (in.next()) {
                switch (in.rowType()) {
                    case CacheFormat.ROW_FILE -> {
                        // ファイル単位で完結する判定（フィールド注入）を、読み終えた前のブロックについて確定する。
                        // 代入（J行）はブロックの後ろにあるので、宣言（V行・D行）が揃ったこの時点で渡す
                        applyPendingAssigns();
                        fields.flushInto(graph.fieldOrigins);
                        currentFile = in.filePath();
                        symbols.clear();
                    }
                    case CacheFormat.ROW_SYMBOL -> symbols.add(in.columns());
                    case CacheFormat.ROW_RETURN -> {
                        // D 行より前に並ぶので、戻り値のあるメソッドは宣言より先に ID 化される
                        if (readValues && inRange(in.columns(), symbols.array())) {
                            readReturn(ReturnFact.fromRow(in.columns(), symbols.array()));
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
                        MethodDeclFact d = inRange(in.columns(), symbols.array())
                                ? MethodDeclFact.fromRow(in.columns(), symbols.array()) : null;
                        if (d != null) {
                            int id = methods.intern(d.ref());
                            ensure(outDegree, id);
                            methods.setDeclaration(id, currentFile, d.declLine(), d.endLine(),
                                    d.hasBody());
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
                    case CacheFormat.ROW_CALL -> {
                        CallEdgeFact c = callOf(in.columns(), symbols.array());
                        if (c != null) {
                            countEdge(methods.intern(c.caller()), methods.intern(c.callee()));
                        }
                    }
                    case CacheFormat.ROW_UNRESOLVED -> {
                        UnresolvedCallFact u = unresolvedOf(in.columns(), symbols.array());
                        if (u != null && u.hasUsableCandidate()) {
                            countEdge(methods.intern(u.caller()), internGuessedCallee(u));
                        }
                    }
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
                        // ここでは溜めるだけにして、ブロックを読み終えてから渡す
                        FieldAssignFact j = readValues ? FieldAssignFact.fromRow(in.columns()) : null;
                        if (j != null) {
                            pendingAssigns.add(j);
                        }
                    }
                    case CacheFormat.ROW_HINT -> {
                        // 証拠は呼び出し元と変数の鍵で集約するだけなので、どのブロックで出会っても同じ結果になる
                        HintFact h = readValues ? HintFact.fromRow(in.columns()) : null;
                        if (h != null) {
                            graph.hintsByScope.computeIfAbsent(h.callerKey() + "|" + h.scopeKey(),
                                    k -> new ArrayList<>()).add(new Hint(h.kind(), h.value()));
                        }
                    }
                    default -> {
                        // I行は差分更新のためだけの行、N行は2回目で読む、A行・K行は今の読み手は使わない
                    }
                }
            }
            applyPendingAssigns();
            fields.flushInto(graph.fieldOrigins);
        }
        graph.hierarchy.sortForDeterminism();
        Log.info(Messages.format("graph.collected", graph.hierarchy.size(), methods.size(), edgeCount));
        if (edgeCount > Integer.MAX_VALUE) {
            throw new IOException(Messages.format("graph.tooManyEdges", edgeCount));
        }
    }

    /** 今のブロックのフィールドへの代入（J行）。宣言が揃ってから {@link #fields} に渡す */
    private final List<FieldAssignFact> pendingAssigns = new ArrayList<>();

    /** 溜めた代入を渡して捨てる。{@code fields.flushInto} の直前に呼ぶ */
    private void applyPendingAssigns() {
        for (FieldAssignFact j : pendingAssigns) {
            fields.assignment(j);
        }
        pendingAssigns.clear();
    }

    /**
     * R 行（戻り値の出所）1 件。メソッドを ID 化し、出所を集める。
     * 戻り値の出所はメソッドの鍵で集約するだけなので、どのブロックで出会っても同じ結果になる
     */
    private void readReturn(ReturnFact r) {
        if (r == null) {
            return;
        }
        int id = methods.intern(r.method());
        ensure(outDegree, id);
        List<String> origins = returnsById.computeIfAbsent(id, k -> new ArrayList<>(2));
        if (!origins.contains(r.origin())) {
            origins.add(r.origin());
        }
    }

    /**
     * C 行を読む。記号がブロックの外を指す・列が足りなければ null。
     * 1 回目と 2 回目で同じ判定をする（食い違うと呼び出し元ごとの本数と書く位置がずれる）
     */
    private CallEdgeFact callOf(String[] cols, MethodRef[] symbols) {
        return inRange(cols, symbols) ? CallEdgeFact.fromRow(cols, symbols) : null;
    }

    /** U 行を読む。{@link #callOf} と同じく、1 回目と 2 回目で同じ判定をする */
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
        Arrays.fill(graph.recvOriginIds, -1);
        graph.argOriginIds = new int[edges];
        Arrays.fill(graph.argOriginIds, -1);
        graph.guardIds = new int[edges];
        Arrays.fill(graph.guardIds, -1);
        graph.qualifierIds = new int[edges];
        Arrays.fill(graph.qualifierIds, -1);

        // R行（戻り値の出所）をメソッドIDの配列に移す。
        // 1回目のスキャンで全メソッドがID化されているのでここで確定できる。
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

        cursor = Arrays.copyOf(graph.offsets, n == 0 ? 0 : n);
    }

    // ------------------------------------------------------------
    // 2回目: エッジを流し込む
    // ------------------------------------------------------------

    private void secondPass() throws IOException {
        try (CacheReader in = CacheReader.open(cacheFile)) {
            SymbolTable.Reader symbols = new SymbolTable.Reader();
            BlockNodes nodes = new BlockNodes();
            while (in.next()) {
                switch (in.rowType()) {
                    case CacheFormat.ROW_FILE -> {
                        symbols.clear();
                        nodes.clear();
                    }
                    case CacheFormat.ROW_SYMBOL -> symbols.add(in.columns());
                    case CacheFormat.ROW_VALUE_NODE -> {
                        if (readValues) {
                            nodes.add(ValueNode.fromRow(in.columns()));
                        }
                    }
                    case CacheFormat.ROW_CALL -> addCall(in.columns(), symbols.array(), nodes);
                    case CacheFormat.ROW_UNRESOLVED -> addUnresolved(in.columns(), symbols.array(), nodes);
                    default -> {
                        // ほかの行は1回目で読み終えている
                    }
                }
            }
        }
    }

    /** C 行 1 行をエッジにする */
    private void addCall(String[] cols, MethodRef[] symbols, BlockNodes nodes) {
        CallEdgeFact c = callOf(cols, symbols);
        if (c == null) {
            return;
        }
        CallSiteValues values = valuesOf(cols, nodes);
        int pos = cursor[methods.intern(c.caller())]++;
        graph.calleeIds[pos] = methods.intern(c.callee());
        graph.callLines[pos] = c.callLine();
        graph.bindKinds[pos] = (byte) BindKind.of(c.callee().name(), c.calleeMods());
        graph.setQualifier(pos, c.qualifier());
        OriginRenderer renderer = nodes.renderer();
        graph.fillCallSite(pos, c.caller().key(), values.recvKey(), c.recvKind(),
                renderer.originOf(values.recv()), renderer.argOriginsOf(values.args()), values.guard());
    }

    /** U 行 1 行を、import からの推定候補があればエッジにする */
    private void addUnresolved(String[] cols, MethodRef[] symbols, BlockNodes nodes) {
        UnresolvedCallFact u = unresolvedOf(cols, symbols);
        if (u == null || !u.hasUsableCandidate()) {
            return;
        }
        CallSiteValues values = valuesOf(cols, nodes);
        int pos = cursor[methods.intern(u.caller())]++;
        graph.calleeIds[pos] = internGuessedCallee(u);
        graph.callLines[pos] = u.line();
        graph.bindKinds[pos] = (byte) BindKind.GUESSED;
        OriginRenderer renderer = nodes.renderer();
        graph.fillCallSite(pos, u.caller().key(), values.recvKey(), u.recvKind(),
                renderer.originOf(values.recv()), renderer.argOriginsOf(values.args()), values.guard());
    }

    /**
     * 呼び出し箇所の値。値を読まない指定なら {@link CallSiteValues#NONE}。
     * ノード番号がブロックの外を指していれば 1 度だけ警告する（その値は組み直せないので使われない）
     */
    private CallSiteValues valuesOf(String[] cols, BlockNodes nodes) {
        if (!readValues) {
            return CallSiteValues.NONE;
        }
        CallSiteValues values = CallSiteValues.fromRow(cols);
        if (!nodes.inRange(values.recv(), true) || !nodes.argsInRange(values.args())) {
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
}
