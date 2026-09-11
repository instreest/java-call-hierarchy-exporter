// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.config;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 直接の依存から出発して、ローカルリポジトリにある POM を辿り、推移的な依存の jar を集める。
 * ネットワークには出ない。無い jar・無い POM は数えて報告する。
 *
 * Maven の解決規則の簡略版:
 * <ul>
 *   <li>版の衝突は、Maven なら「近い方が勝つ」（幅優先で先に見つけた方）、Gradle なら「高い方が勝つ」</li>
 *   <li>推移的な依存の test / provided スコープと optional は含めない（Maven と同じ）</li>
 *   <li>exclusions は経路に沿って引き継ぐ。{@code *} はワイルドカード</li>
 *   <li>ルートの dependencyManagement は推移的な依存の版も上書きする（Maven 3 と同じ）。
 *       直接の依存は自分の版が優先で、無いときだけ管理側の版を使う</li>
 *   <li>リアクタの中のモジュールは target/classes（クラスフォルダ）で、推移的な依存はその pom.xml から</li>
 * </ul>
 */
final class DependencyCollector {

    enum Strategy {
        /** Maven。幅優先で先に見つけた版 */
        NEAREST,
        /** Gradle。最も高い版 */
        HIGHEST
    }

    /** 集めた 1 件。path は jar かクラスフォルダ、via は「どこから要求されたか」の連鎖 */
    record Entry(Path path, String coordinates, String via) {
    }

    static final class Result {
        final List<Entry> entries = new ArrayList<>();
        /** ローカルリポジトリに jar が無い（座標と経路） */
        final List<String> missingJars = new ArrayList<>();
        /** POM が無く、推移的な依存を辿れない */
        final List<String> missingPoms = new ArrayList<>();
        /** 版が決まらない・変数が展開できない等で飛ばした */
        final List<String> unresolved = new ArrayList<>();
        /** 参考情報（ログにそのまま出す。リアクタの構成、ロックファイルを使ったこと等） */
        final List<String> notes = new ArrayList<>();
        int direct;
        int visited;
    }

    private final LocalRepositories repos;
    private final MavenModels models;
    private final MavenReactor reactor;
    private final Strategy strategy;
    /** ルート（解析対象のプロジェクト）の dependencyManagement */
    private final Map<String, Dependency> rootManaged;

    private record Node(Dependency dep, String version, int depth, List<Exclusion> exclusions,
                        String via) {

        String coordinates() {
            return dep.groupId() + ":" + dep.artifactId() + ":" + version
                    + (dep.classifier().isEmpty() ? "" : ":" + dep.classifier());
        }
    }

    /** 見つけたもの。project は推移的な依存を辿るための実効 POM（無ければ null） */
    private record Located(Path artifact, boolean artifactExpected, MavenProject project, boolean pomExpected) {
    }

    /**
     * @param reactor     Maven のリアクタ。Gradle では null
     * @param rootManaged ルートの dependencyManagement（Gradle では platform の BOM）
     */
    DependencyCollector(LocalRepositories repos, MavenModels models, MavenReactor reactor, Strategy strategy,
                        Map<String, Dependency> rootManaged) {
        this.repos = repos;
        this.models = models;
        this.reactor = reactor;
        this.strategy = strategy;
        this.rootManaged = rootManaged;
    }

    /**
     * @param direct     直接の依存（スコープは問わない。テストコードも解析対象になりうるため）
     * @param origin     ログ用。"pom.xml" など
     * @param transitive false なら直接の依存だけ（Gradle のロックファイルのように既に全部揃っているとき）
     */
    Result collect(List<Dependency> direct, String origin, boolean transitive) {
        // HIGHEST（Gradle）は「高い版が勝つ」だが、幅優先の途中で高い版に出会って差し替えると、
        // 低い版の POM から既に辿った（キューに積んだ・処理済みの）推移的な依存が残ったままになる。
        // 高い版の POM の依存とは違いうるので、勝つ版が決まるたびにその版を固定して最初から辿り直す。
        // 版は上がる一方で有限なので必ず止まる（念のため回数の上限も置く）
        Map<String, String> forced = new LinkedHashMap<>();
        for (int round = 0; ; round++) {
            Map<String, String> before = new LinkedHashMap<>(forced);
            Result result = collectOnce(direct, origin, transitive, forced);
            if (forced.equals(before) || round >= MAX_ROUNDS) {
                return result;
            }
        }
    }

