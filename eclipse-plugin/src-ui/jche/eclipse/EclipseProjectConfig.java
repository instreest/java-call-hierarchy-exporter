// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;

/**
 * Eclipse のプロジェクト構成から、解析の設定（config.properties 相当）をその場で組み立てる。
 *
 * <p>これがあるおかげで、利用者は config.properties を書かなくてもビューを使える。
 * Eclipse は必要な情報（ソースフォルダ・依存 jar・文字コード・コンパイラー準拠レベル）を
 * すでに持っているので、それを {@link jche.config.Config} の語彙へ翻訳しているだけである。
 *
 * <p>設定ファイルがあるときはそちらが優先される（{@link ProjectAnalysis#configSource()}）。
 * 自動生成はあくまで既定値で、細かく効かせたい（entry.packages を絞る、外部 jar の被参照を見る等）
 * 場合は、生成した内容を保存してから手で直せばよい（ビューの「設定を config.properties に保存」）。
 */
final class EclipseProjectConfig {

    private EclipseProjectConfig() {
    }

    /** そのプロジェクトが Java プロジェクトか（自動生成できるか） */
    static IJavaProject javaProjectOf(IProject project) {
        try {
            if (project != null && project.isAccessible() && project.hasNature(JavaCore.NATURE_ID)) {
                return JavaCore.create(project);
            }
        } catch (CoreException e) {
            JchePlugin.log(IStatus.WARNING, "プロジェクトの種別を判定できませんでした: " + project, e);
        }
        return null;
    }

    /**
     * プロジェクトの構成から設定を作る。相対パスの起点はプロジェクトの場所。
     *
     * <p>入れる値は次のとおり。書かない項目は既定のまま（起点の指定が無い＝全体モード）。
     * <ul>
     *   <li>project.root … プロジェクトの場所（起点そのものなので ".")</li>
     *   <li>source.folders … クラスパスのソースフォルダ（プロジェクトの外にあるリンクは除く）</li>
     *   <li>library.jars … 解決済みクラスパスの jar と、依存プロジェクトの出力フォルダ</li>
     *   <li>source.encoding … プロジェクトの文字コード</li>
     *   <li>source.level … プロジェクトのコンパイラー準拠レベル</li>
     * </ul>
     */
    static Properties propertiesFor(IJavaProject javaProject) throws IOException {
        IProject project = javaProject.getProject();
        IPath projectLocation = project.getLocation();
        if (projectLocation == null) {
            throw new IOException("プロジェクトの場所が特定できません（ワークスペース外のリンク）: "
                    + project.getName());
        }
        Properties p = new Properties();
        p.setProperty("project.root", ".");
        p.setProperty("source.folders", String.join(",", sourceFoldersOf(javaProject, projectLocation)));
        p.setProperty("library.jars", String.join(",", classpathJarsOf(javaProject)));
        p.setProperty("source.encoding", encodingOf(project));
        p.setProperty("source.level", complianceOf(javaProject));
        return p;
    }

    /** ソースフォルダ（プロジェクトからの相対パス）。project.root の外にあるものは解析できないので除く */
    private static List<String> sourceFoldersOf(IJavaProject javaProject, IPath projectLocation)
            throws IOException {
        List<String> folders = new ArrayList<>();
        try {
            for (IClasspathEntry entry : javaProject.getRawClasspath()) {
                if (entry.getEntryKind() != IClasspathEntry.CPE_SOURCE) {
                    continue;
                }
                IResource resource = ResourcesPlugin.getWorkspace().getRoot()
                        .findMember(entry.getPath());
                if (resource == null || resource.getLocation() == null) {
                    continue;
                }
                IPath location = resource.getLocation();
                if (!projectLocation.isPrefixOf(location)) {
                    // リンクされたソースフォルダ。キャッシュのキーも出力の file 列も
                    // project.root からの相対パスなので、外にあるものは扱えない
                    JchePlugin.log(IStatus.WARNING,
                            "プロジェクトの外にあるソースフォルダは解析対象から外します: " + location, null);
                    continue;
                }
                String relative = location.makeRelativeTo(projectLocation).toString();
                folders.add(relative.isEmpty() ? "." : relative);
            }
        } catch (JavaModelException e) {
            throw new IOException("クラスパスを読み取れませんでした: " + e.getMessage(), e);
        }
        if (folders.isEmpty()) {
            throw new IOException("ソースフォルダがありません: " + javaProject.getElementName());
        }
        return folders;
    }

