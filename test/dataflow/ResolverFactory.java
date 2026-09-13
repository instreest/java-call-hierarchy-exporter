// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0

import java.util.List;

import jche.config.Config;
import jche.dataflow.DataflowBuilder;
import jche.dataflow.DataflowFacts;
import jche.graph.CallGraph;
import jche.graph.CallResolver;
import jche.graph.DataflowResolver;

/** 検査用: CLI（CallHierarchyExporter）と同じ組み立てで CallResolver を作る */
final class ResolverFactory {
    private ResolverFactory() {
    }

    static CallResolver create(CallGraph graph, Config config) {
        DataflowFacts facts = DataflowBuilder.build(graph, config.dataflowEnabled);
        return new CallResolver(graph,
                new DataflowResolver(graph, facts, config.dataflowEnabled, config.dataflowMaxDepth),
                List.of());
    }
}
