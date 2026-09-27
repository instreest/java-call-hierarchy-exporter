// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
import * as path from 'node:path';
import * as vscode from 'vscode';
import { fieldDeclarationLine, type SymbolLike } from './fieldSymbols';
import { accessWord, describeFilters, fromStored, toWords, type FieldAccess, type FilterSettings } from './filters';
import { countMessage, describeRow, fieldViewDescription, viewDescription, type Direction } from './labels';
import { FLAG_FIELD, FLAG_INITIALIZER, FLAG_TRUNCATED, hasFlag, type ServerRow } from './server/response';
import { buildTree, countNodes, type TreeNode } from './server/tree';
import type { Session, TreeDirection } from './session';
import { t } from './messages';

/** ビューに出している木の起点 */
export interface RootSpec {
    readonly session: Session;
    readonly key: string;
    readonly label: string;
    readonly file: string;
    readonly line: number;
    /**
     * 起点がフィールドか（キーは `型FQN#フィールド名`）。省略はメソッド。
     * フィールドの木は根がフィールド、深さ 1 がそれを読み書きしているメソッド、その下が呼び出し元で、
     * 向きは呼び出し元だけ（docs/field-callers-qa.md）
     */
    readonly kind?: 'method' | 'field';
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
    private _filters: FilterSettings;
    /** フィールドの木で出す参照（`access=`）。フィールドを選び直したら `all` に戻す */
    private _access: FieldAccess = 'all';
    /** 続きを取り寄せ済みの節点 */
    private readonly fetched = new WeakSet<TreeNode>();

    constructor(private readonly log: vscode.LogOutputChannel, private readonly state: vscode.Memento) {
        this.view = vscode.window.createTreeView('jche.callers', { treeDataProvider: this, showCollapseAll: true });
        this._filters = fromStored(state.get('filters'), this.defaultDepth());
        void vscode.commands.executeCommand('setContext', 'jche.direction', this._direction);
        void vscode.commands.executeCommand('setContext', 'jche.hasTree', false);
        void vscode.commands.executeCommand('setContext', 'jche.fieldRoot', false);
    }

    get filters(): FilterSettings {
        return this._filters;
    }

    /** 条件を変えて木を取り直す。ワークスペースに覚える（次に開いたときも同じ） */
    async setFilters(filters: FilterSettings): Promise<void> {
        this._filters = filters;
        await this.state.update('filters', filters);
        if (this.root) {
            await this.reload();
        }
    }

    private filterWords(): string[] {
        const words = toWords(this._filters);
        return this.isFieldRoot ? [...words, accessWord(this._access)] : words;
    }

    /** 起点がフィールドか */
    get isFieldRoot(): boolean {
        return this.root?.kind === 'field';
    }

    get access(): FieldAccess {
        return this._access;
    }

    /** フィールドの木で出す参照を変えて取り直す（解析はやり直さない） */
    async setAccess(access: FieldAccess): Promise<void> {
        this._access = access;
        if (this.isFieldRoot) {
            await this.reload();
        }
    }

    /** サーバーへ送る向き。フィールドなら `field` */
    private requestDirection(): TreeDirection {
        return this.isFieldRoot ? 'field' : this._direction;
    }

    get direction(): Direction {
        return this._direction;
    }

    get currentRoot(): RootSpec | undefined {
        return this.root;
    }

    private defaultDepth(): number {
        return vscode.workspace.getConfiguration('jche').get<number>('depth', 5);
    }

    /** 起点を差し替えて木を取り直す */
    async show(root: RootSpec): Promise<void> {
        this.root = root;
        if (root.kind === 'field') {
            // 前のフィールドで選んだ絞り込みを持ち越さない（行が足りないことに気づけなくなる）
            this._access = 'all';
        }
        await vscode.commands.executeCommand('setContext', 'jche.fieldRoot', root.kind === 'field');
        await this.reload();
        await vscode.commands.executeCommand('jche.callers.focus');
    }

