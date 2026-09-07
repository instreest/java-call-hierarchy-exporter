// Copyright 2026 Inoue Kazuhiro. SPDX-License-Identifier: Apache-2.0
package jche.report;

import jche.graph.DataflowContext;

/**
 * 深さ優先探索の経路上の1段（{@link StreamingTreeWalker} が深さぶんだけ持つ）。
 *
 * 経路ごとに前向き（root -> 葉）に伝えるだけで、呼び出し元の候補を
 * 遡って探索することはしない。同じメソッドでも経路が違えば別の値になる
 * ——それがデータフロー解析の意味であり、メモ化できない理由でもある。
 */
final class PathFrame {

    int methodId;
    /** 1つ上の段がこのメソッドを呼んでいる行 */
    int callLine;
    /** 注記（[CYCLE]・深さ制限・CHA候補・解決ラベル等）。無ければ null */
    String note;
    /**
     * このメソッドの引数に「この経路では」何が渡ってきているか（i 番目の引数の具象型）。
     * 分からない引数は null。何も分からなければ配列ごと null
     */
    String[] paramTypes;
    /**
     * 「今メソッドを実行しているオブジェクト」のコンストラクタ実引数の具象型。
     * コンストラクタ注入されたフィールドは、これと突き合わせて具象型が決まる
     */
    String[] ctorArgs;
    /** ctorArgs が属する型。親クラスのフィールドに取り違えて当てないため */
    String ctorOwner;

    void set(int methodId, int callLine, String note,
             String[] paramTypes, String[] ctorArgs, String ctorOwner) {
        this.methodId = methodId;
        this.callLine = callLine;
        this.note = note;
        this.paramTypes = paramTypes;
        this.ctorArgs = ctorArgs;
        this.ctorOwner = ctorOwner;
    }

    /** この段で経路から分かっていること（無ければ null） */
    DataflowContext context() {
        return DataflowContext.of(paramTypes, ctorArgs, ctorOwner);
    }
}
