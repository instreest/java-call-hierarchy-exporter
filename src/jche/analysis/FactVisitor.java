// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.CreationReference;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionMethodReference;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.Initializer;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.SuperMethodReference;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.TypeMethodReference;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import jche.cache.FileAnalysis;
import jche.cache.FunctionalImplFact;
import jche.cache.HintFact;
import jche.cache.MethodDeclFact;
import jche.cache.MethodRef;
import jche.cache.ModifierTokens;
import jche.cache.Origin;
import jche.cache.RecvKind;
import jche.cache.ReturnFact;
import jche.extension.CallSiteHintCollector;
import jche.extension.HintKeys;

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
 *   <li>{@link TypeContextTracker} … 型のスタックと合成メソッド（{@code <clinit>}・暗黙コンストラクタ）</li>
 *   <li>{@link CallSiteRecorder} … 呼び出し箇所（C行・U行）と拡張への引き渡し</li>
 *   <li>{@link FieldAccessRecorder} … フィールドの参照箇所（A行）</li>
 * </ul>
 */
final class FactVisitor extends ASTVisitor {

    /** 呼び出し元を特定できないことを表す番兵（ArrayDequeはnullを保持できないため） */
    private static final List<MethodRef> UNKNOWN_CALLER = TypeContextTracker.UNKNOWN_CALLER;

    private final CompilationUnit cu;
    private final FileAnalysis out;
    private final BindingNames names;
    private final OriginTracker origins;
    private final FieldFactCollector fieldFacts;
    private final TypeContextTracker types;
    private final CallSiteRecorder calls;
    private final FieldAccessRecorder fieldAccesses;

