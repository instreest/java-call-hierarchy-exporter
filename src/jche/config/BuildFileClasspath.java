/*
 * Copyright 2026 the java-call-hierarchy-exporter authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jche.config;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import jche.util.Log;

/**
 * library.folders が空欄のとき、ビルドファイル（pom.xml / build.gradle）とローカルリポジトリから依存 jar を集める。
 *
 * ビルドツール（Maven / Gradle）は実行しない。ネットワークにも出ない。
 * ビルドファイルをこのツールが読み、依存の jar と POM を ~/.m2/repository や ~/.gradle/caches
 * （{@link LocalRepositories}）から探す。推移的な依存はローカルにある POM を辿って集める
 * （{@link DependencyCollector}）。無い jar は数えて報告し、解析は依存無しの部分だけ欠けた状態で続ける。
 *
 * ビルドファイルは、各ソースフォルダから project.root まで上位へ辿って最初に見つかった場所のものを読む。
 * マルチモジュールで project.root がアグリゲータなら、ソースフォルダを持つモジュールごとに読む
 * （兄弟モジュールへの依存はリアクタとして解決される）。
 *
 * 集めた一覧は cache.folders/resolved-classpath.txt に残す（何がどこから来たかを後から確認できる）。
 */
public final class BuildFileClasspath {

    static final String LISTING_FILE = "resolved-classpath.txt";

    private BuildFileClasspath() {
    }

    /**
     * @param config        設定（library.build.tool / library.repositories / cache.folders）
     * @param projectRoot   project.root
     * @param sourceFolders 解析対象のソースフォルダ
     * @return 存在する jar とクラスフォルダ。集められなければ空
     */
    public static List<Path> resolve(Config config, Path projectRoot, List<Path> sourceFolders) {
        if ("none".equals(config.libraryBuildTool)) {
            Log.info("依存jar: library.folders は空欄ですが library.build.tool=none のため、ビルドファイルからの自動取得はしません");
            return List.of();
        }
        List<Path> candidates = candidateDirs(projectRoot, sourceFolders);
        if (candidates.isEmpty()) {
            Log.info("依存jar: library.folders が空欄で、pom.xml / build.gradle も見つからないため、"
                    + "依存jar無しで解析します（探した場所: 各ソースフォルダから " + projectRoot + " までの上位）");
            return List.of();
        }
        Log.info("依存jar: library.folders が空欄のため、ビルドファイルとローカルリポジトリから集めます（ビルドツールは実行しない）");
        LocalRepositories repos = LocalRepositories.discover(config);
        Log.info("  ローカルリポジトリ: " + (repos.roots().isEmpty() ? "（無し）" : repos.roots().toString()));
        MavenModels models = new MavenModels(repos);

        Map<Path, DependencyCollector.Entry> all = new LinkedHashMap<>();
        for (Path dir : candidates) {
            BuildTool.Detection detection = select(config, dir);
            if (detection == null) {
                continue;
            }
            Log.info("  " + detection.tool().displayName + ": " + dir + "（" + detection.reason() + "）");
            long start = System.nanoTime();
            DependencyCollector.Result result = (detection.tool() == BuildTool.MAVEN)
                    ? MavenBuild.resolve(dir, repos, models)
                    : GradleBuild.resolve(dir, repos, models);
            if (result == null) {
                Log.warn("依存jar: " + dir + " のビルドファイルを読めませんでした");
                continue;
            }
            report(result, (System.nanoTime() - start) / 1_000_000_000.0);
            for (DependencyCollector.Entry e : result.entries) {
                all.putIfAbsent(e.path().toAbsolutePath().normalize(), e);
            }
        }

        List<Path> paths = new ArrayList<>(all.keySet());
        int folders = 0;
        for (Path p : paths) {
            if (Files.isDirectory(p)) {
                folders++;
            }
        }
        Path listing = writeListing(config, all.values());
        Log.info("  取得: jar " + (paths.size() - folders) + " 件"
                + (folders > 0 ? "、クラスフォルダ " + folders + " 件" : "")
                + (listing == null ? "" : "（一覧は " + listing + "）"));
        return paths;
    }