    async toggleDirection(): Promise<void> {
        if (this.isFieldRoot) {
            // フィールドの木は呼び出し元の向きしか無い（フィールドの「呼び出し先」には意味が無い）
            vscode.window.showInformationMessage(t('view.fieldDirectionFixed'));
            return;
        }
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
            await vscode.commands.executeCommand('setContext', 'jche.hasTree', false);
            this.changed.fire(undefined);
            return;
        }
        const field = root.kind === 'field';
        this.view.description = field
            ? fieldViewDescription(root.label, this._access)
            : viewDescription(root.label, root.line, this._direction);
        this.view.message = t('view.fetching');
        const response = await root.session.tree(root.key, this.requestDirection(), this.filterWords());
        if (!response.ok) {
            this.tree = undefined;
            this.view.message = failureMessage(response.reason, root);
            await vscode.commands.executeCommand('setContext', 'jche.hasTree', false);
            this.changed.fire(undefined);
            return;
        }
        this.tree = buildTree(response.rows);
        const truncated = response.rows.filter((r) => hasFlag(r, FLAG_TRUNCATED)).length;
        const filterNote = describeFilters(this._filters);
        // 根（フィールド）だけ。「どこからも使われていない」とは言い切れない（リフレクションやフレームワークの
        // 読み書きはソースに現れない）ので、何を見て「無い」と言っているのかを添える
        const emptyField = field && response.rows.length <= 1
            ? (this._access === 'all' ? t('view.fieldNoAccesses') : t('view.fieldNoAccessesFiltered'))
            : '';
        this.view.message = [filterNote, emptyField, countMessage(countNodes(this.tree), truncated, SERVER_MAX_ROWS)]
            .filter((s) => s !== '').join('\n');
        await vscode.commands.executeCommand('setContext', 'jche.hasTree', this.tree !== undefined);
        this.changed.fire(undefined);
    }

    /** 解析し直したあと、⚠ を消すために描き直す（木は取り直さない） */
    refreshDecorations(): void {
        this.changed.fire(undefined);
    }

    /** いま見えている木を、同じ条件で CSV に書く */
    async exportCsv(): Promise<void> {
        const root = this.root;
        if (!root || !this.tree) {
            vscode.window.showInformationMessage(t('view.showTreeFirst'));
            return;
        }
        const safe = root.label.replace(/[^\w.]+/g, '_').replace(/^_+|_+$/g, '');
        const target = await vscode.window.showSaveDialog({
            defaultUri: vscode.Uri.file(path.join(root.session.folder.uri.fsPath, `${this.requestDirection()}-${safe}.csv`)),
            filters: { CSV: ['csv'] },
            title: t('view.exportTitle'),
        });
        if (!target) {
            return;
        }
        const response = await root.session.export(root.key, this.requestDirection(), target.fsPath, this.filterWords());
        if (!response.ok) {
            vscode.window.showErrorMessage(t('view.exportFailed', response.reason));
            return;
        }
        const answer = await vscode.window.showInformationMessage(
            t('view.exportDone', response.field('rows'), target.fsPath), t('view.action.open'));
        if (answer) {
            await vscode.window.showTextDocument(target);
        }
    }

    clear(): void {
        this.root = undefined;
        void vscode.commands.executeCommand('setContext', 'jche.fieldRoot', false);
        void this.reload();
    }

    // ------------------------------------------------------------
    // TreeDataProvider
    // ------------------------------------------------------------

    getTreeItem(node: TreeNode): vscode.TreeItem {
        // フィールドの木の深さ 2 からは呼び出し元なので、向きは呼び出し元として描く
        const look = describeRow(node.row, this.isFieldRoot ? 'callers' : this._direction);
        const item = new vscode.TreeItem(look.label);
        item.description = look.description;
        item.tooltip = look.tooltip;
        item.contextValue = look.contextValue;
        item.iconPath = look.iconColor
            ? new vscode.ThemeIcon(look.icon, new vscode.ThemeColor(look.iconColor))
            : new vscode.ThemeIcon(look.icon);
        // 解析後に変更されたファイルの節点。古いことを理由にグレーアウトはしない（読めなくなるだけ）
        if (node.row.file !== '' && this.root?.session.isDirty(node.row.file)) {
            item.iconPath = new vscode.ThemeIcon('warning', new vscode.ThemeColor('list.warningForeground'));
            item.tooltip = t('view.staleTooltip', look.tooltip, node.row.file);
        }
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
                title: t('view.openCallSite'),
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
        // フィールドの木で打ち切られるのはメソッドの行なので、続きはそのメソッドの呼び出し元
        const response = await root.session.tree(node.row.key, this.isFieldRoot ? 'callers' : this._direction,
            this.filterWords());
        if (!response.ok) {
            this.log.warn(t('view.expandFailed', node.row.key, response.reason));
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
        if (root.kind === 'field' && (hasFlag(node.row, FLAG_FIELD) || hasFlag(node.row, FLAG_INITIALIZER))) {
            // フィールドの宣言の行は解析結果に無い（キャッシュが持たない）ので、エディタの文書シンボルから探す
            await openFieldDeclaration(root.session.folder, node.row.file, root.key);
            return;
        }
        await openAt(root.session.folder, node.row.file, node.row.line);
    }

    dispose(): void {
        this.view.dispose();
        this.changed.dispose();
    }
}

/** 木を取り寄せられなかったときの文言 */
function failureMessage(reason: string, root: RootSpec): string {
    if (reason === 'not-analyzed') {
        return t('view.notAnalyzed');
    }
    if (reason === 'not-found') {
        return root.kind === 'field' ? t('view.fieldNotFound', root.key) : t('view.methodNotFound', root.key);
    }
    if (reason === 'stale-cache') {
        // 解析し直しが途中で終わり、キャッシュだけが新しい。フィールドの参照はキャッシュから読むので断ってくる
        return t('view.staleCache');
    }
    return t('view.fetchFailed', reason);
}

/**
 * フィールドの宣言を開く。行は Java の拡張の文書シンボルから探し（`fieldSymbols.ts`）、
 * 見つからなければ（拡張が無い・決まらない）ファイルの先頭を開く
 */
async function openFieldDeclaration(folder: vscode.WorkspaceFolder, relativeFile: string, fieldKey: string): Promise<void> {
    const hash = fieldKey.lastIndexOf('#');
    const absolute = path.isAbsolute(relativeFile) ? relativeFile : path.join(folder.uri.fsPath, relativeFile);
    let line: number | undefined;
    try {
        const symbols = await vscode.commands.executeCommand<SymbolLike[] | undefined>(
            'vscode.executeDocumentSymbolProvider', vscode.Uri.file(absolute));
        line = fieldDeclarationLine(symbols, fieldKey.substring(0, hash), fieldKey.substring(hash + 1));
    } catch {
        line = undefined;   // 文書シンボルを返す拡張が無い。先頭を開く
    }
    await openAt(folder, relativeFile, line === undefined ? 1 : line + 1);
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
        vscode.window.showWarningMessage(
            t('view.openFailed', absolute, e instanceof Error ? e.message : String(e)));
    }
}
