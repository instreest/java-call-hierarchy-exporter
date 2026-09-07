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

import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodInvocation;

/**
 * 拡張ポイント・フェーズA（抽出時）: ASTから任意の証拠を拾ってキャッシュに残す。
 *
 * 具象クラスの特定方法はプロジェクトごとに異なるため、2つのフェーズに分けた
 * 差し込み口を用意している。必要な情報が手に入るタイミングが2つに分かれているため、
 * 1つのインターフェースにはまとめられない。
 *
 * <pre>
 *   フェーズA（抽出時）  : ASTが手元にある。呼び出し箇所の局所的な証拠を拾う
 *                         例) DaoFactory.get("USER_DAO") の文字列リテラル
 *   フェーズB（構築時）  : 全体が見える。型階層や外部ファイルを使って確定する
 *                         例) "USER_DAO" -> jp.co.xxx.dao.UserDaoImpl の対応表
 * </pre>
 *
 * 実装クラスは設定ファイルでFQNを列挙するとリフレクションで読み込まれる。
 * <pre>
 *   resolver.hint.collectors=jp.co.xxx.FactoryKeyCollector
 *   resolver.candidate.providers=jp.co.xxx.FactoryMapProvider
 * </pre>
 *
 * 実装例（ファクトリメソッド）:
 *   DaoFactory.get("USER_DAO") を見つけたら、その戻り値を受けている
 *   ローカル変数のキーに対して add(varKey, "FACTORY_KEY", "USER_DAO") する。
 *
 * @see TypeCandidateProvider フェーズB
 */
public interface CallSiteHintCollector {

    void collect(MethodInvocation node, CompilationUnit cu, String callerMethodKey, HintSink sink);
}
