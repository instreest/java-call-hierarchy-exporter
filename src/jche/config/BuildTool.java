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

import java.nio.file.Files;
import java.nio.file.Path;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * 依存を宣言しているビルドファイルの種類（Maven の pom.xml / Gradle の build.gradle）と、その検出。
 * ビルドツール自体は実行しない。ファイルを読むだけ。
 *
 * どちらを使うかはビルドファイルの有無で決める。pom.xml と build.gradle の両方があるときは
 * Eclipse のメタデータ（.project の nature、.classpath のコンテナ）でどちらのプロジェクトとして
 * 開かれているかを見る。両方あって Eclipse の手掛かりも無ければ Maven を優先し、
 * 設定の library.build.tool で切り替えられるようにしてある。
 */
public enum BuildTool {

    MAVEN("Maven", "pom.xml"),
    GRADLE("Gradle", "build.gradle / build.gradle.kts");

    /** Eclipse m2e が .project に付ける nature */
    static final String M2E_NATURE = "org.eclipse.m2e.core.maven2Nature";
    /** Eclipse Buildship が .project に付ける nature */
    static final String BUILDSHIP_NATURE = "org.eclipse.buildship.core.gradleprojectnature";
    /** m2e が .classpath に置くクラスパス・コンテナ（kind="con"） */
    static final String M2E_CONTAINER = "org.eclipse.m2e.MAVEN2_CLASSPATH_CONTAINER";
    /** Buildship が .classpath に置くクラスパス・コンテナ */
    static final String BUILDSHIP_CONTAINER = "org.eclipse.buildship.core.gradleclasspathcontainer";

    private static final String[] GRADLE_BUILD_FILES = {
        "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"
    };
    private static final String[] GRADLE_SETTINGS_FILES = {"settings.gradle", "settings.gradle.kts"};

    /** ログ用の表示名 */
    public final String displayName;
    /** 検出に使うビルドファイル（ログ用） */
    public final String buildFileNames;

    BuildTool(String displayName, String buildFileNames) {
        this.displayName = displayName;
        this.buildFileNames = buildFileNames;
    }

    /** 検出結果。reason は「なぜそう決めたか」をログに出すための短い説明 */
    public record Detection(BuildTool tool, String reason) {
    }

    /**
     * dir にあるビルドファイルと Eclipse のメタデータからビルドツールを決める。
     *
     * @return ビルドファイルが無ければ null
     */
    public static Detection detect(Path dir) {
        boolean pom = hasPom(dir);
        boolean gradle = hasGradleBuildFile(dir);
        if (!pom && !gradle) {
            return null;
        }
        BuildTool eclipse = eclipseHint(dir);
        if (pom && gradle) {
            if (eclipse == GRADLE) {
                return new Detection(GRADLE,
                        "pom.xml と build.gradle の両方があり、Eclipse の設定（.project / .classpath）が Gradle のため");
            }
            if (eclipse == MAVEN) {
                return new Detection(MAVEN,
                        "pom.xml と build.gradle の両方があり、Eclipse の設定（.project / .classpath）が Maven のため");
            }
            return new Detection(MAVEN,
                    "pom.xml と build.gradle の両方があるため Maven を優先（Gradle にするには library.build.tool=gradle）");
        }
        if (pom) {
            return new Detection(MAVEN, "pom.xml があるため");
        }
        return new Detection(GRADLE, "build.gradle / settings.gradle があるため");
    }

    /** dir に Maven か Gradle のビルドファイルがあるか */
    public static boolean hasBuildFile(Path dir) {
        return hasPom(dir) || hasGradleBuildFile(dir);
    }

    /** このツールのビルドファイルが dir にあるか（library.build.tool で明示されたときの確認用） */
    public boolean hasBuildFileIn(Path dir) {
        return this == MAVEN ? hasPom(dir) : hasGradleBuildFile(dir);
    }

    static boolean hasPom(Path dir) {
        return Files.isRegularFile(dir.resolve("pom.xml"));
    }

    static boolean hasGradleBuildFile(Path dir) {
        return anyFile(dir, GRADLE_BUILD_FILES);
    }

    /** settings.gradle(.kts) があるか。Gradle のビルドのルート（gradlew の置き場所）の目印 */
    static boolean hasGradleSettings(Path dir) {
        return anyFile(dir, GRADLE_SETTINGS_FILES);
    }

    private static boolean anyFile(Path dir, String[] names) {
        for (String name : names) {
            if (Files.isRegularFile(dir.resolve(name))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Eclipse がそのプロジェクトをどちらとして開いているか。.project の nature を先に見て、
     * 無ければ .classpath のクラスパス・コンテナ（kind="con"）を見る。
     * どちらにも手掛かりが無い、あるいは読めなければ null。
     */
    static BuildTool eclipseHint(Path dir) {
        BuildTool fromProject = hintFrom(dir.resolve(".project"), "nature",
                M2E_NATURE, BUILDSHIP_NATURE);
        if (fromProject != null) {
            return fromProject;
        }
        return hintFrom(dir.resolve(".classpath"), "classpathentry",
                M2E_CONTAINER, BUILDSHIP_CONTAINER);
    }

    /**
     * XML の tag 要素のうち、テキストか path 属性が mavenId / gradleId に一致するものを探す。
     * 読めないファイルは「手掛かり無し」とみなす（ここで止めるほどのことではない）。
     */
    private static BuildTool hintFrom(Path xml, String tag, String mavenId, String gradleId) {
        if (!Files.isRegularFile(xml)) {
            return null;
        }
        Document doc;
        try {
            doc = ProjectLayout.parseXml(xml);
        } catch (Exception e) {
            return null;
        }
        NodeList nodes = doc.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            Element e = (Element) nodes.item(i);
            String value = e.hasAttribute("path") ? e.getAttribute("path") : e.getTextContent();
            if (value == null) {
                continue;
            }
            value = value.trim();
            // コンテナのパスは "org.eclipse.m2e.MAVEN2_CLASSPATH_CONTAINER" の後ろに
            // オプションが付くことがある（"/..."）ので前方一致で見る
            if (value.startsWith(mavenId)) {
                return MAVEN;
            }
            if (value.startsWith(gradleId)) {
                return GRADLE;
            }
        }
        return null;
    }
}
