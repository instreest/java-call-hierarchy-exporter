// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.report;

import jche.graph.DataflowContext;

/**
 * 深さ優先探索の経路上の1段（{@link StreamingTreeWalker} が深さぶんだけ持つ）。
 *
 * 経路ごとに前向き（root -> 葉）に伝えるだけで、呼び出し元の候補を
 * 遡って探索することはしない。同じメソッドでも経路が違えば別の値になる
 * ——それがデータフロー解析の意味であり、メモ化できない理由でもある。
 *
 * <p>経路の値の枠はどれも {@link jche.graph.Slot}（札付きの {@code long}）。配列は束縛した後は書き換えない
 * （捕捉した値やコンストラクタ実引数は、親の段の配列をそのまま共有する）。この段の
 * {@link DataflowContext} は {@link #set} のときに 1 度だけ作り、辺を見るたびには作らない。
 */
final class PathFrame {

    int methodId;
    /** 1つ上の段がこのメソッドを呼んでいる行 */
    int callLine;
    /** 注記（[UNEXPANDED:*]・[EXTERNAL]・[UNREACHABLE]・[RESOLVED:CALLBACK]）。無ければ null */
    String note;
    /** 解決方法（resolved-by 列。{@link ResolvedBy}）。起点の段だけ null */
    String resolvedBy;
    /**
     * このメソッドの引数に「この経路では」何が渡ってきているか（i 番目の引数の枠）。
     * 分からない引数は {@link jche.graph.Slot#NONE}。何も分からなければ配列ごと null
     */
    long[] params;
    /**
     * 「今メソッドを実行しているオブジェクト」のコンストラクタ実引数の枠。
     * コンストラクタ注入されたフィールドは、これと突き合わせて具象型が決まる
     */
    long[] ctorArgs;
    /** ctorArgs が属する型。親クラスのフィールドに取り違えて当てないため */
    String ctorOwner;
    /**
     * ラムダの合成メソッドの段で、生成箇所のフレームの引数（＝捕捉した値）。
     * ラムダ以外の段では null
     */
    long[] captured;
    /** この段で経路から分かっていること（{@link #set} で作る。無ければ null） */
    private DataflowContext context;

    void set(int methodId, int callLine, String note, String resolvedBy,
             long[] params, long[] ctorArgs, String ctorOwner) {
        set(methodId, callLine, note, resolvedBy, params, ctorArgs, ctorOwner, null);
    }

    void set(int methodId, int callLine, String note, String resolvedBy,
             long[] params, long[] ctorArgs, String ctorOwner, long[] captured) {
        this.methodId = methodId;
        this.callLine = callLine;
        this.note = note;
        this.resolvedBy = resolvedBy;
        this.params = params;
        this.ctorArgs = ctorArgs;
        this.ctorOwner = ctorOwner;
        this.captured = captured;
        this.context = DataflowContext.of(params, ctorArgs, ctorOwner, captured);
    }

    /** この段で経路から分かっていること（無ければ null） */
    DataflowContext context() {
        return context;
    }
}
