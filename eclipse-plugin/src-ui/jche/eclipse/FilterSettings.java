// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import org.eclipse.jface.dialogs.IDialogSettings;

/**
 * 呼び出し元ツリーの絞り込み条件。
 *
 * <p><b>フィルタは再解析を起こさない。</b>すべて、できあがったスナップショットを読むときの
 * 絞り込みでしかない。だから解析中でも（古い結果に対して）その場で効く。
 *
 * <p>設定はビューごとに持ち、ワークスペースに保存する（次に開いたときも同じ条件）。
 */
final class FilterSettings {

    /** 型名・メソッド名・パッケージの部分一致（空なら絞り込まない） */
    String text = "";

    /** 木の深さの上限。これを超える枝は「…」で打ち切る */
    int maxDepth = 5;

    /** テストのソースフォルダにあるメソッドを呼び出し元として出すか */
    boolean includeTests;

    /** 推測（データフロー・リフレクション）で特定した呼び出し元を出すか */
    boolean includeGuessed = true;

    /** 設定ファイルの exclude.packages を画面にも効かせるか */
    boolean applyExcludePackages = true;

    /** 同じ呼び出し元メソッドを1回だけ出すか（false なら呼び出している行ごとに出す） */
    boolean dedupeCallers = true;

    FilterSettings copy() {
        FilterSettings c = new FilterSettings();
        c.text = text;
        c.maxDepth = maxDepth;
        c.includeTests = includeTests;
        c.includeGuessed = includeGuessed;
        c.applyExcludePackages = applyExcludePackages;
        c.dedupeCallers = dedupeCallers;
        return c;
    }

    void save(IDialogSettings settings) {
        settings.put("filter.text", text);
        settings.put("filter.maxDepth", maxDepth);
        settings.put("filter.includeTests", includeTests);
        settings.put("filter.includeGuessed", includeGuessed);
        settings.put("filter.applyExcludePackages", applyExcludePackages);
        settings.put("filter.dedupeCallers", dedupeCallers);
    }

    void load(IDialogSettings settings) {
        if (settings.get("filter.maxDepth") == null) {
            return;
        }
        text = settings.get("filter.text") == null ? "" : settings.get("filter.text");
        maxDepth = Math.max(1, settings.getInt("filter.maxDepth"));
        includeTests = settings.getBoolean("filter.includeTests");
        includeGuessed = settings.getBoolean("filter.includeGuessed");
        applyExcludePackages = settings.getBoolean("filter.applyExcludePackages");
        dedupeCallers = settings.getBoolean("filter.dedupeCallers");
    }
}
