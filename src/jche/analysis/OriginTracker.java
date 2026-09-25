// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.SuperMethodReference;
import org.eclipse.jdt.core.dom.ThisExpression;
import org.eclipse.jdt.core.dom.TypeLiteral;
import org.eclipse.jdt.core.dom.TypeMethodReference;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import jche.cache.CacheFormat;
import jche.cache.MethodRef;
import jche.cache.RecvKind;
import jche.cache.Origin;
import jche.cache.ValueNode;

/**
 * 式の「出所」（{@link Origin}）を求める。データフロー解析で具象クラスを特定するための材料集め。
 *
 * <h2>変数の出所の表</h2>
 * メソッドに入る直前に、そのメソッド本体を先読みして
 * 「変数 -> 出所」の表を作る（{@link #scanOrigins}）。走査しながら作らないのは、
 * 同じ変数への代入が後ろにある場合に取りこぼすため。
 * <pre>
 *     X x = new A();
 *     for (...) { x.m(); x = new B(); }
 * </pre>
 * 走査順に作ると x.m() の時点では x は A に見えるが、2周目は B。
 * 先読みして「複数の出所があれば U（不明）」に倒すことで、
 * 具象クラスを誤って1つに決め打ちすることを防ぐ。フロー非依存・安全側の方針。
 * 別の変数へ写した値（{@code X y = x;}）にも後ろの代入が届くよう、先読みは表が変わらなくなるまで繰り返す。
 *
 * 表はメソッドの入れ子（匿名クラス・ローカルクラス）に合わせてスタックで持ち、
 * {@link FactVisitor} がメソッドの出入りで push/pop する。
 */
final class OriginTracker {

    /** 値として残す文字列の長さの上限（クラス名・識別子・条件の比較対象はどれも短い） */
    private static final int MAX_VALUE_LENGTH = 64;

    private final BindingNames names;
    /**
     * 同じ式から作る上限の無い値グラフ（キャッシュの N 行）。
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
     * 呼び出し箇所1件の値（上限の無いノード参照。キャッシュでは C 行・U 行の末尾の列）を作る。
     *
     * @param recv レシーバの式。無ければ null
     * @param args 実引数。メソッド参照のように実引数が無い形では null（出所も作らない）
     */
    CallValues valuesOf(Expression recv, List<?> args) {
        int recvNode = (recv == null) ? ValueNode.NONE : graph.nodeOf(recv);
        String argNodes = (args == null) ? "" : graph.argsOf(args);
        return new CallValues(recvNode, argNodes);
    }

    /**
     * 式の値グラフのノード番号（上限の無い形。{@link ValueGraph#nodeOf}）。追跡できなければ {@link ValueNode#NONE}。
     *
     * キャッシュの値（戻り値の R 行・フィールドへの代入の J 行・条件の subject）は、どれもここで作ったノードを指す。
     * 今のスコープ（変数の表）で求めるので、呼ぶ側はその式を見ているスコープの中で呼ぶこと
     */
    int nodeOf(Expression ex) {
        return graph.nodeOf(ex);
    }

    /** ノードの種別（{@link Origin} の種別の文字）。ノードが無ければ {@link Origin#UNKNOWN} */
    char kindOfNode(int node) {
        return graph.kindOf(node);
    }

