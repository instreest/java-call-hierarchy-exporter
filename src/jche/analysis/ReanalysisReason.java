// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

/**
 * ファイルを解析し直す理由（集計の内訳）。{@link StaleTypes#touches} の判定結果と
 * {@link BlockWriter#countAs} の両方で使う（以前は別々の定数で同じ意味を表していた）
 */
enum ReanalysisReason {
    /** 変わった型にも jar にも触れていない（再解析しない）。自分が変わった（または新規）ファイルの集計にも使う */
    UNTOUCHED,
    /** 依存する型（ソース）が変わった */
    BY_SOURCE,
    /** 依存 jar が変わった */
    BY_LIBRARY
}
