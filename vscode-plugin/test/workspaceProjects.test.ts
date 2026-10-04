// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import * as path from 'node:path';
import { test } from 'node:test';
import {
    entryFor, readWorkspaceProjects, replaced, rootOfEntry, valueOf, writeWorkspaceProjects,
} from '../src/workspaceProjects';

function scratch(): string {
    return mkdtempSync(path.join(tmpdir(), 'jche-vscode-ws-'));
}

// 本体の読み手（jche.config.ConfigFile）と同じ値になること。行末の \ と字下げの続き、続きの途中の注釈、
// 次の行が「項目=」なら末尾の \ を捨てる（Eclipse 版は test/plugin-config が本体の読み手と直接比べている）
test('workspace.projects の読み方は本体の設定ファイルの読み方と同じ', () => {
    const cases: [string, string, string | undefined][] = [
        ['1 行', 'project.root=.\nworkspace.projects=../a,../b\nentry.packages=x\n', '../a,../b'],
        ['行末の円記号の続き', 'workspace.projects=../a,\\\n    ../b,\\\n../c\nentry.packages=x\n', '../a,../b,../c'],
        ['字下げの続き', 'workspace.projects=../a,\n    ../b\nentry.packages=x\n', '../a,../b'],
        ['続きの途中の注釈', 'workspace.projects=../a,\\\n# ../x は外した\n    ../b\nentry.packages=x\n', '../a,../b'],
        ['末尾の円記号の次が項目', 'workspace.projects=C:\\ws\\a\\\nentry.packages=x\n', 'C:\\ws\\a'],
        ['空の値', 'workspace.projects=\nentry.packages=x\n', ''],
        ['無い', 'project.root=.\nentry.packages=x\n', undefined],
        ['注釈の中の同じ名前は項目ではない', '# workspace.projects=../z\nentry.packages=x\n', undefined],
    ];
    for (const [name, text, expected] of cases) {
        assert.equal(valueOf(text.split(/\r?\n/), 'workspace.projects'), expected, name);
    }
});

test('書き換えは論理行ごと 1 行に差し替え、無ければ末尾に足し、ほかの行は変えない', () => {
    const lines = ['project.root=.', 'workspace.projects=../a,\\', '    ../b', 'entry.packages=x', ''];
    assert.deepEqual(replaced(lines, 'workspace.projects', '../p,../q'),
        ['project.root=.', 'workspace.projects=../p,../q', 'entry.packages=x', '']);
    assert.deepEqual(replaced(['project.root=.', 'entry.packages=x'], 'workspace.projects', '../p'),
        ['project.root=.', 'entry.packages=x', '', 'workspace.projects=../p']);
    assert.deepEqual(replaced([], 'workspace.projects', ''), ['workspace.projects=']);
});

test('ファイルの読み書きの往復', async () => {
    const root = scratch();
    const file = path.join(root, 'jche.properties');
    writeFileSync(file, 'project.root=.\nentry.packages=x\n');
    assert.deepEqual(await readWorkspaceProjects(file), []);
    await writeWorkspaceProjects(file, ['../a', '../b']);
    assert.deepEqual(await readWorkspaceProjects(file), ['../a', '../b']);
    assert.match(readFileSync(file, 'utf8'), /^project\.root=\.\nentry\.packages=x\n\nworkspace\.projects=\.\.\/a,\.\.\/b\n$/);
    await writeWorkspaceProjects(file, []);
    assert.deepEqual(await readWorkspaceProjects(file), []);
    assert.match(readFileSync(file, 'utf8'), /\nworkspace\.projects=\n$/);
});

test('件はフォルダか設定ファイルを指し、設定ファイルならその project.root', () => {
    const root = scratch();
    const configFile = path.join(root, 'core', 'jche.properties');
    const other = path.join(root, 'batch');
    writeFileSync(path.join(root, 'other.properties'), 'project.root=batch\n');   // other.properties のフォルダ（root）からの相対
    assert.equal(rootOfEntry(configFile, '../batch'), path.resolve(other));
    assert.equal(rootOfEntry(configFile, '../other.properties'), path.resolve(other));
    assert.equal(rootOfEntry(configFile, path.resolve(other)), path.resolve(other));
    assert.equal(entryFor(configFile, other), '../batch');
    assert.equal(entryFor(configFile, path.join(root, 'core')), '.');
});
