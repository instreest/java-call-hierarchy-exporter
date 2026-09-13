// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
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

    private final Process process;
    private final BufferedWriter out;
    private final BufferedReader in;
    private final LinkedBlockingQueue<String> responses = new LinkedBlockingQueue<String>();
    private final Object conversation = new Object();
    private final Object writeLock = new Object();
    private volatile Listener listener;
    private volatile boolean closed;

    ServerConnection(Process process, Charset charset) {
        this.process = process;
        this.out = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), charset));
        this.in = new BufferedReader(new InputStreamReader(process.getInputStream(), charset));
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop();
            }
        }, "jche-server-reader");
        reader.setDaemon(true);
        reader.start();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    private void readLoop() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("#P")) {
                    notifyProgress(line);
                } else if (line.startsWith("#L")) {
                    Listener current = listener;
                    if (current != null) {
                        String[] parts = line.split(Wire.SEP, -1);
                        current.log(parts.length > 1 ? Wire.unescape(parts[1]) : "");
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

    private void notifyProgress(String line) {
        Listener current = listener;
        if (current == null) {
            return;
        }
        String[] parts = line.split(Wire.SEP, -1);
        if (parts.length < 4) {
            return;
        }
        try {
            current.progress(Wire.unescape(parts[1]), Long.parseLong(parts[2]), Long.parseLong(parts[3]));
        } catch (NumberFormatException e) {
            // 進捗が読めなくても実害はない
        }
    }

    /** 要求を1件送り、応答（と、それまでに来た行）を待つ */
    public ServerResponse request(long timeoutMs, String... words) throws IOException {
        synchronized (conversation) {
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
                    throw new IOException("解析サーバーが応答しません（" + timeoutMs + " ms）");
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

    /** 行儀よく終わらせる。応じなければ止める */
    public void close() {
        if (closed && !process.isAlive()) {
            return;
        }
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
