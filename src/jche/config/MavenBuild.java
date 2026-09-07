// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.config;

import java.nio.file.Path;

/**
 * Maven プロジェクト（pom.xml）の依存 jar を、Maven を実行せずに集める。
 *
 * 実効 POM（{@link MavenModels}）の依存を出発点に、リアクタ（{@link MavenReactor}）と
 * ローカルリポジトリの POM を辿る（{@link DependencyCollector}。版の衝突は近い方が勝つ）。
 */
final class MavenBuild {

    private MavenBuild() {
    }

    /** dir の pom.xml の依存 jar。pom.xml が読めなければ null */
    static DependencyCollector.Result resolve(Path dir, LocalRepositories repos, MavenModels models) {
        MavenProject project = models.project(dir.resolve("pom.xml"));
        if (project == null) {
            return null;
        }
        MavenReactor reactor = MavenReactor.of(dir, models);
        DependencyCollector collector = new DependencyCollector(repos, models, reactor,
                DependencyCollector.Strategy.NEAREST, project.managed);
        DependencyCollector.Result result = collector.collect(project.dependencies, "pom.xml", true);
        if (reactor.size() > 1) {
            result.notes.add("リアクタのモジュール " + reactor.size() + " 件（ルート: " + reactor.root + "）。"
                    + "兄弟モジュールは target/classes と、その pom.xml の依存で解決する");
        }
        return result;
    }
}
