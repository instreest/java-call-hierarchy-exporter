# ソースの外（JDK・フレームワーク）との契約

ツールは jar の中を読みません。けれど「jar の中のこのメソッドは、渡した値のこれを呼び戻す」
「フレームワークはこのメソッドを入口として呼ぶ」「この型はこの実装で動く」という**契約**は文章で書けます。
契約表を持つことで、jar の中を読まずに階層を繋ぎ、入口を仕分け、実装を 1 件に絞ります。

| 種類 | 何を決めるか | 出力 |
|---|---|---|
| **A. 呼び戻し** | 呼び出し箇所で渡した値のどれが、どのメソッドで呼び戻されるか | `call-hierarchy.csv` に `RESOLVED:CALLBACK` の行を足す |
| **B. 起点** | どのメソッドをフレームワークが入口として呼ぶか | `methods.csv` の `role` を `FRAMEWORK_ENTRY` にし、全体モードの起点に加える |
| **C. 具象型** | 宣言型（またはその型のメソッド）を、どの実装に解決するか | `call-hierarchy.csv` の `resolved-by` が `RESOLVED:CONTRACT` になり、その先へ降りる |

> 種類 C は、[インスタンス解析条件の拡張](instance-analysis-plugin.md)と同じことを、Java を書かず
> 契約表の 1 行で指定するものです。設計の経緯は
> [contracts-unification-design.md](contracts-unification-design.md) にあります。

## A. jar の中から呼び戻される呼び出しを繋ぐ

`new Thread(task).start()` の `start` は JDK の中なので、ツールはその先を読めません。
けれど「`Thread#start()` はコンストラクタに渡した `Runnable` の `run()` を呼ぶ」という**契約**は
文章で書けます。ツールはこの契約表を持ち、jar の中を読まずに `start` の先へ辺を張ります。

