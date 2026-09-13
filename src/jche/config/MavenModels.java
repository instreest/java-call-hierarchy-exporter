// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jche.util.Log;

/**
 * pom.xml から実効 POM（{@link MavenProject}）を作る。Maven のモデルビルダーの簡略版。
 *
 * やること:
 * <ul>
 *   <li>親の連鎖を辿る。relativePath（既定 ../pom.xml）にあって座標が一致すればそれ、
 *       無ければローカルリポジトリの POM。ネットワークには出ない</li>
 *   <li>プロパティを親から順に重ね、{@code ${...}} を展開する（project.* / pom.* / parent.* の組み込み、
 *       システムプロパティ、env.* も見る）</li>
 *   <li>dependencies と dependencyManagement を親から継承する（子が同じ鍵を持てば子が勝つ）</li>
 *   <li>dependencyManagement の import（BOM）をローカルリポジトリの POM から展開する</li>
 * </ul>
 * やらないこと: activeByDefault 以外のプロファイル、settings.xml のプロファイル、CLI の -D。
 * 読んだ POM はファイルと座標の両方で覚えておき、同じ POM を何度も読まない。
 */
final class MavenModels {

    private final LocalRepositories repos;
    private final Map<Path, MavenProject> byFile = new HashMap<>();
    private final Map<String, MavenProject> byGav = new HashMap<>();
    /** 構築中の POM（親や BOM が循環していても止まらないための印） */
    private final Set<Path> building = new HashSet<>();
    private final Set<String> warned = new HashSet<>();

    MavenModels(LocalRepositories repos) {
        this.repos = repos;
    }

    /** ファイルの実効 POM。読めなければ警告して null */
    MavenProject project(Path pomFile) {
        Path key = pomFile.toAbsolutePath().normalize();
        if (byFile.containsKey(key)) {
            return byFile.get(key);
        }
        if (!building.add(key)) {
            warnOnce("親か BOM が循環しています: " + key);
            return null;
        }
        MavenProject project = null;
        try {
            project = build(MavenPom.read(key));
        } catch (IOException e) {
            warnOnce(e.getMessage());
        } finally {
            building.remove(key);
        }
        byFile.put(key, project);
        if (project != null) {
            byGav.putIfAbsent(project.gav(), project);
        }
        return project;
    }

    /** ローカルリポジトリにある POM の実効 POM。無ければ null（警告は出さない。呼ぶ側が集計する） */
    MavenProject fromRepository(String groupId, String artifactId, String version) {
        String gav = groupId + ":" + artifactId + ":" + version;
        if (byGav.containsKey(gav)) {
            return byGav.get(gav);
        }
        Path pom = repos.find(groupId, artifactId, version, "", "pom");
        MavenProject project = (pom == null) ? null : project(pom);
        byGav.put(gav, project);
        return project;
    }

