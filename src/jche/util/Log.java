/*
 * Copyright 2026 the java-call-hierarchy-exporter authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jche.util;

/**
 * 標準出力へのログ。行頭に実行開始からの経過時間 [分:秒.ミリ秒s] を付ける。
 *
 * 標準出力の文字コードは指定しない。JDK 19以降、System.out はコンソール自身の
 * 文字コードで書き出すため、指定しないのが最も確実に読める。UTF-8に固定すると、
 * MS932のままのWindowsコンソールでログだけが文字化けする。
 * CSV等のファイル入出力は常に明示的な文字コードを使うので、この影響を受けない。
 */
public final class Log {

    /** 経過時間表示の基準点。クラス初期化（実行開始）時点に固定する */
    private static final long START_NANOS = System.nanoTime();

    private Log() {
    }

    public static void info(Object message) {
        System.out.println(elapsedStamp() + " " + message);
    }

    /** 利用者が対処すべきこと（設定漏れ・読み飛ばし等）。必ず [WARN] を付けて目立たせる */
    public static void warn(Object message) {
        info("[WARN] " + message);
    }

    /** フェーズの区切りに空行を入れる */
    public static void blank() {
        System.out.println();
    }

    /** ヒープの使用量。フェーズごとに出して、メモリ設計が効いているかを確認できるようにする */
    public static void heap(String label) {
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMb = rt.maxMemory() / (1024 * 1024);
        info("=== " + label + ": [heap] 使用 " + usedMb + "MB / 上限 " + maxMb + "MB");
    }

    private static String elapsedStamp() {
        long ms = (System.nanoTime() - START_NANOS) / 1_000_000L;
        return String.format("[%02d:%02d.%03ds]", ms / 60000, (ms / 1000) % 60, ms % 1000);
    }
}
