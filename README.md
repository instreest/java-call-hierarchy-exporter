# java-call-hierarchy-exporter

Javaプロジェクトのメソッド呼び出し階層を一括抽出してCSVに出力するツールです。

- 使い方・出力形式 … このファイル
- 設定項目 … [config/config.properties](config/config.properties)（コメントに全項目の説明）
- 内部設計・再実装のための情報 … [docs/DESIGN.md](docs/DESIGN.md)

---

## Quick start

このリポジトリを最小構成で試す手順です。

### 1. 設定ファイルを編集する

`config/config.properties` の **`project.root`** **`source.folders`** **`library.folders`** **`source.encoding`** を書き換えます。

### 2. 実行する

#### Pleiades/Eclipse環境

Eclipse(Pleiades)がインストールされていれば、そこに含まれるJDT Core一式から、
実行に必要なjarを`lib` フォルダに集めて使います。
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

rem コンパイル
"%JAVA_HOME%\bin\javac" -cp "lib\*" -d bin -encoding UTF-8 src\CallHierarchyExporter.java

rem 実行
"%JAVA_HOME%\bin\java" -cp "bin;lib\*" CallHierarchyExporter config\config.properties
```

### 出力されるファイル

| ファイル | 既定出力先 |
|---|---|
| 呼び出し階層リスト | `./config/output/call-hierarchy.csv` |
| メソッド全体リスト | `./config/output/methods.csv` |

出力はすべてUTF-8（BOM付き）のCSVで、Excelでそのまま開けます。
出力先は設定ファイルからの相対パスなので、`config/config.properties` を使う場合は
`config/output/` の下に出ます。

#### `call-hierarchy.csv` — 呼び出し元が無いメソッドを起点にした呼び出し階層

呼び出し元、呼び出し先、起点メソッド、呼び出し階層（複数）を1行としたCSVです。
呼び出し先ごとに1行出力します。フィルタすることで起点メソッドと呼び出し階層が一覧化できます。
呼び出し元はEclipseの「Javaスタック・トレース・コンソール」に貼り付けると、
`(ファイル:行数)` の部分がハイパーリンクになり、ソースコードへ飛べます。（後述）

```csv
caller,callee,root,call-hierarchy
at jp.co.example.action.OrderAction.execute(OrderAction.java:50),OrderService.findOrder,OrderAction.execute,OrderService.findOrder
at jp.co.example.service.OrderService.findOrder(OrderService.java:25),OrderDaoImpl.selectById,OrderAction.execute,OrderService.findOrder,OrderDaoImpl.selectById
```

#### `methods.csv` — ソース上の全メソッドとその呼び出し状況

```csv
method,declaringType,typeKind,file,line,hasBody,inDegree,outDegree,role,reachable
OrderAction.execute,jp.co.example.action.OrderAction,C,OrderAction.java,45,1,0,1,ENTRY_CANDIDATE,1
OrderService.findOrder,jp.co.example.service.OrderService,C,OrderService.java,20,1,1,1,NORMAL,1
OrderDao.selectById,jp.co.example.dao.OrderDao,I,OrderDao.java,8,0,0,0,ISOLATED,0
OrderDaoImpl.selectById,jp.co.example.dao.OrderDaoImpl,C,OrderDaoImpl.java,15,1,1,0,LEAF,1
```

---

特定のパッケージだけを起点にしたい場合の設定や、各出力ファイルの列の意味・既知の限界などは
以降のセクションで説明します。

---

## 使い方

### 1. 設定ファイルを用意する

`config/config.properties` をコピーして編集します。設定できる項目と意味は
そのファイルにコメントで書いてあります。

相対パスの起点は項目によって異なります。

| 項目 | 相対パスの起点 |
|---|---|
| `project.root` / `cache.folders` / `output.csv` / `methods.csv` / `external.library.folders` | 設定ファイルが置かれているディレクトリ |
| `source.folders` / `library.folders` | `project.root` |

`library.folders` が不足していると型解決に失敗し、その呼び出しが
`call-hierarchy.csv` から丸ごと抜け落ちます。失敗件数は実行ログに出るので、
**初回は必ずこの件数を確認してください**（[既知の限界](#既知の限界)参照）。

### 2. 実行する

処理の進捗は標準出力に出ます。

```
[00:00.033s] === フェーズ1/3: ソース解析 ===
[00:00.037s] Javaファイル数: 900
[00:00.047s] ソース解析 500/900 （直近500件: 1s）
[00:00.057s] ソース解析 900/900 （直近400件: 1s）
[00:00.457s] ソース解析: 再利用=900 新規解析=0 失敗=0
[00:00.457s] === フェーズ1完了: [heap] 使用 45MB / 上限 2048MB
```

ソース解析に時間がかかるため解析結果のキャッシュを作成します。
２回目以降はキャッシュを利用し、差異のあるファイルだけを解析し直します。
差分判定は最終更新時刻とファイルサイズによります。

---

## 出力ファイル

### `call-hierarchy.csv` — 呼び出し階層

```csv
caller,callee,root,call-hierarchy
at jp.co.example.action.OrderAction.execute(OrderAction.java:50),OrderService.findOrder,OrderAction.execute,OrderService.findOrder
at jp.co.example.service.OrderService.findOrder(OrderService.java:25),OrderDaoImpl.selectById,OrderAction.execute,OrderService.findOrder,OrderDaoImpl.selectById
```

| 列 | 内容 |
|---|---|
| `caller` | 呼び出し元。Javaのスタックトレースと同じ形式。**呼び出し箇所**の行を指す |
| `callee` | 呼び出し先。クラス名.メソッド名の形式でExcelのフィルタに使える |
| `root` | 起点メソッド。クラス名.メソッド名の形式でExcelのフィルタに使える |
| `call-hierarchy` | 起点からの呼び出し先を1ノード1列で展開（**可変長**） |

**コンストラクタの呼び出し自体は行になりません。** `new` したこと自体より
「そのコンストラクタの中で何を呼んでいるか」が知りたいためです。
経路には残るので、コンストラクタ内からの呼び出しは
`call-hierarchy` 列に `<init>` を含んだ形で出力されます。

```csv
caller,callee,root,call-hierarchy
at jp.co.example.Sample.<init>(Sample.java:3),Sample.init,Sample.<init>,Sample.init
```

注記が付く場合は `call-hierarchy` の**最後の要素**として出ます。列を間に挟むと
可変長の階層が途中で切れてしまうためです。

| 注記 | 意味 |
|---|---|
| `[CYCLE]` | この経路上で既に呼んでいるメソッドに戻る呼び出し。ここで打ち切る |
| `深さ制限(N)のため打ち切り` | `max.depth` に達した |
| `CHA候補N件（未展開）` | 実装を1つに絞れなかった。候補数^深さで爆発するため展開しない |
| `ソースなし（展開不可）` | 呼び出し先がjar内などでソースが無く、そこから先を辿れない |
| `外部ライブラリ（import推定・未検証）` | クラスパス不足で型解決できず、`import` 文から型名を推定した |
| `解決:ラベル` | インターフェース等から具象クラスに解決した（[具象クラスの解決](#具象クラスの解決)参照） |
| `型解決に失敗（…）` | 呼び出し先の型を特定できなかった行（後述） |
| `被参照:EXACT` 等 | 被参照スキャンの行（後述） |

### **Eclipseへのジャンプ**
`caller` 列の値をコピーし、Eclipseの「Javaスタック・トレース・コンソール」に貼り付けると、
`(ファイル:行数)` の部分がハイパーリンクになり、ソースコードへ飛べます。

1. メニューから ウィンドウ(Window) ＞ ビューの表示(Show View) ＞ コンソール(Console) を選択
2. コンソールビュー右上（ツールバー）の「コンソールのオープン(Open Console)」ボタン
   （プラスの付いたモニターのアイコン）の横の「▼」をクリックし、
   「Javaスタック・トレース・コンソール(Java Stack Trace Console)」を選択
3. `call-hierarchy.csv`のテキストをそのコンソールに貼り付ける

### `methods.csv` — ソース上の全メソッドとその呼び出し状況

| role | 意味 |
|---|---|
| `ENTRY_CANDIDATE` | 呼び出し元が無い。画面入口・デッドコード・テスト・リフレクション経由が混ざる |
| `ISOLATED` | 呼び出し元も呼び出し先も無い。デッドコードの疑いが濃い |
| `LEAF` | 呼び出し先が無い |
| `NORMAL` | 上記以外 |

「よく呼ばれている共通処理」を探したいときは、`inDegree` 列でソート・フィルタしてください。

### 型解決に失敗した呼び出し

依存jarが足りないなどで呼び出し先の型を特定できなかった呼び出しも、
`call-hierarchy.csv` に行として出力されます。**解決できないまま黙って消すと
「呼び出しが無い」ように見えてしまう**ためです。

```csv
caller,callee,root,call-hierarchy
at jp.co.example.Foo.bar(Foo.java:42),getOptions,(型解決失敗),getOptions,型解決に失敗（クラスパス不足・動的呼び出し等の可能性）
```

| 列 | 内容 |
|---|---|
| `caller` | 呼び出し元。呼び出し箇所の行が分かるのでEclipseからジャンプできる |
| `callee` | ソースに書かれていた式（メソッド名）。型が特定できていないのでクラス名は付かない |
| `root` | `(型解決失敗)` 固定。ここでフィルタすると失敗箇所だけを一覧できる |
| `call-hierarchy` | 式と、失敗の理由 |

件数は実行ログにも出ます。多い場合は `library.folders` の設定漏れを疑ってください。

### 他リポジトリからの被参照

自分のコードを呼んでいる側のjarを `external.library.folders` に指定すると、
`call-hierarchy.csv` に追記されます。

```properties
external.library.folders=./lib
```

classファイルの定数プールだけを読むため、「どのjar・どのクラスが参照しているか」までが分かります。
呼び出し元メソッドと行番号までは分かりません。そのため呼び出し階層の行とは列の詰め方が異なります。

```csv
caller,callee,root,call-hierarchy
NightJob,OrderService.findOrder,team-b-batch.jar,OrderService.findOrder,被参照:EXACT
NightJob,OrderService.<init>,team-b-batch.jar,OrderService.<init>,被参照:IMPLICIT_CTOR
```

| 列 | 内容 |
|---|---|
| `caller` | 参照している側のクラス。行番号もメソッドも分からないためスタックトレース形式にはならない |
| `callee` | 参照されている自分のメソッド |
| `root` | 参照元のjar名（起点メソッドが無いため、代わりにjar名を入れる） |
| `call-hierarchy` | `callee` と、照合の種類を表す注記 |

| 注記 | 意味 |
|---|---|
| `被参照:EXACT` | そのクラスで宣言されているメソッドへの参照 |
| `被参照:INHERITED` | 親クラスから継承したメソッドへの参照 |
| `被参照:IMPLICIT_CTOR` | 暗黙のデフォルトコンストラクタ（＝そのクラスを生成している） |

自分の型を参照しているのにメソッドが一致しなかったものは、相手のjarが古い版に対して
ビルドされている可能性があります。件数のみ実行ログに出力されます。

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
| 4 | `CHA` | 候補が複数のまま（低確度） |

**候補数は「サブクラス数」ではなく「そのメソッドをオーバーライドしている宣言の数」です。**
サブクラスが多くても、オーバーライドが1件なら候補は1件のままになります。

段4（`CHA`）になった呼び出しは、候補を列挙するだけでその先へは降りません
（候補数^深さで爆発するため）。`call-hierarchy` 列の最後に
`CHA候補N件（未展開）` と付きます。

段3の拡張は、ファクトリメソッドやDI設定など**プロジェクト固有の解決手法**を
差し込むための口です。同梱の `config/config.properties` には設定キーを載せて
いないので、使う場合は自分で次のキーを追加してください
（実装の詳細は [docs/DESIGN.md](docs/DESIGN.md) を参照）。

```properties
resolver.hint.collectors=jp.co.xxx.MyHintCollector
resolver.candidate.providers=jp.co.xxx.MyCandidateProvider
```

---

## メモリ設計

大規模なコードベースでも `OutOfMemoryError` にならないよう、3点で対策しています。

1. **解析結果をヒープに溜めない** — 1ファイル解析するたびにキャッシュへ書き出して破棄
2. **エッジをオブジェクトで持たない** — メソッドをintのIDに内部化し、CSR形式のプリミティブ配列で保持
3. **ツリーを組み立てない** — 深さ優先で辿りながら1行ずつ書き出す

各フェーズの終わりにヒープ使用量が出るので、`-Xmx` の目安に使えます。
最大になるのはおそらくフェーズ2です。

---

## 既知の限界

| 限界 | 内容 |
|---|---|
| フレームワークのディスパッチ | 画面入口の呼び出しがJavaコード上に存在しない場合、辿れません。命名規則か定義ファイルのパースで補う必要があります |
| リフレクション | 検出できません |
| DIコンテナ | 設定ファイルを読む拡張が別途必要です |
| キャッシュの差分判定 | 最終更新時刻とサイズが両方一致する改変は検出できません。バージョン管理がタイムスタンプを復元する設定（SVNの `use-commit-times` 等）では特に注意。疑わしいときは `cache.enabled=false` にしてください |
| クラスパス不足 | 依存jarが足りないと型解決に失敗し、その呼び出しは出力から抜け落ちます。件数は実行ログに出るので `library.folders` を見直してください |
| 定数のインライン展開 | `public static final` の定数は呼び出し側に埋め込まれるため、被参照スキャンで検出できません |
| オーバーロード | 引数が違う同名メソッドは、`callee` 列では同じ表記で並びます。区別するには `caller` 列の行番号からソースを確認してください |

---
