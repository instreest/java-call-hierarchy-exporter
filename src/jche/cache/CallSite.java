// Copyright 2026 Inoue Kazuhiro. SPDX-License-Identifier: Apache-2.0
package jche.cache;

/**
 * 呼び出し箇所1件（C行またはU行）。
 * 解決できた呼び出しと失敗した呼び出しを、ソース上の順のまま1つの列に持つための共通型。
 */
public sealed interface CallSite permits CallEdgeFact, UnresolvedCallFact {

    String toRow();
}
