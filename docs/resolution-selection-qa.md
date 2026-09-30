# 解決・キャッシュ・選択の 3 層に沿ってコードを組み直した件 — 記録 Q&A

生成 AI（と初めて触る人）が理解・修正しやすい単位にすることを第一の目的に、
機能要件を変えたときの影響範囲が層で分かることを第二の目的にして、コードの置き場所を組み直した。
対応表そのものは [resolution-selection-design.md](resolution-selection-design.md)。ここでは迷ったことと結論、却下した案を残す。

対応の要点:

- 読み手の「具象型で実際に動く本体を選ぶ」コード（JVMS 5.4.6 の写し）を `CallGraph` から `jche.graph.MethodSelection` に
  切り出した。実装探索の入口は `graph.selection()` の `implementationOf` / `implementationOfSignature` の 2 つ（以前と同じ 2 つ）
- 書き手の「上書き・実装の関係の事実」（O 行・H 行の 8 列目・M 行の鍵。JDT の `overrides` / `isSubsignature` に任せる部分）を
  `BindingNames` から `jche.analysis.OverrideFacts` に切り出した
- 3000 行あった `CacheUpdater` の入れ子クラスを、差分更新の「どのファイルを解析し直すか」（`StaleTypes`・`ReanalysisReason`・
  `ReanalysisCascade`）、「解析結果を 1 ブロックとして書く」（`BlockWriter`。行の並びと記号表の番号の振り方を含む）、
  「旧キャッシュの読みと一時ファイル」（`OldBlock`・`OldCache`・`DepsIndex`）に分けて別ファイルにした
- `jche.analysis` / `jche.cache` / `jche.graph` に `package-info.java` を置き、層の責務・決まり・読む順を書いた
- 動きは変えていない（出力・キャッシュの事実は同じ。キャッシュの版は上げない）

---

## Q1. なぜ「解決」と「選択」を分ける見方を採ったのか

JVMS は呼び出しを、シンボリック参照を宣言に結び付ける**解決**（5.4.3.3。一度決まれば以後も同じ）と、受け手の実行時のクラスから
本体を決める**選択**（5.4.6。呼び出し 1 回ごと）の 2 段で規定している。このツールの 3 層（書き手・キャッシュ・読み手）は
最初からこの構図に沿っていた——解決は JDT に任せて結果をキャッシュに置き、選択は読み手が毎回組み立てる——のに、
コードの置き場所にはそれが現れていなかった。選択の写しが `CallGraph`（CSR の配列・値の表・DI の判定と同居）の中に埋まり、
上書きの事実の書き手が `BindingNames`（名前の綴りと I 行の依存を作るクラス）の中にあった。

漏れが続く原因を「設計と要件が合っていない」と疑うなら、まず規定のどの行がコードのどこに写っているかを 1 対 1 で
言えなければならない。層の名前を規定の用語（解決・選択）に合わせ、写しを 1 クラスに集めるのが、その第一歩だと判断した。

## Q2. `MethodSelection` に何を入れ、何を入れなかったか

入れたのは `CallGraph` にあった実装探索の一式: `implementationOf` / `implementationOfSignature` / `search` /
`declarationIn` / `inheritedImplementationIn` / `declaredAmong` / `packageAccessOf` / `overridesAcrossPackage` /
`passesBinaryClass` / `hasOverriders` / `overridingImplementations`。どれも「型 C と宣言 mR から本体を返す」か、その派生
（どの部分型から引いても mR のままか）で、JVMS 5.4.6 の問いに答えるもの。

入れなかったのは `CallResolver` の段（CHA・LOCAL_NEW・値の追跡・契約表・DI）。これは「C としてありうる型はどれか」を
静的に近似する部分で、JVMS には無い（実行時なら受け手のオブジェクトを見れば済む）。選択の写しと混ぜると、
「規定に合っているか」と「近似が粗いか」の区別がつかなくなる。`BindKind`（静的束縛の判定。命令の種類に当たる）も
そのままにした。小さく、C 行の calleeMods だけから決まり、段 0 で使う場所が 1 つなので、動かす利益が無い。

## Q3. 入口を `CallGraph` に残して委譲すれば、呼び出し元を書き換えずに済んだのでは

