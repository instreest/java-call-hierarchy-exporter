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
import java.util.function.BiFunction;

import jche.config.ToolRoot;

/**
 * 対話モードの画面。メニューを出して番号で選ばせる、それだけの素朴な作り。
 *
 * 画面は 4 つ。
 * <ol>
 *   <li>解析を実行する … 設定ファイルの一覧から選び（複数可）、確認してから解析する。
 *       解析そのものは {@code CallHierarchyExporter.runAll}（jbang で直接動かすときと同じ処理）を
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
    /** 解析の実処理。{@code CallHierarchyExporter.runAll} を渡す（このパッケージから既定パッケージのクラスは参照できない） */
    private final BiFunction<List<Path>, ToolRoot, Integer> runner;
    private LauncherSettings settings;

    public App(Terminal t, ToolRoot toolRoot, BiFunction<List<Path>, ToolRoot, Integer> runner) throws IOException {
        this.t = t;
        this.toolRoot = toolRoot;
        this.root = toolRoot.dir;
        this.runner = runner;
        this.settings = LauncherSettings.load(root);
    }

    /**
     * メインループ。
     *
     * @return 終了コード（0）
     */
    public int run() {
        if (!toolRoot.found) {
            t.println("[WARN] ツールのプロジェクトフォルダ（src/CallHierarchyExporter.java のある場所）を特定できません: " + root);
            t.println("       プロジェクト直下の java-call-hierarchy-exporter.sh / java-call-hierarchy-exporter.cmd から起動してください。");
        }
        try {
            while (true) {
                showHeader();
                t.println(" 1) 解析を実行する");
                t.println(" 2) 設定ファイルを新しく作る");
                t.println(" 3) 環境設定（JDK / JBang の置き場所、ヒープ上限、jbang のオプション）");
                t.println(" 4) 実行環境の状態を表示する");
                t.println(" q) 終了");
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
                    default -> t.println("  1〜4 か q を入力してください。");
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
        t.println(" java-call-hierarchy-exporter — 対話モード");
        t.println("================================================================");
        t.println(" ツールのフォルダ : " + root);
        t.println(" JDK / JBang      : " + EnvironmentSettingsScreen.describeJbangDir(settings));
        t.println(" 実行中の JDK     : " + EnvironmentInfo.javaVersion() + "  " + EnvironmentInfo.javaHome());
        List<ConfigCatalog.Entry> configs = ConfigCatalog.scan(root);
        t.println(" 設定ファイル     : " + configs.size() + " 件（" + ConfigCatalog.CONFIGS_DIR_NAME + "/）");
        t.println();
    }

    // ------------------------------------------------------------------ 1) 解析

    private void runAnalysis() throws IOException {
        List<Path> selected = selectConfigs();
        if (selected == null || selected.isEmpty()) {
            return;
        }
        t.println();
        t.println("次の順に解析します:");
        for (int i = 0; i < selected.size(); i++) {
            t.println("  " + (i + 1) + ". " + display(selected.get(i)));
        }
        if (!t.confirm("実行しますか？", true)) {
            return;
        }
        ConfigCatalog.saveRecent(root, selected);
        t.println();
        long start = System.currentTimeMillis();
        int failed = runner.apply(selected, toolRoot);
        long sec = (System.currentTimeMillis() - start) / 1000;
        t.println();
        if (failed == 0) {
            t.println("=== 解析が終わりました（" + selected.size() + " 件、" + sec + " 秒）===");
        } else {
            t.println("=== 解析が終わりました（" + failed + "/" + selected.size() + " 件失敗、" + sec + " 秒）===");
            t.println("失敗の内容は上のログ（[ERROR] の行）にあります。");
        }
        t.println("出力フォルダは各設定の output.folder の下（既定は設定ファイルと同じフォルダ）です。");
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
                t.println("設定ファイルがありません（" + ConfigCatalog.CONFIGS_DIR_NAME + "/ を探しました）。");
                t.println("メニューの 2) で作るか、p でパスを直接入力してください。");
            } else {
                t.println("設定ファイルを選んでください（番号。カンマ区切りで複数可。v 番号 で内容を表示、p でパスを入力、q で戻る）");
                for (int i = 0; i < entries.size(); i++) {
                    ConfigCatalog.Entry e = entries.get(i);
                    String mark = recent.contains(e.path()) ? "  ← 前回" : "";
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
                String raw = t.readLine("設定ファイルのパス: ");
                Path p = Paths.get(raw).toAbsolutePath().normalize();
                if (!Files.isRegularFile(p)) {
                    t.println("  ファイルがありません: " + p);
                    continue;
                }
                return List.of(p);
            }
            if (answer.toLowerCase().startsWith("v")) {
                Integer n = parseIndex(answer.substring(1).trim(), entries.size());
                if (n == null) {
                    t.println("  v のあとに番号を付けてください（例: v 1）");
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
                    t.println("  番号が不正です: " + s.trim());
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
        t.println("--- " + display(config) + "（コメントを除く）---");
        for (String line : ConfigCatalog.settingLines(config)) {
            t.println("  " + line);
        }
        t.println("--- 変更するときはこのファイルをエディタで開いてください: " + config);
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
            t.println("中止しました。");
            return;
        }
        if (t.confirm("続けてこの設定で解析を実行しますか？", false)) {
            ConfigCatalog.saveRecent(root, List.of(created));
            t.println();
            int failed = runner.apply(List.of(created), toolRoot);
            t.println();
            t.println(failed == 0 ? "=== 解析が終わりました ===" : "=== 解析に失敗しました（上の [ERROR] を確認してください）===");
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
