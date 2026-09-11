// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.dataflow;

import java.util.HashMap;
import java.util.Map;

import jche.cache.Origin;
import jche.graph.CallGraph;
import jche.graph.IntArray;
import jche.graph.MethodTable;

/**
 * フェーズ2b: データフローの事実をグラフ全体から一括で確定する。
 *
 * <h2>ファクトリの戻り値の畳み込み</h2>
 * メソッドごとに「必ず返す値の出所」を求める。委譲（{@code return create();}）は
 * {@code dataflow.max.depth} 段まで辿る。
 *
 * 深さは<b>そのメソッドから数えた絶対深さ</b>で見る。途中の委譲先が別のメソッドの計算で
 * 先に確定していても、そこまでに使った段数を含めて上限に収まらなければ「不明」にする。
 * かつては確定済みの委譲先に当たると残りの段数を数え直さなかったため、
 * 「どのメソッドを先に計算したか」で辿れる実効の深さが変わり、解決結果がエッジの処理順に
 * 依存していた（Issue #80）。ここでは全メソッドをID順に確定させ、結果の配列だけを
 * {@link DataflowFacts} として渡す。順序依存を残さないために、メモに載せるのは
 * 「上限にも循環にも当たらずに決まった結果」だけにし、それを再利用するときも
 * 使った段数を現在の深さに足して上限を検査する。
 *
 * <h2>そのほかの事実</h2>
 * 引数を使うメソッド・リフレクションAPIの種別・名前ごとのメソッド一覧は、
 * 元から一括で作っていたものを同じ場所に寄せただけ。
 */
public final class DataflowBuilder {

    // --- リフレクションAPIの種別（DataflowResolver.REFLECT_* と同じ値） ---
    static final byte REFLECT_INVOKE = 1;
    static final byte REFLECT_FOR_NAME = 2;
    static final byte REFLECT_CLASS_NEW_INSTANCE = 3;
    static final byte REFLECT_CTOR_NEW_INSTANCE = 4;

    private static final String METHOD_INVOKE =
            "java.lang.reflect.Method#invoke(java.lang.Object,java.lang.Object[])";
    private static final String CLASS_NEW_INSTANCE = "java.lang.Class#newInstance()";
    private static final String CTOR_NEW_INSTANCE =
            "java.lang.reflect.Constructor#newInstance(java.lang.Object[])";
    private static final String CLASS_FOR_NAME_PREFIX = "java.lang.Class#forName(";

    /**
     * 1メソッドの畳み込みの結果。
     *
     * @param origin   畳んだ出所（決められなければ null）
     * @param consumed この結果を得るのに使った委譲の段数（返り値の中で最も深いもの）
     * @param partial  深さ上限か循環に当たった（＝どの深さから見ても同じとは限らない）
     */
    private record Folded(String origin, int consumed, boolean partial) {
        static final Folded UNDECIDED = new Folded(null, 0, false);
        static final Folded LIMITED = new Folded(null, 0, true);
    }

    private final CallGraph graph;
    private final MethodTable methods;
    private final int maxDepth;

    /** 上限にも循環にも当たらずに決まった結果のメモ（null は未計算）。origin が null なら「決められない」 */
    private final Folded[] memo;
    /** いま計算の途中にあるメソッド（委譲の循環を見つける） */
    private final boolean[] inProgress;
    private int cutOff;

    private DataflowBuilder(CallGraph graph, int maxDepth) {
        this.graph = graph;
        this.methods = graph.methods();
        this.maxDepth = (maxDepth > 0) ? maxDepth : 1;
        this.memo = new Folded[methods.size()];
        this.inProgress = new boolean[methods.size()];
    }

    /**
     * グラフ全体から事実を作る。
     *
     * @param enabled  dataflow.enabled。false ならファクトリの畳み込みはせず、リフレクションの種別だけ持つ
     * @param maxDepth dataflow.max.depth（委譲を辿る段数の上限）
     */
    public static DataflowFacts build(CallGraph graph, boolean enabled, int maxDepth) {
        MethodTable methods = graph.methods();
        byte[] reflectKinds = reflectKinds(methods);
        if (!enabled) {
            return DataflowFacts.empty(methods.size(), reflectKinds);
        }
        DataflowBuilder b = new DataflowBuilder(graph, maxDepth);
        String[] factoryOrigin = new String[methods.size()];
        int decided = 0;
        for (int id = 0; id < factoryOrigin.length; id++) {
            factoryOrigin[id] = b.factoryOriginOf(id);
            if (factoryOrigin[id] != null) {
                decided++;
            }
        }
        return new DataflowFacts(factoryOrigin, usesParameters(graph), reflectKinds,
                methodsByName(methods), decided, b.cutOff);
    }

