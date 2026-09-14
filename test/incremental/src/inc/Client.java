// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package inc;

/** Names 経由で Base の定数を使う（Base は参照する型に現れない） */
public class Client {

    public void run() {
        Dao dao = Factory.create(Names.DAO);
        dao.select();
    }
}
