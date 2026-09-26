// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.Initializer;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.dom.ThisExpression;
import org.eclipse.jdt.core.dom.TypeDeclarationStatement;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import jche.cache.ConstantFact;
import jche.cache.FieldAssignFact;
import jche.cache.FieldDeclFact;
import jche.cache.FileAnalysis;
import jche.cache.MethodRef;
import jche.cache.Origin;
import jche.cache.ValueNode;

/**
 * 1つの型について、フィールドの宣言（V行）・そのフィールドへの代入（J行）・
 * コンパイル時定数の値（K行）を拾う。
 *
 * ここでは事実だけを拾う。「このフィールドには必ずコンストラクタの何番目の
 * 引数が入る」と言い切れるかどうか（private/final か、全コンストラクタで
 * 代入されているか、出所が一致するか）は読み手 jche.graph.FieldFacts が判定する。
 *
 * <h2>拾う範囲（書き込みを 1 つでも取りこぼすと、読み手が誤って値を 1 つに決める）</h2>
 * <ul>
 *   <li>その型自身のフィールド初期化子（site は {@link FieldAssignFact#SITE_INITIALIZER}）</li>
 *   <li>その型自身のメソッド・コンストラクタ・インスタンス初期化ブロック・static 初期化ブロックの中の書き込み。
 *       {@code =} だけでなく複合代入（{@code n += 1}）と {@code ++} / {@code --} も書き込みで、値は
 *       「追跡できない」（{@link ValueNode#NONE}）にする</li>
 *   <li>別の型の本体（入れ子のクラス・static な入れ子のクラス・外側のクラス・子クラス・ほかのファイルの型）から、
 *       ほかの型のインスタンスフィールドへの書き込み（{@link #recordElsewhere}）。書いた側の型を拾うときに、
 *       宣言した型の事実として {@link FieldAssignFact#SITE_ELSEWHERE}・値は「追跡できない」で残す。
 *       private なフィールドに書けるのは同じコンパイル単位の中だけで（JLS 6.6.1）、同じファイルのブロックに V 行と
 *       並ぶ。private でないフィールドには別のファイルからも書ける（{@code svc.dao = …}・子クラスのコンストラクタの
 *       {@code this.dao = …}・{@code Outer.this.dao = …}・{@code super.dao = …}）ので、J 行は<b>書いた側のファイルの
 *       ブロック</b>に載る。読み手はブロックをまたいで集める（jche.graph.FieldFacts の「よその書き込み」。
 *       docs/spring-di-qa.md の Q15）。private でない final なフィールドには、宣言した型の初期化の中でしか
 *       書けない（JLS 8.3.1.2・16）</li>
 * </ul>
 * コンストラクタとインスタンス初期化ブロックの書き込みのうち、site に「そのコンストラクタ」「初期化子」として
 * 残すのは、生成のたびに必ず通る {@code this} への書き込みだけ（{@link #definiteIn}）。条件・ループ・try・
 * ラムダ・入れ子の型の中の書き込み、{@code return} を含む文より後ろの書き込み、{@code this} 以外の
 * インスタンスへの書き込みは {@link FieldAssignFact#SITE_ELSEWHERE} にする。通らない経路では既定値
 * （{@code 0} / {@code false} / {@code null}）が残り、それも実在する値だからである
 * （docs/value-safety-qa.md の Q18）。
 *
 * 代入された値は値グラフのノード（{@link OriginTracker#nodeOf}）で持つ。入れ子（実引数・レシーバ）も付いた
 * ノードを指し、読み手はノードの頭（種別と値）だけで比べる（以前の出所の文字列の頭と同じ）。
 * 値は、この型の枠（囲むメソッドから切り離した空のスコープ）の上で求める（{@link #collect}）。
 * 匿名クラス・ローカルクラスの初期化子が囲むメソッドの引数を読んでも、この型のコンストラクタ引数には見えない。
 * メソッド本体の代入は引数の表だけで求める（ローカル変数の先読みはしない。{@code this.f = local;} は
 * 「追跡できない」のまま）。本体の中で書き換えられる引数（{@code d = new LogDao(d); this.dao = d;}）を
 * 右辺が読むときも「追跡できない」にする。引数の表の {@code A:n} は呼ばれた時点の値で、書き換えた後の値ではない。
 * {@code Objects.requireNonNull(d)}（{@code java.util.Objects} のもの。{@code (d, "msg")}・{@code (d, supplier)} も）は
 * 第 1 引数をそのまま返すので、書き込みの値は第 1 引数として読む（{@link #storedValueOf}。docs/value-safety-qa.md の Q28）。
 * J 行には値のノードの種別も書く（{@link FieldAssignFact#kind}。値を読まない指定の読み手が、N 行を読まずに
 * 「引数を入れる書き込みか」を見るため）。
 * 範囲を変えるときはキャッシュのバージョンを上げる。
 */
