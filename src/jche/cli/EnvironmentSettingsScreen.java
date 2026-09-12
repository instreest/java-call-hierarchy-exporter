// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 対話モードの「環境設定」画面。JDK / JBang の置き場所、依存 jar の置き場所、ヒープ上限、
 * jbang の追加オプションを {@code launcher.properties} に書き、希望すれば再起動して反映する
 * （{@link LauncherSettings}）。{@link App} のメニュー 3) から呼ばれる。
 */
final class EnvironmentSettingsScreen {

    private final Terminal t;
    private final Path root;
    private final LauncherSettings settings;

    EnvironmentSettingsScreen(Terminal t, Path root, LauncherSettings settings) {
        this.t = t;
        this.root = root;
        this.settings = settings;
    }

    /** 設定ファイルで決まる置き場所。今の実行と食い違っていれば（書き換えた直後）それも添える */
    static String describeJbangDir(LauncherSettings settings) {
        Path configured = settings.configuredJbangDir();
        String where = settings.isProjectLocal() ? "このプロジェクトの中"
                : settings.get(LauncherSettings.KEY_JBANG_DIR).isEmpty() ? "JBang の既定" : "指定の場所";
        String s = configured + "（" + where + "）";
        Path current = settings.currentJbangDir();
        if (!current.equals(configured)) {
            s += "  ※ 今の実行は " + current + "。再起動で切り替わる";
        }
        return s;
    }

    /**
     * 環境設定のメニューを回す。
     *
     * @return 再起動のため終了するなら true
     */
    boolean run() throws IOException {
        boolean changed = false;
        while (true) {
            t.println();
            t.println("環境設定（" + settings.file + "。変更は次回の起動から効きます）");
            t.println(" 1) JDK / JBang の置き場所  : " + describeJbangDir(settings));
            t.println(" 2) 依存 jar の置き場所      : " + settings.configuredRepo()
                    + (settings.get(LauncherSettings.KEY_JBANG_REPO).isEmpty() ? "（既定）" : "")
                    + (settings.currentRepo().equals(settings.configuredRepo()) ? "" : "  ※ 今の実行は " + settings.currentRepo()));
            String heap = heapOf(settings.get(LauncherSettings.KEY_JAVA_OPTS));
            t.println(" 3) ヒープ上限（-Xmx）       : " + (heap.isEmpty() ? "既定" : heap)
                    + "（今の実行では " + EnvironmentInfo.maxHeapMb() + " MB）");
            String jbangOpts = settings.get(LauncherSettings.KEY_JBANG_OPTS);
            t.println(" 4) jbang の追加オプション    : " + (jbangOpts.isEmpty() ? "（なし）" : jbangOpts));
            t.println(" 5) ダウンロードの確認        : " + describeNetwork(settings));
            for (String k : settings.extraKeys()) {
                t.println("    " + k + "=" + settings.get(k) + "（手で足された項目。そのまま残します）");
            }
            t.println(" q) 戻る");
            String choice = t.readLine("jche/env> ");
            switch (choice) {
                case "1" -> changed |= changeJbangDir();
                case "2" -> changed |= changeRepo();
                case "3" -> changed |= changeHeap();
                case "4" -> changed |= changeJbangOpts();
                case "5" -> changed |= changeNetwork();
                case "q", "" -> {
                    if (changed) {
                        return offerRestart();
                    }
                    return false;
                }
                default -> t.println("  1〜5 か q を入力してください。");
            }
        }
    }

