// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0

import { t } from './messages';

/**
 * 木の絞り込み条件。
 *
 * **フィルタは再解析を起こさない。** 条件を `TREE` の引数に足して投げ直すだけで、
 * サーバー側（`jche.server.TreeFilters`）がスナップショットを読むときに絞る。
 * 名前と意味は Eclipse 版の `FilterSettings` と同じ。
 *
 * `vscode` に触らないので Node だけで検査できる。
 */
export interface FilterSettings {
    /** 型名・メソッド名・パッケージの部分一致（空なら絞り込まない） */
    readonly text: string;
    /** 木の深さの上限 */
    readonly maxDepth: number;
    /** テストのソースフォルダにあるメソッドを出すか */
    readonly includeTests: boolean;
    /** 推測（データフロー・リフレクション）で特定した呼び出しを出すか */
    readonly includeGuessed: boolean;
    /** 設定ファイルの exclude.packages を画面にも効かせるか */
    readonly applyExcludePackages: boolean;
    /** 同じ相手を1回だけ出すか（false なら呼び出している行ごとに出す） */
    readonly dedupe: boolean;
}

export const DEFAULT_FILTERS: FilterSettings = {
    text: '',
    maxDepth: 5,
    includeTests: false,
    includeGuessed: true,
    applyExcludePackages: true,
    dedupe: true,
};

/** サーバーへ渡す `key=value` の並び。名前は `jche.server.TreeFilters#apply` と対 */
export function toWords(f: FilterSettings): string[] {
    return [
        `depth=${Math.max(1, f.maxDepth)}`,
        `text=${f.text}`,
        `tests=${f.includeTests ? 1 : 0}`,
        `guessed=${f.includeGuessed ? 1 : 0}`,
        `exclude=${f.applyExcludePackages ? 1 : 0}`,
        `dedupe=${f.dedupe ? 1 : 0}`,
    ];
}

/** 保存していた値から復元する。壊れていれば既定に戻す（項目ごと） */
export function fromStored(stored: unknown, defaultDepth: number = DEFAULT_FILTERS.maxDepth): FilterSettings {
    const base = { ...DEFAULT_FILTERS, maxDepth: defaultDepth };
    if (typeof stored !== 'object' || stored === null) {
        return base;
    }
    const s = stored as Record<string, unknown>;
    return {
        text: typeof s.text === 'string' ? s.text : base.text,
        maxDepth: typeof s.maxDepth === 'number' && s.maxDepth >= 1 ? Math.floor(s.maxDepth) : base.maxDepth,
        includeTests: typeof s.includeTests === 'boolean' ? s.includeTests : base.includeTests,
        includeGuessed: typeof s.includeGuessed === 'boolean' ? s.includeGuessed : base.includeGuessed,
        applyExcludePackages: typeof s.applyExcludePackages === 'boolean' ? s.applyExcludePackages : base.applyExcludePackages,
        dedupe: typeof s.dedupe === 'boolean' ? s.dedupe : base.dedupe,
    };
}

/** 効いている条件の説明（`TreeView#message` に出す）。既定のままなら空 */
export function describeFilters(f: FilterSettings): string {
    const parts: string[] = [];
    if (f.text !== '') {
        parts.push(t('filter.text', f.text));
    }
    if (f.includeTests) {
        parts.push(t('filter.includeTests'));
    }
    if (!f.includeGuessed) {
        parts.push(t('filter.excludeGuessed'));
    }
    if (!f.applyExcludePackages) {
        parts.push(t('filter.ignoreExcludes'));
    }
    if (!f.dedupe) {
        parts.push(t('filter.perCallSite'));
    }
    return parts.join(' / ');
}