final class FieldFactCollector {

    private final FileAnalysis out;
    private final BindingNames names;
    private final OriginTracker origins;

    FieldFactCollector(FileAnalysis out, BindingNames names, OriginTracker origins) {
        this.out = out;
        this.names = names;
        this.origins = origins;
    }

    void collect(ITypeBinding typeBinding, List<?> bodyDeclarations) {
        if (typeBinding == null || bodyDeclarations.isEmpty()) {
            return;
        }
        String typeFqn = names.typeNameOf(BindingNames.erasureOf(typeBinding));
        if (typeFqn == null) {
            return;
        }
        // この型の枠を 1 枚挟む。型の宣言に入った時点では、囲むメソッド（匿名クラス・ローカルクラスなら
        // それを書いたメソッド、フィールド初期化子のラムダならそのラムダ）のスコープが一番上に積まれている。
        // そのまま初期化子の出所を求めると、囲むメソッドの引数（A:0）が「今のフレームの引数」として
        // 返り、読み手（jche.graph.FieldFacts）がこの型のコンストラクタの第 1 引数と取り違える。
        // 空の枠を挟めば、外側の変数は捕捉した変数として扱われ、A: / F: は捨てられ
        // T: / M: は頭だけが残る（OriginTracker.frameIndependent。メソッド本体の代入と同じ規則）
        origins.enterScope(origins.newScope());
        try {
            for (Object o : bodyDeclarations) {
                if (o instanceof FieldDeclaration fd) {
                    scanFieldDeclaration(fd, typeFqn);
                } else if (o instanceof MethodDeclaration md) {
                    scanWrites(md.getBody(), md, typeFqn, siteOf(md), md.isConstructor());
                } else if (o instanceof Initializer init) {
                    // インスタンス初期化ブロックは、どのコンストラクタでも本体の前に 1 回走る（JLS 12.6.7）。
                    // フィールド初期化子と同じ site にする。static 初期化ブロックはインスタンスの生成と無関係
                    boolean isStatic = Modifier.isStatic(init.getModifiers());
                    scanWrites(init.getBody(), null, typeFqn,
                            isStatic ? FieldAssignFact.SITE_ELSEWHERE : FieldAssignFact.SITE_INITIALIZER,
                            !isStatic);
                }
            }
        } finally {
            origins.leaveScope();
        }
        scanAnnotationDefaults(typeBinding, typeFqn);
    }

    /**
     * 注釈型のメンバの既定値を定数（K行）として拾う。
     *
     * {@code @Ann} とだけ書かれていても、{@code String value() default "svc"} の "svc" は
     * 使っている側の H行・D行・V行に焼き込まれる（{@link BindingNames#annotationsOf}）。
     * 既定値を書き換えたときに使っている側を解析し直せるよう、値をここに残す。
     * 残すのは文字列の既定値だけ（焼き込まれるのが文字列だけのため）。
     */
    private void scanAnnotationDefaults(ITypeBinding typeBinding, String typeFqn) {
        if (!typeBinding.isAnnotation()) {
            return;
        }
        IMethodBinding[] members = typeBinding.getDeclaredMethods();
        if (members == null) {
            return;
        }
        for (IMethodBinding m : members) {
            if (m.getDefaultValue() instanceof String value) {
                out.constants.add(ConstantFact.of(typeFqn, m.getName(), value));
            }
        }
    }

