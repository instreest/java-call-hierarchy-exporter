// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import jche.cache.CacheFormat;
import jche.cache.TempFiles;
import jche.util.Log;
import jche.util.Messages;
import jche.util.Names;

/**
 * 型解決に失敗した呼び出しのうち、一覧（{@code call-hierarchy.csv} の末尾。{@code jche.report.UnresolvedReport}）に
 * 出す U 行の一時置き場。
 *
 * <p>グラフを組むスキャン（{@link CallGraphBuilder}）がブロックを読みながら行を渡し、CSV を書く側が
 * 最後に読む。以前は CSV を書く側がキャッシュを 2 回読み直していた（ブロックの並びを知るためと、U 行を読むため）。
 *
 * <p>行はヒープに溜めず、キャッシュと同じフォルダの一時ファイル（{@link TempFiles#UNRESOLVED}）へ
 * ブロックごとにまとめて書く。クラスパスが足りないプロジェクトでは呼び出しの多くがここに来るので、
 * 件数に比例するヒープを使わないため。ヒープに持つのは、全ブロックのパス（キャッシュ上の順。
 * 文字列はメソッド表の宣言ファイルと同じものを指す）と、行のあるブロックだけの位置と長さ。
 * 一時ファイルは最初の行を渡されたときに作る（1 行も無ければ作らない）。{@link #close} で消す。
 *
 * <p>ファイルの 1 行は {@code 行番号 呼び出し元のキー 式 理由}（{@link CacheFormat#joinRow} で符号化）。
 * 呼び出し元のキーはメソッドの ID にしない。一覧を書く時点のメソッド表で引く（ID 化はしない）のが
 * 以前と同じ扱いのため。
 */
public final class UnresolvedCalls implements Closeable {

    /** 一覧に出す 1 行 */
    public record Row(int line, String callerKey, String expression, String reason) {
    }

    private final Path cacheFile;
    private Path file;
    /** 書くのも読むのもこの 1 つのチャネル（名前で開き直さない。別の実行が残り物として消しても読める） */
    private FileChannel channel;
    private OutputStream out;
    /** 一時ファイルに書いたバイト数 */
    private long written;
    private long rows;

    /** 全ブロックのパス（キャッシュ上の順）。最初の F 行より前に U 行があったときだけ、先頭が null */
    private final ArrayList<String> blockPaths = new ArrayList<>();
    /** 行のあるブロックの、{@link #blockPaths} での位置（昇順）・一時ファイルの位置・長さ */
    private int[] rowBlocks = new int[16];
    private long[] offsets = new long[16];
    private int[] lengths = new int[16];
    private int rowBlockCount;

    /** @param cacheFile キャッシュ（一時ファイルはこれと同じフォルダに作る） */
    public UnresolvedCalls(Path cacheFile) {
        this.cacheFile = cacheFile;
    }

    /** 次のブロック（F 行）に移る */
    void beginBlock(String path) {
        blockPaths.add(path);
    }

    /**
     * 今のブロックの行を 1 つ足す
     *
     * @param callerKey 呼び出し元のキー。分からなければ空文字
     */
    void add(int line, String callerKey, String expression, String reason) throws IOException {
        if (blockPaths.isEmpty()) {
            blockPaths.add(null);   // 最初の F 行より前（書き手は書かない。読んだとおりに出す）
        }
        int block = blockPaths.size() - 1;
        if (rowBlockCount == 0 || rowBlocks[rowBlockCount - 1] != block) {
            if (rowBlockCount == rowBlocks.length) {
                int grown = rowBlockCount + (rowBlockCount >> 1) + 16;
                rowBlocks = Arrays.copyOf(rowBlocks, grown);
                offsets = Arrays.copyOf(offsets, grown);
                lengths = Arrays.copyOf(lengths, grown);
            }
            rowBlocks[rowBlockCount] = block;
            offsets[rowBlockCount] = written;
            lengths[rowBlockCount] = 0;
            rowBlockCount++;
        }
        if (out == null) {
            file = TempFiles.create(cacheFile, TempFiles.UNRESOLVED);
            channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
            out = new BufferedOutputStream(Channels.newOutputStream(channel), 1 << 16);
        }
        byte[] bytes = (CacheFormat.joinRow(String.valueOf(line), callerKey, expression, reason) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        out.write(bytes);
        written += bytes.length;
        // 1 ブロック（ソースファイル 1 つ）の行は int に収まる
        lengths[rowBlockCount - 1] = Math.toIntExact(written - offsets[rowBlockCount - 1]);
        rows++;
    }

    /**
     * 書き終えた（溜めた分をファイルへ出す）。1 行も無ければ、ブロックのパスも要らないので手放す
     * （クラスパスが揃っていれば、ふつうはこちら）
     */
    void finishWriting() throws IOException {
        if (out != null) {
            out.flush();   // チャネルは開いたまま（読むときに使う）
            out = null;
        }
        if (rows == 0) {
            blockPaths.clear();
            blockPaths.trimToSize();
        }
    }

    /** 行の数 */
    public long rowCount() {
        return rows;
    }

    /** ブロックの数（キャッシュ上の順） */
    public int blockCount() {
        return blockPaths.size();
    }

    /** ブロックのパス。最初の F 行より前の行をまとめたブロックなら null */
    public String blockPath(int block) {
        return blockPaths.get(block);
    }

    /** 行のあるブロックの数 */
    public int blocksWithRows() {
        return rowBlockCount;
    }

    /** 行のあるブロックのうち k 番目の、ブロックの位置（{@link #blockPath} の引数） */
    public int blockOf(int k) {
        return rowBlocks[k];
    }

    /** 行のあるブロックのうち k 番目の行（書いた順）。一時ファイルのその範囲だけを読む */
    public List<Row> rowsOf(int k) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(lengths[k]);
        long at = offsets[k];
        while (buf.hasRemaining()) {
            int n = channel.read(buf, at + buf.position());
            if (n < 0) {
                throw new EOFException(file.toString());   // 書いた長さより短い（自分で書いたファイルなので起きない）
            }
        }
        String text = new String(buf.array(), StandardCharsets.UTF_8);
        List<Row> result = new ArrayList<>();
        int from = 0;
        while (from < text.length()) {
            int end = text.indexOf('\n', from);
            String[] cols = CacheFormat.columnsOf(text.substring(from, end));
            result.add(new Row(Names.parseIntOr(cols[0], -1), CacheFormat.columnAt(cols, 1),
                    CacheFormat.columnAt(cols, 2), CacheFormat.columnAt(cols, 3)));
            from = end + 1;
        }
        return result;
    }

    /** 一時ファイルを消す */
    @Override
    public void close() {
        out = null;
        try {
            if (channel != null) {
                channel.close();
                channel = null;
            }
        } catch (IOException e) {
            Log.info(Messages.format("cache.tempNotDeleted", file, e));
        }
        TempFiles.delete(file);
        file = null;
    }
}
