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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import jche.util.Log;

/**
 * プロジェクトの構成（ソースフォルダ・依存jar）を読み取る。
 *
 * source.folders / library.folders が主たる指定方法。Eclipseの .classpath が
 * 無いプロジェクト（GradleやMaven単体の構成等）でも使える。
 * <ul>
 *   <li>source.folders が空なら、.classpath があれば kind="src" から読む</li>
 *   <li>library.folders は、.classpath の kind="lib"（あれば）と合算する</li>
 *   <li>library.folders が空なら、pom.xml / build.gradle を読んでローカルリポジトリから依存 jar を集める
 *       （{@link BuildFileClasspath}。ビルドファイルが無ければ何もしない）</li>
 * </ul>
 *
 * .classpath の kind="con"（Gradle/Mavenのクラスパス・コンテナ等）は
 * それ自体としては解決しない（上記のとおり、ビルドファイルから集めた結果を使う）。
 * JDK標準クラスは setEnvironment の
 * includeRunningVMBootclasspath=true で実行中のJVMから解決させる。
 * kind="var" やリンクリソース、ユーザーライブラリコンテナも未対応のため、
 * 必要な場合は library.folders で明示的に追加すること。
 */
public final class ProjectLayout {

    public final Path projectRoot;
    public final List<Path> sourceFolders = new ArrayList<>();
    /** 設定と .classpath から来た依存 jar。jar のパス、または jar を集めたフォルダ */
    public final List<Path> classpathEntries = new ArrayList<>();
    /** ビルドファイルとローカルリポジトリから集めたクラスパス。jar のパス、またはクラスフォルダ */
    public final List<Path> resolvedClasspath = new ArrayList<>();

    public ProjectLayout(Config config) throws IOException {
        this.projectRoot = config.projectRoot;

        if (!Files.isDirectory(projectRoot)) {
            throw new IOException("project.root がディレクトリとして存在しません: " + projectRoot);
        }

        for (Path sf : config.sourceFolders) {
            if (!Files.isDirectory(sf)) {
                Log.warn("source.folders のフォルダが見つかりません: " + sf);
                continue;
            }
            if (!sourceFolders.contains(sf)) {
                sourceFolders.add(sf);
            }
        }

        Path dotClasspath = projectRoot.resolve(".classpath");
        if (Files.isRegularFile(dotClasspath)) {
            readDotClasspath(dotClasspath);
        } else if (sourceFolders.isEmpty()) {
            throw new IOException(
                    ".classpath が見つからず、source.folders の指定もありません: " + dotClasspath);
        }

        for (Path lib : config.libraryFolders) {
            if (!Files.exists(lib)) {
                Log.warn("library.folders のフォルダが見つかりません: " + lib);
                continue;
            }
            classpathEntries.add(lib);
        }

        if (sourceFolders.isEmpty()) {
            throw new IOException("ソースフォルダを特定できませんでした: " + projectRoot);
        }

        // library.folders が空欄のときだけ、ビルドファイルとローカルリポジトリから依存 jar を集める。
        // 指定があるときは（.classpath の lib と合わせて）それだけを使い、ビルドファイルは見ない
        if (config.libraryFolders.isEmpty()) {
            resolvedClasspath.addAll(BuildFileClasspath.resolve(config, projectRoot, sourceFolders));
        }
    }

