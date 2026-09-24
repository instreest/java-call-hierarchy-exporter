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

式の「出所」を記号で持つ（`src/jche/cache/Origin.java`）。

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
| 文字列が64文字を超える | 出所に載せる値の長さの上限（`OriginTracker.MAX_VALUE_LENGTH`） |
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
- `Origin.UNKNOWN` … 「分からない」を型として明示的に持つ

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
| オーバーライド | JLS 8.4.2 のサブシグネチャまで見る。型引数を具体化した実装（`class UserRepo implements Repo<User>`）は、消去したキーが食い違うので**上書き関係を事実として残して**照合する（O 行）。呼び戻しの契約表とリフレクションは所有型を知らないので、同じ照合をシグネチャで行う |
| 暗黙のコンストラクタ呼び出し | 書かれていない `super()`（JLS 8.8.7 / 8.8.9）も辺にする。ただし親が `java.lang.Object` / `Enum` / `Record` のときは張らない（辿る先が無い） |
| try-with-resources | 暗黙の `close()`（JLS 14.20.3.1）も辺にする。呼び出し先はリソースの静的型の `close()` で、レシーバはリソースの変数（`new`・ファクトリ・フィールドの出所で絞れる）。宣言と逆の順に、リソースを書いた行で記録する |
| 条件の値 | 値を変えうるキャスト（JLS 5.1.2 / 5.1.3 / 5.1.7 / 5.1.8）が挟まった式は**判定しない**。`char` は二項数値昇格（JLS 5.6.2）に合わせて数値で持つ。浮動小数は表記が揺れるので拾わない |
| record の暗黙メンバ | 正準コンストラクタは合成するが、**アクセサ（JLS 8.10.3）・`equals` / `hashCode` / `toString` は合成しない**。呼び出しは `[EXTERNAL] no source to follow` と出る。本体がソースに無いので辿る先は無いが、「ソースにある型なのに EXTERNAL」と見えるのは正確ではない |
| 準拠レベル | `source.level` を指定しなければ JDT が対応する最大版で読む。**プレビュー機能は有効にしない**ので、プレビュー段階の構文は構文エラーになる（`syntax errors` として件数が出る）。`var` の使い方の誤り（Java 10 より前のコードの `class var` など）は本体を読めているので構文エラーには数えず、コンパイルエラーとして案内する |

「ソースに書いてあるのに辺が無い」を見つけたら、値の話ではなくこちらを疑うこと。

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

## まとめ

| 問い | 答え |
|---|---|
| しきい値は何か | 値が**解析時に有限個の定数へ畳み込めるか**。動的機構の有無ではない |
| リフレクションは壁か | いいえ。形が定型なので拾える |
| 何が本当の壁か | **文字列演算**と、**外部入力**（設定ファイル・DB・リクエスト） |
| プールは壁か | 見た目ほどではない。メモ化なので、生成側が解ければ答えは同じ |
| 限界は消せるか | 消せない（決定不能）。消せるのは**限界が見えないこと** |
| 落としどころ | 健全側に倒し、絞れなかった理由を必ず出力し、**人間が知っている規則は外から与える** |
