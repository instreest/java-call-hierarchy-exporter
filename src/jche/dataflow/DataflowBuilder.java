// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.dataflow;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jche.cache.Origin;
import jche.graph.CallGraph;
import jche.graph.GuardTable;
import jche.graph.IntArray;
import jche.graph.MethodTable;
import jche.graph.ValueStore;
import jche.util.Names;
import jche.util.RunControl;
import jche.util.Messages;

/**
 * フェーズ2b: データフローの事実をグラフ全体から一括で確定する。
 *
 * <h2>ファクトリの戻り値の畳み込み</h2>
 * メソッドごとに「必ず返す値」を求める。委譲（{@code return create();}）は段数の上限なく
 * 畳む（不動点。委譲の依存関係が循環しているメソッドは「決められない」で確定する）。
 * かつては {@code dataflow.max.depth} 段で打ち切っていたうえ、確定済みの委譲先に当たると残りの
 * 段数を数え直さなかったため、「どのメソッドを先に計算したか」で辿れる実効の深さが変わり、
 * 解決結果がエッジの処理順に依存していた（Issue #80）。
 * ここでは全メソッドをID順に確定させ、結果の配列だけを {@link DataflowFacts} として渡す。
 *
 * 畳んだ結果はそのメソッドの本体が返す値で、呼び出し箇所で使ってよいのは、呼び出しがその本体でしか
 * 動かないとき（{@link CallGraph#hasOverriders} が false）だけ。委譲先を部分型が上書きしていれば畳まない。
 *
 * 循環と、再帰の安全策としての段数の上限（{@link #HARD_CAP}。設定では変えられない）に当たった結果は
 * 起点によって変わりうるのでメモに載せず、再利用するときも使った段数を現在の深さに足して
 * 上限を検査する。{@code dataflow.max.depth} はここでは使わず、経路に依存する探索
 * （{@code DataflowResolver} の classOf / literalOf）にだけ効く。
 *
 * <h2>値は値の表から読む</h2>
 * 戻り値・実引数は値の表（{@link ValueStore}。{@link CallGraph#returnAt} など）の参照で読み、
 * 畳んだ結果は種別と値の番号の組で持つ。値どうしが同じかは値の番号で比べる（同じ中身の文字列は
 * 同じ番号）。出所の文字列には組み直さないので、値が {@code | ; { }} を含んでも途中で切れない。
 *
 * <h2>そのほかの事実</h2>
 * 引数を使うメソッド・リフレクションAPIの種別・名前ごとのメソッド一覧は、
 * 元から一括で作っていたものを同じ場所に寄せただけ。
 */
public final class DataflowBuilder {

    /**
     * 進捗の見出し。{@code static final} にしないのは、そうするとこのクラスが読まれた時点の
     * 言語で固まってしまうため。設定ファイルの {@code message.language} は解析の直前に効くので、
     * 使うたびに引き直す（{@code docs/nls-qa.md} の Q2）
     */
    private static String progressLabel() {
        return Messages.get("dataflow.progress.resolve");
    }

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
     * @param kind     畳んだ値の種別（{@code 'T'} / {@code 'C'} / {@code 'A'}。決められなければ 0）
     * @param valueId  畳んだ値の番号（値の表の文字列の置き場。決められなければ -1）
     * @param consumed この結果を得るのに使った委譲の段数（返り値の中で最も深いもの）
     * @param partial  深さ上限か循環に当たった（＝どの深さから見ても同じとは限らない）
     */
    private record Folded(byte kind, int valueId, int consumed, boolean partial) {
        static final Folded UNDECIDED = new Folded((byte) 0, -1, 0, false);
        static final Folded LIMITED = new Folded((byte) 0, -1, 0, true);

        /** 値を決められなかった（使った段数・上限に当たったかだけを持つ） */
        static Folded undecided(int consumed, boolean partial) {
            return new Folded((byte) 0, -1, consumed, partial);
        }

        boolean decided() {
            return kind != 0;
        }
    }

    /**
     * 委譲を辿る段数の安全策（再帰なので、これを超えるとスタックが危ない）。
     * 実在するファクトリの委譲は数段なので、通常はここに当たらない
     */
    static final int HARD_CAP = 512;

    private final CallGraph graph;
    private final MethodTable methods;
    private final ValueStore values;

    /** 上限にも循環にも当たらずに決まった結果のメモ（null は未計算）。種別が 0 なら「決められない」 */
    private final Folded[] memo;
    /** いま計算の途中にあるメソッド（委譲の循環を見つける） */
    private final boolean[] inProgress;
    private int cutOff;

