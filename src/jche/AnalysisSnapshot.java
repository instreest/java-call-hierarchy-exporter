// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche;

import java.time.LocalDateTime;

import jche.config.Config;
import jche.config.ProjectLayout;
import jche.graph.CallGraph;
import jche.graph.CallResolver;
import jche.graph.InboundIndex;

/**
 * ある時点の解析結果ひとそろい。作ったあとは書き換えない。
 *
 * <p>CLI はこれを作ってすぐ CSV を書き（{@link Exporter}）、Eclipse プラグインは
 * これをメモリに置いたまま画面から何度も読む。書き換えないので、読む側はロックを取らなくてよい。
 * 解析し直したときは、この入れ物ごと新しいものに差し替える。
 *
 * <p>{@link #inbound()} は呼び出し元をたどるための転置索引で、要求されたときに1度だけ作る
 * （CSV 出力では使わないため、CLI では作られない）。
 */
public final class AnalysisSnapshot {

    private final Config config;
    private final ProjectLayout layout;
    private final CallGraph graph;
    private final CallResolver resolver;
    private final LocalDateTime analyzedAt;
    private volatile InboundIndex inbound;

    AnalysisSnapshot(Config config, ProjectLayout layout, CallGraph graph, CallResolver resolver) {
        this.config = config;
        this.layout = layout;
        this.graph = graph;
        this.resolver = resolver;
        this.analyzedAt = LocalDateTime.now();
    }

    public Config config() {
        return config;
    }

    public ProjectLayout layout() {
        return layout;
    }

    public CallGraph graph() {
        return graph;
    }

    public CallResolver resolver() {
        return resolver;
    }

    /** この結果を作り終えた時刻。画面に「いつ時点か」を出すために使う */
    public LocalDateTime analyzedAt() {
        return analyzedAt;
    }

    /**
     * 呼び出し元をたどるための転置索引。初回の呼び出しで作る（グラフ全体を2回走査する）。
     *
     * <p>二重に作られても結果は同じで、どちらか一方だけが残る。作りかけが見えることは無いので、
     * ロックはかけていない（作るのは数百ミリ秒で、同時に呼ばれること自体がまれ）。
     */
    public InboundIndex inbound() {
        InboundIndex local = inbound;
        if (local == null) {
            local = InboundIndex.build(graph, resolver);
            inbound = local;
        }
        return local;
    }
}
