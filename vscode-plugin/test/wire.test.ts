// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { parseRow, ServerResponse } from '../src/server/response';
import { buildTree, countNodes, firstTruncated } from '../src/server/tree';
import { escape, join, unescape } from '../src/server/wire';

test('逃がし方は往復する（サーバーの Protocol と同じ規則）', () => {
    for (const s of ['', 'plain', 'a\tb', 'x\ny', 'c\\d', '\\t は文字', 'end\\', '\r\n', '日本語\tタブ']) {
        assert.equal(unescape(escape(s)), s, JSON.stringify(s));
    }
    assert.equal(escape('a\tb\nc\\d'), 'a\\tb\\nc\\\\d');
    assert.equal(join(['FIND', 'a\tb']), 'FIND\ta\\tb');
});

test('応答の行を読む（OK の key=value と NG の理由）', () => {
    const ok = ServerResponse.of('OK\tprotocol=1\tjdt=3.46.0\tjvm=25.0.3', []);
    assert.equal(ok.ok, true);
    assert.equal(ok.field('jdt'), '3.46.0');
    assert.equal(ok.numberField('protocol', 0), 1);
    assert.equal(ok.reason, '');

    const ng = ServerResponse.of('NG\tconfig-not-found\t/x/y.properties', []);
    assert.equal(ng.ok, false);
    assert.equal(ng.reason, 'config-not-found /x/y.properties');

    const escaped = ServerResponse.of('OK\tlabel=a\\tb', []);
    assert.equal(escaped.field('label'), 'a\tb');
});

test('R 行を読む（壊れた行は捨てる）', () => {
    const row = parseRow('R\t2\tfx.A#m()\tA.m()\tsrc/A.java\t12\tDATAFLOW\ttruncated,match'.split('\t'));
    assert.ok(row);
    assert.equal(row.depth, 2);
    assert.equal(row.key, 'fx.A#m()');
    assert.equal(row.line, 12);
    assert.deepEqual(row.flags, ['truncated', 'match']);
    assert.equal(parseRow('R\tx\tk\tl\tf\t1\tr'.split('\t')), undefined);
    assert.equal(parseRow(['R', '0']), undefined);
    const noFlags = parseRow('R\t0\tk\tl\tf\t1\t\t'.split('\t'));
    assert.ok(noFlags);
    assert.deepEqual(noFlags.flags, []);
});

test('深さ優先の行から木を組み直す', () => {
    const lines = [
        'R\t0\troot\t\t\t0\t\t',
        'R\t1\ta\t\t\t0\t\t',
        'R\t2\ta1\t\t\t0\t\ttruncated',
        'R\t1\tb\t\t\t0\t\t',
        'R\t2\tb1\t\t\t0\t\t',
        'R\t2\tb2\t\t\t0\t\t',
        'R\t9\tbroken\t\t\t0\t\t',   // 深さが飛んでいる → 直前の b2 の下
    ];
    const rows = lines.map((l) => parseRow(l.split('\t'))!);
    const root = buildTree(rows);
    assert.ok(root);
    assert.equal(root.row.key, 'root');
    assert.deepEqual(root.children.map((c) => c.row.key), ['a', 'b']);
    assert.deepEqual(root.children[1].children.map((c) => c.row.key), ['b1', 'b2']);
    assert.equal(countNodes(root), 7);
    assert.equal(firstTruncated(root)?.row.key, 'a1');
    assert.equal(buildTree([]), undefined);
});