    /** J行の site。メソッド／コンストラクタの "name(paramSig)" */
    private String siteOf(MethodDeclaration md) {
        MethodRef ref = names.toRef(md.resolveBinding());
        return (ref == null) ? FieldAssignFact.SITE_ELSEWHERE : ref.signature();
    }

    /** フィールド宣言（V行）と、初期化子があればその代入（J行）を拾う */
    private void scanFieldDeclaration(FieldDeclaration fd, String typeFqn) {
        for (Object f : fd.fragments()) {
            if (!(f instanceof VariableDeclarationFragment frag)) {
                continue;
            }
            IVariableBinding vb = frag.resolveBinding();
            if (vb == null || !isOwnField(vb, typeFqn)) {
                continue;
            }
            out.fieldDecls.add(new FieldDeclFact(typeFqn, vb.getName(),
                    BindingNames.modifiersOf(vb.getModifiers()), names.declTypeName(vb.getType()),
                    names.annotationsOf(vb)));
            if (frag.getInitializer() != null) {
                int node = origins.nodeOf(storedValueOf(frag.getInitializer()));
                out.fieldAssigns.add(new FieldAssignFact(typeFqn, vb.getName(),
                        FieldAssignFact.SITE_INITIALIZER, node, origins.kindOfNode(node)));
                // 初期化子の中のラムダ（{@code Consumer<Dao> setter = x -> this.dao = x;}）が書くフィールドも拾う。
                // 後から走るので、生成のたびに必ず通る書き込みではない（本体が式なので definiteIn が偽になる）
                scanWrites(frag.getInitializer(), null, typeFqn, FieldAssignFact.SITE_ELSEWHERE, true);
            }
            // コンパイル時定数は、使っている側のファイルに値が焼き込まれる。
            // 値が変わったことを差分更新が知れるよう、宣言している側に値を残す（K行）
            Object constant = vb.getConstantValue();
            if (constant != null) {
                out.constants.add(ConstantFact.of(typeFqn, vb.getName(), String.valueOf(constant)));
            }
        }
    }

    /**
     * 本体の中のフィールドへの書き込みを拾う（J行）。
     *
     * @param body       メソッド・コンストラクタ・初期化ブロックの本体か、フィールド初期化子の式。無ければ何もしない
     * @param md         引数の表を作るメソッド。初期化ブロックなら null
     * @param site       この本体の site（メソッド・コンストラクタのシグネチャか初期化子）
     * @param everyBuild この本体が生成のたびに走るか（コンストラクタとインスタンス初期化ブロック）。
     *                   true のとき、必ず通るとは言えない書き込みは {@link FieldAssignFact#SITE_ELSEWHERE} にする
     */
    private void scanWrites(ASTNode body, MethodDeclaration md, String typeFqn, String site, boolean everyBuild) {
        if (body == null) {
            return;
        }
        Set<String> rewrittenParams = (md == null) ? Set.of() : rewrittenParamsOf(md, body);
        Set<ASTNode> definite = everyBuild ? definiteStatementsOf(body) : Set.of();
        origins.enterScope((md == null) ? origins.newScope() : origins.paramScopeOf(md));
        try {
            body.accept(new ASTVisitor() {
                /** 入れ子の型（匿名クラス・ローカルクラス）の本体の中にいる深さ */
                private int nestedTypes;

                @Override
                public boolean visit(AnonymousClassDeclaration n) {
                    nestedTypes++;
                    return true;
                }

                @Override
                public void endVisit(AnonymousClassDeclaration n) {
                    nestedTypes--;
                }

                @Override
                public boolean visit(TypeDeclarationStatement n) {
                    nestedTypes++;
                    return true;
                }

                @Override
                public void endVisit(TypeDeclarationStatement n) {
                    nestedTypes--;
                }

                @Override
                public boolean visit(Assignment n) {
                    boolean plain = n.getOperator() == Assignment.Operator.ASSIGN
                            && !mentionsAny(n.getRightHandSide(), rewrittenParams);
                    write(n, n.getLeftHandSide(), plain ? n.getRightHandSide() : null);
                    return true;
                }

                @Override
                public boolean visit(PostfixExpression n) {
                    write(n, n.getOperand(), null);
                    return true;
                }

                @Override
                public boolean visit(PrefixExpression n) {
                    PrefixExpression.Operator op = n.getOperator();
                    if (op == PrefixExpression.Operator.INCREMENT || op == PrefixExpression.Operator.DECREMENT) {
                        write(n, n.getOperand(), null);
                    }
                    return true;
                }

                /**
                 * 書き込み 1 件を拾う。
                 *
                 * @param value 書かれた値の式。値が分からない書き込み（複合代入・{@code ++}・書き換えられる引数を
                 *              読む右辺）なら null
                 */
                private void write(Expression writeExpr, Expression target, Expression value) {
                    IVariableBinding vb = assignedFieldOf(target);
                    if (vb == null) {
                        return;
                    }
                    if (!isOwnField(vb, typeFqn)) {
                        // 別の型のフィールド（入れ子のクラスから外側へ、外側から入れ子へ、子クラスから親へ、ほかの型のインスタンスへ）。
                        // 入れ子の型の本体の中の書き込みは、その型を拾うときに拾う（ここで拾うと、
                        // ローカルクラスのコンストラクタでの自分のフィールドへの代入まで「よそからの書き込み」にしてしまう）
                        if (nestedTypes == 0) {
                            recordElsewhere(vb);
                        }
                        return;
                    }
                    String at = site;
                    if (everyBuild && (nestedTypes > 0 || !isThisTarget(target) || !definiteIn(writeExpr, definite))) {
                        at = FieldAssignFact.SITE_ELSEWHERE;
                    }
                    int node = (value == null) ? ValueNode.NONE : origins.nodeOf(storedValueOf(value));
                    out.fieldAssigns.add(new FieldAssignFact(typeFqn, vb.getName(), at, node,
                            origins.kindOfNode(node)));
                }
            });
        } finally {
            origins.leaveScope();
        }
    }

