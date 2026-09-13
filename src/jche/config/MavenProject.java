// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 親からの継承・BOM の import・${...} の展開を済ませた「実効 POM」（依存 jar を集めるのに要る部分だけ）。
 * {@link MavenModels} が作る。
 */
final class MavenProject {

    /** この POM のファイル。ローカルリポジトリの POM のこともある */
    final Path pomFile;
    final String groupId;
    final String artifactId;
    final String version;
    final String packaging;
    /** 親も含めて併合し、展開したプロパティ */
    final Map<String, String> properties;
    /** 親も含めて併合した依存。版が空のものは managed で決まる */
    final List<Dependency> dependencies;
    /** dependencyManagement（親と import した BOM も含む）。鍵は {@link Dependency#managementKey()} */
    final Map<String, Dependency> managed;
    /** この POM 自身の modules（親のものは含まない） */
    final List<String> modules;
    /** 移転先。無ければ null */
    final MavenPom.Coordinates relocation;

    MavenProject(Path pomFile, String groupId, String artifactId, String version, String packaging,
                 Map<String, String> properties, List<Dependency> dependencies,
                 Map<String, Dependency> managed, List<String> modules, MavenPom.Coordinates relocation) {
        this.pomFile = pomFile;
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.packaging = packaging;
        this.properties = Map.copyOf(properties);
        this.dependencies = List.copyOf(dependencies);
        this.managed = Map.copyOf(managed);
        this.modules = List.copyOf(modules);
        this.relocation = relocation;
    }

    String ga() {
        return groupId + ":" + artifactId;
    }

    String gav() {
        return groupId + ":" + artifactId + ":" + version;
    }

    Path basedir() {
        return pomFile.getParent();
    }
}
