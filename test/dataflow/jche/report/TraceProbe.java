// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.report;

import java.util.List;
import java.util.function.IntFunction;

import jche.graph.CallGraph;
import jche.graph.CallbackContracts;
import jche.graph.DataflowContext;
import jche.graph.DataflowResolver;
import jche.graph.FactoryCalls;
import jche.graph.MethodTable;
import jche.graph.Resolution;
import jche.graph.Slot;
import jche.graph.StringPool;

/**
 * 検査用: 経路を歩いた中身を 1 行ずつ記録する（{@link StreamingTreeWalker#probe} に差し込む）。
 * test/dataflow の TraceCheck が使う。stage B の間だけ置く。
 *
 * <p>記録は辺を見るたびに {@code V} の行と、それに続く字下げした行（解決・経路の値・打ち切り・ファクトリの
 * キー・ひな形の左辺・束縛した値・呼び戻し）。メソッドは ID ではなくキーで書く（ID はキャッシュの並びで変わる）。
 * 値の中の制御文字と {@code \} は {@link #esc} で書き換える（行を壊さないため）。
 *
 * <p>経路の値の枠（{@link Slot}）は、読み手を値の表へ移す前の文字列と同じ形で書く（記録を移す前と
 * 1 文字ずつ突き合わせるため）。具象型は型の名前、文字列・クラス・定数は {@code L:} / {@code K:} / {@code V:} と値、
 * ラムダは値の表のノードを以前の出所の文字列に組み直したもの（{@code LegacyRender}。渡された関数で組み直す）、
 * 分からない枠は {@code null}。
 */
public final class TraceProbe implements StreamingTreeWalker.Probe {

    private final CallGraph graph;
    private final MethodTable methods;
    private final DataflowResolver dataflow;
    private final StringPool strings;
    /** 値の表の参照を以前の出所の文字列に組み直す（ラムダの枠を書くのに使う） */
    private final IntFunction<String> render;
    private final StringBuilder out = new StringBuilder();

    private TraceProbe(CallGraph graph, DataflowResolver dataflow, IntFunction<String> render) {
        this.graph = graph;
        this.methods = graph.methods();
        this.dataflow = dataflow;
        this.strings = graph.values().strings();
        this.render = render;
    }

    /**
     * 差し込む。{@link #uninstall} で外すまで、すべての歩き方が記録される
     *
     * @param render 値の表の参照を以前の出所の文字列に組み直す関数（ラムダの枠を書くのに使う）
     */
    public static TraceProbe install(CallGraph graph, DataflowResolver dataflow, IntFunction<String> render) {
        TraceProbe p = new TraceProbe(graph, dataflow, render);
        StreamingTreeWalker.probe = p;
        return p;
    }

    /** 外す */
    public static void uninstall() {
        StreamingTreeWalker.probe = null;
    }

    /** 記録した文字列 */
    public String text() {
        return out.toString();
    }

    @Override
    public void edge(int depth, int callerId, int edgeIndex, DataflowContext ctx, Resolution res,
                     String unreachable) {
        out.append("V ").append(depth).append(' ').append(methods.key(callerId))
                .append(" @").append(graph.callLineOf(edgeIndex))
                .append(" -> ").append(methods.key(graph.calleeOf(edgeIndex))).append('\n');
        out.append("  res ").append(describe(methods, res)).append('\n');
        if (ctx != null) {
            out.append("  ctx p=").append(slots(ctx.params())).append(" c=").append(slots(ctx.ctorArgs()))
                    .append(" o=").append(esc(ctx.ctorOwner())).append(" k=").append(slots(ctx.captured()))
                    .append('\n');
        }
        if (unreachable != null) {
            out.append("  unreachable ").append(esc(unreachable)).append('\n');
        }
        List<FactoryCalls.Key> keys = FactoryCalls.keysOf(graph.recvNode(edgeIndex), dataflow, ctx);
        if (!keys.isEmpty()) {
            out.append("  keys");
            for (FactoryCalls.Key k : keys) {
                out.append(' ').append(esc(k.typeAndName())).append('|').append(k.kind()).append('|')
                        .append(esc(k.key()));
            }
            out.append('\n');
        }
        // 絞れなかった呼び出しのひな形の左辺（歩き手がひな形を足すのと同じ条件のとき）
        if (res.isMultiple() && unreachable == null && !Resolution.REFLECTION.equals(res.label())) {
            ContractSuggestions.Left left = ContractSuggestions.leftSideFor(graph, dataflow, ctx, edgeIndex,
                    methods, graph.calleeOf(edgeIndex));
            out.append("  left ").append((left == null) ? "-"
                    : esc(left.text()) + (left.fromFactory() ? " factory" : " type")).append('\n');
        }
    }

    @Override
    public void target(int edgeIndex, int target, long[] params, long[] ctorArgs, String ctorOwner,
                       long[] captured) {
        out.append("  T ").append(methods.key(target)).append(" p=").append(slots(params))
                .append(" c=").append(slots(ctorArgs)).append(" o=").append(esc(ctorOwner))
                .append(" k=").append(slots(captured)).append('\n');
    }

    /** 経路の値の枠の並び（以前の文字列の配列と同じ書き方。{@link #arr}） */
    private String slots(long[] a) {
        if (a == null) {
            return "-";
        }
        String[] text = new String[a.length];
        for (int i = 0; i < a.length; i++) {
            text[i] = slotText(a[i]);
        }
        return arr(text);
    }

    /** 経路の値の枠 1 つを、読み手を移す前の文字列の形にする。分からない枠は null */
    private String slotText(long slot) {
        int payload = Slot.payload(slot);
        return switch (Slot.tag(slot)) {
            case Slot.TYPE -> strings.get(payload);
            case Slot.LITERAL -> "L:" + strings.get(payload);
            case Slot.CLASS -> "K:" + strings.get(payload);
            case Slot.CONST -> "V:" + strings.get(payload);
            case Slot.FUNCTIONAL -> render.apply(payload);
            default -> null;
        };
    }

    @Override
    public void callbacks(int edgeIndex, List<CallbackContracts.Match> matches) {
        for (CallbackContracts.Match m : matches) {
            out.append("  CB ").append(methods.key(m.target())).append(" [").append(esc(m.contract()))
                    .append("] ").append(m.candidates()).append('\n');
        }
    }

    /** 解決の結果（ラベルと候補のキー） */
    public static String describe(MethodTable methods, Resolution res) {
        StringBuilder sb = new StringBuilder(esc(res.label()));
        for (int t : res.targets()) {
            sb.append(' ').append(methods.key(t));
        }
        return sb.toString();
    }

    /** 配列（経路の値）。null は {@code -} */
    public static String arr(String[] a) {
        if (a == null) {
            return "-";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append((a[i] == null) ? "null" : esc(a[i]));
        }
        return sb.append(']').toString();
    }

    /** 制御文字と {@code \} を書き換える（行を壊さない）。null は {@code -} */
    public static String esc(String s) {
        if (s == null) {
            return "-";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                sb.append("\\\\");
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else if (c < ' ') {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
