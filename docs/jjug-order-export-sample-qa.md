# JJUG 発表用サンプル（samples/jjug-order-export）— 作成時の QA 一覧

Issue なし（JJUG での発表の準備）。「呼び出し階層において、宣言型がインターフェースでインスタンスが呼び出し元により
変わる場合」の最小限の実務的なサンプルを、Spring Boot と Doma で作った。迷ったこと・確かめたことと、その結論を残す。

対応の要点:

- [`samples/jjug-order-export/`](../samples/jjug-order-export/README.md) に Maven プロジェクトを置く
  （Spring Boot 4.0 / doma-spring-boot-starter 3.0 / H2。`mvn spring-boot:run` でそのまま動く）
- 受注データを、画面 API（`/api/orders`）は JSON、取引先連携（`/partner/orders`）は XML で書き出す。
  共通処理 `OrderExportService.export(from, to, OrderExporter exporter)` の `exporter.export(orders)` の宣言型がインターフェースで、
  どちらの実装が動くかは呼び出し元が決める
- 書き方は Spring のデファクトに合わせた。2 つの実装は `@Component` の Bean で、コントローラが具象型のフィールドに
  コンストラクタ注入し、サービスへ引数で渡す（Q3）
- この書き方では、いまのツールは 29 行目を経路ごとに絞れない。両方の経路で `UNEXPANDED:CHA`（2 候補）になり、
  候補は 2 つとも行に残るがその先へは降りない。呼び出し元で `new` して渡す形なら経路ごとに `RESOLVED:DATAFLOW_PARAM` で確定する（Q4）
- 解析用の設定 `samples/jjug-order-export/jche.properties` を同梱し、出力（`output/`）は追跡しない

---

## 題材

### Q1. 題材を「出力形式（JSON / XML）を分けるエクスポーター」にしたのはなぜか

依頼者の案をそのまま採った。他の候補と比べても、発表の 1 枚に載せやすい条件がそろっていた。

| 候補 | 判断 |
|---|---|
| 出力形式（JSON / XML）を分けるエクスポーター | **採用**。「どの形式で渡すか」は呼び出し元（相手のシステム）が決めることなので、実装を呼び出し元が選ぶ形が自然に出る。実装ごとに先の処理が違う（XML は自前で要素を書く）ので、確定したときに先へ降りていけるかどうかの差も見せられる |
| 通知（メール / Slack / SMS） | 形は同じだが、送り先の設定・テンプレートなど本題と関係ないコードが増える |
| 決済手段（カード / 銀行振込） | 実務的だが、手段を選ぶのは利用者の入力（実行時の値）であることが多く、静的には決まらない形になりやすい |
| CSV / Excel ダウンロード | Excel の生成に Apache POI などの依存が増える |

「Spring ならコンテンツネゴシエーション（`HttpMessageConverter`）で JSON と XML を出し分けられるのでは」という指摘は想定できる。
そこで XML の側は「取引先と取り決めた要素名・属性名で書く」形にし、オブジェクトの汎用の直列化ではないことが分かるようにした。

### Q2. 入口をコントローラ 2 つにしたのはなぜか（バッチを入れなかった理由）

最小にするため。取引先連携は夜間バッチ（`@Scheduled`）で書くのも実務的だが、`curl` で結果を見せられず、
スケジューリングの設定も本題と関係ない。2 つの入口がどちらも `@GetMapping` なので、`methods.csv` では
どちらも `FRAMEWORK_ENTRY`（フレームワークが呼ぶ入口）に仕分けられる。

---

## ツールの解析結果を確かめたこと

### Q3. 実装を Bean にしてコンストラクタ注入する書き方にしたのはなぜか

発表で「デファクトの書き方でどう出るか」を見せるため（依頼者の指定）。Spring では実装に `@Component` を付け、
使う側は `final` フィールドにコンストラクタ注入するのが標準的な書き方である。

同じ型（`OrderExporter`）の Bean が 2 つあるので、使う側で 1 つを選ぶ書き方はいくつかある。
このサンプルは**具象型のフィールドに注入する**形にした。`@Qualifier` が要らず、最も短いため。
インターフェース型のフィールドに `@Qualifier` 付きで注入する形も解析して、結果が同じであることを確かめた（Q4 の表）。

最初の版は、呼び出し元が `new XmlOrderExporter(partnerCode)` のように実装を作って渡す形だった。
ツールが経路ごとに確定できる形だったが、Spring のアプリとしては例外的な書き方なので、発表の題材としてはこちらに改めた。
取引先コードは `new` する理由付けのために持たせていたので、Bean にしたときに外した（入口も `/partner/orders` に戻した）。

### Q4. デファクトの書き方で、29 行目はどう解析されるか（確かめた結果）

29 行目の `exporter.export(orders)` は**どちらの経路でも** `UNEXPANDED:CHA`
（`[UNEXPANDED:CHA] 2 candidates: parameter (passed in from outside the method)`）になり、
`JsonOrderExporter.export` と `XmlOrderExporter.export` の 2 行が出る。候補の先（`jsonMapper.writeValueAsString`、
`XmlOrderExporter.writeOrder`）へは降りない。

