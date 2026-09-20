// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
import * as path from 'node:path';
import * as vscode from 'vscode';
import { Session } from './session';
import { StatusItem } from './status';
import { CallersView, openAt } from './view';
import type { TreeNode } from './server/tree';
import { setLanguage, t } from './messages';

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
        const session = sessionOf(folder);
        if (!session.isAnalyzed) {
            const answer = await vscode.window.showInformationMessage(
                t('command.analyzeNow', folder.name),
                t('command.action.analyze'));
            if (answer !== t('command.action.analyze') || !(await analyze(session))) {
                return;
            }
        }
        if (editor.document.isDirty) {
            log.warn(t('command.unsaved', editor.document.fileName));
        }
        const relative = path.relative(folder.uri.fsPath, editor.document.uri.fsPath).split(path.sep).join('/');
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

    context.subscriptions.push(
        vscode.commands.registerCommand('jche.showCallers', showCallers),
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

export function deactivate(): void {
    // 常駐している子プロセスを止める（CANCEL → SHUTDOWN → 応じなければ kill）
    for (const session of sessions?.values() ?? []) {
        session.dispose();
    }
}
