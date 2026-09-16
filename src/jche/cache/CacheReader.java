// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cache;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * キャッシュファイルを先頭から 1 行ずつ読む。
 *
 * 読み手（{@code CacheUpdater} のパス0・1・3、{@code CallGraphBuilder} の 2 回のスキャン、
 * {@code UnresolvedReport} の 2 回のスキャン）はどれも「ヘッダ行を飛ばし、行種別で分岐し、
 * F 行でブロックが切り替わる」という同じ形をしていた。ここに 1 本にまとめて、
 * ヘッダの扱い・空行の読み飛ばし・列の分割が読み手ごとにずれないようにする。
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
 * <p>例外は {@link #lastLineOf} で、最終行（Z 行）だけを見たい場面のためにファイルの末尾だけを読む。
 * 形式を知っているのはこのクラスなので、読み方もここに置いている。
 */
public final class CacheReader implements Closeable {

    /**
     * {@link #lastLineOf} が末尾から読む量。最終行として想定しているのは Z 行
     * （{@code Z<TAB>ブロック数}）で、これよりはるかに短い
     */
    private static final int TAIL_BYTES = 512;

    private final BufferedReader in;
    private final String header;
    private String line;
    private String[] columns;

    private CacheReader(BufferedReader in, String header) {
        this.in = in;
        this.header = header;
    }

    /** ファイルを開き、ヘッダ行（1 行目）を読んだ状態にする。ヘッダは {@link #header} で見られる */
    public static CacheReader open(Path cacheFile) throws IOException {
        BufferedReader in = Files.newBufferedReader(cacheFile, StandardCharsets.UTF_8);
        try {
            String header = in.readLine();
            return new CacheReader(in, (header == null) ? "" : header.trim());
        } catch (IOException | RuntimeException e) {
            in.close();
            throw e;
        }
    }

    /** ヘッダ行（{@link CacheFormat#headerFor} の形。末尾に世代の印が付く）。無ければ空文字 */
    public String header() {
        return header;
    }

    /**
     * ヘッダの形式が期待どおりか（版・ソースレベル・JDK・拡張の指紋が一致するか）。
     * 世代の印（{@link CacheFormat#GENERATION_PREFIX}）は互換性とは別の軸なので比べない
     */
    public boolean headerMatches(String expected) {
        return CacheFormat.compatibilityPartOf(header).equals(expected);
    }

    /**
     * ヘッダの世代の印。無ければ空文字。
     * 2 つのキャッシュが同じ実行で書かれたかを突き合わせるのに使う
     */
    public String generation() {
        return CacheFormat.generationOf(header);
    }

    /**
     * 次の行へ進む。空行は読み飛ばす。
     *
     * @return 行があれば true。ファイルの終わりなら false
     */
    public boolean next() throws IOException {
        columns = null;
        while ((line = in.readLine()) != null) {
            if (!line.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** 今の行（生の文字列） */
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

    /** 今の行をタブで分割したもの（この行で初めて呼ばれたときに分割する） */
    public String[] columns() {
        if (columns == null) {
            columns = CacheFormat.columnsOf(line);
        }
        return columns;
    }

    /** 今の行の指定位置の列。無ければ空文字 */
    public String column(int index) {
        return CacheFormat.columnAt(columns(), index);
    }

    /**
     * ファイルの最終行（空行を除く）。ファイルを<b>末尾からだけ</b>読む。
     *
     * 最終行（Z 行）だけを見たい場面のためにある。先頭から 1 行ずつ読むと、数百MBの
     * キャッシュでも最後の 1 行を取るためにファイル全体を走ることになるため
     * （{@code CacheUpdater} が 2 つのキャッシュの対を判定するたびに起きていた）。
     *
     * <p>{@link #TAIL_BYTES} の中に行の区切りが無ければ null を返す。そこまで長い行は
     * Z 行ではありえないので、呼び出し側の「最終行が Z 行でなければ対でない」という判定と
     * 同じ結論になる。改行の前を探すので、UTF-8 の多バイト文字を途中で切ることはない
     * （0x0A は継続バイトには現れない）。改行は LF / CRLF のどちらでもよい。
     *
     * @return 最終行（前後の空白と改行を落としたもの）。空ファイル・空行だけ・長すぎる行なら null
     */
    public static String lastLineOf(Path file) throws IOException {
        try (SeekableByteChannel ch = Files.newByteChannel(file, StandardOpenOption.READ)) {
            long size = ch.size();
            if (size == 0) {
                return null;
            }
            int len = (int) Math.min(size, TAIL_BYTES);
            ByteBuffer buf = ByteBuffer.allocate(len);
            ch.position(size - len);
            while (buf.hasRemaining() && ch.read(buf) > 0) {
                // 読めるだけ読む（ファイルの末尾なので、途中で 0 を返されても残りは無い）
            }
            byte[] tail = buf.array();
            int end = buf.position();
            // 末尾の改行を落とす（書き出しは必ず改行で終わる。空行が続いていればそれも飛ばす）
            while (end > 0 && (tail[end - 1] == '\n' || tail[end - 1] == '\r')) {
                end--;
            }
            int start = end;
            while (start > 0 && tail[start - 1] != '\n') {
                start--;
            }
            if (start == 0 && len < size) {
                return null;   // 最終行が読んだ範囲に収まらない（Z 行ではありえない長さ）
            }
            String line = new String(tail, start, end - start, StandardCharsets.UTF_8).trim();
            return line.isEmpty() ? null : line;
        }
    }

    /** F 行（ブロックの先頭）の相対パス。F 行でなければ空文字 */
    public String filePath() {
        return is(CacheFormat.ROW_FILE) ? column(1) : "";
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
