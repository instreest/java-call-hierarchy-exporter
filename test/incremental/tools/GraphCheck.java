// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import jche.graph.CallGraph;
import jche.graph.CallGraphBuilder;
import jche.graph.SpringBeans;

/**
 * グラフの構築（jche.graph.CallGraphBuilder）が、最後まで書き終えていないキャッシュ・別の版のキャッシュを
 * 読んだときに、呼び出しの欠けたグラフを黙って組まずに止めること（test/incremental/run.sh）。
 *
 * <pre>
 *   java GraphCheck &lt;完成した analysis-cache.tsv&gt; &lt;壊したものを置くフォルダ&gt;
 * </pre>
 * 同じキャッシュのフォルダを 2 つの実行で使うと、片方が書きかけの一時ファイルを本物に差し替えることがあった
 * （docs/cache-unification-qa.md の Q56）。その形（途中で切れた・最終行が無い・最終行の数が合わない・版が違う）を
 * 手で作って読ませる。完成したキャッシュは組めること、壊したものはどれも例外になることを見る。
 * 結果は 1 行ずつ「OK」「NG」で出し、NG があれば終了コード 1。
 */
public final class GraphCheck {

    private GraphCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path cache = Paths.get(args[0]);
        Path dir = Paths.get(args[1]);
        Files.createDirectories(dir);
        List<String> lines = Files.readAllLines(cache, StandardCharsets.UTF_8);
        int fail = 0;

        CallGraph whole = CallGraphBuilder.build(cache, true, List.of(), SpringBeans.DISABLED);
        if (whole.edgeCount() > 0) {
            System.out.println("  OK   完成したキャッシュからグラフを組める（エッジ " + whole.edgeCount() + " 本）");
        } else {
            System.out.println("  NG   完成したキャッシュからエッジが 1 本もできません");
            fail++;
        }

        List<String> half = new ArrayList<>(lines.subList(0, lines.size() / 2));
        fail += expectRejected(dir.resolve("half.tsv"), half, "途中で切れた（書きかけの一時ファイルと同じ形）");

        List<String> noTrailer = new ArrayList<>(lines.subList(0, lines.size() - 1));
        fail += expectRejected(dir.resolve("notrailer.tsv"), noTrailer, "最終行（Z 行）が無い");

        List<String> wrongCount = new ArrayList<>(lines);
        wrongCount.set(wrongCount.size() - 1, "Z\t99999");
        fail += expectRejected(dir.resolve("count.tsv"), wrongCount, "最終行のブロック数が合わない");

        List<String> afterTrailer = new ArrayList<>(lines);
        afterTrailer.addAll(lines.subList(1, lines.size()));
        fail += expectRejected(dir.resolve("twice.tsv"), afterTrailer, "最終行の後ろにブロックが続く（2 つの書き手が重なった）");

        List<String> otherVersion = new ArrayList<>(lines);
        otherVersion.set(0, otherVersion.get(0).replaceFirst("^jche-cache-v[0-9]+", "jche-cache-v1"));
        fail += expectRejected(dir.resolve("version.tsv"), otherVersion, "形式の版が違う");

        System.exit(fail == 0 ? 0 : 1);
    }

    /** 壊したキャッシュを書いて組ませ、例外になれば 0、組めてしまえば NG を出して 1 */
    private static int expectRejected(Path file, List<String> lines, String label) throws IOException {
        Files.write(file, lines, StandardCharsets.UTF_8);
        try {
            CallGraph g = CallGraphBuilder.build(file, true, List.of(), SpringBeans.DISABLED);
            System.out.println("  NG   " + label + "キャッシュからグラフを組んでしまいました（エッジ " + g.edgeCount()
                    + " 本。出力が黙って欠ける）");
            return 1;
        } catch (IOException e) {
            System.out.println("  OK   " + label + "キャッシュは組まずに止める: " + e.getMessage());
            return 0;
        }
    }
}
