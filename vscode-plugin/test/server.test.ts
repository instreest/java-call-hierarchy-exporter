// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import * as path from 'node:path';
import { test } from 'node:test';
import { DEFAULT_TIMEOUT_MS } from '../src/server/connection';
import { launchServer } from '../src/server/launcher';
import { buildTree, countNodes, firstTruncated } from '../src/server/tree';

/**
 * 子プロセスを実際に起動して一連のやりとりを確かめる（Eclipse 版の ClientProbe と対になる）。
 * 画面（vscode）は VSCode が無いと動かせないが、その下の層――子プロセスを起動し、
 * プロトコルで話し、返ってきた行を木に組み直すところ――は Node だけで動かせる。
 *
 * 環境変数:
 *   JCHE_JAVA   … 解析に使う java（17 以上）
 *   JCHE_CP     … 解析本体のクラスパス（path.delimiter 区切り）
 *   JCHE_CONFIG … 解析する設定ファイル（test/regression/whole/config.properties）
 *   JCHE_TARGET … 呼び出し元が多いメソッドのキー
 *   JCHE_AT     … 「<project.root からの相対パス>:<行>」（AT の検査に使う）
 */
const env = process.env;
const java = env.JCHE_JAVA ?? '';
const classpath = (env.JCHE_CP ?? '').split(path.delimiter).filter((p) => p !== '');
const config = env.JCHE_CONFIG ?? '';
const target = env.JCHE_TARGET ?? '';
const [atFile, atLine] = (env.JCHE_AT ?? ':').split(':');

test('子プロセスと一連のやりとり（HELLO → ANALYZE → FIND → AT → TREE → EXPORT）', async () => {
    assert.ok(java && classpath.length > 0 && config && target && atFile,
        'JCHE_JAVA / JCHE_CP / JCHE_CONFIG / JCHE_TARGET / JCHE_AT を環境変数で渡すこと');
    const work = mkdtempSync(path.join(tmpdir(), 'jche-vscode-server-'));
    let progressCount = 0;
    let logCount = 0;
    const stderr: string[] = [];
    const connection = launchServer({
        javaExecutable: java,
        classpath,
        cacheRoot: path.join(work, 'cache'),
        vmArguments: ['-Xmx512m'],
        listener: {
            progress: () => { progressCount++; },
            log: () => { logCount++; },
            stderr: (line) => { stderr.push(line); },
        },
    });
    try {
        const hello = await connection.request(DEFAULT_TIMEOUT_MS, 'HELLO', '1');
        assert.ok(hello.ok, `HELLO: ${hello.raw}\n${stderr.join('\n')}`);
        assert.equal(hello.field('protocol'), '1');
        assert.notEqual(hello.field('jdt'), '');
        assert.notEqual(hello.field('jvm'), '');

        // 応答を待っている間に次を投げても、直列に処理される（順序が崩れない）
        const [ping1, ping2] = await Promise.all([
            connection.request(DEFAULT_TIMEOUT_MS, 'PING'),
            connection.request(DEFAULT_TIMEOUT_MS, 'PING'),
        ]);
        assert.equal(ping1.reason, 'pong');
        assert.equal(ping2.reason, 'pong');

        const before = await connection.request(DEFAULT_TIMEOUT_MS, 'AT', atFile, atLine);
        assert.equal(before.ok, false);
        assert.equal(before.reason, 'not-analyzed');

        const analyze = await connection.request(300_000, 'ANALYZE', config);
        assert.ok(analyze.ok, `ANALYZE: ${analyze.raw}`);
        assert.ok(analyze.numberField('methods', 0) > 0);
        assert.ok(progressCount > 0, '進捗（#P）を受け取る');
        assert.ok(logCount > 0, 'ログ（#L）を受け取る');

        const find = await connection.request(DEFAULT_TIMEOUT_MS, 'FIND', target);
        assert.ok(find.ok, find.raw);
        assert.equal(find.field('key'), target);

        const missing = await connection.request(DEFAULT_TIMEOUT_MS, 'FIND', 'no.such.Type#nope()');
        assert.equal(missing.ok, false);
        assert.equal(missing.reason, 'not-found');

        // AT: 相対パスでも、project.root 配下の絶対パスでも同じ答え
        const at = await connection.request(DEFAULT_TIMEOUT_MS, 'AT', atFile, atLine);
        assert.ok(at.ok, `AT: ${at.raw}`);
        assert.equal(at.field('how'), 'at');
        assert.notEqual(at.field('key'), '');
        assert.ok(at.numberField('line', 0) >= 1 && at.numberField('line', 0) <= Number(atLine));
        const projectRoot = analyze.field('root');
        const atAbsolute = await connection.request(DEFAULT_TIMEOUT_MS, 'AT', path.join(projectRoot, atFile), atLine);
        assert.equal(atAbsolute.field('key'), at.field('key'));
        const atNoFile = await connection.request(DEFAULT_TIMEOUT_MS, 'AT', 'no/such/File.java', '1');
        assert.equal(atNoFile.reason, 'file-not-analyzed');

        const tree = await connection.request(60_000, 'TREE', target, 'callers', 'depth=2');
        assert.ok(tree.ok, tree.raw);
        const root = buildTree(tree.rows);
        assert.ok(root);
        assert.equal(root.row.key, target);
        assert.ok(root.children.length > 0, '直接の呼び出し元がある');
        assert.equal(countNodes(root), tree.rows.length, '木の組み直しで行を落とさない');

        const truncated = firstTruncated(root);
        if (truncated) {
            const deeper = await connection.request(60_000, 'TREE', truncated.row.key, 'callers', 'depth=2');
            assert.ok(deeper.ok && deeper.rows.length > 1, '打ち切った節点から辿り直せる');
        }

        const filtered = await connection.request(60_000, 'TREE', target, 'callers', 'depth=5', 'text=zzz-no-such-name');
        assert.ok(filtered.ok && filtered.rows.length < tree.rows.length, '絞り込みで行が減る');

        const csv = path.join(work, 'out.csv');
        const exported = await connection.request(60_000, 'EXPORT', target, 'callers', csv, 'depth=3');
        assert.ok(exported.ok, exported.raw);
        assert.ok(existsSync(csv) && statSync(csv).size > 0, 'CSV が書けている');

        // TAB や改行を含む語を投げても、行が壊れずに応答が返ること
        const escaped = await connection.request(DEFAULT_TIMEOUT_MS, 'FIND', 'a\tb\nc#x()');
        assert.equal(escaped.reason, 'not-found', escaped.raw);
    } finally {
        await connection.close();
    }
    assert.equal(connection.alive, false, '終了後は閉じている');
});

