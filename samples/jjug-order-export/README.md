# samples/jjug-order-export — 呼び出し元によって実装が変わるインターフェース呼び出しの最小例

JJUG での発表用のサンプルです。java-call-hierarchy-exporter で解析して、
**宣言型はインターフェースで、実際に動くインスタンスは呼び出し元によって変わる**呼び出しが
呼び出し階層にどう出るかを見せるために置いています。
Spring Boot（Web MVC）と Doma（DAO）で書いた、受注データを外部へ書き出す小さな Web アプリです。

設計の判断（題材の選び方、`new` で渡す形にした理由など）は
[docs/jjug-order-export-sample-qa.md](../../docs/jjug-order-export-sample-qa.md) にあります。

## 題材

同じ「期間内の受注を読み出して書き出す」処理を、2 つの入口が別の形式で使います。

| 入口 | 用途 | 形式 | 渡す実装 |
|---|---|---|---|
| `GET /api/orders` | 画面（SPA）向けの API | JSON | `new JsonOrderExporter()` |
| `GET /partners/{partnerCode}/orders` | 取引先システムとの連携 | XML（取り決めた要素名・属性名。取引先コード入り） | `new XmlOrderExporter(partnerCode)` |

```
OrderApiController.orders      … new JsonOrderExporter() を渡す
PartnerOrderController.orders  … new XmlOrderExporter(partnerCode) を渡す
  └─ OrderExportService.export(from, to, exporter)
       ├─ orderDao.selectByOrderDate(from, to)  … Doma の DAO。実装 OrderDaoImpl はコンパイル時に生成
       └─ exporter.export(orders)               … 宣言型は OrderExporter。動く実装は呼び出し元で決まる
```

見どころは [`OrderExportService.java`](src/main/java/com/example/orderexport/service/OrderExportService.java) の 29 行目です。

```java
public String export(LocalDate from, LocalDate to, OrderExporter exporter) {
    List<Order> orders = orderDao.selectByOrderDate(from, to);
    return exporter.export(orders);   // ← 29 行目。宣言型は OrderExporter（インターフェース）
}
```

このメソッドだけを見ても、`exporter.export` で `JsonOrderExporter` と `XmlOrderExporter` のどちらが動くかは分かりません。
決めているのは、`exporter` を作って渡している呼び出し元です。

## ファイル

| ファイル | 内容 |
|---|---|
| `src/main/java/com/example/orderexport/web/OrderApiController.java` | 入口 1。JSON の実装を作って渡す |
| `src/main/java/com/example/orderexport/web/PartnerOrderController.java` | 入口 2。取引先コードを持たせた XML の実装を作って渡す |
| `src/main/java/com/example/orderexport/service/OrderExportService.java` | 共通処理。DAO で読み出し、渡された実装で書き出す |
| `src/main/java/com/example/orderexport/exporter/OrderExporter.java` | 書き出し形式のインターフェース |
| `src/main/java/com/example/orderexport/exporter/JsonOrderExporter.java` | JSON（Jackson） |
| `src/main/java/com/example/orderexport/exporter/XmlOrderExporter.java` | XML（StAX） |
| `src/main/java/com/example/orderexport/dao/OrderDao.java` | Doma の DAO。SQL は `src/main/resources/META-INF/.../OrderDao/selectByOrderDate.sql` |
| `src/main/java/com/example/orderexport/domain/Order.java` | 受注のエンティティ（record） |
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

curl "http://localhost:8080/partners/A001/orders?from=2026-09-01&to=2026-09-05"
# <?xml version="1.0" encoding="UTF-8"?><orders partner="A001"><order id="1" customer="Tanaka Shoji" .../>...</orders>
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
依存 jar を取得せずに解析すると、`warnings.txt` ができて依存 jar がローカルリポジトリに無いことと対処が書かれます（JSON・XML の経路の解決自体は変わりませんが、Spring・Jackson の型を使う呼び出しが抜けます）。

## 結果（`call-hierarchy.csv`）

`caller` 列を省き、`call-hierarchy` 列は起点（`root`）の次から並べています（全 13 行）。

