// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import jche.eclipse.server.JavaLocator;
import jche.eclipse.server.JdkDownload;
import jche.eclipse.server.ServerConnection;
import jche.eclipse.server.ServerLauncher;
import jche.eclipse.server.ServerResponse;
import jche.eclipse.server.ServerRow;
import jche.eclipse.server.ServerTree;

/**
 * プラグインのクライアント層（jche.eclipse.server）を Eclipse 無しで動かす検査。
 *
 * Eclipse を起動しないと確かめられない部分（画面）はここでは見ない。見るのは
 * 「子プロセスを起動し、プロトコルで話し、返ってきた行を木に組み直せるか」までで、
 * そこが壊れていれば画面には何も出ないのだから、ここを自動で守る値打ちがある。
 *
 * 使い方: java -cp <クライアントのクラス>:. ClientProbe <java> <解析用クラスパス> <作業フォルダ>
 */
public final class ClientProbe {

    public static void main(String[] args) throws Exception {
        File java = new File(args[0]);
        List<File> classpath = new ArrayList<File>();
        for (String entry : args[1].split(File.pathSeparator)) {
            classpath.add(new File(entry));
        }
        File work = new File(args[2]);
        String config = args[3];
        String target = args[4];

        checkJavaLocator(java);
        checkJdkDownloadUrls();

        final AtomicInteger progressCount = new AtomicInteger();
        final AtomicInteger logCount = new AtomicInteger();
        ServerConnection connection = ServerLauncher.start(java, classpath, new File(work, "cache"),
                Arrays.asList("-Xmx512m"), null);
        connection.setListener(new ServerConnection.Listener() {
            @Override
            public void progress(String label, long done, long total) {
                progressCount.incrementAndGet();
            }

            @Override
            public void log(String line) {
                logCount.incrementAndGet();
            }
        });
        try {
            ServerResponse hello = connection.request(ServerConnection.DEFAULT_TIMEOUT_MS, "HELLO", "1");
            check("HELLO", hello.isOk() && "1".equals(hello.field("protocol"))
                    && !hello.field("jdt").isEmpty() && !hello.field("jvm").isEmpty(),
                    "jdt=" + hello.field("jdt") + " jvm=" + hello.field("jvm")
                            + " maxJava=" + hello.field("maxJava"));

            ServerResponse analyze = connection.request(300_000L, "ANALYZE", config);
            check("ANALYZE", analyze.isOk() && analyze.longField("methods", 0) > 0,
                    "methods=" + analyze.field("methods") + " edges=" + analyze.field("edges"));
            check("進捗の受信", progressCount.get() > 0, "#P " + progressCount.get() + " 回");
            check("ログの受信", logCount.get() > 0, "#L " + logCount.get() + " 行");

            ServerResponse find = connection.request(ServerConnection.DEFAULT_TIMEOUT_MS, "FIND", target);
            check("FIND", find.isOk() && target.equals(find.field("key")),
                    "how=" + find.field("how") + " callers=" + find.field("callers"));

            ServerResponse missing = connection.request(ServerConnection.DEFAULT_TIMEOUT_MS,
                    "FIND", "no.such.Type#nope()");
            check("無いメソッドは NG", !missing.isOk() && "not-found".equals(missing.reason()),
                    missing.raw());

            ServerResponse tree = connection.request(60_000L, "TREE", target, "callers", "depth=2");
            ServerTree built = ServerTree.of(tree.rows());
            check("TREE", tree.isOk() && built.root() != null
                    && target.equals(built.root().row().key()) && !built.root().children().isEmpty(),
                    "rows=" + tree.rows().size() + " nodes=" + built.size()
                            + " 直接の呼び出し元=" + built.root().children().size());
            check("木の組み直し", built.size() == tree.rows().size(),
                    built.size() + " == " + tree.rows().size());

            ServerTree.Node truncated = firstTruncated(built.root());
            if (truncated != null) {
                ServerResponse deeper = connection.request(60_000L,
                        "TREE", truncated.row().key(), "callers", "depth=2");
                check("打ち切った節点から辿り直せる", deeper.isOk() && deeper.rows().size() > 1,
                        truncated.row().key() + " -> rows=" + deeper.rows().size());
            } else {
                check("打ち切った節点から辿り直せる", true, "打ち切りが無かったので省略");
            }

            ServerResponse filtered = connection.request(60_000L,
                    "TREE", target, "callers", "depth=5", "text=zzz-no-such-name");
            check("絞り込み", filtered.isOk() && filtered.rows().size() < tree.rows().size(),
                    filtered.rows().size() + " < " + tree.rows().size());

            File csv = new File(work, "out.csv");
            ServerResponse export = connection.request(60_000L,
                    "EXPORT", target, "callers", csv.getAbsolutePath(), "depth=3");
            check("EXPORT", export.isOk() && csv.isFile() && csv.length() > 0,
                    "rows=" + export.field("rows") + " " + csv.length() + " バイト");

            // TAB や改行を含む語を投げても、行が壊れずに応答が返ること
            ServerResponse escaped = connection.request(ServerConnection.DEFAULT_TIMEOUT_MS,
                    "FIND", "a\tb\nc#x()");
            check("逃がし方の往復", !escaped.isOk() && "not-found".equals(escaped.reason()), escaped.raw());
        } finally {
            connection.close();
        }
        check("終了後は閉じている", !connection.isAlive(), "");
        System.out.println("DONE");
    }

