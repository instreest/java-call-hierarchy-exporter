// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0

/**
 * プロトコルの文字列の逃がし方。サーバー側の `jche.server.Protocol` と対になる。
 *
 * 同じ規則を Java（サーバー・Eclipse プラグイン）と TypeScript（ここ）に書いているのは、
 * 拡張ホストが Node.js で、解析本体のクラスを読めないからである。規則は短いので、
 * 写す方が依存を作るより安い。変えるときは3か所を直すこと
 * （`test/server/run.sh`・`test/plugin-client/run.sh`・`test/vscode/run.sh` が往復で検査する）。
 */
export const SEP = '\t';

/** TAB 区切りを壊す文字を逃がす。undefined は空文字にする */
export function escape(value: string | undefined): string {
    if (!value) {
        return '';
    }
    let out = '';
    for (const c of value) {
        switch (c) {
            case '\\': out += '\\\\'; break;
            case '\t': out += '\\t'; break;
            case '\n': out += '\\n'; break;
            case '\r': out += '\\r'; break;
            default: out += c;
        }
    }
    return out;
}

/** `escape` の逆。受け取り側（サーバー）と同じ規則であることが大事 */
export function unescape(value: string | undefined): string {
    if (!value) {
        return '';
    }
    if (value.indexOf('\\') < 0) {
        return value;
    }
    let out = '';
    for (let i = 0; i < value.length; i++) {
        const c = value[i];
        if (c !== '\\' || i + 1 >= value.length) {
            out += c;
            continue;
        }
        const next = value[++i];
        switch (next) {
            case 't': out += '\t'; break;
            case 'n': out += '\n'; break;
            case 'r': out += '\r'; break;
            case '\\': out += '\\'; break;
            default: out += next;
        }
    }
    return out;
}

/** 要求の語を TAB でつなぐ。語の中の TAB・改行は逃がす */
export function join(words: readonly string[]): string {
    return words.map(escape).join(SEP);
}
