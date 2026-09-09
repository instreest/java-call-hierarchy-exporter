// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
 *       （R行の出所が全て同じ {@code T:FQN}）。&#64;Configuration クラスの定義を拾うため</li>
 * </ul>
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

    /** Bean登録された具象型 -> Bean名 */
    private final Map<String, String> beanNames = new LinkedHashMap<>();
    /** "typeFqn#fieldName" -> 注入時に指定されたBean名。指定が無ければ空文字 */
    private final Map<String, String> injectionPoints = new HashMap<>();
    /** &#64;Bean メソッドのID -> Bean名。R行が読み終わってから型を確定する */
    private final Map<Integer, String> beanMethods = new LinkedHashMap<>();

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
    // 事実の取り込み（CallGraphBuilder の1回目のスキャンから）
    // ------------------------------------------------------------

    void type(TypeFact t) {
        if (!enabled || t.kind() != TypeFact.CONCRETE || t.annotations().isEmpty()) {
            return;
        }
        for (String stereotype : stereotypes) {
            if (AnnotationTokens.has(t.annotations(), stereotype)) {
                String name = AnnotationTokens.valueOf(t.annotations(), stereotype);
                beanNames.putIfAbsent(t.typeFqn(),
                        (name == null || name.isEmpty()) ? defaultBeanName(t.typeFqn()) : name);
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
            beanMethods.put(methodId, (name == null || name.isEmpty()) ? d.ref().name() : name);
        }
    }

    /**
     * &#64;Bean メソッドの戻り値の出所から、そのメソッドが登録する具象型を決める。
     * 返しうる出所が全て同じ {@code new 具象型} のときだけ採る（分岐して複数の型を
     * 返しうるメソッドは、どれが登録されるか静的には決まらない）
     */
    void resolveBeanMethods(CallGraph graph) {
        for (Map.Entry<Integer, String> e : beanMethods.entrySet()) {
            String[] origins = graph.returnOriginsOf(e.getKey());
            if (origins == null || origins.length == 0) {
                continue;
            }
            String type = null;
            for (String origin : origins) {
                if (Origin.kindOf(origin) != Origin.NEW) {
                    type = null;
                    break;
                }
                String fqn = Origin.valueOf(Origin.head(origin));
                if (type != null && !type.equals(fqn)) {
                    type = null;
                    break;
                }
                type = fqn;
            }
            if (type != null && !type.isEmpty()) {
                beanNames.putIfAbsent(type, e.getValue());
            }
        }
        beanMethods.clear();
    }

    // ------------------------------------------------------------
    // 問い合わせ（CallResolver から）
    // ------------------------------------------------------------

    public boolean isBean(String typeFqn) {
        return beanNames.containsKey(typeFqn);
    }

    /**
     * その注入点で指定されたBean名（&#64;Qualifier / &#64;Resource(name)）。
     * 指定が無い、または注入点でなければ null
     */
    public String qualifierOf(String fieldKey) {
        String name = injectionPoints.get(fieldKey);
        return (name == null || name.isEmpty()) ? null : name;
    }

    /** Bean名が一致するか。Bean登録されていない型なら false */
    public boolean hasBeanName(String typeFqn, String beanName) {
        return beanName.equals(beanNames.get(typeFqn));
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
