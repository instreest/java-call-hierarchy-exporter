// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
import { existsSync } from 'node:fs';
import { mkdir, writeFile } from 'node:fs/promises';
import * as path from 'node:path';
import * as vscode from 'vscode';
import { labelOf, materialize, resolveConfigSource, savedConfigText, type ConfigSource } from './config';
import type { Direction } from './labels';
import { DEFAULT_TIMEOUT_MS, type ServerConnection } from './server/connection';
import { chooseJava, type FoundJava } from './server/javaLocator';
import { bundledClasspath, launchServer } from './server/launcher';
import type { ServerResponse } from './server/response';

/** 解析の状態。画面（Language Status Item・ビュー）はこれを見て描く */
export type SessionState =
    | { readonly kind: 'unanalyzed' }
    | { readonly kind: 'analyzing'; readonly label: string; readonly done: number; readonly total: number }
    | { readonly kind: 'analyzed'; readonly at: Date; readonly methods: number; readonly edges: number; readonly configLabel: string }
    | { readonly kind: 'failed'; readonly reason: string };

/** ワークスペースフォルダ1つぶんの解析セッション。子プロセスを1つ持つ */
export class Session implements vscode.Disposable {
    private connection: ServerConnection | undefined;
    private idleTimer: NodeJS.Timeout | undefined;
    private analyzing = false;
    private _state: SessionState = { kind: 'unanalyzed' };
    private readonly stateEmitter = new vscode.EventEmitter<SessionState>();
    readonly onDidChangeState = this.stateEmitter.event;

    constructor(
        readonly folder: vscode.WorkspaceFolder,
        private readonly context: vscode.ExtensionContext,
        private readonly log: vscode.LogOutputChannel,
    ) {}

    get state(): SessionState {
        return this._state;
    }

    get isAnalyzing(): boolean {
        return this.analyzing;
    }

    private setState(state: SessionState): void {
        this._state = state;
        this.stateEmitter.fire(state);
    }

    // ------------------------------------------------------------
    // 設定ファイル
    // ------------------------------------------------------------

    private rememberedKey(): string {
        return `configFile:${this.folder.uri.toString()}`;
    }

    /** 解析に使う設定の出どころ。候補が複数あれば選ばせる。決まらなければ undefined */
    async configSource(): Promise<ConfigSource | undefined> {
        const explicit = vscode.workspace.getConfiguration('jche', this.folder.uri).get<string>('configFile', '');
        const remembered = this.context.workspaceState.get<string>(this.rememberedKey());
        const decided = resolveConfigSource(this.folder.uri.fsPath, explicit, remembered);
        if (decided.error) {
            vscode.window.showErrorMessage(decided.error);
            return undefined;
        }
        if (decided.source) {
            return decided.source;
        }
        return this.pickConfig(decided.choices ?? []);
    }

    /** 設定ファイルを選ばせて覚える */
    async pickConfig(choices?: string[]): Promise<ConfigSource | undefined> {
        const candidates = choices ?? resolveConfigSource(this.folder.uri.fsPath, undefined, undefined).choices ?? [];
        if (candidates.length === 0) {
            vscode.window.showInformationMessage(
                `${this.folder.name} に設定ファイル（*.properties）はありません。project.root だけの設定を自動生成して解析します。`);
            return { kind: 'generated', projectRoot: this.folder.uri.fsPath };
        }
        const picked = await vscode.window.showQuickPick(
            candidates.map((file) => ({ label: path.relative(this.folder.uri.fsPath, file), file })),
            { placeHolder: `${this.folder.name} の解析に使う設定ファイル` });
        if (!picked) {
            return undefined;
        }
        await this.context.workspaceState.update(this.rememberedKey(), picked.file);
        return { kind: 'file', file: picked.file };
    }

