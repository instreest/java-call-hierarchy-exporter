// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
/**
 * フェーズ2（読み手）: <b>選択（selection）</b>の層。キャッシュの事実から呼び出しグラフを組み、呼び出しごとに
 * 「実行時に動く本体」を決める。
 *
 * <h2>この層の責務（3 層のうちの 3 つ目）</h2>
 * <pre>
 *   解決（jche.analysis）  呼び出し式 → コンパイル時宣言。JDT に任せ、結果をキャッシュに書く
 *   キャッシュ（jche.cache）  事実の置き場
 *   選択（この層）      受け手の実行時のクラス C と解決済みの宣言 mR から、動く本体を決める（JVMS 5.4.6。JLS 15.12.4.4）。
 *                       ソース解析では C が 1 つに決まらないので、この層は 2 つの問いに分けて答える:
 *                         (1) C としてありうる型はどれか … {@link jche.graph.CallResolver} の段（CHA・LOCAL_NEW・値の追跡・
 *                             契約表・DI）。静的束縛（invokestatic / invokespecial に当たる呼び出し）は
 *                             {@link jche.graph.BindKind} が先に決める
 *                         (2) その C で動く本体はどれか … {@link jche.graph.MethodSelection}（JVMS 5.4.6 の手順。実装探索の
 *                             入口はここだけ）
 * </pre>
 * 3 層の対応表は docs/resolution-selection-design.md。
 *
 * <h2>決まり</h2>
 * <ul>
 *   <li>実装探索は {@link jche.graph.MethodSelection#implementationOf} / {@link jche.graph.MethodSelection#implementationOfSignature}
 *       の 2 つの入口だけを通す。「継承」と「型引数の置換（O 行・H 行の 8 列目）」を 1 つの探索で見る作りなので、
 *       別の引き方を足すと片方を取りこぼす</li>
 *   <li>実装を探す順は JVM と同じ（親クラスの連鎖 {@link jche.graph.TypeHierarchy#classChain} を根まで → 最も特定的な
 *       親インターフェース {@link jche.graph.TypeHierarchy#superinterfaces} / {@link jche.graph.TypeHierarchy#mostSpecific}）。
 *       名前順の {@link jche.graph.TypeHierarchy#directSupertypes} の順に辿って実装を探さない</li>
 *   <li>CHA の起点は呼び出しを修飾する型（JLS 13.1。C 行の qualifier。{@code CallResolver#usableQualifier}）。
 *       jar の型なら宣言した型に倒す（多すぎる側）</li>
 *   <li>{@link jche.graph.CallResolver#resolve} はエッジの処理順に依らない（メモ化されるので、最初と後で答えが変わる
 *       作りにしない。{@code test/dataflow} の ResolveOrderCheck）</li>
 *   <li>迷ったら候補を落とさない側（多すぎる側）に倒す。分からないものは「判定しない」</li>
 *   <li>この層だけの変更（解決の方針・注記の文言）ではキャッシュの版を上げなくてよい</li>
 * </ul>
 *
 * <h2>読む順</h2>
 * <pre>
 *   CallGraphBuilder   キャッシュを 1 回スキャンして CallGraph（CSR）・MethodTable・TypeHierarchy・OverrideIndex・
 *                      ValueStore（ValueStoreBuilder）・GuardTable を組む
 *   CallGraph          エッジと値の置き場。selection() / hierarchy() / methods() / values()
 *   BindKind           静的束縛か仮想呼び出しか（C 行の calleeMods から）
 *   MethodSelection    JVMS 5.4.6 の選択。TypeHierarchy（H 行）・OverrideIndex（O 行）・MethodTable（D 行）を材料にする
 *   CallResolver       受け手の型の候補を求める段（Resolution のラベルが call-hierarchy.csv の resolved-by になる）
 *     DataflowResolver / DataflowContext / Slot   値の表（ValueStore）と経路の値から具象型を追う
 *     SpringBeans / FieldFacts                    DI の注入点と Bean
 *     TypeContracts / CallbackContracts / FrameworkEntries / FactoryCalls   契約表
 *     UnresolvedCalls / InboundIndex / EntryPoints   未解決の一覧・被参照・起点
 * </pre>
 */
package jche.graph;
