// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.dataflow;

import java.util.HashMap;
import java.util.Map;

import jche.graph.IntArray;
import jche.graph.StringPool;

/**
 * データフローの事実（フェーズ2bの成果物）。グラフ全体から一括で確定した、経路に依存しない情報。
 *
 * 作られた後は変わらない。読み手（{@code jche.graph.DataflowResolver}）はここを読むだけで、
 * 解決の途中で事実を育てることはない。だから {@code CallResolver.resolve} は
 * 「同じグラフなら、何回呼んでも、どの順で呼んでも同じ結果」になる（Issue #80）。
 *
 * <ul>
 *   <li>{@link #factoryKind} / {@link #factoryValueId}: メソッドが必ず返す値（委譲を畳んだ後）の種別と値。
 *       決められなければ種別 0</li>
 *   <li>{@link #usesParameters}: 引数をレシーバに使う、または引数を次へ渡すメソッドか</li>
 *   <li>{@link #reflectKind}: リフレクションAPIの種別（{@code DataflowResolver.REFLECT_*}）</li>
 *   <li>{@link #methodsNamed}: "typeFqn#name" → 本体を持つメソッドID（引数型が分からないときの名前照合用）</li>
 * </ul>
 *
 * <p>戻り値はメソッドごとに種別 1 バイトと値の番号（{@link StringPool}）1 つで持つ。以前は {@code "T:型"} の
 * 文字列を決まったメソッドの数だけ作っていた。
 */
public final class DataflowFacts {

    /** 値の番号を文字列に戻す置き場（値の表と同じもの）。事実を持たない空の事実では null */
    private final StringPool strings;
    /** メソッドごとの戻り値の種別（0 = 決められない、{@code 'T'} / {@code 'C'} / {@code 'A'}） */
    private final byte[] factoryKind;
    /** メソッドごとの戻り値の値の番号（{@link StringPool}。決められなければ -1） */
    private final int[] factoryValueId;
    private final boolean[] usesParameters;
    private final byte[] reflectKinds;
    private final Map<String, IntArray> methodsByName;
    private final int factoriesDecided;
    private final int factoriesCutOff;

    DataflowFacts(StringPool strings, byte[] factoryKind, int[] factoryValueId, boolean[] usesParameters,
                  byte[] reflectKinds, Map<String, IntArray> methodsByName, int factoriesDecided,
                  int factoriesCutOff) {
        this.strings = strings;
        this.factoryKind = factoryKind;
        this.factoryValueId = factoryValueId;
        this.usesParameters = usesParameters;
        this.reflectKinds = reflectKinds;
        this.methodsByName = methodsByName;
        this.factoriesDecided = factoriesDecided;
        this.factoriesCutOff = factoriesCutOff;
    }

    /** 事実を持たない（データフロー解析が無効なときの）空の事実。リフレクションの種別だけは持つ */
    static DataflowFacts empty(int methodCount, byte[] reflectKinds) {
        return new DataflowFacts(null, new byte[methodCount], new int[0], new boolean[methodCount],
                reflectKinds, new HashMap<>(), 0, 0);
    }

    /**
     * そのメソッドが必ず返す値の種別。特定できなければ 0。
     *
     * 返すのは具象型（{@code 'T'}。値は型の FQN）とは限らない。クラス名の文字列を受け取るファクトリは
     * {@code 'C'}（値は引数位置）、引数をそのまま返すメソッドは {@code 'A'}（値は引数位置）になる。
     * これらは<b>そのファクトリを呼んでいる箇所の実引数</b>を見て初めて確定する。
     *
     * <p>これはそのメソッドの<b>本体</b>が返す値。呼び出し箇所でこれを戻り値として使ってよいのは、
     * 呼び出しがその本体でしか動かないとき（{@code CallGraph#hasOverriders} が false）だけ
     */
    public byte factoryKind(int methodId) {
        return (methodId >= 0 && methodId < factoryKind.length) ? factoryKind[methodId] : 0;
    }

    /** そのメソッドが必ず返す値の、値の番号（{@link StringPool}）。特定できなければ -1 */
    public int factoryValueId(int methodId) {
        return (factoryKind(methodId) == 0) ? -1 : factoryValueId[methodId];
    }

    /**
     * そのメソッドが必ず返す値を、以前の出所の文字列の形（{@code 種別:値}）にしたもの。特定できなければ null。
     *
     * <p><b>一時的なもの（stage B の間だけ）。</b>読み手は {@link #factoryKind} / {@link #factoryValueId} を読む。
     * test/dataflow の TraceCheck が、以前の記録と突き合わせるためだけに使う。型の FQN と引数位置は
     * 出所の文法の文字を含まないので、以前の文字列とそのまま同じになる
     */
    public String factoryOrigin(int methodId) {
        int kind = factoryKind(methodId);
        return (kind == 0) ? null : (char) kind + ":" + strings.get(factoryValueId[methodId]);
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
