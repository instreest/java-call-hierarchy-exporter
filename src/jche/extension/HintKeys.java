// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.extension;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

/**
 * 証拠（{@link Hint}）を結び付けるキーの作り方。
 *
 * フェーズAの拡張が {@link HintSink#add} に渡す scopeKey は、呼び出し箇所を記録する側が
 * 付けるレシーバのキーと1文字でも違うと結び付かない。両者が同じ計算を使うように、
 * 唯一の実装をここに置く（本体の FactVisitor もこのクラスを使う）。
 *
 * <pre>
 *   Dao d = DaoFactory.get("USER_DAO");   // ← d の宣言。scopeKey は d のバインディングキー
 *   d.find();                             // ← ここのレシーバ d のキーと一致する
 *
 *   DaoFactory.get("USER_DAO").find();    // ← 変数を介さない。scopeKey は get(...) の "@開始位置"
 * </pre>
 */
public final class HintKeys {

    private HintKeys() {
    }

    /**
     * 呼び出しのレシーバ式に対応するキー。
     *
     * ローカル変数（や引数・フィールド）ならそのバインディングキー、そうでなければ
     * 式の開始位置。バインディングキーには空白が入りうるが、キャッシュはタブ区切りの
     * 1行なので、列が崩れないよう空白は {@code _} に潰す。
     */
    public static String ofReceiver(Expression receiver) {
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
    public static String ofVariable(IVariableBinding binding) {
        if (binding == null) {
            return "";
        }
        String key = binding.getKey();
        return (key == null) ? "" : key.replaceAll("\\s", "_");
    }

    /** 式そのもののキー（変数を介さない呼び出しのレシーバ用） */
    public static String ofPosition(ASTNode node) {
        return "@" + node.getStartPosition();
    }

    /**
     * 式の値を受け取っている変数のキー。受け取っていなければ空文字。
     *
     * {@code Dao d = DaoFactory.get("K");} と {@code d = DaoFactory.get("K");} の
     * どちらの形でも、後続の {@code d.find()} と結び付くキーを返す。
     * ファクトリメソッドの戻り値に証拠を付ける拡張は、まずこれを試し、
     * 空なら {@link #ofPosition} を使えばよい。
     */
    public static String ofAssignedVariable(Expression value) {
        ASTNode parent = value.getParent();
        if (parent instanceof VariableDeclarationFragment frag && frag.getInitializer() == value) {
            return ofVariable(frag.resolveBinding());
        }
        if (parent instanceof Assignment assign && assign.getRightHandSide() == value
                && assign.getLeftHandSide() instanceof SimpleName lhs
                && lhs.resolveBinding() instanceof IVariableBinding vb) {
            return ofVariable(vb);
        }
        return "";
    }
}
