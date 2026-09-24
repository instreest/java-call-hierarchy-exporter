// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.extension;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * 拡張ポイント: ソースの外（JDK・フレームワーク）との契約を、表では書けない条件で返す。
 *
 * 返す行の形は設定ファイルの {@code contracts.files} と同じ（docs/callback-contracts.md）。
 * <pre>
 *   jp.co.xxx.Dispatcher#submit(java.lang.Runnable) -> a0 : run()   … 呼び戻し（種類 A）
 *   &#64;jp.co.xxx.Endpoint                                             … 入口（種類 B）
 *   super jp.co.xxx.BaseAction#execute()
 *   static main(java.lang.String[])
 *   main                                                          … 起動の入口（JLS 12.1.4）
 * </pre>
 * 対応表を書けば済むなら、この拡張を書かずに {@code contracts.files} にファイルを置けばよい。
 * 読み込み方は {@link TypeCandidateProvider} と同じ（{@code plugin.folders} に置き、
 * {@code contracts.providers} に FQN を書く）。
 */
public interface ContractProvider {

    /** 設定ファイルの内容と、その置き場所（相対パス解決の起点）を受け取る */
    default void init(Properties config, Path configDir) {
    }

    /** 契約の行。空行と {@code #} で始まる行は無視される */
    List<String> lines();
}
