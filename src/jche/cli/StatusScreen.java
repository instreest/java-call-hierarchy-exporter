// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import jche.config.Config;
import jche.util.Messages;

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
        t.println(Messages.get("cli.status.title"));
        t.println(Messages.format("cli.status.toolDir", root));
        t.println(Messages.format("cli.status.launchedBy", Messages.get(LauncherSettings.launchedByLauncher()
                ? "cli.status.launchedBy.launcher" : "cli.status.launchedBy.jbang")));
        t.println(Messages.format("cli.status.settingsFile",
                settings.exists() ? settings.file : Messages.get("cli.status.settingsFile.none")));
        t.println(Messages.format("cli.status.jbangDir", jbangDir,
                EnvironmentInfo.humanSize(EnvironmentInfo.sizeOf(jbangDir)),
                jbangDir.equals(settings.configuredJbangDir()) ? ""
                        : Messages.format("cli.status.jbangDir.pending", settings.configuredJbangDir())));
        List<String> jdks = EnvironmentInfo.installedJdks(jbangDir);
        t.println(Messages.format("cli.status.jdks",
                jdks.isEmpty() ? Messages.get("cli.status.jdks.none") : String.join(", ", jdks)));
        t.println(Messages.format("cli.status.repo", repo,
                EnvironmentInfo.humanSize(EnvironmentInfo.sizeOf(repo))));
        t.println(Messages.format("cli.status.runningJdk", EnvironmentInfo.javaVersion()));
        t.println(Messages.format("cli.status.javaHome", EnvironmentInfo.javaHome()));
        t.println(Messages.format("cli.status.maxHeap", EnvironmentInfo.maxHeapMb()));
        t.println(Messages.format("cli.status.jdt", (jdt == null) ? Messages.get("cli.status.jdt.unknown") : jdt));
        t.println(Messages.format("cli.status.cacheRoot", cacheRoot));
        List<String> caches = EnvironmentInfo.cacheEntries(cacheRoot);
        if (caches.isEmpty()) {
            t.println(Messages.get("cli.status.cache.none"));
        } else {
            for (String c : caches) {
                t.println("   " + c);
            }
        }
        t.println(Messages.format("cli.status.env", env("JBANG_DIR"), env("JBANG_REPO"),
                env("JCHE_JAVA_OPTS"), env("JCHE_JBANG_OPTS"), env("JCHE_ALLOW_DOWNLOAD")));
        t.println(Messages.format("cli.status.allowDownload",
                EnvironmentSettingsScreen.describeAllowDownload(settings.get(LauncherSettings.KEY_ALLOW_DOWNLOAD))));
        t.pause();
    }

    private static String env(String key) {
        String v = System.getenv(key);
        return (v == null || v.isEmpty()) ? Messages.get("cli.status.env.unset") : v;
    }
}
