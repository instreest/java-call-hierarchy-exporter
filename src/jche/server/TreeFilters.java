// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.server;

/**
 * 木を切り出すときの絞り込み条件。プロトコルの {@code key=value} を受け取って持つ。
 *
 * <p>絞り込みは<b>解析をやり直さない</b>。できあがったスナップショットを読むときの
 * 条件でしかないので、画面側は解析中でも（古い結果に対して）すぐ結果を得られる。
 * 条件の意味は、プラグインのフィルタ UI（docs/eclipse-plugin-ui-design.md §6）と同じ。
 */
public final class TreeFilters {

    /** 木の深さの上限 */
    public int maxDepth = 5;
    /** 型名・メソッド名・パッケージの部分一致（空なら絞り込まない） */
    public String text = "";
    /** テストのソースにあるメソッドを出すか */
    public boolean includeTests;
    /** 推測（データフロー由来）で特定した呼び出しを出すか */
    public boolean includeGuessed = true;
    /** 設定ファイルの exclude.packages を適用するか */
    public boolean applyExcludePackages = true;
    /** 同じ相手を1回だけ出すか（false なら呼び出し行ごとに出す） */
    public boolean dedupe = true;
    /** 返す行数の上限。木が大きいときに通信量と時間を抑える */
    public long maxRows = 20_000L;

    /**
     * {@code depth=5} のような語を1つ取り込む。知らないキーは黙って無視する
     * （プラグインとサーバーの版が少しずれても壊れないようにするため）。
     */
    public void apply(String word) {
        int eq = word.indexOf('=');
        if (eq <= 0) {
            return;
        }
        String key = word.substring(0, eq);
        String value = Protocol.unescape(word.substring(eq + 1));
        switch (key) {
            case "depth" -> maxDepth = Math.max(1, intOf(value, maxDepth));
            case "text" -> text = value;
            case "tests" -> includeTests = boolOf(value);
            case "guessed" -> includeGuessed = boolOf(value);
            case "exclude" -> applyExcludePackages = boolOf(value);
            case "dedupe" -> dedupe = boolOf(value);
            case "max" -> maxRows = Math.max(1L, longOf(value, maxRows));
            default -> { /* 知らない条件は無視する */ }
        }
    }

    private static boolean boolOf(String value) {
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    private static int intOf(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long longOf(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
