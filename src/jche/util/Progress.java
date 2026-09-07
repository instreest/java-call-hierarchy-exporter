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
 * 標準出力への進捗表示。
 *
 * 解析には時間がかかるため、「今どの処理を、全体の何件中どこまで進めているか」
 * 「直近の一定件数に何秒かかったか」を出して、止まっていないことが分かるようにする。
 * 直近の所要時間を出すのは、途中で急に遅くなる箇所（巨大ファイル、ハブメソッド等）を
 * 見つけやすくするため。
 *
 * 使うのはフェーズ1（ソース解析）だけ。以降のフェーズは1件あたりが十分速く、
 * 件数だけが多いので、進捗を出すとログが流れて肝心の警告が埋もれる。
 */
public final class Progress {

    /** 何件ごとに進捗を出すか */
    private final int interval;

    private final String label;
    /** 0以下なら総数不明 */
    private final long total;
    private final long startNanos = System.nanoTime();
    private long lastNanos = startNanos;
    private long lastDone;
    private long done;

    /**
     * @param total    総数。0以下なら総数不明
     * @param interval 何件ごとに進捗を出すか
     */
    public Progress(String label, long total, int interval) {
        this.label = label;
        this.total = total;
        this.interval = Math.max(1, interval);
    }

    public void step(long current) {
        done = current;
        if (done - lastDone >= interval) {
            report();
        }
    }

    /** 最後の端数ぶんも1行出して締める。戻り値は開始からの経過ミリ秒 */
    public long finish() {
        if (done > lastDone) {
            report();
        }
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private void report() {
        long now = System.nanoTime();
        long recentCount = done - lastDone;
        StringBuilder sb = new StringBuilder();
        sb.append(label).append(' ').append(done);
        if (total > 0) {
            sb.append('/').append(total);
        } else {
            sb.append("件");
        }
        sb.append(String.format(" （直近%d件: %s）", recentCount, formatSeconds((now - lastNanos) / 1e9)));
        Log.info(sb);
        lastNanos = now;
        lastDone = done;
    }

    private static String formatSeconds(double sec) {
        if (sec < 60) {
            return String.format("%.1fs", sec);
        }
        long s = (long) sec;
        return String.format("%d分%02ds", s / 60, s % 60);
    }
}
