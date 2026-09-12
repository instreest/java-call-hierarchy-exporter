// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Properties;

import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.IJavaProject;

import jche.config.Config;

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
    private final Path baseDir;

    private ConfigSource(Kind kind, IFile file, IJavaProject javaProject, Path baseDir) {
        this.kind = kind;
        this.file = file;
        this.javaProject = javaProject;
        this.baseDir = baseDir;
    }

    static ConfigSource ofFile(IFile file) {
        return new ConfigSource(Kind.FILE, file, null, null);
    }

    static ConfigSource generated(IJavaProject javaProject, Path projectLocation) {
        return new ConfigSource(Kind.GENERATED, null, javaProject, projectLocation);
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
     * 解析用の設定を組み立てる。ファイルの読み込みも自動生成も、できあがる {@link Config} は同じ形。
     *
     * @param cacheRoot キャッシュの置き場所の親（プラグインの状態フォルダ）
     */
    Config toConfig(Path cacheRoot) throws IOException {
        if (kind == Kind.FILE) {
            return new Config(file.getLocation().toFile().toPath(), cacheRoot, LocalDateTime.now());
        }
        return new Config(EclipseProjectConfig.propertiesFor(javaProject), baseDir, cacheRoot,
                LocalDateTime.now());
    }
}
