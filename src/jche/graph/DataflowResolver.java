// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import jche.cache.ModifierTokens;
import jche.cache.Origin;
import jche.dataflow.DataflowFacts;
import jche.util.Names;

/**
 * データフロー解析（読み手の判断）。値（値の表 {@link ValueStore} の参照）から具象クラスを特定する。
 *
 * ここは「値 → 具象型」の問い合わせ器で、自分では事実を育てない。経路に依存しない事実
 * （ファクトリの戻り値の畳み込み結果など）はフェーズ2b（{@code jche.dataflow.DataflowBuilder}）が
 * グラフ全体から一括で確定した {@link DataflowFacts} を読むだけ。だから同じエッジへの問い合わせは
 * 何回・どの順で呼んでも同じ答えになる（Issue #80）。
 *
 * <h2>値は値の表から読む</h2>
 * 値の種別・値・実引数・レシーバは値の表の列から読む（{@link ValueStore#kind} / {@link ValueStore#value} /
 * {@link ValueStore#argAt} / {@link ValueStore#receiver}）。出所の文字列（{@link Origin}）に組み直して
 * 区切り文字で分けることはしないので、値そのもの（文字列リテラル・定数）が {@code | ; { }} を含んでも
 * 読み違えない。経路の環境（{@link DataflowContext}）の枠は {@link Slot}（札付きの {@code long}）で、
 * 中身は値の表の文字列の番号か参照なので、経路を歩く間に文字列を作らない。
 *
 * <h2>ファクトリの戻り値・引数・フィールド</h2>
 * レシーバがメソッドの戻り値なら、その宣言の return を見て具象型を決める（事実として確定済み）。
 * 呼び出し箇所の実引数までは値の表に載っているので、クラス名の文字列を
 * 受け取るファクトリもここで決まる。
 * 引数・フィールド由来は経路に依存するため、{@link DataflowContext}（経路から分かった
 * 引数の具象型・コンストラクタ実引数）を受け取って経路ごとに判定する。
 *
 * <h2>リフレクション</h2>
 * Class.forName / X.class / obj.getClass() → getMethod / getDeclaredMethod →
 * Method.invoke、および getConstructor → Constructor.newInstance / Class.newInstance
 * の連鎖を、C行の値（レシーバの入れ子と実引数のリテラル）から辿り、
 * 実際に動くメソッドへ解決する。
 *
 * 追える範囲（キャッシュの収集範囲に依存する）:
 * <ul>
 *   <li>クラス名・メソッド名が文字列リテラルかコンパイル時定数、または
 *       囲みメソッドの引数（経路上の呼び出し元でリテラルが渡っている）のとき</li>
 *   <li>クラスが X.class、Class.forName(リテラル)、obj.getClass()（obj の具象型が分かる）のとき</li>
 *   <li>Method / Class オブジェクトが同じメソッド内のローカル変数を経由するとき</li>
 * </ul>
 * 追えないもの: 設定ファイル・DB・アノテーションから来る名前、
 * Method / Class オブジェクトをフィールドや別メソッドの引数で受け渡す形
 * （実引数・フィールド代入の値は入れ子を持たないため）。
 */
public final class DataflowResolver {

    // --- リフレクションAPIの種別（jche.dataflow.DataflowBuilder が判定する。値はそちらと揃える） ---
    public static final int REFLECT_NONE = 0;
    public static final int REFLECT_INVOKE = 1;
    public static final int REFLECT_FOR_NAME = 2;
    public static final int REFLECT_CLASS_NEW_INSTANCE = 3;
    public static final int REFLECT_CTOR_NEW_INSTANCE = 4;

    private static final String CLASS_FOR_NAME_PREFIX = "java.lang.Class#forName(";
    private static final String CLASS_GET_METHOD =
            "java.lang.Class#getMethod(java.lang.String,java.lang.Class[])";
    private static final String CLASS_GET_DECLARED_METHOD =
            "java.lang.Class#getDeclaredMethod(java.lang.String,java.lang.Class[])";
    private static final String CLASS_GET_CTOR = "java.lang.Class#getConstructor(java.lang.Class[])";
    private static final String CLASS_GET_DECLARED_CTOR =
            "java.lang.Class#getDeclaredConstructor(java.lang.Class[])";
    private static final String OBJECT_GET_CLASS = "java.lang.Object#getClass()";

    /**
     * 値の入れ子を辿る段数の安全策（再帰なので）。値の子の参照は親より小さいので辿りは必ず止まるが、
     * 念のため上限を掛ける。書き手は値の入れ子を 256 段で打ち切るので、ここには当たらない
     */
    static final int MAX_NEST = 512;

