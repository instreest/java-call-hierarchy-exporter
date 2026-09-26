# JJUG 発表用サンプル（samples/jjug-order-export）— 作成時の QA 一覧

Issue なし（JJUG での発表の準備）。「呼び出し階層において、宣言型がインターフェースでインスタンスが呼び出し元により
変わる場合」の最小限の実務的なサンプルを、Spring Boot と Doma で作った。迷ったこと・確かめたことと、その結論を残す。

対応の要点:

- [`samples/jjug-order-export/`](../samples/jjug-order-export/README.md) に Maven プロジェクトを置く
  （Spring Boot 4.0 / doma-spring-boot-starter 3.0 / H2。`mvn spring-boot:run` でそのまま動く）
- 受注データを、画面 API（`/api/orders`）は JSON、取引先連携（`/partners/{partnerCode}/orders`）は XML で書き出す。
  共通処理 `OrderExportService.export(from, to, OrderExporter exporter)` の `exporter.export(orders)` が、
  経路ごとに `JsonOrderExporter.export` / `XmlOrderExporter.export` へ `RESOLVED:DATAFLOW_PARAM` で確定する
- 呼び出し元は実装を `new` で作って渡す。Spring の Bean を注入して渡す形は、いまのツールでは経路ごとに絞れない（Q3）
- 解析用の設定 `samples/jjug-order-export/jche.properties` を同梱し、出力（`output/`）は追跡しない

---

## 題材

### Q1. 題材を「出力形式（JSON / XML）を分けるエクスポーター」にしたのはなぜか

依頼者の案をそのまま採った。他の候補と比べても、発表の 1 枚に載せやすい条件がそろっていた。

| 候補 | 判断 |
|---|---|
| 出力形式（JSON / XML）を分けるエクスポーター | **採用**。「どの形式で渡すか」は呼び出し元（相手のシステム）が決めることなので、実装を呼び出し元が選ぶ形が自然に出る。実装ごとに先の処理が違う（XML は自前で要素を書く）ので、確定した先へ降りていける効果も見せられる |
| 通知（メール / Slack / SMS） | 形は同じだが、送り先の設定・テンプレートなど本題と関係ないコードが増える |
| 決済手段（カード / 銀行振込） | 実務的だが、手段を選ぶのは利用者の入力（実行時の値）であることが多く、静的には決まらない形になりやすい |
| CSV / Excel ダウンロード | Excel の生成に Apache POI などの依存が増える |

「Spring ならコンテンツネゴシエーション（`HttpMessageConverter`）で JSON と XML を出し分けられるのでは」という指摘は想定できる。
そこで XML の側は「取引先と取り決めた要素名・属性名で、取引先コードを入れて書く」形にし、
オブジェクトの汎用の直列化ではない（呼び出し元が自分の条件で実装を作る理由がある）ことが分かるようにした。

### Q2. 入口をコントローラ 2 つにしたのはなぜか（バッチを入れなかった理由）

最小にするため。取引先連携は夜間バッチ（`@Scheduled`）で書くのも実務的だが、`curl` で結果を見せられず、
スケジューリングの設定も本題と関係ない。2 つの入口がどちらも `@GetMapping` なので、`methods.csv` では
どちらも `FRAMEWORK_ENTRY`（フレームワークが呼ぶ入口）に仕分けられる。

---

## ツールの解析結果を確かめたこと

### Q3. 呼び出し元が実装を `new` で渡す形にしたのはなぜか（Bean の注入ではない理由）

最初は Spring らしく、2 つの実装に `@Component` を付け、コントローラが具象型のフィールドでコンストラクタ注入して
サービスへ渡す形で書いた。解析すると、29 行目の `exporter.export(orders)` は**どちらの経路でも**
`UNEXPANDED:CHA`（`[UNEXPANDED:CHA] 2 candidates: parameter (passed in from outside the method)`）になり、
経路ごとには絞れなかった。

