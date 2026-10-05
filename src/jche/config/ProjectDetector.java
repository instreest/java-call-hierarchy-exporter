package jche.config;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Element;

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

    /**
     * pom.xml の {@code <properties>} の project.build.sourceEncoding。無ければ null。
     *
     * <p>XML として読む（{@link ProjectLayout#parseXml}。{@link MavenPom} と同じ読み方）。以前は UTF-8 で読んだ
     * 文字列に正規表現を当てていたので、XML 宣言の文字コードが UTF-8 でない pom.xml で読み損ね、注釈に書かれた
     * （コメントアウトした）値や、読まないプロファイルの中の値まで拾っていた。
     * 親 POM からの継承はしない（決められなければ既定の UTF-8）
     */
    public static String pomEncoding(Path pom) {
        if (!Files.isRegularFile(pom)) {
            return null;
        }
        try {
            Element properties = MavenPom.child(ProjectLayout.parseXml(pom).getDocumentElement(), "properties");
            String enc = (properties == null) ? "" : MavenPom.text(properties, "project.build.sourceEncoding");
            if (enc.isEmpty()) {
                return null;
            }
            // ${file.encoding} のようなプロパティ参照はここでは展開できない。そのまま返すと
            // Charset.forName で落ちるので「決められない」として既定（UTF-8）に倒す
            return (enc.contains("${") || !Charset.isSupported(enc)) ? null : enc;
        } catch (Exception e) {
            return null;
        }
    }
}
