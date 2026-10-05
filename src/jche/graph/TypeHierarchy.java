// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import jche.cache.TypeFact;

/**
 * 型階層（H行から構築）。親子関係の問い合わせと、種別（I/A/C）の参照。
 * jar の型の親型（H 行の 9 列目）も持ち、部分型の列挙（{@link #transitiveSubtypes}）と親子の判定（{@link #isSubtypeOf}）は
 * jar の型を経由した関係も見る。実装を探す並び（{@link #classChain} / {@link #superinterfaces}）は H 行の親型の並び
 * （ソースの型の直接の親と、jar を経由して届くソースの親）のまま
 */
public final class TypeHierarchy {

    /**
     * 親型 -> 子型（組み立て中の形。{@link #sortForDeterminism} が名前順の {@link #directSubtypes} に写す）。
     * H 行の親型の並びなので、「直接の親」のほかに「jar の型を経由して到達するソース上の親」も 1 段の親子として入る
     * （{@link TypeFact#superTypes()}）。jar の型の親子（{@link #binarySupertypeLinks}）も入るので、子型には jar の型も並ぶ
     * （{@link #transitiveSubtypes} はソース上の型だけを返す）。
     * 集合で持つのは、よく使う親型（{@code java.util.List} など。H 行の 9 列目で多くの型が同じ組を書く）の子型の重複を
     * O(1) で弾くため（List の contains で弾くと型の数の 2 乗になる）
     */
    private final HashMap<String, LinkedHashSet<String>> subtypeLinks = new HashMap<>();
    /** 子型 -> 親型（同上。ソース上の型だけ）。具象型からメソッド実装を探すのに使う */
    private final HashMap<String, LinkedHashSet<String>> supertypeLinks = new HashMap<>();
    /**
     * jar の型（ソースの無い型）-> その親型。H 行の 9 列目（{@link TypeFact#binarySupertypes()}。ソースの型から親型を辿って
     * 到達した jar の型の推移的な親型）から。jar の型には H 行が無いので、部分型（{@link #transitiveSubtypes}）と親子の
     * 判定（{@link #isSubtypeOf}・{@link #mostSpecific}）にだけ使い、{@link #supertypeLinks} には混ぜない
     * （実装を探す並び {@link #classChain} / {@link #superinterfaces} は H 行の親型の並びのまま）
     */
    private final HashMap<String, LinkedHashSet<String>> binarySupertypeLinks = new HashMap<>();
    /**
     * {@link #subtypeLinks} / {@link #supertypeLinks} / {@link #binarySupertypeLinks} を名前順の一覧にしたもの
     * （問い合わせはこちらを読む）。{@link #sortForDeterminism} が作り、{@link #add} で捨てる（null）。
     * 作る前に問い合わせがあれば、そのとき作る（{@link #ensureSorted}）
     */
    private HashMap<String, List<String>> directSubtypes;
    private HashMap<String, List<String>> directSupertypes;
    private HashMap<String, List<String>> binarySupertypes;
    /**
     * 型 -> 種別（I/A/C）。同じ型を 2 つのファイルが宣言していれば（{@link #add}）、読んだ順に依らないよう
     * 綴りの小さいほう（{@code A}・{@code C}・{@code I} の順）を採る。{@link #typePackage}・{@link #typeAnnotations} も同じ
     */
    private final HashMap<String, Character> typeKind = new HashMap<>();
    /** 型 -> パッケージ（H 行の 4 列目）。ソース上の型だけ */
    private final HashMap<String, String> typePackage = new HashMap<>();
    /** 型 -> その型に付いていたアノテーション（{@link jche.cache.AnnotationTokens}）。無い型は入れない */
    private final HashMap<String, String> typeAnnotations = new HashMap<>();
    private final HashMap<String, List<String>> transitiveCache = new HashMap<>();
    /**
     * 型 -> 親クラスの連鎖（H 行の {@link TypeFact#superclasses()}。ソース上の型に当たるまで）。空の型は入れない。
     * {@link #directSupertypes} は名前順に並べ替えるので、どれが親クラスかはここでしか分からない
     */
    private final HashMap<String, List<String>> superclasses = new HashMap<>();
    /**
     * 型 -> 親クラスから継承したメソッドが親インターフェースのメソッドを実装する組（H 行の
     * {@link TypeFact#inheritedImpls()}。{@code 実装される側のキー>実装する側のキー}）。空の型は入れない
     */
    private final HashMap<String, List<String>> inheritedImpls = new HashMap<>();
    /** {@link #classChain} の結果 */
    private final HashMap<String, List<String>> classChainCache = new HashMap<>();
    /** {@link #superinterfaces} の結果 */
    private final HashMap<String, List<String>> superinterfaceCache = new HashMap<>();
    /**
     * 型の番号（{@link #indexOf} / {@link #typeAt}）。名前順に並べた型の名前と、名前 -> 番号の索引。
     * 最初に要るときに作る（型を足したら捨てる）。経路の環境の枠（{@link Slot#BOUND}）が型を番号で指すためのもので、
     * 番号は 1 回の実行の中でしか通じない
     */
    private String[] indexedNames;
    private HashMap<String, Integer> indexByName;