引数の出所を呼び出し元まで遡ると、渡しているのはフィールド（`F:`）で、そのフィールドに何が入るかは
DI コンテナが決める（ソースに `new` が無い）ため、データフローで型が決まらない。
段5の `SPRING_DI` はレシーバがフィールドか引数のときに Bean で候補を絞るが、ここでは両方の実装が Bean なので 1 つに定まらない。

書き方を変えた結果:

| 呼び出し元の書き方 | 29 行目の結果 |
|---|---|
| 具象型のフィールドにコンストラクタ注入（サンプル） | 両方の経路で `UNEXPANDED:CHA`（2 候補） |
| インターフェース型のフィールドにコンストラクタ注入し、引数に `@Qualifier` | 同上。コンストラクタ引数の注釈はキャッシュに持っていない（[spring-di-qa.md](spring-di-qa.md) の Q8） |
| インターフェース型のフィールドに `@Autowired @Qualifier` でフィールド注入 | 同上。`@Qualifier` はそのフィールドを受け手にした呼び出しにしか効かず、引数として渡った先には運ばれない |
| 呼び出し元で `new` して渡す | 経路ごとに `RESOLVED:DATAFLOW_PARAM` で確定し、その先まで降りる |

絞れなくても候補は 2 つとも行に残るので、呼び出しは落ちていない（多めに出る側に倒れている）。
発表ではこれを「安全側に倒した結果」として見せられる。

なお、渡した実引数の**静的な型**（ここでは `XmlOrderExporter` 型のフィールド）で候補を絞ることは、健全性を損なわずにできるはずである
（実行時の型は静的な型の下位型に限られるため）。いまは実引数の出所に型を持たせていないので、やるならツール側の改修になる。

### Q5. `exclude.packages` に `java.**,javax.**` を書いたのはなぜか

XML の実装は JDK の StAX（`javax.xml.stream.XMLStreamWriter`）で書いているので、XML の実装の先まで降りる解析
（`new` で渡す形）では、除外しないと `writeStartElement` などの行が 30 行近く並び、スライドに載らない。
同梱の `config/config.properties` の既定と同じ除外にした。Jackson（`tools.jackson.*`）の呼び出しは除外していない。

### Q6. XML の書き出しで、要素ごとの private メソッドを 1 つにまとめたのはなぜか

当初は `writeElement(xml, name, value)` を項目ごとに呼んでいたが、`new` で渡す形（経路ごとに確定して先へ降りる）で解析すると
同じ行が項目の数だけ並んで読みにくかった。`writeOrder` 1 つにして、確定した実装の先（`RESOLVED:STATIC_BOUND:PRIVATE`）へ
降りていることが 1 行で分かる程度に留めた。デファクトの書き方では候補の先へ降りないので、`writeOrder` は `methods.csv` で
`inHierarchy=0` として見える。

### Q7. `methods.csv` の `OrderExportService.export` の「絞れなかった」は、`call-hierarchy.csv` と食い違わないか

食い違いではない。`methods.csv` はメソッド 1 つにつき 1 行で経路を持たないので、
「このメソッドの中だけを見ると引数の実装は決まらない」（`[UNEXPANDED:CHA] parameter (passed in from outside the method)`）と出る。
デファクトの書き方では `call-hierarchy.csv` でも絞れていないので両者は一致する。`new` で渡す形にすると、
`call-hierarchy.csv` だけが経路ごとに確定し、`methods.csv` は同じ表示のまま残る。

---

## 置き場所と検査

### Q8. `test/` ではなく `samples/` に置いたのはなぜか

回帰テストの解析対象ではなく、発表と手元での確認に使うサンプルだから。`samples/demo` を `test/demo` へ移した
（[samples-demo-move-qa.md](samples-demo-move-qa.md)）のは、それが回帰テストの解析対象だったためで、
このサンプルは事情が違う。[source-header-qa.md](source-header-qa.md) の決まりどおり、
`samples/` のソースには著作権表示の行を付けていない。

### Q9. 回帰テストに入れなかったのはなぜか

本物の Spring Boot と Doma に依存するため。`test/demo` は依存 jar なしで解析できることが前提で、Spring の注釈は
スタブで済ませている（[spring-di-qa.md](spring-di-qa.md) の Q12）。このサンプルを CI で同じ結果にするには
数十 MB の依存 jar の取得が要り、取得しないと型が解決できず結果が変わる。
近い形（Bean の定義で候補を絞る・引数で渡った値を経路ごとに解決する・Doma の DAO）は `test/demo` の `fx.di`、`fx.app.Main#useParam`、`fx.dao.ItemDao` が既に検査している。

### Q10. 版を Spring Boot 4.0 / Doma 3 にしたのはなぜか

作成時点（2026 年 9 月）の最新の系列だから。
doma-spring-boot-starter 3.0.0 は Spring Boot 4.0 向けで、Doma 3 を使う。
Spring Boot 4 の Jackson は 3 系（パッケージが `tools.jackson.*`）なので、JSON の実装はそれに合わせた。
