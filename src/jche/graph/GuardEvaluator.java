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
 */
public final class GuardEvaluator {

    private final boolean enabled;

    public GuardEvaluator(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * この経路で呼び出しに到達しないと言い切れるなら、その理由（注記に出す文言）。
     * 言い切れなければ null。
     */
    public String unreachableReason(String guard, DataflowContext ctx) {
        if (!enabled || guard == null || guard.isEmpty()) {
            return null;
        }
        for (String atom : Guard.atomsOf(guard)) {
            String op = Guard.fieldOf(atom, 0);
            String origin = Guard.fieldOf(atom, 1);
            String expected = Guard.fieldOf(atom, 2);
            String text = Guard.fieldOf(atom, 3);
            String actual = valueOf(origin, ctx);
            if (actual == null) {
                continue;   // この経路では値が分からない ＝ 判定しない（安全側）
            }
            if (!holds(op, expected, actual)) {
                return reason(text, origin, actual);
            }
        }
        return null;
    }

    /** 条件が成立するか。op は {@link Guard} の判定種別 */
    private static boolean holds(String op, String expected, String actual) {
        return switch (op) {
            case Guard.EQ -> actual.equals(expected);
            case Guard.NE -> !actual.equals(expected);
            case Guard.IN -> contains(expected, actual);
            case Guard.NOT_IN -> !contains(expected, actual);
            default -> true;   // 知らない種別は判定しない
        };
    }

    private static boolean contains(String values, String actual) {
        for (String v : Guard.valuesOf(values)) {
            if (v.equals(actual)) {
                return true;
            }
        }
        return false;
    }

    /**
     * その出所の、この経路での値。分からなければ null。
     *
     * 定数（V:）はその場で決まる。引数（A:）は、呼び出し元から渡された値が
     * 経路の環境に入っているときだけ決まる。
     */
    private static String valueOf(String origin, DataflowContext ctx) {
        char kind = Origin.kindOf(origin);
        if (kind == Origin.CONST) {
            return Origin.valueOf(origin);
        }
        if (kind != Origin.PARAM || ctx == null || ctx.paramTypes() == null) {
            return null;
        }
        int index;
        try {
            index = Integer.parseInt(Origin.valueOf(origin));
        } catch (NumberFormatException ignore) {
            return null;
        }
        String[] params = ctx.paramTypes();
        if (index < 0 || index >= params.length) {
            return null;
        }
        // 経路の環境には具象型（FQN）と値（V: / L: / K:）が混ざって入っている。
        // 条件の判定に使えるのは値の方だけ
        return Origin.constantValueOf(params[index]);
    }

    /** 注記の文言。「どの条件が」「何の値で」成立しないのかを書く */
    private static String reason(String text, String origin, String actual) {
        StringBuilder sb = new StringBuilder("この経路では呼ばれない: 条件「");
        sb.append(text.isEmpty() ? "?" : text).append("」が成立しない");
        if (Origin.kindOf(origin) == Origin.PARAM) {
            sb.append("（呼び出し元から渡された第").append(paramNumber(origin)).append("引数 = ")
                    .append(actual).append("）");
        } else {
            sb.append("（定数 = ").append(actual).append("）");
        }
        return sb.toString();
    }

    private static String paramNumber(String origin) {
        try {
            return String.valueOf(Integer.parseInt(Origin.valueOf(origin)) + 1);
        } catch (NumberFormatException ignore) {
            return "?";
        }
    }
}
