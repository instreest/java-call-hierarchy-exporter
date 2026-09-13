// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.server;

/**
 * サーバーモード（{@code CallHierarchyExporter --server}）のプロトコル。
 *
 * <h2>形式</h2>
 * 行指向のテキスト（UTF-8、TAB 区切り）。外部ライブラリを増やさないための選択で、
 * 木の行を流すだけの用途には JSON より軽い。値に含まれる TAB・改行・円記号は
 * {@link #escape} で {@code \\t} {@code \\n} {@code \\\\} に置き換える。
 *
 * <h2>やりとり</h2>
 * <pre>
 *   → HELLO 1
 *   ← OK  protocol=1  jdt=3.46.0  jvm=25.0.3
 *
 *   → ANALYZE  /tmp/xxx.properties
 *   ← #P  ソース解析  120  5000          （進捗。何行でも流れる）
 *   ← OK  methods=48213  edges=91022  at=2026-09-12T10:31:04
 *
 *   → FIND  com.example.OrderService#save(com.example.Order)
 *   ← OK  key=...  label=...  file=...  line=42        （無ければ NG not-found）
 *
 *   → TREE  &lt;メソッドキー&gt;  callers  depth=5  text=Order  tests=0
 *   ← R  0  &lt;キー&gt;  &lt;表示名&gt;  &lt;ファイル&gt;  &lt;行&gt;  &lt;解決の理由&gt;  &lt;印&gt;
 *   ← R  1  ...
 *   ← OK  rows=27
 *
 *   → EXPORT  &lt;メソッドキー&gt;  callers  /path/out.csv  depth=5 ...
 *   ← OK  rows=1832
 *
 *   → CANCEL      実行中の ANALYZE を止める（読み取りスレッドが即座に拾う）
 *   → SHUTDOWN
 * </pre>
 *
 * <h2>約束</h2>
 * <ul>
 *   <li>1つの要求には、必ず1つの {@code OK} か {@code NG} で終わる応答を返す</li>
 *   <li>{@code #} で始まる行は付帯情報（進捗・ログ）で、応答の区切りには数えない</li>
 *   <li>標準出力はこのプロトコル専用。解析のログは標準エラーへ出す（混ざると解読できなくなる）</li>
 * </ul>
 */
public final class Protocol {

    /** このプロトコルの版。増やすのは、既存の行の意味を変えるときだけ */
    public static final int VERSION = 1;

    public static final String SEP = "\t";

    /** 応答の先頭（成功） */
    public static final String OK = "OK";
    /** 応答の先頭（失敗）。続けて理由を1語で書く */
    public static final String NG = "NG";
    /** データ行の先頭 */
    public static final String ROW = "R";
    /** 進捗行の先頭 */
    public static final String PROGRESS = "#P";
    /** ログ行の先頭 */
    public static final String LOG = "#L";

    /** 木の向き */
    public static final String CALLERS = "callers";
    public static final String CALLEES = "callees";

    private Protocol() {
    }

    /** TAB 区切りを壊す文字を逃がす。null は空文字にする */
    public static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** {@link #escape} の逆。受け取り側（プラグイン）と同じ規則であることが大事 */
    public static String unescape(String value) {
        if (value == null || value.indexOf('\\') < 0) {
            return (value == null) ? "" : value;
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                sb.append(c);
                continue;
            }
            char next = value.charAt(++i);
            switch (next) {
                case 't' -> sb.append('\t');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case '\\' -> sb.append('\\');
                default -> sb.append(next);
            }
        }
        return sb.toString();
    }
}
