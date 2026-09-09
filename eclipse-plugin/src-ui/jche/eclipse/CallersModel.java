// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import jche.AnalysisSnapshot;
import jche.graph.CallGraph;
import jche.graph.InboundIndex;
import jche.graph.MethodTable;
import jche.graph.Resolution;

/**
 * 1つのスナップショットと1組のフィルタから、呼び出し元ツリーの中身を計算する。
 *
 * <p>スナップショットは不変なので、この入れ物も作ったら書き換えない。フィルタを変えたときは
 * 新しい {@code CallersModel} を作って差し替える。ツリーを描いている最中に解析が終わっても、
 * 描画中のモデルは古いスナップショットを指したままなので、木が途中で入れ替わることはない。
 *
 * <p>子の計算はノードを開いたときだけ行う（{@link #childrenOf}）。CSR の配列を範囲で舐めるだけなので、
 * 1 ノードあたりの計算量は「その呼び出し元の数」に比例する。
 */
final class CallersModel {

    /** 木をどちらへ辿るか */
    enum Direction {
        /** 呼び出し元（既定。主ユースケース） */
        CALLERS,
        /** 呼び出し先 */
        CALLEES
    }

    private final AnalysisSnapshot snapshot;
    private final Direction direction;
    private final FilterSettings filters;
    private final String needle;

    /**
     * 文字列で絞り込むときに「残す枝」の集合。
     * 一致したメソッドから呼び出し先をたどって到達できるメソッドは、その先で一致する枝を
     * 持ちうるので残す（一致した子孫を持つ枝だけ残す、を実現する）。null なら絞り込み無し。
     */
    private final boolean[] keep;

    CallersModel(AnalysisSnapshot snapshot, Direction direction, FilterSettings filters) {
        this.snapshot = snapshot;
        this.direction = direction;
        this.filters = filters.copy();
        this.needle = this.filters.text == null ? "" : this.filters.text.trim().toLowerCase(Locale.ROOT);
        // 呼び出し先の向きでは「一致した子孫を持つ枝」を安く求められない（逆向きの到達可能性が要る）。
        // その向きでは一致した行だけを絞り込みの対象にする
        this.keep = (needle.isEmpty() || direction == Direction.CALLEES) ? null : buildKeepSet();
    }

    AnalysisSnapshot snapshot() {
        return snapshot;
    }

    Direction direction() {
        return direction;
    }

    FilterSettings filters() {
        return filters;
    }

    private CallGraph graph() {
        return snapshot.graph();
    }

    MethodTable methods() {
        return snapshot.graph().methods();
    }

    /**
     * 絞り込み文字列に一致したメソッドと、そこから呼び出し先をたどって到達できるメソッドの集合。
     * グラフ全体を2回舐めるので、フィルタ文字列を変えたときに1度だけ計算する。
     */
    private boolean[] buildKeepSet() {
        MethodTable methods = methods();
        List<Integer> matched = new ArrayList<>();
        for (int id = 0; id < methods.size(); id++) {
            if (matches(methods, id)) {
                matched.add(id);
            }
        }
        int[] roots = new int[matched.size()];
        for (int i = 0; i < roots.length; i++) {
            roots[i] = matched.get(i);
        }
        return snapshot.resolver().reachableFrom(roots);
    }

    private boolean matches(MethodTable methods, int id) {
        return methods.key(id).toLowerCase(Locale.ROOT).contains(needle);
    }

    /** その行が絞り込み文字列に直接一致しているか（見つかった行を太字にするのに使う） */
    boolean isDirectMatch(int methodId) {
        return !needle.isEmpty() && matches(methods(), methodId);
    }

    /**
     * そのノードの子（＝呼び出し元）。フィルタを適用し、名前順に並べて返す。
     * 再帰・深さ上限で打ち切ったノードには印を付ける（子は返さない）。
     */
    List<CallNode> childrenOf(CallNode node) {
        if (node.recursive() || node.truncated()) {
            return List.of();
        }
        if (node.depth() >= filters.maxDepth) {
            return List.of();
        }
        MethodTable methods = methods();
        int methodId = node.methodId();
        List<CallNode> children = new ArrayList<>();
        Set<Integer> seen = filters.dedupeCallers ? new HashSet<>() : null;
        for (int[] pair : neighbours(methodId)) {
            int other = pair[0];
            int edge = pair[1];
            if (other == methodId && node.depth() > 0) {
                continue;   // 自分自身の呼び出しは1階層目だけ出す（無限に増えるため）
            }
            if (!accept(other, edge)) {
                continue;
            }
            if (seen != null && !seen.add(other)) {
                continue;
            }
            boolean recursive = node.isOnPath(other);
            boolean truncated = !recursive
                    && node.depth() + 1 >= filters.maxDepth
                    && hasNeighbour(other);
            children.add(new CallNode(other, edge, node, node.depth() + 1, recursive, truncated));
        }
        children.sort(Comparator.comparing(c -> methods.key(c.methodId())));
        return children;
    }

