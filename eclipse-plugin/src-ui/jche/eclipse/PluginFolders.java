// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import java.io.File;

/**
 * プラグインが作るファイルの置き場所を、1か所にまとめたもの。
 *
 * <p>Eclipse のプラグインが自前のファイルを置く場所は
 * {@code Platform.getStateLocation(bundle)}（＝{@code <ワークスペース>/.metadata/.plugins/<バンドルID>/}）と
 * 決まっている。ワークスペースごとに分かれ、ワークスペースを消せば一緒に消える。
 * このプラグインもそれに従い、その下を用途ごとに分ける。
 *
 * <table>
 *   <caption>既定の置き場所（いずれも状態フォルダの下）</caption>
 *   <tr><th>用途</th><th>フォルダ</th><th>中身</th></tr>
 *   <tr><td>解析キャッシュ</td><td>{@code cache/}</td>
 *       <td>{@code .cache/<プロジェクト名>_<識別子>/analysis-cache.tsv} ほか</td></tr>
 *   <tr><td>解析ログ</td><td>{@code log/}</td><td>{@code analysis-<日時>.log}（10世代）</td></tr>
 *   <tr><td>CSV の出力</td><td>{@code output/}</td><td>CSV 出力ダイアログの初期フォルダ</td></tr>
 *   <tr><td>自動生成した設定</td><td>{@code config/<プロジェクト名>/}</td>
 *       <td>{@code generated-config.properties}（解析のたびに書き直す）</td></tr>
 *   <tr><td>取得した JDK</td><td>{@code jdk/}</td><td>設定画面から取得したもの</td></tr>
 * </table>
 *
 * <p>キャッシュ・ログ・出力の3つは設定で移せる（{@link JchePreferences}）。閉域の共有フォルダに
 * 置きたい、ワークスペースを作り直してもキャッシュを残したい、といった要望に応えるため。
 * 空欄なら既定（この表のとおり）に戻る。<b>どこに何ができるかは設定画面に出す</b>。
 * 見えない場所にファイルを作るのが、利用者にとっていちばん困るからである。
 *
 * <p>自動生成した設定だけは移せない。あれは解析のたびに書き直す内部の作業ファイルで、
 * 利用者が触る想定のものではない（触りたいときは、ビューから
 * {@code config/jche.properties} として保存する）。
 */
final class PluginFolders {

    private PluginFolders() {
    }

    /** 解析キャッシュの置き場所（既定） */
    static File defaultCacheRoot() {
        return new File(PluginRuntime.stateLocation(), "cache");
    }

    /** 解析ログの置き場所（既定） */
    static File defaultLogFolder() {
        return new File(PluginRuntime.stateLocation(), "log");
    }

    /** CSV の出力先（既定） */
    static File defaultOutputRoot() {
        return new File(PluginRuntime.stateLocation(), "output");
    }

    /**
     * 解析キャッシュの置き場所。子プロセスへ {@code --server <ここ>} として渡す。
     * 実際のキャッシュはこの下の {@code .cache/<プロジェクト名>_<識別子>/} に作られる
     * （プロジェクトごとに分けるのは解析側の仕事）。
     */
    static File cacheRoot() {
        return or(JchePreferences.cacheFolder(), defaultCacheRoot());
    }

    /** 解析ログの置き場所 */
    static File logFolder() {
        return or(JchePreferences.logFolder(), defaultLogFolder());
    }

    /** CSV の出力先。自動生成した設定の {@code output.folder} と、出力ダイアログの初期フォルダに使う */
    static File outputRoot() {
        return or(JchePreferences.outputFolder(), defaultOutputRoot());
    }

    /**
     * 自動生成した設定を置くフォルダ（プロジェクトごと）。
     *
     * <p>ここは<b>設定ファイルの置き場所でしかない</b>。解析の起点（project.root）でも
     * 出力先でもない。以前は自動生成の設定に {@code project.root=.} と書いていたため、
     * 解析側がこのフォルダを解析対象のプロジェクトだと解釈し、
     * 「ソースフォルダを特定できませんでした」で必ず失敗していた
     * （docs/eclipse-plugin-folders-qa.md の Q1）。
     */
    static File generatedConfigFolder(String projectName) {
        return new File(new File(PluginRuntime.stateLocation(), "config"), projectName);
    }

    private static File or(File configured, File fallback) {
        return (configured == null) ? fallback : configured;
    }
}
