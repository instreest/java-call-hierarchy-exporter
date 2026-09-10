package jche.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * project.root のフォルダを見るだけで分かること（ソースフォルダの候補、ビルドファイル、ソースの文字コード、
 * 依存 jar を集めたフォルダ）。
 *
 * 設定ファイルに project.root だけ書けば動くようにするための既定値の元。
 * {@link Config}（source.encoding が空欄のとき）、{@link ProjectLayout}（source.folders / library.folders が
 * 空欄のとき）、設定ファイルのウィザード（候補の表示）が共通で使う。
 */
public final class ProjectDetector {

    private ProjectDetector() {
    }

    /** ソースフォルダの候補（project.root からの相対、/ 区切り）。Maven / Gradle の標準配置 → src → 各モジュール */
    public static List<String> sourceFolderCandidates(Path projectRoot) {
        List<String> out = new ArrayList<>();
        for (String c : new String[] {"src/main/java", "src"}) {
            if (Files.isDirectory(projectRoot.resolve(c))) {
                out.add(c);
                return out;
            }
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(projectRoot)) {
            for (Path child : ds) {
                if (Files.isDirectory(child) && Files.isDirectory(child.resolve("src/main/java"))) {
                    out.add(child.getFileName() + "/src/main/java");
                }
            }
            out.sort(null);
        } catch (IOException e) {
            // 候補が出ないだけ
        }
        return out;
    }

    /** project.root 直下のビルドファイル名（pom.xml / build.gradle）。無ければ null */
    public static String buildFile(Path projectRoot) {
        if (Files.isRegularFile(projectRoot.resolve("pom.xml"))) {
            return "pom.xml";
        }
        if (Files.isRegularFile(projectRoot.resolve("build.gradle"))
                || Files.isRegularFile(projectRoot.resolve("build.gradle.kts"))
                || Files.isRegularFile(projectRoot.resolve("settings.gradle"))
                || Files.isRegularFile(projectRoot.resolve("settings.gradle.kts"))) {
            return "build.gradle";
        }
        return null;
    }

    /** ソースの文字コード。pom.xml の project.build.sourceEncoding があればそれ、無ければ UTF-8 */
    public static String sourceEncoding(Path projectRoot) {
        String enc = pomEncoding(projectRoot.resolve("pom.xml"));
        return enc != null ? enc : "UTF-8";
    }

    /** 依存 jar を集めたフォルダの候補（project.root 直下の lib に *.jar があればそれ）。無ければ null */
    public static String libraryFolderCandidate(Path projectRoot) {
        Path lib = projectRoot.resolve("lib");
        if (!Files.isDirectory(lib)) {
            return null;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(lib, "*.jar")) {
            if (ds.iterator().hasNext()) {
                return "lib";
            }
        } catch (IOException e) {
            // 候補が出ないだけ
        }
        return null;
    }

    private static final Pattern POM_ENCODING =
            Pattern.compile("<project\\.build\\.sourceEncoding>\\s*([^<\\s]+)\\s*</project\\.build\\.sourceEncoding>");

    /** pom.xml の project.build.sourceEncoding。無ければ null */
    public static String pomEncoding(Path pom) {
        if (!Files.isRegularFile(pom)) {
            return null;
        }
        try {
            Matcher m = POM_ENCODING.matcher(Files.readString(pom, StandardCharsets.UTF_8));
            return m.find() ? m.group(1) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
