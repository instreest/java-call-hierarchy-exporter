// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
/**
 * <b>キャッシュ（橋渡し）</b>の層。書き手（jche.analysis。解決）が残した事実を、読み手（jche.graph。選択）が読む
 * 1 ファイル {@code analysis-cache.tsv} の形式と、行 1 つ 1 つの record。
 *
 * <h2>3 層の中の位置</h2>
 * <pre>
 *   解決（jche.analysis）  →  キャッシュ（この層）  →  選択（jche.graph）
 * </pre>
 * ここに置いてよいのは「コンパイル時に決まり、以後も同じ」もの（JVMS 5.4.3 が定める解決の性質）。
 * 実行時の情報（どの本体が動くか、候補を絞る方針）は置かない。行の意味と並びは {@link jche.cache.CacheFormat}
 * のクラスコメントが正本で、各行の列は record の {@code toRow} / {@code fromRow} が定義する。
 *
 * <h2>選択に効く行（読み手が JVMS 5.4.6 を組み立てる材料）</h2>
 * <pre>
 *   C 行  呼び出し先（コンパイル時宣言のキー）・calleeMods（静的束縛の判定。jche.graph.BindKind）・
 *         recvKind（レシーバの由来）・qualifier（JLS 13.1 の修飾する型。CHA の起点）
 *   D 行  修飾子（private・static・final・default・abstract）と本体の有無
 *   H 行  親型（3 列目）・親クラスの連鎖（7 列目。JVMS 5.4.6 の 2 段目の順）・継承した実装（8 列目）
 *   O 行  型引数を具体化してキーの食い違う上書き（JDT の IMethodBinding.overrides）
 *   M 行  ラムダ・メソッド参照が実装する抽象メソッドの鍵（invokedynamic に当たる呼び出しの本体）
 * </pre>
 *
 * <h2>決まり</h2>
 * <ul>
 *   <li>形式・収集範囲・書き手が作る文字列を変えたら {@link jche.cache.CacheFormat#VERSION} を上げる（迷ったら上げる）</li>
 *   <li>行を足すときは {@link jche.cache.CacheFormat} の並びに合わせて書き手（jche.analysis.BlockWriter）と読み手
 *       （jche.graph.CallGraphBuilder）の両方を直す</li>
 *   <li>メソッドを指す列は S 行の番号、値は N 行のノード番号で、どれもブロックの中だけで通じる</li>
 *   <li>読む・書く処理は {@link jche.cache.CacheLock} の内側に置く。一時ファイルは {@link jche.cache.TempFiles} で作る</li>
 * </ul>
 */
package jche.cache;
