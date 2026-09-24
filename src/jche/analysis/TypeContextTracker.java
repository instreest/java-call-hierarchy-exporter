// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;

import jche.cache.RecvKind;

import jche.cache.FileAnalysis;
import jche.cache.MethodDeclFact;
import jche.cache.MethodRef;
import jche.cache.ModifierTokens;
import jche.cache.TypeFact;

/**
 * 「現在囲まれている型」のスタック。型階層（H行）の記録と、ソース上に宣言の無い
 * 合成メソッド（{@code <clinit>}・暗黙のデフォルトコンストラクタ）の扱いをまとめる。
 *
 * {@link FactVisitor} は型の宣言に入るたびに {@link #enter} し、初期化子や enum 定数の
 * 呼び出し元を {@link #clinitCallers} / {@link #instanceInitCallers} で引く。
 */
final class TypeContextTracker {

    /** 呼び出し元を特定できないことを表す番兵（ArrayDequeはnullを保持できないため） */
    static final List<MethodRef> UNKNOWN_CALLER = List.of();

    /**
     * 暗黙の {@code super()} の辺を張らない親クラス。
     *
     * {@code java.lang.Object} は全てのクラスの親で、辿る先に何も無い
     * （{@link #collectSupertypes} が親型から除くのと同じ理由）。
     * {@code java.lang.Enum} / {@code java.lang.Record} は enum 宣言・record 宣言に対して
     * コンパイラが与える親（JLS 8.9 / 8.10）で、書き手が呼び出しを書いたわけではなく、
     * やはりソースが無い。辺にすると全ての型に1本ずつ [EXTERNAL] の行が増えるだけになる。
     */
    private static final Set<String> IMPLICIT_SUPER_SKIP =
            Set.of("java.lang.Object", "java.lang.Enum", "java.lang.Record");

    private final FileAnalysis out;
    private final BindingNames names;
    /** 合成した辺（暗黙の {@code super()}）を記録する係 */
    private final CallSiteRecorder calls;
    /** 現在囲まれている型ごとの状態（{@link TypeContext} 参照） */
    private final ArrayDeque<TypeContext> typeContextStack = new ArrayDeque<>();

    TypeContextTracker(FileAnalysis out, BindingNames names, CallSiteRecorder calls) {
        this.out = out;
        this.names = names;
        this.calls = calls;
    }

    /**
     * 型ごとの合成メソッド（{@code <clinit>}・暗黙のデフォルトコンストラクタ）の状態。
     * これらはソース上に対応するAST宣言が無いため、初めて呼び出し元として
     * 使われた時点で1回だけ methods.csv 用の宣言（D行相当）を合成する。
     * 常に合成すると、静的初期化子もフィールド初期化子も持たない大多数の
     * クラスにまで {@code <clinit>} 等が現れてノイズになるため。
     */
    private static final class TypeContext {
        final ITypeBinding binding;
        /** this(...)委譲していないコンストラクタ（インスタンス初期化子の複製先） */
        final List<MethodRef> rootConstructors;
        final int declLine;
        boolean clinitDeclared;

        TypeContext(ITypeBinding binding, List<MethodRef> rootConstructors, int declLine) {
            this.binding = binding;
            this.rootConstructors = rootConstructors;
            this.declLine = declLine;
        }
    }

    /** 型に入る。H 行を記録し、合成メソッドの状態を積む */
    void enter(ITypeBinding tb, List<?> bodyDeclarations, int declLine) {
        recordType(tb);
        typeContextStack.push(buildTypeContext(tb, bodyDeclarations, declLine));
    }

    void leave() {
        if (!typeContextStack.isEmpty()) {
            typeContextStack.pop();
        }
    }

    /** 型階層（H行）を記録する */
    private void recordType(ITypeBinding tb) {
        if (tb == null) {
            return;
        }
        ITypeBinding erased = BindingNames.erasureOf(tb);
        String fqn = names.typeNameOf(erased);
        if (fqn == null) {
            return;
        }
        char kind = erased.isInterface() ? TypeFact.INTERFACE
                : (Modifier.isAbstract(erased.getModifiers()) ? TypeFact.ABSTRACT : TypeFact.CONCRETE);

        List<String> supers = new ArrayList<>();
        collectSupertypes(erased, supers, new HashSet<>(), true, 0);
        out.types.add(new TypeFact(fqn, kind, supers, BindingNames.packageOf(erased),
                names.annotationsOf(erased)));
    }

