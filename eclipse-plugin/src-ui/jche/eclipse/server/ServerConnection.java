// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 解析サーバー（子プロセス）との1本の接続。
 *
 * <p>プラグインが解析に触れるのはこのクラス越しだけである。解析のコードも JDT も
 * 子プロセス側にあるので、Eclipse の中には何も要らない。
 *
 * <h2>スレッドの約束</h2>
 * <ul>
 *   <li>受信は専用スレッド。{@code #P}（進捗）と {@code #L}（ログ）はその場で
 *       {@link Listener} に渡し、それ以外は応答の待ち行列へ積む</li>
 *   <li><b>標準エラーも専用スレッドで読み続ける</b>。読まずに放っておくと、子プロセスが
 *       パイプの緩衝（Windows では数KB）を埋めた時点で書き込みのまま止まり、解析が
 *       永久に返らなくなる（docs/eclipse-plugin-progress-log-qa.md の Q1）</li>
 *   <li>{@link #request} は1件ずつ直列に流す（{@code conversation} で守る）。
 *       送信そのものは別の錠（{@code writeLock}）なので、解析中でも
 *       {@link #cancel()} を割り込ませられる</li>
 * </ul>
 */
public final class ServerConnection {

    /** 進捗とログの受け口。画面（進捗バーとコンソール）へ橋渡しする */
    public interface Listener {
        void progress(String label, long done, long total);

        void log(String line);
    }

    /** 応答を待つ既定の上限。解析は長いので ANALYZE では別の値を渡す */
    public static final long DEFAULT_TIMEOUT_MS = 30_000L;

    /** 諦めた要求の応答が遅れて届くのを待つ上限。これを過ぎたら接続ごと捨てる */
    private static final long STALE_WAIT_MS = 5_000L;

    /** 標準エラーの行に付ける印。解析本体のログと見分けるため */
    public static final String STDERR_PREFIX = "[stderr] ";

    /** 応答しなかったときに添える、直近のログの行数 */
    private static final int RECENT_LINES = 20;

    private final Process process;
    private final BufferedWriter out;
    private final BufferedReader in;
    private final BufferedReader err;

    /** 直近に受け取ったログ（応答が無かったときに、どこで止まったかを添えるため） */
    private final Deque<String> recent = new ArrayDeque<String>();
    /**
     * 諦めた（応答を待つのをやめた）要求の数。遅れて届く応答を読み捨てるために数える。
     * 触るのは {@code conversation} を持っている間だけ
     */
    private int abandoned;
    /** 直近に受け取った進捗。まだ無ければ null */
    private volatile String lastProgress;
    /** 子プロセスから最後に何か受け取った時刻 */
    private volatile long lastHeard = System.currentTimeMillis();
    private final LinkedBlockingQueue<String> responses = new LinkedBlockingQueue<String>();
    private final Object conversation = new Object();
    private final Object writeLock = new Object();
    private volatile Listener listener;
    private volatile boolean closed;

    ServerConnection(Process process, Charset charset) {
        this.process = process;
        this.out = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), charset));
        this.in = new BufferedReader(new InputStreamReader(process.getInputStream(), charset));
        this.err = new BufferedReader(new InputStreamReader(process.getErrorStream(), charset));
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop();
            }
        }, "jche-server-reader");
        reader.setDaemon(true);
        reader.start();
        Thread errorReader = new Thread(new Runnable() {
            @Override
            public void run() {
                errorLoop();
            }
        }, "jche-server-stderr");
        errorReader.setDaemon(true);
        errorReader.start();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    private void readLoop() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                lastHeard = System.currentTimeMillis();
                if (line.startsWith("#P")) {
                    notifyProgress(line);
                } else if (line.startsWith("#L")) {
                    String text = "";
                    String[] parts = line.split(Wire.SEP, -1);
                    if (parts.length > 1) {
                        text = Wire.unescape(parts[1]);
                    }
                    remember(text);
                    Listener current = listener;
                    if (current != null) {
                        current.log(text);
                    }
                } else if (!line.isEmpty()) {
                    responses.put(line);
                }
            }
        } catch (IOException e) {
            // 相手が閉じた。closed で判断できるのでここでは黙る
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closed = true;
            responses.offer("NG" + Wire.SEP + "disconnected");
        }
    }

    /**
     * 標準エラーを読み続ける。中身は JVM の警告・GC のログ・OutOfMemoryError の
     * スタックトレースなどで、プロトコルの行ではない。読み捨てずにログへ流す
     */
    private void errorLoop() {
        try {
            String line;
            while ((line = err.readLine()) != null) {
                String text = STDERR_PREFIX + line;
                remember(text);
                Listener current = listener;
                if (current != null) {
                    current.log(text);
                }
            }
        } catch (IOException e) {
            // 相手が閉じた。標準出力側で気付くのでここでは黙る
        }
    }

    /** 直近の行を覚えておく。応答が無かったときに「どこで止まったか」を添えるため */
    private void remember(String line) {
        if (line == null || line.isEmpty()) {
            return;
        }
        synchronized (recent) {
            recent.addLast(line);
            while (recent.size() > RECENT_LINES) {
                recent.removeFirst();
            }
        }
    }

    /** 応答が無かったときに出す、止まった場所の手がかり */
    private String stallDiagnosis() {
        StringBuilder sb = new StringBuilder();
        String progress = lastProgress;
        sb.append("最後の進捗: ").append((progress == null) ? "（進捗は1件も届いていません）" : progress);
        sb.append(" / 最後に受信してから ")
                .append((System.currentTimeMillis() - lastHeard) / 1000L).append(" 秒");
        List<String> lines = new ArrayList<String>();
        synchronized (recent) {
            lines.addAll(recent);
        }
        if (!lines.isEmpty()) {
            sb.append("\n直近のログ:");
            for (String line : lines) {
                sb.append("\n  ").append(line);
            }
        }
        return sb.toString();
    }

    private void notifyProgress(String line) {
        Listener current = listener;
        String[] parts = line.split(Wire.SEP, -1);
        if (parts.length < 4) {
            return;
        }
        try {
            String label = Wire.unescape(parts[1]);
            long done = Long.parseLong(parts[2]);
            long total = Long.parseLong(parts[3]);
            lastProgress = (total > 0) ? (label + " " + done + "/" + total) : (label + " " + done);
            if (current != null) {
                current.progress(label, done, total);
            }
        } catch (NumberFormatException e) {
            // 進捗が読めなくても実害はない
        }
    }

    /** 要求を1件送り、応答（と、それまでに来た行）を待つ */
    public ServerResponse request(long timeoutMs, String... words) throws IOException {
        synchronized (conversation) {
            discardStale();
            writeLine(join(words));
            List<ServerRow> rows = new ArrayList<ServerRow>();
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (true) {
                long wait = deadline - System.currentTimeMillis();
                String line;
                try {
                    line = (wait <= 0) ? null : responses.poll(wait, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("応答の待機が中断されました", e);
                }
                if (line == null) {
                    // 諦めた後に遅れて応答が来ると、次の要求の答えと取り違える。
                    // 数えておいて、次の要求の前に読み捨てる（discardStale）
                    abandoned++;
                    throw new ServerTimeoutException("解析サーバーが応答しません（"
                            + timeoutMs + " ms）。\n" + stallDiagnosis());
                }
                if (line.startsWith("R" + Wire.SEP)) {
                    ServerRow row = ServerRow.parse(line.split(Wire.SEP, -1));
                    if (row != null) {
                        rows.add(row);
                    }
                    continue;
                }
                return ServerResponse.of(line, rows);
            }
        }
    }

    /**
     * 前に諦めた要求の応答を読み捨てる。行の並びで要求と応答を対応づけているので、
     * 読み捨てないと、次の要求が「前の要求の答え」を受け取ってしまう。
     * 応答が {@link #STALE_WAIT_MS} 待っても来なければ、接続ごと捨てる
     */
    private void discardStale() throws IOException {
        while (abandoned > 0) {
            String line;
            try {
                line = responses.poll(STALE_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("応答の待機が中断されました", e);
            }
            if (line == null) {
                close();
                throw new IOException("前の要求の応答が返らないままです。"
                        + "解析プロセスを停止しました（次の要求で起動し直します）");
            }
            if (!line.startsWith("R" + Wire.SEP)) {
                abandoned--;    // R 行は途中の行。OK / NG が来たら、その要求は終わり
            }
        }
    }

    /** 実行中の解析を止める。応答を待っているスレッドとは別のスレッドから呼べる */
    public void cancel() {
        try {
            writeLine("CANCEL");
        } catch (IOException e) {
            // 既に閉じている
        }
    }

    /** 行き先の語を TAB でつなぐ。語の中の TAB・改行は逃がす */
    private static String join(String[] words) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) {
                sb.append(Wire.SEP);
            }
            sb.append(Wire.escape(words[i]));
        }
        return sb.toString();
    }

    private void writeLine(String line) throws IOException {
        synchronized (writeLock) {
            if (closed) {
                throw new IOException("解析サーバーとの接続が閉じています");
            }
            out.write(line);
            out.write('\n');
            out.flush();
        }
    }

    public boolean isAlive() {
        return !closed && process.isAlive();
    }

    /**
     * 行儀よく終わらせる。応じなければ止める。
     *
     * SHUTDOWN は「積んだ要求を処理し終えてから終わる」という意味なので、解析中に送っても
     * すぐには効かない。ビューを閉じたときは待たせたくないので、先に CANCEL を送って
     * 実行中の解析をバッチの切れ目で止めてから SHUTDOWN を送る
     */
    public void close() {
        if (closed && !process.isAlive()) {
            return;
        }
        cancel();
        try {
            writeLine("SHUTDOWN");
        } catch (IOException e) {
            // 既に閉じている
        }
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        } finally {
            closed = true;
        }
    }
}
