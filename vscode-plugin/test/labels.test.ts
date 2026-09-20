// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { countMessage, describeRow, viewDescription } from '../src/labels';
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
