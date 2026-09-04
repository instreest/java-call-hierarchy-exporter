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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import jche.cache.Origin;

/**
 * データフロー解析（読み手の判断）。出所（{@link Origin}）から具象クラスを特定する。
 *
 * <h2>ファクトリの戻り値・引数・フィールド</h2>
 * レシーバがメソッドの戻り値なら、その宣言の return を見て具象型を決める。
 * 呼び出し箇所の実引数までは出所に含まれているので、クラス名の文字列を
 * 受け取るファクトリもここで決まる。経路に依存しないのでメモ化できる。
 * 引数・フィールド由来は経路に依存するため、{@link DataflowContext}（経路から分かった
 * 引数の具象型・コンストラクタ実引数）を受け取って経路ごとに判定する。
 *
 * <h2>リフレクション</h2>
 * Class.forName / X.class / obj.getClass() → getMethod / getDeclaredMethod →
 * Method.invoke、および getConstructor → Constructor.newInstance / Class.newInstance
 * の連鎖を、C行の出所（レシーバの入れ子と実引数のリテラル）から辿り、
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
 * （実引数・フィールド代入の出所は入れ子を持たないため）。
 */
public final class DataflowResolver {

    // --- リフレクションAPIの種別 ---
    public static final int REFLECT_NONE = 0;
    public static final int REFLECT_INVOKE = 1;
    public static final int REFLECT_FOR_NAME = 2;
    public static final int REFLECT_CLASS_NEW_INSTANCE = 3;
    public static final int REFLECT_CTOR_NEW_INSTANCE = 4;

    private static final String METHOD_INVOKE =
            "java.lang.reflect.Method#invoke(java.lang.Object,java.lang.Object[])";
    private static final String CLASS_NEW_INSTANCE = "java.lang.Class#newInstance()";
    private static final String CTOR_NEW_INSTANCE =
            "java.lang.reflect.Constructor#newInstance(java.lang.Object[])";
    private static final String CLASS_FOR_NAME_PREFIX = "java.lang.Class#forName(";
    private static final String CLASS_GET_METHOD =
            "java.lang.Class#getMethod(java.lang.String,java.lang.Class[])";
    private static final String CLASS_GET_DECLARED_METHOD =
            "java.lang.Class#getDeclaredMethod(java.lang.String,java.lang.Class[])";
    private static final String CLASS_GET_CTOR = "java.lang.Class#getConstructor(java.lang.Class[])";
    private static final String CLASS_GET_DECLARED_CTOR =
            "java.lang.Class#getDeclaredConstructor(java.lang.Class[])";
    private static final String OBJECT_GET_CLASS = "java.lang.Object#getClass()";

    private final CallGraph graph;
    private final MethodTable methods;
    private final boolean enabled;
    /** ファクトリの委譲（return create();）を何段まで辿るか */
    private final int maxDepth;

    /** factoryReturnOrigin のメモ。未計算と「計算したが不明」を区別する */
    private String[] factoryOrigin;
    private byte[] factoryOriginState;   // 0=未計算 / 1=計算中 / 2=計算済み

    /** メソッドごとに「経路の情報を渡す意味があるか」。初回に一度だけ全エッジを見て作る */
    private boolean[] usesParameters;
    /** メソッドIDごとのリフレクションAPIの種別。初回に一度だけ全メソッドを見て作る */
    private byte[] reflectKinds;
    /** "typeFqn#name" -> 本体を持つメソッドID。引数型が分からないときの名前照合用。必要になったら作る */
    private HashMap<String, IntArray> methodsByName;

