// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.config;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import jche.util.Log;

/**
 * ローカルリポジトリ（ダウンロード済みの jar と POM の置き場所）。ここからしか探さず、ネットワークには出ない。
 *
 * 2 種類の配置を見る。どちらかは決め打たず、ルートごとに両方の形で探す。
 * <ul>
 *   <li>Maven の配置 … {@code <root>/グループを/で区切ったパス/アーティファクト/版/アーティファクト-版[-分類子].拡張子}
 *       （{@code ~/.m2/repository}。Eclipse の m2e も同じ場所を使う）</li>
 *   <li>Gradle の配置 … {@code <root>/グループ/アーティファクト/版/<ハッシュ>/アーティファクト-版[-分類子].拡張子}
 *       （{@code ~/.gradle/caches/modules-2/files-2.1}。グループはドット区切りのままのフォルダ名）</li>
 * </ul>
 * 既定のルートは、Maven が {@code ~/.m2/settings.xml} の {@code localRepository}（無ければ {@code ~/.m2/repository}）、
 * Gradle が {@code GRADLE_USER_HOME}（無ければ {@code ~/.gradle}）の下。設定 library.repositories で置き換えられる。
 */
public final class LocalRepositories {

    private final List<Path> roots;

    private LocalRepositories(List<Path> roots) {
        this.roots = roots;
    }

    public List<Path> roots() {
        return roots;
    }

    /** 設定のルート（あれば）か、既定のルートのうち存在するもの */
    public static LocalRepositories discover(Config config) {
        List<Path> roots = new ArrayList<>();
        if (!config.libraryRepositories.isEmpty()) {
            for (Path p : config.libraryRepositories) {
                if (Files.isDirectory(p)) {
                    roots.add(p);
                } else {
                    Log.warn("library.repositories のフォルダが見つかりません: " + p);
                }
            }
            return new LocalRepositories(roots);
        }
        Path maven = mavenLocalRepository();
        Path gradle = gradleCache();
        if (Files.isDirectory(maven)) {
            roots.add(maven);
        }
        if (Files.isDirectory(gradle)) {
            roots.add(gradle);
        }
        if (roots.isEmpty()) {
            Log.warn("ローカルリポジトリが見つかりません（" + maven + "、" + gradle
                    + "）。別の場所にあるなら library.repositories で指定してください");
        }
        return new LocalRepositories(roots);
    }

    /** Maven のローカルリポジトリ。~/.m2/settings.xml の localRepository があればそれ */
    static Path mavenLocalRepository() {
        Path home = Paths.get(System.getProperty("user.home"));
        Path settings = home.resolve(".m2").resolve("settings.xml");
        if (Files.isRegularFile(settings)) {
            try {
                Document doc = ProjectLayout.parseXml(settings);
                Element local = MavenPom.child(doc.getDocumentElement(), "localRepository");
                if (local != null && !local.getTextContent().trim().isEmpty()) {
                    String raw = local.getTextContent().trim()
                            .replace("${user.home}", home.toString())
                            .replace("${env.HOME}", home.toString());
                    if (!raw.contains("${")) {
                        return Paths.get(raw).toAbsolutePath().normalize();
                    }
                    Log.warn("settings.xml の localRepository に展開できない変数があるため既定の場所を使います: " + raw);
                }
            } catch (Exception e) {
                Log.warn("settings.xml を読めないため既定の場所を使います: " + settings + " (" + e + ")");
            }
        }
        return home.resolve(".m2").resolve("repository");
    }

    /** Gradle のモジュールキャッシュ。GRADLE_USER_HOME があればその下 */
    static Path gradleCache() {
        String env = System.getenv("GRADLE_USER_HOME");
        Path base = (env == null || env.trim().isEmpty())
                ? Paths.get(System.getProperty("user.home")).resolve(".gradle")
                : Paths.get(env.trim());
        return base.resolve("caches").resolve("modules-2").resolve("files-2.1");
    }

    /**
     * アーティファクトのファイルを探す。
     *
     * @param classifier 分類子。無ければ空
     * @param extension  jar / pom など
     * @return 見つかったファイル。無ければ null
     */
    public Path find(String groupId, String artifactId, String version, String classifier, String extension) {
        String fileName = artifactId + "-" + version
                + (classifier.isEmpty() ? "" : "-" + classifier) + "." + extension;
        for (Path root : roots) {
            Path found = findIn(mavenDir(root, groupId, artifactId).resolve(version),
                    fileName, artifactId, version, classifier, extension);
            if (found != null) {
                return found;
            }
            Path gradleVersionDir = gradleDir(root, groupId, artifactId).resolve(version);
            if (Files.isDirectory(gradleVersionDir)) {
                for (Path hashDir : subdirectories(gradleVersionDir)) {
                    found = findIn(hashDir, fileName, artifactId, version, classifier, extension);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 1 つのフォルダの中で探す。SNAPSHOT はダウンロード時に
     * {@code artifact-1.0-20240101.123456-3.jar} のようにタイムスタンプ付きで保存されるので、
     * その形も見て最も新しいものを取る。
     */
    private static Path findIn(Path dir, String fileName, String artifactId, String version,
                               String classifier, String extension) {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        Path exact = dir.resolve(fileName);
        if (Files.isRegularFile(exact)) {
            return exact;
        }
        if (!version.endsWith("-SNAPSHOT")) {
            return null;
        }
        String base = version.substring(0, version.length() - "-SNAPSHOT".length());
        Pattern timestamped = Pattern.compile(Pattern.quote(artifactId + "-" + base + "-")
                + "\\d{8}\\.\\d{6}-\\d+"
                + (classifier.isEmpty() ? "" : Pattern.quote("-" + classifier))
                + Pattern.quote("." + extension));
        Path newest = null;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String name = p.getFileName().toString();
                if (timestamped.matcher(name).matches()
                        && (newest == null || name.compareTo(newest.getFileName().toString()) > 0)) {
                    newest = p;
                }
            }
        } catch (IOException e) {
            return null;
        }
        return newest;
    }

    /** ローカルにある版の一覧（範囲や動的バージョンの候補）。順序は不定 */
    public List<String> versions(String groupId, String artifactId) {
        Set<String> out = new LinkedHashSet<>();
        for (Path root : roots) {
            for (Path dir : new Path[] {mavenDir(root, groupId, artifactId), gradleDir(root, groupId, artifactId)}) {
                for (Path versionDir : subdirectories(dir)) {
                    if (containsArtifactFile(versionDir, artifactId)) {
                        out.add(versionDir.getFileName().toString());
                    }
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** 版のフォルダにそのアーティファクトのファイルがあるか（Gradle 配置ならハッシュのフォルダの下） */
    private static boolean containsArtifactFile(Path versionDir, String artifactId) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(versionDir)) {
            for (Path p : ds) {
                if (Files.isRegularFile(p) && p.getFileName().toString().startsWith(artifactId + "-")) {
                    return true;
                }
                if (Files.isDirectory(p) && containsArtifactFile(p, artifactId)) {
                    return true;
                }
            }
        } catch (IOException ignore) {
            return false;
        }
        return false;
    }

    private static Path mavenDir(Path root, String groupId, String artifactId) {
        return root.resolve(groupId.replace('.', '/')).resolve(artifactId);
    }

    private static Path gradleDir(Path root, String groupId, String artifactId) {
        return root.resolve(groupId).resolve(artifactId);
    }

    private static List<Path> subdirectories(Path dir) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (Files.isDirectory(p)) {
                    out.add(p);
                }
            }
        } catch (IOException ignore) {
            // 読めないフォルダは無いものとして扱う
        }
        out.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
        return out;
    }
}
