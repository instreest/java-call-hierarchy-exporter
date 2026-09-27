// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

/**
 * 経路から確定している情報。どれも「1本の経路に対して」の値であり、
 * 別の経路では別の値になる。
 *
 * <p>枠はどれも {@link Slot}（具象型か値を札付きで詰めた {@code long}）。分からない枠は {@link Slot#NONE}。
 * 配列は束縛した後は書き換えない（同じ配列を親の段と共有することがある）。
 *
 * @param params    囲みメソッドの引数（i 番目の引数に、この経路で渡ってきたもの）。
 *                  具象型が決まらなくても、リテラルやクラス・定数・ラムダなら値として入る
 * @param ctorArgs  レシーバのオブジェクトの、コンストラクタ実引数
 * @param ctorOwner ctorArgs が属する型。取り違え防止のため必ず突き合わせる
 * @param captured  ラムダが捕捉した「囲みメソッドの引数」（{@link jche.cache.Origin#CAPTURED}）。
 *                  ラムダの合成メソッドへ降りるときに、生成箇所のフレームの引数をそのまま渡す。
 *                  ラムダの中でなければ null
 */
public record DataflowContext(long[] params, long[] ctorArgs, String ctorOwner, long[] captured) {

    /** 何も分かっていなければ null（以降の深さで何もしないための印） */
    public static DataflowContext of(long[] params, long[] ctorArgs, String ctorOwner) {
        return of(params, ctorArgs, ctorOwner, null);
    }

    /** 何も分かっていなければ null（以降の深さで何もしないための印） */
    public static DataflowContext of(long[] params, long[] ctorArgs, String ctorOwner, long[] captured) {
        return (params == null && ctorArgs == null && captured == null)
                ? null : new DataflowContext(params, ctorArgs, ctorOwner, captured);
    }
}
