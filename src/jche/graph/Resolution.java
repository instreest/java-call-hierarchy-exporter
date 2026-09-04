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
package jche.graph;

/**
 * 1本のエッジの解決結果。呼び出し先の候補（メソッドID）と、どう決めたかのラベル。
 *
 * ラベルは call-hierarchy.csv の注記「解決:ラベル」として出る。
 *
 * @param targets 呼び出し先の候補。1件なら確定、複数ならCHA等で絞れなかった候補集合
 * @param label   解決の根拠
 */
public record Resolution(int[] targets, String label) {

    // --- 段0: 静的束縛（"STATIC_BOUND:理由" の形） ---
    public static final String STATIC_BOUND_PREFIX = "STATIC_BOUND:";
    // --- 段1: オーバーライド候補が1つに定まる ---
    public static final String NO_OVERRIDE = "NO_OVERRIDE";
    public static final String SINGLE_IMPL = "SINGLE_IMPL";
    /** 本体を持つ候補が皆無（ソース外の実装等）。宣言のまま扱う */
    public static final String NO_IMPL = "NO_IMPL";
    // --- 段2: 同一メソッド内で new された型 ---
    public static final String LOCAL_NEW = "LOCAL_NEW";
    public static final String LOCAL_NEW_MULTI = "LOCAL_NEW_MULTI";
    // --- 段4: データフロー（"DATAFLOW_" で始まる。何を材料に決めたかで分ける） ---
    public static final String DATAFLOW_PREFIX = "DATAFLOW_";
    public static final String DATAFLOW_NEW = DATAFLOW_PREFIX + "NEW";
    public static final String DATAFLOW_FACTORY = DATAFLOW_PREFIX + "FACTORY";
    public static final String DATAFLOW_PARAM = DATAFLOW_PREFIX + "PARAM";
    public static final String DATAFLOW_FIELD = DATAFLOW_PREFIX + "FIELD";
    // --- 段5: 候補が複数のまま（低確度） ---
    public static final String CHA = "CHA";
    /** import からの推定（未検証の外部ライブラリ呼び出し） */
    public static final String EXTERNAL_GUESS = "EXTERNAL_GUESS";
    /** リフレクションで指定されたメソッド・コンストラクタに解決した */
    public static final String REFLECTION = "REFLECTION";
    /** Class.forName によるクラス初期化（static 初期化子へ繋ぐ） */
    public static final String REFLECTION_INIT = "REFLECTION_INIT";

    public static Resolution single(int target, String label) {
        return new Resolution(new int[] {target}, label);
    }

    /** 候補が複数のまま（1つに絞れなかった）か */
    public boolean isMultiple() {
        return targets.length > 1;
    }

    public boolean isDataflow() {
        return label.startsWith(DATAFLOW_PREFIX);
    }

    public boolean isReflection() {
        return label.startsWith(REFLECTION);
    }
}
