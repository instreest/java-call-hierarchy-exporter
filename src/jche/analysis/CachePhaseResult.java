// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.util.TreeSet;

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
    /**
     * そのファイルのパス。多すぎても意味が無いので {@link #SYNTAX_ERROR_SAMPLE} 件まで。
     * パスの順で小さいものから残す（{@link #keepSmallest}）
     */
    public final TreeSet<String> syntaxErrorPaths = new TreeSet<>();

    /**
     * コンパイルエラー（構文エラーを含む）のあったファイル数（新規解析ぶんと再利用ぶんの両方）。
     * 0 でなければビルドが通らない状態で解析しており、その箇所の呼び出しは型解決に失敗しうる
     */
    public int compileErrorFiles;
    /** そのファイルのパス。{@link #SYNTAX_ERROR_SAMPLE} 件まで（{@link #syntaxErrorPaths} と同じ残し方） */
    public final TreeSet<String> compileErrorPaths = new TreeSet<>();

    /** ログに出す構文エラー・コンパイルエラーのファイル名の上限 */
    public static final int SYNTAX_ERROR_SAMPLE = 20;

    /**
     * F 行（またはその中身）から、コンパイルエラー・構文エラーのあったファイルを数える。
     * 新規解析・再利用・中断からの引き継ぎのどの経路でも同じ数え方にするため、ここにまとめる
     */
    public void countErrors(String relativePath, int errors, int syntaxErrors) {
        if (errors > 0 || syntaxErrors > 0) {
            compileErrorFiles++;
            keepSmallest(compileErrorPaths, relativePath);
        }
        if (syntaxErrors > 0) {
            addSyntaxErrorFile(relativePath);
        }
    }

    /** 構文エラーのあったファイルを1件数える。パスは上限まで覚える */
    public void addSyntaxErrorFile(String relativePath) {
        syntaxErrorFiles++;
        keepSmallest(syntaxErrorPaths, relativePath);
    }

    /**
     * パスの順で小さいものから {@link #SYNTAX_ERROR_SAMPLE} 件だけを残す。
     *
     * <p>数える順は、差分更新では「解析し直したファイル → 書き写したブロック（旧キャッシュの並び）」で、
     * 全件解析とは違う。先に来た順で残すと、同じソースでも warnings.txt に載るファイルと並びがキャッシュの
     * 状態で変わる。順序に依らない選び方にしておく（ヒープは上限の件数だけ）
     */
    private static void keepSmallest(TreeSet<String> sample, String relativePath) {
        sample.add(relativePath);
        if (sample.size() > SYNTAX_ERROR_SAMPLE) {
            sample.pollLast();
        }
    }
}
