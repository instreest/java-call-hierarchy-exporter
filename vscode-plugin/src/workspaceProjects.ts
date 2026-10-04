// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
import { existsSync, readFileSync, statSync } from 'node:fs';
import { readFile, writeFile } from 'node:fs/promises';
import * as path from 'node:path';

/**
 * 設定ファイルの `workspace.projects`（一緒に解析するワークスペースの他のプロジェクト）の読み書き。
 *
 * 解析が読むのは設定ファイルの `workspace.projects` だけで、画面の状態で解析の範囲を変えることはしない
 * （正本は設定ファイル 1 つ。docs/workspace-callers-design.md の Q11）。拡張がするのは、ワークスペースの
 * 他のフォルダを選ばせてこの項目に書き足すことと、書いてある項目を読んで画面（状態・⚠・カーソルの扱い）に
 * 使うことだけである。Eclipse 版の `WorkspaceProjectsConfig` と同じ読み方・書き方。
 *
 * 読み方は本体（`jche.config.ConfigFile`）に合わせる: 1 行に「項目=値」、バックスラッシュはそのまま、
 * 値の続きは行末の `\`（次の行が「項目=」なら捨てる）か字下げ、続きの途中の `#` の行は読み飛ばす。
 * 書くときは、既にある `workspace.projects` の論理行（続きの行も）を 1 行に差し替え、無ければ末尾に足す。
 *
 * このモジュールは `vscode` を使わない。VSCode 無しで検査できるようにするため。
 */

export const WORKSPACE_PROJECTS_KEY = 'workspace.projects';

/** 「項目=値」の行か（本体の読み手と同じ。先頭の空白は許す。字下げしていても項目の行は新しい項目） */
const KEY_LINE = /^\s*[A-Za-z][A-Za-z0-9._-]*\s*=.*$/;

/** 「項目=値」の行の項目。それ以外（注釈・空行・続きの行）なら undefined。先頭の行の BOM は外す */
function keyOf(line: string, first: boolean): string | undefined {
    const s = first ? line.replace(/^\uFEFF/, '') : line;
    if (!KEY_LINE.test(s)) {
        return undefined;
    }
    const trimmed = s.trim();
    return trimmed.substring(0, trimmed.indexOf('=')).trim();
}

function endsWithBackslash(line: string): boolean {
    return line.trim().endsWith('\\');
}

/**
 * その項目の論理行（先頭の行の位置と、終わりの次の行の位置）。無ければ undefined。
 * 本体の読み手（`jche.config.ConfigFile`）と同じ規則: 論理行は「項目=」の行から始まり、「項目=」の形でない行が
 * 行末の `\`（の後）か字下げで続く。途中の注釈（`#` / `!`）は読み飛ばし、空行は `\` の続きの途中なら読み飛ばし、
 * そうでなければ終わり。終わりの位置は最後の続きの行の次（後ろの注釈・空行は含めない。書き換えで消さないため）
 */
function logicalLineOf(lines: readonly string[], key: string): [number, number] | undefined {
    for (let i = 0; i < lines.length; i++) {
        if (keyOf(lines[i], i === 0) !== key) {
            continue;
        }
        let end = i + 1;
        let continued = endsWithBackslash(lines[i]);
        for (let probe = i + 1; probe < lines.length; probe++) {
            const next = lines[probe];
            const trimmed = next.trim();
            if (trimmed.startsWith('#') || trimmed.startsWith('!')) {
                continue;
            }
            if (trimmed === '') {
                if (continued) {
                    continue;
                }
                break;
            }
            const indented = /^\s/.test(next);
            if (!KEY_LINE.test(next) && (continued || indented)) {
                continued = endsWithBackslash(next);
                end = probe + 1;
                continue;
            }
            break;
        }
        return [i, end];
    }
    return undefined;
}

