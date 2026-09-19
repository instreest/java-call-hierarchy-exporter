// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jche.cache.Origin;

/**
 * ソースの外（JDK・フレームワーク）を経由して自分のコードへ戻ってくる呼び出しの契約表。
 *
 * {@code new Thread(task).start()} の {@code start} は jar の中なので辿れないが、
 * 「{@code Thread#start()} はコンストラクタに渡した {@code Runnable} の {@code run()} を呼ぶ」
 * という契約は文章で書ける。契約表を持てば、jar の中を読まずに辺を張れる
 * （Issue #136。docs/callback-contracts-qa.md）。
 *
 * <h2>契約の1行</h2>
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
public final class CallbackContracts {

    /** 契約の1行。{@code row} は契約表の何行目か（{@link ContractUsage} の添字） */
    record Contract(String calleeKey, char where, int index, String callbackSig, String text, int row) {
        static final char RECEIVER = 'r';
        static final char ARGUMENT = 'a';
        static final char CTOR_ARGUMENT = 'c';
        static final int ANY = -1;
    }

    private final Map<String, List<Contract>> byCallee = new HashMap<>();
    private final CallGraph graph;
    private final MethodTable methods;
    private final DataflowResolver dataflow;
    private final ContractUsage usage;

    public CallbackContracts(CallGraph graph, DataflowResolver dataflow, ContractUsage usage) {
        this.graph = graph;
        this.methods = graph.methods;
        this.dataflow = dataflow;
        this.usage = usage;
        List<ContractUsage.Line> lines = usage.lines();
        for (int row = 0; row < lines.size(); row++) {
            Contract c = parse(lines.get(row).text(), row);
            if (c != null) {
                byCallee.computeIfAbsent(c.calleeKey(), k -> new ArrayList<>()).add(c);
            }
        }
    }

    /** 同梱の JDK の契約だけを持つ表 */
    public static CallbackContracts jdk(CallGraph graph, DataflowResolver dataflow) {
        return new CallbackContracts(graph, dataflow, ContractUsage.ofBundled(JdkCallbacks.LINES));
    }

    /** 行ごとの利用状況（どの契約が効いたか） */
    public ContractUsage usage() {
        return usage;
    }

    /**
     * 1行を読む。空行と {@code #} で始まる行は無視。形が違えば null（黙って捨てず、呼び出し側が
     * ログに出せるよう null を返す）
     */
    static Contract parse(String line) {
        return parse(line, ContractUsage.NO_ROW);
    }

    /** @param row 契約表の何行目か（利用状況の記録用） */
    private static Contract parse(String line, int row) {
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
        if (where == Contract.RECEIVER) {
            index = 0;
        } else if (where == Contract.ARGUMENT || where == Contract.CTOR_ARGUMENT) {
            String n = pos.substring(1);
            if ("*".equals(n)) {
                index = Contract.ANY;
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
        return new Contract(callee, where, index, sig, s, row);
    }

    public boolean isEmpty() {
        return byCallee.isEmpty();
    }

    /** その呼び出し先に契約があるか（辺ごとの判定の前に軽く弾くため） */
    public boolean hasContract(int calleeId) {
        return !byCallee.isEmpty() && byCallee.containsKey(methods.key(calleeId));
    }

    /** 契約で繋がった1件: 呼び戻されるメソッドと、当たった契約の文言（注記用） */
    public record Match(int target, String contract) {
    }

    /**
     * その辺の契約で呼び戻されるメソッド。無ければ空。
     *
     * @param ctx この経路で分かっていること（引数で渡ってきた値など）。無ければ null。
     *            null でも、{@code new} した型やラムダのように経路に依らず決まるものは返す
     */
    public List<Match> matchesOf(int edgeIndex, DataflowContext ctx) {
        List<Contract> contracts = byCallee.get(methods.key(graph.calleeOf(edgeIndex)));
        if (contracts == null) {
            return List.of();
        }
        List<Match> found = new ArrayList<>();
        for (Contract c : contracts) {
            // 呼び出し先には一致した。繋がらなくても「表の綴りは合っている」と言えるので分けて数える
            usage.markReached(c.row());
            for (String origin : valuesAt(edgeIndex, c)) {
                int id = callbackTargetOf(origin, c.callbackSig(), ctx);
                if (id >= 0 && methods.hasSource(id) && found.stream().noneMatch(m -> m.target() == id)) {
                    usage.markApplied(c.row());
                    found.add(new Match(id, shortKey(c.calleeKey()) + " が " + c.callbackSig() + " を呼ぶ"));
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

    /** 契約の位置に当たる値の出所（複数のこともある） */
    private List<String> valuesAt(int edgeIndex, Contract c) {
        List<String> values = new ArrayList<>();
        String recv = graph.recvOrigin(edgeIndex);
        switch (c.where()) {
            case Contract.RECEIVER -> {
                if (recv != null) {
                    values.add(recv);
                }
            }
            case Contract.ARGUMENT -> collectArgs(graph.argOrigins(edgeIndex), c.index(), values);
            case Contract.CTOR_ARGUMENT -> {
                // レシーバが new X(...) の形のときだけ、その実引数が分かる
                if (recv != null && Origin.kindOf(recv) == Origin.NEW) {
                    collectArgs(Origin.argsOf(recv), c.index(), values);
                }
            }
            default -> {
            }
        }
        return values;
    }

    private static void collectArgs(String args, int index, List<String> into) {
        if (args == null || args.isEmpty()) {
            return;
        }
        if (index != Contract.ANY) {
            String one = Origin.argAt(args, index);
            if (one != null) {
                into.add(Origin.unnest(one));
            }
            return;
        }
        for (String entry : Origin.entriesOf(args)) {
            int eq = entry.indexOf('=');
            if (eq <= 0 || !Character.isDigit(entry.charAt(0))) {
                continue;   // n= / r= は実引数ではない
            }
            into.add(Origin.unnest(entry.substring(eq + 1)));
        }
    }

    /**
     * 値の出所から、呼び戻されるメソッドを決める。
     * ラムダ／メソッド参照ならその本体、型が決まるならその型の実装
     */
    private int callbackTargetOf(String origin, String callbackSig, DataflowContext ctx) {
        int functional = dataflow.functionalTargetOf(origin, ctx);
        if (functional >= 0) {
            return functional;
        }
        String fqn = dataflow.concreteTypeOf(origin, ctx);
        return (fqn == null) ? -1 : graph.implementationIn(fqn, callbackSig);
    }
}
