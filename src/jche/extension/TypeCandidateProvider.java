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
package jche.extension;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * 拡張ポイント・フェーズB（構築時）: 宣言型と証拠から具象型の候補を返す。
 *
 * 実装例（ファクトリの対応表）:
 *   hints に FACTORY_KEY があれば、対応表を引いて具象クラスFQNを返す。
 *
 * @see CallSiteHintCollector フェーズAと、拡張の読み込み方法
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