/** 行の列から、その項目の値（続きの行をつないだもの。注釈は飛ばし、行末の `\` とその前後の空白は外す）。無ければ undefined */
export function valueOf(lines: readonly string[], key: string): string | undefined {
    const range = logicalLineOf(lines, key);
    if (!range) {
        return undefined;
    }
    let value = '';
    for (let i = range[0]; i < range[1]; i++) {
        const trimmed = lines[i].trim();
        if (trimmed === '' || trimmed.startsWith('#') || trimmed.startsWith('!')) {
            continue;
        }
        let part = i === range[0] ? trimmed.substring(trimmed.indexOf('=') + 1).trim() : trimmed;
        if (part.endsWith('\\')) {
            part = part.substring(0, part.length - 1).trim();
        }
        value += part;
    }
    return value;
}

/** 行の列の、その項目の論理行を「項目=値」の 1 行に差し替えたもの（無ければ末尾に足す）。ほかの行はそのまま */
export function replaced(lines: readonly string[], key: string, value: string): string[] {
    const out = [...lines];
    const newLine = `${key}=${value}`;
    const range = logicalLineOf(out, key);
    if (!range) {
        if (out.length > 0 && out[out.length - 1].trim() !== '') {
            out.push('');
        }
        out.push(newLine);
    } else {
        out.splice(range[0], range[1] - range[0], newLine);
    }
    return out;
}

export function splitList(raw: string): string[] {
    return raw.split(',').map((s) => s.trim()).filter((s) => s !== '');
}

/** 設定ファイルの `workspace.projects` の値（カンマで分けた生の文字列）。無ければ空 */
export async function readWorkspaceProjects(configFile: string): Promise<string[]> {
    const text = await readFile(configFile, 'utf8');
    const value = valueOf(text.split(/\r?\n/), WORKSPACE_PROJECTS_KEY);
    return value === undefined ? [] : splitList(value);
}

/**
 * 件が指すプロジェクトのフォルダ（絶対パス）。件は設定ファイルのフォルダからの相対パスか絶対パスで、
 * プロジェクトのフォルダか、そのプロジェクトの設定ファイル（`project.root` をそこから読む）。分からなければ undefined
 */
export function rootOfEntry(configFile: string, raw: string): string | undefined {
    const resolved = path.resolve(path.dirname(configFile), raw.trim());
    if (existsSync(resolved) && statSync(resolved).isFile()) {
        try {
            const root = valueOf(readFileSync(resolved, 'utf8').split(/\r?\n/), 'project.root');
            if (root === undefined || root.trim() === '') {
                return undefined;
            }
            return path.resolve(path.dirname(resolved), root.trim());
        } catch {
            return undefined;
        }
    }
    return resolved;
}

/**
 * 設定ファイルに書く 1 件。設定ファイルのフォルダからの相対パス（区切りは `/`。`../app-batch`）。
 * 相対にできない（別のドライブ）ときは絶対パス
 */
export function entryFor(configFile: string, folder: string): string {
    const configDir = path.dirname(path.resolve(configFile));
    const target = path.resolve(folder);
    const relative = path.relative(configDir, target);
    if (relative === '') {
        return '.';
    }
    if (path.isAbsolute(relative)) {
        return target;    // 別のドライブ（Windows）。relative は絶対パスを返す
    }
    return relative.split(path.sep).join('/');
}

/** `workspace.projects` を書き換える（無ければ末尾に足す）。ほかの行はそのまま */
export async function writeWorkspaceProjects(configFile: string, entries: readonly string[]): Promise<void> {
    const text = await readFile(configFile, 'utf8');
    const lines = replaced(text.split(/\r?\n/), WORKSPACE_PROJECTS_KEY, entries.join(','));
    // 元のファイルが改行で終わっていれば（split の末尾が空）そのまま、終わっていなければ足す
    const joined = lines.join('\n');
    await writeFile(configFile, joined.endsWith('\n') ? joined : `${joined}\n`, 'utf8');
}
