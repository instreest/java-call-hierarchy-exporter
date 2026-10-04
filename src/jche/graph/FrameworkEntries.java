// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jche.cache.AnnotationTokens;
import jche.cache.ModifierTokens;

/**
 * フレームワークが起点として呼ぶメソッドのライブラリ呼び出し規則（Issue #136 の種類 B）。
 *
 * {@code @Scheduled} のメソッドや {@code HttpServlet#doGet} の上書きは、ソースのどこからも
 * 呼ばれていないが、フレームワークが呼ぶ入口である。呼び出し箇所が無いので辺は張れないが、
 * 「これは入口だ」という規則は書ける。規則に当たるメソッドは methods.csv の role を
 * {@code FRAMEWORK_ENTRY} にし、全体モードの起点に加える。
 * 呼び出し箇所から戻ってくる形（種類 A）は {@link CallbackRules}。
 *
 * <h2>規則の1行</h2>
 * <pre>
 *   &#64;org.springframework.scheduling.annotation.Scheduled      … そのアノテーションが付いたメソッド
 *   super javax.servlet.http.HttpServlet#doGet(...)                … その型を継承（実装）した型の、同じシグネチャのメソッド
 *   static main(java.lang.String[])                                 … public static でそのシグネチャのメソッド
 *   main                                                            … 起動の入口になる main メソッド（JLS 12.1.4）
 * </pre>
 * {@code main} は JLS 12.1.4 の起動メソッドの条件をそのまま表す。名前が {@code main} で、引数が
 * {@code String[]} 1 つか無し、private でないもの。static でもインスタンスメソッドでもよい
 * （Java 25 で確定したインスタンスの main メソッド。コンパクトなコンパイル単位の {@code void main()} が典型）。
 * 起動器は 1 つのクラスで {@code main(String[])} を {@code main()} より優先するが、両方を入口にする
 * （どちらが選ばれるかは、そのクラスを起動したときに決まるため）。JLS 12.1.4 は戻り値が void であることも
 * 求めるが、D 行は戻り値の型を持たないので見ない（void でない main も入口にする。多すぎる側）。
 * インスタンスの main のために起動器が使う引数なしのコンストラクタがあるかまでは見ない。
 * {@code super} の型は jar の中でよい（H 行の親型に jar の型の名前も入っている）。
 * {@code super} の「同じシグネチャ」は、型引数を具体化してシグネチャの食い違う上書き（O 行）と、親クラスから継承した
 * メソッドによる実装（H 行の 8 列目）も含む（{@link #overridesWithBridge}。Issue #188）。
 */
public final class FrameworkEntries {

    /** 規則の1行。{@code row} はライブラリ呼び出し規則の何行目か（{@link RuleUsage} の添字） */
    record Rule(char kind, String value, String sig, String text, int row) {
        static final char ANNOTATION = '@';
        static final char SUPER = 's';
        static final char STATIC = 'm';
        static final char MAIN = 'L';
    }

    /** JLS 12.1.4 の起動メソッドのシグネチャ（引数が String[] か、無し） */
    private static final List<String> LAUNCH_SIGNATURES = List.of("main(java.lang.String[])", "main()");

    private final List<Rule> rules = new ArrayList<>();
    private final CallGraph graph;
    private final MethodTable methods;
    private final RuleUsage usage;
    /** メソッドIDごとの判定のメモ（null = 未判定、"" = 入口でない） */
    private String[] memo;

    public FrameworkEntries(CallGraph graph, RuleUsage usage) {
        this.graph = graph;
        this.methods = graph.methods;
        this.usage = usage;
        List<RuleUsage.Line> lines = usage.lines();
        for (int row = 0; row < lines.size(); row++) {
            Rule c = parse(lines.get(row).text(), row);
            if (c != null) {
                rules.add(c);
            }
        }
    }

    /** 同梱の規則だけを持つ表 */
    public static FrameworkEntries bundled(CallGraph graph) {
        return new FrameworkEntries(graph, RuleUsage.ofBundled(BundledFrameworkEntries.LINES));
    }

    /** 行ごとの利用状況（どの規則が効いたか） */
    public RuleUsage usage() {
        return usage;
    }

    /** 1行を読む。空行と {@code #} で始まる行は無視。形が違えば null */
    static Rule parse(String line) {
        return parse(line, RuleUsage.NO_ROW);
    }