    private final CallGraph graph;
    private final MethodTable methods;
    private final ValueStore values;
    private final StringPool strings;
    private final DataflowFacts facts;
    private final boolean enabled;
    /** 経路依存の探索（classOf / literalOf）で、引数や戻り値を何段まで辿るか */
    private final int maxDepth;

    /**
     * @param facts    フェーズ2bで確定した事実
     * @param enabled  dataflow.enabled
     * @param maxDepth dataflow.max.depth
     */
    public DataflowResolver(CallGraph graph, DataflowFacts facts, boolean enabled, int maxDepth) {
        this.graph = graph;
        this.methods = graph.methods;
        this.values = graph.values();
        this.strings = values.strings();
        this.facts = facts;
        this.enabled = enabled;
        this.maxDepth = (maxDepth > 0) ? maxDepth : 1;
    }

    public boolean enabled() {
        return enabled;
    }

    /** 値の表（同じパッケージの読み手が、ここで読んだ参照の中身を見るのに使う） */
    ValueStore values() {
        return values;
    }

    /**
     * どの材料で具象クラスを決めたかを表すラベル。
     *
     * 何を根拠に絞ったかで、利用者が結果をどれだけ信用してよいかが変わるため、
     * 値の種別ごとに分ける（注記に「解決:ラベル」として出る）。
     *
     * @param recvKind レシーバの値の種別（{@link ValueStore#kind}。無ければ {@link Origin#UNKNOWN}）
     */
    public static String labelFor(char recvKind) {
        return switch (recvKind) {
            case Origin.FIELD -> Resolution.DATAFLOW_FIELD;
            case Origin.PARAM, Origin.CAPTURED -> Resolution.DATAFLOW_PARAM;
            case Origin.NEW -> Resolution.DATAFLOW_NEW;
            case Origin.FUNCTIONAL -> Resolution.DATAFLOW_LAMBDA;
            default -> Resolution.DATAFLOW_FACTORY;
        };
    }

    /**
     * レシーバの値から具象クラスを決め、その実装のメソッドIDを返す。無ければ -1。
     *
     * @param recv レシーバの値（{@link CallGraph#recvNode}）。無ければ {@link ValueStore#NONE}
     * @param ctx  経路から確定した引数・コンストラクタ実引数（無ければ null）
     */
    public int targetOf(int recv, int calleeId, DataflowContext ctx) {
        if (!enabled || recv == ValueStore.NONE) {
            return -1;
        }
        // ラムダ／メソッド参照が渡ってきた呼び出しだけ、型を経由せずメソッドを直接引く。
        // 関数型インターフェースのメソッド（M行がある＝実装しているラムダが1つでもある）に
        // 限るのは、ラムダを入れた変数への Object#toString() のような、
        // 関数型インターフェースと関係ない呼び出しまで本体に繋がないため
        if (graph.hasFunctionalImpl(calleeId)) {
            int functional = functionalTargetOf(recv, ctx);
            if (functional >= 0) {
                return functional;
            }
        }
        String fqn = concreteTypeOf(recv, ctx);
        if (fqn == null) {
            return -1;
        }
        // 結果が宣言型のままでも、候補が1つに定まったこと自体が成果なので返す
        return graph.implementationOf(fqn, calleeId);
    }

    /**
     * ラムダ／メソッド参照が渡ってきた呼び出しの、実際に動くメソッド。無ければ -1。
     *
     * ここだけは具象「型」を経由しない。ラムダの本体は合成メソッドで、
     * 関数型インターフェースのメソッド（{@code Runnable#run()}）を
     * オーバーライドしているわけではないので、型とシグネチャからは引けないため。
     *
     * <p>メソッド参照の参照先が仮想メソッドで、束縛したレシーバ（{@code dao::describe} の
     * {@code dao}）の具象型が分かるなら、その型で実際に動く実装を返す（JLS 15.13.3）。
     * 分からなければ参照先の宣言そのもの。仮想メソッドの候補を複数返す形は
     * {@link CallResolver} が {@link #functionalRefOf} から組み立てる
     */
    public int functionalTargetOf(int ref, DataflowContext ctx) {
        int functional = functionalRefOf(ref, ctx);
        if (functional == ValueStore.NONE) {
            return -1;
        }
        int target = values.methodId(functional);
        if (target < 0) {
            return -1;
        }
        int viaReceiver = functionalReceiverImpl(functional, target);
        return (viaReceiver >= 0) ? viaReceiver : target;
    }

