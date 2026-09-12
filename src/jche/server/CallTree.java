// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.server;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import jche.AnalysisSnapshot;
import jche.config.PackagePattern;
import jche.graph.CallGraph;
import jche.graph.InboundIndex;
import jche.graph.MethodTable;
import jche.graph.Resolution;

/**
 * スナップショットから「あるメソッドを根にした木」を切り出す。
 *
 * <p>主ユースケースは<b>呼び出し元</b>の向き（{@link Direction#CALLERS}）。
 * 逆向き（呼び出し先）も同じ道具で辿れる。
 *
 * <p>ここは画面ではなくサーバー側に置く。表示側に置くと、グラフを持つ側と
 * 絞り込む側が別プロセスに分かれてしまい、木を切り出すたびにグラフ全体を
 * 送らねばならなくなる（docs/out-of-process-analysis-design.md §2）。
 */
public final class CallTree {

    /** 木を辿る向き */
    public enum Direction {
        CALLERS,
        CALLEES
    }

    /**
     * 木の1行。
     *
     * @param depth     根からの深さ（根は 0）
     * @param methodId  メソッドID（この木の中だけで意味を持つ）
     * @param edgeIndex 親との間の呼び出し（根は -1）
     * @param recursive 経路上に既に出たので打ち切った
     * @param truncated 深さの上限で打ち切った（まだ先がある）
     */
    public record Row(int depth, int methodId, int edgeIndex, boolean recursive, boolean truncated) {
    }

    private final AnalysisSnapshot snapshot;
    private final Direction direction;
    private final TreeFilters filters;
    private final String needle;
    /** 文字列で絞り込むときに残す枝（一致したメソッドから呼び出し先へ到達できる範囲）。null なら絞り込み無し */
    private final boolean[] keep;

    public CallTree(AnalysisSnapshot snapshot, Direction direction, TreeFilters filters) {
        this.snapshot = snapshot;
        this.direction = direction;
        this.filters = filters;
        this.needle = (filters.text == null) ? "" : filters.text.trim().toLowerCase(Locale.ROOT);
        // 呼び出し先の向きでは「一致した子孫を持つ枝」を安く求められないので、一致した行だけを残す
        this.keep = (needle.isEmpty() || direction == Direction.CALLEES) ? null : buildKeepSet();
    }

    private MethodTable methods() {
        return snapshot.graph().methods();
    }

    private CallGraph graph() {
        return snapshot.graph();
    }

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

    /** その行が絞り込み文字列に直接一致しているか（画面で強調するのに使う） */
    public boolean isDirectMatch(int methodId) {
        return !needle.isEmpty() && matches(methods(), methodId);
    }

    /**
     * 根から深さ優先で辿り、行を順に作る。親→子の順に並ぶので、
     * 受け取った側は深さを見るだけで木に組み直せる。
     *
     * @param rootId 根のメソッドID
     */
    public List<Row> walk(int rootId) {
        List<Row> rows = new ArrayList<>();
        Row root = new Row(0, rootId, -1, false, truncatedAt(0, rootId));
        rows.add(root);
        walkInto(root, rows);
        return rows;
    }

    private void walkInto(Row parent, List<Row> rows) {
        if (rows.size() >= filters.maxRows || parent.recursive() || parent.truncated()) {
            return;
        }
        for (Row child : childrenOf(parent, rows)) {
            if (rows.size() >= filters.maxRows) {
                return;
            }
            rows.add(child);
            walkInto(child, rows);
        }
    }

    /** そのノードの子（呼び出し元、または解決後の呼び出し先）。フィルタ適用済み・名前順 */
    private List<Row> childrenOf(Row node, List<Row> rows) {
        if (node.depth() >= filters.maxDepth) {
            return List.of();
        }
        MethodTable methods = methods();
        List<Row> children = new ArrayList<>();
        Set<Integer> seen = filters.dedupe ? new HashSet<>() : null;
        for (int[] pair : neighbours(node.methodId())) {
            int other = pair[0];
            int edge = pair[1];
            if (other == node.methodId() && node.depth() > 0) {
                continue;   // 自分自身の呼び出しは1階層目だけ出す
            }
            if (!accept(other, edge)) {
                continue;
            }
            if (seen != null && !seen.add(other)) {
                continue;
            }
            boolean recursive = isOnPath(node, rows, other);
            int depth = node.depth() + 1;
            children.add(new Row(depth, other, edge, recursive,
                    !recursive && truncatedAt(depth, other)));
        }
        children.sort(Comparator.comparing(r -> methods.key(r.methodId())));
        return children;
    }

