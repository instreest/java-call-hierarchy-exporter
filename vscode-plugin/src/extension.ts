// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
import * as path from 'node:path';
import * as vscode from 'vscode';
import { Session } from './session';
import { StatusItem } from './status';
import { CallersView, openAt } from './view';
import type { TreeNode } from './server/tree';
import { toFieldAccess } from './filters';
import { setLanguage, t } from './messages';
import { entryFor, readWorkspaceProjects, rootOfEntry, writeWorkspaceProjects } from './workspaceProjects';

/**
 * 拡張の入口。
 *
 * 解析は子プロセス（同梱の `lib/jche-core.jar` ＋ `lib/jdt/*.jar`、利用者の JDK）に任せ、
 * ここにあるのは画面と子プロセスの世話だけである（docs/vscode-plugin-design.md）。
 *
 * 重い処理は**利用者が頼んだときだけ**始める。有効化は「ビューを開いた」「コマンドを実行した」
 * ときで、Java のファイルを開いただけでは起きない（`activationEvents` に `onLanguage:java` は無い）。
 * 有効化しても子プロセスは起こさず、最初の解析要求まで待つ。
 */

let sessions: Map<string, Session>;
let log: vscode.LogOutputChannel;
let view: CallersView;
let status: StatusItem;

export function activate(context: vscode.ExtensionContext): void {
    // 画面の文言の言語。VSCode の表示言語に合わせる（既定は英語）。
    // 何かを出す前に決める。子プロセスにも同じ言語を渡す（docs/nls-qa.md の Q8）
    setLanguage(vscode.env.language);
    log = vscode.window.createOutputChannel('Java Call Hierarchy Exporter', { log: true });
    sessions = new Map();
    view = new CallersView(log, context.workspaceState);
    status = new StatusItem();
    context.subscriptions.push(log, view, status);

    const sessionOf = (folder: vscode.WorkspaceFolder): Session => {
        const key = folder.uri.toString();
        let session = sessions.get(key);
        if (!session) {
            session = new Session(folder, context, log);
            session.onDidChangeState((state) => {
                if (session === currentSession()) {
                    status.render(session);
                }
                // ⚠ の付け外し。木そのものは取り直さない（解析が終わったときは analyze() が reload する）
                if (state.kind === 'analyzed' && view.currentRoot?.session === session) {
                    view.refreshDecorations();
                }
            });
            sessions.set(key, session);
            context.subscriptions.push(session);
        }
        return session;
    };

    /** 状態表示の対象。アクティブなエディタのフォルダ → 表示中の木のフォルダ → 唯一のフォルダ */
    const currentSession = (): Session | undefined => {
        const uri = vscode.window.activeTextEditor?.document.uri;
        const folder = uri ? vscode.workspace.getWorkspaceFolder(uri) : undefined;
        if (folder) {
            return sessions.get(folder.uri.toString()) ?? sessionOf(folder);
        }
        if (view.currentRoot) {
            return view.currentRoot.session;
        }
        const folders = vscode.workspace.workspaceFolders ?? [];
        return folders.length === 1 ? sessionOf(folders[0]) : undefined;
    };

    /**
     * そのフォルダのメソッド・フィールドを引くのに使うセッション。表示中の木のセッションの設定ファイルの
     * `workspace.projects` に入っているフォルダ（一緒に解析した相手）なら、そのセッションのまま引く
     * （相手のファイルも解析結果に入っている。相対パスはそのセッションのフォルダからの `../` 付きの形になる）
     */
    const sessionForMember = async (folder: vscode.WorkspaceFolder): Promise<Session> => {
        const shown = view.currentRoot?.session;
        if (shown && shown.folder.uri.toString() !== folder.uri.toString()
                && (await shown.includesWorkspaceFolder(folder.uri.fsPath))) {
            return shown;
        }
        return sessionOf(folder);
    };

    /** 解析するフォルダを決める。複数あれば選ばせる */
    const pickSession = async (): Promise<Session | undefined> => {
        const folders = vscode.workspace.workspaceFolders ?? [];
        if (folders.length === 0) {
            vscode.window.showInformationMessage(t('command.openFolderFirst'));
            return undefined;
        }
        const current = currentSession();
        if (current) {
            return current;
        }
        const picked = await vscode.window.showWorkspaceFolderPick({ placeHolder: t('command.pickFolder') });
        return picked ? sessionOf(picked) : undefined;
    };

    /** 解析を走らせる。手動なので通知の進捗（中止ボタン付き）に出す */
    const analyze = async (session: Session): Promise<boolean> => {
        if (session.isAnalyzing) {
            vscode.window.showInformationMessage(t('command.alreadyAnalyzing', session.folder.name));
            return false;
        }
        const ok = await vscode.window.withProgress(
            { location: vscode.ProgressLocation.Notification,
                title: t('command.analyzingTitle', session.folder.name), cancellable: true },
            async (progress, token) => {
                const listener = session.onDidChangeState((state) => {
                    if (state.kind === 'analyzing') {
                        const count = state.total > 0 ? ` ${state.done.toLocaleString()}/${state.total.toLocaleString()}` : '';
                        progress.report({ message: `${state.label}${count}` });
                    }
                });
                try {
                    return await session.analyze(token);
                } finally {
                    listener.dispose();
                }
            });
        status.render(currentSession());
        if (ok && view.currentRoot?.session === session) {
            await view.reload();     // 見えている木を新しい結果で描き直す
        }
        if (!ok && session.state.kind === 'failed') {
            const answer = await vscode.window.showErrorMessage(
                t('command.analyzeFailed', session.state.reason), t('command.action.openLog'));
            if (answer) {
                log.show();
            }
        }
        return ok;
    };

    /** エディタのカーソル位置のメソッドを起点に木を出す（主ユースケース） */
    const showCallers = async (): Promise<void> => {
        const editor = vscode.window.activeTextEditor;
        if (!editor || editor.document.languageId !== 'java') {
            vscode.window.showInformationMessage(t('command.putCursorInJava'));
            return;
        }
        const folder = vscode.workspace.getWorkspaceFolder(editor.document.uri);
        if (!folder) {
            vscode.window.showInformationMessage(t('command.outsideWorkspace'));
            return;
        }
        const session = await sessionForMember(folder);
        if (!session.isAnalyzed) {
            const answer = await vscode.window.showInformationMessage(
                t('command.analyzeNow', session.folder.name),
                t('command.action.analyze'));
            if (answer !== t('command.action.analyze') || !(await analyze(session))) {
                return;
            }
        }
        if (editor.document.isDirty) {
            log.warn(t('command.unsaved', editor.document.fileName));
        }
        const relative = path.relative(session.folder.uri.fsPath, editor.document.uri.fsPath).split(path.sep).join('/');
        const line = editor.selection.active.line + 1;    // サーバーは 1 始まり
        const at = await session.at(relative, line);
        if (!at.ok) {
            switch (at.reason) {
                case 'file-not-analyzed':
                    vscode.window.showWarningMessage(
                        t('command.fileNotAnalyzed', relative));
                    return;
                case 'not-found':
                    vscode.window.showInformationMessage(t('command.methodNotFound'));
                    return;
                case 'not-analyzed':
                    vscode.window.showInformationMessage(t('command.notAnalyzed'));
                    return;
                default:
                    vscode.window.showErrorMessage(t('command.atFailed', at.reason));
                    return;
            }
        }
        await view.show({
            session,
            key: at.field('key'),
            label: at.field('label'),
            file: at.field('file'),
            line: at.numberField('line', 0),
        });
    };

    /**
     * エディタのカーソル位置のフィールドを起点に木を出す（フィールドの呼び出し元。docs/field-callers-qa.md）。
     *
     * こちらには JDT が無いので、カーソルの下の単語と行をサーバーに送ってフィールドを引かせる（`FIELDAT`）。
     * フィールドの宣言の上でも、使っている箇所（`this.status` の `status`）の上でもよい
     */
    const showFieldCallers = async (): Promise<void> => {
        const editor = vscode.window.activeTextEditor;
        if (!editor || editor.document.languageId !== 'java') {
            vscode.window.showInformationMessage(t('command.putCursorOnField'));
            return;
        }
        const folder = vscode.workspace.getWorkspaceFolder(editor.document.uri);
        if (!folder) {
            vscode.window.showInformationMessage(t('command.outsideWorkspace'));
            return;
        }
        const position = editor.selection.active;
        const wordRange = editor.document.getWordRangeAtPosition(position, /[A-Za-z_$][\w$]*/);
        if (!wordRange) {
            vscode.window.showInformationMessage(t('command.putCursorOnField'));
            return;
        }
        const name = editor.document.getText(wordRange);
        const session = await sessionForMember(folder);
        if (!session.isAnalyzed) {
            const answer = await vscode.window.showInformationMessage(
                t('command.analyzeNow', session.folder.name),
                t('command.action.analyze'));
            if (answer !== t('command.action.analyze') || !(await analyze(session))) {
                return;
            }
        }
        if (editor.document.isDirty) {
            log.warn(t('command.unsaved', editor.document.fileName));
        }
        const relative = path.relative(session.folder.uri.fsPath, editor.document.uri.fsPath).split(path.sep).join('/');
        const found = await session.fieldAt(relative, position.line + 1, name);    // サーバーは 1 始まり
        if (!found.ok) {
            switch (found.reason) {
                case 'file-not-analyzed':
                    vscode.window.showWarningMessage(t('command.fileNotAnalyzed', relative));
                    return;
                case 'not-found':
                    vscode.window.showInformationMessage(t('command.fieldNotFound', name));
                    return;
                case 'not-analyzed':
                    vscode.window.showInformationMessage(t('command.notAnalyzed'));
                    return;
                case 'stale-cache': {
                    const answer = await vscode.window.showWarningMessage(
                        t('command.staleCache'), t('command.action.analyze'));
                    if (answer === t('command.action.analyze')) {
                        await analyze(session);
                    }
                    return;
                }
                default:
                    vscode.window.showErrorMessage(t('command.fieldAtFailed', found.reason));
                    return;
            }
        }
        // 候補が複数（入れ子のクラスに同じ名前のフィールドがある、など）なら選んでもらう。黙って 1 つを選ばない
        const keys = found.field('keys').split(',').filter((k) => k !== '');
        let key = keys.length > 0 ? keys[0] : found.field('key');
        if (keys.length > 1) {
            const picked = await vscode.window.showQuickPick(
                keys.map((k) => ({ label: fieldLabelOf(k), description: k, key: k })),
                { placeHolder: t('command.pickField', name) });
            if (!picked) {
                return;
            }
            key = picked.key;
        }
        await view.show({ session, key, label: fieldLabelOf(key), file: '', line: 0, kind: 'field' });
    };

    context.subscriptions.push(
        vscode.commands.registerCommand('jche.showCallers', showCallers),
        vscode.commands.registerCommand('jche.showFieldCallers', showFieldCallers),
        vscode.commands.registerCommand('jche.fieldAccess', async () => {
            if (!view.isFieldRoot) {
                vscode.window.showInformationMessage(t('command.fieldAccess.noField'));
                return;
            }
            const current = view.access;
            type Item = vscode.QuickPickItem & { value: string };
            const items: Item[] = [
                { value: 'all', label: t('command.fieldAccess.all'), picked: current === 'all' },
                { value: 'write', label: t('command.fieldAccess.write'),
                    description: t('command.fieldAccess.writeDescription'), picked: current === 'write' },
                { value: 'read', label: t('command.fieldAccess.read'),
                    description: t('command.fieldAccess.readDescription'), picked: current === 'read' },
            ];
            const picked = await vscode.window.showQuickPick(items, { title: t('command.fieldAccess.title') });
            if (picked) {
                await view.setAccess(toFieldAccess(picked.value));
            }
        }),
        vscode.commands.registerCommand('jche.analyze', async () => {
            const session = await pickSession();
            if (session) {
                await analyze(session);
            }
        }),
        vscode.commands.registerCommand('jche.cancel', () => {
            for (const session of sessions.values()) {
                if (session.isAnalyzing) {
                    session.cancel();
                }
            }
        }),
        vscode.commands.registerCommand('jche.toggleDirection', () => view.toggleDirection()),
        vscode.commands.registerCommand('jche.export', () => view.exportCsv()),
        vscode.commands.registerCommand('jche.filterText', async () => {
            const text = await vscode.window.showInputBox({
                title: t('command.filterText.title'),
                prompt: t('command.filterText.prompt'),
                value: view.filters.text,
            });
            if (text !== undefined) {
                await view.setFilters({ ...view.filters, text: text.trim() });
            }
        }),
        vscode.commands.registerCommand('jche.filterOptions', async () => {
            const f = view.filters;
            type Item = vscode.QuickPickItem & { key: 'includeTests' | 'includeGuessed' | 'applyExcludePackages' | 'dedupe' | 'depth' };
            const items: Item[] = [
                { key: 'depth', label: t('command.filter.depth', f.maxDepth),
                    description: t('command.filter.depthDescription'), alwaysShow: true },
                { key: 'includeTests', label: t('command.filter.includeTests'),
                    description: t('command.filter.includeTestsDescription'), picked: f.includeTests },
                { key: 'includeGuessed', label: t('command.filter.includeGuessed'), picked: f.includeGuessed },
                { key: 'applyExcludePackages', label: t('command.filter.applyExcludes'), picked: f.applyExcludePackages },
                { key: 'dedupe', label: t('command.filter.dedupe'),
                    description: t('command.filter.dedupeDescription'), picked: f.dedupe },
            ];
            const picked = await vscode.window.showQuickPick(items, {
                canPickMany: true, title: t('command.filter.title'),
            });
            if (!picked) {
                return;
            }
            let next = {
                ...f,
                includeTests: picked.some((i) => i.key === 'includeTests'),
                includeGuessed: picked.some((i) => i.key === 'includeGuessed'),
                applyExcludePackages: picked.some((i) => i.key === 'applyExcludePackages'),
                dedupe: picked.some((i) => i.key === 'dedupe'),
            };
            if (picked.some((i) => i.key === 'depth')) {
                const depth = await vscode.window.showInputBox({
                    title: t('command.depth.title'), prompt: t('command.depth.prompt'), value: String(f.maxDepth),
                    validateInput: (v) => /^\d+$/.test(v) && Number(v) >= 1 && Number(v) <= 50
                        ? undefined : t('command.depth.invalid'),
                });
                if (depth !== undefined) {
                    next = { ...next, maxDepth: Number(depth) };
                }
            }
            await view.setFilters(next);
        }),
        vscode.commands.registerCommand('jche.openCallSite', (node: TreeNode) => view.openCallSite(node)),
        vscode.commands.registerCommand('jche.setRoot', async (node: TreeNode) => {
            const root = view.currentRoot;
            if (!root) {
                return;
            }
            await view.show({ session: root.session, key: node.row.key, label: node.row.label, file: '', line: 0 });
        }),
        vscode.commands.registerCommand('jche.openDeclaration', async (node: TreeNode) => {
            const root = view.currentRoot;
            if (!root) {
                return;
            }
            const found = await root.session.find(node.row.key);
            if (!found.ok || found.field('file') === '') {
                vscode.window.showInformationMessage(t('command.declarationUnknown'));
                return;
            }
            await openAt(root.session.folder, found.field('file'), found.numberField('line', 1));
        }),
        vscode.commands.registerCommand('jche.openLog', () => log.show()),
        vscode.commands.registerCommand('jche.saveConfig', async () => {
            const session = await pickSession();
            if (session) {
                await session.saveConfig();
            }
        }),
        vscode.commands.registerCommand('jche.selectConfig', async () => {
            const session = await pickSession();
            if (session && (await session.pickConfig())) {
                vscode.window.showInformationMessage(t('command.configSelected'));
            }
        }),
        vscode.commands.registerCommand('jche.addWorkspaceProjects', async () => {
            const session = await pickSession();
            if (session) {
                await addWorkspaceProjects(session);
            }
        }),
        vscode.window.onDidChangeActiveTextEditor(() => status.render(currentSession())),
        vscode.workspace.onDidChangeWorkspaceFolders((event) => {
            for (const removed of event.removed) {
                const session = sessions.get(removed.uri.toString());
                if (session) {
                    session.dispose();
                    sessions.delete(removed.uri.toString());
                    if (view.currentRoot?.session === session) {
                        view.clear();
                    }
                }
            }
            status.render(currentSession());
        }),
    );
    status.render(currentSession());
}

