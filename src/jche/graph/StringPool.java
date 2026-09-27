// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

/**
 * 値の表（{@link ValueStore}・{@link GuardTable}）が持つ文字列の置き場。同じ中身の文字列は 1 つだけ置き、
 * 番号で指す（ノードの値・書かれたレシーバの型・条件の期待値と条件式のテキスト）。
 *
 * <p>番号が同じなら中身も同じ、中身が同じなら番号も同じ（構築のときに {@link StringPoolBuilder} が
 * まとめる）。だから値どうしの比較は番号の比較で済む。作った後は変わらない。
 */
public final class StringPool {

    private final String[] strings;

    StringPool(String[] strings) {
        this.strings = strings;
    }

    /** 番号の文字列。番号が範囲の外なら null */
    public String get(int id) {
        return (id < 0 || id >= strings.length) ? null : strings[id];
    }

    /** 置いてある文字列の数（番号は 0 から {@code size() - 1}） */
    public int size() {
        return strings.length;
    }
}
