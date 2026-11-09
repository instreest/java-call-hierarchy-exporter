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
 * <p>{@code *} は {@code $} もまたがない。JDT は匿名クラス・ローカルクラスを {@code Outer$1}・{@code Outer$1Local} の名前で
 * 持つので、{@code Foo*} が {@code Foo$1} に当たると、名前を挙げていない型の呼び出しまで除外してしまう。
 *
 * <p>次の 3 つは何にも当たらず、書き間違いと見て {@link #problem()} で知らせる。
 * <ul>
 *   <li>文字の階層が 1 つも無い指定（{@code *}・{@code **}・{@code *.**}・{@code **.*} など）。全部に当たるように読むと、
 *       {@code exclude.packages} に紛れ込むだけで出力が黙って空になる</li>
 *   <li>クラス指定・メソッド指定の最後の階層が {@code **}（{@code jp.co.app.**#execute}）。{@code **} はクラス名に当たらない</li>
 *   <li>メソッド名（{@code #} の後ろ）の {@code *}。メソッド名はそのまま比べる</li>
 * </ul>
 * 文字の階層が無いかどうか・最後の階層が {@code **} かどうかは、照合に使う階層（{@link Segment}）から決める
 * （判定と照合で階層の読み方を 2 通り持つと、{@code ***} のような書き方で食い違う）。
 *
 * <p>途中の階層の {@code *} に大文字が混じる指定（{@code jp.co.**.*Outer.Inner}）は、外側のクラス名のつもりで書いた
 * ことが多いが、クラス名の部分では {@code *} はいちばん内側にしか書けないので入れ子のクラスには当たらない。
 * パッケージ名としては照合する（大文字のパッケージもありうる）が、{@link #problem()} で知らせる。
 */
public final class PackagePattern {

    private enum Kind { PACKAGE_DIRECT, PACKAGE_RECURSIVE, TYPE, METHOD, NEVER }

    /** 書き間違いと見て知らせるもの。{@link Config#patternWarnings()} が設定の項目名と一緒に警告の文言にする */
    public enum Problem {
        /** 文字の階層が 1 つも無い（{@code *}・{@code **}・{@code *.**}・{@code ***} など）。何にも当たらない */
        WILDCARD_ONLY,
        /** クラス指定・メソッド指定の最後の階層が {@code **}。{@code **} はクラス名に当たらない */
        WILDCARD_CLASS,
        /** メソッド名に {@code *}。メソッド名はそのまま比べるので当たらない */
        WILDCARD_IN_METHOD,
        /** 途中の階層の {@code *} に大文字が混じる（外側のクラス名のつもり）。入れ子のクラスには当たらない（照合はする） */
        WILDCARD_OUTER_CLASS
    }

    /** 書いたままの指定（前後の空白を除く）。警告に出す */
    private final String text;
    private final Kind kind;
    /** パッケージ（PACKAGE_*）か型の FQN（TYPE / METHOD）を . で区切った階層ごとの照合 */
    private final Segment[] segments;
    /** 最初の ** の位置。無ければ -1（階層が 1 対 1 に対応するので、表を作らずに先頭から比べる） */
    private final int firstAny;
    private final String methodName;
    private final Problem problem;

    private PackagePattern(String text, Kind kind, Segment[] segments, String methodName, Problem problem) {
        this.text = text;
        this.kind = kind;
        this.segments = segments;
        int any = -1;
        for (int g = 0; g < segments.length && any < 0; g++) {
            if (segments[g].any()) {
                any = g;
            }
        }
        this.firstAny = any;
        this.methodName = methodName;
        this.problem = problem;
    }

    public static PackagePattern parse(String raw) {
        String s = raw.trim();
        Kind kind;
        String value;
        String method = null;
        if (s.endsWith(".**")) {
            value = s.substring(0, s.length() - 3);
            // .** だけは以前から無名パッケージだけに当たる（先頭が "." のパッケージは無い）。全部に当たるように読み替えない
            kind = value.isEmpty() ? Kind.PACKAGE_DIRECT : Kind.PACKAGE_RECURSIVE;
        } else if (s.endsWith(".*")) {
            value = s.substring(0, s.length() - 2);
            kind = Kind.PACKAGE_DIRECT;
        } else {
            int hash = s.indexOf('#');
            value = hash >= 0 ? s.substring(0, hash) : s;
            method = hash >= 0 ? s.substring(hash + 1) : null;
            kind = hash >= 0 ? Kind.METHOD : Kind.TYPE;
        }
        Segment[] segs = compile(value);
        Problem problem = problemOf(kind, segs, method);
        if (problem != null && problem != Problem.WILDCARD_OUTER_CLASS) {
            return new PackagePattern(s, Kind.NEVER, segs, null, problem);
        }
        return new PackagePattern(s, kind, segs, method, problem);
    }

    /** 照合に使う階層から、書き間違いと見るものを決める。無ければ null */
    private static Problem problemOf(Kind kind, Segment[] segs, String method) {
        boolean wildcardOnly = segs.length > 0;
        for (Segment seg : segs) {
            wildcardOnly &= seg.wildcard();
        }
        if (wildcardOnly) {
            // 以前は何にも当たらなかった（"*" や "**" という名前として比べていた）。全部に当たるように読み替えると、
            // exclude.packages では出力が黙って空になる
            return Problem.WILDCARD_ONLY;
        }
        boolean typeOrMethod = kind == Kind.TYPE || kind == Kind.METHOD;
        if (typeOrMethod && segs.length > 0 && segs[segs.length - 1].any()) {
            return Problem.WILDCARD_CLASS;
        }
        if (method != null && method.indexOf('*') >= 0) {
            return Problem.WILDCARD_IN_METHOD;
        }
        if (typeOrMethod) {
            for (int g = 0; g < segs.length - 1; g++) {
                if (segs[g].globWithUpperCase()) {
                    return Problem.WILDCARD_OUTER_CLASS;
                }
            }
        }
        return null;
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

    public static boolean matchesAny(List<PackagePattern> patterns,
                                     String pkg, String typeFqn, String method) {
        if (patterns.isEmpty()) {
            return false;
        }
        // 階層の数え上げ・始まりの位置はパターンごとではなく 1 回にする（出力のノードごと・辺ごとに呼ばれる）
        Name name = new Name(pkg, typeFqn);
        for (PackagePattern p : patterns) {
            if (p.matches(name, method)) {
                return true;
            }
        }
        return false;
    }

    public boolean matches(String pkg, String typeFqn, String method) {
        return matches(new Name(pkg, typeFqn), method);
    }

    /**
     * 名前は分割せず、文字列の中の位置で階層を比べる。** を含まない大半の指定（既定の java.** など）では
     * 配列も部分文字列も作らない。
     */
    private boolean matches(Name name, String method) {
        return switch (kind) {
            // パッケージ名で判定するため、内部クラス（a.b.Outer.Inner）も正しく直下扱いになる
            case PACKAGE_DIRECT -> matchesPackage(name, false);
            case PACKAGE_RECURSIVE -> matchesPackage(name, true);
            case TYPE -> matchesType(name);
            case METHOD -> method.equals(methodName) && matchesType(name);
            case NEVER -> false;
        };
    }

    /** パッケージがパターンに当たるか。recursive なら先頭の何階層かが当たればよい（末尾の .** と同じく、そのもの自身も含む） */
    private boolean matchesPackage(Name name, boolean recursive) {
        String pkg = name.pkg;
        if (firstAny < 0) {
            int end = matchSequential(segments, 0, segments.length, pkg, start(pkg), -1);
            return recursive ? end >= 0 : end == pastEnd(pkg);
        }
        return matchesWithAny(segments, segments.length, pkg, name.pkgStarts(), name.pkgCount(), recursive);
    }

    /**
     * 型の FQN を、パッケージの部分とクラス名の部分に分けて比べる。パターンの後ろのクラス名の階層数ぶんを
     * クラス名に、残りをパッケージに合わせる（ワイルドカードがパッケージを越えて外側のクラス名を飲み込まない）。
     * クラス名の部分では ** は当たらず、* はいちばん内側のクラス名にだけ書ける。
     */
    private boolean matchesType(Name name) {
        String typeFqn = name.typeFqn;
        int pkgCount = name.pkgCount();
        int classCount = name.typeCount() - pkgCount;
        int split = segments.length - classCount;
        if (classCount <= 0 || split < 0) {
            return false;
        }
        if (firstAny < 0) {
            // 階層は 1 対 1。パッケージの部分の階層数が合わなければ当たらない
            if (split != pkgCount) {
                return false;
            }
            return matchSequential(segments, 0, segments.length, typeFqn, start(typeFqn), split) == pastEnd(typeFqn);
        }
        if (firstAny >= split) {
            return false;   // ** がクラス名の部分にある
        }
        int[] starts = name.typeStarts();
        // クラス名の部分は 1 対 1、パッケージの部分（FQN の先頭 pkgCount 階層）は ** を含む照合
        return matchSequential(segments, split, segments.length, typeFqn, starts[pkgCount], split) == pastEnd(typeFqn)
                && matchesWithAny(segments, split, typeFqn, starts, pkgCount, false);
    }

    // ---- 階層ごとの照合 ----

    /** 名前の末尾を過ぎた位置。階層の終わりの {@code .} の次と同じ扱いにするため、長さ + 1 で表す */
    private static int pastEnd(String s) {
        return s.length() + 1;
    }

    /** 名前の最初の階層の位置。無名パッケージ（空文字）は階層 0 個なので、最初から末尾を過ぎている */
    private static int start(String s) {
        return s.isEmpty() ? pastEnd(s) : 0;
    }

    private static int levelCount(String s) {
        if (s.isEmpty()) {
            return 0;
        }
        int n = 1;
        for (int i = s.indexOf('.'); i >= 0; i = s.indexOf('.', i + 1)) {
            n++;
        }
        return n;
    }

    /** 各階層の始まりの位置。最後の要素は末尾を過ぎた位置（階層の数 + 1 個） */
    private static int[] levelStarts(String s, int n) {
        int[] starts = new int[n + 1];
        int pos = 0;
        for (int k = 0; k < n; k++) {
            starts[k] = pos;
            int dot = s.indexOf('.', pos);
            pos = dot < 0 ? pastEnd(s) : dot + 1;
        }
        starts[n] = pastEnd(s);
        return starts;
    }

    /**
     * 照合される名前。階層の数と始まりの位置は、要るパターンが初めて求めたときに 1 回だけ数える
     * （matchesAny が名前 1 つにつき 1 つ作り、すべてのパターンで使い回す）。
     */
    private static final class Name {
        final String pkg;
        final String typeFqn;
        private int pkgCount = -1;
        private int typeCount = -1;
        private int[] pkgStarts;
        private int[] typeStarts;

        Name(String pkg, String typeFqn) {
            this.pkg = pkg;
            this.typeFqn = typeFqn;
        }

        int pkgCount() {
            if (pkgCount < 0) {
                pkgCount = levelCount(pkg);
            }
            return pkgCount;
        }

        int typeCount() {
            if (typeCount < 0) {
                typeCount = levelCount(typeFqn);
            }
            return typeCount;
        }

        int[] pkgStarts() {
            if (pkgStarts == null) {
                pkgStarts = levelStarts(pkg, pkgCount());
            }
            return pkgStarts;
        }

        int[] typeStarts() {
            if (typeStarts == null) {
                typeStarts = levelStarts(typeFqn, typeCount());
            }
            return typeStarts;
        }
    }

    /**
     * s の pos から始まる階層を segs[from, to) と 1 対 1 で比べる。当たれば次の階層の位置（末尾なら {@link #pastEnd}）、
     * 外れれば -1。classFrom 以降の階層はクラス名の部分で、いちばん内側のほかは文字だけの階層しか当たらない（-1 なら決まり無し）。
     */
    private static int matchSequential(Segment[] segs, int from, int to, String s, int pos, int classFrom) {
        for (int g = from; g < to; g++) {
            if (pos >= pastEnd(s)) {
                return -1;
            }
            int dot = s.indexOf('.', pos);
            int end = dot < 0 ? s.length() : dot;
            Segment seg = segs[g];
            if (classFrom >= 0 && g >= classFrom && g < to - 1 && !seg.literal()) {
                return -1;
            }
            if (!seg.matches(s, pos, end)) {
                return -1;
            }
            pos = end + 1;
        }
        return pos;
    }

    /**
     * segs[0, to) を s の先頭 n 階層（starts は各階層の始まり）に合わせる。recursive でなければ n 階層ちょうど、
     * recursive なら先頭の何階層かに当たればよい。
     * ** の後戻りは「パターンのここまでで名前のどこまで来られるか」の表を 1 段ずつ進めて数え、手数を
     * パターンの階層数 × 名前の階層数に抑える（** を並べても組み合わせで増えない）。表は 2 本を入れ替えて使う。
     */
    private static boolean matchesWithAny(Segment[] segs, int to, String s, int[] starts, int n, boolean recursive) {
        boolean[] reach = new boolean[n + 1];
        boolean[] next = new boolean[n + 1];
        reach[0] = true;
        for (int g = 0; g < to; g++) {
            Segment seg = segs[g];
            if (seg.any()) {
                boolean seen = false;
                for (int k = 0; k <= n; k++) {
                    seen |= reach[k];
                    next[k] = seen;
                }
            } else {
                next[0] = false;
                for (int k = 0; k < n; k++) {
                    next[k + 1] = reach[k] && seg.matches(s, starts[k], starts[k + 1] - 1);
                }
            }
            boolean[] t = reach;
            reach = next;
            next = t;
        }
        if (!recursive) {
            return reach[n];
        }
        for (boolean b : reach) {
            if (b) {
                return true;
            }
        }
        return false;
    }

    /** 1 階層の照合の形 */
    private enum Form {
        /** 階層全体が **。任意の 0 階層以上 */
        ANY_LEVELS,
        /** * を含まない。そのまま比べる */
        LITERAL,
        /** * だけ（*・*** など）。任意の 1 階層 */
        STAR,
        /** 文字 + *（Foo*） */
        PREFIX,
        /** * + 文字（*Impl） */
        SUFFIX,
        /** それ以外（a*b*c など）。正規表現で比べる */
        REGEX
    }

    /**
     * 1 階層ぶんの照合。* は . と $ を含まない任意の文字列（$ をまたぐと匿名クラス Outer$1 に当たる）。
     * よく書く形（* だけ・前方一致・後方一致）は正規表現を使わずに比べ、照合のたびに Matcher を作らない。
     * text は LITERAL ならその文字、PREFIX / SUFFIX なら * を除いた文字、REGEX なら書いたままの階層。
     */
    private record Segment(Form form, String text, Pattern regex) {
        boolean any() {
            return form == Form.ANY_LEVELS;
        }

        boolean literal() {
            return form == Form.LITERAL;
        }

        /** * か ** だけでできている（文字が無い） */
        boolean wildcard() {
            return form == Form.ANY_LEVELS || form == Form.STAR;
        }

        /** 階層の一部に * を含み、文字に大文字が混じる（*Outer など。外側のクラス名のつもりで書いたもの） */
        boolean globWithUpperCase() {
            if (form != Form.PREFIX && form != Form.SUFFIX && form != Form.REGEX) {
                return false;
            }
            for (int i = 0; i < text.length(); i++) {
                if (Character.isUpperCase(text.charAt(i))) {
                    return true;
                }
            }
            return false;
        }

        /** s の [from, to) がこの階層に当たるか */
        boolean matches(String s, int from, int to) {
            return switch (form) {
                case ANY_LEVELS -> true;
                case LITERAL -> to - from == text.length() && s.startsWith(text, from);
                case STAR -> noDollar(s, from, to);
                case PREFIX -> to - from >= text.length() && s.startsWith(text, from)
                        && noDollar(s, from + text.length(), to);
                case SUFFIX -> to - from >= text.length() && s.startsWith(text, to - text.length())
                        && noDollar(s, from, to - text.length());
                case REGEX -> regex.matcher(s).region(from, to).matches();
            };
        }

        private static boolean noDollar(String s, int from, int to) {
            int d = s.indexOf('$', from);
            return d < 0 || d >= to;
        }
    }

    private static Segment[] compile(String value) {
        if (value.isEmpty()) {
            return new Segment[0];   // 末尾の .* / .** だけ（無名パッケージ）
        }
        List<Segment> out = new ArrayList<>();
        for (String p : value.split("\\.", -1)) {
            out.add(segmentOf(p));
        }
        // 続く ** は 1 つと同じ。まとめておく
        List<Segment> merged = new ArrayList<>();
        for (Segment seg : out) {
            if (!(seg.any() && !merged.isEmpty() && merged.get(merged.size() - 1).any())) {
                merged.add(seg);
            }
        }
        return merged.toArray(new Segment[0]);
    }

    private static Segment segmentOf(String p) {
        if (p.equals("**")) {
            return new Segment(Form.ANY_LEVELS, null, null);
        }
        int star = p.indexOf('*');
        if (star < 0) {
            return new Segment(Form.LITERAL, p, null);
        }
        if (p.chars().allMatch(c -> c == '*')) {
            return new Segment(Form.STAR, null, null);   // *** なども * と同じ任意の 1 階層
        }
        int stars = (int) p.chars().filter(c -> c == '*').count();
        if (stars == 1 && star == p.length() - 1) {
            return new Segment(Form.PREFIX, p.substring(0, star), null);
        }
        if (stars == 1 && star == 0) {
            return new Segment(Form.SUFFIX, p.substring(1), null);
        }
        // * の続きは 1 つと同じ。ほかの文字はそのまま比べる（$ などを正規表現として読まない）
        StringBuilder re = new StringBuilder();
        int from = 0;
        for (int s = p.indexOf('*'); s >= 0; s = p.indexOf('*', from)) {
            if (s > from) {
                re.append(Pattern.quote(p.substring(from, s)));
            }
            if (s == 0 || p.charAt(s - 1) != '*') {
                re.append("[^.$]*");
            }
            from = s + 1;
        }
        if (from < p.length()) {
            re.append(Pattern.quote(p.substring(from)));
        }
        return new Segment(Form.REGEX, p, Pattern.compile(re.toString()));
    }
}
