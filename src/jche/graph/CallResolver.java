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

import java.util.ArrayDeque;
import java.util.List;

import jche.cache.HintFact;
import jche.extension.Hint;
import jche.extension.TypeCandidateProvider;
import jche.util.Log;

/**
 * エッジ単位の解決パイプライン。呼び出し先の具象候補を求める。
 * <pre>
 *   段0 STATIC_BOUND               仮想ディスパッチされない呼び出し
 *   段1 NO_OVERRIDE / SINGLE_IMPL  オーバーライド候補が1つに定まる
 *   段2 LOCAL_NEW(_MULTI)          同一メソッド内で new された型
 *   段3 CUSTOM_*                   拡張（ファクトリ・DI設定・外部リスト等）
 *   段4 DATAFLOW_*                 ファクトリの戻り値等から特定（経路非依存の分）
 *   段5 CHA                        候補が複数のまま（低確度）
 * </pre>
 * 段1で確定するならそれが最も確実なので、証拠より先に採用する。
 * リフレクション（Method.invoke / Class.forName / newInstance）は段0の前に試す。
 * 呼び出し先はjar内のAPIなので静的束縛に見えるが、実際に動くのは名前で指定されたメソッド。
 *
 * 経路（呼び出し元から渡された引数・コンストラクタ実引数）に依存する分は
 * {@link #resolveOnPath} で、経路を歩く側（jche.report.StreamingTreeWalker）がもう一度試す。
 */
public final class CallResolver {

    private final CallGraph graph;
    private final MethodTable methods;
    private final DataflowResolver dataflow;
    private final List<TypeCandidateProvider> providers;

    /** 段1の結果のメモ（メソッドIDごと。仮想呼び出しのみ対象） */
    private int[][] resolvedTargets;
    private String[] resolvedLabels;
    /** 解決後の入次数。宣言型ではなく解決先に対して数える */
    private int[] inDegree;

    public CallResolver(CallGraph graph, DataflowResolver dataflow,
                        List<TypeCandidateProvider> providers) {
        this.graph = graph;
        this.methods = graph.methods;
        this.dataflow = dataflow;
        this.providers = providers;
    }

    public DataflowResolver dataflow() {
        return dataflow;
    }

    /** 経路に依存しない解決。結果は決定的で、同じエッジには常に同じ結果を返す */
    public Resolution resolve(int edgeIndex) {
        int calleeId = graph.calleeOf(edgeIndex);
        char bindKind = graph.bindKindOf(edgeIndex);

        // --- リフレクション（出所のリテラル・クラスリテラル・レシーバの連鎖から決める） ---
        if (dataflow.reflectiveKindOf(calleeId) != DataflowResolver.REFLECT_NONE) {
            Resolution reflective = dataflow.reflectiveResolution(edgeIndex, null);
            if (reflective != null) {
                return reflective;
            }
        }

        // --- importからの推定（未検証の外部ライブラリ呼び出し） ---
        if (bindKind == BindKind.GUESSED) {
            return Resolution.single(calleeId, Resolution.EXTERNAL_GUESS);
        }

        // --- 段0: 静的束縛 ---
        if (bindKind != BindKind.VIRTUAL) {
            // 既定では確定として扱うが、ここで打ち切ると拡張に到達せず
            // 呼び出し階層が切れてしまう。opt-inした拡張には必ず声をかける。
            Resolution custom = askProviders(edgeIndex, calleeId, true);
            if (custom != null) {
                return custom;
            }
            return Resolution.single(calleeId,
                    Resolution.STATIC_BOUND_PREFIX + BindKind.staticBoundReason(bindKind));
        }

        // --- 段1: オーバーライド候補 ---
        Resolution base = resolveVirtual(calleeId);
        if (!Resolution.CHA.equals(base.label())) {
            return base;
        }

        // --- 段2: new された型 ---
        String sig = methods.signature(calleeId);
        IntArray fromNew = new IntArray(2);
        for (Hint h : graph.hintsOf(edgeIndex)) {
            if (!HintFact.KIND_NEW.equals(h.kind())) {
                continue;
            }
            int id = methods.idOf(h.value() + "#" + sig);
            if (id >= 0) {
                fromNew.addIfAbsent(id);
            }
        }
        if (!fromNew.isEmpty()) {
            return new Resolution(fromNew.toArray(),
                    fromNew.size() == 1 ? Resolution.LOCAL_NEW : Resolution.LOCAL_NEW_MULTI);
        }

        // --- 段3: 拡張 ---
        Resolution custom = askProviders(edgeIndex, calleeId, false);
        if (custom != null) {
            return custom;
        }

        // --- 段4: データフロー（経路に依存しない分） ---
        if (dataflow.enabled()) {
            String recv = graph.recvOrigin(edgeIndex);
            int resolved = dataflow.targetOf(recv, calleeId, null);
            if (resolved >= 0) {
                return Resolution.single(resolved, DataflowResolver.labelFor(recv));
            }
        }

        // --- 段5: CHA ---
        return base;
    }

    /**
     * 経路の情報も使った解決。{@link #resolve} で絞れなかった呼び出しだけ、
     * この経路で渡ってきた引数の具象型を使ってもう一度試す。
     * 「必要なときだけ」にするのは、全呼び出しで試すと解析コストが呼び出し数に比例して効いてくるため。
     *
     * @param ctx この経路で分かっていること（無ければ null）
     */
    public Resolution resolveOnPath(int edgeIndex, DataflowContext ctx) {
        Resolution res = resolve(edgeIndex);
        int calleeId = graph.calleeOf(edgeIndex);

        if (res.isMultiple() && dataflow.enabled()) {
            String recv = graph.recvOrigin(edgeIndex);
            int viaPath = dataflow.targetOf(recv, calleeId, ctx);
            if (viaPath >= 0) {
                res = Resolution.single(viaPath, DataflowResolver.labelFor(recv));
            }
        }
        // リフレクション: クラス名・メソッド名が引数で渡ってくる形は、
        // この経路で分かっている引数の値を使ってもう一度試す
        if (dataflow.reflectiveKindOf(calleeId) != DataflowResolver.REFLECT_NONE && !res.isReflection()) {
            Resolution viaPath = dataflow.reflectiveResolution(edgeIndex, ctx);
            if (viaPath != null) {
                res = viaPath;
            }
        }
        return res;
    }

