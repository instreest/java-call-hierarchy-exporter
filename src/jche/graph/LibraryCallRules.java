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
import jche.extension.RuleProvider;
import jche.util.Log;
import jche.util.Messages;

/**
 * ライブラリ呼び出し規則の読み込み。同梱の表・設定ファイルで足した表・拡張が返す表を1つにまとめ、
 * 呼び戻し（{@link CallbackRules}）・入口（{@link FrameworkEntries}）・
 * 具象型（{@link TypeRules}）に振り分ける。
 *
 * 行の形で振り分ける。{@code =>} を含む行は具象型、{@code ->} を含む行は呼び戻し、
 * {@code @} / {@code super} / {@code static} で始まる行は入口。どれでも読めない行は、
 * 設定したのに効いていないことに気づけるよう警告に出す。
 *
 * {@code =>} と {@code ->} は別の綴りなので取り違えない（{@code "=>"} は {@code "->"} を含まない）。
 * 順に見るとき {@code =>} を先に判定するのは、将来どちらも含む行を許したくなったときに
 * 迷わないようにするため。
 */
public final class LibraryCallRules {

    /** 読み込んだ3つの表 */
    public record Loaded(CallbackRules callbacks, FrameworkEntries entries, TypeRules types) {
    }

    private LibraryCallRules() {
    }

    public static Loaded load(Config config, CallGraph graph, DataflowResolver dataflow) {
        List<RuleUsage.Line> callbackLines = new ArrayList<>();
        List<RuleUsage.Line> entryLines = new ArrayList<>();
        // 具象型（種類 C）に同梱の行は無い。フレームワークごとの DI の既定を同梱するかは
        // まだ決めていない（docs/call-rules-unification-design.md の §10）
        List<RuleUsage.Line> typeLines = new ArrayList<>();
        if (config.builtinRules) {
            for (String line : JdkCallbacks.LINES) {
                callbackLines.add(new RuleUsage.Line(line, RuleUsage.bundledOrigin(), true));
            }
            for (String line : BundledFrameworkEntries.LINES) {
                entryLines.add(new RuleUsage.Line(line, RuleUsage.bundledOrigin(), true));
            }
        }
        for (Path file : config.ruleFiles) {
            List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                // 表が読めないと「設定したのに効いていない」状態になる。黙らず知らせる
                Log.warn(Messages.format("graph.rules.unreadable", file, e));
                continue;
            }
            int n = sort(lines, callbackLines, entryLines, typeLines, file.toString());
            Log.info(Messages.format("graph.rules.loaded", file, n));
        }
        for (RuleProvider provider : Plugins.load(config, config.ruleProviderClasses,
                RuleProvider.class)) {
            List<String> lines = provider.lines();
            int n = sort((lines == null) ? List.of() : lines, callbackLines, entryLines, typeLines,
                    provider.getClass().getName());
            Log.info(Messages.format("graph.rules.fromExtension", provider.getClass().getName(), n));
        }
        TypeRules types = new TypeRules(new RuleUsage(typeLines), graph.typeNames());
        if (types.hasFactoryRows() && !dataflow.enabled()) {
            // キーはデータフローの値グラフから引くので、切られていると永久に当たらない
            Log.warn(Messages.get("graph.rules.factoryNeedsDataflow"));
        }
        return new Loaded(new CallbackRules(graph, dataflow, new RuleUsage(callbackLines)),
                new FrameworkEntries(graph, new RuleUsage(entryLines)), types);
    }

    /**
     * 行を種類ごとに振り分ける。読めた行数を返す。
     *
     * @param from この行の出所（ライブラリ呼び出し規則のパス、または拡張のクラス名）。読めない行の警告と、
     *             一度も当たらなかった行の報告（{@link RuleUsage}）に使う
     */
    private static int sort(List<String> lines, List<RuleUsage.Line> callbacks,
                            List<RuleUsage.Line> entries, List<RuleUsage.Line> types,
                            String from) {
        int count = 0;
        for (String raw : lines) {
            String line = (raw == null) ? "" : raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.contains("=>")) {
                if (TypeRules.parse(line) == null) {
                    // よくある書き間違いには助言を添える。綴りを疑って時間を使わせないため
                    Log.warn(Messages.format("graph.rules.badRow", from, line)
                            + (TypeRules.hasArguments(line)
                                    ? Messages.get("graph.rules.badRowHint") : ""));
                    continue;
                }
                types.add(new RuleUsage.Line(line, from, false));
            } else if (line.contains("->")) {
                if (CallbackRules.parse(line) == null) {
                    Log.warn(Messages.format("graph.rules.badRow", from, line));
                    continue;
                }
                callbacks.add(new RuleUsage.Line(line, from, false));
            } else {
                if (FrameworkEntries.parse(line) == null) {
                    Log.warn(Messages.format("graph.rules.badRow", from, line));
                    continue;
                }
                entries.add(new RuleUsage.Line(line, from, false));
            }
            count++;
        }
        return count;
    }
}
