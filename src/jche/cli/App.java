// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import jche.CallHierarchyExporter;
import jche.config.ToolRoot;
import jche.util.Messages;

/**
 * 対話モードの画面。メニューを出して番号で選ばせる、それだけの素朴な作り。
 *
 * 画面は 4 つ。
 * <ol>
 *   <li>解析を実行する … 設定ファイルの一覧から選び（複数可）、確認してから解析する。
 *       解析そのものは {@link CallHierarchyExporter#runAll}（jbang で直接動かすときと同じ処理）を
 *       同じ JVM の中で呼ぶ。終わったら結果の一覧と出力フォルダを出す</li>
 *   <li>設定ファイルを新しく作る … {@link ConfigWizard}</li>
 *   <li>環境設定 … {@link EnvironmentSettingsScreen}。{@code launcher.properties} を書き換え、
 *       希望すれば再起動して反映する（{@link LauncherSettings}）</li>
 *   <li>実行環境の状態 … {@link StatusScreen}</li>
 * </ol>
 *
 * 外部ライブラリ（JLine 等）は使わない。依存を JDT だけに保つため、また Windows の cmd で
 * 端末制御が絡む不具合を持ち込まないため。色や画面消去も使わない。
 */
public final class App {

    private final Terminal t;
    private final ToolRoot toolRoot;
    private final Path root;
    private LauncherSettings settings;

    public App(Terminal t, ToolRoot toolRoot) throws IOException {
        this.t = t;
        this.toolRoot = toolRoot;
        this.root = toolRoot.dir;
        this.settings = LauncherSettings.load(root);
    }

    /**
     * メインループ。
     *
     * @return 終了コード（0）
     */
    public int run() {
        if (!toolRoot.found) {
            t.println(Messages.format("cli.app.rootNotFound", root));
            t.println(Messages.get("cli.app.rootNotFound.hint"));
        }
        try {
            while (true) {
                showHeader();
                t.println(Messages.get("cli.menu.analyze"));
                t.println(Messages.get("cli.menu.newConfig"));
                t.println(Messages.get("cli.menu.environment"));
                t.println(Messages.get("cli.menu.status"));
                t.println(Messages.get("cli.menu.quit"));
                String choice = t.readLine("jche> ");
                switch (choice) {
                    case "1" -> runAnalysis();
                    case "2" -> createConfig();
                    case "3" -> {
                        if (new EnvironmentSettingsScreen(t, root, settings).run()) {
                            return 0;
                        }
                    }
                    case "4" -> showStatus();
                    case "q", "quit", "exit" -> {
                        return 0;
                    }
                    case "" -> {
                    }
                    default -> t.println(Messages.get("cli.menu.invalid"));
                }
            }
        } catch (Terminal.EndOfInput e) {
            return 0;
        } catch (IOException e) {
            t.println("[ERROR] " + e);
            return 1;
        }
    }

    private void showHeader() throws IOException {
        t.println();
        t.println("================================================================");
        t.println(Messages.get("cli.header.title"));
        t.println("================================================================");
        t.println(Messages.format("cli.header.toolDir", root));
        t.println(Messages.format("cli.header.jbangDir", EnvironmentSettingsScreen.describeJbangDir(settings)));
        t.println(Messages.format("cli.header.runningJdk",
                EnvironmentInfo.javaVersion(), EnvironmentInfo.javaHome()));
        List<ConfigCatalog.Entry> configs = ConfigCatalog.scan(root);
        t.println(Messages.format("cli.header.configs", configs.size(), ConfigCatalog.CONFIGS_DIR_NAME));
        t.println();
    }

    // ------------------------------------------------------------------ 1) 解析

    private void runAnalysis() throws IOException {
        List<Path> selected = selectConfigs();
        if (selected == null || selected.isEmpty()) {
            return;
        }
        t.println();
        t.println(Messages.get("cli.run.order"));
        for (int i = 0; i < selected.size(); i++) {
            t.println("  " + (i + 1) + ". " + display(selected.get(i)));
        }
        if (!t.confirm(Messages.get("cli.run.confirm"), true)) {
            return;
        }
        ConfigCatalog.saveRecent(root, selected);
        t.println();
        long start = System.currentTimeMillis();
        int failed = CallHierarchyExporter.runAll(selected, toolRoot);
        long sec = (System.currentTimeMillis() - start) / 1000;
        t.println();
        if (failed == 0) {
            t.println(Messages.format("cli.run.done", selected.size(), sec));
        } else {
            t.println(Messages.format("cli.run.doneWithFailures", failed, selected.size(), sec));
            t.println(Messages.get("cli.run.failureHint"));
        }
        t.println(Messages.get("cli.run.outputHint"));
        t.pause();
    }

