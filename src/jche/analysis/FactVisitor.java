/*
 * Copyright 2026 the java-call-hierarchy-exporter authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jche.analysis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.CreationReference;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionMethodReference;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.Initializer;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.SuperMethodReference;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.TypeMethodReference;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import jche.cache.CacheFormat;
import jche.cache.CallEdgeFact;
import jche.cache.FieldAccessFact;
import jche.cache.FileAnalysis;
import jche.cache.FunctionalImplFact;
import jche.cache.HintFact;
import jche.cache.MethodDeclFact;
import jche.cache.MethodRef;
import jche.cache.ModifierTokens;
import jche.cache.Origin;
import jche.cache.RecvKind;
import jche.cache.ReturnFact;
import jche.cache.TypeFact;
import jche.cache.UnresolvedCallFact;
import jche.extension.CallSiteHintCollector;
import jche.extension.HintSink;
import jche.util.Log;

/**
 * ASTを走査して、キャッシュに書く事実（型階層・宣言・呼び出し・フィールド・return・
 * ラムダ・証拠）を {@link FileAnalysis} に集める。
 *
 * <h2>呼び出し元の追跡</h2>
 * 「現在囲まれているメソッド」はスタックで保持する。
 * 匿名クラス・ローカルクラスの MethodDeclaration は、囲みメソッドの
 * MethodDeclaration の内側にネストして現れる。単一スロットで保持すると
 * 内側のメソッドを抜けた時点で囲みメソッドの情報が失われ、
 * 「匿名クラスより後ろにある呼び出しがすべてメソッド外として未解決に落ちる」
 * という静かな欠落が起きる。スタックにすることでこれを防ぐ。
 *
 * ラムダ式は MethodDeclaration ではないため、その中の呼び出しは
 * 自動的に囲みメソッドへ帰属する（ソース上の見え方と一致する）。
 *
 * <h2>役割分担</h2>
 * <ul>
 *   <li>{@link BindingNames} … バインディングから名前を作る（依存する型の記録も）</li>
 *   <li>{@link OriginTracker} … 式の出所（データフロー解析の材料）</li>
 *   <li>{@link FieldFactCollector} … フィールドの宣言と代入</li>
 * </ul>
 */
final class FactVisitor extends ASTVisitor {

    /** 呼び出し元を特定できないことを表す番兵（ArrayDequeはnullを保持できないため） */
    private static final List<MethodRef> UNKNOWN_CALLER = List.of();

    private final CompilationUnit cu;
    private final FileAnalysis out;
    private final List<CallSiteHintCollector> collectors;
    private final BindingNames names;
    private final OriginTracker origins;
    private final FieldFactCollector fieldFacts;

    /**
     * 現在の呼び出し元のスタック。通常は要素1件（そのメソッド自身）だが、
     * インスタンスフィールド初期化子・インスタンス初期化ブロックの中では
     * 「そのクラスの、this(...)委譲していない全コンストラクタ」が
     * 複数件入る（コンパイル後、実際にそれら全部に複製されるため）。
     */
    private final ArrayDeque<List<MethodRef>> methodStack = new ArrayDeque<>();

    /** 現在囲まれている型ごとの状態（{@link TypeContext} 参照） */
    private final ArrayDeque<TypeContext> typeContextStack = new ArrayDeque<>();

    /**
     * 現在のラムダ式の入れ子の深さ。
     *
     * ラムダ式の中の return は、囲みメソッドの return ではなくラムダ自身の
     * 戻り値。これを囲みメソッドの戻り値として記録すると、ファクトリメソッドの
     * 戻り値型を誤って狭めてしまうため、0 のときだけ R行を記録する。
     * （呼び出しの帰属はこれまで通り囲みメソッドのままでよい。
     *   ラムダの中の呼び出しは、実際にその囲みメソッドの一部として書かれている）
     */
    private int lambdaDepth;
    /** MethodDeclaration をまたぐときに lambdaDepth を退避するスタック */
    private final ArrayDeque<Integer> lambdaDepthStack = new ArrayDeque<>();

    FactVisitor(CompilationUnit cu, FileAnalysis out, List<CallSiteHintCollector> collectors) {
        this.cu = cu;
        this.out = out;
        this.collectors = collectors;
        this.names = new BindingNames(out);
        this.origins = new OriginTracker(names);
        this.fieldFacts = new FieldFactCollector(out, names, origins);
    }

