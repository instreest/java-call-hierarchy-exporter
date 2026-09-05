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
package jche.graph;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jche.cache.CacheFormat;
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
     */
    public static CallGraph build(Path cacheFile, List<String> sourceFolderOrder) throws IOException {
        CallGraphBuilder b = new CallGraphBuilder();
        b.graph.sourceFolderOrder = sourceFolderOrder;
        b.firstPass(cacheFile);
        b.allocateEdges();
        b.secondPass(cacheFile);
        return b.graph;
    }

    private static BufferedReader open(Path cacheFile) throws IOException {
        BufferedReader in = Files.newBufferedReader(cacheFile, StandardCharsets.UTF_8);
        in.readLine();  // バージョン行を読み飛ばす
        return in;
    }

    // ------------------------------------------------------------
    // 1回目: ID化と本数カウント
    // ------------------------------------------------------------

    private void firstPass(Path cacheFile) throws IOException {
        try (BufferedReader in = open(cacheFile)) {
            String currentFile = null;
            String line;
            while ((line = in.readLine()) != null) {
                char rowType = CacheFormat.rowTypeOf(line);
                switch (rowType) {
                    case CacheFormat.ROW_FILE -> {
                        // ファイル単位で完結する判定（フィールド注入）をここで確定する
                        fields.flushInto(graph.fieldOrigins);
                        String[] cols = CacheFormat.columnsOf(line);
                        currentFile = (cols.length >= 2) ? cols[1] : null;
                    }
                    case CacheFormat.ROW_TYPE -> {
                        TypeFact t = TypeFact.fromRow(CacheFormat.columnsOf(line));
                        if (t != null) {
                            graph.hierarchy.add(t);
                        }
                    }
                    case CacheFormat.ROW_HINT -> {
                        HintFact h = HintFact.fromRow(CacheFormat.columnsOf(line));
                        if (h != null) {
                            graph.hintsByScope.computeIfAbsent(h.callerKey() + "|" + h.scopeKey(),
                                    k -> new ArrayList<>()).add(new Hint(h.kind(), h.value()));
                        }
                    }
                    case CacheFormat.ROW_METHOD_DECL -> {
                        MethodDeclFact d = MethodDeclFact.fromRow(CacheFormat.columnsOf(line));
                        if (d != null) {
                            int id = methods.intern(d.ref());
                            ensure(outDegree, id);
                            methods.setDeclaration(id, currentFile, d.declLine(), d.hasBody());
                            fields.declaration(d);
                        }
                    }
                    case CacheFormat.ROW_CALL -> {
                        CallEdgeFact c = CallEdgeFact.fromRow(CacheFormat.columnsOf(line));
                        if (c != null) {
                            countEdge(methods.intern(c.caller()), methods.intern(c.callee()));
                        }
                    }
                    case CacheFormat.ROW_UNRESOLVED -> {
                        UnresolvedCallFact u = UnresolvedCallFact.fromRow(CacheFormat.columnsOf(line));
                        if (u != null && u.hasUsableCandidate()) {
                            countEdge(methods.intern(u.caller()), internGuessedCallee(u));
                        }
                    }
                    case CacheFormat.ROW_FIELD_DECL -> {
                        FieldDeclFact v = FieldDeclFact.fromRow(CacheFormat.columnsOf(line));
                        if (v != null) {
                            fields.field(v);
                        }
                    }
                    case CacheFormat.ROW_FIELD_ASSIGN -> {
                        FieldAssignFact j = FieldAssignFact.fromRow(CacheFormat.columnsOf(line));
                        if (j != null) {
                            fields.assignment(j);
                        }
                    }
                    case CacheFormat.ROW_FUNCTIONAL_IMPL -> {
                        FunctionalImplFact m = FunctionalImplFact.fromRow(CacheFormat.columnsOf(line));
                        if (m != null && !m.ifaceMethodKey().isEmpty()) {
                            graph.functionalImpls.add(m.ifaceMethodKey());
                        }
                    }
                    case CacheFormat.ROW_RETURN -> {
                        ReturnFact r = ReturnFact.fromRow(CacheFormat.columnsOf(line));
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
        int dot = fqn.lastIndexOf('.');
        String pkg = (dot >= 0) ? fqn.substring(0, dot) : "";
        return methods.intern(pkg, fqn, u.expression(), "");
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
        cursor = Arrays.copyOf(graph.offsets, n == 0 ? 0 : n);
    }

    // ------------------------------------------------------------
    // 2回目: エッジを流し込む
    // ------------------------------------------------------------

    private void secondPass(Path cacheFile) throws IOException {
        try (BufferedReader in = open(cacheFile)) {
            String line;
            while ((line = in.readLine()) != null) {
                char rowType = CacheFormat.rowTypeOf(line);
                if (rowType == CacheFormat.ROW_CALL) {
                    CallEdgeFact c = CallEdgeFact.fromRow(CacheFormat.columnsOf(line));
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
                    UnresolvedCallFact u = UnresolvedCallFact.fromRow(CacheFormat.columnsOf(line));
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
