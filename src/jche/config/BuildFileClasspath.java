// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.config;

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
import jche.util.Messages;

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
 * 集めた一覧（パス・座標・要求元）は実行ログ（run.log）に書く（何がどこから来たかを後から確認できる）。
 * 以前は出力フォルダの resolved-classpath.txt に別に書いていたが、出力ファイルを減らすためログにまとめた
 * （{@code docs/output-files-simplify-qa.md}）。
 */
public final class BuildFileClasspath {

    private BuildFileClasspath() {
    }

    /**
     * @param config        設定（library.build.tool / library.repositories / 出力フォルダ）
     * @param projectRoot   project.root
     * @param sourceFolders 解析対象のソースフォルダ
     * @return 存在する jar とクラスフォルダ。集められなければ空
     */
    public static List<Path> resolve(Config config, Path projectRoot, List<Path> sourceFolders) {
        if ("none".equals(config.libraryBuildTool)) {
            Log.info(Messages.get("config.deps.toolNone"));
            return List.of();
        }
        List<Path> candidates = candidateDirs(projectRoot, sourceFolders);
        if (candidates.isEmpty()) {
            Log.info(Messages.format("config.deps.noBuildFile", projectRoot));
            return List.of();
        }
        Log.info(Messages.get("config.deps.fromBuildFiles"));
        LocalRepositories repos = LocalRepositories.discover(config);
        Log.info(Messages.format("config.deps.repositories", repos.roots().isEmpty()
                ? Messages.get("config.deps.repositories.none") : repos.roots().toString()));
        MavenModels models = new MavenModels(repos);

        Map<Path, DependencyCollector.Entry> all = new LinkedHashMap<>();
        for (Path dir : candidates) {
            BuildTool.Detection detection = select(config, dir);
            if (detection == null) {
                continue;
            }
            Log.info(Messages.format("config.deps.detected", detection.tool().displayName, dir, detection.reason()));
            long start = System.nanoTime();
            DependencyCollector.Result result = (detection.tool() == BuildTool.MAVEN)
                    ? MavenBuild.resolve(dir, repos, models)
                    : GradleBuild.resolve(dir, projectRoot, repos, models);
            if (result == null) {
                Log.warn(Messages.format("config.deps.unreadableBuildFile", dir));
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
        Log.info(Messages.format("config.deps.collected", paths.size() - folders,
                (folders > 0) ? Messages.format("config.deps.collected.folders", folders) : ""));
        logListing(all.values());
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
                Log.warn(Messages.format("config.deps.forcedToolMissing", forced, dir, tool.buildFileNames));
                return null;
            }
            return new BuildTool.Detection(tool, Messages.format("config.deps.forcedReason", forced));
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
        Log.info(Messages.format("config.deps.resolved", result.direct, result.entries.size() - folders,
                (folders > 0) ? Messages.format("config.deps.collected.folders", folders) : "",
                result.visited, String.format(Locale.ROOT, "%.1f", seconds)));
        for (String note : result.notes) {
            Log.info("    " + note);
        }
        if (!result.missingJars.isEmpty()) {
            Log.warn(Messages.format("config.deps.missingJars", result.missingJars.size()));
            for (String m : result.missingJars) {
                Log.info("      " + m);
            }
        }
        if (!result.missingPoms.isEmpty()) {
            Log.warn(Messages.format("config.deps.missingPoms", result.missingPoms.size()));
            for (String m : result.missingPoms) {
                Log.info("      " + m);
            }
        }
        if (!result.unresolved.isEmpty()) {
            Log.warn(Messages.format("config.deps.unresolved", result.unresolved.size()));
            for (String m : result.unresolved) {
                Log.info("      " + m);
            }
        }
    }

    /**
     * 集めた一覧（パス、座標、要求元の経路）をログに書く。表として読むものなので時刻は付けない（{@link Log#plain}）。
     * 1 行の形は旧 resolved-classpath.txt と同じタブ区切りで、先頭に字下げを付けてログの他の行と見分ける
     */
    private static void logListing(Iterable<DependencyCollector.Entry> entries) {
        Log.plain("    # path\tcoordinates\tvia" + Messages.get("config.deps.listingViaNote"));
        for (DependencyCollector.Entry e : entries) {
            Log.plain("    " + e.path() + "\t" + e.coordinates() + "\t" + e.via());
        }
    }
}
