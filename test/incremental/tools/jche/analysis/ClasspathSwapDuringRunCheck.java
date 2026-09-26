// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;

import jche.CallHierarchyExporter;
import jche.config.ToolRoot;

/**
 * 解析のあいだに依存 jar・クラスフォルダを書き換える実行を、時間に頼らずに作る（test/incremental/run.sh の
 * 「解析のあいだに書き換えた依存 jar・クラスフォルダ」）。
 *
 * <pre>
 *   java jche.analysis.ClasspathSwapDuringRunCheck &lt;設定ファイル&gt; &lt;書き換えるファイル&gt; &lt;書き換えた後の中身のファイル | -&gt;
 * </pre>
 * 差分更新が最初のバッチを JDT に渡す直前（パス0 で依存 jar の指紋を取ったあと）に、書き換えるファイルの中身を
 * 差し替えて（{@code -} なら消して）から、ふつうに解析する（jche.analysis.CacheUpdater の検査用の差し込み口）。
 * 元に戻すのは呼び出し側（実行のあと）。終了コードは失敗した設定の数。
 */
public final class ClasspathSwapDuringRunCheck {

    private ClasspathSwapDuringRunCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path config = Paths.get(args[0]);
        Path target = Paths.get(args[1]);
        String replacement = args[2];
        boolean[] done = {false};
        CacheUpdater.beforeBatchForTest = batch -> {
            if (done[0]) {
                return;
            }
            try {
                if (replacement.equals("-")) {
                    Files.delete(target);
                } else {
                    Files.copy(Paths.get(replacement), target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            done[0] = true;
        };
        int failed = CallHierarchyExporter.runAll(List.of(config), ToolRoot.locate(CallHierarchyExporter.class));
        if (!done[0]) {
            System.err.println("NG: 解析するバッチが無く、書き換えられませんでした");
            System.exit(2);
        }
        System.exit(failed);
    }
}
