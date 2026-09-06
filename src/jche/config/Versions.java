/*
 * Copyright 2026 the java-call-hierarchy-exporter authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jche.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 依存の版の比較と、範囲・動的バージョン（{@code [1.0,2.0)}、{@code 1.+}、{@code latest.release}）の解決。
 *
 * Maven の ComparableVersion を簡略化したもの。数値は数値として、修飾子は
 * alpha &lt; beta &lt; milestone &lt; rc &lt; snapshot &lt; （無し）&lt; sp の順で比べる。
 * ローカルリポジトリにある版から選ぶだけなので、厳密さより「多くの場合に自然な順」を優先した。
 */
final class Versions {

    private Versions() {
    }

    /** 範囲や動的な指定か（ローカルにある版から選ぶ必要がある） */
    static boolean isDynamic(String version) {
        String v = version.trim();
        return v.contains("+") || v.startsWith("[") || v.startsWith("(")
                || v.equalsIgnoreCase("LATEST") || v.equalsIgnoreCase("RELEASE")
                || v.toLowerCase(Locale.ROOT).startsWith("latest.");
    }

    /**
     * 指定に合う最も新しい版を候補から選ぶ。
     *
     * @param spec       版の指定（固定の版でも可。その場合は候補に含まれていればそれ）
     * @param candidates ローカルにある版
     * @return 選んだ版。合うものが無ければ null
     */
    static String select(String spec, List<String> candidates) {
        String s = spec.trim();
        if (!isDynamic(s)) {
            return candidates.contains(s) ? s : null;
        }
        List<String> matching = new ArrayList<>();
        for (String c : candidates) {
            if (matches(s, c)) {
                matching.add(c);
            }
        }
        if (matching.isEmpty()) {
            return null;
        }
        String best = matching.get(0);
        for (String c : matching) {
            if (compare(c, best) > 0) {
                best = c;
            }
        }
        return best;
    }

    private static boolean matches(String spec, String candidate) {
        String lower = spec.toLowerCase(Locale.ROOT);
        if (lower.equals("+") || lower.equals("latest") || lower.equals("latest.integration")) {
            return true;
        }
        if (lower.equals("release") || lower.equals("latest.release")) {
            return !candidate.toUpperCase(Locale.ROOT).endsWith("-SNAPSHOT");
        }
        if (spec.endsWith("+")) {
            // Gradle の「1.2.+」「1.+」。前方一致（"1." で "10.x" を拾わないよう、区切りまで含める）
            String prefix = spec.substring(0, spec.length() - 1);
            return candidate.startsWith(prefix);
        }
        if (spec.startsWith("[") || spec.startsWith("(")) {
            return inRange(spec, candidate);
        }
        return spec.equals(candidate);
    }

    /** 単一の範囲 {@code [a,b]} {@code (a,b)} {@code [a,)} {@code (,b]} {@code [a]}。複数の範囲の組は最初の 1 つだけ見る */
    private static boolean inRange(String spec, String candidate) {
        String range = spec;
        int nextRange = range.indexOf(",", range.indexOf(",") + 1);   // "[1,2),[3,4)" の 2 つ目以降は無視
        if (nextRange > 0 && (range.charAt(nextRange - 1) == ')' || range.charAt(nextRange - 1) == ']')) {
            range = range.substring(0, nextRange);
        }
        boolean lowerInclusive = range.startsWith("[");
        boolean upperInclusive = range.endsWith("]");
        String inner = range.substring(1, range.length() - 1);
        int comma = inner.indexOf(',');
        String lower;
        String upper;
        if (comma < 0) {
            lower = inner.trim();
            upper = inner.trim();
        } else {
            lower = inner.substring(0, comma).trim();
            upper = inner.substring(comma + 1).trim();
        }
        if (!lower.isEmpty()) {
            int c = compare(candidate, lower);
            if (c < 0 || (c == 0 && !lowerInclusive)) {
                return false;
            }
        }
        if (!upper.isEmpty()) {
            int c = compare(candidate, upper);
            if (c > 0 || (c == 0 && !upperInclusive)) {
                return false;
            }
        }
        return true;
    }

    /** 版の大小。同じなら 0、a が新しければ正 */
    static int compare(String a, String b) {
        List<String> ta = tokens(a);
        List<String> tb = tokens(b);
        int n = Math.max(ta.size(), tb.size());
        for (int i = 0; i < n; i++) {
            String x = i < ta.size() ? ta.get(i) : "";
            String y = i < tb.size() ? tb.get(i) : "";
            int c = compareToken(x, y);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /** "1.2.3-beta-1" → [1, 2, 3, beta, 1]。数字と英字の境目でも区切る（"1.0rc1" → [1, 0, rc, 1]） */
    private static List<String> tokens(String version) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean digit = false;
        for (char ch : version.trim().toLowerCase(Locale.ROOT).toCharArray()) {
            if (ch == '.' || ch == '-' || ch == '_') {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                continue;
            }
            boolean d = Character.isDigit(ch);
            if (cur.length() > 0 && d != digit) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            digit = d;
            cur.append(ch);
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        // 末尾の "0" や空は無いのと同じ（1.0 == 1.0.0）
        while (!out.isEmpty() && out.get(out.size() - 1).equals("0")) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    private static int compareToken(String x, String y) {
        boolean xd = !x.isEmpty() && Character.isDigit(x.charAt(0));
        boolean yd = !y.isEmpty() && Character.isDigit(y.charAt(0));
        if (xd && yd) {
            return compareNumeric(x, y);
        }
        if (xd) {
            return 1;      // 数字は修飾子（と空）より新しい: 1.0.1 > 1.0-beta
        }
        if (yd) {
            return -1;
        }
        return Integer.compare(rank(x), rank(y)) != 0 ? Integer.compare(rank(x), rank(y)) : x.compareTo(y);
    }

    private static int compareNumeric(String x, String y) {
        String a = x.replaceFirst("^0+(?=\\d)", "");
        String b = y.replaceFirst("^0+(?=\\d)", "");
        if (a.length() != b.length()) {
            return Integer.compare(a.length(), b.length());
        }
        return a.compareTo(b);
    }

    /** 修飾子の順。空（正式版）は snapshot より新しく sp より古い */
    private static int rank(String qualifier) {
        switch (qualifier) {
            case "alpha":
            case "a":
                return 1;
            case "beta":
            case "b":
                return 2;
            case "milestone":
            case "m":
                return 3;
            case "rc":
            case "cr":
                return 4;
            case "snapshot":
                return 5;
            case "":
            case "ga":
            case "final":
            case "release":
                return 6;
            case "sp":
                return 7;
            default:
                return 8;   // 未知の修飾子は名前順（正式版より後ろに並ぶ）
        }
    }
}