    /**
     * 別の型の本体からの書き込みを、宣言した型の事実として残す（site は {@code ?}、値は「追跡できない」）。
     *
     * <p>読み手の判定に効くのは次の 2 つ（static は対象外。private でない final には外から書けない）。
     * <ul>
     *   <li>private なインスタンスフィールド … 値の判定（jche.graph.FieldFacts の条件 (b)）。型を問わない
     *       （基本型のフィールドの値も条件分岐の判定に使う）</li>
     *   <li>private でない参照型のインスタンスフィールド … DI の注入点の判定（ソースが引数でない値を入れるか。
     *       FieldFacts の ownValued）。子クラスのコンストラクタ・内部クラス・ほかのファイルの型から書ける。
     *       基本型のフィールドは値の判定（private か final が要る）にも注入点にも関わらないので残さない</li>
     * </ul>
     * 値は書かない（「追跡できない」）。よその型の書き込みは、その型の生成や注入の形とは限らない（{@code static void
     * wire(Svc s, Dao d) { s.dao = d; }} の {@code d} はコンテナの値ではない）ので、引数を入れる書き込みでも
     * コンテナが入れた値とは読まない（docs/spring-di-qa.md の Q15）
     */
    private void recordElsewhere(IVariableBinding vb) {
        int mods = vb.getModifiers();
        if (Modifier.isStatic(mods)) {
            return;
        }
        if (!Modifier.isPrivate(mods) && vb.getType() != null && vb.getType().isPrimitive()) {
            return;   // 型の分からないフィールドは残す（拾わない側に倒すと書き込みを見落とす）
        }
        ITypeBinding owner = vb.getDeclaringClass();
        String ownerFqn = (owner == null) ? null : names.typeNameOf(BindingNames.erasureOf(owner));
        if (ownerFqn == null || ownerFqn.isEmpty()) {
            return;
        }
        out.fieldAssigns.add(new FieldAssignFact(ownerFqn, vb.getName(), FieldAssignFact.SITE_ELSEWHERE,
                ValueNode.NONE, Origin.UNKNOWN));
    }

