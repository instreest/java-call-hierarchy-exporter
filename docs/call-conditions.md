# 呼び出しに効いている条件を調べる（`conditions.target`）

「この呼び出しは、どういうときに起きるのか」をその場で調べる機能です。
呼び出しを 1 つ（またはメソッド・ファイル単位で）選ぶと、**そこへ到達するために成立していなければ
ならない条件**を並べて表示します。

[条件分岐による打ち切り](branch-pruning.md)が「成立しないと**言い切れる**条件」だけを使うのに対して、
こちらは**言い切れない条件も含めて全部**出します。「なぜ打ち切られなかったのか」「実行時に何を
確認すればよいのか」を知るための機能です。

実装で迷った点は [call-conditions-qa.md](call-conditions-qa.md) にあります。

## 使い方

設定ファイル（`config/config.properties` など）に、調べたい対象を書きます。

```properties
project.root=/path/to/project
source.folders=src/main/java

# ここに書くと、この設定ファイルの実行は「条件の調査」になる
conditions.target=foo.Bar#method
```

あとは普段どおり実行するだけです。起動コマンドの対話モードから選んでもかまいません。

```bash
./java-call-hierarchy-exporter.sh config/investigate.properties
```

`conditions.target` が**空欄なら通常どおり呼び出し階層を出力**します。書いてあるときは調査だけを行い、
**CSV は書きません。キャッシュも出力フォルダも作りません。** 対象のファイルだけをその場でパースして、
画面（と、複数設定をまとめて流したときの一覧）に出すだけです。
普段の解析（`call-hierarchy.csv` / `methods.csv`）には何の影響もありません。

調べる対象を変えながら使うので、**普段の設定ファイルとは別に調査用の設定ファイルを1つ作っておく**のが
おすすめです（`project.root` と `source.folders` だけ揃っていれば動きます）。

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

## 出力の例

判定できる条件（打ち切りにも使われるもの）:

```
$ ./java-call-hierarchy-exporter.sh config/investigate.properties     # conditions.target=fx.branch.Feature#mode

条件の調査: fx.branch.Feature#mode（conditions.target）
  この設定では CSV を書きません。キャッシュも出力フォルダも作りません。
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
```

判定できない条件（静的には値が決まらないもの）:

```
# conditions.target=fx.excluded.Ping#a

src/fx/excluded/Ping.java:7  Ping.a(int) → Pong.b(int)
    1. [判定不可] n > 0

該当した呼び出し: 1 件（条件つき 1 件 / うち判定できない条件を含む 1 件）
```

### 読み方

- 並んでいる条件は**すべて成立して初めて**その呼び出しに到達します（論理積）。**外側の条件から内側の順**に並びます
- `[判定可]` … 経路ごとの値と突き合わせられる条件。呼び出し元からコンパイル時定数が渡れば、
  呼び出し階層の出力で打ち切られます（[branch-pruning.md](branch-pruning.md)）。
  行末に「判定対象の由来（`引数1` / `定数`）」と「成立する値」が付きます
- `[判定不可] `… 到達には効くが、静的には値が決まらない条件。
  メソッドの戻り値・フィールドの状態・範囲比較・`null` 判定・ループ・`catch`・
  コロン形式の `switch` がここに入ります。**実行時の値を人が確認すべき箇所**です
- `… これ以上の条件は記録していません（上限 32 件）` が出たときは、さらに外側に条件があります
  （深い入れ子。この印が無ければ、表示されているもので全部です）

### 対象が見つからないとき

`conditions.target` に書いた対象のファイルが無い場合や、指定に合う呼び出しが1件も無い場合は、
**その設定ファイルの処理は失敗**として扱います（終了コード 1。複数の設定ファイルをまとめて渡したときは
一覧に `FAIL` と出ます）。設定の書き間違いに気付けるようにするためです。
行やメソッド名の指定を外すと、そのファイルの呼び出しを全件出します。

## この先の拡張（まだやっていないこと）

この機能は「1 か所の深掘り」に絞っています。次の 2 つは**必要性が確認できてから**進める前提です。

| やること | 必要になる条件 |
|---|---|
| 全件を CSV に出す（`call-conditions.csv`） | 棚卸しの需要が確認できたら。その際は**本体とは別のキャッシュ**に置き、本体のキャッシュ形式の版を巻き込まない（[call-conditions-qa.md](call-conditions-qa.md) の Q1・Q2） |
| 早期 `return` / `throw` の後ろまで条件に含める | 制御フローグラフ（CFG）の構築が必要で規模が別物。現在の条件一覧では足りないと分かってから |