    // ------------------------------------------------------------
    // ファクトリの戻り値の畳み込み
    // ------------------------------------------------------------

    /** メソッドが必ず返す値の出所（そのメソッドを深さ0として畳む）。決められなければ null */
    private String factoryOriginOf(int methodId) {
        Folded f = fold(methodId, 0);
        if (f.partial) {
            cutOff++;
        }
        return f.origin;
    }

    /**
     * メソッドの return をすべて畳んで1つの出所にする。
     *
     * 「1つでも追跡できない return があれば不明」「複数の出所を返すなら不明」。
     * 分かった分だけで決め打ちすると、別の型を返す経路を取りこぼす。
     *
     * @param depth このメソッドに至るまでに使った委譲の段数（起点のメソッドは 0）
     */
    private Folded fold(int methodId, int depth) {
        Folded known = memo[methodId];
        if (known != null) {
            if (known.origin == null || depth + known.consumed <= maxDepth) {
                return known;
            }
            // 決まってはいるが、ここから辿ると上限を超える段数を使う。起点によらず同じ結果にするため、
            // 最初からこの深さで計算したときと同じ「上限による不明」にする
            return Folded.LIMITED;
        }
        if (inProgress[methodId]) {
            return new Folded(null, 0, true);   // 委譲が循環している
        }
        String[] origins = graph.returnOriginsOf(methodId);
        if (origins == null || origins.length == 0) {
            memo[methodId] = Folded.UNDECIDED;
            return Folded.UNDECIDED;
        }
        inProgress[methodId] = true;
        String found = null;
        int consumed = 0;
        boolean partial = false;
        boolean undecidable = false;
        for (String o : origins) {
            Folded reduced = reduce(o, depth);
            partial |= reduced.partial;
            consumed = Math.max(consumed, reduced.consumed);
            if (reduced.origin == null || (found != null && !found.equals(reduced.origin))) {
                undecidable = true;
                break;
            }
            found = reduced.origin;
        }
        inProgress[methodId] = false;
        Folded result = new Folded(undecidable ? null : found, consumed, partial);
        if (!partial) {
            memo[methodId] = result;
        }
        return result;
    }

    /** return 1件の出所を、具象型か「呼び出し箇所依存の形」まで畳む */
    private Folded reduce(String origin, int depth) {
        char kind = Origin.kindOf(origin);
        if (kind == Origin.NEW || kind == Origin.REFLECT || kind == Origin.PARAM) {
            return new Folded(Origin.head(origin), 0, false);
        }
        if (kind != Origin.RETURN) {
            return Folded.UNDECIDED;
        }
        if (depth >= maxDepth) {
            return Folded.LIMITED;   // 委譲の深さ上限
        }
        // 別のファクトリへの委譲。委譲先の出所を、この return が書いている
        // 実引数で解決する（return create("jp.co.X"); のような形を畳むため）
        int delegate = methods.idOf(Origin.valueOf(origin));
        if (delegate < 0) {
            return Folded.UNDECIDED;
        }
        Folded inner = fold(delegate, depth + 1);
        int consumed = inner.consumed + 1;
        if (inner.origin == null) {
            return new Folded(null, consumed, inner.partial);
        }
        if (Origin.kindOf(inner.origin) == Origin.NEW) {
            return new Folded(inner.origin, consumed, inner.partial);
        }
        String fqn = applyInvocationArgs(inner.origin, Origin.argsOf(origin));
        return new Folded((fqn == null) ? null : Origin.of(Origin.NEW, fqn), consumed, inner.partial);
    }

