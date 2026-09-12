// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse.server;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * 解析サーバーを子プロセスとして起動する。
 *
 * <p>起動する JVM も、使う JDT も、Eclipse のものではない。バンドルに同梱した
 * {@code lib/jche-core.jar} と {@code lib/jdt/*.jar} を渡し、利用者が選んだ JDK で走らせる
 * （docs/out-of-process-analysis-design.md §5）。これで「Eclipse が古いと新しい Java を
 * 解析できない」という縛りが無くなる。
 *
 * <p>標準エラーは飲み込まない。子プロセスが起動に失敗したときの理由（クラスパスの誤り、
 * JDK の版違いなど）はそこにしか出ないので、呼び出し側がコンソールへ流す。
 */
public final class ServerLauncher {

    /** 子プロセスとのやりとりは UTF-8 に固定する。環境の既定に左右されないため */
    public static final Charset CHARSET = Charset.forName("UTF-8");

    private ServerLauncher() {
    }

    /**
     * サーバーを起動して接続を返す。
     *
     * @param javaExecutable 解析に使う JDK の java（17 以上。既定は 25）
     * @param classpath      {@code lib/jche-core.jar} と {@code lib/jdt/*.jar}
     * @param cacheRoot      キャッシュの置き場所（プラグインの状態フォルダ）
     * @param vmArguments    追加の JVM 引数（{@code -Xmx2g} など）。無ければ空
     * @param workingDir     作業ディレクトリ。null なら継承する
     */
    public static ServerConnection start(File javaExecutable, List<File> classpath, File cacheRoot,
                                         List<String> vmArguments, File workingDir) throws IOException {
        if (javaExecutable == null || !javaExecutable.isFile()) {
            throw new IOException("解析に使う java が見つかりません: " + javaExecutable);
        }
        if (classpath == null || classpath.isEmpty()) {
            throw new IOException("解析本体（lib/）が見つかりません");
        }
        List<String> command = new ArrayList<String>();
        command.add(javaExecutable.getAbsolutePath());
        // 子プロセスの入出力は UTF-8 で固定する。これを外すと環境ごとに文字化けする
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dstdout.encoding=UTF-8");
        command.add("-Dstderr.encoding=UTF-8");
        if (vmArguments != null) {
            command.addAll(vmArguments);
        }
        command.add("-cp");
        command.add(joinClasspath(classpath));
        command.add("CallHierarchyExporter");
        command.add("--server");
        command.add(cacheRoot.getAbsolutePath());

        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDir != null) {
            builder.directory(workingDir);
        }
        return new ServerConnection(builder.start(), CHARSET);
    }

    private static String joinClasspath(List<File> classpath) {
        StringBuilder sb = new StringBuilder();
        for (File entry : classpath) {
            if (sb.length() > 0) {
                sb.append(File.pathSeparatorChar);
            }
            sb.append(entry.getAbsolutePath());
        }
        return sb.toString();
    }
}
