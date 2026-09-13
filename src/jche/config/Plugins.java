// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.config;

import java.util.ArrayList;
import java.util.List;

import jche.extension.CallSiteHintCollector;
import jche.extension.TypeCandidateProvider;
import jche.util.Log;

/** 設定に書かれたFQNから拡張クラスを読み込む */
public final class Plugins {

    private Plugins() {
    }

    /**
     * 設定に書かれた拡張を読み込んで {@code init} まで済ませる。
     *
     * 探す場所は、このツール自身のクラスパス（{@code jche.builtin.*} の同梱拡張）と、
     * {@code plugin.folders} に置かれた {@code .java} / {@code .class} / {@code .jar}
     * （{@link PluginClassLoaders}）。
     */
    public static <T> List<T> load(Config config, List<String> classNames, Class<T> type) {
        ClassLoader loader = PluginClassLoaders.forConfig(config);
        List<T> out = new ArrayList<>();
        for (String className : classNames) {
            try {
                Object o = Class.forName(className, true, loader)
                        .getDeclaredConstructor().newInstance();
                T plugin = type.cast(o);
                init(plugin, config);
                out.add(plugin);
                Log.info("[plugin] 読み込み: " + className + " (" + type.getSimpleName() + ")");
            } catch (Exception e) {
                // 拡張の読み込み失敗は致命的ではないが、黙って無視すると
                // 「設定したのに効いていない」ことに気づけないため必ず出力する
                Log.warn("拡張の読み込みに失敗: " + className + " (" + e + ")");
            }
        }
        return out;
    }

    /**
     * 拡張に設定を渡す。フェーズA・フェーズBのどちらのインターフェースも同じ形の
     * {@code init(Properties, Path)} を持つが、共通の親を作ると拡張ポイントが
     * 1つに見えてしまうため、ここで振り分ける。
     */
    private static void init(Object plugin, Config config) {
        try {
            if (plugin instanceof TypeCandidateProvider provider) {
                provider.init(config.raw, config.configDir);
            } else if (plugin instanceof CallSiteHintCollector collector) {
                collector.init(config.raw, config.configDir);
            }
        } catch (RuntimeException e) {
            // 初期化に失敗した拡張は「設定が効いていない状態」で動くので、必ず知らせる
            Log.warn("拡張の初期化に失敗: " + plugin.getClass().getName() + " (" + e + ")");
        }
    }
}