残すと入口が 2 つ（`graph.implementationOf(...)` と `graph.selection().implementationOf(...)`）になり、
「実装探索は 2 つの入口だけを通す」という決まり（AGENTS.md）が「どちらの 2 つか」で揺れる。呼び出し元は 5 ファイル 21 か所で、
`graph.selection().` を挟むだけの機械的な書き換えなので、書き換えて入口を 1 か所にした。
ドキュメントとテストのコメントの `CallGraph#implementationOf` も `MethodSelection#...` に直した（`grep` で残りが無いことを確認）。

## Q4. `OverrideFacts` を切り出す境界はどこか

`BindingNames` には 2 種類の仕事があった。(1) バインディングからキャッシュに書く名前（型名・メソッドのキー・修飾子・注釈）を
作り、I 行の依存を記録する。(2) 上書き・実装の関係を JDT に尋ねて事実にする（`overriddenKeysOf` → O 行、
`inheritedImplementationsOf` → H 行の 8 列目、`functionalKeysOf` → M 行の鍵）。

(2) は、読み手の `MethodSelection` が「上書きできる宣言」を決める材料をちょうど作る側で、選択の写しと対になる。
ここを 1 クラスにすると「JLS 8.4.8.1 の判定は JDT に任せ、文字列で近似しない」という決まりの置き場所がはっきりする。
`functionalKeysOf` は上書きではなく JLS 9.8 の「上書き同等」だが、同じ `isSubsignature` で同じ性格の事実（本体がどの宣言を
実装するか）なので一緒にした。共通の道具（`toRef`・`supertypesOf`・`keyOf`）は `BindingNames` に残し、`OverrideFacts` が
`BindingNames` を持って呼ぶ（`toRef` は I 行の依存を記録する副作用があるので、複製せず 1 か所のまま）。

## Q5. `CacheUpdater` の分け方はなぜ入れ子クラスの単位か

3000 行の中で、入れ子の static クラスは既に責務の境界だった（外側のインスタンスの状態に触れない）。それを別ファイルに出すのは
機械的で、動きが変わる余地が無い。逆に、`run()` やパス1〜5 のメソッドを分けるのは、パスの間で共有する状態
（`hashes`・`oldDeclarations`・`changedDuringRun`・`parsedThisRun`）の受け渡しを設計し直すことになり、
差分更新の健全性（`test/incremental` の 100 件以上のケース）を危険にさらす。今回はしない。

出したものの名前は、`Reason` → `ReanalysisReason`、`Cascade` → `ReanalysisCascade` に変えた（パッケージの中で `Reason` は
何の理由か分からない）。`BlockWriter` には、`CacheUpdater` の末尾にあった「1 ファイル分の事実をブロックの行にする」
static メソッド群（`writeBlock` と補助）も移した。ブロックを書く側はこの 1 ファイルで閉じる。

## Q6. 「文字列比較で上書きを判定している箇所」は見つかったか

助言のとおり、そこが漏れの発生源になりやすいので調べた。結論は「判定そのものを文字列で行っている箇所は、
読み手のキーの照合（`MethodSelection#declarationIn`）と、書き手の 2 か所（`ImplicitCalls`・暗黙の `super()`）」。

- 読み手のキーの照合は、消去した引数型のキーが同じ＝ JVM のディスクリプタが同じ、なので JVMS 5.4.5 の条件と一致する。
  JLS 8.4.8.1 のサブシグネチャ（型引数の置換。javac ならブリッジメソッド）はキーに現れないので、O 行と H 行の 8 列目で補う。
  漏れを疑うなら「この 3 つの材料のどれにも載らない形」を探す。今のところ、jar のメソッドと合成したメソッドに O 行が無いことが
  構造上の限界（点検表の #14）
- 書き手の `ImplicitCalls` は、拡張 for・try-with-resources に呼び出し式が無く JDT に尋ねられないので、メンバーの探索を
  自前で持つ。`MethodSelection` と同じ順（クラスの連鎖 → 最も特定的なインターフェース）だが、実装が 2 つある
- そのほかの文字列比較は、JDT に尋ねる前の絞り込み（名前と引数の数）か、「キーが同じなら書かなくてよい」の判断で、
  判定ではない

