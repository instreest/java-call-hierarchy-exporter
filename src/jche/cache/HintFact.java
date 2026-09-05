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
 * 証拠の1件（X行）。呼び出し箇所の解決に使う局所的な材料。
 *
 * 組み込みの種別は {@link #KIND_NEW}（同一メソッド内で new された型）。
 * それ以外はフェーズAの拡張（jche.extension.CallSiteHintCollector）が任意の種別で残す。
 *
 * @param callerKey 呼び出し元メソッドのキー typeFqn#method(paramSig)
 * @param scopeKey  結び付く対象（ローカル変数のバインディングキー、または "@位置"）
 * @param kind      種別
 * @param value     値
 */
public record HintFact(String callerKey, String scopeKey, String kind, String value) {

    public static final String KIND_NEW = "NEW";

    public String toRow() {
        return CacheFormat.joinRow("X", callerKey, scopeKey, kind, value);
    }

    /** 列が足りなければ null */
    public static HintFact fromRow(String[] cols) {
        if (cols.length < 5) {
            return null;
        }
        return new HintFact(cols[1], cols[2], cols[3], cols[4]);
    }
}
