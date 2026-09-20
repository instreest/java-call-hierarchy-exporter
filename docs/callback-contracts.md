# ソースの外（JDK・フレームワーク）との契約

ツールは jar の中を読みません。けれど「jar の中のこのメソッドは、渡した値のこれを呼び戻す」
「フレームワークはこのメソッドを入口として呼ぶ」という**契約**は文章で書けます。
契約表を持つことで、jar の中を読まずに階層を繋ぎ、入口を仕分けます。

| 種類 | 何を決めるか | 出力 |
|---|---|---|
| **A. 呼び戻し** | 呼び出し箇所で渡した値のどれが、どのメソッドで呼び戻されるか | `call-hierarchy.csv` に `[RESOLVED:CALLBACK]` の行を足す |
| **B. 起点** | どのメソッドをフレームワークが入口として呼ぶか | `methods.csv` の `role` を `FRAMEWORK_ENTRY` にし、全体モードの起点に加える |

## A. jar の中から呼び戻される呼び出しを繋ぐ

`new Thread(task).start()` の `start` は JDK の中なので、ツールはその先を読めません。
けれど「`Thread#start()` はコンストラクタに渡した `Runnable` の `run()` を呼ぶ」という**契約**は
文章で書けます。ツールはこの契約表を持ち、jar の中を読まずに `start` の先へ辺を張ります。

```csv
at fx.lambda.Starter.viaThread(Starter.java:20),Starter.Job.run,Starter.viaThread,Starter.Job.run,[RESOLVED:CALLBACK] contract: Thread#start() calls run()
at fx.lambda.Starter$Job.run(Starter.java:50),OrderDaoImpl.findById,Starter.viaThread,Starter.Job.run,OrderDaoImpl.findById,[UNEXPANDED:CHA] 2 candidates: field
```

`caller` 列は `start()` を呼んでいる行、`callee` 列は呼び戻される側です。呼び出し先（`Thread.start`）自身の行が
出ている（`java.**` を除外していない）場合は、その次に並びます。`exclude.packages` で `java.**` を
除外していても、契約で繋いだ先は辿ります。

### 追える条件

