// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
import type { ChildProcess } from 'node:child_process';
import { createInterface } from 'node:readline';
import { parseRow, ServerResponse, type ServerRow } from './response';
import { join, SEP, unescape } from './wire';
import { t } from '../messages';

/** 進捗とログの受け口。画面（進捗表示と出力チャネル）へ橋渡しする */
export interface ConnectionListener {
    progress?(label: string, done: number, total: number): void;
    log?(line: string): void;
    /** 子プロセスの標準エラー（起動失敗の理由はここにしか出ない） */
    stderr?(line: string): void;
}

/** 応答を待つ既定の上限。解析は長いので ANALYZE では別の値を渡す */
export const DEFAULT_TIMEOUT_MS = 30_000;

/**
 * 解析サーバー（子プロセス）との1本の接続。
 *
 * 拡張が解析に触れるのはこのクラス越しだけである。解析のコードも JDT も子プロセス側にあるので、
 * 拡張ホストには何も要らない。
 *
 * **約束**
 * - 受信は行単位。`#P`（進捗）と `#L`（ログ）はその場でリスナへ渡し、それ以外は応答の待ち行列へ積む
 * - `request` は1件ずつ直列に流す（前の要求の応答が返るまで次を送らない）。
 *   送信そのものは直列化しないので、解析中でも `cancel()` を割り込ませられる
 */
export class ServerConnection {
    private readonly pending: string[] = [];
    private waiter: ((line: string) => void) | undefined;
    private queue: Promise<unknown> = Promise.resolve();
    private closed = false;
    private exitCode: number | null = null;

    constructor(
        private readonly process: ChildProcess,
        private listener: ConnectionListener = {},
    ) {
        if (!process.stdout || !process.stdin) {
            throw new Error(t('server.noStdio'));
        }
        process.stdout.setEncoding('utf8');
        createInterface({ input: process.stdout, crlfDelay: Infinity }).on('line', (line) => this.onLine(line));
        if (process.stderr) {
            process.stderr.setEncoding('utf8');
            createInterface({ input: process.stderr, crlfDelay: Infinity })
                .on('line', (line) => this.listener.stderr?.(line));
        }
        process.on('exit', (code) => {
            this.exitCode = code;
            this.closed = true;
            this.deliver(`NG${SEP}disconnected`);
        });
        process.on('error', () => {
            this.closed = true;
            this.deliver(`NG${SEP}disconnected`);
        });
    }

    setListener(listener: ConnectionListener): void {
        this.listener = listener;
    }

    private onLine(line: string): void {
        if (line.startsWith('#P')) {
            const parts = line.split(SEP);
            if (parts.length >= 4) {
                const done = Number.parseInt(parts[2], 10);
                const total = Number.parseInt(parts[3], 10);
                if (!Number.isNaN(done) && !Number.isNaN(total)) {
                    this.listener.progress?.(unescape(parts[1]), done, total);
                }
            }
        } else if (line.startsWith('#L')) {
            const parts = line.split(SEP);
            this.listener.log?.(parts.length > 1 ? unescape(parts[1]) : '');
        } else if (line !== '') {
            this.deliver(line);
        }
    }

    private deliver(line: string): void {
        if (this.waiter) {
            const w = this.waiter;
            this.waiter = undefined;
            w(line);
        } else {
            this.pending.push(line);
        }
    }

    private nextLine(timeoutMs: number): Promise<string> {
        const queued = this.pending.shift();
        if (queued !== undefined) {
            return Promise.resolve(queued);
        }
        return new Promise((resolve, reject) => {
            const timer = setTimeout(() => {
                this.waiter = undefined;
                reject(new Error(t('server.timeout', timeoutMs)));
            }, timeoutMs);
            this.waiter = (line) => {
                clearTimeout(timer);
                resolve(line);
            };
        });
    }

    /** 要求を1件送り、応答（と、それまでに来た行）を待つ。要求は到着順に直列で処理する */
    request(timeoutMs: number, ...words: string[]): Promise<ServerResponse> {
        const run = async (): Promise<ServerResponse> => {
            this.writeLine(join(words));
            const rows: ServerRow[] = [];
            for (;;) {
                const line = await this.nextLine(timeoutMs);
                if (line.startsWith(`R${SEP}`)) {
                    const row = parseRow(line.split(SEP));
                    if (row) {
                        rows.push(row);
                    }
                    continue;
                }
                return ServerResponse.of(line, rows);
            }
        };
        // 前の要求が失敗しても次は流す（失敗は各自の Promise で受ける）
        const result = this.queue.then(run, run);
        this.queue = result.catch(() => undefined);
        return result;
    }

    /** 実行中の解析を止める。応答を待っている最中でも呼べる */
    cancel(): void {
        try {
            this.writeLine('CANCEL');
        } catch {
            // 既に閉じている
        }
    }

    private writeLine(line: string): void {
        if (this.closed || !this.process.stdin || this.process.stdin.destroyed) {
            throw new Error(t('server.closed'));
        }
        this.process.stdin.write(line + '\n');
    }

    get alive(): boolean {
        return !this.closed && this.process.exitCode === null;
    }

    /** 終了コード。まだ動いていれば null */
    get exit(): number | null {
        return this.exitCode;
    }

    /**
     * 行儀よく終わらせる。応じなければ止める。
     *
     * SHUTDOWN は「積んだ要求を処理し終えてから終わる」という意味なので、解析中に送っても
     * すぐには効かない。先に CANCEL を送って実行中の解析を止めてから SHUTDOWN を送る。
     */
    async close(): Promise<void> {
        if (this.closed) {
            return;
        }
        this.cancel();
        try {
            this.writeLine('SHUTDOWN');
        } catch {
            // 既に閉じている
        }
        const exited = await this.waitExit(3_000);
        if (!exited) {
            this.process.kill();
            if (!(await this.waitExit(2_000))) {
                this.process.kill('SIGKILL');
            }
        }
        this.closed = true;
    }

    private waitExit(ms: number): Promise<boolean> {
        if (this.process.exitCode !== null) {
            return Promise.resolve(true);
        }
        return new Promise((resolve) => {
            const timer = setTimeout(() => resolve(false), ms);
            this.process.once('exit', () => {
                clearTimeout(timer);
                resolve(true);
            });
        });
    }
}
