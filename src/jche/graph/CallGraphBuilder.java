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
import jche.cache.DataflowBlockReader;
import jche.cache.FieldAssignFact;
import jche.cache.FieldDeclFact;
import jche.cache.FunctionalImplFact;
import jche.cache.HintFact;
import jche.cache.MethodDeclFact;
import jche.cache.MethodRef;
import jche.cache.ReturnFact;
import jche.cache.TypeFact;
import jche.cache.UnresolvedCallFact;
import jche.extension.Hint;
import jche.util.Log;
import jche.util.Names;
import jche.util.RunControl;

/**
 * キャッシュファイルをスキャンして {@link CallGraph} を構築する。
 * <pre>
 *   1回目 … メソッドをID化し、呼び出し元ごとの本数を数える。
 *           型階層・フィールド注入の判定・戻り値の出所・拡張の証拠もこの回で済ませる
 *   2回目 … 数えた本数から offsets を作り、実際のエッジを流し込む
 * </pre>
 * どちらもストリーミングなので、キャッシュ全体をヒープに載せない。
 *
 * <h2>値は dataflow 側から、歩調を合わせて読む</h2>
 * 値に関わる事実（呼び出しの出所・ガード・フィールドへの代入・戻り値の出所・拡張の証拠）は
 * dataflow 側のキャッシュにある（{@code docs/cache-split-qa.md} の Q11）。
 * 2 つのキャッシュは常に同じブロックを同じ順で持つので、analysis 側の F 行に出会うたびに
 * dataflow 側も同じブロックまで読み進める（{@link DataflowBlockReader}）。
 * ヒープに載るのは 1 ブロック分だけ。
 *
 * <p>呼び出し箇所の値（P 行）は C 行・U 行と<b>同じ数・同じ順</b>で並ぶので、ブロックの中では
 * 位置で対応が取れる。鍵（行番号・呼び出し元・呼び出し先の表示名）も持っているので、
 * 位置が合っているかをそこで検算する（食い違えば値を使わない側に倒す）。
 *
 * <p>dataflow 側を読まない指定（{@code dataflow.enabled=false}）のときは、
 * 値が無いものとして組む。具象クラスの解決は CHA まで、条件分岐の打ち切りは起きない
 *
 * 読み手の判断として、U行（型解決失敗）に import からの推定候補があれば、
 * それをエッジにする（クラスパス不足で階層から消えるより、未検証と分かる形で残す方針）。
 */
public final class CallGraphBuilder {

    private final CallGraph graph = new CallGraph();
    private final MethodTable methods = graph.methods;

    /** 1回目のスキャンで数える、呼び出し元ごとのエッジ数 */
    private final IntArray outDegree = new IntArray(1 << 16);
    private final Map<Integer, List<String>> returnsById = new HashMap<>();
    private final FieldFacts fields = new FieldFacts();
    private long edgeCount;

    /** 2回目のスキャンで、呼び出し元ごとに次に書く位置 */
    private int[] cursor;
    /** P 行の並びが合わなかったことを警告したか（1度だけ出す） */
    private boolean warnedAboutJoin;

    private CallGraphBuilder() {
    }

    /**
     * @param sourceFolderOrder 起点の並び替えに使うソースフォルダの順（プロジェクトルートからの相対パス）
     * @param beans             DIコンテナのBean定義の取り込み先（使わないなら {@link SpringBeans#DISABLED}）
     */
    public static CallGraph build(Path cacheFile, Path dataflowCacheFile,
                                 List<String> sourceFolderOrder, SpringBeans beans)
            throws IOException {
        CallGraphBuilder b = new CallGraphBuilder();
        b.graph.sourceFolderOrder = sourceFolderOrder;
        b.graph.beans = beans;
        RunControl.progress("グラフ構築", 0, 2);
        b.firstPass(cacheFile, dataflowCacheFile);
        RunControl.checkCancelled();
        b.allocateEdges();
        RunControl.progress("グラフ構築", 1, 2);
        b.secondPass(cacheFile, dataflowCacheFile);
        b.graph.finishBuild();
        RunControl.progress("グラフ構築", 2, 2);
        return b.graph;
    }

    // ------------------------------------------------------------
    // 1回目: ID化と本数カウント
    // ------------------------------------------------------------