    private void readDotClasspath(Path dotClasspath) throws IOException {
        try {
            Document doc = parseXml(dotClasspath);

            NodeList entries = doc.getElementsByTagName("classpathentry");
            for (int i = 0; i < entries.getLength(); i++) {
                Element e = (Element) entries.item(i);
                String kind = e.getAttribute("kind");
                String path = e.getAttribute("path");
                if (path == null || path.trim().isEmpty()) {
                    continue;
                }
                if ("src".equals(kind)) {
                    // 他プロジェクトへの参照（path が "/" 始まり）は未対応
                    if (path.startsWith("/")) {
                        Log.warn("他プロジェクト参照はスキップします: " + path);
                        continue;
                    }
                    Path sf = projectRoot.resolve(path).normalize();
                    if (!sf.startsWith(projectRoot)) {
                        // project.root からの相対パスが作れないため（キャッシュのキー・出力の file 列）
                        Log.warn(".classpath のソースフォルダが project.root の外にあるためスキップします: " + sf);
                        continue;
                    }
                    if (Files.isDirectory(sf) && !sourceFolders.contains(sf)) {
                        sourceFolders.add(sf);
                    }
                } else if ("lib".equals(kind)) {
                    Path raw = Paths.get(path);
                    Path jar = raw.isAbsolute() ? raw.normalize() : projectRoot.resolve(path).normalize();
                    if (Files.exists(jar)) {
                        classpathEntries.add(jar);
                    } else {
                        Log.warn(".classpath のlibが見つかりません: " + jar);
                    }
                }
                // con / output / var は無視（con は BuildTool の検出の手掛かりにだけ使う）
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(".classpath の解析に失敗しました: " + dotClasspath, e);
        }
    }

    /** Eclipse のメタデータ（.classpath / .project）を読むための XML パース。外部エンティティは無効化する（XXE対策） */
    static Document parseXml(Path xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return f.newDocumentBuilder().parse(xml.toFile());
    }

    /**
     * ソースフォルダ配下の .java を全列挙する。
     *
     * 並びは「ソースフォルダの宣言順 → フォルダ内は相対パス（'/'区切り）の文字列順」で固定する。
     * {@link Files#walk} の返す順はファイルシステム依存（ext4 は作成順に近い順、NTFS は名前順）で、
     * この順のままだと MethodTable のID採番（初出順）が環境ごとに変わり、
     * call-hierarchy.csv の行順まで変わってしまう。相対パスを鍵にするのは、
     * methods.csv の並び（SourceOrder.declaredMethodsInSourceOrder が declFile で整列）と
     * 同じ基準に揃えるためと、Path#compareTo が Windows では大文字小文字を無視するのに対し
     * String#compareTo なら OS によらず同じ順になるため。
     */
    public List<Path> listJavaFiles() throws IOException {
        Set<Path> files = new LinkedHashSet<>();
        for (Path sf : sourceFolders) {
            List<Path> inFolder = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(sf)) {
                walk.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))
                        .forEach(inFolder::add);
            }
            inFolder.sort(Comparator.comparing(this::relativeOf));
            files.addAll(inFolder);
        }
        return new ArrayList<>(files);
    }

    public String[] sourcePathArray() {
        String[] a = new String[sourceFolders.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = sourceFolders.get(i).toString();
        }
        return a;
    }

    /**
     * JDT に渡すクラスパス文字列配列。
     *
     * 前半は classpathEntries（設定と .classpath 由来）。フォルダは「jar を集めたフォルダ」として
     * 直下の *.jar に展開する。.classpath は kind="con"（Gradle/Mavenのクラスパス・コンテナ等）を
     * 解決できないため、そういったプロジェクトでは .classpath だけでは依存jarが1つも分からない。
     * 依存jarを集めたフォルダ（Gradleの application/distribution プラグインが作る lib フォルダ、
     * 手動で集めた lib フォルダ等）を library.folders に指定すれば、ここで展開される
     * （.classpath の kind="lib" と両方指定された場合は単純に合算する）。
     *
     * 後半は resolvedClasspath（ビルドファイル由来）。こちらのフォルダは「クラスフォルダ」
     * （target/classes 等）なので、展開せずそのまま渡す。
     */
    public String[] classpathArray() {
        List<String> expanded = new ArrayList<>();
        for (Path p : classpathEntries) {
            if (Files.isDirectory(p)) {
                List<Path> jars = listJarsIn(p);
                if (jars.isEmpty()) {
                    Log.warn("クラスパスのディレクトリにjarが見つかりません: " + p);
                }
                for (Path jar : jars) {
                    expanded.add(jar.toString());
                }
            } else {
                expanded.add(p.toString());
            }
        }
        for (Path p : resolvedClasspath) {
            expanded.add(p.toString());
        }
        return expanded.toArray(new String[0]);
    }

    /** ディレクトリ直下（サブフォルダは見ない）の *.jar を、ファイル名順で列挙する */
    private static List<Path> listJarsIn(Path dir) {
        List<Path> jars = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.jar")) {
            for (Path p : ds) {
                jars.add(p);
            }
        } catch (IOException e) {
            Log.warn("クラスパスのディレクトリを読み取れません: " + dir + " (" + e + ")");
            return jars;
        }
        jars.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
        return jars;
    }

    /** ASTParser.setUnitName に渡すための、ソースフォルダからの相対パス */
    public String unitNameOf(Path javaFile) {
        for (Path sf : sourceFolders) {
            if (javaFile.startsWith(sf)) {
                return sf.relativize(javaFile).toString().replace('\\', '/');
            }
        }
        return javaFile.getFileName().toString();
    }

    /** キャッシュのキー・出力表示に使う、プロジェクトルートからの相対パス */
    public String relativeOf(Path javaFile) {
        return projectRoot.relativize(javaFile).toString().replace('\\', '/');
    }
}
