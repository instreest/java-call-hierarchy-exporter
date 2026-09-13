// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jface.preference.IPreferenceStore;

/**
 * 設定（ウィンドウ > 設定 > 呼び出し階層 (Exporter)）の読み書き。
 *
 * <p>決められるのは「解析をどう走らせるか」だけである。解析は Eclipse とは別のプロセスなので、
 * 使う JDK・使う JDT・メモリ・常駐の切り方が、ここで初めて意味を持つ。
 * 何を解析するか（ソースフォルダや除外）は設定ファイル側の役目で、ここには置かない。
 */
public final class JchePreferences {

    /** 解析に使う JDK。java の実行ファイルでもホームでもよい。空なら自動で探す */
    public static final String JDK = "analysis.jdk";
    /** 解析に使う JDT の jar を置いたフォルダ。空なら同梱のものを使う */
    public static final String JDT_FOLDER = "analysis.jdtFolder";
    /** 解析プロセスへ渡す JVM 引数（空白区切り）。例: -Xmx4g */
    public static final String VM_ARGUMENTS = "analysis.vmArguments";
    /** 何分使われなければ解析プロセスを終わらせるか。0 なら終わらせない */
    public static final String IDLE_MINUTES = "analysis.idleMinutes";

    /** 既定のアイドル時間（分） */
    public static final int DEFAULT_IDLE_MINUTES = 10;

    private JchePreferences() {
    }

    static void initializeDefaults(IPreferenceStore store) {
        store.setDefault(JDK, "");
        store.setDefault(JDT_FOLDER, "");
        store.setDefault(VM_ARGUMENTS, "");
        store.setDefault(IDLE_MINUTES, DEFAULT_IDLE_MINUTES);
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

    /** アイドルで終わらせるまでの分数。0 なら終わらせない */
    public static int idleMinutes() {
        IPreferenceStore store = store();
        return (store == null) ? DEFAULT_IDLE_MINUTES : store.getInt(IDLE_MINUTES);
    }

}
