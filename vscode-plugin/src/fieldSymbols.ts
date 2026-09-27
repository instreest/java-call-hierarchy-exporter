// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0

/**
 * フィールドの宣言の行を、エディタの文書シンボル（`vscode.executeDocumentSymbolProvider` の結果）から探す。
 *
 * 解析結果（キャッシュ）はフィールドの宣言の行を持たない（V 行に行番号が無い）ので、フィールドの木の根を開くときは
 * これで行を探す。文書シンボルを返すのは Java の拡張（Language Support for Java など）で、入っていなければ
 * 何も返らず、呼び出し側はファイルの先頭を開く（docs/field-callers-qa.md の Q13）。
 *
 * `vscode` に触らないので Node だけで検査できる。形は `vscode.DocumentSymbol`（入れ子）と
 * `vscode.SymbolInformation`（平たい。`containerName` を持つ）の両方を受ける。
 */
export interface SymbolLike {
    readonly name: string;
    /** `vscode.SymbolKind` の値 */
    readonly kind: number;
    /** DocumentSymbol の形 */
    readonly range?: { readonly start: { readonly line: number } };
    readonly selectionRange?: { readonly start: { readonly line: number } };
    readonly children?: readonly SymbolLike[];
    /** SymbolInformation の形 */
    readonly location?: { readonly range: { readonly start: { readonly line: number } } };
    readonly containerName?: string;
}

/** `vscode.SymbolKind` のうち、フィールドとして扱うもの（Property・Field・Constant・EnumMember） */
const FIELD_KINDS = new Set([6, 7, 13, 21]);

/**
 * フィールドの宣言の行（0 始まり）。見つからない・1 つに決まらなければ undefined。
 *
 * 所有型の単純名（`app.Order.Inner` なら `Inner`）を囲む型の名前に持つものを選ぶ。囲む型の名前が分からない
 * 形でも、その名前のフィールドが 1 つしか無ければそれを採る。複数あって決まらなければ、推測で選ばない。
 *
 * @param ownerFqn  フィールドを宣言している型（`型FQN#フィールド名` の `#` の前）
 * @param fieldName フィールド名
 */
export function fieldDeclarationLine(symbols: readonly SymbolLike[] | undefined, ownerFqn: string,
                                     fieldName: string): number | undefined {
    if (!symbols || symbols.length === 0) {
        return undefined;
    }
    const ownerSimple = ownerFqn.substring(ownerFqn.lastIndexOf('.') + 1);
    const all: { line: number; container: string | undefined }[] = [];
    collect(symbols, undefined, fieldName, all);
    const inOwner = all.filter((c) => c.container === ownerSimple);
    if (inOwner.length === 1) {
        return inOwner[0].line;
    }
    if (inOwner.length === 0 && all.length === 1) {
        return all[0].line;
    }
    return undefined;
}

function collect(symbols: readonly SymbolLike[], parent: string | undefined, fieldName: string,
                 out: { line: number; container: string | undefined }[]): void {
    for (const s of symbols) {
        if (FIELD_KINDS.has(s.kind) && s.name === fieldName) {
            const line = s.selectionRange?.start.line ?? s.range?.start.line ?? s.location?.range.start.line;
            if (line !== undefined) {
                out.push({ line, container: parent ?? simpleOf(s.containerName) });
            }
        }
        if (s.children && s.children.length > 0) {
            collect(s.children, simpleOf(s.name), fieldName, out);
        }
    }
}

/** 型の名前の単純名。`Order.Inner` や `Order<T>` のような綴りでも、最後の型名だけにする */
function simpleOf(name: string | undefined): string | undefined {
    if (name === undefined) {
        return undefined;
    }
    const plain = name.replace(/<.*$/, '').trim();
    return plain.substring(plain.lastIndexOf('.') + 1);
}