```csv
at fx.lambda.Starter.viaThread(Starter.java:20),Starter.Job.run,RESOLVED:CALLBACK,1,Starter.viaThread,Starter.Job.run,[RESOLVED:CALLBACK] contract: Thread#start() calls run()
at fx.lambda.Starter$Job.run(Starter.java:50),OrderDaoImpl.findById,UNEXPANDED:CHA,2,Starter.viaThread,Starter.Job.run,OrderDaoImpl.findById,[UNEXPANDED:CHA] 2 candidates: field
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
| レシーバを束縛したメソッド参照を渡す | `new Thread(dao::describe).start()` | `dao` の具象型が分かれば ○（その実装） |
| 上書きされうるメソッドへのメソッド参照で、具象型が分からない | `new Thread(this::hook).start()`、`daos.forEach(Dao::describe)` | 上書き候補を全部出す（`UNEXPANDED:CALLBACK`。参照先の宣言がソースにあるときだけ） |
| ローカル変数に入れて渡す | `Runnable t = new Job(); new Thread(t).start();` | ○ |
| 引数で受け取ったものを渡す | `void kick(Runnable r) { new Thread(r).start(); }` | 呼び出し元でその引数が分かる経路なら ○ |
| `Runnable` 型のフィールド（出所が1つに定まらない） | `this.task` を後から差し替える | × |
| `list.forEach(Runnable::run)` | 要素の `run` を呼ぶが、要素が何かは分からない。参照先の `Runnable#run` は jar の中なので、上書き候補は `Runnable` の全実装になる | × |

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
| JDK | 起動の入口になる `main`（JLS 12.1.4。`static void main(String[])` のほか、引数なしのもの・インスタンスメソッドのものも） |
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
main                                                         … 起動の入口になる main メソッド（JLS 12.1.4）
```

`main` は Java 言語仕様 12.1.4 の起動メソッドの条件そのものです。名前が `main` で、引数が `String[]` 1 つか無し、
`private` でないメソッドを入口にします。`static` でもインスタンスメソッドでも構いません
（Java 25 で確定したインスタンスの main メソッド。コンパクトなソースファイルの `void main()` が典型です）。

`super` の型は jar の中で構いません（型階層には jar の親型の名前も入っています）。
判定はメソッド単位です。`@Controller` のようなクラスのアノテーションだけでは入口にしません
（そのクラスの全メソッドが入口とは限らないため）。メソッド側の `@GetMapping` 等で判定します。

## C. 具象クラスを1件に絞る

インターフェース型で宣言された呼び出しは、実装が複数あると `[UNEXPANDED:CHA] N candidates` で止まり、
その先へ降りません。DI コンテナで注入されるフィールドのように、**どの実装で動くかが設定ファイル側に
書いてある**ものは、その対応を契約表に書けば 1 件に絞れます。

```
宣言型のFQN => 具象型のFQN
jp.co.xxx.dao.UserDao            => jp.co.xxx.dao.UserDaoImpl    … その型で宣言された呼び出し全部
jp.co.xxx.dao.UserDao#find       => jp.co.xxx.dao.CachedUserDao  … その型のそのメソッドだけ
jp.co.xxx.DaoFactory#get("USER") => jp.co.xxx.dao.UserDaoImpl    … そのファクトリにそのキーを渡した値
jp.co.xxx.DaoFactory#get(jp.co.xxx.DaoKind.USER) => jp.co.xxx.dao.UserDaoImpl  … キーが列挙定数の場合
jp.co.xxx.DaoBase#getDao(jp.co.xxx.dao.UserDao.class) => jp.co.xxx.dao.UserDaoImpl  … キーが Class リテラルの場合
jp.co.xxx.dao.UserDao            => jp.co.xxx.dao.A, jp.co.xxx.dao.B   … 絞り切れないときは複数書ける
```

3 つめは、ファクトリメソッドの戻り値を受けた呼び出しを絞ります。

```java
Dao dao = DaoFactory.get("USER");
dao.find();                        // ← ここが UserDaoImpl.find に決まる
DaoFactory.get("USER").find();     // ← 変数に受けない形でも同じ
```

```csv
at jp.co.app.Main.run(Main.java:25),UserDaoImpl.find,RESOLVED:CONTRACT,1,Main.run,UserDaoImpl.find
at jp.co.xxx.dao.UserDaoImpl.find(UserDaoImpl.java:6),UserDaoImpl.load,RESOLVED:NO_OVERRIDE,2,Main.run,UserDaoImpl.find,UserDaoImpl.load
```

| 決まりごと | 内容 |
|---|---|
| 引く順番 | `ファクトリ#メソッド("キー")` → `宣言型#メソッド名` → `宣言型`。狭いほうが先に当たる |
| 複数書いたとき | 1 件に絞れたときだけ展開されるのは本体の判定と同じ。複数のままなら `[UNEXPANDED:CHA] N candidates` |
| 型名の書き方 | **単純名でもかまいません**（`UserDaoImpl`）。解析対象で 1 件に定まるときだけ使い、複数の型に当たるときは使わずに警告に出します |
| 右辺の型 | 具象クラスでかまいません。そのメソッドを親から継承しているだけの型を書いても、本体を持つ親まで辿ります |
| 採用できないとき | その型にも親にもその本体が無ければ、候補を落として CHA に戻します（呼び出しは漏れません）。実行ログに挙がります |
| 効く位置 | [具象クラスの解決](../README.md#具象クラスの解決)の段3。拡張より先、データフローや Spring の判定より先に効きます |
| 効かない呼び出し | `private` / `static` / `final` のように仮想ディスパッチされない呼び出し（段0）には効きません。そこまで差し込みたい場合は[拡張](instance-analysis-plugin.md)を使ってください |

### ファクトリのキーの書き方

| 決まりごと | 内容 |
|---|---|
| キーは引用符で囲む | `#get("USER")`。囲んでいない行は読めない行として警告に出ます |
| 定数で渡していてもよい | `get(Keys.USER)` のように `static final String` で渡していても、**定数の値**で引きます。リテラルの連結（`"USER" + "_DAO"`）も評価されます。書くのは**値**であって定数の単純名ではありません |
| 多引数のファクトリ | 実引数を先頭から見て、**最初に表に載っているキー**を使います。位置は書けません |
| キーが変数で渡る場合 | その変数の値が 1 つに定まれば引けます（ローカル変数に入れた、呼び出し元から引数で渡ってきた、など。下記） |
| 前提 | `dataflow.enabled=true`（既定）。`false` にすると引けないので、その旨を警告します |
| ファクトリの型 | **ソースに書いてある型**で書きます。実装が親クラスにあっても、子クラスの名前で指定できます（下記） |
| 列挙定数のキー | 引用符を付けず**定数の FQN** で書きます（`#get(jp.co.app.Kind.USER)`）。Java のソースに書く形と同じで、文字列のキーと見分けがつきます |
| `Class` リテラルのキー | **型の FQN に `.class` を付けて**書きます（`#getDao(jp.co.app.dao.UserDao.class)`）。これも Java のソースに書く形と同じです |
| 引用符も修飾名も無い形 | `#get(USER)` は読めない行として警告に出ます（文字列なら `"USER"`、列挙定数なら FQN、`Class` なら FQN + `.class`） |

キーは**呼び出し箇所に書かれている値**から引くので、ファクトリの中でクラス名を組み立てていても
（`"jp.co.app.impl." + capitalize(key) + "Service"`）、解析器がその文字列演算を再現する必要はありません。
キーが多すぎて表に並べたくない場合は、算出規則そのものを
[拡張](instance-analysis-plugin.md#3b-算出規則を書く自前の拡張)に書きます。

## 自前のフレームワーク分を足す

同梱の表に無いものは、設定ファイルから足せます。A・B・C の行を同じファイルに混ぜて書けます
（`=>` を含む行が C、`->` を含む行が A、`@` / `super` / `static` で始まる行が B）。

```properties
# config.properties
contracts.files=contracts.txt
```

```
# contracts.txt（UTF-8、1 行 1 契約。# はコメント）
fx.entry.Dispatcher#submit(java.lang.Runnable) -> a0 : run()
@fx.entry.Endpoint
super jp.co.xxx.BaseAction#execute()
jp.co.xxx.dao.UserDao => jp.co.xxx.dao.UserDaoImpl
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

### ファクトリの実装が親クラスにある場合

```java
public class BaseDaoFactory { public static Dao pick(String key) { ... } }
public class ChildDaoFactory extends BaseDaoFactory { }   // pick は宣言していない
public class OtherDaoFactory extends BaseDaoFactory { }

Dao dao = ChildDaoFactory.pick("ORDER_DAO");   // ソースに書いてあるのは子クラス
```

**どちらの型でも書けます。効く範囲が違います。**

| 書き方 | 効く範囲 |
|---|---|
| `fxp.ChildDaoFactory#pick("ORDER_DAO")`（ソースに書いた型） | `ChildDaoFactory.pick(...)` と書いてある箇所だけ。`OtherDaoFactory` 経由は含みません |
| `fxp.BaseDaoFactory#pick("ORDER_DAO")`（宣言元の型） | どの子クラス経由でも。「この親のファクトリなら全部」と言いたいとき |

ふつうは**ソースに書いてある型**で書けば足ります。`contracts-suggested.txt` のひな形も
そちらの形で出ます。「どの子クラス経由でも同じ実装」と言いたいときだけ、宣言元の型に広げます。

### キーが変数で渡る場合

キーは「その呼び出し箇所で 1 つの値に定まる」ときだけ引けます。

| 書き方 | 引けるか | 契約表に書く形 |
|---|---|---|
| `get("USER_DAO")` | ○ | `#get("USER_DAO")` |
| `get(Keys.USER)`（`static final String`） | ○ | **定数の値**で書く（`#get("USER_DAO")`） |
| `get(Kind.ALPHA)`（列挙定数） | ○ | `#get(jp.co.app.Kind.ALPHA)` |
| `getDao(UserDao.class)`（`Class` リテラル） | ○ | `#getDao(jp.co.app.dao.UserDao.class)` |
| `String k = "USER_DAO"; get(k);` | ○ | `#get("USER_DAO")` |
| 呼び出し元から引数で渡ってくる | ○（経路ごと） | `#get("USER_DAO")` |
| フィールド（final でない）を渡す | × | — |
| `get(b ? "X" : "Y")` | × | — |

```java
void run()            { helper("USER_DAO"); }        // ← 呼び出し元では決まっている
void helper(String k) { Factory.get(k).find(); }     // ← run から辿った経路では絞れる
```

**経路ごとの判定**なので、`helper` を別の場所からも呼んでいて、そちらではキーが分からない場合、
その経路では絞れないまま残ります（同じ呼び出しが経路によって違う行になります）。

**既に 1 件に絞れている呼び出しは、経路ごとにやり直しません。** 型単位の広い行
（`jp.co.xxx.Dao#find => …`）が先に決めていれば、そちらが勝ちます。同じ設定なのに経路によって
答えが変わると、読み手が追えなくなるためです。狭く効かせたいときは、広い行のほうを外してください。

## 何を書けばよいか分からないとき

絞れなかった呼び出しがあると、出力フォルダに **`contracts-suggested.txt`** が出ます。
そのまま貼れる契約表のひな形なので、書式を覚えなくても「選んでコメントを外す」だけで済みます。

```
# 3 か所  例) at fxp.App.factoryCall(App.java:17)
#   候補: fxp.AbstractDao / fxp.OrderDaoImpl / fxp.UserDaoImpl
# fxp.DaoFactory#get("USER_DAO") => ??
```

1. 当てはまる行の行頭の `#` を外す
2. `??` を具象型の FQN に置き換える（候補はその行の上にあります）
3. `contracts.files` が指す表に貼る

- まとめ方は**呼び出し箇所ごとではなく、それを直す 1 行ごと**です。同じ呼び出しが 100 か所で
  絞れていなくても、書く行は 1 行だからです。件数の多い順に並びます
- レシーバがファクトリの戻り値なら、ファクトリとキーの形（C-3）で出ます。そちらのほうが狭く、
  同じ型を返す他の呼び出しを巻き込みません
- キーが決まらなかった呼び出しは、`宣言型#メソッド名 => ??` という**型単位の広い行**になります。
  その型のそのメソッドを全部同じ実装に決める行なので、呼び出し箇所ごとに実装が違うなら貼らないでください
  （ひな形にもその注記が付きます）
- 解析のたびに作り直すので、直接編集しても残りません
- 種類が多いときは先頭の 200 行で打ち切ります。上から直していくと、次の実行で残りが出ます

## 効いているかを確かめる

契約表はただの文字列なので、型名やシグネチャを間違えても実行時は「当たらない」だけで、
出力は黙って元のままになります。そこで解析の最後に、**自前の表の行が効いたか**を実行ログに出します。

```
契約表の適用: 自前 2/3 行（呼び戻し 1/2、入口 1/1） ／ 同梱 9 行
[WARN] 自前の契約表で一度も当たらなかった行が 1 件あります。型名・シグネチャの綴り違いか、そのプロジェクトでは使っていない機能の行です:
    /path/to/contracts.txt: jp.co.xxx.EventBus#on(jp.co.xxx.Handler) -> a0 : handle(jp.co.xxx.Event)
    ※ 呼び戻しの行の呼び出し先は、JDT が返す「宣言型」で書きます（List#forEach ではなく Iterable#forEach）。
```

知らせは 2 種類あり、原因が違います。

| 知らせ | 意味 | 見るところ |
|---|---|---|
| `[WARN] 一度も当たらなかった行` | 契約の**呼び出し先そのものが 1 件も見つからなかった** | 型名・シグネチャの綴り。とくに呼び出し先のキーは[宣言型](#同梱の契約表a)で書く。そのプロジェクトで本当に使っていない API なら、そのままで構いません |
| `※ 呼び出し先には一致したが…繋げなかった行` | 呼び出し先は見つかったが、**渡した値の具象型が決まらなかった**（種類 A） | 表は合っています。[追える条件](#追える条件)のどれにも当てはまらない渡し方をしている箇所です |
| `※ 左辺の型には一致したが…採用できなかった行` | 左辺は見つかったが、**右辺の型にその呼び出しの本体が無かった**（種類 C） | 右辺の FQN の綴りと、その型（か親）がそのメソッドを持つか |

- 出すのは**自前の表（`contracts.files` と `contracts.providers`）の行だけ**です。同梱の表は
  「そのプロジェクトで使っていない機能の行」が当たらないのが普通なので、効いた行数だけを数えます
  （同梱の表の検査は `bash test/contracts/run.sh`）
- 自前の表を 1 行も書いていなければ、この知らせは出ません
- 解析サーバー（`--server`）では出しません。グラフ全体を辿らないので「一度も当たらなかった」と
  言い切れないためです
