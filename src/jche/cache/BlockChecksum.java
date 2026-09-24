// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cache;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * ブロックの検査値（F 行の crc 列。{@link CacheFormat} の「ブロックの検査値」）を求める。
 *
 * <p>F 行の次の行から、次の F 行・Z 行の手前までの各行を UTF-8 にし、{@code '\n'} を付けて
 * 並べたバイト列の CRC32 を、小文字の16進8桁で表す。書き手（ブロックを組んだとき）と
 * 読み手（読んだ行を 1 行ずつ足す）で同じ手順を使う。
 *
 * <h2>なぜブロックごとに持つのか</h2>
 * 差分更新はブロック単位で再利用するので、壊れたかどうかもブロック単位で分かれば、
 * 壊れたブロックのファイルだけを解析し直せる（ほかは再利用できる）。
 * 行の書き換え・文字化けのほか、記号（S 行）やノード（N 行）の番号がブロックの外を指す
 * 壊れ方も、検査値が合わないことで捕まえる。検査値の合ったブロックで番号が外れることは事実上ない。
 * それでも読み手（{@code jche.graph.CallGraphBuilder} など）は番号の範囲を確かめる
 * （{@link SymbolTable#refsInRange}）。外れた番号を黙って「無い」と読むと、呼び出しが静かに消えるため。
 */
public final class BlockChecksum {

    private final CRC32 crc = new CRC32();

    /** 1 行を足す（行の区切りの {@code '\n'} はここで足すので、行には含めない） */
    public void add(String line) {
        crc.update(line.getBytes(StandardCharsets.UTF_8));
        crc.update('\n');
    }

    /** ここまでに足した行の検査値（小文字の16進8桁） */
    public String hex() {
        return String.format("%08x", crc.getValue());
    }

    /** 最初からやり直す（次のブロックのため） */
    public void reset() {
        crc.reset();
    }
}