/**
 * ワークスペースの他のフォルダを選ばせて、設定ファイルの `workspace.projects` に書き足す。
 *
 * 一緒に解析する相手は利用者が明示したものだけ（既定では相手を解析しない。ワークスペース全体の解析は
 * 時間とメモリが増えるため）。VSCode は Eclipse のようにプロジェクト間の参照を知らないので、
 * ワークスペースの他のフォルダをそのまま候補にして複数選択させる。解析は設定ファイルだけを読む
 * （画面の状態では解析の範囲を変えない。docs/workspace-callers-design.md の Q11）。
 * 既に書いてあるフォルダは最初から選ばれていて、外せば消える。ワークスペースのフォルダに結び付かない
 * 手書きの指定は残す
 */
async function addWorkspaceProjects(session: Session): Promise<void> {
    const others = (vscode.workspace.workspaceFolders ?? [])
        .filter((f) => f.uri.toString() !== session.folder.uri.toString());
    if (others.length === 0) {
        vscode.window.showInformationMessage(t('workspace.noOtherFolders', session.folder.name));
        return;
    }
    const configFile = await session.configFileForWorkspaceProjects();
    if (!configFile) {
        return;
    }
    const current = await readWorkspaceProjects(configFile);
    const currentRoots = current.map((entry) => rootOfEntry(configFile, entry));
    const rootOf = (folder: vscode.WorkspaceFolder): string => path.resolve(folder.uri.fsPath);
    const isListed = (folder: vscode.WorkspaceFolder): boolean =>
        currentRoots.some((root) => root !== undefined && path.resolve(root) === rootOf(folder));
    type Item = vscode.QuickPickItem & { folder: vscode.WorkspaceFolder };
    const items: Item[] = others.map((folder) => ({
        label: folder.name, description: folder.uri.fsPath, picked: isListed(folder), folder,
    }));
    const picked = await vscode.window.showQuickPick(items, {
        canPickMany: true,
        title: t('workspace.pickTitle', session.folder.name),
        placeHolder: t('workspace.pickPlaceholder'),
    });
    if (!picked) {
        return;
    }
    const pickedRoots = new Set(picked.map((item) => rootOf(item.folder)));
    const entries: string[] = [];
    current.forEach((entry, i) => {
        const root = currentRoots[i];
        const listedFolder = root === undefined ? undefined : others.find((f) => rootOf(f) === path.resolve(root));
        if (!listedFolder) {
            entries.push(entry);                       // ワークスペースの外を指す指定。触らない
        } else if (pickedRoots.delete(rootOf(listedFolder))) {
            entries.push(entry);                       // 既にあって、まだ選ばれている
        }
    });
    for (const item of picked) {
        if (pickedRoots.has(rootOf(item.folder))) {
            entries.push(entryFor(configFile, item.folder.uri.fsPath));
        }
    }
    await writeWorkspaceProjects(configFile, entries);
    vscode.window.showInformationMessage(t('workspace.written', entries.length, path.basename(configFile)));
}

/** フィールドのキー（`型FQN#フィールド名`）を、木の根の見出し（`型FQN.フィールド名`）にする。サーバーの根の行と同じ綴り */
function fieldLabelOf(key: string): string {
    const hash = key.lastIndexOf('#');
    return hash < 0 ? key : `${key.substring(0, hash)}.${key.substring(hash + 1)}`;
}

export async function deactivate(): Promise<void> {
    // 常駐している子プロセスを止める（CANCEL → SHUTDOWN → 応じなければ kill）。
    // 止め終わるまで待って返す。待たずに返すと拡張ホストが先に終わり、子プロセスが残りうる。
    // Session#dispose は context.subscriptions からこの後に呼ばれる（二度目の shutdown は何もしない）
    await Promise.all([...(sessions?.values() ?? [])].map(
        (session) => session.shutdown(t('session.reason.shutdown')).catch((e) => log.error(String(e)))));
}