呼び戻される側を決めるには、**渡した値の具象型**が要ります。決め方は[ラムダ式・メソッド参照](../README.md#ラムダ式メソッド参照)と同じで、
`new` した型・ラムダ／メソッド参照・引数で渡ってきたもの・出所が1つに定まるフィールド、のどれかなら分かります。

| 形 | 例 | 追える |
|---|---|---|
| `Runnable` 実装クラスを `new` して渡す | `new Thread(new Job()).start()` | ○ |
| `Thread` を継承して `run` を上書き | `new Worker().start()` | ○ |
| ラムダを渡す | `pool.submit(() -> ...)` | ○ |
| ローカル変数に入れて渡す | `Runnable t = new Job(); new Thread(t).start();` | ○ |
| 引数で受け取ったものを渡す | `void kick(Runnable r) { new Thread(r).start(); }` | 呼び出し元でその引数が分かる経路なら ○ |
| `Runnable` 型のフィールド（出所が1つに定まらない） | `this.task` を後から差し替える | × |
| `list.forEach(Runnable::run)` | 要素の `run` を呼ぶが、要素が何かは分からない | × |

分からないときは辺を張りません。`Runnable` の全実装を候補に並べるような広い候補は出しません
（誤って絞るより、絞れないと分かる方が害が少ないため）。

### 同梱の契約表（A）

JDK のうち、「呼び戻す」と言い切れて、実務で経路が切れて困るものに絞っています
（`src/jche/graph/JdkCallbacks.java`）。

| 区分 | 呼び出し先 | 呼び戻されるもの |
|---|---|---|
| スレッド | `Thread#start()` / `Thread#run()` | コンストラクタに渡した `Runnable#run()`、または継承した `run()` |
| 非同期 | `Executor#execute` / `ExecutorService#submit` / `ScheduledExecutorService#schedule*` | 引数の `run()` / `call()` |
| | `CompletableFuture#runAsync/supplyAsync/thenApply/thenAccept/thenRun/thenCompose` | 引数の関数型メソッド |
| | `Timer#schedule*` | 引数の `TimerTask#run()` |
| コレクション | `Iterable/Collection/List/Set#forEach`、`Map#forEach`、`Collection#removeIf` | 引数の `accept` / `test` |
| | `List#sort`、`Collections#sort`、`Arrays#sort` | 引数の `compare` |
| | `Map#computeIfAbsent/computeIfPresent/compute/merge` | 引数の `apply` |
| Stream / Optional | `Stream#forEach/map/flatMap/filter/peek/anyMatch/allMatch/noneMatch/sorted` | 引数の関数型メソッド |
| | `Optional#map/flatMap/filter/ifPresent/ifPresentOrElse/orElseGet` | 引数の関数型メソッド |

呼び出し先のキーは JDT のバインディングが返す**宣言型**です。`list.forEach(...)` は `List` が
`forEach` を上書きしていないので `Iterable#forEach` に、`executor.submit(...)` は `ExecutorService#submit` に
解決されます。上書きしていない型で書いた行（`List#forEach`、`ExecutorService#execute`）は永久に当たりません。
同梱表の行は `bash test/contracts/run.sh` が実行中の JDK と照合します
（[callback-contracts-qa.md](callback-contracts-qa.md) の Q15）。

### 契約の書き方（A）

1 行が 1 契約です。

```
呼び出し先のメソッドキー -> 位置 : 呼ばれるメソッドのシグネチャ
java.lang.Thread#start() -> c* : run()
java.lang.Iterable#forEach(java.util.function.Consumer) -> a0 : accept(java.lang.Object)
```

| 位置 | 意味 |
|---|---|
| `r` | レシーバそのもの（`Thread` のサブクラスが `run` を上書きする形） |
| `aN` | N 番目（0 始まり）の実引数。`a*` なら全部 |
| `cN` | レシーバを `new` したときの N 番目の実引数。`c*` なら全部（`Thread(Runnable)` はオーバーロードが多いので `c*`） |

シグネチャは消去型で書きます（`accept(java.lang.Object)`。`accept(T)` ではない）。

## B. フレームワークが起点として呼ぶメソッドを仕分ける

`@Scheduled` のメソッドや `HttpServlet#doGet` の上書きは、ソースのどこからも呼ばれていませんが、
フレームワークが呼ぶ入口です。契約に当たるメソッドは `methods.csv` の `role` が `FRAMEWORK_ENTRY` になり、
残った `ENTRY_CANDIDATE` が「本当に誰からも呼ばれていないもの（デッドコードの疑い）」に近づきます。

```csv
Jobs.nightly(),fx.entry.Jobs,C,src/fx/entry/Jobs.java,17,1,1,2,FRAMEWORK_ENTRY,1,0,,1,
Jobs.list(),fx.entry.Jobs,C,src/fx/entry/Jobs.java,29,1,1,1,FRAMEWORK_ENTRY,1,0,,1,
```

全体モード（`entry.packages` 空欄）では、`FRAMEWORK_ENTRY` は**ソースから呼ばれていても起点**になります
（`Jobs.list` のように画面入口が内部からも呼ばれる形で、入口としての経路が別に出ます）。
`role` は呼び出し元の有無より「フレームワークが呼ぶ」事実を優先するので、内部から呼ばれていても
`NORMAL` ではなく `FRAMEWORK_ENTRY` です。

### 同梱の契約表（B）

`src/jche/graph/BundledFrameworkEntries.java`。javax と jakarta は両方あります。

| 区分 | 契約 |
|---|---|
| JDK | `public static main(String[])` |
| Servlet | `HttpServlet#doGet/doPost/doPut/doDelete/service`、`GenericServlet#service/init`、`Filter#doFilter`、`ServletContextListener` の上書き |
| Spring Web | `@RequestMapping` / `@GetMapping` / `@PostMapping` / `@PutMapping` / `@DeleteMapping` / `@PatchMapping` / `@ExceptionHandler` / `@InitBinder` / `@ModelAttribute` |
| Spring スケジュール・イベント・ライフサイクル | `@Scheduled`、`@EventListener`、`@TransactionalEventListener`、`ApplicationListener#onApplicationEvent`、`InitializingBean#afterPropertiesSet`、`DisposableBean#destroy`、`CommandLineRunner#run`、`ApplicationRunner#run`、`@PostConstruct` / `@PreDestroy` |
| メッセージング | `@KafkaListener` / `@RabbitListener` / `@JmsListener`、`MessageListener#onMessage` |
| バッチ | Quartz `Job#execute`、Spring Batch `Tasklet#execute` |
| Struts | `Action#execute` |
| テスト | JUnit 5 `@Test` / `@BeforeEach` / `@AfterEach` / `@BeforeAll` / `@AfterAll` / `@ParameterizedTest`、JUnit 4 `@Test` / `@Before` / `@After`、TestNG `@Test` |

### 契約の書き方（B）

```
@org.springframework.scheduling.annotation.Scheduled      … そのアノテーションが付いたメソッド
super javax.servlet.http.HttpServlet#doGet(javax.servlet.http.HttpServletRequest,javax.servlet.http.HttpServletResponse)
                                                             … その型を継承（実装）した型の、同じシグネチャのメソッド
static main(java.lang.String[])                              … public static でそのシグネチャのメソッド
```

`super` の型は jar の中で構いません（型階層には jar の親型の名前も入っています）。
判定はメソッド単位です。`@Controller` のようなクラスのアノテーションだけでは入口にしません
（そのクラスの全メソッドが入口とは限らないため）。メソッド側の `@GetMapping` 等で判定します。

## 自前のフレームワーク分を足す

同梱の表に無いものは、設定ファイルから足せます。A と B の行を同じファイルに混ぜて書けます
（`->` を含む行が A、`@` / `super` / `static` で始まる行が B）。

```properties
# config.properties
contracts.files=contracts.txt
```

```
# contracts.txt（UTF-8、1 行 1 契約。# はコメント）
fx.entry.Dispatcher#submit(java.lang.Runnable) -> a0 : run()
@fx.entry.Endpoint
super jp.co.xxx.BaseAction#execute()
```

- パスは設定ファイルのフォルダからの相対（`plugin.folders` と同じ起点）。複数ならカンマ区切り
- 読み込んだ行数は実行ログに出ます。形が違う行は警告に出して読み飛ばします
- `contracts.builtin=false` にすると同梱の表を使わず、自前の表と拡張だけになります

表では書けない条件（設定ファイルから機械的に作る、型の一覧を見て決める等）は、
`jche.extension.ContractProvider` を実装した拡張で返します。読み込み方は
[インスタンス解析条件のプラグイン](instance-analysis-plugin.md)と同じで、`plugin.folders` に
`.java` を置き、クラス名を `contracts.providers` に書きます。

```java
public class MyContracts implements jche.extension.ContractProvider {
    @Override
    public List<String> lines() {
        return List.of("jp.co.xxx.EventBus#on(jp.co.xxx.Handler) -> a0 : handle(jp.co.xxx.Event)",
                       "@jp.co.xxx.Endpoint");
    }
}
```

`test/regression/whole/contracts.txt` に、`test/demo` の自前フレームワーク分（`Dispatcher#submit` と
`@Endpoint`）を足した例があります。
