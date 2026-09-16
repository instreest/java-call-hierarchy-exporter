// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
import { spawnSync } from 'node:child_process';
import { existsSync, statSync } from 'node:fs';
import * as path from 'node:path';

/**
 * 解析に使う JDK を探す。
 *
 * 解析は VSCode とは別のプロセス・別の JDK で走らせる。どの JDK を使うかで解析結果が変わる
 * （JDT は動作中の JVM の標準クラスを解析対象のクラスパスに含める）ので、
 * **CLI と同じ 25 を優先し、無ければ 17 以上で一番新しいもの**を選ぶ。
 *
 * このモジュールは `vscode` を使わない。設定値や環境変数の「読み方」は呼び出し側が決め、
 * ここは渡された候補を調べて選ぶだけにしてある（VSCode 無しで検査できるようにするため）。
 * Eclipse 版の `jche.eclipse.server.JavaLocator` と同じ規則で、`test/vscode/run.sh` が
 * 同じ期待値で検査する。
 */

/** 解析に使いたい版。CLI（//JAVA 25）と結果を揃えるため */
export const PREFERRED = 25;
/** 動かせる下限。解析本体は release 17 でコンパイルしている */
export const MINIMUM = 17;

/** 選んだ java と、その版 */
export interface FoundJava {
    readonly executable: string;
    readonly version: number;
    /** 望みの版（25）より古いか。画面に「結果が CLI と少しずれうる」と出すために使う */
    readonly olderThanPreferred: boolean;
}

export function isWindows(): boolean {
    return process.platform === 'win32';
}

/** JDK のホームから java の実行ファイル */
export function executableIn(javaHome: string): string {
    return path.join(javaHome, 'bin', isWindows() ? 'java.exe' : 'java');
}

/**
 * 候補の中から使えるものを選ぶ。
 *
 * @param candidates JDK のホーム、または java の実行ファイル。前にあるものほど優先する
 * @returns 選んだもの。17 以上が1つも無ければ undefined
 */
export function chooseJava(candidates: readonly (string | undefined)[]): FoundJava | undefined {
    let best: FoundJava | undefined;
    for (const candidate of normalize(candidates)) {
        const version = versionOf(candidate);
        if (version < MINIMUM) {
            continue;
        }
        const found: FoundJava = { executable: candidate, version, olderThanPreferred: version < PREFERRED };
        if (version >= PREFERRED) {
            return found;   // 望みどおりなら即決
        }
        if (!best || version > best.version) {
            best = found;
        }
    }
    return best;
}

/** ホームを渡されたら bin/java に直す。重複は取り除く（同じものを何度も起動しないため） */
function normalize(candidates: readonly (string | undefined)[]): string[] {
    const seen = new Set<string>();
    const result: string[] = [];
    for (const candidate of candidates) {
        if (!candidate || !existsSync(candidate)) {
            continue;
        }
        const executable = statSync(candidate).isDirectory() ? executableIn(candidate) : candidate;
        if (!existsSync(executable) || !statSync(executable).isFile()) {
            continue;
        }
        const absolute = path.resolve(executable);
        if (!seen.has(absolute)) {
            seen.add(absolute);
            result.push(absolute);
        }
    }
    return result;
}

/** その java の主要バージョン。動かせなければ 0 */
export function versionOf(executable: string): number {
    try {
        const result = spawnSync(executable, ['-version'], { encoding: 'utf8', timeout: 20_000 });
        if (result.error) {
            return 0;
        }
        return parseVersion((result.stderr ?? '') + '\n' + (result.stdout ?? ''));
    } catch {
        return 0;
    }
}

/** `openjdk version "25.0.3"` のような出力から 25 を取り出す。1.8 形式は 8 にする */
export function parseVersion(text: string): number {
    const match = /version "(\d+)(?:\.(\d+))?/.exec(text);
    if (!match) {
        return 0;
    }
    const major = Number.parseInt(match[1], 10);
    if (major === 1 && match[2] !== undefined) {
        return Number.parseInt(match[2], 10);
    }
    return major;
}