    /**
     * 書き込みの値として読む式。{@code java.util.Objects#requireNonNull(x)}（{@code (x, message)}・
     * {@code (x, messageSupplier)} も）は x をそのまま返す（null なら例外で抜けるので、書き込みは起きない）ので、
     * x に読み替える。入れ子（{@code requireNonNull(requireNonNull(d))}）・括弧・値を変えないキャストも剥がす。
     * それ以外の式はそのまま（値グラフが読む）。
     *
     * <p>読み替えないと、コンストラクタ注入の {@code this.dao = Objects.requireNonNull(d);} の値はメソッドの戻り値
     * （{@code M:}）になり、引数を入れる書き込みと読めない。DI の段 5 がそのフィールドを注入点から外し、経路で
     * コンストラクタ実引数を当てることもできずに CHA に戻っていた（docs/value-safety-qa.md の Q28）
     */
    static Expression storedValueOf(Expression value) {
        Expression e = value;
        for (int guard = 0; guard < 8; guard++) {
            if (OriginTracker.unwrapValue(e) instanceof MethodInvocation mi && isRequireNonNull(mi)) {
                e = (Expression) mi.arguments().get(0);
            } else {
                return e;
            }
        }
        return e;
    }

    /** {@code java.util.Objects} の static な {@code requireNonNull}（第 1 引数を返す）の呼び出しか */
    private static boolean isRequireNonNull(MethodInvocation mi) {
        if (!"requireNonNull".equals(mi.getName().getIdentifier()) || mi.arguments().isEmpty()) {
            return false;
        }
        IMethodBinding b = mi.resolveMethodBinding();
        if (b == null || !Modifier.isStatic(b.getModifiers()) || b.getDeclaringClass() == null) {
            return false;
        }
        return "java.util.Objects".equals(b.getDeclaringClass().getErasure().getQualifiedName());
    }

    /**
     * 書き込み先が、今生成しているインスタンス（{@code this}）か。修飾の無いフィールド名か {@code this.f} だけ。
     * {@code other.f}・{@code Outer.this.f}・{@code getX().f} は別のインスタンス（かもしれない）
     */
    private static boolean isThisTarget(Expression target) {
        Expression e = OriginTracker.unwrap(target);
        if (e instanceof SimpleName) {
            return true;
        }
        return e instanceof FieldAccess fa && fa.getExpression() instanceof ThisExpression t
                && t.getQualifier() == null;
    }

    /**
     * 書き込みが、本体に入るたびに必ず通る場所にあるか。本体の直下の式文そのもので
     * （条件・ループ・try・ラムダの中でなく、{@code a = b = x} の内側でもない）、
     * それより前の文に {@code return} が無いこと（{@code return} で抜ける経路ではフィールドは既定値のまま）。
     * 例外で抜ける経路はインスタンスができないので数えない
     */
    private static boolean definiteIn(Expression writeExpr, Set<ASTNode> definiteStatements) {
        return writeExpr.getParent() instanceof ExpressionStatement stmt && definiteStatements.contains(stmt);
    }

