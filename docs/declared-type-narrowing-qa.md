# 経路で渡された値の宣言の型による候補の絞り込み — 実装時の QA 一覧

[Issue #192](https://github.com/instreest/java-call-hierarchy-exporter/issues/192)
「Spring のコンストラクタ注入した変数でインターフェース型で呼び出ししたときに経路ごとにインスタンスを絞れるようにしたい」
への対応で、迷ったこと・困ったことと、その結論を Q&A の形で残す。

対応の要点:

- 具象型までは決まらなくても、経路で渡ってきた値の**宣言の型**（具象クラスの型で宣言したフィールド・引数）が分かれば、
  受け手の実行時の型はその部分型に限られる。読み手（`jche.graph.CallResolver#narrowByBound`）が、段 1 の候補
  （修飾する型の部分型の実装）をその上限の部分型の実装との交わりに絞る。1 つに定まれば
  `RESOLVED:DATAFLOW_DECLARED_TYPE`、複数残れば `UNEXPANDED:CHA` のまま候補の数だけ減る
- 上限は経路の環境（`DataflowContext`）の新しい枠 `Slot.BOUND`（中身は `TypeHierarchy#indexOf` の番号）で運ぶ。
  `DataflowResolver#bindArgs` が、具象型も値も決まらない実引数に宣言の型の上限を入れ、`boundTypeOf` で読む。
  コンストラクタ実引数の上限は、コンストラクタで受け取るフィールドを経て次の呼び出しへも運ばれる
- 宣言の型はキャッシュに既にある（フィールドは V 行の `declType`、引数はメソッドキーの引数の型）。**キャッシュの形式は
  変えない**（版を上げない）。読み手が V 行の宣言の型を控える（`CallGraph#fieldDeclType`）だけ
- 段 5（DI）の結論が上限と矛盾すれば（絞った Bean が、この経路で渡した値の型の部分型でない）、経路の事実を採る。
  候補が複数残れば、上限の型を起点に段 5 をもう一度引く（修飾する型と同じ扱い）
- 検査は `test/pruning/run.sh` の `Dt*` / `SbDecl*`（Issue の形・引数の受け渡し・引数をそのまま返すメソッド・キャスト・
  コンストラクタ実引数経由・部分型が複数なら CHA のまま減る・インターフェース型なら絞らない・段 5 との組み合わせ）

---

## 設計の判断

### Q1. 何が起きていたのか

Spring らしい書き方で、呼び出し元によって実装が変わるインターフェース呼び出しを、経路ごとに 1 件に絞れなかった。

```java
// OrderExportService
public String export(LocalDate from, LocalDate to, OrderExporter exporter) {
    return exporter.export(orders);          // ← 宣言型は OrderExporter（インターフェース）。実装は 2 つ
}
// PartnerOrderController
private final XmlOrderExporter xmlOrderExporter;   // @Component の Bean をコンストラクタ注入
...
return orderExportService.export(from, to, xmlOrderExporter);
```

`exporter.export(orders)` は両方の経路で `[UNEXPANDED:CHA] 2 candidates: parameter (passed in from outside the method)`
だった。実引数の出所を呼び出し元まで遡ると `F:PartnerOrderController#xmlOrderExporter`（フィールド）で、その値は
コンテナが入れる（ソースに `new` が無い）ので、値の追跡（`DATAFLOW_PARAM`）では具象型が決まらない。段 5（DI）は
`export` の引数が注入点でない（`export` は Bean のコンストラクタでも `@Autowired` のメソッドでもない）ので効かない。

呼び出しは落ちておらず（候補は 2 つとも行に残る）、多めに出る側に倒れていた。困るのは「改修時の影響調査」で、
経路ごとに違う実装が動くことが分かっているのに、両方を追う手間が要ること。

### Q2. 何で絞るか — 実引数の静的な型

Issue の改善案どおり、**実引数の宣言の型**で絞る。`XmlOrderExporter` 型のフィールドを渡しているなら、`export` の中の
`exporter` の実行時の型は `XmlOrderExporter` の部分型に限られる（JLS 4.10・5.2。消去型の代入互換性は実行時にも保たれ、
型引数の食い違い（ヒープ汚染）は消去型には現れない）。だから、候補を上限の部分型に絞っても健全性は損なわれない。

具象型（`DATAFLOW_PARAM`）とは分けて、**上限**として持つ。上限は「この型の部分型のどれか」で、候補を絞る材料にはなるが
具象型ではない。具象型を求める読み手（`concreteTypeOf`。ファクトリの戻り値の畳み込み・リフレクションの受け手）や
値を求める読み手（条件の判定・リフレクションの名前）に上限を渡すと、`XmlOrderExporter` の部分型を `XmlOrderExporter`
そのものと取り違えて、部分型の上書きを落とす。そこで経路の環境の枠に新しい札 `Slot.BOUND` を足し、
`Slot.isType` / `Slot.isValue` のどちらにも当たらないようにした。既存の読み手は上限を「分からない」として扱い、
候補を絞る読み手（`CallResolver#resolveOnPath`）だけが `DataflowResolver#boundTypeOf` で読む。

### Q3. キャッシュの形式を変えなかったのはなぜか

Issue は「実引数の出所に型を持たせていないので、キャッシュ形式の変更（版を上げる）が必要」としていたが、宣言の型は
キャッシュに既にある。

| 値の出所 | 宣言の型の置き場 |
|---|---|
| フィールド（`F:型#名前` / `O:型#名前`） | V 行の `declType`（消去型の FQN。`FieldDeclFact`） |
| 引数（`A:n`） | 囲みメソッドのキーの引数の型（`MethodTable#paramTypeAt`。消去型） |
| 引数をそのまま返すメソッドの戻り値（`M:`） | 渡した実引数の出所へ辿る（`DataflowFacts#factoryKind` が `A`） |

読み手が V 行の宣言の型を控え（`CallGraph#fieldDeclType`。参照型のフィールドだけ、値を読む指定のときだけ）、引数の型は
メソッドキーから切り出せば足りる。書き手を変えないので版を上げず、利用者は全件解析をやり直さなくてよい。
差分更新との整合も考えなくてよい（読み手だけの変更は「事実」を変えない。`docs/resolution-selection-design.md` の 7 節）。

**持っていないもの**: ローカル変数の型と、引数をそのまま返す形でないメソッドの戻り値の型（D 行の `returnType` は
アノテーションの付いたメソッドにだけ書く）。`XmlOrderExporter x = repo.get(); svc.export(x)` の `x` は出所が
`M:Repo#get()` で、`get` の宣言の戻り値の型は無いので上限が付かない。これを足すには値グラフ（N 行）に式の静的な型を
持たせる書き手の変更（版上げ）が要る。Issue の形（フィールドと引数）で足りるので、いまは足さない。

### Q4. 上限を経路の環境に入れる場所と、読む場所

**入れる**: `DataflowResolver#bindArgs`（呼び出し先の引数の環境と、`new` のコンストラクタ実引数の環境を作る）。
実引数ごとに、具象型（`concreteSlotOf`）→ 値（`valueSlotOf`）→ 上限（`boundSlotOf`）の順に試す。上限は 2 つの材料の
狭いほう（もう片方の部分型のほう）。

- 経路の環境の上限 … 引数（`A:n`）・捕捉した引数（`E:n`）なら呼び出し元が渡した値の上限。コンストラクタで受け取る
  フィールドなら、コンストラクタ実引数の上限（`fieldSlotOf` が経路のコンストラクタ実引数の枠をそのまま返す）
- 宣言の型 … フィールドなら V 行、引数なら囲みメソッドのキー。囲みメソッドは走査側（`StreamingTreeWalker`）が
  `bindArgs` に渡す（`A:n` の葉はグラフ全体で 1 つなので、葉からは持ち主が分からない）

**読む**: `CallResolver#resolveOnPath`。値の追跡（`targetOf`）で具象型が決まらなかったときだけ、`boundTypeOf` で
レシーバの上限を読み、`narrowByBound` で絞る。レシーバ自身の宣言の型（呼び出しを書いたメソッドの引数・フィールドの型）は
足さない。それは呼び出しを修飾する型（JLS 13.1）として段 1 が既に候補の起点にしているので、絞れない。

### Q5. どう絞るか — 修飾する型と同じ扱い

上限の型を、修飾する型と同じ「候補の起点」にする（`resolveVirtual(calleeId, 上限)`。修飾する型ごとのメモと同じ表で
メモ化される）。実行時の型は修飾する型と上限の**両方**の部分型なので、段 1 の候補との交わりを採る。並びは段 1 のまま
（出力の行順を変えない）。

| 交わり | 結果 |
|---|---|
| 1 つ | `RESOLVED:DATAFLOW_DECLARED_TYPE` で確定 |
| 複数で、段 1 より少ない | `UNEXPANDED:CHA` のまま候補を減らす。上限の型を起点に段 5（DI）をもう一度引き、1 つに定まれば `SPRING_DI` |
| 段 1 と同じ数・空 | 絞らない（これまでどおり） |

上限の型がソース上に無い（jar の型・配列）なら絞らない。jar の型は部分型を漏れなく数えられない
（`CallResolver#usableQualifier` と同じ理由）。上限の部分型に本体を持つ実装が 1 つも無いとき（`NO_IMPL`）も絞らない。

絞るのは、段 1 の候補が複数のまま（`CHA`）か段 5 で絞ったものだけ。ライブラリ呼び出し規則・拡張・`new` した型（`LOCAL_NEW_MULTI`）・
リフレクションで集めた候補は、利用者が与えた条件や別の材料で決めたものなので覆さない（`resolveOnPath` がライブラリ呼び出し規則の結果を
経路ごとに覆さないのと同じ）。

### Q6. 段 5（DI）との組み合わせ

段 5 は注入点（Bean のコンストラクタの引数など）を唯一の Bean に絞る。利用者のコードがそのコンストラクタを直接呼び、
Bean でない具象型のフィールドを渡している経路では、段 5 の結論（Bean）は渡した値の型の部分型ではない。
`docs/spring-di-qa.md` の Q5「経路で具象型が分かればそちらを採る」と同じで、上限も経路の事実なので、こちらを採る
（`test/pruning` の SbDeclared）。矛盾しなければ（絞った Bean が上限の部分型なら）段 5 の結論のまま（SbDeclKeep）。

段 1 の候補が複数で段 5 が絞れなかった呼び出し（Bean が 2 つ）も、上限の部分型に Bean が 1 つなら経路で段 5 が効く
（SbDeclDi）。これは、受け手の引数を上限の型で宣言していたときに段 5 がすることと同じで、新しい判断ではない。

### Q7. ラベルを `DATAFLOW_PARAM` と分けたのはなぜか

`DATAFLOW_PARAM` / `DATAFLOW_FIELD` は「渡った値の具象型を追えた」結果で、`DATAFLOW_DECLARED_TYPE` は
「宣言の型で絞ったら 1 つだった」結果。根拠の強さが違う。前者は渡した `new` の型そのものだが、後者は上限の部分型が
ソースに 1 つしか無いことに依っており、部分型を足せば `CHA` に戻る。利用者が結果をどれだけ信用してよいかが変わるので
言い分ける（`DataflowResolver#labelFor` の考え方と同じ）。実行ログの件数（`exporter.dataflowHits`）にも別に数える。

### Q8. 上限を広げるキャスト・狭めるキャスト

`svc.use((DtI) dao)` のように上限を広げるキャストで渡しても、値の出所はフィールドのまま（書き手は参照型のキャストで
出所を変えない）なので、上限はフィールドの宣言の型（`DtB`）で絞れる（`test/pruning` の DtCast）。
`svc.use((DtB) iface)` のように狭めるキャストは、出所のフィールドの宣言の型（`DtI`）が上限になり、キャストの型は
使わない。健全側（広い側）で、キャストの型を使うには書き手の変更が要る（Q3 の「持っていないもの」と同じ）。

### Q9. ラムダが捕捉した引数

捕捉した引数（`E:n`）は、生成したメソッドの経路の上限（`ctx.captured()`）だけを使う。生成したメソッドの引数の宣言の型
（囲みメソッドのキー）は、ラムダの合成メソッドの段からは分からないので使わない。上限が付かなければこれまでどおり
CHA のままで、候補は落ちない。

---

## 実装で困ったこと

### Q10. 上限の枠の中身を何にするか

経路の環境の枠は `long` 1 つで、中身は文字列の置き場（`StringPool`）の番号か値の表の参照。上限の型の名前は V 行の
宣言の型（置き場に入れられる）と、メソッドキーから切り出した引数の型（置き場に無い。走査のたびに切り出す）の 2 通りで、
後者を置き場に足すには全メソッドの引数の型を構築時に intern する費用が要る。
そこで `TypeHierarchy` に名前順の型の番号（`indexOf` / `typeAt`。最初に要るときに作る）を持たせ、枠の中身はその番号に
した。ソース上の型だけが番号を持つので、「上限がソース上の型か」の判定も番号が取れるかで済む。番号は 1 回の実行の中で
しか通じないが、枠も経路を歩く間だけのものなので問題ない。

### Q11. 検査の題材で、具象型が決まってしまわないようにする

`new DtField(new DtB())` のように `new` で渡すと、コンストラクタ実引数の具象型（`DATAFLOW_FIELD` / `DATAFLOW_PARAM`）で
決まってしまい、上限の絞り込みを通らない。Issue の形（コンテナが入れる値）を再現するには、フィールドの値を「具象型の
決まらないファクトリの戻り値」（`private final DtB dao = lookup();` で `lookup()` は `null` を返す）にした。
`FieldFacts` の判定（(a)〜(f)）は通るので `fieldHead` は `M:lookup()` になるが、`fieldSlotOf` は `new` か引数しか
使わないので具象型は決まらず、宣言の型 `DtB` が上限になる。

### Q12. ほかのケースの部分型に左右されないようにする

`test/pruning` は 1 つのプロジェクトに全ケースを置くので、共通の `DaoB` に別のケースが部分型を足すと、上限 `DaoB` の
候補が増えて「1 つに定まる」期待が崩れる。新しいケースは `Dt*` / `Ds*` / `SbDt*` / `SbDd*` の型だけを使い、
`use` メソッドもケースごとのクラスに置く（呼び出し元の行が混ざらない）。

---

## 確認

### Q13. Issue #192 の検証結果の表は、今どうなるか（#192 の確認）

[Issue #192](https://github.com/instreest/java-call-hierarchy-exporter/issues/192) の表の各行を、Issue の形（`OrderExportService.export(from, to,
OrderExporter exporter)` の中の `exporter.export(orders)`。実装は `@Component` の `XmlOrderExporter` と `CsvOrderExporter`。
呼び出し元は `@Component` のコントローラ 4 つ）で使い捨てのプロジェクトを作って動かした（Spring の注釈は単純名で照合するので、同じ名前の
注釈型を置き、jar は使わない。`test/pruning` と同じ）。`OrderExportService.java:10` の `exporter.export(orders)` の行:

| 呼び出し元の書き方 | Issue 起票時 | 今（`477d851` 以降） |
|---|---|---|
| 具象型のフィールド（`XmlOrderExporter`）にコンストラクタ注入 | 両経路とも `UNEXPANDED:CHA`（2 候補） | **`RESOLVED:DATAFLOW_DECLARED_TYPE` で `XmlOrderExporter.export` の 1 件**（`CsvOrderExporter.export` の行は無い） |
| インターフェース型のフィールドにコンストラクタ注入し、引数に `@Qualifier("xml")` | 同上 | `UNEXPANDED:CHA`（2 候補）のまま |
| インターフェース型のフィールドに `@Autowired @Qualifier("xml")` でフィールド注入 | 同上 | `UNEXPANDED:CHA`（2 候補）のまま |
| 呼び出し元で `new XmlOrderExporter()` して渡す | `RESOLVED:DATAFLOW_PARAM` で確定 | `RESOLVED:DATAFLOW_PARAM`（変わらない） |

Issue の改善案（実引数の静的な型で絞る）は 1 行目で効いている。2・3 行目は Issue が「型階層とは別の Spring DI の仕様になるため対応しない」と
した `@Qualifier` の Bean 名の対応で、宣言の型が `OrderExporter`（インターフェース）なので上限が修飾する型と同じになり絞れない（Q5）。
これは Issue の結論どおりで、コードは変えていない。`test/pruning` の `DtField`（1 行目の形）・`DtWide`（インターフェース型のフィールドは
絞らない）が同じことを見ている。
