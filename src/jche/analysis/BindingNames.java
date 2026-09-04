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

import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.Modifier;

import jche.cache.FileAnalysis;
import jche.cache.MethodRef;

/**
 * JDTのバインディングから、キャッシュに書く名前（型名・メソッドの4つ組・修飾子）を作る。
 *
 * 名前を求めた型は「このファイルの解決結果が依存する型」として {@link FileAnalysis#referencedTypes}
 * に記録する（I行の元）。差分更新で、依存先のファイルが変わったときに再解析するため。
 */
final class BindingNames {

    private final FileAnalysis out;

    BindingNames(FileAnalysis out) {
        this.out = out;
    }

    /** 消去型（ジェネリクスの型引数を落とした型）。取れなければそのまま */
    static ITypeBinding erasureOf(ITypeBinding t) {
        ITypeBinding erased = t.getErasure();
        return (erased != null) ? erased : t;
    }

    static String packageOf(ITypeBinding t) {
        return (t.getPackage() != null) ? t.getPackage().getName() : "";
    }

    /** 修飾子ビットをカンマ区切りの語に落とす（{@link jche.cache.ModifierTokens} の語彙） */
    static String modifiersOf(int modifiers) {
        StringBuilder sb = new StringBuilder();
        appendIf(sb, Modifier.isPublic(modifiers), "public");
        appendIf(sb, Modifier.isProtected(modifiers), "protected");
        appendIf(sb, Modifier.isPrivate(modifiers), "private");
        appendIf(sb, Modifier.isStatic(modifiers), "static");
        appendIf(sb, Modifier.isFinal(modifiers), "final");
        appendIf(sb, Modifier.isAbstract(modifiers), "abstract");
        appendIf(sb, Modifier.isDefault(modifiers), "default");
        return sb.toString();
    }

    private static void appendIf(StringBuilder sb, boolean condition, String token) {
        if (condition) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(token);
        }
    }

    /**
     * 型の識別名。取れなければ null。
     *
     * 匿名クラスは getQualifiedName() が空文字を返す。以前はそこでスキップしていたため、
     * 匿名クラスによるオーバーライドと、その内部の呼び出しが丸ごと欠落していた。
     * JDTは匿名クラスにも識別子を持っているので順に切り替えて拾う。
     */
    String typeNameOf(ITypeBinding t) {
        if (t == null) {
            return null;
        }
        String n = t.getQualifiedName();
        if (n == null || n.isEmpty()) {
            n = t.getBinaryName();               // 例: jp.co.xxx.Outer$1
        }
        if (n == null || n.isEmpty()) {
            String key = t.getKey();             // JDT内部の一意キー（最終手段）
            // キャッシュはタブ区切りのため、空白類が混ざると形式が壊れる
            n = (key == null) ? null : key.replaceAll("\\s", "_");
        }
        if (n == null || n.isEmpty()) {
            return null;
        }
        // ここを通った型は、このファイルの解決結果がそれに依存している（I行）。
        // 自分が宣言する型も混ざるが、書き出し時に除く
        if (!t.isPrimitive() && !t.isArray() && !t.isTypeVariable()) {
            out.referencedTypes.add(n);
        }
        return n;
    }

    /** 宣言型のFQN（消去型）。配列は要素型に "[]" を付ける。プリミティブはそのまま。取れなければ空 */
    String declTypeName(ITypeBinding t) {
        if (t == null) {
            return "";
        }
        if (t.isArray()) {
            return declTypeName(t.getComponentType()) + "[]";
        }
        if (t.isPrimitive()) {
            return t.getName();
        }
        String n = typeNameOf(erasureOf(t));
        return (n == null) ? "" : n;
    }

    /** 型を「このファイルの解決結果が依存する型」（I行）に数える。配列は要素型で */
    void noteDependency(ITypeBinding t) {
        while (t != null && t.isArray()) {
            t = t.getComponentType();
        }
        if (t == null || t.isPrimitive() || t.isTypeVariable()) {
            return;
        }
        typeNameOf(erasureOf(t));
    }

    /** メソッドの4つ組。宣言型か引数型の名前が取れなければ null */
    MethodRef toRef(IMethodBinding binding) {
        if (binding == null) {
            return null;
        }
        // ジェネリクスの実体化された型ではなく宣言側を基準にする
        IMethodBinding decl = binding.getMethodDeclaration();
        if (decl == null) {
            return null;
        }
        ITypeBinding type = decl.getDeclaringClass();
        if (type == null) {
            return null;
        }
        ITypeBinding erased = type.getErasure();
        if (erased == null) {
            return null;
        }
        String typeFqn = typeNameOf(erased);
        if (typeFqn == null) {
            return null;
        }

        StringBuilder params = new StringBuilder();
        ITypeBinding[] paramTypes = decl.getParameterTypes();
        if (paramTypes != null) {
            for (int i = 0; i < paramTypes.length; i++) {
                if (i > 0) {
                    params.append(",");
                }
                ITypeBinding erasedParam = paramTypes[i].getErasure();
                params.append(erasedParam != null
                        ? erasedParam.getQualifiedName() : paramTypes[i].getQualifiedName());
                // 引数型はメソッドキーの一部。改名されるとキーが変わるので依存に数える
                noteDependency(erasedParam != null ? erasedParam : paramTypes[i]);
            }
        }
        String name = decl.isConstructor() ? MethodRef.CONSTRUCTOR : decl.getName();
        return new MethodRef(packageOf(erased), typeFqn, name, params.toString());
    }

    /** new された具象型。匿名クラスの場合は匿名型そのもの。取れなければ null */
    String createdTypeOf(ClassInstanceCreation cic) {
        ITypeBinding tb = cic.resolveTypeBinding();
        if (tb == null) {
            IMethodBinding cb = cic.resolveConstructorBinding();
            if (cb != null && cb.getMethodDeclaration() != null) {
                tb = cb.getMethodDeclaration().getDeclaringClass();
            }
        }
        if (tb == null) {
            return null;
        }
        return typeNameOf(erasureOf(tb));
    }
}
