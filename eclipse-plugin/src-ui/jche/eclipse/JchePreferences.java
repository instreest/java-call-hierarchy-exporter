// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jface.preference.IPreferenceStore;

/**
 * 設定（ウィンドウ > 設定 > 影響調査 (Call Hierarchy Exporter)）の読み書き。
 *
 * <p>決められるのは2つだけである。
 * <ol>
 *   <li><b>解析をどう走らせるか</b> … 解析は Eclipse とは別のプロセスなので、
 *       使う JDK・使う JDT・メモリ・常駐の切り方が、ここで初めて意味を持つ</li>
 *   <li><b>プラグインが作るファイルをどこへ置くか</b> … キャッシュ・ログ・CSV の出力先
 *       （既定と内訳は {@link PluginFolders}）。空欄なら既定に戻る</li>
 * </ol>
 *
 * <p>何を解析するか（ソースフォルダや除外）は設定ファイル側の役目で、ここには置かない。
 * そちらはプロジェクトごとに違い、ワークスペース共通の設定にはならないためである。
 */
public final class JchePreferences {

    /** 解析に使う JDK。java の実行ファイルでもホームでもよい。空なら自動で探す */
    public static final String JDK = "analysis.jdk";
    /** 解析に使う JDT の jar を置いたフォルダ。空なら同梱のものを使う */
    public static final String JDT_FOLDER = "analysis.jdtFolder";
    /** 解析プロセスへ渡す JVM 引数（空白区切り）。例: -Xmx4g */
    public static final String VM_ARGUMENTS = "analysis.vmArguments";
    /**
     * 何分使われなければ解析プロセスを終わらせるか。<b>既定は 0（終わらせない）。</b>
     * 終わらせると、そのプロセスが持っている解析結果も消える
     * （docs/eclipse-plugin-ui-simplify-qa.md の Q2）
     */
    public static final String IDLE_MINUTES = "analysis.idleMinutes";
    /** 解析の進捗と作業ログをファイルにも残すか */
    public static final String LOG_TO_FILE = "analysis.logToFile";
    /** 解析キャッシュの置き場所。空なら {@link PluginFolders#defaultCacheRoot()} */
    public static final String CACHE_FOLDER = "analysis.cacheFolder";
    /** 解析ログの置き場所。空なら {@link PluginFolders#defaultLogFolder()} */
    public static final String LOG_FOLDER = "analysis.logFolder";
    /** CSV の出力先。空なら {@link PluginFolders#defaultOutputRoot()} */
    public static final String OUTPUT_FOLDER = "analysis.outputFolder";

    /**
     * 既定のアイドル時間（分）。0＝終わらせない。
     *
     * <p>以前は 10 分で、10 分放っておくと解析結果が消えて解析からやり直しだった。
     * 解析結果は<b>利用者が捨てるまで持つ</b>ことにしたので、既定では終わらせない。
     */
    public static final int DEFAULT_IDLE_MINUTES = 0;

    private JchePreferences() {
    }

    static void initializeDefaults(IPreferenceStore store) {
        store.setDefault(JDK, "");
        store.setDefault(JDT_FOLDER, "");
        store.setDefault(VM_ARGUMENTS, "");
        store.setDefault(IDLE_MINUTES, DEFAULT_IDLE_MINUTES);
        // 既定で残す。解析が返ってこないときに後から見られることの方が、
        // 数百KBのログより価値がある（世代は AnalysisLog が絞る）
        store.setDefault(LOG_TO_FILE, true);
        // 置き場所の既定は空欄＝PluginFolders の既定。ここに実際のパスを入れてしまうと、
        // ワークスペースを移したときに古いパスが設定として残ってしまう
        store.setDefault(CACHE_FOLDER, "");
        store.setDefault(LOG_FOLDER, "");
        store.setDefault(OUTPUT_FOLDER, "");
    }

    private static IPreferenceStore store() {
        JchePlugin plugin = JchePlugin.getDefault();
        return (plugin == null) ? null : plugin.getPreferenceStore();
    }

    private static String text(String key) {
        IPreferenceStore store = store();
        String value = (store == null) ? "" : store.getString(key);
        return (value == null) ? "" : value.trim();
    }

    /** 設定された JDK（java の実行ファイル）。未設定なら null */
    public static File jdk() {
        String value = text(JDK);
        if (value.isEmpty()) {
            return null;
        }
        File file = new File(value);
        return file.isDirectory() ? jche.eclipse.server.JavaLocator.executableIn(file) : file;
    }

    /** 設定された JDT の jar のフォルダ。未設定なら null */
    public static File jdtFolder() {
        String value = text(JDT_FOLDER);
        return value.isEmpty() ? null : new File(value);
    }

    /** 解析プロセスへ渡す JVM 引数 */
    public static List<String> vmArguments() {
        String value = text(VM_ARGUMENTS);
        if (value.isEmpty()) {
            return new ArrayList<String>();
        }
        return new ArrayList<String>(Arrays.asList(value.split("\\s+")));
    }

    /** 解析ログをファイルにも残すか */
    public static boolean logToFile() {
        IPreferenceStore store = store();
        return (store == null) || store.getBoolean(LOG_TO_FILE);
    }

    /** 設定された解析キャッシュの置き場所。未設定なら null（既定を使う） */
    public static File cacheFolder() {
        return folder(CACHE_FOLDER);
    }

    /** 設定された解析ログの置き場所。未設定なら null（既定を使う） */
    public static File logFolder() {
        return folder(LOG_FOLDER);
    }

    /** 設定された CSV の出力先。未設定なら null（既定を使う） */
    public static File outputFolder() {
        return folder(OUTPUT_FOLDER);
    }

    private static File folder(String key) {
        String value = text(key);
        return value.isEmpty() ? null : new File(value);
    }

    /** アイドルで終わらせるまでの分数。0 なら終わらせない */
    public static int idleMinutes() {
        IPreferenceStore store = store();
        return (store == null) ? DEFAULT_IDLE_MINUTES : store.getInt(IDLE_MINUTES);
    }

}
