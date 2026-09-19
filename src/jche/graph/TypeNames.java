// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 契約表や拡張に書かれた型名を、解析対象の型の完全修飾名（FQN）に直す。
 *
 * <h2>なぜ要るか</h2>
 * 契約表の 1 行も拡張が返す候補も、これまでは FQN で書く必要があった。同じパッケージの型を
 * 何十行も並べるのに毎回 FQN を書くのは手数が多く、書き間違えても「効かない」だけで終わる。
 * 単純名で書けるなら、そのほうが速いし読みやすい。
 *
 * <h2>1 件に定まるときだけ使う</h2>
 * 単純名は衝突しうる（{@code jp.co.a.Util} と {@code jp.co.b.Util}）。衝突しているものを
 * どちらかに決めてしまうと、<b>誤った型に静かに解決する</b>。このツールは「絞れないことより
 * 誤って絞ることの方が害が大きい」を一貫して採るので、衝突していれば使わず、曖昧だと知らせる。
 *
 * <h2>引く順番</h2>
 * <ol>
 *   <li>書かれたとおりの名前が解析対象にあれば、それ（FQN として書かれている）</li>
 *   <li>無ければ単純名として引き、1 件に定まればその FQN</li>
 *   <li>どちらでもなければ、書かれたまま返す（見つからないことは呼び出し側が扱う）</li>
 * </ol>
 * 内部クラスは {@code Outer.Inner} の形で FQN に入っているので、単純名は最後の区切り以降
 * （{@code Inner}）になる。
 */
public final class TypeNames {

    /** 単純名 -> FQN。衝突しているものは値を null にして「曖昧」を表す */
    private final Map<String, String> bySimpleName;
    /** 曖昧な単純名 -> 候補の FQN（知らせるため。衝突は多くないので持っておく） */
    private final Map<String, List<String>> ambiguous;
    private final TypeHierarchy hierarchy;

    TypeNames(TypeHierarchy hierarchy) {
        this.hierarchy = hierarchy;
        Map<String, String> index = new HashMap<>();
        Map<String, List<String>> conflicts = new HashMap<>();
        for (String fqn : hierarchy.typeNames()) {
            String simple = simpleNameOf(fqn);
            if (simple.isEmpty() || simple.equals(fqn)) {
                continue;   // 既定パッケージの型は単純名と FQN が同じ。引く意味が無い
            }
            if (!index.containsKey(simple)) {
                index.put(simple, fqn);
                continue;
            }
            String first = index.get(simple);
            if (fqn.equals(first)) {
                continue;
            }
            index.put(simple, null);
            List<String> all = conflicts.computeIfAbsent(simple, k -> new ArrayList<>(List.of(first)));
            if (!all.contains(fqn)) {
                all.add(fqn);
            }
        }
        for (List<String> all : conflicts.values()) {
            Collections.sort(all);   // 知らせる順を実行ごとに変えない
        }
        this.bySimpleName = index;
        this.ambiguous = conflicts;
    }

    /**
     * 書かれた型名を FQN に直す。直せなければ書かれたまま返す。
     *
     * @param name 契約表や拡張が返した型名。FQN でも単純名でもよい
     */
    public String toFqn(String name) {
        if (name == null || name.isEmpty() || hierarchy.contains(name)) {
            return name;
        }
        String fqn = bySimpleName.get(name);
        return (fqn == null) ? name : fqn;
    }

    /**
     * その名前が「単純名としては複数の型に当たる」なら候補の一覧。曖昧でなければ空。
     * 書いた側に「どれのことか分からないので使わなかった」と知らせるために使う
     */
    public List<String> ambiguousCandidates(String name) {
        if (name == null || hierarchy.contains(name)) {
            return List.of();
        }
        List<String> all = ambiguous.get(name);
        return (all == null) ? List.of() : all;
    }

    /** "jp.co.xxx.UserDaoImpl" -> "UserDaoImpl"（内部クラスは最後の区切り以降） */
    private static String simpleNameOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return (dot < 0) ? fqn : fqn.substring(dot + 1);
    }
}
