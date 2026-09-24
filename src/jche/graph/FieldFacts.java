// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jche.cache.FieldAssignFact;
import jche.cache.FieldDeclFact;
import jche.cache.MethodDeclFact;
import jche.cache.ModifierTokens;
import jche.cache.Origin;

/**
 * D行・V行・J行から「コンストラクタ注入されたフィールド」を判定する（読み手の判断）。
 *
 * 「このフィールドには、必ずこの出所の値が入る」と言い切るには次を全部満たす必要がある。
 * <pre>
 *   (a) private または final（クラスの外から代入されない）
 *   (b) 代入がコンストラクタの本体か、フィールド初期化子の中だけにある
 *       （setterや他のメソッドで後から差し替わらない）
 *   (c) 初期化子を持つか、this(...)委譲していない全てのコンストラクタで
 *       代入されている（代入されない生成経路が無い）
 *   (d) それらの代入の出所が全て一致する
 *   (e) 出所がコンストラクタの引数（A:n）なら、その型に this(...) 委譲するコンストラクタが無い。
 *       A:n は根コンストラクタの引数位置だが、経路側で分かる実引数は new X(...) が実際に呼んだ
 *       コンストラクタのもので、委譲があると位置が食い違い、誤った具象型に確定しうる。
 *       「絞れないことより誤って絞ることの方が害が大きい」ので採用しない
 * </pre>
 * 事実はファイル（F行）ごとに溜め、次のF行またはEOFで確定する。
 * static フィールドは対象外（インスタンスの生成経路と無関係なため）。
 */
final class FieldFacts {

    /**
     * 代入 1 件。値は 2 通りの形で持つ（stage B の途中。文字列の側は読み手を移し終えたら消す）
     *
     * @param site     代入した場所（コンストラクタのシグネチャか {@link FieldAssignFact#SITE_INITIALIZER}）
     * @param origin   値の頭の文字列（{@code 種別:値}）。追跡できなければ U
     * @param head     値の頭の葉の参照（{@link ValueStore}）。追跡できなければ {@link ValueStore#NONE}
     * @param headKind 値の頭の種別。追跡できなければ {@link Origin#UNKNOWN}
     */
    private record Assign(String site, String origin, int head, char headKind) {
    }

    private static final class Field {
        final String mods;
        final List<Assign> assigns = new ArrayList<>(2);

        Field(String mods) {
            this.mods = mods;
        }
    }

    /** "typeFqn#fieldName" -> 宣言と代入 */
    private final LinkedHashMap<String, Field> fields = new LinkedHashMap<>();
    /** typeFqn -> this(...)委譲していないコンストラクタの "name(paramSig)" */
    private final HashMap<String, Set<String>> rootCtors = new HashMap<>();
    /** this(...)委譲するコンストラクタを1つでも持つ型（条件 (e)） */
    private final Set<String> typesWithDelegatingCtor = new HashSet<>();

    void declaration(MethodDeclFact d) {
        if (!d.ref().isConstructor()) {
            return;
        }
        if (ModifierTokens.has(d.mods(), ModifierTokens.DELEGATING)) {
            typesWithDelegatingCtor.add(d.ref().typeFqn());
            return;
        }
        rootCtors.computeIfAbsent(d.ref().typeFqn(), k -> new HashSet<>()).add(d.ref().signature());
    }

    void field(FieldDeclFact v) {
        fields.putIfAbsent(v.typeFqn() + "#" + v.fieldName(), new Field(v.mods()));
    }

    /**
     * 代入 1 件を溜める。
     *
     * @param origin 代入された値の出所の頭（{@code 種別:値}。実引数リストは付けない）。追跡できなければ U。
     *               キャッシュの J 行はノード番号なので、読み手（{@link CallGraphBuilder}）が同じブロックの
     *               値グラフから組み直して渡す。頭だけで比べるのは、{@code new X(a)} と {@code new X(b)} を
     *               同じ出所（{@code T:X}）とみなすため（以前の J 行も頭だけを持っていた）
     * @param head     同じ値の頭の葉の参照（{@link ValueStoreBuilder#importHead}）。追跡できなければ
     *                 {@link ValueStore#NONE}。葉は種別と値の組ごとに 1 つなので、参照が同じなら頭も同じ
     * @param headKind 値の頭の種別。追跡できなければ {@link Origin#UNKNOWN}
     */
    void assignment(FieldAssignFact j, String origin, int head, char headKind) {
        Field fd = fields.get(j.typeFqn() + "#" + j.fieldName());
        if (fd != null) {
            fd.assigns.add(new Assign(j.site(), origin, head, headKind));
        }
    }

