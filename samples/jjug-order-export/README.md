# samples/jjug-order-export — 呼び出し元によって実装が変わるインターフェース呼び出しの最小例

JJUG での発表用のサンプルです。java-call-hierarchy-exporter で解析して、
**宣言型はインターフェースで、実際に動くインスタンスは呼び出し元によって変わる**呼び出しが
呼び出し階層にどう出るかを見せるために置いています。
Spring Boot（Web MVC）と Doma（DAO）で書いた、受注データを外部へ書き出す小さな Web アプリです。
書き方は Spring のデファクト（実装は `@Component` の Bean、使う側はコンストラクタ注入）に合わせています。

設計の判断（題材の選び方、書き方を変えたときの結果など）は
[docs/jjug-order-export-sample-qa.md](../../docs/jjug-order-export-sample-qa.md) にあります。

## 題材

同じ「期間内の受注を読み出して書き出す」処理を、3 つの入口が別の形式で使います。

| 入口 | 用途 | 形式 | 実装の選び方 |
|---|---|---|---|
| `GET /api/orders` | 画面（SPA）向けの API | JSON | コンストラクタ注入した `JsonOrderExporter` を渡す |
| `GET /partner/orders` | 取引先連携の標準の口 | XML | コンストラクタ注入した `XmlOrderExporter` を渡す |
| `GET /partners/{partnerCode}/orders` | 取引先別の連携ファイル（ダウンロード） | **取引先マスタの設定で決まる**（A001 は XML、B002 は JSON） | `OrderExporterFactory` が、マスタの `export_format` をキーに選ぶ |

```
OrderApiController.orders            … 注入された JsonOrderExporter を渡す
PartnerOrderController.orders        … 注入された XmlOrderExporter を渡す
  └─ OrderExportService.export(from, to, exporter)
       ├─ orderDao.selectByOrderDate(from, to)       … Doma の DAO。実装 OrderDaoImpl はコンパイル時に生成
       └─ exporter.export(orders)                    … 37 行目。宣言型は OrderExporter。動く実装は呼び出し元で決まる

PartnerOrderController.ordersForPartner
  └─ OrderExportService.exportForPartner(partnerCode, from, to)
       ├─ partnerDao.selectById(partnerCode)          … 取引先マスタ（Doma）
       ├─ orderExporterFactory.get(partner.exportFormat())  … マスタの値をキーにファクトリで選ぶ
       └─ exporter.export(orders)                    … 47 行目。どの実装かは DB の中身で決まる
```

### 見どころ 1: 呼び出し元が Bean を渡す（37 行目）

```java
// OrderExportService
public String export(LocalDate from, LocalDate to, OrderExporter exporter) {
    List<Order> orders = orderDao.selectByOrderDate(from, to);
    return exporter.export(orders);   // ← 37 行目。宣言型は OrderExporter（インターフェース）
}

// PartnerOrderController
private final XmlOrderExporter xmlOrderExporter;       // @Component の Bean をコンストラクタ注入
...
return orderExportService.export(from, to, xmlOrderExporter);
```

このメソッドだけを見ても、`exporter.export` で `JsonOrderExporter` と `XmlOrderExporter` のどちらが動くかは分かりません。
決めているのは、`exporter` を渡している呼び出し元です。

### 見どころ 2: ファクトリがマスタの値で選ぶ（47 行目）

「取引先ごとに連携ファイルの形式が違い、どの取引先がどの形式かは取引先マスタで管理する」という要件です。
形式を足すときは `OrderExporter` の実装を `@Component` で 1 つ足し、マスタの値を変えるだけで済むように、
ファクトリは Spring が渡す `OrderExporter` の Bean の一覧から `format()` をキーにした表を作ります。

```java
// OrderExporterFactory
public OrderExporterFactory(List<OrderExporter> exporters) {
    this.exportersByFormat = exporters.stream()
            .collect(Collectors.toUnmodifiableMap(OrderExporter::format, Function.identity()));
}

public OrderExporter get(String format) {
    OrderExporter exporter = exportersByFormat.get(format);
    ...
    return exporter;
}

// OrderExportService
public ExportFile exportForPartner(String partnerCode, LocalDate from, LocalDate to) {
    Partner partner = partnerDao.selectById(partnerCode).orElseThrow();
    OrderExporter exporter = orderExporterFactory.get(partner.exportFormat());   // キーは DB の値
    ...
    return new ExportFile(fileName, exporter.export(orders));                   // ← 47 行目
}
```

ここは**静的解析では原理的に決められません**。理由は 2 つ重なっています。

