// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.dataflow;

import java.util.HashMap;
import java.util.Map;

import jche.graph.IntArray;

/**
 * データフローの事実（フェーズ2bの成果物）。グラフ全体から一括で確定した、経路に依存しない情報。
 *
 * 作られた後は変わらない。読み手（{@code jche.graph.DataflowResolver}）はここを読むだけで、
 * 解決の途中で事実を育てることはない。だから {@code CallResolver.resolve} は
 * 「同じグラフなら、何回呼んでも、どの順で呼んでも同じ結果」になる（Issue #80）。
 *
 * <ul>
 *   <li>{@link #factoryOrigin}: メソッドが必ず返す値の出所（委譲を畳んだ後）。決められなければ null</li>
 *   <li>{@link #usesParameters}: 引数をレシーバに使う、または引数を次へ渡すメソッドか</li>
 *   <li>{@link #reflectKind}: リフレクションAPIの種別（{@code DataflowResolver.REFLECT_*}）</li>
 *   <li>{@link #methodsNamed}: "typeFqn#name" → 本体を持つメソッドID（引数型が分からないときの名前照合用）</li>
 * </ul>
 */
public final class DataflowFacts {

    private final String[] factoryOrigin;
    private final boolean[] usesParameters;
    private final byte[] reflectKinds;
    private final Map<String, IntArray> methodsByName;
    private final int factoriesDecided;
    private final int factoriesCutOff;

    DataflowFacts(String[] factoryOrigin, boolean[] usesParameters, byte[] reflectKinds,
                  Map<String, IntArray> methodsByName, int factoriesDecided, int factoriesCutOff) {
        this.factoryOrigin = factoryOrigin;
        this.usesParameters = usesParameters;
        this.reflectKinds = reflectKinds;
        this.methodsByName = methodsByName;
        this.factoriesDecided = factoriesDecided;
        this.factoriesCutOff = factoriesCutOff;
    }

    /** 事実を持たない（データフロー解析が無効なときの）空の事実。リフレクションの種別だけは持つ */
    static DataflowFacts empty(int methodCount, byte[] reflectKinds) {
        return new DataflowFacts(new String[methodCount], new boolean[methodCount], reflectKinds,
                new HashMap<>(), 0, 0);
    }

    /**
     * そのメソッドが必ず返す値の出所。特定できなければ null。
     *
     * 返すのは具象型（{@code T:}）とは限らない。クラス名の文字列を受け取るファクトリは
     * {@code C:引数位置}、引数をそのまま返すメソッドは {@code A:引数位置} になる。
     * これらは<b>そのファクトリを呼んでいる箇所の実引数</b>を見て初めて確定する。
     */
    public String factoryOrigin(int methodId) {
        return (methodId >= 0 && methodId < factoryOrigin.length) ? factoryOrigin[methodId] : null;
    }

    /** そのメソッドに経路の情報（引数の具象型）を渡す意味があるか */
    public boolean usesParameters(int methodId) {
        return methodId >= 0 && methodId < usesParameters.length && usesParameters[methodId];
    }

    /** リフレクションAPIの種別。該当しなければ 0（{@code DataflowResolver.REFLECT_NONE}） */
    public int reflectKind(int methodId) {
        return (methodId >= 0 && methodId < reflectKinds.length) ? reflectKinds[methodId] : 0;
    }

    /** その型に直接宣言された、その名前の本体付きメソッド。無ければ null */
    public IntArray methodsNamed(String typeFqn, String name) {
        return methodsByName.get(typeFqn + "#" + name);
    }

    /** 戻り値の出所を1つに決められたメソッドの数（ログ用） */
    public int factoriesDecided() {
        return factoriesDecided;
    }

    /** 委譲が循環している（または安全策の段数上限に当たった）ため決められなかったメソッドの数（ログ用） */
    public int factoriesCutOff() {
        return factoriesCutOff;
    }
}
