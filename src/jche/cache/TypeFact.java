/*
 * Copyright 2026 the java-call-hierarchy-exporter authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jche.cache;

import java.util.ArrayList;
import java.util.List;

/**
 * 型階層の1件（H行）。
 *
 * @param typeFqn    型の完全修飾名
 * @param kind       I=インターフェース / A=抽象クラス / C=具象クラス
 * @param superTypes 直接の親型（親クラスとインターフェース）。java.lang.Object は含まない
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
