# Java 言語仕様（SE 26）への適合と javac との整合の検査 — 実装時の QA 一覧

Issue なし（依頼: 「AST 解析結果の出力が Java 言語仕様を満たしているか、javac の仕様と整合性があるかを
確認するテストを、JLS の何番のどんなテストか分かるように足す。JDT が対応する最大の版 Java 26 で作る」）。
迷ったこと・困ったことと、その結論を Q&A の形で残す。

対応の要点:

- `test/jls/run.sh` を足した。`test/jls/project/src/` の各ファイルが JLS SE 26 の 1 つの節に対応し
  （パッケージ名が節番号。`jls.s14_14_02` = §14.14.2）、先頭のコメントにその節の何を検査するかを書いてある
- 検査は 2 段。**期待値**（`test/jls/expect.tsv`。1 行が 1 テストで、節番号・ID・説明を持つ）を出力 CSV と
  キャッシュに当てる段と、同じソースを **javac 26**（`--release 26`）でコンパイルしたクラスファイルと
  キャッシュの事実を突き合わせる段（`test/jls/JlsCheck.java`）
- 検査を書いたら、ツールが JLS と食い違っている所が 5 つ見つかったので直した（Q9〜Q12）
  - 拡張 for 文の `iterator()` / `hasNext()` / `next()`（§14.14.2）、try-with-resources の `close()`（§14.20.3）、
    レコードパターンのアクセサ（§14.30.2）を辺にしていなかった
  - コンパクトなコンパイル単位が暗黙に宣言するクラス（§7.3）を型として読んでいなかった
  - 引数なし・インスタンスメソッドの `main`（§12.1.4）を起動の入口にしていなかった
  - 別パッケージのサブクラスの同じシグネチャのメソッドを、パッケージアクセスのメソッドの上書きとして
    CHA の候補に入れていた（§8.4.8.1）
- キャッシュの版を `jche-cache-v28` に上げた（C 行・H 行・D 行が増えるため）
- 回帰テストの期待値は `Holder.viaCollection()` の outDegree だけが変わった（Q15）

---

## Q1. なぜ JLS SE 26 なのか。プレビュー機能は含めるのか

JDT 3.46（`//DEPS` の版）が対応する最大の版が 26 だからである（`JavaCore.latestSupportedJavaVersion()`）。
解析の設定（`test/jls/project/config.properties`）は `source.level=26` を明示する。既定の「JDT の最新」に
任せると、JDT を上げたときに検査の前提が黙って変わる。

**プレビュー機能は含めない。** ツールは JDT のプレビューを有効にしない（`docs/static-analysis-limits.md` の 7 節）。
JDK 26 で言語に確定した新しい機能は無く（プリミティブ型のパターンはプレビューのまま）、SE 26 の言語は
Java 21〜25 で確定した機能（レコードパターン・パターンの switch・無名の変数・モジュールインポート・
コンパクトなソースファイルとインスタンスの main・柔軟なコンストラクタ本体）を含む形になる。
それぞれを節として持つ。

## Q2. なぜ javac と突き合わせるのか。期待値だけでは足りないのか

期待値は人が書くので、**書かなかった呼び出しは検査されない**。拡張 for 文の `iterator()` のように
「ソースに書かれていないが呼ばれるもの」は、まさに人が書き漏らす種類の呼び出しである。

javac のバイトコードは「実際に実行される呼び出し」を全部持っている。ソースの節を足すだけで、
その節の全ての呼び出しが検査に入る。実際、今回見つかった食い違いのうち Q9 と Q11 は、期待値を書く前に
javac との突き合わせで見つかった。

逆に、javac では分からないものもある。実行時の上書きの選び方（§15.12.4.4）はバイトコードに現れないので、
パッケージアクセスの上書き（Q12）や `super.m()` が仮想呼び出しでないことは期待値でしか見られない。
2 段を両方持つのはそのためである。

## Q3. javac の何とキャッシュの何を突き合わせるのか

| javac | キャッシュ | 照合の単位 |
|---|---|---|
| クラス（合成のものを除く） | H 行 | 型名。JLS 6.7 の正準名、無ければ（匿名・ローカル）JLS 13.1 のバイナリ名 |
| メソッド（合成・ブリッジを除く） | D 行 | キー（`型#名前(消去した引数型)`） |
| invoke 命令のうち、呼び出し先がこのソースで宣言されたもの | C 行 | （呼び出し元・行・呼び出し先）の組 |
| `LambdaMetafactory` の invokedynamic | D 行のラムダ・M 行 | ラムダを囲む本物のメソッドと行（Q4） |
| ブリッジメソッド | O 行 | ブリッジのシグネチャが O 行の上書き先にあること |

