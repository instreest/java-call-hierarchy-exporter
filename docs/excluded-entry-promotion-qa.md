# 除外した呼び出し先を絞れないと実装側が起点に昇格する件 — 記録 Q&A

[Issue #121](https://github.com/instreest/java-call-hierarchy-exporter/issues/121)
「除外パッケージの呼び出し先を絞れないと、その実装側が起点に昇格して階層が二重に見える」について、
なぜそうなるのか、なぜ直さないのか、利用者はどう回避するかを Q&A の形で残す。

対応の要点:

- **コードは変えない。** 現状の仕様として受け入れる
- 直すには `java.lang.Object` を型階層に載せる必要があり、キャッシュの形式の版上げ・全件再解析・
  CHA 候補の爆発・`methods.csv` のデッドコード検出の鈍化という代償が、得られるものに見合わないと判断した
- 利用者側の回避策は `entry.packages` で起点を固定するか、`exclude.packages` を広げるかの 2 つ

---

## Q1. どういう状態か

宣言型が `exclude.packages`（既定は `java.**` / `javax.**`）に入る呼び出しで、**具象クラスを 1 件も特定できなかった**場合、
呼び出し先は宣言型のまま残る。その行は除外されて `call-hierarchy.csv` に出ない。
一方でソース側の実装（そのメソッドをオーバーライドしている自分のクラス）は入次数が 0 のままなので、
全体モード（`entry.packages` が空）では**起点に昇格**し、その実装の中身が独立した階層として出る。

利用者から見ると、誰から呼ばれるのか分からないメソッドが入口として並び、その下に jar のメソッドを呼ぶ行が続く。

## Q2. 実際に見つかった例は

[spring-petclinic](https://github.com/spring-projects/spring-petclinic) を
`src/main/java` と `src/test/java`、依存 jar 171 個、`exclude.packages=java.**,javax.**` で解析したとき。

```java
Object sourceProperty = source.getProperty(name);
assertNotNull(sourceProperty.toString(), "...");
```

レシーバの宣言型が `Object` で、値は Spring の `PropertySource#getProperty` の戻り値なので具象を特定できない。
結果はこうなった。

| 見えたもの | 内容 |
|---|---|
| `sourceProperty.toString()` の行 | 出ない（`java.lang.Object.toString` として除外される） |
| `Owner.toString()` / `NamedEntity.toString()` | `inDegree` が 0、`role` が `ENTRY_CANDIDATE` |
| `Owner.toString` を起点とする行 | 13 行。中身は `org.springframework.core.style.ToStringCreator.append` の連鎖 |

`append` は `org.springframework.core.style` なので `java.**` には当たらず、除外では消えない。

## Q3. 原因はどこか

`java.lang.Object` を型階層に載せていないこと。
`jche.analysis.TypeContextTracker` の `collectSupertypes` が、候補計算に寄与しないという理由で
`java.lang.Object` を親型から明示的に外している（型階層を無駄に巨大化させないため）。

そのため `jche.graph.CallResolver` は `Object.toString()` の下位型を 1 件も見つけられず、
呼び出し先を `java.lang.Object.toString` のまま確定する。
`CallResolver.inDegrees` は解決後の呼び出し先を数えるので `Owner.toString` は 0 件になり、
`jche.graph.EntryPoints` の全体モードが「入次数 0 かつソースに本体がある」メソッドとして起点に選ぶ。

## Q4. 除外の判定が宣言型で行われているのが原因ではないのか

違う。**除外の判定は既に解決後の具象で行っている。**
`jche.report.StreamingTreeWalker` の `isExcluded` に渡るのは、段の並びで解決したあとの呼び出し先である。

そのため、具象を特定できるケースでは宣言型が除外対象でも行になり、その先も辿れる。

```java
Runnable r = new MyTask();   // 宣言型は java.lang.Runnable（除外対象）
r.run();                     // 解決後の具象は MyTask.run（ソース）
```

```csv
at ex.Main.main(Main.java:6),ex.MyTask.run(),Main.main,MyTask.run,解決:LOCAL_NEW
at ex.MyTask.run(MyTask.java:6),ex.MyTask.helper(),Main.main,MyTask.run,MyTask.helper
```

この Issue は「**絞れなかったとき**」だけの話である。

## Q5. なぜ `java.lang.Object` を型階層に載せて直さないのか

代償が得られるものに見合わないため。載せれば `Object.toString()` の候補を列挙でき、
`Owner.toString` の入次数も 0 でなくなるが、次の 4 つが付いてくる。

1. **キャッシュの作り直し。** 親型はキャッシュに書く事実（H 行）なので、形式の版を上げる必要があり、
   全プロジェクトで一度だけ全件再解析が走る。すべての型の親に `Object` が入るぶんキャッシュも肥大する
2. **CHA 候補の爆発。** `Object` のメソッドの候補は、プロジェクト内のオーバーライド数そのもの。
   `toString` / `equals` / `hashCode` はレガシーな業務コードでは数百件になりうる。
   `Config.CHA_MAX_CANDIDATES`（20）は出力だけを切っていて入次数は切っていないので、
   「CSV に出ていないのに呼ばれている扱い」という食い違いも生む
3. **`methods.csv` の意味が変わる。** `toString` や `equals` が「呼ばれている」側に回り、
   `ENTRY_CANDIDATE` / `ISOLATED` から外れる。デッドコードの洗い出しという用途では検出力が落ちる
4. **それでも片手落ち。** `HashMap` の内部から呼ばれる `equals` のように、jar の中からの呼び出しは
   `Object` を載せても見えない。追えるのは自分のソースにある呼び出し箇所だけ

得られるのは「宣言型が `Object` の呼び出し」という、影響調査ではもともと確度の低い経路の一部である。
このツールの優先順位は「漏れないこと > 正確であること」だが、**候補を大量に並べることは漏れを減らさず、
読む側の負担と誤読（`toString` が呼ばれていると見える）を増やす**ので、ここでは載せない側に倒した。

## Q6. 利用者はどう回避するか

2 つある。目的に応じて選ぶ。

| 回避策 | 効果 | 副作用 |
|---|---|---|
| `entry.packages` に自分のアプリのパッケージを書く | 起点の集合が指定した範囲に固定され、想定外のメソッドが入口として並ばなくなる | 入次数 0 のメソッドを洗い出す全体モードは使えなくなる |
| `exclude.packages` に該当のライブラリを足す（今回なら `org.springframework.**`） | 昇格した起点の下に続く jar のメソッドの行が消える | そのライブラリを経由して自分のコードへ戻る呼び出しも、行としては出なくなる（先は親に繋ぎ直して辿る） |

`methods.csv` の `role` が `ENTRY_CANDIDATE` のメソッドは「真の入口」ではなく、
デッドコード・テスト・リフレクション経由のものが混ざる。今回の昇格もその一種として仕分ける前提で読む。

## Q7. 将来 `Object` を載せるとしたら、何が決まっていれば着手できるか

**候補どまりの呼び出し（CHA で 1 件に絞れなかったもの）を入次数に数えるかどうか。** ここが決まらないと Q5 の 2 と 3 の
落としどころが決まらない。数えるなら今回の昇格は消えるがデッドコード検出が鈍り、数えないなら昇格は消えない。
併せて `Config.CHA_MAX_CANDIDATES` で切った候補を入次数に数えるかも揃える必要がある
（出力と入次数で扱いが違うと、CSV とログの件数が食い違う）。
