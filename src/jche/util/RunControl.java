// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.util;

/**
 * 解析の進捗の通知と、中止の受け口。
 *
 * <p>コマンドラインでは何も付けない（進捗は {@link Progress} が標準出力へ出し、中止は Ctrl+C）。
 * Eclipse プラグインのように「進捗バーへ出す」「利用者が中止できる」必要がある呼び出し側が、
 * 解析を始める前に {@link #attach} で受け口を付ける。
 *
 * <p>受け口はスレッドごとに持つ（{@link ThreadLocal}）。解析は1スレッドで走るので、
 * これで複数のプロジェクトを同時に解析しても混ざらない。静的な1個にすると、
 * Eclipse で2つのプロジェクトを並行して解析したときに進捗と中止が入れ替わる。
 *
 * <p>中止は {@link #checkCancelled()} を「1件処理するごと」の粒度で呼んで実現する。
 * 呼ぶ場所はフェーズ1のファイル単位とフェーズ2のキャッシュ走査で、
 * どちらも途中で放り出してもキャッシュ（テンポラリに書いてから差し替える）を壊さない位置にある。
 */
public final class RunControl {

    /** 解析の呼び出し側が実装する受け口 */
    public interface Listener {

        /**
         * 進捗の通知。
         *
         * @param label 処理の名前（「ソース解析」など）
         * @param done  済んだ件数
         * @param total 総数。0以下なら総数不明
         */
        default void progress(String label, long done, long total) {
        }

        /** 中止が要求されているか。true を返すと解析は {@link CancelledException} で終わる */
        default boolean isCancelled() {
            return false;
        }
    }

    private static final ThreadLocal<Listener> LISTENER = new ThreadLocal<>();

    private RunControl() {
    }

    /** このスレッドの解析に受け口を付ける。付け終わったら必ず {@link #detach()} すること */
    public static void attach(Listener listener) {
        LISTENER.set(listener);
    }

    /** 受け口を外す。付いていなければ何もしない */
    public static void detach() {
        LISTENER.remove();
    }

    /** 進捗を通知する。受け口が無ければ何もしない */
    public static void progress(String label, long done, long total) {
        Listener listener = LISTENER.get();
        if (listener != null) {
            listener.progress(label, done, total);
        }
    }

    /** 中止が要求されていれば {@link CancelledException} を投げる。受け口が無ければ何もしない */
    public static void checkCancelled() {
        Listener listener = LISTENER.get();
        if (listener != null && listener.isCancelled()) {
            throw new CancelledException();
        }
    }
}
