// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { DEFAULT_FILTERS, describeFilters, fromStored, toWords } from '../src/filters';

test('サーバーへ渡す語（jche.server.TreeFilters と同じ名前）', () => {
    assert.deepEqual(toWords(DEFAULT_FILTERS), ['depth=5', 'text=', 'tests=0', 'guessed=1', 'exclude=1', 'dedupe=1']);
    assert.deepEqual(toWords({ ...DEFAULT_FILTERS, text: 'Order', maxDepth: 0, includeTests: true, dedupe: false }),
        ['depth=1', 'text=Order', 'tests=1', 'guessed=1', 'exclude=1', 'dedupe=0']);
});

test('保存していた値からの復元（壊れていれば項目ごとに既定へ）', () => {
    assert.deepEqual(fromStored(undefined), DEFAULT_FILTERS);
    assert.deepEqual(fromStored(undefined, 8), { ...DEFAULT_FILTERS, maxDepth: 8 });
    assert.deepEqual(fromStored({ text: 'x', maxDepth: -3, includeTests: 'yes', dedupe: false }),
        { ...DEFAULT_FILTERS, text: 'x', dedupe: false });
});

test('効いている条件の説明', () => {
    assert.equal(describeFilters(DEFAULT_FILTERS), '');
    assert.equal(describeFilters({ ...DEFAULT_FILTERS, text: 'Order', includeGuessed: false }), '絞り込み: Order / 推定を除く');
});
