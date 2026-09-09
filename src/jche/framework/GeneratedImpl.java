// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.framework;

import java.util.List;

import jche.cache.AnnotationTokens;

/**
 * 「アノテーション処理でコンパイル時に実装クラスが生成される型」の定義。
 *
 * この種のフレームワーク（Doma の {@code @Dao}、MapStruct の {@code @Mapper} など）では、
 * インターフェースにアノテーションを付けるだけで、実装クラスはビルド時に
 * アノテーションプロセッサが生成する。生成物はソースコードリポジトリに存在しないため、
 * このツールがソースだけを見ている限り、実装は「1つも無い」ように見える。
 *
 * <p>そのままだと呼び出し階層は
 * {@code 解決:NO_IMPL 実装なし（宣言のまま）} と出て、
 * 「実装を書き忘れている」のか「生成されるので書かない」のかが読み手に区別できない。
 * そこで、宣言型に付いたアノテーションから生成の事実を見分け、
 * 注記に「コンパイル時生成」と生成されるクラス名を出す。
 *
 * <p>方針として、階層に合成ノードは足さない。生成されたクラスの本体はソースに無く、
 * その先の呼び出しは辿れないため、ノードを増やしても行数が増えるだけで情報は増えない
 * （{@code Dao} メソッドと実際の SQL の対応は、キャッシュを共有する別の出力で扱う）。
 *
 * <p>新しいフレームワークへの対応は {@link #DEFINITIONS} に1件足すだけでよい。
 *
 * @param label          解決ラベル・注記に出す名前（例 "Doma"）
 * @param annotationFqn  この定義の目印になるアノテーションのFQN
 * @param implSuffix     生成される実装クラスの名前に付く接尾辞（例 "Impl"）
 */
public record GeneratedImpl(String label, String annotationFqn, String implSuffix) {

    /**
     * 対応済みのフレームワーク。
     *
     * Doma はアノテーションプロセッサが {@code @Dao} を付けたインターフェースごとに
     * 同じパッケージへ {@code <インターフェース名>Impl} を生成する
     * （{@code doma.dao.subpackage} / {@code doma.dao.suffix} オプションで変えられるが、
     * 既定はこの形）。
     */
    public static final List<GeneratedImpl> DEFINITIONS = List.of(
            new GeneratedImpl("Doma", "org.seasar.doma.Dao", "Impl"));

    /**
     * 型に付いていたアノテーション（{@link AnnotationTokens}）から、当てはまる定義を返す。無ければ null
     */
    public static GeneratedImpl of(String annotations) {
        if (annotations == null || annotations.isEmpty()) {
            return null;
        }
        for (GeneratedImpl def : DEFINITIONS) {
            if (AnnotationTokens.has(annotations, def.annotationFqn)) {
                return def;
            }
        }
        return null;
    }

    /**
     * 生成される実装クラスのFQN（既定の命名規則での推定）。
     *
     * 生成規則はビルドのオプションで変えられるため、あくまで「こういう名前で生成される」
     * という目安として注記に出すだけで、解決の対象にはしない。
     */
    public String implFqnOf(String declaredTypeFqn) {
        return declaredTypeFqn + implSuffix;
    }
}
