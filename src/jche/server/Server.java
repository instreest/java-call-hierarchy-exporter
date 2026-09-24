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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jche.AnalysisSnapshot;
import jche.Exporter;
import jche.analysis.JdtVersion;
import jche.config.Config;
import jche.graph.MethodTable;
import jche.report.Csv;
import jche.util.CancelledException;
import jche.util.Log;
import jche.util.RunControl;
import jche.util.Messages;

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
    /** 解析結果に宣言があるファイル（AT で「解析対象に無いファイル」を言い分けるため）。最初の AT で作る */
    private Set<String> analyzedFiles;
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

    /**
     * 読み取りは専用スレッド。解析中でも CANCEL を受け取れるようにするため。
     *
     * 中止するのは CANCEL だけで、SHUTDOWN は待ち行列に積むだけ。
     * 「積んだ要求を処理し終えてから終わる」が SHUTDOWN の意味であり、
     * 実行中の解析を打ち切りたい場合は CANCEL を先に送る。
     */
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
                // SHUTDOWN も待ち行列に積むだけで、実行中の解析は止めない。
                // 要求をまとめて流し込むクライアント（テストやスクリプト）では、読み取りが
                // 先に走って SHUTDOWN に届くため、ここで中止フラグを立てると
                // 先に積んだ ANALYZE がタイミング次第で中止されてしまう。
                // 実行中の解析を止めたい呼び出し側は、SHUTDOWN の前に CANCEL を送る
                // （Eclipse プラグインの ServerConnection#close がそうしている）
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
                case "AT" -> at(arg(parts, 1), arg(parts, 2));
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
            Log.error(Messages.format("server.requestFailed", line), e);
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
                + Protocol.SEP + "jdt=" + JdtVersion.current()
                + Protocol.SEP + "jvm=" + System.getProperty("java.version", "?")
                // この JDT で解析できる Java の上限。画面はこれを使って「新しい文法は取りこぼす」と伝えられる
                + Protocol.SEP + "maxJava=" + org.eclipse.jdt.core.JavaCore.latestSupportedJavaVersion());
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
            // 呼び出し元の索引もここで作る（TREE を待たせない）。
            // 解析の最後に必ず通る重い処理なので、始まりと終わりをログに残す
            Log.info(Messages.format("server.inbound.building", result.graph().methodCount()));
            Log.info(Messages.format("server.inbound.done", result.inbound().size()));
            snapshot = result;
            analyzedFiles = null;       // 解析し直したので AT の索引は作り直す
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
                + Protocol.SEP + "sourceLevel=" + snapshot.config().sourceLevel
                // 0 でなければ解析結果に抜けがある。画面がそれを出せるように必ず返す
                + Protocol.SEP + "syntaxErrors=" + snapshot.syntaxErrorFiles());
    }

    private void find(String key) {
        if (snapshot == null) {
            respondNg("not-analyzed");
            return;
        }
        MethodTable methods = snapshot.graph().methods();
        int id = methods.idOf(key);
        String how = "exact";
        if (id < 0) {
            id = findLoosely(methods, key);
            how = "loose";
        }
        if (id < 0) {
            respondNg("not-found");
            return;
        }
        respondFound(methods, id, "how=" + how);
    }

    /**
     * エディタのカーソル位置（ファイルと行）を囲むメソッドを引く（{@code AT ファイル 行}）。
     *
     * <p>誰のためにあるか: エディタのプラグインである。プラグイン側でメソッドのキー
     * （{@code 型FQN#名(消去型,…)}）を組み立てるには、型変数・内部クラス・可変長引数の
     * 綴りを解析側と合わせる必要があり、取りこぼしやすい（Eclipse 版の {@code MethodKeys}）。
     * ファイルと行だけ送ってもらえば、こちら側は自分の解析結果を引くだけで済む
     * （docs/vscode-plugin-design.md §4）。
     *
     * <p>D 行が終了行を持っている（docs/method-decl-range-qa.md）ので、メソッドの外
     * （フィールド宣言や空行）を指した場合は直前のメソッドを返さず {@code not-found} にする。
     * 見つかったときの応答は FIND と同じ形（{@code how=enclosing}）。
     *
     * <p>断り方は4つに分ける。呼び出し側が次にすることを選べるようにするためである。
     * <ul>
     *   <li>{@code not-analyzed} … まだ ANALYZE していない</li>
     *   <li>{@code file-not-analyzed} … そのファイルが解析結果に無い（source.folders の外・除外・新規ファイル）。
     *       設定の問題であり、カーソル位置の問題（下）とは対処が違う</li>
     *   <li>{@code not-found} … ファイルはあるが、その行を囲むメソッドが無い</li>
     *   <li>{@code bad-line} / {@code missing-position} … 引数が壊れている（呼び出し側の不具合）</li>
     * </ul>
     *
     * @param file プロジェクトルートからの相対パス（区切りは {@code /}）。ルート配下の絶対パスでもよい
     * @param lineText 行番号（1 始まり）
     */
    private void at(String file, String lineText) {
        if (snapshot == null) {
            respondNg("not-analyzed");
            return;
        }
        if (file == null || file.isEmpty() || lineText == null || lineText.isEmpty()) {
            respondNg("missing-position");
            return;
        }
        int line;
        try {
            line = Integer.parseInt(lineText.trim());
        } catch (NumberFormatException ignore) {
            respondNg("bad-line " + Protocol.escape(lineText));
            return;
        }
        if (line < 1) {
            respondNg("bad-line " + Protocol.escape(lineText));
            return;
        }
        String normalized = normalizePath(file);
        if (!analyzedFiles().contains(normalized)) {
            respondNg("file-not-analyzed");
            return;
        }
        MethodTable methods = snapshot.graph().methods();
        int id = methods.enclosingMethod(normalized, line);
        if (id < 0) {
            respondNg("not-found");
            return;
        }
        respondFound(methods, id, "how=enclosing");
    }

    /** 宣言があるファイルの集合。最初に引かれたときに作り、解析し直すまで使い回す */
    private Set<String> analyzedFiles() {
        Set<String> files = analyzedFiles;
        if (files != null) {
            return files;
        }
        MethodTable methods = snapshot.graph().methods();
        files = new HashSet<>();
        for (int id = 0; id < methods.size(); id++) {
            String file = methods.declFile(id);
            if (file != null) {
                files.add(normalizePath(file));
            }
        }
        analyzedFiles = files;
        return files;
    }

    /** FIND / AT が見つけたメソッドの応答（同じ形にそろえる） */
    private void respondFound(MethodTable methods, int id, String head) {
        respondOk(head
                + Protocol.SEP + "key=" + Protocol.escape(methods.key(id))
                + Protocol.SEP + "label=" + Protocol.escape(methods.displayLabel(id))
                + Protocol.SEP + "file=" + Protocol.escape(nullToEmpty(methods.declFile(id)))
                + Protocol.SEP + "line=" + methods.declLine(id)
                + Protocol.SEP + "endLine=" + methods.declEndLine(id)
                + Protocol.SEP + "callers=" + snapshot.inbound().inDegree(id));
    }

    /**
     * パスを {@link jche.config.ProjectLayout#relativeOf} と同じ綴りに寄せる。
     * 区切りを {@code /} にし、プロジェクトルート配下の絶対パスなら相対にする。
     * 呼び出し側（エディタ）が持っているのは絶対パスなので、ここで受けてしまうほうが親切である。
     */
    private String normalizePath(String path) {
        String normalized = path.replace('\\', '/').trim();
        if (snapshot != null) {
            String root = snapshot.config().projectRoot.toString().replace('\\', '/');
            if (!root.endsWith("/")) {
                root = root + "/";
            }
            if (normalized.startsWith(root)) {
                normalized = normalized.substring(root.length());
            }
        }
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    /**
     * キーがそのまま見つからないときの逃げ道。「型・メソッド名・引数の数」が一致するものを探し、
     * <b>1つに定まるときだけ</b>採る。
     *
     * <p>キーの引数は消去型の完全修飾名だが、呼び出し側（Eclipse プラグイン）は
     * ソースに書かれた型名から組み立てるため、型変数や内部クラスで綴りがずれることがある。
     * 候補が複数あるときに適当に選ぶと「別のメソッドの呼び出し元」を見せることになるので、
     * そのときは見つからなかったものとして扱う。
     *
     * @return メソッドID。決められなければ -1
     */
    private static int findLoosely(MethodTable methods, String key) {
        int hash = key.indexOf('#');
        int open = key.indexOf('(', hash + 1);
        int close = key.lastIndexOf(')');
        if (hash <= 0 || open <= hash || close <= open) {
            return -1;
        }
        String typeFqn = key.substring(0, hash);
        String name = key.substring(hash + 1, open);
        int paramCount = countParams(key.substring(open + 1, close));
        int found = -1;
        for (int id = 0; id < methods.size(); id++) {
            if (!typeFqn.equals(methods.typeFqn(id)) || !name.equals(methods.methodName(id))) {
                continue;
            }
            String otherKey = methods.key(id);
            int otherOpen = otherKey.indexOf('(');
            int otherClose = otherKey.lastIndexOf(')');
            if (otherOpen < 0 || otherClose <= otherOpen
                    || countParams(otherKey.substring(otherOpen + 1, otherClose)) != paramCount) {
                continue;
            }
            if (found >= 0) {
                return -1;   // 複数あるなら決められない
            }
            found = id;
        }
        return found;
    }

    private static int countParams(String params) {
        String trimmed = params.trim();
        if (trimmed.isEmpty()) {
            return 0;
        }
        int count = 1;
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) == ',') {
                count++;
            }
        }
        return count;
    }

    private void tree(String[] parts) {
        Request request = Request.of(parts);
        if (snapshot == null) {
            respondNg("not-analyzed");
            return;
        }
        MethodTable methods = snapshot.graph().methods();
        int rootId = rootIdOf(methods, request.key);
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
        int rootId = rootIdOf(methods, request.key);
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
                // CSV のセルは表示言語に関わらず英語（docs/nls-qa.md の Q6）
                writer.write(row.recursive() ? "recursive" : (row.truncated() ? "depth-limit" : ""));
                writer.newLine();
            }
        }
        respondOk("rows=" + rows.size() + Protocol.SEP + "file=" + Protocol.escape(output.toString()));
    }

    /** キーで引き、だめならゆるい照合も試す */
    private static int rootIdOf(MethodTable methods, String key) {
        int id = methods.idOf(key);
        return (id >= 0) ? id : findLoosely(methods, key);
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
