// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.IJavaProject;

/**
 * 解析に使う設定がどこから来たか。
 *
 * <p>優先順位は次のとおりで、上から順に当てはまった1つを使う（{@link ProjectAnalysis#configSource()}）。
 * <ol>
 *   <li>{@link Kind#FILE} … 利用者がビューで選んだ設定ファイル</li>
 *   <li>{@link Kind#FILE} … プロジェクト直下の config.properties</li>
 *   <li>{@link Kind#GENERATED} … Eclipse のプロジェクト構成から自動生成（{@link EclipseProjectConfig}）</li>
 * </ol>
 * つまり<b>設定ファイルが無くても解析できる</b>。あるときは書いてあるとおりに従う。
 *
 * <p>解析は子プロセスで走るので、設定は<b>ファイルとして</b>渡す（{@link #materialize}）。
 * 自動生成のときは一時ファイルに書き出す。
 */
final class ConfigSource {

    enum Kind {
        /** 設定ファイル */
        FILE,
        /** プロジェクト構成からの自動生成 */
        GENERATED
    }

    private final Kind kind;
    private final IFile file;
    private final IJavaProject javaProject;

    private ConfigSource(Kind kind, IFile file, IJavaProject javaProject) {
        this.kind = kind;
        this.file = file;
        this.javaProject = javaProject;
    }

    static ConfigSource ofFile(IFile file) {
        return new ConfigSource(Kind.FILE, file, null);
    }

    static ConfigSource generated(IJavaProject javaProject) {
        return new ConfigSource(Kind.GENERATED, null, javaProject);
    }

    Kind kind() {
        return kind;
    }

    IFile file() {
        return file;
    }

    /** 画面に出す説明。「どの設定で解析したか」が分からないまま結果だけ見せない */
    String label() {
        return (kind == Kind.FILE)
                ? file.getProjectRelativePath().toString()
                : "自動生成（プロジェクトの構成から）";
    }

    /** 自動生成の内容。設定ファイル由来なら null */
    Properties generatedProperties() throws IOException {
        return (kind == Kind.GENERATED) ? EclipseProjectConfig.propertiesFor(javaProject) : null;
    }

    /**
     * 子プロセスへ渡すための設定ファイルのパスを返す。
     * 自動生成のときは、渡されたフォルダに書き出す（次の解析でも同じ場所を使い回す）。
     *
     * @param scratchDir 一時ファイルの置き場所（プラグインの状態フォルダの下）
     */
    Path materialize(Path scratchDir) throws IOException {
        if (kind == Kind.FILE) {
            if (file.getLocation() == null) {
                throw new IOException("設定ファイルの場所が特定できません: " + file.getFullPath());
            }
            return file.getLocation().toFile().toPath();
        }
        Files.createDirectories(scratchDir);
        Path generated = scratchDir.resolve("generated-config.properties");
        Properties properties = EclipseProjectConfig.propertiesFor(javaProject);
        try (OutputStream out = Files.newOutputStream(generated)) {
            // Properties#store は ISO-8859-1 でエスケープするが、Config は UTF-8 で読むので
            // 自前で書く（パスに日本語が入っても壊れないようにするため）
            out.write(EclipseProjectConfig.toFileText(properties).getBytes(StandardCharsets.UTF_8));
        }
        return generated;
    }
}
