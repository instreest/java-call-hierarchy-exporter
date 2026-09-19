# 解決条件の指定を契約表に一本化する設計（種類 C）

> 現状の使い方は [callback-contracts.md](callback-contracts.md)（契約表）と
> [instance-analysis-plugin.md](instance-analysis-plugin.md)（インスタンス解析条件の拡張）にある。
> この文書は**まだ実装していない設計案**で、決めたことと決めきれていないことを残すためのもの。

利用者が「解決の条件」をツールに与える口は、いま 2 系統に分かれている。

| 系統 | 与えるもの | 指定の形 |
|---|---|---|
| 契約表 | jar の中から呼び戻される呼び出し（種類 A）、フレームワークが呼ぶ入口（種類 B） | `contracts.files` に 1 行 1 契約のテキスト |
| インスタンス解析条件 | CHA で絞れない呼び出しの具象クラス | `resolver.*` に拡張クラスの FQN ＋ `plugin.*` にその引数 ＋ 別ファイルの対応表 |

後者の指定が重い。**具象クラスの対応も契約表の 1 行で書けるようにして（種類 C）、
利用者から見た指定口を「契約表 1 つ」にする**のがこの案である。

> **実装状況**（2026-09-19）: **§9 の段1 まで完了**。`=>` の振り分けと C-1（宣言型）・C-2（型#メソッド名）が
> 動く（`jche.graph.TypeContracts`。ラベルは `[RESOLVED:CONTRACT]`）。C-3（ファクトリ＋キー）は未実装で、
> その形の行は専用の警告を出して読み飛ばす。効いたかの報告（`jche.graph.ContractUsage`）には
> 種類 C もそのまま乗っている。検査は `test/regression` の plugin ケース（5・6 回目）と
> `test/contracts/run.sh`。

---

## 1. 何が難しいのか

「`jp.co.app.ServiceFactory.get("user")` は `UserService` を返す」という**事実 1 個**を伝えるために、
いまは 3 ファイル・5 か所を揃える必要がある。

```properties
# config.properties
resolver.hint.collectors=jche.builtin.FactoryKeyCollector        # ①内部クラスの FQN（フェーズA）
plugin.factory.methods=jp.co.app.ServiceFactory#get              # ②①への引数
resolver.candidate.providers=jche.builtin.TypeMappingProvider    # ③内部クラスの FQN（フェーズB）
plugin.mapping.files=mapping.properties                          # ④③への引数
```
```properties
# mapping.properties
FACTORY_KEY@user = jp.co.app.impl.UserService                    # ⑤事実そのもの
```

利用者が言いたいのは⑤だけで、①〜④は**ツールの内部分割（フェーズ A / B）と実装クラス名が
そのまま設定に漏れ出したもの**である。ここから次の困りごとが出ている。

| # | 症状 | 仕様側の原因 |
|---|---|---|
| 1 | 2 つのキーを**セットで**書かないと効かない | フェーズ A / B の区別が設定に露出している |
| 2 | `jche.builtin.*` という内部クラス名を覚えて書く | 設定の単位が「やりたいこと」ではなく「実装の有効化」になっている |
| 3 | 区切りが `@` `#` `->` `:` `,` と場当たり | `properties` が `:` と `=` を区切りに使うため（instance-analysis-plugin-qa.md の Q16） |
| 4 | 同じ型を返すファクトリを並べると**誤った 1 件に確定しうる** | 証拠の種別が収集器あたり 1 つ（`plugin.factory.hint.kind`）しか持てない |
| 5 | キーを定数で渡していると**定数の単純名**で書く必要がある | `FactoryKeyCollector` が定数の値まで追わない |
| 6 | 「どのファクトリから来た値か」で条件を書けない | `candidates(declaredType, signature, hints)` にファクトリが渡らない |

症状 4・6 のために、[instance-analysis-plugin.md](instance-analysis-plugin.md) の §3c には
「自前のフェーズA拡張を書いて証拠の種別にファクトリ名を入れる」という**回避策**が長々と書いてある。
これは書式の問題ではなく、**指定の単位が事実になっていない**ことの帰結である。

---

## 2. 方針

> **利用者には「事実」だけを書かせ、どの拡張ポイントを使うかはツールが決める。**

契約表はすでに「1 行 1 事実 / 行の形で自動振り分け / 読めない行は警告 / 効いたかを報告」という
欲しい性質を持っている（`jche.graph.Contracts`）。インスタンス解析条件だけがその器の外にあるので、
**種類 C として同じ器に入れる**。

