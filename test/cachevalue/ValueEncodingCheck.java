// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0

import java.io.IOException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import jche.cache.CacheFormat;
import jche.cache.CacheReader;

/**
 * キャッシュの列の符号化（{@link CacheFormat#escape} / {@link CacheFormat#unescape}）を検査する。
 *
 * 見る性質は 3 つ。
 * <pre>
 *   往復   unescape(escape(s)) が s に戻る（どんな文字列でも。対になっていないサロゲートを含む）
 *   無害化 escape(s) にタブ・改行・制御文字が残らない（行が割れない）
 *   UTF-8  escape(s) を UTF-8 に書ける（対になっていないサロゲートが残らない。キャッシュの書き手は
 *          書けない文字で例外にするので、残ると解析ごと失敗する）
 * </pre>
 * ソースに実際に現れる文字列（SQL・書式・Windows のパス・正規表現）と、
 * そこには出てこないが形式を壊しうる文字（全制御文字、バックスラッシュの連なり、
 * 途中で切れた符号）を両方かける。実データでは踏まない形をここで潰しておくため。
 *
 * <p>行そのものへの組み込みも見る。キャッシュのどの列も {@link CacheFormat#joinRow} が符号化して書き、
 * {@link CacheReader#columns} が戻して読む（1 つの規則）。生の値を joinRow に渡して 1 行にし、
 * それをファイルに書いて CacheReader で読み戻したとき、列の数が変わらず、値が元に戻ること。
 */
public final class ValueEncodingCheck {

    private static int checked;
    private static int failed;
    /** 行に組み込んだ値と、その行（あとでファイルに書いて CacheReader で読み戻す） */
    private static final List<String> originals = new ArrayList<>();
    private static final List<String> rows = new ArrayList<>();

    public static void main(String[] args) throws IOException {
        for (String s : corpus()) {
            check(s);
        }
        // 全 BMP を機械的に。1文字ずつと、前後に文字を足した形で。単独のサロゲートも含める
        // （Java の文字列としては作れ、ソースの "\uD800" や途中で切った絵文字として実際に現れる）
        for (int c = 0; c <= 0xFFFF; c++) {
            String one = String.valueOf((char) c);
            check(one);
            check("a" + one + "b");
        }
        // サロゲートペア（絵文字など）が壊れないこと。対になっていれば符号化せずにそのまま書く
        check("値 \uD83D\uDE00 と \uD83D\uDE00\uD83D\uDE00");
        if (!CacheFormat.escape("\uD83D\uDE00").equals("\uD83D\uDE00")) {
            failed++;
            System.out.println("  NG   対になったサロゲートを符号化しています");
        }
        // 対になっていないサロゲートの並び（上位だけ・下位だけ・逆順・上位の連続・ペアの前後に単独）
        check("\uD83D");
        check("\uDE00");
        check("\uDE00\uD83D");
        check("\uD83D\uD83D\uDE00");
        check("\uD83D\uDE00\uDE00");
        check("x\uD800");
        check("AAAA\uD83D");                // 60 文字で切った条件式の末尾に残る形
        check("\\uD800\uD800");           // 符号化の形をした文字と、本物の単独サロゲート
        if (!CacheFormat.escape("a\uD800b").equals("a\\ud800b")) {
            failed++;
            System.out.println("  NG   単独のサロゲートの符号化が \\uXXXX（小文字の 16 進 4 桁）ではありません: "
                    + visible(CacheFormat.escape("a\uD800b")));
        }
        readBack();

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
        CharsetEncoder utf8 = StandardCharsets.UTF_8.newEncoder();   // 書けない文字で例外にする（キャッシュの書き手と同じ）
        if (!utf8.canEncode(escaped)) {
            report("符号化しても UTF-8 に書けない文字（対になっていないサロゲート）が残ります", original, escaped, back);
            return;
        }
        // 行に組み込む。joinRow が符号化するので、生の値を渡す
        String row = CacheFormat.joinRow("N", "0", "L", original);
        if (!row.equals("N\t0\tL\t" + escaped)) {
            report("joinRow の符号化が escape と違います", original, escaped, back);
            return;
        }
        if (CacheFormat.hasControlChar(row.replace('\t', ' '))) {
            report("行に区切り以外の制御文字が残ります", original, escaped, back);
            return;
        }
        String[] cols = CacheFormat.columnsOf(row);
        if (cols.length != 4 || !original.equals(cols[3])) {
            report("行から読み戻すと値が変わります（列数 " + cols.length + "）", original, escaped, back);
            return;
        }
        originals.add(original);
        rows.add(row);
    }

    /** 組み込んだ行をファイルに書き、CacheReader で読み戻して列が元に戻ることを見る */
    private static void readBack() throws IOException {
        Path file = Files.createTempFile("value-encoding", ".tsv");
        try {
            StringBuilder sb = new StringBuilder("header\n");
            for (String row : rows) {
                sb.append(row).append('\n');
            }
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
            int i = 0;
            try (CacheReader in = CacheReader.open(file)) {
                while (in.next()) {
                    checked++;
                    String original = (i < originals.size()) ? originals.get(i) : "";
                    String[] cols = in.columns();
                    if (i >= originals.size() || cols.length != 4 || !original.equals(cols[3])) {
                        report("ファイルから CacheReader で読み戻すと値が変わります（" + (i + 1) + " 行目、列数 "
                                + cols.length + "）", original, CacheFormat.escape(original), in.column(3));
                    }
                    i++;
                }
            }
            if (i != rows.size()) {
                failed++;
                System.out.println("  NG   ファイルの行数が変わります（書いた " + rows.size() + " 行、読んだ " + i + " 行）");
            }
        } finally {
            Files.deleteIfExists(file);
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
            if (c < ' ' || c == '\u007f' || Character.isSurrogate(c)) {
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
