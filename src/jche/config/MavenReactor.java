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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * マルチモジュールの Maven プロジェクトで、同じビルドに属するモジュール（リアクタ）の一覧。
 *
 * 依存先がリアクタの中のモジュールなら、ローカルリポジトリの jar ではなくそのモジュールの
 * target/classes（あれば）を使い、推移的な依存はそのモジュールの pom.xml から辿る。
 * mvn install していなくても兄弟モジュールが解決できるのはこのため。
 *
 * ルートは、実行ディレクトリの pom.xml から親の relativePath を辿って、ディスク上にある最上位の
 * POM とする。そこから modules を再帰的に集める。
 */
final class MavenReactor {

    final Path root;
    /** groupId:artifactId → モジュールのディレクトリ */
    private final Map<String, Path> moduleDirs = new LinkedHashMap<>();

    private MavenReactor(Path root) {
        this.root = root;
    }

    static MavenReactor of(Path projectDir, MavenModels models) {
        MavenReactor reactor = new MavenReactor(findRoot(projectDir));
        reactor.register(reactor.root, models, new HashSet<>());
        reactor.registerDir(projectDir, models);   // アグリゲータに載っていないモジュールでも自分自身は入れる
        return reactor;
    }

    Path moduleDir(String ga) {
        return moduleDirs.get(ga);
    }

    int size() {
        return moduleDirs.size();
    }

    /** 親の relativePath を辿って、ディスク上にある最上位の POM のディレクトリ */
    private static Path findRoot(Path projectDir) {
        Path cur = projectDir;
        for (int depth = 0; depth < 30; depth++) {
            MavenPom pom;
            try {
                pom = MavenPom.read(cur.resolve("pom.xml"));
            } catch (IOException e) {
                break;
            }
            if (pom.parent == null || pom.parentRelativePath.isEmpty()) {
                break;   // relativePath を空にした親はリポジトリから取るもので、ディスク上には無い
            }
            Path candidate = cur.resolve(pom.parentRelativePath).normalize();
            if (Files.isDirectory(candidate)) {
                candidate = candidate.resolve("pom.xml");
            }
            if (!Files.isRegularFile(candidate)) {
                break;
            }
            try {
                MavenPom parent = MavenPom.read(candidate);
                if (!parent.artifactId.equals(pom.parent.artifactId())) {
                    break;
                }
            } catch (IOException e) {
                break;
            }
            cur = candidate.getParent();
        }
        return cur;
    }

    /** dir の pom.xml と、その modules を再帰的に登録する */
    private void register(Path dir, MavenModels models, Set<Path> visited) {
        Path normalized = dir.toAbsolutePath().normalize();
        if (!visited.add(normalized) || !Files.isRegularFile(normalized.resolve("pom.xml"))) {
            return;
        }
        registerDir(normalized, models);
        MavenPom pom;
        try {
            pom = MavenPom.read(normalized.resolve("pom.xml"));
        } catch (IOException e) {
            return;
        }
        for (String module : pom.modules) {
            Path moduleDir = normalized.resolve(module).normalize();
            if (moduleDir.getFileName() != null && moduleDir.getFileName().toString().equals("pom.xml")) {
                moduleDir = moduleDir.getParent();
            }
            register(moduleDir, models, visited);
        }
    }

    private void registerDir(Path dir, MavenModels models) {
        MavenProject project = models.project(dir.resolve("pom.xml"));
        if (project != null) {
            moduleDirs.putIfAbsent(project.ga(), dir.toAbsolutePath().normalize());
        }
    }
}
