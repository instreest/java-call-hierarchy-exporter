// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
import * as vscode from 'vscode';
import type { Session, SessionState } from './session';
import { currentLanguage, t } from './messages';

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
        this.item.name = 'Java Call Hierarchy Exporter';
        this.render(undefined);
    }

    /** 表示しているセッションを差し替える（アクティブなエディタのフォルダに追従する） */
    render(session: Session | undefined): void {
        const state: SessionState | undefined = session?.state;
        this.item.busy = false;
        this.item.severity = vscode.LanguageStatusSeverity.Information;
        if (!session || !state || state.kind === 'unanalyzed') {
            this.item.text = t('status.unanalyzed');
            this.item.detail = session ? t('status.unanalyzed.detail', session.folder.name) : undefined;
            this.item.command = { command: 'jche.analyze', title: t('status.action.analyze') };
            return;
        }
        switch (state.kind) {
            case 'analyzing': {
                this.item.busy = true;
                const progress = state.total > 0 ? ` ${state.done.toLocaleString()}/${state.total.toLocaleString()}` : '';
                this.item.text = t('status.analyzing', state.label, progress);
                this.item.detail = session.folder.name;
                this.item.command = { command: 'jche.cancel', title: t('status.action.cancel') };
                return;
            }
            case 'analyzed': {
                // 時刻の書式も表示言語に合わせる（どちらも 24 時間表記）
                const time = state.at.toLocaleTimeString(
                    currentLanguage() === 'ja' ? 'ja-JP' : 'en-GB', { hour12: false });
                const dirty = state.dirty.size;
                if (dirty > 0) {
                    // 見えているものが古い。バナーの代わりにここで知らせる（docs/vscode-plugin-design.md §2.5）
                    this.item.severity = vscode.LanguageStatusSeverity.Warning;
                    this.item.text = t('status.analyzedDirty', time, dirty);
                    this.item.detail = t('status.analyzedDirty.detail', session.folder.name, dirty, time);
                } else {
                    this.item.text = t('status.analyzed', time);
                    this.item.detail = t('status.analyzed.detail', session.folder.name,
                        state.methods.toLocaleString(), state.edges.toLocaleString(), state.configLabel);
                }
                this.item.command = { command: 'jche.analyze', title: t('status.action.reanalyze') };
                return;
            }
            case 'failed': {
                this.item.severity = vscode.LanguageStatusSeverity.Error;
                this.item.text = t('status.failed');
                this.item.detail = state.reason;
                this.item.command = { command: 'jche.openLog', title: t('status.action.openLog') };
                return;
            }
        }
    }

    dispose(): void {
        this.item.dispose();
    }
}