    /** jar の型を経由して親型を辿る深さの上限（JDK の GUI クラス等でも十数段） */
    private static final int MAX_BINARY_SUPERTYPE_DEPTH = 32;

    /**
     * 親型の名前を集める。直接の親型（親クラスとインターフェース）に加えて、
     * ソースの無い親型（jar の型）を経由して到達するソース上の親型も入れる。
     *
     * 例: {@code class Foo extends LibBase} で、jar の LibBase が {@code implements Handler}
     * （Handler はソース上のインターフェース）なら、Foo の親型は LibBase と Handler の両方。
     * H 行はソース上の型にしか無いので、これが無いと読み手（CHA）は「Foo は Handler の実装」と知れず、
     * Handler のメソッド呼び出しの候補から Foo が抜ける。
     * ソース上の親型の先は、その型自身の H 行が持つので辿らない。
     * java.lang.Object は候補計算に寄与しないので除外する（無駄に巨大化させない）。
     */
    private void collectSupertypes(ITypeBinding type, List<String> out, Set<String> seen,
                                   boolean direct, int depth) {
        List<ITypeBinding> parents = new ArrayList<>();
        if (type.getSuperclass() != null) {
            parents.add(type.getSuperclass());
        }
        ITypeBinding[] interfaces = type.getInterfaces();
        if (interfaces != null) {
            parents.addAll(java.util.Arrays.asList(interfaces));
        }
        for (ITypeBinding parent : parents) {
            ITypeBinding erasedParent = BindingNames.erasureOf(parent);
            String n = names.typeNameOf(erasedParent);
            if (n == null || "java.lang.Object".equals(n) || !seen.add(n)) {
                continue;
            }
            boolean fromSource = erasedParent.isFromSource();
            if (direct || fromSource) {
                out.add(n);
            }
            if (!fromSource && depth < MAX_BINARY_SUPERTYPE_DEPTH) {
                collectSupertypes(erasedParent, out, seen, false, depth + 1);
            }
        }
    }

    /**
     * その型の、this(...)委譲していないコンストラクタ一覧を集計する。
     * インスタンスフィールド初期化子・インスタンス初期化ブロックは、
     * コンパイル後これら全部の先頭（super(...)の直後）に複製される。
     * this(...)委譲するコンストラクタには複製されない
     * （委譲先で二重に初期化されるのを防ぐルールのため）。
     *
     * 明示コンストラクタが1つも無ければ、暗黙のデフォルトコンストラクタが
     * 1つ存在する。匿名クラスは明示コンストラクタを書けない言語仕様のため、
     * 常にこちらに倒れる（曖昧さは生じない）。
     *
     * ただしインターフェース（アノテーション型を含む）にはコンストラクタが無い。
     * デフォルトコンストラクタはクラスにだけ暗黙に宣言される（JLS 8.8.9）もので、
     * インターフェースの本体にはコンストラクタを宣言できず（JLS 9.1.5）、インスタンスも作れない。
     * 合成すると、呼ばれることの無い {@code <init>} の宣言と、そこから親への暗黙の {@code super()} が
     * 生じる。インターフェースのフィールドは暗黙に static（JLS 9.3）なので、インスタンス初期化子の
     * 複製先（rootConstructors）も要らない。
     */
    private TypeContext buildTypeContext(ITypeBinding tb, List<?> bodyDeclarations, int declLine) {
        List<MethodRef> roots = new ArrayList<>();
        boolean anyConstructor = false;
        for (Object o : bodyDeclarations) {
            if (!(o instanceof MethodDeclaration md) || !md.isConstructor()) {
                continue;
            }
            anyConstructor = true;
            if (delegatesToThis(md)) {
                continue;
            }
            MethodRef ref = names.toRef(md.resolveBinding());
            if (ref != null) {
                roots.add(ref);
            }
        }
        if (!anyConstructor && (tb == null || !tb.isInterface())) {
            synthesizeImplicitConstructor(tb, declLine, roots);
        }
        return new TypeContext(tb, roots, declLine);
    }

