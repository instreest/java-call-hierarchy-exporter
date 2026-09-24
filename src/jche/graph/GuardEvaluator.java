// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import jche.cache.Guard;
import jche.cache.Origin;

/**
 * 呼び出し箇所を囲む条件（{@link Guard}）が、<b>この経路では成立しない</b>と
 * 言い切れるかを判定する。フェーズ3（{@link jche.report.StreamingTreeWalker}）が使う。
 *
 * <pre>
 *     void run(boolean debug) { if (debug) { dump(); } }
 *     ...
 *     run(false);      // ← この経路の run から dump() へは降りない
 * </pre>
 *
 * <h2>判定の3値</h2>
 * <ul>
 *   <li>成立しない … 経路上で値が確定していて、条件と食い違う。呼び出しはこの経路では起きない</li>
 *   <li>成立する   … 値が確定していて条件と合う。ふつうに辿る</li>
 *   <li>分からない … 値が確定していない。<b>辿る</b>（消さない）</li>
 * </ul>
 * 「分からない」を常に「辿る」に倒すのがこのクラスの要。値が分かるのは
 * 呼び出し元から定数が渡ってきた場合など限られた場合で、それ以外は
 * これまでどおりの出力になる。
 *
 * <h2>使う情報</h2>
 * 経路の {@link DataflowContext}（この経路で呼び出し元が渡した実引数の値）だけを見る。
 * フィールドの状態や実行時の入力は見ない。したがって判定は「この1本の経路について」
 * 閉じており、別の経路では同じ呼び出しが普通に出力される。
 *
 * <h2>値は切り詰めずに比べる</h2>
 * 条件は条件の表（{@link GuardTable}）から、判定される式は値の表（{@link ValueStore}）の葉から、経路の値は
 * 経路の環境の枠（{@link Slot}）から読み、どれも値そのもの（同じ文字列の置き場の番号）で比べる。
 * 以前は出所の文字列から値を取り出していたので、値が {@code | ;} を含むと途中で切れ（{@code "a|c"} を
 * {@code "a"} と読む）、成立する条件を「成立しない」と誤って打ち切っていた。
 */
public final class GuardEvaluator {

    private final boolean enabled;
    private final GuardTable guards;
    private final ValueStore values;

    /**
     * @param enabled branch.pruning
     * @param graph   条件の表（{@link CallGraph#guards}）と値の表（{@link CallGraph#values}）を持つグラフ
     */
    public GuardEvaluator(boolean enabled, CallGraph graph) {
        this.enabled = enabled;
        this.guards = graph.guards();
        this.values = graph.values();
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * この経路で呼び出しに到達しないと言い切れるなら、その理由（注記に出す文言）。
     * 言い切れなければ null。
     *
     * @param guardId 呼び出し箇所を囲む条件（{@link CallGraph#guardOf}）。無ければ {@link GuardTable#NONE}
     */
    public String unreachableReason(int guardId, DataflowContext ctx) {
        if (!enabled || guardId == GuardTable.NONE) {
            return null;
        }
        for (int a = guards.atomBegin(guardId), end = guards.atomEnd(guardId); a < end; a++) {
            int subject = guards.subject(a);
            int actual = valueOf(subject, ctx);
            if (actual < 0) {
                continue;   // この経路では値が分からない ＝ 判定しない（安全側）
            }
            if (!holds(a, actual)) {
                return reason(guards.text(a), subject, values.strings().get(actual));
            }
        }
        return null;
    }

    /**
     * 条件が成立するか。値は値の番号で比べる（期待値と実際の値は同じ文字列の置き場にあり、同じ中身なら
     * 同じ番号）。どちらも切り詰めていない値そのもの
     */
    private boolean holds(int atom, int actual) {
        return switch (guards.op(atom)) {
            case GuardTable.EQ -> guards.valueId(guards.valueBegin(atom)) == actual;
            case GuardTable.NE -> guards.valueId(guards.valueBegin(atom)) != actual;
            case GuardTable.IN -> contains(atom, actual);
            case GuardTable.NOT_IN -> !contains(atom, actual);
            default -> true;   // 知らない種別は判定しない
        };
    }

    private boolean contains(int atom, int actual) {
        for (int k = guards.valueBegin(atom), end = guards.valueEnd(atom); k < end; k++) {
            if (guards.valueId(k) == actual) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判定される式の、この経路での値の番号（{@link StringPool}）。分からなければ -1。
     *
     * 定数（V）はその場で決まる。引数（A）は、呼び出し元から渡された値が
     * 経路の環境に入っているときだけ決まる。
     */
    private int valueOf(int subject, DataflowContext ctx) {
        char kind = values.kind(subject);
        if (kind == Origin.CONST) {
            return values.valueId(subject);
        }
        if (kind != Origin.PARAM || ctx == null || ctx.params() == null) {
            return -1;
        }
        int index = values.index(subject);
        long[] params = ctx.params();
        if (index < 0 || index >= params.length) {
            return -1;
        }
        // 経路の環境には具象型と値（文字列・クラス・定数・ラムダ）が札付きで入っている。
        // 条件の判定に使えるのは文字列・クラス・定数の値だけ（型の名前は値ではない）
        return Slot.constantValueId(params[index]);
    }

    /** 注記の文言。「どの条件が」「何の値で」成立しないのかを書く */
    private String reason(String text, int subject, String actual) {
        StringBuilder sb = new StringBuilder("[UNREACHABLE] not called on this path: condition '");
        sb.append(text.isEmpty() ? "?" : text).append("' does not hold");
        if (values.kind(subject) == Origin.PARAM) {
            sb.append(" (argument ").append(paramNumber(subject)).append(" from the caller = ")
                    .append(actual).append(")");
        } else {
            sb.append(" (constant = ").append(actual).append(")");
        }
        return sb.toString();
    }

    private String paramNumber(int subject) {
        int index = values.index(subject);
        return (index < 0) ? "?" : String.valueOf(index + 1);
    }
}
