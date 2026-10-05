// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * ワークスペースの他のプロジェクトのメソッドを CSV に出す範囲（設定の {@code workspace.scope}）。
 *
 * <p>{@code callers}（既定）では、他のプロジェクトのメソッドは<b>そこから project.root のメソッドに届く経路の上にある
 * ときだけ</b>出す。他のプロジェクトの、project.root に関係の無い階層（自分に届かない起点とその配下）が CSV に混ざらない
 * ようにするため。Eclipse の参照の検索・呼び出し階層が「このメソッドに届く経路」だけを見せるのと同じ考え方である。
 *
 * <p>判定は、project.root の全メソッドを根にした<b>呼び出し元の向きの到達集合</b>（解決後の辺の転置索引
 * {@link InboundIndex} を逆に辿る）を 1 回求めておき、メソッドごとに引く。経路の上の全ノードは定義上この集合に
 * 入るので、届く経路が途中で切れることは無い。絞るのは<b>経路が project.root のメソッドに届くまでの間だけ</b>で、
 * 一度 project.root のメソッドを通った後の経路（そこから先の呼び出し先。依存先のプロジェクトの中への展開や、
 * 自分のインターフェースの実装が依存先にある形）は絞らない（読み手が {@link #allows} と「経路に project.root の
 * メソッドがあるか」を組にして見る）。
 *
 * <p><b>解決（CHA の候補・データフロー・DI・ライブラリ呼び出し規則）には一切使わない。</b>絞るのは出す行だけで、
 * 解決は全体で行う（候補を減らす方向には倒れない）。
 */
public final class WorkspaceScope {

    /** 絞らない（{@code workspace.scope=all}、またはワークスペースの他のプロジェクトが無い） */
    public static final WorkspaceScope ALL = new WorkspaceScope(null, null);

    private final CallGraph graph;
    /** メソッドごとに、そこから project.root のメソッドに届くか。絞らないときは null */
    private final boolean[] reaches;
    private int hidden;

    private WorkspaceScope(CallGraph graph, boolean[] reaches) {
        this.graph = graph;
        this.reaches = reaches;
    }

    /**
     * {@code callers} の範囲を作る。project.root の全メソッド（ソースのあるもの）から、解決後の呼び出し元の向きに
     * 辿れるメソッドを集める。辺は転置索引（解決した呼び出し先）に加えて、ライブラリ呼び出し規則で呼び戻される側
     * （{@code Thread#start()} → {@code run()}、{@code forEach} に渡したラムダの本体）も数える。入次数・到達の判定
     * （{@link CallResolver#inDegrees}・{@link CallResolver#reachableFrom}）と同じ辺の集合で、ここだけ規則を落とすと、
     * 規則でしか project.root に届かない相手の起点が静かに消える
     *
     * @param inbound  解決後の辺の転置索引（呼び出し先 → 呼び出し元）
     * @param resolver 規則で呼び戻される側を引くのに使う
     */
    public static WorkspaceScope callers(CallGraph graph, InboundIndex inbound, CallResolver resolver) {
        MethodTable methods = graph.methods();
        int n = methods.size();
        // 規則で呼び戻される側 → それを渡した呼び出し元（転置索引には無い辺）
        Map<Integer, IntArray> callbackCallers = new HashMap<>();
        for (int caller = 0; caller < n; caller++) {
            for (int e = graph.edgeStart(caller); e < graph.edgeEnd(caller); e++) {
                for (CallbackRules.Match m : resolver.callbackTargets(e, null)) {
                    callbackCallers.computeIfAbsent(m.target(), k -> new IntArray(4)).add(caller);
                }
            }
        }
        boolean[] reaches = new boolean[n];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int id = 0; id < n; id++) {
            if (graph.projectOf(id) == 0) {
                reaches[id] = true;
                queue.add(id);
            }
        }
        while (!queue.isEmpty()) {
            int id = queue.poll();
            for (int i = inbound.start(id); i < inbound.end(id); i++) {
                mark(reaches, queue, inbound.callerAt(i));
            }
            IntArray viaRule = callbackCallers.get(id);
            if (viaRule != null) {
                for (int i = 0; i < viaRule.size(); i++) {
                    mark(reaches, queue, viaRule.get(i));
                }
            }
        }
        WorkspaceScope scope = new WorkspaceScope(graph, reaches);
        for (int id = 0; id < n; id++) {
            if (!scope.allows(id) && !methods.isLambdaBody(id) && !methods.isConstructor(id)) {
                scope.hidden++;
            }
        }
        return scope;
    }

    private static void mark(boolean[] reaches, ArrayDeque<Integer> queue, int caller) {
        if (caller >= 0 && caller < reaches.length && !reaches[caller]) {
            reaches[caller] = true;
            queue.add(caller);
        }
    }

    /** 絞っているか（{@code callers} で、ワークスペースの他のプロジェクトがある） */
    public boolean restricts() {
        return reaches != null;
    }

    /**
     * そのメソッドを CSV に出してよいか。project.root のメソッド・ソースの無いメソッド（jar）は常に真。他のプロジェクトの
     * メソッドは、そこから project.root のメソッドに届くときだけ真
     */
    public boolean allows(int methodId) {
        if (reaches == null || methodId < 0 || methodId >= reaches.length) {
            return true;
        }
        return reaches[methodId] || graph.projectOf(methodId) <= 0;
    }

    /** そのメソッドが project.root のもの（ソースがあり、他のプロジェクトのものではない）か */
    public boolean isMain(int methodId) {
        return graph != null && graph.projectOf(methodId) == 0;
    }

    /**
     * 範囲の外になった他のプロジェクトのメソッドの数（ラムダの本体・コンストラクタは数えない）。起点にならず、
     * project.root のメソッドから降りた先（依存先の実装）としてだけ出る
     */
    public int hiddenCount() {
        return hidden;
    }
}