    public DataflowResolver(CallGraph graph, boolean enabled, int maxDepth) {
        this.graph = graph;
        this.methods = graph.methods;
        this.enabled = enabled;
        this.maxDepth = (maxDepth > 0) ? maxDepth : 1;
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * どの材料で具象クラスを決めたかを表すラベル。
     *
     * 何を根拠に絞ったかで、利用者が結果をどれだけ信用してよいかが変わるため、
     * 出所の種別ごとに分ける（注記に「解決:ラベル」として出る）。
     */
    public static String labelFor(String recvOrigin) {
        return switch (Origin.kindOf(recvOrigin)) {
            case Origin.FIELD -> Resolution.DATAFLOW_FIELD;
            case Origin.PARAM -> Resolution.DATAFLOW_PARAM;
            case Origin.NEW -> Resolution.DATAFLOW_NEW;
            default -> Resolution.DATAFLOW_FACTORY;
        };
    }

    /**
     * レシーバの出所から具象クラスを決め、その実装のメソッドIDを返す。無ければ -1。
     *
     * @param ctx 経路から確定した引数・コンストラクタ実引数の具象型（無ければ null）
     */
    public int targetOf(String recvOrigin, int calleeId, DataflowContext ctx) {
        if (!enabled || recvOrigin == null) {
            return -1;
        }
        String fqn = concreteTypeOf(recvOrigin, ctx);
        if (fqn == null) {
            return -1;
        }
        // 結果が宣言型のままでも、候補が1つに定まったこと自体が成果なので返す
        return graph.implementationIn(fqn, methods.signature(calleeId));
    }

    // ------------------------------------------------------------
    // 出所 → 具象型
    // ------------------------------------------------------------

    /** 出所から具象型を求める。経路に依存する部分は ctx から取る */
    public String concreteTypeOf(String origin, DataflowContext ctx) {
        char kind = Origin.kindOf(origin);
        if (kind == Origin.NEW) {
            return Origin.valueOf(origin);
        }
        if (kind == Origin.RETURN) {
            int factory = methods.idOf(Origin.valueOf(origin));
            if (factory < 0) {
                return null;
            }
            return applyInvocationArgs(factoryReturnOrigin(factory), Origin.argsOf(origin), ctx);
        }
        if (kind == Origin.PARAM && ctx != null && ctx.paramTypes() != null) {
            int idx = parseIndex(Origin.valueOf(origin));
            if (idx >= 0 && idx < ctx.paramTypes().length) {
                // 型ではなく値（L:/K:）が入っている引数は、具象型としては不明
                String t = ctx.paramTypes()[idx];
                return (t != null && t.indexOf(':') < 0) ? t : null;
            }
            return null;
        }
        if (kind == Origin.FIELD) {
            return fieldTypeOf(Origin.valueOf(origin), ctx);
        }
        return null;
    }

    /**
     * そのメソッドが必ず返す値の出所。特定できなければ null。
     *
     * 「1つでも追跡できない return があれば null」「複数の出所を返すなら null」。
     * 委譲（{@code return create();}）は maxDepth まで辿って畳む。
     *
     * 返すのは具象型（{@code T:}）とは限らない。クラス名の文字列を受け取る
     * ファクトリは {@code C:引数位置}、引数をそのまま返すメソッドは {@code A:引数位置}
     * になる。これらは<b>そのファクトリを呼んでいる箇所の実引数</b>を見て初めて
     * 確定するので、ここではそのまま返して呼び出し側で解決する。
     */
    String factoryReturnOrigin(int methodId) {
        if (methodId < 0 || methodId >= methods.size()) {
            return null;
        }
        if (factoryOrigin == null) {
            factoryOrigin = new String[methods.size()];
            factoryOriginState = new byte[methods.size()];
        }
        return factoryReturnOrigin(methodId, 0);
    }

    private String factoryReturnOrigin(int methodId, int depth) {
        if (factoryOriginState[methodId] == 2) {
            return factoryOrigin[methodId];
        }
        if (factoryOriginState[methodId] == 1) {
            return null;   // 委譲が循環している
        }
        String[] origins = graph.returnOriginsOf(methodId);
        if (origins == null || origins.length == 0) {
            return null;
        }
        factoryOriginState[methodId] = 1;
        String found = null;
        for (String o : origins) {
            String reduced = reduceReturnOrigin(o, depth);
            // 1つでも畳めない return があれば、このメソッドの戻り値は決められない。
            // 分かった分だけで決め打ちすると、別の型を返す経路を取りこぼす
            if (reduced == null || (found != null && !found.equals(reduced))) {
                factoryOriginState[methodId] = 2;
                factoryOrigin[methodId] = null;
                return null;
            }
            found = reduced;
        }
        factoryOriginState[methodId] = 2;
        factoryOrigin[methodId] = found;
        return found;
    }

    /** return 1件の出所を、具象型か「呼び出し箇所依存の形」まで畳む */
    private String reduceReturnOrigin(String origin, int depth) {
        char kind = Origin.kindOf(origin);
        if (kind == Origin.NEW || kind == Origin.REFLECT || kind == Origin.PARAM) {
            return Origin.head(origin);
        }
        if (kind != Origin.RETURN || depth >= maxDepth) {
            return null;
        }
        // 別のファクトリへの委譲。委譲先の出所を、この return が書いている
        // 実引数で解決する（return create("jp.co.X"); のような形を畳むため）
        int delegate = methods.idOf(Origin.valueOf(origin));
        if (delegate < 0) {
            return null;
        }
        String inner = factoryReturnOrigin(delegate, depth + 1);
        if (Origin.kindOf(inner) == Origin.NEW) {
            return inner;
        }
        String fqn = applyInvocationArgs(inner, Origin.argsOf(origin), null);
        return (fqn == null) ? null : Origin.of(Origin.NEW, fqn);
    }

    /**
     * 呼び出し箇所依存の戻り値の出所（C/A）を、その呼び出しの実引数で解決する。
     *
     * @param args 呼び出し箇所の実引数の出所（"0=L:jp.co.X" など）
     * @param ctx  実引数がさらに外側に依存する場合に使う文脈。無ければ null
     */
    private String applyInvocationArgs(String returnOrigin, String args, DataflowContext ctx) {
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
            // Class.forName(引数) 形式。実引数がクラス名の文字列なら型が決まる
            if (Origin.kindOf(arg) != Origin.LITERAL) {
                return null;
            }
            String fqn = Origin.valueOf(arg);
            // 解析対象に存在しない型名は使わない（文字列の見た目だけで決めない）
            return graph.hierarchy.contains(fqn) ? fqn : null;
        }
        if (kind == Origin.PARAM) {
            return concreteTypeOf(arg, ctx);
        }
        return null;
    }

