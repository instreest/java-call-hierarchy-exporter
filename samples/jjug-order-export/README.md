# samples/jjug-order-export — 呼び出し元によって実装が変わるインターフェース呼び出しの最小例

JJUG での発表用のサンプルです。java-call-hierarchy-exporter で解析して、
**宣言型はインターフェースで、実際に動くインスタンスは呼び出し元によって変わる**呼び出しが
呼び出し階層にどう出るかを見せるために置いています。
Spring Boot（Web MVC）と Doma（DAO）で書いた、受注データを外部へ書き出す小さな Web アプリです。
書き方は Spring のデファクト（実装は `@Component` の Bean、使う側はコンストラクタ注入）に合わせています。

設計の判断（題材の選び方、書き方を変えたときの結果など）は
[docs/jjug-order-export-sample-qa.md](../../docs/jjug-order-export-sample-qa.md) にあります。

## 題材

同じ「期間内の受注を読み出して書き出す」処理を、2 つの入口が別の形式で使います。

| 入口 | 用途 | 形式 | 渡す実装 |
|---|---|---|---|
| `GET /api/orders` | 画面（SPA）向けの API | JSON | コンストラクタ注入した `JsonOrderExporter` |
| `GET /partner/orders` | 取引先システムとの連携 | XML（取引先と取り決めた要素名・属性名） | コンストラクタ注入した `XmlOrderExporter` |

```
OrderApiController.orders      … 注入された JsonOrderExporter を渡す
PartnerOrderController.orders  … 注入された XmlOrderExporter を渡す
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
決めているのは、`exporter` を渡している呼び出し元です。

```java
@RestController
public class PartnerOrderController {

    private final OrderExportService orderExportService;
    private final XmlOrderExporter xmlOrderExporter;       // @Component の Bean をコンストラクタ注入

    public PartnerOrderController(OrderExportService orderExportService, XmlOrderExporter xmlOrderExporter) { ... }

