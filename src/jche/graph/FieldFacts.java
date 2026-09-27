// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jche.cache.AnnotationTokens;
import jche.cache.FieldAssignFact;
import jche.cache.FieldDeclFact;
import jche.cache.MethodDeclFact;
import jche.cache.ModifierTokens;
import jche.cache.Origin;
import jche.cache.TypeFact;

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
 *   (d) それらの代入の値の頭（種別と値。値の表の同じ葉）が全て一致する
 *   (e) 値がコンストラクタの引数（A:n）なら、その型に this(...) 委譲するコンストラクタが無い。
 *       A:n は根コンストラクタの引数位置だが、経路側で分かる実引数は new X(...) が実際に呼んだ
 *       コンストラクタのもので、委譲があると位置が食い違い、誤った具象型に確定しうる。
 *       「絞れないことより誤って絞ることの方が害が大きい」ので採用しない
 *   (f) フレームワークが書きうるフィールドでない。フィールドにアノテーションが付いている
 *       （&#64;Autowired・&#64;Inject・&#64;Resource・&#64;Value の注入、JPA の列、JSON の項目、モックなど）か、
 *       宣言した型に DI のステレオタイプ注釈（{@link SpringBeans#DEFAULT_STEREOTYPES}）以外のアノテーションが
 *       付いている（&#64;ConfigurationProperties の結び付け、JPA の &#64;Entity の読み込みなど）なら、生成の後に
 *       リフレクションや生成されたコードがソースに見えない書き込みをする。&#64;Autowired(required = false) の
 *       初期化子は「注入されなかったときの既定」で、注入されれば別の値になる（docs/value-safety-qa.md の Q27）。
 *       どの注釈がそうかを一覧で持つと、載っていない注釈で誤って絞るので、付いていれば外す側に倒す。
 *       {@code java.lang} の注釈（{@code @Deprecated} など）は書き手が記録しないので、ここでは数えない
 * </pre>
 * 事実はファイル（F行）ごとに溜め、次のF行またはEOFで確定する。
 * static フィールドは対象外（インスタンスの生成経路と無関係なため）。
 *
 * <p>あわせて、ソースが引数でない値を入れるフィールド（参照型の static でないフィールドで、初期化子・初期化ブロック・
 * コンストラクタ・メソッドのどこかに、値の頭が引数（{@code A:n}）でない書き込みがある。値の分からない書き込みも含む。
 * {@link #flushInto} の {@code ownValued}）を控える。DI（段 5）はそういうフィールドを注入点にしない。コンテナが入れた値
 * だけが来るとは言えないからである（&#64;Autowired(required = false) の既定・コンテナの外で new したインスタンス・
 * 後から差し替える書き込み。docs/spring-di-qa.md の Q15）。判定できたフィールドも控える。値の頭が {@code new} でなければ
 * （{@code = makeDao()}）経路で具象型が決まらず、段 5 の結論が残るため。
 *
 * <p><b>よその書き込み</b>: private でないフィールドには、別のファイルの型（子クラスのコンストラクタ・ほかのクラスの
 * {@code svc.dao = …}）からも書ける。その J 行は書いた側のブロックにあり、同じブロックに宣言（V 行）が無い。
 * そういう J 行はファイルをまたいで控え（ブロックの並びに依らない和集合）、ownValued に足す（書き手は値を書かないので、
 * どれも引数でない書き込み）。値の判定（条件 (a)〜(e)）には効かない。private でも final でもないフィールドは
 * 条件 (a) で外れ、private なフィールドへの書き込みは必ず同じファイルにある（JLS 6.6.1）からである。
 * 引数を入れる書き込みかは J 行の種別の列（{@link FieldAssignFact#kind}）で見るので、値を読まない指定（N 行を読まない）でも
 * 同じ ownValued になる。値を読まない指定では、レシーバがどのフィールドかが分からないので、ownValued のフィールドの
 * 宣言の型を型ごとに控える（{@link #flushInto} の {@code ownValuedTypes}。{@link CallGraph#mayReadOwnValuedField}）。
 */
final class FieldFacts {

    /**
     * 代入 1 件
     *
     * @param site     代入した場所（コンストラクタのシグネチャか {@link FieldAssignFact#SITE_INITIALIZER}）
     * @param head     値の頭の葉の参照（{@link ValueStore}）。追跡できなければ {@link ValueStore#NONE}
     * @param headKind 値の頭の種別。追跡できなければ {@link Origin#UNKNOWN}
     */
    private record Assign(String site, int head, char headKind) {
    }

    private static final class Field {
        final String mods;
        /** フレームワークが書きうる（条件 (f)） */
        final boolean framework;
        /** 宣言の型（V 行の declType） */
        final String declType;
        final List<Assign> assigns = new ArrayList<>(2);

        Field(String mods, boolean framework, String declType) {
            this.mods = mods;
            this.framework = framework;
            this.declType = declType;
        }

        /** 基本型でも String でもない（DI の注入点になりうる。{@link #ownValued}） */
        boolean reference() {
            return !SpringBeans.isPrimitiveOrString(declType);
        }
    }

    /** 値を読まない指定で、宣言の型の分からない ownValued のフィールドを持つ型に控える印（どの型のレシーバにも当たる） */
    static final String ANY_TYPE = "*";

    /** "typeFqn#fieldName" -> 宣言と代入 */
    private final LinkedHashMap<String, Field> fields = new LinkedHashMap<>();
    /** typeFqn -> this(...)委譲していないコンストラクタの "name(paramSig)" */
    private final HashMap<String, Set<String>> rootCtors = new HashMap<>();
    /** this(...)委譲するコンストラクタを1つでも持つ型（条件 (e)） */
    private final Set<String> typesWithDelegatingCtor = new HashSet<>();
    /** DI のステレオタイプ注釈以外のアノテーションが付いた型（条件 (f)）。H 行は同じブロックの V 行より前に並ぶ */
    private final Set<String> frameworkTypes = new HashSet<>();
    /**
     * このブロックの、同じブロックに宣言（V 行）の無いフィールドへの、引数でない書き込みの鍵（クラスの説明の
     * 「よその書き込み」）
     */
    private final Set<String> foreignWrites = new HashSet<>();

    /** 型の宣言（H 行）。条件 (f) の型のアノテーションを見る */
    void type(TypeFact t) {
        if (!AnnotationTokens.onlyAmong(t.annotations(), SpringBeans.DEFAULT_STEREOTYPES)) {
            frameworkTypes.add(t.typeFqn());
        }
    }

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
        boolean framework = !v.annotations().isEmpty() || frameworkTypes.contains(v.typeFqn());
        fields.putIfAbsent(v.typeFqn() + "#" + v.fieldName(), new Field(v.mods(), framework, v.declType()));
    }

    /**
     * 代入 1 件を溜める。
     *
     * @param head     代入された値の頭（種別と値。実引数リストは付けない）の葉の参照
     *                 （{@link ValueStoreBuilder#importHead}）。追跡できなければ {@link ValueStore#NONE}。
     *                 キャッシュの J 行はノード番号なので、読み手（{@link CallGraphBuilder}）が同じブロックの
     *                 値グラフから取り込んで渡す。頭だけで比べるのは、{@code new X(a)} と {@code new X(b)} を
     *                 同じ値（{@code T:X}）とみなすため（以前の J 行も頭だけを持っていた）。
     *                 葉は種別と値の組ごとに 1 つなので、参照が同じなら頭も同じ。
     *                 値を読まない指定では常に {@link ValueStore#NONE}（値の判定はしない）
     */
    void assignment(FieldAssignFact j, int head) {
        String key = j.typeFqn() + "#" + j.fieldName();
        Field fd = fields.get(key);
        if (fd != null) {
            fd.assigns.add(new Assign(j.site(), head, j.kind()));
        } else if (j.kind() != Origin.PARAM) {
            foreignWrites.add(key);   // 宣言が別のブロックにある（よその書き込み）
        }
    }

    /**
     * 溜めた事実から判定し、確定したフィールドの値の頭の参照を fieldHeads に足して、溜めた事実を捨てる。
     *
     * @param ownValued      ソースが引数でない値を入れる、参照型の static でないフィールドの鍵を足す先
     *                       （クラスの説明。よその書き込みの鍵も足す）
     * @param ownValuedTypes ownValued のフィールドの宣言の型を、フィールドを宣言した型ごとに足す先（値を読まない
     *                       指定のときだけ。要らなければ null）。よその書き込みは宣言の型が分からないので {@link #ANY_TYPE}
     */
    void flushInto(Map<String, Integer> fieldHeads, Set<String> ownValued, Map<String, Set<String>> ownValuedTypes) {
        for (String key : foreignWrites) {
            ownValued.add(key);
            if (ownValuedTypes != null) {
                ownValuedTypes.computeIfAbsent(key.substring(0, key.indexOf('#')), k -> new HashSet<>()).add(ANY_TYPE);
            }
        }
        for (Map.Entry<String, Field> e : fields.entrySet()) {
            String key = e.getKey();
            String typeFqn = key.substring(0, key.indexOf('#'));
            if (ownValued(e.getValue())) {
                ownValued.add(key);
                if (ownValuedTypes != null) {
                    ownValuedTypes.computeIfAbsent(typeFqn, k -> new HashSet<>()).add(e.getValue().declType);
                }
            }
            if (!assignedOnEveryPath(typeFqn, e.getValue())) {
                continue;
            }
            int head = agreedHeadOf(typeFqn, e.getValue());
            if (head != ValueStore.NONE) {
                fieldHeads.put(key, head);
            }
        }
        fields.clear();
        rootCtors.clear();
        typesWithDelegatingCtor.clear();
        frameworkTypes.clear();
        foreignWrites.clear();
    }

    /**
     * 参照型で static でなく、値の頭が引数でない書き込み（値の分からない書き込みを含む）がある。
     * 引数を入れる書き込み（コンストラクタ注入・setter 注入の形）だけなら、コンテナが渡した値と読める
     */
    private static boolean ownValued(Field fd) {
        if (!fd.reference() || ModifierTokens.has(fd.mods, "static")) {
            return false;
        }
        for (Assign a : fd.assigns) {
            if (a.headKind() != Origin.PARAM) {
                return true;
            }
        }
        return false;
    }

    /** 条件 (a)〜(c)・(f) を満たすか（代入の値には依らない部分） */
    private boolean assignedOnEveryPath(String typeFqn, Field fd) {
        if (ModifierTokens.has(fd.mods, "static")
                || !(ModifierTokens.has(fd.mods, "private") || ModifierTokens.has(fd.mods, "final"))) {
            return false;   // (a)
        }
        if (fd.framework) {
            return false;   // (f)
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

    /** 条件 (d)・(e) を満たすなら、そのフィールドに必ず入る値の頭の葉の参照。満たさなければ {@link ValueStore#NONE} */
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
