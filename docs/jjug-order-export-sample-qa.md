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
- この書き方では、いまのツールは 37 行目を経路ごとに絞れない。両方の経路で `UNEXPANDED:CHA`（2 候補）になり、
  候補は 2 つとも行に残るがその先へは降りない。呼び出し元で `new` して渡す形なら経路ごとに `RESOLVED:DATAFLOW_PARAM` で確定する（Q4）
- 3 つめの入口として、**ファクトリで実装を選ぶために型を追えない**呼び出しを足した。取引先ごとの連携ファイルの形式を
  取引先マスタ（Doma で読む `partners.export_format`）で管理し、`OrderExporterFactory` がその値をキーに、
  Spring が渡す `OrderExporter` の Bean の一覧から作った Map で実装を選ぶ（Q11〜Q14）。47 行目の `exporter.export(orders)` は
  `UNEXPANDED:CHA`（2 候補）で、キーが DB の値なので契約表でも絞れない
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

### Q2. 入口をコントローラの `@GetMapping` にしたのはなぜか（バッチを入れなかった理由）

最小にするため。取引先連携は夜間バッチ（`@Scheduled`）で書くのも実務的だが、`curl` で結果を見せられず、
スケジューリングの設定も本題と関係ない。入口がどれも `@GetMapping` なので、`methods.csv` では
どれも `FRAMEWORK_ENTRY`（フレームワークが呼ぶ入口）に仕分けられる。
入口 3（取引先別の連携ファイル）は、入口 2 と同じ取引先連携なので同じ `PartnerOrderController` にメソッドとして足した。

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

### Q4. デファクトの書き方で、37 行目はどう解析されるか（確かめた結果）

37 行目（`OrderExportService.export` の中）の `exporter.export(orders)` は**どちらの経路でも** `UNEXPANDED:CHA`
（`[UNEXPANDED:CHA] 2 candidates: parameter (passed in from outside the method)`）になり、
`JsonOrderExporter.export` と `XmlOrderExporter.export` の 2 行が出る。候補の先（`jsonMapper.writeValueAsString`、
`XmlOrderExporter.writeOrder`）へは降りない。

引数の出所を呼び出し元まで遡ると、渡しているのはフィールド（`F:`）で、そのフィールドに何が入るかは
DI コンテナが決める（ソースに `new` が無い）ため、データフローで型が決まらない。
段5の `SPRING_DI` はレシーバがフィールドか引数のときに Bean で候補を絞るが、ここでは両方の実装が Bean なので 1 つに定まらない。

書き方を変えた結果:

| 呼び出し元の書き方 | 37 行目の結果 |
|---|---|
| 具象型のフィールドにコンストラクタ注入（サンプル） | 両方の経路で `UNEXPANDED:CHA`（2 候補） |
| インターフェース型のフィールドにコンストラクタ注入し、引数に `@Qualifier` | 同上。コンストラクタ引数の注釈はキャッシュに持っていない（[spring-di-qa.md](spring-di-qa.md) の Q8） |
| インターフェース型のフィールドに `@Autowired @Qualifier` でフィールド注入 | 同上。`@Qualifier` はそのフィールドを受け手にした呼び出しにしか効かず、引数として渡った先には運ばれない |
| 呼び出し元で `new` して渡す | 経路ごとに `RESOLVED:DATAFLOW_PARAM` で確定し、その先まで降りる |

絞れなくても候補は 2 つとも行に残るので、呼び出しは落ちていない（多めに出る側に倒れている）。
発表ではこれを「安全側に倒した結果」として見せられる。

なお、渡した実引数の**静的な型**（ここでは `XmlOrderExporter` 型のフィールド）で候補を絞ることは、健全性を損なわずにできるはずである
（実行時の型は静的な型の下位型に限られるため）。いまは実引数の出所に型を持たせていないので、やるならツール側の改修になる。

### Q5. `exclude.packages` に `java.**,javax.**,org.springframework.**` を書いたのはなぜか

XML の実装は JDK の StAX（`javax.xml.stream.XMLStreamWriter`）で書いているので、XML の実装の先まで降りる解析
（`new` で渡す形）では、除外しないと `writeStartElement` などの行が 30 行近く並び、スライドに載らない。
まず同梱の `config/config.properties` の既定と同じ `java.**,javax.**` を除外した。
入口 3 を足したときに、コントローラでダウンロードの応答を組み立てる `ResponseEntity.ok` / `header` / `body` の
`[EXTERNAL]` の行が 3 行増えたので、`org.springframework.**` も足した。本題（どの実装が動くか）に関係しないフレームワークの中の行である。
Jackson（`tools.jackson.*`）の呼び出しは除外していない。

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

---

## ファクトリで型を追えない呼び出し（入口 3）

### Q11. 「ファクトリで型を追えない」要件を、取引先マスタで形式を選ぶ形にしたのはなぜか

