// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;

/**
 * Eclipse のメソッド（{@link IMethod}）と、解析結果のメソッドID を突き合わせる。
 *
 * <p>解析側のキーは {@code 型FQN#メソッド名(引数型,…)}（{@link jche.cache.MethodRef}）で、
 * 引数型は<b>消去型の完全修飾名</b>である。{@link IMethod} が持つのはソースに書かれたままの
 * 型名（{@code List<Order>} や型変数 {@code T}）なので、ここで同じ形へ寄せる。
 *
 * <p>寄せ切れないことがある（型変数の境界、内部クラスの解決失敗など）。その場合は
 * サーバー側が「型・名前・引数の数」で候補を探し、<b>一意に決まるときだけ</b>採る
 * （{@code jche.server.Server#findLoosely}）。決まらなければ見つからなかったものとして扱い、
 * 画面にその旨を出す。黙って別のメソッドを表示するより、見つからないと言うほうが安全なため。
 */
final class MethodKeys {

    private MethodKeys() {
    }

    /** {@link IMethod} から解析側のキーを組み立てる。組み立てられなければ null */
    static String keyOf(IMethod method) {
        try {
            IType type = method.getDeclaringType();
            if (type == null) {
                return null;
            }
            String typeFqn = type.getFullyQualifiedName('.');
            String name = method.isConstructor() ? jche.cache.MethodRef.CONSTRUCTOR : method.getElementName();
            StringBuilder params = new StringBuilder();
            String[] parameterTypes = method.getParameterTypes();
            for (int i = 0; i < parameterTypes.length; i++) {
                if (i > 0) {
                    params.append(',');
                }
                params.append(erasedTypeName(type, parameterTypes[i]));
            }
            return typeFqn + "#" + name + "(" + params + ")";
        } catch (JavaModelException e) {
            return null;
        }
    }

    /**
     * 引数型のシグネチャを、解析側と同じ「消去型の完全修飾名」にする。
     * 配列は要素型に {@code []} を付け、型変数は解決できないので {@code java.lang.Object} とみなす。
     */
    private static String erasedTypeName(IType context, String signature) throws JavaModelException {
        String erased = Signature.getTypeErasure(signature);
        int dimensions = Signature.getArrayCount(erased);
        String element = Signature.getElementType(erased);
        String name;
        if (Signature.getTypeSignatureKind(element) == Signature.BASE_TYPE_SIGNATURE) {
            name = Signature.toString(element);
        } else if (Signature.getTypeSignatureKind(element) == Signature.TYPE_VARIABLE_SIGNATURE) {
            name = "java.lang.Object";
        } else {
            String simple = Signature.toString(element);
            name = resolve(context, simple);
        }
        return name + "[]".repeat(dimensions);
    }

    /**
     * 単純名を完全修飾名にする。解決できなければ書かれたまま返す
     * （シグネチャが既に解決済みなら {@code java.lang.String} のような形で来るので、そのまま通る）。
     */
    private static String resolve(IType context, String simpleName) throws JavaModelException {
        String[][] resolved = context.resolveType(simpleName);
        if (resolved == null || resolved.length != 1) {
            return simpleName;
        }
        String pkg = resolved[0][0];
        String type = resolved[0][1];
        return pkg.isEmpty() ? type : pkg + "." + type;
    }

    /** 選択されている Java 要素からメソッドを取り出す。メソッドの中の要素なら、その囲みメソッド */
    static IMethod methodOf(Object selected) {
        if (selected instanceof IMethod m) {
            return m;
        }
        if (selected instanceof IJavaElement element) {
            IJavaElement ancestor = element.getAncestor(IJavaElement.METHOD);
            if (ancestor instanceof IMethod m) {
                return m;
            }
        }
        return null;
    }

    /** エディタの位置（オフセット）にあるメソッド。無ければ null */
    static IMethod methodAt(ICompilationUnit unit, int offset) {
        try {
            if (unit == null) {
                return null;
            }
            return methodOf(unit.getElementAt(offset));
        } catch (JavaModelException e) {
            return null;
        }
    }
}