クラスファイルは JDK 24 で確定した `java.lang.classfile` で読む。外部のライブラリ（ASM など）を
持ち込まずに済み、検査プログラム（`JlsCheck.java`）は JDK 26 でそのまま実行できる。

呼び出し先の宣言は、JVMS 5.4.3.3 の手順（所有型、その親クラス、親インターフェースの順）で引き直す。
javac は JLS 13.1 の通り「呼び出しを修飾する型」（受け手の静的な型）を書くので、
`sub.inherited()` の呼び出し先は宣言した型ではなく `Sub` になっているためである。

## Q4. ラムダの合成メソッドの名前が javac と違う

違う。JDK 26 の javac は入れ子のラムダの外側に先に番号を振るが、ツールは javac 21 の振り方
（後行順）に合わせている（`docs/lambda-expansion-qa.md` の Q13）。JLS は合成メソッドの名前を決めていない。

そこで、ラムダの中の呼び出しは**それを囲む本物のメソッドの呼び出しとして**照合する。javac 側は
invokedynamic の実装メソッドから、ツール側は「ラムダを生成した」C 行から親を辿る。
ラムダそのものは（囲む本物のメソッド・行）で照合する。javac がメソッド参照の一部（`super::m`、
配列の `::new`）を合成メソッドにする分は、ツールの M 行（`methodref` / `ctorref`）の場所と照合する。

## Q5. 拡張 for 文の `hasNext()` の呼び出し先が javac と違う

違ってよい、というより JLS に合わせるとこうなる。JLS 14.14.2 の変換は

```
for (I #i = Expression.iterator(); #i.hasNext(); ) { ... #i.next() ... }
```

で、`#i` の型 I は **`iterator()` の戻り値の型**である。`Countdown.iterator()` が `CountIterator` を返すなら、
`#i.hasNext()` の呼び出し先は `CountIterator.hasNext()` になる。javac は `java.util.Iterator.hasNext()` で
修飾して書くが、実行時に動くメソッドは同じである（§15.12.4.4）。ツールは JLS の型を使う。候補を
`Iterator` の全実装に広げずに済むからである。

突き合わせでは、ツールにだけある呼び出しのうち、「同じ呼び出し元・同じ行に、同じ名前・同じ引数の
JDK の型の invoke があり、呼び出し先の型がその JDK の型の部分型」であるものを INFO として出して通す。

## Q6. try-with-resources の `close()` が javac では 2 行に出る

javac は `close()` を正常終了の経路（try ブロックの閉じ括弧の行）と例外の経路（資源の行）の両方に書く。
ソースの 1 か所がバイトコードの 2 か所になる。`finally` の中の呼び出しも同じ形になる。

ツールは資源を書いた行に 1 本だけ張る。突き合わせでは、「javac が同じ（呼び出し元・呼び出し先）を
別の行にも書いていて、そちらの行はツールと一致する」ものを INFO として出して通す。

## Q7. コンストラクタのキーを javac から作るのに困ったこと

javac のバイナリのコンストラクタは、ソースに無い引数を持つ。外側のインスタンス（内部クラス）、
取り込んだ変数（ローカル・匿名クラス）、enum の `name` / `ordinal` である。`-parameters` でコンパイルすると
`MethodParameters` に印（`synthetic` / `mandated`）が付くので、それで除く。

1 つ落とし穴があった。**コンパクトな正準コンストラクタの引数も `mandated`** である（成分から暗黙に
宣言されるため）。印だけで除くと `Range#<init>()` になり、ツールの `Range#<init>(int,int)` と食い違う。
外側のインスタンスは名前が `this$0` なので、`mandated` は名前が `this$` で始まるものだけ除く。

## Q8. record や enum の暗黙のメンバが D 行に無いのは食い違いではないのか

JLS 上は食い違いである。`values()` / `valueOf(String)`（§8.9.3）、書かなかったアクセサと
`equals` / `hashCode` / `toString`（§8.10.3）は暗黙に宣言されるメンバで、javac はメソッドを作る。
ツールは本体がソースに無いので D 行を持たず、呼び出しは `[EXTERNAL] no source to follow` と出る
（`docs/static-analysis-limits.md` の 7 節に書いてある既知の近似）。辿る先が無いことは変わらないので、
今回は直さず、突き合わせでは節ごとに件数と名前を INFO として出す。

## Q9. 構文が呼ぶメソッドを辺にしていなかった（§14.14.2・§14.20.3・§14.30.2）

直した。3 つとも AST に呼び出し式が無いので、呼び出し式の visit では拾えない。

