// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import jche.cache.AnnotationTokens;
import jche.cache.FieldDeclFact;
import jche.cache.MethodDeclFact;
import jche.cache.Origin;
import jche.cache.TypeFact;

/**
 * DIコンテナ（Spring）のBean定義（読み手の判断）。H行・V行・D行・R行のアノテーションから、
 * 「この宣言型に実際に注入される具象型」を求める。
 *
 * <h2>なぜCHAでは足りないか</h2>
 * <pre>
 *   &#64;Autowired private UserRepository repo;   // 実装が2つ以上あるとCHAは候補を絞れない
 *   repo.find(id);
 * </pre>
 * CHA（クラス階層解析）は「UserRepository を実装する型すべて」を候補にする。
 * だがSpringのコンテナに載っているのはBeanとして登録された型だけで、
 * テスト用のスタブや手書きの実装は注入されない。Bean登録の事実を使えば、
 * 候補をコンテナが実際に注入しうる型だけに絞れる。
 *
 * <h2>Beanとみなす根拠（この判断の全て）</h2>
 * <ul>
 *   <li>ステレオタイプ注釈（&#64;Component / &#64;Service / &#64;Repository / &#64;Controller /
 *       &#64;RestController / &#64;Configuration / JSR-330 の &#64;Named / &#64;ManagedBean）が
 *       付いた具象型。単純名で照合するので、それらを合成した独自注釈は
 *       {@code spring.di.bean.annotations} に足せば同じ扱いになる</li>
 *   <li>&#64;Bean を付けたメソッドが {@code return new Impl();} の形で返す具象型
 *       （R行の値が全て同じ型の new）。&#64;Configuration クラスの定義を拾うため</li>
 * </ul>
 *
 * <h2>返す具象型が決まらない &#64;Bean メソッド</h2>
 * {@code return Impl.builder().build();}（ファクトリ・ビルダー）、{@code return p;}（引数）、
 * {@code return field;}、{@code return c ? new A() : new A();}、return が 2 通りの型、そして値を読まない指定
 * （{@code dataflow.enabled=false}。R 行を読まない）のときは、どの具象型が Bean になるか分からない。
 * 以前はそのメソッドを数えずに済ませていたので、ステレオタイプ注釈の Bean が 1 つだけ残り、段 5 がそれに
 * 絞って &#64;Bean の実装への呼び出しを黙って落としていた。今は、宣言した戻り値の型（D 行。
 * {@link MethodDeclFact#returnType}）とその部分型を「分からない Bean がなりうる型」として覚え、呼び出しの
 * 候補の型がそこに触れれば段 5 で絞らない（{@link #mayBeUndeterminedBean}。docs/value-safety-qa.md の Q17）。
 * 戻り値の型が分からない・{@code Object} なら、どの呼び出しでも絞らない。
 * Bean名は注釈の値（{@code @Service("userDao")}）、無ければ単純名の先頭を小文字にしたもの
 * （Springの既定の命名）。&#64;Bean メソッドはメソッド名。
 *
 * <h2>絞り込みに使う注入点の情報</h2>
 * フィールドに &#64;Qualifier / &#64;Resource(name) が付いていれば、そのBean名の型に絞る。
 * 付いていなければ型で絞る（候補のうちBeanが1つだけなら確定）。
 *
 * <h2>採らなかったこと（誤って絞る害の方が大きい）</h2>
 * <ul>
 *   <li>&#64;Primary / &#64;Profile / &#64;Conditional による選択。実行時の環境で変わるため、
 *       静的解析で1つに決めると誤る</li>
 *   <li>コンストラクタ・setter の引数に付いた &#64;Qualifier。引数のアノテーションは
 *       事実として持っていない（フィールド単位の注入点だけを見る）</li>
 *   <li>XML（applicationContext.xml）のBean定義。ソースの事実ではないため、
 *       必要なら拡張（{@link jche.extension.TypeCandidateProvider}）で差し込む</li>
 * </ul>
 */
public final class SpringBeans {

    /** Bean登録の印とみなす注釈の単純名（既定） */
    private static final List<String> DEFAULT_STEREOTYPES = List.of(
            "Component", "Service", "Repository", "Controller", "RestController",
            "Configuration", "ControllerAdvice", "RestControllerAdvice",
            "Named", "ManagedBean", "Singleton");
    /** &#64;Configuration クラスのBean定義メソッド */
    private static final String BEAN = "Bean";
    /** 注入点の印 */
    private static final List<String> INJECT_ANNOTATIONS = List.of("Autowired", "Inject", "Resource");
    private static final String QUALIFIER = "Qualifier";
    private static final String RESOURCE = "Resource";

    /** 空（DI解析を使わない）。判定は全て false / null を返す */
    public static final SpringBeans DISABLED = new SpringBeans(false, List.of());

    private final boolean enabled;
    private final List<String> stereotypes;

