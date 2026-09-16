import jdk.jfr.consumer.*;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * JFR の記録を「時間帯」で切って集計する。
 *
 *   java JfrReport <file.jfr> <appPackagePrefix> [fromSec] [toSec]
 *
 * jfr print / jfr view には時間帯で絞る機能が無いため、RecordingFile で自前に読む。
 * 時刻は記録の最初のイベントからの相対秒。
 */
public class JfrReport {

    record Sample(Instant t, List<String> frames) {}

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        String app = args[1];
        double from = args.length > 2 ? Double.parseDouble(args[2]) : 0;
        double to   = args.length > 3 ? Double.parseDouble(args[3]) : Double.MAX_VALUE;

        List<RecordedEvent> all = new ArrayList<>();
        try (RecordingFile rf = new RecordingFile(file)) {
            while (rf.hasMoreEvents()) {
                all.add(rf.readEvent());
            }
        }
        Instant base = all.stream().map(RecordedEvent::getStartTime).min(Instant::compareTo).orElseThrow();
        double last = sec(base, all.stream().map(RecordedEvent::getEndTime).max(Instant::compareTo).orElseThrow());

        // --- 時間帯の目安: どのファイルにいつ書いたか（フェーズの境目を探すため） ---
        System.out.printf("記録全体: 0.000s 〜 %.3fs%n", last);
        Map<String, double[]> writes = new LinkedHashMap<>();
        for (RecordedEvent e : all) {
            if (!e.getEventType().getName().equals("jdk.FileWrite")) continue;
            String p = e.getString("path");
            if (p == null) continue;
            String key = p.substring(p.lastIndexOf('/') + 1);
            double s = sec(base, e.getStartTime());
            writes.compute(key, (k, v) -> v == null ? new double[]{s, s, 1} : new double[]{v[0], s, v[2] + 1});
        }
        if (!writes.isEmpty()) {
            System.out.println("書き込み先ごとの時間帯（フェーズの境目の手がかり）:");
            writes.forEach((k, v) -> System.out.printf("  %-28s %7.3fs 〜 %7.3fs  (%.0f 回)%n", k, v[0], v[1], v[2]));
        }

        List<RecordedEvent> win = all.stream()
                .filter(e -> { double s = sec(base, e.getStartTime()); return s >= from && s <= to; })
                .collect(Collectors.toList());
        System.out.printf("%n===== 集計する時間帯: %.3fs 〜 %s （イベント %d 件）=====%n",
                from, to == Double.MAX_VALUE ? "最後" : String.format("%.3fs", to), win.size());

        // --- 1. 待ち・停止の内訳 ---
        System.out.println("\n[1] 待ち・停止（この時間帯の合計）");
        durationOf(win, "jdk.GCPhasePause", "GC の停止 (STW)");
        durationOf(win, "jdk.FileRead", "ファイル読み込み");
        durationOf(win, "jdk.FileWrite", "ファイル書き込み");
        durationOf(win, "jdk.JavaMonitorEnter", "ロック待ち");
        durationOf(win, "jdk.JavaMonitorWait", "wait()");
        durationOf(win, "jdk.ThreadPark", "park()");