- キー（`partner.exportFormat()`）が**実行時に DB から読む値**で、ソースのどこにも書かれていない
- ファクトリの戻り値が **Map から取り出した要素**で、どの Bean が入っているかは Spring が実行時に渡す一覧で決まる

## ファイル

| ファイル | 内容 |
|---|---|
| `src/main/java/com/example/orderexport/web/OrderApiController.java` | 入口 1。注入された JSON の実装を渡す |
| `src/main/java/com/example/orderexport/web/PartnerOrderController.java` | 入口 2（注入された XML の実装を渡す）と入口 3（取引先別の連携ファイル） |
| `src/main/java/com/example/orderexport/service/OrderExportService.java` | 共通処理。`export`（渡された実装で書き出す）と `exportForPartner`（マスタとファクトリで実装を選ぶ） |
| `src/main/java/com/example/orderexport/service/ExportFile.java` | 連携ファイル（ファイル名と中身）の record |
| `src/main/java/com/example/orderexport/exporter/OrderExporter.java` | 書き出し形式のインターフェース（`format()` と `export()`） |
| `src/main/java/com/example/orderexport/exporter/JsonOrderExporter.java` | JSON（`@Component`。Spring Boot の `JsonMapper` を注入） |
| `src/main/java/com/example/orderexport/exporter/XmlOrderExporter.java` | XML（`@Component`。StAX） |
| `src/main/java/com/example/orderexport/exporter/OrderExporterFactory.java` | 連携形式の名前から実装を選ぶファクトリ（`@Component`） |
| `src/main/java/com/example/orderexport/dao/OrderDao.java` / `PartnerDao.java` | Doma の DAO。SQL は `src/main/resources/META-INF/com/example/orderexport/dao/` の下 |
| `src/main/java/com/example/orderexport/domain/Order.java` / `Partner.java` | 受注・取引先マスタのエンティティ（record） |
| `src/main/resources/schema.sql` / `data.sql` | H2（インメモリ）の表と初期データ |
| `jche.properties` | java-call-hierarchy-exporter でこのサンプルを解析するための設定 |

版は Spring Boot 4.0 / Doma 3（doma-spring-boot-starter 3.0）/ Java 17 以上です。

## アプリとして動かす

```bash
cd samples/jjug-order-export
mvn spring-boot:run
```

```bash
curl "http://localhost:8080/api/orders?from=2026-09-01&to=2026-09-05"
# [{"id":1,"customerName":"Tanaka Shoji","orderDate":"2026-09-01","amount":12000},{"id":2,...}]

curl "http://localhost:8080/partner/orders?from=2026-09-01&to=2026-09-05"
# <?xml version="1.0" encoding="UTF-8"?><orders><order id="1" customer="Tanaka Shoji" .../>...</orders>

curl -i "http://localhost:8080/partners/A001/orders?from=2026-09-01&to=2026-09-05"
# Content-Disposition: attachment; filename="orders-A001.xml"   （取引先マスタで A001 は xml）

curl -i "http://localhost:8080/partners/B002/orders?from=2026-09-01&to=2026-09-05"
# Content-Disposition: attachment; filename="orders-B002.json"  （取引先マスタで B002 は json）
```

## 解析する

ツールは `pom.xml` を読んで、依存 jar をローカルリポジトリ（`~/.m2/repository`）から集めます（Maven は実行しません）。
先に一度ビルドして、依存 jar を取得しておいてください。

```bash
cd samples/jjug-order-export
mvn package          # 依存 jar の取得（mvn dependency:go-offline でもよい）
cd ../..
./java-call-hierarchy-exporter.sh samples/jjug-order-export/jche.properties
```

結果は `samples/jjug-order-export/output/<解析開始日時>_jjug-order-export/` に出ます。
依存 jar を取得せずに解析すると、`warnings.txt` ができて依存 jar がローカルリポジトリに無いことと対処が書かれます。
設定では JDK と Spring の中への呼び出し（`java.**`、`javax.**`、`org.springframework.**`）を行にしないようにしています。

## 結果（`call-hierarchy.csv`）

`caller` 列を省き、`call-hierarchy` 列は起点（`root`）の次から並べています（全 18 行）。