    /**
     * Bean登録された具象型 -> Bean名の集合。
     *
     * 同じ具象型が複数の名前で登録されうる（&#64;Bean メソッドが 2 つ同じ型を返す、
     * ステレオタイプ注釈と &#64;Bean の両方）。先勝ちで 1 つだけ持つと、どの名前が残るかが
     * キャッシュ上のブロックの並び（差分更新で末尾へ移る）に依存して、&#64;Qualifier の
     * 照合結果が実行ごとに変わる。集合で持てば順序に依らない
     */
    private final Map<String, Set<String>> beanNames = new LinkedHashMap<>();
    /** "typeFqn#fieldName" -> 注入時に指定されたBean名。指定が無ければ空文字 */
    private final Map<String, String> injectionPoints = new HashMap<>();
    /** &#64;Bean メソッドのID -> Bean名と宣言した戻り値の型。R行が読み終わってから型を確定する */
    private final Map<Integer, BeanMethod> beanMethods = new LinkedHashMap<>();
    /**
     * 返す具象型が決まらなかった &#64;Bean メソッドの Bean がなりうる型（宣言した戻り値の型とその部分型すべて。
     * クラスの説明「返す具象型が決まらない &#64;Bean メソッド」）
     */
    private final Set<String> undeterminedTypes = new HashSet<>();
    /** 返す具象型も宣言した戻り値の型も分からない（または {@code Object} の）&#64;Bean メソッドがあった */
    private boolean undeterminedAnyType;

    /** &#64;Bean メソッドの Bean 名と、宣言した戻り値の型（分からなければ空） */
    private record BeanMethod(String name, String returnType) {
    }

    private SpringBeans(boolean enabled, List<String> extraStereotypes) {
        this.enabled = enabled;
        List<String> all = new ArrayList<>(DEFAULT_STEREOTYPES);
        for (String extra : extraStereotypes) {
            String s = extra.trim();
            if (!s.isEmpty()) {
                all.add(AnnotationTokens.simpleNameOf(s));
            }
        }
        this.stereotypes = all;
    }

    /**
     * @param extraStereotypes 追加でBeanの印とみなす注釈（FQNでも単純名でもよい）。
     *                         独自のステレオタイプ注釈を使っているプロジェクト向け
     */
    public static SpringBeans of(boolean enabled, List<String> extraStereotypes) {
        return enabled ? new SpringBeans(true, extraStereotypes) : DISABLED;
    }

    public boolean enabled() {
        return enabled;
    }

    public int beanCount() {
        return beanNames.size();
    }

    // ------------------------------------------------------------
    // 事実の取り込み（CallGraphBuilder のスキャンから）
    // ------------------------------------------------------------

    void type(TypeFact t) {
        if (!enabled || t.kind() != TypeFact.CONCRETE || t.annotations().isEmpty()) {
            return;
        }
        for (String stereotype : stereotypes) {
            if (AnnotationTokens.has(t.annotations(), stereotype)) {
                String name = AnnotationTokens.valueOf(t.annotations(), stereotype);
                register(t.typeFqn(), (name == null || name.isEmpty()) ? defaultBeanName(t.typeFqn()) : name);
                return;
            }
        }
    }

    void field(FieldDeclFact v) {
        if (!enabled || v.annotations().isEmpty()) {
            return;
        }
        boolean injected = false;
        for (String inject : INJECT_ANNOTATIONS) {
            injected |= AnnotationTokens.has(v.annotations(), inject);
        }
        if (!injected) {
            return;
        }
        String name = AnnotationTokens.valueOf(v.annotations(), QUALIFIER);
        if (name == null) {
            name = AnnotationTokens.valueOf(v.annotations(), RESOURCE);
        }
        injectionPoints.put(v.typeFqn() + "#" + v.fieldName(), (name == null) ? "" : name);
    }

    /** &#64;Bean メソッドを覚えておく（返す具象型は R行が揃ってから {@link #resolveBeanMethods} で決める） */
    void method(int methodId, MethodDeclFact d) {
        if (enabled && AnnotationTokens.has(d.annotations(), BEAN)) {
            String name = AnnotationTokens.valueOf(d.annotations(), BEAN);
            beanMethods.put(methodId, new BeanMethod((name == null || name.isEmpty()) ? d.ref().name() : name,
                    d.returnType()));
        }
    }

