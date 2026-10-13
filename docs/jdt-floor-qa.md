# 解析本体の JDT の下限を 3.28.0（Eclipse 2021-12）にする — QA 一覧

> 版の対応表（Eclipse / JDT Core / Java / Pleiades）は [eclipse-pleiades-versions.md](eclipse-pleiades-versions.md)、
> 閉域ネットワークで Eclipse の jar を使って動かす手順は [cli.md](cli.md#閉域ネットワークで動かすpleiadeseclipse-の-jar-を使う)。

きっかけは利用者の問いである。

> Eclipse JDT のバージョンを落とした場合の影響を知りたいです。Java17時代までさかのぼったバージョンでの影響を教えてください。

測ってみると、**本体は JDT 3.40.0（Eclipse 2024-12）より古い jar ではコンパイルすらできなかった**。
新しい JDT にしか無い API を 4 つ直接書いていたためである。本体はソースのまま配り、そこにある JDT の jar に対して
コンパイルされる（jbang の `//DEPS`、閉域ネットワークの手順の `javac`）ので、2024 年より前の Pleiades の jar では
閉域ネットワークの手順が `javac` の段階で止まっていた。

その 4 つを「その API があるときだけ使う」形（名前で引く）に直し、**下限を 3.28.0 にした**。

| 変えたところ | 前 | 後 |
|---|---|---|
| 下限より新しい API の使い方 | ソースに直接書く | `jche.analysis.JdtCompat` に集めて名前で引く |
| コンパイルできる JDT | 3.40.0（Eclipse 2024-12）以降 | **3.28.0（Eclipse 2021-12）以降** |
| 下限の検査 | なし | `test/jdt-floor/run.sh`（3.28.0 の jar でコンパイルし、回帰テストを回す。CI の `jdt-floor` ジョブ） |
| `//DEPS` の版（jbang・プラグインが使う版） | 3.46.0 | 3.46.0（変えていない） |

---

### Q1. 下限より新しい API は何だったか

本体を各版の JDT の jar に対してコンパイルして数えた（実行 JDK 25、`--release 17`）。3.28.0 の jar で
足りなかったのは次の 4 つだけで、どれも解析の層の入口にある。

| API | JDT に入った版 | 使っていた場所 | 何のためか |
|---|---|---|---|
| `RecordPattern`（AST のノード） | 3.34.0 | `FactVisitor#visit(RecordPattern)` | レコードパターンのアクセサの呼び出し（JLS 14.30.2） |
| `IProblem.VarCannotBeUsedWithTypeArguments` | 3.36.0 | `CallEdgeExtractor#isSyntaxError` | `var` の誤用を構文エラーに数えない |
| `ImplicitTypeDeclaration`（AST のノード） | 3.38.0 | `FactVisitor#visit/endVisit(ImplicitTypeDeclaration)` | コンパクトなコンパイル単位の暗黙のクラス（JLS 7.3・8.1.8） |
| `ImportDeclaration#getModifiers()`・`Modifier#isModule(int)` | 3.40.0 | `CallEdgeExtractor`（2 か所） | モジュールインポート宣言（`import module m;`。JLS 7.5.5）を型のインポートと区別する |

### Q2. なぜ 3.28.0 なのか

**Java 17 の文法を全部読める最初の版で、それより下げると手間が増えるのに読める文法が減るからである。**
各版の `JavaCore.getAllVersions()` の最大値と、本体をその版でコンパイルしたときに足りない API を実測した。

| 下限の JDT | Eclipse | 解析できる Java の上限 | その版で新たに足りなくなる API |
|---|---|---|---|
| 3.40.0〜 | 2024-12〜 | 23〜 | なし |
| 3.38.0 / 3.39.0 | 2024-06 / 09 | 22 | `getModifiers()`・`isModule` |
| 3.36.0 / 3.37.0 | 2023-12 / 2024-03 | 21 | `ImplicitTypeDeclaration` |
| 3.34.0 / 3.35.0 | 2023-06 / 09 | 20 | `VarCannotBeUsedWithTypeArguments`（定数） |
| **3.28.0〜3.33.0** | **2021-12〜2023-03** | **17〜19** | `RecordPattern` |
| 3.27.0 | 2021-09 | **16** | なし（ただし Java 17 の sealed・permits を読めない） |
| 3.24.0〜3.26.0 | — | 15〜16 | `AST.getJLSLatest()`（3 か所） |
| 3.22.0 / 3.23.0 | — | 14 | `isSyntheticRecordMethod()`・`Modifier.isSealed` |
| 3.18.0〜3.21.0 | — | 12〜13 | `RecordDeclaration`・`ITypeBinding#isRecord()`・switch 式の問題 ID 5 つ |
| 3.14.0〜3.17.0 | — | 10〜11 | `SwitchExpression`・`SwitchCase#expressions()`・`isSwitchLabeledRule()`（`GuardCollector` の switch の条件の読み取りの中心） |
| 〜3.13.0 | — | 〜9 | `JavaCore.latestSupportedJavaVersion()` など、設定の検証と起動ログに使う API。`var` の問題 ID 8 つ |

3.28.0 の範囲なら名前で引くのは 3 つ（定数 1 つは数値で書く）で済み、どれも AST の訪問の入口に収まる。
3.17.0 以下は switch の扱いという解析の中心に名前で引く呼び出しが入り込むので、勧めない。

Eclipse 列は 3.28.0 以上を [eclipse-pleiades-versions.md](eclipse-pleiades-versions.md) の表から写した。
3.26.0 以下は Maven Central の版番号と Eclipse のリリースの対応を確かめていない。
3.28.0 が 2021-12-06 に Maven Central に公開されたことは確かめた（3.29.0 は 2022-03-15）。

### Q3. 3.28.0 で動かすと何が読めなくなるか

Java 18 以降の文法である。3.28.0 で読めない主なもの:

- switch の型パターンと `when` のガード、レコードパターン（Java 21）
- 無名の変数 `_`（Java 22）
- コンパクトなコンパイル単位・インスタンスの `main()`・`import module`・コンストラクタ本体の `super(...)` より前の文（Java 25）

これらを含むファイルは構文エラーになり、そのファイルの呼び出しは出力に出ない。ただし**黙って落ちはしない**。
`warnings.txt` の「構文エラーで本体を読めなかったファイル」に名前が載る（[syntax-error-report-qa.md](syntax-error-report-qa.md)）。
`source.level` に JDT の上限より上（3.28.0 なら 18 以上）を書いた設定は、設定エラーで止まる。
空欄なら上限（17）で読み、そのことを起動ログに出す。

### Q4. Java 17 までのソースなら結果は同じか

同じだった。4 つを外した本体を 3.28.0 の jar でコンパイルし、JDK 25 で動かして測った。

| 検査 | 3.28.0 の結果 |
|---|---|
| `test/regression`（全ケース） | 期待値（3.46.0 で作ったもの）と一致 |
| `test/pruning`（400 項目）・`test/conditions` | すべて OK |
| `test/jls` のうち Java 17 で書ける 19 パッケージ | すべて OK |
| `test/jls` の Java 21 以降の節・`test/ctorbody` | NG（読めない文法。Q3） |
| `test/dataflow`・`test/warnings` の一部 | 題材の設定が `source.level=21` / `26` を固定しているので、設定エラーで止まる |

`test/warnings` の assertion ケースは、3.46.0 の JDT が `AssertionError` を投げる不具合を題材にしている。
3.38.0 ではこの不具合が起きず、呼び出しは出力に出た。つまりこのケースは今の JDT の不具合に依っている。

古い JDT は JDK 25 の標準ライブラリ（jrt）も問題なく読めた。3.22.0・3.24.0 でも動き、回帰テストとの差は
題材の中でただ 1 つ Java 16 の record を使う `test/demo/src/fx/model/Point.java` が構文エラーになることだけだった。
古い JDT で困るのは「動かないこと」ではなく、「読める Java の版が下がること」と「下げるほど名前で引く API が増えること」である。

### Q5. 名前で引く形はどう選んだか

API ごとに、いちばん小さく済む形にした（`jche.analysis.JdtCompat`）。

| API | 形 | 理由 |
|---|---|---|
| `ImplicitTypeDeclaration` | `visit` を上書きせず、`preVisit2` で `Class#isInstance` を見て型を積み、`postVisit` で戻す | 引数の型として書けないので上書きできない。`preVisit2` → `visit` → 子 → `endVisit` → `postVisit` の順に呼ばれるので、積む・戻す時点は前と同じ。中身は親クラス `AbstractTypeDeclaration` の API（`resolveBinding()`・`bodyDeclarations()`）で書け、メソッドを名前で呼ばずに済む |
| `RecordPattern` | 同じく `preVisit2` で振り分け、`getPatternType()` だけ名前で呼ぶ | 前は `visit` で記録していた。`preVisit2` の既存の処理（式の型を I 行に数える）の後に置いたので、記録の順も同じ |
| `VarCannotBeUsedWithTypeArguments` | `IProblem.Syntax + 1513` の定数 | `case` のラベルに使うコンパイル時定数なので、名前で引けない。古い JDT はこの問題を報告しないので、ラベルが余っても害は無い |
| `getModifiers()`・`isModule` | `Method` を一度だけ探してとっておく | 無い版の JDT は `import module` を構文エラーにするので、AST に残る import はどれも型か static のインポートで、false が正しい |

**名前で見つかったのに呼べないときは止める**（`IllegalStateException`。文言は `analysis.jdtApi`）。
見つかったのに呼べないのは、JDT の API が想定と違う形に変わったときである。黙って「無い」扱いにすると、
その構文の呼び出しが出力から静かに落ちる。止めれば、そのファイルは解析の失敗として `warnings.txt` に載る。

### Q6. 4 つを単純に消すのではいけないのか

いけない。消すと、新しい JDT で動かしたときもその構文の呼び出しが落ちる。実際、4 つを消した本体を 3.46.0 で
動かすと `test/jls` が 10 件 NG になった（レコードパターンのアクセサ、暗黙のクラスとそのデフォルトコンストラクタ、
`import module` だけで見える型）。名前で引く形なら、3.46.0 での結果は前と変わらない（全検査が通る）。

### Q7. キャッシュの形式の版（`CacheFormat.VERSION`）は上げたか

上げていない。今の JDT（3.46.0）で書く事実は変わらない（`test/cacheversion` が記録と同じ指紋を出す）。
古い JDT で動かした場合は、JDT の版がキャッシュの鍵（ヘッダ行の `jdt=`）に入っているので、
版を変えた最初の実行で古いキャッシュは自動で捨てられる。

### Q8. 下限をどう守るか

`test/jdt-floor/run.sh`（CI の `jdt-floor` ジョブ）が守る。ほかの検査はどれも `//DEPS` の版（3.46.0）で
コンパイルするので、誰かが新しい API を直接書いても気づけない。この検査だけが 2 つを見る。

1. 3.28.0 の jar だけで本体がコンパイルできること
2. その本体を 3.28.0 で動かしても `test/regression` の期待値と一致すること

jar の組は `test/jdt-floor/pom.xml` で **Eclipse 2021-12 の版に固定**し、推移的な依存はすべて外した。
3.28.0 の POM は依存を版の範囲（`[3.x,4.0)` など）で書いているので、外さないと Maven はその時点の最新の platform の
jar を選ぶ。それは 2021-12 の Eclipse には無い組み合わせで、検査の結果も日によって変わりうる。
並べた jar は、閉域ネットワークの手順（[cli.md](cli.md#閉域ネットワークで動かすpleiadeseclipse-の-jar-を使う)）が
Eclipse の plugins から集めるものと同じにした。手順で集める jar の組がこれで足りることも、併せて確かめている。

回帰テストのほかの検査（jls・ctorbody など）は、題材が Java 21 以降の文法を含み、`source.level` を固定しているので
下限では回さない。`test/regression` の題材は Java 17 までの文法で書いてある。新しい文法の題材を `test/regression` に
足すとこの検査が落ちるので、そういう題材は jls・ctorbody のような最新の JDT が前提の検査の側に置く。

### Q9. 古い JDT で得をすることはあるか

1 つある。**JDT 3.38.0 以前は `source.level` の 1.3〜1.7 をそのまま使う。** 3.39.0 以降は黙って 1.8 に引き上げる
（設定の読み戻しでわかる。`Config#buildCompilerOptions`）。そのため `enum` や `assert` を識別子に使った
Java 5 より前のコードは、3.38.0 以前なら構文エラーにならない。
[eclipse-plugin-java-floor-qa.md](eclipse-plugin-java-floor-qa.md) の Q6（1.7 以下のソース）の窓（3.28.0〜3.38.0）と一致する。
`//DEPS` の版は変えていないので、これを使うのは閉域ネットワークの手順で古い Eclipse の jar を使う場合である。

### Q10. 却下した案

- **3.27.0 以下まで下げる**: Java 17 の文法（sealed・permits）を読めない。名前で引く API は増える（Q2）
- **版ごとに別のソースファイルを置き、片方だけコンパイルする**: jbang の `//SOURCES` は `*.java **/*.java` を全部コンパイルし、
  閉域ネットワークの手順の `javac` も `-sourcepath` で拾う。ファイルを選ぶ仕組みを足すと、起動の経路が増える
- **古い JDT を見つけたら止める**: Java 17 までのソースなら結果は同じ（Q4）で、止める理由が無い。上限は起動ログに出ていて、
  読めない文法は `warnings.txt` に載る
- **1 ファイル版（`single-file/`）も直す**: 1 ファイル版は本体と同期を取らない場合があるもので、
  今回の目的（閉域ネットワークの手順）は本体の側で足りる（[single-file-qa.md](single-file-qa.md) の Q8）

### 測り方の注意

- 実行 JDK はどれも 25（Temurin 25.0.4.1）。古い JDT の jar は Maven Central から取った
- Q2 の表を作るとき、3.31.0 以前の JDT は Maven の推移的な依存を最新の platform の jar で解決した（版の範囲のため）。
  `test/jdt-floor` は Q8 のとおり 2021-12 の版に固定したうえで、同じ結果になることを確かめた
- 1.7 以下の扱い（Q9）は、JDT にソースレベル 1.4 を指定して実際に効いたレベル（1.3 か 1.8 か）を読み戻して確かめた
