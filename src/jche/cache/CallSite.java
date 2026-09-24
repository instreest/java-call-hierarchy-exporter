// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cache;

/**
 * 呼び出し箇所1件（C行またはU行）。
 * 解決できた呼び出しと失敗した呼び出しを、ソース上の順のまま1つの列に持つための共通型。
 */
public sealed interface CallSite permits CallEdgeFact, UnresolvedCallFact {

    /**
     * キャッシュの行にする。呼び出し箇所の値（レシーバ・実引数・識別キー・ガード）も同じ行の末尾に持つ。
     *
     * @param symbols ブロックの記号表。呼び出し元・呼び出し先の番号をここで振る
     * @param values  この呼び出し箇所の値（{@link FileAnalysis#callSiteValues} の同じ位置のもの）
     */
    String toRow(SymbolTable symbols, CallSiteValues values);
}