    /** 辿り直しの上限。ここまで来たら、その時点の結果で打ち切る */
    private static final int MAX_ROUNDS = 20;

    /**
     * 幅優先で 1 回辿る。
     *
     * @param forced HIGHEST で勝つと分かった版（ga → version）。この回で見つかった、より高い版を追記する
     */
    private Result collectOnce(List<Dependency> direct, String origin, boolean transitive,
                               Map<String, String> forced) {
        Result result = new Result();
        Map<String, Node> chosen = new LinkedHashMap<>();
        Map<String, Entry> entries = new LinkedHashMap<>();
        Deque<Node> queue = new ArrayDeque<>();
        for (Dependency d : direct) {
            Node n = prepare(d, 0, List.of(), origin, rootManaged, forced, result);
            if (n != null) {
                queue.add(n);
                result.direct++;
            }
        }
        while (!queue.isEmpty()) {
            Node n = queue.poll();
            String ga = n.dep().ga();
            Node prev = chosen.get(ga);
            if (prev != null) {
                if (strategy == Strategy.HIGHEST && Versions.compare(n.version(), prev.version()) > 0) {
                    // 高い版に出会った。この回は先に選んだ版のまま進め、次の回で高い版に固定して辿り直す
                    forced.put(ga, n.version());
                }
                continue;
            }
            chosen.put(ga, n);
            result.visited++;

            Located loc = locate(n, result);
            if (loc.artifact() != null) {
                entries.put(ga, new Entry(loc.artifact(), n.coordinates(), n.via()));
            } else if (loc.artifactExpected()) {
                result.missingJars.add(n.coordinates() + "（" + n.via() + "）");
            }
            if (!transitive) {
                continue;
            }
            if (loc.project() == null) {
                if (loc.pomExpected()) {
                    result.missingPoms.add(n.coordinates() + "（" + n.via() + "）");
                }
                continue;
            }
            String childVia = n.coordinates() + " ← " + n.via();
            for (Dependency child : loc.project().dependencies) {
                if (child.optional()) {
                    continue;
                }
                Dependency childManaged = loc.project().managed.get(child.managementKey());
                String scope = child.scope().isEmpty()
                        ? (childManaged != null && !childManaged.scope().isEmpty() ? childManaged.scope() : "compile")
                        : child.scope();
                if (scope.equals("test") || scope.equals("provided") || scope.equals("import")) {
                    continue;
                }
                if (excluded(child, n.exclusions())) {
                    continue;
                }
                List<Exclusion> exclusions = new ArrayList<>(n.exclusions());
                exclusions.addAll(child.exclusions());
                if (childManaged != null) {
                    exclusions.addAll(childManaged.exclusions());
                }
                Node c = prepare(child, n.depth() + 1, exclusions, childVia, loc.project().managed, forced, result);
                if (c != null) {
                    queue.add(c);
                }
            }
        }
        result.entries.addAll(entries.values());
        return result;
    }

    /**
     * 版を決めて Node にする。決まらなければ null（result.unresolved に記録）。
     *
     * @param ownManaged その依存を宣言した POM の dependencyManagement（版が無いときに使う）
     */
    private Node prepare(Dependency dep, int depth, List<Exclusion> exclusions, String via,
                         Map<String, Dependency> ownManaged, Map<String, String> forced, Result result) {
        Dependency own = ownManaged.get(dep.managementKey());
        Dependency root = rootManaged.get(dep.managementKey());
        String version = dep.version();
        if (version.isEmpty() && own != null) {
            version = own.version();
        }
        if (depth > 0 && root != null && !root.version().isEmpty()) {
            version = root.version();   // ルートの管理は推移的な依存の版を上書きする
        }
        String won = forced.get(dep.ga());
        if (won != null) {
            version = won;   // 前の回で「高い版が勝つ」と分かった版
        }
        if (dep.groupId().contains("${") || dep.artifactId().contains("${") || version.contains("${")) {
            result.unresolved.add(dep.ga() + ":" + version + "（変数が展開できない。" + via + "）");
            return null;
        }
        if (version.isEmpty()) {
            result.unresolved.add(dep.ga() + "（版が決まらない。" + via + "）");
            return null;
        }
        if (Versions.isDynamic(version)) {
            String selected = Versions.select(version, repos.versions(dep.groupId(), dep.artifactId()));
            if (selected == null) {
                result.unresolved.add(dep.ga() + ":" + version + "（範囲に合う版がローカルに無い。" + via + "）");
                return null;
            }
            version = selected;
        }
        List<Exclusion> all = new ArrayList<>(exclusions);
        if (own != null) {
            all.addAll(own.exclusions());
        }
        return new Node(dep, version, depth, all, via);
    }

