// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.CharacterLiteral;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CreationReference;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionMethodReference;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.MethodReference;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.NumberLiteral;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.SuperMethodReference;
import org.eclipse.jdt.core.dom.TypeLiteral;
import org.eclipse.jdt.core.dom.TypeMethodReference;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import jche.cache.CacheFormat;
import jche.cache.MethodRef;
import jche.cache.Origin;
import jche.cache.ValueNode;

/**
 * 式の「出所」（{@link Origin}）を求める。データフロー解析で具象クラスを特定するための材料集め。
 *
 * <h2>変数の出所の表</h2>
 * メソッドに入る直前に、そのメソッド本体を1回だけ先読みして
 * 「変数 -> 出所」の表を作る（{@link #scanOrigins}）。走査しながら作らないのは、
 * 同じ変数への代入が後ろにある場合に取りこぼすため。
 * <pre>
 *     X x = new A();
 *     for (...) { x.m(); x = new B(); }
 * </pre>
 * 走査順に作ると x.m() の時点では x は A に見えるが、2周目は B。
 * 先読みして「複数の出所があれば U（不明）」に倒すことで、
 * 具象クラスを誤って1つに決め打ちすることを防ぐ。フロー非依存・安全側の方針。
 *
 * 表はメソッドの入れ子（匿名クラス・ローカルクラス）に合わせてスタックで持ち、
 * {@link FactVisitor} がメソッドの出入りで push/pop する。
 */
final class OriginTracker {

    /** 値として残す文字列の長さの上限（クラス名・識別子・条件の比較対象はどれも短い） */
    private static final int MAX_VALUE_LENGTH = 64;

    private final BindingNames names;
    /**
     * 同じ式から作る上限の無い値グラフ（dataflow 側の N 行）。
     * ここが出所の文字列を作るのと同じ場所で作ることで、2 つの表現が同じ式から出ることを保証する
     */
    private final ValueGraph graph;

    /** 現在のメソッドの変数の表のスタック（内側のメソッドから外側へ） */
    private final ArrayDeque<Scope> scopes = new ArrayDeque<>();

    /**
     * 1つのメソッドの変数の表。同じ変数について 2 つの表現を持つ。
     *
     * どちらも同じ式から作る（{@link #mergeOrigin}）。値グラフ側を別に持つのは、
     * 出所の文字列が実引数リストを持たない形（{@link Origin#head}）に落ちる場面でも、
     * 値グラフはノードの参照で入れ子を保てるため
     */
    static final class Scope {
        /** 変数のキー -> 出所（{@link Origin}） */
        final Map<String, String> origins = new HashMap<>();
        /** 変数のキー -> 値グラフのノード番号（{@link ValueNode#NONE} なら無し） */
        final Map<String, Integer> nodes = new HashMap<>();
        /**
         * ローカル変数のコレクションに詰められた要素の出所（変数のキー -> 出所）。
         * 拡張for文の変数の出所を決めるのに使う
         */
        final Map<String, String> elements = new HashMap<>();
        /**
         * ラムダ式の本体のスコープか。捕捉した変数を {@link Origin#CAPTURED} として
         * 持ち込めるのは、生成箇所が経路の1つ上に必ず来るラムダだけ（匿名クラスは
         * 生成箇所と実行箇所が繋がらないので従来どおり落とす）
         */
        boolean lambda;
    }

    /** ラムダ式の合成メソッドの名前。{@link Origin#FUNCTIONAL} の値に使う */
    private LambdaNames lambdaNames;

    OriginTracker(BindingNames names, jche.cache.FileAnalysis out) {
        this.names = names;
        this.graph = new ValueGraph(out, names, this);
    }

    /**
     * ラムダ式の名前の表を受け取る。
     *
     * 表そのものを作るのは {@link FactVisitor} で、作るときに {@link BindingNames} が要る。
     * ここは使うだけなので、生成の順番を縛らないよう後から渡す
     */
    void lambdaNames(LambdaNames lambdaNames) {
        this.lambdaNames = lambdaNames;
    }

    /**
     * 呼び出し箇所1件の値。上限付きの出所（analysis 側の C 行・U 行）と、
     * 上限の無いノード参照（dataflow 側の P 行）を<b>同じ式から一度に</b>作る。
     *
     * @param recv レシーバの式。無ければ null
     * @param args 実引数。メソッド参照のように実引数が無い形では null（出所も作らない）
     */
    CallValues valuesOf(Expression recv, List<?> args) {
        int recvNode = (recv == null) ? ValueNode.NONE : graph.nodeOf(recv);
        String argNodes = (args == null) ? "" : graph.argsOf(args, 0);
        return new CallValues(recvNode, argNodes);
    }