| root | level | callee | resolved-by | call-hierarchy（注記は末尾） |
|---|---|---|---|---|
| OrderApiController.orders | 1 | OrderExportService.export | RESOLVED:NO_OVERRIDE | OrderExportService.export |
| OrderApiController.orders | 2 | OrderDao.selectByOrderDate | UNEXPANDED:GENERATED_IMPL:Doma | … OrderDao.selectByOrderDate, `[UNEXPANDED:GENERATED] implementation is generated at compile time (Doma): …` |
| OrderApiController.orders | 2 | **JsonOrderExporter.export** | **UNEXPANDED:CHA** | … JsonOrderExporter.export, `[UNEXPANDED:CHA] 2 candidates: parameter (passed in from outside the method)` |
| OrderApiController.orders | 2 | **XmlOrderExporter.export** | **UNEXPANDED:CHA** | （同上） |
| PartnerOrderController.orders | 1 | OrderExportService.export | RESOLVED:NO_OVERRIDE | OrderExportService.export |
| PartnerOrderController.orders | 2 | OrderDao.selectByOrderDate | UNEXPANDED:GENERATED_IMPL:Doma | （上と同じ） |
| PartnerOrderController.orders | 2 | **JsonOrderExporter.export** | **UNEXPANDED:CHA** | （上と同じ） |
| PartnerOrderController.orders | 2 | **XmlOrderExporter.export** | **UNEXPANDED:CHA** | （上と同じ） |
| PartnerOrderController.ordersForPartner | 1 | OrderExportService.exportForPartner | RESOLVED:NO_OVERRIDE | OrderExportService.exportForPartner |
| PartnerOrderController.ordersForPartner | 2 | PartnerDao.selectById | UNEXPANDED:GENERATED_IMPL:Doma | … PartnerDao.selectById, `[UNEXPANDED:GENERATED] …` |
| PartnerOrderController.ordersForPartner | 2 | OrderExporterFactory.get | RESOLVED:NO_OVERRIDE | … OrderExporterFactory.get |
| PartnerOrderController.ordersForPartner | 2 | Partner.exportFormat（2 行） | RESOLVED:STATIC_BOUND:FINAL_CLASS | … Partner.exportFormat, `[EXTERNAL] no source to follow` |
| PartnerOrderController.ordersForPartner | 2 | OrderDao.selectByOrderDate | UNEXPANDED:GENERATED_IMPL:Doma | （上と同じ） |
| PartnerOrderController.ordersForPartner | 2 | **JsonOrderExporter.export** | **UNEXPANDED:CHA** | … JsonOrderExporter.export, `[UNEXPANDED:CHA] 2 candidates: local variable` |
| PartnerOrderController.ordersForPartner | 2 | **XmlOrderExporter.export** | **UNEXPANDED:CHA** | （同上） |
| PartnerOrderController.ordersForPartner | 1 | ExportFile.fileName / ExportFile.content（2 行） | RESOLVED:STATIC_BOUND:FINAL_CLASS | …, `[EXTERNAL] no source to follow` |

実際の行（抜粋）:

```csv
caller,callee,resolved-by,level,root,call-hierarchy
at com.example.orderexport.service.OrderExportService.export(OrderExportService.java:37),JsonOrderExporter.export,UNEXPANDED:CHA,2,PartnerOrderController.orders,OrderExportService.export,JsonOrderExporter.export,[UNEXPANDED:CHA] 2 candidates: parameter (passed in from outside the method)
at com.example.orderexport.service.OrderExportService.export(OrderExportService.java:37),XmlOrderExporter.export,UNEXPANDED:CHA,2,PartnerOrderController.orders,OrderExportService.export,XmlOrderExporter.export,[UNEXPANDED:CHA] 2 candidates: parameter (passed in from outside the method)
at com.example.orderexport.service.OrderExportService.exportForPartner(OrderExportService.java:47),JsonOrderExporter.export,UNEXPANDED:CHA,2,PartnerOrderController.ordersForPartner,OrderExportService.exportForPartner,JsonOrderExporter.export,[UNEXPANDED:CHA] 2 candidates: local variable
at com.example.orderexport.service.OrderExportService.exportForPartner(OrderExportService.java:47),XmlOrderExporter.export,UNEXPANDED:CHA,2,PartnerOrderController.ordersForPartner,OrderExportService.exportForPartner,XmlOrderExporter.export,[UNEXPANDED:CHA] 2 candidates: local variable
```

読みどころ:

- **37 行目も 47 行目も、実装を 1 つに絞れていません。** どちらも `JsonOrderExporter.export` と `XmlOrderExporter.export` の 2 行が出ます
- ただし**絞れない理由の質が違います**
  - 37 行目（Bean を渡す）は、渡している値がコントローラのフィールドで、そこに何が入るかは DI コンテナが決める（ソースに `new` が無い）ため。
    コントローラが具象型（`XmlOrderExporter`）で受けているので、人が読めば実装は 1 つに決まります。
    静的な型から絞る改修をすれば、ツールでも決められる種類の「絞れない」です
  - 47 行目（ファクトリ）は、キーが DB の値で、戻り値が Map の要素なので、**ソースをいくら読んでも決まりません**。
    人が読んでも「取引先マスタ次第」としか言えない、原理的な「絞れない」です
