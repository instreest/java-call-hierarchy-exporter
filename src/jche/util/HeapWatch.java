// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.util;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.List;
import java.util.Map;

import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;

import com.sun.management.GarbageCollectionNotificationInfo;

/**
 * 「ヒープ上限が足りていないせいで遅くなっていないか」を、解析を遅くせずに見張る。
 *
 * <h2>なぜ「占有量」だけでは分からないのか</h2>
 * {@link Log#heap} が出す占有量（{@code totalMemory() - freeMemory()}）は、まだ回収されていない
 * ものを含む「今ヒープに載っている量」で、上限を上げるほど大きく出る。足りているかどうかの
 * 判断には使えない（{@code docs/ast-analysis-performance-qa.md} の Q8）。
 * 見るべきは次の3つで、どれも JVM が元々数えている値を読むだけなので、<b>測るための負荷は無い</b>。
 *
 * <pre>
 *   GC に取られた時間の割合 … フェーズの経過時間のうち GC の停止に費やした割合
 *   GC 後の占有率           … GC が済んだ直後でも上限の何割が埋まっているか（本命）
 *   フル GC の回数          … 1 回でも起きていれば、上限に張り付いている印
 * </pre>
 *
 * <h2>2 段構え</h2>
 * <ul>
 *   <li><b>いつでも取れるもの</b> … {@link GarbageCollectorMXBean} の回数・時間と、
 *       スレッドの累計割り当て量。カウンタを読むだけなので登録も解除も要らない</li>
 *   <li><b>見張っている間だけ取れるもの</b> … GC 1 回ごとの通知（{@link #start()} で登録）。
 *       「GC 後の占有率」はこれでしか取れない。通知は GC のたびにしか来ないので
 *       （1 回の解析で数十〜数百回）、これも負荷にならない</li>
 * </ul>
 * 見張っていないときは前者だけを出す。
 *
 * <h2>登録と解除</h2>
 * 解析の始めから終わりまでを {@link jche.Exporter} が {@code try (HeapWatch w = HeapWatch.start())}
 * で囲む。解析サーバー（{@link jche.server.Server}）は 1 つの JVM で解析を何度も走らせるので、
 * <b>解除しないとリスナーが積み上がって二重に数える</b>。入れ子で呼ばれた場合は内側を素通りさせ、
 * 解除も外側の 1 回だけにする。
 *
 * <p>{@code System.gc()} は呼ばない。生存量を正確に出せる代わりに、フェーズごとにフル GC を
 * 挟むことになり、<b>測るために遅くする</b>ことになる（実測で 1 回あたり数十 ms〜、
 * 生存量が大きいほど伸びる）。正確な値が要るときは JFR で測る
 * （{@code docs/ast-analysis-performance-qa.md} の「計測のしかた」）。
 */
public final class HeapWatch implements AutoCloseable {

    /** GC に取られた時間がフェーズのこの割合を超えたら知らせる */
    private static final int GC_TIME_PERCENT_LIMIT = 20;
    /** GC が済んだ直後でも上限のこの割合が埋まっていたら知らせる */
    private static final int AFTER_GC_PERCENT_LIMIT = 70;
    /** 経過時間がこれより短いフェーズでは割合を出さない（分母が小さすぎて意味を持たない） */
    private static final long MIN_ELAPSED_MS_FOR_PERCENT = 500;

    /** 今見張っているもの。入れ子と解除漏れを防ぐため 1 つだけ持つ */
    private static HeapWatch current;

    /** フェーズの区切りで差分を取るための、前回の値 */
    private static long lastGcCount;
    private static long lastFullGcCount;
    private static long lastGcMillis;
    private static long lastAllocatedBytes;
    private static long phaseStartNanos = System.nanoTime();

    private final List<GarbageCollectorMXBean> beans = ManagementFactory.getGarbageCollectorMXBeans();
    private final NotificationListener listener = this::onGarbageCollection;
    private final boolean owner;

    /** GC が済んだ直後のヒープ使用量の最大（バイト）。見張っている間の最大 */
    private volatile long peakAfterGcBytes;
    /** 一度知らせたら、あとは黙る（フェーズごとに同じ注意を繰り返さない） */
    private boolean warned;

    private HeapWatch(boolean owner) {
        this.owner = owner;
    }

    /**
     * 見張りを始める。{@link #close()} まで、GC 1 回ごとの通知を受ける。
     *
     * 既に見張っているときは何もしない handle を返すので、入れ子で呼んでも二重に登録しない。
     *
     * @return {@code try}-with-resources で閉じるための handle
     */
    public static HeapWatch start() {
        if (current != null) {
            return new HeapWatch(false);
        }
        HeapWatch watch = new HeapWatch(true);
        for (GarbageCollectorMXBean bean : watch.beans) {
            if (bean instanceof NotificationEmitter emitter) {
                emitter.addNotificationListener(watch.listener, null, null);
            }
        }
        current = watch;
        resetPhase();
        return watch;
    }

