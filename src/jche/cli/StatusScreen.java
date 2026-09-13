// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import jche.config.Config;

/** 対話モードの「実行環境の状態」画面。実際に使っている JDK・JDT・置き場所・キャッシュの一覧を出す */
final class StatusScreen {

    private StatusScreen() {
    }

    static void show(Terminal t, Path root) throws IOException {
        LauncherSettings settings = LauncherSettings.load(root);
        Path jbangDir = settings.currentJbangDir();
        Path repo = settings.currentRepo();
        Path cacheRoot = root.resolve(Config.DEFAULT_CACHE_DIR_NAME);
        Path jdt = EnvironmentInfo.jdtJar();
        t.println();
        t.println("--- 実行環境の状態 ---");
        t.println(" ツールのフォルダ        : " + root);
        t.println(" 起動の経路              : " + (LauncherSettings.launchedByLauncher() ? "java-call-hierarchy-exporter.sh / java-call-hierarchy-exporter.cmd" : "jbang を直接（環境設定の再起動は使えない）"));
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
                + "  JCHE_JAVA_OPTS=" + env("JCHE_JAVA_OPTS") + "  JCHE_JBANG_OPTS=" + env("JCHE_JBANG_OPTS")
                + "  JCHE_ALLOW_DOWNLOAD=" + env("JCHE_ALLOW_DOWNLOAD"));
        t.println(" ネットワークからの取得  : " + EnvironmentSettingsScreen.describeAllowDownload(settings.get(LauncherSettings.KEY_ALLOW_DOWNLOAD)));
        t.pause();
    }

    private static String env(String key) {
        String v = System.getenv(key);
        return (v == null || v.isEmpty()) ? "（未設定）" : v;
    }
}
