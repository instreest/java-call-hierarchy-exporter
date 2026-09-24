// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.VariableDeclarationExpression;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

/**
 * ソースに書かれていないが、Java 言語仕様が「呼ぶ」と定めている呼び出しの呼び出し先を引く係。
 *
 * <pre>
 *   拡張 for 文（JLS 14.14.2）        Expression.iterator()、#i.hasNext()、#i.next()
 *   try-with-resources（JLS 14.20.3）  資源ごとの close()（宣言と逆の順）
 *   レコードパターン（JLS 14.30.2）    成分ごとのアクセサ
 * </pre>
 * どれも AST に呼び出し式が無いので、{@link FactVisitor} の呼び出し式の visit では拾えない。
 * 拾わないと、{@code Resource.close()} や {@code Point.x()} を変えたときの影響が
 * それらの構文から辿れず、静かに抜ける（docs/jls-conformance-test-qa.md）。
 *
 * 呼び出し先は、JLS の変換どおりの<b>式の静的な型</b>から引く。javac は拡張 for 文の
 * {@code hasNext()} / {@code next()} を {@code java.util.Iterator} で修飾して呼ぶが、
 * JLS 14.14.2 の {@code #i} の型は {@code iterator()} の戻り値の型 I なので、
 * 利用者の型を返す {@code iterator()} ならその型のメソッドになる。実行時に動くメソッドは同じ
 * （JLS 15.12.4.4）で、こちらのほうが候補を絞れる。
 */
final class ImplicitCalls {

    private ImplicitCalls() {
    }

    /**
     * 型のメンバのうち、名前が一致する引数なしのインスタンスメソッド。無ければ null。
     *
     * 型そのもの、親クラス、親インターフェースの順に幅優先で探す。最初に見つかるのが、
     * その型から見て最も特定的な宣言である（上書きした宣言は親より先に当たる）。
     * パラメータ化された型は、型引数を置換したメソッドのバインディングが返るので、
     * {@code iterator()} の戻り値（{@code Iterator<Integer>} など）もそのまま使える。
     * 型変数・交差型は、境界（{@code getSuperclass} / {@code getInterfaces}）から探す。
     */
    static IMethodBinding findNoArgMethod(ITypeBinding type, String name) {
        if (type == null || type.isPrimitive() || type.isArray() || type.isNullType()) {
            return null;
        }
        ArrayDeque<ITypeBinding> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(type);
        while (!queue.isEmpty()) {
            ITypeBinding t = queue.poll();
            if (!seen.add(t.getKey() == null ? t.getQualifiedName() : t.getKey())) {
                continue;
            }
            for (IMethodBinding m : t.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterTypes().length == 0
                        && !Modifier.isStatic(m.getModifiers()) && !m.isConstructor()) {
                    return m;
                }
            }
            if (t.getSuperclass() != null) {
                queue.add(t.getSuperclass());
            }
            for (ITypeBinding i : t.getInterfaces()) {
                queue.add(i);
            }
        }
        return null;
    }

    /**
     * record の成分のアクセサ（成分の宣言順）。record でなければ空。
     *
     * アクセサを明示的に宣言していなくても、JDT は暗黙に宣言されたアクセサ（JLS 8.10.3）の
     * バインディングを返す。record はインスタンスフィールドを宣言できない（JLS 8.10.3）ので、
     * static でないフィールドがそのまま成分である。
     */
    static List<IMethodBinding> accessorsOf(ITypeBinding record) {
        List<IMethodBinding> out = new ArrayList<>();
        if (record == null || !record.isRecord()) {
            return out;
        }
        for (IVariableBinding field : record.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            for (IMethodBinding m : record.getDeclaredMethods()) {
                if (m.getName().equals(field.getName()) && m.getParameterTypes().length == 0
                        && !Modifier.isStatic(m.getModifiers())) {
                    out.add(m);
                    break;
                }
            }
        }
        return out;
    }

    /**
     * try-with-resources の資源 1 つの「close() のレシーバになる式」。
     * 資源の宣言（{@code R r = ...}）なら宣言した変数の名前、既存の変数の参照（{@code try (r)}。
     * JLS 14.20.3）ならその式そのもの。
     */
    static Expression resourceReceiver(Expression resource) {
        if (resource instanceof VariableDeclarationExpression vde && !vde.fragments().isEmpty()
                && vde.fragments().get(0) instanceof VariableDeclarationFragment f) {
            return f.getName();
        }
        return resource;
    }

    /** 資源の静的な型。取れなければ null */
    static ITypeBinding resourceType(Expression resource) {
        if (resource instanceof VariableDeclarationExpression vde && !vde.fragments().isEmpty()
                && vde.fragments().get(0) instanceof VariableDeclarationFragment f) {
            IVariableBinding vb = f.resolveBinding();
            return (vb == null) ? vde.getType().resolveBinding() : vb.getType();
        }
        return resource.resolveTypeBinding();
    }
}
