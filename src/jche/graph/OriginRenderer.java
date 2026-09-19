// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jche.cache.Origin;
import jche.cache.ValueNode;

/**
 * 値グラフ（dataflow-cache.tsv の N 行）を {@link Origin} の文字列に組み直す。
 *
 * <h2>なぜ組み直すのか</h2>
 * 読み手（{@link DataflowResolver} / {@link GuardEvaluator} / {@link CallResolver} /
 * {@code jche.report.StreamingTreeWalker} / {@code jche.dataflow.DataflowBuilder}）は
 * すべて {@link Origin} の文字列を受け取る形になっている。値グラフはその文字列と同じことを
 * <b>上限なしで</b>表せるので、読む直前にこの形へ組み直せば、読み手を1つも書き換えずに
 * 上限だけを外せる。{@link Origin} の文法は入れ子を {@code {}} で囲めるので、
 * 何段でも表せる（{@code docs/cache-split-qa.md} の Q21）。
 *
 * <h2>外れた上限</h2>
 * <pre>
 *   実引数の入れ子   1段のみ（head で剥がす）  -> 無制限。f(g(h())) の h まで辿れる
 *   レシーバの入れ子 3段まで                   -> 無制限
 *   文字列リテラル   64文字以内で、かつFQNか    -> 無制限。SQL・ログ文言もそのまま
 *                    識別子の形
 *   定数の値         64文字以内で、かつ制御文字 -> 無制限。符号化して持つ
 *                    を含まない
 * </pre>
 *
 * <h2>{@link #BUDGET} は上限ではなく安全弁</h2>
 * 値グラフは同じ式を1ノードにまとめた<b>DAG</b>だが、{@link Origin} の文字列は<b>木</b>なので、
 * 組み直すと同じ部分式が何度も展開される。実在のコードの式では問題にならないが、
 * 機械生成のコードなどで深い入れ子と多い引数が重なると、1つの文字列が際限なく伸びうる。
 * そこで文字数の予算を置き、使い切ったらそれ以上は入れ子を展開せず頭（{@code T:型} /
 * {@code M:メソッドキー}）だけにする。<b>意味のある打ち切りではない</b>ので、
 * 実在のコードで当たらない大きさにしてある（当たっても、その先を辿らないだけで結論は安全側）。
 */
final class OriginRenderer {

    /** 1つの出所の文字数の予算（安全弁。{@code jche.analysis.ValueGraph.HARD_CAP} と同じ役割） */
    private static final int BUDGET = 64 * 1024;

    /** 木として展開する深さの安全弁。ノード番号は子 &lt; 親なので循環しないが、念のため置く */
    private static final int MAX_DEPTH = 512;

    private final List<ValueNode> nodes;
    /** 同じノードを何度も組み直さないための覚え書き（番号 -&gt; 組み直した文字列） */
    private final Map<Integer, String> memo = new HashMap<>();

    OriginRenderer(List<ValueNode> nodes) {
        this.nodes = nodes;
    }

    /** ノード番号1つを出所の文字列にする。番号が無ければ null（＝追跡できない） */
    String originOf(int id) {
        return render(id, 0);
    }

    /**
     * P 行の実引数（{@code 位置=ノード番号} のカンマ区切り）を、
     * 出所の実引数リスト（{@code 位置=出所;位置=出所}）にする。
     *
     * 入れ子を剥がさないのがここの本題。{@code repo.save(mapper.toEntity(dto))} の
     * {@code toEntity} の先まで読み手が辿れるようになる
     */
    String argOriginsOf(String args) {
        if (args == null || args.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int start = 0;
        while (start <= args.length()) {
            int end = args.indexOf(ValueNode.ARG_SEP, start);
            if (end < 0) {
                end = args.length();
            }
            appendEntry(sb, args, start, end, 0);
            if (end >= args.length()) {
                break;
            }
            start = end + 1;
        }
        return sb.toString();
    }

    /** {@code 位置=番号} 1件を {@code 位置=出所} にして足す。追跡できなければ足さない */
    private void appendEntry(StringBuilder sb, String args, int from, int to, int depth) {
        int eq = args.indexOf('=', from);
        if (eq < 0 || eq >= to) {
            return;
        }
        String origin = render(ValueNode.intOf(args.substring(eq + 1, to), ValueNode.NONE), depth);
        if (origin == null) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(';');
        }
        sb.append(args, from, eq).append('=').append(Origin.nest(origin));
    }

    private String render(int id, int depth) {
        if (id < 0 || id >= nodes.size()) {
            return null;
        }
        String cached = memo.get(id);
        if (cached != null) {
            return cached;
        }
        ValueNode n = nodes.get(id);
        String head = Origin.of(n.kind(), n.value());
        if (depth >= MAX_DEPTH) {
            return head;
        }
        if (n.kind() != Origin.NEW && n.kind() != Origin.RETURN) {
            memo.put(id, head);
            return head;
        }
        String result = withArgs(n, head, depth);
        if (result.length() != head.length()) {
            // 予算を使い切って頭だけにした結果は覚えない（別の場所では収まるかもしれない）
            memo.put(id, result);
        }
        return result;
    }

    /**
     * 呼び出しの形のノード（new とメソッド呼び出し）に実引数リストを付ける。
     *
     * 書き手が作っていた形をそのまま再現する。new には実引数の数を付けず、
     * メソッド呼び出しには {@code n=実引数の数} と {@code r=レシーバの出所} を付ける
     * （数は「出所が分からず省いた引数」と「引数が無い」を区別するために要る）
     */
    private String withArgs(ValueNode n, String head, int depth) {
        String args = argList(n.args(), depth + 1);
        if (n.kind() == Origin.NEW) {
            return Origin.of(Origin.NEW, n.value(), args);
        }
        if (n.argCount() >= 0) {
            // 実引数の数は「出所が分からず省いた引数」と「引数が無い」を区別するために付ける。
            // -1 は「呼び出しの形になっていない」（ローカル変数の先読みで頭だけ分かった場合など）で、
            // 数が分からないのだから付けない
            String count = Origin.ARG_COUNT + "=" + n.argCount();
            args = args.isEmpty() ? count : args + ";" + count;
        }
        if (n.recv() != ValueNode.NONE) {
            String recv = render(n.recv(), depth + 1);
            if (recv != null) {
                args = args + ";" + Origin.RECEIVER + "=" + Origin.nest(recv);
            }
        }
        if (!n.staticRecv().isEmpty()) {
            // ソースに書かれたレシーバの型。宣言元と違うときだけ入っている
            args = args.isEmpty() ? Origin.STATIC_RECV + "=" + n.staticRecv()
                    : args + ";" + Origin.STATIC_RECV + "=" + n.staticRecv();
        }
        return (head.length() + args.length() > BUDGET) ? head : Origin.of(Origin.RETURN, n.value(), args);
    }

    /** 実引数リストを組む。予算を使い切ったらそれ以上は展開しない */
    private String argList(String args, int depth) {
        if (args == null || args.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int start = 0;
        while (start <= args.length()) {
            int end = args.indexOf(ValueNode.ARG_SEP, start);
            if (end < 0) {
                end = args.length();
            }
            if (sb.length() > BUDGET) {
                break;
            }
            appendEntry(sb, args, start, end, depth);
            if (end >= args.length()) {
                break;
            }
            start = end + 1;
        }
        return sb.toString();
    }
}
