// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.report;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.List;

import jche.analysis.CallConditionScanner;
import jche.config.Config;
import jche.util.Log;

/**
 * 「選んだ呼び出しに効いている条件」を出す（{@link CallConditionScanner} の結果の出力）。
 *
 * 設定に {@code conditions.target} があるときだけ、通常の出力（call-hierarchy.csv / methods.csv）に
 * <b>追加で</b> {@code call-conditions.csv} を書き、あわせて画面にも並べる。
 * 通常の解析を置き換えたり止めたりはしない（モードを増やさず、出力が1つ増えるだけにする）。
 *
 * 画面の表示は調査中にその場で読むため、CSV は表計算で並べ替え・絞り込みをするため。
 * 同じ内容を2つの形で出している。
 */
public final class CallConditionsReport {

    private CallConditionsReport() {
    }

    /**
     * 条件の一覧を CSV に書き、画面にも出す。
     *
     * @return 書いた行数（条件1件で1行。条件が無い呼び出しも1行）
     */
    public static long write(Config config, String target, CallConditionScanner.Result result)
            throws IOException {
        if (result.files().isEmpty()) {
            Log.warn("conditions.target の対象が見つかりません: " + target);
            Log.info("  ファイル（src/foo/Bar.java）・行（src/foo/Bar.java:120）・"
                    + "型（foo.Bar）・メソッド（foo.Bar#method）のいずれかで指定してください。");
            return 0;
        }
        Log.info("対象: " + target);
        Log.info("解析したファイル: " + String.join(", ", result.files())
                + "（呼び出し " + result.callSitesInFiles() + " 件）");
        if (result.sites().isEmpty()) {
            Log.warn("conditions.target に合う呼び出しがありません"
                    + "（行やメソッド名の指定を外すと、そのファイルの全件を出します）");
            return 0;
        }

        printToScreen(result);
        return writeCsv(config, result);
    }

    /** その場で読むための画面表示 */
    private static void printToScreen(CallConditionScanner.Result result) {
        int undecidable = 0;
        int guarded = 0;
        Log.blank();
        for (CallConditionScanner.CallSiteConditions site : result.sites()) {
            Log.plain(site.file() + ":" + site.line() + "  " + site.caller() + " → " + site.callee());
            List<CallConditionScanner.Condition> conditions = site.conditions();
            if (conditions.isEmpty()) {
                Log.plain("    （条件なし。このメソッドに入れば必ず実行される）");
                Log.plain("");
                continue;
            }
            guarded++;
            if (site.anyUndecidable()) {
                undecidable++;
            }
            // 集めた順は内側からなので、読む順（外側の条件から内側へ）に直して出す。
            // 「記録しきれなかった」の印は最も外側に付くので、反転すると自然に先頭へ来る
            int i = 0;
            for (int k = conditions.size() - 1; k >= 0; k--) {
                CallConditionScanner.Condition c = conditions.get(k);
                i++;
                if (c.truncationMark()) {
                    Log.plain("    …  " + c.text());
                    continue;
                }
                Log.plain("    " + i + ". " + (c.decidable() ? "[判定可] " : "[判定不可] ")
                        + c.text()
                        + (c.decidable() ? "   … " + c.subjectKind() + " " + c.expectation() : ""));
            }
            Log.plain("");
        }
        Log.info("該当した呼び出し: " + result.sites().size() + " 件"
                + "（条件つき " + guarded + " 件 / うち判定できない条件を含む " + undecidable + " 件）");
        Log.info("  [判定可]   … 呼び出し元から定数が渡れば、その経路では呼ばれないと判定できる（打ち切りに使われる）");
        Log.info("  [判定不可] … 到達には効くが静的には値が決まらない。実行時の値を人が確認する必要がある");
    }

    /**
     * 表計算で扱うための CSV。
     *
     * 1行＝「呼び出し1件に効いている条件1つ」。条件が無い呼び出しも、
     * 「条件なしで必ず実行される」ことが分かるように1行出す（条件の列は空）。
     * 並びは画面と同じ（呼び出しはソース順、条件は外側から内側）。
     */
    private static long writeCsv(Config config, CallConditionScanner.Result result) throws IOException {
        long rows = 0;
        try (BufferedWriter w = Csv.writer(config.conditionsCsv, config.outputEncoding, config.outputBom)) {
            w.write(String.join(Csv.DELIM, "file", "line", "caller", "callee",
                    "condIndex", "decidable", "condition", "subjectKind", "expectation"));
            w.newLine();
            for (CallConditionScanner.CallSiteConditions site : result.sites()) {
                List<CallConditionScanner.Condition> conditions = site.conditions();
                if (conditions.isEmpty()) {
                    writeRow(w, site, 0, "", "", "", "");
                    rows++;
                    continue;
                }
                int i = 0;
                for (int k = conditions.size() - 1; k >= 0; k--) {
                    CallConditionScanner.Condition c = conditions.get(k);
                    i++;
                    writeRow(w, site, i, c.decidable() ? "1" : "0", c.text(),
                            c.decidable() ? c.subjectKind() : "", c.expectation());
                    rows++;
                }
            }
        }
        Log.info("呼び出しの条件: " + config.conditionsCsv + "（" + rows + " 行）");
        return rows;
    }

    private static void writeRow(BufferedWriter w, CallConditionScanner.CallSiteConditions site,
                                 int index, String decidable, String condition,
                                 String subjectKind, String expectation) throws IOException {
        w.write(String.join(Csv.DELIM,
                Csv.esc(site.file()),
                String.valueOf(site.line()),
                Csv.esc(site.caller()),
                Csv.esc(site.callee()),
                (index == 0) ? "" : String.valueOf(index),
                decidable,
                Csv.esc(condition),
                Csv.esc(subjectKind),
                Csv.esc(expectation)));
        w.newLine();
    }
}
