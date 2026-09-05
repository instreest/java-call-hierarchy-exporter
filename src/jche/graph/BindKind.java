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

    /** 静的束縛と判定した理由。解決ラベル "STATIC_BOUND:理由" として出力に残す */
    public static String staticBoundReason(char bindKind) {
        return switch (bindKind) {
            case PRIVATE -> "PRIVATE";
            case STATIC -> "STATIC";
            case FINAL_METHOD -> "FINAL_METHOD";
            case FINAL_CLASS -> "FINAL_CLASS";
            case CONSTRUCTOR -> "CTOR";
            case SUPER -> "SUPER";
            default -> "OTHER";
        };
    }
}
