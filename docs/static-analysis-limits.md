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
| 40行目 `DaoFactory.byName("fx.dao.OrderDaoImpl").findById(3L)` | `[RESOLVED:DATAFLOW_FACTORY]` |

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

- `[UNEXPANDED:CHA] N candidates: <reason>` … 絞れなかったことと、その理由（`grep '\[UNEXPANDED'` で一括で拾える）
- `(unresolved)` の行と件数のログ … クラスパス不足を「呼び出しが無い」と誤読させない
- `Origin.UNKNOWN` … 「分からない」を型として明示的に持つ

`feature-difficulty.md` でも、どこまで機能を削っても
**1-9（型解決失敗を行として残す）と 2-2（絞れなかった理由の注記）だけは残す**ことにしている。

---

## 7. では、解けないものをどうするか

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