    /**
     * 本体の直下の式文のうち、それより前の文に {@code return} が無いもの（{@link #definiteIn}）。
     * 書き込みごとに前の文を読み直すと、長いコンストラクタで書き込みの数 × 文の数になるので、本体ごとに 1 回だけ求める
     */
    private static Set<ASTNode> definiteStatementsOf(ASTNode body) {
        if (!(body instanceof Block block)) {
            return Set.of();   // フィールド初期化子の式。中の書き込みは後から走るラムダの中だけ
        }
        Set<ASTNode> definite = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object o : block.statements()) {
            if (o instanceof ExpressionStatement stmt) {
                definite.add(stmt);
            }
            if (o instanceof Statement stmt && containsReturn(stmt)) {
                break;
            }
        }
        return definite;
    }

    /** 文の中に、その本体から抜ける {@code return} があるか（ラムダ・入れ子の型の中の return は数えない） */
    private static boolean containsReturn(Statement stmt) {
        boolean[] found = {false};
        stmt.accept(new ASTVisitor() {
            @Override
            public boolean visit(ReturnStatement n) {
                found[0] = true;
                return false;
            }

            @Override
            public boolean visit(LambdaExpression n) {
                return false;
            }

            @Override
            public boolean visit(AnonymousClassDeclaration n) {
                return false;
            }

            @Override
            public boolean visit(TypeDeclarationStatement n) {
                return false;
            }
        });
        return found[0];
    }

    /** 本体の中で書き換えられる引数（{@code =}・複合代入・{@code ++} / {@code --} の対象）のキー */
    private static Set<String> rewrittenParamsOf(MethodDeclaration md, ASTNode body) {
        Set<String> params = new HashSet<>();
        for (Object p : md.parameters()) {
            IVariableBinding vb = (p instanceof SingleVariableDeclaration svd) ? svd.resolveBinding() : null;
            if (vb != null && vb.getKey() != null) {
                params.add(vb.getKey());
            }
        }
        if (params.isEmpty()) {
            return Set.of();
        }
        Set<String> rewritten = new HashSet<>();
        body.accept(new ASTVisitor() {
            @Override
            public boolean visit(Assignment n) {
                mark(n.getLeftHandSide());
                return true;
            }

            @Override
            public boolean visit(PostfixExpression n) {
                mark(n.getOperand());
                return true;
            }

            @Override
            public boolean visit(PrefixExpression n) {
                mark(n.getOperand());
                return true;
            }

            private void mark(Expression target) {
                if (OriginTracker.unwrap(target) instanceof SimpleName sn
                        && sn.resolveBinding() instanceof IVariableBinding vb
                        && vb.getKey() != null && params.contains(vb.getKey())) {
                    rewritten.add(vb.getKey());
                }
            }
        });
        return rewritten;
    }

    /** 式が、キーの集合のどれかの変数を読むか */
    private static boolean mentionsAny(Expression ex, Set<String> keys) {
        if (keys.isEmpty() || ex == null) {
            return false;
        }
        boolean[] found = {false};
        ex.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName n) {
                if (n.resolveBinding() instanceof IVariableBinding vb && vb.getKey() != null
                        && keys.contains(vb.getKey())) {
                    found[0] = true;
                }
                return false;
            }
        });
        return found[0];
    }

    /**
     * 代入先がフィールドなら、そのバインディング。
     *
     * <p>フィールドを指す代入先の形は、修飾の無い名前（{@code f}）・名前で修飾したもの（{@code obj.f}・{@code Outer.f}）・
     * 式で修飾したもの（{@code this.f}・{@code Outer.this.f}・{@code ((Base) this).f}・{@code get().f}）・
     * {@code super} で修飾したもの（{@code super.f}・{@code Outer.super.f}）の 4 つ（JLS 15.26 の左辺のうち、配列の要素を
     * 除いたもの。括弧は剥がす）。{@code super.f} を拾っていなかったので、入れ子の子クラスのコンストラクタから外側の
     * 親クラスの private なフィールドへの書き込み（{@code super.dao = new DaoB();}）が J 行に載らず、読み手は初期化子の
     * 値だけが入ると判定していた（docs/value-safety-qa.md の Q26）。配列の要素（{@code f[0] = x}）はフィールドの
     * 書き込みではない（フィールドが指す配列は変わらない）
     */
    private static IVariableBinding assignedFieldOf(Expression lhs) {
        Expression e = OriginTracker.unwrap(lhs);
        if (e instanceof FieldAccess fa) {
            return fa.resolveFieldBinding();
        }
        if (e instanceof SuperFieldAccess sfa) {
            return sfa.resolveFieldBinding();
        }
        IBinding b = null;
        if (e instanceof SimpleName sn) {
            b = sn.resolveBinding();
        } else if (e instanceof QualifiedName qn) {
            b = qn.resolveBinding();
        }
        return (b instanceof IVariableBinding vb && vb.isField()) ? vb : null;
    }

    /** この型自身が宣言しているフィールドか（他の型のフィールドへの代入は、この型の事実ではない） */
    private boolean isOwnField(IVariableBinding vb, String typeFqn) {
        if (!vb.isField()) {
            return false;
        }
        ITypeBinding owner = vb.getDeclaringClass();
        if (owner == null) {
            return false;
        }
        return typeFqn.equals(names.typeNameOf(BindingNames.erasureOf(owner)));
    }
}