    /**
     * 現在の呼び出し元のスタック。通常は要素1件（そのメソッド自身）だが、
     * インスタンスフィールド初期化子・インスタンス初期化ブロックの中では
     * 「そのクラスの、this(...)委譲していない全コンストラクタ」が
     * 複数件入る（コンパイル後、実際にそれら全部に複製されるため）。
     */
    private final ArrayDeque<List<MethodRef>> methodStack = new ArrayDeque<>();

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
        this.names = new BindingNames(out);
        this.origins = new OriginTracker(names);
        this.fieldFacts = new FieldFactCollector(out, names, origins);
        this.types = new TypeContextTracker(out, names);
        this.calls = new CallSiteRecorder(cu, out, names, collectors);
        this.fieldAccesses = new FieldAccessRecorder(out, names);
    }

    /**
     * 現在の呼び出し元一覧。特定できない場合は null。
     * 通常は要素1件だが、インスタンス初期化子の中では複数件になりうる
     * （{@link TypeContextTracker} 参照）。
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
        types.enter(tb, bodyDeclarations, declLine);
        fieldFacts.collect(tb, bodyDeclarations);
    }

    private void leaveType() {
        types.leave();
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
        methodStack.push(types.clinitCallers());
        origins.enterScope(new HashMap<>());
        IMethodBinding ctor = node.resolveConstructorBinding();
        calls.record(currentCallers(), lambdaDepth, ctor, node, MethodRef.CONSTRUCTOR, CallSiteRecorder.targetModsOf(ctor), "",
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
        methodStack.push(isStaticField(node) ? types.clinitCallers() : types.instanceInitCallers());
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
        methodStack.push(isStatic ? types.clinitCallers() : types.instanceInitCallers());
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
            if (node.isConstructor() && TypeContextTracker.delegatesToThis(node)) {
                mods = ModifierTokens.with(mods, ModifierTokens.DELEGATING);
            }
            out.declarations.add(new MethodDeclFact(ref, lineOf(node.getName()),
                    node.getBody() != null, mods,
                    BindingNames.annotationsOf(node.resolveBinding())));
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
        calls.record(currentCallers(), lambdaDepth, b, n, n.getName().getIdentifier(), CallSiteRecorder.targetModsOf(b),
                CallSiteRecorder.recvKeyOf(recv), CallSiteRecorder.recvKindOf(recv), calls.externalGuessRef(n),
                origins.originOf(recv), origins.argOriginsOf(n.arguments()));
        calls.offerToHintCollectors(n, currentCallers());
        return true;
    }

    @Override
    public boolean visit(SuperMethodInvocation n) {
        // super.m() は静的束縛（オーバーライドの影響を受けない）
        IMethodBinding b = n.resolveMethodBinding();
        calls.record(currentCallers(), lambdaDepth, b, n, n.getName().getIdentifier(), CallSiteRecorder.superMods(b), "",
                RecvKind.THIS, null, null, origins.argOriginsOf(n.arguments()));
        return true;
    }

    @Override
    public boolean visit(ClassInstanceCreation n) {
        IMethodBinding ctor = n.resolveConstructorBinding();
        calls.record(currentCallers(), lambdaDepth, ctor, n, MethodRef.CONSTRUCTOR, CallSiteRecorder.targetModsOf(ctor), "", RecvKind.TYPE,
                null, null, origins.argOriginsOf(n.arguments()));
        return true;
    }

    @Override
    public boolean visit(ConstructorInvocation n) {
        IMethodBinding ctor = n.resolveConstructorBinding();
        calls.record(currentCallers(), lambdaDepth, ctor, n, MethodRef.CONSTRUCTOR, CallSiteRecorder.targetModsOf(ctor), "", RecvKind.TYPE,
                null, null, origins.argOriginsOf(n.arguments()));
        return true;
    }

    /**
     * super(...)。子コンストラクタから親コンストラクタへの呼び出しで、this(...) と同じく静的束縛。
     * 記録しないと、親コンストラクタの中の呼び出し（初期化処理・テンプレートメソッド等）が
     * 起点から到達不能に見える。暗黙の super()（書かれていないもの）はASTに現れないため対象外。
     */
    @Override
    public boolean visit(SuperConstructorInvocation n) {
        IMethodBinding ctor = n.resolveConstructorBinding();
        calls.record(currentCallers(), lambdaDepth, ctor, n, MethodRef.CONSTRUCTOR, CallSiteRecorder.targetModsOf(ctor), "", RecvKind.TYPE,
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
        calls.record(currentCallers(), lambdaDepth, b, n, n.getName().getIdentifier(), CallSiteRecorder.targetModsOf(b), CallSiteRecorder.recvKeyOf(recv),
                CallSiteRecorder.recvKindOf(recv), null, origins.originOf(recv), null);
        return true;
    }

    /** メソッド参照 List&lt;String&gt;::size のように、レシーバが型そのものの形 */
    @Override
    public boolean visit(TypeMethodReference n) {
        IMethodBinding b = n.resolveMethodBinding();
        recordFunctionalImpl(n.resolveTypeBinding(), n, FunctionalImplFact.METHOD_REF);
        calls.record(currentCallers(), lambdaDepth, b, n, n.getName().getIdentifier(), CallSiteRecorder.targetModsOf(b), "",
                RecvKind.TYPE, null, null, null);
        return true;
    }

    /** メソッド参照 super::m。super 呼び出しと同じく静的束縛 */
    @Override
    public boolean visit(SuperMethodReference n) {
        IMethodBinding b = n.resolveMethodBinding();
        recordFunctionalImpl(n.resolveTypeBinding(), n, FunctionalImplFact.METHOD_REF);
        calls.record(currentCallers(), lambdaDepth, b, n, n.getName().getIdentifier(), CallSiteRecorder.superMods(b), "",
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
        calls.record(currentCallers(), lambdaDepth, b, n, MethodRef.CONSTRUCTOR, CallSiteRecorder.targetModsOf(b), "", RecvKind.TYPE, null, null, null);
        return true;
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
                addNewHint(HintKeys.ofVariable(vb), cic);
            }
        }
        return true;
    }

    @Override
    public boolean visit(Assignment node) {
        if (node.getRightHandSide() instanceof ClassInstanceCreation cic
                && node.getLeftHandSide() instanceof SimpleName lhs
                && lhs.resolveBinding() instanceof IVariableBinding vb) {
            addNewHint(HintKeys.ofVariable(vb), cic);
        }
        return true;
    }

    private void addNewHint(String varKey, ClassInstanceCreation cic) {
        List<MethodRef> callers = currentCallers();
        if (callers == null || varKey == null || varKey.isEmpty()) {
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

    /** フィールドの参照箇所（A行）。{@link FieldAccessRecorder} 参照 */
    @Override
    public boolean visit(SimpleName node) {
        fieldAccesses.record(node, lineOf(node), currentCallers(), lambdaDepth);
        return true;
    }
}
