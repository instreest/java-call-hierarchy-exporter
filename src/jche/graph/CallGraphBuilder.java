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
import jche.cache.FieldAssignFact;
import jche.cache.FieldDeclFact;
import jche.cache.FunctionalImplFact;
import jche.cache.HintFact;
import jche.cache.MethodDeclFact;
import jche.cache.ReturnFact;
import jche.cache.TypeFact;
import jche.cache.UnresolvedCallFact;
import jche.extension.Hint;
import jche.util.Log;
import jche.util.Names;

/**
 * キャッシュファイルを2回スキャンして {@link CallGraph} を構築する。
 * <pre>
 *   1回目 … メソッドをID化し、呼び出し元ごとの本数を数える。型階層・証拠・戻り値・
 *           フィールド注入の判定もこの回で済ませる
 *   2回目 … 数えた本数から offsets を作り、実際のエッジを流し込む
 * </pre>
 * どちらもストリーミングなので、キャッシュ全体をヒープに載せない。
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

    private CallGraphBuilder() {
    }

    /**
     * @param sourceFolderOrder 起点の並び替えに使うソースフォルダの順（プロジェクトルートからの相対パス）
     * @param beans             DIコンテナのBean定義の取り込み先（使わないなら {@link SpringBeans#DISABLED}）
     */
    public static CallGraph build(Path cacheFile, List<String> sourceFolderOrder, SpringBeans beans)
            throws IOException {
        CallGraphBuilder b = new CallGraphBuilder();
        b.graph.sourceFolderOrder = sourceFolderOrder;
        b.graph.beans = beans;
        b.firstPass(cacheFile);
        b.allocateEdges();
        b.secondPass(cacheFile);
        b.graph.finishBuild();
        return b.graph;
    }

    // ------------------------------------------------------------
    // 1回目: ID化と本数カウント
    // ------------------------------------------------------------

    private void firstPass(Path cacheFile) throws IOException {
        try (CacheReader in = CacheReader.open(cacheFile)) {
            String currentFile = null;
            while (in.next()) {
                switch (in.rowType()) {
                    case CacheFormat.ROW_FILE -> {
                        // ファイル単位で完結する判定（フィールド注入）をここで確定する
                        fields.flushInto(graph.fieldOrigins);
                        currentFile = in.filePath();
                    }
                    case CacheFormat.ROW_TYPE -> {
                        TypeFact t = TypeFact.fromRow(in.columns());
                        if (t != null) {
                            graph.hierarchy.add(t);
                            graph.beans.type(t);
                        }
                    }
                    case CacheFormat.ROW_HINT -> {
                        HintFact h = HintFact.fromRow(in.columns());
                        if (h != null) {
                            graph.hintsByScope.computeIfAbsent(h.callerKey() + "|" + h.scopeKey(),
                                    k -> new ArrayList<>()).add(new Hint(h.kind(), h.value()));
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
                    case CacheFormat.ROW_FIELD_ASSIGN -> {
                        FieldAssignFact j = FieldAssignFact.fromRow(in.columns());
                        if (j != null) {
                            fields.assignment(j);
                        }
                    }
                    case CacheFormat.ROW_FUNCTIONAL_IMPL -> {
                        FunctionalImplFact m = FunctionalImplFact.fromRow(in.columns());
                        if (m != null && !m.ifaceMethodKey().isEmpty()) {
                            graph.functionalImpls.add(m.ifaceMethodKey());
                        }
                    }
                    case CacheFormat.ROW_RETURN -> {
                        ReturnFact r = ReturnFact.fromRow(in.columns());
                        if (r != null) {
                            int id = methods.intern(r.method());
                            ensure(outDegree, id);
                            List<String> origins = returnsById.computeIfAbsent(id, k -> new ArrayList<>(2));
                            if (!origins.contains(r.origin())) {
                                origins.add(r.origin());
                            }
                        }
                    }
                    default -> {
                        // I行・A行は読み手が使わない
                    }
                }
            }
            fields.flushInto(graph.fieldOrigins);
        }
        graph.hierarchy.sortForDeterminism();
        Log.info("収集: 型 " + graph.hierarchy.size()
                + " / メソッド " + methods.size() + " / エッジ " + edgeCount);
        if (edgeCount > Integer.MAX_VALUE) {
            throw new IOException("エッジ数が多すぎます: " + edgeCount);
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

    private void secondPass(Path cacheFile) throws IOException {
        try (CacheReader in = CacheReader.open(cacheFile)) {
            while (in.next()) {
                char rowType = in.rowType();
                if (rowType == CacheFormat.ROW_CALL) {
                    CallEdgeFact c = CallEdgeFact.fromRow(in.columns());
                    if (c == null) {
                        continue;
                    }
                    int pos = cursor[methods.intern(c.caller())]++;
                    graph.calleeIds[pos] = methods.intern(c.callee());
                    graph.callLines[pos] = c.callLine();
                    graph.bindKinds[pos] = (byte) BindKind.of(c.callee().name(), c.calleeMods());
                    graph.fillCallSite(pos, c.caller().key(), c.recvKey(), c.recvKind(),
                            c.recvOrigin(), c.argOrigins());
                } else if (rowType == CacheFormat.ROW_UNRESOLVED) {
                    UnresolvedCallFact u = UnresolvedCallFact.fromRow(in.columns());
                    if (u == null || !u.hasUsableCandidate()) {
                        continue;
                    }
                    int pos = cursor[methods.intern(u.caller())]++;
                    graph.calleeIds[pos] = internGuessedCallee(u);
                    graph.callLines[pos] = u.line();
                    graph.bindKinds[pos] = (byte) BindKind.GUESSED;
                    graph.fillCallSite(pos, u.caller().key(), u.recvKey(), u.recvKind(),
                            u.recvOrigin(), u.argOrigins());
                }
            }
        }
    }
}
