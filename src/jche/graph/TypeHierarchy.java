// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import jche.cache.TypeFact;

/** 型階層（H行から構築）。親子関係の問い合わせと、種別（I/A/C）の参照 */
public final class TypeHierarchy {

    /**
     * 親型 -> 子型。H 行の親型の並びなので、「直接の親」のほかに
     * 「jar の型を経由して到達するソース上の親」も 1 段の親子として入る（{@link TypeFact#superTypes()}）
     */
    private final HashMap<String, List<String>> directSubtypes = new HashMap<>();
    /** 子型 -> 親型（同上）。具象型からメソッド実装を探すのに使う */
    private final HashMap<String, List<String>> directSupertypes = new HashMap<>();
    /** 型 -> 種別（I/A/C） */
    private final HashMap<String, Character> typeKind = new HashMap<>();
    /** 型 -> その型に付いていたアノテーション（{@link jche.cache.AnnotationTokens}）。無い型は入れない */
    private final HashMap<String, String> typeAnnotations = new HashMap<>();
    private final HashMap<String, List<String>> transitiveCache = new HashMap<>();
    /**
     * 型 -> 親クラスの連鎖（H 行の {@link TypeFact#superclasses()}。ソース上の型に当たるまで）。空の型は入れない。
     * {@link #directSupertypes} は名前順に並べ替えるので、どれが親クラスかはここでしか分からない
     */
    private final HashMap<String, List<String>> superclasses = new HashMap<>();
    /** {@link #classChain} の結果 */
    private final HashMap<String, List<String>> classChainCache = new HashMap<>();
    /** {@link #superinterfaces} の結果 */
    private final HashMap<String, List<String>> superinterfaceCache = new HashMap<>();

    void add(TypeFact t) {
        typeKind.put(t.typeFqn(), t.kind());
        if (!t.superclasses().isEmpty()) {
            // 同じ型を 2 つのファイルが宣言していれば、読んだ順に依らないよう綴りの小さいほうを採る
            List<String> known = superclasses.get(t.typeFqn());
            if (known == null || String.join(",", t.superclasses()).compareTo(String.join(",", known)) < 0) {
                superclasses.put(t.typeFqn(), List.copyOf(t.superclasses()));
            }
        }
        if (!t.annotations().isEmpty()) {
            typeAnnotations.put(t.typeFqn(), t.annotations());
        }
        for (String sup : t.superTypes()) {
            link(directSubtypes, sup, t.typeFqn());
            link(directSupertypes, t.typeFqn(), sup);
        }
    }

    private static void link(HashMap<String, List<String>> map, String from, String to) {
        List<String> list = map.computeIfAbsent(from, k -> new ArrayList<>());
        if (!list.contains(to)) {
            list.add(to);
        }
    }

    /**
     * 型階層の並びをキャッシュ上の出現順から切り離す。差分更新では解析し直した
     * ファイルのブロックが移動するので、出現順のままだと CHA 候補の並び
     * （＝出力の行順）が実行のたびに変わりうる
     */
    void sortForDeterminism() {
        for (List<String> l : directSubtypes.values()) {
            Collections.sort(l);
        }
        for (List<String> l : directSupertypes.values()) {
            Collections.sort(l);
        }
        // 並べ替える前に引いた結果を残さない
        transitiveCache.clear();
        classChainCache.clear();
        superinterfaceCache.clear();
    }

    /** ソース上に宣言のある型か */
    public boolean contains(String typeFqn) {
        return typeKind.containsKey(typeFqn);
    }

    /** その型に付いていたアノテーション（{@link jche.cache.AnnotationTokens}）。無ければ空文字列 */
    public String annotationsOf(String typeFqn) {
        return typeAnnotations.getOrDefault(typeFqn, "");
    }

    public char kindOf(String typeFqn) {
        Character k = typeKind.get(typeFqn);
        return (k == null) ? '?' : k;
    }

    public int size() {
        return typeKind.size();
    }

    /** ソース上に宣言のある型の名前一覧（コピー） */
    public Set<String> typeNames() {
        return new HashSet<>(typeKind.keySet());
    }

    /**
     * 親型（名前順）。直接の親と、jar の型を経由して到達するソース上の親。無ければ空。
     * 親クラスとインターフェースの区別は無い。実際に動く実装を探す順は {@link #classChain}
     */
    public List<String> directSupertypes(String type) {
        List<String> sups = directSupertypes.get(type);
        return (sups == null) ? List.of() : sups;
    }