ツールが**追えない**ことが、ツールの弱さではなく**原理的な限界**だと分かる形にしたかった。
[static-analysis-limits.md](static-analysis-limits.md) の整理では、ファクトリの戻り値の型は「キーがソースに書いてあれば追える」
「キーが外部（設定ファイル・DB・リクエスト）から来た時点で終わり」「コレクションの要素は追わない」。候補を比べた。

| 候補 | 判断 |
|---|---|
| 取引先マスタ（DB）の連携形式をキーに、Bean の一覧から作った Map で選ぶ | **採用**。「取引先ごとに連携形式が違い、マスタで管理する」は業務システムでよくある要件で、キーが DB の値・戻り値が Map の要素、と追えない理由が 2 つ重なる。人が読んでも「マスタ次第」としか言えないので、ツールが絞れないことに納得がいく |
| リクエストパラメータ（`?format=xml`）をキーにする | 形は同じで最小だが、「利用者がその場で選ぶ」のは実装を選ぶ話としては素朴すぎ、ファクトリを置く理由が弱い |
| `Map<String, OrderExporter>` を注入して Bean 名（`format + "OrderExporter"`）で引く | Spring でよく見る書き方だが、Bean 名とキーの文字列を連結で結び付けるのは脆い書き方で、題材として勧めにくい |
| 設定ファイルのクラス名から `Class.forName(...).newInstance()` | 古いフレームワークに多いが、Spring のアプリでは書かない。ツールはリテラルのクラス名なら追えるので、追えない例にするには別の細工が要る |

形式を足すときに「`OrderExporter` の実装を `@Component` で 1 つ足し、マスタの値を変えるだけ」で済む（ファクトリに `if` / `switch` を
書き足さない）ように、`OrderExporter` に `format()` を足し、ファクトリのコンストラクタで `format()` をキーにした Map を作る。
Spring で複数の実装から 1 つを選ぶときの定番の書き方である。

### Q12. ファクトリを経由した呼び出しは、どう解析されるか（確かめた結果）

47 行目（`OrderExportService.exportForPartner` の中）の `exporter.export(orders)` は `UNEXPANDED:CHA`
（`[UNEXPANDED:CHA] 2 candidates: local variable`）で、`JsonOrderExporter.export` と `XmlOrderExporter.export` の 2 行が出る。
ファクトリの戻り値の出所は `exportersByFormat.get(format)`（Map の要素）で、データフローはここで途切れる。
`OrderExporterFactory.get` 自体は `RESOLVED:NO_OVERRIDE` の行として出る（`Map.get` は `java.**` の除外で行にならない）。

37 行目（Bean を渡す）と結果は同じだが、絞れない理由の質が違う。37 行目は渡した値の静的な型（`XmlOrderExporter`）から
絞る改修をすれば決められる（Q4 の最後）のに対し、47 行目はソースに答えが無い。README の「読みどころ」でこの違いを説明した。

注記が `return value (factory method etc.)` ではなく `local variable` なのは、受け手が `exporter` という変数だからである。
変数に入れずに `orderExporterFactory.get(partner.exportFormat()).export(orders)` と書くと `return value (factory method etc.)` になる
（試して確かめた）。サンプルは読みやすさを優先して変数に入れる形にした。

### Q13. 契約表（`contracts-suggested.txt`）で絞れないか

絞れない。ひな形は `OrderExporter#export => ??` の型単位の 1 行だけで（37 行目と 47 行目の 2 か所をまとめたもの）、
「ファクトリに渡したキーが決まらなかったので、その型のそのメソッドの呼び出しを全部 1 つの実装に決めてしまう」旨の注記が付く。
経路で実装が変わるサンプルなので貼ってはいけない行である。

対比として、キーを定数にした形（`orderExporterFactory.get("xml")`）も解析した。ひな形にファクトリのキー単位の行
（`OrderExporterFactory#get("xml") => ??`）が出て、`XmlOrderExporter` を書いて `contracts.files` に指定すると、
47 行目が `RESOLVED:CONTRACT` で `XmlOrderExporter.export` に決まり、`writeOrder` まで降りた。
「キーがソースにあれば 1 行で絞れる、DB にあれば絞れない」という対比は README の比較表に載せた。

### Q14. `OrderExporter` に `format()` を足したことで、出力は増えたか

`call-hierarchy.csv` には増えていない。`format()` を呼んでいるのはファクトリのコンストラクタ（`OrderExporter::format` のメソッド参照）だけで、
コンストラクタは Spring が呼ぶので起点（コントローラ）から辿り着かない。`methods.csv` では `JsonOrderExporter.format()` /
`XmlOrderExporter.format()` が `reachable=0` で、`absentCause` は `[NOT_REACHED] no caller row was emitted` になる。