    /**
     * 明示コンストラクタが無い型にも、暗黙のコンストラクタが存在する。
     * ソース上に宣言が無いのでここで合成しておく。作っておかないと
     * new B() が「[EXTERNAL] ソースが無いため辿れない」の未知メソッド扱いになってしまう。
     *
     * 通常のクラスと enum は引数なしの {@code <init>()} だが、record の暗黙の
     * 正準コンストラクタはレコードコンポーネントを引数に取る
     * （Point(int,int) 等）。バインディングにはコンパイラが合成した
     * コンストラクタが載っているので、そちらを正として合成し、
     * 取れない場合だけ引数なしにフォールバックする。
     */
    private void synthesizeImplicitConstructor(ITypeBinding tb, int declLine, List<MethodRef> roots) {
        boolean synthesized = false;
        if (tb != null && tb.getDeclaredMethods() != null) {
            for (IMethodBinding m : tb.getDeclaredMethods()) {
                if (!m.isConstructor()) {
                    continue;
                }
                MethodRef ref = names.toRef(m);
                if (ref != null) {
                    roots.add(ref);
                    out.declarations.add(new MethodDeclFact(ref, declLine, true,
                            ModifierTokens.with(BindingNames.modifiersOf(m.getModifiers()),
                                    ModifierTokens.IMPLICIT)));
                    recordImplicitSuper(List.of(ref), tb, ref.paramSig(), declLine);
                    synthesized = true;
                }
            }
        }
        if (!synthesized) {
            MethodRef implicit = implicitConstructorRef(tb);
            if (implicit != null) {
                roots.add(implicit);
                out.declarations.add(new MethodDeclFact(implicit, declLine, true, ModifierTokens.IMPLICIT));
                recordImplicitSuper(List.of(implicit), tb, "", declLine);
            }
        }
    }

    /**
     * 暗黙の {@code super(...)} の辺を1本張る（JLS 8.8.7 / 8.8.9）。
     *
     * 明示的コンストラクタ呼び出しで始まらないコンストラクタの本体は、暗黙に
     * {@code super();} で始まる。ソースに文が無いので AST には現れないが、
     * <b>実行される呼び出しであることに変わりはない</b>。辺にしないと、
     * {@code super()} を書いていないサブクラスからは親コンストラクタの中の処理
     * （初期化・テンプレートメソッド）が到達不能になり、影響調査から丸ごと抜ける。
     *
     * @param paramSig 呼び出し元コンストラクタの引数シグネチャ。匿名クラスの合成
     *                 コンストラクタは、選ばれた親コンストラクタと同じ引数を取り、
     *                 それをそのまま渡す（JLS 15.9.5.1）ので、まず同じ並びのものを探す
     */
    void recordImplicitSuper(List<MethodRef> callers, ITypeBinding type, String paramSig,
                             int line) {
        IMethodBinding target = implicitSuperTargetOf(type, paramSig);
        MethodRef ref = names.toRef(target);
        if (ref != null) {
            calls.recordSyntheticAt(callers, ref, line,
                    CallSiteRecorder.targetModsOf(target), RecvKind.TYPE, 0, "");
        }
    }

    /**
     * 暗黙の {@code super(...)} の呼び出し先。見つからなければ null。
     *
     * 探す順は (1) 呼び出し元と同じ引数の並び（匿名クラスの合成コンストラクタ用）、
     * (2) 引数なし（通常の暗黙 {@code super()}）、(3) 可変長引数1つだけのもの
     * （{@code Base(String... a)} しか無い親は {@code super()} がそれに解決される）。
     * 見つからないときは辺を張らない。合成した呼び出しなので、ソースに対応する文が無く、
     * 未解決として報告しても利用者が調べようがないためである。
     */
    private IMethodBinding implicitSuperTargetOf(ITypeBinding type, String paramSig) {
        if (type == null) {
            return null;
        }
        ITypeBinding superclass = type.getSuperclass();
        if (superclass == null
                || IMPLICIT_SUPER_SKIP.contains(BindingNames.erasureOf(superclass).getQualifiedName())) {
            return null;
        }
        IMethodBinding[] declared = superclass.getDeclaredMethods();
        if (declared == null) {
            return null;
        }
        IMethodBinding noArg = null;
        IMethodBinding varargs = null;
        for (IMethodBinding m : declared) {
            if (!m.isConstructor()) {
                continue;
            }
            int count = m.getParameterTypes().length;
            if (count == 0) {
                noArg = m;
            } else if (count == 1 && m.isVarargs()) {
                varargs = m;
            }
            if (!paramSig.isEmpty() && paramSig.equals(paramSigOf(m))) {
                return m;
            }
        }
        return (noArg != null) ? noArg : varargs;
    }