    /**
     * H 行 1 つを足す。同じ型を 2 つのファイルが宣言していれば（ビルドの通らない状態。CallGraphBuilder が警告する）、
     * どの値も読んだ順（キャッシュのブロックの並び＝差分更新で変わる）に依らないよう、綴りの小さいほうを採る
     * （種別・パッケージ・アノテーション・親クラスの連鎖・継承した実装。親型の集合は両方の和）
     */
    void add(TypeFact t) {
        indexedNames = null;
        indexByName = null;
        directSubtypes = null;
        directSupertypes = null;
        binarySupertypes = null;
        typeKind.merge(t.typeFqn(), t.kind(), (a, b) -> (a <= b) ? a : b);
        typePackage.merge(t.typeFqn(), t.pkg() == null ? "" : t.pkg(), TypeHierarchy::smaller);
        if (!t.superclasses().isEmpty()) {
            List<String> known = superclasses.get(t.typeFqn());
            if (known == null || String.join(",", t.superclasses()).compareTo(String.join(",", known)) < 0) {
                superclasses.put(t.typeFqn(), List.copyOf(t.superclasses()));
            }
        }
        if (!t.inheritedImpls().isEmpty()) {
            List<String> known = inheritedImpls.get(t.typeFqn());
            if (known == null || String.join(";", t.inheritedImpls()).compareTo(String.join(";", known)) < 0) {
                inheritedImpls.put(t.typeFqn(), List.copyOf(t.inheritedImpls()));
            }
        }
        if (!t.annotations().isEmpty()) {
            typeAnnotations.merge(t.typeFqn(), t.annotations(), TypeHierarchy::smaller);
        }
        for (String sup : t.superTypes()) {
            link(subtypeLinks, sup, t.typeFqn());
            link(supertypeLinks, t.typeFqn(), sup);
        }
        for (String edge : t.binarySupertypes()) {
            int gt = edge.indexOf('>');
            if (gt <= 0 || gt == edge.length() - 1) {
                continue;
            }
            String child = edge.substring(0, gt);
            String parent = edge.substring(gt + 1);
            link(subtypeLinks, parent, child);
            link(binarySupertypeLinks, child, parent);
        }
    }

    private static String smaller(String a, String b) {
        return (a.compareTo(b) <= 0) ? a : b;
    }

    private static void link(HashMap<String, LinkedHashSet<String>> map, String from, String to) {
        map.computeIfAbsent(from, k -> new LinkedHashSet<>()).add(to);
    }

    /**
     * 型階層の並びをキャッシュ上の出現順から切り離す。差分更新では解析し直した
     * ファイルのブロックが移動するので、出現順のままだと CHA 候補の並び
     * （＝出力の行順）が実行のたびに変わりうる
     */
    void sortForDeterminism() {
        directSubtypes = sorted(subtypeLinks);
        directSupertypes = sorted(supertypeLinks);
        binarySupertypes = sorted(binarySupertypeLinks);
        // 並べ替える前に引いた結果を残さない
        transitiveCache.clear();
        classChainCache.clear();
        superinterfaceCache.clear();
    }

    /** 組み立て中の集合を、名前順の一覧に写す */
    private static HashMap<String, List<String>> sorted(HashMap<String, LinkedHashSet<String>> links) {
        HashMap<String, List<String>> out = new HashMap<>(links.size() * 2);
        for (var e : links.entrySet()) {
            String[] names = e.getValue().toArray(new String[0]);
            Arrays.sort(names);
            out.put(e.getKey(), List.of(names));
        }
        return out;
    }

