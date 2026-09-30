// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * パス1 で読んだ旧キャッシュのうち、パス5 で書き写す候補（有効だったブロック）と、ファイル全体の形。
 *
 * <p>ブロックごとに持つのは、パス（今のソース一覧 {@code live} の文字列そのもの）・ファイル上の範囲
 * （F 行の先頭から、次の F 行・Z 行の手前まで）・F 行の件数（エラー数・構文エラー数・未解決数）だけで、
 * 有効なブロックの数に比例する小さな配列に持つ。パス5 はこの範囲をバイトのまま書き写し、件数もここから数える
 * （旧キャッシュを行として読み直さない）。
 */
final class OldCache {
    String[] paths = new String[64];
    long[] starts = new long[64];
    long[] ends = new long[64];
    int[] errors = new int[64];
    int[] syntaxErrors = new int[64];
    int[] unresolved = new int[64];
    /** 宣言する型のパッケージ（最初の H 行。無ければ null）。同じ中身の文字列は 1 つを共有する */
    String[] packages = new String[64];
    /** 書き手の書くとおりの形でないブロック（空行・CRLF を含む）。書き写すときは行に戻して書き直す */
    final BitSet irregular = new BitSet();
    /**
     * 型解決に失敗していたブロック（F 行のエラー数が 0 でない、または U 行に BINDING_FAILED がある）。
     * 何かが変わった実行では、名前を照合せず必ず解析し直す（クラスの説明「型解決に失敗していたファイル」）
     */
    final BitSet unresolvedTypes = new BitSet();
    /** {@link #packages} の文字列を共有するための表 */
    private final Map<String, String> packageNames = new HashMap<>();
    int size;

    /** F 行の数 */
    long blocks;
    /** 今のソースに無いファイル（消した・外したフォルダの）のブロックのパス（{@link SameUnitFiles#pairedWithDeleted}） */
    final List<String> deleted = new ArrayList<>();
    /** どのブロックも有効だったか（書き写す候補になったか） */
    boolean allKept = true;
    /** 最初の F 行（ブロックが無ければ Z 行）の位置。ここより前はヘッダ・L 行・T 行 */
    long blocksStart = -1;
    /** ファイル全体が書き手の書くとおりの形か（空行・CRLF・改行で終わらない行が無く、Z 行が 1 つだけ） */
    boolean writtenAsIs;

    void add(String path, long start, long end, int errorCount, int syntaxErrorCount, int unresolvedCount,
             boolean irregularBlock, boolean failedTypes, String pkg) {
        if (size == paths.length) {
            int grown = size + (size >> 1) + 16;
            paths = Arrays.copyOf(paths, grown);
            packages = Arrays.copyOf(packages, grown);
            starts = Arrays.copyOf(starts, grown);
            ends = Arrays.copyOf(ends, grown);
            errors = Arrays.copyOf(errors, grown);
            syntaxErrors = Arrays.copyOf(syntaxErrors, grown);
            unresolved = Arrays.copyOf(unresolved, grown);
        }
        paths[size] = path;
        starts[size] = start;
        ends[size] = end;
        errors[size] = errorCount;
        syntaxErrors[size] = syntaxErrorCount;
        unresolved[size] = unresolvedCount;
        if (irregularBlock) {
            irregular.set(size);
        }
        if (failedTypes) {
            unresolvedTypes.set(size);
        }
        packages[size] = (pkg == null) ? null : packageNames.computeIfAbsent(pkg, k -> k);
        size++;
    }
}
