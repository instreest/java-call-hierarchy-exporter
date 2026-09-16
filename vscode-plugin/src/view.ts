// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
import * as path from 'node:path';
import * as vscode from 'vscode';
import { countMessage, describeRow, viewDescription, type Direction } from './labels';
import { FLAG_TRUNCATED, hasFlag, type ServerRow } from './server/response';
import { buildTree, countNodes, type TreeNode } from './server/tree';
import type { Session } from './session';

/** ビューに出している木の起点 */
export interface RootSpec {
    readonly session: Session;
    readonly key: string;
    readonly label: string;
    readonly file: string;
    readonly line: number;
}

/** サーバーの maxRows の既定（jche.server.TreeFilters）。上限に当たったことを伝えるために知っておく */
const SERVER_MAX_ROWS = 20_000;

/**
 * 影響調査ビュー。行データ（`R` 行）を描くだけで、グラフは持たない。
 *
 * 深さの上限で打ち切られた節点は、開いたときにその節点を根にして `TREE` を投げ直す
 * （docs/vscode-plugin-design.md §11「木の転送量」。一括転送のまま、続きだけ遅延で取る）。
 */
export class CallersView implements vscode.TreeDataProvider<TreeNode>, vscode.Disposable {
    private readonly changed = new vscode.EventEmitter<TreeNode | undefined>();
    readonly onDidChangeTreeData = this.changed.event;
    private readonly view: vscode.TreeView<TreeNode>;
    private root: RootSpec | undefined;
    private tree: TreeNode | undefined;
    private _direction: Direction = 'callers';
    /** 続きを取り寄せ済みの節点 */
    private readonly fetched = new WeakSet<TreeNode>();

    constructor(private readonly log: vscode.LogOutputChannel) {
        this.view = vscode.window.createTreeView('jche.callers', { treeDataProvider: this, showCollapseAll: true });
        void vscode.commands.executeCommand('setContext', 'jche.direction', this._direction);
    }

    get direction(): Direction {
        return this._direction;
    }

    get currentRoot(): RootSpec | undefined {
        return this.root;
    }

    private depth(): number {
        return vscode.workspace.getConfiguration('jche').get<number>('depth', 5);
    }

    /** 起点を差し替えて木を取り直す */
    async show(root: RootSpec): Promise<void> {
        this.root = root;
        await this.reload();
        await vscode.commands.executeCommand('jche.callers.focus');
    }

    async toggleDirection(): Promise<void> {
        this._direction = this._direction === 'callers' ? 'callees' : 'callers';
        await vscode.commands.executeCommand('setContext', 'jche.direction', this._direction);
        if (this.root) {
            await this.reload();
        }
    }

    /** 木を取り直す（再解析のあとにも呼ぶ） */
    async reload(): Promise<void> {
        const root = this.root;
        if (!root) {
            this.tree = undefined;
            this.view.description = undefined;
            this.view.message = undefined;
            this.changed.fire(undefined);
            return;
        }
        this.view.description = viewDescription(root.label, root.line, this._direction);
        this.view.message = '取り寄せ中…';
        const response = await root.session.tree(root.key, this._direction, this.depth());
        if (!response.ok) {
            this.tree = undefined;
            this.view.message = response.reason === 'not-analyzed'
                ? 'まだ解析していません。解析してから表示してください'
                : response.reason === 'not-found'
                    ? `このメソッドは解析結果にありません: ${root.key}`
                    : `取り寄せに失敗しました: ${response.reason}`;
            this.changed.fire(undefined);
            return;
        }
        this.tree = buildTree(response.rows);
        const truncated = response.rows.filter((r) => hasFlag(r, FLAG_TRUNCATED)).length;
        this.view.message = countMessage(countNodes(this.tree), truncated, SERVER_MAX_ROWS);
        this.changed.fire(undefined);
    }

    clear(): void {
        this.root = undefined;
        void this.reload();
    }

