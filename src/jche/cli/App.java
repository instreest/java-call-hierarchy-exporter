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

import jche.config.Config;
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
 *   <li>環境設定 … JDK / JBang の置き場所、依存 jar の置き場所、ヒープ上限、jbang の追加オプション。
 *       {@code launcher.properties} を書き換え、希望すれば再起動して反映する（{@link LauncherSettings}）</li>
 *   <li>実行環境の状態 … 実際に使っている JDK・JDT・置き場所・キャッシュの一覧</li>
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
            t.println("       プロジェクト直下の jche.sh / jche.cmd から起動してください。");
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
                        if (environmentSettings()) {
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
        t.println(" JDK / JBang      : " + describeJbangDir());
        t.println(" 実行中の JDK     : " + EnvironmentInfo.javaVersion() + "  " + EnvironmentInfo.javaHome());
        List<ConfigCatalog.Entry> configs = ConfigCatalog.scan(root);
        t.println(" 設定ファイル     : " + configs.size() + " 件（プロジェクト直下と " + ConfigCatalog.CONFIGS_DIR_NAME + "/）");
        t.println();
    }

    /** 設定ファイルで決まる置き場所。今の実行と食い違っていれば（書き換えた直後）それも添える */
    private String describeJbangDir() {
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
        t.println("出力フォルダは各設定の output.folder の下（既定は設定ファイルと同じ場所の output/）です。");
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
                t.println("設定ファイルがありません（プロジェクト直下と " + ConfigCatalog.CONFIGS_DIR_NAME + "/ を探しました）。");
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

    // ------------------------------------------------------------------ 3) 環境設定

    /**
     * @return 再起動のため終了するなら true
     */
    private boolean environmentSettings() throws IOException {
        boolean changed = false;
        while (true) {
            t.println();
            t.println("環境設定（" + settings.file + "。変更は次回の起動から効きます）");
            t.println(" 1) JDK / JBang の置き場所  : " + describeJbangDir());
            t.println(" 2) 依存 jar の置き場所      : " + settings.configuredRepo()
                    + (settings.get(LauncherSettings.KEY_JBANG_REPO).isEmpty() ? "（既定）" : "")
                    + (settings.currentRepo().equals(settings.configuredRepo()) ? "" : "  ※ 今の実行は " + settings.currentRepo()));
            String heap = heapOf(settings.get(LauncherSettings.KEY_JAVA_OPTS));
            t.println(" 3) ヒープ上限（-Xmx）       : " + (heap.isEmpty() ? "既定" : heap)
                    + "（今の実行では " + EnvironmentInfo.maxHeapMb() + " MB）");
            String jbangOpts = settings.get(LauncherSettings.KEY_JBANG_OPTS);
            t.println(" 4) jbang の追加オプション    : " + (jbangOpts.isEmpty() ? "（なし）" : jbangOpts));
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
                case "q", "" -> {
                    if (changed) {
                        return offerRestart();
                    }
                    return false;
                }
                default -> t.println("  1〜4 か q を入力してください。");
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

    /** 設定を変えたあと、再起動して反映するかを尋ねる。true なら終了して起動コマンドにやり直させる */
    private boolean offerRestart() throws IOException {
        if (!LauncherSettings.launchedByLauncher()) {
            t.println("変更は次回 jche.sh / jche.cmd から起動したときに効きます（今回は起動コマンドを通していないため、再起動できません）。");
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

    // ------------------------------------------------------------------ 4) 状態

    private void showStatus() throws IOException {
        settings = LauncherSettings.load(root);
        Path jbangDir = settings.currentJbangDir();
        Path repo = settings.currentRepo();
        Path cacheRoot = root.resolve(Config.DEFAULT_CACHE_DIR_NAME);
        Path jdt = EnvironmentInfo.jdtJar();
        t.println();
        t.println("--- 実行環境の状態 ---");
        t.println(" ツールのフォルダ        : " + root);
        t.println(" 起動の経路              : " + (LauncherSettings.launchedByLauncher() ? "jche.sh / jche.cmd" : "jbang を直接（環境設定の再起動は使えない）"));
        t.println(" launcher.properties     : " + (settings.exists() ? settings.file : "無し（すべて既定）"));
        t.println(" JDK / JBang の置き場所  : " + jbangDir + "  " + EnvironmentInfo.humanSize(EnvironmentInfo.sizeOf(jbangDir))
                + (jbangDir.equals(settings.configuredJbangDir()) ? "" : "  ※ 設定ファイルは " + settings.configuredJbangDir() + "（次回から）"));
        List<String> jdks = EnvironmentInfo.installedJdks(jbangDir);
        t.println("   取得済みの JDK        : " + (jdks.isEmpty() ? "無し（システムの JDK か、他の場所のものを使っている）" : String.join(", ", jdks)));
        t.println(" 依存 jar の置き場所     : " + repo + "  " + EnvironmentInfo.humanSize(EnvironmentInfo.sizeOf(repo)));
        t.println(" 実行中の JDK            : " + EnvironmentInfo.javaVersion());
        t.println("   java.home             : " + EnvironmentInfo.javaHome());
        t.println("   ヒープ上限            : " + EnvironmentInfo.maxHeapMb() + " MB");
        t.println(" JDT                     : " + (jdt == null ? "不明" : jdt));
        t.println(" 解析キャッシュ          : " + cacheRoot);
        List<String> caches = EnvironmentInfo.cacheEntries(cacheRoot);
        if (caches.isEmpty()) {
            t.println("   （まだ無い）");
        } else {
            for (String c : caches) {
                t.println("   " + c);
            }
        }
        t.println(" 環境変数                : JBANG_DIR=" + env("JBANG_DIR") + "  JBANG_REPO=" + env("JBANG_REPO")
                + "  JCHE_JAVA_OPTS=" + env("JCHE_JAVA_OPTS") + "  JCHE_JBANG_OPTS=" + env("JCHE_JBANG_OPTS"));
        t.pause();
    }

    private static String env(String key) {
        String v = System.getenv(key);
        return (v == null || v.isEmpty()) ? "（未設定）" : v;
    }
}