    /**
     * 呼び出し箇所依存の戻り値の出所（C/A）を、その呼び出しの実引数で解決する（経路の情報は使わない）。
     * 実引数が別のファクトリの戻り値なら、そのファクトリを起点（深さ0）として畳んだ事実を使う。
     * 経路の情報も使う版は {@code DataflowResolver.applyInvocationArgs}
     */
    private String applyInvocationArgs(String returnOrigin, String args) {
        char kind = Origin.kindOf(returnOrigin);
        if (kind == Origin.NEW) {
            return Origin.valueOf(returnOrigin);
        }
        int index = parseIndex(Origin.valueOf(returnOrigin));
        if (index < 0) {
            return null;
        }
        String arg = Origin.argAt(args, index);
        if (kind == Origin.REFLECT) {
            if (Origin.kindOf(arg) != Origin.LITERAL) {
                return null;
            }
            String fqn = Origin.valueOf(arg);
            // 解析対象に存在しない型名は使わない（文字列の見た目だけで決めない）
            return graph.hierarchy().contains(fqn) ? fqn : null;
        }
        if (kind == Origin.PARAM) {
            return concreteTypeOf(arg);
        }
        return null;
    }

    /** 出所から具象型を求める（経路に依存しない分だけ）。{@code DataflowResolver.concreteTypeOf} の ctx 無し版 */
    private String concreteTypeOf(String origin) {
        char kind = Origin.kindOf(origin);
        if (kind == Origin.NEW) {
            return Origin.valueOf(origin);
        }
        if (kind == Origin.RETURN) {
            int factory = methods.idOf(Origin.valueOf(origin));
            if (factory < 0) {
                return null;
            }
            // 実引数のファクトリは「その戻り値」という独立した事実なので、深さ0から畳む。
            // 計算途中のメソッド（循環）に当たれば不明
            return applyInvocationArgs(fold(factory, 0).origin, Origin.argsOf(origin));
        }
        if (kind == Origin.FIELD) {
            String fieldOrigin = graph.fieldOrigin(Origin.valueOf(origin));
            return (fieldOrigin != null && Origin.kindOf(fieldOrigin) == Origin.NEW)
                    ? Origin.valueOf(fieldOrigin) : null;
        }
        return null;
    }

    private static int parseIndex(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------
    // そのほかの事実（グラフ全体を一度見て作る）
    // ------------------------------------------------------------

    /**
     * メソッドごとに「経路の情報を渡す意味があるか」。
     * 引数をレシーバとして使う、または引数（入れ子のレシーバ・実引数を含む）をそのまま次へ渡すメソッド
     */
    private static boolean[] usesParameters(CallGraph graph) {
        boolean[] flags = new boolean[graph.methodCount()];
        for (int caller = 0; caller < flags.length; caller++) {
            for (int e = graph.edgeStart(caller); e < graph.edgeEnd(caller); e++) {
                if (Origin.kindOf(graph.recvOrigin(e)) == Origin.PARAM
                        || mentionsParam(graph.recvOrigin(e))
                        || mentionsParam(graph.argOrigins(e))) {
                    flags[caller] = true;
                    break;
                }
            }
        }
        return flags;
    }

    /** 実引数の出所の中に「囲みメソッドの引数」が含まれるか（引数の受け渡し） */
    private static boolean mentionsParam(String argOrigins) {
        if (argOrigins == null) {
            return false;
        }
        // "0=A:1;2=T:jp.co.X" のような形。"=A:" があれば引数を渡している
        return argOrigins.indexOf("=" + Origin.PARAM + ":") >= 0;
    }

    /** メソッドIDごとのリフレクションAPIの種別 */
    private static byte[] reflectKinds(MethodTable methods) {
        byte[] kinds = new byte[methods.size()];
        for (int id = 0; id < kinds.length; id++) {
            String key = methods.key(id);
            if (METHOD_INVOKE.equals(key)) {
                kinds[id] = REFLECT_INVOKE;
            } else if (key.startsWith(CLASS_FOR_NAME_PREFIX)) {
                kinds[id] = REFLECT_FOR_NAME;
            } else if (CLASS_NEW_INSTANCE.equals(key)) {
                kinds[id] = REFLECT_CLASS_NEW_INSTANCE;
            } else if (CTOR_NEW_INSTANCE.equals(key)) {
                kinds[id] = REFLECT_CTOR_NEW_INSTANCE;
            }
        }
        return kinds;
    }

    /** "typeFqn#name" → 本体を持つ（ソース上の）メソッドID */
    private static Map<String, IntArray> methodsByName(MethodTable methods) {
        Map<String, IntArray> map = new HashMap<>();
        for (int id = 0; id < methods.size(); id++) {
            if (!methods.hasBody(id) || !methods.hasSource(id)) {
                continue;
            }
            String k = methods.typeFqn(id) + "#" + methods.methodName(id);
            map.computeIfAbsent(k, key -> new IntArray(2)).add(id);
        }
        return map;
    }
}