    /**
     * 段1: 仮想呼び出しのオーバーライド候補を求める。
     * <pre>
     *  NO_OVERRIDE … オーバーライドしている型が1つも無い → 宣言のまま確定
     *  SINGLE_IMPL … 候補が1つだけ（IFに実装が1つ等） → その実装で確定
     *  CHA         … 候補が複数。ここは低確度
     *  NO_IMPL     … 本体を持つ候補が皆無（ソース外の実装等）。宣言のまま扱う
     * </pre>
     * 重要: 候補数は「サブタイプ数」ではなく「そのメソッドをオーバーライドしている
     * 宣言の数」。サブクラスが多くてもオーバーライドが1件なら候補は1件のまま。
     */
    private Resolution resolveVirtual(int calleeId) {
        if (resolvedTargets == null) {
            resolvedTargets = new int[methods.size()][];
            resolvedLabels = new String[methods.size()];
        }
        if (resolvedTargets[calleeId] != null) {
            return new Resolution(resolvedTargets[calleeId], resolvedLabels[calleeId]);
        }

        String declType = methods.typeFqn(calleeId);
        String sig = methods.signature(calleeId);

        IntArray cands = new IntArray(4);
        if (methods.hasBody(calleeId)) {
            cands.add(calleeId);   // 宣言型自身の実装（IFの抽象メソッドは除外される）
        }
        for (String sub : graph.hierarchy.transitiveSubtypes(declType)) {
            int id = methods.idOf(sub + "#" + sig);
            if (id >= 0 && id != calleeId) {
                cands.add(id);
            }
        }

        int[] targets;
        String label;
        if (cands.isEmpty()) {
            targets = new int[] {calleeId};
            label = Resolution.NO_IMPL;
        } else if (cands.size() == 1) {
            targets = new int[] {cands.get(0)};
            label = (cands.get(0) == calleeId) ? Resolution.NO_OVERRIDE : Resolution.SINGLE_IMPL;
        } else {
            targets = cands.toArray();
            label = Resolution.CHA;
        }
        resolvedTargets[calleeId] = targets;
        resolvedLabels[calleeId] = label;
        return new Resolution(targets, label);
    }

    /**
     * 拡張（フェーズB）に候補を尋ねる。
     *
     * @param staticBoundOnly true なら appliesToStaticBound() が true の拡張だけに尋ねる
     * @return 解決できた場合のみ Resolution。できなければ null
     */
    private Resolution askProviders(int edgeIndex, int calleeId, boolean staticBoundOnly) {
        if (providers.isEmpty()) {
            return null;
        }
        List<Hint> hints = graph.hintsOf(edgeIndex);
        String declType = methods.typeFqn(calleeId);
        String sig = methods.signature(calleeId);

        for (TypeCandidateProvider provider : providers) {
            if (staticBoundOnly && !provider.appliesToStaticBound()) {
                continue;
            }
            String[] candidates;
            try {
                candidates = provider.candidates(declType, sig, hints);
            } catch (RuntimeException e) {
                Log.warn("candidate provider 失敗: " + provider.getClass().getName() + " (" + e + ")");
                continue;
            }
            if (candidates == null || candidates.length == 0) {
                continue;
            }
            IntArray ids = new IntArray(candidates.length);
            for (String c : candidates) {
                int id = methods.idOf(c + "#" + sig);
                if (id >= 0) {
                    ids.addIfAbsent(id);
                }
            }
            if (!ids.isEmpty()) {
                return new Resolution(ids.toArray(), provider.label());
            }
        }
        return null;
    }

    // ------------------------------------------------------------
    // 解決後のグラフの集計
    // ------------------------------------------------------------

    /**
     * 入次数を数える。
     *
     * 重要: 宣言型の呼び出し先ではなく「解決後の候補」に対して数える。
     * そうしないと、IF経由でしか呼ばれない実装クラス（DAO実装など）が
     * すべて入次数0となり、真の入口と区別がつかなくなる。
     */
    public int[] inDegrees() {
        if (inDegree != null) {
            return inDegree;
        }
        inDegree = new int[methods.size()];
        for (int caller = 0; caller < methods.size(); caller++) {
            for (int e = graph.edgeStart(caller); e < graph.edgeEnd(caller); e++) {
                for (int t : resolve(e).targets()) {
                    inDegree[t]++;
                }
            }
        }
        return inDegree;
    }

    /** 起点集合から解決後のエッジを辿って到達できるメソッドに印を付ける */
    public boolean[] reachableFrom(int[] roots) {
        boolean[] seen = new boolean[methods.size()];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int r : roots) {
            if (!seen[r]) {
                seen[r] = true;
                queue.add(r);
            }
        }
        while (!queue.isEmpty()) {
            int cur = queue.poll();
            for (int e = graph.edgeStart(cur); e < graph.edgeEnd(cur); e++) {
                for (int t : resolve(e).targets()) {
                    if (!seen[t]) {
                        seen[t] = true;
                        queue.add(t);
                    }
                }
            }
        }
        return seen;
    }
}
