// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jche.AnalysisSnapshot;
import jche.Exporter;
import jche.config.Config;
import jche.graph.MethodTable;
import jche.report.Csv;
import jche.util.CancelledException;
import jche.util.Log;
import jche.util.RunControl;

/**
 * サーバーモードの本体。標準入力から要求を読み、標準出力へ応答を返す（{@link Protocol}）。
 *
 * <p>誰のためにあるか: Eclipse プラグインである。プラグインは Eclipse と同じ JVM で動くので、
 * そこで解析すると「Eclipse を動かしている JDK・JDT」に結果が縛られる。解析をこのサーバー
 * （別プロセス・別 JDK・同梱の新しい JDT）に任せれば、その縛りが無くなる
 * （docs/out-of-process-analysis-design.md）。
 *
 * <p>状態はスナップショット1つだけ。{@code ANALYZE} で作り直し、{@code TREE} は
 * そのときの結果を読むだけなので、何度問い合わせても解析は走らない。
 *
 * <h2>標準出力の扱い</h2>
 * 標準出力はプロトコル専用にする。解析のログ（{@link Log}）は標準出力へ書くため、
 * 起動直後に {@code System.setOut} を標準エラーへ差し替えて混線を防ぐ。
 * ログは {@code #L} 行としても流すので、プラグイン側はそれをコンソールに出せる。
 */
public final class Server {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final BufferedReader in;
    private final Writer out;
    private final Path cacheRoot;

    /** 直近の解析結果。まだ解析していなければ null */
    private AnalysisSnapshot snapshot;
    /** 中止の要求。読み取りスレッドが立て、解析中のスレッドが見る */
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final BlockingQueue<String> commands = new ArrayBlockingQueue<>(64);
    private volatile boolean stopped;

    private Server(InputStream input, OutputStream output, Path cacheRoot) {
        this.in = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        this.out = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        this.cacheRoot = cacheRoot;
    }

    /**
     * サーバーを動かす。標準入力が閉じるか {@code SHUTDOWN} を受け取るまで返らない。
     *
     * @param cacheRoot キャッシュの置き場所の親（呼び出し側＝プラグインが決める）
     * @return 終了コード
     */
    public static int run(Path cacheRoot) {
        // 標準出力はプロトコル専用にする。ログや println が混ざると解読できなくなる
        PrintStream protocolStream = System.out;
        System.setOut(System.err);
        Server server = new Server(System.in, protocolStream, cacheRoot);
        return server.loop();
    }