自前の拡張（`TypeCandidateProvider` / `CallSiteHintCollector` / `ContractProvider`）は**残す**。
消すのは「表に落ちるのに拡張の設定を書かせる」部分だけで、算出規則のように表へ落ちない条件は
引き続き Java で書く（§8）。

---

## 3. 種類 C の書式

```
# contracts.txt — A も B も C も同じファイルに 1 行 1 事実
jp.co.app.Dispatcher#submit(java.lang.Runnable) -> a0 : run()        # A 呼び戻し（現状のまま）
@jp.co.app.Endpoint                                                  # B 入口（現状のまま）

jp.co.app.dao.UserDao         => jp.co.app.dao.UserDaoImpl           # C-1 宣言型
jp.co.app.dao.UserDao#find    => jp.co.app.dao.CachedUserDao         # C-2 宣言型#メソッド名
jp.co.app.ServiceFactory#get("user") => jp.co.app.impl.UserService   # C-3 ファクトリ＋キー
jp.co.app.ServiceFactory#get("order") => jp.co.app.impl.OrderService, jp.co.app.legacy.OrderImpl
```

| 形 | 左辺 | 意味 |
|---|---|---|
| C-1 | 宣言型の FQN | その型で宣言された呼び出しは、この具象型に解決する |
| C-2 | 宣言型 FQN `#` メソッド名 | 同じ型でもメソッドによって実装が違う場合 |
| C-3 | ファクトリの型 FQN `#` メソッド名 `(` キー `)` | そのファクトリにそのキーを渡して得た値は、この具象型 |

- 右辺はカンマ区切りで複数書ける（絞り切れないとき。現状の対応表と同じ）。ただし
  **1 件に絞れたときだけ展開される**規則は変わらないので、複数書いた箇所は `[UNEXPANDED:CHA]` になる
- 行の振り分けは `=>` を含むかで見る。`"=>"` は `"->"` を含まないので、既存の A 判定
  （`line.contains("->")`）と衝突しない。実装では `=>` を先に見る
- C-3 のキーは**呼び出し箇所に書かれている文字列**。引用符で囲む。コンパイル時定数で渡していても
  JDT が値まで評価するので、**定数の単純名ではなく値**で書く（§5。現状の対応表と変わる点）
- 左辺の型は省略できない。現状の `plugin.factory.methods` はメソッド名だけの指定を許しているが、
  広く当たりすぎて誤解決の元なので、種類 C では常に型を書かせる（意図的な機能縮小）

### 何が要らなくなるか

| 消えるもの | 理由 |
|---|---|
| `resolver.hint.collectors` / `resolver.candidate.providers` の記述 | 表で済む用途では、どの拡張を使うかをツールが決める |
| `plugin.factory.methods` | C-3 行の左辺がそのまま対象ファクトリの指定 |
| `plugin.mapping.files` と `mapping.properties` | 契約表に統合 |
| `plugin.factory.hint.kind` と `FACTORY_KEY@` という中間概念 | 利用者から見えなくなる |

§1 の症状 4・6 は**構造的に起きなくなる**。どのファクトリ由来かが行の左辺に書いてあるので、
証拠の種別が衝突しようがない。§3c の回避策は丸ごと不要になる。

---

## 4. 判定の順序

複数の C 行が当たりうるときは、**より具体的な行が勝つ**。現状の `TypeMappingProvider` の
引き順（証拠 → 型＋メソッド → 型。instance-analysis-plugin-qa.md の Q18）をそのまま使う。

```
C-3（ファクトリ＋キー） → C-2（型#メソッド） → C-1（型）
```

拡張を複数書いたときの「先に候補を返したものが勝つ」という**設定の並び順への依存**（`CallResolver.askProviders`）は、
この順序に置き換わって消える。

