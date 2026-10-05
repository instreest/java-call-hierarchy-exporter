// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
import { existsSync } from 'node:fs';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import * as path from 'node:path';
import * as vscode from 'vscode';
import { codeExecutingKeys, labelOf, materialize, resolveConfigSource, savedConfigText, type ConfigSource } from './config';
import { readWorkspaceProjects, rootOfEntry } from './workspaceProjects';
import type { Direction } from './labels';
import { DEFAULT_TIMEOUT_MS, type ServerConnection } from './server/connection';
import { chooseJava, findJavaIn, PREFERRED, type FoundJava } from './server/javaLocator';
import { installJdk } from './server/jdkDownload';
import { bundledClasspath, launchServer } from './server/launcher';
import type { ServerResponse } from './server/response';
import { currentLanguage, t } from './messages';

/** 設定ファイルとして扱う名前（変わったら差分ではなく全体を作り直す）。以前の名前の config.properties は見ない */
function isConfigName(name: string): boolean {
    return name === 'jche.properties';
}

/**
 * 木を切り出す向き。`field` はキーがフィールド（`型FQN#フィールド名`）で、根がフィールド、深さ 1 がそれを読み書き
 * しているメソッド、その下が呼び出し元（サーバーの `jche.server.FieldTree`。docs/field-callers-qa.md）
 */
export type TreeDirection = Direction | 'field';

/**
 * フィールドの木を待つ上限。フィールドの参照は解析結果のメモリに無く、サーバーが要求のたびにキャッシュを
 * 読み直すので、メソッドの木より時間がかかりうる（Eclipse 版と同じ 300 秒）
 */
const FIELD_TREE_TIMEOUT_MS = 300_000;

/** 解析の状態。画面（Language Status Item・ビュー）はこれを見て描く */
export type SessionState =
    | { readonly kind: 'unanalyzed' }
    | { readonly kind: 'analyzing'; readonly label: string; readonly done: number; readonly total: number }
    | { readonly kind: 'analyzed'; readonly at: Date; readonly methods: number; readonly edges: number; readonly configLabel: string;
        /** 一緒に解析したワークスペースの他のプロジェクト（設定ファイルの workspace.projects）の数 */
        readonly workspaceCount: number;
        /** 解析後に変更されたファイル（project.root からの相対パス）。空なら最新 */
        readonly dirty: ReadonlySet<string> }
    | { readonly kind: 'failed'; readonly reason: string };

