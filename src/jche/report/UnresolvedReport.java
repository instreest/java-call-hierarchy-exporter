// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jche.cache.CacheFormat;
import jche.cache.CacheReader;
import jche.cache.SymbolTable;
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
 *
 * <p>U 行の呼び出し元はブロックの記号表（S 行。{@link SymbolTable}）の番号で書かれているので、
 * ブロックごとに S 行を読んで引く。引いた呼び出し元はグラフのメソッド表で ID を<b>引くだけ</b>で、
 * ID 化はしない（メソッドの数を変えないため）。
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
        // 順番待ちのファイルのU行（記号表を引いたもの。記号はブロックの中でしか引けないので、引いてから溜める）
        Map<Integer, List<UnresolvedCallFact>> pending = new HashMap<>();
        int next = 0;            // 次に書き出すべきファイルの順位
        int currentRank = -1;    // 読んでいる最中のブロックの順位
        SymbolTable.Reader symbols = new SymbolTable.Reader();
        try (CacheReader in = CacheReader.open(config.cacheFile)) {
            while (in.next()) {
                char rowType = in.rowType();
                if (rowType == CacheFormat.ROW_SYMBOL) {
                    symbols.add(in.columns());
                    continue;
                }
                if (rowType == CacheFormat.ROW_FILE) {
                    symbols.clear();
                    if (currentRank >= 0) {
                        finished[currentRank] = true;
                        while (next < order.length && finished[next]) {
                            rows += flush(g, out, order[next], pending.remove(next));
                            next++;
                        }
                    }
                    Integer rank = rankOf.get(in.filePath());
                    currentRank = (rank == null) ? -1 : rank;
                    continue;
                }
                if (rowType != CacheFormat.ROW_UNRESOLVED) {
                    continue;
                }
                // 呼び出し元の記号が壊れていても行は捨てず、呼び出し元不明として出す（OUTSIDE_METHOD の行と同じ）。
                // グラフを組む側（CallGraphBuilder）はその行を警告して使わないので、ここで落とすと黙って消える
                UnresolvedCallFact u = UnresolvedCallFact.fromRowKeepingUnknownCaller(
                        in.columns(), symbols.array());
                // import 推定でエッジになっている行は、call-hierarchy.csv 側に
                // 「[EXTERNAL] import から型名を推定（未検証）」の注記付きで出ているので、ここでは出さない
                // （F 行の未解決数と同じ定義。呼び出し元が引けなかった行はエッジになっていないので出す）
                if (u == null || u.hasUsableCandidate()) {
                    continue;
                }
                if (currentRank < 0 || currentRank <= next) {
                    // 順番が来ているブロック（順位が無い・重複したブロックも溜めずにそのまま出す）
                    String file = (currentRank < 0) ? null : order[currentRank];
                    rows += emit(g, out, file, u);
                } else {
                    pending.computeIfAbsent(currentRank, k -> new ArrayList<>()).add(u);
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
        try (CacheReader in = CacheReader.open(cacheFile)) {
            while (in.next()) {
                if (in.is(CacheFormat.ROW_FILE)) {
                    paths.add(in.filePath());
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
    private static long flush(CallGraph g, CallHierarchyCsvWriter out, String file,
                              List<UnresolvedCallFact> facts) throws IOException {
        if (facts == null) {
            return 0L;
        }
        long rows = 0L;
        for (UnresolvedCallFact u : facts) {
            rows += emit(g, out, file, u);
        }
        return rows;
    }

    /**
     * U行を1行書き出す。
     *
     * @return 書き出した行数（1）
     */
    private static long emit(CallGraph g, CallHierarchyCsvWriter out, String currentFile, UnresolvedCallFact u)
            throws IOException {
        // 呼び出し元メソッドのキーはD行と同じ形式なので、そのままIDを引ける。
        // 引ければ caller 列をスタックトレース形式にでき、Eclipseから飛べる
        String callerKey = (u.caller() == null) ? "" : u.caller().key();
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
