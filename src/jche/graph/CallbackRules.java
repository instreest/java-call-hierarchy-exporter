// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jche.cache.Origin;

/**
 * ソースの外（JDK・フレームワーク）を経由して自分のコードへ戻ってくる呼び出しのライブラリ呼び出し規則。
 *
 * {@code new Thread(task).start()} の {@code start} は jar の中なので辿れないが、
 * 「{@code Thread#start()} はコンストラクタに渡した {@code Runnable} の {@code run()} を呼ぶ」
 * という規則は文章で書ける。ライブラリ呼び出し規則を持てば、jar の中を読まずに辺を張れる
 * （Issue #136。docs/library-call-rules-qa.md）。
 *
 * <h2>規則の1行</h2>
 * <pre>
 *   呼び出し先のメソッドキー -> 位置 : 呼ばれるメソッドのシグネチャ
 *   java.lang.Thread#start() -> c* : run()
 *   java.lang.Iterable#forEach(java.util.function.Consumer) -> a0 : accept(java.lang.Object)
 * </pre>
 * 位置は、呼び出し箇所で渡した値のどれが呼び戻されるか:
 * <ul>
 *   <li>{@code r} … レシーバそのもの（{@code Thread} のサブクラスが {@code run} を上書きする形）</li>
 *   <li>{@code aN} … N 番目（0始まり）の実引数。{@code a*} なら全部</li>
 *   <li>{@code cN} … レシーバを {@code new} したときの N 番目の実引数。{@code c*} なら全部</li>
 * </ul>
 * 値の具象型は #127 のデータフロー（{@link DataflowResolver}）で決める。ラムダ／メソッド参照
 * （{@link Origin#FUNCTIONAL}）ならその本体、{@code new} した型ならその型の実装。
 * 分からなければ辺は張らない（jar の型の全実装を並べるような広い候補は出さない）。
 */
public final class CallbackRules {

    /** 規則の1行。{@code row} はライブラリ呼び出し規則の何行目か（{@link RuleUsage} の添字） */
    record Rule(String calleeKey, char where, int index, String callbackSig, String text, int row) {
        static final char RECEIVER = 'r';
        static final char ARGUMENT = 'a';
        static final char CTOR_ARGUMENT = 'c';
        static final int ANY = -1;
    }

    private final Map<String, List<Rule>> byCallee = new HashMap<>();
    private final CallGraph graph;
    private final MethodTable methods;
    private final ValueStore values;
    private final DataflowResolver dataflow;
    private final RuleUsage usage;

    public CallbackRules(CallGraph graph, DataflowResolver dataflow, RuleUsage usage) {
        this.graph = graph;
        this.methods = graph.methods;
        this.values = graph.values();
        this.dataflow = dataflow;
        this.usage = usage;
        List<RuleUsage.Line> lines = usage.lines();
        for (int row = 0; row < lines.size(); row++) {
            Rule c = parse(lines.get(row).text(), row);
            if (c != null) {
                byCallee.computeIfAbsent(c.calleeKey(), k -> new ArrayList<>()).add(c);
            }
        }
    }

    /** 同梱の JDK の規則だけを持つ表 */
    public static CallbackRules jdk(CallGraph graph, DataflowResolver dataflow) {
        return new CallbackRules(graph, dataflow, RuleUsage.ofBundled(JdkCallbacks.LINES));
    }

    /** 行ごとの利用状況（どの規則が効いたか） */
    public RuleUsage usage() {
        return usage;
    }

    /**
     * 1行を読む。空行と {@code #} で始まる行は無視。形が違えば null（黙って捨てず、呼び出し側が
     * ログに出せるよう null を返す）
     */
    static Rule parse(String line) {
        return parse(line, RuleUsage.NO_ROW);
    }