    /**
     * メソッド参照が束縛したレシーバの具象型で実際に動く実装。決まらなければ -1。
     *
     * レシーバの値は参照を書いたメソッドから見たもので、今歩いている経路の
     * フレームとは別なので、経路の文脈（引数の環境）は使わない。フレームに依らず決まる
     * 値（new・値が1つのフィールド・引数を使わないファクトリ）だけで決める
     *
     * @param functional ラムダ／メソッド参照の値（{@link #functionalRefOf} が返した {@code Z} のノード）
     */
    public int functionalReceiverImpl(int functional, int target) {
        int receiver = values.receiver(functional);
        if (receiver == ValueStore.NONE || methods.isLambdaBody(target)) {
            return -1;
        }
        String fqn = concreteTypeOf(receiver, null);
        return (fqn == null) ? -1 : graph.implementationOf(fqn, target);
    }

    /**
     * 値が指している「関数型インターフェースの実装」（{@code Z} のノード。束縛したレシーバがあれば
     * {@code r=} 付き）。無ければ {@link ValueStore#NONE}
     */
    public int functionalRefOf(int ref, DataflowContext ctx) {
        char kind = values.kind(ref);
        if (kind == Origin.FUNCTIONAL) {
            return ref;
        }
        if (kind == Origin.FIELD) {
            // フィールドに保持されたラムダ（private Runnable task = () -> ...;）。
            // 値が1つに定まっているフィールドだけが表に載っているので、
            // どのラムダが入るかは経路を見なくても決まる（フィールドの値は頭だけ。束縛したレシーバは持たない）
            int held = graph.fieldHead(values.value(ref));
            return (values.kind(held) == Origin.FUNCTIONAL) ? held : ValueStore.NONE;
        }
        // 引数・捕捉した引数として渡ってきたラムダ。経路の環境には FUNCTIONAL の枠で入っている
        if (ctx == null) {
            return ValueStore.NONE;
        }
        long[] args = (kind == Origin.PARAM) ? ctx.params()
                : (kind == Origin.CAPTURED) ? ctx.captured() : null;
        long slot = slotAt(args, ref);
        return (Slot.tag(slot) == Slot.FUNCTIONAL) ? Slot.payload(slot) : ValueStore.NONE;
    }

    // ------------------------------------------------------------
    // 値 → 具象型
    // ------------------------------------------------------------

    /**
     * 値から具象型を求める。経路に依存する部分は ctx から取る。具象型が決まらなければ null
     * （経路の環境で型ではなく値が入っている枠は、型としては使わない）
     */
    public String concreteTypeOf(int ref, DataflowContext ctx) {
        long slot = concreteSlotOf(ref, ctx, 0);
        return Slot.isType(slot) ? strings.get(Slot.payload(slot)) : null;
    }

    /**
     * 値から具象型を求めた枠。ふつうは {@link Slot#TYPE} だが、コンストラクタで受け取るフィールドは
     * 経路で分かっているコンストラクタ実引数の枠をそのまま返すので、値（{@link Slot#LITERAL} など）のこともある
     * （コンストラクタで受け取った値を、フィールドを経て次の呼び出しへ渡すため。{@link #fieldSlotOf}）。
     * 決まらなければ {@link Slot#NONE}
     *
     * @param nest 値の入れ子を辿った段数（{@link #MAX_NEST} の安全策）
     */
    private long concreteSlotOf(int ref, DataflowContext ctx, int nest) {
        if (nest > MAX_NEST) {
            return Slot.NONE;
        }
        char kind = values.kind(ref);
        if (kind == Origin.NEW) {
            return Slot.of(Slot.TYPE, values.valueId(ref));
        }
        if (kind == Origin.RETURN) {
            int factory = bodyOf(ref, ctx);
            if (factory < 0) {
                return Slot.NONE;
            }
            return applyInvocationArgs(facts.factoryKind(factory), facts.factoryValueId(factory), ref, ctx,
                    nest);
        }
        if (kind == Origin.PARAM && ctx != null && ctx.params() != null) {
            return typeAt(ctx.params(), ref);
        }
        if (kind == Origin.CAPTURED && ctx != null && ctx.captured() != null) {
            // ラムダが捕捉した、囲みメソッドの引数。生成箇所のフレームの引数を当てる
            return typeAt(ctx.captured(), ref);
        }
        if (kind == Origin.FIELD) {
            return fieldSlotOf(values.value(ref), ctx);
        }
        return Slot.NONE;
    }

