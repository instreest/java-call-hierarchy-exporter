// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.config;

/**
 * entry.packages / exclude.packages のパターン（{@link PackagePattern}）の照合の検査。
 *
 * <p>パターンは文字列なので、照合を誤ってもコンパイルは通り、除外が「効かない」か「効きすぎる」だけで何も言わない。
 * 除外が効きすぎると呼び出しが静かに出なくなるので、当たる例と当たらない例の両方を並べて見る。
 * 途中の {@code *}（任意の 1 階層）・{@code **}（任意の 0 階層以上）と、従来の末尾の {@code .*} / {@code .**}・
 * クラス指定・メソッド指定が変わらないこと。
 */
public final class PackagePatternCheck {

    private static int failures;
    private static int checked;

    private PackagePatternCheck() {
    }

    public static void main(String[] args) {
        // ---- 従来の書き方（* が末尾だけ） ----
        yes("jp.co.xxx.action.*", "jp.co.xxx.action", "jp.co.xxx.action.UserAction", "run");
        yes("jp.co.xxx.action.*", "jp.co.xxx.action", "jp.co.xxx.action.Outer.Inner", "run");
        no("jp.co.xxx.action.*", "jp.co.xxx.action.sub", "jp.co.xxx.action.sub.A", "run");
        yes("jp.co.xxx.action.**", "jp.co.xxx.action", "jp.co.xxx.action.A", "run");
        yes("jp.co.xxx.action.**", "jp.co.xxx.action.sub.deep", "jp.co.xxx.action.sub.deep.A", "run");
        no("jp.co.xxx.action.**", "jp.co.xxx.actions", "jp.co.xxx.actions.A", "run");
        yes("jp.co.xxx.action.UserAction", "jp.co.xxx.action", "jp.co.xxx.action.UserAction", "run");
        no("jp.co.xxx.action.UserAction", "jp.co.xxx.action", "jp.co.xxx.action.UserActionX", "run");
        yes("jp.co.xxx.action.UserAction#execute", "jp.co.xxx.action", "jp.co.xxx.action.UserAction", "execute");
        no("jp.co.xxx.action.UserAction#execute", "jp.co.xxx.action", "jp.co.xxx.action.UserAction", "run");
        // $ や . を正規表現として読まない
        no("jp.co.x$y.**", "jp.co.xy", "jp.co.xy.A", "run");
        yes("jp.co.x$y.*", "jp.co.x$y", "jp.co.x$y.A", "run");

        // ---- 途中の *（任意の 1 階層） ----
        yes("jp.co.*.dao.**", "jp.co.a.dao", "jp.co.a.dao.UserDao", "find");
        yes("jp.co.*.dao.**", "jp.co.b.dao.impl", "jp.co.b.dao.impl.UserDaoImpl", "find");
        no("jp.co.*.dao.**", "jp.co.dao", "jp.co.dao.UserDao", "find");
        no("jp.co.*.dao.**", "jp.co.a.b.dao", "jp.co.a.b.dao.UserDao", "find");
        no("jp.co.*.dao.**", "jp.co.a.daox", "jp.co.a.daox.UserDao", "find");
        no("jp.co.*.dao.**", "jp.co.a", "jp.co.a.Dao", "find");
        yes("jp.co.*.dao.*", "jp.co.a.dao", "jp.co.a.dao.UserDao", "find");
        no("jp.co.*.dao.*", "jp.co.a.dao.impl", "jp.co.a.dao.impl.UserDaoImpl", "find");
        yes("*.co.xxx.**", "jp.co.xxx.web", "jp.co.xxx.web.A", "run");
        no("*.co.xxx.**", "co.xxx", "co.xxx.A", "run");
        // 階層の一部の *
        yes("jp.co.xxx.*impl.**", "jp.co.xxx.daoimpl", "jp.co.xxx.daoimpl.A", "run");
        yes("jp.co.xxx.*impl.**", "jp.co.xxx.impl", "jp.co.xxx.impl.A", "run");
        no("jp.co.xxx.*impl.**", "jp.co.xxx.implx", "jp.co.xxx.implx.A", "run");
        // クラス指定・メソッド指定の途中の *
        yes("jp.co.*.dao.UserDao", "jp.co.a.dao", "jp.co.a.dao.UserDao", "find");
        no("jp.co.*.dao.UserDao", "jp.co.a.dao", "jp.co.a.dao.OrderDao", "find");
        yes("jp.co.xxx.*Dao#find", "jp.co.xxx", "jp.co.xxx.UserDao", "find");
        no("jp.co.xxx.*Dao#find", "jp.co.xxx", "jp.co.xxx.UserDao", "save");
        no("jp.co.xxx.*Dao#find", "jp.co.xxx", "jp.co.xxx.UserService", "find");

        // ---- 途中の **（任意の 0 階層以上） ----
        yes("jp.co.**.dao.*", "jp.co.dao", "jp.co.dao.UserDao", "find");
        yes("jp.co.**.dao.*", "jp.co.a.b.dao", "jp.co.a.b.dao.UserDao", "find");
        no("jp.co.**.dao.*", "jp.co.a.b.dao.impl", "jp.co.a.b.dao.impl.UserDaoImpl", "find");
        yes("jp.co.**.dao.**", "jp.co.a.b.dao.impl", "jp.co.a.b.dao.impl.UserDaoImpl", "find");
        no("jp.co.**.dao.**", "jp.co.a.b", "jp.co.a.b.Dao", "find");
        yes("**.generated.**", "generated", "generated.A", "run");
        yes("**.generated.**", "jp.co.x.generated.y", "jp.co.x.generated.y.A", "run");
        no("**.generated.**", "jp.co.x.generatedx", "jp.co.x.generatedx.A", "run");
        // 無名パッケージ
        no("*.**", "", "Main", "main");
        yes("**.Main", "", "Main", "main");

        System.out.println("  検査した例: " + checked + "、問題: " + failures);
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static void yes(String pattern, String pkg, String type, String method) {
        expect(pattern, pkg, type, method, true);
    }

    private static void no(String pattern, String pkg, String type, String method) {
        expect(pattern, pkg, type, method, false);
    }

    private static void expect(String pattern, String pkg, String type, String method, boolean want) {
        checked++;
        boolean got = PackagePattern.parse(pattern).matches(pkg, type, method);
        if (got != want) {
            failures++;
            System.out.println("  NG   " + pattern + " が " + type + "#" + method + "（パッケージ " + pkg + "）に"
                    + (want ? "当たらない（当たるはず）" : "当たる（当たらないはず）"));
        }
    }
}
