// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
import * as path from 'node:path';
import * as vscode from 'vscode';
import { Session } from './session';
import { StatusItem } from './status';
import { CallersView, openAt } from './view';
import type { TreeNode } from './server/tree';

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
    log = vscode.window.createOutputChannel('Call Hierarchy Exporter', { log: true });
    sessions = new Map();
    view = new CallersView(log);
    status = new StatusItem();
    context.subscriptions.push(log, view, status);

    const sessionOf = (folder: vscode.WorkspaceFolder): Session => {
        const key = folder.uri.toString();
        let session = sessions.get(key);
        if (!session) {
            session = new Session(folder, context, log);
            session.onDidChangeState(() => {
                if (session === currentSession()) {
                    status.render(session);
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
            vscode.window.showInformationMessage('フォルダを開いてから解析してください。');
            return undefined;
        }
        const current = currentSession();
        if (current) {
            return current;
        }
        const picked = await vscode.window.showWorkspaceFolderPick({ placeHolder: '解析するフォルダ' });
        return picked ? sessionOf(picked) : undefined;
    };

    /** 解析を走らせる。手動なので通知の進捗（中止ボタン付き）に出す */
    const analyze = async (session: Session): Promise<boolean> => {
        if (session.isAnalyzing) {
            vscode.window.showInformationMessage(`${session.folder.name} は解析中です。`);
            return false;
        }
        const ok = await vscode.window.withProgress(
            { location: vscode.ProgressLocation.Notification, title: `影響調査: ${session.folder.name} を解析中`, cancellable: true },
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
            const answer = await vscode.window.showErrorMessage(`解析に失敗しました: ${session.state.reason}`, 'ログを開く');
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
            vscode.window.showInformationMessage('Java のファイルでメソッドの中にカーソルを置いてから実行してください。');
            return;
        }
        const folder = vscode.workspace.getWorkspaceFolder(editor.document.uri);
        if (!folder) {
            vscode.window.showInformationMessage('このファイルはワークスペースのフォルダの外です。');
            return;
        }
        const session = sessionOf(folder);
        if (!session.isAnalyzed) {
            const answer = await vscode.window.showInformationMessage(
                `${folder.name} はまだ解析していません。解析しますか？（プロジェクト全体を解析するので時間がかかります）`,
                '解析する');
            if (answer !== '解析する' || !(await analyze(session))) {
                return;
            }
        }
        if (editor.document.isDirty) {
            log.warn(`${editor.document.fileName} は未保存です。保存前の行番号で引きます。`);
        }
        const relative = path.relative(folder.uri.fsPath, editor.document.uri.fsPath).split(path.sep).join('/');
        const line = editor.selection.active.line + 1;    // サーバーは 1 始まり
        const at = await session.at(relative, line);
        if (!at.ok) {
            switch (at.reason) {
                case 'file-not-analyzed':
                    vscode.window.showWarningMessage(
                        `${relative} は解析対象に入っていません（source.folders の外、除外パッケージ、または解析後に増えたファイル）。設定を確認するか、再解析してください。`);
                    return;
                case 'not-found':
                    vscode.window.showInformationMessage('メソッドの中にカーソルを置いてください（ここを囲むメソッドが解析結果にありません）。');
                    return;
                case 'not-analyzed':
                    vscode.window.showInformationMessage('まだ解析していません。解析してから実行してください。');
                    return;
                default:
                    vscode.window.showErrorMessage(`メソッドを特定できませんでした: ${at.reason}`);
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
                vscode.window.showInformationMessage('宣言の場所が分かりません（ソースが無いか、解析結果にありません）。');
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
                vscode.window.showInformationMessage('次の解析からこの設定ファイルを使います。');
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
