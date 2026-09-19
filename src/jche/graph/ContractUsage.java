// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import java.util.ArrayList;
import java.util.List;

import jche.util.Log;

/**
 * 契約表の行ごとに「効いたか」を数え、解析の最後に知らせる。
 *
 * <p>契約表はただの文字列なので、型名やシグネチャを間違えても実行時は「当たらない」だけで
 * 何も言わない。同梱の表ですら、当たらない行があることに検査（{@code test/contracts/run.sh}）を
 * 書くまで気づけなかった。利用者が足した表にはその検査が無いので、一度も当たらなかった行を
 * 実行のたびに知らせる（docs/callback-contracts-qa.md の Q16）。
 *
 * <p>同梱の表は「そのプロジェクトで使っていない機能の行」が当たらないのが普通
 * （{@code Timer} を使っていなければ {@code Timer} の行は当たらない）なので、効いた行数だけを
 * 数えて列挙はしない。列挙するのは利用者が足した行だけにする。
 *
 * <p>数え上げはグラフ全体の走査が終わってから読むこと。呼び戻しの契約は
 * {@link CallResolver#inDegrees()} が全エッジについて、入口の契約は methods.csv の出力が
 * 全メソッドについて問い合わせるので、その両方が済んで初めて「一度も当たらなかった」と言える。
 */
public final class ContractUsage {

    /** 出所の表示: 同梱の表 */
    static final String BUNDLED = "同梱";

    /** 契約表の1行と、その出所（契約表のファイル名・拡張のクラス名・同梱） */
    public record Line(String text, String origin, boolean bundled) {
    }

    /** 行に対応づかない（{@link CallbackContracts#parse} を単体で呼んだ場合） */
    static final int NO_ROW = -1;

    private final List<Line> lines;
    /** 呼び戻し先（または入口）まで決まった行 */
    private final boolean[] applied;
    /** 種類Aのみ: 呼び出し先には一致したが、渡した値の具象型が決まらず繋げなかった行 */
    private final boolean[] reached;

    public ContractUsage(List<Line> lines) {
        this.lines = List.copyOf(lines);
        this.applied = new boolean[this.lines.size()];
        this.reached = new boolean[this.lines.size()];
    }

    /** 同梱の表だけを持つ（契約表の設定を読まない経路と、テスト用） */
    static ContractUsage ofBundled(List<String> texts) {
        List<Line> out = new ArrayList<>(texts.size());
        for (String text : texts) {
            out.add(new Line(text, BUNDLED, true));
        }
        return new ContractUsage(out);
    }

    /** 読み込んだ行（並びは読み込んだ順。添字がそのまま行の番号） */
    List<Line> lines() {
        return lines;
    }

    /** その行で呼び戻し先（または入口）が決まった */
    void markApplied(int row) {
        if (row >= 0 && row < applied.length) {
            applied[row] = true;
        }
    }

    /** その行の呼び出し先には一致した（繋がったかは問わない） */
    void markReached(int row) {
        if (row >= 0 && row < reached.length) {
            reached[row] = true;
        }
    }

    /**
     * 契約表の利用状況を知らせる。CSV を書き終えたあとに 1 回だけ呼ぶ。
     *
     * <p>自前の表を書いていなければ何も出さない。同梱の表だけで動かしている利用者の
     * ログを増やさないため（同梱の表が当たらないのは異常ではない）。
     *
     * @param callbacks 呼び戻しの表（種類 A）
     * @param entries   入口の表（種類 B）
     */
    public static void report(ContractUsage callbacks, ContractUsage entries) {
        int userRows = callbacks.userRows() + entries.userRows();
        if (userRows == 0) {
            return;
        }
        Log.info("契約表の適用: 自前 " + (callbacks.appliedUser() + entries.appliedUser()) + "/" + userRows + " 行"
                + "（呼び戻し " + callbacks.appliedUser() + "/" + callbacks.userRows()
                + "、入口 " + entries.appliedUser() + "/" + entries.userRows() + "）"
                + " ／ 同梱 " + (callbacks.appliedBundled() + entries.appliedBundled()) + " 行");

        List<Line> unusedCallbacks = callbacks.unusedUser();
        List<Line> unused = new ArrayList<>(unusedCallbacks);
        unused.addAll(entries.unusedUser());
        if (!unused.isEmpty()) {
            Log.warn("自前の契約表で一度も当たらなかった行が " + unused.size() + " 件あります。"
                    + "型名・シグネチャの綴り違いか、そのプロジェクトでは使っていない機能の行です:");
            for (Line line : unused) {
                Log.info("    " + line.origin() + ": " + line.text());
            }
            if (!unusedCallbacks.isEmpty()) {
                // 呼び戻しの行でいちばん多い間違い。入口の行しか無いときは関係が無いので出さない
                Log.info("    ※ 呼び戻しの行の呼び出し先は、JDT が返す「宣言型」で書きます"
                        + "（List#forEach ではなく Iterable#forEach。docs/callback-contracts.md）。");
            }
        }

        List<Line> near = callbacks.reachedButUnappliedUser();
        if (!near.isEmpty()) {
            Log.info("※ 呼び出し先には一致したが、渡した値の具象型が決まらず繋げなかった行が "
                    + near.size() + " 件あります（表の誤りではありません）:");
            for (Line line : near) {
                Log.info("    " + line.origin() + ": " + line.text());
            }
            Log.info("    ※ 追える形は docs/callback-contracts.md の「追える条件」にあります。");
        }
    }

    /** 利用者が足した行数 */
    private int userRows() {
        int n = 0;
        for (Line line : lines) {
            if (!line.bundled()) {
                n++;
            }
        }
        return n;
    }

    /** 効いた行数（利用者が足した分） */
    private int appliedUser() {
        return appliedCount(false);
    }

    /** 効いた行数（同梱の分） */
    private int appliedBundled() {
        return appliedCount(true);
    }

    private int appliedCount(boolean bundled) {
        int n = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).bundled() == bundled && applied[i]) {
                n++;
            }
        }
        return n;
    }

    /** 利用者が足した行のうち、呼び出し先にも一致しなかったもの（綴り違いが疑われる） */
    private List<Line> unusedUser() {
        List<Line> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).bundled() && !applied[i] && !reached[i]) {
                out.add(lines.get(i));
            }
        }
        return out;
    }

    /** 利用者が足した行のうち、呼び出し先には一致したが繋げなかったもの */
    private List<Line> reachedButUnappliedUser() {
        List<Line> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).bundled() && !applied[i] && reached[i]) {
                out.add(lines.get(i));
            }
        }
        return out;
    }
}