    /**
     * 現在の呼び出し元一覧。特定できない場合は null。
     * 通常は要素1件だが、インスタンス初期化子の中では複数件になりうる
     * （{@link #buildTypeContext} 参照）。
     */
    private List<MethodRef> currentCallers() {
        List<MethodRef> top = methodStack.peek();
        return (top == null || top.isEmpty()) ? null : top;
    }

    private int lineOf(ASTNode node) {
        return cu.getLineNumber(node.getStartPosition());
    }

    // ================================================================
    // 型の宣言（H行）と型コンテキスト
    // ================================================================

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

    @Override
    public boolean visit(TypeDeclaration node) {
        enterType(node.resolveBinding(), node.bodyDeclarations(), lineOf(node.getName()));
        return true;
    }

    @Override
    public void endVisit(TypeDeclaration node) {
        leaveType();
    }

    /**
     * enum も型階層（H行）と型コンテキストに載せる。
     *
     * TypeDeclaration と EnumDeclaration はASTノードとして別物で、
     * こちらの visit が無いと enum が丸ごと素通りしていた。その結果、
     * (1) インターフェースを実装する enum がCHAの候補に入らず、他に実装が
     *     1つだけあると SINGLE_IMPL でそちらに誤確定する（実測で再現）、
     * (2) enum のフィールド初期化子が「メソッド本体の外」として
     *     型解決失敗に落ちる、という2つの漏れが起きていた。
     */
    @Override
    public boolean visit(EnumDeclaration node) {
        enterType(node.resolveBinding(), node.bodyDeclarations(), lineOf(node.getName()));
        return true;
    }

    @Override
    public void endVisit(EnumDeclaration node) {
        leaveType();
    }

    /** record も enum と同じ理由で型階層と型コンテキストに載せる */
    @Override
    public boolean visit(RecordDeclaration node) {
        enterType(node.resolveBinding(), node.bodyDeclarations(), lineOf(node.getName()));
        return true;
    }

    @Override
    public void endVisit(RecordDeclaration node) {
        leaveType();
    }

    /**
     * アノテーション型。呼び出しはほぼ現れないが、定数フィールドの
     * 初期化子が「メソッド本体の外」に落ちないよう型コンテキストだけは積む。
     */
    @Override
    public boolean visit(AnnotationTypeDeclaration node) {
        enterType(node.resolveBinding(), node.bodyDeclarations(), lineOf(node.getName()));
        return true;
    }

    @Override
    public void endVisit(AnnotationTypeDeclaration node) {
        leaveType();
    }

    /** 匿名クラスも型階層に載せる。載せないとオーバーライド候補から漏れる */
    @Override
    public boolean visit(AnonymousClassDeclaration node) {
        // 匿名クラスには名前が無いため、本体の開始位置を代わりに使う
        enterType(node.resolveBinding(), node.bodyDeclarations(), lineOf(node));
        return true;
    }

    @Override
    public void endVisit(AnonymousClassDeclaration node) {
        leaveType();
    }

    private void enterType(ITypeBinding tb, List<?> bodyDeclarations, int declLine) {
        recordType(tb);
        typeContextStack.push(buildTypeContext(tb, bodyDeclarations, declLine));
        fieldFacts.collect(tb, bodyDeclarations);
    }

    private void leaveType() {
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
        ITypeBinding superclass = erased.getSuperclass();
        if (superclass != null) {
            String n = names.typeNameOf(BindingNames.erasureOf(superclass));
            // java.lang.Object は候補計算に寄与しないので除外（無駄に巨大化させない）
            if (n != null && !"java.lang.Object".equals(n)) {
                supers.add(n);
            }
        }
        ITypeBinding[] interfaces = erased.getInterfaces();
        if (interfaces != null) {
            for (ITypeBinding i : interfaces) {
                String n = names.typeNameOf(BindingNames.erasureOf(i));
                if (n != null) {
                    supers.add(n);
                }
            }
        }
        out.types.add(new TypeFact(fqn, kind, supers, BindingNames.packageOf(erased)));
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
        if (!anyConstructor) {
            synthesizeImplicitConstructor(tb, declLine, roots);
        }
        return new TypeContext(tb, roots, declLine);
    }