    /** 自動生成の内容をワークスペースへ書き出す（細かく直したい人のため） */
    async saveConfig(): Promise<void> {
        const target = path.join(this.folder.uri.fsPath, 'config.properties');
        if (existsSync(target)) {
            const answer = await vscode.window.showWarningMessage(
                `${target} は既にあります。上書きしますか？`, { modal: true }, '上書きする');
            if (answer !== '上書きする') {
                return;
            }
        }
        await writeFile(target, savedConfigText(), 'utf8');
        await vscode.window.showTextDocument(vscode.Uri.file(target));
    }

    // ------------------------------------------------------------
    // 子プロセス
    // ------------------------------------------------------------

    private settings() {
        return vscode.workspace.getConfiguration('jche', this.folder.uri);
    }

    /** 解析に使う JDK。設定 → JAVA_HOME → PATH の順（docs/vscode-plugin-design.md §8） */
    private findJava(): FoundJava | undefined {
        const configured = this.settings().get<string>('javaHome', '').trim();
        const candidates: (string | undefined)[] = [
            configured !== '' ? configured : undefined,
            process.env.JAVA_HOME,
            ...(process.env.PATH ?? '').split(path.delimiter)
                .filter((dir) => dir !== '')
                .map((dir) => path.join(dir, process.platform === 'win32' ? 'java.exe' : 'java')),
        ];
        return chooseJava(candidates);
    }

    private classpath(): string[] {
        const configured = this.settings().get<string>('libFolder', '').trim();
        const libDir = configured !== '' ? configured : path.join(this.context.extensionPath, 'lib');
        return bundledClasspath(libDir);
    }

    private async ensureConnection(): Promise<ServerConnection> {
        if (this.connection?.alive) {
            return this.connection;
        }
        const java = this.findJava();
        if (!java) {
            throw new Error('解析に使う JDK（17 以上）が見つかりません。設定 jche.javaHome に JDK のフォルダを指定してください。');
        }
        const classpath = this.classpath();
        if (classpath.length === 0) {
            throw new Error('解析本体（lib/jche-core.jar と lib/jdt/*.jar）が見つかりません。設定 jche.libFolder を確認してください。');
        }
        const storage = this.context.storageUri?.fsPath ?? path.join(this.context.globalStorageUri.fsPath, 'ws');
        const cacheRoot = path.join(storage, 'cache');
        await mkdir(cacheRoot, { recursive: true });
        this.log.info(`解析サーバーを起動します: ${java.executable}（Java ${java.version}）`);
        if (java.olderThanPreferred) {
            this.log.warn(`Java ${java.version} で解析します。CLI（Java 25）と結果が少しずれることがあります。`);
        }
        const connection = launchServer({
            javaExecutable: java.executable,
            classpath,
            cacheRoot,
            vmArguments: this.settings().get<string[]>('vmArguments', []),
            workingDir: this.folder.uri.fsPath,
            listener: {
                log: (line) => this.log.info(line),
                stderr: (line) => this.log.error(line),
                progress: (label, done, total) => {
                    if (this.analyzing) {
                        this.setState({ kind: 'analyzing', label, done, total });
                    }
                },
            },
        });
        const hello = await connection.request(DEFAULT_TIMEOUT_MS, 'HELLO', '1');
        if (!hello.ok) {
            await connection.close();
            throw new Error(`解析サーバーが起動できません（${hello.reason}）。ログを確認してください。`);
        }
        this.log.info(`解析サーバー: protocol=${hello.field('protocol')} jdt=${hello.field('jdt')} jvm=${hello.field('jvm')} maxJava=${hello.field('maxJava')}`);
        this.connection = connection;
        this.touch();
        return connection;
    }

    /** アイドル終了のタイマーを張り直す */
    private touch(): void {
        if (this.idleTimer) {
            clearTimeout(this.idleTimer);
            this.idleTimer = undefined;
        }
        const minutes = this.settings().get<number>('idleMinutes', 10);
        if (minutes > 0) {
            this.idleTimer = setTimeout(() => void this.shutdown('アイドル'), minutes * 60_000);
        }
    }

