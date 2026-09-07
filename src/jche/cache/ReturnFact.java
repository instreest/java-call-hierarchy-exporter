// Copyright 2026 Inoue Kazuhiro. SPDX-License-Identifier: Apache-2.0
package jche.cache;

/**
 * そのメソッドが返しうる値の出所の1件（R行）。
 *
 * 追跡できない return も U として記録する。「追跡できない return が1つでもあれば
 * 戻り値は不定」という判定は読み手が行う。
 *
 * @param method return を含むメソッド
 * @param origin 返す値の出所（{@link Origin}）
 */
public record ReturnFact(MethodRef method, String origin) {

    public String toRow() {
        return CacheFormat.joinRow("R", method.pkg(), method.typeFqn(), method.name(),
                method.paramSig(), origin);
    }

    /** 列が足りなければ null */
    public static ReturnFact fromRow(String[] cols) {
        if (cols.length < 6) {
            return null;
        }
        MethodRef method = MethodRef.fromColumns(cols, 1);
        return (method == null) ? null : new ReturnFact(method, cols[5]);
    }
}
