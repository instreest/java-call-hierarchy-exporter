// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.server;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jche.AnalysisSnapshot;
import jche.graph.MethodTable;
import jche.server.FieldAccesses.Access;

/**
 * フィールドを根にした木（{@code TREE <型FQN#フィールド名> field}）。
 * <pre>
 *   深さ 0  フィールド                       （ファイルは宣言しているファイル、行は 0）
 *   深さ 1  そのフィールドを参照しているメソッド （行は参照の行、理由の列は read / write / read/write）
 *   深さ 2〜 そのメソッドの呼び出し元          （メソッドの木 {@link CallTree} と同じ行）
 * </pre>
 * 「このフィールドを変えたら、どこまで影響するか」を、メソッドの呼び出し元と同じ見た目で辿るためのもの
 * （{@code docs/field-callers-qa.md}）。
 *
 * <h2>深さ 1 の行のまとめ方</h2>
 * 同じメソッドの中の参照は 1 行にまとめ、行番号は最初の参照、理由の列は読み書きの別を合わせたものにする
 * （呼び出し元の木はメソッドで決まるので、参照の数だけ同じ木を繰り返さない）。{@code dedupe=0} のときだけ、
 * 参照の行ごとに分ける（メソッドの木の「呼び出し行ごとに出す」と同じ）。
 *
 * <p>囲むメソッドが特定できない参照も、落とさずに深さ 1 の行にする（キーは空、印は {@link #FLAG_NO_METHOD}）。
 * 影響調査で「参照が無い」と誤って読ませないため。
 *
 * <p>宣言の初期化子（{@code private String status = "NEW";}）は、参照ではなく宣言なので A 行に無い。
 * 書き込みの 1 つには違いないので、深さ 1 の先頭に初期化子の行（キーは空、印は {@link #FLAG_INITIALIZER}、
 * 場所は宣言のファイル）を置く。初期化子を通るコンストラクタまでは辿らない（行に呼び出し元は付かない）。
 */
final class FieldTree {

    /** 根（フィールド）の行の印 */
    static final String FLAG_FIELD = "field";
    /** フィールドを参照しているメソッドの行（深さ 1）の印 */
    static final String FLAG_ACCESS = "access";
    /** 参照を囲むメソッドが特定できない行の印 */
    static final String FLAG_NO_METHOD = "nomethod";
    /** 宣言の初期化子の行（深さ 1）の印 */
    static final String FLAG_INITIALIZER = "initializer";

    /** 理由の列に書く、参照の種類（CSV のセルにもなるので英語で固定し、カンマを入れない） */
    static final String READ = "read";
    static final String WRITE = "write";
    static final String READ_WRITE = "read/write";

    /** 木の 1 行（{@link Server} がそのまま {@code R} 行と CSV の行に書く） */
    record Line(int depth, String key, String label, String file, int line, String reason, String flags,
                boolean recursive, boolean truncated) {
    }

    /** 深さ 1 の行 1 つぶん（まとめた参照） */
    private static final class Accessor {
        final int methodId;
        final String key;
        final String file;
        int line;
        boolean reads;
        boolean writes;

        Accessor(int methodId, String key, String file, int line) {
            this.methodId = methodId;
            this.key = key;
            this.file = file;
            this.line = line;
        }

        String reason() {
            return (reads && writes) ? READ_WRITE : (writes ? WRITE : READ);
        }
    }

    private final AnalysisSnapshot snapshot;
    private final TreeFilters filters;

    FieldTree(AnalysisSnapshot snapshot, TreeFilters filters) {
        this.snapshot = snapshot;
        this.filters = filters;
    }

