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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.TypeLiteral;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import jche.cache.MethodRef;
import jche.cache.Origin;

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

    private final BindingNames names;

    /** 現在のメソッドの「変数の出所」（IVariableBinding.getKey() -> {@link Origin}）のスタック */
    private final ArrayDeque<Map<String, String>> scopes = new ArrayDeque<>();

    OriginTracker(BindingNames names) {
        this.names = names;
    }

    // ------------------------------------------------------------
    // スコープ（変数 -> 出所 の表）
    // ------------------------------------------------------------

    void enterScope(Map<String, String> scope) {
        scopes.push(scope);
    }

    void leaveScope() {
        if (!scopes.isEmpty()) {
            scopes.pop();
        }
    }

    /** 引数を出所として登録した、メソッド用の初期スコープ */
    Map<String, String> paramScopeOf(MethodDeclaration node) {
        Map<String, String> scope = new HashMap<>();
        List<?> params = node.parameters();
        for (int i = 0; i < params.size(); i++) {
            if (!(params.get(i) instanceof SingleVariableDeclaration param)) {
                continue;
            }
            IVariableBinding vb = param.resolveBinding();
            if (vb != null && vb.getKey() != null) {
                scope.put(vb.getKey(), Origin.of(Origin.PARAM, String.valueOf(i)));
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
    Map<String, String> scanOrigins(ASTNode body, Map<String, String> scope) {
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
            });
        } finally {
            leaveScope();
        }
        return scope;
    }

    /** 同じ変数に別の出所が現れたら U（不明）に落とす */
    private void mergeOrigin(Map<String, String> scope, String varKey, Expression value) {
        if (varKey == null) {
            return;
        }
        String origin = originOf(value);
        if (origin == null) {
            origin = Origin.UNKNOWN_S;
        }
        String prev = scope.get(varKey);
        scope.put(varKey, (prev == null || prev.equals(origin)) ? origin : Origin.UNKNOWN_S);
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
                return variableOriginOf(vb);
            }
        }
        return null;
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
        boolean enclosing = false;
        for (Map<String, String> scope : scopes) {
            String origin = scope.get(key);
            if (origin == null) {
                enclosing = true;   // 今のメソッドには無い。1つ外へ
                continue;
            }
            if (Origin.isUnknown(origin)) {
                return null;
            }
            if (!enclosing) {
                return origin;
            }
            return isEffectivelyFinal(vb) ? frameIndependent(origin) : null;
        }
        return null;
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
        if (value == null || value.isEmpty() || value.length() > 64) {
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
    private String reflectiveOriginOf(MethodInvocation mi) {
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

    /** 括弧とキャストを剥がす。どちらも実体のインスタンスは変えない */
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
