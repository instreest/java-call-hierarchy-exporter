// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import * as path from 'node:path';
import { EN } from '../src/messages.en';
import { JA } from '../src/messages.ja';
import { currentLanguage, setLanguage, t } from '../src/messages';

/**
 * 文言（英語が既定、日本語は重ねる）の検査。
 *
 * 訳し忘れ・キーの片落ち・差し込みの取り違えは、どれも例外を出さずに素通りする
 * （画面にキー名が出る、値が出ない）。解析本体の `test/nls/run.sh` と同じ観点を、
 * この拡張の側でも機械で見る（docs/nls-qa.md の Q13）。
 */

/** リポジトリの `vscode-plugin/` （束ねた検査は out/test/ から動く） */
const PLUGIN = path.resolve(__dirname, '..', '..');

function sourceFiles(dir: string): string[] {
    const out: string[] = [];
    for (const name of readdirSync(dir)) {
        const full = path.join(dir, name);
        if (statSync(full).isDirectory()) {
            out.push(...sourceFiles(full));
        } else if (name.endsWith('.ts')) {
            out.push(full);
        }
    }
    return out;
}

/** コメント・文字列・テンプレートを頭から順に読み分ける（コメントを先に消すと、文字列の中の // で検査が死ぬ） */
const TOKEN = /(\/\*[\s\S]*?\*\/)|(\/\/[^\n]*)|('(?:[^'\\\n]|\\.)*')|("(?:[^"\\\n]|\\.)*")|(`(?:[^`\\]|\\.)*`)|[\s\S]/g;
const JAPANESE = /[぀-ゟ゠-ヿ一-鿿！-｠]/;

function stringLiterals(text: string): string[] {
    const out: string[] = [];
    for (const m of text.matchAll(TOKEN)) {
        const lit = m[3] ?? m[4] ?? m[5];
        if (lit !== undefined) {
            out.push(lit);
        }
    }
    return out;
}

function placeholdersOf(table: Record<string, string>): Map<string, string> {
    const out = new Map<string, string>();
    for (const [key, value] of Object.entries(table)) {
        const numbers = [...value.matchAll(/\{(\d+)\}/g)].map((m) => Number(m[1]));
        out.set(key, [...new Set(numbers)].sort((a, b) => a - b).join(','));
    }
    return out;
}

test('既定は英語で、日本語を選んだときだけ日本語になる', () => {
    setLanguage(undefined);
    assert.equal(currentLanguage(), 'en');
    assert.equal(t('status.action.analyze'), 'Analyze');

    setLanguage('ja');
    assert.equal(currentLanguage(), 'ja');
    assert.equal(t('status.action.analyze'), '解析する');

    // 地域付きも言語だけ見る
    setLanguage('ja-jp');
    assert.equal(currentLanguage(), 'ja');

    // 訳の無い言語は英語に落ちる
    setLanguage('fr');
    assert.equal(currentLanguage(), 'en');
    assert.equal(t('status.action.analyze'), 'Analyze');
    setLanguage(undefined);
});

test('差し込みと、無いキーの見え方', () => {
    setLanguage(undefined);
    assert.equal(t('view.exportDone', 12, '/tmp/a.csv'), 'Wrote 12 rows: /tmp/a.csv');
    // 対応する引数が無ければそのまま残す（訳を直すときに気づけるように）
    assert.equal(t('view.exportDone', 12), 'Wrote 12 rows: {1}');
    assert.equal(t('no.such.key'), '!no.such.key!');
});

test('英語と日本語でキーと差し込みがそろっている', () => {
    const en = Object.keys(EN).sort();
    const ja = Object.keys(JA).sort();
    assert.deepEqual(ja, en, '片方にしか無いキーがある');

    const enPh = placeholdersOf(EN);
    const jaPh = placeholdersOf(JA);
    for (const key of en) {
        assert.equal(jaPh.get(key), enPh.get(key),
            `${key} の差し込み（{0} {1} …）の番号が英語と日本語で食い違う`);
    }
});

test('使っているキーが英語の表にあり、使っていないキーが残っていない', () => {
    const used = new Set<string>();
    for (const file of sourceFiles(path.join(PLUGIN, 'src'))) {
        if (path.basename(file).startsWith('messages.')) {
            continue;
        }
        const text = readFileSync(file, 'utf8');
        for (const m of text.matchAll(/\bt\(\s*'([a-zA-Z0-9._]+)'/g)) {
            used.add(m[1]);
        }
        // t(cond ? 'a' : 'b') の形
        for (const m of text.matchAll(/\bt\(\s*[^)]*?\?\s*'([a-zA-Z0-9._]+)'\s*:\s*'([a-zA-Z0-9._]+)'/g)) {
            used.add(m[1]);
            used.add(m[2]);
        }
    }
    const missing = [...used].filter((k) => !(k in EN)).sort();
    assert.deepEqual(missing, [], '英語の表に無いキーを使っている（画面に !キー! が出る）');
    const unused = Object.keys(EN).filter((k) => !used.has(k)).sort();
    assert.deepEqual(unused, [], '使われていないキーが残っている（消し忘れ）');
});

test('ソースに日本語の文字列リテラルが残っていない', () => {
    const left: string[] = [];
    for (const file of sourceFiles(path.join(PLUGIN, 'src'))) {
        if (path.basename(file) === 'messages.ja.ts') {
            continue;   // 日本語の表そのもの
        }
        for (const lit of stringLiterals(readFileSync(file, 'utf8'))) {
            if (JAPANESE.test(lit)) {
                left.push(`${path.relative(PLUGIN, file)}: ${lit}`);
            }
        }
    }
    assert.deepEqual(left, [], '文言は messages.en.ts / messages.ja.ts へ');
});

test('package.json の寄与が package.nls*.json でそろっている', () => {
    const manifest = readFileSync(path.join(PLUGIN, 'package.json'), 'utf8');
    const en = JSON.parse(readFileSync(path.join(PLUGIN, 'package.nls.json'), 'utf8')) as Record<string, string>;
    const ja = JSON.parse(readFileSync(path.join(PLUGIN, 'package.nls.ja.json'), 'utf8')) as Record<string, string>;

    assert.deepEqual(Object.keys(ja).sort(), Object.keys(en).sort(), '片方にしか無いキーがある');

    // package.json に日本語が残っていないこと（VSCode 本体が起動時に読むので、ここは %キー% だけ）
    assert.ok(!JAPANESE.test(manifest), 'package.json に日本語が残っている（package.nls*.json へ）');

    // "%キー%" だけの値が、すべて英語のファイルにあること
    const referenced = [...manifest.matchAll(/"%([a-zA-Z0-9._]+)%"/g)].map((m) => m[1]);
    assert.ok(referenced.length > 0, '%キー% が 1 つも無い');
    const unknown = referenced.filter((k) => !(k in en)).sort();
    assert.deepEqual([...new Set(unknown)], [], 'package.nls.json に無いキーを参照している');
    const notUsed = Object.keys(en).filter((k) => !referenced.includes(k)).sort();
    assert.deepEqual(notUsed, [], 'package.json から参照されていないキーが残っている');
});
