// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jche.cache.OverrideFact;

/**
 * 「上書きされた側のキー → 上書きしている側のメソッドID」の逆引き（O行から構築）。
 *
 * <h2>なぜ要るのか</h2>
 * 候補引きはメソッドのキー（{@code typeFqn#name(消去済み引数型)}）の文字列一致で行うが、
 * JLS 8.4.2 のオーバーライドは「同じシグネチャ<b>または</b>消去したシグネチャと同じ」なので、
 * 型引数を具体化した実装はキーが一致しない。
 * <pre>
 *   Repo#save(java.lang.Object)    ← 呼び出し先（宣言）
 *   UserRepo#save(g.User)          ← 実際に動く実装。キーは一致しない
 * </pre>
 * 書き手（AST走査）が {@code IMethodBinding.overrides} で判定した結果を O行に残してあるので、
 * ここではその逆引きを作るだけでよい。上書きの判定そのものはやり直さない。
 *
 * <h2>推移的な関係も1段で引ける</h2>
 * 書き手は<b>推移的な親型すべて</b>に対して判定して書くので、
 * {@code UserRepo → AbstractRepo → Repo} のような多段でも、
 * {@code Repo#save(…)} から直接 {@code UserRepo#save(…)} が引ける。
 * 読む側で推移閉包を取る必要は無い。
 */
public final class OverrideIndex {

    /** 上書きされた側のキー -> 上書きしている側のメソッドID（重複なし・登録順） */
    private final Map<String, IntArray> overriders = new HashMap<>();

    /** O行を1件取り込む。{@code id} はその行が表す宣言のメソッドID */
    void add(OverrideFact fact, int id) {
        for (String key : fact.keys()) {
            overriders.computeIfAbsent(key, k -> new IntArray(2)).addIfAbsent(id);
        }
    }

    /**
     * そのキーの宣言を上書きしているメソッドの一覧。無ければ null。
     *
     * 並びは O行の出現順（＝キャッシュ上の並び）だが、呼び出し側は
     * {@link CallGraph#implementationOf} で型を辿って1件に決めるので、
     * 差分更新でブロックが動いても結果は変わらない。
     */
    public IntArray overridersOf(String overriddenKey) {
        return overriders.get(overriddenKey);
    }

    public boolean isEmpty() {
        return overriders.isEmpty();
    }

    /** 取り込んだ上書き関係の件数（ログ用） */
    public int size() {
        int n = 0;
        for (IntArray ids : overriders.values()) {
            n += ids.size();
        }
        return n;
    }

    /** 上書きされた側のキー一覧（検査用） */
    public List<String> keys() {
        return List.copyOf(overriders.keySet());
    }
}
