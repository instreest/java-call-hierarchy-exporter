// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
import { spawn } from 'node:child_process';
import { createWriteStream } from 'node:fs';
import { chmod, mkdir, readdir, rm, stat } from 'node:fs/promises';
import * as path from 'node:path';
import { pipeline } from 'node:stream/promises';
import { executableIn, MINIMUM, versionOf } from './javaLocator';
import { t } from '../messages';

/**
 * 解析に使う JDK が手元に無いときに、取ってきて展開する。
 *
 * 取得先は Adoptium（Eclipse Temurin）の API。置き場所は呼び出し側が決める
 * （拡張のグローバルストレージ）。**黙って取りに行かない**のが約束で、
 * 実行するかどうかは画面で確認してから呼ぶこと（約 200MB ある）。
 *
 * 閉域ネットワークでは当然失敗する。そのときは例外の内容をそのまま画面に出して、
 * 「JDK の場所を指定する」へ誘導する。失敗そのものは異常ではない。
 *
 * 展開は zip・tar.gz ともに `tar` コマンドに任せる（Windows 10 以降・Linux・macOS に必ずある。
 * Windows の `tar` は zip も読める）。Node の標準ライブラリに zip も tar も無いので、
 * 依存を足すよりこちらのほうが小さい。
 */

/** 取得の進み具合を画面へ伝える受け口 */
export interface DownloadProgress {
    /** @param done 受け取った量（バイト） @param total 全体の量。分からなければ -1 */
    received(done: number, total: number): void;
    /** 中止が要求されているか */
    isCancelled(): boolean;
}

const API = 'https://api.adoptium.net/v3/binary/latest/';

/**
 * 取得先の URL を組み立てる。
 *
 * @param feature  取りたい版（25 など）
 * @param platform Node の `process.platform`（`win32` / `darwin` / `linux` / `aix` …）
 * @param arch     Node の `process.arch`（`x64` / `arm64` / `ppc64` / `ia32` …）
 */
export function urlFor(feature: number, platform: string, arch: string): string {
    return `${API}${feature}/ga/${osOf(platform)}/${archOf(arch)}/jdk/hotspot/normal/eclipse`;
}

/** Adoptium の OS 名 */
export function osOf(platform: string): string {
    const lower = (platform ?? '').toLowerCase();
    if (lower.startsWith('win')) {
        return 'windows';
    }
    if (lower === 'darwin' || lower.includes('mac')) {
        return 'mac';
    }
    if (lower === 'aix') {
        return 'aix';
    }
    return 'linux';
}

/** Adoptium のアーキテクチャ名 */
export function archOf(arch: string): string {
    const lower = (arch ?? '').toLowerCase();
    if (lower === 'x64' || lower === 'amd64' || lower === 'x86_64') {
        return 'x64';
    }
    if (lower === 'arm64' || lower === 'aarch64') {
        return 'aarch64';
    }
    if (lower.includes('ppc64')) {
        return 'ppc64le';
    }
    if (lower === 'ia32' || lower === 'x86' || lower.includes('i386') || lower.includes('i586')) {
        return 'x32';
    }
    return lower === '' ? 'x64' : lower;
}

/** 取得したファイルの名前（zip か tar.gz か）を OS から決める */
export function archiveNameFor(platform: string): string {
    return osOf(platform) === 'windows' ? 'jdk.zip' : 'jdk.tar.gz';
}

/**
 * JDK を取ってきて展開し、java の実行ファイルを返す。
 *
 * @param feature   取りたい版
 * @param targetDir 展開先（空でなくてもよい。中に版ごとのフォルダを作る）
 * @param progress  進み具合の受け口
 */
export async function installJdk(feature: number, targetDir: string, progress?: DownloadProgress): Promise<string> {
    const platform = process.platform;
    const versionDir = path.join(targetDir, String(feature));
    const archive = path.join(targetDir, archiveNameFor(platform));
    await mkdir(versionDir, { recursive: true });
    await download(urlFor(feature, platform, process.arch), archive, progress);
    try {
        await extract(archive, versionDir);
    } finally {
        await rm(archive, { force: true });
    }
    const java = await findJava(versionDir);
    if (!java) {
        throw new Error(t('jdk.javaNotFound', versionDir));
    }
    if (process.platform !== 'win32') {
        await chmod(java, 0o755);
    }
    const version = versionOf(java);
    if (version < MINIMUM) {
        throw new Error(t('jdk.doesNotRun', version, java));
    }
    return java;
}

async function download(url: string, target: string, progress?: DownloadProgress): Promise<void> {
    // fetch はリダイレクトを自動で追う（Adoptium は実体の置き場所へ飛ばす）
    const response = await fetch(url, {
        headers: { 'User-Agent': 'java-call-hierarchy-exporter' },
        signal: AbortSignal.timeout(10 * 60_000),
    });
    if (!response.ok || !response.body) {
        throw new Error(t('jdk.httpFailed', response.status, url));
    }
    const total = Number.parseInt(response.headers.get('content-length') ?? '-1', 10);
    let done = 0;
    const counting = new TransformStream<Uint8Array, Uint8Array>({
        transform(chunk, controller) {
            if (progress?.isCancelled()) {
                controller.error(new Error(t('jdk.cancelled')));
                return;
            }
            done += chunk.byteLength;
            progress?.received(done, Number.isNaN(total) ? -1 : total);
            controller.enqueue(chunk);
        },
    });
    const { Readable } = await import('node:stream');
    await pipeline(Readable.fromWeb(response.body.pipeThrough(counting) as never), createWriteStream(target));
}

/** zip も tar.gz も `tar` に任せる（Windows 10 以降の tar は zip を読める） */
function extract(archive: string, targetDir: string): Promise<void> {
    return new Promise((resolve, reject) => {
        const child = spawn('tar', ['-xf', archive, '-C', targetDir], { stdio: ['ignore', 'ignore', 'pipe'] });
        let stderr = '';
        child.stderr.on('data', (chunk: Buffer) => { stderr += chunk.toString(); });
        child.on('error', (e) => reject(new Error(t('jdk.tarFailed', e.message))));
        child.on('close', (code) => {
            if (code === 0) {
                resolve();
            } else {
                reject(new Error(`${t('jdk.extractFailed', archive)}\n${stderr.trim()}`));
            }
        });
    });
}

/** 展開したフォルダの中から java を探す（配布物は中に1段フォルダを作る。macOS は Contents/Home の下） */
export async function findJava(dir: string): Promise<string | undefined> {
    const direct = executableIn(dir);
    if (await isFile(direct)) {
        return direct;
    }
    let children: string[];
    try {
        children = await readdir(dir);
    } catch {
        return undefined;
    }
    for (const child of children) {
        const full = path.join(dir, child);
        if (!(await isDirectory(full))) {
            continue;
        }
        const found = await findJava(full);
        if (found) {
            return found;
        }
    }
    return undefined;
}

async function isFile(p: string): Promise<boolean> {
    try {
        return (await stat(p)).isFile();
    } catch {
        return false;
    }
}

async function isDirectory(p: string): Promise<boolean> {
    try {
        return (await stat(p)).isDirectory();
    } catch {
        return false;
    }
}