    /**
     * &#64;Bean メソッドの戻り値（値の表の参照。{@link CallGraph#returnAt}）から、そのメソッドが登録する
     * 具象型を決める。返しうる値が全て同じ {@code new 具象型} のときだけ採る（分岐して複数の型を
     * 返しうるメソッドは、どれが登録されるか静的には決まらない）。型が同じかは値の番号で比べる
     * （同じ中身の文字列は同じ番号）。
     *
     * <p>コンテナは設定クラスのインスタンスで &#64;Bean メソッドを呼ぶので、部分型の設定クラスがその
     * メソッドを上書きしていれば（&#64;Bean を付け直していなくても）、動くのは上書きした本体で、登録されるのは
     * その本体が返す型になる。宣言の本体が返す型だけを登録すると、上書きした本体の型が Bean に数えられず、
     * 段 5 がもう一方の Bean へ誤って絞る。そこで上書きした本体（{@link CallGraph#overridingImplementations}）
     * が返す型も同じ名前で登録する。Bean を多く数える側は絞り込みを減らすだけで、呼び出しを落とさない。
     *
     * <p>返す具象型が決まらない本体（値を読まないときは全部）は、宣言した戻り値の型の「分からない Bean」として
     * 数える（{@link #undetermined}）。上書きした本体の戻り値の型は D 行に無いこと（&#64;Bean を付け直して
     * いなければアノテーションが無い）があるので、上書きされた宣言の型を使う（上書きした本体の型はその部分型で、
     * 広い側に倒す）
     */
    void resolveBeanMethods(CallGraph graph) {
        for (Map.Entry<Integer, BeanMethod> e : beanMethods.entrySet()) {
            int methodId = e.getKey();
            BeanMethod bean = e.getValue();
            if (!registerReturnedType(graph, methodId, bean.name())) {
                undetermined(graph, bean.returnType());
            }
            if (graph.hasOverriders(methodId)) {
                IntArray overriding = graph.overridingImplementations(methodId);
                for (int i = 0; i < overriding.size(); i++) {
                    if (!registerReturnedType(graph, overriding.get(i), bean.name())) {
                        undetermined(graph, bean.returnType());
                    }
                }
            }
        }
        beanMethods.clear();
    }

    /** 返す具象型が決まらない &#64;Bean メソッドの Bean を、宣言した戻り値の型とその部分型の「どれか」として覚える */
    private void undetermined(CallGraph graph, String returnType) {
        if (returnType.isEmpty() || "java.lang.Object".equals(returnType)) {
            // H 行の親型は java.lang.Object を含まないので、部分型を引けない。どの型にもなりうる
            undeterminedAnyType = true;
        } else if (undeterminedTypes.add(returnType)) {
            undeterminedTypes.addAll(graph.hierarchy.transitiveSubtypes(returnType));
        }
    }

    /**
     * メソッドの return がどれも同じ {@code new 具象型} なら、その型を Bean として登録する。
     *
     * @return 登録した（返す具象型が決まった）か。return が無い（値を読まない指定を含む）・new 以外の値がある・
     *         型が 2 通り以上なら false
     */
    private boolean registerReturnedType(CallGraph graph, int methodId, String beanName) {
        ValueStore values = graph.values();
        int count = graph.returnCount(methodId);
        int type = -1;
        for (int k = 0; k < count; k++) {
            int ref = graph.returnAt(methodId, k);
            if (values.kind(ref) != Origin.NEW
                    || (type >= 0 && type != values.valueId(ref))) {
                type = -1;
                break;
            }
            type = values.valueId(ref);
        }
        String fqn = (type < 0) ? "" : values.strings().get(type);
        if (fqn.isEmpty()) {
            return false;
        }
        register(fqn, beanName);
        return true;
    }

    private void register(String typeFqn, String beanName) {
        beanNames.computeIfAbsent(typeFqn, k -> new LinkedHashSet<>(2)).add(beanName);
    }

    // ------------------------------------------------------------
    // 問い合わせ（CallResolver から）
    // ------------------------------------------------------------

    public boolean isBean(String typeFqn) {
        return beanNames.containsKey(typeFqn);
    }

    /**
     * 候補の型（呼び出しを修飾する型とその部分型）のどれかが、返す具象型の決まらなかった &#64;Bean メソッドの
     * Bean でありうるか。ありうるなら、コンテナはその Bean を注入しうるので段 5 で絞ってはいけない。
     * 候補の型が「分からない Bean」の宣言した戻り値の型そのものかその部分型なら、実際の Bean はその型か
     * 部分型で、候補の型に代入できる場合がある
     */
    public boolean mayBeUndeterminedBean(Collection<String> candidateTypes) {
        if (undeterminedAnyType) {
            return true;
        }
        if (undeterminedTypes.isEmpty()) {
            return false;
        }
        for (String type : candidateTypes) {
            if (undeterminedTypes.contains(type)) {
                return true;
            }
        }
        return false;
    }

    /**
     * その注入点で指定されたBean名（&#64;Qualifier / &#64;Resource(name)）。
     * 指定が無い、または注入点でなければ null
     */
    public String qualifierOf(String fieldKey) {
        String name = injectionPoints.get(fieldKey);
        return (name == null || name.isEmpty()) ? null : name;
    }

    /** その型がそのBean名で登録されているか。Bean登録されていない型なら false */
    public boolean hasBeanName(String typeFqn, String beanName) {
        Set<String> names = beanNames.get(typeFqn);
        return names != null && names.contains(beanName);
    }

    /** Bean名の既定（単純名の先頭を小文字にする。Springの既定の命名） */
    private static String defaultBeanName(String typeFqn) {
        String simple = AnnotationTokens.simpleNameOf(typeFqn);
        if (simple.isEmpty()) {
            return simple;
        }
        // 先頭2文字が大文字なら（URLShortener 等）Springは小文字化しない
        if (simple.length() > 1 && Character.isUpperCase(simple.charAt(1))) {
            return simple;
        }
        return simple.substring(0, 1).toLowerCase(Locale.ROOT) + simple.substring(1);
    }
}
