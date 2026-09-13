# 呼び出しに効いている条件を出す（`conditions.target`）

「この呼び出しは、どういうときに起きるのか」を調べるための出力です。
呼び出しを 1 つ（またはメソッド・ファイル単位で）選ぶと、**そこへ到達するために成立していなければ
ならない条件**を一覧にします。

設定ファイルに `conditions.target` を書くだけで、**普段の解析（キャッシュの更新・`call-hierarchy.csv` /
`methods.csv` の出力）はそのまま行い、そのうえで追加で `call-conditions.csv` を書きます**。
モードが切り替わるわけではなく、出力が 1 つ増えるだけです。

[条件分岐による打ち切り](branch-pruning.md)が「成立しないと**言い切れる**条件」だけを使うのに対して、
こちらは**言い切れない条件も含めて全部**出します。「なぜ打ち切られなかったのか」「実行時に何を
確認すればよいのか」を知るための出力です。

実装で迷った点は [call-conditions-qa.md](call-conditions-qa.md) にあります。

## 使い方

設定ファイル（`config/config.properties` など）に、調べたい対象を 1 行足します。

```properties
project.root=/path/to/project
source.folders=src/main/java

# 普段の出力に追加して、この呼び出しの条件を出す
conditions.target=foo.Bar#method
```

あとは普段どおり実行するだけです。起動コマンドの対話モードから選んでもかまいません。

```bash
./java-call-hierarchy-exporter.sh config/config.properties
```

出力フォルダにできるもの:

| ファイル | 内容 |
|---|---|
| `call-hierarchy.csv` / `methods.csv` | これまでどおりの解析結果（`conditions.target` を書いても変わりません） |
| `call-conditions.csv` | **追加**。`conditions.target` に合う呼び出しと、そこに効いている条件 |

`conditions.target` が空欄なら `call-conditions.csv` は作りません。それ以外は何も変わりません
（キャッシュの更新も、CSV の中身も、処理時間もほぼ同じです）。
条件の一覧は画面と実行ログ（`run.log`）にも同じ内容が出るので、その場で読むこともできます。

### 対象の指定（`conditions.target`）

| 書き方 | 意味 |
|---|---|
| `src/foo/Bar.java` | そのファイルの呼び出し全部 |
| `src/foo/Bar.java:120` | その行の呼び出しだけ |
| `foo.Bar` | 型（`foo/Bar.java` に読み替えます） |
| `foo.Bar#method` | その型の `method` の中に書かれている呼び出しだけ |

- ファイルは**相対パスの末尾一致**で探すので、`Bar.java` のような短い指定でもかまいません（複数当たれば全部調べます）
- メソッド名は**呼び出し元**で絞ります（「このメソッドの中で何が呼ばれるか」）
- 内部クラス（`Outer.Inner` / `Outer$Inner`）は外側のファイルに読み替えます

## `call-conditions.csv`

`conditions.target=fx.branch.Feature#mode` を指定したときの出力です。

```csv
file,line,caller,callee,condIndex,decidable,condition,subjectKind,expectation
src/fx/branch/Feature.java,29,Feature.mode(String),String.equals(Object),,,,,
src/fx/branch/Feature.java,30,Feature.mode(String),Feature.full(),1,1,"""full"".equals(name)",引数1,= full
src/fx/branch/Feature.java,32,Feature.mode(String),Feature.light(),1,1,"""full"".equals(name)",引数1,≠ full
src/fx/branch/Feature.java,32,Feature.mode(String),Feature.light(),2,1,"""light"".equals(name)",引数1,= light
```

| 列 | 内容 |
|---|---|
| `file` / `line` | 呼び出しが書かれている場所 |
| `caller` / `callee` | 呼び出し元と呼び出し先 |
| `condIndex` | 条件の番号（**外側から内側の順**）。条件が無い呼び出しは空 |
| `decidable` | `1`＝判定できる条件、`0`＝判定できない条件 |
| `condition` | ソースに書かれていた条件式 |
| `subjectKind` / `expectation` | 判定対象の由来（`引数1` / `定数`）と、成立する値（`= full` など）。`decidable=0` のときは空 |

1 行＝「呼び出し 1 件に効いている条件 1 つ」です。条件が無い呼び出しも
「条件なしで必ず実行される」ことが分かるように 1 行出ます。
並んでいる条件は**すべて成立して初めて**その呼び出しに到達します（論理積）。

## 画面・`run.log` の表示

```
=== 追加: 呼び出しに効いている条件（conditions.target=fx.branch.Feature#mode） ===
対象: fx.branch.Feature#mode
解析したファイル: src/fx/branch/Feature.java（呼び出し 26 件）

src/fx/branch/Feature.java:29  Feature.mode(java.lang.String) → String.equals(java.lang.Object)
    （条件なし。このメソッドに入れば必ず実行される）

src/fx/branch/Feature.java:30  Feature.mode(java.lang.String) → Feature.full()
    1. [判定可] "full".equals(name)   … 引数1 = full

src/fx/branch/Feature.java:32  Feature.mode(java.lang.String) → Feature.light()
    1. [判定可] "full".equals(name)   … 引数1 ≠ full
    2. [判定可] "light".equals(name)   … 引数1 = light

該当した呼び出し: 4 件（条件つき 3 件 / うち判定できない条件を含む 0 件）
呼び出しの条件: …/call-conditions.csv（5 行）
```

判定できない条件は次のように出ます（`decidable=0`）。

```
src/fx/excluded/Ping.java:7  Ping.a(int) → Pong.b(int)
    1. [判定不可] n > 0
```

### 読み方

- `[判定可]`（`decidable=1`）… 経路ごとの値と突き合わせられる条件。呼び出し元からコンパイル時定数が
  渡れば、呼び出し階層の出力で打ち切られます（[branch-pruning.md](branch-pruning.md)）
- `[判定不可]`（`decidable=0`）… 到達には効くが、静的には値が決まらない条件。
  メソッドの戻り値・フィールドの状態・範囲比較・`null` 判定・ループ・`catch`・
  コロン形式の `switch` がここに入ります。**実行時の値を人が確認すべき箇所**です
- `… これ以上の条件は記録していません（上限 32 件）` が出たときは、さらに外側に条件があります
  （深い入れ子。この印が無ければ、出ているもので全部です）

### 対象が見つからないとき

`conditions.target` に書いた対象のファイルが無い場合や、指定に合う呼び出しが 1 件も無い場合は、
**警告を出して `call-conditions.csv` を作りません**。解析そのものは成功で、通常の出力は揃います
（追加の出力が空振りしただけなので、実行は失敗にしません）。
行やメソッド名の指定を外すと、そのファイルの呼び出しを全件出します。

## この先の拡張（まだやっていないこと）

この出力は「1 か所の深掘り」に絞っています。次の 3 つは**必要性が確認できてから**進める前提です。

| やること | 必要になる条件 |
|---|---|
| 対象を指定せず、プロジェクト全件の条件を出す | 棚卸しの需要が確認できたら。その際は条件を**本体とは別のキャッシュ**に置き、本体のキャッシュ形式の版を巻き込まない（[call-conditions-qa.md](call-conditions-qa.md) の Q1・Q2） |
| キャッシュを前提にした個別解析にする | 今は対象のファイルだけをその場でパースし直している。条件をキャッシュに持てば再パースが要らなくなる（同上） |
| 早期 `return` / `throw` の後ろまで条件に含める | 制御フローグラフ（CFG）の構築が必要で規模が別物。現在の条件一覧では足りないと分かってから |
