// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cache;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import jche.util.Log;
import jche.util.Messages;
import jche.util.RunControl;

/**
 * 同じキャッシュのフォルダを使う実行を 1 つずつにする排他の錠（{@code <キャッシュのファイル名>.lock}）。
 *
 * <p>キャッシュを読み書きするあいだ（フェーズ1 の差分更新と、フェーズ2 のグラフの構築）持ち、
 * 終われば（失敗しても）放す。2 つ目の実行は、1 つ目が放すまで待つ。
 *
 * <h2>なぜ要るのか</h2>
 * キャッシュ本体の一時ファイル（{@code analysis-cache.tsv.tmp}）と退避ファイル（{@code .partial}）は、
 * 中断からの引き継ぎのために決まった名前で、差分更新は旧キャッシュを何度かに分けて読む（パス1 で位置を覚え、
 * パス5 でその範囲を書き写す）。2 つの実行（CLI と Eclipse プラグインの解析サーバー、CI の並列ジョブなど）が
 * 同じフォルダで重なると、片方がもう片方の書きかけの一時ファイルを引き継ぎに奪い、書きかけのものを本物に
 * 差し替えてしまう。そのまま進むと、呼び出しの 1 本も無い CSV が「成功」として出ることがあった
 * （{@code docs/cache-unification-qa.md} の Q51）。
 *
 * <h2>待ち方</h2>
 * OS のファイルの錠（{@link FileChannel#tryLock}）を使う。プロセスが落ちれば OS が放すので、錠が残って
 * 次の実行を止め続けることは無い（中身で見るロックファイルと違い、古い錠を消す手順が要らない）。
 * 取れなければ 1 度だけ「待っている」と {@code Log.info} で知らせ、少しずつ間を置いて取り直す。
 * 待つ上限（既定 30 分。環境変数 {@link #WAIT_ENV} で秒数を変えられる）を過ぎたら、何を待っていたかが
 * 分かる例外で実行を失敗にする（黙って錠なしで進めない）。待っているあいだも中止は受け付ける
 * （{@link RunControl#checkCancelled}）。同じ JVM の中で錠が重なったとき（解析サーバーの中の 2 つの解析）も
 * 同じく待つ。
 *
 * <p>錠をかけられないファイルシステム（錠の仕組みの無いネットワークドライブなど。{@link FileChannel#tryLock} が
 * {@link IOException} を投げる）では、錠なしで続け、{@code Log.warn} で「同じフォルダを同時に使わないで」と知らせる
 * （そのフォルダを使えなくするより、案内して続けるほうがよい。錠が無くても、書き終えていないキャッシュからグラフを
 * 組むことはない。{@code jche.graph.CallGraphBuilder}）。
 *
 * <p>錠のファイルは消さない。消すと、消した直後に別の実行が同じ名前で作り直したファイルと、消される前に
 * 開いていたファイルとで、2 つの実行が同時に「錠を持っている」ことになりうるため。中身は空（0 バイト）。
 */
public final class CacheLock implements Closeable {

    /** 待つ上限の秒数を変える環境変数（既定 {@link #DEFAULT_WAIT_SECONDS}。0 なら待たない） */
    public static final String WAIT_ENV = "JCHE_CACHE_LOCK_WAIT_SECONDS";
    private static final long DEFAULT_WAIT_SECONDS = 30 * 60;
    /** 取り直す間隔の上限（ミリ秒）。最初は短く、だんだん延ばす */
    private static final long MAX_POLL_MILLIS = 1000;

    private final Path file;
    private final FileChannel channel;
    /** 錠。錠をかけられないファイルシステムなら null（錠なしで続ける） */
    private final FileLock lock;

    private CacheLock(Path file, FileChannel channel, FileLock lock) {
        this.file = file;
        this.channel = channel;
        this.lock = lock;
    }

    /** キャッシュのファイルに対する錠のファイル（キャッシュと同じフォルダ） */
    public static Path lockFileOf(Path cacheFile) {
        return cacheFile.resolveSibling(cacheFile.getFileName() + ".lock");
    }

    /**
     * 錠を取る。ほかの実行が持っていれば、放すまで待つ（上限まで）。
     *
     * @throws IOException 錠のファイルを作れない、または上限まで待っても取れなかった
     */
    public static CacheLock acquire(Path cacheFile) throws IOException {
        return acquire(cacheFile, waitSeconds());
    }

    static CacheLock acquire(Path cacheFile, long waitSeconds) throws IOException {
        Path file = lockFileOf(cacheFile.toAbsolutePath());
        Files.createDirectories(file.getParent());
        FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            long deadline = System.nanoTime() + waitSeconds * 1_000_000_000L;
            long poll = 50;
            boolean told = false;
            while (true) {
                FileLock lock;
                try {
                    lock = tryLock(channel);
                } catch (IOException e) {
                    // 錠の仕組みが無いファイルシステム。錠なしで続ける（クラスの説明）
                    Log.warn(Messages.format("cache.lock.unsupported", file.getParent(), e));
                    return new CacheLock(file, channel, null);
                }
                if (lock != null) {
                    if (told) {
                        Log.info(Messages.get("cache.lock.acquired"));
                    }
                    return new CacheLock(file, channel, lock);
                }
                if (System.nanoTime() - deadline >= 0) {
                    throw new IOException(Messages.format("cache.lock.timeout", file.getParent(), waitSeconds,
                            WAIT_ENV));
                }
                if (!told) {
                    Log.info(Messages.format("cache.lock.waiting", file.getParent(), waitSeconds));
                    told = true;
                }
                RunControl.checkCancelled();
                try {
                    Thread.sleep(poll);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(Messages.format("cache.lock.interrupted", file.getParent()), e);
                }
                poll = Math.min(MAX_POLL_MILLIS, poll * 2);
            }
        } catch (IOException | RuntimeException | Error e) {
            channel.close();
            throw e;
        }
    }

    /** 取れれば錠、ほかが持っていれば null（同じ JVM の中の重なりも「ほかが持っている」とみなす） */
    private static FileLock tryLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException e) {
            return null;
        }
    }

    /** 待つ上限の秒数。環境変数が無い・読めなければ既定 */
    private static long waitSeconds() {
        String raw = System.getenv(WAIT_ENV);
        if (raw != null) {
            try {
                return Math.max(0, Long.parseLong(raw.trim()));
            } catch (NumberFormatException e) {
                Log.info(Messages.format("cache.lock.badWait", WAIT_ENV, raw));
            }
        }
        return DEFAULT_WAIT_SECONDS;
    }

    /** 錠を放す（ファイルは消さない。クラスの説明） */
    @Override
    public void close() throws IOException {
        try {
            if (lock != null && lock.isValid()) {
                lock.release();
            }
        } finally {
            channel.close();
        }
    }

    @Override
    public String toString() {
        return file.toString();
    }
}
