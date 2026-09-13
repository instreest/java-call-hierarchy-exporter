package org.seasar.doma;

/**
 * Doma の @Dao の代役（回帰テスト用のスタブ）。
 *
 * 本物は org.seasar.doma:doma-core の jar にあり、これを付けたインターフェースの
 * 実装クラス（<インターフェース名>Impl）はコンパイル時にアノテーションプロセッサが生成する。
 * 回帰テストで jar を持ちたくないので、同じ完全修飾名の空のアノテーションをソースに置いている。
 */
public @interface Dao {
}