    /**
     * 設定ファイルを選ぶ。番号（カンマ区切りで複数可）、{@code v 番号} で内容を表示、{@code p} でパスを直接入力。
     *
     * @return 選んだ設定ファイル（渡す順）。やめたら null
     */
    private List<Path> selectConfigs() throws IOException {
        List<ConfigCatalog.Entry> entries = ConfigCatalog.scan(root);
        List<Path> recent = ConfigCatalog.loadRecent(root);
        while (true) {
            t.println();
            if (entries.isEmpty()) {
                t.println(Messages.format("cli.select.none", ConfigCatalog.CONFIGS_DIR_NAME));
                t.println(Messages.get("cli.select.noneHint"));
            } else {
                t.println(Messages.get("cli.select.prompt"));
                for (int i = 0; i < entries.size(); i++) {
                    ConfigCatalog.Entry e = entries.get(i);
                    String mark = recent.contains(e.path()) ? Messages.get("cli.select.recentMark") : "";
                    String pr = e.projectRoot().isEmpty() ? "" : "   project.root=" + e.projectRoot();
                    t.println(String.format("  %2d) %-40s%s%s", i + 1, e.display(), pr, mark));
                }
            }
            String defaultChoice = defaultChoice(entries, recent);
            String answer = t.ask("jche/run", defaultChoice);
            if (answer.equalsIgnoreCase("q")) {
                return null;
            }
            if (answer.equalsIgnoreCase("p")) {
                String raw = t.readLine(Messages.get("cli.select.pathPrompt"));
                Path p = Paths.get(raw).toAbsolutePath().normalize();
                if (!Files.isRegularFile(p)) {
                    t.println(Messages.format("cli.select.noFile", p));
                    continue;
                }
                return List.of(p);
            }
            if (answer.toLowerCase().startsWith("v")) {
                Integer n = parseIndex(answer.substring(1).trim(), entries.size());
                if (n == null) {
                    t.println(Messages.get("cli.select.viewNeedsNumber"));
                    continue;
                }
                showConfig(entries.get(n - 1).path());
                continue;
            }
            Set<Path> picked = new LinkedHashSet<>();
            boolean ok = true;
            for (String s : answer.split(",")) {
                Integer n = parseIndex(s.trim(), entries.size());
                if (n == null) {
                    t.println(Messages.format("cli.select.badNumber", s.trim()));
                    ok = false;
                    break;
                }
                picked.add(entries.get(n - 1).path());
            }
            if (ok && !picked.isEmpty()) {
                return new ArrayList<>(picked);
            }
        }
    }

    /** 前回使ったものが一覧にあれば、その番号（複数なら 1,3 の形）を既定にする */
    private static String defaultChoice(List<ConfigCatalog.Entry> entries, List<Path> recent) {
        List<String> nums = new ArrayList<>();
        for (Path r : recent) {
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).path().equals(r)) {
                    nums.add(String.valueOf(i + 1));
                }
            }
        }
        return String.join(",", nums);
    }

    private static Integer parseIndex(String s, int size) {
        try {
            int n = Integer.parseInt(s);
            return (n >= 1 && n <= size) ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void showConfig(Path config) throws IOException {
        t.println();
        t.println(Messages.format("cli.select.showHeader", display(config)));
        for (String line : ConfigCatalog.settingLines(config)) {
            t.println("  " + line);
        }
        t.println(Messages.format("cli.select.showFooter", config));
    }

    private String display(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        return abs.startsWith(root) ? root.relativize(abs).toString().replace('\\', '/') : abs.toString();
    }

    // ------------------------------------------------------------------ 2) 設定ファイルの作成

    private void createConfig() throws IOException {
        t.println();
        Path created = new ConfigWizard(t, root).run();
        if (created == null) {
            t.println(Messages.get("cli.create.cancelled"));
            return;
        }
        if (t.confirm(Messages.get("cli.create.runNow"), false)) {
            ConfigCatalog.saveRecent(root, List.of(created));
            t.println();
            int failed = CallHierarchyExporter.runAll(List.of(created), toolRoot);
            t.println();
            t.println(Messages.get(failed == 0 ? "cli.create.done" : "cli.create.failed"));
            t.pause();
        }
    }

    // ------------------------------------------------------------------ 3) 環境設定 … EnvironmentSettingsScreen

    // ------------------------------------------------------------------ 4) 状態 … StatusScreen

    private void showStatus() throws IOException {
        StatusScreen.show(t, root);
        settings = LauncherSettings.load(root);
    }
}