    /**
     * メソッドの戻り値の値（{@code M} のノード）が表す呼び出しで、実際に動く本体が 1 つに決まるなら
     * そのメソッドID。決まらなければ -1（その return の値は使わない）。
     *
     * <p>関数型インターフェースのメソッドの戻り値（{@code s.get()}）で、レシーバがラムダ／メソッド参照と
     * 分かるなら、返す値はその本体（またはメソッド参照の参照先）の return で決まる。
     * 関数型インターフェースのメソッド以外（{@code Object#toString()} 等）には当てない。
     * 束縛したレシーバの具象型から引いた実装（{@link #functionalReceiverImpl}）は、その型で実際に動く本体なのでそのまま使う。
     *
     * <p>それ以外は、呼び出し先の宣言の本体を使えるのは部分型に上書きされていないときだけ
     * （{@link CallGraph#hasOverriders}）。上書きされていれば、実際に動くのは部分型の本体かもしれない
     */
    private int bodyOf(int ref, DataflowContext ctx) {
        int callee = values.methodId(ref);
        if (callee < 0) {
            return -1;
        }
        int receiver = values.receiver(ref);
        if (receiver != ValueStore.NONE && graph.hasFunctionalImpl(callee)) {
            int functional = functionalRefOf(receiver, ctx);
            int target = (functional == ValueStore.NONE) ? -1 : values.methodId(functional);
            if (target >= 0) {
                int viaReceiver = functionalReceiverImpl(functional, target);
                if (viaReceiver >= 0) {
                    return viaReceiver;
                }
                callee = target;   // ラムダの本体か、メソッド参照の参照先（仮想なら下で上書きを調べる）
            }
        }
        return graph.hasOverriders(callee) ? -1 : callee;
    }

    /** 経路から分かっている引数の並びから、その位置（値は引数位置）の具象型の枠を取る。分からなければ {@link Slot#NONE} */
    private long typeAt(long[] args, int ref) {
        // 型ではなく値（リテラル・クラスなど）が入っている引数は、具象型としては不明
        long slot = slotAt(args, ref);
        return Slot.isType(slot) ? slot : Slot.NONE;
    }

    /** 経路の枠の並びの、値（A・E の引数位置）が指す枠。範囲の外・並びが無ければ {@link Slot#NONE} */
    private long slotAt(long[] args, int ref) {
        if (args == null) {
            return Slot.NONE;
        }
        int idx = values.index(ref);
        return (idx < 0 || idx >= args.length) ? Slot.NONE : args[idx];
    }

    /**
     * 呼び出し箇所依存の戻り値（C/A）を、その呼び出しの実引数で解決する。
     *
     * @param kind       そのメソッドが必ず返す値の種別（{@link DataflowFacts#factoryKind}。決まらなければ 0）
     * @param valueId    その値の番号（{@link DataflowFacts#factoryValueId}）
     * @param invocation 呼び出しの値（メソッドの戻り値のノード。実引数を持つ）
     * @param ctx        実引数がさらに外側に依存する場合に使う文脈。無ければ null
     */
    private long applyInvocationArgs(byte kind, int valueId, int invocation, DataflowContext ctx, int nest) {
        if (kind == Origin.NEW) {
            return Slot.of(Slot.TYPE, valueId);
        }
        if (kind == 0) {
            return Slot.NONE;
        }
        int index = Names.parseIntOr(strings.get(valueId), -1);
        if (index < 0) {
            return Slot.NONE;
        }
        int arg = values.argAt(invocation, index);
        if (kind == Origin.REFLECT) {
            // Class.forName(引数) 形式。実引数がクラス名の文字列なら型が決まる
            if (values.kind(arg) != Origin.LITERAL) {
                return Slot.NONE;
            }
            // 解析対象に存在しない型名は使わない（文字列の見た目だけで決めない）
            return graph.hierarchy.contains(values.value(arg))
                    ? Slot.of(Slot.TYPE, values.valueId(arg)) : Slot.NONE;
        }
        if (kind == Origin.PARAM) {
            return concreteSlotOf(arg, ctx, nest + 1);
        }
        return Slot.NONE;
    }

    /**
     * コンストラクタ注入されたフィールドの具象型の枠。
     *
     * フィールドの値の頭（V行・J行から FieldFacts が判定した「このフィールドには必ずこの値が入る」。
     * {@link CallGraph#fieldHead}）を引き、コンストラクタの引数なら経路上で分かっている実引数と突き合わせる。
     * 引数ではなく初期化子の new で決まっているフィールドは、経路を見ずに決まる。
     *
     * <p>コンストラクタの引数なら、経路で分かっている実引数の枠を<b>そのまま</b>返す（値のこともある）。
     * コンストラクタで受け取った文字列を、フィールドを経て別のメソッドへ渡す経路で、その値を運ぶため
     */
    private long fieldSlotOf(String fieldKey, DataflowContext ctx) {
        int head = graph.fieldHead(fieldKey);
        char kind = values.kind(head);
        if (kind == Origin.NEW) {
            return Slot.of(Slot.TYPE, values.valueId(head));
        }
        if (kind != Origin.PARAM || ctx == null || ctx.ctorArgs() == null) {
            return Slot.NONE;
        }
        // このコンストラクタ実引数が、本当にこのフィールドを持つ型のものか。
        // 親クラスのフィールドにサブクラスのコンストラクタ実引数を当てないため
        // （鍵は "型FQN#フィールド名"。FQN は '#' を含まないので、先頭が型FQN + '#' かで比べられる）
        String owner = ctx.ctorOwner();
        if (owner == null || !fieldKey.startsWith(owner) || fieldKey.length() <= owner.length()
                || fieldKey.charAt(owner.length()) != '#') {
            return Slot.NONE;
        }
        return slotAt(ctx.ctorArgs(), head);
    }

