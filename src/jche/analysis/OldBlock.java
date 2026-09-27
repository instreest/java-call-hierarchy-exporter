// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.util.ArrayList;
import java.util.List;

import jche.cache.BlockChecksum;
import jche.cache.CacheFormat;
import jche.cache.CacheReader;

/**
 * パス1 で読んでいる途中の旧キャッシュのブロック 1 つ。ブロックの終わり（次の F 行・Z 行・
 * ファイルの終わり）で {@link CacheUpdater#finishOldBlock} が判定する。検査値はブロックを読み終えるまで
 * 分からないので、判定に要るものをここに溜めておく（1 ブロック分だけ）
 */
final class OldBlock {
    /** F 行の相対パス。F 行が壊れていれば null */
    final String rel;
    /** そのファイルが今のソースにあるか（無ければ、壊れていても解析し直さないので数えない） */
    final boolean inSources;
    /** F 行のサイズと内容ハッシュが今のソースと一致するか */
    final boolean identical;
    /** F 行のエラー数・構文エラー数・未解決数 */
    final int errors;
    final int syntaxErrors;
    final int unresolved;
    final String expectedCrc;
    /** F 行の先頭のファイル上の位置（バイト） */
    final long start;
    /** F 行を読んだ時点の {@link CacheReader#irregularities}（ブロックが書き手の書くとおりの形かを見る） */
    final long irregularAtStart;
    final BlockChecksum checksum = new BlockChecksum();
    /** H 行（ファイルに書かれたまま）。部分型の索引に足し、無効なブロックなら「変わった型」に加える */
    final List<String> typeRows = new ArrayList<>();
    /**
     * 理由が BINDING_FAILED の U 行があったか。エラー数が 0 でなくても同じで、型解決に失敗していたブロックは、
     * 何かが変わった実行では必ず解析し直す（{@link CacheUpdater#reanalyzeDependents}）
     */
    boolean bindingFailed;
    /** F 行の直後の行をまだ読んでいないか */
    boolean firstRow = true;
    /** I 行（F 行の直後）の依存。I 行が無ければ空 */
    String deps = "";
    /** I 行の自分の宣言の指紋（宣言の連鎖。{@link CacheUpdater#oldDeclarations}）。I 行が無ければ空 */
    String declarations = "";

    OldBlock(String[] f, boolean inSources, boolean identical, long start, long irregularAtStart) {
        this.rel = (f.length >= 2) ? f[1] : null;
        this.inSources = inSources;
        this.identical = identical;
        this.errors = CacheFormat.errorsOf(f);
        this.syntaxErrors = CacheFormat.syntaxErrorsOf(f);
        this.unresolved = CacheFormat.unresolvedOf(f);
        this.expectedCrc = CacheFormat.crcOf(f);
        this.start = start;
        this.irregularAtStart = irregularAtStart;
    }
}
