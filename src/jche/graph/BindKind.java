// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import jche.cache.MethodRef;
import jche.cache.ModifierTokens;

/**
 * 呼び出しの束縛の種別（読み手の判断）。C行の calleeMods（呼び出し先の修飾子）から決め、
 * エッジごとに1バイトで持つ。
 *
 * 宣言型が具象クラスであることは根拠にならない。
 * <pre>
 *   Base b = new Derived(); b.m();  // 実際に走るのは Derived.m
 * </pre>
 * 正しい軸は「静的束縛か仮想呼び出しか」。
 * final は CGLIB でもインターセプトできず、final クラスはサブクラスを作れない。
 */
public final class BindKind {

    // --- 静的束縛（仮想ディスパッチされない） ---
    public static final char CONSTRUCTOR = 'C';
    public static final char SUPER = 'U';
    public static final char PRIVATE = 'P';
    public static final char STATIC = 'T';
    public static final char FINAL_METHOD = 'F';
    public static final char FINAL_CLASS = 'L';
    /** 仮想呼び出し（オーバーライドされうる） */
    public static final char VIRTUAL = 'V';
    /**
     * import からの推定（U行の candidate）で作った合成エッジ。
     * 型階層情報を一切持たない合成メソッドのため、「候補は常にこの1件」として扱い、
     * CHA展開の対象にはしない
     */
    public static final char GUESSED = 'G';

    private BindKind() {
    }

    public static char of(String calleeMethod, String calleeMods) {
        if (MethodRef.CONSTRUCTOR.equals(calleeMethod)) {
            return CONSTRUCTOR;
        }
        if (ModifierTokens.has(calleeMods, ModifierTokens.SUPER)) {
            return SUPER;
        }
        if (ModifierTokens.has(calleeMods, "private")) {
            return PRIVATE;
        }
        if (ModifierTokens.has(calleeMods, "static")) {
            return STATIC;
        }
        if (ModifierTokens.has(calleeMods, "final")) {
            return FINAL_METHOD;
        }
        if (ModifierTokens.has(calleeMods, ModifierTokens.FINAL_CLASS)) {
            return FINAL_CLASS;
        }
        return VIRTUAL;
    }

    /**
     * 段0 の解決ラベル。仕様の上で仮想呼び出しでないもの（JLS 15.12.3 の呼び出し方式 static / nonvirtual / super と
     * コンストラクタ。JVMS では invokestatic / invokespecial）は "STATIC_BOUND:理由"、
     * 仮想呼び出し（invokevirtual）だが上書きできないので選ばれる本体が 1 つに決まるもの（final メソッド・
     * final クラスのメソッド。JLS 8.4.3.3・8.1.1.2）は "NOT_OVERRIDABLE:理由" として出力に残す。
     * どちらも候補は呼び出し先の 1 件で扱いは同じで、名前だけを仕様に合わせて分ける
     * （docs/resolved-by-naming-qa.md の Q3）
     */
    public static String label(char bindKind) {
        return switch (bindKind) {
            case FINAL_METHOD -> Resolution.NOT_OVERRIDABLE_PREFIX + "FINAL_METHOD";
            case FINAL_CLASS -> Resolution.NOT_OVERRIDABLE_PREFIX + "FINAL_CLASS";
            case PRIVATE -> Resolution.STATIC_BOUND_PREFIX + "PRIVATE";
            case STATIC -> Resolution.STATIC_BOUND_PREFIX + "STATIC";
            case CONSTRUCTOR -> Resolution.STATIC_BOUND_PREFIX + "CTOR";
            case SUPER -> Resolution.STATIC_BOUND_PREFIX + "SUPER";
            default -> Resolution.STATIC_BOUND_PREFIX + "OTHER";
        };
    }
}