    /**
     * コンストラクタ注入されたフィールドの具象型。
     *
     * fieldOrigins（V行・J行から FieldFacts が判定した「このフィールドには必ずこの出所の
     * 値が入る」）を引き、コンストラクタの引数なら経路上で分かっている実引数と突き合わせる。
     * 引数ではなく初期化子の new で決まっているフィールドは、経路を見ずに決まる。
     */
    private String fieldTypeOf(String fieldKey, DataflowContext ctx) {
        String origin = graph.fieldOrigin(fieldKey);
        if (origin == null) {
            return null;
        }
        if (Origin.kindOf(origin) == Origin.NEW) {
            return Origin.valueOf(origin);
        }
        if (Origin.kindOf(origin) != Origin.PARAM || ctx == null || ctx.ctorArgs() == null) {
            return null;
        }
        // このコンストラクタ実引数が、本当にこのフィールドを持つ型のものか。
        // 親クラスのフィールドにサブクラスのコンストラクタ実引数を当てないため
        String owner = fieldKey.substring(0, fieldKey.indexOf('#'));
        if (!owner.equals(ctx.ctorOwner())) {
            return null;
        }
        int idx = parseIndex(Origin.valueOf(origin));
        return (idx >= 0 && idx < ctx.ctorArgs().length) ? ctx.ctorArgs()[idx] : null;
    }

    /**
     * そのメソッドに経路の情報を渡す意味があるか。
     *
     * 意味が無いメソッドには渡さないことで、データフロー解析を
     * 「必要な場合のみ」に絞る。次のいずれかなら意味がある。
     * <ul>
     *   <li>引数をレシーバとして使う、または引数をそのまま次へ渡す</li>
     *   <li>コンストラクタ注入されたフィールドを持つ型のメソッド
     *       （自分のメソッドを呼び合った先でフィールドを使うことがある）</li>
     * </ul>
     */
    public boolean usesContext(int methodId) {
        if (!enabled || methodId < 0) {
            return false;
        }
        if (usesParameters == null) {
            usesParameters = computeUsesParameters();
        }
        if (methodId < usesParameters.length && usesParameters[methodId]) {
            return true;
        }
        return graph.hasInjectedFields(methods.typeFqn(methodId));
    }