    @GetMapping(value = "/partner/orders", produces = MediaType.APPLICATION_XML_VALUE)
    public String orders(...) {
        return orderExportService.export(from, to, xmlOrderExporter);
    }
}
```

## ファイル

| ファイル | 内容 |
|---|---|
| `src/main/java/com/example/orderexport/web/OrderApiController.java` | 入口 1。注入された JSON の実装を渡す |
| `src/main/java/com/example/orderexport/web/PartnerOrderController.java` | 入口 2。注入された XML の実装を渡す |
| `src/main/java/com/example/orderexport/service/OrderExportService.java` | 共通処理。DAO で読み出し、渡された実装で書き出す |
| `src/main/java/com/example/orderexport/exporter/OrderExporter.java` | 書き出し形式のインターフェース |
| `src/main/java/com/example/orderexport/exporter/JsonOrderExporter.java` | JSON（`@Component`。Spring Boot の `JsonMapper` を注入） |
| `src/main/java/com/example/orderexport/exporter/XmlOrderExporter.java` | XML（`@Component`。StAX） |
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

curl "http://localhost:8080/partner/orders?from=2026-09-01&to=2026-09-05"
# <?xml version="1.0" encoding="UTF-8"?><orders><order id="1" customer="Tanaka Shoji" .../>...</orders>
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

## 結果（`call-hierarchy.csv`）

`caller` 列を省き、`call-hierarchy` 列は起点（`root`）の次から並べています（全 8 行）。

| root | level | callee | resolved-by | call-hierarchy（注記は末尾） |
|---|---|---|---|---|
| OrderApiController.orders | 1 | OrderExportService.export | RESOLVED:NO_OVERRIDE | OrderExportService.export |
| OrderApiController.orders | 2 | OrderDao.selectByOrderDate | UNEXPANDED:GENERATED_IMPL:Doma | … OrderDao.selectByOrderDate, `[UNEXPANDED:GENERATED] implementation is generated at compile time (Doma): …` |
| OrderApiController.orders | 2 | **JsonOrderExporter.export** | **UNEXPANDED:CHA** | … JsonOrderExporter.export, `[UNEXPANDED:CHA] 2 candidates: parameter (passed in from outside the method)` |
| OrderApiController.orders | 2 | **XmlOrderExporter.export** | **UNEXPANDED:CHA** | … XmlOrderExporter.export, （同じ注記） |
| PartnerOrderController.orders | 1 | OrderExportService.export | RESOLVED:NO_OVERRIDE | OrderExportService.export |
| PartnerOrderController.orders | 2 | OrderDao.selectByOrderDate | UNEXPANDED:GENERATED_IMPL:Doma | （上と同じ） |
| PartnerOrderController.orders | 2 | **JsonOrderExporter.export** | **UNEXPANDED:CHA** | （上と同じ） |
| PartnerOrderController.orders | 2 | **XmlOrderExporter.export** | **UNEXPANDED:CHA** | （上と同じ） |

実際の行（抜粋）:

```csv
caller,callee,resolved-by,level,root,call-hierarchy
at com.example.orderexport.service.OrderExportService.export(OrderExportService.java:29),JsonOrderExporter.export,UNEXPANDED:CHA,2,PartnerOrderController.orders,OrderExportService.export,JsonOrderExporter.export,[UNEXPANDED:CHA] 2 candidates: parameter (passed in from outside the method)
at com.example.orderexport.service.OrderExportService.export(OrderExportService.java:29),XmlOrderExporter.export,UNEXPANDED:CHA,2,PartnerOrderController.orders,OrderExportService.export,XmlOrderExporter.export,[UNEXPANDED:CHA] 2 candidates: parameter (passed in from outside the method)
```

読みどころ:

- **29 行目の呼び出しは、どちらの経路でも実装を 1 つに絞れていません。**
  `PartnerOrderController.orders`（実際には XML しか動かない）から来た経路にも `JsonOrderExporter.export` の行が出ます。
  渡している値はコントローラのフィールドで、そこに何が入るかは DI コンテナが決める（ソースに `new` が無い）ため、
  引数を呼び出し元まで遡っても型が決まらないからです。両方の実装が Bean なので、Bean 定義（`SPRING_DI`）でも 1 つに定まりません
- **絞れなくても、候補は 2 つとも行として残ります。** 呼び出しを静かに落とさず、行が多めに出る側に倒れています。
  `resolved-by` の `UNEXPANDED:` と注記の `[UNEXPANDED:CHA]` が「ここは辿り切れていない」の目印で、
  Excel で `resolved-by` が `UNEXPANDED:` で始まる行に絞れば、確かめるべき箇所だけが残ります
- **候補の先へは降りません。** 候補数ぶん枝分かれして爆発するのを避けるためで、JSON 側の `jsonMapper.writeValueAsString`、
  XML 側の `writeOrder` は `call-hierarchy.csv` に出てきません。`methods.csv` では `XmlOrderExporter.writeOrder` が
  `inHierarchy=0` になっており、ここから「階層に出てこなかったメソッド」を拾えます
- 影響調査の向き: Excel で `callee` 列を `XmlOrderExporter.export` に絞ると、`root` は 2 つとも出ます。
  実際に影響するのは取引先連携の口だけですが、静的解析で言い切れないので両方を挙げています（多めに出る側の誤り）
- `OrderDao.selectByOrderDate` は Doma の DAO で、実装クラス（`OrderDaoImpl`）はアノテーション処理がコンパイル時に作るのでソースにありません。
  「実装が無い」のではなく「コンパイル時に生成される」ことが注記（`[UNEXPANDED:GENERATED]`）で分かります

出力フォルダの `contracts-suggested.txt` には、絞れなかった呼び出しを 1 件に決めるための契約表のひな形
（`OrderExporter#export => ??`）が出ます。ただしこの行は呼び出し箇所に関係なく 1 つの実装に決めてしまうので、
このサンプルのように経路で実装が変わる場合は貼らないでください（ひな形の注記にもそう書かれます）。

## 書き方を変えるとどうなるか（発表の補足）

同じ題材を、注入の書き方だけ変えて解析した結果です。

| 呼び出し元の書き方 | 29 行目の結果 |
|---|---|
| 具象型のフィールドにコンストラクタ注入（**このサンプル**） | 両方の経路で `UNEXPANDED:CHA`（2 候補） |
| インターフェース型のフィールドにコンストラクタ注入し、引数に `@Qualifier("xmlOrderExporter")` | 同上 |
| インターフェース型のフィールドに `@Autowired @Qualifier("xmlOrderExporter")` でフィールド注入 | 同上 |
| Bean にせず、呼び出し元で `new XmlOrderExporter()` して渡す | 経路ごとに 1 件に確定（`RESOLVED:DATAFLOW_PARAM`）。JSON の経路には `JsonOrderExporter.export` だけ、XML の経路には `XmlOrderExporter.export` だけが出て、その先（`writeOrder`）まで降りる |

`@Qualifier` は、その注入先のフィールドを受け手にした呼び出しを絞るのには使われますが（`SPRING_DI_QUALIFIER`）、
引数として別のメソッドへ渡った先までは運ばれません。
呼び出し元で `new` した値は、引数を経路上で遡れば型が決まるので、経路ごとに確定します。
