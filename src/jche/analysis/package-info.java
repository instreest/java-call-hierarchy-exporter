// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
/**
 * フェーズ1（書き手）: <b>解決（resolution）</b>の層。ソースを JDT で解析し、ソースファイル 1 つにつき 1 ブロックの
 * 事実（{@link jche.cache.FileAnalysis}）を作ってキャッシュに書く。
 *
 * <h2>この層の責務（3 層のうちの 1 つ目）</h2>
 * <pre>
 *   解決（この層）      呼び出し式 → コンパイル時宣言（JLS 15.12.1〜15.12.3。JVMS 5.4.3.3 のシンボリック参照に当たる）。
 *                       判定は JDT の IMethodBinding に任せ、結果（宣言した型のキー・修飾子・修飾する型・上書き・
 *                       親クラスの連鎖）を事実として書く。実行時にどの本体が動くかはここでは決めない
 *   キャッシュ（jche.cache）  事実の置き場。解決の結果は「一度成功すれば以後も同じ」（JVMS 5.4.3）なので置いてよい
 *   選択（jche.graph）  受け手の実行時のクラスごとに動く本体を選ぶ（JVMS 5.4.6。{@link jche.graph.MethodSelection}）
 * </pre>
 * 3 層の対応表（どの規則をどこで読み、どの行に書き、どこで選び、どの検査が見るか）は
 * docs/resolution-selection-design.md。
 *
 * <h2>決まり</h2>
 * <ul>
 *   <li><b>事実だけを書く</b>。設定・出力形式・解決の方針に依る判断は読み手（jche.graph / jche.report）に置く
 *       （{@link jche.cache.CacheFormat} の「原則」）</li>
 *   <li><b>JLS の判定は JDT に任せる</b>。上書き（{@code IMethodBinding.overrides}）・サブシグネチャ
 *       （{@code isSubsignature}）・定数の畳み込み・コンパイル時宣言（{@code getMethodDeclaration}）を、名前や引数型の
 *       文字列で近似しない。分からないものは「判定しない」に倒す（{@link jche.analysis.OverrideFacts}）</li>
 *   <li><b>事実はバッチ（JDT に一緒に渡すファイルの組）に依らせない</b>。すべて解決し終えてから集める
 *       （{@link jche.analysis.CallEdgeExtractor#analyzeBatch}）</li>
 *   <li><b>書く事実が変わる変更は {@link jche.cache.CacheFormat#VERSION} を上げる</b>（迷ったら上げる。
 *       上げ忘れは {@code test/cacheversion} が捕まえる）</li>
 *   <li><b>JDT の下限（3.28.0）より新しい API はソースに直接書かない</b>。本体はそこにある JDT の jar に対して
 *       コンパイルされるので、古い jar で動かす人（閉域ネットワークの手順）のところでコンパイルが通らなくなる。
 *       {@link jche.analysis.JdtCompat} に置いて名前で引く（{@code test/jdt-floor} が下限の jar で検査する）</li>
 *   <li>差分更新（{@link jche.analysis.CacheUpdater}）は、I 行に載らない依存を {@link jche.analysis.StaleTypes} で拾う。
 *       依存を足したら {@code test/incremental} に全件解析との一致の検査を足す</li>
 * </ul>
 *
 * <h2>読む順</h2>
 * <pre>
 *   CacheUpdater          差分更新のパスの順序（旧キャッシュの読み・解析し直すファイルの選別・書き写し）
 *     StaleTypes / ReanalysisReason / ReanalysisCascade   どのファイルを解析し直すか
 *     OldBlock / OldCache / DepsIndex                      旧キャッシュの読みと一時ファイル
 *     BlockWriter                                          解析結果を 1 ブロックとして書く
 *   CallEdgeExtractor     JDT に渡すバッチの組み方（ProjectScan・SameUnitFiles）と、失敗したときの立て直し
 *   FactVisitor           AST の訪問。事実の収集を次に分担する
 *     BindingNames        バインディング → キャッシュに書く名前（メソッドのキー・型名・修飾子）と I 行の依存
 *     OverrideFacts       上書き・実装の関係（O 行・H 行の 8 列目・M 行の鍵。JDT の overrides / isSubsignature）
 *     CallSiteRecorder    呼び出し箇所（C 行・U 行）。修飾する型（JLS 13.1）とレシーバの由来
 *     TypeContextTracker  型（H 行。親クラスの連鎖）・合成メソッド・暗黙の super()・宣言の指紋
 *     ImplicitCalls       構文が呼ぶメソッド（拡張 for・try-with-resources・レコードパターン）
 *     LambdaNames         ラムダの合成メソッドの名前
 *     OriginTracker / ValueGraph / GuardCollector / FieldFactCollector / FieldAccessRecorder   値と条件の事実
 *   LibraryDiff / ZipDirectory   依存 jar・クラスフォルダの指紋（L 行）
 *   JdtVersion / JdtCompat       使っている JDT の版（キャッシュの鍵）と、下限（3.28.0）より新しい JDT の API
 * </pre>
 */
package jche.analysis;