    /** 問い合わせの前に、名前順の一覧ができていることを保証する（{@link #add} の後は作り直す） */
    private void ensureSorted() {
        if (directSubtypes == null) {
            sortForDeterminism();
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

    /**
     * その型のパッケージ（H 行）。ソース上に無い型（jar の型）なら、名前の最後のドットより前
     * （入れ子の型では外側の型まで含む名前になる。呼ぶ側はそのつもりで使う）
     */
    public String packageOf(String typeFqn) {
        String pkg = typePackage.get(typeFqn);
        if (pkg != null) {
            return pkg;
        }
        int dot = typeFqn.lastIndexOf('.');
        return (dot < 0) ? "" : typeFqn.substring(0, dot);
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
     * ソース上に宣言のある型の番号（名前順。{@link #typeAt} で名前に戻す）。ソース上に無い型（jar の型・配列）なら -1。
     * 経路の環境の枠（{@link Slot#BOUND}）が実行時の型の上限をこの番号で持つ
     */
    public int indexOf(String typeFqn) {
        if (typeFqn == null) {
            return -1;
        }
        buildIndex();
        Integer i = indexByName.get(typeFqn);
        return (i == null) ? -1 : i;
    }

    /** {@link #indexOf} の番号の型の名前。範囲の外なら null */
    public String typeAt(int index) {
        buildIndex();
        return (index < 0 || index >= indexedNames.length) ? null : indexedNames[index];
    }

    private void buildIndex() {
        if (indexedNames != null) {
            return;
        }
        String[] names = typeKind.keySet().toArray(new String[0]);
        Arrays.sort(names);
        HashMap<String, Integer> byName = new HashMap<>(names.length * 2);
        for (int i = 0; i < names.length; i++) {
            byName.put(names[i], i);
        }
        indexByName = byName;
        indexedNames = names;
    }

    /**
     * 親型（名前順）。直接の親と、jar の型を経由して到達するソース上の親。無ければ空（jar の型も空。
     * jar の型の親型は {@link #binarySupertypesOf}）。
     * 親クラスとインターフェースの区別は無い。実際に動く実装を探す順は {@link #classChain}
     */
    public List<String> directSupertypes(String type) {
        ensureSorted();
        List<String> sups = directSupertypes.get(type);
        return (sups == null) ? List.of() : sups;
    }

    /**
     * jar の型（ソースに宣言の無い型）の親型（名前順。H 行の 9 列目から）。無ければ空。
     * ソースの型から親型を辿って到達した jar の型についてだけ分かる（それ以外の jar の型は空）
     */
    public List<String> binarySupertypesOf(String type) {
        ensureSorted();
        List<String> sups = binarySupertypes.get(type);
        return (sups == null) ? List.of() : sups;
    }

    /**
     * その型で、親クラスから継承したメソッドが親インターフェースのメソッドを実装する組
     * （{@code 実装される側のキー>実装する側のキー}。{@link TypeFact#inheritedImpls()}）。無ければ空
     */
    public List<String> inheritedImplementations(String type) {
        List<String> l = inheritedImpls.get(type);
        return (l == null) ? List.of() : l;
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
     * {@code interface I2 extends I1} の両方が宣言していれば I2 のものが残る。
     * jar のインターフェースを経由した親子（{@code interface lib.LibMid extends s.Top} の LibMid と Top）も
     * {@link #isSubtypeOf} が jar の型の親型（H 行の 9 列目）を辿るので見える
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

    /**
     * 推移的なサブタイプ（ソース上の型だけ）。循環があっても止まるように訪問済みを持つ。
     * jar の型を経由した部分型（{@code class MyList extends ArrayList<String>} は {@code java.util.List} の部分型）も、
     * jar の型の親子（H 行の 9 列目）を通って辿る。途中の jar の型は結果に入れない（ソースの無い型は実装を探せず、
     * 呼び出しの候補にも Bean にもならない）
     */
    public List<String> transitiveSubtypes(String type) {
        List<String> cached = transitiveCache.get(type);
        if (cached != null) {
            return cached;
        }
        ensureSorted();
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
                    if (typeKind.containsKey(sub)) {
                        out.add(sub);
                    }
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
            String cur = queue.poll();
            // ソースの型の親型（H 行の 3 列目）と、jar の型の親型（9 列目）の両方を辿る
            for (List<String> sups : List.of(directSupertypes(cur), binarySupertypesOf(cur))) {
                for (String s : sups) {
                    if (s.equals(ancestor)) {
                        return true;
                    }
                    if (seen.add(s)) {
                        queue.add(s);
                    }
                }
            }
        }
        return false;
    }
}
