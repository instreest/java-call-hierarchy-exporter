// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.IAnnotationBinding;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMemberValuePairBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.Modifier;

import jche.cache.AnnotationTokens;
import jche.cache.FileAnalysis;
import jche.cache.MethodRef;

/**
 * JDTのバインディングから、キャッシュに書く名前（型名・メソッドの4つ組・修飾子）を作る。
 *
 * 名前を求めた型は「このファイルの解決結果が依存する型」として {@link FileAnalysis#referencedTypes}
 * に記録する（I行の元）。差分更新で、依存先のファイルが変わったときに再解析するため。
 *
 * <h2>同じ型を何度も名前にしない</h2>
 * {@link #typeNameOf} は AST の走査中に極めて多く呼ばれる（フィールド参照1つ、実引数1つごとに
 * 呼ばれる）。{@code ITypeBinding.getQualifiedName()} はそのたびに名前を組み立てるため、
 * 同じ型について何度も同じ文字列を作り直すことになる。JDT のバインディングは1回のパースの中で
 * 同じ型なら同じインスタンスなので、<b>インスタンスの同一性</b>で結果を覚えておく
 * （{@link #typeNames}）。この表はファイル1件ぶんの寿命しかなく（{@link FactVisitor} が
 * ファイルごとに作る）、解析が終われば {@link FileAnalysis} ごと捨てられる。
 */
final class BindingNames {

    /** {@link #typeNames} で「名前が取れなかった」を表す印（Map は null を「未登録」と区別できないため） */
    private static final String NO_NAME = "";

    private final FileAnalysis out;
    /**
     * 型のバインディング -> 識別名。1ファイルの解析の間だけ持つ。
     * equals ではなくインスタンスの同一性で引くのは、{@code ITypeBinding.equals} が
     * 名前の比較まで行うため（覚える目的に対して高くつく）
     */
    private final Map<ITypeBinding, String> typeNames = new IdentityHashMap<>();

    BindingNames(FileAnalysis out) {
        this.out = out;
    }

    /** 消去型（ジェネリクスの型引数を落とした型）。取れなければそのまま */
    static ITypeBinding erasureOf(ITypeBinding t) {
        ITypeBinding erased = t.getErasure();
        return (erased != null) ? erased : t;
    }

    static String packageOf(ITypeBinding t) {
        return (t.getPackage() != null) ? t.getPackage().getName() : "";
    }

    /**
     * 修飾子の語を組み立て済みで持っておく表。添字は {@link #modifierIndex} が作る 7 ビット。
     *
     * 修飾子は「public」「private,static,final」のように取りうる組み合わせが高々 128 通りしかないのに、
     * メソッド宣言1件・フィールド参照1件ごとに同じ文字列を作り直していた。組み立て済みのものを
     * 共有すれば、作り直しの手間も、同じ内容の文字列が事実（D行・A行）の数だけヒープに残るのも無くなる。
     * 中身は不変な文字列で、同じ添字には必ず同じ内容が入るため、埋める順序は結果に影響しない
     */
    private static final String[] MODIFIER_CACHE = new String[128];

    /** {@link #MODIFIER_CACHE} の添字。関係する修飾子ビットだけを詰めた 7 ビット */
    private static int modifierIndex(int modifiers) {
        int index = 0;
        if (Modifier.isPublic(modifiers)) {
            index |= 1;
        }
        if (Modifier.isProtected(modifiers)) {
            index |= 1 << 1;
        }
        if (Modifier.isPrivate(modifiers)) {
            index |= 1 << 2;
        }
        if (Modifier.isStatic(modifiers)) {
            index |= 1 << 3;
        }
        if (Modifier.isFinal(modifiers)) {
            index |= 1 << 4;
        }
        if (Modifier.isAbstract(modifiers)) {
            index |= 1 << 5;
        }
        if (Modifier.isDefault(modifiers)) {
            index |= 1 << 6;
        }
        return index;
    }

    /** 修飾子ビットをカンマ区切りの語に落とす（{@link jche.cache.ModifierTokens} の語彙） */
    static String modifiersOf(int modifiers) {
        int index = modifierIndex(modifiers);
        String cached = MODIFIER_CACHE[index];
        if (cached != null) {
            return cached;
        }
        String built = buildModifiers(modifiers);
        MODIFIER_CACHE[index] = built;
        return built;
    }

