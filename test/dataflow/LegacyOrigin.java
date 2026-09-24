// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0

import java.util.ArrayList;
import java.util.List;

/**
 * 検査用: 出所の文字列（{@code jche.cache.Origin}）と条件の文字列（{@code jche.cache.Guard}）の読み方の写し。
 *
 * <p>値の読み手を文字列から値の表（{@code jche.graph.ValueStore}）へ移す間（stage B）、ValueStoreCheck が
 * 「表を組み直した文字列を、今の読み手の読み方で読むと、表と同じ構造が返るか」を確かめるのに使う。
 * 本体の読み方は移し終えると消えるので、ここに<b>そのまま</b>写しておく（直さない。直すと検査の意味が無くなる）。
 * {@link #mentions} / {@link #guardsOnParam} は {@code jche.dataflow.DataflowBuilder} の文字列の探し方の写し。
 */
final class LegacyOrigin {

    static final char UNKNOWN = 'U';
    static final char ARGS = '|';
    static final String RECEIVER = "r";
    static final String ARG_COUNT = "n";
    static final String STATIC_RECV = "s";

    static final char ATOM_SEP = '\u0001';
    static final char FIELD_SEP = '\u0002';
    static final char VALUE_SEP = '\u0003';

    private LegacyOrigin() {
    }

    static boolean isUnknown(String origin) {
        return origin == null || origin.isEmpty() || origin.charAt(0) == UNKNOWN;
    }

    static char kindOf(String origin) {
        return isUnknown(origin) ? UNKNOWN : origin.charAt(0);
    }

    /** 入れ子の境界（{...}）の外側で最初に現れる文字の位置。無ければ -1 */
    private static int indexAtTop(String s, char ch, int from) {
        int depth = 0;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                if (depth > 0) {
                    depth--;
                }
            } else if (c == ch && depth == 0) {
                return i;
            }
        }
        return -1;
    }

    static String valueOf(String origin) {
        int i = (origin == null) ? -1 : origin.indexOf(':');
        if (i < 0) {
            return "";
        }
        int bar = indexAtTop(origin, ARGS, i);
        return (bar < 0) ? origin.substring(i + 1) : origin.substring(i + 1, bar);
    }

    static String argsOf(String origin) {
        int bar = (origin == null) ? -1 : indexAtTop(origin, ARGS, 0);
        return (bar < 0) ? null : origin.substring(bar + 1);
    }

    static String head(String origin) {
        int bar = (origin == null) ? -1 : indexAtTop(origin, ARGS, 0);
        return (bar < 0) ? origin : origin.substring(0, bar);
    }

    static String of(char kind, String value) {
        return kind + ":" + value;
    }

    static String of(char kind, String value, String args) {
        return (args == null || args.isEmpty())
                ? of(kind, value) : (kind + ":" + value + ARGS + args);
    }

    static String nest(String origin) {
        return (origin.indexOf(ARGS) < 0) ? origin : "{" + origin + "}";
    }

    static String argAt(String args, int index) {
        return entryAt(args, String.valueOf(index));
    }

    static String staticReceiverOf(String origin) {
        return entryAt(argsOf(origin), STATIC_RECV);
    }

    static String receiverOf(String origin) {
        return entryAt(argsOf(origin), RECEIVER);
    }

    static int argCountOf(String origin) {
        String n = entryAt(argsOf(origin), ARG_COUNT);
        if (n == null) {
            return -1;
        }
        try {
            return Integer.parseInt(n);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static List<String> entriesOf(String args) {
        if (args == null || args.isEmpty()) {
            return List.of();
        }
        List<String> entries = new ArrayList<>(4);
        int start = 0;
        while (start <= args.length()) {
            int end = indexAtTop(args, ';', start);
            if (end < 0) {
                end = args.length();
            }
            if (end > start) {
                entries.add(args.substring(start, end));
            }
            if (end >= args.length()) {
                break;
            }
            start = end + 1;
        }
        return entries;
    }

    static String unnest(String value) {
        return (value != null && value.length() >= 2
                && value.charAt(0) == '{' && value.charAt(value.length() - 1) == '}')
                ? value.substring(1, value.length() - 1) : value;
    }

    static String entryAt(String args, String key) {
        if (args == null || args.isEmpty()) {
            return null;
        }
        String prefix = key + "=";
        int start = 0;
        while (start <= args.length()) {
            int end = indexAtTop(args, ';', start);
            if (end < 0) {
                end = args.length();
            }
            String entry = args.substring(start, end);
            if (entry.startsWith(prefix)) {
                String v = entry.substring(prefix.length());
                if (v.length() >= 2 && v.charAt(0) == '{' && v.charAt(v.length() - 1) == '}') {
                    v = v.substring(1, v.length() - 1);
                }
                return v;
            }
            if (end >= args.length()) {
                break;
            }
            start = end + 1;
        }
        return null;
    }

    static String constantValueOf(String origin) {
        char kind = kindOf(origin);
        return (kind == 'V' || kind == 'L' || kind == 'K') ? valueOf(origin) : null;
    }

    // --- jche.cache.Guard の読み手に渡す文字列の組み方 ---

    static String atom(String op, String origin, String value, String text) {
        return op + FIELD_SEP + clean(origin) + FIELD_SEP + value + FIELD_SEP + clean(text);
    }

    static String values(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (sb.length() > 0) {
                sb.append(VALUE_SEP);
            }
            sb.append(clean(v));
        }
        return sb.toString();
    }

    static String join(List<String> atoms) {
        return String.join(String.valueOf(ATOM_SEP), atoms);
    }

    static String clean(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append((c < ' ') ? ' ' : c);
        }
        return sb.toString();
    }

    // --- jche.dataflow.DataflowBuilder の文字列の探し方 ---

    /** 出所の中に {@code =種別:} が含まれるか（mentionsParam / mentionsCaptured） */
    static boolean mentions(String origins, char kind) {
        return origins != null && origins.indexOf("=" + kind + ":") >= 0;
    }

    /** 条件が囲みメソッドの引数を見ているか */
    static boolean guardsOnParam(String guard) {
        return guard != null && guard.indexOf(FIELD_SEP + "" + 'A' + ":") >= 0;
    }
}