    private DataflowBuilder(CallGraph graph) {
        this.graph = graph;
        this.methods = graph.methods();
        this.values = graph.values();
        this.memo = new Folded[methods.size()];
        this.inProgress = new boolean[methods.size()];
    }

    /**
     * グラフ全体から事実を作る。
     *
     * @param enabled dataflow.enabled。false ならファクトリの畳み込みはせず、リフレクションの種別だけ持つ
     */
    public static DataflowFacts build(CallGraph graph, boolean enabled) {
        MethodTable methods = graph.methods();
        byte[] reflectKinds = reflectKinds(methods);
        if (!enabled) {
            return DataflowFacts.empty(methods.size(), reflectKinds);
        }
        DataflowBuilder b = new DataflowBuilder(graph);
        int n = methods.size();
        byte[] factoryKind = new byte[n];
        int[] factoryValueId = new int[n];
        int decided = 0;
        // 進捗は 4096 件ごとに出す。メソッド数が数十万になると、ここだけで数分かかることがあり、
        // 何も出ないと「止まった」と見分けが付かない（docs/eclipse-plugin-progress-log-qa.md）
        RunControl.progress(progressLabel(), 0, n);
        for (int id = 0; id < n; id++) {
            if ((id & 0xFFF) == 0xFFF) {
                RunControl.checkCancelled();
                RunControl.progress(progressLabel(), id + 1, n);
            }
            Folded f = b.factoryOf(id);
            factoryKind[id] = f.kind();
            factoryValueId[id] = f.valueId();
            if (f.decided()) {
                decided++;
            }
        }
        RunControl.progress(progressLabel(), n, n);
        return new DataflowFacts(factoryKind, factoryValueId, usesParameters(graph),
                reflectKinds, methodsByName(methods), decided, b.cutOff);
    }

    // ------------------------------------------------------------
    // ファクトリの戻り値の畳み込み
    // ------------------------------------------------------------

    /** メソッドが必ず返す値（そのメソッドを深さ0として畳む）。決められなければ種別 0 */
    private Folded factoryOf(int methodId) {
        Folded f = fold(methodId, 0);
        if (f.partial()) {
            cutOff++;
        }
        return f;
    }

    /**
     * メソッドの return をすべて畳んで1つの値にする。
     *
     * 「1つでも追跡できない return があれば不明」「複数の値を返すなら不明」。
     * 分かった分だけで決め打ちすると、別の型を返す経路を取りこぼす。
     *
     * @param depth このメソッドに至るまでに使った委譲の段数（起点のメソッドは 0）
     */
    private Folded fold(int methodId, int depth) {
        Folded known = memo[methodId];
        if (known != null) {
            if (!known.decided() || depth + known.consumed() <= HARD_CAP) {
                return known;
            }
            // 決まってはいるが、ここから辿ると上限を超える段数を使う。起点によらず同じ結果にするため、
            // 最初からこの深さで計算したときと同じ「上限による不明」にする
            return Folded.LIMITED;
        }
        if (inProgress[methodId]) {
            return Folded.undecided(0, true);   // 委譲が循環している
        }
        int count = graph.returnCount(methodId);
        if (count == 0) {
            memo[methodId] = Folded.UNDECIDED;
            return Folded.UNDECIDED;
        }
        inProgress[methodId] = true;
        byte foundKind = 0;
        int foundValue = -1;
        int consumed = 0;
        boolean partial = false;
        boolean undecidable = false;
        // 同じ参照は 1 つにまとめてある。参照が違っても同じ値になるもの（ブロックをまたいだ同じ式）は
        // 同じ結果に畳まれるので、「すべて一致するか」の判定は変わらない
        for (int k = 0; k < count; k++) {
            Folded reduced = reduce(graph.returnAt(methodId, k), depth);
            partial |= reduced.partial();
            consumed = Math.max(consumed, reduced.consumed());
            if (!reduced.decided()
                    || (foundKind != 0 && (foundKind != reduced.kind() || foundValue != reduced.valueId()))) {
                undecidable = true;
                break;
            }
            foundKind = reduced.kind();
            foundValue = reduced.valueId();
        }
        inProgress[methodId] = false;
        Folded result = undecidable ? Folded.undecided(consumed, partial)
                : new Folded(foundKind, foundValue, consumed, partial);
        if (!partial) {
            memo[methodId] = result;
        }
        return result;
    }

