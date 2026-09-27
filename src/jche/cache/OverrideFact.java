// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cache;

import java.util.List;

/**
 * メソッド宣言が「どの宣言を上書きしているか」の1件（O行）。
 *
 * <h2>なぜ別の事実として要るのか</h2>
 * メソッドのキーは {@code typeFqn#name(消去済み引数型FQN,…)} だが、JLS 8.4.2 の
 * オーバーライドは「同じシグネチャ<b>または</b>消去したシグネチャと同じ（サブシグネチャ）」なので、
 * 消去シグネチャ1本では関係を表現できない。
 * <pre>
 *   interface Repo&lt;T&gt; { void save(T t); }          キー: Repo#save(java.lang.Object)
 *   class UserRepo implements Repo&lt;User&gt; {
 *       public void save(User u) { … }               キー: UserRepo#save(g.User)
 *   }
 * </pre>
 * 文字列としては一致しないため、キーの照合だけで候補を引くと
 * 「実装が無い」（NO_IMPL）や、もっと悪いことに「別の実装1件に確定」（SINGLE_IMPL）になる。
 *
 * 判定そのものは JDT の {@code IMethodBinding.overrides}（JLS 8.4.8.1 の実装）が行い、
 * ここにはその<b>結果だけ</b>を事実として残す。読み手（{@link jche.graph.OverrideIndex}）が
 * 逆引きを作り、候補引きで使う。
 *
 * <p>シグネチャ（{@code name(paramSig)}）が自分と同じ宣言は書かない。読み手は候補を
 * 「型FQN + '#' + シグネチャ」で引き、その型から親へ辿るので、シグネチャが同じなら
 * キーの照合だけで引ける。型引数を使っていない普通のオーバーライドはすべてこちらで、
 * 書いてもキャッシュが膨らむだけである。
 *
 * @param ref             上書きしている側（この宣言）
 * @param overriddenKeys  上書きされている側のキー（{@code typeFqn#name(paramSig)}）を
 *                        {@link #SEP} で連結したもの。paramSig がカンマを含むためカンマは使えない
 */
public record OverrideFact(MethodRef ref, String overriddenKeys) {

    /** 複数のキーを並べるときの区切り。型名・メソッド名・引数シグネチャのどれにも現れない文字 */
    public static final String SEP = ";";

    public OverrideFact {
        overriddenKeys = (overriddenKeys == null) ? "" : overriddenKeys;
    }

    public OverrideFact(MethodRef ref, List<String> keys) {
        this(ref, String.join(SEP, keys));
    }

    /** 上書きされている側のキー。無ければ空 */
    public List<String> keys() {
        return overriddenKeys.isEmpty() ? List.of() : List.of(overriddenKeys.split(SEP));
    }

    /** {@code O 記号 上書き先のキー}。記号はブロックの記号表（{@link SymbolTable}）の番号 */
    public String toRow(SymbolTable symbols) {
        return CacheFormat.joinRow("O", symbols.columnOf(ref), overriddenKeys);
    }

    /**
     * 列が足りない・記号が引けない・上書き先が空なら null（持っていても意味の無い行は読み飛ばす）
     *
     * @param symbols ブロックの記号表（{@link SymbolTable.Reader#array}）
     */
    public static OverrideFact fromRow(String[] cols, MethodRef[] symbols) {
        if (cols.length < 3) {
            return null;
        }
        MethodRef ref = SymbolTable.resolve(symbols, cols[1]);
        String keys = cols[2];
        if (ref == null || keys.isEmpty()) {
            return null;
        }
        return new OverrideFact(ref, keys);
    }
}