    private int loop() {
        Thread reader = new Thread(this::readCommands, "jche-server-reader");
        reader.setDaemon(true);
        reader.start();
        // ログは標準エラーと #L 行の両方へ。プラグインは #L を拾ってコンソールに出す
        Log.attachSink(line -> emit(Protocol.LOG, line));
        try {
            while (!stopped) {
                String command = commands.poll(200, TimeUnit.MILLISECONDS);
                if (command == null) {
                    continue;
                }
                handle(command);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            Log.detachSink();
            flush();
        }
        return 0;
    }

    /** 読み取りは専用スレッド。解析中でも CANCEL / SHUTDOWN を受け取れるようにするため */
    private void readCommands() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                String name = trimmed.split("\t| ", 2)[0].toUpperCase(java.util.Locale.ROOT);
                if ("CANCEL".equals(name)) {
                    cancelled.set(true);     // 解析中のスレッドがすぐ見る
                    continue;
                }
                if ("SHUTDOWN".equals(name)) {
                    // 中止だけは割り込みで処理し、終了は待ち行列に積む。
                    // ここで即座に止めると、先に積んだ要求が処理されないまま終わってしまう
                    cancelled.set(true);
                }
                commands.put(trimmed);
            }
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // 標準入力が閉じたら終わる（親プロセスが消えた場合）。
            // 積んである要求を処理し終えてから止まるよう、ここも待ち行列を通す
            commands.offer("SHUTDOWN");
        }
    }

    private void handle(String line) {
        String[] parts = line.split(Protocol.SEP, -1);
        String name = parts[0].toUpperCase(java.util.Locale.ROOT);
        try {
            switch (name) {
                case "HELLO" -> hello();
                case "ANALYZE" -> analyze(arg(parts, 1));
                case "STATUS" -> status();
                case "FIND" -> find(arg(parts, 1));
                case "TREE" -> tree(parts);
                case "EXPORT" -> export(parts);
                case "PING" -> respondOk("pong");
                case "SHUTDOWN" -> {
                    stopped = true;
                    respondOk("bye");
                }
                default -> respondNg("unknown-command " + Protocol.escape(name));
            }
        } catch (CancelledException e) {
            respondNg("cancelled");
        } catch (Exception | Error e) {
            Log.error("要求の処理に失敗しました: " + line, e);
            respondNg("error " + Protocol.escape(String.valueOf(e)));
        }
    }

    private static String arg(String[] parts, int index) {
        return (index < parts.length) ? Protocol.unescape(parts[index]) : "";
    }

    // ------------------------------------------------------------
    // 要求ごとの処理
    // ------------------------------------------------------------

    private void hello() {
        respondOk("protocol=" + Protocol.VERSION
                + Protocol.SEP + "jdt=" + jdtVersion()
                + Protocol.SEP + "jvm=" + System.getProperty("java.version", "?")
                // この JDT で解析できる Java の上限。画面はこれを使って「新しい文法は取りこぼす」と伝えられる
                + Protocol.SEP + "maxJava=" + org.eclipse.jdt.core.JavaCore.latestSupportedJavaVersion());
    }

    /**
     * 使っている JDT の版。クラスパス上の jar の MANIFEST（Bundle-Version）から読む。
     * 「どの JDT で解析したか」は結果の説明に要るので、起動時に必ず伝える。
     */
    private static String jdtVersion() {
        try {
            java.security.CodeSource source =
                    org.eclipse.jdt.core.JavaCore.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return "?";
            }
            Path jar = Paths.get(source.getLocation().toURI());
            if (!Files.isRegularFile(jar)) {
                return "?";
            }
            try (java.util.jar.JarFile file = new java.util.jar.JarFile(jar.toFile())) {
                java.util.jar.Manifest manifest = file.getManifest();
                String version = (manifest == null) ? null
                        : manifest.getMainAttributes().getValue("Bundle-Version");
                return (version == null || version.isBlank()) ? "?" : version;
            }
        } catch (Exception e) {
            return "?";
        }
    }

    private void analyze(String configPath) throws Exception {
        if (configPath.isEmpty()) {
            respondNg("missing-config");
            return;
        }
        Path path = Paths.get(configPath);
        if (!Files.isRegularFile(path)) {
            respondNg("config-not-found " + Protocol.escape(configPath));
            return;
        }
        cancelled.set(false);
        RunControl.attach(new RunControl.Listener() {
            @Override
            public void progress(String label, long done, long total) {
                // 区切りの TAB は本物のまま。逃がすのはラベルの中身だけ
                emitLine(Protocol.PROGRESS + Protocol.SEP + Protocol.escape(label)
                        + Protocol.SEP + done + Protocol.SEP + total);
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }
        });
        try {
            Log.resetClock();
            Config config = new Config(path, cacheRoot, LocalDateTime.now());
            AnalysisSnapshot result = Exporter.analyze(config);
            result.inbound();           // 呼び出し元の索引もここで作る（TREE を待たせない）
            snapshot = result;
            status();
        } finally {
            RunControl.detach();
        }
    }

    private void status() {
        if (snapshot == null) {
            respondOk("analyzed=0");
            return;
        }
        respondOk("analyzed=1"
                + Protocol.SEP + "methods=" + snapshot.graph().methodCount()
                + Protocol.SEP + "edges=" + snapshot.graph().edgeCount()
                + Protocol.SEP + "inbound=" + snapshot.inbound().size()
                + Protocol.SEP + "at=" + snapshot.analyzedAt().format(STAMP)
                + Protocol.SEP + "root=" + Protocol.escape(snapshot.config().projectRoot.toString())
                + Protocol.SEP + "sourceLevel=" + snapshot.config().sourceLevel);
    }

    private void find(String key) {
        if (snapshot == null) {
            respondNg("not-analyzed");
            return;
        }
        MethodTable methods = snapshot.graph().methods();
        int id = methods.idOf(key);
        if (id < 0) {
            respondNg("not-found");
            return;
        }
        respondOk("key=" + Protocol.escape(methods.key(id))
                + Protocol.SEP + "label=" + Protocol.escape(methods.displayLabel(id))
                + Protocol.SEP + "file=" + Protocol.escape(nullToEmpty(methods.declFile(id)))
                + Protocol.SEP + "line=" + methods.declLine(id)
                + Protocol.SEP + "callers=" + snapshot.inbound().inDegree(id));
    }

    private void tree(String[] parts) {
        Request request = Request.of(parts);
        if (snapshot == null) {
            respondNg("not-analyzed");
            return;
        }
        MethodTable methods = snapshot.graph().methods();
        int rootId = methods.idOf(request.key);
        if (rootId < 0) {
            respondNg("not-found");
            return;
        }
        CallTree tree = new CallTree(snapshot, request.direction, request.filters);
        List<CallTree.Row> rows = tree.walk(rootId);
        List<CallTree.Row> byDepth = new ArrayList<>(rows);
        for (int i = 0; i < rows.size(); i++) {
            writeRow(tree, methods, rows.get(i), parentOf(byDepth, i));
        }
        respondOk("rows=" + rows.size());
    }

    /** 直前に出た「1つ浅い行」が親。行は深さ優先で並んでいるので後ろから探せばよい */
    private static CallTree.Row parentOf(List<CallTree.Row> rows, int index) {
        int wanted = rows.get(index).depth() - 1;
        for (int i = index - 1; i >= 0; i--) {
            if (rows.get(i).depth() == wanted) {
                return rows.get(i);
            }
        }
        return null;
    }

    private void writeRow(CallTree tree, MethodTable methods, CallTree.Row row, CallTree.Row parent) {
        StringBuilder sb = new StringBuilder(Protocol.ROW);
        sb.append(Protocol.SEP).append(row.depth());
        sb.append(Protocol.SEP).append(Protocol.escape(methods.key(row.methodId())));
        sb.append(Protocol.SEP).append(Protocol.escape(methods.displayLabel(row.methodId())));
        sb.append(Protocol.SEP).append(Protocol.escape(nullToEmpty(tree.callSiteFile(row, parent))));
        sb.append(Protocol.SEP).append(tree.callSiteLine(row));
        sb.append(Protocol.SEP).append(Protocol.escape(tree.reasonOf(row.edgeIndex())));
        sb.append(Protocol.SEP).append(flagsOf(tree, row, methods));
        emitLine(sb.toString());
    }

    /** 行に付ける印。画面はこれを見てアイコンや色を決める */
    private static String flagsOf(CallTree tree, CallTree.Row row, MethodTable methods) {
        List<String> flags = new ArrayList<>();
        if (row.recursive()) {
            flags.add("recursive");
        }
        if (row.truncated()) {
            flags.add("truncated");
        }
        if (tree.isGuessed(row.edgeIndex())) {
            flags.add("guessed");
        }
        if (tree.isDirectMatch(row.methodId())) {
            flags.add("match");
        }
        if (methods.declFile(row.methodId()) == null) {
            flags.add("nosource");
        }
        return String.join(",", flags);
    }

    /** 画面に出ている木をそのまま CSV へ。列は行の並びと同じ（深さ・キー・場所・理由・印） */
    private void export(String[] parts) throws IOException {
        Request request = Request.of(parts);
        if (snapshot == null) {
            respondNg("not-analyzed");
            return;
        }
        if (request.output.isEmpty()) {
            respondNg("missing-output");
            return;
        }
        MethodTable methods = snapshot.graph().methods();
        int rootId = methods.idOf(request.key);
        if (rootId < 0) {
            respondNg("not-found");
            return;
        }
        CallTree tree = new CallTree(snapshot, request.direction, request.filters);
        List<CallTree.Row> rows = tree.walk(rootId);
        Path output = Paths.get(request.output);
        try (BufferedWriter writer = Csv.writer(output, StandardCharsets.UTF_8, true)) {
            writer.write("depth,method,file,line,reason,note");
            writer.newLine();
            for (int i = 0; i < rows.size(); i++) {
                CallTree.Row row = rows.get(i);
                CallTree.Row parent = parentOf(rows, i);
                writer.write(String.valueOf(row.depth()));
                writer.write(Csv.DELIM);
                writer.write(Csv.esc(methods.key(row.methodId())));
                writer.write(Csv.DELIM);
                writer.write(Csv.esc(nullToEmpty(tree.callSiteFile(row, parent))));
                writer.write(Csv.DELIM);
                writer.write(String.valueOf(tree.callSiteLine(row)));
                writer.write(Csv.DELIM);
                writer.write(Csv.esc(tree.reasonOf(row.edgeIndex())));
                writer.write(Csv.DELIM);
                writer.write(row.recursive() ? "再帰" : (row.truncated() ? "深さ上限" : ""));
                writer.newLine();
            }
        }
        respondOk("rows=" + rows.size() + Protocol.SEP + "file=" + Protocol.escape(output.toString()));
    }

    /** TREE / EXPORT の引数（キー・向き・出力先・絞り込み） */
    private static final class Request {
        private String key = "";
        private CallTree.Direction direction = CallTree.Direction.CALLERS;
        private String output = "";
        private final TreeFilters filters = new TreeFilters();

        static Request of(String[] parts) {
            Request request = new Request();
            boolean isExport = "EXPORT".equalsIgnoreCase(parts[0]);
            request.key = arg(parts, 1);
            String dir = arg(parts, 2);
            request.direction = Protocol.CALLEES.equalsIgnoreCase(dir)
                    ? CallTree.Direction.CALLEES : CallTree.Direction.CALLERS;
            int from = 3;
            if (isExport) {
                request.output = arg(parts, 3);
                from = 4;
            }
            for (int i = from; i < parts.length; i++) {
                request.filters.apply(parts[i]);
            }
            return request;
        }
    }

    // ------------------------------------------------------------
    // 出力
    // ------------------------------------------------------------

    private void respondOk(String rest) {
        emitLine(rest.isEmpty() ? Protocol.OK : Protocol.OK + Protocol.SEP + rest);
    }

    private void respondNg(String reason) {
        emitLine(Protocol.NG + Protocol.SEP + reason);
    }

    private void emit(String prefix, String text) {
        emitLine(prefix + Protocol.SEP + Protocol.escape(text));
    }

    private synchronized void emitLine(String line) {
        try {
            out.write(line);
            out.write('\n');
            out.flush();      // 1行ごとに流す。相手は行単位で読んでいる
        } catch (IOException e) {
            stopped = true;   // 送り先が閉じた＝親プロセスが消えた
        }
    }

    private void flush() {
        try {
            out.flush();
        } catch (IOException e) {
            // 終了時なので何もできることはない
        }
    }

    private static String nullToEmpty(String value) {
        return (value == null) ? "" : value;
    }
}
