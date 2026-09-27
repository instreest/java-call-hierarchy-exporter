// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { fieldDeclarationLine, type SymbolLike } from '../src/fieldSymbols';

// vscode.SymbolKind の値（Class=4・Method=5・Field=7・Constant=13・EnumMember=21）
const CLASS = 4;
const METHOD = 5;
const FIELD = 7;

function at(line: number) {
    return { start: { line } };
}

test('文書シンボル（入れ子の形）からフィールドの宣言の行を探す', () => {
    const symbols: SymbolLike[] = [{
        name: 'Order', kind: CLASS, range: at(1), children: [
            { name: 'status', kind: FIELD, range: at(2), selectionRange: at(2) },
            { name: 'getStatus()', kind: METHOD, range: at(4) },
            { name: 'Inner', kind: CLASS, range: at(14), children: [
                { name: 'status', kind: FIELD, range: at(15) },
            ] },
        ],
    }];
    assert.equal(fieldDeclarationLine(symbols, 'app.Order', 'status'), 2);
    assert.equal(fieldDeclarationLine(symbols, 'app.Order.Inner', 'status'), 15, '入れ子の型は単純名で見分ける');
    assert.equal(fieldDeclarationLine(symbols, 'app.Order', 'getStatus'), undefined, 'メソッドはフィールドではない');
    assert.equal(fieldDeclarationLine(symbols, 'app.Other', 'status'), undefined, '決まらなければ推測で選ばない');
});

test('平たい形（SymbolInformation）と、拡張が無いとき', () => {
    const flat: SymbolLike[] = [
        { name: 'count', kind: FIELD, containerName: 'Order', location: { range: at(7) } },
    ];
    assert.equal(fieldDeclarationLine(flat, 'app.Order', 'count'), 7);
    assert.equal(fieldDeclarationLine(undefined, 'app.Order', 'count'), undefined, '文書シンボルを返す拡張が無い');
    assert.equal(fieldDeclarationLine([], 'app.Order', 'count'), undefined);
});