```java
try (Resource r = new Resource()) { ... }      // r.close() が呼ばれる
for (Integer i : countdown) { ... }             // countdown.iterator() / hasNext() / next()
if (o instanceof Point(int x, int y)) { ... }   // ((Point) o).x() / .y()
```

直す前は `Resource.close()`・`Countdown.iterator()`・`Point.x()` が「呼び出し元なし」
（`ENTRY_CANDIDATE`）と出ていた。影響調査で「誰も呼んでいない」と読まれる、最も悪い形の取りこぼしである。

`FactVisitor` に `EnhancedForStatement` / `TryStatement` / `RecordPattern` の visit を足し、
呼び出し先は `ImplicitCalls` が JLS の変換どおりの静的な型から引く。決めたことは次のとおり。

| 何を | どうしたか | 理由 |
|---|---|---|
| 呼び出し先が引けないとき | 何も記録しない（U 行にしない） | ソースに対応する式が無いので、「未解決」と報告しても利用者が調べようがない（暗黙の `super()` と同じ。`docs/jls-conformance-qa.md` の Q11） |
| 拡張 for 文の行 | for 文の行 | javac が行番号表に書くのと同じ |
| `close()` の行・順 | 資源の行。宣言と逆の順に、本体を読み終えた後で記録する | JLS 14.20.3.1 の実行順 |
| `_` の成分 | アクセサを呼ぶ | JLS 14.30.2 は成分の値をアクセサで取り出すと定めていて、パターンが何にでも一致するかどうかは関係ない。javac も呼ぶ |
| 配列の拡張 for 文 | 何も記録しない | 添字で回すのでメソッドを呼ばない |

文字列変換の `toString()`（§5.1.11。`"x" + obj`）は辺にしなかった。`Object.toString()` の全実装が候補になるだけで
絞れず、javac も（文字列連結を invokedynamic にするので）呼び出し命令を書かない。

## Q10. インスタンスの `main` を起動の入口にしていなかった（§12.1.4）

直した。同梱の契約表は `static main(java.lang.String[])`（public static でそのシグネチャのメソッド）だったので、
Java 25 で確定したインスタンスの main（コンパクトなソースファイルの `void main()` が典型）が
`FRAMEWORK_ENTRY` にならなかった。

契約表に `main` という行の形を足し、同梱の行をこれに置き換えた。名前が `main` で、引数が `String[]` か
無し、private でないメソッドを入口にする（static でもインスタンスでもよい）。起動器は 1 つのクラスで
`main(String[])` を `main()` より優先するが、両方を入口にする（どちらが選ばれるかは起動したときに決まる）。
インスタンスの main のために引数なしのコンストラクタがあるかまでは見ない。

従来の `static main(java.lang.String[])` の形は残してある（利用者の契約表に書かれていても意味は変わらない）。
注記の文言も、従来の `public static main(String[])` では `static main(java.lang.String[])` のままにした。

## Q11. コンパクトなコンパイル単位のクラスを読んでいなかった（§7.3）

直した。パッケージ宣言も型宣言も無いファイルのトップレベルのメソッドは、ファイル名を名前に持つ
final なクラスのメンバになる。JDT はこれを `TypeDeclaration` ではなく `ImplicitTypeDeclaration` として返すので、
visit が無いと H 行が無く、暗黙のデフォルトコンストラクタ（§8.8.9）も合成されなかった。
D 行と C 行は出ていたので呼び出し階層はほぼ出ていたが、型階層に載らないので型として扱えなかった。

## Q12. パッケージアクセスのメソッドを別パッケージのメソッドが上書きするとみなしていた（§8.4.8.1）

直した。

```java
package a; public class PackageBase { public void run() { hook(); }  void hook() { } }
package b; public class OtherPackageSub extends a.PackageBase { public void hook() { } }
```

`OtherPackageSub.hook()` は `PackageBase.hook()` にアクセスできない場所で宣言されているので上書きではなく、
`run()` の `hook()` からは動かない。CHA はシグネチャの一致だけで候補を引いていたので、候補に入っていた。

多すぎる側の近似ではあるが、**動かないことが言語仕様で決まっている**メソッドを「呼ばれうる」と出すのは
影響調査の範囲を不当に広げる。`CallGraph.search` で、呼び出し先がパッケージアクセスのときは別パッケージの
宣言を飛ばして親へ進むようにした。

