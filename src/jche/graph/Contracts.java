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

/**
 * 契約表の読み込み。同梱の表・設定ファイルで足した表・拡張が返す表を1つにまとめ、
 * 呼び戻し（{@link CallbackContracts}）と入口（{@link FrameworkEntries}）に振り分ける。
 *
 * 行の形で振り分ける。{@code ->} を含む行は呼び戻し、{@code @} / {@code super} / {@code static} で
 * 始まる行は入口。どちらでも読めない行は、設定したのに効いていないことに気づけるよう警告に出す。
 */
public final class Contracts {

    /** 読み込んだ2つの表 */
    public record Loaded(CallbackContracts callbacks, FrameworkEntries entries) {
    }

    private Contracts() {
    }

    public static Loaded load(Config config, CallGraph graph, DataflowResolver dataflow) {
        List<String> callbackLines = new ArrayList<>();
        List<String> entryLines = new ArrayList<>();
        if (config.builtinContracts) {
            callbackLines.addAll(JdkCallbacks.LINES);
            entryLines.addAll(BundledFrameworkEntries.LINES);
        }
        for (Path file : config.contractFiles) {
            List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                // 表が読めないと「設定したのに効いていない」状態になる。黙らず知らせる
                Log.warn("契約表を読めません: " + file + " (" + e + ")。この表は使わずに続けます");
                continue;
            }
            int n = sort(lines, callbackLines, entryLines, file.toString());
            Log.info("契約表を読み込み: " + file + "（" + n + " 行）");
        }
        for (ContractProvider provider : Plugins.load(config, config.contractProviderClasses,
                ContractProvider.class)) {
            List<String> lines = provider.lines();
            int n = sort((lines == null) ? List.of() : lines, callbackLines, entryLines,
                    provider.getClass().getName());
            Log.info("契約を拡張から受け取り: " + provider.getClass().getName() + "（" + n + " 行）");
        }
        return new Loaded(new CallbackContracts(graph, dataflow, callbackLines),
                new FrameworkEntries(graph, entryLines));
    }

    /** 行を種類ごとに振り分ける。読めた行数を返す */
    private static int sort(List<String> lines, List<String> callbacks, List<String> entries,
                            String from) {
        int count = 0;
        for (String raw : lines) {
            String line = (raw == null) ? "" : raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.contains("->")) {
                if (CallbackContracts.parse(line) == null) {
                    Log.warn("契約の行を読めません（" + from + "）: " + line);
                    continue;
                }
                callbacks.add(line);
            } else {
                if (FrameworkEntries.parse(line) == null) {
                    Log.warn("契約の行を読めません（" + from + "）: " + line);
                    continue;
                }
                entries.add(line);
            }
            count++;
        }
        return count;
    }
}
