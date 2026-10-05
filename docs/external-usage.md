# jar からの被参照を調べる

自分のコードを呼んでいる**ほかのリポジトリの jar**（別チームのバッチ・画面など）から、どのメソッドが参照されているかを
`call-hierarchy.csv` に足す機能の使い方。出力の行の読み方は [README の「jar からの被参照メソッド」](../README.md#jar-からの被参照メソッド)にある。
実装時に迷った点は [external-usage-callsite-qa.md](external-usage-callsite-qa.md) と [fatjar-external-usage-qa.md](fatjar-external-usage-qa.md) にある。

## 指定のしかた

自分のコードを呼んでいる側の jar を設定ファイルの `external.library.folders` に指定すると、
`call-hierarchy.csv` に追記されます。

```properties
external.library.folders=./lib
```

class ファイルの命令列を読むため、「どの jar・どのクラスの**どのメソッドの何行目**から参照しているか」まで分かります。
`caller` 列は呼び出し階層の行と同じスタックトレース形式なので、Eclipse の Java スタック・トレース・コンソールに貼れば
（相手のソースがワークスペースにあれば）その行へ飛べます。起点も階層も無いので `call-hierarchy` の先頭の列（起点の列）には参照元の jar 名が入り、`resolved-by` は `EXTERNAL_USAGE:` で始まり、`depth` は `1` です。
ラムダ式やメソッド参照（`Counter::bump`）からの参照も、それを書いた行として出ます。

```csv
caller,callee,resolved-by,depth,call-hierarchy
at teamb.NightJob.run(NightJob.java:15),OrderService.findOrder,EXTERNAL_USAGE:EXACT,1,team-b-batch.jar,OrderService.findOrder,external-ref:EXACT
at teamb.NightJob.run(NightJob.java:14),OrderService.OrderService,EXTERNAL_USAGE:EXACT,1,team-b-batch.jar,OrderService.OrderService,external-ref:EXACT
at teamb.NoDebugJob.run(Unknown Source),OrderService.findOrder,EXTERNAL_USAGE:EXACT,1,team-b-batch.jar,OrderService.findOrder,external-ref:EXACT
```

行番号は相手の jar が行番号情報付きでビルドされている（`javac` の既定）ときだけ出ます。
`-g:none` でビルドされた jar は、JVM のスタックトレースと同じく `(Unknown Source)` になります（メソッド名までは出ます）。

`external.library.folders` に指定したフォルダに自プロジェクトの jar が混ざっていても、
それは「他リポジトリからの被参照」ではないので読み飛ばします。
除外した件数は実行ログに出ます。
`dist` を丸ごと指定しても、自分から自分への呼び出しが被参照として出ることはありません。


## 行の種別

| 種別（`call-hierarchy` 列の末尾） | 意味 |
|---|---|
| `external-ref:EXACT` | そのクラスで宣言されているメソッド（暗黙のデフォルトコンストラクタを含む）への参照 |
| `external-ref:INHERITED` | 親から継承したメソッドへの参照。JVM がその参照を解決する宣言（親クラスの連鎖を先に、無ければインターフェースの宣言のうち最も特定的なもの。インターフェースの `static`・`private` は除く）のメソッドとして出る |
| `external-ref:IMPLICIT_CTOR` | 引数なしコンストラクタへの参照で、ソース上に一致する宣言が無いもの。暗黙のデフォルトコンストラクタは解析時に宣言として合成され `EXACT` で照合されるため、ここに来るのは「相手の jar をビルドした時点では引数なしで生成できたが、今のソースにはそのコンストラクタが無い」形、つまり版違いの可能性が高い。生成箇所として有用なので行として残す |

自分の型を参照しているのに一致するメソッドが無いもの（引数付きのコンストラクタを含む）は、
相手の jar が古い版に対してビルドされている可能性があります。件数のみ実行ログに出力されます。
非 static な内部クラスのコンストラクタは、バイトコード上は外側インスタンスが引数に付くため

設定の詳しい書き方（FatJar・war・ear の中の jar も開くこと、サブフォルダも見ること）は
[config/jche.properties](../config/jche.properties) の `external.library.folders` のコメントにある。

## methods.csv との対応

被参照の行があるメソッドは、`methods.csv` の最終列 `externalRefs` にその行数が入り、`inHierarchy` が `1` になる
（被参照の行は `call-hierarchy.csv` にあるので「1 行でも出た」に当たる）。`role` はソースの中の呼び出しだけで決めるので、
`ISOLATED` のまま `externalRefs` が `1` 以上なら「ソースからは呼ばれないが、他の jar からは呼ばれる」と読む。
`IMPLICIT_CTOR` の行はソース上の宣言に当たらないので数えない。

## 検出できない形

被参照は、class ファイルの命令が指す**受け手の静的型（owner）が自分の型**であるときだけ照合する。次の形は出ない。

- **相手の jar の中で宣言したサブクラス経由の呼び出し**。相手が `class TheirSub extends OurBase` を持ち、
  `theirSub.ourMethod()` と呼ぶと、命令の owner は `TheirSub`（相手の型）になるので、`OurBase.ourMethod` への
  参照とは見なされない。相手の jar の中の `TheirSub` から `OurBase` への継承は読んでいない
- **第三者のインターフェース経由の呼び出し**。自分の型が `java.lang.Runnable` や相手のインターフェースを実装していて、
  相手がその型で受けて `r.run()` と呼ぶと、owner はそのインターフェースになるので同じく出ない
- **クラスフォルダ**（`bin/` や `target/classes/` のように `.class` が直に置かれたフォルダ）は走査しない。
  `external.library.folders` に `.class` だけで jar の無いフォルダを指すと、何も走査せず `warnings.txt` に載る。
  相手の jar（war / ear も可）を指すか、jar に固めてから指す

どれも「被参照が 0 件」を「使われていない」と読まないための注意点で、相手の jar の中の継承・実装まで読む対応は
していない（相手のソースがワークスペースにあるなら `workspace.projects` で相手の呼び出し階層そのものを出せる）。
