# 条件分岐による打ち切り（呼ばれない経路の判定）

呼び出しがソースに書かれていても、その経路では条件が成立せず実行されないことがあります。
`branch.pruning.enabled=true`（既定）のとき、呼び出し箇所を囲む条件を経路ごとに突き合わせ、
**成立しないと言い切れる場合はその先の階層を出力しません**。

設定は [config/config.properties](../config/config.properties) の `branch.pruning.enabled` にあります
（コメントに既定値と注意点を書いてあります）。実装で迷った点は
[branch-pruning-qa.md](branch-pruning-qa.md) にまとめています。

## 何が起きるか

```java
class Feature {
    void run(boolean verbose) {
        if (verbose) {
            report();       // ← run(false) の経路では実行されない
        } else {
            summary();
        }
    }
}
...
new Feature().run(false);
```

`call-hierarchy.csv`:

```csv
at fx.branch.Feature.run(Feature.java:16),fx.branch.Feature.report(),Main.main,…,Feature.run,Feature.report,[UNREACHABLE] この経路では呼ばれない: 条件「verbose」が成立しない（呼び出し元から渡された第1引数 = false）
at fx.branch.Feature.run(Feature.java:18),fx.branch.Feature.summary(),Main.main,…,Feature.run,Feature.summary
```

呼び出しが書かれている事実は消さず、**呼び出し自体は1行出して、その先の階層だけを出しません**。
理由は注記として階層の末尾に付きます（`[UNEXPANDED:CYCLE] 経路上で既に呼んでいるメソッドへ戻る` や `[UNEXPANDED:DEPTH] 深さ制限(N)に達した` と同じ位置）。
打ち切った件数は実行ログにも出ます。

## 判定できる条件

判定するのは次の形だけです。

| 条件の形 | 例 |
|---|---|
| `if` / `else` / 三項演算子 `?:` の枝 | `if (debug) { … }` |
| `&&` `\|\|` の右側（左側が成立していないと評価されない） | `if (a && b) { … }` |
| アロー形式の `switch` の `case` / `default` | `switch (kind) { case 1 -> … }` |
| `equals` による値の比較 | `if ("full".equals(name)) { … }` |
| プリミティブ・列挙型・文字列の `==` / `!=` | `if (mode == Mode.FULL) { … }` |

値が分かるのは「呼び出し元からコンパイル時定数が渡された引数」と「コンパイル時定数
（`static final` の定数・列挙定数・リテラル）」だけです。**値が分からない条件は、これまでどおり
すべて辿ります**（分からないことを理由に階層を消さない、という安全側の方針）。
経路ごとの引数の値は `dataflow.enabled` の仕組みで運ぶため、`dataflow.enabled=false` のときは
コンパイル時定数の条件だけが判定されます。

意図的に見ないもの:

- コロン形式の `switch`（フォールスルーがあり、どの `case` を通ったか1つに決められない）
- ラムダ式・匿名クラスの外側の条件（本体が生成箇所と同じタイミングで動くとは限らない）
- ループ条件・早期 `return`・例外（「呼ばれない」ことの証明にはならない）

## 打ち切りで階層から消えたメソッドを探す

打ち切った先にしか無いメソッド（上の例で `report()` からしか呼ばれないメソッド）は、
呼び出し階層CSVから丸ごと消えます。消えたメソッドは `methods.csv` の次の2列で一覧できます。

| 列 | 意味 |
|---|---|
| `inHierarchy` | `call-hierarchy.csv` に1行でも出たか（`1` / `0`） |
| `absentCause` | `inHierarchy=0` のときの理由 |

`inHierarchy=0` は「呼び出し元はあるのに、呼び出し階層CSVには一度も現れなかった」メソッドです。
階層CSVは経路ごとに枝分かれするので、「どの経路にも出ない」はメソッド一覧側で見るのが確実です。
打ち切りが妥当かを確かめるときはこの2列で絞り込んでください。

| `absentCause` | 意味 |
|---|---|
| `[UNREACHABLE] 条件分岐で打ち切った先` | 条件分岐による打ち切りで階層から消えた。打ち切った呼び出し先から宣言上のエッジを辿って求めた範囲（別の経路で1行でも出たメソッドは含まない） |
| `[EXCLUDED] exclude.packages で除外` | `exclude.packages` で除外された |
| `[UNEXPANDED:CHA] 候補のため展開されなかった` | 実装を1つに絞れず、候補として行にはなるがその先へ降りなかった |
| `[UNEXPANDED:CYCLE] 循環のため展開されなかった` | 経路上で既に呼んでいるメソッドへ戻る辺だった |
| `[NOT_REACHED] 上流が未出力` | そこへ至る呼び出し自体が出ていない（`max.depth` / `max.rows` の先、起点から辿り着かない、デッドコード） |

## 仕組み（どこで何をしているか）

2段階に分かれています。条件を**集める**のと、成立しないと**判定する**のは別です。

| 段階 | クラス | すること |
|---|---|---|
| フェーズ1（抽出） | `jche.analysis.GuardCollector` | 呼び出し箇所を囲む条件を**事実**としてキャッシュに記録する（C行・U行の `guard` 列）。判断はしない |
| フェーズ3（出力） | `jche.graph.GuardEvaluator` | その経路で渡ってきた値と条件を突き合わせ、成立しないと言い切れるかを判定する |

条件の文字列の形は `jche.cache.Guard`、値の出所は `jche.cache.Origin`（`V:` が値）にあります。
判定できない条件はフェーズ1で**そもそも記録しない**ので、読み手は従来どおりの出力になります。

キャッシュ形式は v13 → v14（`guard` 列の追加とコンパイル時定数の値の記録）。
古いキャッシュは自動で捨てられ、次の実行で作り直されます。
