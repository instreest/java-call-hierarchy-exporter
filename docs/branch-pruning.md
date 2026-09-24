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
at fx.branch.Feature.run(Feature.java:16),fx.branch.Feature.report(),Main.main,…,Feature.run,Feature.report,[UNREACHABLE] not called on this path: condition 'verbose' does not hold（呼び出し元から渡された第1引数 = false）
at fx.branch.Feature.run(Feature.java:18),fx.branch.Feature.summary(),Main.main,…,Feature.run,Feature.summary
```

呼び出しが書かれている事実は消さず、**呼び出し自体は1行出して、その先の階層だけを出しません**。
理由は注記として階層の末尾に付きます（`[UNEXPANDED:CYCLE] returns to a method already on this path` や `[UNEXPANDED:DEPTH] depth limit (N) reached` と同じ位置）。
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

`==` / `!=` で判定できるのは、両辺の値が**同じ土俵で比べられる**ときだけです。

- 整数系（`byte` / `short` / `int` / `long` / `char`）どうしは数値で比べます。
  `'A' == 65` は真（JLS 5.6 の数値昇格）なので、`char` の定数も数値で持ちます
- 浮動小数は判定しません（`1` と `1.0` と `1.0f` は同じ値ですが表記が違うため）。
  比べる片方が `float` / `double` の条件は、相手が整数の定数でも判定しません
  （整数の実引数は浮動小数の引数へ暗黙に変換され、精度を失うことがあるため。JLS 5.1.2）
- 文字列の `==` を判定に使えるのは、値を畳めるのが**コンパイル時定数だけ**だからです。
  コンパイル時定数の文字列はインターンされて同じインスタンスになります（JLS 3.10.5）
- 16 進・8 進のリテラルは Java の値で比べます。`int` の `0x80000000` は `-2147483648` です（JLS 3.10.1）

`equals` で判定できるのは、比べる相手の**静的な型**と定数の型が揃うときだけです。
`equals` は実行時の型が違えば、表記が同じ値でも偽になります（`Long.valueOf(0).equals(0)` は偽）。

- `String` の相手と文字列の定数（`"full".equals(name)`・`name.equals("full")`）
- 同じ列挙型の相手と列挙定数（`mode.equals(Mode.FULL)`）
- ボックス型の相手と、それに箱詰めされる定数（`Integer` と `5`、`Long` と `5L`、`Character` と `'A'`）。
  `Float` / `Double` は判定しません

`Object` や型の食い違う相手（`Long` と `0`、`Object` と `"5"` など）の `equals` は判定しません。

値が分かるのは「呼び出し元からコンパイル時定数が渡された引数」と「コンパイル時定数
（`static final` の定数・列挙定数・リテラル）」だけです。**値が分からない条件は、これまでどおり
すべて辿ります**（分からないことを理由に階層を消さない、という安全側の方針）。
経路ごとの引数の値は `dataflow.enabled` の仕組みで運ぶため、`dataflow.enabled=false` のときは
コンパイル時定数の条件だけが判定されます。

意図的に見ないもの:

- 値を変えうるキャストが挟まった条件（`if ((byte) mode == 44)`）。
  縮小プリミティブ変換（JLS 5.1.3）・精度を失いうる拡大（`int` → `float` など。5.1.2）・
  ボックス化・非ボックス化（5.1.7 / 5.1.8）は値そのものを変えるので、キャストを剥がして比べると
  誤った打ち切りになります（`(byte)300 == 44` は真）。呼び出し元の実引数やローカル変数の値
  （`run((byte) p)`・`int m = (byte) p;`）も同じ扱いで、値は「分からない」になります。
  ただしコンパイル時定数（`run((byte) 300)`）は、変換後の値（`44`）で判定します。
  値を保つキャスト（参照型どうし・`(long) i` のような正確な拡大）は剥がして判定します
- 複合代入（`n += 1`）や `n++` で書き換わるローカル変数は、値が分からないものとして扱います。
  ループの中で後ろから書き換わる変数を写した変数（`int copy = n; … n++;`）も同じです
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
| `[UNREACHABLE] below a call pruned by a condition` | 条件分岐による打ち切りで階層から消えた。打ち切った呼び出し先から宣言上のエッジを辿って求めた範囲（別の経路で1行でも出たメソッドは含まない） |
| `[EXCLUDED] excluded by exclude.packages` | `exclude.packages` で除外された |
| `[UNEXPANDED:CHA] not expanded (CHA candidate)` | 実装を1つに絞れず、候補として行にはなるがその先へ降りなかった |
| `[UNEXPANDED:CYCLE] not expanded (cycle)` | 経路上で既に呼んでいるメソッドへ戻る辺だった |
| `[NOT_REACHED] no caller row was emitted` | そこへ至る呼び出し自体が出ていない（`max.depth` / `max.rows` の先、起点から辿り着かない、デッドコード） |

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
