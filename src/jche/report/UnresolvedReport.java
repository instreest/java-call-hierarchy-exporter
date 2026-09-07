// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.report;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jche.cache.CacheFormat;
import jche.cache.UnresolvedCallFact;
import jche.config.Config;
import jche.graph.CallGraph;

/**
 * 型解決に失敗した呼び出しを call-hierarchy.csv に書き出す。
 *
 * これらは呼び出し先の型が特定できていないため、呼び出し階層としては辿れない。
 * しかし「解決できなかったせいで階層から抜け落ちている」こと自体が
 * 重要な情報（依存jarの不足を示す）なので、静かに消さずに行として残す。
 *
 * キャッシュのU行を読み直して出力する。件数ぶんをヒープに載せないための
 * ストリーミング処理。
 *
 * 行の並びは「ソースフォルダの宣言順 → ファイルの相対パス順 → ファイル内の出現順」に固定する
 * （methods.csv と同じ基準）。キャッシュのブロック順のまま出すと、差分更新で解析し直した
 * ファイルのブロックが先頭へ移るため、ファイルを1つ直すたびにこの節の行順が入れ替わってしまう。
 * 並べ替えのためにU行を全件ためることはせず、ブロックの並びを先に読んでおき、
 * 「次に出すべきファイル」のブロックはそのまま流し、順番がまだ来ていないファイルの行だけを
 * 順番が来るまで保留する。キャッシュがすでにその順で並んでいれば（初回実行、全ファイル再利用）
 * 何も保留しない。
 */
public final class UnresolvedReport {

    private UnresolvedReport() {
    }

    /** @return 書き出した行数 */
    public static long write(CallGraph g, Config config, CallHierarchyCsvWriter out) throws IOException {
        if (!Files.isRegularFile(config.cacheFile)) {
            return 0L;
        }
        // パス1: ブロック（F行）の相対パスを集め、出力したい順に並べて順位を振る
        String[] order = blockPathsInOutputOrder(g, config.cacheFile);
        Map<String, Integer> rankOf = new HashMap<>();
        for (int i = 0; i < order.length; i++) {
            rankOf.put(order[i], i);
        }

        // パス2: U行を読み、順位どおりに書き出す
        long rows = 0L;
        boolean[] finished = new boolean[order.length];         // そのファイルのブロックを読み終えたか
        Map<Integer, List<String>> pending = new HashMap<>();   // 順番待ちのファイルのU行（生の行）
        int next = 0;            // 次に書き出すべきファイルの順位
        int currentRank = -1;    // 読んでいる最中のブロックの順位
        try (BufferedReader in = Files.newBufferedReader(config.cacheFile, StandardCharsets.UTF_8)) {
            in.readLine();   // バージョン行
            String line;
            while ((line = in.readLine()) != null) {
                char rowType = CacheFormat.rowTypeOf(line);
                if (rowType == CacheFormat.ROW_FILE) {
                    if (currentRank >= 0) {
                        finished[currentRank] = true;
                        while (next < order.length && finished[next]) {
                            rows += flush(g, out, order[next], pending.remove(next));
                            next++;
                        }
                    }
                    String rel = CacheFormat.columnAt(CacheFormat.columnsOf(line), 1);
                    Integer rank = rankOf.get(rel);
                    currentRank = (rank == null) ? -1 : rank;
                    continue;
                }
                if (rowType != CacheFormat.ROW_UNRESOLVED || !isReportable(line)) {
                    continue;
                }
                if (currentRank < 0 || currentRank <= next) {
                    // 順番が来ているブロック（順位が無い・重複したブロックも溜めずにそのまま出す）
                    String file = (currentRank < 0) ? null : order[currentRank];
                    rows += emit(g, out, file, line);
                } else {
                    pending.computeIfAbsent(currentRank, k -> new ArrayList<>()).add(line);
                }
            }
        }
        if (currentRank >= 0) {
            finished[currentRank] = true;
        }
        // 読み終えた。残っている保留分を順位どおりに出す（順位の抜けはブロックが無かったファイル）
        for (int i = next; i < order.length; i++) {
            rows += flush(g, out, order[i], pending.remove(i));
        }
        return rows;
    }

    /**
     * キャッシュのF行の相対パスを、出力順（ソースフォルダの宣言順 → 相対パス順）に並べて返す。
     * 相対パスは常に '/' 区切りで書かれているので、String の比較で OS によらず同じ順になる
     */
    private static String[] blockPathsInOutputOrder(CallGraph g, Path cacheFile) throws IOException {
        List<String> paths = new ArrayList<>();
        try (BufferedReader in = Files.newBufferedReader(cacheFile, StandardCharsets.UTF_8)) {
            in.readLine();   // バージョン行
            String line;
            while ((line = in.readLine()) != null) {
                if (CacheFormat.rowTypeOf(line) == CacheFormat.ROW_FILE) {
                    paths.add(CacheFormat.columnAt(CacheFormat.columnsOf(line), 1));
                }
            }
        }
        String[] order = paths.stream().distinct().toArray(String[]::new);
        Arrays.sort(order, Comparator.comparingInt(g::sourceFolderIndexOf)
                .thenComparing(Comparator.naturalOrder()));
        return order;
    }

    /**
     * 保留していた行を書き出す。
     *
     * @return 書き出した行数
     */
    private static long flush(CallGraph g, CallHierarchyCsvWriter out, String file, List<String> lines)
            throws IOException {
        if (lines == null) {
            return 0L;
        }
        long rows = 0L;
        for (String line : lines) {
            rows += emit(g, out, file, line);
        }
        return rows;
    }

    /** このU行を報告するか。import 推定でエッジになっているものは除く */
    private static boolean isReportable(String line) {
        UnresolvedCallFact u = UnresolvedCallFact.fromRow(CacheFormat.columnsOf(line));
        // import 推定でエッジになっている行は、call-hierarchy.csv 側に
        // 「外部ライブラリ（import推定・未検証）」の注記付きで出ているので、ここでは出さない
        return u != null && !u.hasUsableCandidate();
    }

    /**
     * U行を1行書き出す。
     *
     * @return 書き出した行数（0 か 1）
     */
    private static long emit(CallGraph g, CallHierarchyCsvWriter out, String currentFile, String line)
            throws IOException {
        UnresolvedCallFact u = UnresolvedCallFact.fromRow(CacheFormat.columnsOf(line));
        if (u == null) {
            return 0L;
        }
        // 呼び出し元メソッドのキーはD行と同じ形式なので、そのままIDを引ける。
        // 引ければ caller 列をスタックトレース形式にでき、Eclipseから飛べる
        String callerKey = (u.caller() == null) ? "" : u.caller().key();
        int callerId = callerKey.isEmpty() ? -1 : g.methods().idOf(callerKey);
        String location = (currentFile == null)
                ? (callerKey.isEmpty() ? "(unknown)" : callerKey)
                : currentFile + ":" + u.line();
        out.writeUnresolvedRow(g.methods(), callerId, location,
                u.line(), u.expression(), reasonText(u.reason()));
        return 1L;
    }

    /** 理由コードの文言（読み手の判断。キャッシュには文言を入れない） */
    static String reasonText(String code) {
        if (UnresolvedCallFact.BINDING_FAILED.equals(code)) {
            return "型解決に失敗（クラスパス不足・動的呼び出し等の可能性）";
        }
        if (UnresolvedCallFact.OUTSIDE_METHOD.equals(code)) {
            return "メソッド本体の外からの呼び出し";
        }
        return code;
    }
}
