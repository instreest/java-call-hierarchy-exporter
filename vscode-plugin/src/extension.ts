// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
import type * as vscode from 'vscode';

/**
 * 拡張の入口。
 *
 * まだ画面は無い（docs/vscode-plugin-design.md §12 の M3 で足す）。ここにあるのは
 * `vscode` に触らない層（`src/server`・`src/config`）だけで、それらは `test/vscode/run.sh` が
 * VSCode 無しで検査する。
 */
export function activate(_context: vscode.ExtensionContext): void {
    // M3 で、ビュー・コマンド・Language Status Item を登録する
}

export function deactivate(): void {
    // M3 で、常駐している子プロセスを CANCEL → SHUTDOWN で止める
}