| root | level | callee | resolved-by | call-hierarchy（注記は末尾） |
|---|---|---|---|---|
| OrderApiController.orders | 1 | OrderExportService.export | RESOLVED:NO_OVERRIDE | OrderExportService.export |
| OrderApiController.orders | 2 | OrderDao.selectByOrderDate | UNEXPANDED:GENERATED_IMPL:Doma | … OrderDao.selectByOrderDate, `[UNEXPANDED:GENERATED] implementation is generated at compile time (Doma): …` |
| OrderApiController.orders | 2 | **JsonOrderExporter.export** | **RESOLVED:DATAFLOW_PARAM** | OrderExportService.export, JsonOrderExporter.export |
| OrderApiController.orders | 3 | ObjectMapper.writeValueAsString | RESOLVED:NO_OVERRIDE | … JsonOrderExporter.export, ObjectMapper.writeValueAsString, `[EXTERNAL] no source to follow` |
| OrderApiController.orders | 3 | JsonMapper.shared | RESOLVED:STATIC_BOUND:STATIC | … JsonOrderExporter.export, JsonMapper.shared, `[EXTERNAL] no source to follow` |
| PartnerOrderController.orders | 1 | OrderExportService.export | RESOLVED:NO_OVERRIDE | OrderExportService.export |
| PartnerOrderController.orders | 2 | OrderDao.selectByOrderDate | UNEXPANDED:GENERATED_IMPL:Doma | （上と同じ） |
| PartnerOrderController.orders | 2 | **XmlOrderExporter.export** | **RESOLVED:DATAFLOW_PARAM** | OrderExportService.export, XmlOrderExporter.export |
| PartnerOrderController.orders | 3 | XmlOrderExporter.writeOrder | RESOLVED:STATIC_BOUND:PRIVATE | … XmlOrderExporter.export, XmlOrderExporter.writeOrder |
| PartnerOrderController.orders | 4 | Order.id（ほか customerName / orderDate / amount の 4 行） | RESOLVED:STATIC_BOUND:FINAL_CLASS | … XmlOrderExporter.writeOrder, Order.id, `[EXTERNAL] no source to follow` |

実際の行（抜粋）:

```csv
caller,callee,resolved-by,level,root,call-hierarchy
at com.example.orderexport.service.OrderExportService.export(OrderExportService.java:29),JsonOrderExporter.export,RESOLVED:DATAFLOW_PARAM,2,OrderApiController.orders,OrderExportService.export,JsonOrderExporter.export
at com.example.orderexport.service.OrderExportService.export(OrderExportService.java:29),XmlOrderExporter.export,RESOLVED:DATAFLOW_PARAM,2,PartnerOrderController.orders,OrderExportService.export,XmlOrderExporter.export
```

読みどころ:

- **同じ `OrderExportService.java:29` の呼び出しが、経路ごとに別の実装へ確定しています。**
  `OrderApiController.orders` から来た経路では `JsonOrderExporter.export`、
  `PartnerOrderController.orders` から来た経路では `XmlOrderExporter.export` だけが行になり、
  どちらも `resolved-by` が `RESOLVED:DATAFLOW_PARAM`（呼び出し元から渡された引数を経路上で追って特定した）です。
  もう片方の実装は、その経路には出ません
- そのため、確定した実装の**先**（XML なら `writeOrder`）まで経路ごとに降りていけます。
  1 件に絞れなかった呼び出しは候補を並べるだけで先へ降りないので（`UNEXPANDED:CHA`）、ここが確定するかどうかで見える深さが変わります
- 影響調査の向き: Excel で `callee` 列を `XmlOrderExporter.export` に絞ると、`root` は `PartnerOrderController.orders` だけです。
  「XML の書き出しを直したら、取引先連携の口だけを確認すればよい」と分かります。
  逆に `OrderExportService.export` を直すと、`root` が 2 つとも出ます
- `OrderDao.selectByOrderDate` は Doma の DAO で、実装クラス（`OrderDaoImpl`）はアノテーション処理がコンパイル時に作るのでソースにありません。
  「実装が無い」のではなく「コンパイル時に生成される」ことが注記（`[UNEXPANDED:GENERATED]`）で分かります

`methods.csv` の `OrderExportService.export(LocalDate,LocalDate,OrderExporter)` の行は、`unresolvedCause` に
`[UNEXPANDED:CHA] parameter (passed in from outside the method)` が出ます。
`methods.csv` はメソッド 1 つにつき 1 行で経路を持たないため、「このメソッドの中だけを見ると引数の実装は決まらない」という意味です。
経路ごとの答えは `call-hierarchy.csv` のほうにあります。

## 書き方を変えるとどうなるか（発表の補足）

実装を `new` で作って渡すのではなく、**Spring の Bean として注入したものを引数で渡す**書き方もよくあります。

```java
// JsonOrderExporter / XmlOrderExporter に @Component を付け、コントローラはコンストラクタ注入で受ける
private final XmlOrderExporter xmlOrderExporter;
...
return orderExportService.export(from, to, xmlOrderExporter);
```

この形では、いまのツールは経路ごとには絞れません。29 行目の呼び出しは、どちらの経路でも
`UNEXPANDED:CHA` の 2 行（`JsonOrderExporter.export` と `XmlOrderExporter.export`）になり、注記は
`[UNEXPANDED:CHA] 2 candidates: parameter (passed in from outside the method)` です。
渡した値の出所がフィールドで、そのフィールドに何が入るかは DI コンテナが決める（ソースに `new` が無い）ためです。
`@Qualifier` 付きのフィールドを渡す形も同じ結果でした。

絞れなかったときも、**候補は 2 つとも行として残ります**（呼び出しを静かに落とさない）。
取りこぼしは無く、行が多めに出る側に倒れる、ということです。
なお、このときの `contracts-suggested.txt` のひな形（`OrderExporter#export => ??`）は、呼び出し箇所に関係なく 1 つの実装に決めてしまう行なので、
このサンプルのように経路で実装が変わる場合は貼らないでください（ひな形の注記にもそう書かれます）。