    private MavenProject build(MavenPom pom) {
        // --- 親の連鎖 ---
        List<MavenPom> chain = new ArrayList<>();
        chain.add(pom);
        MavenPom cur = pom;
        for (int depth = 0; cur.parent != null && depth < 30; depth++) {
            MavenPom parent = locateParent(cur);
            if (parent == null) {
                break;
            }
            boolean cycle = false;
            for (MavenPom seen : chain) {
                if (seen.file.equals(parent.file)) {
                    cycle = true;
                }
            }
            if (cycle) {
                warnOnce("親の連鎖が循環しています: " + parent.file);
                break;
            }
            chain.add(parent);
            cur = parent;
        }

        // --- 座標（無ければ親の宣言から） ---
        String groupId = pom.groupId.isEmpty() && pom.parent != null ? pom.parent.groupId() : pom.groupId;
        String version = pom.version.isEmpty() && pom.parent != null ? pom.parent.version() : pom.version;

        // --- プロパティ: 最上位の親から順に重ねる（子が勝つ）。組み込みの project.* を足して展開する ---
        Map<String, String> properties = new LinkedHashMap<>();
        for (int i = chain.size() - 1; i >= 0; i--) {
            properties.putAll(chain.get(i).properties);
        }
        Map<String, String> builtin = new LinkedHashMap<>();
        builtin.put("project.groupId", groupId);
        builtin.put("project.artifactId", pom.artifactId);
        builtin.put("project.version", version);
        builtin.put("project.packaging", pom.packaging);
        builtin.put("project.basedir", pom.file.getParent().toString());
        builtin.put("basedir", pom.file.getParent().toString());
        if (pom.parent != null) {
            builtin.put("project.parent.groupId", pom.parent.groupId());
            builtin.put("project.parent.artifactId", pom.parent.artifactId());
            builtin.put("project.parent.version", pom.parent.version());
            builtin.put("parent.version", pom.parent.version());
            builtin.put("parent.groupId", pom.parent.groupId());
        }
        for (Map.Entry<String, String> e : builtin.entrySet()) {
            properties.put(e.getKey(), e.getValue());
            properties.put(e.getKey().replace("project.", "pom."), e.getValue());
        }
        properties.putIfAbsent("groupId", groupId);
        properties.putIfAbsent("artifactId", pom.artifactId);
        properties.putIfAbsent("version", version);
        for (int round = 0; round < 10; round++) {
            boolean changed = false;
            for (Map.Entry<String, String> e : properties.entrySet()) {
                String expanded = interpolate(e.getValue(), properties);
                if (!expanded.equals(e.getValue())) {
                    e.setValue(expanded);
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        groupId = interpolate(groupId, properties);
        version = interpolate(version, properties);

        // --- 依存と dependencyManagement: 子から順に、同じ鍵は先に見たものが勝つ ---
        Map<String, Dependency> dependencies = new LinkedHashMap<>();
        Map<String, Dependency> managed = new LinkedHashMap<>();
        for (MavenPom p : chain) {
            for (Dependency d : p.dependencies) {
                Dependency expanded = interpolate(d, properties);
                dependencies.putIfAbsent(expanded.managementKey(), expanded);
            }
            for (Dependency d : p.dependencyManagement) {
                Dependency expanded = interpolate(d, properties);
                managed.putIfAbsent(expanded.managementKey(), expanded);
            }
        }
        expandImports(managed, pom.file);

        return new MavenProject(pom.file, groupId, pom.artifactId, version, pom.packaging, properties,
                new ArrayList<>(dependencies.values()), managed, pom.modules, pom.relocation);
    }

    /**
     * 親 POM を探す。relativePath にあって座標が一致すればそれ、無ければローカルリポジトリ。
     * 親の座標の ${...}（${revision} のような CI 向けの書き方）は子のプロパティで展開する
     */
    private MavenPom locateParent(MavenPom child) {
        Map<String, String> props = new LinkedHashMap<>(child.properties);
        String g = interpolate(child.parent.groupId(), props);
        String a = interpolate(child.parent.artifactId(), props);
        String v = interpolate(child.parent.version(), props);
        if (!child.parentRelativePath.isEmpty()) {
            Path candidate = child.file.getParent().resolve(child.parentRelativePath).normalize();
            if (Files.isDirectory(candidate)) {
                candidate = candidate.resolve("pom.xml");
            }
            if (Files.isRegularFile(candidate)) {
                try {
                    MavenPom parent = MavenPom.read(candidate);
                    if (matches(parent, g, a, v)) {
                        return parent;
                    }
                } catch (IOException e) {
                    warnOnce(e.getMessage());
                }
            }
        }
        Path pom = repos.find(g, a, v, "", "pom");
        if (pom == null) {
            warnOnce("親 POM がローカルリポジトリにありません: " + g + ":" + a + ":" + v + "（" + child.file + " の親）");
            return null;
        }
        try {
            return MavenPom.read(pom);
        } catch (IOException e) {
            warnOnce(e.getMessage());
            return null;
        }
    }

    /** relativePath の POM が本当にその親か（座標の一致。POM に無い groupId / version はさらにその親のもの） */
    private static boolean matches(MavenPom pom, String g, String a, String v) {
        String pg = pom.groupId.isEmpty() && pom.parent != null ? pom.parent.groupId() : pom.groupId;
        String pv = pom.version.isEmpty() && pom.parent != null ? pom.parent.version() : pom.version;
        return pom.artifactId.equals(a) && pg.equals(g) && pv.equals(v);
    }

    /** scope=import の BOM をローカルリポジトリから読み、その dependencyManagement を（自分のより低い優先度で）足す */
    private void expandImports(Map<String, Dependency> managed, Path owner) {
        List<Dependency> imports = new ArrayList<>();
        for (Dependency d : managed.values()) {
            if ("import".equals(d.scope()) && "pom".equals(d.type())) {
                imports.add(d);
            }
        }
        for (Dependency bom : imports) {
            managed.remove(bom.managementKey());
        }
        for (Dependency bom : imports) {
            if (bom.version().isEmpty() || bom.version().contains("${")) {
                warnOnce("BOM の版が決まりません: " + bom.ga() + ":" + bom.version() + "（" + owner + "）");
                continue;
            }
            MavenProject bomProject = fromRepository(bom.groupId(), bom.artifactId(), bom.version());
            if (bomProject == null) {
                warnOnce("BOM がローカルリポジトリにありません: " + bom.ga() + ":" + bom.version() + "（" + owner + "）");
                continue;
            }
            for (Map.Entry<String, Dependency> e : bomProject.managed.entrySet()) {
                managed.putIfAbsent(e.getKey(), e.getValue());
            }
        }
    }

    private static Dependency interpolate(Dependency d, Map<String, String> props) {
        List<Exclusion> exclusions = new ArrayList<>();
        for (Exclusion x : d.exclusions()) {
            exclusions.add(new Exclusion(interpolate(x.groupId(), props), interpolate(x.artifactId(), props)));
        }
        return new Dependency(interpolate(d.groupId(), props), interpolate(d.artifactId(), props),
                interpolate(d.version(), props), interpolate(d.type(), props), interpolate(d.classifier(), props),
                interpolate(d.scope(), props), d.optional(), exclusions, interpolate(d.systemPath(), props));
    }

    /**
     * {@code ${name}} を展開する。プロパティ → システムプロパティ → env.* の順に探し、
     * 分からないものはそのまま残す（呼ぶ側が「決まらない依存」として扱う）
     */
    static String interpolate(String s, Map<String, String> props) {
        if (s == null || s.indexOf("${") < 0) {
            return s;
        }
        String cur = s;
        for (int round = 0; round < 10 && cur.contains("${"); round++) {
            StringBuilder out = new StringBuilder();
            int pos = 0;
            boolean changed = false;
            while (true) {
                int start = cur.indexOf("${", pos);
                if (start < 0) {
                    out.append(cur, pos, cur.length());
                    break;
                }
                int end = cur.indexOf('}', start);
                if (end < 0) {
                    out.append(cur, pos, cur.length());
                    break;
                }
                String key = cur.substring(start + 2, end).trim();
                String value = props.get(key);
                if (value == null) {
                    value = System.getProperty(key);
                }
                if (value == null && key.startsWith("env.")) {
                    value = System.getenv(key.substring(4));
                }
                out.append(cur, pos, start);
                if (value == null || value.contains("${" + key + "}")) {
                    out.append(cur, start, end + 1);
                } else {
                    out.append(value);
                    changed = true;
                }
                pos = end + 1;
            }
            cur = out.toString();
            if (!changed) {
                break;
            }
        }
        return cur;
    }

    private void warnOnce(String message) {
        if (warned.add(message)) {
            Log.warn("依存jar: " + message);
        }
    }
}