- **絞れなくても、候補は 2 つとも行として残ります。** 呼び出しを静かに落とさず、行が多めに出る側に倒れています。
  `resolved-by` の `UNEXPANDED:` と注記の `[UNEXPANDED:CHA]` が「ここは辿り切れていない」の目印です
- **候補の先へは降りません。** 候補数ぶん枝分かれして爆発するのを避けるためで、JSON 側の `jsonMapper.writeValueAsString`、
  XML 側の `writeOrder` は `call-hierarchy.csv` に出てきません。`methods.csv` では `XmlOrderExporter.writeOrder` が
  `inHierarchy=0` になっており、ここから「階層に出てこなかったメソッド」を拾えます
- 影響調査の向き: Excel で `callee` 列を `XmlOrderExporter.export` に絞ると、`root` は 3 つとも出ます。
  実際には `/api/orders` からは XML の実装は動きませんが、静的解析で言い切れないので挙げています（多めに出る側の誤り）。
  取引先別の連携ファイルは、取引先マスタに `xml` の取引先がいる限り本当に影響するので、こちらは正しく挙がっています
- 47 行目の注記は `local variable` です。受け手が `exporter` という変数だからで、変数に入れずに
  `orderExporterFactory.get(partner.exportFormat()).export(orders)` と書くと `return value (factory method etc.)` になります
- `OrderDao` と `PartnerDao` は Doma の DAO で、実装クラスはアノテーション処理がコンパイル時に作るのでソースにありません。
  「実装が無い」のではなく「コンパイル時に生成される」ことが注記（`[UNEXPANDED:GENERATED]`）で分かります

### 契約表のひな形（`contracts-suggested.txt`）

出力フォルダの `contracts-suggested.txt` には、絞れなかった呼び出しを 1 件に決めるための契約表のひな形が出ます。
このサンプルでは `OrderExporter#export => ??` の 1 行だけで、注記に「ファクトリに渡したキーが決まらなかったので、
この行はその型のそのメソッドの呼び出しを全部 1 つの実装に決めてしまう。呼び出し箇所で実装が違うなら貼らない」旨が書かれます。
このサンプルは経路で実装が変わるので、貼ってはいけない行です。

ファクトリに渡すキーが**ソースに書いてある値**なら事情が変わります。
`orderExporterFactory.get("xml")` と定数で渡す形にすると、ひな形にファクトリのキー単位の行
（`OrderExporterFactory#get("xml") => ??`）が出て、`??` を `XmlOrderExporter` にして契約表（`contracts.files`）に貼れば、
47 行目が `RESOLVED:CONTRACT` で `XmlOrderExporter.export` に決まり、その先（`writeOrder`）まで降ります。
キーが DB から来る限り、この手は使えません。

## 書き方を変えるとどうなるか（発表の補足）

同じ題材を書き方だけ変えて解析した結果です。

| 書き方 | 実装を選ぶ呼び出しの結果 |
|---|---|
| 具象型のフィールドにコンストラクタ注入して渡す（**入口 1・2**） | 両方の経路で `UNEXPANDED:CHA`（2 候補） |
| インターフェース型のフィールドにコンストラクタ注入し、引数に `@Qualifier("xmlOrderExporter")` | 同上 |
| インターフェース型のフィールドに `@Autowired @Qualifier("xmlOrderExporter")` でフィールド注入 | 同上 |
| Bean にせず、呼び出し元で `new XmlOrderExporter()` して渡す | 経路ごとに 1 件に確定（`RESOLVED:DATAFLOW_PARAM`）。その先（`writeOrder`）まで降りる |
| ファクトリに取引先マスタ（DB）の値をキーとして渡す（**入口 3**） | `UNEXPANDED:CHA`（2 候補）。契約表でも絞れない |
| ファクトリに定数のキー（`get("xml")`）を渡す | そのままでは `UNEXPANDED:CHA`。ひな形のキー単位の行を契約表に貼れば `RESOLVED:CONTRACT` |

`@Qualifier` は、その注入先のフィールドを受け手にした呼び出しを絞るのには使われますが（`SPRING_DI_QUALIFIER`）、
引数として別のメソッドへ渡った先までは運ばれません。
呼び出し元で `new` した値は、引数を経路上で遡れば型が決まるので、経路ごとに確定します。
