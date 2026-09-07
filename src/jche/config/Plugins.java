// Copyright 2026 Inoue Kazuhiro. SPDX-License-Identifier: Apache-2.0
package jche.config;

import java.util.ArrayList;
import java.util.List;

import jche.util.Log;

/** 設定に書かれたFQNから拡張クラスを読み込む */
public final class Plugins {

    private Plugins() {
    }

    public static <T> List<T> load(List<String> classNames, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (String className : classNames) {
            try {
                Object o = Class.forName(className).getDeclaredConstructor().newInstance();
                out.add(type.cast(o));
                Log.info("[plugin] 読み込み: " + className + " (" + type.getSimpleName() + ")");
            } catch (Exception e) {
                // 拡張の読み込み失敗は致命的ではないが、黙って無視すると
                // 「設定したのに効いていない」ことに気づけないため必ず出力する
                Log.warn("拡張の読み込みに失敗: " + className + " (" + e + ")");
            }
        }
        return out;
    }
}
