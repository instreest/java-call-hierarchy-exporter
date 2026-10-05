// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

/**
 * 1本のエッジの解決結果。呼び出し先の候補（メソッドID）と、どう決めたかのラベル。
 *
 * ラベルは call-hierarchy.csv の {@code resolved-by} 列の後半（{@code RESOLVED:ラベル} / {@code UNEXPANDED:ラベル}）
 * として出る（{@code jche.report.ResolvedBy}）。注記に出るのは列に無い情報だけ。
 *
 * <p>{@code targets} は {@link CallResolver#resolve} がエッジごとにメモした配列（候補 1 件なら
 * メソッドごとに共有した配列）をそのまま渡すので、<b>受け取った側は書き換えない</b>。複製しないのは、
 * 階層の展開で同じエッジが経路の数だけ現れ、そのたびに配列を作るとエッジ数×経路数の割り当てになるため。
 *
 * @param targets 呼び出し先の候補。1件なら確定、複数ならCHA等で絞れなかった候補集合。書き換え不可
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
    /**
     * 本体を持つ候補が皆無で、かつ実装がコンパイル時のアノテーション処理で生成される型
     * （"GENERATED_IMPL:フレームワーク名" の形。{@link jche.framework.GeneratedImpl}）。
     * NO_IMPL の特殊形で、「実装を書き忘れている」のではないことを読み手に示す
     */
    public static final String GENERATED_IMPL_PREFIX = "GENERATED_IMPL:";
    // --- 段2: 同一メソッド内で new された型 ---
    public static final String LOCAL_NEW = "LOCAL_NEW";
    public static final String LOCAL_NEW_MULTI = "LOCAL_NEW_MULTI";
    // --- 段3: 利用者が与えた条件（拡張が返すラベルはここに並ばない。拡張の label() がそのまま出る） ---
    /**
     * 利用者がライブラリ呼び出し規則に書いた「この宣言型（またはこのメソッド）はこの具象型」で決めた
     * （{@link TypeRules}）。ツールの推測ではなく、人が与えた条件
     */
    public static final String CALL_RULE = "CALL_RULE";
    // --- 段4: データフロー（"DATAFLOW_" で始まる。何を材料に決めたかで分ける） ---
    public static final String DATAFLOW_PREFIX = "DATAFLOW_";
    public static final String DATAFLOW_NEW = DATAFLOW_PREFIX + "NEW";
    public static final String DATAFLOW_FACTORY = DATAFLOW_PREFIX + "FACTORY";
    public static final String DATAFLOW_PARAM = DATAFLOW_PREFIX + "PARAM";
    public static final String DATAFLOW_FIELD = DATAFLOW_PREFIX + "FIELD";
    /**
     * 経路で渡ってきた値の宣言の型（具象クラスの型で宣言したフィールド・引数）の部分型に候補を絞ったら
     * 1 つに定まった。具象型を追えたわけではなく、実行時の型の上限で絞った結果
     * （{@link CallResolver#resolveOnPath}。Issue #192）
     */
    public static final String DATAFLOW_DECLARED_TYPE = DATAFLOW_PREFIX + "DECLARED_TYPE";
    /**
     * ラムダ式かメソッド参照が、その関数型インターフェースの実装として
     * この呼び出し箇所まで渡ってきたと特定できた
     */
    public static final String DATAFLOW_LAMBDA = DATAFLOW_PREFIX + "LAMBDA";
    /**
     * ソースの外（JDK 等）のメソッドが、渡された値を呼び戻す規則で繋いだ
     * （{@link CallbackRules}）。jar の中を読んだわけではない
     */
    public static final String CALLBACK = "CALLBACK";
    // --- 段5: DIコンテナ（Spring）のBean定義で絞る ---
    /** 候補のうちBean登録されている型が1つだけだった */
    public static final String SPRING_DI = "SPRING_DI";
    /** &#64;Qualifier / &#64;Resource(name) で指定されたBean名で1つに定まった */
    public static final String SPRING_DI_QUALIFIER = "SPRING_DI_QUALIFIER";
    // --- 段6: 候補が複数のまま（低確度） ---
    public static final String CHA = "CHA";
    /** import からの推定（未検証の外部ライブラリ呼び出し） */
    public static final String EXTERNAL_GUESS = "EXTERNAL_GUESS";
    /** リフレクションで指定されたメソッド・コンストラクタに解決した */
    public static final String REFLECTION = "REFLECTION";
    /** Class.forName によるクラス初期化（static 初期化子へ繋ぐ） */
    public static final String REFLECTION_INIT = "REFLECTION_INIT";
    /**
     * リフレクションの呼び出し（{@code Method#invoke} / {@code Constructor#newInstance} / {@code Class#newInstance}）
     * なのに、クラス名・メソッド名が定数に畳めず（設定ファイルや入力から来る値、文字列演算、
     * {@code dataflow.enabled=false}）、動くメソッドを 1 つも決められなかった。候補は宣言どおりの呼び出し先
     * （jar の中の {@code invoke} 等）1 件のままで、静的束縛として扱うと {@code exclude.packages} の既定
     * （{@code java.**}）で行ごと消えるので、ラベルで区別して呼び出し階層に「繋げなかった」行を残す
     * （{@code resolved-by} は {@code UNEXPANDED:REFLECTION}。{@code jche.report.StreamingTreeWalker}）。
     * {@link #isReflection} には含めない（特定した件数に数えず、経路の値でもう一度試す対象にするため）
     */
    public static final String REFLECTION_UNKNOWN = "REFLECTION_UNKNOWN";

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

    /** 実装がコンパイル時に生成される型への呼び出しか */
    public boolean isGeneratedImpl() {
        return label.startsWith(GENERATED_IMPL_PREFIX);
    }

    /** リフレクションで指定されたメソッド・コンストラクタ・クラス初期化を特定した（{@link #REFLECTION_UNKNOWN} は含まない） */
    public boolean isReflection() {
        return REFLECTION.equals(label) || REFLECTION_INIT.equals(label);
    }

    /** リフレクションの呼び出しで、動くメソッドを 1 つも決められなかった */
    public boolean isReflectionUnknown() {
        return REFLECTION_UNKNOWN.equals(label);
    }
}