    private void firstPass(Path cacheFile, Path dataflowCacheFile) throws IOException {
        try (CacheReader in = CacheReader.open(cacheFile);
             DataflowBlockReader flow = openFlow(dataflowCacheFile)) {
            String currentFile = null;
            while (in.next()) {
                switch (in.rowType()) {
                    case CacheFormat.ROW_FILE -> {
                        // ファイル単位で完結する判定（フィールド注入）をここで確定する。
                        // 代入（J行）は dataflow 側にあり、宣言（V行）より先に読めてしまうので、
                        // ブロックを読み終えたこの時点で渡す
                        applyPendingAssigns();
                        fields.flushInto(graph.fieldOrigins);
                        currentFile = in.filePath();
                        // dataflow 側の同じブロックへ進み、値の事実を取り込む
                        readBlockValues(flow, currentFile);
                    }
                    case CacheFormat.ROW_TYPE -> {
                        TypeFact t = TypeFact.fromRow(in.columns());
                        if (t != null) {
                            graph.hierarchy.add(t);
                            graph.beans.type(t);
                        }
                    }
                    case CacheFormat.ROW_METHOD_DECL -> {
                        MethodDeclFact d = MethodDeclFact.fromRow(in.columns());
                        if (d != null) {
                            int id = methods.intern(d.ref());
                            ensure(outDegree, id);
                            methods.setDeclaration(id, currentFile, d.declLine(), d.hasBody());
                            fields.declaration(d);
                            graph.beans.method(id, d);
                        }
                    }
                    case CacheFormat.ROW_CALL -> {
                        CallEdgeFact c = CallEdgeFact.fromRow(in.columns());
                        if (c != null) {
                            countEdge(methods.intern(c.caller()), methods.intern(c.callee()));
                        }
                    }
                    case CacheFormat.ROW_UNRESOLVED -> {
                        UnresolvedCallFact u = UnresolvedCallFact.fromRow(in.columns());
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
                        FunctionalImplFact m = FunctionalImplFact.fromRow(in.columns());
                        if (m != null && !m.ifaceMethodKey().isEmpty()) {
                            graph.functionalImpls.add(m.ifaceMethodKey());
                        }
                    }
                    default -> {
                        // I行は差分更新のためだけの行で、読み手は使わない
                    }
                }
            }
            applyPendingAssigns();
            fields.flushInto(graph.fieldOrigins);
        }
        graph.hierarchy.sortForDeterminism();
        Log.info("収集: 型 " + graph.hierarchy.size()
                + " / メソッド " + methods.size() + " / エッジ " + edgeCount);
        if (edgeCount > Integer.MAX_VALUE) {
            throw new IOException("エッジ数が多すぎます: " + edgeCount);
        }
    }

    /** dataflow 側を開く。読まない指定なら null（{@link #readBlockValues} が何もしない） */
    private static DataflowBlockReader openFlow(Path dataflowCacheFile) throws IOException {
        return (dataflowCacheFile == null) ? null : DataflowBlockReader.open(dataflowCacheFile);
    }

    /** 今のブロックのフィールドへの代入（J行）。宣言が揃ってから {@link #fields} に渡す */
    private List<FieldAssignFact> pendingAssigns = List.of();

    /** 溜めた代入を渡して捨てる。{@code fields.flushInto} の直前に呼ぶ */
    private void applyPendingAssigns() {
        for (FieldAssignFact j : pendingAssigns) {
            fields.assignment(j);
        }
        pendingAssigns = List.of();
    }