引数の出所を呼び出し元まで遡ると、渡しているのはフィールド（`F:`）で、そのフィールドに何が入るかは
DI コンテナが決める（ソースに `new` が無い）ため、データフローで型が決まらない。
段5の `SPRING_DI` はレシーバがフィールドか引数のときに Bean で候補を絞るが、ここでは両方の実装が Bean なので 1 つに定まらない。
`@Autowired @Qualifier("xmlOrderExporter")` を付けたフィールドを渡す形も試したが、
`@Qualifier` はそのフィールドをレシーバにした呼び出しにしか効かず、引数として渡った先には運ばれないので、結果は同じだった。

発表で見せたいのは「経路ごとに確定する」ことなので、ツールが確定できる形（呼び出し元で `new` した値を引数で渡す）にした。
XML の実装に取引先コードを持たせ、「呼び出しごとに条件を持った実装を作る」ことに理由があるようにしてある。
Bean を注入して渡す形の結果は、サンプルの README に「書き方を変えるとどうなるか」として残した（候補が 2 つとも行に残り、
呼び出しを落とさない側に倒れていることの説明として使える）。

なお、渡した実引数の**静的な型**（ここでは `XmlOrderExporter` 型のフィールド）で候補を絞ることは、健全性を損なわずにできるはずである
（実行時の型は静的な型の下位型に限られるため）。いまは実引数の出所に型を持たせていないので、やるならツール側の改修になる。

### Q4. `exclude.packages` に `java.**,javax.**` を書いたのはなぜか

XML の実装は JDK の StAX（`javax.xml.stream.XMLStreamWriter`）で書いているので、除外しないと
`writeStartElement` などの行が 30 行近く並び、スライドに載らない。同梱の `config/config.properties` の既定と同じ除外にした。
Jackson（`tools.jackson.*`）の呼び出しは JSON の経路で 2 行だけなので、除外せずに残した（`[EXTERNAL]` の例にもなる）。

### Q5. XML の書き出しで、要素ごとの private メソッドを 1 つにまとめたのはなぜか

当初は `writeElement(xml, name, value)` を項目ごとに呼んでいたが、同じ行が項目の数だけ並んで読みにくかった。
`writeOrder` 1 つにして、確定した実装の先（`RESOLVED:STATIC_BOUND:PRIVATE`）へ降りていることが 1 行で分かる程度に留めた。
record のアクセサ（`Order.id` など）は暗黙に宣言されソースに本体が無いので、`[EXTERNAL] no source to follow` の行になる。

### Q6. `methods.csv` では `OrderExportService.export` が「絞れなかった」と出るが、食い違いではないか

食い違いではない。`methods.csv` はメソッド 1 つにつき 1 行で経路を持たないので、
「このメソッドの中だけを見ると引数の実装は決まらない」（`[UNEXPANDED:CHA] parameter (passed in from outside the method)`）と出る。
経路ごとの答えは `call-hierarchy.csv` にある。README の「結果」に注意書きを入れた。

---

## 置き場所と検査

### Q7. `test/` ではなく `samples/` に置いたのはなぜか

回帰テストの解析対象ではなく、発表と手元での確認に使うサンプルだから。`samples/demo` を `test/demo` へ移した
（[samples-demo-move-qa.md](samples-demo-move-qa.md)）のは、それが回帰テストの解析対象だったためで、
このサンプルは事情が違う。[source-header-qa.md](source-header-qa.md) の決まりどおり、
`samples/` のソースには著作権表示の行を付けていない。

### Q8. 回帰テストに入れなかったのはなぜか

本物の Spring Boot と Doma に依存するため。`test/demo` は依存 jar なしで解析できることが前提で、Spring の注釈は
スタブで済ませている（[spring-di-qa.md](spring-di-qa.md) の Q12）。このサンプルを CI で同じ結果にするには
数十 MB の依存 jar の取得が要り、取得しないと型が解決できず結果が変わる。
同じ形（引数で渡った値を経路ごとに解決する・Doma の DAO）は `test/demo` の `fx.app.Main#useParam` と `fx.dao.ItemDao` が既に検査している。

### Q9. 版を Spring Boot 4.0 / Doma 3 にしたのはなぜか

作成時点（2026 年 9 月）の最新の系列だから。
doma-spring-boot-starter 3.0.0 は Spring Boot 4.0 向けで、Doma 3 を使う。
Spring Boot 4 の Jackson は 3 系（パッケージが `tools.jackson.*`）なので、JSON の実装はそれに合わせた。
