// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.extension;

/**
 * 拡張に渡す証拠。キーと値だけの汎用の箱にしてある。
 *
 * 例: ファクトリメソッド {@code DaoFactory.get("USER_DAO")} の文字列リテラルを
 * {@code kind="FACTORY_KEY", value="USER_DAO"} として渡す。
 *
 * <h2>出どころ</h2>
 * すべて<b>ツールがデータフローから読んで渡す</b>。ファクトリに渡されたキーは値グラフに
 * 載っているので、拡張側で呼び出し箇所を走査する必要は無い（{@code jche.graph.FactoryCalls}）。
 * 同一メソッド内で {@code new} された型（{@code NEW}）だけは本体が解決に使う組み込みの証拠で、
 * こちらも同じ形で並ぶ。
 *
 * <p>扱えるキーの種類を増やすときは {@code jche.graph.FactoryCalls#readsOf} に足す。
 * 契約表・この証拠・ひな形の 3 つが同じ読み口を通るので、1 か所で揃う。
 */
public record Hint(String kind, String value) {

    /**
     * ファクトリのメソッド。値は {@code 型FQN#メソッド名}。
     *
     * 同じ型を返すファクトリが複数あって規則が違うとき、これで場合分けできる。
     * 実装が親クラスにあるときは、ソースに書いた型と宣言元の型の両方が並ぶ
     */
    public static final String KIND_FACTORY = "FACTORY";

    /** ファクトリに渡された文字列のキー。値はその文字列（コンパイル時定数は評価済みの値） */
    public static final String KIND_FACTORY_KEY = "FACTORY_KEY";

    /** ファクトリに渡された列挙定数。値は {@code 型FQN.定数名} */
    public static final String KIND_FACTORY_CONST = "FACTORY_CONST";

    /**
     * ファクトリに渡された {@code Class} リテラル。値は型の FQN。
     *
     * {@code DaoBase.getDao(UserDao.class)} のように、インターフェースそのものを
     * キーにするファクトリ向け。{@code "UserDao"} のような文字列と違い、型名は
     * そのまま具象クラス名の材料になる（{@code …UserDao} → {@code …impl.UserDaoImpl}）
     */
    public static final String KIND_FACTORY_CLASS = "FACTORY_CLASS";

    @Override
    public String toString() {
        return kind + "=" + value;
    }
}
