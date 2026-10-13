// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import jche.config.Config;
import jche.config.ProjectLayout;
import jche.util.Log;
import jche.util.Messages;
import jche.util.Warnings;

/**
 * 一緒に解析するワークスペースの他のプロジェクト（設定の {@code workspace.projects} の 1 件）。
 *
 * <p>相手は<b>相手自身の設定（クラスパス・文字コード・準拠レベル）で別々に解析</b>し、相手のキャッシュ
 * （{@code analysis-cache.tsv}）をこの実行のグラフに結合する。キャッシュの中でメソッドと型を指す列はどれも名前
 * （パッケージ・型の完全修飾名・メソッド名・引数のシグネチャ）なので、別々に作ったキャッシュでも同じメソッドは同じ名前に
 * なり、番号（記号表・値グラフ）はブロックの中で閉じているので結合に要らない。相手が project.root の型を
 * jar の型として解決していれば、相手の呼び出しの行は project.root のメソッドを名前で指し、相手の型の行の親型には
 * project.root のインターフェースの名前が入る（docs/workspace-callers-design.md）。
 *
 * <p>相手のファイルのパスは、キャッシュの中では相手の project.root からの相対パスで、グラフと出力ではこの
 * {@link #prefix}（この実行の project.root から相手の project.root への相対パス。{@code ../app-batch/}）を前に付けた形になる。
 * 読み手（出力の {@code file} 列・解析サーバーの {@code AT}・プラグインがファイルを開く処理）は「project.root と連結して
 * 正規化する」今までの読み方のままで、相手のファイルにも届く。
 */
public final class WorkspaceProject {

    /** 相手の設定（相手の設定ファイルを読んだもの、またはフォルダから組んだもの。{@link Config#forWorkspaceProject}） */
    public final Config config;
    public final ProjectLayout layout;
    /**
     * 相手のファイルのパスに前置する、この実行の project.root からの相対パス（区切りは {@code /}。末尾に {@code /} を持つ）。
     * 相対パスにできない（Windows の別ドライブ）ときは絶対パス
     */
    public final String prefix;
    /**
     * グラフを組んだときの相手のキャッシュの印（{@link Exporter#cacheStampOf}）。グラフに入れていない行を後から相手の
     * キャッシュで引く読み手（解析サーバーのフィールドの参照）が、キャッシュがそのままかを確かめるのに使う
     */
    private volatile String cacheStamp = "";

    private WorkspaceProject(Config config, ProjectLayout layout, String prefix) {
        this.config = config;
        this.layout = layout;
        this.prefix = prefix;
    }

    /** 相手のプロジェクトの名前（相手の project.root のフォルダ名） */
    public String name() {
        return config.projectName;
    }

    public String cacheStamp() {
        return cacheStamp;
    }

    void setCacheStamp(String stamp) {
        this.cacheStamp = stamp;
    }

    /**
     * 相手のソースフォルダの並び（この実行の project.root からの相対パス。{@link #prefix} 付き）。
     * グラフの起点の並び替えと、同じ型を 2 つのファイルが宣言したときの勝ち負けに使う
     */
    public List<String> sourceFolderOrder() {
        List<String> out = new ArrayList<>();
        for (Path folder : layout.sourceFolders) {
            String relative = layout.relativeOf(folder);
            out.add(relative.isEmpty() ? prefix.substring(0, prefix.length() - 1) : prefix + relative);
        }
        return out;
    }

    /**
     * 設定の {@code workspace.projects} を読んで並べる（書いた順）。無い指定は警告して読み飛ばし、project.root 自身を
     * 指す指定も読み飛ばす（自分を自分に結合しない）
     */
    public static List<WorkspaceProject> load(Config main) throws IOException {
        List<WorkspaceProject> out = new ArrayList<>();
        // 同じプロジェクト（同じキャッシュ）を 2 度は読まない。2 度読むと、同じ錠を同じ JVM の中で 2 度取ろうとして
        // 「ほかの実行が持っている」と待ち続け、グラフにも同じブロックが 2 度入る
        java.util.Set<Path> seenRoots = new java.util.HashSet<>();
        java.util.Set<Path> seenCaches = new java.util.HashSet<>();
        seenRoots.add(main.projectRoot.toAbsolutePath().normalize());
        seenCaches.add(main.cacheFile.toAbsolutePath().normalize());
        for (Path entry : main.workspaceProjects) {
            if (!Files.exists(entry)) {
                Warnings.warn(Warnings.Topic.CONFIG, Messages.format("config.workspace.missing", entry));
                continue;
            }
            Config config = main.forWorkspaceProject(entry);
            if (config.projectRoot.equals(main.projectRoot)) {
                Log.info(Messages.format("config.workspace.self", entry));
                continue;
            }
            Path root = config.projectRoot.toAbsolutePath().normalize();
            Path cache = config.cacheFile.toAbsolutePath().normalize();
            if (!seenRoots.add(root) || !seenCaches.add(cache)) {
                Log.info(Messages.format("config.workspace.duplicate", entry, root));
                continue;
            }
            out.add(new WorkspaceProject(config, new ProjectLayout(config), prefixOf(main.projectRoot, config.projectRoot)));
        }
        return out;
    }

    /**
     * この実行の project.root から相手の project.root への相対パス（{@code ../app-batch/}）。区切りは {@code /} で、
     * 末尾に {@code /} を付ける。相対パスにできない（別のドライブ）ときは絶対パス
     */
    static String prefixOf(Path mainRoot, Path otherRoot) {
        Path main = mainRoot.toAbsolutePath().normalize();
        Path other = otherRoot.toAbsolutePath().normalize();
        String key;
        try {
            key = ProjectLayout.pathKeyOf(main.relativize(other));
        } catch (IllegalArgumentException e) {
            key = ProjectLayout.pathKeyOf(other);
        }
        return key.endsWith("/") ? key : key + "/";
    }
}
