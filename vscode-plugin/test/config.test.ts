// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import * as path from 'node:path';
import { test } from 'node:test';
import { findConfigFiles, generatedConfigText, materialize, resolveConfigSource } from '../src/config';

function scratch(): string {
    return mkdtempSync(path.join(tmpdir(), 'jche-vscode-'));
}

test('設定ファイルが無ければ自動生成（project.root だけ）', async () => {
    const root = scratch();
    const decided = resolveConfigSource(root, undefined, undefined);
    assert.equal(decided.source?.kind, 'generated');
    const file = await materialize(decided.source!, path.join(root, '.scratch'));
    const text = readFileSync(file, 'utf8');
    assert.match(text, new RegExp(`^project.root=${path.resolve(root).replace(/[\\\\]/g, '\\\\\\\\')}$`, 'm'));
    assert.doesNotMatch(text, /^source\.folders=/m, '推測はしない。ProjectDetector に任せる');
});

test('config.properties が1つならそれ', () => {
    const root = scratch();
    writeFileSync(path.join(root, 'config.properties'), 'project.root=.\n');
    writeFileSync(path.join(root, 'launcher.properties'), 'x=1\n');   // 起動コマンドの設定。候補に入れない
    const decided = resolveConfigSource(root, undefined, undefined);
    assert.equal(decided.source?.kind, 'file');
    assert.equal(decided.source?.kind === 'file' && path.basename(decided.source.file), 'config.properties');
});

test('複数あれば候補を返し、覚えていればそれを使う', () => {
    const root = scratch();
    writeFileSync(path.join(root, 'b.properties'), '');
    writeFileSync(path.join(root, 'config.properties'), '');
    writeFileSync(path.join(root, 'a.properties'), '');
    mkdirSync(path.join(root, 'dir.properties'));   // フォルダは候補に入れない
    assert.deepEqual(findConfigFiles(root).map((f) => path.basename(f)),
        ['config.properties', 'a.properties', 'b.properties']);
    const undecided = resolveConfigSource(root, undefined, undefined);
    assert.equal(undecided.source, undefined);
    assert.equal(undecided.choices?.length, 3);
    const remembered = resolveConfigSource(root, undefined, path.join(root, 'a.properties'));
    assert.equal(remembered.source?.kind === 'file' && path.basename(remembered.source.file), 'a.properties');
});

test('明示された設定ファイルは最優先。無ければエラーにする（黙って自動生成に落とさない）', () => {
    const root = scratch();
    writeFileSync(path.join(root, 'config.properties'), '');
    mkdirSync(path.join(root, 'conf'));
    writeFileSync(path.join(root, 'conf', 'mine.properties'), '');
    const explicit = resolveConfigSource(root, 'conf/mine.properties', undefined);
    assert.equal(explicit.source?.kind === 'file' && path.basename(explicit.source.file), 'mine.properties');
    const missing = resolveConfigSource(root, 'conf/nope.properties', undefined);
    assert.equal(missing.source, undefined);
    assert.match(missing.error ?? '', /jche\.configFile/);
});

test('生成する設定の中のバックスラッシュは逃がす（Windows のパス）', () => {
    assert.match(generatedConfigText('C:\\work\\app'), /^project\.root=.*work\\\\app$/m);
});
