# java-call-hierarchy-exporter
A tool that batch-extracts project-wide Java method call hierarchies and exports them to CSV files.

Javaプロジェクト全体のメソッド呼び出し階層を一括で抽出してCSVファイルに出力するツールです。

## Overview

Eclipseの「呼び出し階層」ビューを再帰的に一括出力するようなイメージでCSVファイルを出力します。
Eclipseは起動せず、解析エンジンに Eclipse JDT のコンパイラを使用するコマンドラインツールです。
出力CSVファイルをExcelで開いて呼び出し先メソッドをフィルタすることで影響範囲を抽出できます。

## ドキュメント

| 知りたいこと | 場所 |
|---|---|
| 最小の手順 | [Quick start](#quick-start)（このファイル） |
| 出力 CSV の読み方 | [出力ファイル](#出力ファイル)（このファイル） |
| 設定項目 | [config/config.properties](config/config.properties) のコメント |
| 起動コマンドの全仕様・JBang 直接実行・閉域ネットワーク | [docs/cli.md](docs/cli.md) |
| 依存 jar の自動取得（Maven / Gradle） | [docs/build-tool-classpath.md](docs/build-tool-classpath.md) |
| 具象クラスの解決条件を外から与える（プラグイン） | [docs/instance-analysis-plugin.md](docs/instance-analysis-plugin.md) |
| GitHub Actions から使う | [docs/github-actions.md](docs/github-actions.md) |
| キャッシュの設計 | [docs/cache-design.md](docs/cache-design.md) |
| 設計の記録（機能ごとに迷った点と結論）・再実装用の仕様 | [docs/README.md](docs/README.md) |

---

## Quick start

ツールを最小構成で試す手順です。初回実行時は JDK と依存モジュールのダウンロードが必要です。（通信量 約 165MB → 展開後 約 500MB）。

1. 設定ファイルを編集する … [`config/config.properties`](config/config.properties) の `project.root`（解析対象プロジェクトのフォルダ）をセットします。

2. 実行する … リポジトリ直下の起動コマンドに設定ファイルを引数で渡して実行します。

   ```bat
   rem Windows
   .\java-call-hierarchy-exporter.cmd config\config.properties
   ```

   ```bash
   # Linux / macOS / Git Bash
   ./java-call-hierarchy-exporter.sh config/config.properties
   ```

3. 結果を見る … （[出力ファイル](#出力ファイル)）

### Pleiades/Eclipse環境（閉域ネットワーク等の場合）

Pleiades/Eclipseがインストールされていれば、そこに含まれるJDT Core一式から、実行に必要なjarを `lib` フォルダに集めて使います。
バージョン部分はEclipseのバージョンによって変わるためワイルドカードでコピーします。

```bat
rem java-call-hierarchy-exporterをカレントディレクトリとしてください
rem 環境に合わせて次の2行を書き換えてください
set ECLIPSE_HOME=C:\pleiades\2026-06\eclipse
set JAVA_HOME=C:\pleiades\2026-06\java\17

rem　実行に必要なjarの収集
mkdir lib
for %P in (org.apache.xerces org.eclipse.core.contenttype org.eclipse.core.jobs org.eclipse.core.resources org.eclipse.core.runtime org.eclipse.equinox.common org.eclipse.equinox.preferences org.eclipse.jdt.core.compiler.batch org.eclipse.jdt.core org.eclipse.osgi org.osgi.service.prefs) ^
do copy "%ECLIPSE_HOME%\plugins\%P_*.jar" lib\

rem コンパイル（src\jche 配下のクラスも一緒にコンパイルされる）
"%JAVA_HOME%\bin\javac" -classpath lib\* -sourcepath src -d bin src\CallHierarchyExporter.java -encoding UTF-8

rem 実行
"%JAVA_HOME%\bin\java" -classpath bin;lib\* CallHierarchyExporter config\config.properties
```

---

## 出力ファイル

実行のたびに設定ファイルと同じフォルダに**`<解析開始日時>_<project.rootフォルダ名>`** のフォルダを作ってまとめます。

```
config/
├── config.properties             設定ファイル（既定。コピーして解析対象プロジェクトごとに増やすことを推奨）
└── 20260907-163000_myapp/        実行ごとの出力フォルダ
    ├── call-hierarchy.csv        呼び出し階層リスト
    ├── methods.csv               メソッド全体リスト
    ├── config.properties         この実行に使った設定ファイルの複製（渡したファイル名のまま）
    ├── run.log                   標準出力と同じ内容の実行ログ（UTF-8）
    └── resolved-classpath.txt    解析時の依存jar一覧と要求元
```

出力CSVファイルはUTF-8（BOM付き）なのでExcelで開けます。

### `call-hierarchy.csv` — 呼び出し階層

```csv
caller,callee,root,call-hierarchy
at jp.co.example.action.OrderAction.execute(OrderAction.java:50),jp.co.example.service.OrderService.findOrder(String),OrderAction.execute,OrderService.findOrder
at jp.co.example.service.OrderService.findOrder(OrderService.java:25),jp.co.example.dao.OrderDaoImpl.selectById(long),OrderAction.execute,OrderService.findOrder,OrderDaoImpl.selectById
```

| 列 | 内容 |
|---|---|
| `caller` | 呼び出し元。Javaのスタックトレースと同じ形式。**呼び出し箇所**の行を指す |
| `callee` | 呼び出し先。**完全修飾クラス名.メソッド名(引数型略名)**。Excelのフィルタに使える |
| `root` | 起点メソッド。クラス名.メソッド名の形式でExcelのフィルタに使える |
| `call-hierarchy` | 起点からの呼び出し先を1ノード1列で展開（**可変長**） |

コンストラクタの呼び出し自体は行になりません。
コンストラクタ内からのメソッド呼び出しは行として出力されます。

行順は、rootメソッドのクラス順（ソースフォルダ順 → 完全修飾クラス名順 → 宣言行順）、rootメソッドからの呼び出し順（深さ優先）です。
具象クラスの候補が複数ある呼び出しは候補ごとに 1 行で、宣言型自身の実装 → 下位型（直接の下位型は完全修飾クラス名順）の順に出ます。
末尾の `型解決に失敗（…）` の行はソースの並び順（ソースフォルダ順 → ファイルの相対パス順 → 呼び出し順）で出ます。
注記が付く場合は `call-hierarchy` の**最後の要素**として出ます。 （[注記](#注記)）

### `methods.csv` — ソース上の全メソッドとその呼び出し状況

| 列 | 内容 |
|---|---|
| `unresolvedCalls` | このメソッドの中で、具象クラスを1つに絞れなかった呼び出しの件数 |
| `unresolvedCause` | その理由（上の「理由」表と同じ。`実装なし（宣言のまま）` と `実装はコンパイル時生成（名前）` も入る。複数ある場合は `;` 区切り） |

| role | 意味 |
|---|---|
| `ENTRY_CANDIDATE` | 呼び出し元が無い。画面入口・デッドコード・テスト・リフレクション経由が混ざる |
| `ISOLATED` | 呼び出し元も呼び出し先も無い。デッドコードの疑いが濃い |
| `LEAF` | 呼び出し先が無い |
| `NORMAL` | 上記以外 |

行はソースの並び順（ソースフォルダ順 → ファイルの相対パス順 → 宣言行順）で出ます。
「よく呼ばれている共通処理」を探したいときは、`inDegree` 列でソート・フィルタしてください。
コンストラクタ（`<init>`）は出力しません。


### Eclipse でソースコードへジャンプする
`call-hierarchy.csv` の行をコピーし、Eclipseの「Javaスタック・トレース・コンソール」に貼り付けると、
`(ファイル:行数)` の部分がハイパーリンクになり、ソースコードへ飛べます。

1. メニューから ウィンドウ(Window) ＞ ビューの表示(Show View) ＞ コンソール(Console) を選択
2. コンソールビュー右上（ツールバー）の「コンソールのオープン(Open Console)」ボタン
   （プラスの付いたモニターのアイコン）の横の「▼」をクリックし、
   「Javaスタック・トレース・コンソール(Java Stack Trace Console)」を選択
3. `call-hierarchy.csv`のテキストをそのコンソールに貼り付ける


### 注記

| 注記 | 意味 |
|---|---|
| `[CYCLE]` | この経路上で既に呼んでいるメソッドに戻る呼び出し。ここで打ち切る |
| `深さ制限(N)のため打ち切り` | `max.depth` に達した |
| `CHA候補N件（未展開）: 理由` | 実装を1つに絞れなかった。候補は1件ずつ行になるが、その先へは降りない（候補数^深さで爆発するため）。理由は下表 |
| `実装なし（宣言のまま）: 理由` | 本体を持つ実装がソース上に1つも無い。宣言のまま出しているだけ |
| `実装はコンパイル時生成（名前）: FQN はアノテーション処理で生成されるためソース上に無い` | 実装がアノテーション処理でビルド時に生成される型への呼び出し（[docs/doma-generated-impl-qa.md](docs/doma-generated-impl-qa.md) 参照） |
| `ラムダ/メソッド参照の実装あり（未展開・本体は定義元メソッドに計上）` | その関数型インターフェースをラムダかメソッド参照も実装している。展開できないので候補には数えていない |
| `ソースなし（展開不可）` | 呼び出し先がjar内などでソースが無く、そこから先を辿れない |
| `外部ライブラリ（import推定・未検証）` | クラスパス不足で型解決できず、`import` 文から型名を推定した |
| `解決:DATAFLOW_NEW` | `new` された具象型から特定した（捕捉された変数を含む） |
| `解決:DATAFLOW_FACTORY` | ファクトリメソッドの戻り値から具象クラスを特定した |
| `解決:DATAFLOW_PARAM` | 呼び出し元から渡された引数を経路上で追跡して特定した |
| `解決:DATAFLOW_FIELD` | コンストラクタ注入されたフィールドを経路上で追跡して特定した |
| `解決:SPRING_DI` | DI コンテナ（Spring）の Bean 定義で候補が1つに定まった（[docs/spring-di-qa.md](docs/spring-di-qa.md) 参照） |
| `解決:SPRING_DI_QUALIFIER` | `@Qualifier` / `@Resource(name=...)` で指定された Bean 名で1つに定まった（同上） |
| `解決:ラベル` | インターフェース等から具象クラスに解決した（[具象クラスの解決](#具象クラスの解決)参照） |
| `解決:REFLECTION` | `Method.invoke` / `newInstance` を、リフレクションで指定されたメソッド・コンストラクタに解決した |
| `解決:REFLECTION_INIT` | `Class.forName` によるクラス初期化。そのクラスの static 初期化子（`<clinit>`）へ繋ぐ |
| `リフレクション候補N件（未展開）: 引数型が不明なため名前で照合` | `getMethod` の引数型（クラスリテラル）が揃わず、同名のメソッドを候補にした |
| `型解決に失敗（…）` | 呼び出し先の型を特定できなかった行（後述） |
| `被参照:EXACT` 等 | 被参照スキャンの行（後述） |


### jar からの被参照メソッド

自分のコードを呼んでいる側のjarを config の `external.library.folders` に指定すると、
`call-hierarchy.csv` に追記されます。

```properties
external.library.folders=./lib
```

classファイルの定数プールだけを読むため、「どのjar・どのクラスが参照しているか」までが分かります。
呼び出し元メソッドと行番号までは分かりません。そのため呼び出し階層の行とは列の出力形式が異なります。

```csv
caller,callee,root,call-hierarchy
NightJob,jp.co.example.service.OrderService.findOrder(String),team-b-batch.jar,OrderService.findOrder,被参照:EXACT
NightJob,jp.co.example.service.OrderService.OrderService(),team-b-batch.jar,OrderService.OrderService,被参照:EXACT
```

`external.library.folders` に指定したフォルダに自プロジェクトのjarが混ざっていても、
それは「他リポジトリからの被参照」ではないので読み飛ばします。
除外した件数は実行ログに出ます。
`dist` を丸ごと指定しても、自分から自分への呼び出しが被参照として出ることはありません。


| 注記 | 意味 |
|---|---|
| `被参照:EXACT` | そのクラスで宣言されているメソッド（暗黙のデフォルトコンストラクタを含む）への参照 |
| `被参照:INHERITED` | 親から継承したメソッドへの参照。宣言している最も近い親（親クラスの連鎖を先に、次にインターフェース）のメソッドとして出る |
| `被参照:IMPLICIT_CTOR` | 引数なしコンストラクタへの参照で、ソース上に一致する宣言が無いもの。暗黙のデフォルトコンストラクタは解析時に宣言として合成され `EXACT` で照合されるため、ここに来るのは「相手の jar をビルドした時点では引数なしで生成できたが、今のソースにはそのコンストラクタが無い」形、つまり版違いの可能性が高い。生成箇所として有用なので行として残す |

自分の型を参照しているのに一致するメソッドが無いもの（引数付きのコンストラクタを含む）は、
相手のjarが古い版に対してビルドされている可能性があります。件数のみ実行ログに出力されます。
非 static な内部クラスのコンストラクタは、バイトコード上は外側インスタンスが引数に付くため
ソースの宣言と一致せず、この件数に入ります。

---

## 具象クラスの解決

インターフェース型で宣言された呼び出しを、どの実装に解決したかを段階的に判定します。
先に確定した段で打ち切ります。

| 段 | ラベル | 判定 |
|---|---|---|
| 0 | `STATIC_BOUND:*` | private / static / final メソッド、finalクラス、コンストラクタ、super呼び出し |
| 1 | `NO_OVERRIDE` / `SINGLE_IMPL` | オーバーライド候補が1つに定まる |
| 2 | `LOCAL_NEW` / `LOCAL_NEW_MULTI` | 同一メソッド内で `new` された型 |
| 3 | （拡張が返すラベル） | ファクトリ・DI設定・外部リスト等 |
| 4 | `DATAFLOW_NEW` / `DATAFLOW_FACTORY` | `new` された型、またはファクトリメソッドの戻り値から特定（[注記の表](#注記)） |
| — | `DATAFLOW_PARAM` | 呼び出し元から渡された引数から特定（経路ごとに判定するため段の外） |
| — | `DATAFLOW_FIELD` | コンストラクタ注入されたフィールドから特定（同上） |
| 5 | `SPRING_DI` / `SPRING_DI_QUALIFIER` | DI コンテナ（Spring）の Bean 定義で候補を絞った（[注記の表](#注記)） |
| 6 | `CHA` | 候補が複数のまま（低確度） |
| — | `GENERATED_IMPL:名前` | 実装がコンパイル時のアノテーション処理で生成される型（`NO_IMPL` の特殊形） |

---

## 複数のプロジェクトをまとめて解析する

設定ファイルを引数に複数渡すと、渡した順に 1 つずつ処理します。設定ファイルは互いに独立で、
それぞれの `output.folder` の下に `<解析開始日時>_<プロジェクト名>` のフォルダができます
（[出力ファイル](#出力ファイル)）。

```bash
./java-call-hierarchy-exporter.sh config/app-a.properties config/app-b.properties config/batch.properties
```

- 1 つの設定が失敗（設定ファイルが無い、`project.root` が無い等）しても、残りの設定は処理します。
  失敗した設定のエラーとスタックトレースは標準出力（と、出力フォルダを作れていればその `run.log`）に出ます
- 最後に設定ごとの結果（`OK` と出力フォルダ、または `FAIL` と原因）を一覧で出します。
  1 つでも失敗があれば終了コードは 1 です
- ログの経過時間 `[分:秒]` は設定ごとに 0 から数え直します

同じプロジェクトを指す設定ファイルが複数あっても（起点 `entry.packages` だけ違う等）、
キャッシュは `project.root` ごとに 1 つを共有するので、2 つ目以降の解析はキャッシュの再利用だけで済みます。

## GitHub Actions から使う

リポジトリ直下の [`action.yml`](action.yml) を利用者のワークフローから `uses:` で呼ぶと、
解析対象のリポジトリを解析して CSV をアーティファクトにアップロードします。
JBang も JDK も設定ファイルもアクションの中で用意するので、ワークフローに書くのは解析対象の指定だけです。

```yaml
      - uses: actions/checkout@v5
      - uses: instreest/java-call-hierarchy-exporter@main
        with:
          source-folders: src/main/java
          source-encoding: UTF-8
```

参照する版の書き方、入力と出力の一覧、依存 jar を自動で集めるときの下準備、キャッシュの引き継ぎ、
Actions 以外の CI から使うときは [docs/github-actions.md](docs/github-actions.md) にあります。

## キャッシュ

解析結果のキャッシュは出力フォルダには置かず、**このツールのプロジェクトフォルダ**の
`.cache/<project.root のフォルダ名>_<絶対パスのハッシュ 8 桁>/analysis-cache.tsv` に作ります。
同じプロジェクトを指す設定ファイルは同じキャッシュを共有し、名前が同じでも場所が違うプロジェクト
（ブランチごとのチェックアウト等）は混ざりません。設定ファイルをどこに置いても、
どこから実行しても、キャッシュの場所は変わりません。

- 置き場所を変えるときは `cache.folder`、再利用しないときは `cache.enabled=false`
- 2 回目以降は変更されたファイルだけを解析し直します。依存 jar を足したときも、
  その jar の型を使っているファイルだけが対象です
- 大規模なコードベースで `OutOfMemoryError` にならないための作りと、差分更新が
  何を見て判断しているかは [docs/cache-design.md](docs/cache-design.md) にあります

---

## ライセンス

Copyright 2026 Inoue Kazuhiro ([@instreest](https://github.com/instreest)). SPDX-License-Identifier: Apache-2.0