        // --- 2. ヒープ ---
        System.out.println("\n[2] ヒープ");
        long peakBefore = 0, minAfter = Long.MAX_VALUE;
        for (RecordedEvent e : win) {
            if (!e.getEventType().getName().equals("jdk.GCHeapSummary")) continue;
            long used = e.getLong("heapUsed");
            if ("Before GC".equals(e.getString("when"))) peakBefore = Math.max(peakBefore, used);
            else minAfter = Math.min(minAfter, used);
        }
        System.out.printf("  GC 直前の最大使用量（＝ヒープ使用量の最大）: %,d MB%n", peakBefore / 1048576);
        if (minAfter != Long.MAX_VALUE) {
            System.out.printf("  GC 直後の最小使用量（＝生き残っている量の目安）: %,d MB%n", minAfter / 1048576);
        }
        // 累計の割り当て量（スレッドごとの累計。GC で回収されたものも含む）
        Map<String, Long> alloc = new LinkedHashMap<>();
        for (RecordedEvent e : win) {
            if (!e.getEventType().getName().equals("jdk.ThreadAllocationStatistics")) continue;
            RecordedThread th = e.getThread("thread");
            alloc.merge(th == null ? "?" : th.getJavaName(), e.getLong("allocated"), Math::max);
        }
        long total = alloc.values().stream().mapToLong(Long::longValue).sum();
        System.out.printf("  累計ヒープ割り当て量（全スレッド、GC 済みも含む）: %,d MB%n", total / 1048576);
        alloc.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(4)
             .forEach(en -> System.out.printf("      %-24s %,d MB%n", en.getKey(), en.getValue() / 1048576));

        // --- 3. 処理時間の機能内訳 ---
        System.out.println("\n[3] 処理時間の機能内訳（ExecutionSample を、スタック最内の " + app + " フレームで寄せる）");
        breakdown(win, "jdk.ExecutionSample", app, null);

        // --- 4. メモリ使用量の機能内訳 ---
        System.out.println("\n[4] メモリ割り当ての機能内訳（ObjectAllocationSample の weight を同じ寄せ方で）");
        breakdown(win, "jdk.ObjectAllocationSample", app, "weight");
    }

    static double sec(Instant base, Instant t) {
        return (t.toEpochMilli() - base.toEpochMilli()) / 1000.0
                + (t.getNano() % 1_000_000 - base.getNano() % 1_000_000) / 1e9;
    }

    static void durationOf(List<RecordedEvent> win, String type, String label) {
        long n = 0, ns = 0;
        for (RecordedEvent e : win) {
            if (e.getEventType().getName().equals(type)) {
                n++;
                ns += e.getDuration().toNanos();
            }
        }
        System.out.printf("  %-22s %6d 件  合計 %,8.1f ms%n", label, n, ns / 1e6);
    }

    /** 最内の app フレームで寄せる。weightField が null なら件数、あればその値を足す */
    static void breakdown(List<RecordedEvent> win, String type, String app, String weightField) {
        Map<String, long[]> byApp = new HashMap<>();   // [重み, 件数]
        Map<String, long[]> byLeaf = new HashMap<>();
        long grand = 0;
        for (RecordedEvent e : win) {
            if (!e.getEventType().getName().equals(type)) continue;
            RecordedStackTrace st = e.getStackTrace();
            if (st == null) continue;
            long w = weightField == null ? 1 : e.getLong(weightField);
            grand += w;
            List<RecordedFrame> fs = st.getFrames();
            String leaf = fs.isEmpty() ? "?" : name(fs.get(0));
            byLeaf.computeIfAbsent(leaf, k -> new long[2])[0] += w;
            String owner = "(" + app + " の外側だけ)";
            for (RecordedFrame f : fs) {
                String n = name(f);
                if (n.startsWith(app)) { owner = n; break; }
            }
            long[] a = byApp.computeIfAbsent(owner, k -> new long[2]);
            a[0] += w; a[1]++;
        }
        print(byApp, grand, weightField != null, 14);
        System.out.println("  -- 参考: 最内フレーム（JDT 内部も含む）--");
        print(byLeaf, grand, weightField != null, 8);
    }

    static void print(Map<String, long[]> m, long grand, boolean bytes, int limit) {
        m.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0])).limit(limit)
         .forEach(e -> {
             long w = e.getValue()[0];
             String amount = bytes ? String.format("%,8d MB", w / 1048576) : String.format("%,6d サンプル", w);
             System.out.printf("  %5.1f%%  %s  %s%n", 100.0 * w / grand, amount, e.getKey());
         });
    }

    static String name(RecordedFrame f) {
        RecordedMethod m = f.getMethod();
        return m.getType().getName() + "." + m.getName();
    }
}
