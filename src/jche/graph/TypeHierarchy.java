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

    void add(TypeFact t) {
        typeKind.put(t.typeFqn(), t.kind());
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

    /** 親型（名前順）。直接の親と、jar の型を経由して到達するソース上の親。無ければ空 */
    public List<String> directSupertypes(String type) {
        List<String> sups = directSupertypes.get(type);
        return (sups == null) ? List.of() : sups;
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