    /**
     * 溜めた事実から判定し、確定したフィールドの出所を fieldOrigins に、値の頭の参照を fieldHeads に足して、
     * 溜めた事実を捨てる。2 つは同じ判定を文字列と参照でそれぞれ行ったもの（stage B の途中）
     */
    void flushInto(Map<String, String> fieldOrigins, Map<String, Integer> fieldHeads) {
        for (Map.Entry<String, Field> e : fields.entrySet()) {
            String key = e.getKey();
            String typeFqn = key.substring(0, key.indexOf('#'));
            if (!assignedOnEveryPath(typeFqn, e.getValue())) {
                continue;
            }
            String origin = agreedOriginOf(typeFqn, e.getValue());
            if (origin != null) {
                fieldOrigins.put(key, origin);
            }
            int head = agreedHeadOf(typeFqn, e.getValue());
            if (head != ValueStore.NONE) {
                fieldHeads.put(key, head);
            }
        }
        fields.clear();
        rootCtors.clear();
        typesWithDelegatingCtor.clear();
    }

    /** 条件 (a)〜(c) を満たすか（代入の値には依らない部分） */
    private boolean assignedOnEveryPath(String typeFqn, Field fd) {
        if (ModifierTokens.has(fd.mods, "static")
                || !(ModifierTokens.has(fd.mods, "private") || ModifierTokens.has(fd.mods, "final"))) {
            return false;   // (a)
        }
        if (fd.assigns.isEmpty()) {
            return false;
        }
        Set<String> roots = rootCtors.get(typeFqn);
        int rootCount = (roots == null) ? 0 : roots.size();
        boolean hasInitializer = false;
        Set<String> assignedIn = new HashSet<>();
        for (Assign a : fd.assigns) {
            if (FieldAssignFact.SITE_INITIALIZER.equals(a.site())) {
                hasInitializer = true;
            } else if (roots != null && roots.contains(a.site())) {
                assignedIn.add(a.site());
            } else {
                return false;   // (b) 生成後に差し替わりうる
            }
        }
        // (c) 代入されない生成経路がある
        return hasInitializer || assignedIn.size() >= rootCount;
    }

    /** 条件 (d)・(e) を満たすなら、そのフィールドに必ず入る値の出所。満たさなければ null */
    private String agreedOriginOf(String typeFqn, Field fd) {
        String origin = null;
        for (Assign a : fd.assigns) {
            if (Origin.isUnknown(a.origin()) || (origin != null && !origin.equals(a.origin()))) {
                return null;   // (d)
            }
            origin = a.origin();
        }
        if (Origin.kindOf(origin) == Origin.PARAM && typesWithDelegatingCtor.contains(typeFqn)) {
            return null;   // (e) 委譲があると実引数の位置が根コンストラクタと一致しない
        }
        return origin;
    }

    /** {@link #agreedOriginOf} と同じ判定を、値の頭の葉の参照で行う。満たさなければ {@link ValueStore#NONE} */
    private int agreedHeadOf(String typeFqn, Field fd) {
        int head = ValueStore.NONE;
        char kind = Origin.UNKNOWN;
        for (Assign a : fd.assigns) {
            if (a.head() == ValueStore.NONE || a.headKind() == Origin.UNKNOWN
                    || (head != ValueStore.NONE && head != a.head())) {
                return ValueStore.NONE;   // (d)
            }
            head = a.head();
            kind = a.headKind();
        }
        if (kind == Origin.PARAM && typesWithDelegatingCtor.contains(typeFqn)) {
            return ValueStore.NONE;   // (e)
        }
        return head;
    }
}