    /**
     * 式が列挙定数なら、その値（宣言型で修飾した形。{@code cx.Mode.FULL}）。違えば null。
     *
     * 列挙定数はコンパイル時定数ではないので {@code resolveConstantExpressionValue} では取れない。
     * 値グラフ（{@link ValueGraph}）が {@link Origin#CONST} のノードにするために使う。
     * 表記は {@link #constantOf} と同じに揃えている
     */
    String enumConstantValueOf(Expression ex) {
        Expression e = unwrapValue(ex);
        if (e instanceof SimpleName || e instanceof QualifiedName || e instanceof FieldAccess) {
            IVariableBinding vb = variableBindingOf(e);
            if (vb != null && vb.isEnumConstant()) {
                return Origin.valueOf(enumConstant(vb));
            }
        }
        return null;
    }

    // ------------------------------------------------------------
    // スコープ（変数 -> 出所 の表）
    // ------------------------------------------------------------

    /** 空の変数の表 */
    Scope newScope() {
        return new Scope();
    }

    void enterScope(Scope scope) {
        scopes.push(scope);
    }

    void leaveScope() {
        if (!scopes.isEmpty()) {
            scopes.pop();
        }
    }

    /**
     * 引数を出所として登録した、メソッド用の初期スコープ。
     *
     * 引数はノードを登録しない。引数の出所（{@code A:位置}）は実引数リストを持たないので、
     * 値グラフ側は葉として作り直しても同じものになる
     */
    Scope paramScopeOf(MethodDeclaration node) {
        Scope scope = new Scope();
        List<?> params = node.parameters();
        for (int i = 0; i < params.size(); i++) {
            if (!(params.get(i) instanceof SingleVariableDeclaration param)) {
                continue;
            }
            IVariableBinding vb = param.resolveBinding();
            if (vb != null && vb.getKey() != null) {
                scope.origins.put(vb.getKey(), Origin.of(Origin.PARAM, String.valueOf(i)));
            }
        }
        return scope;
    }

    /**
     * ラムダ式の引数を出所として登録した、合成メソッド用の初期スコープ。
     *
     * {@link #paramScopeOf} と同じ形にする。ラムダの引数は、型を書く形
     * （{@code (Dao d) -> ...}）と推論に任せる形（{@code d -> ...}）で
     * AST のノードが違うので、両方から束縛を取る。
     *
     * 捕捉した変数（外側のメソッドのローカル）は、このスコープには入れない。
     * スコープはスタックなので、外側は1つ外のスコープから引ける
     * （{@link #localOriginOf} が実質的finalのときだけ持ち込む）。
     */
    Scope lambdaParamScope(List<?> params) {
        Scope scope = new Scope();
        scope.lambda = true;
        for (int i = 0; i < params.size(); i++) {
            Object param = params.get(i);
            IVariableBinding vb = (param instanceof SingleVariableDeclaration typed)
                    ? typed.resolveBinding()
                    : (param instanceof VariableDeclarationFragment inferred)
                            ? inferred.resolveBinding() : null;
            if (vb != null && vb.getKey() != null) {
                scope.origins.put(vb.getKey(), Origin.of(Origin.PARAM, String.valueOf(i)));
            }
        }
        return scope;
    }

    /**
     * 本体を先読みして、ローカル変数の出所を集める。
     *
     * 同じ変数に出所の違う代入が複数あれば U（不明）にする。
     * ローカル変数どうしの別名付け（{@code Y y = x;}）は、先読みが1回のため
     * x が y より後ろで宣言されていると追えない。安全側（U）に倒れるだけなので
     * 実害は「解決できない」に留まる。
     */
    Scope scanOrigins(ASTNode body, Scope scope) {
        if (body == null) {
            return scope;
        }
        // 先読み中は originOf() が参照するスコープを差し替える
        enterScope(scope);
        try {
            body.accept(new ASTVisitor() {
                @Override
                public boolean visit(VariableDeclarationFragment n) {
                    IVariableBinding vb = n.resolveBinding();
                    if (vb != null && !vb.isField()) {
                        mergeOrigin(scope, vb.getKey(), n.getInitializer());
                    }
                    return true;
                }

                @Override
                public boolean visit(Assignment n) {
                    if (!(n.getLeftHandSide() instanceof SimpleName lhs)) {
                        return true;
                    }
                    if (lhs.resolveBinding() instanceof IVariableBinding vb && !vb.isField()) {
                        mergeOrigin(scope, vb.getKey(), n.getRightHandSide());
                    }
                    return true;
                }

                @Override
                public boolean visit(EnhancedForStatement n) {
                    bindLoopVariable(scope, body, n);
                    return true;
                }
            });
        } finally {
            leaveScope();
        }
        return scope;
    }

