// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
import * as path from 'node:path';
import { FLAG_GUESSED, FLAG_MATCH, FLAG_NO_SOURCE, FLAG_RECURSIVE, FLAG_TRUNCATED, hasFlag, type ServerRow } from './server/response';

/**
 * 木の1行をどう見せるか。`vscode` に触らない純粋な関数にしてあるので、Node だけで検査できる。
 *
 * アイコンは VSCode の codicon の名前（`ThemeIcon` の id）。絵文字は使わない
 * （テーマとの相性とアクセシビリティのため。docs/vscode-plugin-design.md §5）。
 */
export interface RowAppearance {
    /** codicon の id */
    readonly icon: string;
    /** アイコンの色（`ThemeColor` の id）。無ければ既定色 */
    readonly iconColor?: string;
    readonly label: string;
    /** ラベルの右に薄く出す（呼び出し箇所の `File.java:12`） */
    readonly description: string;
    readonly tooltip: string;
    /** `view/item/context` の `when` で使う語（空白区切り） */
    readonly contextValue: string;
    /** 展開できるか（子がありうるか）。再帰は打ち切る */
    readonly expandable: boolean;
}

export type Direction = 'callers' | 'callees';

export function directionLabel(direction: Direction): string {
    return direction === 'callers' ? '呼び出し元' : '呼び出し先';
}

export function describeRow(row: ServerRow, direction: Direction): RowAppearance {
    const recursive = hasFlag(row, FLAG_RECURSIVE);
    const noSource = hasFlag(row, FLAG_NO_SOURCE);
    const guessed = hasFlag(row, FLAG_GUESSED);
    const truncated = hasFlag(row, FLAG_TRUNCATED);
    const matched = hasFlag(row, FLAG_MATCH);

    let icon = 'symbol-method';
    let iconColor: string | undefined;
    if (recursive) {
        icon = 'refresh';
    } else if (noSource) {
        icon = 'library';
    } else if (guessed) {
        icon = 'symbol-method';
        iconColor = 'list.warningForeground';
    } else if (matched) {
        iconColor = 'list.highlightForeground';
    }

    const context = ['method'];
    if (row.file !== '') {
        context.push('source');
    }
    if (truncated) {
        context.push('truncated');
    }

    const place = row.file !== '' ? `${path.basename(row.file)}:${row.line}` : '';
    const notes: string[] = [];
    if (recursive) {
        notes.push('再帰');
    }
    if (guessed) {
        notes.push(row.reason !== '' ? `推定: ${row.reason}` : '推定');
    } else if (row.reason !== '') {
        notes.push(row.reason);
    }
    if (noSource) {
        notes.push('ソースなし');
    }
    const description = [place, ...notes].filter((s) => s !== '').join('  ');

    const tooltipLines = [row.key];
    if (row.file !== '') {
        tooltipLines.push(`${directionLabel(direction) === '呼び出し元' ? '呼び出している行' : '呼び出されている行'}: ${row.file}:${row.line}`);
    } else if (noSource) {
        tooltipLines.push('ソースが無い（依存 jar か、解析対象の外）');
    }
    if (row.reason !== '') {
        tooltipLines.push(`解決の理由: ${row.reason}`);
    }
    if (recursive) {
        tooltipLines.push('再帰。ここで打ち切る');
    }
    if (truncated) {
        tooltipLines.push('深さの上限で打ち切り。開くと続きを取り寄せる');
    }

    return {
        icon,
        iconColor,
        label: row.label,
        description,
        tooltip: tooltipLines.join('\n'),
        contextValue: context.join(' '),
        expandable: !recursive,
    };
}

/** ビューの見出し（起点のメソッドと方向）。`TreeView#description` に出す */
export function viewDescription(rootLabel: string, rootLine: number, direction: Direction): string {
    const line = rootLine > 0 ? `（${rootLine}行目）` : '';
    return `${rootLabel}${line} の${directionLabel(direction)}`;
}

/** 件数の説明。`TreeView#message` に出す */
export function countMessage(shown: number, truncatedCount: number, maxRows: number): string {
    const parts = [`表示 ${shown.toLocaleString()} 件`];
    if (truncatedCount > 0) {
        parts.push(`深さの上限で打ち切った節点 ${truncatedCount.toLocaleString()} 件（開くと続きを取り寄せます）`);
    }
    if (shown >= maxRows) {
        parts.push(`${maxRows.toLocaleString()} 件で打ち切りました。絞り込みか深さを使ってください`);
    }
    return parts.join(' / ');
}
