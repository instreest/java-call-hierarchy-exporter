// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

/**
 * 経路から確定している情報。どれも「1本の経路に対して」の値であり、
 * 別の経路では別の値になる。
 *
 * @param paramTypes 囲みメソッドの引数の具象型。型ではなく値（L:文字列 / K:クラス）が
 *                   渡っている引数にはその出所（':' を含む）が入る。分からない引数は null
 * @param ctorArgs   レシーバのオブジェクトの、コンストラクタ実引数の具象型
 * @param ctorOwner  ctorArgs が属する型。取り違え防止のため必ず突き合わせる
 * @param capturedTypes ラムダが捕捉した「囲みメソッドの引数」の具象型（{@link jche.cache.Origin#CAPTURED}）。
 *                   ラムダの合成メソッドへ降りるときに、生成箇所のフレームの引数をそのまま渡す。
 *                   ラムダの中でなければ null
 */
public record DataflowContext(String[] paramTypes, String[] ctorArgs, String ctorOwner,
                              String[] capturedTypes) {

    /** 何も分かっていなければ null（以降の深さで何もしないための印） */
    public static DataflowContext of(String[] paramTypes, String[] ctorArgs, String ctorOwner) {
        return of(paramTypes, ctorArgs, ctorOwner, null);
    }

    /** 何も分かっていなければ null（以降の深さで何もしないための印） */
    public static DataflowContext of(String[] paramTypes, String[] ctorArgs, String ctorOwner,
                                     String[] capturedTypes) {
        return (paramTypes == null && ctorArgs == null && capturedTypes == null)
                ? null : new DataflowContext(paramTypes, ctorArgs, ctorOwner, capturedTypes);
    }
}