    /**
     * ビルドファイルを読むディレクトリ。各ソースフォルダから project.root まで上位へ辿って、
     * 最初にビルドファイルが見つかった場所（重複は除く）。
     */
    static List<Path> candidateDirs(Path projectRoot, List<Path> sourceFolders) {
        Set<Path> found = new LinkedHashSet<>();
        for (Path sf : sourceFolders) {
            for (Path dir = sf; dir != null && dir.startsWith(projectRoot); dir = dir.getParent()) {
                if (BuildTool.hasBuildFile(dir)) {
                    found.add(dir);
                    break;
                }
            }
        }
        return new ArrayList<>(found);
    }

    /** library.build.tool の明示があればそれ、無ければビルドファイルから検出する */
    private static BuildTool.Detection select(Config config, Path dir) {
        String forced = config.libraryBuildTool;
        if ("maven".equals(forced) || "gradle".equals(forced)) {
            BuildTool tool = BuildTool.valueOf(forced.toUpperCase(Locale.ROOT));
            if (!tool.hasBuildFileIn(dir)) {
                Log.warn("依存jar: library.build.tool=" + forced + " ですが、" + dir + " に "
                        + tool.buildFileNames + " がありません。このディレクトリは飛ばします");
                return null;
            }
            return new BuildTool.Detection(tool, "library.build.tool=" + forced + " の指定による");
        }
        return BuildTool.detect(dir);
    }

    private static void report(DependencyCollector.Result result, double seconds) {
        int folders = 0;
        for (DependencyCollector.Entry e : result.entries) {
            if (Files.isDirectory(e.path())) {
                folders++;
            }
        }
        Log.info("    直接依存 " + result.direct + " 件 → jar " + (result.entries.size() - folders) + " 件"
                + (folders > 0 ? "、クラスフォルダ " + folders + " 件" : "")
                + "（辿った依存 " + result.visited + " 件、" + String.format(Locale.ROOT, "%.1f", seconds) + " 秒）");
        for (String note : result.notes) {
            Log.info("    " + note);
        }
        if (!result.missingJars.isEmpty()) {
            Log.warn("依存jar: ローカルリポジトリに無い jar: " + result.missingJars.size() + " 件。"
                    + "Eclipse や Maven で一度ビルド（依存の取得）すると入ります。別の場所にあるなら library.repositories を指定してください");
            for (String m : result.missingJars) {
                Log.info("      " + m);
            }
        }
        if (!result.missingPoms.isEmpty()) {
            Log.warn("依存jar: POM がローカルリポジトリに無く、その先の推移的な依存を辿れない: " + result.missingPoms.size() + " 件");
            for (String m : result.missingPoms) {
                Log.info("      " + m);
            }
        }
        if (!result.unresolved.isEmpty()) {
            Log.warn("依存jar: 版が決まらない等で飛ばした依存: " + result.unresolved.size() + " 件");
            for (String m : result.unresolved) {
                Log.info("      " + m);
            }
        }
    }

    /** 集めた一覧（パス、座標、要求元の経路）を cache.folders に書く。書けなければ null */
    private static Path writeListing(Config config, Iterable<DependencyCollector.Entry> entries) {
        Path dir = config.cacheFile.toAbsolutePath().getParent();
        if (dir == null) {
            return null;
        }
        Path file = dir.resolve(LISTING_FILE);
        try {
            Files.createDirectories(dir);
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                w.write("# path\tcoordinates\tvia（要求元の連鎖。直接の依存はビルドファイル名）");
                w.newLine();
                for (DependencyCollector.Entry e : entries) {
                    w.write(e.path() + "\t" + e.coordinates() + "\t" + e.via());
                    w.newLine();
                }
            }
            return file;
        } catch (IOException e) {
            Log.warn("依存jar: 一覧を書けません: " + file + " (" + e + ")");
            return null;
        }
    }
}
