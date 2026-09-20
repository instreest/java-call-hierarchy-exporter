// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 同梱の契約表（{@link JdkCallbacks} / {@link BundledFrameworkEntries}）の検査。
 *
 * 契約表は文字列なので、形を崩しても・JDK 側でメソッドの宣言元が動いても、
 * コンパイルは通り、実行時は「当たらない」だけで何も言わない。ここで機械的に見る。
 * <pre>
 *   形      すべての行が parse できる（形が違えば読み込み時に捨てられて黙って効かなくなる）
 *   重複    同じ行が 2 回無い
 *   宣言元  JDK の型は、その型が本当にそのメソッドを「宣言」していること（継承しているだけでは
 *           JDT の getMethodDeclaration がその型を返さず、行は永久に当たらない）
 *   呼び戻し 契約の位置にある値の型が、呼び戻すメソッドを持っていること
 *   具象型  種類 C（{@code 型 => 具象型}、{@code 型#メソッド("キー") => 具象型}、
 *           {@code 型#メソッド(列挙定数のFQN) => 具象型}）の読み書き。同梱の行は無いので形だけを見る
 * </pre>
 * 型の照合は実行中の JDK のリフレクションで行う。リフレクションが返す宣言クラスと型消去後の
 * 引数型は .class の記述子そのもので、JDT の {@code getMethodDeclaration().getErasure()} と同じ形になる
 * （docs/callback-contracts-qa.md）。JDK に無い型（Servlet・Spring 等）は形だけを見る。
 */
public final class BundledContractsCheck {

    private static int failures;
    private static int checked;

    private BundledContractsCheck() {
    }

    public static void main(String[] args) {
        checkCallbacks();
        checkEntries();
        checkTypes();
        checkRejects();
        System.out.println("  検査した行: " + checked + "、問題: " + failures);
        if (failures > 0) {
            System.exit(1);
        }
    }

    // ---- A 型: JDK が呼び戻す ----

    private static void checkCallbacks() {
        Set<String> seen = new HashSet<>();
        for (String line : JdkCallbacks.LINES) {
            checked++;
            CallbackContracts.Contract c = CallbackContracts.parse(line);
            if (c == null) {
                ng(line, "形が違う（parse が null）");
                continue;
            }
            if (!seen.add(c.text())) {
                ng(line, "重複している");
            }
            MethodSig callee = MethodSig.of(c.calleeKey());
            if (callee == null) {
                ng(line, "呼び出し先のキーが 型#名前(引数,...) の形でない");
                continue;
            }
            Class<?> owner = classOf(callee.type);
            if (owner == null) {
                if (callee.type.startsWith("java.")) {
                    ng(line, "型が JDK に無い: " + callee.type);
                }
                continue;
            }
            Method m = declared(owner, callee);
            if (m == null) {
                Method inherited = inherited(owner, callee);
                if (inherited == null) {
                    ng(line, "そのメソッドが無い: " + callee);
                } else {
                    ng(line, "宣言元が違う。JDT は " + inherited.getDeclaringClass().getName()
                            + " に解決するので、この行は当たらない");
                }
                continue;
            }
            Class<?> value = valueTypeOf(c, m);
            if (value == null) {
                ng(line, "契約の位置（" + c.where() + c.index() + "）に当たる引数が無い");
                continue;
            }
            MethodSig cb = MethodSig.of("x#" + c.callbackSig());
            if (cb == null || inherited(value, cb) == null) {
                ng(line, value.getName() + " に " + c.callbackSig() + " が無い");
            }
        }
    }

    /** 契約の位置にある値の型。r / c* はレシーバの型、aN は N 番目の引数の型 */
    private static Class<?> valueTypeOf(CallbackContracts.Contract c, Method callee) {
        if (c.where() != CallbackContracts.Contract.ARGUMENT) {
            return callee.getDeclaringClass();
        }
        Class<?>[] params = callee.getParameterTypes();
        if (c.index() == CallbackContracts.Contract.ANY) {
            return params.length == 0 ? null : params[0];
        }
        return (c.index() < params.length) ? params[c.index()] : null;
    }

    // ---- B 型: フレームワークが起点として呼ぶ ----

    private static void checkEntries() {
        Set<String> seen = new HashSet<>();
        for (String line : BundledFrameworkEntries.LINES) {
            checked++;
            FrameworkEntries.Contract c = FrameworkEntries.parse(line);
            if (c == null) {
                ng(line, "形が違う（parse が null）");
                continue;
            }
            if (!seen.add(c.text())) {
                ng(line, "重複している");
            }
            switch (c.kind()) {
                case FrameworkEntries.Contract.ANNOTATION -> {
                    if (!looksLikeFqn(c.value())) {
                        ng(line, "アノテーションの完全修飾名でない: " + c.value());
                    }
                }
                case FrameworkEntries.Contract.SUPER -> {
                    MethodSig sig = MethodSig.of(c.value() + "#" + c.sig());
                    if (!looksLikeFqn(c.value()) || sig == null) {
                        ng(line, "super 型#名前(引数,...) の形でない");
                        continue;
                    }
                    Class<?> owner = classOf(c.value());
                    if (owner != null && inherited(owner, sig) == null) {
                        ng(line, c.value() + " に " + c.sig() + " が無い");
                    } else if (owner == null && c.value().startsWith("java.")) {
                        ng(line, "型が JDK に無い: " + c.value());
                    }
                }
                case FrameworkEntries.Contract.STATIC -> {
                    if (MethodSig.of("x#" + c.sig()) == null) {
                        ng(line, "static 名前(引数,...) の形でない");
                    }
                }
                default -> ng(line, "知らない種類: " + c.kind());
            }
        }
    }

    // ---- C 型: 具象型（同梱の行は無いので、読み書きの形だけを見る） ----

    private static void checkTypes() {
        // 読めるべき形。左辺の型・メソッド名と、右辺の候補が取り出せること
        record Case(String line, String type, String method, String[] candidates) {
        }
        Case[] good = {
            new Case("jp.co.xxx.UserDao => jp.co.xxx.UserDaoImpl",
                    "jp.co.xxx.UserDao", "", new String[] {"jp.co.xxx.UserDaoImpl"}),
            new Case("jp.co.xxx.UserDao#find => jp.co.xxx.CachedUserDao",
                    "jp.co.xxx.UserDao", "find", new String[] {"jp.co.xxx.CachedUserDao"}),
            new Case("  jp.co.xxx.Dao  =>  jp.co.xxx.A ,  jp.co.xxx.B  ",
                    "jp.co.xxx.Dao", "", new String[] {"jp.co.xxx.A", "jp.co.xxx.B"}),
        };
        for (Case c : good) {
            checked++;
            TypeContracts.Contract parsed = TypeContracts.parse(c.line());
            if (parsed == null) {
                ng(c.line(), "読めるべき形が parse できない");
                continue;
            }
            if (!c.type().equals(parsed.declaredType()) || !c.method().equals(parsed.methodName())
                    || !parsed.key().isEmpty()
                    || !Arrays.equals(c.candidates(), parsed.candidates())) {
                ng(c.line(), "読み取った内容が違う: 型=" + parsed.declaredType()
                        + " メソッド=" + parsed.methodName()
                        + " 候補=" + Arrays.toString(parsed.candidates()));
            }
        }
        // C-3（ファクトリ＋キー）。左辺の型・メソッド名とキーが取り出せること
        checked++;
        String factoryLine = "jp.co.xxx.Factory#get(\"USER\") => jp.co.xxx.UserImpl";
        TypeContracts.Contract factory = TypeContracts.parse(factoryLine);
        if (factory == null) {
            ng(factoryLine, "C-3 の行が parse できない");
        } else if (!"jp.co.xxx.Factory".equals(factory.declaredType())
                || !"get".equals(factory.methodName()) || !"USER".equals(factory.key())) {
            ng(factoryLine, "C-3 の読み取りが違う: 型=" + factory.declaredType()
                    + " メソッド=" + factory.methodName() + " キー=" + factory.key());
        }
        // 列挙定数のキーは引用符なしの FQN。Java のソースに書く形と同じ
        checked++;
        String enumLine = "jp.co.xxx.Factory#get(jp.co.xxx.Kind.USER) => jp.co.xxx.UserImpl";
        TypeContracts.Contract enumKey = TypeContracts.parse(enumLine);
        if (enumKey == null || !"jp.co.xxx.Kind.USER".equals(enumKey.key())) {
            ng(enumLine, "列挙定数のキーを読めていない");
        }
        // 文字列のキーと列挙定数のキーは、同じ綴りでも別の行として区別されること
        checked++;
        TypeContracts.Contract quoted = TypeContracts.parse(
                "jp.co.xxx.Factory#get(\"jp.co.xxx.Kind.USER\") => jp.co.xxx.UserImpl");
        if (quoted == null || enumKey == null || quoted.keyKind() == enumKey.keyKind()) {
            ng(enumLine, "引用符の有無でキーの種別が分かれていない");
        }
        // 修飾されていない名前は、書き間違いとして弾く（助言を添えられるよう実引数の有無は見分ける）
        checked++;
        String unqualified = "jp.co.xxx.Factory#get(USER) => jp.co.xxx.UserImpl";
        if (TypeContracts.parse(unqualified) != null || !TypeContracts.hasArguments(unqualified)) {
            ng(unqualified, "修飾されていないキーを読んでしまう、または実引数の形と見分けられていない");
        }
        checked++;
        if (TypeContracts.hasArguments("jp.co.xxx.UserDao => jp.co.xxx.UserDaoImpl")) {
            ng("C-1 の行", "実引数を書いた形ではないのにそう判定された");
        }
        // C-1 / C-2 の行にキーは入らない
        checked++;
        TypeContracts.Contract plain = TypeContracts.parse("jp.co.xxx.UserDao#find => jp.co.xxx.Impl");
        if (plain == null || !plain.key().isEmpty()) {
            ng("C-2 の行", "キーの無い行にキーが入っている");
        }
        // 種類の振り分けが取り違えられないこと。"=>" は "->" を含まない
        checked++;
        if ("jp.co.xxx.UserDao => jp.co.xxx.UserDaoImpl".contains("->")) {
            ng("=> の行", "呼び戻し（->）の行として振り分けられてしまう");
        }
    }

    // ---- 形の違う行が黙って通らないこと ----

    private static void checkRejects() {
        String[] badCallbacks = {
            "java.lang.Thread#start() -> run()",          // 位置が無い
            "java.lang.Thread#start() -> x0 : run()",     // 知らない位置
            "java.lang.Thread#start() -> a : run()",      // 番号が無い
            "-> a0 : run()",                              // 呼び出し先が無い
            "java.lang.Thread#start() -> a0 :",           // 呼び戻すメソッドが無い
        };
        for (String s : badCallbacks) {
            checked++;
            if (CallbackContracts.parse(s) != null) {
                ng(s, "形が違うのに parse が通った");
            }
        }
        String[] badEntries = {
            "@",                                          // 名前が無い
            "super javax.servlet.http.HttpServlet",       // # が無い
            "super javax.servlet.http.HttpServlet#",      // シグネチャが無い
            "static",                                     // シグネチャが無い
            "public void main()",                         // 知らない書き出し
            "java.lang.Thread#start()",                   // A 型の矢印が無い
        };
        for (String s : badEntries) {
            checked++;
            if (FrameworkEntries.parse(s) != null) {
                ng(s, "形が違うのに parse が通った");
            }
        }
        String[] badTypes = {
            "=> jp.co.xxx.UserDaoImpl",                   // 左辺が無い
            "jp.co.xxx.UserDao =>",                       // 右辺が無い
            "jp.co.xxx.UserDao => ,",                     // 右辺が区切りだけ
            "jp.co.xxx.UserDao# => jp.co.xxx.Impl",       // メソッド名が無い
            "#find => jp.co.xxx.Impl",                    // 型が無い
            "jp.co.xxx.UserDao",                          // 矢印が無い
            "jp.co.xxx.Factory#get(\"USER\" => jp.co.xxx.Impl",   // 閉じ括弧が無い
            "jp.co.xxx.Factory#get(\"\") => jp.co.xxx.Impl",      // キーが空
            "jp.co.xxx.Factory(\"USER\") => jp.co.xxx.Impl",      // メソッド名が無い
            "jp.co.xxx.Factory#get(1.5) => jp.co.xxx.Impl",       // 修飾名でも文字列でもない
            "jp.co.xxx.Factory#get(.USER) => jp.co.xxx.Impl",     // 修飾名の形が壊れている
        };
        for (String s : badTypes) {
            checked++;
            if (TypeContracts.parse(s) != null) {
                ng(s, "形が違うのに parse が通った");
            }
        }
        checked++;
        if (CallbackContracts.parse("# comment") != null || CallbackContracts.parse("  ") != null
                || FrameworkEntries.parse("# comment") != null || FrameworkEntries.parse("") != null
                || TypeContracts.parse("# comment") != null || TypeContracts.parse("") != null) {
            ng("# / 空行", "コメント・空行は無視されるべき");
        }
    }

    // ---- 道具 ----

    /** "型#名前(引数,引数)" */
    private record MethodSig(String type, String name, List<String> params) {
        static MethodSig of(String key) {
            int hash = key.indexOf('#');
            int open = key.indexOf('(', hash);
            if (hash <= 0 || open < 0 || !key.endsWith(")")) {
                return null;
            }
            String name = key.substring(hash + 1, open);
            String inner = key.substring(open + 1, key.length() - 1).trim();
            if (name.isEmpty() || name.contains(" ")) {
                return null;
            }
            List<String> params = new ArrayList<>();
            if (!inner.isEmpty()) {
                for (String p : inner.split(",")) {
                    if (p.isBlank() || p.contains(" ") || p.contains("<")) {
                        return null;   // 型消去前の形（ジェネリクス）は不可
                    }
                    params.add(p.trim());
                }
            }
            return new MethodSig(key.substring(0, hash), name, params);
        }

        @Override
        public String toString() {
            return type + "#" + name + "(" + String.join(",", params) + ")";
        }
    }

    /** その型自身が宣言しているメソッド（継承分は含まない） */
    private static Method declared(Class<?> owner, MethodSig sig) {
        for (Method m : owner.getDeclaredMethods()) {
            if (matches(m, sig)) {
                return m;
            }
        }
        return null;
    }

    /** 継承分も含めて探す（public でないものも見る） */
    private static Method inherited(Class<?> owner, MethodSig sig) {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            Method m = declared(c, sig);
            if (m != null) {
                return m;
            }
        }
        for (Class<?> i : allInterfaces(owner)) {
            Method m = declared(i, sig);
            if (m != null) {
                return m;
            }
        }
        return null;
    }

    private static Set<Class<?>> allInterfaces(Class<?> type) {
        Set<Class<?>> out = new HashSet<>();
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            collectInterfaces(c, out);
        }
        return out;
    }

    private static void collectInterfaces(Class<?> c, Set<Class<?>> out) {
        for (Class<?> i : c.getInterfaces()) {
            if (out.add(i)) {
                collectInterfaces(i, out);
            }
        }
    }

    private static boolean matches(Method m, MethodSig sig) {
        if (!m.getName().equals(sig.name()) || m.isSynthetic() || Modifier.isPrivate(m.getModifiers())) {
            return false;
        }
        Class<?>[] params = m.getParameterTypes();
        if (params.length != sig.params().size()) {
            return false;
        }
        for (int i = 0; i < params.length; i++) {
            if (!typeName(params[i]).equals(sig.params().get(i))) {
                return false;
            }
        }
        return true;
    }

    /** JDT の getQualifiedName と同じ形（ネストは '.'、配列は "[]"） */
    private static String typeName(Class<?> c) {
        return c.isArray() ? typeName(c.getComponentType()) + "[]" : c.getName().replace('$', '.');
    }

    /** 名前から Class。ネスト型は '.' を後ろから '$' に読み替えて探す。無ければ null */
    private static Class<?> classOf(String name) {
        String n = name;
        while (true) {
            try {
                return Class.forName(n, false, BundledContractsCheck.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                int dot = n.lastIndexOf('.');
                if (dot < 0) {
                    return null;
                }
                n = n.substring(0, dot) + "$" + n.substring(dot + 1);
            }
        }
    }

    private static boolean looksLikeFqn(String s) {
        return s.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
    }

    private static void ng(String line, String why) {
        failures++;
        System.out.println("  NG   " + line);
        System.out.println("       " + why);
    }
}
