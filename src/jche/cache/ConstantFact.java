// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cache;

import jche.util.FileHash;

/**
 * このファイルが宣言しているコンパイル時定数の1件（K行）。
 *
 * <h2>何のためにあるか</h2>
 * コンパイル時定数（{@code static final} の値と、注釈のメンバの既定値）は、
 * <b>それを使っている側のファイルに値そのものが焼き込まれる</b>（Javaの言語仕様どおり、
 * JDTもそう解決する）。そのため「ファイルAの事実はAが参照した型にだけ依存する」という
 * 差分更新の前提が、定数についてだけ成り立たない。
 *
 * <pre>
 *   P.java   static final String KIND = "ALPHA";
 *   X.java   static final String KIND = P.KIND;    ← "ALPHA" が焼き込まれる
 *   C.java   if (X.KIND.equals("BETA")) { … }      ← ここにも "ALPHA" が焼き込まれる
 * </pre>
 * C.java が参照している型は X だけなので、P.java を変えても C.java は再解析されない。
 * K行は「このファイルが宣言する定数の値」を残しておき、解析し直した結果その値が変わったときだけ
 * 使っている側も解析し直すためにある（{@link jche.analysis.CacheUpdater} の「定数の連鎖」）。
 *
 * <h2>値の持ち方</h2>
 * 値はそのまま持つ（{@link #KIND_VALUE}）。ただし長すぎる値・行形式を壊す文字を含む値は
 * ハッシュにして持つ（{@link #KIND_HASH}）。SQL や Base64 のような長い定数でキャッシュが
 * 膨らむのを避けつつ、変化は取りこぼさないため。どちらの形かを列に持つのは、
 * 「たまたまハッシュと同じ綴りの短い値」と区別するため。
 *
 * @param typeFqn 宣言している型（注釈のメンバなら注釈型）
 * @param name    フィールド名（注釈のメンバならメンバ名）
 * @param kind    値の持ち方（{@link #KIND_VALUE} / {@link #KIND_HASH}）
 * @param value   値、またはそのハッシュ
 */
public record ConstantFact(String typeFqn, String name, String kind, String value) {

    /** value 列が値そのもの */
    public static final String KIND_VALUE = "V";
    /** value 列が値のハッシュ */
    public static final String KIND_HASH = "H";

    /** 値をそのまま持てる長さの上限（{@link jche.cache.Origin} の値の上限と揃える） */
    private static final int MAX_VALUE_LENGTH = 64;

    /**
     * 値から1件作る。長すぎる値・行形式を壊す文字を含む値はハッシュにする。
     *
     * @param typeFqn 宣言している型
     * @param name    フィールド名・メンバ名
     * @param value   定数の値を文字列にしたもの
     */
    public static ConstantFact of(String typeFqn, String name, String value) {
        String v = (value == null) ? "" : value;
        if (v.length() > MAX_VALUE_LENGTH || CacheFormat.hasControlChar(v)) {
            return new ConstantFact(typeFqn, name, KIND_HASH, FileHash.ofText(v));
        }
        return new ConstantFact(typeFqn, name, KIND_VALUE, v);
    }

    public String toRow() {
        return CacheFormat.joinRow("K", typeFqn, name, kind, value);
    }

    /** 列が足りなければ null */
    public static ConstantFact fromRow(String[] cols) {
        if (cols.length < 4) {
            return null;
        }
        return new ConstantFact(cols[1], cols[2], cols[3], CacheFormat.columnAt(cols, 4));
    }

    /** 値が変わったかを見るための文字列（{@link jche.analysis.CacheUpdater} が突き合わせる） */
    public String fingerprint() {
        return typeFqn + "#" + name + "=" + kind + ":" + value;
    }
}