    /**
     * そのメソッドの隣（呼び出し元、または解決後の呼び出し先）を {メソッドID, エッジ番号} で返す。
     * 向きの違いをここだけに閉じ込める。
     */
    private List<int[]> neighbours(int methodId) {
        List<int[]> result = new ArrayList<>();
        if (direction == Direction.CALLERS) {
            InboundIndex inbound = snapshot.inbound();
            for (int i = inbound.start(methodId); i < inbound.end(methodId); i++) {
                result.add(new int[] {inbound.callerAt(i), inbound.edgeAt(i)});
            }
        } else {
            CallGraph g = graph();
            for (int e = g.edgeStart(methodId); e < g.edgeEnd(methodId); e++) {
                for (int t : snapshot.resolver().resolve(e).targets()) {
                    result.add(new int[] {t, e});
                }
            }
        }
        return result;
    }

    /** 深さ上限の表示のため「フィルタ抜きで、さらに辿れる先があるか」だけを見る */
    private boolean hasNeighbour(int methodId) {
        if (direction == Direction.CALLERS) {
            return snapshot.inbound().inDegree(methodId) > 0;
        }
        return graph().edgeEnd(methodId) > graph().edgeStart(methodId);
    }

    /** 根の直下に出しうる件数（フィルタ前）。画面下の「全 n 件」に使う */
    int totalNeighbourCount(int methodId) {
        return neighbours(methodId).size();
    }

    /** そのノードを開けるか（子があるか）。開いてみないと分からないので実際に計算する */
    boolean hasChildren(CallNode node) {
        return !childrenOf(node).isEmpty();
    }

    /** フィルタ1件ぶんの判定。呼び出し元メソッドと、その呼び出しの辺を見る */
    private boolean accept(int callerId, int edgeIndex) {
        MethodTable methods = methods();
        if (!needle.isEmpty()) {
            // 呼び出し元の向きでは「一致した子孫を持つ枝」も残す。呼び出し先の向きでは
            // その集合を安く作れないので、一致した行だけを残す（Q4）
            boolean hit = matches(methods, callerId) || (keep != null && keep[callerId]);
            if (!hit) {
                return false;
            }
        }
        if (!filters.includeTests && isTestSource(methods.declFile(callerId))) {
            return false;
        }
        if (!filters.includeGuessed && isGuessed(edgeIndex)) {
            return false;
        }
        if (filters.applyExcludePackages && jche.config.PackagePattern.matchesAny(
                snapshot.config().excludePatterns, methods.pkg(callerId),
                methods.typeFqn(callerId), methods.methodName(callerId))) {
            return false;
        }
        return true;
    }

    /**
     * 推測で特定した呼び出しか。データフロー由来の解決（{@link Resolution} の DATAFLOW_*）を指す。
     * 「確実な呼び出し元だけ見たい」ときに落とせるようにする。
     */
    boolean isGuessed(int edgeIndex) {
        if (edgeIndex < 0) {
            return false;
        }
        String label = snapshot.resolver().resolve(edgeIndex).label();
        return label != null && label.startsWith(Resolution.DATAFLOW_PREFIX);
    }

    /** その解決の理由（«...» として行末に出す）。理由が無ければ null */
    String resolutionLabel(int edgeIndex) {
        if (edgeIndex < 0) {
            return null;
        }
        String label = snapshot.resolver().resolve(edgeIndex).label();
        if (label == null || label.isEmpty() || label.startsWith(Resolution.STATIC_BOUND_PREFIX)
                || Resolution.NO_OVERRIDE.equals(label)) {
            return null;   // ふつうの呼び出しにいちいち理由は出さない
        }
        return label;
    }

    /** 呼び出している行（1始まり）。分からなければ -1 */
    int callLine(int edgeIndex) {
        return (edgeIndex < 0) ? -1 : graph().callLineOf(edgeIndex);
    }

    /**
     * その行の「呼び出している場所」のファイル（プロジェクトルートからの相対）。
     *
     * <p>呼び出し元の向きでは、呼び出しの行はそのノード自身のファイルにある。
     * 呼び出し先の向きでは、呼び出しているのは親（呼び出し元）なので親のファイルになる。
     * ここを取り違えると、開いたエディタと行番号がちぐはぐになる。
     */
    String callSiteFile(CallNode node) {
        MethodTable methods = methods();
        if (node.edgeIndex() < 0) {
            return methods.declFile(node.methodId());
        }
        if (direction == Direction.CALLERS) {
            return methods.declFile(node.methodId());
        }
        CallNode parent = node.parent();
        return (parent == null) ? methods.declFile(node.methodId()) : methods.declFile(parent.methodId());
    }

    /** その行の「呼び出している場所」の行番号。分からなければ宣言の行 */
    int callSiteLine(CallNode node) {
        int line = callLine(node.edgeIndex());
        return (line > 0) ? line : methods().declLine(node.methodId());
    }

    /**
     * テストのソースにあるか。ソースフォルダ名かパスの区切りに test/tests が現れるかで判断する
     * （Maven/Gradle の src/test/java、Eclipse の test フォルダのどちらも拾える）。
     */
    static boolean isTestSource(String relativePath) {
        if (relativePath == null) {
            return false;
        }
        for (String segment : relativePath.replace('\\', '/').split("/")) {
            String lower = segment.toLowerCase(Locale.ROOT);
            if (lower.equals("test") || lower.equals("tests")) {
                return true;
            }
        }
        return false;
    }
}
