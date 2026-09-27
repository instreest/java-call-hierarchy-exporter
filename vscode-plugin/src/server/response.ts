// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
import { SEP, unescape } from './wire';

/** 印（flags 列）。画面のアイコンや色はこれで決まる */
export const FLAG_RECURSIVE = 'recursive';
export const FLAG_TRUNCATED = 'truncated';
export const FLAG_GUESSED = 'guessed';
export const FLAG_MATCH = 'match';
export const FLAG_NO_SOURCE = 'nosource';
/** フィールドの木の根（フィールドそのもの）。サーバーの `jche.server.FieldTree` と同じ綴り */
export const FLAG_FIELD = 'field';
/** フィールドの木の深さ 1（そのフィールドを参照しているメソッド）。理由の列が read / write / read/write */
export const FLAG_ACCESS = 'access';
/** 参照を囲むメソッドが特定できない行（キーが空） */
export const FLAG_NO_METHOD = 'nomethod';
/** 宣言の初期化子の行（キーが空） */
export const FLAG_INITIALIZER = 'initializer';

/** フィールドの木の理由の列（サーバーの `FieldTree` と同じ綴り。表示のときに訳す） */
export const ACCESS_READ = 'read';
export const ACCESS_WRITE = 'write';
export const ACCESS_READ_WRITE = 'read/write';

/**
 * サーバーが返す木の1行（`R` 行）。
 *
 * 画面はこの行だけを見て描く。メソッドID やグラフはサーバー側にあり、こちらへは来ない。
 */
export interface ServerRow {
    /** 根からの深さ。行は深さ優先の順で来るので、これだけで木に組み直せる */
    readonly depth: number;
    /**
     * メソッドのキー（型FQN#名前(引数)）。次の問い合わせの起点に使える。
     * フィールドの木の根ではフィールドのキー（型FQN#フィールド名）、初期化子・囲むメソッドの無い行では空
     */
    readonly key: string;
    readonly label: string;
    /** 呼び出している場所のファイル（プロジェクトルートからの相対）。無ければ空 */
    readonly file: string;
    /** 呼び出している行。分からなければ宣言の行 */
    readonly line: number;
    /**
     * 解決の理由（DATAFLOW_FACTORY 等）。ふつうの呼び出しでは空。
     * フィールドの木の深さ 1 では参照の種類（`ACCESS_READ` / `ACCESS_WRITE` / `ACCESS_READ_WRITE`）
     */
    readonly reason: string;
    readonly flags: readonly string[];
}

/** `R<TAB>深さ<TAB>キー<TAB>表示名<TAB>ファイル<TAB>行<TAB>理由<TAB>印` を読む。壊れていれば undefined */
export function parseRow(parts: readonly string[]): ServerRow | undefined {
    if (parts.length < 7) {
        return undefined;
    }
    const depth = Number.parseInt(parts[1], 10);
    const line = Number.parseInt(parts[5], 10);
    if (Number.isNaN(depth) || Number.isNaN(line)) {
        return undefined;
    }
    const flags = (parts.length > 7 && parts[7] !== '') ? parts[7].split(',') : [];
    return {
        depth,
        key: unescape(parts[2]),
        label: unescape(parts[3]),
        file: unescape(parts[4]),
        line,
        reason: unescape(parts[6]),
        flags,
    };
}

export function hasFlag(row: ServerRow, flag: string): boolean {
    return row.flags.includes(flag);
}

/** 要求1件ぶんの応答（`OK` か `NG` の行と、それまでに来た `R` 行） */
export class ServerResponse {
    private constructor(
        readonly ok: boolean,
        private readonly fields: ReadonlyMap<string, string>,
        /** `TREE` の結果。深さ優先の順に並んでいる */
        readonly rows: readonly ServerRow[],
        /** 応答の行そのもの。ログや不具合の調査用 */
        readonly raw: string,
    ) {}

    static of(line: string, rows: readonly ServerRow[]): ServerResponse {
        const parts = line.split(SEP);
        const ok = parts[0] === 'OK';
        const fields = new Map<string, string>();
        const words: string[] = [];
        for (let i = 1; i < parts.length; i++) {
            const word = parts[i];
            const eq = word.indexOf('=');
            if (eq > 0) {
                fields.set(word.substring(0, eq), unescape(word.substring(eq + 1)));
            } else if (word !== '') {
                words.push(unescape(word));
            }
        }
        if (words.length > 0) {
            // NG の理由のように key=value でない語は、まとめて reason として持つ
            fields.set('reason', words.join(' '));
        }
        return new ServerResponse(ok, fields, rows, line);
    }

    /** 失敗の理由（`not-analyzed` など）。成功なら空 */
    get reason(): string {
        return this.fields.get('reason') ?? '';
    }

    field(name: string): string {
        return this.fields.get(name) ?? '';
    }

    numberField(name: string, fallback: number): number {
        const value = Number.parseInt(this.field(name).trim(), 10);
        return Number.isNaN(value) ? fallback : value;
    }
}