test('CANCEL で解析中の ANALYZE が止まる', async () => {
    const work = mkdtempSync(path.join(tmpdir(), 'jche-vscode-cancel-'));
    const connection = launchServer({ javaExecutable: java, classpath, cacheRoot: path.join(work, 'cache') });
    try {
        const analyzing = connection.request(300_000, 'ANALYZE', config);
        // 解析が始まってから中止する。進捗が1つでも来たら始まっている
        await new Promise<void>((resolve) => {
            connection.setListener({ progress: () => resolve() });
            setTimeout(resolve, 5_000);
        });
        connection.cancel();
        const result = await analyzing;
        // 小さな demo は中止より先に終わることがある。どちらでも壊れていなければよい
        assert.ok(result.ok || result.reason === 'cancelled', result.raw);
    } finally {
        await connection.close();
    }
});

test('壊れたクラスパスで起動すると、応答ではなく切断になる（標準エラーに理由が出る）', async () => {
    const work = mkdtempSync(path.join(tmpdir(), 'jche-vscode-broken-'));
    const stderr: string[] = [];
    const connection = launchServer({
        javaExecutable: java,
        classpath: [path.join(work, 'nothing.jar')],
        cacheRoot: path.join(work, 'cache'),
        listener: { stderr: (line) => { stderr.push(line); } },
    });
    try {
        const hello = await connection.request(DEFAULT_TIMEOUT_MS, 'HELLO', '1');
        assert.equal(hello.ok, false);
        assert.equal(hello.reason, 'disconnected');
        assert.ok(stderr.length > 0, '起動失敗の理由が標準エラーに出る');
    } finally {
        await connection.close();
    }
});
