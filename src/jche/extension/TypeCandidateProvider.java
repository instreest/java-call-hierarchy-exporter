// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.extension;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * 拡張ポイント: 宣言型と証拠（{@link Hint}）から具象型の候補を返す。
 *
 * <p>これがこのツールで唯一、解決の条件を Java で書ける差し込み口。グラフを組むときに動き、
 * キャッシュには何も書かないので、足しても外してもキャッシュは捨てられない。
 *
 * <p>実装例（ファクトリの対応表）:
 *   hints に FACTORY_KEY があれば、対応表を引いて具象クラスFQNを返す。
 *   これは同梱の {@link jche.builtin.TypeMappingProvider} がそのまま行うので、
 *   対応表を書けば済む場合は自分で実装しなくてよい。
 *
 * <p>読み込み方は {@code plugin.folders} に {@code .java} / {@code .class} / {@code .jar} を置き、
 * {@code resolver.candidate.providers} に FQN を書く（docs/instance-analysis-plugin.md）。
 */
public interface TypeCandidateProvider {

    /** 設定ファイルの内容と、その置き場所（相対パス解決の起点）を受け取る */
    default void init(Properties config, Path configDir) {
    }

    /**
     * 静的束縛（段0）と判定された呼び出しにも、この拡張を適用するか。
     *
     * Javaの言語仕様上は、private/static/final・finalクラス・コンストラクタ・
     * super呼び出しは仮想ディスパッチされないため、DIコンテナのプロキシ
     * （CGLIBはサブクラス生成、JDK動的プロキシはインターフェース実装）でも
     * 実行される本体は変わらない。よって既定では段0を確定として扱う。
     *
     * ただし、バイトコード織り込み（AspectJのCTW等）や独自フレームワークの
     * 仕掛けによって、この前提が崩れる可能性は残る。そうした環境では
     * true を返すことで、段0の呼び出しにも解決を差し込める。
     *
     * 段0で打ち切ってしまうと拡張に到達せず、呼び出し階層がそこで
     * 切れてしまうため、この逃げ道を用意している。
     */
    default boolean appliesToStaticBound() {
        return false;
    }

    /**
     * @return 具象型のFQN配列。解決できない場合は null または空配列
     */
    String[] candidates(String declaredType, String signature, List<Hint> hints);

    /** CSVの由来ラベルに出る名前。例: "CUSTOM_FACTORY" */
    String label();
}
