# 静的解析で何が分かり、何が分からないか

「文字列からクラス名を算出してインスタンスを返すファクトリ」のような呼び出しを、このツールが
どこまで解決できるかの整理。解決できない呼び出しにどう対処するかは
[instance-analysis-plugin.md](instance-analysis-plugin.md) にある。

このツールの目的は「改修時の影響調査で呼び出しを漏らさない」ことなので、
**絞れなかったことが分かる**ことと、**漏れないこと**を、絞れることより優先している。
この文書はその線引きを説明する。

---

## 1. しきい値は「動的かどうか」ではない

分かれ目は次の一点だけ。

> **その値が、解析時に有限個の定数へ畳み込めるか。**

リフレクションを使っているかどうかは境界ではない。`test/demo` の実際の出力
（`test/regression/whole/expected/call-hierarchy.csv`）がそれを示している。

| `test/demo/src/fx/app/Main.java` | 出力された注記 |
|---|---|
| 39行目 `DaoFactory.createEither(args.length > 0).findById(2L)` | `[UNEXPANDED:CHA] 2 candidates: return value (factory method etc.)` |
| 40行目 `DaoFactory.byName("fx.dao.OrderDaoImpl").findById(3L)` | `RESOLVED:DATAFLOW_FACTORY` |

40行目は `Class.forName(className).getDeclaredConstructor().newInstance()` を返すファクトリ
（`test/demo/src/fx/dao/DaoFactory.java` の `byName`）なのに1件に確定していて、
39行目は `new` を2つ並べただけの素朴なファクトリなのに絞れていない。
39行目が絞れないのは `args.length > 0` が実行時にしか決まらないからで、リフレクションとは関係がない。

---

## 2. 「文字列からクラス名を算出するファクトリ」を分解する

```java
public final class ServiceFactory {

    private static final Map<String, Service> POOL = new HashMap<>();          // (D) プール

    public static Service get(String key) {                                    // (A) 引数の値
        String fqn = "jp.co.app.impl." + capitalize(key) + "Service";          // (B) 文字列演算
        Service cached = POOL.get(fqn);
        if (cached != null) {
            return cached;
        }
        Service created = (Service) Class.forName(fqn)                         // (C) リフレクション
                .getDeclaredConstructor().newInstance();
        POOL.put(fqn, created);
        return created;
    }
}
```

難易度のまったく違う4つの問題が重なっている。

