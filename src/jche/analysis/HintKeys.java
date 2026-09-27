// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.SimpleName;

/**
 * new の証拠（{@link jche.cache.HintFact}）を呼び出し箇所に結び付けるキーの作り方。
 *
 * 証拠を残す側（{@link FactVisitor}）と、呼び出し箇所を記録する側（{@link CallSiteRecorder}）が
 * 1 文字でも違うキーを付けると結び付かない。両者が同じ計算を使うように、唯一の実装をここに置く。
 * 結び付けは書き手がブロックを書くときに行い（{@code CacheUpdater}）、キーそのものはキャッシュに書かない。
 *
 * <pre>
 *   Dao d = new UserDaoImpl();   // ← d の宣言。キーは d のバインディングキー
 *   d.find();                    // ← ここのレシーバ d のキーと一致する
 * </pre>
 */
final class HintKeys {

    private HintKeys() {
    }

    /**
     * 呼び出しのレシーバ式に対応するキー。変数（の単純名）ならそのバインディングキー、そうでなければ空。
     *
     * 以前は変数でない式にも「式の開始位置」のキーを付けていたが、証拠は変数のキーでしか残さないので
     * 一度も結び付いたことがなかった。結び付かないキーは作らない
     */
    static String ofReceiver(Expression receiver) {
        if (receiver instanceof SimpleName name && name.resolveBinding() instanceof IVariableBinding vb) {
            return ofVariable(vb);
        }
        return "";
    }

    /**
     * 変数のキー。バインディングが取れないときは空文字。
     * 空白は {@code _} に潰す（キャッシュの行に書いていた頃の名残。証拠を残す側と呼び出し箇所の側が
     * 同じ計算を通るので、結び付きには影響しない）
     */
    static String ofVariable(IVariableBinding binding) {
        if (binding == null) {
            return "";
        }
        String key = binding.getKey();
        return (key == null) ? "" : key.replaceAll("\\s", "_");
    }
}