    /**
     * 明示コンストラクタが無い型にも、暗黙のコンストラクタが存在する。
     * ソース上に宣言が無いのでここで合成しておく。作っておかないと
     * new B() が「ソースなし（展開不可）」の未知メソッド扱いになってしまう。
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
                    synthesized = true;
                }
            }
        }
        if (!synthesized) {
            MethodRef implicit = implicitConstructorRef(tb);
            if (implicit != null) {
                roots.add(implicit);
                out.declarations.add(new MethodDeclFact(implicit, declLine, true, ModifierTokens.IMPLICIT));
            }
        }
    }

    /** コンストラクタ本体の先頭文が this(...) か（=他のコンストラクタへの委譲か） */
    private static boolean delegatesToThis(MethodDeclaration md) {
        Block body = md.getBody();
        if (body == null || body.statements().isEmpty()) {
            return false;
        }
        return body.statements().get(0) instanceof ConstructorInvocation;
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
    private List<MethodRef> clinitContext() {
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
    private List<MethodRef> instanceInitContext() {
        TypeContext ctx = typeContextStack.peek();
        if (ctx == null || ctx.rootConstructors.isEmpty()) {
            return UNKNOWN_CALLER;
        }
        return ctx.rootConstructors;
    }

    // ================================================================
    // 初期化子・enum定数（呼び出し元が合成メソッドになるもの）
    // ================================================================

    /**
     * enum 定数（{@code JA("こんにちは")}）はコンストラクタ呼び出しそのもの。
     *
     * 定数は static final フィールドであり、初期化はクラス初期化時に走るので、
     * 呼び出し元は {@code <clinit>} に帰属させる（静的フィールド初期化子と同じ扱い）。
     * ここで記録しないと、enum のコンストラクタが誰からも呼ばれていない
     * ように見える。定数固有のボディ（匿名サブクラス）は、この子ノードの
     * AnonymousClassDeclaration として既存の visit が処理する。
     */
    @Override
    public boolean visit(EnumConstantDeclaration node) {
        methodStack.push(clinitContext());
        origins.enterScope(new HashMap<>());
        IMethodBinding ctor = node.resolveConstructorBinding();
        recordCall(ctor, node, MethodRef.CONSTRUCTOR, targetModsOf(ctor), "",
                RecvKind.TYPE, null, null, origins.argOriginsOf(node.arguments()));
        return true;
    }

    @Override
    public void endVisit(EnumConstantDeclaration node) {
        popCaller();
        origins.leaveScope();
    }

    @Override
    public boolean visit(FieldDeclaration node) {
        methodStack.push(isStaticField(node) ? clinitContext() : instanceInitContext());
        origins.enterScope(origins.scanOrigins(node, new HashMap<>()));
        return true;
    }

    @Override
    public void endVisit(FieldDeclaration node) {
        popCaller();
        origins.leaveScope();
    }

    @Override
    public boolean visit(Initializer node) {
        boolean isStatic = Modifier.isStatic(node.getModifiers());
        methodStack.push(isStatic ? clinitContext() : instanceInitContext());
        origins.enterScope(origins.scanOrigins(node.getBody(), new HashMap<>()));
        return true;
    }

    @Override
    public void endVisit(Initializer node) {
        popCaller();
        origins.leaveScope();
    }

    private void popCaller() {
        if (!methodStack.isEmpty()) {
            methodStack.pop();
        }
    }

    /**
     * フィールドがstaticかどうか。構文上のキーワードでなくバインディングを見るのは、
     * インターフェースのフィールドが暗黙にstaticになる（キーワードが無くても）
     * ケースを正しく扱うため。
     */
    private static boolean isStaticField(FieldDeclaration node) {
        List<?> fragments = node.fragments();
        if (!fragments.isEmpty() && fragments.get(0) instanceof VariableDeclarationFragment frag) {
            IVariableBinding vb = frag.resolveBinding();
            if (vb != null) {
                return Modifier.isStatic(vb.getModifiers());
            }
        }
        return Modifier.isStatic(node.getModifiers());
    }

    // ================================================================
    // メソッド宣言（D行）・ラムダ（M行）・return（R行）
    // ================================================================

    @Override
    public boolean visit(MethodDeclaration node) {
        MethodRef ref = names.toRef(node.resolveBinding());
        if (ref != null) {
            String mods = BindingNames.modifiersOf(node.resolveBinding().getModifiers());
            if (node.isConstructor() && delegatesToThis(node)) {
                mods = ModifierTokens.with(mods, ModifierTokens.DELEGATING);
            }
            out.declarations.add(new MethodDeclFact(ref, lineOf(node.getName()),
                    node.getBody() != null, mods));
            methodStack.push(List.of(ref));
        } else {
            methodStack.push(UNKNOWN_CALLER);
        }
        origins.enterScope(origins.scanOrigins(node.getBody(), origins.paramScopeOf(node)));
        // 匿名クラスのメソッドはラムダ式の中に現れうる。その中の return は
        // ラムダではなくこのメソッドの return なので、深さを一旦0に戻す
        lambdaDepthStack.push(lambdaDepth);
        lambdaDepth = 0;
        return true;
    }

    @Override
    public void endVisit(MethodDeclaration node) {
        // visit で必ず push しているため、ここで必ず pop して対応を保つ。
        // （JDTは visit が false を返した場合も endVisit を呼ぶ）
        popCaller();
        origins.leaveScope();
        if (!lambdaDepthStack.isEmpty()) {
            lambdaDepth = lambdaDepthStack.pop();
        }
    }

    @Override
    public boolean visit(LambdaExpression node) {
        lambdaDepth++;
        recordFunctionalImpl(node.resolveTypeBinding(), node, FunctionalImplFact.LAMBDA);
        return true;
    }

    @Override
    public void endVisit(LambdaExpression node) {
        if (lambdaDepth > 0) {
            lambdaDepth--;
        }
    }

    /**
     * ラムダ／メソッド参照が「その関数型インターフェースの実装でもある」
     * ことを記録する（M行）。
     *
     * これが無いと、インターフェース型の変数に対する呼び出しが
     * 「実装はソース上に1つ（匿名クラス等）だけ」と見えてしまい、
     * 実際にはラムダが入っている経路まで SINGLE_IMPL で1つに決め打ちされる。
     * 絞れないことより誤って絞ることの方が害が大きいので、
     * 「展開できない実装が他にもある」ことだけは必ず残す。
     *
     * ラムダ本体の呼び出しは、引き続き囲みメソッドに計上する。
     * 合成メソッドに付け替えると、ラムダを受け取った側からの経路を辿るために
     * 関数型インターフェース経由の呼び出しを全て展開する必要があり、現在の
     * 設計（キャッシュに事実だけを持つ）では扱えないため。
     */
    private void recordFunctionalImpl(ITypeBinding fnType, ASTNode node, String kind) {
        if (fnType == null) {
            return;
        }
        IMethodBinding sam = fnType.getFunctionalInterfaceMethod();
        if (sam == null) {
            return;
        }
        MethodRef ref = names.toRef(sam);
        if (ref == null) {
            return;
        }
        int line = lineOf(node);
        List<MethodRef> callers = currentCallers();
        if (callers == null) {
            out.functionalImpls.add(new FunctionalImplFact(line, null, ref.key(), kind));
            return;
        }
        // 囲みメソッドごとに1件（初期化子の中なら根のコンストラクタそれぞれ）
        for (MethodRef caller : callers) {
            out.functionalImpls.add(new FunctionalImplFact(line, caller, ref.key(), kind));
        }
    }

    /**
     * このメソッドの return が返しうる値の出所を記録する（R行）。
     *
     * ファクトリメソッド（{@code Factory.create()}）の戻り値に対する呼び出しを
     * 具象クラスまで辿るために使う。追跡できない return も U として
     * 記録するのが重要で、そうしないと「実は複数の型を返しうるメソッド」を
     * 分かった分だけで1つに決め打ちしてしまう。
     */
    @Override
    public boolean visit(ReturnStatement node) {
        Expression ex = node.getExpression();
        if (ex == null || lambdaDepth > 0) {
            // void の return、またはラムダ式自身の戻り値
            return true;
        }
        ITypeBinding tb = ex.resolveTypeBinding();
        if (tb != null && (tb.isPrimitive() || tb.isArray()
                || "java.lang.String".equals(tb.getQualifiedName()))) {
            // 具象クラスの絞り込みに使えない戻り値。記録しても嵩むだけ
            return true;
        }
        List<MethodRef> callers = currentCallers();
        if (callers == null) {
            return true;
        }
        String origin = origins.originOf(ex);
        if (origin == null) {
            origin = Origin.UNKNOWN_S;
        }
        for (MethodRef caller : callers) {
            out.returns.add(new ReturnFact(caller, origin));
        }
        return true;
    }

    // ================================================================
    // 呼び出し箇所（C行・U行）
    // ================================================================

    @Override
    public boolean visit(MethodInvocation n) {
        IMethodBinding b = n.resolveMethodBinding();
        Expression recv = n.getExpression();
        recordCall(b, n, n.getName().getIdentifier(), targetModsOf(b),
                recvKeyOf(recv), recvKindOf(recv), externalGuessRef(n),
                origins.originOf(recv), origins.argOriginsOf(n.arguments()));
        offerToHintCollectors(n);
        return true;
    }

    @Override
    public boolean visit(SuperMethodInvocation n) {
        // super.m() は静的束縛（オーバーライドの影響を受けない）
        IMethodBinding b = n.resolveMethodBinding();
        recordCall(b, n, n.getName().getIdentifier(), superMods(b), "",
                RecvKind.THIS, null, null, origins.argOriginsOf(n.arguments()));
        return true;
    }

    @Override
    public boolean visit(ClassInstanceCreation n) {
        IMethodBinding ctor = n.resolveConstructorBinding();
        recordCall(ctor, n, MethodRef.CONSTRUCTOR, targetModsOf(ctor), "", RecvKind.TYPE,
                null, null, origins.argOriginsOf(n.arguments()));
        return true;
    }

    @Override
    public boolean visit(ConstructorInvocation n) {
        IMethodBinding ctor = n.resolveConstructorBinding();
        recordCall(ctor, n, MethodRef.CONSTRUCTOR, targetModsOf(ctor), "", RecvKind.TYPE,
                null, null, origins.argOriginsOf(n.arguments()));
        return true;
    }

    /**
     * メソッド参照 obj::m。
     *
     * 参照した時点ではまだ呼ばれず、実際に動くのは関数型インターフェース
     * 経由だが、そこまで辿るにはラムダ／メソッド参照を合成メソッドとして
     * 持つ必要がある（{@link #recordFunctionalImpl} 参照）。
     * ここで記録しないと「:: でしか参照されていないメソッド」が
     * 呼ばれていないように見えてしまう。取りこぼす方が害が大きいので、
     * 参照を「囲みメソッドからの呼び出し」として記録する。
     *
     * レシーバの扱いは通常の呼び出しと同じ。obj::m の obj が
     * コンストラクタ注入されたフィールドなら、そのままデータフローで絞れる。
     */
    @Override
    public boolean visit(ExpressionMethodReference n) {
        Expression recv = n.getExpression();
        IMethodBinding b = n.resolveMethodBinding();
        recordFunctionalImpl(n.resolveTypeBinding(), n, FunctionalImplFact.METHOD_REF);
        recordCall(b, n, n.getName().getIdentifier(), targetModsOf(b), recvKeyOf(recv),
                recvKindOf(recv), null, origins.originOf(recv), null);
        return true;
    }

    /** メソッド参照 List&lt;String&gt;::size のように、レシーバが型そのものの形 */
    @Override
    public boolean visit(TypeMethodReference n) {
        IMethodBinding b = n.resolveMethodBinding();
        recordFunctionalImpl(n.resolveTypeBinding(), n, FunctionalImplFact.METHOD_REF);
        recordCall(b, n, n.getName().getIdentifier(), targetModsOf(b), "",
                RecvKind.TYPE, null, null, null);
        return true;
    }

    /** メソッド参照 super::m。super 呼び出しと同じく静的束縛 */
    @Override
    public boolean visit(SuperMethodReference n) {
        IMethodBinding b = n.resolveMethodBinding();
        recordFunctionalImpl(n.resolveTypeBinding(), n, FunctionalImplFact.METHOD_REF);
        recordCall(b, n, n.getName().getIdentifier(), superMods(b), "",
                RecvKind.THIS, null, null, null);
        return true;
    }

    /** コンストラクタ参照 Type::new */
    @Override
    public boolean visit(CreationReference n) {
        IMethodBinding b = n.resolveMethodBinding();
        recordFunctionalImpl(n.resolveTypeBinding(), n, FunctionalImplFact.CTOR_REF);
        ITypeBinding created = n.getType().resolveBinding();
        if (b == null && created != null && created.isArray()) {
            // int[]::new は配列生成であって呼び出すメソッドが無い。
            // 未解決として記録すると、実体の無い失敗が件数に混ざる
            return true;
        }
        recordCall(b, n, MethodRef.CONSTRUCTOR, targetModsOf(b), "", RecvKind.TYPE, null, null, null);
        return true;
    }

    /**
     * 呼び出し箇所を1件記録する。
     *
     * 解決できれば C 行（呼び出し元ごとに1本）。解決できなければ U 行に、
     * 理由コードと import から推定した候補（{@link #externalGuessRef}）を事実として残す。
     * 候補をエッジとして採用するかは読み手（jche.graph.CallGraphBuilder）が決める。
     */
    private void recordCall(IMethodBinding binding, ASTNode node, String displayName,
                            String calleeMods, String recvKey, char recvKind,
                            String externalGuess, String recvOrigin, String argOrigins) {
        int line = lineOf(node);
        List<MethodRef> callers = currentCallers();
        if (callers == null) {
            // 呼び出し元の型・コンストラクタ自体を特定できないケース
            // （型のバインディング解決に失敗した等）
            out.callSites.add(new UnresolvedCallFact(line, null, displayName,
                    UnresolvedCallFact.OUTSIDE_METHOD, "", recvKey, recvKind,
                    recvOrigin, argOrigins, lambdaDepth));
            return;
        }
        MethodRef callee = names.toRef(binding);
        if (callee == null) {
            // 呼び出し先の型解決に失敗したケース。呼び出し元ごとに1件残す
            // （C行と同じく、初期化子の中なら根のコンストラクタそれぞれに属する）
            for (MethodRef caller : callers) {
                out.callSites.add(new UnresolvedCallFact(line, caller, displayName,
                        UnresolvedCallFact.BINDING_FAILED, externalGuess, recvKey, recvKind,
                        recvOrigin, argOrigins, lambdaDepth));
            }
            return;
        }
        // 呼び出し元が複数（インスタンス初期化子等）でも全件をエッジにする。
        // 実際にコンパイル後それぞれから1回ずつ呼ばれるため、これは近似ではない
        for (MethodRef caller : callers) {
            out.callSites.add(new CallEdgeFact(caller, callee, line, calleeMods,
                    recvKey, recvKind, recvOrigin, argOrigins, lambdaDepth));
        }
    }

    /**
     * フェーズAの拡張に、この呼び出し箇所を見せる。
     *
     * 呼び出し元が複数（インスタンス初期化子等）ある場合は、その全員に対して
     * 見せる。一部にしか見せないと、その呼び出し元経由の解決だけ証拠を
     * 見つけられなくなるため。CallSiteHintCollector のインターフェースは
     * 呼び出し元1件を前提にしているため、呼び出し元ごとに1回ずつ呼ぶ。
     */
    private void offerToHintCollectors(MethodInvocation n) {
        List<MethodRef> callers = currentCallers();
        if (callers == null || collectors.isEmpty()) {
            return;
        }
        for (MethodRef caller : callers) {
            String callerKey = caller.key();
            HintSink sink = (scopeKey, kind, value) -> {
                if (scopeKey == null || kind == null || value == null) {
                    return;
                }
                out.hints.add(new HintFact(callerKey, CacheFormat.clean(scopeKey),
                        CacheFormat.clean(kind), CacheFormat.clean(value)));
            };
            for (CallSiteHintCollector collector : collectors) {
                try {
                    collector.collect(n, cu, callerKey, sink);
                } catch (RuntimeException e) {
                    // 拡張の失敗で解析全体を止めない
                    Log.warn("hint collector 失敗: " + collector.getClass().getName() + " (" + e + ")");
                }
            }
        }
    }

    /**
     * バインディング解決が完全に失敗した場合の最後の手段。
     * レシーバの単純名が、このファイルの単一型インポート（{@code import a.b.C;}）と
     * 一致すれば、そのFQNを候補として返す。あくまでソース上のテキストからの
     * 推定であり、JDTによる検証済みの型解決ではない
     * （メンバの実在・オーバーロードの妥当性までは確認できない）。
     * ワイルドカードimport・static import・型不明のレシーバでは使わない。
     */
    private String externalGuessRef(MethodInvocation n) {
        if (!(n.getExpression() instanceof SimpleName recv)) {
            return null;
        }
        String simple = recv.getIdentifier();
        for (Object o : cu.imports()) {
            ImportDeclaration imp = (ImportDeclaration) o;
            if (imp.isOnDemand() || imp.isStatic()) {
                continue;
            }
            String name = imp.getName().getFullyQualifiedName();
            if (name.equals(simple) || name.endsWith("." + simple)) {
                return name;
            }
        }
        return null;
    }

    /**
     * 呼び出し先（宣言側）の修飾子（事実）。静的束縛かどうかの判定は
     * 読み手が行う（jche.graph.BindKind）。
     * 宣言クラスが final なら finalclass を足す（サブクラスを作れない＝オーバーライド不能）
     */
    private static String targetModsOf(IMethodBinding b) {
        if (b == null) {
            return "";
        }
        IMethodBinding decl = b.getMethodDeclaration();
        if (decl == null) {
            decl = b;
        }
        String mods = BindingNames.modifiersOf(decl.getModifiers());
        ITypeBinding owner = decl.getDeclaringClass();
        if (owner != null && Modifier.isFinal(owner.getModifiers())) {
            mods = ModifierTokens.with(mods, ModifierTokens.FINAL_CLASS);
        }
        return mods;
    }

    /** super.m() / super::m の呼び出し先。super 経由である事実を足す */
    private static String superMods(IMethodBinding b) {
        return ModifierTokens.with(targetModsOf(b), ModifierTokens.SUPER);
    }

    /**
     * レシーバの識別キー。
     * ローカル変数なら変数のバインディングキー、そうでなければ "@開始位置"。
     * 後者にしておくと、変数を介さない呼び出し
     * （DaoFactory.get("X").execute(...) など）にも拡張が証拠を結び付けられる。
     */
    private static String recvKeyOf(Expression ex) {
        if (ex == null) {
            return "";
        }
        if (ex instanceof SimpleName name && name.resolveBinding() instanceof IVariableBinding vb) {
            String k = vb.getKey();
            if (k != null && !k.isEmpty()) {
                return k.replaceAll("\\s", "_");
            }
        }
        return "@" + ex.getStartPosition();
    }

    /**
     * レシーバがどこから来たかを判定する（{@link RecvKind}）。
     *
     * CHAで実装を絞れなかったときに「なぜ絞れないのか」を出力へ載せるため。
     * 例: 戻り値ならファクトリメソッド、引数ならメソッド外から渡されている、
     * という具合に、利用者が次に何を調べるべきかが変わる。
     */
    private static char recvKindOf(Expression ex) {
        if (ex == null) {
            return RecvKind.THIS;
        }
        if (ex instanceof MethodInvocation) {
            return RecvKind.RETURN;
        }
        if (ex instanceof ClassInstanceCreation) {
            return RecvKind.LOCAL;   // new した直後に呼ぶ形。型は確定している
        }
        if (ex instanceof FieldAccess) {
            return RecvKind.FIELD;
        }
        if (ex instanceof SimpleName || ex instanceof QualifiedName) {
            IBinding b = (ex instanceof SimpleName sn) ? sn.resolveBinding()
                    : ((QualifiedName) ex).resolveBinding();
            if (b instanceof ITypeBinding) {
                return RecvKind.TYPE;
            }
            if (b instanceof IVariableBinding vb) {
                if (vb.isField()) {
                    return RecvKind.FIELD;
                }
                if (vb.isParameter()) {
                    return RecvKind.PARAM;
                }
                return RecvKind.LOCAL;
            }
        }
        return RecvKind.OTHER;
    }

    // ================================================================
    // 同一メソッド内の new（X行の NEW）
    //
    // フロー依存解析（分岐やループを厳密に追う）はコストが高いので、
    // 「そのメソッド内でその変数に代入される new を全部集める」という
    // フロー非依存・安全側の方針を取る。
    //   1件   -> LOCAL_NEW（確定）
    //   複数件 -> LOCAL_NEW_MULTI（候補集合。CHAよりはるかに狭い）
    //
    // 変数の同定は名前ではなく IVariableBinding.getKey() で行う。
    // 名前で照合すると、同名変数がスコープ違いで複数ある場合に誤解決する。
    // ================================================================

    @Override
    public boolean visit(VariableDeclarationFragment node) {
        if (node.getInitializer() instanceof ClassInstanceCreation cic) {
            IVariableBinding vb = node.resolveBinding();
            if (vb != null) {
                addNewHint(vb.getKey(), cic);
            }
        }
        return true;
    }

    @Override
    public boolean visit(Assignment node) {
        if (node.getRightHandSide() instanceof ClassInstanceCreation cic
                && node.getLeftHandSide() instanceof SimpleName lhs
                && lhs.resolveBinding() instanceof IVariableBinding vb) {
            addNewHint(vb.getKey(), cic);
        }
        return true;
    }

    private void addNewHint(String varKey, ClassInstanceCreation cic) {
        List<MethodRef> callers = currentCallers();
        if (callers == null || varKey == null) {
            return;
        }
        String type = names.createdTypeOf(cic);
        if (type == null) {
            return;
        }
        // 呼び出し元が複数（インスタンス初期化子等）でも全件に紐づける。
        // 一部にしか付けないと、その呼び出し元経由の解決だけ証拠を見つけられなくなる。
        for (MethodRef caller : callers) {
            out.hints.add(new HintFact(caller.key(), varKey, HintFact.KIND_NEW, type));
        }
    }

    // ================================================================
    // フィールドの参照箇所（A行）
    // ================================================================

    /** import 文の中の名前は参照箇所ではない（依存としては CallEdgeExtractor が別に数える） */
    @Override
    public boolean visit(ImportDeclaration node) {
        return false;
    }

    /**
     * フィールドの参照箇所（A行）。
     *
     * 参照はどんな形でも最終的に SimpleName に行き着く（a.b.c の c、this.x の x、
     * super.x の x）ので、SimpleName だけを見れば重複なく拾える。
     * 宣言そのもの（フィールド宣言の名前）は除く。配列の length のように
     * 型に属さないものも除く。
     */
    @Override
    public boolean visit(SimpleName node) {
        if (node.isDeclaration()) {
            return true;
        }
        if (!(node.resolveBinding() instanceof IVariableBinding vb) || !vb.isField()) {
            return true;
        }
        ITypeBinding owner = vb.getDeclaringClass();
        if (owner == null) {
            return true;
        }
        String ownerFqn = names.typeNameOf(BindingNames.erasureOf(owner));
        if (ownerFqn == null) {
            return true;
        }
        int line = lineOf(node);
        String access = accessKindOf(node);
        String mods = BindingNames.modifiersOf(vb.getModifiers());
        List<MethodRef> callers = currentCallers();
        if (callers == null) {
            out.fieldAccesses.add(new FieldAccessFact(line, null, ownerFqn, vb.getName(),
                    access, mods, lambdaDepth));
            return true;
        }
        // 囲みメソッドごとに1件（初期化子の中なら根のコンストラクタそれぞれ）
        for (MethodRef caller : callers) {
            out.fieldAccesses.add(new FieldAccessFact(line, caller, ownerFqn, vb.getName(),
                    access, mods, lambdaDepth));
        }
        return true;
    }

    /** read / write / readwrite。代入の左辺なら write、複合代入と ++/-- なら readwrite */
    private static String accessKindOf(SimpleName name) {
        ASTNode expr = name;
        ASTNode parent = name.getParent();
        // a.b / this.b / super.b の b なら、参照式はその親
        if ((parent instanceof QualifiedName qn && qn.getName() == name)
                || (parent instanceof FieldAccess fa && fa.getName() == name)
                || (parent instanceof SuperFieldAccess sfa && sfa.getName() == name)) {
            expr = parent;
            parent = expr.getParent();
        }
        if (parent instanceof Assignment assignment && assignment.getLeftHandSide() == expr) {
            return (assignment.getOperator() == Assignment.Operator.ASSIGN)
                    ? FieldAccessFact.WRITE : FieldAccessFact.READ_WRITE;
        }
        if (parent instanceof PostfixExpression) {
            return FieldAccessFact.READ_WRITE;
        }
        if (parent instanceof PrefixExpression prefix) {
            PrefixExpression.Operator op = prefix.getOperator();
            return (op == PrefixExpression.Operator.INCREMENT
                    || op == PrefixExpression.Operator.DECREMENT)
                    ? FieldAccessFact.READ_WRITE : FieldAccessFact.READ;
        }
        return FieldAccessFact.READ;
    }
}
