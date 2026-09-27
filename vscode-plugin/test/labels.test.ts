// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { accessLabel, countMessage, describeRow, fieldViewDescription, viewDescription } from '../src/labels';
import { parseRow } from '../src/server/response';

function row(line: string) {
    const r = parseRow(line.split('\t'));
    assert.ok(r);
    return r;
}

test('印からアイコンと説明を決める', () => {
    const plain = describeRow(row('R\t1\tfx.A#m()\tfx.A.m()\tsrc/fx/A.java\t12\t\t'), 'callers');
    assert.equal(plain.icon, 'symbol-method');
    assert.equal(plain.description, 'A.java:12');
    assert.equal(plain.expandable, true);
    assert.match(plain.contextValue, /\bsource\b/);

    const recursive = describeRow(row('R\t2\tfx.A#m()\tfx.A.m()\tsrc/fx/A.java\t12\t\trecursive'), 'callers');
    assert.equal(recursive.icon, 'refresh');
    assert.equal(recursive.expandable, false, '再帰は打ち切る');
    assert.match(recursive.description, /recursive/);

    const guessed = describeRow(row('R\t1\tfx.B#n()\tfx.B.n()\tsrc/fx/B.java\t3\tDATAFLOW_FACTORY\tguessed'), 'callers');
    assert.equal(guessed.iconColor, 'list.warningForeground');
    assert.match(guessed.description, /guessed: DATAFLOW_FACTORY/);
    assert.match(guessed.tooltip, /How it was resolved: DATAFLOW_FACTORY/);

    const noSource = describeRow(row('R\t1\tlib.C#x()\tlib.C.x()\t\t0\t\tnosource'), 'callers');
    assert.equal(noSource.icon, 'library');
    assert.doesNotMatch(noSource.contextValue, /\bsource\b/, '宣言を開けない');
    assert.match(noSource.tooltip, /No source/);

    const truncated = describeRow(row('R\t5\tfx.D#y()\tfx.D.y()\tsrc/fx/D.java\t7\t\ttruncated'), 'callers');
    assert.match(truncated.contextValue, /\btruncated\b/);
    assert.match(truncated.tooltip, /fetch the rest/);
});

test('見出しと件数の文言', () => {
    assert.equal(viewDescription('fx.A.m()', 38, 'callers'), 'fx.A.m() (line 38) - Callers');
    assert.equal(viewDescription('fx.A.m()', 0, 'callees'), 'fx.A.m() - Callees');
    assert.equal(countMessage(27, 0, 20000), '27 shown');
    assert.match(countMessage(27, 3, 20000), /3 node\(s\) cut off/);
    assert.match(countMessage(20000, 0, 20000), /Stopped at 20,000 rows/, '上限に当たったら黙って切らない');
});

test('フィールドの木の根と深さ 1 の行（docs/field-callers-qa.md）', () => {
    const root = describeRow(row('R\t0\tapp.Order#status\tapp.Order.status\tsrc/app/Order.java\t0\t\tfield'), 'callers');
    assert.equal(root.icon, 'symbol-field');
    assert.equal(root.label, 'app.Order.status');
    assert.equal(root.description, 'Order.java', '宣言の行はキャッシュに無いので、ファイル名だけ');
    assert.equal(root.contextValue, 'field', '「根にする」「宣言を開く」（メソッドの操作）は出さない');

    const writer = describeRow(row('R\t1\tapp.Order#setStatus(java.lang.String)\tapp.Order.setStatus(String)\tsrc/app/Order.java\t6\twrite\taccess'), 'callers');
    assert.equal(writer.icon, 'edit');
    assert.equal(writer.description, 'Order.java:6  \u00abwrite\u00bb');
    assert.match(writer.contextValue, /\bmethod\b/, 'メソッドなので根にできる');
    assert.match(writer.contextValue, /\bsource\b/);
    assert.match(writer.tooltip, /Access: write/);

    const reader = describeRow(row('R\t1\tapp.Order#getStatus()\tapp.Order.getStatus()\tsrc/app/Order.java\t5\tread\taccess,truncated'), 'callers');
    assert.equal(reader.icon, 'eye');
    assert.match(reader.contextValue, /\btruncated\b/);

    const initializer = describeRow(row('R\t1\t\t\tsrc/app/Order.java\t0\twrite\taccess,initializer'), 'callers');
    assert.equal(initializer.label, '(field initializer)', 'キーの無い行は、ここで名前を付ける');
    assert.doesNotMatch(initializer.contextValue, /\bmethod\b/, 'メソッドではないので根にできない');
    assert.equal(initializer.expandable, false);

    const noMethod = describeRow(row('R\t1\t\t\tsrc/app/Order.java\t3\tread\taccess,nomethod'), 'callers');
    assert.equal(noMethod.label, '(outside any method)');

    // 深さ 2 からは呼び出し元なので、ふつうの行
    const caller = describeRow(row('R\t2\tapp.Service#top(app.Order)\tapp.Service.top(Order)\tsrc/app/Service.java\t5\t\t'), 'callers');
    assert.equal(caller.icon, 'symbol-method');
});

test('参照の種類と、フィールドの木の見出し', () => {
    assert.equal(accessLabel('read'), 'read');
    assert.equal(accessLabel('read/write'), 'read/write');
    assert.equal(accessLabel('someday'), 'someday', '知らない値は黙って消さずにそのまま出す');
    assert.equal(fieldViewDescription('app.Order.status', 'all'), 'app.Order.status - Methods that read or write it, and their callers');
    assert.match(fieldViewDescription('app.Order.status', 'write'), /Methods that write it/);
    assert.match(fieldViewDescription('app.Order.status', 'read'), /Methods that read it/);
});
