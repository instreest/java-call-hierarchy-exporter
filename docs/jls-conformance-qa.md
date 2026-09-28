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
  `MethodSelection.implementationOf(型FQN, 呼び出し先ID)` に一本化し、
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
- **その後**: インターフェース（アノテーション型を含む）に暗黙のコンストラクタを合成していたのを
  やめた（Q25。キャッシュの版を `jche-cache-v27` に上げた）
- **その後**: Q12 の「出所の追跡はキャストを全部剥がす」は、出所が R 行・J 行・ローカル変数の表・値グラフを
  通じて条件の判定にも使われるため誤りだった。値として使う経路はすべて `unwrapValue` を通し、
  値を保つ拡大は剥がす側に入れた（`docs/value-safety-qa.md` の Q2。`jche-cache-v32`）
- **その後**: Q14 の数値リテラルは、表記から読むのをやめて JDT の評価した値を使う
  （16 進・8 進の `int` は最上位ビットが立つと負。`docs/value-safety-qa.md` の Q12）。
  Q13 の `equals` は、比べる相手の静的な型と定数の型が揃うときだけ判定する（同 Q15。`jche-cache-v32`）
- **その後**: 具象型からの実装の探し方を JVM の選び方（JLS 8.4.8・9.4.1、JVMS 5.4.6）に合わせた。
  親クラスの連鎖を根まで先に見て、無ければ親インターフェースの最も特定的な宣言を採る。親型の private と
  親インターフェースの static は採らない。H 行に親クラスの連鎖（7 列目）を書き、try-with-resources の `close()`・
  拡張 for の `iterator()` の呼び出し先と、jar からの被参照（`ExternalUsageScanner`）も同じ順で引く（Q26〜Q31）
- **その後**: 親クラスから継承したメソッドが型引数を置き換えたインターフェースのメソッドを実装する形を、
  H 行の 8 列目（継承した実装）に持つ（Q33）。親クラスの連鎖に jar のクラスが挟まる実装の戻り値は使わない（Q32）
- **その後**: 暗黙の `super()` の呼び出し先を、匿名クラスは型引数を置き換えた親のメンバーで比べ、それ以外は
  決めきれなければ候補すべてに辺を張るようにした（Q35。Q10 の探す順を置き換えた）。
  この 3 項目は形式 `jche-cache-v43` で入った（Q31）。この段で直さなかったものは Q36
- **その後**: H 行の継承した実装（Q33）に、別パッケージのパッケージアクセスのメソッドを書いていたのを直した。
  実装する側に採るのは public の宣言だけ（Q37。Issue #168。形式 `jche-cache-v45`）

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

（その後）親クラスから継承したメソッドが、型引数を置き換えたインターフェースのメソッドを実装する形
（`class UserRepo extends BaseRepo implements Repo<User>` で `BaseRepo.save(User)` が実装する）は、
O 行ではなく H 行の 8 列目（継承した実装）に持つ（Q33）。

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

`MethodSelection.implementationOf` は探索の**各段（型）で両方の軸を見る**。
探す順は、親クラスの連鎖を根まで先に、次に親インターフェースの最も特定的な宣言である（Q26・Q27。
以前は親型を名前順の幅優先で混ぜて辿り、最初に見つかった実装を採っていたが、それは JVM の選び方と違った）。
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

> **その後（Q35）**: この探す順は 3 つの場面で javac と違うコンストラクタに辺を張っていた（親がジェネリックな
> 匿名クラス、最も特殊な可変長引数、見えない引数なしのコンストラクタ）。今は、匿名クラスは型引数を置き換えた
> 親のメンバーとして比べ、それ以外は「引数なしのものが public・protected ならそれだけ、そうでなければ引数なしの
> ものと可変長引数 1 つのものすべて」に辺を張る。

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

JLS 5.6 の数値昇格により（SE 13 までの 5.6.2「二項数値昇格」。SE 14 で 5.6 にまとめられた）、`char` と `int` を `==` で比べると両方 `int` に昇格してから比較される。
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
（その後キャッシュを 1 ファイルにまとめ、版は `CacheFormat.VERSION` の 1 つだけになった。
`docs/cache-unification-qa.md` の Q22）

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
`MethodSelection#implementationOfSignature` から使うことにした。

## Q23. シグネチャで引くと、別のジェネリック型の上書きに当たらないか

理屈の上では当たりうる。1 つの型が、消去すると同じシグネチャになる別々のジェネリックメソッドを
2 つとも上書きしている場合、どちらが選ばれるかは決まらない。

ただしこれは**キーの照合（`型#シグネチャ`）が元から持っている曖昧さと同じ**である。
`implementationIn` の時代から、契約表は「その型（か親）に `run()` があるか」を見るだけで、
それが `Runnable#run()` の上書きかどうかは確かめていなかった。
契約がシグネチャで名指しする以上、ここで新たに生じる曖昧さではない。

そのうえで、直す前は**確実に落ちていた**。落ちるのと、極めて稀な同名衝突で
どちらかに決まるのとでは、前者のほうが害が大きい。