    /** return 1件の値（値の表の参照）を、具象型か「呼び出し箇所依存の形」まで畳む */
    private Folded reduce(int ref, int depth) {
        char kind = values.kind(ref);
        if (kind == Origin.NEW || kind == Origin.REFLECT || kind == Origin.PARAM) {
            return new Folded((byte) kind, values.valueId(ref), 0, false);
        }
        if (kind != Origin.RETURN) {
            return Folded.UNDECIDED;
        }
        if (depth >= HARD_CAP) {
            return Folded.LIMITED;   // 再帰の安全策の上限
        }
        // 別のファクトリへの委譲。委譲先の値を、この return が書いている
        // 実引数で解決する（return create("jp.co.X"); のような形を畳むため）。
        // 委譲先を部分型が上書きしていれば、実際に動く本体は委譲先の宣言とは限らないので畳まない
        // （CallGraph#hasOverriders。宣言の return で決めると、上書きした本体が返す型を落とす）
        int delegate = values.methodId(ref);
        if (delegate < 0 || graph.hasOverriders(delegate)) {
            return Folded.UNDECIDED;
        }
        Folded inner = fold(delegate, depth + 1);
        int consumed = inner.consumed() + 1;
        if (!inner.decided()) {
            return Folded.undecided(consumed, inner.partial());
        }
        if (inner.kind() == Origin.NEW) {
            return new Folded(inner.kind(), inner.valueId(), consumed, inner.partial());
        }
        int fqn = applyInvocationArgs(inner.kind(), inner.valueId(), ref);
        return (fqn < 0) ? Folded.undecided(consumed, inner.partial())
                : new Folded((byte) Origin.NEW, fqn, consumed, inner.partial());
    }

    /**
     * 呼び出し箇所依存の戻り値（C/A）を、その呼び出しの実引数で解決する（経路の情報は使わない）。
     * 実引数が別のファクトリの戻り値なら、そのファクトリを起点（深さ0）として畳んだ事実を使う。
     * 経路の情報も使う版は {@code DataflowResolver.applyInvocationArgs}
     *
     * @param invocation 呼び出しの値（メソッドの戻り値のノード。実引数を持つ）
     * @return 具象型の値の番号。決まらなければ -1
     */
    private int applyInvocationArgs(byte kind, int valueId, int invocation) {
        if (kind == Origin.NEW) {
            return valueId;
        }
        if (kind == 0) {
            return -1;
        }
        int index = Names.parseIntOr(values.strings().get(valueId), -1);
        if (index < 0) {
            return -1;
        }
        int arg = values.argAt(invocation, index);
        if (kind == Origin.REFLECT) {
            if (values.kind(arg) != Origin.LITERAL) {
                return -1;
            }
            // 解析対象に存在しない型名は使わない（文字列の見た目だけで決めない）
            return graph.hierarchy().contains(values.value(arg)) ? values.valueId(arg) : -1;
        }
        if (kind == Origin.PARAM) {
            return concreteTypeOf(arg);
        }
        return -1;
    }

    /**
     * 値から具象型を求める（経路に依存しない分だけ）。{@code DataflowResolver.concreteSlotOf} の ctx 無し版
     *
     * @return 具象型の値の番号。決まらなければ -1
     */
    private int concreteTypeOf(int ref) {
        char kind = values.kind(ref);
        if (kind == Origin.NEW) {
            return values.valueId(ref);
        }
        if (kind == Origin.RETURN) {
            // 部分型が上書きしているメソッドは、実際に動く本体が宣言とは限らないので使わない（reduce と同じ）
            int factory = values.methodId(ref);
            if (factory < 0 || graph.hasOverriders(factory)) {
                return -1;
            }
            // 実引数のファクトリは「その戻り値」という独立した事実なので、深さ0から畳む。
            // 計算途中のメソッド（循環）に当たれば不明
            Folded f = fold(factory, 0);
            return applyInvocationArgs(f.kind(), f.valueId(), ref);
        }
        if (kind == Origin.FIELD) {
            int head = graph.fieldHead(values.value(ref));
            return (values.kind(head) == Origin.NEW) ? values.valueId(head) : -1;
        }
        return -1;
    }

    // ------------------------------------------------------------
    // そのほかの事実（グラフ全体を一度見て作る）
    // ------------------------------------------------------------

