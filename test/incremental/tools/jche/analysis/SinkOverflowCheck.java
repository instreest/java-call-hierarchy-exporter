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
 *   java jche.analysis.SinkOverflowCheck &lt;設定ファイル&gt; &lt;溢れさせるファイルの相対パス&gt; [&lt;深い式のファイルの相対パス&gt; | --alone]
 * </pre>
 * 設定のソースをすべて（ソースフォルダの並びのまま）1 つのバッチで {@link CallEdgeExtractor#analyzeBatch} に渡し、
 * 受け手は 2 つ目の引数のファイルを受け取ったときに {@link StackOverflowError} を投げる。3 つ目の引数は JDT 自身が
 * 溢れる深い式のファイルで、ソースフォルダの並びでほかのファイルより前に置くと一括パースがどのファイルも渡さないうちに溢れ
 * （バッチを半分ずつに分けて解析し直す経路）、後ろに置くと溢れる前に受け取ったファイルがある（それをそれだけで解析し直し、
 * 溢れたファイルを脇に置いて最後に 1 つだけで解析する経路）。どちらの経路の受け手でも同じことを見る（深いファイル自身は
 * 失敗になる）。3 つ目の引数が {@code --alone} なら、どのファイルも 1 ファイルずつの解析（{@code CallEdgeExtractor#analyzeAlone}。
 * 脇に置いたファイルを最後に解析する経路）で解析し、その受け手でも同じことを見る。
 * どのファイルも「受け取った」か「失敗した」のちょうど 1 回だけ数えられ、溢れさせたファイルは失敗で、
 * 例外が外へ抜けないこと。終了コードは NG の数。
 *
 * <p>システムプロパティ {@code check.blank} に相対パスを渡すと、受け手はそのファイルで文言の無い例外
 * （{@code new IllegalStateException()}）を投げる。そのファイルも失敗として数え、失敗の理由（warnings.txt に載る文言）が
 * 空でなく例外の名前を含むことを見る（JDT の打ち切りの例外 {@code AbortCompilation} も文言を持たず、以前は
 * warnings.txt の行が「()」だけになっていた）。どの失敗の理由も空でないことは、いつも見る。
 */
public final class SinkOverflowCheck {

    private SinkOverflowCheck() {
    }

    public static void main(String[] args) throws Exception {
        Config config = new Config(Paths.get(args[0]), Paths.get("").toAbsolutePath(), LocalDateTime.now());
        String overflowAt = args[1];
        boolean alone = args.length > 2 && args[2].equals("--alone");
        String deep = (args.length > 2 && !alone) ? args[2] : null;
        String blankAt = System.getProperty("check.blank");
        ProjectLayout layout = new ProjectLayout(config);
        List<SourceFile> files = new ArrayList<>();
        for (Path p : layout.listJavaFiles()) {
            files.add(new SourceFile(p, layout.relativeOf(p), p.toFile().length()));
        }
        Map<String, String> outcome = new LinkedHashMap<>();
        Map<String, String> reasons = new LinkedHashMap<>();
        int ng = 0;
        CallEdgeExtractor.Sink sink = new CallEdgeExtractor.Sink() {
            @Override
            public void accept(SourceFile file, FileAnalysis analysis) throws IOException {
                if (file.relativePath().equals(overflowAt)) {
                    throw new StackOverflowError("injected by SinkOverflowCheck");
                }
                if (file.relativePath().equals(blankAt)) {
                    throw new IllegalStateException();
                }
                outcome.merge(file.relativePath(), "accepted", (a, b) -> a + "+" + b);
            }

            @Override
            public void failed(SourceFile file, Exception error) {
                outcome.merge(file.relativePath(), "failed", (a, b) -> a + "+" + b);
                reasons.put(file.relativePath(), String.valueOf(error.getMessage()));
            }
        };
        String label = alone ? "1 ファイルずつの解析の受け手"
                : (deep == null) ? "一括パースの受け手" : "一括パースが溢れたあとに解析し直す経路の受け手";
        try {
            CallEdgeExtractor extractor = new CallEdgeExtractor(layout, config);
            extractor.prepare(files);   // 本番（CacheUpdater）と同じく、添えるファイル・関わるファイルの材料を読む
            if (alone) {
                for (SourceFile f : files) {
                    extractor.analyzeAlone(f, sink);
                }
            } else {
                extractor.analyzeBatch(files, sink);
            }
        } catch (Throwable t) {
            System.out.println("  NG   " + label + "で溢れたら、例外が外へ抜けました: " + t);
            ng++;
        }
        for (SourceFile f : files) {
            String got = outcome.get(f.relativePath());
            String want = (f.relativePath().equals(overflowAt) || f.relativePath().equals(deep)
                    || f.relativePath().equals(blankAt)) ? "failed" : "accepted";
            if (want.equals(got)) {
                System.out.println("  OK   " + label + ": " + f.relativePath() + " は " + got);
            } else {
                System.out.println("  NG   " + label + ": " + f.relativePath() + " は " + want + " のはずが "
                        + ((got == null) ? "どちらにも数えられていない（黙って消えた）" : got));
                ng++;
            }
        }
        for (Map.Entry<String, String> e : reasons.entrySet()) {
            String reason = e.getValue();
            boolean named = !e.getKey().equals(blankAt) || reason.contains(IllegalStateException.class.getName());
            if (reason.isBlank() || reason.equals("null") || !named) {
                System.out.println("  NG   " + label + ": " + e.getKey() + " の失敗の理由が空か、例外の名前を含みません: " + reason);
                ng++;
            } else {
                System.out.println("  OK   " + label + ": " + e.getKey() + " の失敗の理由が空でない"
                        + (e.getKey().equals(blankAt) ? "（文言の無い例外でも、例外の名前を添える）" : ""));
            }
        }
        System.exit(ng);
    }
}