    /** バインディングの引数シグネチャ（消去済み）。{@link MethodRef} と同じ並びにする */
    private String paramSigOf(IMethodBinding m) {
        MethodRef ref = names.toRef(m);
        return (ref == null) ? null : ref.paramSig();
    }

    /** コンストラクタが this(...) で他のコンストラクタへ委譲しているか */
    static boolean delegatesToThis(MethodDeclaration md) {
        return explicitConstructorInvocationOf(md) instanceof ConstructorInvocation;
    }

    /**
     * そのコンストラクタ本体の明示的コンストラクタ呼び出し（{@code this(...)} か
     * {@code super(...)}）。書かれていなければ null。
     *
     * <h4>先頭文だけを見てはいけない</h4>
     * Java 25 で確定した柔軟なコンストラクタ本体（JEP 513）により、
     * <b>{@code this(...)} / {@code super(...)} の前に文を書ける</b>ようになった（JLS 8.8.7）。
     * <pre>
     *     Box() {
     *         int v = check(1);   // プロローグ
     *         this(v);
     *     }
     * </pre>
     * 先頭文だけを見ると、この形を「委譲していない」と取り違える。そうなると
     * インスタンス初期化子の呼び出しが委譲側にも複製されて二重に数えられ、さらに
     * D行に {@code delegating} が付かないため、{@link jche.graph.FieldFacts} の
     * 「委譲コンストラクタを持つ型ではコンストラクタ引数由来の出所を採らない」という
     * 安全弁が効かなくなる（＝誤って1つに絞る経路が開く）。
     *
     * 明示的コンストラクタ呼び出しは本体に高々1つしか書けないので、トップレベルの文を
     * 順に見て最初に見つかったもので確定する。入れ子のブロックの中には書けない。
     */
    static Statement explicitConstructorInvocationOf(MethodDeclaration md) {
        Block body = md.getBody();
        if (body == null) {
            return null;
        }
        for (Object statement : body.statements()) {
            if (statement instanceof ConstructorInvocation
                    || statement instanceof SuperConstructorInvocation) {
                return (Statement) statement;
            }
        }
        return null;
    }

    /** 明示コンストラクタが無い型の、暗黙のデフォルトコンストラクタの参照を合成する */
    private MethodRef implicitConstructorRef(ITypeBinding typeBinding) {
        if (typeBinding == null) {
            return null;
        }
        ITypeBinding erased = BindingNames.erasureOf(typeBinding);
        String typeFqn = names.typeNameOf(erased);
        if (typeFqn == null) {
            return null;
        }
        return new MethodRef(BindingNames.packageOf(erased), typeFqn, MethodRef.CONSTRUCTOR, "");
    }

    /**
     * 現在の型の {@code <clinit>}（静的初期化子）への参照を1件だけ含むリスト。
     * この型で初めて使う場合は、methods.csv 等に載るようD行も合成する。
     */
    List<MethodRef> clinitCallers() {
        TypeContext ctx = typeContextStack.peek();
        if (ctx == null || ctx.binding == null) {
            return UNKNOWN_CALLER;
        }
        ITypeBinding erased = BindingNames.erasureOf(ctx.binding);
        String typeFqn = names.typeNameOf(erased);
        if (typeFqn == null) {
            return UNKNOWN_CALLER;
        }
        MethodRef clinit = new MethodRef(BindingNames.packageOf(erased), typeFqn,
                MethodRef.STATIC_INITIALIZER, "");
        if (!ctx.clinitDeclared) {
            ctx.clinitDeclared = true;
            out.declarations.add(new MethodDeclFact(clinit, ctx.declLine, true, "static"));
        }
        return List.of(clinit);
    }

    /** 現在の型の、this(...)委譲していないコンストラクタ一覧（インスタンス初期化子用） */
    List<MethodRef> instanceInitCallers() {
        TypeContext ctx = typeContextStack.peek();
        if (ctx == null || ctx.rootConstructors.isEmpty()) {
            return UNKNOWN_CALLER;
        }
        return ctx.rootConstructors;
    }
}
