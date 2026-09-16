// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
import * as vscode from 'vscode';
import type { Session, SessionState } from './session';

/**
 * 解析の状態を Language Status Item に出す（エディタ右下の `{}`。vscode-java の "Java: Ready" と同じ場所）。
 *
 * ビューを閉じていても状態が見える標準の置き場所である（docs/vscode-plugin-design.md §2.5）。
 * Java のファイルを開いているときだけ出る（selector）。
 */
export class StatusItem implements vscode.Disposable {
    private readonly item: vscode.LanguageStatusItem;

    constructor() {
        this.item = vscode.languages.createLanguageStatusItem('jche.status', { language: 'java' });
        this.item.name = 'Call Hierarchy Exporter';
        this.render(undefined);
    }

    /** 表示しているセッションを差し替える（アクティブなエディタのフォルダに追従する） */
    render(session: Session | undefined): void {
        const state: SessionState | undefined = session?.state;
        this.item.busy = false;
        this.item.severity = vscode.LanguageStatusSeverity.Information;
        if (!session || !state || state.kind === 'unanalyzed') {
            this.item.text = 'Call Hierarchy Exporter: 未解析';
            this.item.detail = session ? `${session.folder.name} はまだ解析していません` : undefined;
            this.item.command = { command: 'jche.analyze', title: '解析する' };
            return;
        }
        switch (state.kind) {
            case 'analyzing': {
                this.item.busy = true;
                const progress = state.total > 0 ? ` ${state.done.toLocaleString()}/${state.total.toLocaleString()}` : '';
                this.item.text = `Call Hierarchy Exporter: 解析中 ${state.label}${progress}`;
                this.item.detail = session.folder.name;
                this.item.command = { command: 'jche.cancel', title: '中止' };
                return;
            }
            case 'analyzed': {
                const time = state.at.toLocaleTimeString('ja-JP', { hour12: false });
                const dirty = state.dirty.size;
                if (dirty > 0) {
                    // 見えているものが古い。バナーの代わりにここで知らせる（docs/vscode-plugin-design.md §2.5）
                    this.item.severity = vscode.LanguageStatusSeverity.Warning;
                    this.item.text = `Call Hierarchy Exporter: ${time} 時点（${dirty} ファイル変更）`;
                    this.item.detail = `${session.folder.name} / 解析後に ${dirty} ファイルが変更されています。表示は ${time} 時点のものです`;
                } else {
                    this.item.text = `Call Hierarchy Exporter: ${time} 時点`;
                    this.item.detail = `${session.folder.name} / ${state.methods.toLocaleString()} メソッド / ${state.edges.toLocaleString()} 呼び出し / 設定: ${state.configLabel}`;
                }
                this.item.command = { command: 'jche.analyze', title: '再解析' };
                return;
            }
            case 'failed': {
                this.item.severity = vscode.LanguageStatusSeverity.Error;
                this.item.text = 'Call Hierarchy Exporter: 失敗';
                this.item.detail = state.reason;
                this.item.command = { command: 'jche.openLog', title: 'ログを開く' };
                return;
            }
        }
    }

    dispose(): void {
        this.item.dispose();
    }
}
