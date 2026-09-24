// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cache;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jche.util.Log;
import jche.util.Messages;

/**
 * キャッシュと同じフォルダに置く、1 回の実行の中だけで使う一時ファイル。
 *
 * <p>キャッシュを何度も読み直す代わりに、読んだものを小さな一時ファイルに書いておき、あとでそちらを読む
 * （ヒープに溜めない）。どれも実行の中で作って消す。名前はすべて {@code <キャッシュのファイル名>.<種類>-<乱数>.tmp}。
 * <pre>
 *   analysis-cache.tsv.deps-*.tmp          差分更新（パス1 → パス3）。有効なブロックの依存（I 行）
 *   analysis-cache.tsv.edges-*.tmp         グラフの構築。エッジの記録（{@code jche.graph.CallGraphBuilder}）
 *   analysis-cache.tsv.unresolved-*.tmp    型解決に失敗した呼び出しの一覧に出す行（{@code jche.graph.UnresolvedCalls}）
 * </pre>
 * キャッシュ本体の一時ファイル（{@code analysis-cache.tsv.tmp}）は中断からの引き継ぎに使うので、ここには入れない
 * （{@code jche.analysis.CacheUpdater}）。強制終了のときにも消さない（次の実行が引き継ぐ）。
 *
 * <p>消す経路は 3 つある。
 * <ul>
 *   <li>ふつうは作った側が {@code finally}（try-with-resources）で消す（成功・失敗・中止のどれでも）</li>
 *   <li>{@code finally} が動かない終わり方（Ctrl+C・{@code kill}＝SIGINT / SIGTERM。GitHub Actions の中止・
 *       時間切れ、IDE が解析サーバーを止めたとき）は、JVM の終了フック（{@link #create} が最初に 1 回だけ登録する）が
 *       まだ消していないものを消す</li>
 *   <li>終了フックも動かない終わり方（SIGKILL・停電）で残ったものは、次の実行の最初（{@link #deleteLeftovers}）に消す</li>
 * </ul>
 * GitHub Actions のようにキャッシュのフォルダを丸ごと保存する使い方でも、実行が終わった時点では残っていない
 * （action.yml も保存の前に念のため消す）。
 */
public final class TempFiles {

    /** 差分更新が使う依存の索引（パス1 で書き、パス3 で読む） */
    public static final String DEPS = "deps";
    /** エッジの記録 */
    public static final String EDGES = "edges";
    /** 型解決に失敗した呼び出しの一覧に出す行 */
    public static final String UNRESOLVED = "unresolved";

    /** 一時ファイルの種類（{@link #create}。前の実行の残り物を探すときにも使う） */
    private static final List<String> KINDS = List.of(DEPS, EDGES, UNRESOLVED);
    private static final String SUFFIX = ".tmp";

    /**
     * 作って、まだ消していない一時ファイル。JVM の終了フックが消す。
     * このオブジェクトを錠にして、{@code hookInstalled}・{@code shuttingDown} もあわせて守る
     */
    private static final Set<Path> LIVE = new HashSet<>();
    /** 終了フックを登録したか（登録は 1 回だけ） */
    private static boolean hookInstalled;
    /**
     * 終了フックが動き始めた。これより後に作ったファイルはフックが消せないので、作らせない
     * （解析のスレッドは終了フックと並んで動き続けるため）
     */
    private static boolean shuttingDown;

    private TempFiles() {
    }

    /**
     * キャッシュと同じフォルダに一時ファイルを作る（{@code <キャッシュのファイル名>.<種類>-<乱数>.tmp}）。
     * 名前が実行ごとに違うのは、解析サーバーと CLI が同じキャッシュのフォルダでたまたま重なっても
     * 互いのファイルを上書きせず、前の実行の残り物を読むこともないため。
     *
     * <p>作ったファイルは {@link #delete} で消すまで覚えておき、強制終了（SIGINT / SIGTERM）のときは
     * JVM の終了フックが消す。
     *
     * @throws IOException 作れない。JVM が終わろうとしているとき（終了フックが動き始めた後）も作らない
     */
    public static Path create(Path cacheFile, String kind) throws IOException {
        Path dir = cacheFile.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        synchronized (LIVE) {
            if (shuttingDown) {
                throw new IOException(Messages.format("cache.tempShuttingDown", dir));
            }
            if (!hookInstalled) {
                try {
                    Runtime.getRuntime().addShutdownHook(new Thread(TempFiles::deleteAllOnExit, "jche-temp-files"));
                } catch (IllegalStateException e) {
                    // 終了が始まっている（ほかの終了フックが動いている）。作っても消す機会が無い
                    throw new IOException(Messages.format("cache.tempShuttingDown", dir), e);
                }
                hookInstalled = true;
            }
            // 作ってから覚えるまでのあいだに終了フックが動くと取りこぼすので、錠の中で作る
            Path file = Files.createTempFile(dir, cacheFile.getFileName() + "." + kind + "-", SUFFIX);
            LIVE.add(file);
            return file;
        }
    }

    /** 前の実行が異常終了して残した一時ファイルを消す（実行の最初に 1 回） */
    public static void deleteLeftovers(Path cacheFile) {
        Path dir = cacheFile.toAbsolutePath().getParent();
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        String name = cacheFile.getFileName().toString();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path p : files) {
                String n = p.getFileName().toString();
                for (String kind : KINDS) {
                    if (n.startsWith(name + "." + kind + "-") && n.endsWith(SUFFIX)) {
                        delete(p);
                    }
                }
            }
        } catch (IOException e) {
            Log.info(Messages.format("cache.tempNotDeleted", dir, e));
        }
    }

    /**
     * 消す（null なら何もしない）。消せなくても解析の結果には関わらないので、{@code Log.info} で知らせるだけにする
     * （終了フックがもう一度消そうとし、それでも残ったものは次の実行の {@link #deleteLeftovers} が消す）
     */
    public static void delete(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            Log.info(Messages.format("cache.tempNotDeleted", file, e));
            return;   // 覚えたままにして、終了フックにもう一度消させる
        }
        synchronized (LIVE) {
            LIVE.remove(file);
        }
    }

    /**
     * JVM の終了フック。まだ消していない一時ファイルを消す。
     * ログは出さない（終了の途中で、ログの書き出し先がもう閉じているかもしれない）。
     * 消せなかったものは次の実行の {@link #deleteLeftovers} が消す
     */
    private static void deleteAllOnExit() {
        List<Path> left;
        synchronized (LIVE) {
            shuttingDown = true;
            left = new ArrayList<>(LIVE);
            LIVE.clear();
        }
        for (Path p : left) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException | RuntimeException e) {
                // 次の実行が消す
            }
        }
    }
}