ただし推移的な上書きがある。同じパッケージのサブクラスが public に広げて宣言し直せば（§8.4.8.3）、
別パッケージのサブクラスはそれを経由して元のメソッドを上書きする（`test/jls` の `Widened` / `WidenedSub`）。
これを落とすと取りこぼしになるので、別パッケージの宣言の親型に「元のパッケージで public / protected の
同じシグネチャ」があれば上書きとみなす（多すぎる側に倒す）。

修飾子は D 行からしか分からないので、jar の中のメソッドには使わない（分からないものは判定しない）。
O 行の上書き（型引数を具体化したもの）は JDT の `IMethodBinding.overrides` の判定なので、ここでは見ない。

## Q13. 見つけたが直さなかったもの

| 何か | なぜ直さないか |
|---|---|
| 呼び出しを修飾する型（§13.1） | C 行の呼び出し先は宣言した型で持つので、`Plain p; p.greet()`（`greet` は `Greeter` のデフォルトメソッド）の候補に、`Plain` の部分型でない `Greeter` の実装（`Polite.greet`）も入る。直すには C 行に受け手の静的な型の列が要り、キャッシュの形も読み手も大きく変わる。多すぎる側（安全側）の近似なので、`docs/static-analysis-limits.md` の 7 節に書いて残した |
| record・enum の暗黙のメンバの D 行（§8.9.3・§8.10.3） | Q8 |
| 文字列変換の `toString()`（§5.1.11） | Q9 |

どれも「動くメソッドを落とす」形ではない。落とす形のもの（Q9〜Q11）は全部直した。

## Q14. 期待値をどう書くか。節番号はどこに書くのか

`test/jls/expect.tsv` の 1 行が 1 テストで、列は「節・ID・検査・引数 3 つ・説明」。節は JLS SE 26 の節番号で、
出力の各行は `OK   JLS 14.14.2   foreach-iterator   拡張 for 文は Expression.iterator() を呼ぶ` の形になる。
javac との突き合わせも節（＝パッケージ）ごとに 1 行出す。

検査の種類は `call` / `nocall` / `callcount`（C 行）、`csv` / `nocsv`（call-hierarchy.csv の行と resolved-by）、
`path` / `nopath`（root 列から call-hierarchy 列にかけてのノードの並び）、`decl` / `nodecl`（D 行と修飾子）、
`type`（H 行）、`override`（O 行）、`role`（methods.csv）、`noerror`（F 行のエラー数）。

`csv` と `path` を分けたのは、コンストラクタ（とその先が無いメソッド）への辺が call-hierarchy.csv の
行としては出ず、経路の途中のノードとしてだけ現れるからである。
経路の区切りは「 > 」（前後に空白）。`<clinit>` の `>` と区別するため。

ソースのファイルごとの先頭のコメントにも節と内容を書き、`expect.tsv` の行と対応させてある。
新しい節を足すときは、`test/jls/project/src/jls/s<節>/` にソースを置き、`expect.tsv` に行を足す。
javac との突き合わせは自動で入る。

## Q15. 回帰テストの期待値はどう変わったか

`methods.csv` の `Holder.viaCollection()` の outDegree が 4 → 7 になっただけである
（`whole` / `entry` / `cachesplit` / `jarchange` の前後）。`for (Runnable job : jobs)` が
`List.iterator()` / `Iterator.hasNext()` / `Iterator.next()` を呼ぶようになったため（Q9）。
呼び出し先は JDK なので `call-hierarchy.csv` には行が出ない（`exclude.packages=java.**`）。

## Q16. なぜ `test/demo` に置かないのか

`test/ctorbody` と同じ理由である（`docs/jls-conformance-qa.md` の Q17）。`test/demo` は多くの検査が共有していて、
README の手順で javac でコンパイルして jar を作る。Java 26 でしか通らない構文（コンパクトなソースファイル・
モジュールインポート・柔軟なコンストラクタ本体）を置くと、古い JDK でその手順が落ちる。

`test/ctorbody` と違って使い捨てにせず、`test/jls/project/` にソースとして置いた。節ごとのソースを読めることが
この検査の目的の半分だからである（「JLS の何番のどんなテストか分かるように」）。

## Q17. JDK 26 はどう用意するのか

ツール本体は従来どおり JDK 25 で動かし（`//JAVA 25`）、javac と検査プログラムだけを JDK 26 で動かす。
`run.sh` は `jbangw/jbang jdk home 26` で JDK 26 を取る（無ければ jbang が取得する）。
`JCHE_JAVAC26` / `JCHE_JAVA26` で差し替えられる。CI（`.github/workflows/smoke.yml`）も同じ経路で取る。

JDK 26 の javac を使うのは `--release 26` のためで、クラスファイルを読む API（`java.lang.classfile`）は
JDK 24 以降ならどれでもよい。
