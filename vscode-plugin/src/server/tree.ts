// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
import { FLAG_TRUNCATED, hasFlag, type ServerRow } from './response';

/** 木の節点 */
export interface TreeNode {
    readonly row: ServerRow;
    readonly parent: TreeNode | undefined;
    readonly children: TreeNode[];
}

/** 深さの上限で打ち切られた節点。開くときに、ここを根にして問い合わせ直す */
export function isTruncated(node: TreeNode): boolean {
    return hasFlag(node.row, FLAG_TRUNCATED);
}

/**
 * サーバーが返した行（深さ優先の並び）を木に組み直す。
 *
 * 行には深さしか入っていないが、深さ優先で並んでいるので「直前に出た1つ浅い行」が親になる。
 * 行が無ければ根は undefined。
 */
export function buildTree(rows: readonly ServerRow[]): TreeNode | undefined {
    let root: TreeNode | undefined;
    let previous: TreeNode | undefined;
    for (const row of rows) {
        if (!previous) {
            root = { row, parent: undefined, children: [] };
            previous = root;
            continue;
        }
        let parent: TreeNode | undefined = previous;
        // 直前の行から、深さが1つ浅くなるまでさかのぼる
        while (parent && parent.row.depth >= row.depth) {
            parent = parent.parent;
        }
        if (!parent) {
            continue;   // 行が壊れている。捨てて先へ進む
        }
        const node: TreeNode = { row, parent, children: [] };
        parent.children.push(node);
        previous = node;
    }
    return root;
}

/** 節点の総数（画面下の件数表示に使う） */
export function countNodes(node: TreeNode | undefined): number {
    if (!node) {
        return 0;
    }
    let total = 1;
    for (const child of node.children) {
        total += countNodes(child);
    }
    return total;
}

/** 深さ優先で最初に見つかる打ち切り節点。無ければ undefined */
export function firstTruncated(node: TreeNode | undefined): TreeNode | undefined {
    if (!node) {
        return undefined;
    }
    if (isTruncated(node)) {
        return node;
    }
    for (const child of node.children) {
        const found = firstTruncated(child);
        if (found) {
            return found;
        }
    }
    return undefined;
}