    @Override
    public void close() {
        if (!owner) {
            return;
        }
        for (GarbageCollectorMXBean bean : beans) {
            if (bean instanceof NotificationEmitter emitter) {
                try {
                    emitter.removeNotificationListener(listener);
                } catch (javax.management.ListenerNotFoundException e) {
                    // 既に外れているだけ。解析の結果には関係しない
                }
            }
        }
        if (current == this) {
            current = null;
        }
    }

    /** フェーズの差分の起点を今にする。{@link Log#resetClock()} と対で呼ぶ */
    public static void resetPhase() {
        Counters now = Counters.read();
        lastGcCount = now.count;
        lastFullGcCount = now.fullCount;
        lastGcMillis = now.millis;
        lastAllocatedBytes = allocatedBytes();
        phaseStartNanos = System.nanoTime();
    }

    /**
     * 直前のフェーズぶんの計測値。
     *
     * @param line           ログに出す一行
     * @param gcMillis       このフェーズで GC の停止に費やした時間
     * @param elapsedMillis  このフェーズの経過時間
     * @param fullGcCount    このフェーズで起きたフル GC の回数
     */
    record Phase(String line, long gcMillis, long elapsedMillis, long fullGcCount) {
    }

    /**
     * 直前のフェーズを締めて、計測値を返す。{@link Log#heap} が使う。
     *
     * 呼ぶたびに差分の起点を進めるので、フェーズの区切りでだけ呼ぶこと。
     */
    static Phase endPhase() {
        Counters now = Counters.read();
        long elapsedMs = (System.nanoTime() - phaseStartNanos) / 1_000_000;
        long gcCount = now.count - lastGcCount;
        long fullGcCount = now.fullCount - lastFullGcCount;
        long gcMillis = now.millis - lastGcMillis;
        long allocated = allocatedBytes();
        long allocatedDelta = (allocated < 0 || lastAllocatedBytes < 0) ? -1 : allocated - lastAllocatedBytes;

        lastGcCount = now.count;
        lastFullGcCount = now.fullCount;
        lastGcMillis = now.millis;
        lastAllocatedBytes = allocated;
        phaseStartNanos = System.nanoTime();

        StringBuilder sb = new StringBuilder();
        sb.append("[heap] 上限 ").append(Runtime.getRuntime().maxMemory() / (1024 * 1024)).append("MB");
        if (allocatedDelta >= 0) {
            // 環境に依らない「仕事の量」。上限を変えても変わらないので、版ごとの比較に使える
            sb.append(" / 確保 ").append(allocatedDelta / (1024 * 1024)).append("MB");
        }
        sb.append(" / GC ").append(gcCount).append("回");
        if (fullGcCount > 0) {
            sb.append("（うちフル ").append(fullGcCount).append("回）");
        }
        sb.append(" ").append(gcMillis).append("ms");
        if (elapsedMs >= MIN_ELAPSED_MS_FOR_PERCENT) {
            sb.append("＝経過の").append(100 * gcMillis / elapsedMs).append("%");
        }
        int afterGcPercent = afterGcPercent();
        if (afterGcPercent >= 0) {
            sb.append(" / GC後の占有 ").append(afterGcPercent).append("%");
        }
        return new Phase(sb.toString(), gcMillis, elapsedMs, fullGcCount);
    }

    /**
     * 上限が足りていないと言えるなら、その理由。足りていそうなら null。
     *
     * 3 つのうち 1 つでも当てはまれば知らせる。<b>GC の回数は見ない</b>。
     * 回数は上限を下げると素直に増えるが（実測で 31 回 → 172 回）、そのぶん遅くなるとは限らず
     * （同じ実測で全体は 13% 増にとどまった）、注意としては過敏すぎる。
     */
    private static String shortageReason(Phase phase) {
        if (phase.fullGcCount() > 0) {
            return "フル GC が " + phase.fullGcCount() + " 回起きています";
        }
        int afterGcPercent = afterGcPercent();
        if (afterGcPercent >= AFTER_GC_PERCENT_LIMIT) {
            return "GC が済んだ直後でも上限の " + afterGcPercent + "% が埋まっています";
        }
        if (phase.elapsedMillis() >= MIN_ELAPSED_MS_FOR_PERCENT
                && 100 * phase.gcMillis() / phase.elapsedMillis() >= GC_TIME_PERCENT_LIMIT) {
            return "このフェーズの " + (100 * phase.gcMillis() / phase.elapsedMillis())
                    + "% を GC に費やしています";
        }
        return null;
    }

