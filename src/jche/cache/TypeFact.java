// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cache;

import java.util.ArrayList;
import java.util.List;

/**
 * 型階層の1件（H行）。
 *
 * @param typeFqn    型の完全修飾名
 * @param kind       I=インターフェース / A=抽象クラス / C=具象クラス
 * @param superTypes 親型。直接の親型（親クラスとインターフェース）と、jar の型を経由して到達する
 *                   ソース上の親型（v12 で追加。jar の基底クラスがソースのインターフェースを実装している場合に、
 *                   その子がインターフェースの実装だと分かるように）。java.lang.Object は含まない
 * @param pkg        パッケージ名
 */
public record TypeFact(String typeFqn, char kind, List<String> superTypes, String pkg) {

    public static final char INTERFACE = 'I';
    public static final char ABSTRACT = 'A';
    public static final char CONCRETE = 'C';

    public TypeFact {
        pkg = (pkg == null) ? "" : pkg;
    }

    public String toRow() {
        return CacheFormat.joinRow("H", typeFqn, String.valueOf(kind), String.join(",", superTypes), pkg);
    }

    /** 列が足りなければ null */
    public static TypeFact fromRow(String[] cols) {
        if (cols.length < 3) {
            return null;
        }
        char kind = cols[2].isEmpty() ? CONCRETE : cols[2].charAt(0);
        List<String> supers = new ArrayList<>();
        String supersCsv = CacheFormat.columnAt(cols, 3);
        if (!supersCsv.isEmpty()) {
            for (String s : supersCsv.split(",")) {
                if (!s.isEmpty()) {
                    supers.add(s);
                }
            }
        }
        return new TypeFact(cols[1], kind, supers, CacheFormat.columnAt(cols, 4));
    }
}
