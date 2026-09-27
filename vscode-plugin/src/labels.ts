// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
import * as path from 'node:path';
import {
    ACCESS_READ, ACCESS_READ_WRITE, ACCESS_WRITE, FLAG_ACCESS, FLAG_FIELD, FLAG_GUESSED, FLAG_INITIALIZER, FLAG_MATCH,
    FLAG_NO_METHOD, FLAG_NO_SOURCE, FLAG_RECURSIVE, FLAG_TRUNCATED, hasFlag, type ServerRow,
} from './server/response';
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

/**
 * 節点の見た目。フィールドの木（docs/field-callers-qa.md）の根と深さ 1 の行は、印で見分けて {@link describeFieldRow} に回す
 */
export function describeRow(row: ServerRow, direction: Direction): RowAppearance {
    if (hasFlag(row, FLAG_FIELD) || hasFlag(row, FLAG_ACCESS)) {
        return describeFieldRow(row);
    }
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

/**
 * 参照の種類（サーバーが英語で返す理由の列）を、表示の言語にする。知らない値はそのまま出す
 * （CSV のセルと同じ英語。サーバーだけが新しいときでも、黙って消さない）
 */
export function accessLabel(reason: string): string {
    switch (reason) {
        case ACCESS_READ:
            return t('label.access.read');
        case ACCESS_WRITE:
            return t('label.access.write');
        case ACCESS_READ_WRITE:
            return t('label.access.readWrite');
        default:
            return reason;
    }
}

/**
 * フィールドの木の、根（フィールド）と深さ 1（そのフィールドを読み書きしているメソッド）の行の見た目。
 * 深さ 2 からは呼び出し元なので、ふつうの行（{@link describeRow}）になる。
 *
 * - 根: アイコンは `symbol-field`。「根にする」「宣言を開く」は出さない（メソッドではないため）
 * - 深さ 1: 説明にファイル:行と、読み書きの別（«読み取り» など）。メソッドなら「根にする」ができる
 * - キーの無い行（宣言の初期化子・囲むメソッドの無い参照）: サーバーが名前を付けないので、ここで付ける
 */
export function describeFieldRow(row: ServerRow): RowAppearance {
    const place = row.file !== '' ? (row.line > 0 ? `${path.basename(row.file)}:${row.line}` : path.basename(row.file)) : '';
    if (hasFlag(row, FLAG_FIELD)) {
        const noSource = hasFlag(row, FLAG_NO_SOURCE);
        const tooltipLines = [row.key];
        if (row.file !== '') {
            tooltipLines.push(t('label.tooltip.fieldDeclaredIn', row.file));
        } else if (noSource) {
            tooltipLines.push(t('label.tooltip.noSource'));
        }
        return {
            icon: 'symbol-field',
            label: row.label,
            description: [place, noSource ? t('label.note.noSource') : ''].filter((s) => s !== '').join('  '),
            tooltip: tooltipLines.join('\n'),
            contextValue: 'field',
            expandable: true,
        };
    }
    const initializer = hasFlag(row, FLAG_INITIALIZER);
    const noMethod = hasFlag(row, FLAG_NO_METHOD);
    const truncated = hasFlag(row, FLAG_TRUNCATED);
    const recursive = hasFlag(row, FLAG_RECURSIVE);
    const access = accessLabel(row.reason);
    const label = initializer ? t('label.fieldInitializer') : noMethod ? t('label.noMethod') : row.label;
    const context = ['access'];
    if (row.key !== '') {
        // 参照しているメソッド。「根にする」「宣言を開く」ができる（初期化子・メソッドの外の行はできない）
        context.push('method');
        if (row.file !== '') {
            context.push('source');
        }
    }
    if (truncated) {
        context.push('truncated');
    }
    const tooltipLines = [row.key !== '' ? row.key : label];
    if (row.file !== '') {
        tooltipLines.push(t('label.tooltip.accessSite', row.file, row.line));
    }
    if (access !== '') {
        tooltipLines.push(t('label.tooltip.access', access));
    }
    if (truncated) {
        tooltipLines.push(t('label.tooltip.truncated'));
    }
    return {
        icon: row.reason === ACCESS_READ ? 'eye' : 'edit',
        label,
        description: [place, access !== '' ? `\u00ab${access}\u00bb` : ''].filter((s) => s !== '').join('  '),
        tooltip: tooltipLines.join('\n'),
        contextValue: context.join(' '),
        expandable: !recursive && row.key !== '',
    };
}

/** ビューの見出し（起点のメソッドと方向）。`TreeView#description` に出す */
export function viewDescription(rootLabel: string, rootLine: number, direction: Direction): string {
    const line = rootLine > 0 ? t('label.view.rootLine', rootLine) : '';
    return t('label.view.description', rootLabel, line, directionLabel(direction));
}

/** フィールドの木の見出し。読み書きの絞り込みが効いていればそれも添える */
export function fieldViewDescription(fieldLabel: string, access: 'all' | 'read' | 'write'): string {
    if (access === 'read') {
        return t('label.view.fieldReads', fieldLabel);
    }
    if (access === 'write') {
        return t('label.view.fieldWrites', fieldLabel);
    }
    return t('label.view.field', fieldLabel);
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
