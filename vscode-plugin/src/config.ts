// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
import { existsSync, readdirSync, statSync } from 'node:fs';
import { mkdir, writeFile } from 'node:fs/promises';
import * as path from 'node:path';

/**
 * 解析に使う設定（`config.properties`）をどう用意するか。
 *
 * 優先順位は次のとおりで、上から順に当てはまった1つを使う（docs/vscode-plugin-design.md §3）。
 * 1. 利用者が設定（`jche.configFile`）で明示したファイル
 * 2. ワークスペースフォルダ直下の `config.properties`（複数あれば呼び出し側が選ばせる）
 * 3. どれも無ければ自動生成 … **`project.root` だけ**書いた最小の設定
 *
 * 自動生成で `source.folders` や `library.folders` を書かないのは意図的である。
 * 空欄なら本体の `ProjectDetector` が `project.root` の中身（`pom.xml` / `build.gradle` /
 * フォルダ構成）から決めるので、こちらで推測し直す必要が無い。Eclipse 版はプロジェクトの
 * クラスパスを持っているのでそれを翻訳しているが、VSCode は持っていない（vscode-java に
 * 頼れば取れるが、公開 API でないコマンドに寄りかかることになる）。
 *
 * このモジュールは `vscode` を使わない。VSCode 無しで検査できるようにするため。
 */

export type ConfigSource =
    | { readonly kind: 'file'; readonly file: string }
    | { readonly kind: 'generated'; readonly projectRoot: string };

/** 画面に出す説明。「どの設定で解析したか」が分からないまま結果だけ見せない */
export function labelOf(source: ConfigSource, workspaceRoot: string): string {
    return source.kind === 'file'
        ? path.relative(workspaceRoot, source.file) || path.basename(source.file)
        : '自動生成（project.root だけ。残りはプロジェクトの中身から決める）';
}

/**
 * フォルダ直下にある設定ファイルの候補。`config.properties` を先頭に、
 * ほかの `*.properties` は名前順（設定ファイルをプロジェクトごとに増やす使い方があるため）。
 * `launcher.properties` は起動コマンドの設定なので除く。
 */
export function findConfigFiles(folder: string): string[] {
    if (!existsSync(folder) || !statSync(folder).isDirectory()) {
        return [];
    }
    const names = readdirSync(folder)
        .filter((name) => name.endsWith('.properties') && name !== 'launcher.properties')
        .filter((name) => statSync(path.join(folder, name)).isFile())
        .sort((a, b) => {
            if (a === 'config.properties') return -1;
            if (b === 'config.properties') return 1;
            return a.localeCompare(b);
        });
    return names.map((name) => path.join(folder, name));
}

/**
 * 設定の出どころを決める。
 *
 * @param workspaceRoot ワークスペースフォルダ
 * @param explicit      設定 `jche.configFile`（相対ならワークスペースフォルダ起点）。空なら無し
 * @param remembered    前回このフォルダで選んだ設定ファイル。無ければ undefined
 * @returns 決まった出どころ。候補が複数あって決められないときは `choices` に候補を返す
 */
export function resolveConfigSource(
    workspaceRoot: string,
    explicit: string | undefined,
    remembered: string | undefined,
): { readonly source?: ConfigSource; readonly choices?: string[]; readonly error?: string } {
    if (explicit && explicit.trim() !== '') {
        const file = path.resolve(workspaceRoot, explicit.trim());
        if (!existsSync(file) || !statSync(file).isFile()) {
            return { error: `設定 jche.configFile のファイルがありません: ${file}` };
        }
        return { source: { kind: 'file', file } };
    }
    const candidates = findConfigFiles(workspaceRoot);
    if (candidates.length === 0) {
        return { source: { kind: 'generated', projectRoot: workspaceRoot } };
    }
    if (candidates.length === 1) {
        return { source: { kind: 'file', file: candidates[0] } };
    }
    if (remembered && candidates.includes(remembered)) {
        return { source: { kind: 'file', file: remembered } };
    }
    return { choices: candidates };
}

/**
 * 自動生成する設定の中身。`project.root` は絶対パスで書く（生成ファイルは拡張のストレージに
 * 置くので、相対にするとそこが起点になってしまう）。
 */
export function generatedConfigText(projectRoot: string): string {
    return [
        '# Call Hierarchy Exporter（VSCode）が自動生成した設定です。',
        '# project.root だけを指定し、ソースフォルダ・依存 jar・文字コードはプロジェクトの中身から決めます',
        '# （pom.xml / build.gradle / フォルダ構成。docs/build-tool-classpath.md）。',
        '# 細かく効かせたいときは「設定を config.properties に保存」でワークスペースに書き出して直してください。',
        `project.root=${escapeProperty(path.resolve(projectRoot))}`,
        '',
    ].join('\n');
}

/** ワークスペースへ書き出すときの中身。こちらは `project.root=.` で、項目の説明を付ける */
export function savedConfigText(): string {
    return [
        '# Call Hierarchy Exporter（VSCode）が書き出した設定です。',
        '# 各項目の意味は、ツール同梱の config.properties のコメントを参照してください。',
        '# このファイルがあると、拡張は自動生成ではなくこちらを使います。',
        'project.root=.',
        '# 空欄ならプロジェクトの中身（pom.xml / build.gradle / フォルダ構成）から決めます。',
        'source.folders=',
        'library.folders=',
        'source.encoding=',
        'source.level=',
        '# 起点を絞るときは entry.packages を、除外するときは exclude.packages を書きます。',
        'entry.packages=',
        'exclude.packages=',
        '',
    ].join('\n');
}

/**
 * 子プロセスへ渡すための設定ファイルのパスを返す。
 * 自動生成のときは、渡されたフォルダに書き出す（次の解析でも同じ場所を使い回す）。
 *
 * @param scratchDir 一時ファイルの置き場所（拡張のストレージの下）
 */
export async function materialize(source: ConfigSource, scratchDir: string): Promise<string> {
    if (source.kind === 'file') {
        return source.file;
    }
    await mkdir(scratchDir, { recursive: true });
    const generated = path.join(scratchDir, 'generated-config.properties');
    // Config は UTF-8 で読むので、パスに日本語が入っても壊れない
    await writeFile(generated, generatedConfigText(source.projectRoot), 'utf8');
    return generated;
}

/** properties の値として書くときに、区切りと誤読される文字を逃がす（バックスラッシュと先頭の空白） */
function escapeProperty(value: string): string {
    return value.replace(/\\/g, '\\\\').replace(/^ /, '\\ ');
}