| # | 難所 | 必要な解析 | 実際のところ |
|---|---|---|---|
| A | `key` に何が来るか | 手続き間の定数伝播 | **経路依存だが追える**。呼び出し元がリテラルを渡していれば確定する。値が外部（設定ファイル・DB・リクエスト）から来た時点で終わり |
| B | 文字列からクラス名を算出 | 文字列解析 | **ここが本当の壁**。このツールは文字列の連結・加工を一切追わない |
| C | `Class.forName(...).newInstance()` | パターン照合 | **最も簡単**。形が定型なので拾える（`OriginTracker.reflectiveOriginOf`） |
| D | インスタンスプール | コンテナ解析 | **見た目ほど重要ではない**（[5節](#5-プールは実は問題ではない)） |

---

## 3. 何をどう追っているか

式の「出所」を、1 つの式を 1 ノードとする値グラフ（キャッシュの N 行。`src/jche/cache/ValueNode.java`）で持つ。
ノードは種別の 1 文字（`src/jche/cache/Origin.java`）と値を持ち、呼び出しなら実引数とレシーバのノードも指す。
下は人が読むときの表記（`jche.cache.CacheDump` の出力と同じ）。

```
T:jp.co.UserDaoImpl        new された具象型（その場で確定）
A:2                        囲みメソッドの3番目の引数（呼び出し元まで遡って確定）
M:jp.co.Factory#create()   メソッドの戻り値（その宣言の return を見て確定）
F:jp.co.Service#dao        フィールド
L:jp.co.UserDaoImpl        文字列リテラル（コンパイル時定数を含む）
C:0                        Class.forName(引数) で名前指定された型
K:jp.co.UserDaoImpl        クラスオブジェクト（X.class）
U                          追跡できない  ← 「分からない」を明示的に持つのが重要
```

`byName` の `return` は `C:0`（0番目の引数で名前指定された型）になり、呼び出し元の実引数
`L:fx.dao.OrderDaoImpl` と突き合わせて初めて型が決まる。解決側
（`DataflowResolver.literalOf`）が文字列として扱うのは次の3つだけ。

- `L` … 文字列リテラルと、`static final String` などのコンパイル時定数
- `A` … 経路上で分かっている実引数
- `M` … 全ての `return` が同じ値に畳めるメソッドの戻り値（委譲は `dataflow.max.depth` 段まで）

**文字列の連結（`+`）も、文字列を加工するメソッドの中身も、この一覧に無い。** ここが (B) の壁の正体。

---

## 4. できる／できないの一覧

### 解決できる

```java
Class.forName("jp.co.app.UserServiceImpl")     // リテラル
Class.forName(Names.USER_SERVICE)               // static final String（値が畳まれる）
factory.get(key)                                // 呼び出し元が get("jp.co...") とリテラルを渡している経路
```

引数経由の解決（`DATAFLOW_PARAM`）は**経路ごと**に判定する。同じファクトリでも、
呼び出し元Xからの経路では確定、Yからの経路では不明、という出方をする。

具象型が決まらなくても、渡した値の**宣言の型**が具象クラスなら、その部分型に候補を絞る（`DATAFLOW_DECLARED_TYPE`。
[declared-type-narrowing-qa.md](declared-type-narrowing-qa.md)）。Spring でコンストラクタ注入した `XmlOrderExporter` 型の
フィールドを `export(OrderExporter e)` に渡した先の `e.export()` は、`XmlOrderExporter` の部分型の実装だけになる。
宣言の型がインターフェース（`OrderExporter` 型のフィールド）なら絞れないので、これまでどおり CHA のまま。

ラムダ式・メソッド参照は値として追う（`DATAFLOW_LAMBDA`）。追える形と追えない形の一覧は
[lambda-expansion-qa.md](lambda-expansion-qa.md) の Q6。要点は次のとおり。

```java
Runnable r = () -> dao.describe(); r.run();     // ローカル変数・引数・フィールド・ローカルのコレクション経由
Runnable r = dao::describe;                     // 束縛したレシーバ（dao）の具象型が分かれば、その実装
Supplier<Dao> s = () -> new UserDaoImpl(); s.get().describe();   // ラムダの return を戻り値の出所にする
```

### 解決できない

| 形 | 理由 |
|---|---|
| `"jp.co.impl." + key + "Service"` | 文字列の連結を追わない |
| `capitalize(key)` / `key.toUpperCase()` | 文字列を返すメソッドの**計算**は追わない（`return` がリテラルそのものなら追う） |
| `props.getProperty("service.class")` | 値がソースの外にある。**原理的に不可能** |
| `get(request.getParameter("type"))` | 同上（実行時入力） |
| `POOL.get(fqn)` の戻り値 | コレクションの要素を追う仕組みが無い。Map / List に入れた時点で出所が `U` になる |
| `if (flag) A else B` で `flag` が実行時値 | 候補は複数のまま（2節の39行目） |
| `b.mode()` の `mode` を部分型が上書きしている | メソッドが返す値（`return` の値）は、呼び出しがその宣言の本体でしか動かないとき（static・private・final、または部分型が上書きしていない）だけ使う。上書きがあれば、レシーバの具象型が分かっていても使わない（CHA のまま。[cache-unification-qa.md](cache-unification-qa.md) の Q10）。部分型からその宣言までの親クラスの連鎖に jar のクラスが挟まるときも使わない（jar のクラスの見えない宣言が動きうる。親インターフェースの default を jar のクラスを親に持つ型が継承する形など。[jls-conformance-qa.md](jls-conformance-qa.md) の Q32） |
| `list.forEach(Runnable::run)` | `forEach` の中は jar なのでソースが無い。生成の辺があるので本体の呼び出しは階層に出るが、実行箇所からは繋がらない（`[UNEXPANDED:LAMBDA]`） |
| `Dao::describe`（型名で書いたメソッド参照） | レシーバは呼び出し時の第1引数で、追っていない。上書き候補（CHA）を全部出す |
| ラムダが捕捉した引数を、渡した先で呼ぶ | 捕捉した値はラムダを作った時点で決まるが、生成箇所の引数を実行箇所の経路へ持ち運んでいない。生成したメソッドの段でだけ当てる（[lambda-expansion-qa.md](lambda-expansion-qa.md) の Q10） |

---

## 5. プールは実は問題ではない

インスタンスプールは**メモ化でしかない**。「プールから出てくる型の集合」は
「同じメソッド内で `newInstance` が作りうる型の集合」と必ず一致するので、
プールは答えを変えない。人間はこれを一瞬で見抜くが、コンテナ解析を持たない解析器は
`POOL.get()` で糸が切れる。

つまり (D) は「解析器に教えれば消える」問題で、(B) の文字列演算のような
「原理的に難しい」問題とは質が違う。**プールがあることを理由に諦める必要はない。**
生成側（`newInstance` の行）が解ければ答えは同じになる。

---

## 6. 理論的な壁と、この実装の倒し方

「あるコール箇所で実際に呼ばれる具象メソッドの集合」を正確に求めることは決定不能（ライスの定理）。
したがって実用の静的解析は、必ずどちらかに倒すことになる。

| 倒し方 | 意味 | 影響調査での評価 |
|---|---|---|
| **健全側（over-approximation）** | 候補を多めに出す。偽陽性は出るが**漏れない** | 正しい。調べる手間は増えるが見落とさない |
| 精度側（under-approximation） | 確実なものだけ出す。**漏れる** | 致命的。「呼ばれていない」と誤読する |

このツールは健全側に倒す。絞れなければ CHA で実装を全部候補に挙げ、確実な証拠があるときだけ1件に絞る。
`FieldFacts` の判定条件にも同じ方針が書いてある。

> 「絞れないことより誤って絞ることの方が害が大きい」ので採用しない

**限界は消せない。消せるのは「限界が見えないこと」だけ**なので、出力には必ず理由が付く。

ただしこれは**値を絞り込む段**の話である。そもそも辺や候補の読み取りが言語仕様と食い違っていると、
理由を付ける機会すら無いまま静かに落ちる。その境界は[7節](#7-言語仕様の側の限界値とは別の話)に分けて書いた。

- `[UNEXPANDED:CHA] N candidates: <reason>` … 絞れなかったことと、その理由（`grep '\[UNEXPANDED'` で一括で拾える）
- `(unresolved)` の行と件数のログ … クラスパス不足を「呼び出しが無い」と誤読させない
- 種別 `U`（キャッシュでは `-1`）… 「分からない」を値として明示的に持つ

`feature-difficulty.md` でも、どこまで機能を削っても
**1-9（型解決失敗を行として残す）と 2-2（絞れなかった理由の注記）だけは残す**ことにしている。

---

## 7. 言語仕様の側の限界（値とは別の話）

ここまでは「**値**が畳めるか」の話だった。それとは別に、**言語構文の読み取り**そのものにも
境界がある。こちらは注記が出ないので、出力を見ても分からない。分かるように書いておく。

過去に直したもの（[jls-conformance-qa.md](jls-conformance-qa.md)、
[inherited-impl-candidates-qa.md](inherited-impl-candidates-qa.md)）は、どれも
**CHA の候補や辺が静かに落ちる**形だった。ここに書く意味は、同じ形の問題を次に見つけたときに
「値の限界」と混同しないためである。

| 何を読むか | どこまで読むか |
|---|---|
| オーバーライド | JLS 8.4.2 のサブシグネチャまで見る。型引数を具体化した実装（`class UserRepo implements Repo<User>`）は、消去したキーが食い違うので**上書き関係を事実として残して**照合する（O 行）。呼び戻しのライブラリ呼び出し規則とリフレクションは所有型を知らないので、同じ照合をシグネチャで行う |
| 暗黙のコンストラクタ呼び出し | 書かれていない `super()`（JLS 8.8.7 / 8.8.9）も辺にする。ただし親が `java.lang.Object` / `Enum` / `Record` のときは張らない（辿る先が無い）。匿名クラスの合成コンストラクタ（JLS 15.9.5.1）は、型引数を置き換えた親のコンストラクタと引数の型で比べる。それ以外で呼ばれるコンストラクタを 1 つに決めきれないとき（引数なしのものが public・protected でなく可変長引数 1 つのものと並ぶ、可変長引数 1 つのものが複数ある）は、最も特殊なもの（JLS 15.12.2.5）・見えるもの（JLS 6.6）を自前で選ばず、**候補すべてに辺を張る**。javac が選ぶもの以外の辺も出る（多すぎる側。[jls-conformance-qa.md](jls-conformance-qa.md) の Q35） |
| 実行時に動く実装の選び方 | 具象型から実装を探す順は JVM の選び方（JLS 8.4.8・9.4.1、JVMS 5.4.6）に合わせる。親クラスの連鎖を根まで先に見て、無ければ親インターフェースの宣言のうち最も特定的なもの。クラスのメソッドは親インターフェースの default に常に勝ち、親型の private と親インターフェースの static は実装にしない。jar からの被参照（`external-ref:INHERITED`）も同じ順で結びつける。親クラスから継承したメソッドが型引数を置き換えたインターフェースのメソッドを実装する形（`class UserRepo extends BaseRepo implements Repo<User>`）も、その型から見た関係として引く。別のパッケージの親クラスのパッケージアクセスのメソッドは、JLS では継承されないが、今も実装に選ぶ（コンパイルした形は実行時に `IllegalAccessError` になる形。[jls-conformance-qa.md](jls-conformance-qa.md) の Q26〜Q36） |
| 構文が呼ぶメソッド | 呼び出し式が無くても JLS が「呼ぶ」と定めるものは辺にする。拡張 for 文の `iterator()` / `hasNext()` / `next()`（JLS 14.14.2。後の 2 つは `java.util.Iterator` のメソッド）、try-with-resources の `close()`（JLS 14.20.3）、レコードパターンのアクセサ（JLS 14.30.2）。`iterator()` / `close()` の呼び出し先は式の型のメンバー（JLS 8.4.8）で、型と親クラスの連なりを先に、無ければインターフェースの最も特定的な宣言を、public な宣言だけから引く（private・別のパッケージのパッケージアクセスは継承されない）。**文字列変換の `toString()`（JLS 5.1.11。`"x" + obj`）は辺にしない**（`Object.toString` の全実装が候補になるだけで絞れず、javac も呼び出し命令を書かない） |
| パッケージアクセスの上書き | パッケージアクセスのメソッドは同じパッケージの宣言からしか上書きされない（JLS 8.4.8.1）ので、別パッケージのサブクラスの同じシグネチャのメソッドは CHA の候補に入れない。同じパッケージで public / protected に広げた中間の宣言があれば、推移的な上書きとして候補に入れる |
| 呼び出しを修飾する型 | CHA の候補は、メソッドを宣言した型ではなく**呼び出しを修飾する型**（JLS 13.1。受け手の式の静的な型、単純名なら囲む型）の部分型から引く。`Plain p; p.greet()`（`greet` は親インターフェース `Greeter` のデフォルトメソッド）の候補に、`Plain` の部分型でない `Greeter` の実装は入らない。修飾する型が jar の型のときは、jar の中の中間の型を経由した部分型を数え漏らしうるので、宣言した型から引く（多すぎる側） |
| 条件の値 | 値を変えうるキャスト（JLS 5.1.3 の縮小、5.1.2 のうち精度を失う拡大、5.1.7 / 5.1.8）が挟まった式は**判定しない**。条件の両辺だけでなく、実引数・ローカル変数・戻り値の値も同じ規則で読む（コンパイル時定数は変換後の値）。`char` は数値昇格（JLS 5.6）に合わせて数値で持つ。浮動小数は表記が揺れるので拾わず、比べる両辺の片方でも `float` / `double` なら判定しない（整数の実引数は浮動小数の引数へ暗黙に拡大され、`int` → `float` などは精度を失うので、`f(16777217)` の `x == 16777216` は真になる。JLS 5.1.2・5.3）。数値リテラルの値は JDT の評価から取る（16 進の `0x80000000` は `-2147483648`。JLS 3.10.1）。`equals` は、比べる相手の静的な型が `String`・定数と同じ列挙型・定数を箱詰めした型（浮動小数を除く）のときだけ判定する（実行時の型が違えば表記が同じでも偽になるため）（`docs/value-safety-qa.md`） |
| record・enum の暗黙メンバ | 正準コンストラクタと、record の書かれなかった成分のアクセサ（JLS 8.10.3）は宣言（D 行）を合成する（`TypeContextTracker#synthesizeImplicitAccessors`。形式 v45。合成しないと `record R(String name) implements Named` で実装探索が親インターフェースの `default name()` に進んでしまう。Issue #177）。**`equals` / `hashCode` / `toString` と、enum の `values()` / `valueOf(String)`（JLS 8.9.3）は合成しない**。これらの呼び出しは `[EXTERNAL] no source to follow` と出る。本体がソースに無いので辿る先は無いが、「ソースにある型なのに EXTERNAL」と見えるのは正確ではない |
| 起動の入口 | 名前が `main` で引数が `String[]` か無し、private でないメソッドを入口（`FRAMEWORK_ENTRY`）にする（JLS 12.1.4。インスタンスメソッドの `void main()` を含む）。JLS は戻り値が void であることも求めるが、D 行に戻り値の型が無いので見ない（多すぎる側） |
| 準拠レベル | `source.level` を指定しなければ JDT が対応する最大版で読む。**プレビュー機能は有効にしない**ので、プレビュー段階の構文は構文エラーになる（`syntax errors` として件数が出る） |

「ソースに書いてあるのに辺が無い」を見つけたら、値の話ではなくこちらを疑うこと。

この表の読み取りは `test/jls/run.sh` が JLS SE 26 の節ごとに検査し、同じソースを javac 26 で
コンパイルしたバイトコードとも突き合わせる（[jls-conformance-test-qa.md](jls-conformance-test-qa.md)）。
新しい構文の読み取りを直したら、そこに節を 1 つ足すこと。

---

## 8. では、解けないものをどうするか

(B) の文字列演算は解析器には解けないが、**その規則を書いた人間は答えを知っている**。
だから解析器に外から教える口を用意してある（[instance-analysis-plugin.md](instance-analysis-plugin.md)）。

| 手段 | 何を書くか | 向いている場合 |
|---|---|---|
| 1. 対応表 | `.properties` の対応表だけ（Java 不要） | キーと具象型の対応が列挙できる |
| 2. 自前の拡張 | `TypeCandidateProvider` の実装（`.java` を置くだけ。ビルド不要） | 算出規則が規則的で、対応表を手で並べたくない |
| 3. コード側を変える | ファクトリを `switch` + `new` にする | 新規・改修の余地がある |

手段3は、静的解析でも人間の目でも追えるようになるので、選べるなら最も効く。

```java
return switch (key) {
    case "user"  -> new UserService();
    case "order" -> new OrderService();
    default -> throw new IllegalArgumentException(key);
};
```

これで候補は全て `T:`（new された具象型）になり、拡張なしで解決できる。

---

## 9. 解決の段（具象クラスをどの順に決めるか）

インターフェースや親クラスの型に対する呼び出しで、どの実装に解決したかを次の段の順に判定し、先に確定した段で打ち切る。
ラベルは `call-hierarchy.csv` の `resolved-by` 列の後半になる（1 件に確定したら `RESOLVED:`、候補のままなら
`UNEXPANDED:` が頭に付く）。各ラベルの意味は [README の「具象クラスの解決」](../README.md#具象クラスの解決)にある。

| 段 | ラベル | 判定 |
|---|---|---|
| 0 | `STATIC_BOUND:*` | private / static / final メソッド、final クラス、コンストラクタ、super 呼び出し。理由が後ろに付く（`STATIC_BOUND:PRIVATE` 等） |
| 1 | `NO_OVERRIDE` / `SINGLE_IMPL` | オーバーライド候補が 1 つに定まる |
| 1 | `NO_IMPL` | 本体を持つ実装がソース上に 1 つも無い（宣言のまま扱う） |
| 2 | `LOCAL_NEW` / `LOCAL_NEW_MULTI` | 同一メソッド内で `new` された型 |
| 3 | `CALL_RULE` | ライブラリ呼び出し規則に書いた「この宣言型（メソッド）はこの具象型」で決めた（[docs/library-call-rules.md](library-call-rules.md)） |
| 3 | （拡張が返すラベル） | ファクトリ・DI 設定・外部リスト等（[docs/instance-analysis-plugin.md](instance-analysis-plugin.md)）。ライブラリ呼び出し規則の次に尋ねる |
| 4 | `DATAFLOW_NEW` / `DATAFLOW_FACTORY` | `new` された型、またはファクトリメソッドの戻り値から特定 |
| — | `DATAFLOW_PARAM` | 呼び出し元から渡された引数を経路上で追跡して特定（経路ごとに判定するため段の外） |
| — | `DATAFLOW_FIELD` | コンストラクタ注入されたフィールドを経路上で追跡して特定（同上） |
| — | `DATAFLOW_DECLARED_TYPE` | 経路上で渡された値の**宣言の型**（具象クラスの型で宣言したフィールド・引数）の部分型に候補を絞ったら 1 つに定まった（同上。[declared-type-narrowing-qa.md](declared-type-narrowing-qa.md)） |
| — | `DATAFLOW_LAMBDA` | ラムダ式・メソッド参照から特定（同上。下記） |
| 5 | `SPRING_DI` / `SPRING_DI_QUALIFIER` | DI コンテナ（Spring）の Bean 定義で候補が 1 つに定まった。`SPRING_DI_QUALIFIER` は `@Qualifier` / `@Resource(name=...)` の Bean 名で定まった（[docs/spring-di-qa.md](spring-di-qa.md)） |
| 6 | `CHA` | 候補が複数のまま（低確度） |
| — | `GENERATED_IMPL:名前` | 実装がコンパイル時のアノテーション処理で生成される型（`NO_IMPL` の特殊形） |
| — | `CALLBACK` | 「渡した値のこのメソッドを呼び戻す」という規則で jar の中を跨いで繋いだ（[docs/library-call-rules.md](library-call-rules.md)）。渡したメソッド参照の実装を 1 つに決められず候補を並べたときは `UNEXPANDED:CALLBACK` |
| — | `REFLECTION` / `REFLECTION_INIT` | `Method.invoke` / `newInstance` をリフレクションで指定されたメソッド・コンストラクタに解決した／`Class.forName` によるクラス初期化（`<clinit>` へ繋ぐ） |
| — | `EXTERNAL_GUESS` | クラスパス不足で型解決できず、`import` から型名を推定した（**未検証**） |
| — | `LAMBDA` | ラムダ／メソッド参照による実装があり、どれが実行されるかは未特定。`resolved-by` 列でだけ使う言い換えで、必ず `UNEXPANDED:LAMBDA` の形で出る |

`DATAFLOW_PARAM` / `DATAFLOW_FIELD` / `DATAFLOW_DECLARED_TYPE` / `DATAFLOW_LAMBDA` は経路ごとに判定するので段の外に置いている。
ライブラリ呼び出し規則（段 3 の `CALL_RULE`）は拡張より先、データフロー（段 4）や Spring の判定（段 5）より先に効く。

---

## 10. ラムダ式・メソッド参照の追い方

ラムダ式の本体は、解析の内部では javac に似せた名前（`lambda$囲みメソッド名$通し番号`）を付けた
**合成メソッド**として扱います。CSV には出ません（`methods.csv` にも `call-hierarchy.csv` にも）。
static 初期化子・static フィールド・enum 定数の引数の中のラムダは `lambda$static$N` です。
通し番号はスタックトレースに出る javac の番号と一致するとは限りません（[docs/lambda-expansion-qa.md](lambda-expansion-qa.md) の Q13）。

```csv
at fx.lambda.Holder.viaField(Holder.java:27),OrderDaoImpl.describe,RESOLVED:DATAFLOW_FIELD,1,Holder.viaField,OrderDaoImpl.describe
```

ラムダを作った箇所からは、内部では必ず「生成した」1 本の辺を張ります。
どこで実行されるか分からないラムダでも、本体の中の呼び出しが階層から落ちないようにするためです。
CSV にはラムダの合成メソッド（`lambda$…`）の行を出しません（ソースに書いていない、コンパイラが作る暗黙のメソッドだからです）。
本体の中の呼び出しは、ラムダを実行するメソッド（ラムダを作ったメソッド、または引数で渡した先で `r.run()` するメソッド）の
直下に出ます。`depth` 列と `call-hierarchy` 列にもラムダの段は入りません（Eclipse の呼び出し階層と同じ見え方）。
同じ本体へ降りる辺（作った辺・規則の呼び戻し・`DATAFLOW_LAMBDA`）は 1 本の枝にまとめます
（[lambda-collapse-qa.md](lambda-collapse-qa.md)）。

実行箇所を特定できる形:

| 形 | 例 |
|---|---|
| ローカル変数に入れて呼ぶ | `Runnable r = () -> ...; r.run();` |
| 引数で渡した先で呼ぶ | `runIt(() -> ...)` の中の `r.run()` |
| フィールドに保持して呼ぶ | `private final Runnable task = () -> ...;` の `task.run()` |
| メソッド参照 | `Runnable r = this::helper; r.run();` → `helper` に繋がる |
| レシーバを束縛したメソッド参照 | `Runnable r = dao::describe; r.run();` → `dao` の具象型が分かればその実装（`OrderDaoImpl.describe`）に繋がる。分からなければ上書き候補（`UNEXPANDED:CHA`） |
| ラムダの戻り値に対する呼び出し | `Supplier<Dao> s = () -> new X(); s.get().describe();` → ラムダの `return` から `X.describe` に繋がる |
| ローカルのコレクションに詰めて拡張 for 文で回す | `jobs.add(() -> ...); for (Runnable j : jobs) j.run();` |

型名で書いたメソッド参照（`Consumer<Dao> c = Dao::describe;`）は、レシーバが呼び出し時の第 1 引数なので追わず、
上書き候補を全部出します（`UNEXPANDED:CHA`）。

特定できない形（`resolved-by` が `UNEXPANDED:LAMBDA` になります）:

- `list.forEach(Runnable::run)` のように、**jar の中**から呼ばれる形。`forEach` の中はソースが無いので辿れません
- フィールドのコレクションに詰める形、詰める場所と回す場所が別メソッドの形
- 同じ変数に複数のラムダが入りうる形（どれが実行されるか決められないので、絞りません）

特定できない場合でも、ラムダを作ったメソッドの直下に本体の中の呼び出しが出ます。

ラムダが捕捉した囲みメソッドの引数（`(Dao dao) -> … () -> dao.describe()` の `dao`）の具象型は、
ラムダを作ったメソッドの段でだけ当てます。引数で渡した先から本体へ降りたときは、その先の引数は
捕捉した値ではないので絞りません（捕捉した値はラムダを作った時点で決まります）。

`new Thread(task).start()` や `executor.submit(task)` のように、**jar の中から呼び戻される**形は、

---

## まとめ

| 問い | 答え |
|---|---|
| しきい値は何か | 値が**解析時に有限個の定数へ畳み込めるか**。動的機構の有無ではない |
| リフレクションは壁か | いいえ。形が定型なので拾える |
| 何が本当の壁か | **文字列演算**と、**外部入力**（設定ファイル・DB・リクエスト） |
| プールは壁か | 見た目ほどではない。メモ化なので、生成側が解ければ答えは同じ |
| 限界は消せるか | 消せない（決定不能）。消せるのは**限界が見えないこと** |
| 落としどころ | 健全側に倒し、絞れなかった理由を必ず出力し、**人間が知っている規則は外から与える** |