    private static boolean excluded(Dependency dep, List<Exclusion> exclusions) {
        for (Exclusion x : exclusions) {
            if (x.matches(dep.groupId(), dep.artifactId())) {
                return true;
            }
        }
        return false;
    }

    /** jar（またはクラスフォルダ）と、推移的な依存を辿るための実効 POM を探す */
    private Located locate(Node n, Result result) {
        Dependency dep = n.dep();
        String type = dep.type().isEmpty() ? "jar" : dep.type();
        String classifier = dep.classifier();
        String extension;
        switch (type) {
            case "pom":
                extension = "";
                break;
            case "test-jar":
                extension = "jar";
                classifier = classifier.isEmpty() ? "tests" : classifier;
                break;
            case "ejb-client":
                extension = "jar";
                classifier = classifier.isEmpty() ? "client" : classifier;
                break;
            case "jar":
            case "bundle":
            case "ejb":
            case "maven-plugin":
            case "eclipse-plugin":
                extension = "jar";
                break;
            default:
                extension = type;   // war / ear / zip / aar など。zip 以外はクラスパスに載らない
        }
        boolean onClasspath = extension.equals("jar") || extension.equals("zip");

        if ("system".equals(dep.scope()) && !dep.systemPath().isEmpty()) {
            Path p = null;
            try {
                p = Paths.get(dep.systemPath());
            } catch (InvalidPathException ignore) {
                // systemPath が壊れている。無いものとして扱う
            }
            return new Located(p != null && Files.isRegularFile(p) ? p : null, true, null, false);
        }

        Path moduleDir = (reactor == null) ? null : reactor.moduleDir(dep.ga());
        if (moduleDir != null) {
            MavenProject project = models.project(moduleDir.resolve("pom.xml"));
            boolean expectsClasses = project == null || !project.packaging.equals("pom");
            Path classes = moduleDir.resolve("target").resolve("classes");
            if (expectsClasses && !Files.isDirectory(classes)) {
                // 兄弟モジュールが未ビルド。そのソースを解析対象に含めていれば型はソースから解決されるので、警告ではなく注記
                result.notes.add("リアクタのモジュール " + dep.ga() + " はビルドされていません（" + classes
                        + " が無い）。そのモジュールのソースを解析対象に含めていれば問題ありません");
                return new Located(null, false, project, project == null);
            }
            return new Located(expectsClasses ? classes : null, false, project, project == null);
        }

        MavenProject project = models.fromRepository(dep.groupId(), dep.artifactId(), n.version());
        if (project != null && project.relocation != null) {
            MavenPom.Coordinates r = project.relocation;
            String g = r.groupId().isEmpty() ? dep.groupId() : r.groupId();
            String a = r.artifactId().isEmpty() ? dep.artifactId() : r.artifactId();
            String v = r.version().isEmpty() ? n.version() : r.version();
            project = models.fromRepository(g, a, v);
            Path jar = onClasspath ? repos.find(g, a, v, classifier, extension) : null;
            return new Located(jar, onClasspath && !isPomPackaging(project), project, project == null);
        }
        boolean expectsArtifact = onClasspath && !isPomPackaging(project) && !extension.isEmpty();
        Path jar = expectsArtifact ? repos.find(dep.groupId(), dep.artifactId(), n.version(), classifier, extension) : null;
        return new Located(jar, expectsArtifact, project, project == null);
    }

    private static boolean isPomPackaging(MavenProject project) {
        return project != null && project.packaging.equals("pom");
    }
}
