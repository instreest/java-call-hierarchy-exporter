// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cache;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
 */
public final class CacheReader implements Closeable {

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

    /** F 行（ブロックの先頭）の相対パス。F 行でなければ空文字 */
    public String filePath() {
        return is(CacheFormat.ROW_FILE) ? column(1) : "";
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
