// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.config;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * エントリポイント指定・除外指定で使うパターン。
 *
 * <pre>
 *   jp.co.xxx.action.*                  … そのパッケージ直下のクラス全部
 *   jp.co.xxx.action.**                 … そのパッケージ配下（サブパッケージ含む）全部
 *   jp.co.xxx.action.UserAction         … クラス指定（内部クラスは Outer.Inner）
 *   jp.co.xxx.action.UserAction#execute … メソッド指定
 *   jp.co.*.dao.**                      … 途中の * は任意の 1 階層（jp.co.a.dao・jp.co.b.dao の配下）
 *   jp.co.**.dao.*                      … 途中の ** は任意の 0 階層以上（jp.co.dao・jp.co.a.b.dao の直下）
 * </pre>
 *
 * <p>末尾の {@code .*} / {@code .**} より前（クラス指定・メソッド指定では {@code #} より前）の部分にも
 * {@code *} を書ける。{@code .} で区切った 1 階層の中の {@code *} は {@code .} を含まない任意の文字列
 * （{@code *} だけの階層なら任意の 1 階層、{@code *Impl} なら Impl で終わる 1 階層）、階層全体が {@code **} なら
 * 任意の 0 階層以上に当たる。メソッド名には {@code *} を書けない（そのままの名前で比べる）。
 */
public final class PackagePattern {

    private enum Kind { PACKAGE_DIRECT, PACKAGE_RECURSIVE, TYPE, METHOD }

    private final Kind kind;
    private final String value;
    private final String methodName;
    /** value が * を含むときの階層ごとの照合。含まなければ null（文字列の比較で済ませる） */
    private final Segment[] glob;

    private PackagePattern(Kind kind, String value, String methodName) {
        this.kind = kind;
        this.value = value;
        this.methodName = methodName;
        this.glob = value.indexOf('*') >= 0 ? compile(value) : null;
    }

    public static PackagePattern parse(String raw) {
        String s = raw.trim();
        if (s.endsWith(".**")) {
            return new PackagePattern(Kind.PACKAGE_RECURSIVE, s.substring(0, s.length() - 3), null);
        }
        if (s.endsWith(".*")) {
            return new PackagePattern(Kind.PACKAGE_DIRECT, s.substring(0, s.length() - 2), null);
        }
        int hash = s.indexOf('#');
        if (hash >= 0) {
            return new PackagePattern(Kind.METHOD, s.substring(0, hash), s.substring(hash + 1));
        }
        return new PackagePattern(Kind.TYPE, s, null);
    }

    public static List<PackagePattern> parseAll(List<String> raws) {
        List<PackagePattern> out = new ArrayList<>();
        for (String s : raws) {
            if (!s.trim().isEmpty()) {
                out.add(parse(s));
            }
        }
        return out;
    }

    public boolean matches(String pkg, String typeFqn, String method) {
        if (glob != null) {
            return switch (kind) {
                case PACKAGE_DIRECT -> globMatches(pkg, false);
                case PACKAGE_RECURSIVE -> globMatches(pkg, true);
                case TYPE -> globMatches(typeFqn, false);
                case METHOD -> method.equals(methodName) && globMatches(typeFqn, false);
            };
        }
        return switch (kind) {
            // パッケージ名で判定するため、内部クラス（a.b.Outer.Inner）も正しく直下扱いになる
            case PACKAGE_DIRECT -> pkg.equals(value);
            case PACKAGE_RECURSIVE -> pkg.equals(value) || pkg.startsWith(value + ".");
            case TYPE -> typeFqn.equals(value);
            case METHOD -> typeFqn.equals(value) && method.equals(methodName);
        };
    }

    public static boolean matchesAny(List<PackagePattern> patterns,
                                     String pkg, String typeFqn, String method) {
        for (PackagePattern p : patterns) {
            if (p.matches(pkg, typeFqn, method)) {
                return true;
            }
        }
        return false;
    }

    // ---- 途中の * の照合 ----

    /** 1 階層ぶんの照合。any は階層全体が ** で、任意の 0 階層以上に当たる */
    private record Segment(boolean any, Pattern name) {
        boolean matches(String s) {
            return name.matcher(s).matches();
        }
    }

    private static Segment[] compile(String value) {
        String[] parts = value.split("\\.", -1);
        Segment[] out = new Segment[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.equals("**")) {
                out[i] = new Segment(true, null);
                continue;
            }
            // 階層の中の * は . を含まない任意の文字列。ほかの文字はそのまま比べる（$ などを正規表現として読まない）
            StringBuilder re = new StringBuilder();
            int from = 0;
            for (int star = p.indexOf('*'); star >= 0; star = p.indexOf('*', from)) {
                if (star > from) {
                    re.append(Pattern.quote(p.substring(from, star)));
                }
                re.append("[^.]*");
                from = star + 1;
            }
            if (from < p.length()) {
                re.append(Pattern.quote(p.substring(from)));
            }
            out[i] = new Segment(false, Pattern.compile(re.toString()));
        }
        return out;
    }

    /**
     * name（パッケージ名か型の FQN）が glob に当たるか。
     * recursive なら name の先頭の何階層かが当たればよい（末尾の .** と同じく、そのもの自身も含む）。
     */
    private boolean globMatches(String name, boolean recursive) {
        // 無名パッケージは空文字で来る。階層 0 個として比べる
        String[] parts = name.isEmpty() ? new String[0] : name.split("\\.", -1);
        return matchFrom(0, parts, 0, recursive);
    }

    private boolean matchFrom(int gi, String[] parts, int ni, boolean recursive) {
        if (gi == glob.length) {
            return recursive || ni == parts.length;
        }
        Segment g = glob[gi];
        if (g.any()) {
            for (int k = ni; k <= parts.length; k++) {
                if (matchFrom(gi + 1, parts, k, recursive)) {
                    return true;
                }
            }
            return false;
        }
        return ni < parts.length && g.matches(parts[ni]) && matchFrom(gi + 1, parts, ni + 1, recursive);
    }
}
