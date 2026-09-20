// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import jche.util.RunControl;
import jche.util.Messages;

/**
 * 「このメソッドを呼んでいるのは誰か」を引くための転置索引（CSR形式）。
 *
 * <p>{@link CallGraph} は呼び出し先の向き（out）しか持たない。CSV 出力は起点から下流へ辿るだけで
 * 済むためだが、Eclipse プラグインの呼び出し元階層はこの逆向きが要る。
 *
 * <p>転置するのは<b>解決後</b>のエッジである。宣言型のまま転置すると、インタフェース経由でしか
 * 呼ばれない実装メソッド（DAO 実装など）の呼び出し元が空になり、まさに知りたいものが出なくなる。
 * {@link CallResolver#resolve(int)} が返す候補すべてに対して 1 本ずつ辺を張る
 * （1つの呼び出しが複数の実装に解決されることがあるので、辺はエッジ数より多くなりうる）。
 *
 * <p>持ち方は {@link CallGraph} と同じ CSR で、メソッドあたり int 1個、辺あたり int 2個
 * （呼び出し元のメソッドIDと、元のエッジ番号）。エッジ番号を持つのは、呼び出している行や
 * 束縛の種別（{@link BindKind}）を後から引くため。
 */
public final class InboundIndex {

    /**
     * 進捗の見出し。{@code static final} にしないのは、そうするとこのクラスが読まれた時点の
     * 言語で固まってしまうため（{@code docs/nls-qa.md} の Q2）
     */
    private static String progressLabel() {
        return Messages.get("graph.progress.inbound");
    }

    private final int[] offsets;    // 長さ methodCount + 1
    private final int[] callerIds;  // 長さ = 辺の数
    private final int[] edgeIndexes;

    private InboundIndex(int[] offsets, int[] callerIds, int[] edgeIndexes) {
        this.offsets = offsets;
        this.callerIds = callerIds;
        this.edgeIndexes = edgeIndexes;
    }

    /**
     * 解決後のエッジから転置索引を作る。グラフ全体の走査は<b>1回だけ</b>で、
     * 拾った (呼び出し先, 呼び出し元, エッジ) の3つ組を計数ソートで CSR に並べ替える。
     *
     * <p>「1回目に本数を数えて、2回目に書き込む」という作りにしていないのは、
     * 数える回と書く回が同じ結果を見ることを構造として保証するためである。
     * かつて {@link CallResolver#resolve(int)} は初回と2回目で答えが変わることがあり
     * （データフローの解決に使う情報が、他のエッジを解決する過程で埋まっていた）、
     * 2回走査すると本数が食い違って CSR の並びが静かに壊れた。
     * いまはデータフローの事実を解決より前に確定させる（{@link jche.dataflow.DataflowBuilder}）ので
     * resolve は決定的だが、ここが壊れると原因が分かりにくいため、1回走査のままにしてある。
     *
     * @param graph    構築済みの呼び出しグラフ
     * @param resolver 具象クラスの解決。ここで解決した先に対して辺を張る
     */
    public static InboundIndex build(CallGraph graph, CallResolver resolver) {
        int methodCount = graph.methodCount();
        IntArray targets = new IntArray(1 << 12);
        IntArray callers = new IntArray(1 << 12);
        IntArray edges = new IntArray(1 << 12);
        // 解析サーバーはこの索引を解析の最後に必ず作る。メソッド数が多いと数分かかるので、
        // 中止の受け付けと同じ間隔で進捗も出す（docs/eclipse-plugin-progress-log-qa.md）
        RunControl.progress(progressLabel(), 0, methodCount);
        for (int caller = 0; caller < methodCount; caller++) {
            if ((caller & 0xFFF) == 0) {
                RunControl.checkCancelled();
                RunControl.progress(progressLabel(), caller, methodCount);
            }
            for (int e = graph.edgeStart(caller); e < graph.edgeEnd(caller); e++) {
                for (int t : resolver.resolve(e).targets()) {
                    if (t < 0 || t >= methodCount) {
                        continue;   // 解決の過程で増えたメソッド。この索引の対象外
                    }
                    targets.add(t);
                    callers.add(caller);
                    edges.add(e);
                }
            }
        }

        RunControl.progress(progressLabel(), methodCount, methodCount);
        int total = targets.size();
        int[] offsets = new int[methodCount + 1];
        for (int i = 0; i < total; i++) {
            offsets[targets.get(i) + 1]++;
        }
        for (int i = 0; i < methodCount; i++) {
            offsets[i + 1] += offsets[i];
        }
        int[] callerIds = new int[total];
        int[] edgeIndexes = new int[total];
        int[] cursor = offsets.clone();
        for (int i = 0; i < total; i++) {
            int at = cursor[targets.get(i)]++;
            callerIds[at] = callers.get(i);
            edgeIndexes[at] = edges.get(i);
        }
        return new InboundIndex(offsets, callerIds, edgeIndexes);
    }

    /** このメソッドを呼んでいる辺の数 */
    public int inDegree(int methodId) {
        return offsets[methodId + 1] - offsets[methodId];
    }

    /** 辺の範囲の始まり（{@link #callerAt} / {@link #edgeAt} に渡す添字） */
    public int start(int methodId) {
        return offsets[methodId];
    }

    /** 辺の範囲の終わり（この値は含まない） */
    public int end(int methodId) {
        return offsets[methodId + 1];
    }

    /** その辺の呼び出し元メソッドID */
    public int callerAt(int index) {
        return callerIds[index];
    }

    /** その辺のもとになったエッジ番号（呼び出している行や束縛の種別を引くのに使う） */
    public int edgeAt(int index) {
        return edgeIndexes[index];
    }

    /** 辺の総数（解決後の呼び出し関係の本数） */
    public int size() {
        return callerIds.length;
    }
}