    private boolean changeJbangDir() throws IOException {
        Path local = root.resolve(LauncherSettings.PROJECT_LOCAL_JBANG_DIR);
        Path home = Paths.get(System.getProperty("user.home"), ".jbang");
        t.println();
        t.println("JDK / JBang の置き場所（合わせて数百 MB）:");
        t.println("  1) このプロジェクトの中   " + local + "  " + EnvironmentInfo.humanSize(EnvironmentInfo.sizeOf(local)));
        t.println("     他の環境を汚さず、フォルダごと消せば元に戻る。依存 jar も同じ場所に置く");
        t.println("  2) ユーザーのホーム       " + home + "  " + EnvironmentInfo.humanSize(EnvironmentInfo.sizeOf(home)) + "（JBang の既定）");
        t.println("     他の JBang スクリプトと共有する");
        t.println("  3) 別の場所を指定する");
        t.println("  q) 変えない");
        String choice = t.readLine("jche/env/jbang> ");
        String newDir;
        String newRepo = null;
        switch (choice) {
            case "1" -> {
                newDir = LauncherSettings.PROJECT_LOCAL_JBANG_DIR;
                if (t.confirm("依存 jar もプロジェクトの中（" + LauncherSettings.PROJECT_LOCAL_REPO + "）に置きますか？", true)) {
                    newRepo = LauncherSettings.PROJECT_LOCAL_REPO;
                }
            }
            case "2" -> {
                newDir = "";
                if (!settings.get(LauncherSettings.KEY_JBANG_REPO).isEmpty()
                        && t.confirm("依存 jar の置き場所も既定（~/.m2/repository）に戻しますか？", true)) {
                    newRepo = "";
                }
            }
            case "3" -> {
                String raw = t.readLine("置き場所のパス（相対はツールのフォルダ起点）: ");
                if (raw.isEmpty()) {
                    return false;
                }
                newDir = raw.replace('\\', '/');
            }
            default -> {
                return false;
            }
        }
        Path oldDir = settings.configuredJbangDir();
        Path resolved = settings.resolvePath(newDir);
        Path shown = (resolved == null) ? home : resolved;
        if (shown.equals(oldDir)) {
            t.println("  今と同じ場所です。");
            return false;
        }
        t.println("  変更後: " + shown);
        if (!Files.isDirectory(shown)) {
            t.println("  この場所にはまだ何も無いので、次回の起動で JBang と JDK を取得し直します（ネットワークが要ります）。");
        }
        if (Files.isDirectory(oldDir)) {
            t.println("  今の場所 " + oldDir + "（" + EnvironmentInfo.humanSize(EnvironmentInfo.sizeOf(oldDir)) + "）はそのまま残ります。");
            if (oldDir.startsWith(root)) {
                t.println("  このプロジェクト専用だったので、不要なら終了後に手で削除してください（実行中は JDK が使用中で消せません）。");
            }
        }
        if (!t.confirm("この内容で保存しますか？", true)) {
            return false;
        }
        settings.set(LauncherSettings.KEY_JBANG_DIR, newDir);
        if (newRepo != null) {
            settings.set(LauncherSettings.KEY_JBANG_REPO, newRepo);
        }
        settings.save();
        t.println("  保存しました: " + settings.file);
        return true;
    }

    private boolean changeRepo() throws IOException {
        t.println();
        t.println("依存 jar（JDT 等）の置き場所:");
        t.println("  1) このプロジェクトの中   " + root.resolve(LauncherSettings.PROJECT_LOCAL_REPO));
        t.println("  2) 既定（~/.m2/repository。Maven / Eclipse の m2e と共有）");
        t.println("  3) 別の場所を指定する");
        t.println("  q) 変えない");
        String choice = t.readLine("jche/env/repo> ");
        String value;
        switch (choice) {
            case "1" -> value = LauncherSettings.PROJECT_LOCAL_REPO;
            case "2" -> value = "";
            case "3" -> {
                value = t.readLine("置き場所のパス（相対はツールのフォルダ起点）: ").replace('\\', '/');
                if (value.isEmpty()) {
                    return false;
                }
            }
            default -> {
                return false;
            }
        }
        if (value.equals(settings.get(LauncherSettings.KEY_JBANG_REPO))) {
            t.println("  今と同じです。");
            return false;
        }
        settings.set(LauncherSettings.KEY_JBANG_REPO, value);
        settings.save();
        t.println("  保存しました。無い jar は次回の起動で取得し直します（ネットワークが要ります）。");
        return true;
    }

    private boolean changeHeap() throws IOException {
        t.println();
        t.println("解析を動かす JVM のヒープ上限（-Xmx）。大きなプロジェクトで OutOfMemoryError になるときに上げる。");
        t.println("例: 2g、4096m。空欄で JVM の既定（物理メモリの 1/4）に戻す。");
        String current = heapOf(settings.get(LauncherSettings.KEY_JAVA_OPTS));
        String raw = t.readLine("ヒープ上限 [" + (current.isEmpty() ? "既定" : current) + "]: ");
        if (raw.equalsIgnoreCase("q")) {
            return false;
        }
        String value = raw.toLowerCase();
        if (!value.isEmpty() && !value.matches("\\d+[kmg]?")) {
            t.println("  数字と単位（k / m / g）で指定してください。");
            return false;
        }
        String opts = withoutXmx(settings.get(LauncherSettings.KEY_JAVA_OPTS));
        if (!value.isEmpty()) {
            opts = (opts.isEmpty() ? "" : opts + " ") + "-Xmx" + value;
        }
        settings.set(LauncherSettings.KEY_JAVA_OPTS, opts);
        settings.save();
        t.println("  保存しました: " + LauncherSettings.KEY_JAVA_OPTS + "=" + opts);
        return true;
    }