対応表の点検表（design の 6 節）には、これらと、調査の途中で見つけた疑い（jar の親の親を経由する部分型が CHA の候補に
入らない、など）を層と倒れる側つきで並べた。**直していない。** 直す判断は別にする（利用者の指示どおり）。

## Q7. `package-info.java` に何を書いたか。クラスコメントと重複しないか

パッケージごとに「3 層のうちのどれか・決まり・読む順」だけを書いた。個々のクラスの中身はクラスコメントに任せる。
生成 AI が最初に読むのは `AGENTS.md` → パッケージの `package-info` → クラスコメントの順になるので、それぞれの粒度を分けた。
`-Xdoclint:all` の対象なので、`{@link}` は解決できるものだけを書いた（package-private のクラスへのリンクは同じパッケージなら通る）。

## Q8. 却下した案

- **パッケージを `resolution` / `selection` に改名する**: 既存の `docs/` と `AGENTS.md` の参照が数百か所あり、
  読み手が慣れた `analysis` / `graph` の名前を捨てる利益が見合わない。層の名前は `package-info` と設計文書で与える
- **`CallResolver` の段も `MethodSelection` に寄せる**: Q2 のとおり、規定の写しと静的な近似を混ぜない
- **`ExternalUsageScanner#inheritedFrom` を `MethodSelection#implementationOfSignature` に置き換える**: 結び先が変わりうる
  （O 行を見るようになる）ので出力が変わる。今回は「動きを変えない」ので点検表に載せるだけにした（#6）
- **`CacheUpdater` のパスごとの分割**: Q5
- **キャッシュの版上げ**: 事実は変えていないので上げない。`test/cacheversion` が指紋の一致で確かめる

## Q9. 何で確かめたか

動きを変えないリファクタリングなので、既存の検査をすべて通した: `test/regression`・`test/cacheversion`（事実の指紋が同じ）・
`test/dataflow`・`test/jls`・`test/pruning`・`test/incremental`・`test/ctorbody`・`test/conditions`・`test/warnings`・
`test/cachevalue`・`test/contracts`・`test/pom`・`test/nls`・`test/server` と、JDK 25 の javac の lint（`-Xlint:all -Werror -Xdoclint:all,-missing`）。

## Q10. 「クラスの連鎖 → 最も特定的な親インターフェース」の写しが 3 か所にある（#189）

[Issue #189](https://github.com/instreest/java-call-hierarchy-exporter/issues/189)。同じ順が `jche.graph.MethodSelection#search`（選択。
正本）・`jche.analysis.ImplicitCalls#findNoArgMethod`（解決。拡張 for の `iterator()`・try-with-resources の `close()`）・
`jche.external.ExternalUsageScanner#inheritedFrom`（被参照）にある。順の決まりを変えるとき、片方を忘れると暗黙の呼び出しの宣言や
被参照の結び先だけが古い順のまま残り、エラーにならない。

**結論: 1 つにはまとめず、正本を `docs/resolution-selection-design.md` の 4 節に置き、「同時に直す」の決まりを AGENTS.md の
コードの決まりに 1 項目足し、3 つのクラス javadoc が互いを指すようにした。** `ExternalUsageScanner` は別途 `MethodSelection` の
入口（`implementationOfSignature`）に置き換える（点検表 #6・#186）ので、写しはいずれ 2 つになる。

却下した案: **型階層を抽象化して 1 つの探索に寄せる**（`TypeHierarchy` と JDT の `ITypeBinding` の両方を同じインターフェースで
包み、`MethodSelection#search` を両方の上で動かす）。層が違い材料が違う。`ImplicitCalls` はフェーズ1 の解決の層で、材料は JDT の
バインディング（型引数を置換したメソッド・public の宣言だけ・jar の型のメンバーも見える）、`MethodSelection` はフェーズ2 の選択の
層で、材料はキャッシュの行（O 行・継承した実装・パッケージアクセス・本体の有無。jar の型のメンバーは見えない）。同じ順でも
「見るもの」が違うので、共通のインターフェースは両方の材料の和になり、片方でしか意味の無い口（O 行・`hasBody`）をもう片方が
空で返す形になる。解決の層から選択の層のクラスを呼ぶ（フェーズ1 が `jche.graph` に依る）のも、3 層の向きを崩す。抽象化で得る
のは順の一致だけで、それは「同時に直す」の決まりと相互参照で足りる。