    /** 解析に使う JDK の選び方（設定や環境変数の読み方は Eclipse 側の仕事なので、ここは選ぶ所だけ） */
    private static void checkJavaLocator(File java) {
        int version = JavaLocator.versionOf(java);
        check("JDK の版を読める", version >= JavaLocator.MINIMUM, java + " -> Java " + version);

        JavaLocator.Found found = JavaLocator.choose(Arrays.asList(
                new File("/no/such/java"), java, new File("/another/missing")));
        check("使える JDK を選ぶ（無いものは飛ばす）",
                found != null && found.version() == version, String.valueOf(found));

        check("17 未満しか無ければ選ばない",
                JavaLocator.choose(Arrays.asList(new File("/no/such/java"))) == null, "");
        check("1.8 形式の版を読める", JavaLocator.parseVersion("java version \"1.8.0_402\"") == 8, "");
        check("新しい形式の版を読める", JavaLocator.parseVersion("openjdk version \"25.0.3\" 2026-01-20") == 25, "");
    }

    /** 取得先の URL の組み立て（実際には取りに行かない。閉域でも検査できるように） */
    private static void checkJdkDownloadUrls() {
        String windows = JdkDownload.urlFor(25, "Windows 11", "amd64");
        String linux = JdkDownload.urlFor(25, "Linux", "aarch64");
        String mac = JdkDownload.urlFor(25, "Mac OS X", "x86_64");
        check("取得先の URL（Windows/x64）",
                windows.endsWith("/25/ga/windows/x64/jdk/hotspot/normal/eclipse"), windows);
        check("取得先の URL（Linux/aarch64）",
                linux.endsWith("/25/ga/linux/aarch64/jdk/hotspot/normal/eclipse"), "");
        check("取得先の URL（macOS/x64）",
                mac.endsWith("/25/ga/mac/x64/jdk/hotspot/normal/eclipse"), "");
        check("取得するファイルの種類が OS で変わる",
                "jdk.zip".equals(JdkDownload.archiveNameFor("Windows 11"))
                        && "jdk.tar.gz".equals(JdkDownload.archiveNameFor("Linux")), "");
    }

    private static ServerTree.Node firstTruncated(ServerTree.Node node) {
        if (node == null) {
            return null;
        }
        if (node.isTruncated()) {
            return node;
        }
        for (ServerTree.Node child : node.children()) {
            ServerTree.Node found = firstTruncated(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static void check(String what, boolean ok, String detail) {
        System.out.println((ok ? "OK   " : "NG   ") + what + (detail.isEmpty() ? "" : "  （" + detail + "）"));
    }
}
