// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * 1 つの pom.xml を読んだままの内容（親からの継承と ${...} の展開はまだしていない）。
 *
 * 読むのは依存 jar を集めるのに要る要素だけ。座標、親、プロパティ、dependencyManagement、
 * dependencies、modules、relocation。activeByDefault のプロファイルは無条件に有効とみなして
 * 中身を本体に足す（それ以外のプロファイルは見ない）。
 */
final class MavenPom {

    /** groupId:artifactId:version */
    record Coordinates(String groupId, String artifactId, String version) {
    }

    final Path file;
    final Coordinates parent;        // 無ければ null
    final String parentRelativePath; // 未指定なら "../pom.xml"
    final String groupId;            // 無ければ空（親から継承）
    final String artifactId;
    final String version;            // 無ければ空（親から継承）
    final String packaging;          // 無ければ "jar"
    final Map<String, String> properties = new LinkedHashMap<>();
    final List<Dependency> dependencyManagement = new ArrayList<>();
    final List<Dependency> dependencies = new ArrayList<>();
    final List<String> modules = new ArrayList<>();
    final Coordinates relocation;    // 無ければ null

    private MavenPom(Path file, Element project) {
        this.file = file;
        Element parentElement = child(project, "parent");
        if (parentElement != null) {
            this.parent = new Coordinates(text(parentElement, "groupId"), text(parentElement, "artifactId"),
                    text(parentElement, "version"));
            String rel = text(parentElement, "relativePath");
            this.parentRelativePath = rel.isEmpty() && child(parentElement, "relativePath") == null ? "../pom.xml" : rel;
        } else {
            this.parent = null;
            this.parentRelativePath = "";
        }
        this.groupId = text(project, "groupId");
        this.artifactId = text(project, "artifactId");
        this.version = text(project, "version");
        String p = text(project, "packaging");
        this.packaging = p.isEmpty() ? "jar" : p;
        readInto(project);
        Element profiles = child(project, "profiles");
        if (profiles != null) {
            for (Element profile : children(profiles, "profile")) {
                Element activation = child(profile, "activation");
                if (activation != null && "true".equals(text(activation, "activeByDefault"))) {
                    readInto(profile);
                }
            }
        }
        Element dist = child(project, "distributionManagement");
        Element reloc = (dist == null) ? null : child(dist, "relocation");
        this.relocation = (reloc == null) ? null
                : new Coordinates(text(reloc, "groupId"), text(reloc, "artifactId"), text(reloc, "version"));
    }

    /** project 要素かプロファイル要素から、プロパティ・依存・モジュールを読む */
    private void readInto(Element container) {
        Element props = child(container, "properties");
        if (props != null) {
            for (Element e : elements(props)) {
                properties.put(e.getTagName(), e.getTextContent().trim());
            }
        }
        Element management = child(container, "dependencyManagement");
        if (management != null) {
            Element deps = child(management, "dependencies");
            if (deps != null) {
                for (Element e : children(deps, "dependency")) {
                    dependencyManagement.add(dependency(e));
                }
            }
        }
        Element deps = child(container, "dependencies");
        if (deps != null) {
            for (Element e : children(deps, "dependency")) {
                dependencies.add(dependency(e));
            }
        }
        Element modules = child(container, "modules");
        if (modules != null) {
            for (Element e : children(modules, "module")) {
                String m = e.getTextContent().trim();
                if (!m.isEmpty()) {
                    this.modules.add(m);
                }
            }
        }
    }

    private static Dependency dependency(Element e) {
        List<Exclusion> exclusions = new ArrayList<>();
        Element ex = child(e, "exclusions");
        if (ex != null) {
            for (Element x : children(ex, "exclusion")) {
                String g = text(x, "groupId");
                String a = text(x, "artifactId");
                exclusions.add(new Exclusion(g.isEmpty() ? "*" : g, a.isEmpty() ? "*" : a));
            }
        }
        return new Dependency(text(e, "groupId"), text(e, "artifactId"), text(e, "version"), text(e, "type"),
                text(e, "classifier"), text(e, "scope"), "true".equals(text(e, "optional")), exclusions,
                text(e, "systemPath"));
    }

    static MavenPom read(Path file) throws IOException {
        try {
            Document doc = ProjectLayout.parseXml(file);
            return new MavenPom(file, doc.getDocumentElement());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("pom.xml を読めません: " + file + " (" + e + ")", e);
        }
    }

    // ---- DOM の小道具（名前空間は見ない。pom.xml は接頭辞を使わない） ----

    static Element child(Element parent, String name) {
        for (Element e : elements(parent)) {
            if (e.getTagName().equals(name)) {
                return e;
            }
        }
        return null;
    }

    static List<Element> children(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        for (Element e : elements(parent)) {
            if (e.getTagName().equals(name)) {
                out.add(e);
            }
        }
        return out;
    }

    static List<Element> elements(Element parent) {
        List<Element> out = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                out.add((Element) n);
            }
        }
        return out;
    }

    /** 子要素のテキスト。無ければ空 */
    static String text(Element parent, String name) {
        Element e = child(parent, name);
        return (e == null) ? "" : e.getTextContent().trim();
    }
}
