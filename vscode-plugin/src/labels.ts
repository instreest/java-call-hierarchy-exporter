// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
import * as path from 'node:path';
import { FLAG_GUESSED, FLAG_MATCH, FLAG_NO_SOURCE, FLAG_RECURSIVE, FLAG_TRUNCATED, hasFlag, type ServerRow } from './server/response';
import { t } from './messages';

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
    return t(direction === 'callers' ? 'label.direction.callers' : 'label.direction.callees');
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
        notes.push(t('label.note.recursive'));
    }
    if (guessed) {
        notes.push(row.reason !== '' ? t('label.note.guessedWith', row.reason) : t('label.note.guessed'));
    } else if (row.reason !== '') {
        notes.push(row.reason);
    }
    if (noSource) {
        notes.push(t('label.note.noSource'));
    }
    const description = [place, ...notes].filter((s) => s !== '').join('  ');

    const tooltipLines = [row.key];
    if (row.file !== '') {
        // 方向そのもので分ける。訳した見出しと比べると、日本語以外で必ず外れる
        tooltipLines.push(t(direction === 'callers' ? 'label.tooltip.callSite' : 'label.tooltip.calleeSite',
            row.file, row.line));
    } else if (noSource) {
        tooltipLines.push(t('label.tooltip.noSource'));
    }
    if (row.reason !== '') {
        tooltipLines.push(t('label.tooltip.reason', row.reason));
    }
    if (recursive) {
        tooltipLines.push(t('label.tooltip.recursive'));
    }
    if (truncated) {
        tooltipLines.push(t('label.tooltip.truncated'));
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
    const line = rootLine > 0 ? t('label.view.rootLine', rootLine) : '';
    return t('label.view.description', rootLabel, line, directionLabel(direction));
}

/** 件数の説明。`TreeView#message` に出す */
export function countMessage(shown: number, truncatedCount: number, maxRows: number): string {
    const parts = [t('label.count.shown', shown.toLocaleString())];
    if (truncatedCount > 0) {
        parts.push(t('label.count.truncated', truncatedCount.toLocaleString()));
    }
    if (shown >= maxRows) {
        parts.push(t('label.count.maxRows', maxRows.toLocaleString()));
    }
    return parts.join(' / ');
}
