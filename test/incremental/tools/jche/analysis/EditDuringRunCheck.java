// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;

import jche.CallHierarchyExporter;
import jche.analysis.CallEdgeExtractor.SourceFile;
import jche.config.ToolRoot;

/**
 * 解析のあいだにソースを書き換える実行を、時間に頼らずに作る（test/incremental/run.sh の
 * 「解析のあいだに書き換えたソース」）。
 *
 * <pre>
 *   java jche.analysis.EditDuringRunCheck &lt;設定ファイル&gt; &lt;きっかけのファイルの相対パス&gt;
 *        &lt;書き換えるファイル&gt; &lt;書き換えた後の中身のファイル&gt;
 * </pre>
 * 差分更新が、きっかけのファイルを含むバッチを JDT に渡す直前（パス1 で内容ハッシュを取ったあと）に、
 * 書き換えるファイルの中身を差し替えてから、ふつうに解析する（jche.analysis.CacheUpdater の検査用の差し込み口。
 * ソースと同じパッケージに置いて、パッケージの中だけで見える口を使う）。終了コードは失敗した設定の数。
 */
public final class EditDuringRunCheck {

    private EditDuringRunCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path config = Paths.get(args[0]);
        String trigger = args[1];
        Path target = Paths.get(args[2]);
        Path replacement = Paths.get(args[3]);
        boolean[] done = {false};
        CacheUpdater.beforeBatchForTest = batch -> {
            if (done[0] || batch.stream().map(SourceFile::relativePath).noneMatch(trigger::equals)) {
                return;
            }
            try {
                Files.copy(replacement, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            done[0] = true;
        };
        int failed = CallHierarchyExporter.runAll(List.of(config), ToolRoot.locate(CallHierarchyExporter.class));
        if (!done[0]) {
            System.err.println("NG: " + trigger + " を含むバッチが無く、書き換えられませんでした");
            System.exit(2);
        }
        System.exit(failed);
    }
}
