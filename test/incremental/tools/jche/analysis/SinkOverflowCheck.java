// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jche.analysis.CallEdgeExtractor.SourceFile;
import jche.cache.FileAnalysis;
import jche.config.Config;
import jche.config.ProjectLayout;

/**
 * 受け手（{@link CallEdgeExtractor.Sink#accept}）の中でスタックが溢れても、そのファイルが失敗として数えられ、
 * ほかのファイルの解析が続くこと（test/incremental/run.sh の「受け手の中で溢れたファイル」。
 * docs/cache-unification-qa.md の Q69）。
 *
 * <pre>
 *   java jche.analysis.SinkOverflowCheck &lt;設定ファイル&gt; &lt;溢れさせるファイルの相対パス&gt; [&lt;深い式のファイルの相対パス&gt;]
 * </pre>
 * 設定のソースをすべて（ソースフォルダの並びのまま）1 つのバッチで {@link CallEdgeExtractor#analyzeBatch} に渡し、
 * 受け手は 2 つ目の引数のファイルを受け取ったときに {@link StackOverflowError} を投げる。3 つ目の引数は JDT 自身が
 * 溢れる深い式のファイルで、先に並ぶソースフォルダに置くと、一括パースが途中で溢れて残りを 1 ファイルずつ解析する
 * 経路の受け手でも同じことを見る（そのファイル自身は失敗になる）。
 * どのファイルも「受け取った」か「失敗した」のちょうど 1 回だけ数えられ、溢れさせたファイルは失敗で、
 * 例外が外へ抜けないこと。終了コードは NG の数。
 */
public final class SinkOverflowCheck {

    private SinkOverflowCheck() {
    }

    public static void main(String[] args) throws Exception {
        Config config = new Config(Paths.get(args[0]), Paths.get("").toAbsolutePath(), LocalDateTime.now());
        String overflowAt = args[1];
        String deep = (args.length > 2) ? args[2] : null;
        ProjectLayout layout = new ProjectLayout(config);
        List<SourceFile> files = new ArrayList<>();
        for (Path p : layout.listJavaFiles()) {
            files.add(new SourceFile(p, layout.relativeOf(p), p.toFile().length()));
        }
        Map<String, String> outcome = new LinkedHashMap<>();
        int ng = 0;
        CallEdgeExtractor.Sink sink = new CallEdgeExtractor.Sink() {
            @Override
            public void accept(SourceFile file, FileAnalysis analysis) throws IOException {
                if (file.relativePath().equals(overflowAt)) {
                    throw new StackOverflowError("injected by SinkOverflowCheck");
                }
                outcome.merge(file.relativePath(), "accepted", (a, b) -> a + "+" + b);
            }

            @Override
            public void failed(SourceFile file, Exception error) {
                outcome.merge(file.relativePath(), "failed", (a, b) -> a + "+" + b);
            }
        };
        String label = (deep == null) ? "一括パースの受け手" : "1 ファイルずつの解析の受け手";
        try {
            new CallEdgeExtractor(layout, config).analyzeBatch(files, sink);
        } catch (Throwable t) {
            System.out.println("  NG   " + label + "で溢れたら、例外が外へ抜けました: " + t);
            ng++;
        }
        for (SourceFile f : files) {
            String got = outcome.get(f.relativePath());
            String want = (f.relativePath().equals(overflowAt) || f.relativePath().equals(deep))
                    ? "failed" : "accepted";
            if (want.equals(got)) {
                System.out.println("  OK   " + label + ": " + f.relativePath() + " は " + got);
            } else {
                System.out.println("  NG   " + label + ": " + f.relativePath() + " は " + want + " のはずが "
                        + ((got == null) ? "どちらにも数えられていない（黙って消えた）" : got));
                ng++;
            }
        }
        System.exit(ng);
    }
}
