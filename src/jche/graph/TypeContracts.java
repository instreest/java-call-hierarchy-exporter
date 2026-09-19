// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 具象型の契約表（種類 C）。「この宣言型（またはこの型のこのメソッド）は、この具象型」を
 * 契約表の 1 行で書く（docs/contracts-unification-design.md）。
 *
 * <h2>契約の1行</h2>
 * <pre>
 *   jp.co.xxx.dao.UserDao      =&gt; jp.co.xxx.dao.UserDaoImpl        … C-1 宣言型
 *   jp.co.xxx.dao.UserDao#find =&gt; jp.co.xxx.dao.CachedUserDao       … C-2 宣言型#メソッド名
 * </pre>
 * 右辺はカンマ区切りで複数書ける。ただし 1 件に絞れたときだけ展開される規則は変わらないので、
 * 複数書いた箇所は {@code [UNEXPANDED:CHA]} になる。
 *
 * <p>引く順番は C-2（型＋メソッド）→ C-1（型）。呼び出しごとに狭いほうを先に見る
 * （同梱の {@link jche.builtin.TypeMappingProvider} と同じ考え方）。
 *
 * <p>ファクトリとキーを書く形（C-3。{@code Factory#get("user") =&gt; 型}）はまだ実装していない。
 * その形の行は {@link Contracts} が専用の警告を出して読み飛ばす。
 *
 * <p>効かせる位置は段3（拡張と同じ）で、拡張より先に引く。利用者が表に書いた条件を、
 * ツールの推測（段4 データフロー・段5 Spring DI）より先に効かせるという既存の判断
 * （docs/instance-analysis-plugin-qa.md の Q24）を引き継ぐ。段0（静的束縛）には効かせない。
 */
public final class TypeContracts {

    /**
     * 契約の1行。
     *
     * @param declaredType 左辺の型（呼び出し先を宣言している型の FQN）
     * @param methodName   左辺のメソッド名。C-1（型だけ）なら空文字
     * @param candidates   右辺の具象型の FQN
     * @param text         元の行（報告用）
     * @param row          契約表の何行目か（{@link ContractUsage} の添字）
     */
    record Contract(String declaredType, String methodName, String[] candidates, String text, int row) {
    }

    /** 左辺の型ごとの契約。同じ型に C-1 と C-2 が並ぶことがある */
    private final Map<String, List<Contract>> byType = new LinkedHashMap<>();
    private final ContractUsage usage;

    public TypeContracts(ContractUsage usage) {
        this.usage = usage;
        List<ContractUsage.Line> lines = usage.lines();
        for (int row = 0; row < lines.size(); row++) {
            Contract c = parse(lines.get(row).text(), row);
            if (c != null) {
                byType.computeIfAbsent(c.declaredType(), k -> new ArrayList<>()).add(c);
            }
        }
    }

    /** 契約を 1 行も持たない表（同梱の行は無いので、設定を読まない経路はこれになる） */
    public static TypeContracts empty() {
        return new TypeContracts(new ContractUsage(List.of()));
    }

    /** 行ごとの利用状況（どの契約が効いたか） */
    public ContractUsage usage() {
        return usage;
    }

    public boolean isEmpty() {
        return byType.isEmpty();
    }

    /** 1行を読む。形が違えば null（黙って捨てず、呼び出し側がログに出せるよう null を返す） */
    static Contract parse(String line) {
        return parse(line, ContractUsage.NO_ROW);
    }

    /**
     * 左辺にファクトリの実引数を書いた形（C-3）か。まだ実装していないので、
     * 「読めない行」ではなく専用の警告にするために見分ける
     */
    static boolean isFactoryKeyForm(String line) {
        String s = (line == null) ? "" : line.trim();
        int arrow = s.indexOf("=>");
        return arrow > 0 && s.substring(0, arrow).indexOf('(') >= 0;
    }

    /** @param row 契約表の何行目か（利用状況の記録用） */
    private static Contract parse(String line, int row) {
        String s = (line == null) ? "" : line.trim();
        if (s.isEmpty() || s.startsWith("#")) {
            return null;
        }
        int arrow = s.indexOf("=>");
        if (arrow <= 0) {
            return null;
        }
        String left = s.substring(0, arrow).trim();
        String right = s.substring(arrow + 2).trim();
        if (left.isEmpty() || right.isEmpty() || left.indexOf('(') >= 0) {
            return null;   // 実引数を書いた形（C-3）はここでは読まない
        }
        String type = left;
        String methodName = "";
        int hash = left.indexOf('#');
        if (hash >= 0) {
            type = left.substring(0, hash).trim();
            methodName = left.substring(hash + 1).trim();
            if (type.isEmpty() || methodName.isEmpty()) {
                return null;
            }
        }
        List<String> candidates = new ArrayList<>(1);
        for (String one : right.split(",")) {
            String fqn = one.trim();
            if (!fqn.isEmpty()) {
                candidates.add(fqn);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        return new Contract(type, methodName, candidates.toArray(new String[0]), s, row);
    }

    /**
     * その呼び出しに当たる契約。無ければ null。
     *
     * <p>当たった行には「左辺が一致した」印を付ける。実際に具象型を決められたかは
     * 呼び出し側（{@link CallResolver}）が {@link ContractUsage#markApplied} で付ける。
     * 分けてあるのは、「左辺が一度も一致しない（綴り違い）」と「一致したが右辺を採用できない」を
     * 報告で区別するため。
     *
     * @param declaredType 呼び出し先を宣言している型の FQN
     * @param signature    呼び出し先のシグネチャ（{@code find(java.lang.String)}）
     */
    Contract matchFor(String declaredType, String signature) {
        List<Contract> rows = byType.get(declaredType);
        if (rows == null) {
            return null;
        }
        String methodName = methodNameOf(signature);
        Contract byMethod = null;
        Contract byTypeOnly = null;
        for (Contract c : rows) {
            if (c.methodName().isEmpty()) {
                if (byTypeOnly == null) {
                    byTypeOnly = c;
                }
            } else if (byMethod == null && c.methodName().equals(methodName)) {
                byMethod = c;
            }
        }
        Contract hit = (byMethod != null) ? byMethod : byTypeOnly;
        if (hit != null) {
            usage.markReached(hit.row());
        }
        return hit;
    }

    /** シグネチャ "find(java.lang.String)" からメソッド名だけを取り出す */
    private static String methodNameOf(String signature) {
        if (signature == null) {
            return "";
        }
        int paren = signature.indexOf('(');
        return (paren < 0) ? signature.trim() : signature.substring(0, paren).trim();
    }
}