    /**
     * 依存 jar。解決済みクラスパス（コンテナも展開された状態）から、
     * ライブラリの jar / クラスフォルダと、依存プロジェクトの出力フォルダを集める。
     */
    private static List<String> classpathJarsOf(IJavaProject javaProject) throws IOException {
        Set<String> jars = new LinkedHashSet<>();
        try {
            for (IClasspathEntry entry : javaProject.getResolvedClasspath(true)) {
                switch (entry.getEntryKind()) {
                    case IClasspathEntry.CPE_LIBRARY -> add(jars, entry.getPath());
                    case IClasspathEntry.CPE_PROJECT -> add(jars, outputLocationOf(entry.getPath()));
                    default -> { /* ソースは source.folders 側で扱う */ }
                }
            }
        } catch (JavaModelException e) {
            throw new IOException("クラスパスを解決できませんでした: " + e.getMessage(), e);
        }
        return new ArrayList<>(jars);
    }

    /** 依存プロジェクトの出力フォルダ（クラスファイルの置き場所）。無ければ null */
    private static IPath outputLocationOf(IPath projectPath) throws JavaModelException {
        IProject referenced = ResourcesPlugin.getWorkspace().getRoot()
                .getProject(projectPath.lastSegment());
        IJavaProject referencedJava = javaProjectOf(referenced);
        return (referencedJava == null) ? null : referencedJava.getOutputLocation();
    }

    /**
     * ワークスペースの相対パスならファイルシステム上の場所へ、そうでなければそのまま加える。
     * 設定は「カンマ区切りの1行」なので、カンマを含むパスは諦めて警告を出す。
     */
    private static void add(Set<String> jars, IPath path) {
        if (path == null) {
            return;
        }
        IResource resource = ResourcesPlugin.getWorkspace().getRoot().findMember(path);
        IPath location = (resource != null && resource.getLocation() != null)
                ? resource.getLocation() : path;
        String text = location.toOSString();
        if (text.indexOf(',') >= 0) {
            JchePlugin.log(IStatus.WARNING,
                    "カンマを含むパスは設定に載せられないため除外します: " + text, null);
            return;
        }
        jars.add(text);
    }

    private static String encodingOf(IProject project) {
        try {
            return project.getDefaultCharset();
        } catch (CoreException e) {
            return "UTF-8";
        }
    }

    /**
     * コンパイラー準拠レベル。プロジェクト固有の設定が無ければワークスペースの既定が返る。
     * この値は解析する文法の版であって、実行 JDK の版ではない。
     */
    private static String complianceOf(IJavaProject javaProject) {
        String compliance = javaProject.getOption(JavaCore.COMPILER_COMPLIANCE, true);
        return (compliance == null || compliance.isBlank()) ? "" : compliance.trim();
    }

    /** 自動生成した設定を config.properties の体裁で書き出す（保存用） */
    static String toFileText(Properties p) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Eclipse のプロジェクト構成から自動生成した設定です。\n");
        sb.append("# 各項目の意味は、ツール同梱の config.properties のコメントを参照してください。\n");
        sb.append("# このファイルがあると、ビューは自動生成ではなくこちらを使います。\n");
        for (String key : new String[] {"project.root", "source.folders", "library.jars",
                "source.encoding", "source.level"}) {
            sb.append(key).append('=').append(p.getProperty(key, "")).append('\n');
        }
        sb.append("# 起点を絞るときは entry.packages を、除外するときは exclude.packages を書きます。\n");
        sb.append("entry.packages=\n");
        sb.append("exclude.packages=\n");
        return sb.toString();
    }
}
