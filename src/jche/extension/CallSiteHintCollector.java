// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
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
