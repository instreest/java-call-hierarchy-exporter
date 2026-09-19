// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.util.ArrayList;
import java.util.List;

/** フェーズ1（ソース解析とキャッシュ更新）の集計 */
public final class CachePhaseResult {
    public int reused;
    public int parsed;
    public int failed;
    /** 自分は変わっていないが、依存する型（ソース）が変わったので解析し直したファイル数（parsed に含む） */
    public int dependents;
    /** 自分は変わっていないが、依存 jar が変わったので解析し直したファイル数（parsed に含む） */
    public int libraryDependents;
    /**
     * 中断した前回の実行から解析結果を引き継いだファイル数（parsed にも reused にも含まない）。
     * パースし直さずに済んだが、扱いは「解析したファイル」と同じ（{@link CacheUpdater} 参照）
     */
    public int salvaged;
    /** 型解決できなかった呼び出しの件数。クラスパス不足の検知に使う */
    public long unresolved;
    /**
     * 構文エラーがあって本体を読めなかったファイル数（新規解析ぶんと再利用ぶんの両方）。
     * 0 でなければ、そのファイルに書かれた呼び出しは出力に出ていない
     */
    public int syntaxErrorFiles;
    /** そのファイルのパス。多すぎても意味が無いので {@link #SYNTAX_ERROR_SAMPLE} 件まで */
    public final List<String> syntaxErrorPaths = new ArrayList<>();

    /** ログに出す構文エラーのファイル名の上限 */
    public static final int SYNTAX_ERROR_SAMPLE = 20;

    /** 構文エラーのあったファイルを1件数える。パスは上限まで覚える */
    public void addSyntaxErrorFile(String relativePath) {
        syntaxErrorFiles++;
        if (syntaxErrorPaths.size() < SYNTAX_ERROR_SAMPLE) {
            syntaxErrorPaths.add(relativePath);
        }
    }
}