    /**
     * {@code list.add(() -> ...)} のように、そのコレクションへ詰められた要素の出所。
     *
     * 本体を走査して集める。拡張for文があるときだけ呼ぶので、
     * ループの無いメソッドに走査を増やさない。結果は変数ごとに覚えておく。
     *
     * 出所が1つに定まらなければ U（不明）。拾えるのは「レシーバがそのローカル変数」の形だけで、
     * フィールドのコレクションや、他のメソッドへ渡してから詰める形は追わない（安全側）。
     */
    private String elementOriginOf(Scope scope, ASTNode body, String varKey) {
        String known = scope.elements.get(varKey);
        if (known != null) {
            return known;
        }
        String[] merged = {null};
        body.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation n) {
                if (!(unwrap(n.getExpression()) instanceof SimpleName recv)
                        || !(recv.resolveBinding() instanceof IVariableBinding vb)
                        || !varKey.equals(vb.getKey())) {
                    return true;
                }
                int at = elementArgumentOf(n.getName().getIdentifier(), n.arguments().size());
                if (at < 0 || !(n.arguments().get(at) instanceof Expression element)) {
                    return true;
                }
                String origin = originOf(element);
                if (origin == null) {
                    origin = Origin.UNKNOWN_S;
                }
                merged[0] = (merged[0] == null || merged[0].equals(origin))
                        ? origin : Origin.UNKNOWN_S;
                return true;
            }
        });
        String result = (merged[0] == null) ? Origin.UNKNOWN_S : merged[0];
        scope.elements.put(varKey, result);
        return result;
    }

    /**
     * 「要素を足す」メソッドで、要素そのものが何番目の実引数か。違うメソッドなら -1。
     *
     * 足し方が分かっているものだけを見る。{@code add(int, E)} のように
     * 位置を指定する形も要素は末尾なので同じ扱いにできる
     */
    private static int elementArgumentOf(String name, int argCount) {
        boolean adds = switch (name) {
            case "add", "addLast", "addFirst", "offer", "offerLast", "offerFirst",
                 "push", "set", "put" -> true;
            default -> false;
        };
        if (!adds || argCount < 1 || argCount > 2) {
            return -1;
        }
        // 1引数なら要素そのもの。2引数（位置や鍵を伴う形）なら末尾が要素
        return argCount - 1;
    }

    /**
     * 拡張for文の変数に、回しているコレクションの要素の出所を当てる。
     * {@code for (Runnable t : tasks) t.run();} の {@code t} を追えるようにする
     */
    private void bindLoopVariable(Scope scope, ASTNode body, EnhancedForStatement n) {
        if (!(unwrap(n.getExpression()) instanceof SimpleName source)
                || !(source.resolveBinding() instanceof IVariableBinding sourceVar)
                || sourceVar.isField()) {
            return;
        }
        String element = elementOriginOf(scope, body, sourceVar.getKey());
        if (Origin.isUnknown(element)) {
            return;
        }
        IVariableBinding loopVar = n.getParameter().resolveBinding();
        if (loopVar != null && loopVar.getKey() != null) {
            scope.origins.put(loopVar.getKey(), element);
        }
    }

    /** 同じ変数に別の出所が現れたら U（不明）に落とす。値グラフ側も同じ判断で揃える */
    private void mergeOrigin(Scope scope, String varKey, Expression value) {
        if (varKey == null) {
            return;
        }
        String origin = originOf(value);
        if (origin == null) {
            origin = Origin.UNKNOWN_S;
        }
        String prev = scope.origins.get(varKey);
        scope.origins.put(varKey, (prev == null || prev.equals(origin)) ? origin : Origin.UNKNOWN_S);

        int node = graph.nodeOf(value);
        Integer prevNode = scope.nodes.get(varKey);
        scope.nodes.put(varKey,
                (prevNode == null || prevNode == node) ? node : ValueNode.NONE);
    }

    // ------------------------------------------------------------
    // 式の出所
    // ------------------------------------------------------------

    /**
     * 式の出所（{@link Origin}）。追跡できなければ null。
     *
     * ここで返せるのは「どこから来たか」までで、具象型が確定するとは限らない。
     * A（引数）は呼び出し元、M（戻り値）はその宣言のreturnを見て初めて決まる。
     */
    String originOf(Expression ex) {
        return originOf(ex, 0);
    }

    /** @param depth レシーバの入れ子の深さ（{@link Origin#MAX_RECEIVER_DEPTH} で打ち切る） */
    private String originOf(Expression ex, int depth) {
        Expression e = unwrap(ex);
        if (e == null) {
            return null;
        }
        if (e instanceof ClassInstanceCreation cic) {
            String type = names.createdTypeOf(cic);
            // 実引数も付ける。コンストラクタ注入されたフィールドを追うのに要る
            return (type == null) ? null : Origin.of(Origin.NEW, type, argOriginsOf(cic.arguments()));
        }
        if (e instanceof MethodInvocation mi) {
            return invocationOriginOf(mi, depth);
        }
        String functional = functionalOriginOf(e);
        if (functional != null) {
            return functional;
        }
        if (e instanceof StringLiteral literal) {
            return classNameLiteral(literal.getLiteralValue());
        }
        if (e instanceof TypeLiteral typeLiteral) {
            // X.class。リフレクションでクラスを指定する形
            ITypeBinding tb = typeLiteral.getType().resolveBinding();
            String n = (tb == null) ? "" : names.declTypeName(tb);
            return n.isEmpty() ? null : Origin.of(Origin.CLASS, n);
        }
        if (e instanceof SimpleName || e instanceof QualifiedName) {
            IBinding b = (e instanceof SimpleName sn) ? sn.resolveBinding()
                    : ((QualifiedName) e).resolveBinding();
            if (b instanceof IVariableBinding vb) {
                return variableOriginOf(vb);
            }
        }
        if (e instanceof FieldAccess fa) {
            IVariableBinding vb = fa.resolveFieldBinding();
            if (vb != null) {
                String origin = variableOriginOf(vb);
                if (origin != null) {
                    return origin;
                }
            }
        }
        // ここまでで決まらなければ、コンパイル時定数の「値」として拾う。
        // 具象型は分からないが、条件分岐の判定には使える（jche.cache.Guard）
        return constantOf(e);
    }

    /**
     * 式がラムダ式かメソッド参照なら、実際に動くメソッドを指す出所（{@link Origin#FUNCTIONAL}）。
     *
     * ラムダは本体を持つ合成メソッド、メソッド参照は参照先のメソッドそのもの。
     * これがあると「関数型インターフェースの変数に何が入っているか」を
     * 他の値と同じように追える（{@code Runnable r = this::helper; r.run();} が繋がる）。
     */
    String functionalOriginOf(Expression ex) {
        Expression e = unwrap(ex);
        if (e instanceof LambdaExpression lambda) {
            MethodRef body = (lambdaNames == null) ? null : lambdaNames.of(lambda);
            return (body == null) ? null : Origin.of(Origin.FUNCTIONAL, body.key());
        }
        if (e instanceof MethodReference ref) {
            MethodRef target = names.toRef(methodBindingOf(ref));
            return (target == null) ? null : Origin.of(Origin.FUNCTIONAL, target.key());
        }
        return null;
    }

    /** メソッド参照の4つの形から、参照先のバインディングを取る */
    private static IMethodBinding methodBindingOf(MethodReference ref) {
        if (ref instanceof ExpressionMethodReference expr) {
            return expr.resolveMethodBinding();
        }
        if (ref instanceof TypeMethodReference type) {
            return type.resolveMethodBinding();
        }
        if (ref instanceof SuperMethodReference sup) {
            return sup.resolveMethodBinding();
        }
        if (ref instanceof CreationReference creation) {
            return creation.resolveMethodBinding();
        }
        return null;
    }

    /**
     * 式がコンパイル時定数（または列挙定数）なら、その値の出所（{@link Origin#CONST}）。
     *
     * 条件分岐の判定に使う値だけを拾う。{@code true} / {@code 3} / {@code 'a'} のような
     * リテラル、{@code static final} の定数、列挙定数（{@code Color.RED} は "RED"）。
     * 列挙定数を単純名にするのは、switch の case ラベルが単純名で書かれるため。
     */
    String constantOf(Expression ex) {
        Expression e = unwrapValue(ex);
        if (e == null) {
            return null;
        }
        if (e instanceof BooleanLiteral b) {
            return Origin.of(Origin.CONST, String.valueOf(b.booleanValue()));
        }
        if (e instanceof NumberLiteral n) {
            return numericConst(n.getToken());
        }
        if (e instanceof CharacterLiteral c) {
            return charConst(c.charValue());
        }
        if (e instanceof StringLiteral s) {
            return valueConst(s.getLiteralValue());
        }
        if (e instanceof SimpleName || e instanceof QualifiedName || e instanceof FieldAccess) {
            IVariableBinding vb = variableBindingOf(e);
            if (vb == null) {
                return null;
            }
            if (vb.isEnumConstant()) {
                return enumConstant(vb);
            }
            return constantText(vb.getConstantValue());
        }
        return null;
    }

    /**
     * コンパイル時定数の値を出所の表記にする。拾えないものは null。
     *
     * <h4>char は数値にする（JLS 5.6.2 二項数値昇格）</h4>
     * {@code char} と {@code int} を {@code ==} で比べると、どちらも {@code int} に昇格してから
     * 比較される。{@code 'A' == 65} は真である。値を文字のまま持つと、この 2 つが
     * 別の値に見えて「条件が成立しない」と誤判定し、実際には通る経路を落としてしまう。
     * {@code byte} / {@code short} / {@code int} / {@code long} と同じ 10 進表記に揃えることで、
     * 整数系どうしはどの組み合わせでも一致を判定できる。
     *
     * <h4>浮動小数は拾わない</h4>
     * {@code 1} と {@code 1.0} と {@code 1.0f} は同じ値だが表記が違い、文字列の一致では
     * 判定できない。誤って打ち切るより判定しないほうがよい（{@link #numericConst} と同じ方針）。
     */
    private static String constantText(Object constant) {
        if (constant == null || constant instanceof Double || constant instanceof Float) {
            return null;
        }
        if (constant instanceof Character c) {
            return charConst(c.charValue());
        }
        return valueConst(String.valueOf(constant));
    }

    /** char の定数値。整数系と突き合わせられるよう数値にする（{@link #constantText} 参照） */
    private static String charConst(char value) {
        return Origin.of(Origin.CONST, String.valueOf((int) value));
    }

    private static IVariableBinding variableBindingOf(Expression e) {
        IBinding b = null;
        if (e instanceof SimpleName sn) {
            b = sn.resolveBinding();
        } else if (e instanceof QualifiedName qn) {
            b = qn.resolveBinding();
        } else if (e instanceof FieldAccess fa) {
            b = fa.resolveFieldBinding();
        }
        return (b instanceof IVariableBinding vb) ? vb : null;
    }

    /**
     * 列挙定数の値。宣言型で修飾する（{@code cx.Mode.FULL}）。
     * 単純名だけだと、別の列挙型の同名定数や同じ綴りの文字列と一致してしまう。
     */
    private String enumConstant(IVariableBinding vb) {
        ITypeBinding owner = vb.getDeclaringClass();
        String ownerFqn = (owner == null) ? null : names.typeNameOf(BindingNames.erasureOf(owner));
        return (ownerFqn == null || ownerFqn.isEmpty())
                ? null : Origin.of(Origin.CONST, ownerFqn + "." + vb.getName());
    }

    /** 数値リテラルは表記の揺れ（1L / 0x10 / 1_000）を値に正規化する。できなければ拾わない */
    private static String numericConst(String token) {
        String t = token.replace("_", "");
        try {
            if (t.indexOf('.') >= 0 || t.indexOf('e') > 0 || t.indexOf('E') > 0
                    || t.endsWith("f") || t.endsWith("F") || t.endsWith("d") || t.endsWith("D")) {
                return null;   // 浮動小数の一致判定はしない
            }
            if (t.endsWith("l") || t.endsWith("L")) {
                t = t.substring(0, t.length() - 1);
            }
            return Origin.of(Origin.CONST, String.valueOf(Long.decode(t)));
        } catch (NumberFormatException ignore) {
            return null;
        }
    }

    /**
     * 値として持てる長さ・内容のものだけ（長い文字列でキャッシュを膨らませない）。
     *
     * タブ・改行などの制御文字を含む値は拾わない（{@code char sep = '\t';} や
     * 改行を含む文字列定数）。キャッシュはタブ区切りの行形式で、{@link jche.cache.Guard} の
     * アトムも制御文字を区切りに使っているため、値がそれらを含むと行が読み戻せなくなる。
     * 書き出すときに空白へ置き換えるだけだと {@code '\t'} と {@code ' '} が同じ値に見え、
     * 「条件が成立しない」と誤って打ち切りうるので、値そのものを拾わない
     * （＝その条件は判定しない）方に倒す。
     */
    private static String valueConst(String value) {
        if (value == null || value.length() > MAX_VALUE_LENGTH
                || CacheFormat.hasControlChar(value)) {
            return null;
        }
        return Origin.of(Origin.CONST, value);
    }

    /** メソッド呼び出しの出所（M:）。実引数の出所・実引数の数・レシーバの出所を付ける */
    private String invocationOriginOf(MethodInvocation mi, int depth) {
        String reflected = reflectiveOriginOf(mi);
        if (reflected != null) {
            return reflected;
        }
        MethodRef ref = names.toRef(mi.resolveMethodBinding());
        if (ref == null) {
            return null;
        }
        // 実引数も付ける。クラス名の文字列を受け取るファクトリを追うのに要る。
        // 実引数の数も付ける（出所が分からず省いた引数と、引数が無いことを区別するため）
        String args = argOriginsOf(mi.arguments());
        String count = Origin.ARG_COUNT + "=" + mi.arguments().size();
        args = args.isEmpty() ? count : args + ";" + count;
        // レシーバの出所も、上限の段数まで入れ子で付ける。
        // clazz.getMethod("run").invoke(obj) のような連鎖を読み手が辿るのに要る
        if (depth < Origin.MAX_RECEIVER_DEPTH && mi.getExpression() != null) {
            String recv = originOf(mi.getExpression(), depth + 1);
            if (recv != null) {
                args = args + ";" + Origin.RECEIVER + "=" + Origin.nest(recv);
            }
        }
        return Origin.of(Origin.RETURN, ref.key(), args);
    }

    /** ローカル変数・引数はスコープ表から、フィールドは宣言型から出所を決める */
    private String variableOriginOf(IVariableBinding vb) {
        if (!vb.isField()) {
            return localOriginOf(vb);
        }
        if (vb.isEnumConstant()) {
            // 列挙定数は「値」。switch の case ラベルや == の比較対象になるので、
            // フィールドの出所（F:）ではなく定数の値（V:）として持つ。
            // F: にしても、列挙定数はコンストラクタ注入されたフィールドの表に
            // 載らないため具象型は決まらず、失うものが無い
            return enumConstant(vb);
        }
        // static final String などのコンパイル時定数は、その文字列そのもの。
        // Factory.create(Names.USER_DAO) のような書き方を追えるようにする
        Object constant = vb.getConstantValue();
        if (constant instanceof String s) {
            return classNameLiteral(s);
        }
        ITypeBinding owner = vb.getDeclaringClass();
        if (owner == null) {
            return null;
        }
        String ownerFqn = names.typeNameOf(BindingNames.erasureOf(owner));
        return (ownerFqn == null) ? null : Origin.of(Origin.FIELD, ownerFqn + "#" + vb.getName());
    }

    /**
     * ローカル変数・引数の出所。
     *
     * まず今のメソッドのスコープを見る。無ければ外側のメソッドのスコープへ辿る。
     * 匿名クラス・ローカルクラスのメソッドは MethodDeclaration なので独自の
     * スコープを持つが、その中から囲みメソッドの変数を参照できる（捕捉）。
     * <pre>
     *     void run() {
     *         Dao dao = new UserDaoImpl();
     *         exec(new Task() {
     *             public void run() { dao.select(); }   // ← ここ
     *         });
     *     }
     * </pre>
     * 外側の値を持ち込めるのは、<b>捕捉できる変数が final か実質的final
     * （effectively final）だと言語仕様が保証しているから</b>。捕捉した後で
     * 中身が別のインスタンスに差し替わることはないので、囲みメソッドで
     * 分かった出所がそのまま通用する。実質的finalでない変数はそもそも
     * 捕捉できずコンパイルが通らないが、判断の根拠を実装にも残すため明示的に確認する。
     */
    private String localOriginOf(IVariableBinding vb) {
        String key = vb.getKey();
        // 変数が見つかるまでに越えたスコープの境界。0 なら今のメソッドの中
        int crossed = 0;
        // 越えた境界が「ラムダの本体」だけか。1つだけなら生成箇所が経路の1つ上に来る
        boolean onlyLambda = true;
        for (Scope scope : scopes) {
            String origin = scope.origins.get(key);
            if (origin == null) {
                onlyLambda &= scope.lambda;
                crossed++;          // 今のメソッドには無い。1つ外へ
                continue;
            }
            if (Origin.isUnknown(origin)) {
                return null;
            }
            if (crossed == 0) {
                return origin;
            }
            if (!isEffectivelyFinal(vb)) {
                return null;
            }
            // ラムダの境界を1つだけ越えて捕捉した引数は、生成箇所のフレームの引数として持ち込める
            if (crossed == 1 && onlyLambda && Origin.kindOf(origin) == Origin.PARAM) {
                return Origin.of(Origin.CAPTURED, Origin.valueOf(origin));
            }
            return frameIndependent(origin);
        }
        return null;
    }

    /**
     * ローカル変数の値グラフのノード番号。引けなければ {@link ValueNode#NONE}。
     *
     * 今のメソッドのスコープにあるものだけを返す。<b>外側のメソッドから捕捉した変数は返さない</b>
     * （{@link #frameIndependent} と同じ理由で、入れ子の中の {@code A:}（引数）は
     * 別のフレームへ持ち込むと別物を指してしまう）。呼び出し側は頭だけの葉に落とす
     */
    int localNodeOf(Expression ex) {
        IVariableBinding vb = variableBindingOf(unwrap(ex));
        if (vb == null || vb.isField()) {
            return ValueNode.NONE;
        }
        String key = vb.getKey();
        boolean enclosing = false;
        for (Scope scope : scopes) {
            if (scope.origins.containsKey(key)) {
                // 外側のメソッドから捕捉した変数は持ち込まない
                Integer node = scope.nodes.get(key);
                return (enclosing || node == null) ? ValueNode.NONE : node;
            }
            enclosing = true;   // 今のメソッドには無い。1つ外へ
        }
        return ValueNode.NONE;
    }

    /** final または実質的final（＝もう中身が変わらないと言い切れる） */
    private static boolean isEffectivelyFinal(IVariableBinding vb) {
        return vb.isEffectivelyFinal() || Modifier.isFinal(vb.getModifiers());
    }

    /**
     * 別のメソッドの中へ持ち込んでも意味が変わらない出所だけを残す。
     *
     * T（newされた具象型）とM（メソッドの戻り値）は、どこから見ても同じものを指す。
     * 一方 A（引数）とF（フィールド）は「今実行しているメソッドの引数」
     * 「今のオブジェクトのフィールド」という相対的な意味なので、匿名クラスの中へ
     * 持ち込むと別物を指してしまう（匿名クラスの run() には引数が無い、など）。
     * 捕捉された引数を追うには匿名クラスの生成箇所まで遡る必要があり、
     * それは現在の経路の持ち方では表現できないため、ここで落とす。
     * ラムダだけは生成箇所からの辺が必ず経路の1つ上に来るので、
     * 引数を {@link Origin#CAPTURED} として持ち込める（{@link #localOriginOf}）。
     *
     * 持ち込む際は実引数リスト（|0=A:0 等）も剥がす。リストの中の A（引数）も
     * 「捕捉した時点のメソッドの引数」という相対的な意味であり、付けたまま
     * 持ち込むと、解決時に<b>今歩いているメソッド</b>（匿名クラスのメソッド）の
     * 引数を誤って当てる。実測では、ファクトリ経由で捕捉した変数の呼び出しが
     * 匿名メソッドの引数の型に DATAFLOW_FACTORY で誤確定し、正しい実装の行が
     * 出力から消えた。頭（T:型 / M:メソッドキー）だけなら、どのフレームから
     * 見ても同じものを指すので安全に持ち込める。
     */
    private static String frameIndependent(String origin) {
        char kind = Origin.kindOf(origin);
        return (kind == Origin.NEW || kind == Origin.RETURN) ? Origin.head(origin) : null;
    }

    /**
     * 文字列リテラルのうち、完全修飾クラス名の形か識別子の形をしたものだけ出所にする。
     *
     * ログの文言やSQLまで記録すると、キャッシュが文字列で埋まる割に
     * 何の役にも立たない。クラス名は「ドットを含み、各要素が識別子で、最後の要素が
     * 英大文字で始まる」、メソッド名・フィールド名は「識別子1つ（64文字以内）」を
     * 条件にする。誤って拾っても、解決時にその名前がプロジェクトに無ければ
     * 使われないだけで害はない。
     */
    private static String classNameLiteral(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_VALUE_LENGTH) {
            return null;
        }
        if (value.indexOf('.') < 0) {
            // 識別子の形。getMethod("run") のようにメソッド名として渡されるもの
            if (!Character.isJavaIdentifierStart(value.charAt(0))) {
                return null;
            }
            for (int i = 1; i < value.length(); i++) {
                if (!Character.isJavaIdentifierPart(value.charAt(i))) {
                    return null;
                }
            }
            return Origin.of(Origin.LITERAL, value);
        }
        int last = 0;
        for (int i = 0; i <= value.length(); i++) {
            if (i < value.length() && value.charAt(i) != '.') {
                char c = value.charAt(i);
                if (!Character.isJavaIdentifierPart(c) && c != '$') {
                    return null;
                }
                continue;
            }
            if (i == last) {
                return null;   // 空の要素（先頭・末尾・連続するドット）
            }
            if (!Character.isJavaIdentifierStart(value.charAt(last))) {
                return null;
            }
            last = i + 1;
        }
        int dot = value.lastIndexOf('.');
        return Character.isUpperCase(value.charAt(dot + 1)) ? Origin.of(Origin.LITERAL, value) : null;
    }

    /**
     * {@code Class.forName(x).newInstance()} 系の生成を出所にする。
     *
     * 対応する形:
     * <pre>
     *   Class.forName(x).newInstance()
     *   Class.forName(x).getDeclaredConstructor().newInstance()
     *   Class.forName(x).getConstructor().newInstance()
     * </pre>
     * x が文字列リテラルなら型が確定するので T、
     * 囲みメソッドの引数なら「その引数で名前指定された型」として C を返す。
     * C は、そのメソッドを呼んでいる側の実引数を見て初めて確定する。
     */
    String reflectiveOriginOf(MethodInvocation mi) {
        if (!"newInstance".equals(mi.getName().getIdentifier())) {
            return null;
        }
        Expression recv = unwrap(mi.getExpression());
        // getDeclaredConstructor() / getConstructor() を挟む形を1段だけ剥がす
        if (recv instanceof MethodInvocation ctorGetter) {
            String n = ctorGetter.getName().getIdentifier();
            if ("getDeclaredConstructor".equals(n) || "getConstructor".equals(n)) {
                recv = unwrap(ctorGetter.getExpression());
            }
        }
        if (!(recv instanceof MethodInvocation forName)
                || !"forName".equals(forName.getName().getIdentifier())) {
            return null;
        }
        IMethodBinding fb = forName.resolveMethodBinding();
        if (fb == null || fb.getDeclaringClass() == null
                || !"java.lang.Class".equals(fb.getDeclaringClass().getQualifiedName())) {
            return null;
        }
        if (forName.arguments().isEmpty()) {
            return null;
        }
        String argOrigin = originOf(unwrap((Expression) forName.arguments().get(0)));
        if (Origin.kindOf(argOrigin) == Origin.LITERAL) {
            // クラス名が文字列で確定している。生成される型そのものが分かる
            return Origin.of(Origin.NEW, Origin.valueOf(argOrigin));
        }
        if (Origin.kindOf(argOrigin) == Origin.PARAM) {
            return Origin.of(Origin.REFLECT, Origin.valueOf(argOrigin));
        }
        return null;
    }

    /**
     * <b>値の判定に使うために</b>括弧とキャストを剥がす。値を変えうるキャストに
     * 当たったら null（＝この式の値は判定に使えない）。
     *
     * <h4>{@link #unwrap} と分けている理由</h4>
     * 参照型どうしのキャスト（JLS 5.5）は同じインスタンスを指し続けるので、
     * <b>どのメソッドが動くか</b>を追う {@link #unwrap} は剥がしてよい。
     * しかしプリミティブの変換（JLS 5.1.2 拡大 / 5.1.3 縮小）とボックス化・非ボックス化
     * （5.1.7 / 5.1.8）は<b>値そのものを変える</b>。
     * <pre>
     *     void run(int mode) { if ((byte) mode == 44) target(); }
     *     run(300);          // (byte)300 == 44 は真
     * </pre>
     * ここでキャストを剥がすと「mode（=300）と 44 の比較」になり、実際には通る経路を
     * 「呼ばれない」と判定して、その先の階層をまるごと落としてしまう。
     * 判定できないほうへ倒すのが安全側である。
     *
     * 型が取れないときも「変わりうる」とみなす。分からないものを畳まない側に倒す。
     */
    static Expression unwrapValue(Expression ex) {
        Expression e = ex;
        for (int guard = 0; guard < 8; guard++) {
            if (e instanceof ParenthesizedExpression p) {
                e = p.getExpression();
            } else if (e instanceof CastExpression c) {
                if (changesValue(c)) {
                    return null;
                }
                e = c.getExpression();
            } else {
                return e;
            }
        }
        return e;
    }

    /** そのキャストが値を変えうるか。プリミティブが絡めば変えうる（JLS 5.1.2 / 5.1.3 / 5.1.7 / 5.1.8） */
    private static boolean changesValue(CastExpression cast) {
        ITypeBinding to = cast.getType().resolveBinding();
        ITypeBinding from = cast.getExpression().resolveTypeBinding();
        return to == null || from == null || to.isPrimitive() || from.isPrimitive();
    }

    /**
     * 括弧とキャストを剥がす。<b>どのインスタンスを指すか</b>は変わらないので、
     * 出所（どのメソッドが動くか）の追跡にはこちらを使う。
     * 値の一致を判定する用途では {@link #unwrapValue} を使うこと
     */
    static Expression unwrap(Expression ex) {
        Expression e = ex;
        for (int guard = 0; guard < 8; guard++) {
            if (e instanceof ParenthesizedExpression p) {
                e = p.getExpression();
            } else if (e instanceof CastExpression c) {
                e = c.getExpression();
            } else {
                return e;
            }
        }
        return e;
    }

    /** 実引数の出所を "位置=出所;位置=出所" にまとめる。追跡できない引数は載せない */
    String argOriginsOf(List<?> args) {
        if (args == null || args.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (!(args.get(i) instanceof Expression arg)) {
                continue;
            }
            // 引数の出所は入れ子にしない（実引数リストが付いていたら剥がす）
            String origin = Origin.head(originOf(arg));
            if (origin == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(i).append('=').append(origin);
        }
        return sb.toString();
    }
}
