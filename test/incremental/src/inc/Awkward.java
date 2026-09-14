// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package inc;

/**
 * 行形式を壊しかねない値を持つクラス。
 *
 * 複数行の注釈の値・タブ・改行を含む定数は、そのままキャッシュの行に書くと行が割れ、
 * 以降の事実（メソッド宣言や呼び出し）が読めなくなる。2 行目が "FROM …" のような
 * 行種別と同じ文字で始まると、別のファイルのブロックとして読まれてしまう。
 */
@Tag("alpha")
public class Awkward {

    /** 2 行目が F 行に見える注釈の値 */
    @Sql("SELECT u\n"
            + "FROM User u\n"
            + "WHERE u.name = ?1")
    public void query() {
        Log.write("query");
    }

    public void separators() {
        char sep = '\t';
        char eol = '\n';
        Log.write("" + sep + eol);
        if (Separators.TAB == '\t') {
            Log.write("tab");
        }
    }
}
