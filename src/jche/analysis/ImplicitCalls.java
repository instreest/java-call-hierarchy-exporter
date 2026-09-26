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
 * 呼び出し先は、JLS の変換どおりの<b>静的な型</b>から引く。拡張 for 文の {@code iterator()} は式の型、
 * {@code #i.hasNext()} / {@code #i.next()} は {@code #i} の型から引く。JLS 14.14.2 は {@code #i} の型 I を
 * {@code java.util.Iterator<X>}（式の型が {@code Iterable<X>} の部分型でなければ生の {@code Iterator}）と定めているので、
 * {@code iterator()} が利用者の Iterator の型を返しても、呼び出し先は {@code java.util.Iterator} のメソッドになる
 * （javac のバイトコードも同じ）。実際に動く実装は、読み手が {@code Iterator} の実装から探す（JLS 15.12.4.4）。
 */
final class ImplicitCalls {

    private ImplicitCalls() {
    }

    /**
     * 型のメンバのうち、名前が一致する引数なしのインスタンスメソッド（JLS 15.12.1〜15.12.2 で {@code #r.close()} や
     * {@code Expression.iterator()} が呼ぶ宣言）。無ければ null。
     *
     * <ol>
     *   <li>型そのものと、親クラスの連なり（型変数・交差型は境界のクラス。{@code getSuperclass}）を近い順に見る。
     *       クラスのメソッドは、インターフェースの既定のメソッドより先に選ばれる（JLS 8.4.8。親クラスの {@code close()} は
     *       インターフェースの {@code default close()} を実装・上書きする）</li>
     *   <li>クラスの連なりに無ければ、それらのインターフェース（推移的に）が宣言するもののうち、最も特定的なもの
     *       （宣言した型が、ほかの候補の宣言した型の親でないもの。JLS 9.4.1）。同じ特定さのものが複数なら、近い順で先のもの</li>
     * </ol>
     * 型そのもの以外の型の {@code private} なメソッドは飛ばす。private なメンバーは継承されず（JLS 8.4.8・9.4.1）、
     * 呼び出しの候補にならない。飛ばさないと、{@code <T extends PBase & PApi>} の {@code try (T r = t)} で、PBase の
     * private な {@code close()} を呼び出し先にしてしまい、実際に動く {@code PApi.close()} への呼び出しが無くなっていた。
     * static なメソッドも飛ばす（インスタンスメソッドの呼び出しにならない）。
     * パラメータ化された型は、型引数を置換したメソッドのバインディングが返るので、
     * {@code iterator()} の戻り値（{@code Iterator<Integer>} など）もそのまま使える。
     */
    static IMethodBinding findNoArgMethod(ITypeBinding type, String name) {
        if (type == null || type.isPrimitive() || type.isArray() || type.isNullType()) {
            return null;
        }
        Set<String> seen = new HashSet<>();
        ArrayDeque<ITypeBinding> interfaces = new ArrayDeque<>();
        // 1. 型そのものと親クラスの連なり
        for (ITypeBinding c = type; c != null && seen.add(keyOf(c)); c = c.getSuperclass()) {
            IMethodBinding m = declaredNoArgMethod(c, name, c == type);
            if (m != null) {
                return m;
            }
            interfaces.addAll(List.of(c.getInterfaces()));
        }
        // 2. インターフェース（近い順）。候補を全部集めてから、最も特定的なものを選ぶ
        List<IMethodBinding> candidates = new ArrayList<>();
        while (!interfaces.isEmpty()) {
            ITypeBinding i = interfaces.poll();
            if (!seen.add(keyOf(i))) {
                continue;
            }
            IMethodBinding m = declaredNoArgMethod(i, name, i == type);
            if (m != null) {
                candidates.add(m);
            }
            interfaces.addAll(List.of(i.getInterfaces()));
        }
        for (IMethodBinding m : candidates) {
            if (!overriddenByOther(m, candidates)) {
                return m;
            }
        }
        return null;
    }

    /** 型が宣言する、名前が一致する引数なしのインスタンスメソッド。{@code ownType} でなければ private は飛ばす */
    private static IMethodBinding declaredNoArgMethod(ITypeBinding t, String name, boolean ownType) {
        for (IMethodBinding m : t.getDeclaredMethods()) {
            int mods = m.getModifiers();
            if (m.getName().equals(name) && m.getParameterTypes().length == 0 && !m.isConstructor()
                    && !Modifier.isStatic(mods) && (ownType || !Modifier.isPrivate(mods))) {
                return m;
            }
        }
        return null;
    }

    /** {@code m} を宣言した型が、ほかの候補を宣言した型の（推移的な）親か（そのほかの候補のほうが特定的） */
    private static boolean overriddenByOther(IMethodBinding m, List<IMethodBinding> candidates) {
        String declaring = keyOf(m.getDeclaringClass().getErasure());
        for (IMethodBinding other : candidates) {
            if (other != m && superKeysOf(other.getDeclaringClass()).contains(declaring)) {
                return true;
            }
        }
        return false;
    }

    /** 型の推移的な親型（消去した型の鍵） */
    private static Set<String> superKeysOf(ITypeBinding type) {
        Set<String> keys = new HashSet<>();
        ArrayDeque<ITypeBinding> queue = new ArrayDeque<>();
        queue.add(type);
        while (!queue.isEmpty()) {
            ITypeBinding t = queue.poll();
            if (t.getSuperclass() != null && keys.add(keyOf(t.getSuperclass().getErasure()))) {
                queue.add(t.getSuperclass());
            }
            for (ITypeBinding i : t.getInterfaces()) {
                if (keys.add(keyOf(i.getErasure()))) {
                    queue.add(i);
                }
            }
        }
        return keys;
    }

    /** 訪問済みの判定用の鍵。取れない型は名前で見る */
    private static String keyOf(ITypeBinding t) {
        return (t.getKey() == null) ? t.getQualifiedName() : t.getKey();
    }

    /**
     * 型 {@code type} から見た、型 {@code declaringFqn}（消去した名前）のメンバのうち、名前が一致する
     * 引数なしのインスタンスメソッド。{@code type} の親型をたどって {@code declaringFqn} を探し、
     * 見つけた型（パラメータ化されたまま）の宣言を返す。無ければ null。
     *
     * 拡張 for 文の {@code #i.hasNext()} のように、JLS の変換が変数の型を決まった型
     * （{@code java.util.Iterator<X>}）と定めているときに使う。{@code X} は {@code type} の親型の
     * 型引数から決まる。
     */
    static IMethodBinding findNoArgMethodOf(ITypeBinding type, String declaringFqn, String name) {
        if (type == null || type.isPrimitive() || type.isArray() || type.isNullType()) {
            return null;
        }
        ArrayDeque<ITypeBinding> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(type);
        while (!queue.isEmpty()) {
            ITypeBinding t = queue.poll();
            if (!seen.add(keyOf(t))) {
                continue;
            }
            ITypeBinding erased = t.getErasure();
            if (erased != null && declaringFqn.equals(erased.getQualifiedName())) {
                for (IMethodBinding m : t.getDeclaredMethods()) {
                    if (m.getName().equals(name) && m.getParameterTypes().length == 0
                            && !Modifier.isStatic(m.getModifiers())) {
                        return m;
                    }
                }
                return null;
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
