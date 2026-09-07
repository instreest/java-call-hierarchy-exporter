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
package jche.cache;

/**
 * 修飾子をカンマ区切りの語にしたもの（D行・V行・C行で共通の語彙）。
 *
 * 基本の語: public / protected / private / static / final / abstract / default
 * （JDTの修飾子ビットからの変換は jche.analysis.BindingNames#modifiersOf）。
 * 書き手が状況に応じて足す語:
 * <ul>
 *   <li>implicit … ソースに無い暗黙のコンストラクタを合成した（D行）</li>
 *   <li>delegating … コンストラクタ本体の先頭が this(...) 委譲（D行）</li>
 *   <li>finalclass … 宣言クラスが final（C行の calleeMods）</li>
 *   <li>super … super.m() 形式の呼び出し（C行の calleeMods）</li>
 * </ul>
 */
public final class ModifierTokens {

    public static final String IMPLICIT = "implicit";
    public static final String DELEGATING = "delegating";
    public static final String FINAL_CLASS = "finalclass";
    public static final String SUPER = "super";

    private ModifierTokens() {
    }

    /** 語を1つ足す */
    public static String with(String mods, String token) {
        return (mods == null || mods.isEmpty()) ? token : mods + "," + token;
    }

    /** その語が含まれるか */
    public static boolean has(String mods, String token) {
        return mods != null && !mods.isEmpty()
                && ("," + mods + ",").contains("," + token + ",");
    }
}
