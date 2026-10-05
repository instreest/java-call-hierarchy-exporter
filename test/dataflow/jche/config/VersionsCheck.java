// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.config;

import java.util.List;

/**
 * 依存の版の比較（{@link Versions}）の検査。パッケージの中だけで見える {@code Versions} を直接たたく。
 *
 * <p>見るのは、ローカルリポジトリから版を選ぶときに自然な順になること（Maven の ComparableVersion と同じ結果）。
 * とくに、修飾子の直前の "0" を落とさないと "1.0.0-SNAPSHOT" が "1.0" より新しいと判定され、
 * 範囲や {@code 1.+} の指定で正式版ではなくスナップショットが選ばれていた。
 *
 * <p>結果は 1 行ずつ {@code OK} / {@code NG} で出し、1 つでも NG なら終了コード 1。
 */
public final class VersionsCheck {

    private static int failures;

    private VersionsCheck() {
    }

    public static void main(String[] args) {
        // 修飾子の前の 0（Maven と同じ順）
        older("1.0.0-SNAPSHOT", "1.0");
        older("1.0.0-SNAPSHOT", "1.0.0");
        older("1.0-SNAPSHOT", "1.0.0");
        older("2.0.0-rc1", "2.0");
        older("1.0rc1", "1.0");
        older("1.0.0-beta", "1.0.0-rc");
        same("1.0", "1.0.0");
        same("1.0.0-SNAPSHOT", "1.0-SNAPSHOT");
        // 数字は修飾子より新しい。桁数ではなく数値で比べる
        newer("1.0.1", "1.0-beta");
        newer("1.0.0.1", "1.0.0-SNAPSHOT");
        newer("1.10", "1.9");
        newer("1.0-sp1", "1.0");
        // 選ぶ側。同じ版の正式版とスナップショットがあれば正式版（以前はスナップショットが選ばれていた）
        List<String> candidates = List.of("1.0.0-SNAPSHOT", "1.0", "0.9");
        selects("[1.0,2.0)", candidates, "1.0");
        selects("1.+", candidates, "1.0");
        selects("+", candidates, "1.0");
        selects("1.0", candidates, "1.0");
        // スナップショットは 1 つ前の正式版より新しい（[0.5,1.0) には 1.0.0-SNAPSHOT が入る）
        selects("[0.5,1.0)", candidates, "1.0.0-SNAPSHOT");
        // より新しいスナップショットは + で選ばれ、latest.release では除かれる
        List<String> withNewer = List.of("1.0.0-SNAPSHOT", "1.0", "0.9", "1.1-SNAPSHOT");
        selects("+", withNewer, "1.1-SNAPSHOT");
        selects("latest.release", withNewer, "1.0");
        if (failures > 0) {
            System.out.println("NG   " + failures + " case(s) failed");
            System.exit(1);
        }
        System.out.println("OK   Versions: all cases passed");
    }

    private static void older(String a, String b) {
        check(Versions.compare(a, b) < 0 && Versions.compare(b, a) > 0, a + " < " + b);
    }

    private static void newer(String a, String b) {
        check(Versions.compare(a, b) > 0 && Versions.compare(b, a) < 0, a + " > " + b);
    }

    private static void same(String a, String b) {
        check(Versions.compare(a, b) == 0 && Versions.compare(b, a) == 0, a + " == " + b);
    }

    private static void selects(String spec, List<String> candidates, String expected) {
        String got = Versions.select(spec, candidates);
        check(expected.equals(got), "select(" + spec + ") = " + expected + " (got " + got + ")");
    }

    private static void check(boolean ok, String label) {
        if (ok) {
            System.out.println("OK   " + label);
        } else {
            System.out.println("NG   " + label);
            failures++;
        }
    }
}