    /**
     * そのメソッドに経路の情報を渡す意味があるか。
     *
     * 意味が無いメソッドには渡さないことで、データフロー解析を
     * 「必要な場合のみ」に絞る。次のいずれかなら意味がある。
     * <ul>
     *   <li>引数をレシーバとして使う、または引数をそのまま次へ渡す</li>
     *   <li>引数を条件分岐（jche.cache.Guard）の判定に使う
     *       （その経路で呼ばれない呼び出しを見分けるのに、渡された値が要る）</li>
     *   <li>コンストラクタ注入されたフィールドを持つ型のメソッド
     *       （自分のメソッドを呼び合った先でフィールドを使うことがある）</li>
     * </ul>
     */
    public boolean usesContext(int methodId) {
        if (!enabled || methodId < 0) {
            return false;
        }
        if (facts.usesParameters(methodId)) {
            return true;
        }
        return graph.hasInjectedFields(methods.typeFqn(methodId));
    }

    /**
     * 呼び出し箇所の実引数（{@code holder} の位置ごとの値）を、この経路で分かっている枠の並びにする
     * （呼び出し先の引数の環境。{@code new X(...)} のレシーバを渡せばコンストラクタ実引数の環境）。
     *
     * 具象型が決まる実引数は型、決まらなくてもリテラルやクラス・定数・ラムダなら値として入れる
     * （リフレクションのメソッド名・クラスが引数で渡ってくる形や、条件分岐の判定のため）。
     * 並びは実引数の位置で引けるよう、最も大きい位置まで伸ばす（分からない位置は {@link Slot#NONE}）。
     * 何も分からなければ null
     *
     * @param holder 実引数の並び（{@link CallGraph#argsNode}）か、{@code new} のノード。無ければ {@link ValueStore#NONE}
     * @param ctx    呼び出し元の段で分かっていること（無ければ null）
     */
    public long[] bindArgs(int holder, DataflowContext ctx) {
        long[] bound = null;
        for (int k = values.argBegin(holder), end = values.argEnd(holder); k < end; k++) {
            int index = values.argPos(k);
            int arg = values.argRef(k);
            long slot = concreteSlotOf(arg, ctx, 0);
            if (slot == Slot.NONE) {
                // 具象型は決まらないが、リテラルやクラスリテラルなら「値」として渡す
                // （リフレクションのメソッド名・クラスが引数で渡ってくる形のため）
                slot = valueSlotOf(arg, ctx);
            }
            if (slot == Slot.NONE) {
                continue;
            }
            if (bound == null) {
                bound = new long[index + 1];
            } else if (index >= bound.length) {
                bound = Arrays.copyOf(bound, index + 1);
            }
            bound[index] = slot;
        }
        return bound;
    }

    // ------------------------------------------------------------
    // リフレクション
    // ------------------------------------------------------------

    public int reflectiveKindOf(int methodId) {
        return facts.reflectKind(methodId);
    }

    /**
     * リフレクション呼び出しを、実際に動くメソッドへ解決する。
     *
     * @param ctx 経路から確定した引数の値（無ければ null。その場合はリテラルだけで決める）
     * @return 解決できなければ null
     */
    public Resolution reflectiveResolution(int edgeIndex, DataflowContext ctx) {
        if (!enabled) {
            return null;
        }
        int calleeId = graph.calleeOf(edgeIndex);
        switch (reflectiveKindOf(calleeId)) {
            case REFLECT_INVOKE: {
                int[] targets = invokeTargets(graph.recvNode(edgeIndex), graph.argsNode(edgeIndex), ctx);
                return (targets == null) ? null : new Resolution(targets, Resolution.REFLECTION);
            }
            case REFLECT_FOR_NAME: {
                // Class.forName("a.B") はクラスを初期化する。static初期化子があればそこへ
                int fqn = classNameOf(values.argAt(graph.argsNode(edgeIndex), 0), ctx);
                int init = (fqn < 0) ? -1 : methods.idOf(strings.get(fqn) + "#<clinit>()");
                return (init < 0) ? null : Resolution.single(init, Resolution.REFLECTION_INIT);
            }
            case REFLECT_CLASS_NEW_INSTANCE: {
                int fqn = classOf(graph.recvNode(edgeIndex), ctx, 0);
                int ctor = (fqn < 0) ? -1 : methods.idOf(strings.get(fqn) + "#<init>()");
                return (ctor < 0) ? null : Resolution.single(ctor, Resolution.REFLECTION);
            }
            case REFLECT_CTOR_NEW_INSTANCE: {
                int recv = graph.recvNode(edgeIndex);
                if (values.kind(recv) != Origin.RETURN) {
                    return null;
                }
                String v = values.value(recv);
                if (!CLASS_GET_CTOR.equals(v) && !CLASS_GET_DECLARED_CTOR.equals(v)) {
                    return null;
                }
                int fqn = classOf(values.receiver(recv), ctx, 0);
                String params = paramTypesOf(recv, 0, ctx);
                if (fqn < 0 || params == null) {
                    return null;
                }
                int ctor = methods.idOf(strings.get(fqn) + "#<init>(" + params + ")");
                return (ctor < 0) ? null : Resolution.single(ctor, Resolution.REFLECTION);
            }
            default:
                return null;
        }
    }