> **その後（[#189](https://github.com/instreest/java-call-hierarchy-exporter/issues/189)）**: 文字列（シグネチャ・修飾子の語）で
> 判定している残りの 2 か所を、多すぎる側として受け入れた。
> - `implementationOfSignature`（契約表・リフレクション）のこの曖昧さ。契約表がシグネチャで名指しする以上避けられず、
>   選ばれるのはどちらかの上書き（落ちはしない）
> - `DataflowResolver#dispatchesVirtually`（リフレクションの `invoke` の再選択）は private・static だけを見て、パッケージアクセスを
>   見ない。別のパッケージの同じシグネチャのメソッドを上書きとみなしうるが、候補が増えるだけで落ちない。`getMethod(name)` の
>   引数の型が分からないときは名前だけで多重定義すべてを候補にするのも同じ側
>
> どちらも「呼び出しを静かに落とさない」に反しないので直さない。`docs/resolution-selection-design.md` の点検表 #10。
> `GuardCollector` の `equals` の判定（点検表 #12。打ち切りに効く）は別の Issue で直す。

## Q24. この穴はどう固定したか

`test/demo/src/fx/generic/` に `OrderPrinter implements Consumer<Order>` と
`RawPrinter implements Consumer<Object>` を置き、`GenericMain.run` から
`orders.forEach(...)` で両方に渡す。**当たる側と当たらない側を並べてある**ので、
片方だけが出る状態に戻ったらすぐ分かる。

直す前のコードで実際にこの検査が落ちることを確認してある（`CALLBACK` の行が 2 本ではなく 1 本になる）。

## Q25. インターフェースにも暗黙のコンストラクタを合成していた

`TypeContextTracker` は、明示コンストラクタが 1 つも無い型に暗黙のデフォルトコンストラクタ（`<init>()`）を
合成して D 行にする。このとき型の種類を見ておらず、**インターフェースとアノテーション型にも**
`D  p  p.Task  <init>  ...  implicit` を書いていた。

JLS 8.8.9 でデフォルトコンストラクタが暗黙に宣言されるのは**クラスだけ**である。インターフェースの本体に
コンストラクタは宣言できず（JLS 9.1.5）、インスタンスも作れない。合成した `<init>` は誰からも呼ばれない
実在しないメソッドとして、メソッドの表とグラフに残る（親クラスが無いので、Q8〜Q10 の暗黙の `super()` の辺は
張られていなかった）。

**出力 CSV は変わらない。** `<init>` は `methods.csv` に出さず（`docs/lambda-expansion-qa.md` の Q9）、
呼び出し元の無いこの宣言は `call-hierarchy.csv` の起点にもならなかったので、回帰テストの期待値には
現れていなかった。そのぶん見つけにくく、グラフの中の余計なノードとして残り続けていた。

直し方は、合成する前に `ITypeBinding.isInterface()` を見る（JDT ではアノテーション型も true を返す）。
インターフェースのフィールドは暗黙に static（JLS 9.3）で初期化は `<clinit>` で走るので、
インスタンス初期化子の複製先（`rootConstructors`）が空になっても困らない。

出力に出ないので回帰テストでは固定できない。`test/ctorbody/run.sh` の使い捨てのプロジェクトに
インターフェース（`Shape`）・アノテーション型（`Tag`）・それを実装するクラス（`Square`）を足し、
キャッシュの D 行で「前 2 つには `<init>` が無く、クラスには有る」ことを見る。
直す前のコードでこの検査が落ちることを確認してある。

D 行の中身が変わるので、キャッシュの版を `jche-cache-v27` に上げた。
上げないと、再利用したファイルのインターフェースにだけ `<init>` が残る。

---

## 実装の探し方を JVM の選び方に合わせる（形式 v43）

v42 の穴探し（`docs/cache-unification-qa.md` の「v42 の穴探し（形式 v43）」。読み手の実装の探し方は同じ文書の Q127、
暗黙の `super()` は Q98・Q99）で見つかった、実行時に動くメソッドの選び方の食い違いと、その直し。
どれも「実際に動く実装が呼び出しの先から消える」形で、全件解析でも起きていた（差分更新の問題ではない）。

## Q26. 親インターフェースの default や JDK のインターフェースの宣言が、親クラスの実装より先に選ばれていた

直した。`MethodSelection#search`（`implementationOf` / `implementationOfSignature` の中身）は、具象型から親型を
**名前順の幅優先**で混ぜて辿り、最初に見つかった本体を採っていた。

```java
public class Base { public void m() { ... } }
public class Mid extends Base { }
public interface Api { default void m() { ... } }
public class Impl extends Mid implements Api { }

Api a = new Impl(); a.m();   // 実行されるのは Base.m
```

`Impl` の親型は `[Api, Mid]`（名前順）。1 段目で `Api.m`（本体あり）に当たり、2 段上の `Base.m` まで行かない。
出力は `Api.m,RESOLVED:LOCAL_NEW` で、`Base.m` は呼び出し元の無い `ENTRY_CANDIDATE` になった。
同じ深さでも、インターフェースの名前が親クラスより先に並べば同じことが起きる
（`class OrderService extends BaseService implements Auditable`。`Auditable` < `BaseService`）。

JDK や jar のインターフェースでも同じだった。呼び出し先にしか現れないメソッド（ソースの無いもの）は、
`MethodTable` が「本体あり」として登録する（落とさない側に倒すため）。

```java
public class BaseRes { public void close() { Log.closed(); } }
public class MidRes extends BaseRes { }
public class MyRes extends MidRes implements AutoCloseable { }

try (MyRes r = new MyRes()) { }   // javac は invokevirtual MyRes.close。BaseRes.close が動く
```

`MyRes` の親型は `[java.lang.AutoCloseable, q.MidRes]`。`java.` は `jp.`・`org.`・`net.` などより先に並ぶので、
ソースの無い `AutoCloseable.close` に当たり、`Main -> BaseRes.close` の行が消えた。拡張 for の `iterator()`、
`Runnable` の `run()`（`new Thread(task).start()` の呼び戻しを含む）も同じ形で、**無関係なファイルが
`AutoCloseable#close()` を呼ぶ行を 1 本足すだけで**、別のファイルの呼び出しが消えた（そのキーが表に載るため）。

JLS 8.4.8 では、クラスは親クラスから継承した具象メソッドと同じシグネチャの default メソッドを親インターフェースから
継承しない（クラスが勝つ）。JVMS 5.4.6 の選び方も、手順 2（親クラスの連鎖）が手順 3（親インターフェース）より先である。

**今の決まり**: `search` は 2 段で探す。

1. **親クラスの連鎖**（`TypeHierarchy#classChain`）: その型から親クラスへ根まで順に、各型で上書き（O 行）と
   キーの両方を見て（Q6）、最初の本体を採る。その型より上の private は飛ばす（Q28）。各型では、その型の H 行の
   「継承した実装」も見る（Q33）
2. **親インターフェース**（`TypeHierarchy#superinterfaces`）: 連鎖に無ければ、連鎖の型が実装するインターフェース
   すべての宣言から、最も特定的な本体を採る（Q27）

これを通る段はすべて直る（段 1 の CHA・`NO_OVERRIDE`・`SINGLE_IMPL`、段 2 の `LOCAL_NEW`、段 3 の呼び戻しの契約、
段 4 の `DATAFLOW_PARAM` / `DATAFLOW_FIELD` / リフレクション、段 5 の Spring DI）。`hasOverriders` も同じ探索を
使うので、`Api.m` を部分型が上書きしている（実際には `Base.m` が動く）と分かり、`Api.m` の return の値を当てて
具象クラスを絞ることもなくなった（`default Svc create() { return new SvcA(); }` に絞って、実際に動く
`BaseF.create` と `SvcB.run` を落としていた）。

**却下した案**:

- *ソースに本体のある宣言を、経路のどこかにあれば jar の宣言より先にする*。
  `AutoCloseable` の形は直るが、`Api` の default が `Base.m` に勝つ形（どちらもソース）は直らない。
  JLS の決まりではなく近似なので採らない
- *呼び出し先の C 行の修飾子に `abstract` があれば、ソースの無いキーを「本体なし」にする*。jar の宣言を実装から
  外せるが、上と同じく default とクラスの順は直らない。また「本体あり」は CHA の候補を落とさない側の既定値として
  ほかでも使っているので、意味を変える範囲が広い
- *読み手が種別（H 行の I/A/C）から親クラスを推す*。親型がソースの型なら種別で分かるが、jar の型は種別が
  分からない。`class X implements Runnable, p.DefRun` のように jar のインターフェースが先に書かれると、それを
  親クラスと取り違えて同じ穴が開く。推すのをやめて、書き手に親クラスを書かせた（Q29）

**費用**: 連鎖に無いときは親インターフェースを最後まで辿る（以前は最初の本体で止まった）。連鎖と親インターフェースの
並びは型ごとに 1 回だけ作って覚える。

**検査**: `test/jls` の §8.4.8（`s08_04_08/ClassWins.java`。`class-wins-*`：2 段上の親クラス・同じ深さ・名前が先の
インターフェース）、§15.12.4.4（`s15_12_04_04/Dispatch.java`。`jdk-interface-*`：`Runnable` の型・実装クラスの型・
`Thread` からの呼び戻し）、§14.14.2・§14.20.3.1（`foreach-inherited-*`・`twr-inherited-*`。親クラスから継承した
`iterator()` / `close()`）。`test/pruning` の `Dtwr`・`Dfe`・`DRun`（JDK のインターフェース `Flushable`。`Runnable` に
すると他のケースのラムダの候補が変わる）・`DrRet`（戻り値）。どれも直す前の版で落ちることを確かめた。

## Q27. 子インターフェースの default が上書きした、親インターフェースの default へ行っていた

直した。

```java
interface I1 { default void m() { ... } }
interface I2 extends I1 { default void m() { ... } }
class C implements I1, I2 { }
class B2 implements I2 { }
class C2 extends B2 implements I1 { }   // I1 のほうが近い（1 段目）

I2 x = new C(); x.m();      // 実行されるのは I2.m
new C2().m();               // 同じく I2.m
```

幅優先で最初の本体を採ると、`C` は名前順で、`C2` は深さで `I1.m` に当たる。`I2 x` の呼び出し（呼び出し先そのものが
`I2#m()`）まで、それが上書きした `I1.m` へ送っていた。JDT は `new C().m()` を `I1#m()` に束縛するので、書き手の
呼び出し先をそのまま信じても直らない。

JLS 9.4.1.1 で `I2.m` は `I1.m` を上書きする。JVMS 5.4.6 の手順 3 は、親インターフェースの「最も特定的な」
（maximally-specific。JVMS 5.4.3.3）宣言のうち本体を持つものが 1 つならそれを選ぶ。

**今の決まり**: 親インターフェースの段では、連鎖の型が実装するインターフェースすべての宣言（上書きとキー。private・
static は除く）を集め、**ほかの宣言の型の真の親型で宣言したもの**を除き（`TypeHierarchy#mostSpecific`。部分型の
関係は H 行の親型で見る）、残りから本体を持つものを採る。抽象の宣言も「最も特定的」の判定には入れる
（子インターフェースが抽象で宣言し直した default は、実行時にも選ばれない）。

複数残ることがある。JLS ではコンパイルエラーになる形（関係の無い 2 つの default）か、親が jar の型で部分型の関係が
見えない形である。そのときは**ソースに本体のある宣言を jar の宣言より先**にし、その中は近い順（同じ深さは名前順）の
先頭にする。jar の宣言は本体の有無が分からず（抽象でも「本体あり」で登録される）、関係の見えない jar の宣言と
ソースの default が並ぶとき、コンパイルできるソースならソースの default が動くほうだからである
（jar のインターフェースがソースのインターフェースを継承して default を宣言し直している形だけは外れるが、そのとき
動くのはソースの無い宣言で、外れても余計な行が 1 本出るだけ）。

**却下した案**: *呼び出し先（C 行）のキーをそのまま実装にする*。JDT が上書きされた `I1#m()` に束縛するので直らない。
*書き手が呼び出し先を最も特定的な宣言に直す*。JDT のバインディングに任せる決まり（JLS を自前で近似しない）に反する。

**検査**: `test/jls` の §9.4.1（`s09_04_01/MostSpecific.java`。`most-specific-*`：菱形・近いだけの I1・
子インターフェースの型・親インターフェースの型）。

## Q28. 親クラスの private メソッドや、親インターフェースの static・private メソッドを実装にしていた

直した。

```java
class PBase { private void m() { ... } }
interface Api { default void m() { ... } }
interface Api2 extends Api { }
class Impl extends PBase implements Api2 { }

Api x = new Impl(); x.m();                           // 実行されるのは Api.m
Impl.class.getMethod("m").invoke(new Impl());       // 同じく Api.m

interface SI { static void m() { ... } }
class ImplS extends Mid implements SI { }           // Mid extends Base（Base.m を持つ）
```

private メソッドは継承されず（JLS 8.2・8.4.8）、上書きもしない。インターフェースの static メソッドは継承されない
（JLS 8.4.8・9.4.1）。JVMS 5.4.6 もどちらも選ばない。以前は `PBase.m` を `DATAFLOW_PARAM` や `REFLECTION` の
実装にし（リフレクションの経路は、見つかったのが private だと「実行時のクラスで選び直さない」として確定していた。
`docs/value-safety-qa.md` の Q22）、`SI.m` を `SINGLE_IMPL` にしていた。

**今の決まり**: 親クラスの連鎖では、**その型より上の** private を飛ばす（その型自身の宣言は飛ばさない。private の
呼び出し先そのものを引くときに要る）。親インターフェースの段では private と static を除く。

親クラスの static は飛ばさない。インスタンスメソッドと同じシグネチャの static メソッドを継承するクラスはコンパイル
できない（JLS 8.4.8.2）ので仮想呼び出しでは当たらず、当たるのはリフレクション（`Class.getMethod` は親クラスの
public な static メソッドも返す）でだけで、そこでは static を返すのが正しい。

書き手の側にも同じ穴があった。try-with-resources の `close()` と拡張 for の `iterator()` の呼び出し先を引く
`ImplicitCalls#findNoArgMethod` は、親型の private を飛ばさず、親クラスとインターフェースを混ぜた幅優先だった。

```java
class PBase { private void close() { ... } }
interface PApi extends AutoCloseable { default void close() { ... } }
class PRes extends PBase implements PApi { }

try (PRes r = new PRes()) { }    // JLS では PApi.close（PBase.close は PRes のメンバではない）
```

以前は呼び出し先を `PBase#close()`（private）にして `STATIC_BOUND:PRIVATE` で確定し、`PApi.close` を落としていた。
同じ穴は、一括解析のレーン（W3）のレビューでも別の形（型変数の資源、別のパッケージの親クラスのパッケージアクセスの
`close()`、交差型のキャスト）で見つかり、統合したあとの決まりは「型と親クラスの連なりを近い順に先に見て、
無ければインターフェースの宣言のうち最も特定的なもの。**public な宣言だけ**を見る」になった
（呼ぶのは `AutoCloseable.close()`・`Iterable.iterator()` でどちらも public なので、それを実装・継承するメンバーの
宣言も必ず public。`docs/cache-unification-qa.md` の Q110）。javac が `TwrRes.close` を呼んで親クラスの
`TwrBase.close` が動く形（Q26）でも、呼び出し先が `AutoCloseable#close()` ではなく `TwrBase#close()` になり、
javac のバイトコードとの突き合わせ（JVMS 5.4.3.3 で引き直した宣言）とも一致する。

なお、この `PRes` の形を javac でコンパイルして動かすと `IllegalAccessError` になる（JVM の解決が private の
`PBase.close` に当たる。JVMS 5.4.3.3 の手順 2 は private を除かない）。JLS の上では `PApi.close` が呼ばれる式なので、
ツールは JLS に合わせる。`test/jls` の突き合わせはこの JVM の解決をそのまま真似るので、この形は `test/jls` に置けず、
`test/pruning` の `DtwrP` に置いた。

**検査**: `test/jls` の §8.4.8（`private-super-not-member` / `-not-selected`）・§9.4.1（`static-not-inherited` /
`static-not-selected` / `static-not-selected-param`）。`test/pruning` の `DtwrP`（書き手の private の飛ばし）。
`JlsCheck` の解決も、親インターフェースの private・static を飛ばすようにした（JVMS 5.4.3.3 の手順 3・4。飛ばさないと
`new MsC3().m()` を static の `MsAaStatic.m` と取り違え、javac との突き合わせが食い違う。
[jls-conformance-test-qa.md](jls-conformance-test-qa.md) の Q20）。

## Q29. どれが親クラスかを、なぜ書き手が H 行に書くのか。何を書くのか

H 行の親型（4 列目）は、親クラスとインターフェースを区別しない並びで、読み手は並びを実行ごとに変えないよう
名前順に並べ替える（`TypeHierarchy#sortForDeterminism`）。並べ替えた後では親クラスが分からない。書き手は
並べ替える前の並び（親クラスが先頭）を書いているが、親クラスが `Object` なら先頭はインターフェースで、先頭が
jar の型なら種別も分からないので、並びから取り戻す決まりは作れない（Q26 の却下した案）。

**今の決まり**: H 行の 7 列目に**親クラスの連鎖**を書く（`TypeFact#superclasses`）。直接の親クラスから親へ、
**ソース上の型に当たるまで**（当たった型を含む）。途中の jar のクラスも並べ、`java.lang.Object` は含めない。
インターフェースと、親クラスが `Object` のクラスは空。

- ソース上の型で止めるのは、その先はその型自身の H 行が持つから。親クラスの親が変わっても子の H 行は変わらず、
  差分更新で子の H 行を書き直さなくてよい（子は変わった型の部分型として解析し直す。`test/incremental` の
  「親クラスの連鎖を変える」2 件で、差分更新 == 全件解析）
- jar のクラスを並べるのは、`class Foo extends LibBase`（jar）で `LibBase` が親に `SrcBase`（ソース）を持つ形の
  ために、`SrcBase` が親クラスの連鎖にあると分かるようにするため。並べる型は、親型を集める
  `collectSupertypes` が名前にしている型と同じ（jar の親型を辿る深さの上限も同じ 32）なので、I 行の依存は増えない
- 同じ型を 2 つのファイルが宣言していれば、綴りの小さいほうの連鎖を採る（読んだ順に依らない）

読み手は `TypeHierarchy#classChain` で、7 列目を順に並べ、最後の型がソース上の型ならその型の H 行から続ける。

**却下した案**: *親型の列（4 列目）の親クラスに印を付ける*。親型の列を読むところ（部分型の索引・CHA・差分更新の
部分型の索引）がすべて印を剥がす必要があり、変更が広い。*読み手が並べ替えをやめる*。並びは差分更新でブロックが
動くと変わるので、決定的な出力の前提（`docs/deterministic-row-order-qa.md`）が崩れる。

**費用**: H 行が 1 列増える（親クラスの無い型は空の列）。事実が変わるので形式の版を上げた（Q31）。

## Q30. 外部の jar からの被参照を、上書きされた宣言や static の宣言に結びつけていた

直した。`ExternalUsageScanner#inheritedFrom`（jar の `new C().m()` を、継承したソースの宣言に結びつける）は、
親クラスの連鎖を先に見るところまでは JVM の解決（JVMS 5.4.3.3）と同じだったが、インターフェースの段で
幅優先の最初の宣言を採り、static も採っていた。

```java
interface I1 { default void m() { ... } }
interface I2 extends I1 { default void m() { ... } }
class C implements I1, I2 { }

interface ASI { static void m() { ... } }
interface Api { default void m() { ... } }
class C3 implements ASI, Api { }

// jar の中: new C().m();  new C3().m();   実行されるのは I2.m と Api.m
```

出力は `I1.m,EXTERNAL_USAGE:INHERITED` と `ASI.m,EXTERNAL_USAGE:INHERITED` で、`I2.m`・`Api.m` を変えても
jar からの呼び出しが影響調査に出なかった。Javadoc は「JVM のメソッド解決と同じ順」と書いていた。

**今の決まり**: 親クラスの連鎖は H 行の 7 列目から組む（`TypeHierarchy#classChain`。以前は種別 C/A の親型を
親クラスとみなしていたので、jar のクラスを経由した親クラスを見落としていた）。インターフェースの段は、private・
static を除いた宣言から最も特定的なもの（`TypeHierarchy#mostSpecific`）を残し、本体を持つものを先に採る
（JVMS 5.4.3.3 の手順 3。本体を持つものが無ければ手順 4 の通りどれか 1 つを、近い順の先頭で決める）。

**検査**: `test/pruning` の「外部の jar からの被参照」（その場で jar を作る。`C`・`C2`・`C3` の 3 件）。直す前の版で
3 件とも落ちることを確かめた。

## Q31. 形式の版と、変わる出力

Q26〜Q35 のうち、H 行に列を足したもの（Q29・Q33）、try-with-resources・拡張 for の呼び出し先（C 行）を変えたもの
（Q28）、暗黙の `super()` の辺（C 行）を変えたもの（Q35）は、書き手が作る事実を変えるので、形式の版を上げた
（`jche-cache-v43`。ほかのレーンの変更と合わせて 1 回だけ上げた。`docs/cache-unification-qa.md` の Q130）。
読み手だけの変更（Q26・Q27 の探す順、Q30 の jar からの被参照、Q32 の戻り値を使う条件）は事実を変えない。
`test/cacheversion` の記録は、題材（`test/jls/project`）も変えたので `--update` した。

回帰テストの期待値（`test/demo` の各設定）は変わらなかった。`test/demo` に「親クラスとインターフェースが同じ
シグネチャを持ち、名前順で取り違える」形や、候補を決めきれない暗黙の `super()` が無いため。`test/jls` では、
`jls.s15_27.Lambdas.run` の `Runnable.run()` の CHA の候補に、新しく足した `DTask`（`Runnable` を実装）の
`DTaskBase.run` が加わる（ほかの節の期待値は変わらない）。

## Q32. 親クラスが jar のクラスだと、default の戻り値で呼び出しを絞っていた

直した（Q26〜Q30 を壊しにいったレビューで見つかった。直す前の版からあったもの）。

```java
// jar（依存 jar）
public class Holder<T> { private final T v; public Holder(T v) { this.v = v; } public T create() { return v; } }

// ソース
interface Fac { default Dao create() { return new DaoA(); } }
class Impl extends lib.Holder<Dao> implements Fac { Impl() { super(new DaoB()); } }

static void use(Fac f) { f.create().find(); }   // use(new Impl())。実行されるのは Holder.create → DaoB.find
```

出力は `Fac.create,RESOLVED:NO_OVERRIDE` と `DaoA.find,RESOLVED:DATAFLOW_FACTORY` だけで、実際に動く `DaoB.find` が
消えていた。jar のクラスのメソッドは、ソースのどこかがそれを呼び出し先にしていない限りメソッドの表に無い。実装の探索
（`MethodSelection#search`）は親クラスの連鎖の `lib.Holder` で何も見つけられずに通り過ぎ、`Fac` の default に行く。
`hasOverriders(Fac.create)` は「部分型 Impl から引いても Fac.create」なので false を返し、default の return の値
（`new DaoA()`）がそのまま `f.create()` の値になった。

**今の決まり: 部分型から見つけた実装までの親クラスの連鎖に、H 行の無いクラス（jar・JDK のクラス）が挟まれば、
その実装を「その型で動く本体」として戻り値に使わない**（`MethodSelection#passesBinaryClass`）。

- `hasOverriders` は、部分型ごとに「実装が別」か「間に jar のクラスが挟まる」なら「振り分けられうる」とする
- メソッド参照の束縛したレシーバの具象型から引いた実装（`DataflowResolver#bodyOf`）も、間に jar のクラスが挟まれば
  本体の戻り値に使わない（参照先の宣言に戻して `hasOverriders` を見る）
- 候補に並べる側（CHA・`LOCAL_NEW`・`DATAFLOW_*`・DI）は変えない。見つけた宣言は動くかもしれない（jar のクラスが
  宣言していなければ動く）ので、並べるのは多すぎる側。jar のクラスの宣言（ソースが無い）は元から出力の先が無い
- 見つけた実装の型より上にある jar のクラス（`class Base extends lib.X` の `Base.m` を `Sub` から引く）は数えない。
  上の型は下の型の宣言を上書きできない
- 親インターフェースの default（連鎖に無い型の宣言）なら、連鎖の jar のクラスをすべて数える。`enum E implements Fac`
  （連鎖に `java.lang.Enum`）や `record` も数える。`Enum`・`Record` が default と同じシグネチャのメソッドを持ちえない
  （`Object` のメソッドは default にできない。JLS 9.4.1.2）ことを JDK の型ごとに覚えるのは、規則を増やすだけなので
  やめた（戻り値を使わない側に倒れるだけ）

**却下した案**:

- *jar のクラスの宣言を H 行に書く*（書き手は JDT のバインディングで jar のクラスのメソッドを知っている）: 型の形の
  指紋と同じく「継承したものの一覧」を書くことになり、「型の形を持ち直さない」（`docs/cache-unification-qa.md` の
  Q77）に反する。jar の型のメソッドの一覧は大きい（`HttpServlet`・Spring の抽象クラス）
- *`search` が -1 を返す*（分からないので実装なし）: 候補から見つけた宣言が消える。`class Impl extends lib.JarMid`
  （`JarMid extends SrcBase`、`JarMid` は `m` を上書きしない）の `SrcBase.m` は実際に動くので、CHA から落とすと
  呼び出しを落とす

**費用**: jar のクラスを親に持つソースのクラスが default を継承する形（と enum・record が default を持つ
インターフェースを実装する形）で、default の戻り値を使った絞り込みが効かなくなる。呼び出しは落ちず、候補が増えるだけ。

**検査**: `test/pruning` の `JarHold`（jar をその場で作る。`use` の引数経由と `ref` のメソッド参照経由の両方）。

## Q33. 親クラスから継承したメソッドが、型引数を置き換えたインターフェースのメソッドを実装する形が見えなかった

直した（Q32 と同じレビューで見つかった。直す前の版からあったもの）。

```java
interface Repo<T> { void save(T t); }                    // キー Repo#save(java.lang.Object)
class BaseRepo { public void save(User u) { ... } }      // キー BaseRepo#save(p.User)。Repo を実装しない
class UserRepo extends BaseRepo implements Repo<User> { } // javac は UserRepo に save(Object) のブリッジを作る

static void use(Repo<User> r) { r.save(new User()); }    // 実行されるのは BaseRepo.save
```

出力は `Repo.save,UNEXPANDED:NO_IMPL` で、`BaseRepo.save` は呼び出し元の無いメソッドになっていた。
`Repo` に default があれば（`interface Fac<T> { default Dao create(T t) {...} }`・`class Base { public Dao create(String s) }`・
`class Impl extends Base implements Fac<String>`）、`Fac.create` に `NO_OVERRIDE` で決め、その戻り値で次の呼び出しまで
絞っていた（クラスのメソッドが勝つのに）。親クラスの側が型引数を持つ形（`class StrRepo extends GenBase<String> implements SRepo`
の `GenBase.save(T)` が `SRepo.save(String)` を実装する）も同じ。

キーの照合（`型#シグネチャ`）は消去した引数型が違うので当たらない。O 行（Q1〜Q5）は宣言ごとの「上書きしている」で、
`BaseRepo.save` は `BaseRepo` から見て何も上書きしていない（`Repo` を実装しないので）。実装の関係は **`UserRepo` から
見たときにだけ**成り立つ（JLS 8.4.8.1。`class Other extends BaseRepo implements Repo<Order>` では成り立たない）。

**今の決まり: H 行の 8 列目に、その型から見た「継承した実装」を `実装される側のキー>実装する側のキー` で書く**
（`;` 区切り、名前順。`OverrideFacts#inheritedImplementationsOf`）。

- 書き手: クラスの親インターフェース（親クラスが実装するものも含む。型引数を置き換えたもの）のメソッド
  （private・static を除く）ごとに、その型自身が subsignature を宣言していなければ、親クラスを近い順に見て最初に
  subsignature の当たる宣言（static・private を除く）を採る。キーが同じなら読み手はキーの照合で引けるので書かない。
  実装する側の型が実装される側のインターフェースを実装していれば、その宣言の O 行が同じことを言うので書かない
- subsignature の判定: JDT の `IMethodBinding.isSubsignature` は、自分を宣言した型がパラメータ化された型
  （`GenBase<String>` の `save(String)`）だと置き換える前の宣言（`save(T)`）に戻して比べるので false になる
  （`SRepo.save(String)` から見た逆向きは true）。引数の型が置き換えたあとでそろう（同じシグネチャ）ことを
  `ITypeBinding#isEqualTo` で先に見て、そろわなければ JDT の判定に任せる。型引数を持つメソッドは JDT の判定だけ
- 読み手: `MethodSelection#search` は親クラスの連鎖の各段で、宣言（キー・O 行）を見たあと、その型の H 行の継承した実装を
  見る（呼び出し先のキーで。シグネチャで引く入口では実装される側のシグネチャで）。書き手が「その型から近い順の最初」を
  選んでいるので、連鎖の順と食い違わない
- 型ごとの事実なので、同じ親クラスを継承した別の型（`IiOther extends IiMaker implements IiFac<Integer>`）には効かない

**却下した案**:

- *O 行に書く*（`BaseRepo.save(User)` が `Repo#save(Object)` を上書きする、と）: O 行の索引は宣言ごとなので、同じ
  `BaseRepo` を継承して `Repo<Order>` を実装する別の型でも `BaseRepo.save` を実装に選び、その型で実際に動く default を落とす
- *読み手で名前と引数の数だけで当てる*: JLS の subsignature を名前で近似することになり、上の型ごとの違いも見えない
- *行を分ける（新しい行の種類）*: 型ごとの事実で H 行と同じ単位なので、列で足りる。行の種類を増やすと
  `CacheFormat` の並び・読み手・`CacheDump` をそろえる手間が増える

**差分更新**: 事実は `UserRepo` のブロックにあり、`BaseRepo`・`Repo`（とその親）の宣言に依る。親の親だけ・親インターフェースの
親だけを書き換えても、変わった型の部分型が変わった型になる決まり（`docs/cache-unification-qa.md` の Q77）で
`UserRepo.java` を解析し直す。

**費用**: H 行の列が 1 つ増える。ほとんどの型では空（親クラスが実装していないインターフェースのメソッドを、キーの違う形で
継承したときだけ書く）。書き手は親インターフェースのメソッドの数×親クラスの連鎖の宣言の数を見る。

**検査**:

- `test/jls` の §8.4.8.1（`s08_04_08/InheritedImpl.java`。`inherited-impl-*` の 6 件）。あわせて `JlsCheck` の
  「javac のブリッジに対応する O 行があること」を「O 行か、ブリッジを置いた型の H 行の継承した実装にあること」に広げた。
  javac が作るブリッジ（`IiUserRepo.save(Object)` など）がそのまま書き手の事実の正解になる
  （[jls-conformance-test-qa.md](jls-conformance-test-qa.md) の Q20）
- `test/pruning` の `GiRet`（default の戻り値で絞らない）
- `test/incremental` の「継承した実装を変える」2 件（親の親が上書きをやめる／親インターフェースの親にメソッドを足す）。
  差分更新 == 全件解析と、呼び出しが動く実装に届くこと

## Q34. try-with-resources の close() の呼び出し先が親クラスの宣言に依るようになった。差分更新は大丈夫か

大丈夫。資源の型のメンバの `close()` を親クラスの連鎖から先に引く（Q28）ので、C 行の呼び出し先は
`java.lang.AutoCloseable#close()` ではなく `tw.Base#close()` のような親クラスの宣言になる。中間のクラスに `close()` を
足すと、資源を使うファイルが変わらなくても呼び出し先が `tw.Mid#close()` に変わる。資源の型（`Res`）が変わった型の
部分型になるので、`Res` を使う `Use.java` は解析し直される。`test/incremental` の「try-with-resources の close() の
宣言を中間のクラスに足す」で、差分更新 == 全件解析と、足した `Mid.close` に届くことを見る。

## Q35. 暗黙の `super()` の呼び出し先を、なぜ取り違えていたのか。今はどう決めるのか

直した（v40 より前から。全件解析でも起きる）。Q10 の決め方「(1) 呼び出し元と同じ引数の並び（匿名クラス）、
(2) 引数なし、(3) 可変長引数 1 つだけのもの」は、3 つの場面で javac と違うコンストラクタに辺を張り、
本当に呼ばれるコンストラクタの中の処理が呼び出し元の階層から落ちていた。

1. **匿名クラスの親がジェネリック**:
   ```java
   abstract class Base<T> { protected Base() { Log.noarg(); } protected Base(T t) { Log.init(); } }
   new Base<Foo>(f) { ... }
   ```
   JDT は匿名コンストラクタに、型引数を置き換えた引数（`Foo`）を与える（JLS 15.9.5.1）。(1) は親のコンストラクタを
   宣言の形（`BindingNames#toRef` は型変数を上限に消去するので `Base(Object)`）で比べていたので一致せず、(2) の
   `Base()` に辺を張っていた（`Base()` が無ければ辺そのものが無い）。`T[]`・ダイヤモンド・ジェネリックな
   コンストラクタ `<X> G(X)`・ジェネリックな外側のクラスの内部クラス `Outer<Foo>.In(T)` も同じ
2. **最も特殊な可変長引数**: `Base(Foo... f)` と `Base(Bar... b)`（`Foo extends Bar`）。javac は `Base(Foo[])` を呼ぶ
   （JLS 15.12.2.5）。(3) は最後に宣言したものを採っていたので `Base(Bar[])` になった（宣言の順を入れ替えると合う）。
   `Base(int...)` と `Base(long...)` も同じ（`int` が `long` の部分型。JLS 4.10.1）
3. **見えない引数なしのコンストラクタ**: `Base2() { }`（パッケージ private）と `public Base2(Object... o)`。別の
   パッケージの部分型からは `Base2()` が見えないので javac は `Base2(Object[])` を呼ぶ（JLS 6.6・15.12.2.1）。(2) は
   見えるかを見ずに `Base2()` を採っていた。private の `Base3()` と `protected Base3(String...)` を、同じパッケージの
   別のトップレベルのクラスが継承するときも同じ

**今の決まり（`TypeContextTracker#implicitSuperTargetsOf`。候補のすべてに辺を張る）**:

- **匿名クラス**: 親のコンストラクタを、型引数を置き換えた親の型（`Base<Foo>` の `getDeclaredMethods`）のメンバーとして、
  引数の型を消去して比べる（`Base(T)` は `Base(Foo)`）。当たったものすべて。ジェネリックなコンストラクタは置き換えても
  型変数のままで比べられないので、引数の数が同じなら候補に入れる。1 つも当たらなければ、引数の数が同じものすべて。
  辺の行き先の鍵は、これまでどおり宣言の形（`toRef`）で作る
- **それ以外**（既定のコンストラクタと、`this(...)` も `super(...)` も書かないコンストラクタ。実引数は 0 個）: 候補は
  引数なしのものと、可変長引数 1 つだけのもの。引数なしのものが public か protected なら、部分型の `super()` から
  いつでも見え（JLS 6.6.2.2）第 1 段で選ばれるので、それだけ。そうでなければ、引数なしのもの（あれば）と
  可変長引数 1 つのものすべてに辺を張る

どれが最も特殊か・見えるかは求めない。求めると、JLS 15.12.2.5 と 6.6（同じパッケージ・同じトップレベルの型）を
自前で組み直すことになる（AGENTS.md の「自前で近似しない」）。候補すべてに辺を張れば、呼ばれるものは必ず入る
（余計な辺は安全側）。候補は親の宣言（修飾子・引数の数・可変長引数か）だけで決まるので、親が変われば親の宣言の
指紋で届き、可変長引数の要素の型どうしの親子関係には依らない。あわせて、暗黙の `super()` の候補の引数の型と
呼び出し先の throws の型を、書いた `super(...)` と同じく I 行に数えるようにした（差分更新の話なので
`docs/cache-unification-qa.md` の Q98）。

余計な辺が増えるのは、引数なしのものが public・protected でなく可変長引数 1 つのものと並ぶとき、可変長引数 1 つの
ものが複数あるときだけ。ふつうのクラス（引数なしのものだけ、または public の引数なしのもの）は 1 本のまま
（Q8・Q9 の「1 本張る」は、この 2 つの形を除けば今もそのまま）。`test/demo` と `test/jls/project` の
`call-hierarchy.csv` は変わらない。

**却下した案**:

- *最も特殊なものを `isSubTypeCompatible` で選び、見えるかをパッケージ・トップレベルの型で判定する*（1 本に決める）:
  JLS を自前で近似する。javac との食い違いがあれば呼び出しを落とす側に倒れる
- *匿名クラスで当たらなければ引数なし・可変長引数へ倒す*（Q10 の (2)(3)）: JDT は匿名コンストラクタに必ず選んだ
  コンストラクタの並びを与えるので、引数の数の違うものに倒すと誤った辺にしかならない

**検査**:

- `test/jls` の §15.9.5.1（`s15_09_05/GenericSuper.java`。`anonymous-generic-*`：`T`・`T[]`・`T...`・ダイヤモンド）。
  javac 26 のバイトコードとの突き合わせも通る（匿名クラスでは 1 本に決まるので余計な辺が無い）
- `test/ctorbody` の 4（最も特殊な可変長引数・パッケージ private と private の引数なし・`int...` と `long...`・
  public の引数なしなら可変長引数へ辺を張らない・ジェネリックなコンストラクタ・ジェネリックな外側のクラスの
  内部クラス）。余計な辺を張るので javac との突き合わせ（`test/jls`）には載せられず、javac で確かめた呼び出し先が
  入っていることを見る

どちらも直す前の版で落ちることを確かめた。

## Q36. この段で直さなかったもの

- **別のパッケージの親クラスのパッケージアクセスのメソッド**: `class Impl extends a.Base implements Api`（`a.Base.m()` が
  パッケージアクセス、`Api.m()` が default）で、読み手は今も `Base.m` を選ぶ。JLS では `Base.m` は `Impl` に継承されず
  `Api.m` だが、JVMS 5.4.5 ではパッケージアクセスのメソッドが別の public なメソッドを上書きしうる。どちらにしても javac で
  コンパイルした形は実行時に `IllegalAccessError` になり、指摘にも無かったので変えていない
  （[#175](https://github.com/instreest/java-call-hierarchy-exporter/issues/175)）。シグネチャが違う形（型引数の置換で
  H 行の 8 列目に書かれるもの）は実行時に default が動くので別で、書き手を直した（Q37。Issue #168）
- **jar のクラスが private の親クラスのメソッドに対してコンパイルされた形**（Q28 の `PRes`）: 実行時は `IllegalAccessError`。
  ツールは JLS に合わせる
- **ジェネリックなコンストラクタ `<X> G(X)` と、ジェネリックな外側のクラスの内部クラスを親にする匿名クラス**（Q35）:
  匿名コンストラクタ自身のキーが javac と違う（JDT は推論した型 `Item`、javac は消去した `Object`・外側のインスタンスを
  含む並び）。呼び出しの辺はつながっているので出力には効かないが、`test/jls` の突き合わせに載せられない
- **呼び出しを `UNEXPANDED:CHA` のまま残すとき**、呼び出し先を宣言したインターフェースの default も候補に並ぶ（以前と同じ）。
  行が増えるだけで、呼び出しは落ちない

## Q37. H 行の継承した実装（8 列目）に、別パッケージのパッケージアクセスのメソッドを入れていた

[Issue #168](https://github.com/instreest/java-call-hierarchy-exporter/issues/168)。Q36 の 1 つ目（「別のパッケージの
親クラスのパッケージアクセスのメソッド」）のうち、**シグネチャが違う**形。v43（Q33）で入った書き手の穴で、直した（形式 v45）。

```java
// package a
public class BaseRepo { void save(User u) { } }          // パッケージアクセス
// package b
public interface Repo<T> { default void save(T t) { System.out.println("Repo.save"); } }
public class UserRepo extends a.BaseRepo implements Repo<a.User> { }

Repo<a.User> r = new UserRepo();
r.save(u);          // 動くのは Repo.save（javac + java で確認。ブリッジは作られず IllegalAccessError も出ない）
```

v43 は `UserRepo` の H 行 8 列目に `b.Repo#save(java.lang.Object)>a.BaseRepo#save(a.User)` を書き、`MethodSelection#search` は
8 列目の組を親インターフェースより先に返すので、呼び出しは `BaseRepo.save` に確定（`RESOLVED:LOCAL_NEW` / `DATAFLOW_PARAM` /
`SINGLE_IMPL`）していた。実際に動く `Repo.save` は出力から消え、動かない `BaseRepo.save` が確定として出る（呼び出しを落とす側）。
v42 は `Repo.save NO_OVERRIDE` で正しかった。

**原因**: `OverrideFacts#inheritedImplementationsOf` → `subsignatureIn` が static と private は除いていたが、パッケージアクセスの
メソッドを除いていなかった。JLS 8.4.8 では、パッケージアクセスのメソッドは同じパッケージのサブクラスにしか継承されない。
`BaseRepo.save` は `UserRepo` のメンバーでないので、何も実装しない。

**今の決まり: 実装する側に採るのは public の宣言だけ**（`subsignatureIn` の当たった宣言が public でなければ飛ばして親へ進む）。

- Issue の案は「パッケージアクセスのメソッドは、その型から宣言したクラスまでの連鎖がすべて同じパッケージにあるときだけ採る」
  だったが、題材を javac に通して分かったのは、**同じパッケージで継承されても、public でないメソッドが public な
  インターフェースのメソッドを実装することは JLS 8.4.8.3（弱いアクセス権限）でコンパイルできない**ということ
  （`save(PkgUser) in PkgRepoBase cannot implement save(T) in PkgSameApi; attempting to assign weaker access privileges`）。
  protected でも同じ。つまりコンパイルできるコードでは、インターフェースのメソッドを実装する継承したメソッドは public に限る。
  連鎖のパッケージを見る書き分けと public だけの規則の違いが出るのはコンパイルできないコードだけなので、簡単なほうにした
- コンパイルできないコードでは「実装なし」に倒れる（default があれば default、無ければ `NO_IMPL`）。実行時の正解が無い形なので、
  クラスのメソッドに確定させるよりは候補を残す側

**間に別パッケージのクラスが挟まる形**（`a.PkgViaMid extends b.PkgMid extends a.PkgRepoBase`）も同じ。自分が `PkgRepoBase` と
同じパッケージでも、`save(PkgUser)` は `b.PkgMid` のメンバーにならないので継承されない（JLS 8.4.8 は直接の親クラスのメンバーから
継承する）。javac もブリッジを作らず、default が動く。public だけの規則ならこの形も自然に外れる。

**読み手（`MethodSelection#search`）は変えていない**（8 列目に書かれた組をそのまま採る）。キーの同じ形（Q36 の `class Impl extends
a.Base implements Api` で `a.Base.m()` がパッケージアクセス、`Api.m()` が default）は `declarationIn` のパッケージアクセスの判定の
話で、[#175](https://github.com/instreest/java-call-hierarchy-exporter/issues/175) として別に扱う。

**検査**: `test/jls` の §8.4.8（`s08_04_08/a/PkgRepoBase.java`・`PkgViaMid.java`、`s08_04_08/b/PkgRepo.java`・`PkgUserRepo.java`・
`PkgMid.java`・`PkgMidApi.java`・`PkgInherited.java`。`pkg-inherited-impl-*` の 6 件。`csv` で default が出て `nocsv` で
`PkgRepoBase.save` が出ないこと。引数で受けた形・`new` のローカル変数で絞る形・中間のクラスを挟む形）。javac 26 との突き合わせ
（`JlsCheck`）は「javac のブリッジに対応する H 行の継承した実装があること」を見るので、ブリッジが無いこの形に H 行の組を書いても
落ちないが、`nocsv` が捕まえる。

**形式の版**: 書き手の事実（H 行 8 列目）が変わるので `jche-cache-v45`。

## Q38. jar のクラスを経由した部分型（`class MyList extends ArrayList<String>`）が、なぜ CHA の候補に入らなかったのか

[Issue #184](https://github.com/instreest/java-call-hierarchy-exporter/issues/184)。呼び出しを落とす側の不具合。

```java
public class MyList extends java.util.ArrayList<String> { @Override public int size() { return helper(); } }
java.util.List<String> l = pick(args);   // MyList か ArrayList
l.size();                                // 実際に動きうるのは MyList.size
```

`l.size()` が `List.size RESOLVED:NO_OVERRIDE [EXTERNAL]` になり、`MyList.size()` は起点の候補（`ENTRY_CANDIDATE` /
`[NOT_REACHED]`）に昇格していた。H 行の親型（3 列目）は「直接の親」と「jar の型を経由して到達するソースの親」だけで、
jar の親の親（`ArrayList` の先の `List` / `Collection`）は載らない。読み手の段 1 は宣言した型 `java.util.List` の
`transitiveSubtypes` から候補を引くので、`MyList` は部分型に数えられず、候補は jar の宣言だけになって `NO_OVERRIDE` で確定した。
同じ形で jar のインターフェースを**直接** implements した型は 3 列目に載るので候補に入る（`UNEXPANDED:CHA`）。

**今の決まり: 書き手は、親型を辿って到達した jar の型の推移的な親型の組（`jar の型>親型`。`java.lang.Object` を除く）を
H 行の 9 列目に書く**（`TypeContextTracker#collectSupertypes`・`TypeFact#binarySupertypes`）。`MyList` の H 行なら
`java.util.ArrayList>java.util.List;java.util.ArrayList>java.util.AbstractList;…` である。読み手の `TypeHierarchy` はこれを
jar の型の親型として持ち（`binarySupertypesOf`）、部分型の列挙（`transitiveSubtypes`）と親子の判定（`isSubtypeOf`）で jar の型を
通って辿る。`transitiveSubtypes("java.util.List")` が `MyList` を返し、候補は `List.size`（jar）と `MyList.size` の 2 つ
（`UNEXPANDED:CHA`）になる。`MyList m; m.size()` のように修飾する型がソースの型なら、`isSubtypeOf(MyList, java.util.List)` が
真になるので修飾する型から引き（`usableQualifier`）、`MyList.size` に確定する。

3 列目に jar の親の親を平らに足す（Issue の案）のではなく組にしたのは、Q39（jar のインターフェースどうしの親子）にも同じ
材料が要るからである。平らに足すと `LibMid` と `Top` が並ぶだけで、どちらが親かは分からない。

変えなかったもの:

- **`transitiveSubtypes` はソース上の型だけを返す。** jar の型（`ArrayList`）を候補の型に並べると、`implementationOf` が
  メソッドの表にたまたまある jar の宣言（`ArrayList#size()`。ソースのどこかが呼び出し先にしていれば載る）を候補に足し、
  同じソースでも表の中身で行が増減する。読み手の呼び出し側（`CallResolver`・`SpringBeans`・`MethodSelection#hasOverriders`）は
  もともとソースの型しか受け取っていない
- **実装を探す並び（`classChain` / `superinterfaces`）は H 行の親型の並びのまま。** `superinterfaces` に jar の親の親
  （`List` / `Collection`）まで並べると、`MethodSelection#search` が表にある jar の宣言を「最も特定的な宣言」に選び、
  `class Plain extends ArrayList<String>` の `Collection.size()` の呼び出しに `List.size` の行が増える。候補は落ちないが
  出力が表の中身で変わるので、並びは変えない
- **`usableQualifier` の「修飾する型が jar の型なら宣言した型に倒す」も変えない。** 9 列目があれば jar の型の部分型も
  漏れなく数えられるが、修飾する型が jar の型（`ArrayList<String> a; a.size()`）のとき受け手は jar の型そのものでもあり、
  その実装（jar の宣言）は `implementationOf(修飾する型, …)` では見つからない（メソッドの表に無いか、本体が分からない）。
  宣言した型から引けば宣言そのものが候補に入り、`[EXTERNAL]` の行で「jar の実装が動きうる」と分かる
  （`docs/jls-conformance-test-qa.md` の Q18）
- `java.lang.Object` は載せない（`docs/excluded-entry-promotion-qa.md` の Q5・Q8）

費用は H 行が長くなること（JDK の GUI クラスを継承した型で数十組）と、jar の型の親型が変わる（jar の差し替え）と型階層の
安全網（`docs/cache-unification-qa.md` の Q132）で全件解析になること。どちらも安全側の費用として受け入れる。
書き手の変更なので形式の版を v45 に上げた。検査は `test/pruning` の `JarList`（`List<String>` / `ArrayList<String>` 型の変数で
呼んだ `size()` の候補に `JlList.size` が入り、その先の呼び出しが階層に残る）。

`test/regression` の期待出力は 2 か所で変わった。どちらもこの形の候補が増えたもので、直す前は落ちていた呼び出しである。

- `test/demo` の `Starter.Worker extends Thread`（`run()` を上書き）。`Runnable::run`（`Main.lambdas`・`Holder.viaForEach`）の
  候補が `Starter.Job.run` と jar の `Runnable.run` の 2 つから、`Worker.run` を足した 3 つになった（`Thread` が `Runnable` を
  実装していることが 9 列目で分かる）。`Worker.run` の入次数が 1 から 6 に増え、`entry` の設定では `[NOT_REACHED]` でなくなった
- `test/maven-demo` / `test/gradle-demo` の `LogHandler extends sample.deps.AbstractHandler`（jar のクラス。jar の
  `Handler` を実装）。`Service.run` の `handler.handle(text)`（フィールドの `new LogHandler()`）が jar の `Handler.handle`
  （`[EXTERNAL]`）から `LogHandler.handle`（`RESOLVED:DATAFLOW_FIELD`）になり、その先の `Util.count` が階層に載った

## Q39. jar のインターフェースを経由した親子が見えず、なぜ別の default を選んだのか

[Issue #185](https://github.com/instreest/java-call-hierarchy-exporter/issues/185)。

```java
interface Top { default void m() { } }                          // ソース
public interface LibMid extends s.Top { default void m() { } }  // jar。Top.m を上書き
class Impl implements lib.LibMid, s.Top { }                     // ソース。Top も直接書く
Top t = new Impl(); t.m();                                      // 実際に動くのは LibMid.m
```

`Impl` の `superinterfaces` には `LibMid`（jar。H 行なし）と `Top` が並ぶ。`MethodSelection#search` の後半は
`TypeHierarchy#mostSpecific` で「ほかの型の真の親型」を除くが、その判定（`isSubtypeOf`）は H 行の親型（3 列目）しか見ず、
`LibMid` の親が `Top` であることは知りようがなかった。両方が残り、「ソースに本体のある宣言を jar の宣言より先」の近似で
`Top.m` を選んでいた（JVM は `LibMid.m`）。`Top.m` の本体の呼び出しが「呼ばれる」と出て、`Top.m` の戻り値で候補を絞る
（`test/pruning` の `DefMid`: `DmTop.create` の戻り値 `DaoA` に絞って `DaoB.find` が消える）と、呼び出しが落ちる。

**今の決まり: Q38 の 9 列目（`lib.LibMid>s.Top`）を `isSubtypeOf` が辿るので、`mostSpecific([LibMid, Top])` は `Top` を除く。**
`MethodSelection#search` は変えていない（同じ段で別の直しが入る）。jar の宣言 `LibMid.m` は、ソースのどこかがそれを
呼び出し先にしていればメソッドの表にあり、`implementationOf(Impl, Top.m)` はそれを返す（`[EXTERNAL]`）。表に無ければ
`declaring` に `LibMid` が入らず、これまでどおり `Top.m` を返す（jar のインターフェースが `m` を宣言しているかは、jar の
事実を持たない読み手には分からない）。「最も特定的な宣言が複数残ったら 1 つに決めない」（Issue の代案）は、jar の型の親子が
見えるようになれば残るのは JLS でコンパイルエラーになる形だけなので採らなかった。

検査は `test/pruning` の `DefMid`（jar の `prlib.Mid extends pr.DmTop` を挟む。`t.create()` の実装が `Mid.create` で、
`DmTop.create` を選ばず、`DaoB.find` を落とさない）。`test/jls` の javac との突き合わせは jar を使わないので、そこには置かない。

## 選択の層の穴を塞ぐ（Issue #174・#175・#177）

`docs/resolution-selection-design.md` の点検表（6 節）で位置を挙げていた、`MethodSelection#search` とその周辺の食い違いの直し。
どれも「実際に動く実装が呼び出しの先から消える」形で、全件解析でも起きる。

## Q40. 別パッケージの上書きの判定が、同じパッケージのインターフェースや jar のクラスを取り違えていた

[Issue #174](https://github.com/instreest/java-call-hierarchy-exporter/issues/174)。

**症状**: パッケージアクセスの `p.Base.work()` を、別パッケージの `q.Sub extends p.Base implements p.Worker` が
`public void work()` で宣言し直した形。`Base b = new q.Sub(); b.work()` で動くのは `Base.work`（`Sub.work` は
別のメソッド。JLS 8.4.8.1）なのに、ツールは `Sub.work` に確定して `Base.work` を落としていた。
逆に、同じパッケージの jar のクラス `a.JMid extends a.PA` が `m()` を public に宣言し直し、別パッケージの
`b.PB extends a.JMid` がそれを上書きしている形では、`PA x = new b.PB(); x.m()` で動く `PB.m` を落として
`PA.m` に確定していた。

**原因**: `overridesAcrossPackage` は「別パッケージの宣言が、そのパッケージの public / protected の中間の宣言を経由して
推移的に上書きしているか」を、親型（`directSupertypes`。クラスとインターフェースが混ざり、名前順）を幅優先で辿って
「1 つでもあれば真」で見ていた。`Worker.work()`（インターフェース。public）も数えてしまうが、インターフェースは
`Sub` と `Base` の間のクラスではない。また、途中の宣言をメソッドの表（`methods.idOf`）で探すので、ソースから呼ばれて
いない jar のメソッドは見えなかった。

**直し**: 見るのは親クラスの連鎖（`TypeHierarchy#classChain`。H 行の 7 列目）だけにし（JVMS 5.4.5 の「上書きしうる」の
推移は親クラスの連鎖の上でしか成り立たない）、呼び出し先を宣言した型に着いたら止める。連鎖の途中に H 行の無い
（jar の）クラスがあって呼び出し先のパッケージに属すなら、public に宣言し直しているかもしれないとみなして真にする
（Q32 と同じ「候補を多めに残す」側。jar の型の名前しか無いので、パッケージは「呼び出し先のパッケージの直後の名前が
大文字で始まる」で決める。入れ子の型 `a.Outer.Inner` を `a.Outer` パッケージと取り違えないため）。
読み手だけの変更なので形式の版は上げない。

**検査**: `test/pruning` の `XaBase.run -> XaBase.work`（`XaSub.work` が無いこと）と `XaPUse.run -> XaPB.m`
（jar `work/lib/xalib.jar` はソースの `xa.XaPA` に対してコンパイルし、`XaPA.class` は入れない）。
直す前の版で落ちることを確かめた。

## Q41. 継承されない static を default より先に選び、型引数の置換を挟んだ別パッケージの上書きを見落としていた

[Issue #175](https://github.com/instreest/java-call-hierarchy-exporter/issues/175)。どちらも `MethodSelection#search` の
親クラスの連鎖の段。

**症状 1**: `class Impl5 extends a.SBase implements I5` で、`a.SBase` の `static void s5()`（パッケージアクセス）と
`I5` の `default void s5()`。`I5 x = new Impl5(); x.s5()` で動くのは `I5.s5` なのに、`SBase.s5` に確定していた。
連鎖の段では、その型より上の private は飛ばすが static は残していた（「同じシグネチャの static を継承するクラスは
コンパイルできない（JLS 8.4.8.2）ので仮想呼び出しでは当たらない」）。この理屈は static が<b>継承される</b>ときのもので、
別パッケージのパッケージアクセスの static は継承されない（JLS 8.4.8）ので、クラスはコンパイルできる。

**直し 1**: 連鎖の段 i>0 では、継承されない宣言を飛ばす（`inheritedBy`）: private と、public でも protected でもなく
その型と同じパッケージでもない static。public・protected の static は残す（リフレクションの `getMethod` が返す）。
型のパッケージは H 行の 4 列目を `TypeHierarchy#packageOf` で引く。インスタンスメソッドのパッケージアクセスは
変えない（呼び出し先がパッケージアクセスなら `declarationIn` が同じパッケージに限り、public な呼び出し先に対しては
JVMS 5.4.5 の「上書きしうる」形なので残す。Q36）。

**症状 2**: `class GA<T> { void g(T) }`（パッケージアクセス）を同じパッケージの `class GM extends GA<String> { public void g(String) }`
が上書きし、別パッケージの `class GB2 extends GM { public void g(String) }` がそれを上書きしている形。
`GA<String> x = new b.GB2(); x.g("s")` で動くのは `GB2.g`（GM のブリッジ `g(Object)` が仮想で `g(String)` を呼ぶ）なのに、
`GM.g` に確定していた。`GB2.g` の O 行には `GA#g(Object)` が無い。JDT の `overrides()` は、別パッケージのパッケージアクセスの
メソッドに対して偽を返す（JLS 8.4.8.1 の推移を見ない）ためで、`search` は GM の段で O 行から `GM.g` を見つけるが、
それより下の段で「見つけた上書きと同じシグネチャを宣言しているか」を見ていなかった。

**直し 2**: 答えが連鎖の j 段目の、呼び出し先とシグネチャの違う宣言（O 行の上書き・H 行の 8 列目の継承した実装）から
来たときは、それより下の段でその宣言を上書きしうる宣言（private でも static でもなく本体を持ち、上の宣言が public か
protected か同じパッケージ）を探し、いちばん下のものを採る（`lowestOverriderOf`）。読み手で直すほうを採り、
O 行の書き手（`OverrideFacts`）には推移の場合を足さない（JDT の判定に任せる作りを保つ。形式の版も上げない）。

**却下した案**: O 行の書き手に JLS 8.4.8.1 の推移を足す。JDT の `overrides` の結果をそのまま事実にする作りが崩れ、
形式の版を上げる（全件解析）ことになる。読み手の 1 段の探索で足りる。

**検査**: `test/pruning` の `XaImpl5.run -> XaI5.s5`（`XaSBase.s5` が無いこと）と `XaGUse.run -> XaGB2.g`（`XaGM.g` が無いこと）。
直す前の版で落ちることを確かめた。

## Q42. インターフェースのダイヤモンドの super 呼び出し・jar のインターフェースの宣言し直し・record と Enum の暗黙のメソッド

[Issue #177](https://github.com/instreest/java-call-hierarchy-exporter/issues/177)。3 つとも「動かない default に確定する」形。
形式の版を `jche-cache-v45` に上げた（1 と 3 が書き手の変更）。

**1. `X.super.m()` / `super.m()` が特定性の低い default に向く。** `interface Both extends Top, Mid`（両方に `hi()` の default、
`Mid extends Top`）で `Both.super.hi()` は `Mid.hi` を呼ぶが、JDT の束縛は `Top#hi` を返すことがあり、辺は `STATIC_BOUND:SUPER` なので
`search` で選び直されず `Mid.hi` が落ちていた。`class Y extends Y0`（`Y0 implements Top, Mid`）の `super.hi()` も同じ。
直し: 書き手が `super.m()` / `X.super.m()` / `super::m` の C 行に修飾する型（JLS 13.1: `super.m()` なら囲む型の親クラス、
`X.super.m()` なら X がインターフェースならその X、クラスなら X の親クラス。`CallSiteRecorder#superQualifierOf`）を書き、
読み手（`CallResolver#superTarget`）が段 0 で `implementationOf(修飾する型, 呼び出し先)` で選び直す（JVMS 6.5 の invokespecial:
C は直接の親クラスか名指しのインターフェースで、そこから JVMS 5.4.6 の順）。修飾する型が空（宣言した型と同じ・jar の型）なら
宣言した型から引き、選べなければ呼び出し先のまま。ラベルは `STATIC_BOUND:SUPER` のまま。
`super.m()` の修飾する型は H 行の 7 列目からも分かるが、`X.super.m()` は書き手にしか分からないので、両方とも書き手が書く
（1 つの決まりにする）。

**2. jar のインターフェースがソースの default を宣言し直す。** `interface JApi extends s.Api`（jar）が `dao()` の default を
宣言し直し、`class Impl implements lib.JApi` を `Api` 型で使う形。Q32 の守り（`passesBinaryClass`）は親クラスの連鎖の H 行の無い
クラスしか数えないので、`Api.dao` の戻り値（`DaoA`）で `DaoB.find` を落としていた。直し: 実装がインターフェースの宣言なら、
その型の親インターフェース（`TypeHierarchy#superinterfaces`）に H 行の無い型があれば真にする。`java.*` / `javax.*` は利用者の
インターフェースを継承できないので除く（Spring などの jar のインターフェースを併せて implements する型は「絞らない」側に
倒れる。多すぎる側なので受け入れる）。読み手だけの変更。

**3. record の暗黙のアクセサと Enum の final メソッド。** `record R2(String name) implements Named`（`Named` に `name()` の default）
で `n.name()` が default に確定していた。暗黙のアクセサには D 行が無く、`search` がその型の段で何も見つけず default へ進むため。
直し: 書き手（`TypeContextTracker#synthesizeImplicitAccessors`）が JDT の合成したアクセサ（`isSyntheticRecordMethod`）の D 行を
`public implicit` で書く（`test/jls` の javac との突き合わせは、javac にあるメソッドの D 行があれば一致として数える）。
`enum E3 implements HasOrd` の `h.ordinal()` は `Enum.ordinal`（final）が動くが、`java.lang.Enum` のメソッドは呼ばれない限り表に無い。
読み手の `search` で、連鎖に `java.lang.Enum` があってシグネチャが `Enum` の final メソッド（`name()` / `ordinal()` /
`compareTo(java.lang.Enum)` / `getDeclaringClass()` / `describeConstable()`）なら、表にあればそれ、無ければ「分からない」（-1）を返して
default へ進まない（-1 なら `hasOverriders` が「別の本体へ振り分けられうる」になり、default の戻り値で絞らない）。
`equals` / `hashCode` はインターフェースが default にできない（JLS 9.4.1.2）ので入れない。

**却下した案**: Enum も書き手で D 行を合成する（`E3#ordinal()` のような宣言は無いので、jar の `Enum` のメソッドを D 行にすることになり、
「D 行はソースの宣言」という約束が崩れる）。record を読み手で見る（成分の名前が読み手に無い）。

**検査**: `test/pruning` の `Diamond`（`X.hi` / `Y.hi` / `Z.r` → `Mid.hi`。`Top.hi` が無いこと）、`JarIface`（jar `work/lib/xalib2.jar` の
`xj.XaJApi extends xa.XaApi`。`DaoB.find` が残ること）、`RecAcc`（`R2.name`）、`EnumOrd`（表にある `Enum.ordinal`）、
`EnumName`（表に無い `name()` の default の戻り値で打ち切らない）。

**`methods.csv` への影響**: 合成したアクセサの D 行は、明示的に書いたメソッドと同じく `methods.csv` の行になる（宣言行は
record の見出しの行）。`methods.csv` は「他から呼び出せる定義」を並べる一覧で、暗黙のアクセサは `p.x()` と呼び出せるので、
除く理由が無い（除いているのはコンストラクタ・ラムダの合成メソッド・static 初期化子・匿名クラスのメソッド）。
そのため `test/regression` の期待出力に `test/demo` の `record Point(int x, int y)` の `Point.x()` / `Point.y()` の 2 行が
増えた（whole / entry / novalues / jarchange / cacheblocks）。
