// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0

import java.util.ArrayList;
import java.util.List;

import jche.cache.CacheFormat;

/**
 * dataflow-cache.tsv の値の符号化（{@link CacheFormat#escape} / {@link CacheFormat#unescape}）を検査する。
 *
 * 見る性質は 2 つ。
 * <pre>
 *   往復   unescape(escape(s)) が s に戻る（どんな文字列でも）
 *   無害化 escape(s) にタブ・改行・制御文字が残らない（行が割れない）
 * </pre>
 * ソースに実際に現れる文字列（SQL・書式・Windows のパス・正規表現）と、
 * そこには出てこないが形式を壊しうる文字（全制御文字、バックスラッシュの連なり、
 * 途中で切れた符号）を両方かける。実データでは踏まない形をここで潰しておくため。
 *
 * <p>行そのものへの組み込み（{@link CacheFormat#joinRow} を通しても値が変わらないこと）も見る。
 * joinRow は analysis 側の規則で {@code clean} を通すので、escape 済みの値が
 * そこで削られないことを確かめる意味がある。
 */
public final class ValueEncodingCheck {

    private static int checked;
    private static int failed;

    public static void main(String[] args) {
        for (String s : corpus()) {
            check(s);
        }
        // 全 BMP を機械的に。1文字ずつと、前後に文字を足した形で
        for (int c = 0; c <= 0xFFFF; c++) {
            if (Character.isSurrogate((char) c)) {
                continue;   // 単独のサロゲートは文字列として不正なので除く
            }
            String one = String.valueOf((char) c);
            check(one);
            check("a" + one + "b");
        }
        // サロゲートペア（絵文字など）が壊れないこと
        check("値 \uD83D\uDE00 と \uD83D\uDE00\uD83D\uDE00");

        System.out.println(failed == 0
                ? "OK   " + checked + " 通りの値が往復し、行を壊さない"
                : "NG   " + failed + " / " + checked + " 件で失敗");
        if (failed != 0) {
            System.exit(1);
        }
    }

    /** ソースに現れる形と、形式を壊しうる形 */
    private static List<String> corpus() {
        List<String> out = new ArrayList<>();
        out.add("");
        out.add("jp.co.example.dao.OrderDaoImpl");
        out.add("SELECT id, name\n  FROM orders\n WHERE kind = ?");
        out.add("SELECT\tid\tFROM\tt");
        out.add("行末が CR LF の SQL\r\nSELECT 1\r\n");
        out.add("C:\\Users\\dev\\app\\config.properties");
        out.add("[a-z]+\\s*\\\\(\\\\d+\\\\)");
        out.add("%s に %d 件（%,.2f%%）");
        out.add("\\");
        out.add("\\\\");
        out.add("\\\\\\");
        out.add("\\t");                  // 「バックスラッシュと t」という 2 文字。タブではない
        out.add("\\n\\r\\\\");
        out.add("\\u0041");              // 符号化の形をした 6 文字。復号で 'A' にしてはいけない
        out.add("\\u00");                // 途中で切れた符号の形
        out.add("\\uZZZZ");
        out.add("末尾がバックスラッシュ\\");
        out.add("\u0001\u0002\u0003");   // Guard の区切りに使っている制御文字
        out.add("\u0000終端\u007f");
        out.add("日本語とタブ\tと改行\n");
        out.add("a".repeat(10000) + "\n" + "b".repeat(10000));   // 長さの上限が無いこと
        return out;
    }

    private static void check(String original) {
        checked++;
        String escaped = CacheFormat.escape(original);
        String back = CacheFormat.unescape(escaped);
        if (!original.equals(back)) {
            report("往復しません", original, escaped, back);
            return;
        }
        if (CacheFormat.hasControlChar(escaped)) {
            report("符号化しても制御文字が残ります", original, escaped, back);
            return;
        }
        // 行に組み込んでも値が変わらないこと（joinRow は clean を通す）
        String row = CacheFormat.joinRow("N", "0", "L", escaped);
        String[] cols = CacheFormat.columnsOf(row);
        if (cols.length != 4 || !escaped.equals(cols[3])) {
            report("行に組み込むと値が変わります（列数 " + cols.length + "）", original, escaped, back);
            return;
        }
        if (!original.equals(CacheFormat.unescape(cols[3]))) {
            report("行から読み戻せません", original, escaped, back);
        }
    }

    private static void report(String what, String original, String escaped, String back) {
        failed++;
        if (failed <= 10) {
            System.out.println("  NG   " + what);
            System.out.println("       元    : " + visible(original));
            System.out.println("       符号化: " + visible(escaped));
            System.out.println("       復号  : " + visible(back));
        }
    }

    /** 制御文字を目に見える形にして表示する（診断用） */
    private static String visible(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && i < 120; i++) {
            char c = s.charAt(i);
            if (c < ' ' || c == '\u007f') {
                sb.append("<").append(String.format("%02x", (int) c)).append(">");
            } else {
                sb.append(c);
            }
        }
        if (s.length() > 120) {
            sb.append("…(").append(s.length()).append(" 文字)");
        }
        return sb.toString();
    }

    private ValueEncodingCheck() {
    }
}
