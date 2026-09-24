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
 * フレームワークが起点として呼ぶメソッドの契約表（Issue #136 の種類 B）。
 *
 * {@code @Scheduled} のメソッドや {@code HttpServlet#doGet} の上書きは、ソースのどこからも
 * 呼ばれていないが、フレームワークが呼ぶ入口である。呼び出し箇所が無いので辺は張れないが、
 * 「これは入口だ」という契約は書ける。契約に当たるメソッドは methods.csv の role を
 * {@code FRAMEWORK_ENTRY} にし、全体モードの起点に加える。
 * 呼び出し箇所から戻ってくる形（種類 A）は {@link CallbackContracts}。
 *
 * <h2>契約の1行</h2>
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
 * （どちらが選ばれるかは、そのクラスを起動したときに決まるため）。インスタンスの main のために
 * 起動器が使う引数なしのコンストラクタがあるかまでは見ない。
 * {@code super} の型は jar の中でよい（H 行の親型に jar の型の名前も入っている）。
 */
public final class FrameworkEntries {

    /** 契約の1行。{@code row} は契約表の何行目か（{@link ContractUsage} の添字） */
    record Contract(char kind, String value, String sig, String text, int row) {
        static final char ANNOTATION = '@';
        static final char SUPER = 's';
        static final char STATIC = 'm';
        static final char MAIN = 'L';
    }

    /** JLS 12.1.4 の起動メソッドのシグネチャ（引数が String[] か、無し） */
    private static final List<String> LAUNCH_SIGNATURES = List.of("main(java.lang.String[])", "main()");

    private final List<Contract> contracts = new ArrayList<>();
    private final CallGraph graph;
    private final MethodTable methods;
    private final ContractUsage usage;
    /** メソッドIDごとの判定のメモ（null = 未判定、"" = 入口でない） */
    private String[] memo;

    public FrameworkEntries(CallGraph graph, ContractUsage usage) {
        this.graph = graph;
        this.methods = graph.methods;
        this.usage = usage;
        List<ContractUsage.Line> lines = usage.lines();
        for (int row = 0; row < lines.size(); row++) {
            Contract c = parse(lines.get(row).text(), row);
            if (c != null) {
                contracts.add(c);
            }
        }
    }

    /** 同梱の契約だけを持つ表 */
    public static FrameworkEntries bundled(CallGraph graph) {
        return new FrameworkEntries(graph, ContractUsage.ofBundled(BundledFrameworkEntries.LINES));
    }

    /** 行ごとの利用状況（どの契約が効いたか） */
    public ContractUsage usage() {
        return usage;
    }

    /** 1行を読む。空行と {@code #} で始まる行は無視。形が違えば null */
    static Contract parse(String line) {
        return parse(line, ContractUsage.NO_ROW);
    }

    /** @param row 契約表の何行目か（利用状況の記録用） */
    private static Contract parse(String line, int row) {
        String s = (line == null) ? "" : line.trim();
        if (s.isEmpty() || s.startsWith("#")) {
            return null;
        }
        if (s.startsWith("@")) {
            String fqn = s.substring(1).trim();
            return fqn.isEmpty() ? null : new Contract(Contract.ANNOTATION, fqn, "", s, row);
        }
        if ("main".equals(s)) {
            return new Contract(Contract.MAIN, "", "", s, row);
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
            return new Contract(Contract.SUPER, rest.substring(0, hash), rest.substring(hash + 1),
                    s, row);
        }
        if ("static".equals(head)) {
            return rest.isEmpty() ? null : new Contract(Contract.STATIC, "", rest, s, row);
        }
        return null;
    }

    public boolean isEmpty() {
        return contracts.isEmpty();
    }

    /** そのメソッドがフレームワークの入口か */
    public boolean isEntry(int methodId) {
        return !describe(methodId).isEmpty();
    }

    /** 当たった契約の文言（ログ・注記用）。入口でなければ空文字列 */
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
        if (contracts.isEmpty() || !methods.hasSource(id) || !methods.hasBody(id)) {
            return "";
        }
        String sig = methods.signature(id);
        String annotations = methods.annotations(id);
        String mods = methods.mods(id);
        Set<String> supers = null;
        for (Contract c : contracts) {
            switch (c.kind()) {
                case Contract.ANNOTATION -> {
                    if (AnnotationTokens.has(annotations, c.value())) {
                        usage.markApplied(c.row());
                        return "@" + simpleName(c.value());
                    }
                }
                case Contract.STATIC -> {
                    if (sig.equals(c.sig()) && ModifierTokens.has(mods, "static")
                            && ModifierTokens.has(mods, "public")) {
                        usage.markApplied(c.row());
                        return "static " + c.sig();
                    }
                }
                case Contract.MAIN -> {
                    if (LAUNCH_SIGNATURES.contains(sig) && !ModifierTokens.has(mods, "private")) {
                        usage.markApplied(c.row());
                        // 従来の public static main(String[]) と同じ文言になるよう static を前に付ける
                        return ModifierTokens.has(mods, "static") ? "static " + sig : sig;
                    }
                }
                case Contract.SUPER -> {
                    if (!sig.equals(c.sig())) {
                        continue;
                    }
                    if (supers == null) {
                        supers = transitiveSupertypes(methods.typeFqn(id));
                    }
                    if (supers.contains(c.value())) {
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