    private static String buildModifiers(int modifiers) {
        StringBuilder sb = new StringBuilder();
        appendIf(sb, Modifier.isPublic(modifiers), "public");
        appendIf(sb, Modifier.isProtected(modifiers), "protected");
        appendIf(sb, Modifier.isPrivate(modifiers), "private");
        appendIf(sb, Modifier.isStatic(modifiers), "static");
        appendIf(sb, Modifier.isFinal(modifiers), "final");
        appendIf(sb, Modifier.isAbstract(modifiers), "abstract");
        appendIf(sb, Modifier.isDefault(modifiers), "default");
        return sb.toString();
    }

    /**
     * 宣言に付いていたアノテーション（{@link jche.cache.AnnotationTokens} の形）。
     *
     * 収集の打ち切り（書き手の判断）: {@code java.lang} のアノテーション（Override、
     * SuppressWarnings 等）は、どの型が動くかに一切関わらないうえ全メソッドに付きうるので落とす。
     * 値は単一メンバか value / name が文字列のものだけ残す。DIコンテナが Bean を
     * 見分けるのに使うのは名前の文字列だけのため。
     *
     * <p>値には注釈のメンバの<b>既定値</b>も含まれる（{@code @Ann} だけ書いても
     * {@code String value() default "svc"} の "svc" が入る）ので、注釈の宣言を変えるとここの結果も変わる。
     * 注釈の型は、このファイルに書かれたアノテーションの節として I 行に載る（{@link FactVisitor#preVisit2}）。
     */
    String annotationsOf(IBinding binding) {
        if (binding == null) {
            return "";
        }
        IAnnotationBinding[] annotations = binding.getAnnotations();
        if (annotations == null || annotations.length == 0) {
            return "";
        }
        List<String> tokens = new ArrayList<>(annotations.length);
        for (IAnnotationBinding a : annotations) {
            ITypeBinding type = a.getAnnotationType();
            if (type == null) {
                continue;
            }
            String fqn = type.getQualifiedName();
            if (fqn == null || fqn.isEmpty() || fqn.startsWith("java.lang.")) {
                continue;
            }
            tokens.add(AnnotationTokens.token(fqn, stringMemberOf(a)));
        }
        return AnnotationTokens.join(tokens);
    }

    /** アノテーションの値。単一メンバ、または value / name が文字列のもの。無ければ null */
    private static String stringMemberOf(IAnnotationBinding a) {
        IMemberValuePairBinding[] pairs = a.getAllMemberValuePairs();
        if (pairs == null || pairs.length == 0) {
            return null;
        }
        String single = null;
        for (IMemberValuePairBinding pair : pairs) {
            if (!(pair.getValue() instanceof String value) || value.isEmpty()) {
                continue;
            }
            String name = pair.getName();
            if ("value".equals(name) || "name".equals(name)) {
                return value;
            }
            if (pairs.length == 1) {
                single = value;
            }
        }
        return single;
    }

