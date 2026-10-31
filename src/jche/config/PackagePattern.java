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
 */
public final class PackagePattern {

    private enum Kind { PACKAGE_DIRECT, PACKAGE_RECURSIVE, TYPE, METHOD, NEVER }

    /** 書き間違いと見て知らせるもの。{@link Config#patternWarnings()} が設定の項目名と一緒に警告の文言にする */
    public enum Problem {
        /** 文字の階層が 1 つも無い（{@code *}・{@code **}・{@code *.**} など）。何にも当たらない */
        WILDCARD_ONLY,
        /** クラス指定・メソッド指定の最後の階層が {@code **}。{@code **} はクラス名に当たらない */
        WILDCARD_CLASS,
        /** メソッド名に {@code *}。メソッド名はそのまま比べるので当たらない */
        WILDCARD_IN_METHOD
    }

    /** 名前の末尾を過ぎた位置。階層の終わりの {@code .} の次と同じ扱いにするため、長さ + 1 で表す */
    private static int pastEnd(String s) {
        return s.length() + 1;
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

    private PackagePattern(String text, Kind kind, String value, String methodName, Problem problem) {
        this.text = text;
        this.kind = kind;
        this.segments = kind == Kind.NEVER ? new Segment[0] : compile(value);
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
        if (!value.isEmpty() && wildcardOnly(value)) {
            // 以前は何にも当たらなかった（"*" や "**" という名前として比べていた）。全部に当たるように読み替えると、
            // exclude.packages では出力が黙って空になる
            return new PackagePattern(s, Kind.NEVER, value, null, Problem.WILDCARD_ONLY);
        }
        if ((kind == Kind.TYPE || kind == Kind.METHOD) && (value.equals("**") || value.endsWith(".**"))) {
            return new PackagePattern(s, Kind.NEVER, value, null, Problem.WILDCARD_CLASS);
        }
        if (method != null && method.indexOf('*') >= 0) {
            return new PackagePattern(s, Kind.NEVER, value, null, Problem.WILDCARD_IN_METHOD);
        }
        return new PackagePattern(s, kind, value, method, null);
    }

    /** どの階層も * か ** だけか */
    private static boolean wildcardOnly(String value) {
        for (String part : value.split("\\.", -1)) {
            if (!part.equals("*") && !part.equals("**")) {
                return false;
            }
        }
        return true;
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
        for (PackagePattern p : patterns) {
            if (p.matches(pkg, typeFqn, method)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 名前は分割せず、文字列の中の位置で階層を比べる（出力のノードごと・辺ごとに呼ばれるので、
     * ** を含まない大半の指定では配列も部分文字列も作らない）。
     */
    public boolean matches(String pkg, String typeFqn, String method) {
        return switch (kind) {
            // パッケージ名で判定するため、内部クラス（a.b.Outer.Inner）も正しく直下扱いになる
            case PACKAGE_DIRECT -> matchesPackage(pkg, false);
            case PACKAGE_RECURSIVE -> matchesPackage(pkg, true);
            case TYPE -> matchesType(pkg, typeFqn);
            case METHOD -> method.equals(methodName) && matchesType(pkg, typeFqn);
            case NEVER -> false;
        };
    }

    /** パッケージがパターンに当たるか。recursive なら先頭の何階層かが当たればよい（末尾の .** と同じく、そのもの自身も含む） */
    private boolean matchesPackage(String pkg, boolean recursive) {
        if (firstAny < 0) {
            int end = matchSequential(segments, 0, segments.length, pkg, start(pkg), -1);
            return recursive ? end >= 0 : end == pastEnd(pkg);
        }
        int[] starts = levelStarts(pkg);
        return matchesWithAny(segments, segments.length, pkg, starts, starts.length - 1, recursive);
    }

    /**
     * 型の FQN を、パッケージの部分とクラス名の部分に分けて比べる。パターンの後ろのクラス名の階層数ぶんを
     * クラス名に、残りをパッケージに合わせる（ワイルドカードがパッケージを越えて外側のクラス名を飲み込まない）。
     * クラス名の部分では ** は当たらず、* はいちばん内側のクラス名にだけ書ける。
     */
    private boolean matchesType(String pkg, String typeFqn) {
        int pkgCount = levelCount(pkg);
        int classCount = levelCount(typeFqn) - pkgCount;
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
        int[] starts = levelStarts(typeFqn);
        // クラス名の部分は 1 対 1、パッケージの部分（FQN の先頭 pkgCount 階層）は ** を含む照合
        return matchSequential(segments, split, segments.length, typeFqn, starts[pkgCount], split) == pastEnd(typeFqn)
                && matchesWithAny(segments, split, typeFqn, starts, pkgCount, false);
    }

    // ---- 階層ごとの照合 ----

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

    /** 各階層の始まりの位置。最後の要素は末尾を過ぎた位置（階層の数 + 1 個）。** を含む指定だけが使う */
    private static int[] levelStarts(String s) {
        int n = levelCount(s);
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
     * パターンの階層数 × 名前の階層数に抑える（** を並べても組み合わせで増えない）。
     */
    private static boolean matchesWithAny(Segment[] segs, int to, String s, int[] starts, int n, boolean recursive) {
        boolean[] reach = new boolean[n + 1];
        reach[0] = true;
        for (int g = 0; g < to; g++) {
            Segment seg = segs[g];
            boolean[] next = new boolean[n + 1];
            if (seg.any()) {
                boolean seen = false;
                for (int k = 0; k <= n; k++) {
                    seen |= reach[k];
                    next[k] = seen;
                }
            } else {
                for (int k = 0; k < n; k++) {
                    next[k + 1] = reach[k] && seg.matches(s, starts[k], starts[k + 1] - 1);
                }
            }
            reach = next;
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

    /**
     * 1 階層ぶんの照合。any は階層全体が ** で任意の 0 階層以上、text は * を含まない階層の文字、
     * glob は階層の一部に * を含むときの照合（* だけの階層は任意の 1 階層）。
     */
    private record Segment(boolean any, String text, Pattern glob) {
        boolean literal() {
            return text != null;
        }

        /** s の [from, to) がこの階層に当たるか */
        boolean matches(String s, int from, int to) {
            if (text != null) {
                return to - from == text.length() && s.startsWith(text, from);
            }
            return glob.matcher(s).region(from, to).matches();
        }
    }

    private static Segment[] compile(String value) {
        if (value.isEmpty()) {
            return new Segment[0];   // 末尾の .* / .** だけ（無名パッケージ）
        }
        List<Segment> out = new ArrayList<>();
        for (String p : value.split("\\.", -1)) {
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
            // 階層の中の * は . と $ を含まない任意の文字列（$ をまたぐと匿名クラス Outer$1 に当たる）。
            // ほかの文字はそのまま比べる（$ などを正規表現として読まない）
            StringBuilder re = new StringBuilder();
            int from = 0;
            for (int star = p.indexOf('*'); star >= 0; star = p.indexOf('*', from)) {
                if (star > from) {
                    re.append(Pattern.quote(p.substring(from, star)));
                }
                re.append("[^.$]*");
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
