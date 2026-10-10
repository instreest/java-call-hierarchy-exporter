// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Type;

import jche.util.Messages;

/**
 * JDT の下限（{@value #FLOOR}。Eclipse 2021-12。Java 17 まで解析できる最初の版）より新しい API を、名前で引いて使う。
 *
 * <p>本体は jbang（{@code //DEPS}）でも閉域ネットワークの手順（Pleiades / Eclipse の jar を集めて javac）でも、
 * そこにある JDT の jar に対してソースからコンパイルされる。下限より新しい API をソースに直接書くと、古い jar では
 * コンパイルが通らない。そこで、下限に無い API はここに集めて、クラス・メソッドを名前で探す。
 * 見つからなければ「その構文を JDT が読めない版」なので、その構文に当たるノードは AST に現れない。
 *
 * <h2>ここに置いているもの（括弧は JDT に入った版）</h2>
 * <ul>
 *   <li>{@code RecordPattern}（3.34.0）… {@link #isRecordPattern}・{@link #recordPatternType}</li>
 *   <li>{@code IProblem.VarCannotBeUsedWithTypeArguments}（3.36.0）… {@link #VAR_CANNOT_BE_USED_WITH_TYPE_ARGUMENTS}</li>
 *   <li>{@code ImplicitTypeDeclaration}（3.38.0）… {@link #isImplicitTypeDeclaration}</li>
 *   <li>{@code ImportDeclaration#getModifiers()}・{@code Modifier#isModule(int)}（3.40.0）… {@link #isModuleImport}</li>
 * </ul>
 * 下限で書けることは {@code test/jdt-floor/run.sh} が 3.28.0 の jar でコンパイルして確かめる。
 * 新しい API を足すときはここに置き、上の一覧に版を書く。下限を下げるときに何が増えるかは
 * docs/jdt-floor-qa.md（3.27.0 以前は Java 17 の文法を読めない）。
 *
 * <h2>見つかったのに呼べないときは止める</h2>
 * 名前で見つかったメソッドの呼び出しが失敗するのは、JDT の API が想定と違う形に変わったときである。
 * 黙って「無い」扱いにすると、その構文の呼び出しが出力から静かに落ちるので、{@link IllegalStateException} で止める
 * （そのファイルは解析の失敗として warnings.txt に載る）。
 */
final class JdtCompat {

    /** 本体がコンパイルでき、結果が今の JDT と同じであることを検査している JDT の下限 */
    static final String FLOOR = "3.28.0";

    /**
     * {@code IProblem.VarCannotBeUsedWithTypeArguments}（3.36.0〜）と同じ値。{@code case} のラベルに使うので
     * 定数で持つ。それより古い JDT はこの問題を報告しないので、ラベルが余っても害は無い
     */
    static final int VAR_CANNOT_BE_USED_WITH_TYPE_ARGUMENTS = IProblem.Syntax + 1513;

    private static final Class<?> RECORD_PATTERN = domClass("RecordPattern");
    private static final Method RECORD_PATTERN_TYPE = method(RECORD_PATTERN, "getPatternType");
    private static final Class<?> IMPLICIT_TYPE_DECLARATION = domClass("ImplicitTypeDeclaration");
    private static final Method IMPORT_MODIFIERS = method(ImportDeclaration.class, "getModifiers");
    private static final Method IS_MODULE = method(Modifier.class, "isModule", int.class);

    private JdtCompat() {
    }

    /** レコードパターン（JLS 14.30.1）のノードか。読めない版の JDT では常に false */
    static boolean isRecordPattern(ASTNode node) {
        return RECORD_PATTERN != null && RECORD_PATTERN.isInstance(node);
    }

    /** レコードパターンの型（{@code RecordPattern#getPatternType()}）。{@link #isRecordPattern} が true のノードに使う */
    static Type recordPatternType(ASTNode recordPattern) {
        return (Type) invoke(RECORD_PATTERN_TYPE, recordPattern);
    }

    /** コンパクトなコンパイル単位（JLS 7.3）が暗黙に宣言するクラスのノードか。読めない版の JDT では常に false */
    static boolean isImplicitTypeDeclaration(ASTNode node) {
        return IMPLICIT_TYPE_DECLARATION != null && IMPLICIT_TYPE_DECLARATION.isInstance(node);
    }

    /**
     * モジュールインポート宣言（{@code import module m;}。JLS 7.5.5）か。{@code getModifiers()} が無い版の JDT は
     * この宣言を構文エラーにするので、AST に残った import はどれも型か static のインポートである
     */
    static boolean isModuleImport(ImportDeclaration imp) {
        return IMPORT_MODIFIERS != null && IS_MODULE != null
                && (Boolean) invoke(IS_MODULE, null, invoke(IMPORT_MODIFIERS, imp));
    }

    private static Class<?> domClass(String simpleName) {
        try {
            return Class.forName("org.eclipse.jdt.core.dom." + simpleName, false, ASTNode.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameterTypes) {
        if (owner == null) {
            return null;
        }
        try {
            return owner.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Object invoke(Method m, Object target, Object... args) {
        try {
            return m.invoke(target, args);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException(Messages.format("analysis.jdtApi",
                    m.getDeclaringClass().getSimpleName() + "#" + m.getName(), e), e);
        }
    }
}