    /** @param row ライブラリ呼び出し規則の何行目か（利用状況の記録用） */
    private static Rule parse(String line, int row) {
        String s = (line == null) ? "" : line.trim();
        if (s.isEmpty() || s.startsWith("#")) {
            return null;
        }
        int arrow = s.indexOf("->");
        int colon = (arrow < 0) ? -1 : s.indexOf(':', arrow);
        if (arrow < 0 || colon < 0) {
            return null;
        }
        String callee = s.substring(0, arrow).trim();
        String pos = s.substring(arrow + 2, colon).trim();
        String sig = s.substring(colon + 1).trim();
        if (callee.isEmpty() || pos.isEmpty() || sig.isEmpty()) {
            return null;
        }
        char where = pos.charAt(0);
        int index;
        if (where == Rule.RECEIVER) {
            index = 0;
        } else if (where == Rule.ARGUMENT || where == Rule.CTOR_ARGUMENT) {
            String n = pos.substring(1);
            if ("*".equals(n)) {
                index = Rule.ANY;
            } else {
                try {
                    index = Integer.parseInt(n);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        } else {
            return null;
        }
        return new Rule(callee, where, index, sig, s, row);
    }

    public boolean isEmpty() {
        return byCallee.isEmpty();
    }

    /** その呼び出し先に規則があるか（辺ごとの判定の前に軽く弾くため） */
    public boolean hasRule(int calleeId) {
        return !byCallee.isEmpty() && byCallee.containsKey(methods.key(calleeId));
    }

    /**
     * 規則で繋がった1件: 呼び戻されるメソッドと、当たった規則の文言（注記用）。
     *
     * @param candidates 同じ値から引いた候補の数。1 なら確定。2 以上は、渡した値が
     *                   上書き可能なメソッドへのメソッド参照で、実際に動く実装を1つに
     *                   決められなかった（上書き候補を全部並べた）ことを表す
     */
    public record Match(int target, String rule, int candidates) {
        public boolean isMultiple() {
            return candidates > 1;
        }
    }

    /**
     * ラムダ／メソッド参照が渡された値の解決。{@link CallResolver} の
     * 関数型インターフェース経由の呼び出しと同じ判断を使う（決め方を2か所に持たないため）。
     */
    @FunctionalInterface
    public interface FunctionalLookup {
        /**
         * 値がラムダ／メソッド参照でなければ null
         *
         * @param ref 渡された値（値の表の参照）
         */
        Resolution resolve(int ref, DataflowContext ctx);
    }

    /**
     * その辺の規則で呼び戻されるメソッド。無ければ空。
     *
     * @param ctx        この経路で分かっていること（引数で渡ってきた値など）。無ければ null。
     *                   null でも、{@code new} した型やラムダのように経路に依らず決まるものは返す
     * @param functional ラムダ／メソッド参照の解決（{@link CallResolver} から渡す）
     */
    public List<Match> matchesOf(int edgeIndex, DataflowContext ctx, FunctionalLookup functional) {
        List<Rule> rules = byCallee.get(methods.key(graph.calleeOf(edgeIndex)));
        if (rules == null) {
            return List.of();
        }
        List<Match> found = new ArrayList<>();
        for (Rule c : rules) {
            // 呼び出し先には一致した。繋がらなくても「表の綴りは合っている」と言えるので分けて数える
            usage.markReached(c.row());
            String text = shortKey(c.calleeKey()) + " calls " + c.callbackSig();
            IntArray passed = valuesAt(edgeIndex, c);
            for (int i = 0; i < passed.size(); i++) {
                int[] ids = callbackTargetsOf(passed.get(i), c.callbackSig(), ctx, functional);
                for (int id : ids) {
                    if (id >= 0 && methods.hasSource(id) && found.stream().noneMatch(m -> m.target() == id)) {
                        usage.markApplied(c.row());
                        found.add(new Match(id, text, ids.length));
                    }
                }
            }
        }
        return found;
    }

    /** "java.lang.Thread#start()" -> "Thread#start()" */
    private static String shortKey(String key) {
        int hash = key.indexOf('#');
        String type = (hash < 0) ? key : key.substring(0, hash);
        int dot = type.lastIndexOf('.');
        return (dot < 0) ? key : key.substring(dot + 1);
    }

    /** 規則の位置に当たる値（値の表の参照。複数のこともある） */
    private IntArray valuesAt(int edgeIndex, Rule c) {
        IntArray found = new IntArray(2);
        int recv = graph.recvNode(edgeIndex);
        switch (c.where()) {
            case Rule.RECEIVER -> {
                if (recv != ValueStore.NONE) {
                    found.add(recv);
                }
            }
            case Rule.ARGUMENT -> collectArgs(graph.argsNode(edgeIndex), c.index(), found);
            case Rule.CTOR_ARGUMENT -> {
                // レシーバが new X(...) の形のときだけ、その実引数が分かる
                if (values.kind(recv) == Origin.NEW) {
                    collectArgs(recv, c.index(), found);
                }
            }
            default -> {
            }
        }
        return found;
    }

    /**
     * 実引数を持つ値（実引数の並び・new）から、その位置の実引数（{@link Rule#ANY} なら全部を
     * 書かれた順に）を足す
     */
    private void collectArgs(int holder, int index, IntArray into) {
        if (index != Rule.ANY) {
            int one = values.argAt(holder, index);
            if (one != ValueStore.NONE) {
                into.add(one);
            }
            return;
        }
        for (int k = values.argBegin(holder), end = values.argEnd(holder); k < end; k++) {
            into.add(values.argRef(k));
        }
    }

    /**
     * 値から、呼び戻されるメソッドを決める。決まらなければ空。
     *
     * ラムダならその本体。メソッド参照は参照先が上書き可能なメソッドなら、実際に動くのは
     * レシーバの実行時クラスの実装（JLS 15.13.3）なので、通常の呼び出しと同じく
     * 束縛したレシーバの具象型か上書き候補から引く（候補が複数なら全部返す。ただし参照先の
     * 宣言が jar の中なら、その全実装になるので返さない）。
     * 参照先の宣言をそのまま返すと、上書きした実装や、抽象メソッドの先の実装が落ちる
     * （docs/lambda-expansion-qa.md の Q16）。型が決まる値ならその型の実装
     */
    private int[] callbackTargetsOf(int ref, String callbackSig, DataflowContext ctx,
                                    FunctionalLookup functional) {
        Resolution viaFunctional = functional.resolve(ref, ctx);
        if (viaFunctional != null) {
            if (viaFunctional.isMultiple() && !declaredInSource(ref, ctx)) {
                // 参照先の宣言が jar の中（list.forEach(Runnable::run) の Runnable#run）なら、
                // その全実装を並べることになる。jar の型の全実装のような広い候補は出さない
                // （docs/library-call-rules.md の「追える条件」）
                return new int[0];
            }
            return viaFunctional.targets();
        }
        String fqn = dataflow.concreteTypeOf(ref, ctx);
        // 規則は呼び戻されるメソッドの「シグネチャ」だけを書く（それを宣言している型は
        // 規則のどこにも現れない。例: Thread#start() -> c* : run() の Runnable）ので、
        // 上書きの引きもシグネチャで行う。キーの照合だけで引くと、型引数を具体化した実装
        // （class OrderPrinter implements Consumer<Order> の accept(Order)）が
        // 消去済みの規則（accept(java.lang.Object)）と一致せず、辺が静かに落ちる
        int id = (fqn == null) ? -1 : graph.selection().implementationOfSignature(fqn, callbackSig);
        return (id < 0) ? new int[0] : new int[] {id};
    }

    /** 渡した値がメソッド参照で、その参照先（コンパイル時宣言）がソースにあるか */
    private boolean declaredInSource(int ref, DataflowContext ctx) {
        int functional = dataflow.functionalRefOf(ref, ctx);
        int declared = (functional == ValueStore.NONE) ? -1 : values.methodId(functional);
        return declared >= 0 && methods.hasSource(declared);
    }
}