解決の段（[README の「具象クラスの解決」](../README.md#具象クラスの解決)）では、**段3（拡張と同じ位置）**に入れる。
利用者が明示した条件を、ツールの推測（段4 データフロー・段5 Spring DI）より先に効かせるという
既存の判断（instance-analysis-plugin-qa.md の Q24）を引き継ぐ。段3 の中では
**契約表 → 拡張**の順とする（表のほうが読み手に見えるので、食い違ったときに追いやすい）。

採用できない候補（その型にも親にもその本体が無い）を警告して CHA に戻す扱いは、現状の
`askProviders` と同じにする。返した FQN が親からメソッドを継承しているだけでも
`graph.implementationIn` が本体まで辿る点も同じ。

---

## 5. 証拠はすでにキャッシュにある（この設計の肝）

当初は「C-3 はフェーズA（AST 走査中の証拠採取）が要る」と考えていたが、**要らない**。
ファクトリに渡されたキーは、すでに dataflow キャッシュに入っている。

- `jche.analysis.ValueGraph` は、メソッド呼び出しを
  `node(Origin.RETURN, メソッドキー, レシーバ, 実引数のノード番号, 引数の数)` として記録する。
  **戻り値のノードが、その呼び出しの実引数のノード番号を持っている**
- 文字列リテラルは `Origin.LITERAL` のノードとして値そのものを持つ。長さも内容も問わない
- `resolveConstantExpressionValue()` を通すので、`Keys.USER`（`static final String`）や
  `"a" + "b"` のような**コンパイル時定数も値に評価されてから**同じ種別のノードになる
- 値グラフには深さの上限が無い（`jche.cache.ValueNode` の「`Origin` との違い」と、
  `ValueGraph` の「レシーバの段数に上限を設けない」）
- 呼び出し箇所ごとの実引数は `jche.cache.CallSiteValues`（P 行）にも記録されている

しかも**同じ形の判定が既に動いている**。`DataflowResolver.applyInvocationArgs` は
「レシーバは `Class.forName(引数)` の戻り値 → その規則が指す位置の実引数が `LITERAL` なら、
その文字列を型として扱う（ただし `graph.hierarchy.contains` で実在を確かめ、見た目だけで決めない）」
という規則を持っている。
種類 C-3 は、この組み込みの規則を**利用者が書いた表に差し替えた**ものにあたる。

この結果、設計が大きく単純になる。

| | 当初の想定 | 実際 |
|---|---|---|
| フェーズAの収集器 | C-3 のために必要 | **不要**。フェーズ2で dataflow キャッシュを読むだけ |
| 契約表の読み込み位置 | フェーズ1より前に前倒しが必要 | **現状のまま**（`Exporter` のフェーズ2、グラフ構築後） |
| キャッシュの指紋 | C-3 行を入れる必要がある | **不要**。契約表はキャッシュの中身に影響しない（§6） |
| `ContractProvider` の読み込み | フェーズ1より前に前倒しが必要 | **現状のまま** |

**制約**: C-3 は `dataflow.enabled=true` が前提になる（既定は true）。false のときは C-3 行を
読み飛ばして 1 回警告する。C-1・C-2 は宣言型だけで引けるのでデータフローに依存しない。

---

## 6. キャッシュとの関係

契約表は読み手（フェーズ2以降）だけが使い、キャッシュに書く事実には影響しない。
この性質（callback-contracts-qa.md の Q13）は**種類 C を足しても変わらない**。

| 変えたもの | 再解析 |
|---|---|
| 契約表（A / B / C のどれでも） | **起きない**。読み直すだけで次の実行に反映される |
| フェーズAの拡張とその設定 | 起きる（指紋がキャッシュのヘッダ行に入る） |
| フェーズBの拡張・対応表 | 起きない |

§5 のとおり C-3 がフェーズAを必要としないので、「表を 1 行足したら全件解析し直し」にはならない。
対応表を育てながら何度も回す使い方（これが本来の使い方）で、待ち時間が増えない。

---

## 7. 既存の指定との関係

旧来の指定は**読み続ける**。`resolver.hint.collectors` / `resolver.candidate.providers` /
`plugin.*` / `mapping.properties` はそのまま動き、種類 C と併用もできる。

| 旧 | 新（種類 C） |
|---|---|
| `mapping.properties` の `jp.co.xxx.UserDao = jp.co.xxx.UserDaoImpl` | `jp.co.xxx.UserDao => jp.co.xxx.UserDaoImpl` |
| `jp.co.xxx.UserDao#find = jp.co.xxx.CachedUserDao` | `jp.co.xxx.UserDao#find => jp.co.xxx.CachedUserDao` |
| `plugin.factory.methods` ＋ `FACTORY_KEY@USER_DAO = …` | `jp.co.xxx.DaoFactory#get("USER_DAO") => …` |

旧キーが使われたときは、1 回だけ新しい書き方を案内するログを出す（削除はしない）。
`docs/` は「新しい書き方」を主にし、旧来の指定は移行の節に落とす。

CSV のラベルは**新しく `CONTRACT` を起こす**ことを勧める（`[RESOLVED:CONTRACT]`）。
`MAPPING` は `TypeMappingProvider` のラベルとして残るので、由来が CSV から区別できるほうが
調査で役に立つ。既存の期待出力は、`test/regression/plugin` を種類 C 版の**別ケースとして足す**限り
変わらない（既存ケースは旧来の指定の回帰として残す）。

---

## 8. この設計で解決しないこと

- **キーを列挙できない場合**。`"jp.co.app.impl." + capitalize(key) + "Service"` のような
  算出規則は、引き続きフェーズBの拡張に書く（instance-analysis-plugin.md の §3b）。
  種類 C は「列挙できる対応」のための形であって、拡張の置き換えではない
- **独自形式の DI 設定ファイルを読む**用途。`ContractProvider` か `TypeCandidateProvider` のまま
- **`[UNEXPANDED:CHA]` から設定への導線**（改善3・ひな形の自動生成）。これは別の作業で、
  種類 C の書式が決まってから着手する
- **フェーズAの拡張の指紋が `plugin.` という命名規約に依存している問題**（改善6）。
  種類 C はフェーズAを使わないので、この穴は残る。自前のフェーズA拡張が
  `demo.factory.methods` のような独自キーを読むと、そのキーを変えても再解析されない

---

## 9. 実装の段階と検査

| 段 | 内容 | 期待出力への影響 |
|---|---|---|
| 1 | `Contracts` の振り分けに `=>` を足し、C-1・C-2 を段3 で引く | 無し（新しいケースを足す） |
| 2 | C-3 を足す（dataflow キャッシュの実引数から引く） | 無し（同上） |
| 3 | `docs` を新しい書き方に寄せ、旧キーに案内ログを足す | 無し |

検査は既存の作りに乗せる。

- `test/contracts/run.sh`（同梱表の検査）に C 行の parse の検査を足す。形の違う行が弾かれること、
  右辺が FQN の形であることを見る
- `test/regression/` に種類 C のケースを足す。解析対象は `test/plugin-demo` を流用し、
  旧来の指定のケース（`plugin`）と**同じ CSV になる**ことを期待出力で確かめる。
  「指定の仕方を変えても結果は同じ」が、この作業のいちばん大事な性質
- 改善2 の報告（`ContractUsage`）に C 行も乗るので、「書いたのに引かれなかった C 行」は
  自動的に実行ログへ挙がる。専用の作り込みは要らない

---

## 10. 決めきれていないこと

実装に入るときに判断する。ここで決め打ちにせず、実際のコードを見て選ぶ。

1. **読み口**。C-3 の実引数を、`DataflowResolver.applyInvocationArgs` が使っている
   出所の文字列（`graph.recvOrigin` 経由。レシーバ 3 段・実引数 1 段の上限あり）から読むか、
   値グラフ（上限なし）から読むか。前者なら既存の経路に相乗りできる。後者のほうが追える形が広い。
   まず前者で書いてみて、`test/plugin-demo` の形が通らなければ後者に移る
2. **`dataflow.max.depth` との関係**。ファクトリの戻り値を受けた変数を何段も引き回したとき、
   どこまで辿れるか。上限に当たったら「絞れなかった」に倒す（現状と同じ安全側）
3. **キーが引数の何番目か**。C-3 の `#get("user")` は「実引数のどこかにその文字列がある」と読むか、
   「引数 0 がその文字列」と読むか。多引数のファクトリ（`get(scope, "user")`）のために
   `#get(_, "user")` のような位置指定を将来許す余地を残す書き方にしておく
4. **列挙定数のキー**。`get(Kind.USER)` は値グラフでは `Origin.CONST` に**列挙定数の名前**で入る。
   文字列リテラルと同じ `("USER")` で書かせるか、`(Kind.USER)` と書かせるか
5. **`contracts.builtin=false` との関係**。同梱の表に C 行は無いので影響しないが、
   将来 C の同梱行（よくあるフレームワークの DI 既定）を持つなら、そのときに決める

---

## 11. 却下した案

| 案 | 却下の理由 |
|---|---|
| 対応表を YAML / JSON にする | パーサの依存が増え（instance-analysis-plugin-qa.md の Q17）、DI 設定から `grep` と置換で機械的に書き出す用途が重くなる。困っているのは表現力ではなく**指定の単位** |
| `plugin.mapping.files` があれば自動で拡張を有効にする | 綴り間違いが「何も起きない」で終わる（同 Q4）。種類 C なら行そのものが構文として検査され、当たらなければ報告されるので、同じ問題が起きない |
| 種類 A / B / C をファイルで分ける | 利用者にとって「うちのフレームワークの契約」は 1 単位（callback-contracts-qa.md の Q12）。行の形で振り分ける現状の判断を変える理由が無い |
| 旧来の指定を消す | 既に使っている設定を壊す。読み続けたうえで、docs の主役を入れ替えるだけで足りる |
| 対話モード（`ConfigWizard`）に項目を足す | 対話で答えられるのはファクトリの FQN 程度で、肝心の「キーと実装の対応」は結局ファイルになる。同じ労力なら改善3（ひな形の自動生成）のほうが効く |
