// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.BufferedWriter;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;

/**
 * 解析の進捗と作業ログを、ワークスペースの中のファイルに残す。
 *
 * <p>なぜ要るか: これまでログの行き先は「Call Hierarchy Exporter」コンソールだけで、しかも
 * <b>解析を始める前にコンソールを開いていた場合しか</b>残らなかった。解析が返ってこないときに
 * 「何が起きていたか」を後から見る手段が無く、利用者にやり直しを頼むしかなかった
 * （docs/eclipse-plugin-progress-log-qa.md の Q2）。
 *
 * <p>置き場所は {@link PluginFolders#logFolder()}（既定はプラグインの状態フォルダの {@code log/}、
 * 設定で移せる）。Eclipse を起動してから最初の1行で 1ファイル作り、以降はそこへ追記する。
 * 古いものは {@link #KEEP} 世代だけ残して消す。
 *
 * <p>書くのは受信スレッド（標準出力・標準エラーの2本）なので {@code synchronized} で守り、
 * 1行ごとに {@code flush} する。落ちたときにこそ読みたいログなので、溜めない。
 * ログを書けない（権限が無い等）場合は黙って諦める。<b>ログのために解析を止めない</b>。
 */
final class AnalysisLog {

    /** 残す世代数 */
    private static final int KEEP = 10;

    private static final AnalysisLog INSTANCE = new AnalysisLog();

    private final SimpleDateFormat stamp = new SimpleDateFormat("HH:mm:ss");
    private PrintWriter writer;
    private File file;
    private boolean failed;

    private AnalysisLog() {
    }

    static AnalysisLog get() {
        return INSTANCE;
    }

    /** ログを置くフォルダ（設定画面の「開く」もここを指す） */
    static File folder() {
        return PluginFolders.logFolder();
    }

    /** いま書いているログファイル。まだ1行も書いていなければ null */
    synchronized File file() {
        return file;
    }

    /** 1行残す。プロジェクト名を頭に付けるのは、複数のプロジェクトを並行して解析できるため */
    synchronized void println(String project, String line) {
        PrintWriter out = open();
        if (out == null) {
            return;
        }
        out.println(stamp.format(new Date()) + " [" + project + "] " + (line == null ? "" : line));
        out.flush();
    }

    /**
     * ログを閉じる。プラグインの停止時に呼ぶ。
     * 閉じた後にまた書かれたら、次のファイルを作り直す
     */
    synchronized void close() {
        if (writer != null) {
            writer.close();
            writer = null;
        }
        file = null;
    }

    /** 設定で切られていれば null。まだ開いていなければ開く */
    private PrintWriter open() {
        if (!JchePreferences.logToFile()) {
            return null;
        }
        if (writer != null) {
            return writer;
        }
        if (failed) {
            return null;
        }
        try {
            File dir = folder();
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException(Messages.format("log.folderNotCreated", dir));
            }
            sweep(dir);
            File target = new File(dir, "analysis-"
                    + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".log");
            writer = new PrintWriter(new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(target, true), Charset.forName("UTF-8"))));
            file = target;
            return writer;
        } catch (IOException e) {
            // ログが書けないだけで解析は続けられる。理由は Eclipse のエラーログへ残す
            failed = true;
            PluginRuntime.logWarning(Messages.get("log.notWritable"), e);
            return null;
        }
    }

    /** 古い世代を消す。新しいものから {@link #KEEP} - 1 個を残す（これから1つ作るため） */
    private static void sweep(File dir) {
        File[] logs = dir.listFiles(new java.io.FilenameFilter() {
            @Override
            public boolean accept(File parent, String name) {
                return name.startsWith("analysis-") && name.endsWith(".log");
            }
        });
        if (logs == null || logs.length < KEEP) {
            return;
        }
        Arrays.sort(logs);
        for (int i = 0; i <= logs.length - KEEP; i++) {
            if (!logs[i].delete()) {
                return;     // 消せないものが1つでもあれば、それ以上は触らない
            }
        }
    }
}
