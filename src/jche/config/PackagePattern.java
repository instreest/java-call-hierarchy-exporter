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
 * 任意の 0 階層以上に当たる。
 *
 * <p>クラス指定・メソッド指定では、パターンの後ろの階層を型のクラス名の部分（入れ子なら {@code Outer.Inner}）に、
 * 残りをパッケージの部分に合わせて比べる。ワイルドカードがパッケージの階層を越えて外側のクラス名を飲み込むと
 * 除外が利用者の思ったより効きすぎるので、クラス名の部分では {@code **} は当たらず、{@code *} は最後の階層
 * （いちばん内側のクラス名）にだけ書ける（{@code jp.co.**.UserDao} は入れ子の {@code jp.co.a.Outer.UserDao} に当たらない）。
 *
 * <p>{@code *} / {@code **} だけの指定と、メソッド名（{@code #} の後ろ）の {@code *} は何にも当たらない。
 * どちらも書き間違いと見て {@link #problem()} で知らせる（前者は全部に当たるように読むと出力が黙って空になる）。
 */
public final class PackagePattern {

    private enum Kind { PACKAGE_DIRECT, PACKAGE_RECURSIVE, TYPE, METHOD, NEVER }

    /** 書き間違いと見て知らせるもの。{@link Config#patternWarnings()} が設定の項目名と一緒に警告の文言にする */
    public enum Problem {
        /** {@code *} / {@code **} だけ。何にも当たらない */
        WILDCARD_ONLY,
        /** メソッド名に {@code *}。メソッド名はそのまま比べるので当たらない */
        WILDCARD_IN_METHOD
    }

    /** 書いたままの指定（前後の空白を除く）。警告に出す */
    private final String text;
    private final Kind kind;
    /** パッケージ（PACKAGE_*）か型の FQN（TYPE / METHOD）を . で区切った階層ごとの照合 */
    private final Segment[] segments;
    private final String methodName;
    private final Problem problem;

    private PackagePattern(String text, Kind kind, String value, String methodName, Problem problem) {
        this.text = text;
        this.kind = kind;
        this.segments = kind == Kind.NEVER ? new Segment[0] : compile(value);
        this.methodName = methodName;
        this.problem = problem;
    }

    public static PackagePattern parse(String raw) {
        String s = raw.trim();
        if (s.equals("*") || s.equals("**")) {
            // 以前から何にも当たらない指定。全部に当たるように読み替えると、exclude.packages では出力が黙って空になる
            return new PackagePattern(s, Kind.NEVER, s, null, Problem.WILDCARD_ONLY);
        }
        if (s.endsWith(".**")) {
            String pkg = s.substring(0, s.length() - 3);
            // .** だけは以前から無名パッケージだけに当たる（先頭が "." のパッケージは無い）。全部に当たるように読み替えない
            return new PackagePattern(s, pkg.isEmpty() ? Kind.PACKAGE_DIRECT : Kind.PACKAGE_RECURSIVE, pkg, null, null);
        }
        if (s.endsWith(".*")) {
            return new PackagePattern(s, Kind.PACKAGE_DIRECT, s.substring(0, s.length() - 2), null, null);
        }
        int hash = s.indexOf('#');
        if (hash >= 0) {
            String method = s.substring(hash + 1);
            return new PackagePattern(s, Kind.METHOD, s.substring(0, hash), method,
                    method.indexOf('*') >= 0 ? Problem.WILDCARD_IN_METHOD : null);
        }
        return new PackagePattern(s, Kind.TYPE, s, null, null);
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

    /** 書いたままの指定（前後の空白を除く） */
    public String text() {
        return text;
    }

    /** 書き間違いと見たもの。無ければ null */
    public Problem problem() {
        return problem;
    }

    public boolean matches(String pkg, String typeFqn, String method) {
        return matches(new Name(pkg, typeFqn), method);
    }

    public static boolean matchesAny(List<PackagePattern> patterns,
                                     String pkg, String typeFqn, String method) {
        if (patterns.isEmpty()) {
            return false;
        }
        // 名前の分割はパターンごとではなく 1 回にする（出力のノードごと・辺ごとに呼ばれる）
        Name name = new Name(pkg, typeFqn);
        for (PackagePattern p : patterns) {
            if (p.matches(name, method)) {
                return true;
            }
        }
        return false;
    }

    private boolean matches(Name name, String method) {
        return switch (kind) {
            // パッケージ名で判定するため、内部クラス（a.b.Outer.Inner）も正しく直下扱いになる
            case PACKAGE_DIRECT -> matchesLevels(segments, segments.length, name.pkg(), name.pkgCount(), false);
            case PACKAGE_RECURSIVE -> matchesLevels(segments, segments.length, name.pkg(), name.pkgCount(), true);
            case TYPE -> matchesType(name);
            case METHOD -> method.equals(methodName) && matchesType(name);
            case NEVER -> false;
        };
    }

    /**
     * 型の FQN を、パッケージの部分とクラス名の部分に分けて比べる。パターンの後ろのクラス名の階層数ぶんを
     * クラス名に、残りをパッケージに合わせる（ワイルドカードがパッケージを越えて外側のクラス名を飲み込まない）。
     */
    private boolean matchesType(Name name) {
        String[] type = name.type();
        int pkgCount = name.pkgCount();
        int classCount = type.length - pkgCount;
        int split = segments.length - classCount;
        if (classCount <= 0 || split < 0) {
            return false;
        }
        for (int i = 0; i < classCount; i++) {
            Segment s = segments[split + i];
            boolean innermost = i == classCount - 1;
            // クラス名の部分の ** は当たらない。* はいちばん内側のクラス名にだけ書ける
            if (s.any() || (!innermost && !s.literal()) || !s.matches(type[pkgCount + i])) {
                return false;
            }
        }
        // パッケージの部分は FQN の先頭の階層と比べる（文字だけの指定なら FQN の完全一致と同じになる）
        return matchesLevels(segments, split, type, pkgCount, false);
    }

    /**
     * segs[0, to) が name の先頭 n 階層にちょうど当たるか。recursive なら、先頭 n 階層のうちさらに先頭の何階層かに
     * 当たればよい（末尾の .** と同じく、そのもの自身も含む）。
     */
    private static boolean matchesLevels(Segment[] segs, int to, String[] name, int n, boolean recursive) {
        if (!hasAny(segs, to)) {
            // ** が無ければ階層は 1 対 1 に対応する。表を作らずに先頭から比べる（既定の java.** など大半の指定）
            if (recursive ? n < to : n != to) {
                return false;
            }
            for (int g = 0; g < to; g++) {
                if (!segs[g].matches(name[g])) {
                    return false;
                }
            }
            return true;
        }
        boolean[] r = reach(segs, to, name, n);
        if (!recursive) {
            return r[n];
        }
        for (boolean b : r) {
            if (b) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAny(Segment[] segs, int to) {
        for (int g = 0; g < to; g++) {
            if (segs[g].any()) {
                return true;
            }
        }
        return false;
    }

    /**
     * segs[0, to) を name の先頭 n 階層に合わせたとき、名前のどこまで来られるか（reach[k] は先頭 k 階層にちょうど当たる）。
     * ** の後戻りはこの表を 1 段ずつ進めて数え、手数をパターンの階層数 × 名前の階層数に抑える
     * （** を並べても組み合わせで増えない）。
     */
    private static boolean[] reach(Segment[] segs, int to, String[] name, int n) {
        boolean[] reach = new boolean[n + 1];
        reach[0] = true;
        for (int g = 0; g < to; g++) {
            Segment s = segs[g];
            boolean[] next = new boolean[n + 1];
            if (s.any()) {
                boolean seen = false;
                for (int k = 0; k <= n; k++) {
                    seen |= reach[k];
                    next[k] = seen;
                }
            } else {
                for (int k = 0; k < n; k++) {
                    next[k + 1] = reach[k] && s.matches(name[k]);
                }
            }
            reach = next;
        }
        return reach;
    }

    // ---- 階層ごとの照合 ----

    /** 照合される名前。パッケージと型の FQN を . で分けたもの。型の FQN はクラス指定・メソッド指定で初めて分ける */
    private static final class Name {
        private final String[] pkg;
        private final String typeFqn;
        private String[] type;

        Name(String pkg, String typeFqn) {
            this.pkg = split(pkg);
            this.typeFqn = typeFqn;
        }

        String[] pkg() {
            return pkg;
        }

        int pkgCount() {
            return pkg.length;
        }

        String[] type() {
            if (type == null) {
                type = split(typeFqn);
            }
            return type;
        }

        private static String[] split(String s) {
            // 無名パッケージは空文字で来る。階層 0 個として比べる
            return s.isEmpty() ? new String[0] : s.split("\\.", -1);
        }
    }

    /**
     * 1 階層ぶんの照合。any は階層全体が ** で任意の 0 階層以上、text は * を含まない階層の文字、
     * glob は階層の一部に * を含むときの照合（* だけの階層は任意の 1 階層）。
     */
    private record Segment(boolean any, String text, Pattern glob) {
        boolean literal() {
            return text != null;
        }

        boolean matches(String s) {
            return text != null ? text.equals(s) : glob.matcher(s).matches();
        }
    }

    private static Segment[] compile(String value) {
        if (value.isEmpty()) {
            return new Segment[0];   // 末尾の .* / .** だけ（無名パッケージ）
        }
        String[] parts = value.split("\\.", -1);
        List<Segment> out = new ArrayList<>();
        for (String p : parts) {
            if (p.equals("**")) {
                // 続く ** は 1 つと同じ。まとめておく
                if (out.isEmpty() || !out.get(out.size() - 1).any()) {
                    out.add(new Segment(true, null, null));
                }
                continue;
            }
            if (p.indexOf('*') < 0) {
                out.add(new Segment(false, p, null));
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
            out.add(new Segment(false, null, Pattern.compile(re.toString())));
        }
        return out.toArray(new Segment[0]);
    }
}
