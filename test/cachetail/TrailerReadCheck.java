// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import jche.cache.CacheReader;

/**
 * キャッシュの最終行を末尾から読む {@link CacheReader#lastLineOf} を検査する。
 *
 * 2 つのキャッシュが対になっているか（Z 行のブロック数が一致するか）の判定は毎回の実行で
 * 走るので、先頭から読まずに末尾だけを読む。バイト単位で境界を触るため、
 * <b>実データでは踏まない形</b>を機械的にかけて潰しておく。
 *
 * <pre>
 *   等価   先頭から読んで得た最後の非空行と一致する（これが本来の意味）
 *   境界   改行で終わる／終わらない、CRLF、1 行だけ、空、空行だけ、読む量の境目
 *   文字   多バイト文字が途中で切れない（窓の先頭が文字の途中に落ちても壊さない）
 *   長さ   読む量に収まらない行は null（Z 行ではありえない長さなので、対でないと判定させる）
 * </pre>
 */
public final class TrailerReadCheck {

    /** {@code CacheReader.TAIL_BYTES}。private なのでここに写して境界を突く */
    private static final int TAIL_BYTES = 512;

    private static Path dir;
    private static int checked;
    private static int failed;

    public static void main(String[] args) throws IOException {
        dir = Files.createTempDirectory("jche-cachetail");
        try {
            // --- 素直な形 ---
            expect("ふつうの複数行（LF・改行で終わる）", "h\nA\tx\nZ\t3\n", "Z\t3");
            expect("改行で終わらない", "h\nA\tx\nZ\t3", "Z\t3");
            expect("CRLF（Windows で書いた場合）", "h\r\nA\tx\r\nZ\t3\r\n", "Z\t3");
            expect("1 行だけ・改行あり", "h\n", "h");
            expect("1 行だけ・改行なし", "h", "h");

            // --- 空と空行 ---
            expect("空ファイル", "", null);
            expect("改行だけ", "\n\n\n", null);
            expect("末尾に空行が続く", "h\nZ\t3\n\n\n", "Z\t3");
            expect("末尾に空行が続く（CRLF）", "h\r\nZ\t3\r\n\r\n", "Z\t3");

            // --- 読む量の境目（窓に収まる／収まらない） ---
            for (int pad : new int[] {TAIL_BYTES - 12, TAIL_BYTES - 11, TAIL_BYTES - 10,
                                      TAIL_BYTES - 1, TAIL_BYTES, TAIL_BYTES + 1, TAIL_BYTES * 3}) {
                expect("前の行が " + pad + " バイト", "h\n" + "a".repeat(pad) + "\nZ\t7\n", "Z\t7");
            }
            // 最終行そのものが窓に収まらない。Z 行ではありえない長さなので null でよい
            expect("最終行が窓より長い", "h\n" + "a".repeat(TAIL_BYTES + 50) + "\n", null);
            expect("窓より長い 1 行だけ", "a".repeat(TAIL_BYTES + 50), null);

            // --- 多バイト文字（窓の先頭が文字の途中に落ちる形をわざと作る） ---
            for (int n : new int[] {160, 170, 171, 172, 200}) {
                // 「あ」は UTF-8 で 3 バイト。並べる数を変えて窓の先頭を 1 バイトずつずらす
                expect("前の行が 3 バイト文字 " + n + " 個", "h\n" + "あ".repeat(n) + "\nZ\t9\n", "Z\t9");
            }
            expect("最終行に多バイト文字", "h\nZ\t9\tあいう\n", "Z\t9\tあいう");
            expect("最終行に絵文字（サロゲートペア）", "h\nZ\t9\t😀\n", "Z\t9\t😀");

            // --- 先頭から読んだ結果と一致すること（本来の意味） ---
            sameAsReadingFromStart("ふつうのキャッシュの形", "jche-cache-v19\tsource=26\tgen=abc\n"
                    + "L\tlib.jar\tfp\tpkg\nF\tsrc/A.java\t10\t0\th\nD\tp\tp.A\tm\t\t1\t1\t\nZ\t1\n");
            sameAsReadingFromStart("ブロックが多い形", manyBlocks(200));
            sameAsReadingFromStart("値に多バイト文字が多い形", "h\n"
                    + "N\t0\tL\tSELECT * FROM 注文 WHERE 状態 = ?\t-1\t\t-1\n".repeat(30) + "Z\t30\n");

            System.out.println(failed == 0
                    ? "  OK   " + checked + " 通りの形で最終行の読み取りが一致"
                    : "  NG   " + failed + " / " + checked + " 件が期待と違います");
        } finally {
            deleteAll(dir);
        }
        if (failed != 0) {
            System.exit(1);
        }
    }

    /** {@code content} を書いたファイルの最終行が {@code expected}（null なら「無し」）であること */
    private static void expect(String label, String content, String expected) throws IOException {
        checked++;
        Path f = write(content);
        String actual = CacheReader.lastLineOf(f);
        if (!java.util.Objects.equals(expected, actual)) {
            failed++;
            System.out.println("  NG   " + label);
            System.out.println("       期待: " + show(expected));
            System.out.println("       実際: " + show(actual));
        }
    }

    /**
     * 末尾から読んだ結果が、先頭から 1 行ずつ読んで得た最後の非空行と一致すること。
     * 置き換え前の実装（{@link CacheReader#next} で最後まで読む）と同じ答えになることを見る
     */
    private static void sameAsReadingFromStart(String label, String content) throws IOException {
        checked++;
        Path f = write(content);
        String fromStart = null;
        try (CacheReader in = CacheReader.open(f)) {
            while (in.next()) {
                fromStart = in.line();
            }
        }
        String fromEnd = CacheReader.lastLineOf(f);
        if (!java.util.Objects.equals(fromStart, fromEnd)) {
            failed++;
            System.out.println("  NG   " + label + "（先頭から読んだ結果と一致しません）");
            System.out.println("       先頭から: " + show(fromStart));
            System.out.println("       末尾から: " + show(fromEnd));
        }
    }

    private static String manyBlocks(int blocks) {
        StringBuilder sb = new StringBuilder("jche-cache-v19\tsource=26\tgen=abc\n");
        for (int i = 0; i < blocks; i++) {
            sb.append("F\tsrc/pkg/Type").append(i).append(".java\t100\t0\thash").append(i).append('\n');
            sb.append("I\tjava.lang.String,java.util.List\n");
        }
        return sb.append("Z\t").append(blocks).append('\n').toString();
    }

    private static Path write(String content) throws IOException {
        Path f = dir.resolve("c" + checked + ".tsv");
        Files.write(f, content.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private static String show(String s) {
        return (s == null) ? "(無し)" : "[" + s.replace("\t", "<TAB>") + "]";
    }

    private static void deleteAll(Path d) throws IOException {
        try (var files = Files.list(d)) {
            for (Path f : files.toList()) {
                Files.deleteIfExists(f);
            }
        }
        Files.deleteIfExists(d);
    }

    private TrailerReadCheck() {
    }
}