    /**
     * @param ownerFqn  フィールドを宣言している型
     * @param fieldName フィールド名
     * @param scanned   キャッシュから拾った参照（{@link FieldAccesses#scan}）
     */
    List<Line> walk(String ownerFqn, String fieldName, FieldAccesses.Result scanned) {
        MethodTable methods = snapshot.graph().methods();
        CallTree tree = new CallTree(snapshot, CallTree.Direction.CALLERS, filters);
        List<Line> lines = new ArrayList<>();
        String rootFlags = (scanned.declFile() == null) ? FLAG_FIELD + ",nosource" : FLAG_FIELD;
        lines.add(new Line(0, ownerFqn + "#" + fieldName, ownerFqn + "." + fieldName,
                nullToEmpty(scanned.declFile()), 0, "", rootFlags, false, false));
        if (filters.maxDepth < 1) {
            return lines;
        }
        if (scanned.initialized() && !TreeFilters.ACCESS_READ.equals(filters.access)
                && (filters.includeTests || !CallTree.isTestSource(scanned.declFile()))) {
            lines.add(new Line(1, "", "", nullToEmpty(scanned.declFile()), 0, WRITE,
                    FLAG_ACCESS + "," + FLAG_INITIALIZER, false, false));
        }
        for (Accessor accessor : accessorsOf(scanned.accesses(), methods, tree)) {
            if (lines.size() >= filters.maxRows) {
                break;
            }
            if (accessor.methodId < 0) {
                String flags = accessor.key.isEmpty() ? FLAG_ACCESS + "," + FLAG_NO_METHOD : FLAG_ACCESS;
                lines.add(new Line(1, accessor.key, accessor.key, accessor.file, accessor.line,
                        accessor.reason(), flags, false, false));
                continue;
            }
            List<CallTree.Row> rows = tree.walk(accessor.methodId, 1, filters.maxRows - lines.size());
            for (int i = 0; i < rows.size(); i++) {
                CallTree.Row row = rows.get(i);
                if (i == 0) {
                    // 参照している行そのもの。場所は宣言ではなく参照の行、理由は読み書きの別
                    String flags = Server.flagsOf(tree, row, methods);
                    lines.add(new Line(1, methods.key(row.methodId()), methods.displayLabel(row.methodId()),
                            accessor.file, accessor.line, accessor.reason(),
                            flags.isEmpty() ? FLAG_ACCESS : FLAG_ACCESS + "," + flags,
                            row.recursive(), row.truncated()));
                    continue;
                }
                lines.add(new Line(row.depth(), methods.key(row.methodId()), methods.displayLabel(row.methodId()),
                        nullToEmpty(tree.callSiteFile(row, null)), tree.callSiteLine(row),
                        tree.reasonOf(row.edgeIndex()), Server.flagsOf(tree, row, methods),
                        row.recursive(), row.truncated()));
            }
        }
        return lines;
    }

    /**
     * 参照のうち、読み書きの絞り込み（{@code access=}）に合うものの数（応答の {@code accesses=}）。
     * 宣言の初期化子も書き込みの 1 つとして数える
     */
    long countMatching(FieldAccesses.Result scanned) {
        long initializer = (scanned.initialized() && !TreeFilters.ACCESS_READ.equals(filters.access)) ? 1 : 0;
        return initializer + scanned.accesses().stream().filter(this::matchesAccess).count();
    }

    private boolean matchesAccess(Access a) {
        return switch (filters.access) {
            case TreeFilters.ACCESS_READ -> a.reads();
            case TreeFilters.ACCESS_WRITE -> a.writes();
            default -> true;
        };
    }

    /** 参照をメソッド（{@code dedupe=0} ならメソッドと行）ごとにまとめ、絞り込みを掛けて並べる */
    private List<Accessor> accessorsOf(List<Access> accesses, MethodTable methods, CallTree tree) {
        Map<String, Accessor> grouped = new LinkedHashMap<>();
        for (Access a : accesses) {
            if (!matchesAccess(a)) {
                continue;
            }
            String key = (a.caller() == null) ? "" : a.caller().key();
            int id = key.isEmpty() ? -1 : methods.idOf(key);
            if (id >= 0 && !tree.accepts(id)) {
                continue;
            }
            if (id < 0 && !filters.includeTests && CallTree.isTestSource(a.file())) {
                continue;
            }
            // 囲むメソッドが無い参照は、場所ごとに別の行にする（まとめる相手が無い）
            String group = (id < 0 || !filters.dedupe)
                    ? key + "\u0000" + a.file() + "\u0000" + a.line() : key;
            Accessor accessor = grouped.get(group);
            if (accessor == null) {
                accessor = new Accessor(id, key, a.file(), a.line());
                grouped.put(group, accessor);
            } else if (a.line() < accessor.line && a.file().equals(accessor.file)) {
                accessor.line = a.line();
            }
            accessor.reads |= a.reads();
            accessor.writes |= a.writes();
        }
        List<Accessor> sorted = new ArrayList<>(grouped.values());
        // メソッドの木の子と同じくキーの順。囲むメソッドの無い行（キーが空）は、ファイルと行の順で最後に置く
        sorted.sort(Comparator.<Accessor, Boolean>comparing(x -> x.key.isEmpty())
                .thenComparing(x -> x.key)
                .thenComparing(x -> x.file)
                .thenComparingInt(x -> x.line));
        return sorted;
    }

    private static String nullToEmpty(String value) {
        return (value == null) ? "" : value;
    }
}