    /**
     * メソッドごとに「経路の情報を渡す意味があるか」。
     * 引数をレシーバとして使う、または引数（入れ子のレシーバ・実引数を含む）をそのまま次へ渡すメソッド。
     *
     * <p>「値の中に引数（A）・捕捉した引数（E）があるか」は、値の表を前から 1 回なめて参照ごとに決める
     * （子の参照は親より小さいので、子の答えは先に決まっている）。自分自身と、実引数・レシーバ（r=）の
     * 部分木を見る（new は実引数だけを持つ）。出所の文字列から {@code =A:} を探していたときと違い、
     * 文字列リテラルの中の {@code =A:} を引数と取り違えない
     */
    private static boolean[] usesParameters(CallGraph graph) {
        ValueStore values = graph.values();
        GuardTable guards = graph.guards();
        BitSet hasParam = new BitSet(values.size());
        BitSet hasCaptured = new BitSet(values.size());
        for (int ref = 0; ref < values.size(); ref++) {
            boolean param = values.kind(ref) == Origin.PARAM;
            boolean captured = values.kind(ref) == Origin.CAPTURED;
            for (int k = values.argBegin(ref), end = values.argEnd(ref); k < end; k++) {
                int child = values.argRef(k);
                param |= hasParam.get(child);
                captured |= hasCaptured.get(child);
            }
            int receiver = values.receiver(ref);
            if (receiver != ValueStore.NONE) {
                param |= hasParam.get(receiver);
                captured |= hasCaptured.get(receiver);
            }
            if (param) {
                hasParam.set(ref);
            }
            if (captured) {
                hasCaptured.set(ref);
            }
        }
        boolean[] flags = new boolean[graph.methodCount()];
        boolean[] usesCaptured = new boolean[graph.methodCount()];
        for (int caller = 0; caller < flags.length; caller++) {
            for (int e = graph.edgeStart(caller); e < graph.edgeEnd(caller); e++) {
                if (has(hasParam, graph.recvNode(e)) || has(hasParam, graph.argsNode(e))
                        || guards.onParam(graph.guardOf(e))) {
                    flags[caller] = true;
                }
                if (has(hasCaptured, graph.recvNode(e)) || has(hasCaptured, graph.argsNode(e))) {
                    usesCaptured[caller] = true;
                }
            }
        }
        // ラムダが捕捉した引数を使うなら、その値を持っているのは生成箇所の
        // フレーム（＝囲みメソッド）なので、囲みメソッドにも経路の引数が要る。
        // ここで立てないと、ラムダを作るだけのメソッドは「引数を使わない」と
        // 見なされて引数が束縛されず、捕捉した値が本体に届かない
        for (int caller = 0; caller < flags.length; caller++) {
            if (flags[caller]) {
                continue;
            }
            for (int e = graph.edgeStart(caller); e < graph.edgeEnd(caller); e++) {
                int callee = graph.calleeOf(e);
                if (callee >= 0 && graph.methods().isLambdaBody(callee) && usesCaptured[callee]) {
                    flags[caller] = true;
                    break;
                }
            }
        }
        return flags;
    }

    /** 参照の部分木にその種別があるか（参照が無ければ false） */
    private static boolean has(BitSet subtree, int ref) {
        return ref != ValueStore.NONE && subtree.get(ref);
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

    /**
     * "typeFqn#name" → 本体を持つ（ソース上の）メソッドID。
     *
     * 並びはリフレクションの候補（{@code Method.invoke} で名前しか分からないとき）として
     * call-hierarchy.csv の行の順になり、候補の数の上限で切るときにどれが残るかも決めるので、
     * ID の順（キャッシュ上の並びで変わる）にせず宣言の位置の順
     * （宣言ファイル → {@link MethodTable#compareDeclarationOrder}）に並べる
     */
    private static Map<String, IntArray> methodsByName(MethodTable methods) {
        Map<String, IntArray> map = new HashMap<>();
        for (int id = 0; id < methods.size(); id++) {
            if (!methods.hasBody(id) || !methods.hasSource(id)) {
                continue;
            }
            String k = methods.typeFqn(id) + "#" + methods.methodName(id);
            map.computeIfAbsent(k, key -> new IntArray(2)).add(id);
        }
        // 並べ替えが要るのは同名のメソッド（オーバーロード）がある名前だけ。ほとんどは 1 件なので触らない
        Comparator<Integer> order = Comparator.<Integer, String>comparing(methods::declFile)
                .thenComparing(methods::compareDeclarationOrder);
        for (IntArray ids : map.values()) {
            if (ids.size() < 2) {
                continue;
            }
            List<Integer> sorted = new ArrayList<>(ids.size());
            for (int i = 0; i < ids.size(); i++) {
                sorted.add(ids.get(i));
            }
            sorted.sort(order);
            for (int i = 0; i < sorted.size(); i++) {
                ids.set(i, sorted.get(i));
            }
        }
        return map;
    }
}
