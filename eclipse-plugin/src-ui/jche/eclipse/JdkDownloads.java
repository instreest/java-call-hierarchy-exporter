// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import java.io.File;

import jche.eclipse.server.JdkDownload;

/**
 * 設定画面から取得した JDK の置き場所（{@code <状態フォルダ>/jdk/<版>/…}）を見る。
 *
 * <p>設定に場所が残っていなくても、取得済みのものがあれば使えるようにするためのもの。
 * ワークスペースを消せば一緒に消えるので、環境は汚さない。
 */
final class JdkDownloads {

    private JdkDownloads() {
    }

    /** 取得済みの JDK のうち、いちばん新しい版の java。無ければ null */
    static File latestIn(File root) {
        File[] versions = root.listFiles();
        if (versions == null) {
            return null;
        }
        File best = null;
        int bestVersion = -1;
        for (File dir : versions) {
            if (!dir.isDirectory()) {
                continue;
            }
            int version;
            try {
                version = Integer.parseInt(dir.getName());
            } catch (NumberFormatException e) {
                continue;   // 版でない名前のフォルダは無視する
            }
            if (version > bestVersion) {
                File java = JdkDownload.findJava(dir);
                if (java != null) {
                    best = java;
                    bestVersion = version;
                }
            }
        }
        return best;
    }
}
