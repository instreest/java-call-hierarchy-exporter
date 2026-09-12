// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import org.eclipse.core.runtime.Platform;
import org.eclipse.jdt.core.JavaCore;
import org.osgi.framework.Bundle;

/**
 * 「何の上で解析したか」。ログとビューのツールチップに出す。
 *
 * <p>この3つで結果の差がほぼ説明できる。
 * <ul>
 *   <li><b>実行 JVM</b> … JDT は動いている JVM の標準クラスを解析対象のクラスパスに含めるため、
 *       Eclipse を動かす JDK が変わると解析結果も変わりうる。古い JDK だと、新しい API の呼び出しが
 *       型解決に失敗し、その戻り値を使う呼び出しごと欠ける。eclipse.ini の -vm で指定できる</li>
 *   <li><b>JDT Core の版</b> … このプラグインが動いている Eclipse のもの。プラグイン側は下限の版で
 *       コンパイルしてあるので、新しい Eclipse ではその Eclipse の JDT がそのまま使われる</li>
 *   <li><b>解析できる Java の上限</b> … JDT の版で決まる。解析対象がこれより新しい文法を使っていると、
 *       その版として解析され、取りこぼしが出る</li>
 * </ul>
 */
final class EnvironmentInfo {

    private EnvironmentInfo() {
    }

    static String jvmVersion() {
        return System.getProperty("java.version", "?");
    }

    /** この Eclipse に入っている JDT Core の版 */
    static String jdtVersion() {
        Bundle bundle = Platform.getBundle(JavaCore.PLUGIN_ID);
        return (bundle == null) ? "?" : bundle.getVersion().toString();
    }

    /** その JDT が解析できる Java の最大版 */
    static String latestSupportedJavaVersion() {
        return JavaCore.latestSupportedJavaVersion();
    }

    /** ツールチップ用の1行ずつの説明 */
    static String summary() {
        return "実行 JVM: " + jvmVersion()
                + "\nJDT Core: " + jdtVersion()
                + "\nこの JDT が解析できる Java: " + latestSupportedJavaVersion() + " まで"
                + "\n※ 解析対象がこれより新しい文法・API を使っている場合は取りこぼしが出ます。"
                + "\n   Eclipse を新しい JDK で動かす（eclipse.ini の -vm）と改善することがあります。";
    }
}