    private async request(timeoutMs: number, ...words: string[]): Promise<ServerResponse> {
        const connection = await this.ensureConnection();
        const response = await connection.request(timeoutMs, ...words);
        this.touch();
        if (!response.ok && response.reason === 'disconnected') {
            this.log.error('解析サーバーとの接続が切れました。次の要求で作り直します。');
            this.connection = undefined;
            this.setState({ kind: 'failed', reason: '解析サーバーが終了しました' });
        }
        return response;
    }

    /** 子プロセスを止める。結果はキャッシュに残っているので、次は速い */
    async shutdown(why: string): Promise<void> {
        if (this.idleTimer) {
            clearTimeout(this.idleTimer);
            this.idleTimer = undefined;
        }
        const connection = this.connection;
        this.connection = undefined;
        if (connection) {
            this.log.info(`解析サーバーを終了します（${why}）`);
            await connection.close();
        }
        if (this._state.kind !== 'unanalyzed') {
            this.setState({ kind: 'unanalyzed' });
        }
    }

    // ------------------------------------------------------------
    // 要求
    // ------------------------------------------------------------

    /** 解析する。進捗は状態として流す。中止は token で */
    async analyze(token?: vscode.CancellationToken): Promise<boolean> {
        if (this.analyzing) {
            return false;
        }
        const source = await this.configSource();
        if (!source) {
            return false;
        }
        this.analyzing = true;
        await vscode.commands.executeCommand('setContext', 'jche.analyzing', true);
        this.setState({ kind: 'analyzing', label: '準備', done: 0, total: 0 });
        const cancelListener = token?.onCancellationRequested(() => this.connection?.cancel());
        try {
            const storage = this.context.storageUri?.fsPath ?? path.join(this.context.globalStorageUri.fsPath, 'ws');
            const configPath = await materialize(source, path.join(storage, 'config'));
            this.log.info(`解析します: ${this.folder.name}（設定: ${labelOf(source, this.folder.uri.fsPath)}）`);
            const response = await this.request(24 * 60 * 60_000, 'ANALYZE', configPath);
            if (!response.ok) {
                const reason = response.reason === 'cancelled' ? '中止しました' : response.reason;
                this.setState({ kind: 'failed', reason });
                this.log.warn(`解析: ${reason}`);
                return false;
            }
            this.setState({
                kind: 'analyzed',
                at: new Date(),
                methods: response.numberField('methods', 0),
                edges: response.numberField('edges', 0),
                configLabel: labelOf(source, this.folder.uri.fsPath),
            });
            this.log.info(`解析が終わりました: methods=${response.field('methods')} edges=${response.field('edges')}`);
            return true;
        } catch (e) {
            const reason = e instanceof Error ? e.message : String(e);
            this.setState({ kind: 'failed', reason });
            this.log.error(reason);
            return false;
        } finally {
            cancelListener?.dispose();
            this.analyzing = false;
            await vscode.commands.executeCommand('setContext', 'jche.analyzing', false);
        }
    }

    cancel(): void {
        this.connection?.cancel();
    }

    get isAnalyzed(): boolean {
        return this._state.kind === 'analyzed' && this.connection?.alive === true;
    }

    /** カーソル位置（ファイルと 1 始まりの行）を囲むメソッド */
    at(file: string, line: number): Promise<ServerResponse> {
        return this.request(DEFAULT_TIMEOUT_MS, 'AT', file, String(line));
    }

    find(key: string): Promise<ServerResponse> {
        return this.request(DEFAULT_TIMEOUT_MS, 'FIND', key);
    }

    tree(key: string, direction: Direction, depth: number): Promise<ServerResponse> {
        return this.request(120_000, 'TREE', key, direction, `depth=${depth}`);
    }

    export(key: string, direction: Direction, output: string, depth: number): Promise<ServerResponse> {
        return this.request(600_000, 'EXPORT', key, direction, output, `depth=${depth}`);
    }

    dispose(): void {
        void this.shutdown('拡張の終了');
        this.stateEmitter.dispose();
    }
}
