// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.SimpleName;

/**
 * 証拠（X 行）を結び付けるキーの作り方。
 *
 * 証拠を残す側（{@link FactVisitor}）と、呼び出し箇所を記録する側（{@link CallSiteRecorder}）が
 * 1 文字でも違うキーを付けると結び付かない。両者が同じ計算を使うように、唯一の実装をここに置く。
 *
 * <pre>
 *   Dao d = new UserDaoImpl();   // ← d の宣言。scopeKey は d のバインディングキー
 *   d.find();                    // ← ここのレシーバ d のキーと一致する
 * </pre>
 */
final class HintKeys {

    private HintKeys() {
    }

    /**
     * 呼び出しのレシーバ式に対応するキー。
     *
     * ローカル変数（や引数・フィールド）ならそのバインディングキー、そうでなければ
     * 式の開始位置。バインディングキーには空白が入りうるが、キャッシュはタブ区切りの
     * 1行なので、列が崩れないよう空白は {@code _} に潰す。
     */
    static String ofReceiver(Expression receiver) {
        if (receiver == null) {
            return "";
        }
        if (receiver instanceof SimpleName name && name.resolveBinding() instanceof IVariableBinding vb) {
            String key = ofVariable(vb);
            if (!key.isEmpty()) {
                return key;
            }
        }
        return ofPosition(receiver);
    }

    /** 変数のキー。バインディングが取れないときは空文字 */
    static String ofVariable(IVariableBinding binding) {
        if (binding == null) {
            return "";
        }
        String key = binding.getKey();
        return (key == null) ? "" : key.replaceAll("\\s", "_");
    }

    /** 式そのもののキー（変数を介さない呼び出しのレシーバ用） */
    static String ofPosition(ASTNode node) {
        return "@" + node.getStartPosition();
    }
}