    private boolean[] computeUsesParameters() {
        boolean[] flags = new boolean[methods.size()];
        for (int caller = 0; caller < flags.length; caller++) {
            for (int e = graph.edgeStart(caller); e < graph.edgeEnd(caller); e++) {
                if (Origin.kindOf(graph.recvOrigin(e)) == Origin.PARAM
                        || mentionsParam(graph.recvOrigin(e))   // 入れ子のレシーバ・実引数（リフレクション）
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

    private static int parseIndex(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------
    // リフレクション
    // ------------------------------------------------------------

    public int reflectiveKindOf(int methodId) {
        if (reflectKinds == null) {
            reflectKinds = new byte[methods.size()];
            for (int id = 0; id < reflectKinds.length; id++) {
                String key = methods.key(id);
                if (METHOD_INVOKE.equals(key)) {
                    reflectKinds[id] = REFLECT_INVOKE;
                } else if (key.startsWith(CLASS_FOR_NAME_PREFIX)) {
                    reflectKinds[id] = REFLECT_FOR_NAME;
                } else if (CLASS_NEW_INSTANCE.equals(key)) {
                    reflectKinds[id] = REFLECT_CLASS_NEW_INSTANCE;
                } else if (CTOR_NEW_INSTANCE.equals(key)) {
                    reflectKinds[id] = REFLECT_CTOR_NEW_INSTANCE;
                }
            }
        }
        return (methodId >= 0 && methodId < reflectKinds.length) ? reflectKinds[methodId] : REFLECT_NONE;
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
                int[] targets = invokeTargets(graph.recvOrigin(edgeIndex), graph.argOrigins(edgeIndex), ctx);
                return (targets == null) ? null : new Resolution(targets, Resolution.REFLECTION);
            }
            case REFLECT_FOR_NAME: {
                // Class.forName("a.B") はクラスを初期化する。static初期化子があればそこへ
                String fqn = classNameOf(Origin.argAt(graph.argOrigins(edgeIndex), 0), ctx);
                int init = (fqn == null) ? -1 : methods.idOf(fqn + "#<clinit>()");
                return (init < 0) ? null : Resolution.single(init, Resolution.REFLECTION_INIT);
            }
            case REFLECT_CLASS_NEW_INSTANCE: {
                String fqn = classOf(graph.recvOrigin(edgeIndex), ctx, 0);
                int ctor = (fqn == null) ? -1 : methods.idOf(fqn + "#<init>()");
                return (ctor < 0) ? null : Resolution.single(ctor, Resolution.REFLECTION);
            }
            case REFLECT_CTOR_NEW_INSTANCE: {
                String recv = graph.recvOrigin(edgeIndex);
                if (Origin.kindOf(recv) != Origin.RETURN) {
                    return null;
                }
                String v = Origin.valueOf(recv);
                if (!CLASS_GET_CTOR.equals(v) && !CLASS_GET_DECLARED_CTOR.equals(v)) {
                    return null;
                }
                String fqn = classOf(Origin.receiverOf(recv), ctx, 0);
                String params = paramTypesOf(recv, 0, ctx);
                if (fqn == null || params == null) {
                    return null;
                }
                int ctor = methods.idOf(fqn + "#<init>(" + params + ")");
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
     */
    private int[] invokeTargets(String recv, String invokeArgs, DataflowContext ctx) {
        if (Origin.kindOf(recv) != Origin.RETURN) {
            return null;
        }
        String v = Origin.valueOf(recv);
        if (!CLASS_GET_METHOD.equals(v) && !CLASS_GET_DECLARED_METHOD.equals(v)) {
            return null;
        }
        String name = literalOf(Origin.argAt(Origin.argsOf(recv), 0), ctx, 0);
        String owner = classOf(Origin.receiverOf(recv), ctx, 0);
        if (name == null || owner == null) {
            return null;
        }
        String recvType = concreteTypeOf(Origin.argAt(invokeArgs, 0), ctx);
        String lookup = (recvType != null && graph.hierarchy.isSubtypeOf(recvType, owner)) ? recvType : owner;

        String params = paramTypesOf(recv, 1, ctx);
        if (params != null) {
            int id = graph.implementationIn(lookup, name + "(" + params + ")");
            if (id < 0 && !lookup.equals(owner)) {
                id = graph.implementationIn(owner, name + "(" + params + ")");
            }
            return (id < 0) ? null : new int[] {id};
        }
        IntArray ids = methodsNamed(lookup, name);
        if (ids.isEmpty() && !lookup.equals(owner)) {
            ids = methodsNamed(owner, name);
        }
        return ids.isEmpty() ? null : ids.toArray();
    }

    /**
     * getMethod / getConstructor の引数のクラスリテラルから、引数型のカンマ区切りを作る。
     * 引数の数は n= で分かるので、出所の無い引数（変数など）があれば「不明」として null を返す
     */
    private String paramTypesOf(String origin, int from, DataflowContext ctx) {
        int count = Origin.argCountOf(origin);
        if (count < 0) {
            return null;
        }
        String args = Origin.argsOf(origin);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < count; i++) {
            String t = classOf(Origin.argAt(args, i), ctx, 0);
            if (t == null) {
                return null;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(t);
        }
        return sb.toString();
    }

    /** 出所が表すクラス（Class オブジェクト）のFQN。分からなければ null */
    private String classOf(String origin, DataflowContext ctx, int depth) {
        if (origin == null || depth > maxDepth) {
            return null;
        }
        switch (Origin.kindOf(origin)) {
            case Origin.CLASS:
                return Origin.valueOf(origin);
            case Origin.LITERAL:
                return classNameOf(origin, ctx);
            case Origin.PARAM: {
                String v = paramValueOf(origin, ctx);
                return (v == null) ? null : classOf(v, null, depth + 1);
            }
            case Origin.RETURN: {
                String v = Origin.valueOf(origin);
                if (v.startsWith(CLASS_FOR_NAME_PREFIX)) {
                    return classNameOf(Origin.argAt(Origin.argsOf(origin), 0), ctx);
                }
                if (OBJECT_GET_CLASS.equals(v)) {
                    return concreteTypeOf(Origin.receiverOf(origin), ctx);
                }
                // ソース上のメソッドが Class を返す形。全ての return が同じクラスなら決まる
                String[] returns = graph.returnOriginsOf(methods.idOf(v));
                if (returns == null) {
                    return null;
                }
                String found = null;
                for (String o : returns) {
                    String c = classOf(o, null, depth + 1);
                    if (c == null || (found != null && !found.equals(c))) {
                        return null;
                    }
                    found = c;
                }
                return found;
            }
            default:
                return null;
        }
    }

    /** クラス名の文字列（リテラル・定数・経路上の引数）が解析対象の型を指していればそのFQN */
    private String classNameOf(String origin, DataflowContext ctx) {
        String name = literalOf(origin, ctx, 0);
        // 解析対象に存在しない型名は使わない（文字列の見た目だけで決めない）
        return (name != null && graph.hierarchy.contains(name)) ? name : null;
    }

    /** 出所が表す文字列の値（リテラル・定数・経路上の引数）。分からなければ null */
    private String literalOf(String origin, DataflowContext ctx, int depth) {
        if (origin == null || depth > maxDepth) {
            return null;
        }
        switch (Origin.kindOf(origin)) {
            case Origin.LITERAL:
                return Origin.valueOf(origin);
            case Origin.PARAM: {
                String v = paramValueOf(origin, ctx);
                return (v == null) ? null : literalOf(v, null, depth + 1);
            }
            case Origin.RETURN: {
                String[] returns = graph.returnOriginsOf(methods.idOf(Origin.valueOf(origin)));
                if (returns == null) {
                    return null;
                }
                String found = null;
                for (String o : returns) {
                    String s = literalOf(o, null, depth + 1);
                    if (s == null || (found != null && !found.equals(s))) {
                        return null;
                    }
                    found = s;
                }
                return found;
            }
            default:
                return null;
        }
    }

    /**
     * 経路上で分かっている引数の「値」（L:文字列 / K:クラス）。
     * DataflowContext.paramTypes には具象型（FQN）のほか、型ではなく値が渡っている引数には
     * その出所（':' を含む）が入っている（{@link #valueOriginOf} 参照）
     */
    private static String paramValueOf(String paramOrigin, DataflowContext ctx) {
        if (ctx == null || ctx.paramTypes() == null) {
            return null;
        }
        int idx = parseIndex(Origin.valueOf(paramOrigin));
        if (idx < 0 || idx >= ctx.paramTypes().length) {
            return null;
        }
        String v = ctx.paramTypes()[idx];
        return (v != null && v.indexOf(':') >= 0) ? v : null;
    }

    /** 経路の引数環境に入れる「値」。具象型が決まらない引数でも、リテラルやクラスなら値として渡す */
    public String valueOriginOf(String origin, DataflowContext ctx) {
        switch (Origin.kindOf(origin)) {
            case Origin.LITERAL:
            case Origin.CLASS:
                return Origin.head(origin);
            case Origin.PARAM:
                return paramValueOf(origin, ctx);
            case Origin.RETURN: {
                String c = classOf(origin, ctx, 0);
                if (c != null) {
                    return Origin.of(Origin.CLASS, c);
                }
                String s = literalOf(origin, ctx, 0);
                return (s == null) ? null : Origin.of(Origin.LITERAL, s);
            }
            default:
                return null;
        }
    }

    /** 型（と親型）の中で、その名前を持つ本体付きメソッド。最初に見つかった型のものだけ */
    private IntArray methodsNamed(String typeFqn, String name) {
        if (methodsByName == null) {
            methodsByName = new HashMap<>();
            for (int id = 0; id < methods.size(); id++) {
                if (!methods.hasBody(id) || !methods.hasSource(id)) {
                    continue;
                }
                String k = methods.typeFqn(id) + "#" + methods.methodName(id);
                methodsByName.computeIfAbsent(k, key -> new IntArray(2)).add(id);
            }
        }
        ArrayDeque<String> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(typeFqn);
        seen.add(typeFqn);
        while (!queue.isEmpty()) {
            String t = queue.poll();
            IntArray ids = methodsByName.get(t + "#" + name);
            if (ids != null && !ids.isEmpty()) {
                return ids;
            }
            for (String s : graph.hierarchy.directSupertypes(t)) {
                if (seen.add(s)) {
                    queue.add(s);
                }
            }
        }
        return new IntArray(1);
    }
}
