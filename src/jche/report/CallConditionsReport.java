// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.report;

import java.util.List;

import jche.analysis.CallConditionScanner;
import jche.util.Log;

/**
 * 「選んだ呼び出しに効いている条件」を画面に出す（{@link CallConditionScanner} の結果の表示）。
 *
 * ファイルは書かない。調べたいときにその場で読むための出力なので、CSV にはしていない
 * （全件を CSV に出す案は {@code docs/call-conditions.md} の「この先の拡張」）。
 */
public final class CallConditionsReport {

    private CallConditionsReport() {
    }

    /** 結果を画面に書く。呼び出しが1件も見つからなければ false */
    public static boolean print(String target, CallConditionScanner.Result result) {
        if (result.files().isEmpty()) {
            Log.warn("対象のソースファイルが見つかりません: " + target);
            Log.info("  ファイル（src/foo/Bar.java）・行（src/foo/Bar.java:120）・"
                    + "型（foo.Bar）・メソッド（foo.Bar#method）のいずれかで指定してください。");
            return false;
        }
        Log.info("対象: " + target);
        Log.info("解析したファイル: " + String.join(", ", result.files())
                + "（呼び出し " + result.callSitesInFiles() + " 件）");
        if (result.sites().isEmpty()) {
            Log.warn("指定に合う呼び出しがありません（行やメソッド名の指定を外すと、そのファイルの全件を出します）");
            return false;
        }

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
        return true;
    }
}
