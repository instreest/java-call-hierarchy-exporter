/*
 * Copyright 2026 the java-call-hierarchy-exporter authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
