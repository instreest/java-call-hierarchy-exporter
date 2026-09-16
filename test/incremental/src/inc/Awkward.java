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

    /**
     * 64 文字を超え、タブと改行を含む文字列を実引数に渡す。
     * analysis 側の出所は「FQN か識別子の形で 64 文字以内」しか拾わないので値を捨てるが、
     * dataflow 側の値グラフ（N 行）はこれをそのまま持つ。行が割れないことを含めて検査する
     */
    public void longLiteral() {
        Log.write("SELECT id, name, kind, created_at\n"
                + "\tFROM orders\n"
                + "\tWHERE kind = ? AND created_at > ?\n"
                + "\tORDER BY id DESC");
    }

    /**
     * 実引数の入れ子。analysis 側の出所は実引数を1段で剥がすので Factory.create の先は見えないが、
     * dataflow 側の値グラフは何段でも辿る
     */
    public void nestedArgs() {
        Log.write(String.valueOf(Factory.create(Names.DAO)));
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
