// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.report;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jche.cache.UnresolvedCallFact;
import jche.graph.CallGraph;
import jche.graph.IntArray;
import jche.graph.UnresolvedCalls;

/**
 * 型解決に失敗した呼び出しを call-hierarchy.csv に書き出す。
 *
 * これらは呼び出し先の型が特定できていないため、呼び出し階層としては辿れない。
 * しかし「解決できなかったせいで階層から抜け落ちている」こと自体が
 * 重要な情報（依存jarの不足を示す）なので、静かに消さずに行として残す。
 *
 * <p>出す行（使える候補の無い U 行）は、グラフを組むスキャン（{@code CallGraphBuilder}）が
 * 一時ファイルに拾ってある（{@link UnresolvedCalls}）。ここではキャッシュを読み直さない。
 * 件数ぶんをヒープに載せないよう、ブロック 1 つ分ずつ一時ファイルから読んで書く。
 *
 * 行の並びは「ソースフォルダの宣言順 → ファイルの相対パス順 → ファイル内の出現順」に固定する
 * （methods.csv と同じ基準）。キャッシュのブロック順のまま出すと、差分更新で解析し直した
 * ファイルのブロックが先頭へ移るため、ファイルを1つ直すたびにこの節の行順が入れ替わってしまう。
 * 並べ方は、キャッシュをブロックの順に流しながら「次に出すべきファイル」のブロックはそのまま出し、
 * 順番がまだ来ていないファイルのブロックは順番が来るまで保留する、という手順そのもの
 * （同じパスのブロックが 2 つあるときの出る位置も、この手順で決まる）。保留するのはブロックの番号だけで、
 * 行は出すときに一時ファイルから読む。
 */
public final class UnresolvedReport {

    private UnresolvedReport() {
    }

    /**
     * @param calls グラフを組むときに拾った行。拾っていなければ null（何も書かない）
     * @return 書き出した行数
     */
    public static long write(CallGraph g, UnresolvedCalls calls, CallHierarchyCsvWriter out) throws IOException {
        if (calls == null || calls.rowCount() == 0) {
            return 0L;
        }
        // ブロック（F行）の相対パスを、出力したい順に並べて順位を振る
        String[] order = blockPathsInOutputOrder(g, calls);
        Map<String, Integer> rankOf = new HashMap<>();
        for (int i = 0; i < order.length; i++) {
            rankOf.put(order[i], i);
        }

        long rows = 0L;
        boolean[] finished = new boolean[order.length];          // そのファイルのブロックを読み終えたか
        Map<Integer, IntArray> pending = new HashMap<>();        // 順番待ちのファイルの、行のあるブロック
        int next = 0;            // 次に書き出すべきファイルの順位
        int currentRank = -1;    // 流している最中のブロックの順位
        int k = 0;               // 次に行のあるブロック（calls.blockOf(k)）
        for (int block = 0; block < calls.blockCount(); block++) {
            String path = calls.blockPath(block);
            boolean hasRows = k < calls.blocksWithRows() && calls.blockOf(k) == block;
            if (path == null) {
                // 最初の F 行より前の行。ファイルが分からないので、流れてきたとおりにそのまま出す
                if (hasRows) {
                    rows += emitBlock(g, out, null, calls, k++);
                }
                continue;
            }
            if (currentRank >= 0) {
                finished[currentRank] = true;
                while (next < order.length && finished[next]) {
                    rows += flush(g, out, order[next], calls, pending.remove(next));
                    next++;
                }
            }
            currentRank = rankOf.get(path);
            if (!hasRows) {
                continue;
            }
            if (currentRank <= next) {
                // 順番が来ているブロック（重複したブロックも溜めずにそのまま出す）
                rows += emitBlock(g, out, order[currentRank], calls, k);
            } else {
                pending.computeIfAbsent(currentRank, r -> new IntArray(2)).add(k);
            }
            k++;
        }
        // 流し終えた。残っている保留分を順位どおりに出す（順位の抜けは行の無いファイル）
        for (int i = next; i < order.length; i++) {
            rows += flush(g, out, order[i], calls, pending.remove(i));
        }
        return rows;
    }

    /**
     * ブロックの相対パスを、出力順（ソースフォルダの宣言順 → 相対パス順）に並べて返す（重複は除く）。
     * 相対パスは常に '/' 区切りで書かれているので、String の比較で OS によらず同じ順になる
     */
    private static String[] blockPathsInOutputOrder(CallGraph g, UnresolvedCalls calls) {
        List<String> paths = new ArrayList<>(calls.blockCount());
        for (int block = 0; block < calls.blockCount(); block++) {
            String path = calls.blockPath(block);
            if (path != null) {
                paths.add(path);
            }
        }
        String[] order = paths.stream().distinct().toArray(String[]::new);
        Arrays.sort(order, Comparator.comparingInt(g::sourceFolderIndexOf)
                .thenComparing(Comparator.naturalOrder()));
        return order;
    }

    /**
     * 保留していたブロックの行を書き出す。
     *
     * @return 書き出した行数
     */
    private static long flush(CallGraph g, CallHierarchyCsvWriter out, String file, UnresolvedCalls calls,
                              IntArray blocks) throws IOException {
        if (blocks == null) {
            return 0L;
        }
        long rows = 0L;
        for (int i = 0; i < blocks.size(); i++) {
            rows += emitBlock(g, out, file, calls, blocks.get(i));
        }
        return rows;
    }

    /**
     * 行のあるブロックのうち k 番目の行を書き出す
     *
     * @param file ブロックのファイル。分からなければ null
     * @return 書き出した行数
     */
    private static long emitBlock(CallGraph g, CallHierarchyCsvWriter out, String file, UnresolvedCalls calls,
                                  int k) throws IOException {
        long rows = 0L;
        for (UnresolvedCalls.Row u : calls.rowsOf(k)) {
            rows += emit(g, out, file, u);
        }
        return rows;
    }

    /**
     * 1行書き出す。
     *
     * @return 書き出した行数（1）
     */
    private static long emit(CallGraph g, CallHierarchyCsvWriter out, String currentFile, UnresolvedCalls.Row u)
            throws IOException {
        // 呼び出し元メソッドのキーはD行と同じ形式なので、そのままIDを引ける（ID 化はしない。メソッドの数を変えないため）。
        // 引ければ caller 列をスタックトレース形式にでき、Eclipseから飛べる
        String callerKey = u.callerKey();
        int callerId = callerKey.isEmpty() ? -1 : g.methods().idOf(callerKey);
        String location = (currentFile == null)
                ? (callerKey.isEmpty() ? "(unknown)" : callerKey)
                : currentFile + ":" + u.line();
        out.writeUnresolvedRow(g.methods(), callerId, location,
                u.line(), u.expression(), reasonText(u.reason()),
                ResolvedBy.UNRESOLVED + u.reason());
        return 1L;
    }

    /** 理由コードの文言（読み手の判断。キャッシュには文言を入れない） */
    static String reasonText(String code) {
        if (UnresolvedCallFact.BINDING_FAILED.equals(code)) {
            return "type resolution failed (missing classpath / dynamic call / etc.)";
        }
        if (UnresolvedCallFact.OUTSIDE_METHOD.equals(code)) {
            return "call from outside a method body";
        }
        return code;
    }
}