/** ワークスペースフォルダ1つぶんの解析セッション。子プロセスを1つ持つ */
export class Session implements vscode.Disposable {
    private connection: ServerConnection | undefined;
    private idleTimer: NodeJS.Timeout | undefined;
    private analyzing = false;
    private _state: SessionState = { kind: 'unanalyzed' };
    private readonly stateEmitter = new vscode.EventEmitter<SessionState>();
    readonly onDidChangeState = this.stateEmitter.event;
    /** 解析後に変わった *.java（相対パス）。最初の解析が終わってから数え始める */
    private readonly dirty = new Set<string>();
    private watcher: vscode.FileSystemWatcher | undefined;
    /** 一緒に解析した相手のフォルダ（設定ファイルの workspace.projects。絶対パス）と、その変更の見張り */
    private workspaceRoots: string[] = [];
    private workspaceWatchers: vscode.FileSystemWatcher[] = [];
    private autoTimer: NodeJS.Timeout | undefined;

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
                t('session.noConfigFile', this.folder.name));
            return { kind: 'generated', projectRoot: this.folder.uri.fsPath };
        }
        const picked = await vscode.window.showQuickPick(
            candidates.map((file) => ({ label: path.relative(this.folder.uri.fsPath, file), file })),
            { placeHolder: t('session.pickConfig', this.folder.name) });
        if (!picked) {
            return undefined;
        }
        await this.context.workspaceState.update(this.rememberedKey(), picked.file);
        // 設定ファイルを選び直したら、Java の拡張を動かしてよいかも問い直す（断った後に許可する道でもある）
        await this.context.workspaceState.update(this.extensionsKey(), undefined);
        return { kind: 'file', file: picked.file };
    }

    /**
     * いまの設定ファイルの `workspace.projects` が指すフォルダ（絶対パス）。設定ファイルを使っていなければ空。
     * 候補が複数あっても尋ねない（画面の状態の判定に使うため）。読めなければ空
     */
    async workspaceProjectRoots(): Promise<string[]> {
        const explicit = vscode.workspace.getConfiguration('jche', this.folder.uri).get<string>('configFile', '');
        const remembered = this.context.workspaceState.get<string>(this.rememberedKey());
        const decided = resolveConfigSource(this.folder.uri.fsPath, explicit, remembered);
        if (!decided.source || decided.source.kind !== 'file') {
            return [];
        }
        try {
            const entries = await readWorkspaceProjects(decided.source.file);
            const roots: string[] = [];
            for (const entry of entries) {
                const root = rootOfEntry(decided.source.file, entry);
                if (root !== undefined && !roots.includes(root)) {
                    roots.push(root);
                }
            }
            return roots;
        } catch {
            return [];
        }
    }

    /** そのフォルダが、この解析の相手（workspace.projects）に入っているか。解析済みならその解析の設定で、まだなら今の設定で見る */
    async includesWorkspaceFolder(folder: string): Promise<boolean> {
        const target = path.resolve(folder);
        const roots = this._state.kind === 'analyzed' ? this.workspaceRoots : await this.workspaceProjectRoots();
        return roots.some((root) => path.resolve(root) === target);
    }

    /**
     * `workspace.projects` を書く先の設定ファイル。設定ファイルを使っていればそれ。自動生成なら、ワークスペースに
     * `jche.properties` を書き出してそれにする（以降はそのファイルが使われる。自動生成には workspace.projects を入れない）。
     * 候補が複数あって決まらなければ選ばせる。決まらなければ undefined
     */
    async configFileForWorkspaceProjects(): Promise<string | undefined> {
        const source = await this.configSource();
        if (!source) {
            return undefined;
        }
        if (source.kind === 'file') {
            return source.file;
        }
        const target = path.join(this.folder.uri.fsPath, 'jche.properties');
        if (!existsSync(target)) {
            await writeFile(target, savedConfigText(), 'utf8');
        }
        return target;
    }

    private extensionsKey(): string {
        return `extensionsAllowed:${this.folder.uri.toString()}`;
    }

    /**
     * フォルダから自動で拾った設定ファイルが Java の拡張を動かすなら、解析の前に一度だけ確かめる。
     *
     * `plugin.folders` / `resolver.candidate.providers` / `call.rules.providers` は、解析対象のリポジトリに
     * 置かれた Java を利用者の PC でコンパイルして実行させる（`config.ts` の `codeExecutingKeys`）。
     * リポジトリを開いただけでそれが走らないよう、設定 `jche.configFile` で利用者が明示したファイル以外は
     * 一度問い、答えをワークスペースに覚える（問い直すのは「設定ファイルを選ぶ」）。
     * 信頼していないワークスペースでは問わずに断る（docs/vscode-plugin-usage.md の「安全性」）。
     * 断ったときは解析を飛ばし、どうすれば許可できるかを状態とログに残す
     */
    private async allowedToRunExtensions(source: ConfigSource): Promise<boolean> {
        if (source.kind !== 'file' || this.settings().get<string>('configFile', '').trim() !== '') {
            return true;
        }
        let keys: string[];
        try {
            keys = codeExecutingKeys(await readFile(source.file, 'utf8'));
        } catch {
            return true;    // 読めないファイルは解析側がエラーにする
        }
        if (keys.length === 0) {
            return true;
        }
        const label = labelOf(source, this.folder.uri.fsPath);
        const keyList = keys.join(', ');
        if (!vscode.workspace.isTrusted) {
            return this.refuseExtensions(t('session.extensions.untrusted', label, keyList));
        }
        let allowed = this.context.workspaceState.get<boolean>(this.extensionsKey());
        if (allowed === undefined) {
            const allow = t('session.extensions.allow');
            const answer = await vscode.window.showWarningMessage(
                t('session.extensions.confirm', label, path.dirname(source.file), keyList),
                { modal: true, detail: t('session.extensions.detail') },
                allow, t('session.extensions.deny'));
            if (answer === undefined) {
                // 閉じただけなら覚えない（次の解析でまた問う）
                return this.refuseExtensions(t('session.extensions.denied', label, keyList));
            }
            allowed = answer === allow;
            await this.context.workspaceState.update(this.extensionsKey(), allowed);
        }
        return allowed || this.refuseExtensions(t('session.extensions.denied', label, keyList));
    }

    /** 拡張を動かさないので解析を飛ばす。理由は右下の状態（失敗）とログの両方に出す */
    private refuseExtensions(reason: string): false {
        this.log.warn(reason);
        this.setState({ kind: 'failed', reason });
        return false;
    }

    /** 自動生成の内容をワークスペースへ書き出す（細かく直したい人のため） */
    async saveConfig(): Promise<void> {
        const target = path.join(this.folder.uri.fsPath, 'jche.properties');
        if (existsSync(target)) {
            const answer = await vscode.window.showWarningMessage(
                t('session.overwrite', target), { modal: true }, t('session.action.overwrite'));
            if (answer !== t('session.action.overwrite')) {
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
            findJavaIn(this.downloadDir()),     // 以前この拡張が取得したもの
            ...(process.env.PATH ?? '').split(path.delimiter)
                .filter((dir) => dir !== '')
                .map((dir) => path.join(dir, process.platform === 'win32' ? 'java.exe' : 'java')),
        ];
        return chooseJava(candidates);
    }

    private downloadDir(): string {
        return path.join(this.context.globalStorageUri.fsPath, 'jdk', String(PREFERRED));
    }

    /**
     * JDK が無いときの手当て。黙って取りに行かず、必ず一度確認する（約 200MB）。
     * 閉域では取得できないので「場所を指定する」も並べる。
     */
    private async offerJdk(): Promise<FoundJava | undefined> {
        const canDownload = this.settings().get<boolean>('jdkDownload', true);
        const download = t('session.jdk.download', PREFERRED);
        const specify = t('session.jdk.specify');
        const answer = await vscode.window.showWarningMessage(
            t('session.jdk.notFound'),
            { modal: true, detail: t(canDownload
                ? 'session.jdk.downloadDetail'
                : 'session.jdk.downloadOff') },
            ...(canDownload ? [download, specify] : [specify]));
        if (answer === specify) {
            const picked = await vscode.window.showOpenDialog({
                canSelectFiles: false, canSelectFolders: true, canSelectMany: false, title: t('session.jdk.pickFolder') });
            if (!picked || picked.length === 0) {
                return undefined;
            }
            const found = chooseJava([picked[0].fsPath]);
            if (!found) {
                vscode.window.showErrorMessage(t('session.jdk.unusable', picked[0].fsPath));
                return undefined;
            }
            await vscode.workspace.getConfiguration('jche').update('javaHome', picked[0].fsPath, vscode.ConfigurationTarget.Global);
            return found;
        }
        if (answer !== download) {
            return undefined;
        }
        const targetDir = path.dirname(this.downloadDir());
        try {
            const java = await vscode.window.withProgress(
                { location: vscode.ProgressLocation.Notification, title: t('session.jdk.downloading', PREFERRED), cancellable: true },
                (progress, token) => installJdk(PREFERRED, targetDir, {
                    received: (done, total) => progress.report({
                        message: total > 0 ? `${Math.round(done / 1048576)} / ${Math.round(total / 1048576)} MB` : `${Math.round(done / 1048576)} MB`,
                    }),
                    isCancelled: () => token.isCancellationRequested,
                }));
            this.log.info(t('session.jdk.downloaded', java, PREFERRED));
            return chooseJava([java]);
        } catch (e) {
            const reason = e instanceof Error ? e.message : String(e);
            this.log.error(t('session.jdk.downloadFailed', reason));
            const retry = await vscode.window.showErrorMessage(
                t('session.jdk.downloadFailed', reason), t('session.jdk.specify'));
            return retry ? this.offerJdk() : undefined;
        }
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
        const java = this.findJava() ?? (await this.offerJdk());
        if (!java) {
            throw new Error(t('session.jdk.notConfigured'));
        }
        const classpath = this.classpath();
        if (classpath.length === 0) {
            throw new Error(t('session.libNotFound'));
        }
        const storage = this.context.storageUri?.fsPath ?? path.join(this.context.globalStorageUri.fsPath, 'ws');
        const cacheRoot = path.join(storage, 'cache');
        await mkdir(cacheRoot, { recursive: true });
        this.log.info(t('session.starting', java.executable, java.version));
        if (java.olderThanPreferred) {
            this.log.warn(t('session.olderJava', java.version));
        }
        const connection = launchServer({
            javaExecutable: java.executable,
            classpath,
            cacheRoot,
            vmArguments: this.settings().get<string[]>('vmArguments', []),
            workingDir: this.folder.uri.fsPath,
            // 解析側のログをこの画面と同じ言語で出す（docs/nls-qa.md の Q8）
            messageLanguage: currentLanguage(),
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
            throw new Error(t('session.startFailed', hello.reason));
        }
        this.log.info(t('session.hello', hello.field('protocol'), hello.field('jdt'),
            hello.field('jvm'), hello.field('maxJava')));
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
            this.idleTimer = setTimeout(
                () => this.shutdown(t('session.reason.idle')).catch((e) => this.logRejection(e)), minutes * 60_000);
        }
    }

    private async request(timeoutMs: number, ...words: string[]): Promise<ServerResponse> {
        const connection = await this.ensureConnection();
        const response = await connection.request(timeoutMs, ...words);
        this.touch();
        if (!response.ok && response.reason === 'disconnected') {
            this.log.error(t('session.disconnected'));
            this.connection = undefined;
            this.setState({ kind: 'failed', reason: t('session.serverExited') });
        }
        return response;
    }

    /** 待ち手のいない非同期処理の失敗をログに残す（握りつぶすと、止まった理由がどこにも出ない） */
    private logRejection(e: unknown): void {
        this.log.error(e instanceof Error ? e.message : String(e));
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
            this.log.info(t('session.stopping', why));
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
        if (!source || !(await this.allowedToRunExtensions(source))) {
            return false;
        }
        this.analyzing = true;
        await vscode.commands.executeCommand('setContext', 'jche.analyzing', true);
        this.setState({ kind: 'analyzing', label: t('session.phase.preparing'), done: 0, total: 0 });
        const cancelListener = token?.onCancellationRequested(() => this.connection?.cancel());
        try {
            const storage = this.context.storageUri?.fsPath ?? path.join(this.context.globalStorageUri.fsPath, 'ws');
            const configPath = await materialize(source, path.join(storage, 'config'));
            this.log.info(t('session.analyzing', this.folder.name, labelOf(source, this.folder.uri.fsPath)));
            const workspaceRoots = source.kind === 'file' ? await this.workspaceProjectRoots() : [];
            const response = await this.request(24 * 60 * 60_000, 'ANALYZE', configPath);
            if (!response.ok) {
                const reason = response.reason === 'cancelled' ? t('session.cancelled') : response.reason;
                this.setState({ kind: 'failed', reason });
                this.log.warn(t('session.analysisReason', reason));
                return false;
            }
            this.dirty.clear();
            this.workspaceRoots = workspaceRoots;
            this.setState({
                kind: 'analyzed',
                at: new Date(),
                methods: response.numberField('methods', 0),
                edges: response.numberField('edges', 0),
                configLabel: labelOf(source, this.folder.uri.fsPath),
                workspaceCount: workspaceRoots.length,
                dirty: new Set(),
            });
            this.watch();
            this.watchWorkspaceRoots();
            this.log.info(t('session.analyzed', response.field('methods'), response.field('edges')));
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

    // ------------------------------------------------------------
    // 変更の検知（docs/vscode-plugin-design.md §7）
    // ------------------------------------------------------------

    /** *.java と設定ファイルの変更を数える。タイピング中は見ない（保存・追加・削除だけ） */
    private watch(): void {
        if (this.watcher) {
            return;
        }
        this.watcher = vscode.workspace.createFileSystemWatcher(
            new vscode.RelativePattern(this.folder, '**/*.{java,properties}'));
        const mark = (uri: vscode.Uri) => {
            const relative = path.relative(this.folder.uri.fsPath, uri.fsPath).split(path.sep).join('/');
            if (relative.startsWith('..') || (relative.endsWith('.properties') && !isConfigName(path.basename(relative)))) {
                return;
            }
            this.dirty.add(relative);
            if (this._state.kind === 'analyzed') {
                this.setState({ ...this._state, dirty: new Set(this.dirty) });
            }
            this.scheduleAutoAnalyze();
        };
        this.watcher.onDidChange(mark);
        this.watcher.onDidCreate(mark);
        this.watcher.onDidDelete(mark);
    }

    /**
     * 一緒に解析した相手のフォルダ（workspace.projects）の *.java の変更も、この解析の ⚠ にする。
     * 相対パスはこのフォルダからの形（`../app-batch/src/...`）で、木の行の file 列と同じ綴りになる
     */
    private watchWorkspaceRoots(): void {
        for (const watcher of this.workspaceWatchers) {
            watcher.dispose();
        }
        this.workspaceWatchers = [];
        for (const root of this.workspaceRoots) {
            const watcher = vscode.workspace.createFileSystemWatcher(
                new vscode.RelativePattern(vscode.Uri.file(root), '**/*.java'));
            const mark = (uri: vscode.Uri) => {
                const relative = path.relative(this.folder.uri.fsPath, uri.fsPath).split(path.sep).join('/');
                this.dirty.add(relative);
                if (this._state.kind === 'analyzed') {
                    this.setState({ ...this._state, dirty: new Set(this.dirty) });
                }
                this.scheduleAutoAnalyze();
            };
            watcher.onDidChange(mark);
            watcher.onDidCreate(mark);
            watcher.onDidDelete(mark);
            this.workspaceWatchers.push(watcher);
        }
    }

    /** 自動再解析（既定 OFF）。3 秒静止したら裏で走らせる */
    private scheduleAutoAnalyze(): void {
        if (!this.settings().get<boolean>('autoAnalyze', false)) {
            return;
        }
        if (this.autoTimer) {
            clearTimeout(this.autoTimer);
        }
        this.autoTimer = setTimeout(() => {
            this.autoTimer = undefined;
            if (this.analyzing || this.dirty.size === 0) {
                return;
            }
            // 自動のときは静かに（右下の細い進捗）。手動と違って通知は出さない
            vscode.window.withProgress(
                { location: vscode.ProgressLocation.Window, title: t('session.updating', this.folder.name) },
                () => this.analyze()).then(undefined, (e) => this.logRejection(e));
        }, 3_000);
    }

    /** 解析後に変わったファイル数 */
    get dirtyCount(): number {
        return this.dirty.size;
    }

    isDirty(relativeFile: string): boolean {
        return this.dirty.has(relativeFile);
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

    /**
     * カーソル位置（ファイル・1 始まりの行・カーソルの下の単語）にあるフィールド。
     * こちらには JDT が無いので、フィールドのキーはサーバーに引かせる（`FIELDAT`）。候補が複数なら `keys` に並ぶ
     */
    fieldAt(file: string, line: number, name: string): Promise<ServerResponse> {
        return this.request(FIELD_TREE_TIMEOUT_MS, 'FIELDAT', file, String(line), name);
    }

    /** 木を切り出す。`filterWords` は `depth=5` のような語（`filters.ts#toWords`） */
    tree(key: string, direction: TreeDirection, filterWords: readonly string[]): Promise<ServerResponse> {
        return this.request(direction === 'field' ? FIELD_TREE_TIMEOUT_MS : 120_000, 'TREE', key, direction, ...filterWords);
    }

    /** いま見えている木と同じ条件で CSV に書く */
    export(key: string, direction: TreeDirection, output: string, filterWords: readonly string[]): Promise<ServerResponse> {
        return this.request(600_000, 'EXPORT', key, direction, output, ...filterWords);
    }

    dispose(): void {
        if (this.autoTimer) {
            clearTimeout(this.autoTimer);
        }
        this.watcher?.dispose();
        for (const watcher of this.workspaceWatchers) {
            watcher.dispose();
        }
        // 拡張の終了時は deactivate() が先に shutdown を待っているので、ここでは二度目（何もしない）になる
        this.shutdown(t('session.reason.shutdown')).catch((e) => this.logRejection(e));
        this.stateEmitter.dispose();
    }
}