    /** @param row ライブラリ呼び出し規則の何行目か（利用状況の記録用） */
    private static Rule parse(String line, int row) {
        String s = (line == null) ? "" : line.trim();
        if (s.isEmpty() || s.startsWith("#")) {
            return null;
        }
        if (s.startsWith("@")) {
            String fqn = s.substring(1).trim();
            return fqn.isEmpty() ? null : new Rule(Rule.ANNOTATION, fqn, "", s, row);
        }
        if ("main".equals(s)) {
            return new Rule(Rule.MAIN, "", "", s, row);
        }
        int sp = s.indexOf(' ');
        if (sp < 0) {
            return null;
        }
        String head = s.substring(0, sp);
        String rest = s.substring(sp + 1).trim();
        if ("super".equals(head)) {
            int hash = rest.indexOf('#');
            if (hash <= 0 || hash == rest.length() - 1) {
                return null;
            }
            return new Rule(Rule.SUPER, rest.substring(0, hash), rest.substring(hash + 1),
                    s, row);
        }
        if ("static".equals(head)) {
            return rest.isEmpty() ? null : new Rule(Rule.STATIC, "", rest, s, row);
        }
        return null;
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    /** そのメソッドがフレームワークの入口か */
    public boolean isEntry(int methodId) {
        return !describe(methodId).isEmpty();
    }

    /** 当たった規則の文言（ログ・注記用）。入口でなければ空文字列 */
    public String describe(int methodId) {
        if (memo == null) {
            memo = new String[methods.size()];
        }
        if (methodId < 0 || methodId >= memo.length) {
            return "";
        }
        String known = memo[methodId];
        if (known == null) {
            known = judge(methodId);
            memo[methodId] = known;
        }
        return known;
    }

    private String judge(int id) {
        if (rules.isEmpty() || !methods.hasSource(id) || !methods.hasBody(id)) {
            return "";
        }
        String sig = methods.signature(id);
        String annotations = methods.annotations(id);
        String mods = methods.mods(id);
        Set<String> supers = null;
        for (Rule c : rules) {
            switch (c.kind()) {
                case Rule.ANNOTATION -> {
                    if (AnnotationTokens.has(annotations, c.value())) {
                        usage.markApplied(c.row());
                        return "@" + simpleName(c.value());
                    }
                }
                case Rule.STATIC -> {
                    if (sig.equals(c.sig()) && ModifierTokens.has(mods, "static")
                            && ModifierTokens.has(mods, "public")) {
                        usage.markApplied(c.row());
                        return "static " + c.sig();
                    }
                }
                case Rule.MAIN -> {
                    if (LAUNCH_SIGNATURES.contains(sig) && !ModifierTokens.has(mods, "private")) {
                        usage.markApplied(c.row());
                        // 従来の public static main(String[]) と同じ文言になるよう static を前に付ける
                        return ModifierTokens.has(mods, "static") ? "static " + sig : sig;
                    }
                }
                case Rule.SUPER -> {
                    boolean hit;
                    if (sig.equals(c.sig())) {
                        if (supers == null) {
                            supers = transitiveSupertypes(methods.typeFqn(id));
                        }
                        hit = supers.contains(c.value());
                    } else {
                        hit = overridesWithBridge(id, c.value() + "#" + c.sig());
                    }
                    if (hit) {
                        usage.markApplied(c.row());
                        return simpleName(c.value()) + "#" + c.sig();
                    }
                }
                default -> {
                }
            }
        }
        return "";
    }

    /**
     * シグネチャの違うメソッド {@code id} が、規則の宣言 {@code ruleKey}（{@code 型FQN#name(paramSig)}）を
     * 型引数を具体化して上書き・実装しているか（javac がブリッジメソッドでディスクリプタをそろえる形）。
     *
     * <p>{@code class MyHandler implements Handler<Req> { void handle(Req r) }} の {@code handle(Req)} は、規則
     * {@code super Handler#handle(java.lang.Object)} のシグネチャと文字列では一致しないが、フレームワークが
     * {@code Handler#handle(Object)} を呼べば（ブリッジを経て）動く入口である（Issue #188）。判定は選択と同じ 2 つの材料
     * （{@link MethodSelection} の「上書きできる宣言」）で、自前で名前や引数型を比べない:
     * <ul>
     *   <li>O 行（{@link OverrideIndex#overridersOf}。書き手が {@code IMethodBinding.overrides} で判定した、型引数を具体化した
     *       上書き。親型が jar の型でも書かれている）</li>
     *   <li>H 行の 8 列目（{@link TypeHierarchy#inheritedImplementations}。親クラスから継承したメソッドが、型引数を置き換えた
     *       親インターフェースのメソッドを実装する組。{@code class MyHandler extends BaseHandler implements Handler<Req>} で
     *       {@code BaseHandler#handle(Req)} が入口になる形。その部分型から見たときだけの関係なので、宣言した型の部分型を見る）</li>
     * </ul>
     * 規則の宣言のメソッド ID から {@link MethodSelection#overridingImplementations} で引く案は、jar の型の宣言
     * （{@code HttpServlet#doGet}）はソースのどこかが呼び出し先にしていない限り表に無いので使えない
     */
    private boolean overridesWithBridge(int id, String ruleKey) {
        IntArray overriders = graph.overrides.overridersOf(ruleKey);
        if (overriders != null) {
            for (int i = 0; i < overriders.size(); i++) {
                if (overriders.get(i) == id) {
                    return true;
                }
            }
        }
        String pair = ruleKey + ">" + methods.key(id);
        for (String sub : graph.hierarchy.transitiveSubtypes(methods.typeFqn(id))) {
            if (graph.hierarchy.inheritedImplementations(sub).contains(pair)) {
                return true;
            }
        }
        return false;
    }

    /** 親型を全部集める。jar の型の名前も含む（H 行に入っている） */
    private Set<String> transitiveSupertypes(String type) {
        Set<String> seen = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(type);
        while (!queue.isEmpty()) {
            for (String sup : graph.hierarchy.directSupertypes(queue.poll())) {
                if (seen.add(sup)) {
                    queue.add(sup);
                }
            }
        }
        return seen;
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return (dot < 0) ? fqn : fqn.substring(dot + 1);
    }
}
