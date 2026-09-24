// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.CreationReference;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionMethodReference;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.ImplicitTypeDeclaration;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.Initializer;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.RecordPattern;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.SuperMethodReference;
import org.eclipse.jdt.core.dom.TryStatement;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.TypeMethodReference;
import org.eclipse.jdt.core.dom.VariableDeclarationExpression;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;

import jche.cache.FileAnalysis;
import jche.cache.FunctionalImplFact;
import jche.cache.HintFact;
import jche.cache.MethodDeclFact;
import jche.cache.MethodRef;
import jche.cache.ModifierTokens;
import jche.cache.Origin;
import jche.cache.OverrideFact;
import jche.cache.RecvKind;
import jche.cache.ReturnFact;

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
 * ラムダ式は MethodDeclaration ではないが、本体を持つ合成メソッド
 * （{@code lambda$囲みメソッド名$通し番号}）を作ってスタックに積むので、
 * その中の呼び出しはその合成メソッドへ帰属する（docs/lambda-expansion-qa.md）。
 *
 * <h2>役割分担</h2>
 * <ul>
 *   <li>{@link BindingNames} … バインディングから名前を作る（依存する型の記録も）</li>
 *   <li>{@link OriginTracker} … 式の出所（データフロー解析の材料）</li>
 *   <li>{@link FieldFactCollector} … フィールドの宣言と代入</li>
 *   <li>{@link TypeContextTracker} … 型のスタックと合成メソッド（{@code <clinit>}・暗黙コンストラクタ）</li>
 *   <li>{@link CallSiteRecorder} … 呼び出し箇所（C行・U行）と拡張への引き渡し</li>
 *   <li>{@link ImplicitCalls} … 構文が呼ぶメソッド（拡張 for 文・try-with-resources・レコードパターン）の呼び出し先</li>
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
    /** ラムダ式の合成メソッドの名前（このファイルぶんを先に配ってある） */
    private final LambdaNames lambdaNames;

    /**
     * 現在の呼び出し元のスタック。通常は要素1件（そのメソッド自身）だが、
     * インスタンスフィールド初期化子・インスタンス初期化ブロックの中では
     * 「そのクラスの、this(...)委譲していない全コンストラクタ」が
     * 複数件入る（コンパイル後、実際にそれら全部に複製されるため）。
     */
    private final ArrayDeque<List<MethodRef>> methodStack = new ArrayDeque<>();

    /**
     * 合成メソッドに<b>できなかった</b>ラムダ式の入れ子の深さ。
     *
     * 合成できたラムダは本体を自分のメソッドとして持つので、その中では 0 に戻す。
     * 合成できなかったラムダ（関数型インターフェースや囲みメソッドを特定できない）の中の
     * return は、囲みメソッドの return ではなくラムダ自身の戻り値。これを囲みメソッドの
     * 戻り値として記録すると、ファクトリメソッドの戻り値型を誤って狭めてしまうため、
     * 0 のときだけ R行を記録する。C 行の lambda 列にもこの値が出る
     * （合成した本体の中の呼び出しは 0）。
     */
    private int lambdaDepth;
    /** MethodDeclaration をまたぐときに lambdaDepth を退避するスタック */
    private final ArrayDeque<Integer> lambdaDepthStack = new ArrayDeque<>();

    /**
     * そのラムダ式を合成メソッドにできたか。endVisit で戻す対象を決めるために積む。
     * 合成できなかったラムダ（関数型インターフェースを特定できない等）は
     * 従来どおり囲みメソッドに計上している
     */
    private final ArrayDeque<Boolean> lambdaSynthesized = new ArrayDeque<>();
    /** 合成メソッドの修飾子。ラムダ本体はオーバーライドされないので private 相当 */
    private static final String PRIVATE = "private";

    FactVisitor(CompilationUnit cu, FileAnalysis out) {
        this(cu, out, false);
    }

    /**
     * @param recordAllConditions 判定できない条件も guard に残す（条件の調査用。
     *                            {@link GuardCollector} の記録用モード）。キャッシュへは書かない
     */
    FactVisitor(CompilationUnit cu, FileAnalysis out,
                boolean recordAllConditions) {
        this.cu = cu;
        this.out = out;
        this.names = new BindingNames(out);
        this.origins = new OriginTracker(names, out);
        this.fieldFacts = new FieldFactCollector(out, names, origins);
        // 型コンテキストは、合成した暗黙のコンストラクタから暗黙の super() の辺を張るので、
        // 呼び出しの記録係を先に作って渡す
        this.calls = new CallSiteRecorder(cu, out, names,
                new GuardCollector(origins, recordAllConditions));
        this.types = new TypeContextTracker(out, names, calls);
        // ラムダの名前は、本体の先読み（OriginTracker）より先に決まっている必要がある
        this.lambdaNames = new LambdaNames(cu, names);
        this.origins.lambdaNames(lambdaNames);
        this.fieldAccesses = new FieldAccessRecorder(cu, out, names);
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

    /**
     * 宣言の終了行（最後の文字がある行）。
     *
     * 開始位置は修飾子・アノテーションを含む宣言全体の先頭なので、宣言行（名前の行）とは
     * ずれることがあるが、ここで欲しいのは終わりだけなので気にしない。
     * 長さが取れない（-1）ときは開始行に倒す。
     */
    private int endLineOf(ASTNode node) {
        int start = node.getStartPosition();
        int len = node.getLength();
        if (start < 0 || len <= 0) {
            return lineOf(node);
        }
        return cu.getLineNumber(start + len - 1);
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

    /**
     * コンパクトなコンパイル単位（JLS 7.3）が暗黙に宣言するクラス（JLS 8.1.8）。
     *
     * パッケージ宣言も型宣言も無いファイルのトップレベルのメソッド・フィールドは、ファイル名を
     * 名前に持つ final なクラスのメンバになる。JDT はこれを TypeDeclaration ではなく
     * ImplicitTypeDeclaration として返すので、visit が無いと型階層（H 行）にも
     * 型コンテキストにも載らず、暗黙のデフォルトコンストラクタ（JLS 8.8.9）も合成されない。
     * 名前が無いので、宣言行はノードの開始位置で代える。
     */
    @Override
    public boolean visit(ImplicitTypeDeclaration node) {
        enterType(node.resolveBinding(), node.bodyDeclarations(), lineOf(node));
        return true;
    }

    @Override
    public void endVisit(ImplicitTypeDeclaration node) {
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
        origins.enterScope(origins.newScope());
        IMethodBinding ctor = node.resolveConstructorBinding();
        calls.record(currentCallers(), lambdaDepth, ctor, node, MethodRef.CONSTRUCTOR, CallSiteRecorder.targetModsOf(ctor), "",
                RecvKind.TYPE, null, origins.valuesOf(null, node.arguments()));
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
        origins.enterScope(origins.scanOrigins(node, origins.newScope()));
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
        origins.enterScope(origins.scanOrigins(node.getBody(), origins.newScope()));
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
    static boolean isStaticField(FieldDeclaration node) {
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
        // バインディングの解決は1回だけ（名前・修飾子・アノテーションで同じものを使う）
        IMethodBinding binding = node.resolveBinding();
        MethodRef ref = names.toRef(binding);
        if (ref != null) {
            String mods = BindingNames.modifiersOf(binding.getModifiers());
            if (node.isConstructor() && TypeContextTracker.delegatesToThis(node)) {
                mods = ModifierTokens.with(mods, ModifierTokens.DELEGATING);
            }
            out.declarations.add(new MethodDeclFact(ref, lineOf(node.getName()),
                    node.getBody() != null, mods,
                    names.annotationsOf(binding), endLineOf(node)));
            recordOverrides(ref, binding);
            methodStack.push(List.of(ref));
            recordImplicitSuperCall(node, binding);
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

    /**
     * この宣言が上書きしている親の宣言（O行）。無ければ何も書かない。
     *
     * メソッドのキーは消去済みの引数型で作るので、型引数を具体化した実装
     * （{@code class UserRepo implements Repo<User>} の {@code save(User)}）は
     * 親（{@code Repo#save(java.lang.Object)}）とキーが一致しない。
     * 一致しないまま候補を引くと「実装が無い」や「別の実装1件に確定」になるため、
     * <b>上書きしているという事実</b>を残して読み手に渡す（{@link jche.cache.OverrideFact}）。
     */
    private void recordOverrides(MethodRef ref, IMethodBinding binding) {
        List<String> overridden = names.overriddenKeysOf(binding);
        if (!overridden.isEmpty()) {
            out.overrides.add(new OverrideFact(ref, overridden));
        }
    }

    /**
     * 書かれていない {@code super()} を辺にする（JLS 8.8.7）。
     *
     * 明示的コンストラクタ呼び出し（{@code this(...)} / {@code super(...)}）で始まらない
     * コンストラクタの本体は、暗黙に {@code super();} で始まる。ASTには現れないが実行される
     * 呼び出しなので、辺にしないと「{@code super()} を書いていないサブクラス」からは
     * 親コンストラクタの中の処理が到達不能になり、影響調査から静かに抜ける。
     *
     * コンストラクタの本体は言語仕様上かならずあるが、構文エラーから復元した AST では
     * 欠けうるので、その場合は何もしない。呼び出し元は既に {@link #methodStack} に
     * 積まれている前提で呼ぶ。
     */
    private void recordImplicitSuperCall(MethodDeclaration node, IMethodBinding binding) {
        if (!node.isConstructor() || node.getBody() == null
                || TypeContextTracker.explicitConstructorInvocationOf(node) != null) {
            return;
        }
        types.recordImplicitSuper(currentCallers(), binding.getDeclaringClass(), "",
                lineOf(node.getName()));
    }

    /**
     * ラムダ式を「合成メソッド」として1つのノードにする。
     *
     * 本体の呼び出しはこの合成メソッドに計上し、囲みメソッドからは
     * 「ラムダを生成した」辺を1本張る。生成の辺を残すのは、
     * ラムダがどこで実行されるか分からない形（コレクションに詰める等）でも
     * 本体の呼び出しを階層から落とさないため。解決できたときは
     * {@code s.get()} の側からも同じノードに繋がる。
     *
     * 合成できない（囲みメソッドや関数型インターフェースを特定できない）ときは、
     * 従来どおり囲みメソッドに計上する。取りこぼす方が害が大きいので安全側に倒す。
     */
    @Override
    public boolean visit(LambdaExpression node) {
        recordFunctionalImpl(node.resolveTypeBinding(), node, FunctionalImplFact.LAMBDA);
        MethodRef body = synthesizeLambda(node);
        lambdaSynthesized.push(body != null);
        if (body == null) {
            lambdaDepth++;
            return true;
        }
        methodStack.push(List.of(body));
        origins.enterScope(origins.scanOrigins(node.getBody(),
                origins.lambdaParamScope(node.parameters())));
        // 合成メソッドから見た深さに戻す。ラムダの中の return は、
        // 囲みメソッドではなくこの合成メソッドの戻り値になった
        lambdaDepthStack.push(lambdaDepth);
        lambdaDepth = 0;
        if (node.getBody() instanceof Expression bodyExpression) {
            // 式本体（() -> new X()）は「return 式;」と同じ（JLS 15.27.4。本体の式の値を返す）。
            // ブロック本体の return と同じく R 行にしないと、書き方で結果が変わる
            recordReturn(bodyExpression);
        }
        return true;
    }

    @Override
    public void endVisit(LambdaExpression node) {
        if (!Boolean.TRUE.equals(lambdaSynthesized.poll())) {
            if (lambdaDepth > 0) {
                lambdaDepth--;
            }
            return;
        }
        popCaller();
        origins.leaveScope();
        if (!lambdaDepthStack.isEmpty()) {
            lambdaDepth = lambdaDepthStack.pop();
        }
    }

    /**
     * ラムダ式の本体を持つ合成メソッドを宣言（D行）として足し、生成の辺を記録する。
     * 合成できなければ null。
     *
     * 名前は {@link LambdaNames} が先に配ったものを使う（決める場所を1つにするため）。
     */
    private MethodRef synthesizeLambda(LambdaExpression node) {
        List<MethodRef> callers = currentCallers();
        MethodRef body = lambdaNames.of(node);
        if (callers == null || body == null) {
            return null;
        }
        String mods = ModifierTokens.with(PRIVATE, ModifierTokens.LAMBDA);
        out.declarations.add(new MethodDeclFact(body, lineOf(node), true, mods, "",
                endLineOf(node)));
        // 生成の辺。呼び出し元が複数（初期化子）なら、それぞれから1本ずつ
        calls.recordSynthetic(callers, body, node, mods, RecvKind.TYPE, lambdaDepth);
        return body;
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
     * ラムダ本体は {@link #synthesizeLambda} で合成メソッドにしてあり、値として
     * 追えた呼び出しはそちらに解決される（{@code DATAFLOW_LAMBDA}）。この M 行は
     * 追えなかった呼び出し（jar の中から呼ばれる forEach 形式など）のために残す。
     *
     * <h4>親インターフェースの宣言の鍵でも書く</h4>
     * 関数型インターフェースのメソッドは、親インターフェースの抽象メソッドを上書きした
     * 再宣言であることがある（{@code interface StrFoo extends Foo<String> { void accept(String s); }}。
     * JLS 9.4.1.3）。このラムダは親の型で受けた変数への呼び出し（{@code Foo<String> f; f.accept(x)}）
     * でも実行されるが、その呼び出し先の鍵は親の宣言（{@code Foo#accept(java.lang.Object)}）で、
     * SAM の鍵（{@code StrFoo#accept(java.lang.String)}）とは一致しない。読み手は M 行を
     * 鍵の完全一致で引くので、SAM と上書き同等な親の抽象メソッドすべての鍵でも M 行を書く。
     * 上書きの関係に無い2つの親から同じメソッドを継承した形
     * （{@code interface C extends A, B {}} で A・B とも {@code void go()}。JLS 9.8）も、
     * ラムダは両方を実装するので含める。判定は {@link BindingNames#functionalKeysOf} に任せる
     * （docs/lambda-expansion-qa.md の Q11・Q15）。
     */
    private void recordFunctionalImpl(ITypeBinding fnType, ASTNode node, String kind) {
        if (fnType == null) {
            return;
        }
        IMethodBinding sam = fnType.getFunctionalInterfaceMethod();
        if (sam == null) {
            return;
        }
        List<String> keys = names.functionalKeysOf(fnType, sam);
        if (keys.isEmpty()) {
            return;
        }
        int line = lineOf(node);
        List<MethodRef> callers = currentCallers();
        for (String key : keys) {
            if (callers == null) {
                out.functionalImpls.add(new FunctionalImplFact(line, null, key, kind));
                continue;
            }
            // 囲みメソッドごとに1件（初期化子の中なら根のコンストラクタそれぞれ）
            for (MethodRef caller : callers) {
                out.functionalImpls.add(new FunctionalImplFact(line, caller, key, kind));
            }
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
            // void の return、または合成できなかったラムダ式自身の戻り値
            return true;
        }
        recordReturn(ex);
        return true;
    }

    /** 今の呼び出し元（メソッドまたはラムダの合成メソッド）が返す値の出所を R 行にする */
    private void recordReturn(Expression ex) {
        ITypeBinding tb = ex.resolveTypeBinding();
        if (tb != null && (tb.isPrimitive() || tb.isArray()
                || "java.lang.String".equals(tb.getQualifiedName()))) {
            // 具象クラスの絞り込みに使えない戻り値。記録しても嵩むだけ
            return;
        }
        List<MethodRef> callers = currentCallers();
        if (callers == null) {
            return;
        }
        String origin = origins.originOf(ex);
        if (origin == null) {
            origin = Origin.UNKNOWN_S;
        }
        for (MethodRef caller : callers) {
            out.returns.add(new ReturnFact(caller, origin));
        }
    }

    // ================================================================
    // 呼び出し箇所（C行・U行）
    // ================================================================

    @Override
    public boolean visit(MethodInvocation n) {
        IMethodBinding b = n.resolveMethodBinding();
        Expression recv = n.getExpression();
        // 修飾する型（JLS 13.1）: 式があればその型、単純名なら m をメンバに持つ最も内側の囲む型
        ITypeBinding qualifying = (recv != null) ? recv.resolveTypeBinding()
                : CallSiteRecorder.enclosingForSimpleName(n, b);
        calls.record(currentCallers(), lambdaDepth, b, n, n.getName().getIdentifier(), CallSiteRecorder.targetModsOf(b),
                CallSiteRecorder.recvKeyOf(recv), CallSiteRecorder.recvKindOf(recv), n,
                origins.valuesOf(recv, n.arguments()), calls.qualifierOf(b, qualifying));
        return true;
    }

    @Override
    public boolean visit(SuperMethodInvocation n) {
        // super.m() は静的束縛（オーバーライドの影響を受けない）
        IMethodBinding b = n.resolveMethodBinding();
        calls.record(currentCallers(), lambdaDepth, b, n, n.getName().getIdentifier(), CallSiteRecorder.superMods(b), "",
                RecvKind.THIS, null, origins.valuesOf(null, n.arguments()));
        return true;
    }

    @Override
    public boolean visit(ClassInstanceCreation n) {
        IMethodBinding ctor = n.resolveConstructorBinding();
        calls.record(currentCallers(), lambdaDepth, ctor, n, MethodRef.CONSTRUCTOR, CallSiteRecorder.targetModsOf(ctor), "", RecvKind.TYPE,
                null, origins.valuesOf(null, n.arguments()));
        return true;
    }

    @Override
    public boolean visit(ConstructorInvocation n) {
        IMethodBinding ctor = n.resolveConstructorBinding();
        calls.record(currentCallers(), lambdaDepth, ctor, n, MethodRef.CONSTRUCTOR, CallSiteRecorder.targetModsOf(ctor), "", RecvKind.TYPE,
                null, origins.valuesOf(null, n.arguments()));
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
                null, origins.valuesOf(null, n.arguments()));
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
                CallSiteRecorder.recvKindOf(recv), null, origins.valuesOf(recv, null),
                calls.qualifierOf(b, recv.resolveTypeBinding()));
        return true;
    }

    /** メソッド参照 List&lt;String&gt;::size のように、レシーバが型そのものの形 */
    @Override
    public boolean visit(TypeMethodReference n) {
        IMethodBinding b = n.resolveMethodBinding();
        recordFunctionalImpl(n.resolveTypeBinding(), n, FunctionalImplFact.METHOD_REF);
        calls.record(currentCallers(), lambdaDepth, b, n, n.getName().getIdentifier(), CallSiteRecorder.targetModsOf(b), "",
                RecvKind.TYPE, null, CallValues.NONE, calls.qualifierOf(b, n.getType().resolveBinding()));
        return true;
    }

    /** メソッド参照 super::m。super 呼び出しと同じく静的束縛 */
    @Override
    public boolean visit(SuperMethodReference n) {
        IMethodBinding b = n.resolveMethodBinding();
        recordFunctionalImpl(n.resolveTypeBinding(), n, FunctionalImplFact.METHOD_REF);
        calls.record(currentCallers(), lambdaDepth, b, n, n.getName().getIdentifier(), CallSiteRecorder.superMods(b), "",
                RecvKind.THIS, null, CallValues.NONE);
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
        calls.record(currentCallers(), lambdaDepth, b, n, MethodRef.CONSTRUCTOR, CallSiteRecorder.targetModsOf(b), "", RecvKind.TYPE, null, CallValues.NONE);
        return true;
    }

    // ================================================================
    // ソースに書かれていない呼び出し（JLS が「呼ぶ」と定めるもの）
    //
    // 呼び出し式は AST に無いが、言語仕様の変換どおりに必ず（あるいは条件を満たせば）
    // 呼ばれるもの。辺にしないと、呼ばれる側を変えたときの影響がこれらの構文から辿れない。
    // 呼び出し先は ImplicitCalls が JLS の変換どおりの静的な型から引く。見つからないとき
    // （型の解決に失敗した等）は何も記録しない。ソースに対応する式が無い呼び出しを
    // 「未解決」と報告しても利用者が調べようがないため（暗黙の super() と同じ。
    // docs/jls-conformance-qa.md の Q11）
    // ================================================================

    /**
     * 拡張 for 文（JLS 14.14.2）。式の型が Iterable の部分型なら
     * {@code for (I #i = Expression.iterator(); #i.hasNext(); ) { T x = (T) #i.next(); ... }}
     * と同じ意味なので、{@code iterator()}・{@code hasNext()}・{@code next()} を呼ぶ。
     * 配列は添字で回すのでメソッドを呼ばない。
     *
     * {@code #i} の型 I は {@code java.util.Iterator<X>}（JLS 14.14.2）なので、{@code hasNext()} と
     * {@code next()} は {@code java.util.Iterator} のメソッドになる。{@code iterator()} が利用者の
     * Iterator の型を返しても同じで、実際に動く実装は読み手が Iterator の実装から探す。
     * 行は for 文の行（javac が行番号表に書くのと同じ）。
     */
    @Override
    public boolean visit(EnhancedForStatement n) {
        Expression ex = n.getExpression();
        ITypeBinding type = ex.resolveTypeBinding();
        if (type == null || type.isArray()) {
            return true;
        }
        IMethodBinding iterator = ImplicitCalls.findNoArgMethod(type, "iterator");
        if (iterator == null) {
            return true;
        }
        recordImplicit(iterator, n, CallSiteRecorder.recvKeyOf(ex), CallSiteRecorder.recvKindOf(ex),
                origins.valuesOf(ex, null), type);
        for (String name : List.of("hasNext", "next")) {
            IMethodBinding m = ImplicitCalls.findNoArgMethodOf(iterator.getReturnType(),
                    "java.util.Iterator", name);
            if (m != null) {
                recordImplicit(m, n, "", RecvKind.RETURN, CallValues.NONE, null);
            }
        }
        return true;
    }

    /**
     * try-with-resources（JLS 14.20.3）。資源は、try ブロックを抜けるときに宣言と逆の順で
     * {@code close()} が呼ばれる（14.20.3。変換は 14.20.3.1）。資源が null なら呼ばれないが、呼ばれうることに変わりはない。
     *
     * 本体の呼び出しより後に実行されるので、本体を読み終えた endVisit で記録する。
     * 行は資源を書いた行。
     */
    @Override
    public void endVisit(TryStatement n) {
        List<?> resources = n.resources();
        for (int i = resources.size() - 1; i >= 0; i--) {
            if (!(resources.get(i) instanceof Expression resource)) {
                continue;
            }
            IMethodBinding close = ImplicitCalls.findNoArgMethod(ImplicitCalls.resourceType(resource),
                    "close");
            if (close == null) {
                continue;
            }
            Expression recv = ImplicitCalls.resourceReceiver(resource);
            recordImplicit(close, resource, CallSiteRecorder.recvKeyOf(recv),
                    CallSiteRecorder.recvKindOf(recv), origins.valuesOf(recv, null),
                    ImplicitCalls.resourceType(resource));
        }
    }

    /**
     * レコードパターン（JLS 14.30.2）。値がレコードパターンに一致するかは、各成分の値を
     * アクセサを呼んで取り出して判定する。成分のパターンが {@code _}（何にでも一致する）でも
     * 取り出しは起きる。入れ子のパターンは、子の RecordPattern の visit がそれぞれ記録する。
     */
    @Override
    public boolean visit(RecordPattern n) {
        ITypeBinding type = (n.getPatternType() == null) ? null : n.getPatternType().resolveBinding();
        for (IMethodBinding accessor : ImplicitCalls.accessorsOf(type)) {
            recordImplicit(accessor, n, "", RecvKind.OTHER, CallValues.NONE, type);
        }
        return true;
    }

    /**
     * ソースに呼び出し式が無い呼び出しを 1 件記録する（import からの推定はしない）。
     *
     * @param qualifying 呼び出しを修飾する型（JLS 13.1。変換後の式の静的な型）。無ければ null
     */
    private void recordImplicit(IMethodBinding b, ASTNode node, String recvKey, char recvKind,
                                CallValues values, ITypeBinding qualifying) {
        calls.record(currentCallers(), lambdaDepth, b, node, b.getName(),
                CallSiteRecorder.targetModsOf(b), recvKey, recvKind, null, values,
                calls.qualifierOf(b, qualifying));
    }

    // ================================================================
    // 同一メソッド内の new（X行の NEW）
    //
    // フロー依存解析（分岐やループを厳密に追う）はコストが高いので、
    // 「そのローカル変数への代入を全部集める」というフロー非依存・安全側の方針を取る。
    //   代入がすべて new で、型が1種類  -> LOCAL_NEW（確定）
    //   代入がすべて new で、型が複数種類 -> LOCAL_NEW_MULTI（候補集合。CHAよりはるかに狭い）
    //   new 以外の代入が1つでもある     -> 何も残さない（段2を使わない）
    //
    // 読み手（jche.graph.CallResolver の段2）は、この証拠があれば候補をその型だけに絞る。
    // 「new 以外の値も入りうる変数」に証拠を残すと、入ってくるほかの実装が黙って落ちる。
    //   void m(Dao d) { if (d == null) d = new DefaultDao(); d.find(); }   // 呼び出し元が渡す実装が落ちる
    // そのため対象は、宣言文で宣言したローカル変数（for 文の初期化部・try の資源を含む）に限る。
    // 引数（ラムダの引数を含む）・フィールド・catch の引数・拡張 for の変数・パターンの変数は、
    // 宣言そのものが new 以外の値を受け取るので対象にしない。フィールドは別のメソッドからも
    // 代入されるので、このメソッドの中の代入だけでは言い切れない。
    //
    // 全部の代入を見終えるまで判断できないので、ファイルの終わり（endVisit(CompilationUnit)）で書く。
    // 変数の同定は名前ではなく IVariableBinding.getKey() で行う。
    // 名前で照合すると、同名変数がスコープ違いで複数ある場合に誤解決する。
    // ================================================================

    /** 証拠の候補になるローカル変数ごとの、代入の集計（変数のキー -> 集計）。宣言した順に並ぶ */
    private final Map<String, LocalAssignments> localAssignments = new LinkedHashMap<>();

    /** 1つのローカル変数への代入の集計 */
    private static final class LocalAssignments {
        /** 宣言したときの呼び出し元（初期化ブロックの中なら根のコンストラクタそれぞれ）。無ければ null */
        final List<MethodRef> callers;
        /** new した型（最初に現れた順） */
        final Set<String> newTypes = new LinkedHashSet<>();
        /** new 以外の代入（複合代入・++/-- を含む）が1つでもあったか */
        boolean other;

        LocalAssignments(List<MethodRef> callers) {
            this.callers = callers;
        }
    }

    @Override
    public boolean visit(VariableDeclarationFragment node) {
        IVariableBinding vb = node.resolveBinding();
        if (vb == null || vb.isField() || vb.isParameter()
                || !(node.getParent() instanceof VariableDeclarationStatement
                        || node.getParent() instanceof VariableDeclarationExpression)) {
            return true;
        }
        String key = HintKeys.ofVariable(vb);
        if (key.isEmpty()) {
            return true;
        }
        LocalAssignments assignments = localAssignments.computeIfAbsent(key,
                k -> new LocalAssignments(currentCallers()));
        if (node.getInitializer() != null) {
            recordLocalAssignment(assignments, node.getInitializer());
        }
        return true;
    }

    @Override
    public boolean visit(Assignment node) {
        LocalAssignments assignments = localAssignmentsOf(node.getLeftHandSide());
        if (assignments != null) {
            if (node.getOperator() == Assignment.Operator.ASSIGN) {
                recordLocalAssignment(assignments, node.getRightHandSide());
            } else {
                assignments.other = true;   // 複合代入（+= など）
            }
        }
        return true;
    }

    /** {@code x++} / {@code x--}。参照型には付かないが、new 以外の代入であることに変わりはない */
    @Override
    public boolean visit(PostfixExpression node) {
        LocalAssignments assignments = localAssignmentsOf(node.getOperand());
        if (assignments != null) {
            assignments.other = true;
        }
        return true;
    }

    /** {@code ++x} / {@code --x}（{@link #visit(PostfixExpression)} と同じ） */
    @Override
    public boolean visit(PrefixExpression node) {
        PrefixExpression.Operator op = node.getOperator();
        if (op == PrefixExpression.Operator.INCREMENT || op == PrefixExpression.Operator.DECREMENT) {
            LocalAssignments assignments = localAssignmentsOf(node.getOperand());
            if (assignments != null) {
                assignments.other = true;
            }
        }
        return true;
    }

    /** 代入先が証拠の候補のローカル変数なら、その集計。違えば null（引数・フィールドなど） */
    private LocalAssignments localAssignmentsOf(Expression target) {
        Expression e = target;
        while (e instanceof ParenthesizedExpression p) {
            e = p.getExpression();   // (x) = ... も x への代入（JLS 15.26）
        }
        if (e instanceof SimpleName name && name.resolveBinding() instanceof IVariableBinding vb) {
            return localAssignments.get(HintKeys.ofVariable(vb));
        }
        return null;
    }

    /**
     * 代入される値を1つ集計する。値を変えないキャストを挟んだ new（{@code (Dao) new DaoImpl()}）も new。
     * 型が決められない new は、new 以外と同じに扱う（候補を狭められないので証拠を残さない）
     */
    private void recordLocalAssignment(LocalAssignments assignments, Expression value) {
        String type = (OriginTracker.unwrapValue(value) instanceof ClassInstanceCreation cic)
                ? names.createdTypeOf(cic) : null;
        if (type == null) {
            assignments.other = true;
        } else {
            assignments.newTypes.add(type);
        }
    }

    /** 代入を全部見終えたので、代入がすべて new だったローカル変数の証拠を書く */
    @Override
    public void endVisit(CompilationUnit node) {
        for (Map.Entry<String, LocalAssignments> e : localAssignments.entrySet()) {
            LocalAssignments assignments = e.getValue();
            if (assignments.other || assignments.callers == null) {
                continue;
            }
            // 呼び出し元が複数（インスタンス初期化子等）でも全件に紐づける。
            // 一部にしか付けないと、その呼び出し元経由の解決だけ証拠を見つけられなくなる。
            for (MethodRef caller : assignments.callers) {
                for (String type : assignments.newTypes) {
                    out.hints.add(new HintFact(caller.key(), e.getKey(), HintFact.KIND_NEW, type));
                }
            }
        }
        localAssignments.clear();
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
        fieldAccesses.record(node, currentCallers(), lambdaDepth);
        return true;
    }
}
