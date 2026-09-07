// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.util;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 標準出力へのログ。行頭に実行開始からの経過時間 [分:秒.ミリ秒s] を付ける。
 *
 * 標準出力の文字コードは指定しない。JDK 19以降、System.out はコンソール自身の
 * 文字コードで書き出すため、指定しないのが最も確実に読める。UTF-8に固定すると、
 * MS932のままのWindowsコンソールでログだけが文字化けする。
 * CSV等のファイル入出力は常に明示的な文字コードを使うので、この影響を受けない。
 *
 * 出力フォルダにも同じ内容を残せる（{@link #attachFile}）。設定ファイルごとに出力フォルダが
 * 変わるので、1つの設定の処理を始めるときに付け替え、終わったら外す。ファイル側は常に UTF-8 で書く
 * （後から別の環境で開くものなので、コンソールの文字コードに合わせない）。
 * 1行ごとに flush するのは、途中で落ちたときも直前の行までファイルに残すため。
 */
public final class Log {

    /** 経過時間表示の基準点。クラス初期化（実行開始）時点、または {@link #resetClock()} の時点 */
    private static long startNanos = System.nanoTime();

    /** 標準出力と同じ内容を書く先。無ければ null */
    private static PrintWriter file;

    private Log() {
    }

    public static void info(Object message) {
        println(elapsedStamp() + " " + message);
    }

    /** 利用者が対処すべきこと（設定漏れ・読み飛ばし等）。必ず [WARN] を付けて目立たせる */
    public static void warn(Object message) {
        info("[WARN] " + message);
    }

    /** 処理を続けられない失敗。スタックトレースも標準出力（とファイル）に残す */
    public static void error(Object message, Throwable cause) {
        info("[ERROR] " + message);
        if (cause != null) {
            StringWriter sw = new StringWriter();
            cause.printStackTrace(new PrintWriter(sw));
            for (String line : sw.toString().split("\\r?\\n")) {
                println(line);
            }
        }
    }

    /** フェーズの区切りに空行を入れる */
    public static void blank() {
        println("");
    }

    /** ヒープの使用量。フェーズごとに出して、メモリ設計が効いているかを確認できるようにする */
    public static void heap(String label) {
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMb = rt.maxMemory() / (1024 * 1024);
        info("=== " + label + ": [heap] 使用 " + usedMb + "MB / 上限 " + maxMb + "MB");
    }

    /** 経過時間の基準点を今にする。設定ファイルごとの所要時間が読めるよう、1つの設定の処理を始めるたびに呼ぶ */
    public static void resetClock() {
        startNanos = System.nanoTime();
    }

    /**
     * 以降のログを標準出力に加えてこのファイルにも書く（UTF-8、上書き）。
     * 既に別のファイルが付いていれば、それは閉じる。
     */
    public static void attachFile(Path path) throws IOException {
        detachFile();
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        file = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8));
    }

    /** ログファイルへの複写をやめて閉じる。付いていなければ何もしない */
    public static void detachFile() {
        if (file != null) {
            file.close();
            file = null;
        }
    }

    private static void println(String line) {
        System.out.println(line);
        if (file != null) {
            file.println(line);
            file.flush();
        }
    }

    private static String elapsedStamp() {
        long ms = (System.nanoTime() - startNanos) / 1_000_000L;
        return String.format("[%02d:%02d.%03ds]", ms / 60000, (ms / 1000) % 60, ms % 1000);
    }
}