    private boolean changeJbangOpts() throws IOException {
        t.println();
        t.println("jbang run に足すオプション（空白区切り）。例:");
        t.println("  --offline    ネットワークに出ない（取得済みの JDK と jar だけで動かす。閉域ネットワーク向け）");
        t.println("  --java 21    JDK 25 を取得できない環境で、手元の JDK 21 を使う（解析結果が一部変わりうる）");
        String raw = t.readLine("オプション [" + settings.get(LauncherSettings.KEY_JBANG_OPTS) + "]（- で消去）: ");
        if (raw.equalsIgnoreCase("q")) {
            return false;
        }
        String value = raw.equals("-") ? "" : raw;
        if (raw.isEmpty()) {
            return false;
        }
        settings.set(LauncherSettings.KEY_JBANG_OPTS, value);
        settings.save();
        t.println("  保存しました: " + LauncherSettings.KEY_JBANG_OPTS + "=" + value);
        return true;
    }

    /** {@link LauncherSettings#KEY_NETWORK} を一覧に出すときの文言。空欄は ask と同じ */
    static String describeNetwork(LauncherSettings settings) {
        String v = settings.get(LauncherSettings.KEY_NETWORK).trim().toLowerCase(java.util.Locale.ROOT);
        return switch (v) {
            case LauncherSettings.NETWORK_ALLOW -> "尋ねずに許可する（allow）";
            case LauncherSettings.NETWORK_DENY -> "許可しない（deny）";
            default -> "足りないときだけ尋ねる（ask。既定）";
        };
    }

    /**
     * 手元に無いもの（JDK・JBang 本体・依存 jar）を取りに行ってよいかの決め方を変える。
     * 判断と確認そのものは起動コマンド（java-call-hierarchy-exporter.sh / .cmd）が Java の前に行うので、
     * ここで書いた値は次回の起動から効く。
     */
    private boolean changeNetwork() throws IOException {
        t.println();
        t.println("このツールが外に出るのは、手元に無いもの（JDK・JBang 本体・依存 jar）を取りに行くときだけです。");
        t.println("  1) 足りないときだけ尋ねる（既定）");
        t.println("  2) 尋ねずに許可する  端末の無い CI などで使う");
        t.println("  3) 許可しない        足りないものがあれば実行せずに終わる");
        t.println("  q) 変えない");
        String value;
        switch (t.readLine("jche/env/network> ")) {
            case "1" -> value = LauncherSettings.NETWORK_ASK;
            case "2" -> value = LauncherSettings.NETWORK_ALLOW;
            case "3" -> value = LauncherSettings.NETWORK_DENY;
            default -> {
                return false;
            }
        }
        settings.set(LauncherSettings.KEY_NETWORK, value);
        settings.save();
        t.println("  保存しました: " + LauncherSettings.KEY_NETWORK + "=" + value);
        return true;
    }

    /** 設定を変えたあと、再起動して反映するかを尋ねる。true なら終了して起動コマンドにやり直させる */
    private boolean offerRestart() throws IOException {
        if (!LauncherSettings.launchedByLauncher()) {
            t.println("変更は次回 java-call-hierarchy-exporter.sh / java-call-hierarchy-exporter.cmd から起動したときに効きます（今回は起動コマンドを通していないため、再起動できません）。");
            return false;
        }
        if (t.confirm("変更を反映するために今すぐ再起動しますか？", true)) {
            settings.requestRestart();
            return true;
        }
        t.println("次回の起動から効きます。");
        return false;
    }

    private static String heapOf(String javaOpts) {
        for (String o : javaOpts.trim().split("\\s+")) {
            if (o.startsWith("-Xmx")) {
                return o.substring(4);
            }
        }
        return "";
    }

    private static String withoutXmx(String javaOpts) {
        List<String> keep = new ArrayList<>();
        for (String o : javaOpts.trim().split("\\s+")) {
            if (!o.isEmpty() && !o.startsWith("-Xmx")) {
                keep.add(o);
            }
        }
        return String.join(" ", keep);
    }
}
