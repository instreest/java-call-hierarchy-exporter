// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package inc;

/** Names 経由で Base の定数を使う（Base は参照する型に現れない） */
public class Client {

    public void run() {
        Dao dao = Factory.create(Names.DAO);
        dao.select();
        local();
    }

    /**
     * new だけが代入されるローカル変数への呼び出し。書き手がファイルの中で new の証拠を結びつけ、
     * C 行の hints 列に載せる（差分更新で解析し直したブロックと書き写したブロックの両方で見る）
     */
    void local() {
        Dao local = new AlphaDao();
        local.select();
    }
}
