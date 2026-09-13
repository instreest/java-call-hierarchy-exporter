// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.util;

/** 設定値のパスに書かれた {@code ~} をホームディレクトリに置き換える（OS を問わず同じ書き方で書けるように） */
public final class UserHome {

    private UserHome() {
    }

    /** 先頭の "~"、"~/"、"~\" をホームディレクトリにする。それ以外はそのまま（前後の空白は除く） */
    public static String expand(String raw) {
        String s = raw.trim();
        if (s.equals("~") || s.startsWith("~/") || s.startsWith("~\\")) {
            return System.getProperty("user.home") + s.substring(1);
        }
        return s;
    }
}