    /**
     * 直前のフェーズを見て、上限が足りていなければ 1 度だけ知らせる。
     * 見張っていないときと、既に知らせたあとは何もしない
     */
    static void warnIfShort(Phase phase) {
        HeapWatch watch = current;
        if (watch == null || watch.warned) {
            return;
        }
        String reason = shortageReason(phase);
        if (reason == null) {
            return;
        }
        watch.warned = true;
        long suggestGb = Math.max(2, Runtime.getRuntime().maxMemory() / (1024L * 1024 * 1024) * 2);
        Log.warn("ヒープの上限が足りていない可能性があります（" + reason + "）。"
                + "上限を増やすと速くなることがあります。");
        Log.info("   対話モードの「環境設定」の「ヒープ上限（-Xmx）」で設定するか、"
                + "環境変数 JCHE_JAVA_OPTS に -Xmx" + suggestGb + "g のように指定してください。");
    }

    /** GC が済んだ直後の占有率（%）。見張っていない・まだ GC が起きていないなら -1 */
    private static int afterGcPercent() {
        HeapWatch watch = current;
        if (watch == null || watch.peakAfterGcBytes <= 0) {
            return -1;
        }
        long max = Runtime.getRuntime().maxMemory();
        return (max <= 0) ? -1 : (int) (100 * watch.peakAfterGcBytes / max);
    }

    /**
     * GC 1 回ぶんの通知。ヒープのプールだけを足して、GC 後の占有量の最大を覚える。
     *
     * ここで例外を投げると JMX の通知の仕組みを巻き込むので、何があっても飲み込む
     * （ログの飾りのために解析を止めない）。
     */
    private void onGarbageCollection(Notification notification, Object handback) {
        try {
            if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION
                    .equals(notification.getType())) {
                return;
            }
            GarbageCollectionNotificationInfo info =
                    GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
            long after = heapUsedOf(info.getGcInfo().getMemoryUsageAfterGc());
            if (after > peakAfterGcBytes) {
                peakAfterGcBytes = after;
            }
        } catch (RuntimeException e) {
            // 触れない値があっただけ。占有率が出ないだけで、解析には影響しない
        }
    }

    /**
     * ヒープのプールの使用量の合計。
     *
     * Metaspace やコードキャッシュはヒープ上限（-Xmx）の外なので数えない。
     * プールの名前は GC の実装ごとに違う（G1 Eden Space / PS Eden Space / ZHeap …）ため、
     * 「ヒープの外だと分かっているもの」を除く形にしてある。知らない GC でも数え漏らさない
     */
    private static long heapUsedOf(Map<String, MemoryUsage> pools) {
        long used = 0;
        for (Map.Entry<String, MemoryUsage> pool : pools.entrySet()) {
            String name = pool.getKey();
            if (name.contains("Metaspace") || name.contains("Code") || name.contains("Compressed Class")) {
                continue;
            }
            used += pool.getValue().getUsed();
        }
        return used;
    }

    /**
     * このスレッドがこれまでに確保した合計（バイト）。取れなければ -1。
     *
     * {@code com.sun.management.ThreadMXBean} は HotSpot 系にしかないので、名乗りを確かめてから使う。
     * 解析は 1 スレッドで走る（{@link RunControl}）ので、呼び出し元のスレッドぶんで足りる
     */
    private static long allocatedBytes() {
        if (ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean
                && bean.isThreadAllocatedMemorySupported()) {
            return bean.getCurrentThreadAllocatedBytes();
        }
        return -1;
    }

    /**
     * GC の回数と時間。{@code Concurrent} と名の付くものを回数に数えないのは、
     * これが「並行サイクル」の回数で、止まった回数ではないため（G1 では実測で 8 回ぶん食い違った）。
     * フル GC は {@code Old} / {@code MarkSweep} を含む名前で見分ける
     */
    private record Counters(long count, long fullCount, long millis) {

        static Counters read() {
            long count = 0;
            long fullCount = 0;
            long millis = 0;
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                long n = bean.getCollectionCount();
                if (n < 0) {
                    continue;   // この実装では数えられない
                }
                String name = bean.getName();
                if (name.contains("Concurrent")) {
                    continue;
                }
                count += n;
                millis += Math.max(0, bean.getCollectionTime());
                if (name.contains("Old") || name.contains("MarkSweep")) {
                    fullCount += n;
                }
            }
            return new Counters(count, fullCount, millis);
        }
    }
}