    private static void appendIf(StringBuilder sb, boolean condition, String token) {
        if (condition) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(token);
        }
    }

    /**
     * 型の識別名。取れなければ null。
     *
     * 匿名クラスは getQualifiedName() が空文字を返す。以前はそこでスキップしていたため、
     * 匿名クラスによるオーバーライドと、その内部の呼び出しが丸ごと欠落していた。
     * JDTは匿名クラスにも識別子を持っているので順に切り替えて拾う。
     */
    String typeNameOf(ITypeBinding t) {
        if (t == null) {
            return null;
        }
        String known = typeNames.get(t);
        if (known != null) {
            // 依存（I行）への記録は最初に名前を求めたときに済んでいる
            return known.isEmpty() ? null : known;
        }
        String name = resolveTypeName(t);
        typeNames.put(t, (name == null) ? NO_NAME : name);
        if (name != null && !t.isFromSource() && !t.isPrimitive() && !t.isArray() && !t.isTypeVariable()
                && !isJdk(t)) {
            noteBinarySupertypes(t);
        }
        return name;
    }

    /**
     * jar の型（ソースの無い、{@code java.*} でない型）の親型を、推移的に I 行に数える（{@code java.*} の型で止める）。
     *
     * <p>jar の型のメンバーは、その親型（別の jar の型のこともある）から継承したものを含む。親型の jar が変わると、
     * この型を使うファイルの事実（どのオーバーロードが選ばれるか）も変わるが、ソースに書かれているのはこの型だけで、
     * 差分更新は変わった jar のパッケージに当たる型を I 行に持つファイルしか解析し直さない。ソースの型の親の変化は
     * 部分型の索引（H 行）で届くが、jar の型には H 行が無い（docs/cache-unification-qa.md の Q86）。
     * 名前にした型は {@link #typeNames} に先に入れてあるので、親をたどっても同じ型を 2 度見ない
     */
    private void noteBinarySupertypes(ITypeBinding t) {
        ITypeBinding superclass = t.getSuperclass();
        if (superclass != null && !isJdk(superclass)) {
            typeNameOf(erasureOf(superclass));
        }
        for (ITypeBinding i : t.getInterfaces()) {
            if (!isJdk(i)) {
                typeNameOf(erasureOf(i));
            }
        }
    }

    /** {@link #typeNameOf} の本体（覚えていない型のときだけ通る） */
    private String resolveTypeName(ITypeBinding t) {
        String n = t.getQualifiedName();
        if (n == null || n.isEmpty()) {
            n = t.getBinaryName();               // 例: jp.co.xxx.Outer$1
        }
        if (n == null || n.isEmpty()) {
            String key = t.getKey();             // JDT内部の一意キー（最終手段）
            // キャッシュはタブ区切りのため、空白類が混ざると形式が壊れる
            n = (key == null) ? null : key.replaceAll("\\s", "_");
        }
        if (n == null || n.isEmpty()) {
            return null;
        }
        // ここを通った型は、このファイルの解決結果がそれに依存している（I行）。
        // 自分が宣言する型も混ざるが、書き出し時に除く
        if (!t.isPrimitive() && !t.isArray() && !t.isTypeVariable()) {
            out.referencedTypes.add(n);
        }
        return n;
    }

    /** 宣言型のFQN（消去型）。配列は要素型に "[]" を付ける。プリミティブはそのまま。取れなければ空 */
    String declTypeName(ITypeBinding t) {
        if (t == null) {
            return "";
        }
        if (t.isArray()) {
            return declTypeName(t.getComponentType()) + "[]";
        }
        if (t.isPrimitive()) {
            return t.getName();
        }
        String n = typeNameOf(erasureOf(t));
        return (n == null) ? "" : n;
    }

    /** 型を「このファイルの解決結果が依存する型」（I行）に数える。配列は要素型で */
    void noteDependency(ITypeBinding t) {
        while (t != null && t.isArray()) {
            t = t.getComponentType();
        }
        if (t == null || t.isPrimitive() || t.isTypeVariable()) {
            return;
        }
        typeNameOf(erasureOf(t));
    }

    /**
     * 式・型の節の型を I 行に数える（{@link FactVisitor#preVisit2}）。{@link #noteDependency} と違い、型変数・
     * 捕捉された型変数（{@code Box<?>} の {@code b.get()} の型）・ワイルドカード・交差型は飛ばさず、上限の消去で
     * 数える（上限が複数なら全部）。その型の名前はソースに無いことがあり（{@code a.getB().x()} の B、
     * {@code D extends E<Foo>} の {@code d.get()} の Foo）、数えないと、その型にメンバーを足す・親を変えても差分更新が
     * このファイルを解析し直さない（docs/cache-unification-qa.md の Q50）。型引数も数える（{@code List<Foo>} の Foo。
     * 上限と同じく {@link #MAX_BOUND_DEPTH} 段まで。Q85）。
     */
    void noteReachedType(ITypeBinding t) {
        noteReachedType(t, 0, true);
    }

    /**
     * 呼び出したメソッド・コンストラクタの throws の型を I 行に数える（型変数は上限の消去）。
     *
     * <p>その例外が検査例外かどうか（JLS 11.1.1。親を {@code RuntimeException} から {@code Exception} に変えた）で、
     * 呼び出し側が「例外を処理していない」エラーになるかが変わる（11.2.3）。throws の型は呼び出し側のソースに
     * 書かれていないことが多く、ほかの経路では I 行に載らない（docs/cache-unification-qa.md の Q52）。
     * {@code java.*} の例外（{@code IOException} など）は数えない（JDK の版はキャッシュの鍵に入っていて、変われば
     * 全件解析になる）
     */
    void noteThrownTypes(IMethodBinding b) {
        if (b == null) {
            return;
        }
        for (ITypeBinding e : b.getExceptionTypes()) {
            if (e.isTypeVariable() || !isJdk(e)) {
                noteReachedType(e, 0, true);
            }
        }
    }

    /** {@link #noteCandidates} で見終えた「型のキー#名前」（同じファイルで同じ型・同じ名前を 2 度辿らない） */
    private final Set<String> candidatesSeen = new HashSet<>();

    /**
     * 呼び出しの候補（JLS 15.12.2）の引数の型を I 行に数える。{@code searched}（探す型）とその推移的な親型が宣言する、
     * 名前が {@code name} のメソッド（{@code name} が null ならコンストラクタ。探す型が宣言するものだけ）すべての
     * 引数の型（型変数は上限の消去。型引数も数える）。探す型は型引数を付けたまま辿り（{@code Box<Foo>} の
     * {@code put(T)} は {@code put(Foo)}）、{@code java.*} の型が宣言するものは、型引数を置き換えた引数の型のうち
     * {@code java.*} でない型だけを数える（{@code S extends ArrayList<Foo>} の {@code s.add(0, null)} の
     * {@code add(int, Foo)}。JDK の版は鍵に入っているので、JDK の型そのものは数えない。docs/cache-unification-qa.md の Q85）。
     *
     * <p>どのオーバーロードが選ばれるか（選べないか）は、選ばれなかった候補の引数の型にも依る。{@code x.n(null)} は
     * {@code n(Y)} と {@code n(Z)} で曖昧だが、Z を消すと {@code n(Y)} に決まる。ラムダを渡す {@code x.k(() -> {})} は、
     * 候補の関数型インターフェース（{@code k(Fn)} の Fn）の形が変わると選ばれる候補が変わる。選ばれた候補の引数の型は
     * 呼び出し先の鍵として数える（{@link #toRef}）が、選ばれなかった候補の型はこのファイルのどこにも現れない
     * （docs/cache-unification-qa.md の Q78）
     */
    void noteCandidates(ITypeBinding searched, String name) {
        if (searched == null) {
            return;
        }
        if (searched.isIntersectionType() || searched.isTypeVariable() || searched.isCapture()) {
            for (ITypeBinding b : searched.getTypeBounds()) {
                noteCandidates(b, name);
            }
            return;
        }
        ITypeBinding type = searched;
        if (type.isArray() || type.isPrimitive() || !candidatesSeen.add(keyOf(type) + '#' + name)) {
            return;
        }
        List<ITypeBinding> types = new ArrayList<>();
        types.add(type);
        if (name != null) {
            types.addAll(supertypesOf(type));
        }
        for (ITypeBinding t : types) {
            // java.* の型が宣言する候補は、型引数を置き換えた引数の型（ArrayList<Foo> の add(int, E) の Foo）だけを見る
            boolean jdk = isJdk(t);
            if (jdk && !t.isParameterizedType()) {
                continue;
            }
            for (IMethodBinding m : t.getDeclaredMethods()) {
                if (name == null ? m.isConstructor() : (!m.isConstructor() && name.equals(m.getName()))) {
                    for (ITypeBinding p : m.getParameterTypes()) {
                        noteReachedType(p, 0, !jdk);
                    }
                }
            }
        }
    }

    /** 型を I 行に数える。ただし {@code java.*} の型は数えない（JDK の型は JDK の版で決まり、版はキャッシュの鍵に入っている） */
    void noteDependencyUnlessJdk(ITypeBinding t) {
        if (t != null && !isJdk(t)) {
            noteDependency(t);
        }
    }

    /** {@code java.*} の型か（消去で見る） */
    private static boolean isJdk(ITypeBinding t) {
        String p = packageOf(erasureOf(t));
        return p.equals("java") || p.startsWith("java.");
    }

    /** 上限を辿る深さの上限（{@code T extends Comparable<T>} のような自己参照でも止まるように） */
    private static final int MAX_BOUND_DEPTH = 8;

    /**
     * @param jdkToo {@code java.*} の型も数えるか。false なら {@code java.*} でない型だけを数える（型引数と上限は辿る）
     */
    private void noteReachedType(ITypeBinding t, int depth, boolean jdkToo) {
        while (t != null && t.isArray()) {
            t = t.getComponentType();
        }
        if (t == null || t.isPrimitive() || t.isNullType() || depth > MAX_BOUND_DEPTH) {
            return;
        }
        if (t.isWildcardType()) {
            noteReachedType(t.getBound(), depth + 1, jdkToo);
            return;
        }
        if (t.isTypeVariable() || t.isCapture() || t.isIntersectionType()) {
            ITypeBinding[] bounds = t.getTypeBounds();
            if (bounds != null) {
                for (ITypeBinding b : bounds) {
                    noteReachedType(b, depth + 1, jdkToo);
                }
            }
            if (t.isCapture()) {
                noteReachedType(t.getWildcard(), depth + 1, jdkToo);
            }
            return;
        }
        if (jdkToo || !isJdk(t)) {
            typeNameOf(erasureOf(t));
        }
        if (t.isParameterizedType() && (!jdkToo || argumentsSeen.add(t))) {
            // 型引数も数える（List<Foo> の Foo）。a.foos() の型 List<Foo> の Foo は、ソースに名前が無いことがあり、
            // Foo の親を変えると for (Bar b : a.foos()) や m(Collection<? extends Bar>) の解決が変わる（Q85）
            for (ITypeBinding a : t.getTypeArguments()) {
                noteReachedType(a, depth + 1, jdkToo);
            }
        }
    }

    /**
     * {@code java.*} の型も数えて型引数を辿り終えた型（{@link #noteReachedType}）。同じ型の式は何度も現れるので、
     * 2 度目からは辿らない
     */
    private final Set<ITypeBinding> argumentsSeen = Collections.newSetFromMap(new IdentityHashMap<>());

    /** メソッドの4つ組。宣言型か引数型の名前が取れなければ null */
    MethodRef toRef(IMethodBinding binding) {
        if (binding == null) {
            return null;
        }
        // ジェネリクスの実体化された型ではなく宣言側を基準にする
        IMethodBinding decl = binding.getMethodDeclaration();
        if (decl == null) {
            return null;
        }
        ITypeBinding type = decl.getDeclaringClass();
        if (type == null) {
            return null;
        }
        ITypeBinding erased = type.getErasure();
        if (erased == null) {
            return null;
        }
        String typeFqn = typeNameOf(erased);
        if (typeFqn == null) {
            return null;
        }

        StringBuilder params = new StringBuilder();
        ITypeBinding[] paramTypes = decl.getParameterTypes();
        if (paramTypes != null) {
            for (int i = 0; i < paramTypes.length; i++) {
                if (i > 0) {
                    params.append(",");
                }
                ITypeBinding erasedParam = paramTypes[i].getErasure();
                params.append(erasedParam != null
                        ? erasedParam.getQualifiedName() : paramTypes[i].getQualifiedName());
                // 引数型はメソッドキーの一部。改名されるとキーが変わるので依存に数える
                noteDependency(erasedParam != null ? erasedParam : paramTypes[i]);
            }
        }
        String name = decl.isConstructor() ? MethodRef.CONSTRUCTOR : decl.getName();
        return new MethodRef(packageOf(erased), typeFqn, name, params.toString());
    }

    /**
     * その宣言が上書きしている、親型の宣言のキー（{@code typeFqn#name(paramSig)}）。
     * シグネチャ（{@code name(paramSig)}）が自分と同じものは含めない
     * （キーの照合だけで引けるため）。無ければ空。
     *
     * <h4>なぜ要るのか</h4>
     * メソッドのキーは消去済みの引数型で作るが、JLS 8.4.2 のオーバーライドは
     * 「同じシグネチャ<b>または</b>消去したシグネチャと同じ（サブシグネチャ）」なので、
     * 型引数を具体化した実装（{@code class UserRepo implements Repo<User>} の
     * {@code save(User)}）は親（{@code Repo#save(java.lang.Object)}）とキーが一致しない。
     * 一致しないまま候補を引くと「実装が無い」や「別の実装1件に確定」になる
     * （{@code docs/jls-conformance-qa.md} の Q1。ジェネリックなメソッドの上書き、Issue #154）。
     *
     * <h4>判定は JDT に任せる</h4>
     * {@code IMethodBinding.overrides} は JLS 8.4.8.1 の実装なので、アクセス修飾子・
     * 静的メソッドの隠蔽・型変数の置換の扱いを自前で組み直さない。親型は<b>型引数を
     * 具体化したまま</b>（{@code Repo<User>}）辿る。消去してから辿ると置換が失われ、
     * 判定そのものが成り立たない。
     */
    List<String> overriddenKeysOf(IMethodBinding binding) {
        if (binding == null || binding.isConstructor()
                || Modifier.isStatic(binding.getModifiers())
                || Modifier.isPrivate(binding.getModifiers())) {
            // コンストラクタ・static・private は上書きされない（JLS 8.4.8.1）
            return List.of();
        }
        ITypeBinding declaring = binding.getDeclaringClass();
        if (declaring == null) {
            return List.of();
        }
        MethodRef self = toRef(binding);
        if (self == null) {
            return List.of();
        }
        String selfSignature = self.signature();
        List<String> keys = new ArrayList<>(1);
        Set<String> seen = new HashSet<>();
        for (ITypeBinding sup : supertypesOf(declaring)) {
            for (IMethodBinding candidate : sup.getDeclaredMethods()) {
                if (candidate.isConstructor()
                        || !candidate.getName().equals(binding.getName())
                        || candidate.getParameterTypes().length != binding.getParameterTypes().length
                        || !binding.overrides(candidate)) {
                    continue;
                }
                MethodRef ref = toRef(candidate);
                if (ref == null) {
                    continue;
                }
                // シグネチャ（name(paramSig)）が同じ上書きは書かない。読み手は候補を
                // 「型FQN + '#' + シグネチャ」で引き、その型から親へ辿るので、
                // シグネチャが同じならキーの照合だけで引ける。書いても嵩むだけである。
                // 書くのは型引数の置換でシグネチャが食い違う場合だけ
                String key = ref.key();
                if (!ref.signature().equals(selfSignature) && seen.add(key)) {
                    keys.add(key);
                }
            }
        }
        return keys;
    }

    /**
     * クラス {@code type} で、親クラスから継承したメソッドが親インターフェースのメソッドを実装していて、
     * 両者のキー（消去した引数型）が食い違うものの組（{@code 実装される側のキー>実装する側のキー}。名前順）。
     * {@link jche.cache.TypeFact#inheritedImpls()} に書く。
     *
     * <pre>
     *   interface Repo&lt;T&gt; { void save(T t); }                      Repo#save(java.lang.Object)
     *   class BaseRepo { public void save(User u) {...} }              BaseRepo#save(p.User)
     *   class UserRepo extends BaseRepo implements Repo&lt;User&gt; { }   ← BaseRepo.save が Repo.save を実装する
     * </pre>
     * BaseRepo は Repo を実装していないので、BaseRepo.save の O 行（{@link #overriddenKeysOf}）には現れない。
     * 実装の関係は UserRepo から見たときにだけ成り立ち（JLS 8.4.8.1。javac は UserRepo にブリッジを作る）、
     * {@code class Other extends BaseRepo implements Repo<Order>} では成り立たないので、型ごとの事実にする。
     * 無いと読み手は UserRepo の {@code save(Object)} の実装を見つけられず、{@code Repo<User> r; r.save(u)} を
     * 「実装なし」にして BaseRepo.save を落とす（default があれば default に決めてしまう）。
     *
     * <p>判定は JDT に任せる: 親インターフェースのメソッド（型引数を置き換えたもの。private・static は除く）ごとに、
     * {@code type} 自身が同じシグネチャ（{@code isSubsignature}）を宣言していなければ、親クラスを近い順に見て
     * 最初に {@code isSubsignature} の当たる宣言（static・private を除く）を採る（クラスのメソッドが勝つ。JLS 8.4.8）。
     * キーが同じなら読み手はキーの照合で引けるので書かない。実装する側の型が実装される側のインターフェースを
     * 実装していれば、その宣言の O 行が同じことを言うので書かない
     */
    List<String> inheritedImplementationsOf(ITypeBinding type) {
        if (type == null || type.isInterface() || type.getSuperclass() == null) {
            return List.of();
        }
        List<IMethodBinding> interfaceMethods = new ArrayList<>();
        ArrayDeque<ITypeBinding> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        for (ITypeBinding c = type; c != null && seen.add(keyOf(c)); c = c.getSuperclass()) {
            queue.addAll(java.util.Arrays.asList(c.getInterfaces()));
        }
        while (!queue.isEmpty()) {
            ITypeBinding i = queue.poll();
            if (!seen.add(keyOf(i))) {
                continue;
            }
            for (IMethodBinding m : i.getDeclaredMethods()) {
                if (!m.isConstructor() && !Modifier.isStatic(m.getModifiers())
                        && !Modifier.isPrivate(m.getModifiers())) {
                    interfaceMethods.add(m);
                }
            }
            queue.addAll(java.util.Arrays.asList(i.getInterfaces()));
        }
        java.util.TreeSet<String> out = new java.util.TreeSet<>();
        for (IMethodBinding mi : interfaceMethods) {
            if (subsignatureIn(type, mi) != null) {
                continue;   // その型自身の宣言が実装する（キーか O 行で引ける）
            }
            Set<String> classes = new HashSet<>();
            for (ITypeBinding sc = type.getSuperclass(); sc != null && classes.add(keyOf(sc));
                    sc = sc.getSuperclass()) {
                IMethodBinding mc = subsignatureIn(sc, mi);
                if (mc == null) {
                    continue;
                }
                MethodRef implemented = toRef(mi);
                MethodRef implementing = toRef(mc);
                if (implemented != null && implementing != null
                        && !implemented.signature().equals(implementing.signature())
                        && !isSupertypeOf(mi.getDeclaringClass(), mc.getDeclaringClass())) {
                    out.add(implemented.key() + ">" + implementing.key());
                }
                break;
            }
        }
        return out.isEmpty() ? List.of() : new ArrayList<>(out);
    }

    /** 型 {@code t} が宣言する、{@code mi} の subsignature（JLS 8.4.2）のインスタンスメソッド（private を除く）。無ければ null */
    private static IMethodBinding subsignatureIn(ITypeBinding t, IMethodBinding mi) {
        for (IMethodBinding m : t.getDeclaredMethods()) {
            if (!m.isConstructor() && m.getName().equals(mi.getName())
                    && m.getParameterTypes().length == mi.getParameterTypes().length
                    && !Modifier.isStatic(m.getModifiers()) && !Modifier.isPrivate(m.getModifiers())
                    && isSubsignature(m, mi)) {
                return m;
            }
        }
        return null;
    }

    /**
     * {@code m} が {@code mi} の subsignature か（JLS 8.4.2）。JDT の {@code isSubsignature} は、{@code m} を宣言した型が
     * パラメータ化された型（{@code GenBase<String>} の {@code save(String)}）だと型引数を置き換える前の宣言
     * （{@code save(T)}）に戻して比べるので、置き換えた引数の型がそろう（同じシグネチャ）ことは先に自分で見る。
     * 型引数を持つメソッドは JDT の判定だけにする
     */
    private static boolean isSubsignature(IMethodBinding m, IMethodBinding mi) {
        if (m.getTypeParameters().length == 0 && mi.getTypeParameters().length == 0) {
            ITypeBinding[] a = m.getParameterTypes();
            ITypeBinding[] b = mi.getParameterTypes();
            boolean same = true;
            for (int k = 0; k < a.length && same; k++) {
                same = a[k].isEqualTo(b[k]);
            }
            if (same) {
                return true;
            }
        }
        return m.isSubsignature(mi);
    }

    /** {@code sup} が {@code sub} の親型（消去して比べる）か */
    private static boolean isSupertypeOf(ITypeBinding sup, ITypeBinding sub) {
        if (sup == null || sub == null) {
            return false;
        }
        String want = keyOf(sup.getErasure());
        for (ITypeBinding t : supertypesOf(sub)) {
            if (keyOf(t.getErasure()).equals(want)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 関数型インターフェース {@code fnType} のラムダ／メソッド参照が実装するメソッドの鍵すべて。
     *
     * JLS 9.8 では、関数型インターフェースの抽象メソッドは1つとは限らない。親から継承した
     * 抽象メソッドのうち、互いに上書き同等（シグネチャが一方の subsignature）なものはまとめて
     * 1つの関数型を成し、ラムダはその<b>すべて</b>を実装する。JDT の
     * {@code getFunctionalInterfaceMethod} はそのうちの1つしか返さないので、残りをここで集める。
     * <pre>
     *   interface A { void go(); }  interface B { void go(); }  interface C extends A, B {}
     *   C c = () -> ...;   // A#go() と B#go() の両方を実装する（SAM は片方だけ）
     *
     *   interface StrFoo extends Foo&lt;String&gt; { void accept(String s); }
     *   // StrFoo#accept(java.lang.String) と Foo#accept(java.lang.Object) の両方
     * </pre>
     * 読み手（M 行）は鍵の完全一致で引くので、親の宣言の鍵が無いと、親の型で受けた変数への
     * 呼び出しでラムダが見えず、別の実装1件に誤って確定する（docs/lambda-expansion-qa.md の Q11・Q15）。
     * 親型は型引数を具体化したまま辿り、上書き同等かの判定は
     * {@code IMethodBinding.isSubsignature}（JLS 8.4.2）に任せる。
     *
     * <p>目標の型は、ラムダ・メソッド参照の式の型として I 行に載る（{@link FactVisitor#preVisit2}）。その親
     * （{@code interface Door extends Opener} の Opener）を変えると返す鍵が変わるが、それは差分更新が部分型の側で
     * 拾う（Door は Opener の部分型なので、Opener が変われば Door も変わった型になる。docs/cache-unification-qa.md の Q77）。
     *
     * @return 先頭は SAM 自身の鍵。SAM の鍵を作れなければ空
     */
    List<String> functionalKeysOf(ITypeBinding fnType, IMethodBinding sam) {
        MethodRef self = toRef(sam);
        if (self == null) {
            return List.of();
        }
        List<String> keys = new ArrayList<>(2);
        keys.add(self.key());
        if (fnType == null) {
            return keys;
        }
        // 交差型（(Runnable & Serializable) () -> ...）は、各成分とその親を見る
        List<ITypeBinding> roots = fnType.isIntersectionType()
                ? List.of(fnType.getTypeBounds()) : List.of(fnType);
        Set<String> seenTypes = new HashSet<>();
        for (ITypeBinding root : roots) {
            List<ITypeBinding> types = new ArrayList<>();
            types.add(root);
            types.addAll(supertypesOf(root));
            for (ITypeBinding type : types) {
                if (!type.isInterface() || !seenTypes.add(keyOf(type))) {
                    continue;
                }
                for (IMethodBinding candidate : type.getDeclaredMethods()) {
                    int mods = candidate.getModifiers();
                    if (!Modifier.isAbstract(mods) || Modifier.isStatic(mods)
                            || !candidate.getName().equals(sam.getName())
                            || candidate.getParameterTypes().length != sam.getParameterTypes().length
                            || !(sam.isSubsignature(candidate) || candidate.isSubsignature(sam))) {
                        continue;
                    }
                    MethodRef ref = toRef(candidate);
                    if (ref != null && !keys.contains(ref.key())) {
                        keys.add(ref.key());
                    }
                }
            }
        }
        return keys;
    }

    /** 推移的な親型（型引数を具体化したまま）。循環と多重継承で同じ型を2度辿らないよう鍵で覚える */
    private static List<ITypeBinding> supertypesOf(ITypeBinding type) {
        List<ITypeBinding> out = new ArrayList<>(4);
        ArrayDeque<ITypeBinding> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(type);
        while (!queue.isEmpty()) {
            ITypeBinding current = queue.poll();
            ITypeBinding parent = current.getSuperclass();
            if (parent != null && seen.add(keyOf(parent))) {
                out.add(parent);
                queue.add(parent);
            }
            ITypeBinding[] interfaces = current.getInterfaces();
            if (interfaces == null) {
                continue;
            }
            for (ITypeBinding iface : interfaces) {
                if (seen.add(keyOf(iface))) {
                    out.add(iface);
                    queue.add(iface);
                }
            }
        }
        return out;
    }

    /** 訪問済みの判定用。バインディングの鍵が取れない型は名前で見る */
    private static String keyOf(ITypeBinding t) {
        String key = t.getKey();
        return (key == null || key.isEmpty()) ? t.getQualifiedName() : key;
    }

    /** new された具象型。匿名クラスの場合は匿名型そのもの。取れなければ null */
    String createdTypeOf(ClassInstanceCreation cic) {
        ITypeBinding tb = cic.resolveTypeBinding();
        if (tb == null) {
            IMethodBinding cb = cic.resolveConstructorBinding();
            if (cb != null && cb.getMethodDeclaration() != null) {
                tb = cb.getMethodDeclaration().getDeclaringClass();
            }
        }
        if (tb == null) {
            return null;
        }
        return typeNameOf(erasureOf(tb));
    }
}
