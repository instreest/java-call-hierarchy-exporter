// esbuild で拡張本体を dist/extension.js の1ファイルに束ねる。
//
//   node esbuild.mjs          … 拡張本体（dist/extension.js）
//   node esbuild.mjs --test   … 検査（test/*.test.ts → out/test/*.test.js。node --test で走らせる）
//
// `vscode` モジュールは VSCode 本体が実行時に差し込むので external にする。
// 検査は `vscode` に触らない層（src/server, src/config）だけを対象にするため、
// 束ねた結果に `vscode` の require が残っていたら検査側の設計が崩れている。
import * as esbuild from 'esbuild';
import { readdirSync } from 'node:fs';

const forTest = process.argv.includes('--test');

if (forTest) {
    const entryPoints = readdirSync('test')
        .filter((name) => name.endsWith('.test.ts'))
        .map((name) => `test/${name}`);
    await esbuild.build({
        entryPoints,
        bundle: true,
        platform: 'node',
        format: 'cjs',
        target: 'node22',
        outdir: 'out/test',
        sourcemap: true,
        external: ['vscode'],
        logLevel: 'info',
    });
} else {
    await esbuild.build({
        entryPoints: ['src/extension.ts'],
        bundle: true,
        platform: 'node',
        format: 'cjs',
        target: 'node22',
        outfile: 'dist/extension.js',
        sourcemap: true,
        minify: false,
        external: ['vscode'],
        logLevel: 'info',
    });
}
