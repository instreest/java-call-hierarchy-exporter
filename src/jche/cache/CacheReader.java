// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cache;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * キャッシュファイルを先頭から 1 行ずつ読む。
 *
 * 読み手（{@code CacheUpdater} のパス0・1・引き継ぎ、{@code CallGraphBuilder} の 1 回のスキャン、
 * {@link CacheDump}）はどれも「ヘッダ行を飛ばし、行種別で分岐し、F 行でブロックが切り替わる」という
 * 同じ形をしている。ここに 1 本にまとめて、ヘッダの扱い・空行の読み飛ばし・列の分割と符号化の
 * 戻し方が読み手ごとにずれないようにする。
 *
 * <pre>
 *   try (CacheReader in = CacheReader.open(cacheFile)) {
 *       while (in.next()) {
 *           switch (in.rowType()) { ... in.columns() ... }
 *       }
 *   }
 * </pre>
 * 列の分割（{@link #columns}）は必要になった行でだけ行う。行種別だけ見て読み飛ばす行が多いため。
 *
 * <h2>バイトで読む</h2>
 * ファイルはバイトのまま読み、{@code '\n'} で行に分け、行末の {@code '\r'} を 1 つ落とす
 * （書き手は常に {@code '\n'} で書く。CRLF に変換されたファイルも読めるようにする）。
 * バイトで読むのは、行の<b>位置（ファイルの先頭からのバイト数）</b>を知るため。差分更新は
 * 再利用するブロックの位置を覚えておき、書き写すときに行へ戻さずバイトの範囲のまま写す
 * （{@code CacheUpdater} のパス5）。ブロックの検査値（{@link BlockChecksum}）も、ファイルに
 * 書かれたままのバイトから求める（書き手が求めるのと同じバイト列）。
 *
 * <p>各行は<b>厳格な</b> UTF-8 の復号器で文字列に戻す。UTF-8 として正しくないバイト列があれば
 * {@link java.nio.charset.MalformedInputException} を投げる（置換文字に化かして読み進めない）。
 * 読めないキャッシュは丸ごと捨てて全件解析し直す、という扱いをそのまま保つため。
 */
public final class CacheReader implements Closeable {

    /** 読み込みの単位。1 行がこれより長ければ、その行が収まるまでバッファを広げる */
    private static final int BUFFER_SIZE = 1 << 16;

    private final FileChannel channel;
    /** {@link #close} でチャネルも閉じるか（開いたチャネルを借りて読むときは閉じない） */
    private final boolean ownsChannel;
    /** 次に読み足すファイル上の位置（チャネルの位置は使わない。1 つのチャネルを何度も読み直せるように） */
    private long readPos;
    private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    private byte[] buf = new byte[BUFFER_SIZE];
    /** まだ行に分けていないバイトは {@code buf[pos, limit)} */
    private int pos;
    private int limit;
    /** {@code buf[0]} のファイル上の位置 */
    private long bufStart;
    private boolean eof;

    private String header = "";
    private String line;
    private String[] columns;
    /** 今の行のバイト（行末の '\n' と '\r' を除く）は {@code buf[lineOff, lineOff + lineLen)}。次の {@link #next} までだけ有効 */
    private int lineOff;
    private int lineLen;
    private long lineStart;
    private long nextLineStart;
    /** 書き手が書かない形に出会った数（{@link #irregularities}）。今の行のぶんも含む */
    private long irregular;
    /** 今の行より前（ファイルの {@code [0, lineStart)}）の {@link #irregular} */
    private long irregularBeforeLine;

    private CacheReader(FileChannel channel, long offset, boolean ownsChannel) {
        this.channel = channel;
        this.ownsChannel = ownsChannel;
        this.readPos = offset;
        this.bufStart = offset;
        this.lineStart = offset;
        this.nextLineStart = offset;
    }

    /** ファイルを開き、ヘッダ行（1 行目）を読んだ状態にする。ヘッダは {@link #header} で見られる */
    public static CacheReader open(Path cacheFile) throws IOException {
        return withHeader(openAt(cacheFile, 0L));
    }

    /**
     * 開いてあるチャネルを先頭から読む（ヘッダ行を読んだ状態にする）。{@link #close} してもチャネルは閉じない。
     * 差分更新が旧キャッシュを 1 つのチャネルで何度かに分けて読むときに使う（途中でファイルが差し替えられても、
     * 最初に開いたものを読み続ける。{@code jche.analysis.CacheUpdater}）
     */
    public static CacheReader open(FileChannel channel) throws IOException {
        return withHeader(new CacheReader(channel, 0L, false));
    }

    /** {@link #open(FileChannel)} と同じく、開いてあるチャネルを {@code offset} バイト目（行の先頭）から読む */
    public static CacheReader openAt(FileChannel channel, long offset) {
        return new CacheReader(channel, offset, false);
    }

    private static CacheReader withHeader(CacheReader in) throws IOException {
        try {
            if (in.readRaw()) {
                in.header = in.decode().trim();
            }
            return in;
        } catch (IOException | RuntimeException e) {
            in.close();
            throw e;
        }
    }

    /**
     * ファイルを開き、{@code offset} バイト目（行の先頭であること）から読む状態にする。ヘッダは読まない
     * （{@link #header} は空文字）。覚えておいたブロックの位置から読み直すときに使う
     */
    public static CacheReader openAt(Path file, long offset) throws IOException {
        return new CacheReader(FileChannel.open(file, StandardOpenOption.READ), offset, true);
    }

    /** ヘッダ行（{@link CacheFormat#headerFor} の形）。無ければ空文字 */
    public String header() {
        return header;
    }

    /**
     * ヘッダの形式が期待どおりか（版・ソースレベル・文字コード・JDK・JDT の版が一致するか）。
     *
     * <p>期待する側に分からない値（{@code ?}。JDT の版が読めなかった等）が入っていれば、
     * 書かれている側と同じ文字列でも一致とはみなさない。「分からない」どうしが一致しても、
     * 同じ環境で書かれたとは言えないため（安全側に倒す）
     */
    public boolean headerMatches(String expected) {
        if (expected.contains("=?")) {
            return false;
        }
        return header.equals(expected);
    }

    /**
     * 旧キャッシュを今回の実行で使い続けてよいか。{@link #headerMatches} に加えて、ソースフォルダを足した・外した
     * だけ（両方にあるフォルダの並びが同じで、入れ子が無い）なら使える（{@link CacheFormat#headerReusable}）。
     * 足した・外したフォルダのファイルは、足した・消したファイルとして扱う
     */
    public boolean headerReusable(String expected) {
        return CacheFormat.headerReusable(header, expected);
    }

    /**
     * 次の行へ進む。空行は読み飛ばす。
     *
     * @return 行があれば true。ファイルの終わりなら false
     * @throws java.nio.charset.MalformedInputException 行が UTF-8 として正しくない
     */
    public boolean next() throws IOException {
        columns = null;
        line = null;
        while (readRaw()) {
            if (lineLen > 0) {
                line = decode();
                return true;
            }
            irregular++;   // 空行（書き手は書かない）
        }
        return false;
    }

    /** 今の行（ファイルに書かれたままの文字列。列は符号化されたまま） */
    public String line() {
        return line;
    }

    /** 今の行の種別（{@link CacheFormat#rowTypeOf}） */
    public char rowType() {
        return CacheFormat.rowTypeOf(line);
    }

    public boolean is(char rowType) {
        return rowType() == rowType;
    }

    /**
     * 今の行をタブで分割し、各列の符号化を戻したもの（{@link CacheFormat#columnsOf}。
     * この行で初めて呼ばれたときに分割する）
     */
    public String[] columns() {
        if (columns == null) {
            columns = CacheFormat.columnsOf(line);
        }
        return columns;
    }

    /** 今の行の指定位置の列（符号化を戻したもの）。無ければ空文字 */
    public String column(int index) {
        return CacheFormat.columnAt(columns(), index);
    }

    /** F 行（ブロックの先頭）の相対パス。F 行でなければ空文字 */
    public String filePath() {
        return is(CacheFormat.ROW_FILE) ? column(1) : "";
    }

    /** 今の行の先頭の、ファイル上の位置（バイト） */
    public long lineStart() {
        return lineStart;
    }

    /**
     * 今の行の次の位置（行末の {@code '\n'} の直後。ファイル上のバイト）。{@link #next} が false を
     * 返したあとは、読み終えた位置（ファイルの長さ）
     */
    public long nextLineStart() {
        return nextLineStart;
    }

    /** 今の行のバイト（ファイルに書かれたまま。行末の改行を除く）を検査値に足す */
    public void addTo(BlockChecksum checksum) {
        checksum.add(buf, lineOff, lineLen);
    }

    /**
     * 今の行（F 行か T 行）を、最後の列（検査値の列）を空にした形で検査値に足す
     * （{@link BlockChecksum#addWithoutLastColumn(String)} と同じバイト列。最後のタブまでを足す）
     */
    public void addWithoutLastColumnTo(BlockChecksum checksum) {
        int end = lineOff + lineLen;
        int cut = lineOff;   // タブが無ければ空の行として足す（書き手はそういう行を書かないので、検査値は合わない）
        for (int i = end - 1; i >= lineOff; i--) {
            if (buf[i] == '\t') {
                cut = i + 1;
                break;
            }
        }
        checksum.add(buf, lineOff, cut - lineOff);
    }

    /**
     * 今の行より前（ファイルの先頭から {@link #lineStart} まで）にあった、書き手が書かない形の数
     * （読み飛ばした空行・行末の {@code '\r'}・{@code '\n'} で終わらない行）。{@link #next} が false を
     * 返したあとは、ファイル全体の数。
     *
     * <p>2 つの位置で取った値が同じなら、そのあいだのバイト列は書き手が書くとおりの形
     * （読んだ行に {@code '\n'} を付けて並べたもの）である。差分更新が、ブロックをバイトのまま
     * 書き写してよいか（書き写すと行を書き直したのと同じバイト列になるか）の判定に使う
     */
    public long irregularities() {
        return irregularBeforeLine;
    }

    @Override
    public void close() throws IOException {
        if (ownsChannel) {
            channel.close();
        }
    }

    // ------------------------------------------------------------
    // バイトの読み込み
    // ------------------------------------------------------------

    /**
     * 次の 1 行（空行を含む）を {@code buf[lineOff, lineOff + lineLen)} に置く。
     *
     * @return 行があれば true。ファイルの終わりなら false
     */
    private boolean readRaw() throws IOException {
        int scanned = 0;   // pos から数えて、'\n' が無いと分かっているバイト数
        while (true) {
            for (int i = pos + scanned; i < limit; i++) {
                if (buf[i] == '\n') {
                    take(i, i + 1, true);
                    return true;
                }
            }
            scanned = limit - pos;
            if (!fill()) {
                if (pos == limit) {
                    lineStart = nextLineStart;
                    lineLen = 0;
                    irregularBeforeLine = irregular;
                    return false;
                }
                take(limit, limit, false);   // '\n' で終わらない最後の行
                return true;
            }
        }
    }

    /**
     * {@code buf[pos, end)} を今の行にし、次の行を {@code next} から始める
     *
     * @param terminated 行が {@code '\n'} で終わっているか
     */
    private void take(int end, int next, boolean terminated) {
        lineOff = pos;
        lineStart = bufStart + pos;
        irregularBeforeLine = irregular;
        if (!terminated) {
            irregular++;
        }
        int len = end - pos;
        if (len > 0 && buf[end - 1] == '\r') {
            len--;
            irregular++;
        }
        lineLen = len;
        pos = next;
        nextLineStart = bufStart + next;
    }

    /**
     * バッファの後ろへ読み足す。読み残し（{@code buf[pos, limit)}）は先頭へ寄せ、
     * バッファが読み残しで埋まっていれば広げる（1 行が {@link #BUFFER_SIZE} より長いとき）。
     *
     * @return 読み足せたら true。ファイルの終わりなら false
     */
    private boolean fill() throws IOException {
        if (eof) {
            return false;
        }
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, limit - pos);
            bufStart += pos;
            limit -= pos;
            pos = 0;
        }
        if (limit == buf.length) {
            buf = Arrays.copyOf(buf, buf.length * 2);
        }
        int n = channel.read(ByteBuffer.wrap(buf, limit, buf.length - limit), readPos);
        if (n < 0) {
            eof = true;
            return false;
        }
        limit += n;
        readPos += n;
        return true;
    }

    /**
     * 今の行のバイトを文字列に戻す。ASCII だけの行（ほとんどがそう）は復号器を通さずに作る。
     * それ以外は厳格な UTF-8 の復号器で戻し、正しくないバイト列なら例外にする
     */
    private String decode() throws CharacterCodingException {
        int end = lineOff + lineLen;
        for (int i = lineOff; i < end; i++) {
            if (buf[i] < 0) {
                return decoder.decode(ByteBuffer.wrap(buf, lineOff, lineLen)).toString();
            }
        }
        return new String(buf, lineOff, lineLen, StandardCharsets.ISO_8859_1);
    }
}