    /**
     * Method.invoke(obj, args) の呼び出し先。
     *
     * レシーバ（Method）が getMethod / getDeclaredMethod の戻り値で、その受け手のクラスと
     * 第1引数（メソッド名）が分かれば決まる。引数型（第2引数以降のクラスリテラル）が
     * 揃っていればシグネチャで1件に、揃わなければ名前が一致する本体付きメソッドを候補にする。
     * invoke の第1引数（実際のレシーバ）の具象型が分かれば、その型の実装を優先する。
     *
     * @param recv       invoke のレシーバ（Method）の値
     * @param invokeArgs invoke の実引数の並び
     */
    private int[] invokeTargets(int recv, int invokeArgs, DataflowContext ctx) {
        if (values.kind(recv) != Origin.RETURN) {
            return null;
        }
        String v = values.value(recv);
        if (!CLASS_GET_METHOD.equals(v) && !CLASS_GET_DECLARED_METHOD.equals(v)) {
            return null;
        }
        int nameId = literalOf(values.argAt(recv, 0), ctx, 0);
        int ownerId = classOf(values.receiver(recv), ctx, 0);
        if (nameId < 0 || ownerId < 0) {
            return null;
        }
        String name = strings.get(nameId);
        String owner = strings.get(ownerId);
        boolean declaredOnly = CLASS_GET_DECLARED_METHOD.equals(v);
        String recvType = concreteTypeOf(values.argAt(invokeArgs, 0), ctx);
        String dispatch = (recvType != null && graph.hierarchy.isSubtypeOf(recvType, owner)) ? recvType : null;

        String params = paramTypesOf(recv, 1, ctx);
        if (params != null) {
            // リフレクションは実引数から名前と引数型を組み立てるので、宣言している型は
            // 分からない。上書きの引きもシグネチャで行う（implementationOfSignature）
            String sig = name + "(" + params + ")";
            int found = declaredOnly ? declaredIn(owner, sig) : graph.implementationOfSignature(owner, sig);
            if (found >= 0 && !dispatchesVirtually(found)) {
                return new int[] {found};   // private・static は実行時のクラスで選び直さない
            }
            int id = (dispatch == null) ? -1 : graph.implementationOfSignature(dispatch, sig);
            if (id < 0) {
                id = found;
            }
            return (id < 0) ? null : new int[] {id};
        }
        IntArray ids = new IntArray(2);
        IntArray named = declaredOnly ? facts.methodsNamed(owner, name) : methodsNamedAlongSupertypes(owner, name);
        for (int i = 0; named != null && i < named.size(); i++) {
            int id = named.get(i);
            if (dispatchesVirtually(id)) {
                // 実際に動くのは、受け手の実行時のクラス（分からなければ owner）から探した実装
                String from = (dispatch == null) ? owner : dispatch;
                int impl = graph.implementationOfSignature(from, methods.signature(id));
                if (impl >= 0) {
                    id = impl;
                }
            }
            ids.addIfAbsent(id);
        }
        if (ids.isEmpty() && dispatch != null) {
            // owner の宣言に本体が無い（インターフェース・抽象メソッド）。受け手の実行時のクラスから引く
            IntArray fromReceiver = methodsNamedAlongSupertypes(dispatch, name);
            for (int i = 0; i < fromReceiver.size(); i++) {
                ids.addIfAbsent(fromReceiver.get(i));
            }
        }
        return ids.isEmpty() ? null : ids.toArray();
    }

    /**
     * リフレクションの invoke が、受け手の実行時のクラスで実装を選び直すメソッドか（JLS 15.12.4.4 と同じ）。
     * private（上書きされない。JLS 8.4.8）と static（受け手を使わない）は、見つけた宣言そのものが動く
     */
    private boolean dispatchesVirtually(int id) {
        String mods = methods.mods(id);
        return !ModifierTokens.has(mods, "private") && !ModifierTokens.has(mods, "static");
    }