    /**
     * その型と、その親クラスの連鎖（直接の親クラスから親へ順に。java.lang.Object は含まない）。
     * 途中の jar のクラスも並ぶ（H 行が並べたもの）。インターフェースなら、その型だけ。
     *
     * <p>実際に動く実装は、親クラスの連鎖を根まで見てから親インターフェースを見て探す（JLS 8.4.8・JVMS 5.4.6）。
     * {@link #directSupertypes} は親クラスとインターフェースを名前順に混ぜて並べるので、その順に辿ると
     * {@code class Impl extends Base implements Api} で Api の default メソッドが Base のメソッドより先に当たる
     */
    public List<String> classChain(String type) {
        List<String> cached = classChainCache.get(type);
        if (cached != null) {
            return cached;
        }
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String cur = type;
        while (cur != null && seen.add(cur)) {
            out.add(cur);
            List<String> sups = superclasses.get(cur);
            if (sups == null) {
                break;
            }
            // 最後の型だけがソース上の型でありうる。その先はその型自身の H 行から続ける
            for (int i = 0; i < sups.size() - 1; i++) {
                if (seen.add(sups.get(i))) {
                    out.add(sups.get(i));
                }
            }
            cur = sups.get(sups.size() - 1);
        }
        List<String> result = List.copyOf(out);
        classChainCache.put(type, result);
        return result;
    }

    /**
     * その型の親クラスの連鎖（{@link #classChain}）の型が実装するインターフェースを、近いものから幅優先で
     * （同じ深さは名前順で）。連鎖の型は含まない。jar の型を経由して到達するソース上の型も入る
     * （{@link #directSupertypes} と同じ）
     */
    public List<String> superinterfaces(String type) {
        List<String> cached = superinterfaceCache.get(type);
        if (cached != null) {
            return cached;
        }
        List<String> chain = classChain(type);
        Set<String> seen = new HashSet<>(chain);
        List<String> out = new ArrayList<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        for (String c : chain) {
            for (String sup : directSupertypes(c)) {
                if (seen.add(sup)) {
                    queue.add(sup);
                }
            }
        }
        while (!queue.isEmpty()) {
            String t = queue.poll();
            out.add(t);
            for (String sup : directSupertypes(t)) {
                if (seen.add(sup)) {
                    queue.add(sup);
                }
            }
        }
        List<String> result = List.copyOf(out);
        superinterfaceCache.put(type, result);
        return result;
    }

    /**
     * 型の並びから、ほかの型の真の親型であるものを除いたもの（並びは保つ）。
     * 親インターフェースのメソッドから最も特定的なもの（JLS 9.4.1・JVMS 5.4.3.3 の maximally-specific）を選ぶのに使う。
     * {@code interface I2 extends I1} の両方が宣言していれば I2 のものが残る
     */
    public List<String> mostSpecific(List<String> types) {
        List<String> out = new ArrayList<>(types.size());
        for (String t : types) {
            boolean overridden = false;
            for (String other : types) {
                if (!other.equals(t) && isSubtypeOf(other, t)) {
                    overridden = true;
                    break;
                }
            }
            if (!overridden) {
                out.add(t);
            }
        }
        // 循環した型階層（壊れたソース）では全部が消えうる。そのときは絞らない
        return out.isEmpty() ? new ArrayList<>(types) : out;
    }

    /** 推移的なサブタイプ。循環があっても止まるように訪問済みを持つ */
    public List<String> transitiveSubtypes(String type) {
        List<String> cached = transitiveCache.get(type);
        if (cached != null) {
            return cached;
        }
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        ArrayDeque<String> stack = new ArrayDeque<>();
        stack.push(type);
        while (!stack.isEmpty()) {
            String cur = stack.pop();
            List<String> subs = directSubtypes.get(cur);
            if (subs == null) {
                continue;
            }
            for (String sub : subs) {
                if (seen.add(sub)) {
                    out.add(sub);
                    stack.push(sub);
                }
            }
        }
        transitiveCache.put(type, out);
        return out;
    }

    public boolean isSubtypeOf(String type, String ancestor) {
        if (type.equals(ancestor)) {
            return true;
        }
        ArrayDeque<String> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(type);
        seen.add(type);
        while (!queue.isEmpty()) {
            for (String s : directSupertypes(queue.poll())) {
                if (s.equals(ancestor)) {
                    return true;
                }
                if (seen.add(s)) {
                    queue.add(s);
                }
            }
        }
        return false;
    }
}
