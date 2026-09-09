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
 * @param annotations 型に付いているアノテーションのFQN（v13 で追加）。宣言順。
 *                    コンパイル時にアノテーション処理で実装クラスが生成される型
 *                    （Doma の {@code @Dao} など）を読み手が見分けるために使う
 */
public record TypeFact(String typeFqn, char kind, List<String> superTypes, String pkg,
                       List<String> annotations) {

    public static final char INTERFACE = 'I';
    public static final char ABSTRACT = 'A';
    public static final char CONCRETE = 'C';

    public TypeFact {
        pkg = (pkg == null) ? "" : pkg;
        annotations = (annotations == null) ? List.of() : annotations;
    }

    /** アノテーションを持たない型（合成・旧形式の読み込み用） */
    public TypeFact(String typeFqn, char kind, List<String> superTypes, String pkg) {
        this(typeFqn, kind, superTypes, pkg, List.of());
    }

    public String toRow() {
        return CacheFormat.joinRow("H", typeFqn, String.valueOf(kind), String.join(",", superTypes),
                pkg, String.join(",", annotations));
    }

    /** 列が足りなければ null */
    public static TypeFact fromRow(String[] cols) {
        if (cols.length < 3) {
            return null;
        }
        char kind = cols[2].isEmpty() ? CONCRETE : cols[2].charAt(0);
        return new TypeFact(cols[1], kind, splitCsv(CacheFormat.columnAt(cols, 3)),
                CacheFormat.columnAt(cols, 4), splitCsv(CacheFormat.columnAt(cols, 5)));
    }

    private static List<String> splitCsv(String csv) {
        List<String> list = new ArrayList<>();
        if (!csv.isEmpty()) {
            for (String s : csv.split(",")) {
                if (!s.isEmpty()) {
                    list.add(s);
                }
            }
        }
        return list;
    }
}
