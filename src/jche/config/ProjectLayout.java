// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
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

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import jche.util.FileTree;
import jche.util.Log;
import jche.util.Messages;
import jche.util.Warnings;

/**
 * プロジェクトの構成（ソースフォルダ・依存jar）を読み取る。
 *
 * source.folders / library.folders が主たる指定方法。Eclipseの .classpath が
 * 無いプロジェクト（GradleやMaven単体の構成等）でも使える。
 * <ul>
 *   <li>source.folders が空なら、.classpath があれば kind="src" から読む</li>
 *   <li>library.folders（jar を集めたフォルダ）は、.classpath の kind="lib"（あれば）と合算する</li>
 *   <li>library.jars は jar（またはクラスフォルダ）を1件ずつ指定するもの（Eclipse プラグインが解決済みクラスパスを
 *       渡すのに使う）。.classpath の kind="lib" も同じく 1 件ずつの指定として読む（Eclipse と同じ意味）</li>
 *   <li>library.folders と library.jars がどちらも空なら、pom.xml / build.gradle を読んで
 *       ローカルリポジトリから依存 jar を集める
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
    /**
     * 設定と .classpath から来たクラスパス（書いた順）。{@link ClasspathEntry#jarFolder} が真のものは
     * 「jar を集めたフォルダ」（library.folders と project.root 直下の lib）で、直下の *.jar に展開する。
     * 偽のものは 1 件ずつの指定（library.jars と .classpath の kind="lib"）で、jar でもクラスフォルダでもそのまま渡す
     */
    private final List<ClasspathEntry> classpathEntries = new ArrayList<>();
    /** ビルドファイルとローカルリポジトリから集めたクラスパス。jar のパス、またはクラスフォルダ */
    public final List<Path> resolvedClasspath = new ArrayList<>();
    /** {@link #classpathArray} の答え。1 回の解析で 1 回だけ作る（警告を 1 回にし、どこから引いても同じ並びにする） */
    private String[] classpath;

    /**
     * 設定と .classpath から来たクラスパスの 1 件。
     *
     * @param jarFolder 真なら jar を集めたフォルダ（直下の *.jar に展開する）。偽なら jar かクラスフォルダの 1 件
     */
    private record ClasspathEntry(Path path, boolean jarFolder) {
    }

    public ProjectLayout(Config config) throws IOException {
        this.projectRoot = config.projectRoot;

        if (!Files.isDirectory(projectRoot)) {
            throw new IOException(Messages.format("config.layout.projectRootMissing", projectRoot));
        }

        for (Path sf : config.sourceFolders) {
            if (!Files.isDirectory(sf)) {
                Warnings.warn(Warnings.Topic.CONFIG, Messages.format("config.layout.sourceFolderMissing", sf));
                continue;
            }
            if (!sourceFolders.contains(sf)) {
                sourceFolders.add(sf);
            }
        }

        Path dotClasspath = projectRoot.resolve(".classpath");
        if (Files.isRegularFile(dotClasspath)) {
            readDotClasspath(dotClasspath);
        }
        // source.folders が空欄で .classpath も無い（または .classpath にソースが無い）ときは、
        // project.root の標準的な配置（src/main/java、src、各モジュールの src/main/java）から決める
        if (sourceFolders.isEmpty() && config.sourceFolders.isEmpty()) {
            List<String> candidates = ProjectDetector.sourceFolderCandidates(projectRoot);
            for (String c : candidates) {
                sourceFolders.add(projectRoot.resolve(c).normalize());
            }
            if (!candidates.isEmpty()) {
                Log.info(Messages.format("config.layout.sourceFoldersDetected", String.join(", ", candidates)));
            }
        }

        for (Path lib : config.libraryFolders) {
            if (!Files.exists(lib)) {
                Warnings.warn(Warnings.Topic.CONFIG, Messages.format("config.layout.libraryFolderMissing", lib));
                continue;
            }
            classpathEntries.add(new ClasspathEntry(lib, true));
        }

        // library.jars は jar（またはクラスフォルダ）を1件ずつ指定するもの。
        // Eclipse プラグインが IJavaProject の解決済みクラスパス（依存プロジェクトの出力フォルダを含む）を
        // そのまま渡すために使う。フォルダは jar を集めたフォルダとして展開せず、クラスフォルダとして渡す
        for (Path jar : config.libraryJars) {
            if (!Files.exists(jar)) {
                Warnings.warn(Warnings.Topic.CONFIG, Messages.format("config.layout.libraryJarMissing", jar));
                continue;
            }
            classpathEntries.add(new ClasspathEntry(jar, false));
        }

        if (sourceFolders.isEmpty()) {
            throw new IOException(Messages.format("config.layout.noSourceFolder", projectRoot));
        }

        // library.folders が空欄のときだけ、ビルドファイルとローカルリポジトリから依存 jar を集める。
        // 指定があるときは（.classpath の lib と合わせて）それだけを使い、ビルドファイルは見ない。
        // ビルドファイルからも .classpath からも jar が集まらなければ、project.root 直下の lib（*.jar）を使う。
        // library.jars（Eclipse プラグインが解決済みクラスパスを渡す形）も「指定あり」として扱う
        if (config.libraryFolders.isEmpty() && config.libraryJars.isEmpty()) {
            resolvedClasspath.addAll(BuildFileClasspath.resolve(config, projectRoot, sourceFolders));
            if (resolvedClasspath.isEmpty() && classpathEntries.isEmpty()) {
                String lib = ProjectDetector.libraryFolderCandidate(projectRoot);
                if (lib != null) {
                    Path dir = projectRoot.resolve(lib);
                    Log.info(Messages.format("config.layout.libFromProjectRoot", lib, dir));
                    classpathEntries.add(new ClasspathEntry(dir, true));
                }
            }
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
                        Log.warn(Messages.format("config.layout.skipOtherProject", path));
                        continue;
                    }
                    Path sf = projectRoot.resolve(path).normalize();
                    if (!sf.startsWith(projectRoot)) {
                        // project.root からの相対パスが作れないため（キャッシュのキー・出力の file 列）
                        Warnings.warn(Warnings.Topic.CONFIG, Messages.format("config.layout.sourceOutsideRoot", sf));
                        continue;
                    }
                    if (Files.isDirectory(sf) && !sourceFolders.contains(sf)) {
                        sourceFolders.add(sf);
                    }
                } else if ("lib".equals(kind)) {
                    // Eclipse の kind="lib" は jar かクラスフォルダの 1 件（フォルダは jar を集めたフォルダではない）
                    Path raw = Paths.get(path);
                    Path jar = raw.isAbsolute() ? raw.normalize() : projectRoot.resolve(path).normalize();
                    if (Files.exists(jar)) {
                        classpathEntries.add(new ClasspathEntry(jar, false));
                    } else {
                        Warnings.warn(Warnings.Topic.CONFIG, Messages.format("config.layout.classpathLibMissing", jar));
                    }
                }
                // con / output / var は無視（con は BuildTool の検出の手掛かりにだけ使う）
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(Messages.format("config.layout.classpathUnreadable", dotClasspath), e);
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
     *
     * <p>シンボリックリンクはたどる（{@link FileTree}）。JDT はソースフォルダをソースパスとして {@code java.io.File} で
     * 読むので、リンクになったソースフォルダやパッケージのフォルダの型も見つける。以前は {@link Files#walk} の既定
     * （たどらない）で歩いていたので、そのファイルが一覧に入らず（ソースフォルダ自体がリンクなら 0 件）、JDT は
     * ソースとして解決するのに呼び出しは読まれず、書き換えても検知されなかった。パスはソースフォルダからたどった綴りの
     * まま持つ（キャッシュのキーと出力の file 列がプロジェクトの中の相対パスになるように）。祖先へ戻るリンクの先には
     * 入らない
     */
    public List<Path> listJavaFiles() throws IOException {
        Set<Path> files = new LinkedHashSet<>();
        for (Path sf : sourceFolders) {
            List<Path> inFolder = new ArrayList<>();
            FileTree.forEachFile(sf, (p, attrs) -> {
                if (p.toString().endsWith(".java")) {
                    inFolder.add(p);
                }
            });
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
     * 前半は classpathEntries（設定と .classpath 由来）。library.folders（と project.root 直下の lib）のフォルダは
     * 「jar を集めたフォルダ」として直下の *.jar に展開する。.classpath は kind="con"（Gradle/Mavenのクラスパス・
     * コンテナ等）を解決できないため、そういったプロジェクトでは .classpath だけでは依存jarが1つも分からない。
     * 依存jarを集めたフォルダ（Gradleの application/distribution プラグインが作る lib フォルダ、
     * 手動で集めた lib フォルダ等）を library.folders に指定すれば、ここで展開される
     * （.classpath の kind="lib" と両方指定された場合は単純に合算する）。
     * library.jars と .classpath の kind="lib" は 1 件ずつの指定なので、フォルダでも展開せず「クラスフォルダ」として
     * そのまま渡す（Eclipse の意味。Eclipse プラグインは依存プロジェクトの出力フォルダをここに書く）。以前は
     * これも jar を集めたフォルダとして展開していたので、クラスフォルダの型がどれも JDT に渡らなかった。
     *
     * 後半は resolvedClasspath（ビルドファイル由来）。こちらのフォルダは「クラスフォルダ」
     * （target/classes 等）なので、展開せずそのまま渡す。
     *
     * <p>答えは最初の呼び出しで作って覚える。JDT・依存 jar の突き合わせ（LibraryDiff）・ログが同じ並びを見るように
     * するためと、展開のときの警告を 1 回だけ出すため（以前は呼ぶたびに出していた）。解析のたびに
     * {@link ProjectLayout} を作り直すので、フォルダの jar の増減は次の解析で見る。
     */
    public String[] classpathArray() {
        if (classpath != null) {
            return classpath.clone();
        }
        List<String> expanded = new ArrayList<>();
        for (ClasspathEntry e : classpathEntries) {
            Path p = e.path();
            if (e.jarFolder() && Files.isDirectory(p)) {
                List<Path> jars = listJarsIn(p);
                if (jars.isEmpty()) {
                    Warnings.warn(Warnings.Topic.CONFIG, Messages.format("config.layout.noJarInDir", p));
                }
                for (Path jar : jars) {
                    expanded.add(jar.toString());
                }
            } else {
                if (!e.jarFolder() && Files.isDirectory(p) && !listJarsIn(p).isEmpty()) {
                    // jar を集めたフォルダを 1 件ずつの指定に書いたのかもしれない。クラスフォルダとしては渡すが、
                    // 中の jar は使わないことを知らせる
                    Log.warn(Messages.format("config.layout.jarsInClassFolder", p));
                }
                expanded.add(p.toString());
            }
        }
        for (Path p : resolvedClasspath) {
            expanded.add(p.toString());
        }
        classpath = expanded.toArray(new String[0]);
        return classpath.clone();
    }

    /** ディレクトリ直下（サブフォルダは見ない）の *.jar を、ファイル名順で列挙する */
    private static List<Path> listJarsIn(Path dir) {
        List<Path> jars = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.jar")) {
            for (Path p : ds) {
                jars.add(p);
            }
        } catch (IOException e) {
            Warnings.warn(Warnings.Topic.CONFIG, Messages.format("config.layout.dirUnreadable", dir, e));
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

    /** キャッシュのキー・出力表示に使う、プロジェクトルートからの相対パス（綴りは {@link #pathKeyOf}） */
    public String relativeOf(Path javaFile) {
        return pathKeyOf(projectRoot.relativize(javaFile));
    }

    /**
     * キャッシュのキー（F 行・T 行のパス、ヘッダ行のソースフォルダ、L 行の jar）に使うパスの綴り。パスの要素（名前）を
     * {@code /} でつなぐ。絶対パスなら根（{@code /}・{@code C:\}）も {@code \} を {@code /} にして前に付ける。
     *
     * <p>以前は {@code toString()} の {@code \} を {@code /} に置き換えていた。Linux・macOS では {@code \} は名前の中の
     * ただの文字なので、{@code x\y} という名前のフォルダと入れ子の {@code x/y} が同じキーになり、フォルダの名前を
     * 変えても「ソースフォルダは変わっていない」と読んで旧キャッシュ（と中断した実行の一時ファイル）を使い続けた
     * （docs/cache-unification-qa.md の Q75）。要素ごとにつなげば、名前の中の {@code \} は残る。Windows では
     * {@code \} は区切りなので要素に現れず、どちらの書き方でも以前と同じ綴りになる
     */
    public static String pathKeyOf(Path path) {
        StringBuilder sb = new StringBuilder();
        Path root = path.getRoot();
        if (root != null) {
            sb.append(root.toString().replace('\\', '/'));
        }
        int names = path.getNameCount();
        for (int i = 0; i < names; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(path.getName(i).toString());
        }
        return sb.toString();
    }
}
