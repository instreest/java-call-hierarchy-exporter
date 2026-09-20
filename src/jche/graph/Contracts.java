// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import jche.config.Config;
import jche.config.Plugins;
import jche.extension.ContractProvider;
import jche.util.Log;
import jche.util.Messages;

/**
 * 契約表の読み込み。同梱の表・設定ファイルで足した表・拡張が返す表を1つにまとめ、
 * 呼び戻し（{@link CallbackContracts}）・入口（{@link FrameworkEntries}）・
 * 具象型（{@link TypeContracts}）に振り分ける。
 *
 * 行の形で振り分ける。{@code =>} を含む行は具象型、{@code ->} を含む行は呼び戻し、
 * {@code @} / {@code super} / {@code static} で始まる行は入口。どれでも読めない行は、
 * 設定したのに効いていないことに気づけるよう警告に出す。
 *
 * {@code =>} と {@code ->} は別の綴りなので取り違えない（{@code "=>"} は {@code "->"} を含まない）。
 * 順に見るとき {@code =>} を先に判定するのは、将来どちらも含む行を許したくなったときに
 * 迷わないようにするため。
 */
public final class Contracts {

    /** 読み込んだ3つの表 */
    public record Loaded(CallbackContracts callbacks, FrameworkEntries entries, TypeContracts types) {
    }

    private Contracts() {
    }

    public static Loaded load(Config config, CallGraph graph, DataflowResolver dataflow) {
        List<ContractUsage.Line> callbackLines = new ArrayList<>();
        List<ContractUsage.Line> entryLines = new ArrayList<>();
        // 具象型（種類 C）に同梱の行は無い。フレームワークごとの DI の既定を同梱するかは
        // まだ決めていない（docs/contracts-unification-design.md の §10）
        List<ContractUsage.Line> typeLines = new ArrayList<>();
        if (config.builtinContracts) {
            for (String line : JdkCallbacks.LINES) {
                callbackLines.add(new ContractUsage.Line(line, ContractUsage.bundledOrigin(), true));
            }
            for (String line : BundledFrameworkEntries.LINES) {
                entryLines.add(new ContractUsage.Line(line, ContractUsage.bundledOrigin(), true));
            }
        }
        for (Path file : config.contractFiles) {
            List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                // 表が読めないと「設定したのに効いていない」状態になる。黙らず知らせる
                Log.warn(Messages.format("graph.contracts.unreadable", file, e));
                continue;
            }
            int n = sort(lines, callbackLines, entryLines, typeLines, file.toString());
            Log.info(Messages.format("graph.contracts.loaded", file, n));
        }
        for (ContractProvider provider : Plugins.load(config, config.contractProviderClasses,
                ContractProvider.class)) {
            List<String> lines = provider.lines();
            int n = sort((lines == null) ? List.of() : lines, callbackLines, entryLines, typeLines,
                    provider.getClass().getName());
            Log.info(Messages.format("graph.contracts.fromExtension", provider.getClass().getName(), n));
        }
        TypeContracts types = new TypeContracts(new ContractUsage(typeLines), graph.typeNames());
        if (types.hasFactoryRows() && !dataflow.enabled()) {
            // キーはデータフローの値グラフから引くので、切られていると永久に当たらない
            Log.warn(Messages.get("graph.contracts.factoryNeedsDataflow"));
        }
        return new Loaded(new CallbackContracts(graph, dataflow, new ContractUsage(callbackLines)),
                new FrameworkEntries(graph, new ContractUsage(entryLines)), types);
    }

    /**
     * 行を種類ごとに振り分ける。読めた行数を返す。
     *
     * @param from この行の出所（契約表のパス、または拡張のクラス名）。読めない行の警告と、
     *             一度も当たらなかった行の報告（{@link ContractUsage}）に使う
     */
    private static int sort(List<String> lines, List<ContractUsage.Line> callbacks,
                            List<ContractUsage.Line> entries, List<ContractUsage.Line> types,
                            String from) {
        int count = 0;
        for (String raw : lines) {
            String line = (raw == null) ? "" : raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.contains("=>")) {
                if (TypeContracts.parse(line) == null) {
                    // よくある書き間違いには助言を添える。綴りを疑って時間を使わせないため
                    Log.warn(Messages.format("graph.contracts.badRow", from, line)
                            + (TypeContracts.hasArguments(line)
                                    ? Messages.get("graph.contracts.badRowHint") : ""));
                    continue;
                }
                types.add(new ContractUsage.Line(line, from, false));
            } else if (line.contains("->")) {
                if (CallbackContracts.parse(line) == null) {
                    Log.warn(Messages.format("graph.contracts.badRow", from, line));
                    continue;
                }
                callbacks.add(new ContractUsage.Line(line, from, false));
            } else {
                if (FrameworkEntries.parse(line) == null) {
                    Log.warn(Messages.format("graph.contracts.badRow", from, line));
                    continue;
                }
                entries.add(new ContractUsage.Line(line, from, false));
            }
            count++;
        }
        return count;
    }
}
