// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
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
