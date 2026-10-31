// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.config;

/**
 * entry.packages / exclude.packages のパターン（{@link PackagePattern}）の照合の検査。
 *
 * <p>パターンは文字列なので、照合を誤ってもコンパイルは通り、除外が「効かない」か「効きすぎる」だけで何も言わない。
 * 除外が効きすぎると呼び出しが静かに出なくなるので、当たる例と当たらない例の両方を並べて見る。
 * 途中の {@code *}（任意の 1 階層）・{@code **}（任意の 0 階層以上）、クラス指定のワイルドカードが外側のクラス名を
 * 飲み込まないこと、{@code *} が {@code $}（匿名クラス）をまたがないこと、何にも当たらない書き間違いを
 * {@code problem()} で知らせること、{@code *} を含まない指定が
 * 以前の文字列の比較と同じ答えになること。
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
        yes("jp.co.xxx.action.Outer.Inner", "jp.co.xxx.action", "jp.co.xxx.action.Outer.Inner", "run");
        no("jp.co.xxx.action.Outer.Inner", "jp.co.xxx.action", "jp.co.xxx.action.Outer", "run");
        yes("jp.co.x$y.*", "jp.co.x$y", "jp.co.x$y.A", "run");

        // ---- 途中の *（任意の 1 階層） ----
        yes("jp.co.*.dao.**", "jp.co.a.dao", "jp.co.a.dao.UserDao", "find");
        yes("jp.co.*.dao.**", "jp.co.b.dao.impl", "jp.co.b.dao.impl.UserDaoImpl", "find");
        no("jp.co.*.dao.**", "jp.co.dao", "jp.co.dao.UserDao", "find");
        no("jp.co.*.dao.**", "jp.co.a.b.dao", "jp.co.a.b.dao.UserDao", "find");
        no("jp.co.*.dao.**", "jp.co.a.daox", "jp.co.a.daox.UserDao", "find");
        // 途中の * の後ろの $ + . を正規表現として読まない（Pattern.quote を通る形）
        no("jp.co.*.x$y.**", "jp.co.a.xy", "jp.co.a.xy.A", "run");
        yes("jp.co.*.x$y.**", "jp.co.a.x$y", "jp.co.a.x$y.A", "run");
        no("jp.co.*x+y.**", "jp.co.xxy", "jp.co.xxy.A", "run");
        yes("jp.co.*x+y.**", "jp.co.ax+y", "jp.co.ax+y.A", "run");
        no("jp.co.a*.b.**", "jp.co.aXb", "jp.co.aXb.A", "run");
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
        // ** を並べても同じ。深いパッケージでも照合が組み合わせで増えない
        String deep = "a.b.c.d.e.f.g.h.i.j.k.l.m.n.o.p.q.r.s.t.u.v.w.x.y.z.a1.b1.c1.d1";
        no("**.**.**.**.**.**.zz.**", deep, deep + ".A", "run");
        yes("**.**.**.**.**.**.x.**", deep, deep + ".A", "run");
        yes("**.c.**.k.**.z.**.d1.*", deep, deep + ".A", "run");
        no("**.c.**.k.**.z.**.c1.*", deep, deep + ".A", "run");

        // ---- クラス指定のワイルドカードは外側のクラス名を飲み込まない ----
        yes("jp.co.**.UserDao", "jp.co.app", "jp.co.app.UserDao", "find");
        no("jp.co.**.UserDao", "jp.co.app", "jp.co.app.Outer.UserDao", "find");
        yes("jp.co.**.Outer.UserDao", "jp.co.app", "jp.co.app.Outer.UserDao", "find");
        yes("jp.co.*.*Impl", "jp.co.x", "jp.co.x.FooImpl", "find");
        no("jp.co.*.*Impl", "jp.co", "jp.co.Outer.FooImpl", "find");
        yes("jp.co.xxx.Outer.*Impl", "jp.co.xxx", "jp.co.xxx.Outer.FooImpl", "find");
        no("jp.co.xxx.*.FooImpl", "jp.co.xxx", "jp.co.xxx.Outer.FooImpl", "find");
        no("jp.co.**.UserDao#find", "jp.co.app", "jp.co.app.Outer.UserDao", "find");

        // ---- * は $ をまたがない（匿名クラス・ローカルクラスの名前 Outer$1 / Outer$1Local に当たらない） ----
        yes("jp.co.x.Foo*", "jp.co.x", "jp.co.x.FooHelper", "run");
        no("jp.co.x.Foo*", "jp.co.x", "jp.co.x.Foo$1", "run");
        no("jp.co.x.Foo*", "jp.co.x", "jp.co.x.Foo$1Local", "run");
        no("jp.co.x.*Local", "jp.co.x", "jp.co.x.Main$1Local", "run");
        yes("jp.co.x.Foo$*", "jp.co.x", "jp.co.x.Foo$1", "run");
        yes("jp.co.**.Foo$1", "jp.co.x", "jp.co.x.Foo$1", "run");

        // ---- 書き間違い: 何にも当たらず、problem で知らせる ----
        no("*", "", "Main", "main");
        no("**", "jp.co", "jp.co.A", "run");
        no("**", "", "Main", "main");
        problem("*", PackagePattern.Problem.WILDCARD_ONLY);
        problem("**", PackagePattern.Problem.WILDCARD_ONLY);
        no("jp.co.xxx.UserDao#get*", "jp.co.xxx", "jp.co.xxx.UserDao", "getName");
        problem("jp.co.xxx.UserDao#get*", PackagePattern.Problem.WILDCARD_IN_METHOD);
        // 文字の階層が 1 つも無い指定は、以前どおり何にも当たらない（全部に当たると出力が黙って空になる）
        for (String w : new String[] {"*.*", "*.**", "**.*", "**.**", "*.*.**", "**#run", "*#run"}) {
            problem(w, PackagePattern.Problem.WILDCARD_ONLY);
            no(w, "jp.co", "jp.co.A", "run");
            no(w, "", "Main", "run");
        }
        // クラス名の部分の ** は当たらない。書き間違いとして知らせる
        problem("jp.co.app.**#execute", PackagePattern.Problem.WILDCARD_CLASS);
        no("jp.co.app.**#execute", "jp.co.app", "jp.co.app.UserAction", "execute");
        no("jp.co.app.**#execute", "jp.co.app.sub", "jp.co.app.sub.UserAction", "execute");
        problem("jp.co.*#run", null);
        yes("jp.co.*#run", "jp.co", "jp.co.Job", "run");
        no("jp.co.*#run", "jp.co.x", "jp.co.x.Job", "run");
        problem("jp.co.**.*Dao#find", null);
        yes("jp.co.**.*Dao#find", "jp.co.a.b", "jp.co.a.b.UserDao", "find");
        problem("jp.co.*.dao.**", null);
        problem("jp.co.xxx.UserDao#find", null);
        problem("**.Main", null);

        // ---- * を含まない指定は、以前の文字列の比較と同じ答えになる ----
        sameAsLiteralCompare();

        // 無名パッケージ
        no("*.**", "", "Main", "main");
        yes("**.Main", "", "Main", "main");

        System.out.println("  検査した例: " + checked + "、問題: " + failures);
        if (failures > 0) {
            System.exit(1);
        }
    }

    /** 以前の照合（* を含まない指定の文字列の比較）と、すべての組み合わせで同じ答えになること */
    private static void sameAsLiteralCompare() {
        String[] patterns = {"jp.co.a.*", "jp.co.a.**", "jp.co.*", "jp.co.**", "jp.co.a.A", "jp.co.a.Outer.Inner",
            "jp.co.a.A#run", "jp.co.a.Outer.Inner#run", "jp.co.A", "jp.*", ".*", ".**", "Main", "Main#main", "jp.co.a",
            "jp.co.a.A$1", "jp.co.a.A$1#run", "jp..a.**", "#run"};
        String[][] names = {
            {"jp.co.a", "jp.co.a.A", "run"}, {"jp.co.a", "jp.co.a.Outer.Inner", "run"},
            {"jp.co.a.b", "jp.co.a.b.A", "run"}, {"jp.co", "jp.co.A", "run"}, {"jp.co.ab", "jp.co.ab.A", "run"},
            {"jp.co.a", "jp.co.a.A", "stop"}, {"", "Main", "main"}, {"jp", "jp.A", "run"}, {"jp.co", "jp.co.a", "run"},
            {"jp.co.a", "jp.co.a.A$1", "run"}, {"jp..a", "jp..a.A", "run"}, {"jp..a.b", "jp..a.b.A", "run"},
        };
        for (String pattern : patterns) {
            for (String[] n : names) {
                expect(pattern, n[0], n[1], n[2], literalCompare(pattern, n[0], n[1], n[2]));
            }
        }
    }

    /** 途中のワイルドカードを入れる前の照合を写したもの */
    private static boolean literalCompare(String s, String pkg, String type, String method) {
        if (s.endsWith(".**")) {
            String v = s.substring(0, s.length() - 3);
            return pkg.equals(v) || pkg.startsWith(v + ".");
        }
        if (s.endsWith(".*")) {
            return pkg.equals(s.substring(0, s.length() - 2));
        }
        int hash = s.indexOf('#');
        if (hash >= 0) {
            return type.equals(s.substring(0, hash)) && method.equals(s.substring(hash + 1));
        }
        return type.equals(s);
    }

    private static void problem(String pattern, PackagePattern.Problem want) {
        checked++;
        PackagePattern.Problem got = PackagePattern.parse(pattern).problem();
        if (got != want) {
            failures++;
            System.out.println("  NG   " + pattern + " の problem が " + got + "（" + want + " のはず）");
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
