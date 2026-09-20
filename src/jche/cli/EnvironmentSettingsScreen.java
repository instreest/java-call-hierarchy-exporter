// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import jche.util.Messages;

/**
 * 対話モードの「環境設定」画面。JDK / JBang の置き場所、依存 jar の置き場所、ヒープ上限、
 * jbang の追加オプション、ネットワークからの取得の確認の要否を {@code launcher.properties} に書き、希望すれば再起動して反映する
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
        String where = Messages.get(settings.isProjectLocal() ? "cli.env.where.projectLocal"
                : settings.get(LauncherSettings.KEY_JBANG_DIR).isEmpty() ? "cli.env.where.jbangDefault"
                : "cli.env.where.specified");
        String s = Messages.format("cli.env.jbangDir.describe", configured, where);
        Path current = settings.currentJbangDir();
        if (!current.equals(configured)) {
            s += Messages.format("cli.env.jbangDir.pending", current);
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
            t.println(Messages.format("cli.env.title", settings.file));
            t.println(Messages.format("cli.env.menu.jbangDir", describeJbangDir(settings)));
            t.println(Messages.format("cli.env.menu.repo", settings.configuredRepo(),
                    settings.get(LauncherSettings.KEY_JBANG_REPO).isEmpty()
                            ? Messages.get("cli.env.menu.repo.default") : "",
                    settings.currentRepo().equals(settings.configuredRepo()) ? ""
                            : Messages.format("cli.env.menu.repo.pending", settings.currentRepo())));
            String heap = heapOf(settings.get(LauncherSettings.KEY_JAVA_OPTS));
            t.println(Messages.format("cli.env.menu.heap",
                    heap.isEmpty() ? Messages.get("cli.env.menu.heap.default") : heap,
                    EnvironmentInfo.maxHeapMb()));
            String jbangOpts = settings.get(LauncherSettings.KEY_JBANG_OPTS);
            t.println(Messages.format("cli.env.menu.jbangOpts",
                    jbangOpts.isEmpty() ? Messages.get("cli.env.menu.jbangOpts.none") : jbangOpts));
            t.println(Messages.format("cli.env.menu.allowDownload",
                    describeAllowDownload(settings.get(LauncherSettings.KEY_ALLOW_DOWNLOAD))));
            for (String k : settings.extraKeys()) {
                t.println(Messages.format("cli.env.menu.extraKey", k, settings.get(k)));
            }
            t.println(Messages.get("cli.env.menu.back"));
            String choice = t.readLine("jche/env> ");
            switch (choice) {
                case "1" -> changed |= changeJbangDir();
                case "2" -> changed |= changeRepo();
                case "3" -> changed |= changeHeap();
                case "4" -> changed |= changeJbangOpts();
                case "5" -> changed |= changeAllowDownload();
                case "q", "" -> {
                    if (changed) {
                        return offerRestart();
                    }
                    return false;
                }
                default -> t.println(Messages.get("cli.env.menu.invalid"));
            }
        }
    }

    private boolean changeJbangDir() throws IOException {
        Path local = root.resolve(LauncherSettings.PROJECT_LOCAL_JBANG_DIR);
        Path home = Paths.get(System.getProperty("user.home"), ".jbang");
        t.println();
        t.println(Messages.get("cli.env.jbang.title"));
        t.println(Messages.format("cli.env.jbang.local", local,
                EnvironmentInfo.humanSize(EnvironmentInfo.sizeOf(local))));
        t.println(Messages.get("cli.env.jbang.localHint"));
        t.println(Messages.format("cli.env.jbang.home", home,
                EnvironmentInfo.humanSize(EnvironmentInfo.sizeOf(home))));
        t.println(Messages.get("cli.env.jbang.homeHint"));
        t.println(Messages.get("cli.env.choice.other"));
        t.println(Messages.get("cli.env.choice.keep"));
        String choice = t.readLine("jche/env/jbang> ");
        String newDir;
        String newRepo = null;
        switch (choice) {
            case "1" -> {
                newDir = LauncherSettings.PROJECT_LOCAL_JBANG_DIR;
                if (t.confirm(Messages.format("cli.env.jbang.repoToo", LauncherSettings.PROJECT_LOCAL_REPO), true)) {
                    newRepo = LauncherSettings.PROJECT_LOCAL_REPO;
                }
            }
            case "2" -> {
                newDir = "";
                if (!settings.get(LauncherSettings.KEY_JBANG_REPO).isEmpty()
                        && t.confirm(Messages.get("cli.env.jbang.repoBack"), true)) {
                    newRepo = "";
                }
            }
            case "3" -> {
                String raw = t.readLine(Messages.get("cli.env.pathPrompt"));
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
            t.println(Messages.get("cli.env.sameLocation"));
            return false;
        }
        t.println(Messages.format("cli.env.newValue", shown));
        if (!Files.isDirectory(shown)) {
            t.println(Messages.get("cli.env.willDownload"));
        }
        if (Files.isDirectory(oldDir)) {
            t.println(Messages.format("cli.env.oldStays", oldDir,
                    EnvironmentInfo.humanSize(EnvironmentInfo.sizeOf(oldDir))));
            if (oldDir.startsWith(root)) {
                t.println(Messages.get("cli.env.oldStaysLocal"));
            }
        }
        if (!t.confirm(Messages.get("cli.env.saveConfirm"), true)) {
            return false;
        }
        settings.set(LauncherSettings.KEY_JBANG_DIR, newDir);
        if (newRepo != null) {
            settings.set(LauncherSettings.KEY_JBANG_REPO, newRepo);
        }
        settings.save();
        t.println(Messages.format("cli.env.saved", settings.file));
        return true;
    }

    private boolean changeRepo() throws IOException {
        t.println();
        t.println(Messages.get("cli.env.repo.title"));
        t.println(Messages.format("cli.env.repo.local", root.resolve(LauncherSettings.PROJECT_LOCAL_REPO)));
        t.println(Messages.get("cli.env.repo.default"));
        t.println(Messages.get("cli.env.choice.other"));
        t.println(Messages.get("cli.env.choice.keep"));
        String choice = t.readLine("jche/env/repo> ");
        String value;
        switch (choice) {
            case "1" -> value = LauncherSettings.PROJECT_LOCAL_REPO;
            case "2" -> value = "";
            case "3" -> {
                value = t.readLine(Messages.get("cli.env.pathPrompt")).replace('\\', '/');
                if (value.isEmpty()) {
                    return false;
                }
            }
            default -> {
                return false;
            }
        }
        if (value.equals(settings.get(LauncherSettings.KEY_JBANG_REPO))) {
            t.println(Messages.get("cli.env.same"));
            return false;
        }
        settings.set(LauncherSettings.KEY_JBANG_REPO, value);
        settings.save();
        t.println(Messages.get("cli.env.repo.saved"));
        return true;
    }

    private boolean changeHeap() throws IOException {
        t.println();
        t.println(Messages.get("cli.env.heap.title"));
        t.println(Messages.get("cli.env.heap.examples"));
        String current = heapOf(settings.get(LauncherSettings.KEY_JAVA_OPTS));
        String raw = t.readLine(Messages.format("cli.env.heap.prompt",
                current.isEmpty() ? Messages.get("cli.env.menu.heap.default") : current));
        if (raw.equalsIgnoreCase("q")) {
            return false;
        }
        String value = raw.toLowerCase();
        if (!value.isEmpty() && !value.matches("\\d+[kmg]?")) {
            t.println(Messages.get("cli.env.heap.invalid"));
            return false;
        }
        String opts = withoutXmx(settings.get(LauncherSettings.KEY_JAVA_OPTS));
        if (!value.isEmpty()) {
            opts = (opts.isEmpty() ? "" : opts + " ") + "-Xmx" + value;
        }
        settings.set(LauncherSettings.KEY_JAVA_OPTS, opts);
        settings.save();
        t.println(Messages.format("cli.env.savedKeyValue", LauncherSettings.KEY_JAVA_OPTS, opts));
        return true;
    }

    private boolean changeJbangOpts() throws IOException {
        t.println();
        t.println(Messages.get("cli.env.jbangOpts.title"));
        t.println(Messages.get("cli.env.jbangOpts.offline"));
        t.println(Messages.get("cli.env.jbangOpts.java"));
        String raw = t.readLine(Messages.format("cli.env.jbangOpts.prompt",
                settings.get(LauncherSettings.KEY_JBANG_OPTS)));
        if (raw.equalsIgnoreCase("q")) {
            return false;
        }
        String value = raw.equals("-") ? "" : raw;
        if (raw.isEmpty()) {
            return false;
        }
        settings.set(LauncherSettings.KEY_JBANG_OPTS, value);
        settings.save();
        t.println(Messages.format("cli.env.savedKeyValue", LauncherSettings.KEY_JBANG_OPTS, value));
        return true;
    }

    /** {@code JCHE_ALLOW_DOWNLOAD} の値の説明（起動コマンドの読み方と同じ: yes / no、それ以外は毎回尋ねる） */
    static String describeAllowDownload(String value) {
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "yes", "y", "true", "1":
                return Messages.format("cli.env.download.yes", value);
            case "no", "n", "false", "0":
                return Messages.format("cli.env.download.no", value);
            default:
                return Messages.format("cli.env.download.ask", value.isEmpty()
                        ? Messages.get("cli.env.download.ask.default")
                        : Messages.format("cli.env.download.ask.unknown", value));
        }
    }

    private boolean changeAllowDownload() throws IOException {
        t.println();
        t.println(Messages.get("cli.env.download.title"));
        t.println(Messages.get("cli.env.download.question"));
        t.println(Messages.get("cli.env.download.ask.item"));
        t.println(Messages.get("cli.env.download.yes.item"));
        t.println(Messages.get("cli.env.download.no.item"));
        t.println(Messages.get("cli.env.choice.keep"));
        String choice = t.readLine("jche/env/download> ");
        String value;
        switch (choice) {
            case "1" -> value = "";
            case "2" -> value = "yes";
            case "3" -> value = "no";
            default -> {
                return false;
            }
        }
        if (value.equals(settings.get(LauncherSettings.KEY_ALLOW_DOWNLOAD))) {
            t.println(Messages.get("cli.env.same"));
            return false;
        }
        settings.set(LauncherSettings.KEY_ALLOW_DOWNLOAD, value);
        settings.save();
        t.println(Messages.format("cli.env.savedWithNote", LauncherSettings.KEY_ALLOW_DOWNLOAD, value,
                describeAllowDownload(value)));
        return true;
    }

    /** 設定を変えたあと、再起動して反映するかを尋ねる。true なら終了して起動コマンドにやり直させる */
    private boolean offerRestart() throws IOException {
        if (!LauncherSettings.launchedByLauncher()) {
            t.println(Messages.get("cli.env.restart.notAvailable"));
            return false;
        }
        if (t.confirm(Messages.get("cli.env.restart.confirm"), true)) {
            settings.requestRestart();
            return true;
        }
        t.println(Messages.get("cli.env.restart.later"));
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