    /** 定数の値（{@link Origin#CONST}）のノード。同じ値なら同じノード */
    int constantNodeOf(String value) {
        return graph.constantValueNode(value);
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

    /**
     * スコープを積む。値グラフの式ごとの控え（{@link ValueGraph#forgetExpressions}）はここで捨てる。
     * 同じ式でも、見えている変数の表が変われば値が変わりうるため
     */
    void enterScope(Scope scope) {
        scopes.push(scope);
        graph.forgetExpressions();
    }

    void leaveScope() {
        if (!scopes.isEmpty()) {
            scopes.pop();
        }
        graph.forgetExpressions();
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
     *
     * <h4>表が変わらなくなるまで読み直す</h4>
     * 代入の右辺の出所は、先読みの<b>その時点</b>の表で求める。ループの中では、ソースで後ろにある
     * 代入が前の行に届く。
     * <pre>
     *     int n = 0;
     *     for (String s : items) { int copy = n; if (copy != 0) notFirst(); n++; }
     *
     *     Dao x = new DaoA();
     *     for (...) { Dao y = x; y.find(); x = new DaoB(); }
     * </pre>
     * 1 回読むだけでは、{@code copy} は {@code n} が {@code 0} だった時点の値（{@code V:0}）、
     * {@code y} は {@code x} が {@code DaoA} だった時点の値のまま残る。{@code n++} / {@code x = new DaoB()} で
     * {@code n} / {@code x} が U になっても、写した先には伝わらない。読み手は {@code copy != 0} を偽と判定して
     * {@code notFirst} を落とし、{@code y.find()} を {@code DaoA} だけに絞って {@code DaoB} を落とす。
     *
     * そこで、表（出所の文字列とノード）が変わらなくなるまで本体を読み直す（{@link Scan}）。
     * 2 回目以降は、前の回で揃った表で右辺を求めるので、後ろの代入が前の行に届く。
     * 表の値は「最初の値」から U（ノードは {@link ValueNode#NONE}）へ動くだけで戻らないので、必ず止まる。
     * 念のため回数に上限（{@link #MAX_SCAN_PASSES}）を置き、使い切ったらこの先読みで書いた変数を
     * すべて U にする（安全側）。
     *
     * 1 回目で、どの変数にも 2 回目の書き込み（再代入・{@code ++}・複合代入）が無ければ、読み直さない。
     * ローカル変数は宣言より前では読めないので、1 回しか書かれない変数は、読んだ時点で既に最後の値である。
     *
     * 読み直すたびに、値グラフの式ごとの控え（{@link ValueGraph#forgetExpressions}）と
     * コレクションの要素の出所（{@link Scope#elements}）を捨てる。どちらも前の回の途中の表から作ったもので、
     * 残すと古い値（{@code x} が {@code DaoA} だけだった頃）をそのまま使ってしまう。
     *
     * <h4>値を書き換えるのは {@code =} だけではない</h4>
     * 複合代入（{@code n += 1}）と {@code ++} / {@code --} も変数の値を変える。右辺だけを
     * 出所として足すと {@code int n = 1; n += 1;} の n が 1 のまま（右辺も 1）に見え、
     * {@code n++} を見落とすと {@code int n = 0; … n++; if (n != 0) …} の条件を偽と判定して、
     * 数を数える典型的な書き方の先を落とす。どちらも U にする。
     *
     * 宣言が値を受け取る変数（{@code catch} の引数・拡張 for の変数・パターンの変数）は、
     * 宣言の時点で U にしておく。そうしないと本体の中の代入（{@code x = new A();}）が
     * 唯一の出所に見え、宣言が受け取った値（コレクションの要素・投げられた例外）を落とす。
     * 引数（{@link #paramScopeOf}）と要素の出所が分かった拡張 for の変数（{@link Scan#bindLoopVariable}）は
     * 先に表にあるので、そのままにする。
     */
    Scope scanOrigins(ASTNode body, Scope scope) {
        if (body == null) {
            return scope;
        }
        // 先読み中は originOf() が参照するスコープを差し替える
        enterScope(scope);
        try {
            Scan scan = new Scan(scope, body);
            for (int pass = 1; ; pass++) {
                Map<String, String> originsBefore = new HashMap<>(scope.origins);
                Map<String, Integer> nodesBefore = new HashMap<>(scope.nodes);
                // 前の回の途中の表から作ったものを捨てる
                graph.forgetExpressions();
                scope.elements.clear();
                scan.rewritten = false;
                body.accept(scan);
                boolean settled = (pass == 1) ? !scan.rewritten
                        : originsBefore.equals(scope.origins) && nodesBefore.equals(scope.nodes);
                if (settled) {
                    break;
                }
                if (pass >= MAX_SCAN_PASSES) {
                    scan.giveUp();
                    break;
                }
            }
        } finally {
            leaveScope();
        }
        return scope;
    }

    /**
     * 先読みを読み直す回数の上限（{@link #scanOrigins}）。
     *
     * 1 回読み直すごとに、U が代入 1 段ぶん伝わる。後ろから前へ写す代入が長く連なる
     * （{@code a = b; b = c; c = d; …} をループで回す）ような書き方でなければ、3 回ほどで止まる。
     * 上限は、その連なりが長すぎるときに時間を使いすぎないための安全策で、使い切ったら U に倒す
     */
    private static final int MAX_SCAN_PASSES = 8;

    /**
     * 先読みの 1 回ぶん。表が変わらなくなるまで同じものを何度でも本体に通す（{@link #scanOrigins}）。
     *
     * 表への書き込みはすべて {@link #write} / {@link #writeUnknown} を通し、書いた変数と
     * 「既にある変数への書き込み（再代入）」があったかを控える
     */
    private final class Scan extends ASTVisitor {
        private final Scope scope;
        private final ASTNode body;
        /** この先読みで書いた変数（回数の上限を使い切ったときに U に倒す相手） */
        private final Set<String> written = new HashSet<>();
        /** この回に、表に既にある変数へ書き込んだか（1 回目にこれが無ければ読み直さない） */
        boolean rewritten;

        Scan(Scope scope, ASTNode body) {
            this.scope = scope;
            this.body = body;
        }

        private void write(String key, Expression value) {
            if (key == null) {
                return;
            }
            touch(key);
            mergeOrigin(scope, key, value);
        }

        private void writeUnknown(String key) {
            touch(key);
            mergeUnknown(scope, key);
        }

        private void touch(String key) {
            written.add(key);
            rewritten |= scope.origins.containsKey(key);
        }

        /** 回数の上限を使い切った。書いた変数はどれも最後の値と言い切れないので、すべて U にする */
        void giveUp() {
            for (String key : written) {
                mergeUnknown(scope, key);
            }
        }

        @Override
        public boolean visit(VariableDeclarationFragment n) {
            IVariableBinding vb = n.resolveBinding();
            if (vb != null && !vb.isField()) {
                write(vb.getKey(), n.getInitializer());
            }
            return true;
        }

        @Override
        public boolean visit(SingleVariableDeclaration n) {
            IVariableBinding vb = n.resolveBinding();
            if (vb != null && !vb.isField() && vb.getKey() != null
                    && !scope.origins.containsKey(vb.getKey())) {
                writeUnknown(vb.getKey());
            }
            return true;
        }

        @Override
        public boolean visit(Assignment n) {
            String key = localKeyOf(n.getLeftHandSide());
            if (key == null) {
                return true;
            }
            if (n.getOperator() == Assignment.Operator.ASSIGN) {
                write(key, n.getRightHandSide());
            } else {
                writeUnknown(key);   // 複合代入。元の値と右辺から新しい値を作る
            }
            return true;
        }

        @Override
        public boolean visit(PostfixExpression n) {
            String key = localKeyOf(n.getOperand());
            if (key != null) {
                writeUnknown(key);
            }
            return true;
        }

        @Override
        public boolean visit(PrefixExpression n) {
            PrefixExpression.Operator op = n.getOperator();
            String key = (op == PrefixExpression.Operator.INCREMENT
                    || op == PrefixExpression.Operator.DECREMENT) ? localKeyOf(n.getOperand()) : null;
            if (key != null) {
                writeUnknown(key);
            }
            return true;
        }

        @Override
        public boolean visit(EnhancedForStatement n) {
            bindLoopVariable(n);
            return true;
        }

        /**
         * 拡張for文の変数に、回しているコレクションの要素の出所を当てる。
         * {@code for (Runnable t : tasks) t.run();} の {@code t} を追えるようにする。
         *
         * 置き換えずに、ほかの代入と同じく<b>合わせる</b>（食い違えば U）。読み直しの 2 回目以降は、
         * 本体の代入（{@code x = new DaoA();}）で U になった変数が表に既にある。置き換えると
         * その回の途中だけ要素の出所に戻り、その間に写した変数（入れ子のループの {@code Dao y = x;}）が
         * 要素の出所のまま残ってしまう。要素の出所が前の回と変わった（途中の表で求めた値だった）ときも U になる
         */
        private void bindLoopVariable(EnhancedForStatement n) {
            IVariableBinding loopVar = n.getParameter().resolveBinding();
            if (loopVar == null || loopVar.getKey() == null) {
                return;
            }
            String key = loopVar.getKey();
            String element = elementOf(n);
            if (element == null) {
                // 要素の出所が分からない。宣言（SingleVariableDeclaration）が受け取る値として U にする。
                // 2 回目以降で表に既にあるなら、ここで U に合わせる
                if (scope.origins.containsKey(key)) {
                    writeUnknown(key);
                }
                return;
            }
            touch(key);
            String prev = scope.origins.get(key);
            if (prev != null && !prev.equals(element)) {
                mergeUnknown(scope, key);
            } else {
                scope.origins.put(key, element);
            }
        }

        /** 拡張for文で回しているローカル変数のコレクションの要素の出所。分からなければ null */
        private String elementOf(EnhancedForStatement n) {
            if (!(unwrap(n.getExpression()) instanceof SimpleName source)
                    || !(source.resolveBinding() instanceof IVariableBinding sourceVar)
                    || sourceVar.isField()) {
                return null;
            }
            String element = elementOriginOf(scope, body, sourceVar.getKey());
            return Origin.isUnknown(element) ? null : element;
        }
    }

    /** 書き換えの対象がフィールド以外の変数（ローカル変数・引数）なら、そのキー。違えば null */
    private static String localKeyOf(Expression target) {
        Expression e = target;
        while (e instanceof ParenthesizedExpression p) {
            e = p.getExpression();   // (x) = ... も x への代入（JLS 15.26）
        }
        return (e instanceof SimpleName name && name.resolveBinding() instanceof IVariableBinding vb
                && !vb.isField()) ? vb.getKey() : null;
    }

    /**
     * {@code list.add(() -> ...)} のように、そのコレクションへ詰められた要素の出所。
     *
     * 本体を走査して集める。拡張for文があるときだけ呼ぶので、
     * ループの無いメソッドに走査を増やさない。結果は変数ごとに覚えておく
     * （先読みを読み直すたびに捨てる。{@link #scanOrigins}）。
     *
     * <h4>要素を「詰めた値だけ」と言い切れるコレクションに限る</h4>
     * 詰める経路は {@code add} だけではない。コンストラクタの実引数（{@code new ArrayList<>(List.of(b))}）、
     * {@code addAll}・{@code Collections.addAll(list, …)}、別のメソッドへ渡して詰めてもらう形、別名
     * （{@code alias = list; alias.add(b)}）、再代入、{@code list.listIterator().add(b)}、{@code list::add} を
     * 渡す形、{@code replaceAll}。1 つでも見落とすと、見えた {@code add} の値だけに絞って、実際に回る要素への
     * 呼び出しを落とす（docs/value-safety-qa.md の Q20）。そこで、次を全部満たすときだけ要素の出所を使う。
     * <ul>
     *   <li>この本体の中で、引数の無い {@code new}（匿名クラスの本体なし）で初期化したローカル変数。
     *       作る型は {@code java.util} のコレクション（利用者の型は、コンストラクタや反復子が何を返すか分からない）</li>
     *   <li>再代入しない</li>
     *   <li>使うのは、要素を足すメソッド（{@link #elementArgumentOf}）のレシーバ、拡張 for の回す式、
     *       要素を足さず外へも漏らさない問い合わせ（{@link #isQueryOnly}）のレシーバだけ</li>
     * </ul>
     * 引数・フィールドのコレクションは、外で何が詰められたか分からないので使わない。
     * 出所が1つに定まらなければ U（不明）。
     */
    private String elementOriginOf(Scope scope, ASTNode body, String varKey) {
        String known = scope.elements.get(varKey);
        if (known != null) {
            return known;
        }
        String[] merged = {null};
        boolean[] ok = {true};
        int[] declarations = {0};
        body.accept(new ASTVisitor() {
            @Override
            public boolean visit(VariableDeclarationFragment n) {
                IVariableBinding vb = n.resolveBinding();
                if (vb != null && varKey.equals(vb.getKey())) {
                    declarations[0]++;
                    ok[0] &= isFreshLocalCollection(n.getInitializer());
                }
                return true;
            }

            @Override
            public boolean visit(SingleVariableDeclaration n) {
                IVariableBinding vb = n.resolveBinding();
                if (vb != null && varKey.equals(vb.getKey())) {
                    ok[0] = false;   // 引数・catch の引数・拡張 for の変数など、外から値を受け取る宣言
                }
                return true;
            }

            @Override
            public boolean visit(SimpleName n) {
                if (!(n.resolveBinding() instanceof IVariableBinding vb) || !varKey.equals(vb.getKey())
                        || n.getLocationInParent() == VariableDeclarationFragment.NAME_PROPERTY
                        || n.getLocationInParent() == SingleVariableDeclaration.NAME_PROPERTY) {
                    return true;
                }
                ASTNode parent = n.getParent();
                if (parent instanceof EnhancedForStatement loop && loop.getExpression() == n) {
                    return true;
                }
                if (parent instanceof MethodInvocation mi && mi.getExpression() == n) {
                    String name = mi.getName().getIdentifier();
                    int at = elementArgumentOf(name, mi.arguments().size());
                    if (at >= 0 && mi.arguments().get(at) instanceof Expression element) {
                        String origin = originOf(element);
                        if (origin == null) {
                            origin = Origin.UNKNOWN_S;
                        }
                        merged[0] = (merged[0] == null || merged[0].equals(origin))
                                ? origin : Origin.UNKNOWN_S;
                        return true;
                    }
                    if (isQueryOnly(name)) {
                        return true;
                    }
                }
                ok[0] = false;   // それ以外の使い方（渡す・写す・再代入・他のメソッドのレシーバ・メソッド参照）
                return true;
            }
        });
        String result = (!ok[0] || declarations[0] != 1 || merged[0] == null) ? Origin.UNKNOWN_S : merged[0];
        scope.elements.put(varKey, result);
        return result;
    }

    /**
     * 初期化子が、中身の空の {@code java.util} のコレクションを作る {@code new} か
     * （{@code new ArrayList<>()}。実引数も匿名クラスの本体も無い）
     */
    private boolean isFreshLocalCollection(Expression initializer) {
        if (!(unwrap(initializer) instanceof ClassInstanceCreation cic)
                || !cic.arguments().isEmpty() || cic.getAnonymousClassDeclaration() != null) {
            return false;
        }
        String type = names.createdTypeOf(cic);
        return type != null && type.startsWith("java.util.");
    }

    /**
     * 要素を足さず、コレクションそのものも反復子も外へ渡さない問い合わせ・削除か。
     * 削除は要素を減らすだけなので、「詰めた値のどれか」という答えは崩れない
     */
    private static boolean isQueryOnly(String name) {
        return switch (name) {
            case "size", "isEmpty", "contains", "clear", "remove", "hashCode", "toString" -> true;
            default -> false;
        };
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
     * 同じ変数に別の出所が現れたら U（不明）に落とす。値グラフ側も同じ判断で揃える。
     *
     * 出所の文字列が食い違ったら、ノードも必ず無し（{@link ValueNode#NONE}）にする。
     * 引数・ラムダの引数・拡張 for の変数は、文字列の表にだけ最初の値（{@code A:0} など）が入っていて
     * ノードの表には無い。ノードの表だけで合わせると、{@code void m(Dao d) { if (d == null) d = new A(); d.find(); }}
     * の d が「new A() だけ」に見え、呼び出し元が渡す実装を落としてしまう。
     *
     * 「文字列が U」だけでは無しにしない。文字列が持てない値（識別子の形でない文字列リテラル
     * {@code "light mode"} など。{@link #classNameLiteral}）は、1 つしか代入されていなくても文字列は U になるが、
     * ノードは値をそのまま持てる
     */
    private void mergeOrigin(Scope scope, String varKey, Expression value) {
        if (varKey == null) {
            return;
        }
        String origin = originOf(value);
        if (origin == null) {
            origin = Origin.UNKNOWN_S;
        }
        String prev = scope.origins.get(varKey);
        boolean conflict = prev != null && !prev.equals(origin);
        scope.origins.put(varKey, conflict ? Origin.UNKNOWN_S : origin);

        int node = graph.nodeOf(value);
        Integer prevNode = scope.nodes.get(varKey);
        scope.nodes.put(varKey, (!conflict && (prevNode == null || prevNode == node))
                ? node : ValueNode.NONE);
    }

    /** 値が分からない書き換え（複合代入・{@code ++}・宣言が受け取る値）。どちらの表も U に落とす */
    private static void mergeUnknown(Scope scope, String varKey) {
        scope.origins.put(varKey, Origin.UNKNOWN_S);
        scope.nodes.put(varKey, ValueNode.NONE);
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
        if (ex == null) {
            return null;
        }
        // 剥がすのは値を変えないキャストだけ（{@link #unwrapValue}）。値を変えうるキャストの先の
        // 出所を使うと、(byte) p が p（A:0）に見えて、300 を渡した経路で (byte)300 == 44 を偽と判定してしまう
        Expression e = unwrapValue(ex);
        if (e == null) {
            // 値を変えうるキャスト。コンパイル時定数なら JDT が畳んだ値（(byte)300 は 44）だけを持つ
            return constantOf(ex);
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
                if (!(e instanceof QualifiedName && isInstanceField(vb))) {
                    return variableOriginOf(vb);
                }
                String other = otherFieldOriginOf(vb);
                if (other != null) {
                    return other;
                }
            }
        }
        if (e instanceof FieldAccess fa) {
            IVariableBinding vb = fa.resolveFieldBinding();
            if (vb != null) {
                String origin = (!isInstanceField(vb) || isThis(fa.getExpression()))
                        ? variableOriginOf(vb) : otherFieldOriginOf(vb);
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
     * ラムダは本体を持つ合成メソッド、メソッド参照は参照先のメソッド
     * （コンパイル時宣言。JLS 15.13.1）。
     * これがあると「関数型インターフェースの変数に何が入っているか」を
     * 他の値と同じように追える（{@code Runnable r = this::helper; r.run();} が繋がる）。
     *
     * <p>レシーバを束縛したメソッド参照（{@code dao::describe}）には、そのレシーバの出所を
     * {@code r=} に付ける（{@code Z:fx.Dao#describe()|r=F:fx.App#dao}）。参照先が仮想メソッドなら
     * 実際に動くのはレシーバの実行時クラスの実装（JLS 15.13.3）で、読み手はこの出所から
     * その実装を引く。レシーバは参照を作った時点で評価されるので、出所は参照を書いた
     * メソッドから見たもの（docs/lambda-expansion-qa.md の Q12）。
     */
    String functionalOriginOf(Expression ex) {
        Expression e = unwrapValue(ex);
        if (e instanceof LambdaExpression lambda) {
            MethodRef body = (lambdaNames == null) ? null : lambdaNames.of(lambda);
            return (body == null) ? null : Origin.of(Origin.FUNCTIONAL, body.key());
        }
        if (e instanceof MethodReference ref) {
            MethodRef target = names.toRef(methodBindingOf(ref));
            if (target == null) {
                return null;
            }
            Expression receiver = boundReceiverOf(ref);
            String recvOrigin = (receiver == null) ? null : originOf(receiver);
            return (recvOrigin == null) ? Origin.of(Origin.FUNCTIONAL, target.key())
                    : Origin.of(Origin.FUNCTIONAL, target.key(),
                            Origin.RECEIVER + "=" + Origin.nest(recvOrigin));
        }
        return null;
    }

    /**
     * メソッド参照が束縛しているレシーバの式（{@code dao::describe} の {@code dao}）。
     * 型名を書いた形（{@code Dao::describe}。レシーバは呼び出し時の第1引数）、
     * {@code super::m}、{@code Type::new} には無いので null
     */
    static Expression boundReceiverOf(MethodReference ref) {
        if (!(ref instanceof ExpressionMethodReference expr)) {
            return null;
        }
        Expression receiver = expr.getExpression();
        return (CallSiteRecorder.recvKindOf(receiver) == RecvKind.TYPE) ? null : receiver;
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
        if (ex == null) {
            return null;
        }
        Expression e = unwrapValue(ex);
        if (e == null) {
            // 値を変えうるキャスト。リテラルの表記からは値を読めない（(byte)300 の 300 は値ではない）。
            // コンパイル時定数（JLS 15.29。プリミティブへのキャストを含む）なら、JDT が型変換まで
            // 済ませて畳んだ値を使う
            return constantValueText(ex.resolveConstantExpressionValue());
        }
        if (e instanceof BooleanLiteral b) {
            return Origin.of(Origin.CONST, String.valueOf(b.booleanValue()));
        }
        if (e instanceof NumberLiteral n) {
            // 値は JDT が評価したものを使う（{@link #constantText}。浮動小数は拾わない）。
            // 表記から読むと、int の 0x80000000（-2147483648）が 2147483648 に見える。
            // 呼び出し元の値（{@link ValueGraph}）は JDT の値なので、条件の期待値と食い違って
            // 実際には通る経路を「呼ばれない」と判定してしまう
            return constantValueText(n.resolveConstantExpressionValue());
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
            return constantValueText(vb.getConstantValue());
        }
        return null;
    }

    /**
     * コンパイル時定数の値を出所（{@code V:}）にする。拾えないものは null。
     * 表記は {@link #constantText}、長さと内容の上限は {@link #valueConst}
     */
    private static String constantValueText(Object constant) {
        String text = constantText(constant);
        return (text == null) ? null : valueConst(text);
    }

    /**
     * コンパイル時定数の値（{@code resolveConstantExpressionValue} / {@code getConstantValue} の結果）の
     * 表記。拾えないもの（浮動小数と null）は null。長さの上限は掛けない。
     *
     * 出所の文字列（{@link #constantOf}。上限あり）と値グラフ（{@link ValueGraph}。上限なし）の
     * 両方がこの表記を使う。2 か所で表記が食い違うと、ガードの期待値（{@code "1"}）と
     * 呼び出し元の値（{@code "1.0"}）のような比べられない組ができてしまう。
     *
     * <h4>char は数値にする（JLS 5.6 の数値昇格）</h4>
     * {@code char} と {@code int} を {@code ==} で比べると、どちらも {@code int} に昇格してから
     * 比較される。{@code 'A' == 65} は真である。値を文字のまま持つと、この 2 つが
     * 別の値に見えて「条件が成立しない」と誤判定し、実際には通る経路を落としてしまう。
     * {@code byte} / {@code short} / {@code int} / {@code long} と同じ 10 進表記に揃えることで、
     * 整数系どうしはどの組み合わせでも一致を判定できる。
     *
     * <h4>浮動小数は拾わない</h4>
     * {@code 1} と {@code 1.0} と {@code 1.0f} は同じ値だが表記が違い、文字列の一致では
     * 判定できない。誤って打ち切るより判定しないほうがよい。
     *
     * <h4>数値はリテラルの表記から読まない</h4>
     * 数値リテラルも JDT が評価した値（{@code resolveConstantExpressionValue}）をここに通す（{@link #constantOf}）。
     * 16 進・8 進の int リテラルは最上位ビットが立つと負の値になる（JLS 3.10.1。{@code 0x80000000} は
     * {@code -2147483648}、{@code 0xFFFFFFFF} は {@code -1}）。表記を {@code long} として読むと正の値になり、
     * JDT の値を使う呼び出し元の側（{@link ValueGraph}・{@code static final} の定数）と食い違う。
     */
    static String constantText(Object constant) {
        if (constant == null || constant instanceof Double || constant instanceof Float) {
            return null;
        }
        if (constant instanceof Character c) {
            return String.valueOf((int) c.charValue());
        }
        return String.valueOf(constant);
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

    /**
     * インスタンスフィールドか（static でも列挙定数でもないフィールド）。
     *
     * フィールドの出所（{@code F:型#名前}）は「今のオブジェクト（{@code this}）のフィールド」という意味で、
     * 読み手は経路で分かっている今のオブジェクトのコンストラクタ実引数を当てる（jche.graph.DataflowResolver の
     * fieldSlotOf）。{@code other.dao}・{@code getPeer().mode}・{@code Outer.this.dao} のように別のインスタンス
     * （かもしれないもの）を修飾したインスタンスフィールドをこの出所にすると、{@code other} の値のはずが
     * {@code this} のコンストラクタ実引数に見えて、誤った具象型に確定し、条件を誤って偽と判定する。
     * そこで修飾した読み取りは {@code F:} にしない（コンパイル時定数なら値だけは使う。{@link #constantOf}）。
     * 修飾の無い名前と {@code this.f} だけを {@code F:} にする（docs/value-safety-qa.md の Q19）。修飾した読み取りは
     * 別の種別 {@code O:} にする（{@link #otherFieldOriginOf}。Q25）
     */
    private static boolean isInstanceField(IVariableBinding vb) {
        return vb.isField() && !vb.isEnumConstant() && !Modifier.isStatic(vb.getModifiers());
    }

    /**
     * {@code this} 以外で修飾したインスタンスフィールドの読み取り（{@code other.dao}・{@code getPeer().mode}・
     * {@code Outer.this.dao}）の出所（{@link Origin#OTHER_FIELD}）。コンパイル時定数（定数変数）なら null
     * （呼び出し側が値だけを拾う。{@link #constantOf}）。
     *
     * <p>{@code F:} にすると、読み手は今のオブジェクトのコンストラクタ実引数を当ててしまう（上の {@link #isInstanceField}）。
     * かといって出所にしないと、どのインスタンスでも同じ値（初期化子の {@code new DaoA()}・コンストラクタで入れる
     * ラムダ）で絞れていた呼び出しまで CHA に戻る。種別を分け、読み手はコンストラクタ実引数を当てずに、どの
     * インスタンスでも同じ値だけを使う（docs/value-safety-qa.md の Q25）
     */
    private String otherFieldOriginOf(IVariableBinding vb) {
        if (vb.getConstantValue() != null) {
            return null;
        }
        ITypeBinding owner = vb.getDeclaringClass();
        String ownerFqn = (owner == null) ? null : names.typeNameOf(BindingNames.erasureOf(owner));
        return (ownerFqn == null) ? null : Origin.of(Origin.OTHER_FIELD, ownerFqn + "#" + vb.getName());
    }

    /** 修飾の無い {@code this}（{@code Outer.this} は別のインスタンスなので含めない） */
    private static boolean isThis(Expression ex) {
        return unwrap(ex) instanceof ThisExpression t && t.getQualifier() == null;
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
        IVariableBinding vb = variableBindingOf(unwrapValue(ex));
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
        return Origin.isNameShaped(value) ? Origin.of(Origin.LITERAL, value) : null;
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
        String argOrigin = originOf((Expression) forName.arguments().get(0));
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
     * 括弧と、<b>値を変えないキャスト</b>を剥がす。値を変えうるキャストに当たったら null
     * （＝この式の先の出所を、この式の値として使ってはいけない）。
     *
     * 値として使う経路はすべてここを通す。条件（{@link GuardCollector}）の両辺だけでなく、
     * 出所（{@link #originOf}。ローカル変数の表）と値グラフ（{@link ValueGraph}。呼び出し箇所の実引数・
     * R 行・J 行・条件の subject）もである。読み手は呼び出し元の実引数の値を引数（{@code A:}）に当てて
     * 条件を判定するので、どこか 1 か所でもキャストを剥がしすぎると、同じ誤判定になる。
     * <pre>
     *     void run(int mode) { if ((byte) mode == 44) target(); }
     *     run(300);          // (byte)300 == 44 は真
     *
     *     run((byte) 300);   // 実引数が 300 に見えると、void run(byte mode) { if (mode == 44) … } を偽と判定する
     * </pre>
     * キャストを剥がすと「300 と 44 の比較」になり、実際には通る経路を
     * 「呼ばれない」と判定して、その先の階層をまるごと落としてしまう。
     * 判定できないほうへ倒すのが安全側である。値を変えうるキャストの式でも、コンパイル時定数なら
     * JDT が畳んだ値（{@code (byte)300} は 44）を使える（{@link #constantOf}・{@link ValueGraph}）。
     *
     * どのキャストが値を変えないかは {@link #preservesValue}。型が取れないときも「変わりうる」とみなす。
     *
     * <h4>{@link #unwrap} と分けている理由</h4>
     * {@link #unwrap} は値を変えるキャストも剥がす。どのインスタンスを指すか（参照型の変数・レシーバ）
     * だけを見る場面では同じ結果になるが、値として使う経路では使わないこと。
     */
    static Expression unwrapValue(Expression ex) {
        Expression e = ex;
        for (int guard = 0; guard < 8; guard++) {
            if (e instanceof ParenthesizedExpression p) {
                e = p.getExpression();
            } else if (e instanceof CastExpression c) {
                if (!preservesValue(c)) {
                    return null;
                }
                e = c.getExpression();
            } else {
                return e;
            }
        }
        return e;
    }

    /**
     * そのキャストが値を変えないと言い切れるか（JLS 5.5 のキャスト変換）。
     *
     * <ul>
     *   <li>参照型どうし（JLS 5.1.5 拡大参照 / 5.1.6 縮小参照）… 同じインスタンスを指し続ける</li>
     *   <li>同じプリミティブ型（JLS 5.1.1 恒等変換）</li>
     *   <li>値が正確に保たれる拡大プリミティブ変換（JLS 5.1.2）。精度を失いうる
     *       {@code int}・{@code long} → {@code float}、{@code long} → {@code double} は除く</li>
     * </ul>
     * それ以外（縮小プリミティブ変換 5.1.3、拡大と縮小 5.1.4、ボックス化 5.1.7・非ボックス化 5.1.8、
     * 型が取れない）は「変えうる」。
     */
    static boolean preservesValue(CastExpression cast) {
        ITypeBinding to = cast.getType().resolveBinding();
        ITypeBinding from = cast.getExpression().resolveTypeBinding();
        if (to == null || from == null) {
            return false;
        }
        if (!to.isPrimitive() && !from.isPrimitive()) {
            return true;
        }
        return to.isPrimitive() && from.isPrimitive() && exactPrimitiveConversion(from.getName(), to.getName());
    }

    /** プリミティブ型 from から to への変換で、どの値もそのまま保たれるか（恒等か正確な拡大。JLS 5.1.1 / 5.1.2） */
    private static boolean exactPrimitiveConversion(String from, String to) {
        if (from.equals(to)) {
            return true;
        }
        return switch (from) {
            case "byte" -> switch (to) {
                case "short", "int", "long", "float", "double" -> true;
                default -> false;
            };
            case "short", "char" -> switch (to) {
                case "int", "long", "float", "double" -> true;
                default -> false;
            };
            case "int" -> "long".equals(to) || "double".equals(to);
            case "float" -> "double".equals(to);
            default -> false;
        };
    }

    /**
     * 括弧とキャストを、値を変えるものも含めて剥がす。
     *
     * 使ってよいのは、参照型の変数やレシーバが<b>どのインスタンスを指すか</b>だけを見る場面
     * （代入先の変数・コレクションの変数・{@code Class.forName(...)} の連鎖）に限る。
     * そこに現れるキャストは参照型どうしで、値を変えない。
     * 式の値（出所・値グラフ・条件）を求める用途では {@link #unwrapValue} を使うこと
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
