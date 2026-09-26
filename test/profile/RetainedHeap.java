// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;

import jche.AnalysisSnapshot;
import jche.Exporter;
import jche.config.Config;

/**
 * 解析結果（フェーズ 2 の終わりの {@link AnalysisSnapshot}。呼び出しグラフ・値の表・データフローの事実・resolver）が
 * 持ち続けるヒープを測る。合否の検査ではない（CI では動かさない）。
 *
 * <pre>
 *   javac -cp &lt;classes&gt;:&lt;jdtの依存&gt; -d &lt;out&gt; test/profile/RetainedHeap.java
 *   java -XX:+UseSerialGC -cp &lt;out&gt;:&lt;classes&gt;:&lt;jdtの依存&gt; RetainedHeap &lt;設定ファイル&gt; [回数]
 * </pre>
 *
 * 1 回目の解析でキャッシュを作って捨て、その後キャッシュを再利用する解析を「回数」（既定 3）だけ繰り返す。
 * 毎回、解析結果を持ったまま完全 GC で落ち着かせた使用量と、手放してから同じく落ち着かせた使用量の差を取り、
 * その中央値を出す。{@code Log.heap()} の数字（回収前のものを含む）や GC ログの差の足し上げと違い、
 * 生きている量そのものなので、版を変えて比べられる（{@code docs/cache-unification-qa.md} の Q17）。
 *
 * <p>注意: 手放したものが解放されるのは GC 1〜2 回ぶん遅れることがある（ファイナライズを待つオブジェクトから
 * 辿れるものは、その次の GC まで残る）。1 回だけ GC して読むと差が 0 になるので、毎回 8 回かける。
 * {@code -XX:+UseSerialGC} で {@code System.gc()} を確実に完全 GC にする。
 */
public final class RetainedHeap {

    private RetainedHeap() {
    }

    /** 完全 GC を何度もかけて落ち着かせたヒープの使用量 */
    private static long settled() throws InterruptedException {
        for (int i = 0; i < 8; i++) {
            System.gc();
            Thread.sleep(60);
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    public static void main(String[] args) throws Exception {
        Path cfg = Path.of(args[0]).toAbsolutePath();
        int reps = (args.length > 1) ? Integer.parseInt(args[1]) : 3;
        Path root = Path.of("").toAbsolutePath();
        // 1 回目: キャッシュを作る（フェーズ 1）。結果は捨てる
        AnalysisSnapshot s = Exporter.analyze(new Config(cfg, root, LocalDateTime.now()));
        s = null;
        long[] retained = new long[reps];
        int edges = 0;
        int methods = 0;
        for (int rep = 0; rep < reps; rep++) {
            s = Exporter.analyze(new Config(cfg, root, LocalDateTime.now()));
            long with = settled();
            edges = s.graph().edgeCount();
            methods = s.graph().methodCount();
            s = null;
            retained[rep] = with - settled();
        }
        long[] sorted = retained.clone();
        Arrays.sort(sorted);
        System.out.println("retained=" + sorted[reps / 2] + " B（各回 " + Arrays.toString(retained) + "） edges=" + edges
                + " methods=" + methods);
    }
}