    /** 深さの上限に達していて、かつまだ辿れる先があるか */
    private boolean truncatedAt(int depth, int methodId) {
        return depth >= filters.maxDepth && hasNeighbour(methodId);
    }

    /**
     * 根からそのノードまでの経路に、同じメソッドが既に出ているか。
     * 経路は「今まで作った行のうち、深さが減っていく並び」を後ろから辿れば分かる。
     */
    private boolean isOnPath(Row node, List<Row> rows, int methodId) {
        if (node.methodId() == methodId) {
            return true;
        }
        int wanted = node.depth();
        for (int i = rows.size() - 1; i >= 0 && wanted >= 0; i--) {
            Row row = rows.get(i);
            if (row.depth() != wanted) {
                continue;
            }
            if (row.methodId() == methodId) {
                return true;
            }
            wanted--;
        }
        return false;
    }

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

    private boolean hasNeighbour(int methodId) {
        if (direction == Direction.CALLERS) {
            return snapshot.inbound().inDegree(methodId) > 0;
        }
        return graph().edgeEnd(methodId) > graph().edgeStart(methodId);
    }

    private boolean accept(int methodId, int edgeIndex) {
        MethodTable methods = methods();
        if (!needle.isEmpty()) {
            boolean hit = matches(methods, methodId) || (keep != null && keep[methodId]);
            if (!hit) {
                return false;
            }
        }
        if (!filters.includeTests && isTestSource(methods.declFile(methodId))) {
            return false;
        }
        if (!filters.includeGuessed && isGuessed(edgeIndex)) {
            return false;
        }
        if (filters.applyExcludePackages && PackagePattern.matchesAny(
                snapshot.config().excludePatterns, methods.pkg(methodId),
                methods.typeFqn(methodId), methods.methodName(methodId))) {
            return false;
        }
        return true;
    }

    /** データフロー由来の解決か（「確実な呼び出しだけ見たい」ときに落とせるようにする） */
    public boolean isGuessed(int edgeIndex) {
        String label = labelOf(edgeIndex);
        return label != null && label.startsWith(Resolution.DATAFLOW_PREFIX);
    }

    /** 解決の理由。ふつうの静的束縛には付けない（行が読みにくくなるだけなので） */
    public String reasonOf(int edgeIndex) {
        String label = labelOf(edgeIndex);
        if (label == null || label.isEmpty() || label.startsWith(Resolution.STATIC_BOUND_PREFIX)
                || Resolution.NO_OVERRIDE.equals(label)) {
            return "";
        }
        return label;
    }

    private String labelOf(int edgeIndex) {
        return (edgeIndex < 0) ? null : snapshot.resolver().resolve(edgeIndex).label();
    }

    /**
     * その行の「呼び出している場所」のファイル。呼び出し元の向きならその行自身のファイル、
     * 呼び出し先の向きなら親（呼び出し元）のファイルにある。
     */
    public String callSiteFile(Row row, Row parent) {
        MethodTable methods = methods();
        if (row.edgeIndex() < 0 || direction == Direction.CALLERS || parent == null) {
            return methods.declFile(row.methodId());
        }
        return methods.declFile(parent.methodId());
    }

    /** その行の「呼び出している行」。分からなければ宣言の行 */
    public int callSiteLine(Row row) {
        int line = (row.edgeIndex() < 0) ? -1 : graph().callLineOf(row.edgeIndex());
        return (line > 0) ? line : methods().declLine(row.methodId());
    }

    /** テストのソースか。パスの区切りに test / tests が現れるかで判断する */
    public static boolean isTestSource(String relativePath) {
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
