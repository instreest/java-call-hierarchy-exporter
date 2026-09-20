// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0

/**
 * 画面とログに出す文言。既定は**英語**で、日本語を選んだときだけ日本語になる。
 *
 * 解析本体（`jche.util.Messages`）・Eclipse プラグイン（`jche.eclipse.Messages`）と
 * 同じ作法にそろえてある。英語の表を土台にして日本語を重ね、差し込みは `{0}` `{1}` …。
 *
 * ## なぜ `vscode.l10n` を使わないのか
 * `src/server/` と `src/config.ts` は **`vscode` モジュールに触らない層**で、
 * Node だけでコンパイル・実行できることを `test/vscode/run.sh` が検査している
 * （`docs/vscode-plugin-design.md`）。`vscode.l10n` はそこへ持ち込めない。
 *
 * 画面の層だけ `vscode.l10n`、下の層は自前、と分けると表が 2 つになり、
 * キーの対応を 2 通り検査することになる。1 つの仕組みにそろえた。
 * 文言の表は TypeScript のモジュール（`messages.en.ts` / `messages.ja.ts`）なので、
 * esbuild が `dist/extension.js` に束ねる。配布物に別ファイルを入れる必要が無い
 * （解析本体で properties を採らなかったのと同じ理由。`docs/nls-qa.md` の Q3）。
 *
 * `package.json` の寄与（ビューの名前・コマンドの見出し・設定の説明）は VSCode 本体が
 * 起動時に読むので、そちらだけは VSCode の仕組み（`package.nls.json` / `package.nls.ja.json`）を使う。
 */
import { EN } from './messages.en';
import { JA } from './messages.ja';

/** 訳を持っている言語 */
export type Language = 'en' | 'ja';

/** 土台の言語。訳が足りない項目はここへ落ちる */
const BASE: Language = 'en';

let language: Language = BASE;
let texts: Record<string, string> = EN;

/**
 * 表示言語を決める。知らない言語は英語のまま。
 *
 * 画面の層が `vscode.env.language`（`ja`、`ja-jp`、`en-US` など）を渡す。
 * この層は `vscode` に触らないので、値だけを受け取る。
 */
export function setLanguage(raw: string | undefined): void {
    const lang = normalize(raw);
    if (lang !== language) {
        language = lang;
        texts = (lang === 'ja') ? { ...EN, ...JA } : EN;
    }
}

/** いま文言を出している言語。解析の子プロセスにも同じ言語を渡すために使う */
export function currentLanguage(): Language {
    return language;
}

/**
 * 文言を引いて `{0}` `{1}` … を差し替える。
 *
 * 知らないキーは `!キー!` を返す（画面で気づけるように）。
 * `test/vscode/run.sh` が、使っているキーと表のキーを突き合わせる。
 */
export function t(key: string, ...args: unknown[]): string {
    const text = texts[key];
    if (text === undefined) {
        return `!${key}!`;
    }
    if (args.length === 0) {
        return text;
    }
    return text.replace(/\{(\d+)\}/g, (whole, index: string) => {
        const value = args[Number(index)];
        return (value === undefined) ? whole : String(value);
    });
}

/**
 * 言語の指定を `en` / `ja` に揃える。
 *
 * 地域（`ja-jp`）は言語だけ見る。地域まで分ける訳を持つ予定が無く、
 * 持つときになってから足せばよい。訳を持たない言語は土台（英語）にする。
 */
function normalize(raw: string | undefined): Language {
    const value = (raw ?? '').trim().toLowerCase().split(/[-_.]/)[0];
    return (value === 'ja') ? 'ja' : BASE;
}