    /**
     * 1回目のスキャンで、dataflow 側の同じブロックから値の事実を取り込む。
     *
     * フィールドへの代入（J行）はそのブロックの V 行と組で判定するのでここで渡す。
     * 戻り値の出所（R行）と拡張の証拠（X行）はメソッド・証拠の鍵で集約するだけなので、
     * どのブロックで出会っても同じ結果になる
     */
    private void readBlockValues(DataflowBlockReader flow, String path) throws IOException {
        if (flow == null || path == null) {
            return;
        }
        DataflowBlockReader.Block block = flow.advanceTo(path);
        // 代入はフィールドの宣言（analysis 側の V 行）が揃ってからでないと拾われないので、
        // ここでは溜めるだけにして、ブロックを読み終えてから渡す
        pendingAssigns = block.fieldAssigns();
        for (ReturnFact r : block.returns()) {
            int id = methods.intern(r.method());
            ensure(outDegree, id);
            List<String> origins = returnsById.computeIfAbsent(id, k -> new ArrayList<>(2));
            if (!origins.contains(r.origin())) {
                origins.add(r.origin());
            }
        }
        for (HintFact h : block.hints()) {
            graph.hintsByScope.computeIfAbsent(h.callerKey() + "|" + h.scopeKey(),
                    k -> new ArrayList<>()).add(new Hint(h.kind(), h.value()));
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

    private void secondPass(Path cacheFile, Path dataflowCacheFile) throws IOException {
        try (CacheReader in = CacheReader.open(cacheFile);
             DataflowBlockReader flow = openFlow(dataflowCacheFile)) {
            // 今のブロックの P 行と、そこを何件目まで使ったか。C 行・U 行と同じ順に並ぶ
            List<CallSiteValues> blockValues = List.of();
            // 同じブロックの値グラフ（N 行）。出所の文字列はここから組み直す
            OriginRenderer renderer = new OriginRenderer(List.of());
            int valueIndex = 0;
            joinKeyCounts.clear();
            while (in.next()) {
                char rowType = in.rowType();
                if (rowType == CacheFormat.ROW_FILE) {
                    DataflowBlockReader.Block block = (flow == null)
                            ? DataflowBlockReader.Block.empty() : flow.advanceTo(in.filePath());
                    blockValues = block.callSiteValues();
                    renderer = new OriginRenderer(block.valueNodes());
                    valueIndex = 0;
                    joinKeyCounts.clear();
                    continue;
                }
                if (rowType != CacheFormat.ROW_CALL && rowType != CacheFormat.ROW_UNRESOLVED) {
                    continue;
                }
                // C 行・U 行 1 行につき P 行 1 行。エッジにならない U 行でも位置を進める
                CallSiteValues values = (valueIndex < blockValues.size())
                        ? blockValues.get(valueIndex) : CallSiteValues.NONE;
                valueIndex++;
                if (rowType == CacheFormat.ROW_CALL) {
                    CallEdgeFact c = CallEdgeFact.fromRow(in.columns());
                    if (c == null) {
                        continue;
                    }
                    values = verified(values, c.callLine(), c.caller(), c.callee().name());
                    int pos = cursor[methods.intern(c.caller())]++;
                    graph.calleeIds[pos] = methods.intern(c.callee());
                    graph.callLines[pos] = c.callLine();
                    graph.bindKinds[pos] = (byte) BindKind.of(c.callee().name(), c.calleeMods());
                    graph.fillCallSite(pos, c.caller().key(), values.recvKey(), c.recvKind(),
                            renderer.originOf(values.recv()),
                            renderer.argOriginsOf(values.args()), values.guard());
                } else {
                    UnresolvedCallFact u = UnresolvedCallFact.fromRow(in.columns());
                    if (u == null) {
                        continue;
                    }
                    // エッジにならない U 行でも検算は通す。同じ鍵の通し番号を数え進めるため
                    values = verified(values, u.line(), u.caller(), u.expression());
                    if (!u.hasUsableCandidate()) {
                        continue;
                    }
                    int pos = cursor[methods.intern(u.caller())]++;
                    graph.calleeIds[pos] = internGuessedCallee(u);
                    graph.callLines[pos] = u.line();
                    graph.bindKinds[pos] = (byte) BindKind.GUESSED;
                    graph.fillCallSite(pos, u.caller().key(), values.recvKey(), u.recvKind(),
                            renderer.originOf(values.recv()),
                            renderer.argOriginsOf(values.args()), values.guard());
                }
            }
        }
    }

    /** 今のブロックで、同じ鍵（行番号・呼び出し元・表示名）の C 行・U 行を何本見たか */
    private final Map<String, Integer> joinKeyCounts = new HashMap<>();

    /**
     * 位置で取った P 行が、本当にこの呼び出し箇所のものかを検算する。
     *
     * P 行は C 行・U 行と同じ順に書かれるので位置で対応が取れるが、鍵（行番号・呼び出し元・
     * 呼び出し先の表示名）と通し番号も持っているので突き合わせる。通し番号は
     * 書き手（{@code CallSiteRecorder}）と同じく「同じ鍵をこのブロックで何本見たか」で数え、
     * {@code f(g(), g())} のようにまったく同じ鍵が並ぶ箇所でもずれを検出できるようにする
     * （位置だけの対応が最も弱いのがそこなので）。
     * 食い違っていれば<b>値を使わない</b>方に倒す
     * （呼び出しは消えず、具象クラスの解決が CHA 止まりになるだけ）。
     * 2 つのキャッシュが対である限り起きないので、起きたら1度だけ警告を出す
     */
    private CallSiteValues verified(CallSiteValues values, int line, MethodRef caller, String calleeName) {
        String expected = line + "\u0000" + ((caller == null) ? "" : caller.key()) + "\u0000" + calleeName;
        // 値が無くても数える。数え漏らすと、それ以降の同じ鍵の通し番号が全部ずれる
        int ordinal = joinKeyCounts.merge(expected, 1, Integer::sum) - 1;
        if (values == CallSiteValues.NONE) {
            return values;
        }
        if (expected.equals(values.joinKey()) && values.ordinal() == ordinal) {
            return values;
        }
        if (!warnedAboutJoin) {
            warnedAboutJoin = true;
            Log.warn("[cache] データフローのキャッシュの並びが呼び出し箇所と合いません（"
                    + expected.replace('\u0000', ' ') + " #" + ordinal + "）。このぶんの値は使いません");
        }
        return CallSiteValues.NONE;
    }
}
