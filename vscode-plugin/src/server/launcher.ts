// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
import { spawn } from 'node:child_process';
import { existsSync, readdirSync, statSync } from 'node:fs';
import * as path from 'node:path';
import { ServerConnection, type ConnectionListener } from './connection';

/**
 * 解析サーバーを子プロセスとして起動する。
 *
 * 起動する JVM も、使う JDT も、VSCode のものではない。拡張に同梱した
 * `lib/jche-core.jar` と `lib/jdt/*.jar` を渡し、利用者が選んだ JDK で走らせる
 * （docs/out-of-process-analysis-design.md §5）。
 *
 * 標準エラーは飲み込まない。子プロセスが起動に失敗したときの理由（クラスパスの誤り、
 * JDK の版違いなど）はそこにしか出ないので、リスナ経由で出力チャネルへ流す。
 */
export interface LaunchOptions {
    /** 解析に使う JDK の java（17 以上。既定は 25） */
    readonly javaExecutable: string;
    /** `lib/jche-core.jar` と `lib/jdt/*.jar` */
    readonly classpath: readonly string[];
    /** キャッシュの置き場所（拡張のストレージ） */
    readonly cacheRoot: string;
    /** 追加の JVM 引数（`-Xmx2g` など） */
    readonly vmArguments?: readonly string[];
    /** 作業ディレクトリ。無ければ継承する */
    readonly workingDir?: string;
    readonly listener?: ConnectionListener;
}

export function launchServer(options: LaunchOptions): ServerConnection {
    if (!existsSync(options.javaExecutable) || !statSync(options.javaExecutable).isFile()) {
        throw new Error(`解析に使う java が見つかりません: ${options.javaExecutable}`);
    }
    if (options.classpath.length === 0) {
        throw new Error('解析本体（lib/）が見つかりません');
    }
    const args = [
        // 子プロセスの入出力は UTF-8 で固定する。これを外すと環境ごとに文字化けする
        '-Dfile.encoding=UTF-8',
        '-Dstdout.encoding=UTF-8',
        '-Dstderr.encoding=UTF-8',
        ...(options.vmArguments ?? []),
        '-cp',
        options.classpath.map((p) => path.resolve(p)).join(path.delimiter),
        'jche.CallHierarchyExporter',
        '--server',
        path.resolve(options.cacheRoot),
    ];
    const child = spawn(options.javaExecutable, args, {
        cwd: options.workingDir,
        stdio: ['pipe', 'pipe', 'pipe'],
        windowsHide: true,
    });
    return new ServerConnection(child, options.listener);
}

/**
 * 同梱の `lib/` からクラスパスを組む（`jche-core.jar` ＋ `jdt/*.jar`）。
 * 無ければ空を返す（呼び出し側がその旨を画面に出す）。
 */
export function bundledClasspath(libDir: string): string[] {
    const core = path.join(libDir, 'jche-core.jar');
    const jdtDir = path.join(libDir, 'jdt');
    if (!existsSync(core) || !existsSync(jdtDir)) {
        return [];
    }
    const jars = readdirSync(jdtDir).filter((name) => name.endsWith('.jar')).sort()
        .map((name) => path.join(jdtDir, name));
    return [core, ...jars];
}
