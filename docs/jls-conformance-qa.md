# Java 言語仕様との食い違いを直した件 — 実装時の QA 一覧

[Issue #154](https://github.com/instreest/java-call-hierarchy-exporter/issues/154)
（ジェネリックなメソッドのオーバーライドを照合できず、別の実装に誤確定する）、
[#155](https://github.com/instreest/java-call-hierarchy-exporter/issues/155)
（暗黙の `super()` を辺にしていない）、
[#156](https://github.com/instreest/java-call-hierarchy-exporter/issues/156)
（枝刈りの定数比較が型変換・数値昇格を無視する）、
[#157](https://github.com/instreest/java-call-hierarchy-exporter/issues/157)
（`this(...)` 委譲の判定が本体の先頭文固定）への対応で、
迷ったこと・困ったことと、その結論を Q&A の形で残す。

4 件はどれも **AST の読み取りが JLS と食い違っていて、呼び出しが静かに落ちる**という同じ形の問題で、
一度のレビューで見つかったのでまとめてある（`docs/code-review-fixes-qa.md` と同じ立て方）。

対応の要点:

- **#154**: メソッド宣言が上書きしている宣言のキーを **O 行**（`jche.cache.OverrideFact`）として残し、
  読み手（`jche.graph.OverrideIndex`）が逆引きを作る。候補引きは
  `CallGraph.implementationOf(型FQN, 呼び出し先ID)` に一本化し、
  「継承」と「型引数の置換」の 2 つの軸を同じ探索の中で見る
- **#155**: 明示的コンストラクタ呼び出しで始まらないコンストラクタから、親クラスのコンストラクタへ
  辺を合成する。合成した暗黙のデフォルトコンストラクタからも張る
- **#156**: 値を変えうるキャストを剥がさない `OriginTracker.unwrapValue` を足し、判定に使う経路は
  すべてそこを通す。char の定数値は整数系と突き合わせられるよう数値に正規化する
- **#157**: `this(...)` / `super(...)` を本体のトップレベルの文から探す
  （柔軟なコンストラクタ本体＝JEP 513 に対応）
- キャッシュの版を `jche-cache-v23` に上げた（O 行の追加と、定数値の表記の変更）
- `test/demo/src/fx/generic/` と `test/demo/src/fx/ctor/` を足し、
  `test/demo/src/fx/branch/Feature.java` にキャストと char の条件を足した。
  JEP 513 は `test/ctorbody/run.sh`（使い捨てのプロジェクトをその場で作る）で見る

---

## Q1. なぜ「消去済みのキー 1 本」で足りないと分かったのか

キーは `typeFqn#name(消去済み引数型FQN,…)` で、ID 化・CHA・出所・被参照の照合すべてに使っている。
ところが JLS 8.4.2 のオーバーライドは

> m1 のシグネチャが m2 のシグネチャと同じか、**m2 のシグネチャを消去したものと同じ**

なので、型引数を具体化した実装は親とキーが一致しない。

```java
interface Repo<T> { void save(T t); }                    // キー: Repo#save(java.lang.Object)
class UserRepo implements Repo<User> {
    public void save(User u) { … }                       // キー: UserRepo#save(g.User)
}
```

`Repo<User>` 型の変数経由で `save(...)` を呼ぶと、候補引きは `UserRepo#save(java.lang.Object)` を探して
見つけられない。出方は 3 通りあり、**どれも黙っている**。

| 状況 | 出力 | 何が起きているか |
|---|---|---|
| 実装が 1 つだけ | `UNEXPANDED:NO_IMPL` | 「ソースに本体のある実装が無い」＝嘘 |
| 抽象でない基底がある | `RESOLVED:NO_OVERRIDE` | 基底の空実装に確定し、本物の実装へ辿れない |
| 消去形と一致する実装が他にある | `RESOLVED:SINGLE_IMPL` | **実際には動かない別の実装に確定する** |

3 つ目が最も悪い。`docs/static-analysis-limits.md` の
「絞れないことより誤って絞ることの方が害が大きい」に真っ向から反する。

## Q2. `docs/inherited-impl-candidates-qa.md` で直したのと同じ話ではないのか

同じ形の穴の**別の軸**である。あのときは「継承」の軸で、
`UserDao extends AbstractDao` の `select()` が親にしか無い形を、
`CallGraph.implementationIn` が親を辿ることで直した。

今回は「型引数の置換」の軸。軸が 2 つあることを意識していなかったので、
片方を直したときにもう片方は見えていなかった。今回で 2 軸が揃ったことになる。

## Q3. 上書き関係をキャッシュに持つのか、読むときに計算するのか

**キャッシュに事実として持つ**（O 行）。理由は 2 つ。

1. 上書きかどうかの判定には**バインディング**が要る。型引数の置換・アクセス修飾子・
   static の隠蔽を JLS 8.4.8.1 のとおりに扱うのは自前ではまず間違える。
   AST を持っているのは書き手だけなので、判定できるのも書き手だけ
2. キャッシュの原則が「事実だけを持ち、判断は読み手でする」だから。
   「上書きしている」は AST とバインディングから機械的に読み取れる事実で、
   設定にも出力形式にも解決アルゴリズムにも依存しない

判定そのものは JDT の `IMethodBinding.overrides` に任せた。これは JLS 8.4.8.1 の実装なので、
こちらで組み直さない。親型は**型引数を具体化したまま**（`Repo<User>`）辿る。
先に消去してしまうと置換が失われ、判定そのものが成り立たない。

## Q4. O 行はキャッシュをどれだけ膨らませるか

**シグネチャ（`name(paramSig)`）が自分と同じ上書きは書かない。**

ここは一度間違えた。最初は「キー（`typeFqn#name(paramSig)`）が自分と同じなら書かない」にしていたが、
上書きする側とされる側は<b>必ず型が違う</b>のでキーは常に食い違い、フィルタが 1 件も弾かなかった
（`test/demo` で 30 行）。読み手が候補を引くのは「型FQN + `#` + シグネチャ」なので、
判断に使うのはシグネチャのほうである。

シグネチャで見るようにしたら 2 行になった（288 個の D 行に対して）。残った 2 行は
`fx.generic` の型引数を具体化した上書きそのもので、狙いどおりである。
`RawRepo#save(java.lang.Object)` は消去形と同じシグネチャなので書かれない（キーの照合で引ける）。

## Q5. 推移的な上書き（3 段以上）はどう扱うか

書き手が**推移的な親型すべて**に対して判定して書くので、読み手は推移閉包を取らなくてよい。

```java
interface Repo<T> { void save(T t); }
abstract class AbstractRepo<T> implements Repo<T> { }
class UserRepo extends AbstractRepo<User> { public void save(User u) { … } }
```

`UserRepo#save(g.User)` の O 行には `AbstractRepo#save(java.lang.Object)` と
`Repo#save(java.lang.Object)` の両方が入る。`Repo` から直接 1 段で引ける。

## Q6. 候補引きで「キーの照合 → 駄目なら上書き」の順にしたら間違いだった

最初はそう書いて、`test/demo` の `fx.generic` で候補が 1 件足りないことに気づいた。

```java
abstract class AbstractStore<T> { public void put(T item) { fallback(); } }   // 本体を持つ
class OrderStore extends AbstractStore<Order> { public void put(Order item) { kept(); } }
```

`AbstractStore#put(java.lang.Object)` の候補を `OrderStore` から引くとき、
先にキーの照合で親へ辿ると **`AbstractStore.put` に当たってしまい**、
「`OrderStore` は上書きしていない」と結論してしまう。

実際に動くのは「その型から親へ辿って**最初に見つかる実装**」なので、
`CallGraph.implementationOf` は 1 つの幅優先探索の**各段で両方の軸を見る**。
同じ段では上書きを先に見る。キーが同じ上書きは O 行に書かないので、
そこで当たるのは必ず「型引数を具体化した上書き」であり、
親から継承した同キーの宣言よりそちらが優先されるべきだからである。

## Q7. 候補引きの入口を 1 つにしたのはなぜか

段 1（CHA）・段 2（`LOCAL_NEW`）・段 3（契約表と拡張）・段 4（dataflow）・段 5（Spring DI）が
それぞれ `implementationIn` を呼んでいた。`docs/inherited-impl-candidates-qa.md` の Q1 と同じで、
**1 か所だけ直すと残りが静かに取りこぼす**。今回は `implementationOf` に寄せて入口を 1 つにした。

入口は結局 2 つになった。呼び出し先のキーが分かる場合の `implementationOf(型FQN, 呼び出し先ID)` と、
シグネチャしか分からない場合の `implementationOfSignature(型FQN, シグネチャ)` で、
どちらも同じ探索（`search`）を呼ぶ。キーだけで引く `implementationIn` は残っていない。
後者が要る理由は Q21。

## Q8. 暗黙の `super()` を拾わない判断はどこから来たのか

`docs/prompt-B-detailed.md` の 2.4(e) と 5.2 に「書かれていない暗黙の `super()` は拾わない」とあるが、
**理由が書かれていない**。しかも 2.4(e) は、`super(...)` を記録する理由を

> これが無いと、サブクラスからしか生成されない親クラスのコンストラクタが入次数 0 になる

と説明した直後の行でそう書いている。同じ理由がそのまま暗黙 `super()` にも当てはまる。
JLS 8.8.7 は「明示的コンストラクタ呼び出しで始まらないコンストラクタの本体は、暗黙に `super();` で始まる」
と定めていて、AST に現れないだけで**実行される呼び出しであることに変わりはない**。

`super()` を書かないほうが普通なので、実害は大きかった。

```java
class Base     { Base() { init(); }  void init() { } }
class Implicit extends Base { Implicit() { } }      // 暗黙の super()
```

直す前は `Implicit` 経由で `Base.init` に辿り着く行が 1 つも出なかった。
`Explicit extends Base { Explicit() { super(); } }` が別にあれば
そちらの経路だけ出るので、**「出ている」ことが余計に誤解を招く**状態だった。

## Q9. 暗黙の `super()` で、親が `java.lang.Object` のときも辺を張るのか

張らない。除くのは 3 つ。

| 親 | 理由 |
|---|---|
| `java.lang.Object` | 全てのクラスの親で、辿る先に何も無い。`TypeContextTracker#collectSupertypes` が H 行の親型から除くのと同じ理由 |
| `java.lang.Enum` | enum 宣言に対してコンパイラが与える親（JLS 8.9）。書き手が呼び出しを書いたわけではない |
| `java.lang.Record` | record 宣言に対して同じ（JLS 8.10） |

張ると全ての型に 1 本ずつ `[EXTERNAL] no source to follow` の行が増えるだけになる。
「絞れなかったことが分かる」ことに寄与しない行は、影響調査の邪魔にしかならない
（`docs/excluded-entry-promotion-qa.md` の「候補を大量に並べることは漏れを減らさない」と同じ判断）。

## Q10. 匿名クラスの暗黙の `super(...)` は引数なしではない

そのとおりで、ここだけ引数なし固定にすると取りこぼす。
JLS 15.9.5.1 のとおり、匿名クラスの合成コンストラクタは**選ばれた親コンストラクタと同じ引数**を取り、
それをそのまま渡す。

```java
Runnable r = new Base(1) { … };   // 合成コンストラクタは (int) を取り、Base(int) へ渡す
```

そこで探す順を (1) 呼び出し元コンストラクタと同じ引数の並び、(2) 引数なし、
(3) 可変長引数 1 つだけ（`Base(String... a)` しか無い親は `super()` がそれに解決される）とした。

この形は実際に `test/demo` にあった。`enum Color { RED("r") { … }, BLUE("b"); Color(String code) { … Registry.register(code); } }` の
`RED` は定数ごとのボディを持つので匿名サブクラス `Color$1` になり、
その合成コンストラクタが `Color(String)` を呼ぶ。直す前は **`RED` の登録経路だけが階層から抜けていた**
（`BLUE` は直接 `Color.Color` を呼ぶので出ていた）。回帰テストの期待出力に 1 行増えたのはこれである。

## Q11. 見つからなければ未解決（U 行）として残さないのか

残さない。ソースに対応する文が無い合成した呼び出しなので、
「未解決」と報告されても利用者が調べようがない。未解決の件数はクラスパス不足を知らせるための数なので、
そこに実体の無い失敗を混ぜない（`CreationReference` で `int[]::new` を数えないのと同じ判断）。

## Q12. キャストを剥がすのをやめると、出所の追跡まで効かなくなるのでは

用途で分けた。

| 使う場面 | 関数 | 剥がすか |
|---|---|---|
| どのメソッドが動くかを追う（`originOf`） | `OriginTracker.unwrap` | 剥がす |
| 値の一致を判定する（ガード・定数） | `OriginTracker.unwrapValue` | 値を変えうるキャストに当たったら**判定しない**（null） |

参照型どうしのキャスト（JLS 5.5）は同じインスタンスを指し続けるので、前者は今までどおりでよい。
`(OrderDaoImpl) factory.get()` の出所は `factory.get()` のままで正しい。

一方、プリミティブの変換（JLS 5.1.2 拡大 / 5.1.3 縮小）とボックス化・非ボックス化（5.1.7 / 5.1.8）は
**値そのものを変える**。

```java
void run(int mode) { if ((byte) mode == 44) target(); }
run(300);          // (byte)300 == 44 は真なので target() は呼ばれる
```

剥がすと「300 と 44 の比較」になり、`[UNREACHABLE]` と書いて**その先の階層をまるごと落とす**。
型が取れないときも「変わりうる」に倒す。

## Q13. 判定に使う式が全部そこを通ることは、どう担保したのか

`GuardCollector#evaluableOriginOf` を唯一の入口にした。ここは
「読み手が経路ごとに値を求められる出所か」を返す関数で、条件式・比較の両辺・`equals` の
レシーバと引数・switch の選択子がすべてここを通る。先頭で `unwrapValue` を呼び、
null なら出所を返さない。

個々の呼び出し側で剥がし方を選ぶ作りにすると、新しい条件の形を足したときに
片方だけ古いままになる。

## Q14. char の定数値を数値にすると、注記が読みにくくならないか

なる（`case 'a'` の値が `97` と出る）。それでも数値にした。

JLS 5.6.2 の二項数値昇格により、`char` と `int` を `==` で比べると両方 `int` に昇格してから比較される。
`'A' == 65` は真である。値を文字のまま持つと、この 2 つが別の値に見えて
「条件が成立しない」と誤判定してしまう。

```java
void run(char c) { if (c == 65) target(); }
run('A');   // 実際には呼ばれる
```

`byte` / `short` / `int` / `long` と同じ 10 進表記に揃えることで、整数系どうしはどの組み合わせでも
一致を判定できる。注記に出す条件式のテキスト（`'c == 65'`）はソースのままなので、
値だけが数値で出る形になる。読みにくさより、誤って階層を落とさないことを採る。

浮動小数は逆に**拾わない**ことにした。`1` と `1.0` と `1.0f` は同じ値だが表記が違い、
文字列の一致では判定できない。`NumberLiteral` 側では元から除いていたが、
`static final double` の定数（`IVariableBinding.getConstantValue()` が `Double` を返す）が
素通りしていたので、そちらも塞いだ。

## Q15. 文字列の `==` を判定に使ってよいのか

よい。ただし**理由がどこにも書かれていなかった**ので書き足した。

`GuardCollector#addComparison` の javadoc には
「参照型どうしの `==` は同一性の比較なので……プリミティブと列挙型に限る」とあったが、
実装（`comparableByValue`）は `java.lang.String` を通していて、`docs/branch-pruning.md` にも
「プリミティブ・列挙型・文字列の `==` / `!=`」と書いてある。**javadoc だけが古かった**。

通してよい理由は、ここで値を畳めるのが**コンパイル時定数だけ**だからである。
コンパイル時定数の文字列はインターンされて同じインスタンスになる（JLS 3.10.5）ので、
畳める場合に限り `==` は値の比較と一致する。
逆に言えば、畳める出所（`L` / `A` / `M`）を広げるときはこの前提が崩れないか確かめる必要がある。
その注意書きごと javadoc に書いた。

## Q16. `this(...)` の委譲を先頭文で見てはいけないのはなぜか

Java 25 で確定した柔軟なコンストラクタ本体（JEP 513）により、
`this(...)` / `super(...)` の**前に文を書ける**ようになった（JLS 8.8.7）。

```java
Box() {
    int v = check(1);   // プロローグ
    this(v);
}
```

`Config#buildCompilerOptions` は `JavaCore.latestSupportedJavaVersion()` を既定にするので、
この構文は**既定で受理される**。先頭文だけを見ると「委譲していない」と取り違える。

取り違えると 2 つのことが起きる。

1. インスタンス初期化子の呼び出しが委譲側にも複製され、二重に数えられる
   （JLS 8.8.7.1 のとおり、委譲するコンストラクタは初期化子を実行しない）
2. D 行に `delegating` が付かないため、`jche.graph.FieldFacts` の条件 (e)
   「委譲コンストラクタを持つ型ではコンストラクタ引数由来のフィールド出所を採らない」という
   安全弁が効かなくなる

2 のほうが重い。**誤って 1 つに絞る**経路が開くからである。

明示的コンストラクタ呼び出しは本体に高々 1 つしか書けず、入れ子のブロックの中には書けないので、
トップレベルの文を順に見て最初に見つかったもので確定できる。

## Q17. JEP 513 の検査を `test/demo` に置かなかったのはなぜか

`test/demo` は回帰テスト以外に `test/incremental` / `test/dataflow` / `test/conditions` /
`test/server` / GitHub Actions の検査も共有していて、README.md の手順で **javac でコンパイルして
jar を作る**（`extjars/demo-app.jar`）。Java 25 でしか書けない構文を置くと、
古い JDK でその手順が落ちるうえ、将来 `source.level` を古い版に固定したケースを足したときに
静かに壊れる。

そこで `test/ctorbody/run.sh` を足し、使い捨てのプロジェクトをその場で 2 つ作って
（従来形とプロローグ付き）、**同じ意味の 2 つの書き方が同じ呼び出し階層になること**を見ることにした。

突き合わせは行の集合で行う。行の並びは呼び出し箇所のソース上の位置で決まり、
プロローグ形は `this(...)` が 1 行下がるので、**並びだけは正しく違う**。
`caller` 列の行番号も同じ理由で違うので比較から外している。

## Q18. 回帰テストの期待値はどう変わったか

`test/demo` に事実を足したので `whole` / `entry` / `cachesplit` / `jarchange` が変わった。
行番号の移動と入次数・出次数の増減を除くと、**増えた行だけで、消えた行は無い**。

| 増えた行 | どの修正か |
|---|---|
| `fx.generic.*` の 4 行（`Repo#save` と `AbstractStore#put` がそれぞれ候補 2 件） | #154 |
| `fx.ctor.*` の 3 行（3 つの書き方のどれからも `CtorBase.prepare` へ辿る） | #155 |
| `Color.<clinit> → Color$1.Color$1 → Color.Color → Registry.register` | #155（Q10 の enum 定数ボディ） |
| `Feature.narrowed` / `Feature.code` の各 2 行（打ち切られなくなった） | #156 |

`maven` / `mavenmulti` / `gradle` / `plugin` は変わっていない。
これらはジェネリクスも暗黙 `super()` も踏まないソースなので、変わらないほうが正しい。

## Q19. キャッシュの版を上げる必要はあったか

あった。2 つの理由が重なっている。

1. O 行という**新しい事実**が増えた。古いキャッシュには入っていないので、
   再利用すると「上書き関係が無い」と見えて #154 が直らないファイルが混ざる
2. 定数値の**表記が変わった**（char が数値に、浮動小数が落ちる）。
   `Guard` のアトムに焼き込まれるので、古い行と新しい行が同じ CSV に混ざると
   判定が食い違う（`docs/nls-qa.md` の Q7 と同じ形）

`jche-cache-v22` → `v23`。dataflow 側の版（`DATAFLOW_VERSION`）は、
2 つが常に対で書かれ対でしか再利用されない（`docs/cache-split-qa.md`）ので据え置いた。

## Q20. 性能への影響は

O 行を作るために、メソッド宣言ごとに推移的な親型を辿る。絞り込みを 3 段入れてある。

- コンストラクタ・`static`・`private` は上書きされない（JLS 8.4.8.1）ので、そもそも辿らない
- 親型のメソッドのうち、**名前と引数の数が一致するもの**だけを `overrides` に掛ける
- 読み手側は、上書き関係が 1 件も無い呼び出し先では従来どおりキーの照合だけで済ませる
  （`OverrideIndex#overridersOf` が null を返す経路）

`test/demo`（91 ファイル）ではフェーズ 1 の時間に測れる差は出なかった。
大規模プロジェクトでの実測は `docs/ast-analysis-performance-qa.md` の「計測のしかた」に従うこと。

## Q21. 契約表とリフレクションだけ `implementationIn` のままにしたら、同じ穴が残っていた

残っていた。PR の本文を差分と突き合わせて点検したときに見つかったもので、Q7 で
「呼び出し先の ID を持たない別の引き方だから」と書いて片付けたのが誤りだった。
ID を持たないことと、上書き関係を見なくてよいことは別の話である。

同梱の JDK の契約表は**消去済みのシグネチャ**を書く。

```
java.lang.Iterable#forEach(java.util.function.Consumer) -> a0 : accept(java.lang.Object)
java.util.List#sort(java.util.Comparator)               -> a0 : compare(java.lang.Object,java.lang.Object)
```

そこに型引数を具体化した実装を渡すと、#154 とまったく同じ取りこぼしになる。

```java
class OrderPrinter implements Consumer<Order> { public void accept(Order o) { … } }
orders.forEach(new OrderPrinter());   // RESOLVED:CALLBACK の行が 1 本も出なかった
```

`Consumer<Object>` の実装なら消去形と一致するので当たる。**当たる実装と当たらない実装が
混ざる**ので、出力を見ても気づけない。

## Q22. 契約表の側は、なぜキーではなくシグネチャで引くのか

**契約に所有型が書かれていないから**である。これは書き方の都合ではなく、契約の仕組みそのもの。

```
java.lang.Thread#start() -> c* : run()
```

呼び戻される `run()` を宣言している型は `java.lang.Runnable` だが、この行のどこにも現れない。
`-> a0` の形なら呼び出し先の引数型から導けるが、`-> r` と `-> cN` では導けない。
契約はもともと「呼び戻されるメソッドのシグネチャ」で名指しする仕組みで、
リフレクション（`Method.invoke` の実引数から名前と引数型を組み立てる）も同じである。

そこで `OverrideIndex` にシグネチャ引きの索引を足し、
`CallGraph#implementationOfSignature` から使うことにした。

## Q23. シグネチャで引くと、別のジェネリック型の上書きに当たらないか

理屈の上では当たりうる。1 つの型が、消去すると同じシグネチャになる別々のジェネリックメソッドを
2 つとも上書きしている場合、どちらが選ばれるかは決まらない。

ただしこれは**キーの照合（`型#シグネチャ`）が元から持っている曖昧さと同じ**である。
`implementationIn` の時代から、契約表は「その型（か親）に `run()` があるか」を見るだけで、
それが `Runnable#run()` の上書きかどうかは確かめていなかった。
契約がシグネチャで名指しする以上、ここで新たに生じる曖昧さではない。

そのうえで、直す前は**確実に落ちていた**。落ちるのと、極めて稀な同名衝突で
どちらかに決まるのとでは、前者のほうが害が大きい。

## Q24. この穴はどう固定したか

`test/demo/src/fx/generic/` に `OrderPrinter implements Consumer<Order>` と
`RawPrinter implements Consumer<Object>` を置き、`GenericMain.run` から
`orders.forEach(...)` で両方に渡す。**当たる側と当たらない側を並べてある**ので、
片方だけが出る状態に戻ったらすぐ分かる。

直す前のコードで実際にこの検査が落ちることを確認してある（`CALLBACK` の行が 2 本ではなく 1 本になる）。

---

## 追記: try-with-resources の暗黙の `close()`（JLS 14.20.3）

`var` の扱いが JLS どおりかを確かめたときに、`var` とは別に見つかった穴。
暗黙の `super()`（Q8）と同じく、**AST に現れないが実行される呼び出し**を辺にしていなかった。

対応の要点:

- `FactVisitor#endVisit(TryStatement)` で、リソースごとに `close()` の呼び出し箇所（C 行・U 行）を記録する。
  宣言と逆の順に積み、行はリソースを書いた行にする
- 呼び出し先はリソースの静的型から引いた `close()` の宣言（`BindingNames#closeMethodOf`）。
  レシーバはリソースの変数なので、`new` した型・ファクトリの戻り値・フィールドの出所で通常どおり絞れる
- 同じ版で `var` の使い方の誤りを構文エラーから外したこと（`docs/syntax-error-report-qa.md` の Q7）と合わせて、
  キャッシュの版を `jche-cache-v27` に上げた
- `test/demo/src/fx/resource/` を足した

## Q25. 何が起きていたのか

`try (Conn c = pool.borrow()) { … }` の `c.close()` は、コンパイラが本体の後ろに足す呼び出しで
（JLS 14.20.3.1）、ソースには書かれていない。辺にしていなかったので、

```java
class PooledConn implements Conn { public void close() { ConnPool.release(); } }   // 返却
class TxConn     implements Conn { public void close() { ConnPool.commit();  } }   // コミット
```

のような `close()` の実装は**呼び出し元が 0 件**になり、`methods.csv` では入口の候補
（`ENTRY_CANDIDATE`）に並んでいた。接続の返却やコミットは影響調査でまさに追いたい処理なので、
「呼ばれていない」と見えるのは害が大きい。

`var` とは関係なく、型を明示したリソース（`try (Res r = …)`）でも同じように落ちていた。

## Q26. 呼び出し先はどの `close()` にするのか

**リソースの静的型から親へ辿って最初に見つかる `close()` の宣言**である（`BindingNames#closeMethodOf`）。
JLS 14.20.3.1 の変換は `#resource.close()` という普通のメソッド呼び出しなので、
ソースに `r.close()` と書いたときに JDT が選ぶのと同じものを選べばよい。
親クラスを先に、インターフェースを後に見る（`supertypesOf` の順）。

実際に動く実装を選ぶのは、ほかの呼び出しと同じく読み手（出所と CHA）に任せる。
ここで具象型まで決めてしまうと、書き手と読み手に同じ判断が 2 つできる。

型変数（`<T extends AutoCloseable> … try (t)`）・キャプチャ・交差型（`var r = f ? a : b` の推論結果）は
上限の各成分から探す。見つからなければバインディング無しとして U 行（`BINDING_FAILED`）に落とし、
黙って消さない。

## Q27. レシーバは何として扱うのか

**リソースの変数**（`try (Res r = …)` の `r`、Java 9 以降の `try (r)` の `r`、`try (this.field)` の `field`）。
通常の `r.close()` とまったく同じ扱いになるので、既存の絞り込みがそのまま効く。

| 書き方 | 結果 |
|---|---|
| `try (var c = new PooledConn())` | `PooledConn.close` に確定（静的型がもともと具象） |
| `try (Conn a = new PooledConn())` | `LOCAL_NEW` で確定 |
| `try (Conn b = ConnPool.open())` | `DATAFLOW_FACTORY` で確定 |
| `try (this.field)`（final フィールド） | `DATAFLOW_FIELD` で確定 |
| `try (c)`（引数） | `UNEXPANDED:CHA`（候補を並べる。外から渡されるので絞れない） |

レシーバの識別キー（`recvKey`）も通常の呼び出しと同じ作り方なので、拡張
（`TypeCandidateProvider`）もこの呼び出しに証拠を結び付けられる。

## Q28. 行と並びはどうしたか

行は**リソースを書いた行**にした。ソースに対応する文が無いので、利用者が
「どこで閉じられるのか」を探すなら、そのリソースの宣言を見るのが最も近い。

並びは実行の順に合わせた。`close()` は本体を抜けたあとに、**宣言と逆の順**で呼ばれる（JLS 14.20.3.1）。
`endVisit` で後ろのリソースから積むので、`call-hierarchy.csv` でも本体の呼び出しより後ろに、
`try (a; b)` なら `b.close` → `a.close` の順で並ぶ。行番号だけを見ると前後しているように見えるが、
行の並びはキャッシュの C / U 行の順（呼び出し箇所を記録した順）で決まる（`docs/deterministic-row-order-qa.md`）ので決定的である。

条件（guard）はリソースの位置から数えるので、`try` を囲む分岐だけが効く。
`close()` はリソースが `null` のときだけ呼ばれないが、これは実行時の値の話なので条件にしない
（Q9 の暗黙 `super()` と同じく「必ず実行される」側に倒す）。

## Q29. 回帰テストの期待値はどう変わったか

`test/demo/src/fx/resource/` を足したぶん、`whole` / `entry` / `cachesplit` / `jarchange` の
期待値に行が**増えただけ**で、消えた行も変わった行も無い。`test/demo` の既存のソースには
try-with-resources が無いので、既存の行が変わらないのは正しい。

`ResourceMain` の 3 つの書き方（`var` で受けた `new`・2 つ並べたリソース・既存の変数）が、
`close()` の実装の中の `ConnPool.release` / `ConnPool.commit` まで辿れることを見ている。
