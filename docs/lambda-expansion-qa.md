# ラムダ式・メソッド参照の展開 — Q&A

Issue [#127](https://github.com/instreest/java-call-hierarchy-exporter/issues/127)。
ラムダ式の本体を合成メソッドとして持ち、関数型インターフェース経由の呼び出しを
その本体まで辿れるようにした判断を残す。
関連: [note-tags-qa.md](note-tags-qa.md)（`[UNEXPANDED:LAMBDA]` の注記と、当時できなかった理由）、
[dataflow-facts-qa.md](dataflow-facts-qa.md)（値の追跡の枠組み）。

## 結論

- ラムダ式の本体は、javac に似せた名前（`lambda$囲みメソッド名$通し番号`）の**合成メソッド**にする。
  D 行（修飾子に `lambda`）を足し、本体の中の呼び出しはその合成メソッドに計上する。
  `methods.csv` には出さない（Q9）
- 囲みメソッドからは「ラムダを生成した」辺を1本必ず張る。実行箇所を特定できなくても
  本体の中の呼び出しが階層から落ちないようにするため
- 値の種別に `Z`（{@code Origin.FUNCTIONAL}。実装しているメソッドのキー）と
  `E`（{@code Origin.CAPTURED}。ラムダが捕捉した囲みメソッドの引数）を足した
- 実行箇所を特定できたら `[RESOLVED:DATAFLOW_LAMBDA]`。できなければ従来どおり `[UNEXPANDED:LAMBDA]`
- キャッシュは analysis v21 / dataflow v5
- **その後の修正**: 捕捉した引数（`E`）は、ラムダを生成したメソッドの段でだけ当てる（Q10）。
  M 行は SAM が上書きしている親インターフェースの宣言の鍵でも書く（Q11。analysis v24）。
  メソッド参照の参照先が仮想メソッドなら、束縛したレシーバの具象型か上書き候補で実装まで繋ぐ（Q12。dataflow v7）。
  合成メソッドの番号は javac 21 と同じ後行順にし、enum 定数の引数の中は `lambda$static$N`（Q13。analysis v25）。
  式本体のラムダにも R 行を書き、`s.get()` の戻り値をラムダの return から追う（Q14）。
  M 行は、上書きの関係に無い2つの親から継承した同じ抽象メソッドの鍵でも書く（Q15。analysis v26）。
  呼び戻しの契約に渡したメソッド参照も、参照先が仮想メソッドなら実装まで繋ぐ（Q16）。
  インターフェースのフィールドの中のラムダは `lambda$static$N`（Q17。analysis v26）。
  型名で書いたメソッド参照の注記を `type name (unbound method reference)` にし、
  methods.csv の `unresolvedCause` の判定順を階層の注記と揃えた（Q18）

### Q1. なぜ合成メソッドにしたのか。囲みメソッドに計上したままではだめか

`r.run()` の行き先になるノードが要るため。ラムダ本体を囲みメソッドの一部として扱っている限り、
「この呼び出しで動くのはこのラムダだ」と言っても指す先が無い。

副次的な効果として、ラムダの中の `return` がラムダ自身の戻り値として扱えるようになった。
以前は囲みメソッドの `return` と混ざらないよう、ラムダの中の `return` は捨てていた。

### Q2. 生成の辺（囲みメソッド → 合成メソッド）を残したのはなぜか

**呼び出しを静かに落とさない**ため。これを張らないと、実行箇所を特定できないラムダ
（`forEach` に渡す形、フィールドのコレクションに詰める形）の本体が、起点から到達できなくなる。
以前はラムダの中の呼び出しが囲みメソッドの行として出ていたので、これは**退行**になる。

生成の辺は事実としても正しい。ラムダはその行で作られており、メソッド参照（`this::helper`）を
以前から「囲みメソッドからの呼び出し」として記録していたのと同じ扱いになる。

代償は、実行箇所を特定できた場合に本体が2回（生成の辺の先と、実行箇所の先）出ること。
どちらも経路としては本当に存在するので、重複ではなく別の経路として扱う。

### Q3. 捕捉した変数（クロージャ）はどう扱うのか

ラムダが囲みメソッドの**引数**を捕捉している場合、合成メソッドから見るとそれは自分の引数ではない。
`A:`（引数）のまま持ち込むと、合成メソッド自身の引数を誤って当ててしまう。

そこで `E:`（{@code Origin.CAPTURED}）という別の種別にし、読み手はラムダの合成メソッドへ
降りるとき、**今の段がそのラムダを生成したメソッドである場合だけ**、その段の引数を
「捕捉した値」として渡す（Q10）。生成の辺の先と、生成したメソッドの中で `r.run()` した形が
これに当たる。

これを入れないと、合成メソッドに移したことで従来解決できていた
`DATAFLOW_PARAM` が解けなくなる（実際に回帰テストで検出した）。

匿名クラスは対象外のまま。匿名クラスは生成箇所と実行箇所が経路として繋がらないので、
捕捉した引数を持ち込む足場が無い（{@code OriginTracker.frameIndependent} のコメント参照）。

### Q4. 名前を `lambda$囲みメソッド名$通し番号` にしたのはなぜか

javac が付ける名前と同じ形だから。実行時のスタックトレースに現れる名前と同じ形なので、
CSV とログを突き合わせやすい。ただし番号の振り方は javac の版で変わるので、番号まで一致するとは限らない（Q13）。
通し番号は**型ごと**に、javac 21 と同じく本体を読み終えた順（後行順）に振る
（オーバーロードで囲みメソッド名が同じでも重ならない）。

名前は {@code LambdaNames} がファイル単位で**先に**配る。名前を決める場所が
「本体の先読み（{@code OriginTracker.scanOrigins}）」と「本走査（{@code FactVisitor}）」の
2 か所あり、見つけた順で番号を振ると食い違うため。先に配れば、
同じソースからは必ず同じ名前になる（差分更新でも変わらない）。

### Q5. メソッド参照はどう解決するのか

参照先のメソッドそのものを指す。`Runnable r = this::helper;` なら `Z:...#helper()` で、
合成メソッドは要らない（`helper` は実在するメソッド）。
`r.run()` はそのまま `helper` に繋がる。

参照先が仮想メソッド（`dao::describe` の `Dao#describe()`）なら、`Z:` が指すのはコンパイル時宣言で、
実際に動くのはレシーバの実行時クラスの実装。読み手はレシーバの具象型が分かればその実装に、
分からなければ上書き候補に繋ぐ（Q12）。

### Q6. どこまで追えるのか

追えるのは、ラムダが**値として**呼び出し箇所まで流れてくる形。

| 形 | 追える | 仕組み |
|---|---|---|
| ローカル変数に入れてその場で呼ぶ | ○ | 変数の出所がそのまま `Z:` |
| 引数で渡した先で呼ぶ | ○ | 経路の引数環境に `Z:` を載せる（`DATAFLOW_PARAM` と同じ枠組み） |
| フィールドに保持して呼ぶ | ○ | 出所が1つに定まるフィールドの表（`FieldFacts`）に `Z:` が載る |
| ローカルのコレクションに詰めて拡張for文で回す | ○ | 「詰めた要素の出所」を覚え、ループ変数に当てる（その場で空の `new` をして、足すメソッドと拡張 for にしか使わないコレクションだけ。`docs/value-safety-qa.md` の Q20） |
| レシーバを束縛したメソッド参照（`dao::describe`） | ○ | `Z:` にレシーバの出所（`\|r=`）を付け、その具象型での実装を引く（Q12） |
| 型名で書いたメソッド参照（`Dao::describe`） | △ | レシーバは呼び出し時の第1引数で追っていない。上書き候補（CHA）を全部出す。参照先の引数には、呼び出しの実引数を 1 つずらして当てる（`docs/value-safety-qa.md` の Q21） |
| ラムダの戻り値に対する呼び出し（`s.get().describe()`） | ○ | `s.get()` のレシーバがラムダなら、その本体の `return` を戻り値の出所にする（Q14） |
| `list.forEach(Runnable::run)` | × | `forEach` の中は jar なのでソースが無く、辿れない |
| フィールドのコレクション、詰める場所と回す場所が別メソッド | × | 要素の出所はメソッドの中でしか追っていない |
| 同じ変数に複数のラムダが入りうる | × | どれが実行されるか決められないので U（不明）に倒す |

追えない形は `[UNEXPANDED:LAMBDA]` のまま残り、本体は生成の辺の先に出る。
**絞れないことより誤って絞ることの方が害が大きい**という方針は変えていない。

### Q7. コレクションの要素を追うのに、なぜ `add` だけを見るのか

呼び出し名から「要素を足した」と言い切れる形に限っているため。
`add` / `addFirst` / `addLast` / `offer` / `push` / `set` / `put` の、引数が1つか2つの形だけを見る
（2つなら末尾が要素）。レシーバがローカル変数の場合だけで、フィールドや
他のメソッドへ渡してから詰める形は追わない。

（あとから改めた）`add` だけを見ても、見えない経路で入った要素（コンストラクタの実引数・`addAll`・渡した先・別名・
再代入・引数のコレクション・`listIterator().add`・`list::add`）を落とさないよう、要素の出所を使うのは
「この本体で空の `new` をした `java.util` のローカル変数で、再代入せず、足すメソッドのレシーバと拡張 for と
要素を足さない問い合わせにしか使わない」コレクションだけにした（`docs/value-safety-qa.md` の Q20）。

コレクションの中身を厳密に追うにはコンテナの別名解析が要り、コストに見合わない。
「その場で詰めてその場で回す」形が実務で最も多く、そこだけを安全に拾う。

### Q8. 出力はどれだけ変わるのか

`test/demo` では、ラムダ本体の行が囲みメソッドの下から合成メソッドの下へ1段深くなった。
行が消えたものは無い。

`methods.csv` の `outDegree` は、ラムダを持つメソッドでは下がる
（本体の呼び出しが合成メソッドに移るため）。合成メソッド自身は一覧に出さない（Q9）。

### Q9. 合成メソッドを `methods.csv` に出さないのはなぜか

`methods.csv` の基準を「他から呼び出せる定義」に置いたから（アクセス修飾子の話ではなく、
定義として呼び出せる形かどうか）。出す・出さないは次のとおり。

| 種類 | methods.csv | call-hierarchy.csv | 理由 |
|---|---|---|---|
| 通常のメソッド | 出す | 出る | — |
| 内部クラス・static なネストクラス・ローカルクラスのメソッド | 出す | 出る | 名前を持つ定義 |
| インターフェースの抽象メソッド・default メソッド | 出す | 出る | 定義（`hasBody` で区別） |
| 匿名クラスのメソッド（`Outer$1`） | 出さない | 出る | その場で親の定義を上書きした処理内容で、他から呼び出す定義ではない |
| ラムダの合成メソッド（`lambda$…`） | 出さない | 出る | 名前はこちらが付けたもので、呼び出せるメソッドではない |
| static 初期化子（`<clinit>`） | 出さない | 出る | 呼び出せるメソッドではない |
| コンストラクタ（`<init>`） | 出さない | 出ない（経路には積む） | 従来どおり |
| jar の中のメソッド | 出さない | 出る（`[EXTERNAL]`） | ソースに宣言が無い |

匿名クラスは一度「呼び出せる定義」として出す側にしたが、匿名クラスのメソッドを
呼ぶ側は必ず親の型（インターフェース等）を通しており、`Outer$1.execute` という定義を
指名して呼ぶ箇所は存在しない。一覧の役割（どれが呼ばれていないか・よく呼ばれているかを
俯瞰する）から見れば、親の定義の行があれば足りる。

出さないぶん、ラムダを持つメソッドの `outDegree` は本体の呼び出しを含まない
（生成の辺1本だけを数える）。本体の中の呼び出しは `call-hierarchy.csv` で追う。
出力対象外にした件数は実行ログに出す。

### Q10. 捕捉した引数を、実行した側のフレームで当ててしまっていた

当初は「合成メソッドへ降りるときは、必ず 1 つ上の段の引数を捕捉した値として渡す」としていた。
生成の辺（囲みメソッド → 合成メソッド）だけを考えれば 1 つ上は生成箇所なのでこれでよいが、
`DATAFLOW_LAMBDA` の辺では 1 つ上の段は**実行箇所**であって、生成箇所とは限らない。

```java
private void captureOuter(Dao dao) {                       // dao = new OrderDaoImpl()
    runWithOther(new UserDaoImpl(), () -> dao.describe());
}
private void runWithOther(Dao other, Runnable r) { r.run(); }
```

`runWithOther` の段から本体へ降りると、`E:0` が `runWithOther` の第 1 引数（`UserDaoImpl`）に
当たり、`UserDaoImpl.describe` に **`RESOLVED:DATAFLOW_PARAM` で誤って確定**していた。
捕捉した値はラムダを作った時点で決まる（JLS 15.27.4。捕捉できるのは実質的 final な変数だけ＝JLS 15.27.2）ので、
実行した側の引数を当てるのは値の取り違えである。

直し方は、降りる先の合成メソッドを**今の段のメソッドが生成したか**（`CallGraph.createsLambda`。
宣言どおりの呼び出し先がその合成メソッドである辺＝生成の辺を持つか）で判定し、
生成したメソッドの段でだけ引数を渡す。それ以外の段では null にして、捕捉した引数への
呼び出しは CHA のまま残す（`test/demo` の `fx.lambda.Captured`）。

却下した案は、`Z:` の値に生成箇所の引数環境を一緒に持ち運ぶ（クロージャとして扱う）もの。
引数で渡した先で捕捉した引数まで解けるようになるが、値の文字列に環境を埋め込む形になり
キャッシュの形式と読み手の両方が大きくなる。「引数で渡した先で呼ぶ」形で本体まで繋がることは
変わらず（Q6 の表のとおり）、失うのは本体の中の**捕捉した引数**への呼び出しの絞り込みだけなので、
安全側に倒すだけにした。

### Q11. 親インターフェースの型で受けた呼び出しで、ラムダが無視されていた

M 行（`FunctionalImplFact`）の鍵は SAM（`ITypeBinding.getFunctionalInterfaceMethod`）の宣言型で
作っていた。関数型インターフェースのメソッドが親インターフェースの抽象メソッドを
再宣言している場合（JLS 9.4.1.3）、SAM はその再宣言の側になる。

```java
interface Handler<T> { void handle(T value); }
interface StringHandler extends Handler<String> { @Override void handle(String value); }
class LoggingHandler implements StringHandler { ... }      // ソース上の唯一の実装クラス

StringHandler h = value -> dao.describe();
dispatch(h);
private void dispatch(Handler<String> h) { h.handle("x"); }
```

ラムダの M 行の鍵は `StringHandler#handle(java.lang.String)`、`h.handle("x")` の呼び出し先の鍵は
`Handler#handle(java.lang.Object)` で一致しない。読み手（`CallGraph.hasFunctionalImpl`）は
完全一致で引くので「ラムダの実装は無い」と見え、`LoggingHandler.handle` に
**`RESOLVED:SINGLE_IMPL` で決め打ち**していた。候補が複数のときは
`DataflowResolver.targetOf` が別経路で `Z:` を試すので偶然救われることがあるが、それも
同じ鍵の M 行が別のラムダから出ている場合だけである。

直し方は書き手の側で、SAM が上書きしている親の宣言すべての鍵でも M 行を書く
（当初は `BindingNames.overriddenKeysOf(sam, true)`。Q15 で `BindingNames.functionalKeysOf` に置き換えた）。
O 行と違ってシグネチャが同じ再宣言
（`interface MyRunnable extends Runnable { void run(); }`）も含める。読み手は型を辿らず鍵の
完全一致で引く作りのままなので、親の鍵が無ければ当たらないためである。
上書きの判定は O 行と同じく `IMethodBinding.overrides` に任せる。
キャッシュに書く内容が変わるので analysis の版を v24 に上げた（`test/demo` の `fx.lambda.Redeclared`）。

読み手の側で `OverrideIndex` を引く案は、O 行がシグネチャの同じ上書きを持たない
（キーの照合で引けるので書かない）ため `MyRunnable` の形を救えず、却下した。

### Q12. メソッド参照の参照先が仮想メソッドのとき、宣言を「確定」と書いていた

`Z:` はメソッド参照の**コンパイル時宣言**（JLS 15.13.1）を指す。`Runnable r = dao::describe; r.run();`
なら `Z:fx.Dao#describe()` で、読み手はこれをそのまま実行先にしていた。出力は
`Dao.describe, RESOLVED:DATAFLOW_LAMBDA` で、本体の無い抽象メソッドが葉になり、その先の
`OrderDaoImpl.describe` の階層は出なかった（参照箇所の辺 `viaBoundRef → Dao#describe` の側では
`DATAFLOW_FIELD` で実装まで出るので、取りこぼしではないが「確定」と書いた先が動かないメソッドだった）。

JLS 15.13.3 では、メソッド参照の実行時には参照先の宣言ではなくレシーバの実行時クラスで
仮想ディスパッチされる。レシーバを束縛した形（`dao::describe`）のレシーバは参照を作った時点で
評価される。そこで:

- 書き手は、束縛したレシーバの出所を `Z:` に付ける（`Z:fx.Dao#describe()|r=F:fx.Refs#dao`。
  値グラフの N 行ではレシーバをノードで持つ）。型名で書いた形（`Dao::describe`。レシーバは
  呼び出し時の第1引数）、`super::m`、`Type::new` には付けない
- 読み手（`CallResolver.functionalResolution`）は、参照先が仮想メソッドなら通常の呼び出しと同じ段を踏む。
  レシーバの具象型が分かればその実装（`DataflowResolver.functionalReceiverImpl`）、分からなければ
  上書き候補（`resolveVirtual`）で、1 件なら確定、複数なら CHA として全部出す。本体を持つ候補が
  無ければ宣言のまま。ラムダの本体と静的束縛の参照先（private / static / final / コンストラクタ）は
  従来どおりそのまま確定

レシーバの出所は参照を書いたメソッドから見たもので、今歩いている経路のフレームとは別なので、
経路の文脈（引数の環境）は使わない（Q10 と同じ理由）。`new`・出所が 1 つに定まるフィールド・
引数を使わないファクトリのように、フレームに依らない出所だけで決める。
`super::m` は静的束縛だが `Z:` の形ではそれが分からず、上書き候補を出す（絞りすぎる側ではないので許容した）。

### Q13. 合成メソッドの番号が javac と一致しなかった

「javac と同じ名前」と書いていたが、javac 21 で確かめると 2 つずれていた。

- **入れ子のラムダ**。javac は本体を読み終えた順（後行順）に番号を振るので、
  `() -> { () -> {} }` は内側が `$0`、外側が `$1`。`LambdaNames` は `visit` で振っていたので逆だった。
  `endVisit` で振るようにした
- **enum 定数の引数の中のラムダ**。定数の初期化は `<clinit>` で走る（JLS 8.9.3）ので javac は
  `lambda$static$N` と付けるが、`LambdaNames.enclosingOf` は `EnumConstantDeclaration` を見ておらず
  `lambda$new$N` になっていた。`FactVisitor` は生成の辺を `<clinit>` から張るので、ツールの中でも
  食い違っていた。enum 定数を static 文脈として扱うようにした

javac 21 と比べて残る差は次のとおり。名前は「javac と同じ形」であって、番号の一致は保証しない。

- javac がメソッド参照の一部（配列の `Type[]::new`、`super::m`、可変長引数の調整が要るもの等）を
  内部でラムダに変換して番号を消費する場合。このツールはメソッド参照に合成メソッドを作らないので、
  その後ろの番号がずれる
- 直列化可能なラムダ（`(Runnable & Serializable) () -> ...`）。javac は `lambda$main$422b1e3c$1` のような
  別の形にし、番号も別に数える。javac 21 ではその後ろの番号もずれる
- 匿名クラスのフィールド初期化子の中のラムダ。javac 21 は `lambda$$0`（囲みメソッド名が空）と付ける

**javac の版で振り方そのものが違う。** JDK 25 の javac（このツールの実行と CI に使う版）で同じソースを
コンパイルすると、番号は**囲みメソッド名ごと**に 0 から振られ（`lambda$main$0` と `lambda$new$0` が並ぶ）、
入れ子は**外側が先**（先行順）になる。このツールは javac 21 の振り方に合わせたままにした。
解析対象のプロジェクトがどの版の javac でコンパイルされているかはツールから分からず、
どちらかに合わせても片方とは食い違うため。名前が役に立つのは「どのメソッドのどのラムダか」の
見当を付けるところまでで、スタックトレースとの照合は行番号（`at ...(File.java:行)`）で行う。

名前は D 行・C 行・M 行に焼き込まれるので analysis の版を v25 に上げた。

### Q14. 式本体のラムダの戻り値が R 行になっていなかった

`() -> { return new X(); }` はブロックの `return` で R 行になるが、`() -> new X()` は
`ReturnStatement` が無いので何も残らなかった。JLS 15.27.4 で式本体は「その式の値を返す」ので `return 式;` と同じであり、
`visit(LambdaExpression)` で本体が式なら同じ条件で R 行を書く。

あわせて、その R 行を使う経路を足した。`s.get().describe()` の `s.get()` の出所は
`M:Supplier#get()|r=Z:...lambda$m$0()` で、`Supplier#get()` 自体は jar の中なので R 行が無い。
レシーバがラムダ／メソッド参照と分かり、かつ呼び出し先が関数型インターフェースのメソッド
（M 行がある）なら、戻り値の出所はその本体の R 行で決める（`DataflowResolver.concreteTypeOf`）。
`Object#toString()` のような関数型インターフェースと関係ない呼び出しには当てない。

### Q15. 2 つの親から同じ抽象メソッドを継承した関数型インターフェースで、別の実装に確定していた

JLS 9.8 では、関数型インターフェースの抽象メソッドは 1 つとは限らない。
親から継承した抽象メソッドのうち、互いに上書き同等（シグネチャが一方の subsignature）なものは
まとめて 1 つの関数型を成し、ラムダはその**すべて**を実装する。

```java
interface Opener { void act(); }
interface Closer { void act(); }
interface Door extends Opener, Closer {}      // 抽象メソッドは Opener#act と Closer#act の 2 つ
class PlainOpener implements Opener { ... }   // ソース上の唯一の Opener 実装クラス

Door d = () -> dao.describe();
viaOpener(d);                                 // viaOpener(Opener o) { o.act(); }
```

JDT の `getFunctionalInterfaceMethod` はこのうち 1 つ（ここでは `Closer#act`）しか返さない。
`Closer#act` は `Opener#act` を上書きしていないので、Q11 の `overriddenKeysOf(sam, true)` でも
`Opener#act()` の鍵は出てこない。その結果 `o.act()` は `PlainOpener.act` に `RESOLVED:SINGLE_IMPL` で
**誤って確定**していた（実際に動くのはラムダ）。

直し方は Q11 と同じく書き手の側で、`BindingNames.functionalKeysOf(ラムダの型, SAM)` が
ラムダの型とその親インターフェース（型引数を具体化したまま）を辿り、SAM と上書き同等な抽象メソッド
すべての鍵を返す。判定は `IMethodBinding.isSubsignature`（JLS 8.4.2）に任せる。Q11 の
再宣言（`StringHandler extends Handler<String>`）もこの判定に含まれるので、`overriddenKeysOf` の
`includeSameSignature` 版は要らなくなり、消した。交差型（`(Runnable & Serializable) () -> ...`）は
成分ごとに辿る。

M 行の中身が変わるので analysis の版を v26 に上げた（`test/demo` の `fx.lambda.TwoParents`）。

### Q16. 呼び戻しの契約に渡したメソッド参照が、参照先の宣言に確定していた

Q12 で `r.run()` のような関数型インターフェース経由の呼び出しは直したが、jar の中から呼び戻される
経路（`CallbackContracts`）は別に値を引いており、参照先の宣言をそのまま呼び戻し先にしていた。

```java
new Thread(this::hook).start();   // 子クラスが hook を上書きしていても CallbackRefs.hook だけ
daos.forEach(Dao::describe);      // 本体の無い Dao.describe が RESOLVED:CALLBACK の葉になる
```

呼び戻し先の決め方を `CallResolver.functionalResolution` と同じにした（`CallbackContracts.FunctionalLookup`
として渡す。決め方を 2 か所に持たないため）。束縛したレシーバの具象型が分かればその実装に確定し
（`new Thread(dao::describe)` は `RESOLVED:CALLBACK`）、分からなければ上書き候補を全部出す。
候補が複数の行は確定に見せないよう、`resolved-by` を `UNEXPANDED:CALLBACK`、注記を
`[UNEXPANDED:CHA] N candidates: method reference to an overridable method contract: ...` にする。
通常の CHA と同じく、候補の行からその先へは降りない（候補数^深さで爆発するため）。

ただし**参照先の宣言が jar の中**（`list.forEach(Runnable::run)` の `Runnable#run`）なら、上書き候補は
`Runnable` の全実装になる。これは契約表の「jar の型の全実装のような広い候補は出さない」
（[callback-contracts.md](callback-contracts.md) の「追える条件」）に反するので、従来どおり辺を張らない。
参照を書いた箇所からの辺（`Main.lambdas → Starter.Job.run` の CHA）は別にあるので、本体の呼び出しは落ちない。

`test/demo` の `fx.lambda.CallbackRefs`。

### Q17. インターフェースのフィールドの中のラムダが `lambda$new$N` になっていた

```java
interface Defaults { Runnable DEFAULT = () -> ...; }
```

インターフェースのフィールドは `static` と書かなくても static（JLS 9.3）で、初期化は `<clinit>` で走る。
javac は `lambda$static$0` と付け、`FactVisitor` も生成の辺を `<clinit>` から張る。ところが
`LambdaNames.enclosingOf` は書かれた修飾子（`FieldDeclaration.getModifiers()`）を見ていたので
`lambda$new$0` になり、Q13 の enum 定数と同じ「ツールの中での食い違い」が残っていた。
`FactVisitor.isStaticField`（バインディングで判定する。まさにこの落とし穴のためにある）を使うようにした。
analysis の版は Q15 と同じ v26（`test/demo` の `fx.lambda.Defaults`）。

### Q18. 注記と methods.csv の表記を直した

- **型名で書いたメソッド参照の注記**。`Dao::describe` のように型名で書いた参照が CHA になったとき、
  理由が `type name (static)` と出ていた。レシーバの種別 `RecvKind.TYPE` は static 呼び出しと共用だが、
  static 呼び出しは静的束縛なので CHA にならず、この理由が出るのは型名で書いたメソッド参照だけである。
  その参照のレシーバは呼び出し時の第 1 引数で、static 呼び出しではない（JLS 15.13.1）。
  `type name (unbound method reference)` に改めた。読み手が作る文言なのでキャッシュの版は上げていない
- **`unresolvedCause` の判定順**。`InventoryReport` は `NO_IMPL` を `LAMBDA` より先に見ていたので、
  ソース上に実装クラスが無くラムダだけが実装している関数型インターフェース（Q15 の `Closer`）への
  呼び出しが、methods.csv では `[UNEXPANDED:NO_IMPL]`、call-hierarchy.csv では `LAMBDA` 系と食い違っていた。
  注記（`StreamingTreeWalker.noteFor`）と同じく `LAMBDA` を先に見る

