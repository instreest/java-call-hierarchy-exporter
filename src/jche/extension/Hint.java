// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.extension;

/**
 * 拡張に渡す証拠。キーと値だけの汎用の箱にしてある。
 *
 * 例: ファクトリメソッド {@code DaoFactory.get("USER_DAO")} の文字列リテラルを
 * {@code kind="FACTORY_KEY", value="USER_DAO"} として渡す。
 *
 * <h2>出どころは 2 つある</h2>
 * <ul>
 *   <li>フェーズAの拡張（{@link CallSiteHintCollector}）が拾ってキャッシュに残したもの</li>
 *   <li><b>ツールがデータフローから読んで足すもの</b>。ファクトリに渡されたキーは値グラフに
 *       載っているので、フェーズAの拡張を書かなくても下の 3 つが届く
 *       （{@code jche.graph.FactoryCalls}）</li>
 * </ul>
 * どちらも同じ形で渡るので、受け取る側は区別しなくてよい。
 */
public record Hint(String kind, String value) {

    /**
     * ファクトリのメソッド。値は {@code 型FQN#メソッド名}。
     *
     * 同じ型を返すファクトリが複数あって規則が違うとき、これで場合分けできる
     * （以前は自前のフェーズA拡張を書いて証拠の種別に埋め込む必要があった）
     */
    public static final String KIND_FACTORY = "FACTORY";

    /** ファクトリに渡された文字列のキー。値はその文字列（コンパイル時定数は評価済みの値） */
    public static final String KIND_FACTORY_KEY = "FACTORY_KEY";

    /** ファクトリに渡された列挙定数。値は {@code 型FQN.定数名} */
    public static final String KIND_FACTORY_CONST = "FACTORY_CONST";

    @Override
    public String toString() {
        return kind + "=" + value;
    }
}
