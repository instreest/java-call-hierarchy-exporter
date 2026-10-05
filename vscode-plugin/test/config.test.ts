// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import * as path from 'node:path';
import { test } from 'node:test';
import { codeExecutingKeys, findConfigFiles, generatedConfigText, materialize, resolveConfigSource } from '../src/config';

function scratch(): string {
    return mkdtempSync(path.join(tmpdir(), 'jche-vscode-'));
}

test('設定ファイルが無ければ自動生成（project.root だけ）', async () => {
    const root = scratch();
    const decided = resolveConfigSource(root, undefined, undefined);
    assert.equal(decided.source?.kind, 'generated');
    const file = await materialize(decided.source!, path.join(root, '.scratch'));
    const text = readFileSync(file, 'utf8');
    assert.ok(text.split('\n').includes(`project.root=${path.resolve(root)}`), 'project.root は絶対パスをそのまま書く');
    assert.doesNotMatch(text, /^source\.folders=/m, '推測はしない。ProjectDetector に任せる');
});

test('jche.properties が1つならそれ', () => {
    const root = scratch();
    writeFileSync(path.join(root, 'jche.properties'), 'project.root=.\n');
    writeFileSync(path.join(root, 'launcher.properties'), 'x=1\n');   // 起動コマンドの設定。候補に入れない
    const decided = resolveConfigSource(root, undefined, undefined);
    assert.equal(decided.source?.kind, 'file');
    assert.equal(decided.source?.kind === 'file' && path.basename(decided.source.file), 'jche.properties');
});

test('複数あれば候補を返し、覚えていればそれを使う', () => {
    const root = scratch();
    writeFileSync(path.join(root, 'b.properties'), '');
    writeFileSync(path.join(root, 'jche.properties'), '');
    writeFileSync(path.join(root, 'config.properties'), '');   // 以前の名前。jche.properties の次に出す
    writeFileSync(path.join(root, 'a.properties'), '');
    mkdirSync(path.join(root, 'dir.properties'));   // フォルダは候補に入れない
    assert.deepEqual(findConfigFiles(root).map((f) => path.basename(f)),
        ['jche.properties', 'config.properties', 'a.properties', 'b.properties']);
    const undecided = resolveConfigSource(root, undefined, undefined);
    assert.equal(undecided.source, undefined);
    assert.equal(undecided.choices?.length, 4);
    const remembered = resolveConfigSource(root, undefined, path.join(root, 'a.properties'));
    assert.equal(remembered.source?.kind === 'file' && path.basename(remembered.source.file), 'a.properties');
});

test('明示された設定ファイルは最優先。無ければエラーにする（黙って自動生成に落とさない）', () => {
    const root = scratch();
    writeFileSync(path.join(root, 'jche.properties'), '');
    mkdirSync(path.join(root, 'conf'));
    writeFileSync(path.join(root, 'conf', 'mine.properties'), '');
    const explicit = resolveConfigSource(root, 'conf/mine.properties', undefined);
    assert.equal(explicit.source?.kind === 'file' && path.basename(explicit.source.file), 'mine.properties');
    const missing = resolveConfigSource(root, 'conf/nope.properties', undefined);
    assert.equal(missing.source, undefined);
    assert.match(missing.error ?? '', /jche\.configFile/);
});

test('生成する設定の中のバックスラッシュはそのまま（読み手はエスケープとして読まない）', () => {
    assert.match(generatedConfigText('C:\\work\\app'), /^project\.root=.*work\\app$/m);
    assert.doesNotMatch(generatedConfigText('C:\\work\\app'), /\\\\/);
});

test('Java を実行させる項目（plugin.folders / *.providers）を値のあるものだけ拾う', () => {
    assert.deepEqual(codeExecutingKeys('project.root=.\nsource.folders=src\n'), []);
    assert.deepEqual(codeExecutingKeys('plugin.folders=\nresolver.candidate.providers=\ncall.rules.providers= \n'), [],
        '値が空なら拾わない（ひな形にキーだけ残っていることがある）');
    assert.deepEqual(codeExecutingKeys('\uFEFF# plugin.folders=plugins\n!call.rules.providers=x\nproject.root=.\n'), [],
        '注釈の行は拾わない。先頭の BOM は読み飛ばす');
    assert.deepEqual(codeExecutingKeys('call.rules.providers=a.B\nplugin.folders = plugins \r\nproject.root=.\n'),
        ['plugin.folders', 'call.rules.providers'], '並びは表の順。CRLF と = の前後の空白を許す');
    assert.deepEqual(codeExecutingKeys('resolver.candidate.providers=jp.co.x.A,\\\n    jp.co.x.B\nproject.root=.\n'),
        ['resolver.candidate.providers'], '行末の \\ で続く値');
    assert.deepEqual(codeExecutingKeys('plugin.folders=\\\n    plugins\n'), ['plugin.folders'],
        '1 行目が空でも、続きの行に値があれば拾う');
    assert.deepEqual(codeExecutingKeys('plugin.folders=\n    plugins\n'), ['plugin.folders'],
        '字下げだけの続き（\\ 無し）も本体と同じく値として読む');
    assert.deepEqual(codeExecutingKeys('plugin.folders=\n\n    plugins\n'), [],
        '空行を挟むと字下げの続きではない');
    assert.deepEqual(codeExecutingKeys('plugin.folders=\\\n  resolver.candidate.providers=x\n'), ['resolver.candidate.providers'],
        '項目=の形の行は、\\ の後ろでも字下げされていても新しい項目');
});