    /** その型自身が宣言している、そのシグネチャのメソッド（{@code getDeclaredMethod} は親を探さない）。無ければ -1 */
    private int declaredIn(String typeFqn, String sig) {
        int id = methods.idOf(typeFqn + "#" + sig);
        return (id >= 0 && methods.hasBody(id)) ? id : -1;
    }

    /**
     * getMethod / getConstructor の引数のクラスリテラルから、引数型のカンマ区切りを作る。
     * 引数の数は n= で分かるので、値の無い引数（変数など）があれば「不明」として null を返す
     */
    private String paramTypesOf(int ref, int from, DataflowContext ctx) {
        int count = values.argCount(ref);
        if (count < 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < count; i++) {
            int t = classOf(values.argAt(ref, i), ctx, 0);
            if (t < 0) {
                return null;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(strings.get(t));
        }
        return sb.toString();
    }

    /** 値が表すクラス（Class オブジェクト）のFQN の番号。分からなければ -1 */
    private int classOf(int ref, DataflowContext ctx, int depth) {
        if (depth > maxDepth) {
            return -1;
        }
        switch (values.kind(ref)) {
            case Origin.CLASS:
                return values.valueId(ref);
            case Origin.LITERAL:
                return classNameOf(ref, ctx);
            case Origin.PARAM: {
                long v = paramValueOf(ref, ctx);
                return (v == Slot.NONE) ? -1 : classOfSlot(v, depth + 1);
            }
            case Origin.RETURN: {
                String v = values.value(ref);
                if (v.startsWith(CLASS_FOR_NAME_PREFIX)) {
                    return classNameOf(values.argAt(ref, 0), ctx);
                }
                if (OBJECT_GET_CLASS.equals(v)) {
                    // 具象型だけを採る。コンストラクタで受け取るフィールドは、経路の環境の値の枠
                    // （リテラルなど）をそのまま返しうる（fieldSlotOf）が、それは型ではない
                    long type = concreteSlotOf(values.receiver(ref), ctx, 0);
                    return Slot.isType(type) ? Slot.payload(type) : -1;
                }
                // ソース上のメソッドが Class を返す形。全ての return が同じクラスなら決まる。
                // 部分型が上書きしているメソッドは、実際に動く本体が違いうるので使わない（CallGraph#hasOverriders）
                int m = values.methodId(ref);
                int count = graph.returnCount(m);
                if (count == 0 || graph.hasOverriders(m)) {
                    return -1;
                }
                int found = -1;
                for (int k = 0; k < count; k++) {
                    int c = classOf(graph.returnAt(m, k), null, depth + 1);
                    if (c < 0 || (found >= 0 && found != c)) {
                        return -1;
                    }
                    found = c;
                }
                return found;
            }
            default:
                return -1;
        }
    }

    /** 経路の環境の値の枠が表すクラスの FQN の番号（クラスか、解析対象の型の名前の文字列）。分からなければ -1 */
    private int classOfSlot(long slot, int depth) {
        if (depth > maxDepth) {
            return -1;
        }
        return switch (Slot.tag(slot)) {
            case Slot.CLASS -> Slot.payload(slot);
            case Slot.LITERAL -> graph.hierarchy.contains(strings.get(Slot.payload(slot))) ? Slot.payload(slot) : -1;
            default -> -1;
        };
    }

    /** クラス名の文字列（リテラル・定数・経路上の引数）が解析対象の型を指していればそのFQN の番号。無ければ -1 */
    private int classNameOf(int ref, DataflowContext ctx) {
        int name = literalOf(ref, ctx, 0);
        // 解析対象に存在しない型名は使わない（文字列の見た目だけで決めない）
        return (name >= 0 && graph.hierarchy.contains(strings.get(name))) ? name : -1;
    }

    /**
     * 値が表す文字列の値（リテラル・コンパイル時定数と、委譲を畳んだ戻り値）。分からなければ null。
     *
     * <p>種類 C の契約表（{@link TypeContracts}）が、ファクトリに渡されたキーを引くのに使う。
     * 辿る段数は {@code dataflow.max.depth} で頭打ちになり、それを超えるものは「分からない」に倒す。
     */
    String literalValueOf(int ref, DataflowContext ctx) {
        int id = literalOf(ref, ctx, 0);
        return (id < 0) ? null : strings.get(id);
    }

    /** 値が表す文字列の値（リテラル・定数・経路上の引数）の番号。分からなければ -1 */
    private int literalOf(int ref, DataflowContext ctx, int depth) {
        if (depth > maxDepth) {
            return -1;
        }
        switch (values.kind(ref)) {
            case Origin.LITERAL:
                return values.valueId(ref);
            case Origin.PARAM: {
                long v = paramValueOf(ref, ctx);
                return (v == Slot.NONE) ? -1 : literalOfSlot(v, depth + 1);
            }
            case Origin.RETURN: {
                // 全ての return が同じ文字列なら決まる。部分型が上書きしているメソッドは、
                // 実際に動く本体が違いうるので使わない（CallGraph#hasOverriders）
                int m = values.methodId(ref);
                int count = graph.returnCount(m);
                if (count == 0 || graph.hasOverriders(m)) {
                    return -1;
                }
                int found = -1;
                for (int k = 0; k < count; k++) {
                    int s = literalOf(graph.returnAt(m, k), null, depth + 1);
                    if (s < 0 || (found >= 0 && found != s)) {
                        return -1;
                    }
                    found = s;
                }
                return found;
            }
            default:
                return -1;
        }
    }

    /** 経路の環境の値の枠が表す文字列の値の番号（文字列の値だけ）。分からなければ -1 */
    private int literalOfSlot(long slot, int depth) {
        if (depth > maxDepth) {
            return -1;
        }
        return (Slot.tag(slot) == Slot.LITERAL) ? Slot.payload(slot) : -1;
    }

    /**
     * 経路上で分かっている引数の「値」の枠（文字列・クラス・定数・ラムダ）。具象型の枠や分からない枠は
     * {@link Slot#NONE}（{@link #valueSlotOf} 参照）
     */
    private long paramValueOf(int ref, DataflowContext ctx) {
        if (ctx == null) {
            return Slot.NONE;
        }
        long v = slotAt(ctx.params(), ref);
        return Slot.isValue(v) ? v : Slot.NONE;
    }

    /**
     * 経路の引数環境に入れる「値」の枠。具象型が決まらない引数でも、リテラルやクラスなら値として渡す。
     * 渡せる値が無ければ {@link Slot#NONE}
     */
    public long valueSlotOf(int ref, DataflowContext ctx) {
        char kind = values.kind(ref);
        switch (kind) {
            case Origin.LITERAL:
            case Origin.CLASS:
            case Origin.CONST:   // 条件分岐の判定に使う定数（jche.graph.GuardEvaluator）
                return Slot.ofValueKind(kind, values.valueId(ref));
            case Origin.FUNCTIONAL:
                // 実引数で渡されたラムダ／メソッド参照。束縛したレシーバ（r=）は落とさない（ノードごと渡す）。
                // 読み手はフレームに依らない値だけでレシーバを判定する（functionalReceiverImpl）
                return Slot.of(Slot.FUNCTIONAL, ref);
            case Origin.PARAM:
            case Origin.CAPTURED: {
                // ラムダそのものが引数として渡ってきた形も、値として次のフレームへ渡す
                int functional = functionalRefOf(ref, ctx);
                if (functional != ValueStore.NONE) {
                    return Slot.of(Slot.FUNCTIONAL, functional);
                }
                return (kind == Origin.PARAM) ? paramValueOf(ref, ctx) : Slot.NONE;
            }
            case Origin.RETURN: {
                int c = classOf(ref, ctx, 0);
                if (c >= 0) {
                    return Slot.of(Slot.CLASS, c);
                }
                int s = literalOf(ref, ctx, 0);
                return (s < 0) ? Slot.NONE : Slot.of(Slot.LITERAL, s);
            }
            default:
                return Slot.NONE;
        }
    }

    /**
     * 型と、その親型すべての中で、その名前を持つ本体付きメソッド（{@code getMethod} は継承したメソッドも返す）。
     *
     * 最初に見つかった型のものだけにすると、{@code C.class.getMethod("m", types)} で引数型が分からないとき、
     * 親から継承した多重定義（{@code P#m(int)}）を落として、{@code C#m(String)} だけに確定してしまう
     * （docs/value-safety-qa.md の Q22）。親の private なメソッドは {@code getMethod} では見つからないので除く
     * （その型自身のものは除かない。多すぎる側に倒す）
     */
    private IntArray methodsNamedAlongSupertypes(String typeFqn, String name) {
        IntArray found = new IntArray(2);
        ArrayDeque<String> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(typeFqn);
        seen.add(typeFqn);
        while (!queue.isEmpty()) {
            String t = queue.poll();
            IntArray ids = facts.methodsNamed(t, name);
            for (int i = 0; ids != null && i < ids.size(); i++) {
                int id = ids.get(i);
                if (t.equals(typeFqn) || !ModifierTokens.has(methods.mods(id), "private")) {
                    found.addIfAbsent(id);
                }
            }
            for (String s : graph.hierarchy.directSupertypes(t)) {
                if (seen.add(s)) {
                    queue.add(s);
                }
            }
        }
        return found;
    }
}
