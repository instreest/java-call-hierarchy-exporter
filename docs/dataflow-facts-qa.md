# データフローの事実の一括確定（フェーズ2b） — 実装時の QA 一覧

[Issue #80](https://github.com/instreest/java-call-hierarchy-exporter/issues/80)
「CallResolver.resolve が呼ぶ順で結果が変わる不具合を修正したい」への対応で、
迷ったこと・困ったことと、その結論を Q&A の形で残す。

対応の要点:

- フェーズ2を「2a グラフ構築（`CallGraphBuilder`）/ 2b データフロー確定（新設 `jche.dataflow.DataflowBuilder`
  → `DataflowFacts`）/ 2c 解決（`CallResolver`）」に分けた。ファクトリの戻り値の畳み込みは 2b が
  グラフ全体から一括で確定し、`DataflowResolver` は不変の `DataflowFacts` を読むだけの問い合わせ器に縮めた
- `CallResolver.resolve(edgeIndex)` は同じグラフなら何回・どの順で呼んでも同じ結果を返す（副作用でメモを育てない）
- 畳み込みは「深さ上限つき再帰」から「上限なしの不動点」へ。循環する委譲は「決められない」で確定する。
  `dataflow.max.depth` は経路依存の探索（`classOf` / `literalOf`）にだけ効く
- `test/dataflow/run.sh` を追加。`test/demo` の全エッジを「昇順 → 同じ resolver でもう一度昇順 → 新しい
  resolver で降順」の 3 通りで解決し、結果が完全に一致することを CI（`smoke.yml`）でも検査する
- 段階は Issue の S1（案B: 絶対深さで決定化）→ S3（案C: 不動点化）の順に、別のコミットで入れた

---

## 再現と検証

### Q1. 症状は本当に再現するか。どこで出るか

再現する。`test/dataflow/run.sh` を旧実装に対して動かすと、Issue に書かれた edge 34 がそのまま出た。

```
edge 34  fx.app.Main#run(java.lang.String[]) -> fx.dao.Dao#describe()
  1周目 = [fx.dao.OrderDaoImpl#describe(), fx.dao.UserDaoImpl#describe()]  CHA
  2周目 = [fx.dao.UserDaoImpl#describe()]  DATAFLOW_FACTORY
  逆順  = [fx.dao.UserDaoImpl#describe()]  DATAFLOW_FACTORY
```

`Main.run` の 60 行目 `Chain.c1().describe()` で、`Chain` は c1 → … → c7 の 7 段の委譲（`test/demo/src/fx/dao/Chain.java`）。
`dataflow.max.depth=5` に当たって 1 周目は不明（CHA）だが、61 行目の `Chain.c6().describe()` が c6 を
深さ 0 から確定させてメモに載せるので、2 周目の c1 は c5 まで辿ったところでメモに当たり、残りの予算を数え直さずに確定する。

### Q2. 「CSV 出力には実害なし」は正しかったか

**正しくなかった。** 旧実装の期待出力（`test/regression/{whole,entry}/expected`）には、この症状が 2 つの形で焼き付いていた。

| ファイル | 内容 | どの周の結果か |
|---|---|---|
| `call-hierarchy.csv` | `Main.run` 60 行目が `解決:DATAFLOW_FACTORY`（1 件に確定） | 2 周目（`StreamingTreeWalker`） |
| `methods.csv` | `OrderDaoImpl.describe` の inDegree が 12（c1 経由の CHA 候補を数えている） | 1 周目（`InventoryReport` → `inDegrees()`） |

つまり同じ実行の中で、集計は「候補 2 件」、階層は「1 件に確定」と食い違っていた。走査順が固定なので
「毎回同じ CSV」ではあったが、「正しい CSV」ではなかった。決定化した今は両者が一致する（inDegree 11）。

### Q3. 検査は何を比べるか。2 周では足りないか

2 周（同じ resolver で 2 回）は「メモが育つ」ことの検出で、旧実装ではこれだけで edge 34 が出る。
それに加えて **新しい resolver で降順** も比べる。2 周目が 1 周目と一致しても「1 周目の順序に依存している」
可能性は残るので、処理順そのものを変えた結果が同じであることまで見る。3 通りとも `Resolution` の
`targets` と `label` の両方で比べる（ラベルだけ違う＝根拠が違う、も食い違いとして扱う）。

### Q4. 検査プログラムはどう動かすか。回帰テストに混ぜられないか

回帰テストは CSV の比較なので、「呼ぶ順を変えて同じか」は CSV からは分からない。別のスクリプト
`test/dataflow/run.sh` にした。ツール本体（`src/`）と検査プログラムを javac でコンパイルし、jbang が用意した
JDK 25 と JDT の jar で動かす（CI の lint と同じ経路。実行 JDK を 25 に揃える理由は `CallHierarchyExporter.java` 冒頭）。
本体に「検査モード」を足すことはしなかった。利用者向けの入口に検査だけの分岐を増やしたくないため。

`CallResolver` の組み立て（`DataflowBuilder.build` → `DataflowResolver` → `CallResolver`）は本体と検査で
同じでなければ意味が無いので、検査側の `ResolverFactory` に本体と同じ順で書いてある。本体の組み立てを
変えたらこちらも合わせること。

## 設計

### Q5. なぜ「メモに当たったとき深さを数え直す」だけで済ませなかったか

それでも決定的にはなる（Issue の案B）が、事実を育てる場所が解決の中に残るので、部分的に解決する処理
（差分更新・並列化・途中から辿る画面）を書くたびに同じ罠を踏む。Issue の要求どおり、事実の確定を
解決の前に一括で済ませる層（2b）を作り、解決は読むだけにした。一括計算は遅延計算と総量が同じで、
位置がフェーズ2の中で前に動くだけ（`test/demo` では 1 ms 台）。

### Q6. 段階 S1（案B）を経由する意味はあったか

あった。S1 だけを入れた時点で回帰テストを回すと、`Main.run` 60 行目が CHA 候補 2 件に戻り、
期待出力が変わった（Q2）。これが「決定化のせい」だと切り分けられたのは、畳み込みの強さを変えていない
S1 の時点で見たからで、S3 と一緒に入れていたら「なぜ変わらないのか」（畳み込みが強くなって元に戻った）
が説明できなかった。S1 のコミットには案B の期待出力を入れ、S3 のコミットで戻している。

### Q7. 不動点にするとき、強連結成分の縮約は要るか

要らなかった。委譲の依存関係（`return create();` と、実引数に書かれたファクトリ）を深さ優先で辿り、
「計算中のメソッドにもう一度来たら不明」とすれば、循環に属するメソッドは必ず不明になる
（循環を回る return が 1 つでもあれば「1 つでも追跡できない return があれば不明」の規則で不明）。
循環に属さないメソッドの結果は、自分の依存先（循環より外側）だけで決まる。よって深さ優先 + 計算中の印で
結果は起点によらず定まり、SCC を明示的に作る必要は無い。

ただしメモの扱いには注意した。上限（Q8）か循環に当たった結果は起点によって変わりうるのでメモに載せず、
メモを再利用するときも「その結果を得るのに使った段数」を現在の深さに足して上限を検査する（`Folded.consumed`）。
これが案B の「絶対深さ」の実装で、S3 では上限が安全策の 512 段になっただけで仕組みは同じ。

### Q8. 上限を完全に無くしてよいか

再帰で辿るので、上限なしはスタックの安全策が無くなる。設定では変えられない 512 段（`DataflowBuilder.HARD_CAP`。
`StreamingTreeWalker` の実効上限と同じ値）を残した。実在するファクトリの委譲は数段なので当たらない。
当たったときは案B と同じ「不明」で、ログの「決められなかったもの N 件」に含まれる。

### Q9. `dataflow.max.depth` は廃止しなくてよいか

廃止しなかった（Issue の案C）。`classOf` / `literalOf`（引数で渡ってきたクラス名の文字列やクラスリテラルを、
リフレクションの解決のために経路上で辿る）は経路依存でメモ化できず、上限が要る。設定の意味を
「ファクトリの委譲の段数」から「経路依存の探索の段数」に絞り、`config/config.properties` の説明を書き換えた。
既定値 5 はそのまま。

### Q10. `applyInvocationArgs` が Builder と Resolver の両方にあるのはなぜか

畳み込みの途中で `return create(make());` の実引数 `make()` を畳むには、Builder の中で「出所 → 具象型」が要る。
一方 Resolver の同名メソッドは経路の情報（`DataflowContext`）も使う。共通化するには Resolver が
「事実の途中経過」を見る形になり、確定前の事実を読む経路がまた生まれるので、Builder には
経路の情報を使わない版を別に持たせた（15 行ほど）。Resolver 側のコメントから相互に参照している。

### Q11. 実引数に書かれたファクトリは、どの深さから畳むか

深さ 0 から（`return create(make());` の `make()` は、`create` までに使った段数を引き継がない）。
「`make()` の戻り値」はそのメソッド固有の事実で、どこから呼ばれたかに依存しないため。
旧実装も同じ（`applyInvocationArgs` → `concreteTypeOf` → `factoryReturnOrigin(id)` は深さ 0 から）で、
S1 の時点でも振る舞いを変えないためにそろえた。S3 では上限が実質無いので、この区別は結果に影響しない。

### Q12. `usesParameters` / `reflectKinds` / `methodsByName` も Builder に移す必要はあったか

順序依存の原因ではなかった（初回に一度だけグラフ全体を見て作る）が、「グラフ全体から一括で作る事実」
という性質は同じなので、`DataflowFacts` にまとめた。Resolver に残すと「遅延で作る配列」と
「渡される配列」が混ざり、次に何かを足すときにどちらに置くかで迷う。

## 影響範囲

### Q13. `InboundIndex.warmUp`（Issue の S2）はどうしたか

このリポジトリには `InboundIndex` も `docs/dataflow-phase-proposal.md` も無い（Eclipse プラグインは別の場所）。
ここでは本体を決定的にするところまでを入れた。プラグイン側は `DataflowBuilder.build` →
`DataflowResolver(graph, facts, …)` の順に組み立てれば、索引作成前に全エッジを 1 周させる回避は不要になる。

### Q14. 事実のダンプ（Issue の S4）は入れなかったのか

入れていない。出力ファイルが増える変更は利用者に見える仕様なので、必要になってから
（`dataflow-facts.csv` の形を決めてから）別に入れる。デバッグに使うなら `DataflowFacts.factoryOrigin(id)` を
`MethodTable.key(id)` と並べて出せばよく、その足場は `test/dataflow/ResolveOrderCheck.java` にある。

### Q15. main 側で先に入った同じ症状への対応（`code-review-fixes-qa.md` の Q2）とはどう違うか

この作業と並行して、main には「上限をそのメソッドから数えた委譲の段数に掛ける」形の対応
（`DataflowResolver.factoryHops`。Issue の案B と同じ考え方）が入っていた。決定性という意味では
どちらでも症状は消える。違いは 2 つ。

- main の対応は事実を育てる場所（遅延メモ）が解決の中に残る。ここでは Issue の要求どおり、
  確定を解決の前の層（2b）に出した（Q5）
- main の対応は上限 5 で打ち切るので `Chain.c1()` は CHA 候補 2 件のまま。ここでは S3 で
  上限なく畳むので 1 件に確定する。main を取り込んだあと、期待出力（whole / entry / jarchange）の
  この差分だけを最新の出力で更新した

取り込みで `DataflowResolver` が両方から変わって衝突したが、main 側の変更は畳み込みのアルゴリズム
（Builder へ移した部分）と `Names.parseIntOr` への置き換えだけだったので、後者を Builder と Resolver の
両方に取り込み、前者は Builder の実装で置き換えた。

### Q16. 出力の変化をどう確認したか

`bash test/regression/run.sh` の全ケース（whole / entry / jarchange / maven / mavenmulti / gradle / plugin / multi）。
main との差は `whole` と `entry` の `methods.csv` の 1 セル（`OrderDaoImpl.describe` の inDegree 12 → 11。Q2）だけ。
`call-hierarchy.csv` は全ケースで一致した。ログには「ファクトリの戻り値を確定: N 件」を出すようにし、
`test/demo` では 14 件（S1 時点では 13 件 + 上限で不明 1 件）。
