# jar の中から呼び戻される呼び出しを契約で繋ぐ

`new Thread(task).start()` の `start` は JDK の中なので、ツールはその先を読めません。
けれど「`Thread#start()` はコンストラクタに渡した `Runnable` の `run()` を呼ぶ」という**契約**は
文章で書けます。ツールはこの契約表を持ち、jar の中を読まずに `start` の先へ辺を張ります。

```csv
at fx.lambda.Starter.viaThread(Starter.java:20),Starter.Job.run,Starter.viaThread,Starter.Job.run,[RESOLVED:CALLBACK] 契約: Thread#start() が run() を呼ぶ
at fx.lambda.Starter$Job.run(Starter.java:50),OrderDaoImpl.findById,Starter.viaThread,Starter.Job.run,OrderDaoImpl.findById,[UNEXPANDED:CHA] 候補2件: フィールド変数
```

`caller` 列は `start()` を呼んでいる行、`callee` 列は呼び戻される側です。呼び出し先（`Thread.start`）自身の行が
出ている（`java.**` を除外していない）場合は、その次に並びます。`exclude.packages` で `java.**` を
除外していても、契約で繋いだ先は辿ります。

## 追える条件

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

## 同梱の契約表

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
解決されます。静的な型によって宣言型が変わるものは、それぞれの型で行を持っています。

## 契約の書き方

1 行が 1 契約です（今は同梱の表だけ。設定ファイルで足す形とプラグインは #136 の段階 3）。

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
