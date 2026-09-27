// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;

import jche.cache.CacheFormat;
import jche.cache.TempFiles;
import jche.util.Log;
import jche.util.Messages;

/**
 * パス1 が書き、パス3 が読む依存の索引（一時ファイル。{@link TempFiles#DEPS}）。1 行に 1 ブロック、
 * {@code 番号 依存}（番号は {@link OldCache} の添字。{@link CacheFormat#joinRow} で符号化）を
 * 旧キャッシュのブロックの順に書く。
 * 最初の行を書くときにファイルを作る（書くものが無ければ作らない）。
 *
 * <p>名前は実行ごとに違い（{@link TempFiles#create}）、書くのも読むのも作ったときに開いた 1 つのチャネルで行う
 * （パス3 の周回ごとに先頭へ戻す。名前で開き直さない）。同じキャッシュのフォルダを使う別の実行が
 * 残り物として消しても読めるし、前の実行の残り物を読むこともない。書いた行数を数えておき、読んだ行数が
 * 違えば例外にする（解析は失敗として終わる。依存を読み落として再解析を静かに飛ばすよりよい）。
 *
 * <p>書けなかった（ディスクの空きが無い・権限が無い）ときは、索引を捨てて {@link #unwritable} を立てる。
 * 旧キャッシュは読めているので、キャッシュを捨てたりせず、パス3 は旧キャッシュから I 行を読む
 * （{@link CacheUpdater#selectDependentsFromCache}）。利用者が対処することではないので {@code Log.info} で知らせる。
 * {@link #close} で閉じて消す
 */
final class DepsIndex implements Closeable {
    private final Path cacheFile;
    private Path file;
    private FileChannel channel;
    private BufferedWriter out;
    /** 書いた行の数 */
    private long lines;
    /** 書けなかった。パス3 は旧キャッシュから読む */
    private boolean unwritable;

    DepsIndex(Path cacheFile) {
        this.cacheFile = cacheFile;
    }

    /** 1 ブロックの依存を書く（{@code index} は {@link OldCache} の添字）。書けなければ索引を捨てる（例外は投げない） */
    void add(int index, String deps) {
        if (unwritable) {
            return;
        }
        try {
            if (out == null) {
                file = TempFiles.create(cacheFile, TempFiles.DEPS);
                channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
                // 閉じない（閉じるとチャネルも閉じる）。書き終えたら flush だけする
                out = new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(channel),
                        StandardCharsets.UTF_8.newEncoder()));
            }
            CacheUpdater.writeLine(out, CacheFormat.joinRow(String.valueOf(index), deps));
            lines++;
        } catch (IOException | RuntimeException e) {
            giveUp(e);
        }
    }

    /** パス1 を読み終えた。溜めた分をファイルへ出す（チャネルは開いたまま。パス3 が読む） */
    void finishWriting() {
        if (out == null || unwritable) {
            return;
        }
        try {
            out.flush();
        } catch (IOException | RuntimeException e) {
            giveUp(e);
        }
    }

    /** 書けなかった。索引を捨て、以後は書かない（パス3 は旧キャッシュから読む） */
    private void giveUp(Exception e) {
        Log.info(Messages.format("analysis.cache.depsIndexUnwritable",
                file != null ? file : cacheFile.toAbsolutePath().getParent(), e));
        unwritable = true;
        out = null;
        close();
    }

    /** 書けたか（false なら、パス3 は旧キャッシュから読む） */
    boolean usable() {
        return !unwritable;
    }

    /**
     * 書いた行を先頭から順に渡す（パス3 の 1 周ぶん）。1 行も書いていなければ何も渡さない。
     *
     * @throws IOException 読めない、または読んだ行数が書いた行数と違う
     */
    void forEach(DepsIndex.Consumer each) throws IOException {
        if (channel == null) {
            if (lines != 0) {
                throw new IOException(Messages.format("analysis.cache.depsIndexMismatch", file, lines, 0));
            }
            return;
        }
        channel.position(0);
        // 閉じない（閉じるとチャネルも閉じる。次の周回でまた読む）
        BufferedReader in = new BufferedReader(new InputStreamReader(Channels.newInputStream(channel),
                StandardCharsets.UTF_8.newDecoder()));
        long read = 0;
        String line;
        while ((line = in.readLine()) != null) {
            read++;
            String[] cols = CacheFormat.columnsOf(line);
            each.accept(Integer.parseInt(cols[0]), CacheFormat.columnAt(cols, 1));
        }
        if (read != lines) {
            throw new IOException(Messages.format("analysis.cache.depsIndexMismatch", file, lines, read));
        }
    }

    /** 閉じて消す */
    @Override
    public void close() {
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (IOException e) {
            Log.info(Messages.format("cache.tempNotDeleted", file, e));
        }
        channel = null;
        TempFiles.delete(file);
    }

    /** 依存の索引の 1 行（{@link OldCache} の添字と依存）を受け取る */
    @FunctionalInterface
    interface Consumer {
        void accept(int index, String deps);
    }
}
