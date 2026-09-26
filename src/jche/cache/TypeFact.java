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
 * @param annotations 型に付いていたアノテーション（{@link AnnotationTokens}。v13 で追加）
 * @param superclasses 親クラスの連鎖（v43 で追加）。直接の親クラスから親へ順に、ソース上の型に当たるまで
 *                   （当たった型を含む。その先はその型自身の H 行が持つ）。途中の jar のクラスも並べる。
 *                   java.lang.Object は含まない。インターフェースと、親クラスが Object のクラスは空。
 *                   {@code superTypes} は読み手が名前順に並べ替えるので、どれが親クラスかはここからしか分からない。
 *                   実際に動く実装を探すとき、親クラスの連鎖を親インターフェースより先に見る
 *                   （JLS 8.4.8・JVMS 5.4.6。jche.graph.CallGraph#implementationOf）ために持つ
 * @param inheritedImpls 親クラスから継承したメソッドが親インターフェースのメソッドを実装し、キーが食い違うものの組
 *                   （v43 で追加。{@code 実装される側のキー>実装する側のキー}、名前順）。
 *                   {@code class UserRepo extends BaseRepo implements Repo<User>} の
 *                   {@code p.Repo#save(java.lang.Object)>p.BaseRepo#save(p.User)}。この型から見たときにだけ成り立つ
 *                   関係なので O 行（宣言ごとの上書き）には書けない（jche.analysis.BindingNames#inheritedImplementationsOf）
 */
public record TypeFact(String typeFqn, char kind, List<String> superTypes, String pkg,
                       String annotations, List<String> superclasses, List<String> inheritedImpls) {

    public static final char INTERFACE = 'I';
    public static final char ABSTRACT = 'A';
    public static final char CONCRETE = 'C';

    public TypeFact {
        pkg = (pkg == null) ? "" : pkg;
        annotations = (annotations == null) ? "" : annotations;
        superclasses = (superclasses == null) ? List.of() : superclasses;
        inheritedImpls = (inheritedImpls == null) ? List.of() : inheritedImpls;
    }

    public String toRow() {
        return CacheFormat.joinRow("H", typeFqn, String.valueOf(kind), String.join(",", superTypes), pkg,
                annotations, String.join(",", superclasses), String.join(";", inheritedImpls));
    }

    /** 列が足りなければ null */
    public static TypeFact fromRow(String[] cols) {
        if (cols.length < 3) {
            return null;
        }
        char kind = cols[2].isEmpty() ? CONCRETE : cols[2].charAt(0);
        return new TypeFact(cols[1], kind, namesOf(CacheFormat.columnAt(cols, 3), ","), CacheFormat.columnAt(cols, 4),
                CacheFormat.columnAt(cols, 5), namesOf(CacheFormat.columnAt(cols, 6), ","),
                namesOf(CacheFormat.columnAt(cols, 7), ";"));
    }

    /** 区切り文字 {@code sep} で区切った並び（空の要素は捨てる） */
    private static List<String> namesOf(String joined, String sep) {
        List<String> out = new ArrayList<>();
        if (!joined.isEmpty()) {
            for (String s : joined.split(sep)) {
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return out;
    }
}
