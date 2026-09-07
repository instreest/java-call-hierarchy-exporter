// Copyright 2026 Inoue Kazuhiro. SPDX-License-Identifier: Apache-2.0
package jche.analysis;

/** フェーズ1（ソース解析とキャッシュ更新）の集計 */
public final class CachePhaseResult {
    public int reused;
    public int parsed;
    public int failed;
    /** 自分は変わっていないが、依存する型（ソース）が変わったので解析し直したファイル数（parsed に含む） */
    public int dependents;
    /** 自分は変わっていないが、依存 jar が変わったので解析し直したファイル数（parsed に含む） */
    public int libraryDependents;
    /** 型解決できなかった呼び出しの件数。クラスパス不足の検知に使う */
    public long unresolved;
}
