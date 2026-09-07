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
package jche.extension;

/** フェーズA（{@link CallSiteHintCollector}）の出力先 */
public interface HintSink {

    /**
     * @param scopeKey この証拠が結び付く対象。ローカル変数なら
     *                 IVariableBinding.getKey()、レシーバ式なら "@開始位置"。
     *                 呼び出し箇所側が記録するキーと一致させる必要がある
     */
    void add(String scopeKey, String kind, String value);
}