    // ------------------------------------------------------------
    // TreeDataProvider
    // ------------------------------------------------------------

    getTreeItem(node: TreeNode): vscode.TreeItem {
        const look = describeRow(node.row, this._direction);
        const item = new vscode.TreeItem(look.label);
        item.description = look.description;
        item.tooltip = look.tooltip;
        item.contextValue = look.contextValue;
        item.iconPath = look.iconColor
            ? new vscode.ThemeIcon(look.icon, new vscode.ThemeColor(look.iconColor))
            : new vscode.ThemeIcon(look.icon);
        // 根は開いた状態で出す。子がある（か、打ち切りで続きがある）節点は閉じた状態。再帰は開けない
        const isRoot = node === this.tree;
        const mayHaveChildren = node.children.length > 0 || hasFlag(node.row, FLAG_TRUNCATED);
        item.collapsibleState = !look.expandable || !(isRoot || mayHaveChildren)
            ? vscode.TreeItemCollapsibleState.None
            : isRoot
                ? vscode.TreeItemCollapsibleState.Expanded
                : vscode.TreeItemCollapsibleState.Collapsed;
        if (node.row.file !== '') {
            item.command = {
                command: 'jche.openCallSite',
                title: '呼び出している行を開く',
                arguments: [node],
            };
        }
        return item;
    }

    async getChildren(node?: TreeNode): Promise<TreeNode[]> {
        if (!node) {
            return this.tree ? [this.tree] : [];
        }
        if (node.children.length > 0 || !hasFlag(node.row, FLAG_TRUNCATED) || this.fetched.has(node)) {
            return node.children;
        }
        // 打ち切った節点。ここを根にして続きを取り寄せる
        const root = this.root;
        if (!root) {
            return [];
        }
        this.fetched.add(node);
        const response = await root.session.tree(node.row.key, this._direction, this.depth());
        if (!response.ok) {
            this.log.warn(`続きを取り寄せられませんでした: ${node.row.key}（${response.reason}）`);
            return [];
        }
        const sub = buildTree(response.rows);
        if (!sub) {
            return [];
        }
        // 取り寄せた木の根は node 自身。子だけを、深さを付け直してつなぐ
        for (const child of sub.children) {
            node.children.push(rebase(child, node, node.row.depth + 1));
        }
        return node.children;
    }

    getParent(node: TreeNode): TreeNode | undefined {
        return node.parent;
    }

    /** 節点の呼び出し箇所（ファイルと行）をエディタで開く */
    async openCallSite(node: TreeNode, root: RootSpec | undefined = this.root): Promise<void> {
        if (!root || node.row.file === '') {
            return;
        }
        await openAt(root.session.folder, node.row.file, node.row.line);
    }

    dispose(): void {
        this.view.dispose();
        this.changed.dispose();
    }
}

/** 取り寄せた部分木を、既存の節点の下につなぎ直す（深さも付け直す） */
function rebase(node: TreeNode, parent: TreeNode, depth: number): TreeNode {
    const row: ServerRow = { ...node.row, depth };
    const moved: TreeNode = { row, parent, children: [] };
    for (const child of node.children) {
        moved.children.push(rebase(child, moved, depth + 1));
    }
    return moved;
}

/** project.root からの相対パスと 1 始まりの行を開く */
export async function openAt(folder: vscode.WorkspaceFolder, relativeFile: string, line: number): Promise<void> {
    const absolute = path.isAbsolute(relativeFile) ? relativeFile : path.join(folder.uri.fsPath, relativeFile);
    const uri = vscode.Uri.file(absolute);
    const position = new vscode.Position(Math.max(0, line - 1), 0);
    try {
        await vscode.window.showTextDocument(uri, { selection: new vscode.Range(position, position), preserveFocus: false });
    } catch (e) {
        vscode.window.showWarningMessage(`開けませんでした: ${absolute}（${e instanceof Error ? e.message : String(e)}）`);
    }
}
