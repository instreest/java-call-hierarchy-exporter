# java-call-hierarchy-exporter
A tool that recursively extracts Java method call hierarchies across an entire project and exports them to CSV files. Powered by Eclipse JDT and runnable via JBang.

**日本語** | [English](#english)

## Overview
Javaプロジェクト全体のメソッド呼び出し階層を一括で抽出してCSVファイルに出力するツールです。

Eclipseの「呼び出し階層」ビューが一括で再帰的に取得できないため、このツールで一括でCSVファイルを出力します。
Eclipseは起動せず、解析エンジンとして Eclipse JDT を使用してソースコードを解析するコマンドラインツールです。
解析結果のCSVファイルをExcelで開いて呼び出し先メソッドでフィルタすることで対象機能の影響範囲を抽出できます。

## ドキュメント

| 知りたいこと | 場所 |
|---|---|
| 使い方・ツールの起動方法 | [Quick start](#quick-start)（このファイル） |
| 出力CSVファイルの読み方 | [出力ファイル](#出力ファイル)（このファイル） |
| 設定ファイルの項目内容 | [config/config.properties](config/config.properties) のコメント |
| 設計の記録（機能ごとに迷った点と結論）・再実装用の仕様 | [docs/README.md](docs/README.md) |

---

## Quick start

起動スクリプトと設定ファイルを使用します。

起動スクリプトが JDK 25 と依存モジュールが環境上にあるかチェックし、無ければ確認メッセージのうえ自動でダウンロードします。（通信量 約 165MB → 展開後 約 500MB）。

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

　3. 結果を見る … 出力されたCSVファイルを参照します。（[出力ファイル](#出力ファイル)）

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
"%JAVA_HOME%\bin\javac" -classpath lib\* -sourcepath src -d bin src\jche\CallHierarchyExporter.java -encoding UTF-8

rem 実行
"%JAVA_HOME%\bin\java" -classpath bin;lib\* jche.CallHierarchyExporter config\config.properties
```

### GitHub Actions Workflow

リポジトリ直下の [`action.yml`](action.yml) を利用者のワークフローから `uses:` で呼ぶと、
解析対象の指定をするとリポジトリのソースコードを解析してCSVファイルをアーティファクトにアップロードします。
詳細な機能仕様は[docs/github-actions.md](docs/github-actions.md) にあります。

ワークフローはそのまま写せる次の形を推奨します（手動実行のみ、読み取り権限だけで動きます）。

```yaml
name: call hierarchy

on:
  workflow_dispatch:

permissions:
  contents: read

jobs:
  export:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v5

      # 依存 jar を先にローカルリポジトリへ取得しておく
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: '17'
          cache: maven
      - run: mvn -B --no-transfer-progress dependency:go-offline

      - uses: instreest/java-call-hierarchy-exporter@main
        with:
          source-folders: src/main/java
          source-encoding: UTF-8
```

初めて使うときの注意:

- 依存の取得を省くと解析結果が欠けます（ジョブに警告が出ます）
- 依存の取得はランナーの設定（`settings.xml`、プロキシ等）で外部に問い合わせるので、
  `pull_request_target` では使わず、self-hosted ランナーでは設定の扱いを確認してください
- プライベートリポジトリではアーティファクトの容量が利用者のストレージの無料枠から引かれます。
  繰り返し動かす場合は `artifact-retention-days: 7` 程度に（[保存量と保持期間](docs/github-actions.md#保存量と保持期間)）
- 詳細と、依存の取得を省く方法は [docs/github-actions.md](docs/github-actions.md) にあります

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
    ├── run.log                   標準出力と同じ内容の実行ログ（UTF-8。ビルドファイルから集めた依存jarの一覧と要求元も含む）
    ├── warnings.txt              確認してほしいことと対処のしかた（UTF-8。警告やエラーがあったときだけ）
    └── contracts-suggested.txt   絞れなかった呼び出しを1件に絞るための契約表のひな形（UTF-8。絞れなかった呼び出しがあるときだけ）
```

出力CSVファイルはUTF-8（BOM付き）なのでExcelで開けます。

**`warnings.txt` があったら、先に開いてください。** このツールは、ビルドが通り、依存jarがすべて解決できている状態で解析することを前提にしています。
設定の誤り・依存jarの不足・コンパイルエラーなどでそうなっていないときも解析は最後まで動きますが、CSVに抜けが出ます。
そのときだけ `warnings.txt` ができ、何が起きたか・影響・対処のしかたが書かれます。
`run.log` は実行ごとに必ずできる経過の記録（どの設定で何が動いたか、どこに何を保存したか）です。


### `call-hierarchy.csv` — 呼び出し階層

```csv
caller,callee,resolved-by,level,root,call-hierarchy
at jp.co.example.action.OrderAction.execute(OrderAction.java:50),OrderService.findOrder,RESOLVED:NO_OVERRIDE,1,OrderAction.execute,OrderService.findOrder
at jp.co.example.service.OrderService.findOrder(OrderService.java:25),OrderDaoImpl.selectById,RESOLVED:SPRING_DI,2,OrderAction.execute,OrderService.findOrder,OrderDaoImpl.selectById
```

| 列 | 内容 |
|---|---|
| `caller` | 呼び出し元。Javaのスタックトレースと同じ形式。**呼び出し箇所**の行を指す |
| `callee` | 呼び出し先。**クラス名.メソッド名**（引数は付けない）。Excelのフィルタに使える |
| `resolved-by` | 呼び出し先をどう特定したか、絞れなかった場合は候補をどう集めたか（下表）。`caller` → `callee` という1本の呼び出しの性質なので `callee` の隣に置いています |
| `level` | 起点からの階層の深さ（起点が `0`、その呼び出し先が `1`）。`call-hierarchy` に並ぶノード数と必ず一致する |
| `root` | 起点メソッド。クラス名.メソッド名の形式でExcelのフィルタに使える |
| `call-hierarchy` | 起点からの呼び出し先を1ノード1列で展開（**可変長**） |

`resolved-by` は「接頭辞（確度）＋ 解決の段のラベル（手法）」の形で、**どの行にも必ず入ります**。

| 接頭辞 | 意味 |
|---|---|
| `RESOLVED:` | 呼び出し先を1件に確定した。後半が[どの段で決めたか](#具象クラスの解決)（`RESOLVED:DATAFLOW_FIELD` 等） |
| `UNEXPANDED:` | 1件に絞れず候補のまま。後半が候補の集め方（`UNEXPANDED:CHA` 等）。行は候補ごとに出るが、その先へは降りない |
| `UNRESOLVED:` | 呼び出し先の型を特定できなかった行。`UNRESOLVED:BINDING_FAILED`（クラスパス不足・動的呼び出し等）と `UNRESOLVED:OUTSIDE_METHOD`（メソッド本体の外からの呼び出し）。`root` 列は `(型解決失敗)` |
| `EXTERNAL_USAGE:` | jar からの被参照の行（`EXTERNAL_USAGE:EXACT` / `INHERITED` / `IMPLICIT_CTOR`。[jar からの被参照メソッド](#jar-からの被参照メソッド)） |

後半は解決の段のラベルそのものですが、1つだけ例外があります。ラムダ式・メソッド参照が実装している
関数型インターフェースの呼び出しは、ソース上の実装が1件でも（ラベルは `SINGLE_IMPL` 等の確定系でも）
どれが実行されるかは未特定なので `UNEXPANDED:LAMBDA` になります。

Excel では `resolved-by` で「`UNEXPANDED:` で始まる行だけ」＝**辿り切れなかった呼び出し**、
`level` で「3 以下」＝**起点の近く**、のように絞り込めます。

コンストラクタの呼び出し自体は行になりません。
コンストラクタ内からのメソッド呼び出しは行として出力されます。

行順は、rootメソッドのクラス順（ソースフォルダ順 → 完全修飾クラス名順 → 宣言行順）、rootメソッドからの呼び出し順（深さ優先）です。
具象クラスの候補が複数ある呼び出しは候補ごとに 1 行で、宣言型自身の実装 → 下位型（直接の下位型は完全修飾クラス名順）の順に出ます。
末尾の `type resolution failed …` の行はソースの並び順（ソースフォルダ順 → ファイルの相対パス順 → 呼び出し順）で出ます。
注記が付く場合は `call-hierarchy` の**最後の要素**として出ます。 （[注記](#注記)）
解決方法そのものは `resolved-by` 列に出るので、注記には**列に無いこと**（打ち切りの理由、候補の件数と
レシーバの由来、繋いだ契約）だけが載ります。


### `methods.csv` — ソース上の全メソッドとその呼び出し状況

`call-hierarchy.csv` が起点からの経路を展開するのに対し、こちらはソース上のメソッドを 1 行ずつ並べた一覧です。
経路の数ではなくメソッドの数で決まるので大きくなりません。
「誰からも呼ばれていないのはどれか」「よく呼ばれている共通処理はどれか」を俯瞰するのに使います。

```csv
method,declaringType,typeKind,file,line,hasBody,inDegree,outDegree,role,reachable,unresolvedCalls,unresolvedCause,inHierarchy,absentCause
OrderAction.execute(),jp.co.example.action.OrderAction,C,src/jp/co/example/action/OrderAction.java,45,1,0,1,ENTRY_CANDIDATE,1,0,,1,
OrderService.findOrder(String),jp.co.example.service.OrderService,C,src/jp/co/example/service/OrderService.java,20,1,1,1,NORMAL,1,1,[UNEXPANDED:CHA] field,1,
OrderDao.selectById(long),jp.co.example.dao.OrderDao,I,src/jp/co/example/dao/OrderDao.java,8,0,0,0,ISOLATED,0,0,,0,[NOT_REACHED] no caller row was emitted
OrderDaoImpl.selectById(long),jp.co.example.dao.OrderDaoImpl,C,src/jp/co/example/dao/OrderDaoImpl.java,15,1,1,0,LEAF,1,0,,1,
```

| 列 | 内容 |
|---|---|
| `method` | **単純クラス名.メソッド名(引数型略名)**。引数を付けてオーバーロードを見分けられるようにしています。略名が衝突する場合だけ完全修飾の引数に戻ります |
| `declaringType` | 宣言しているクラスの完全修飾名。Excelのフィルタに使える |
| `typeKind` | `C`=具象クラス / `A`=抽象クラス / `I`=インターフェース |
| `file` | 宣言されているファイル。`project.root` からの相対パス |
| `line` | 宣言行 |
| `hasBody` | 本体を持つなら `1`、持たない（インターフェースや抽象メソッドの宣言）なら `0` |
| `inDegree` | このメソッドを呼んでいる箇所の数 |
| `outDegree` | このメソッドが出している呼び出しの数 |
| `role` | 呼び出し元・呼び出し先の有無による分類（下表） |
| `reachable` | 起点からの呼び出しを辿って到達できるなら `1`、できないなら `0` |
| `unresolvedCalls` | このメソッドの中で、具象クラスを1つに絞れなかった呼び出しの件数 |
| `unresolvedCause` | その理由（下表）。複数ある場合は `;` 区切り |
| `inHierarchy` | `call-hierarchy.csv` に1行でも出たなら `1`、出なかったなら `0` |
| `absentCause` | 出なかった理由（下表）。`inHierarchy` が `1` なら空欄 |

| role | 意味 |
|---|---|
| `FRAMEWORK_ENTRY` | 契約でフレームワークが呼ぶと分かる入口（`main`、Servlet の `doGet`、`@Scheduled`、`@GetMapping`、`@Test` 等。[docs/callback-contracts.md](docs/callback-contracts.md)）。全体モードでは、ソースから呼ばれていても起点になる |
| `ENTRY_CANDIDATE` | 呼び出し元が無く、上の契約にも当たらない。画面入口・バッチ・デッドコード・テスト・リフレクション経由が混ざるので仕分けが要る |
| `ISOLATED` | 呼び出し元も呼び出し先も無い。デッドコードの疑いが濃い |
| `LEAF` | 呼び出し先が無い。末端処理 |
| `NORMAL` | 上記以外 |

`unresolvedCause` は、絞れなかった呼び出しのレシーバ（呼び出しの受け手）がどこから来たかで決まります。
次に何を調べればよいかの手がかりになります。
タグは `call-hierarchy.csv` の[注記](#注記)と同じものを使っているので、
一覧で見つけた呼び出しをそのまま階層側で `grep` して追えます。

| unresolvedCause | 意味 |
|---|---|
| `[UNEXPANDED:CHA] return value (factory method etc.)` | レシーバが他のメソッドの戻り値。ファクトリの実装を[プラグイン](docs/instance-analysis-plugin.md)で教えると絞れることがある |
| `[UNEXPANDED:CHA] parameter (passed in from outside the method)` | レシーバが呼び出し元から渡された引数 |
| `[UNEXPANDED:CHA] field` | レシーバがフィールド。DI で注入される形なら[プラグイン](docs/instance-analysis-plugin.md)で絞れる |
| `[UNEXPANDED:CHA] local variable` | レシーバがローカル変数（同一メソッド内の `new` は追跡済みで、それでも絞れなかったもの） |
| `[UNEXPANDED:CHA] own class (this)` / `type name (unbound method reference)` / `receiver unknown` | それぞれ `this`・暗黙のレシーバ、型名で書いたメソッド参照（`Dao::describe`。レシーバは呼び出し時の第1引数）、配列要素やキャスト式など |
| `[UNEXPANDED:NO_IMPL] no implementation with a body in the source` | 中身を書いたクラスがソース上に1つも無い |
| `[UNEXPANDED:GENERATED] implementation is generated at compile time (フレームワーク名)` | 実装がアノテーション処理でビルド時に生成される型（[docs/doma-generated-impl-qa.md](docs/doma-generated-impl-qa.md) 参照） |
| `[UNEXPANDED:LAMBDA] implemented by a lambda/method reference` | その関数型インターフェースをラムダかメソッド参照が実装している |

`absentCause` は、そのメソッドが `call-hierarchy.csv` に1行も出なかった理由です。
打ち切りで階層から消えた部分木は、ここでしか見えません。

| absentCause | 意味 |
|---|---|
| `[UNEXPANDED:CHA] not expanded (CHA candidate)` | 実装を1つに絞れず、候補として行にはなるがその先へ降りなかった |
| `[UNEXPANDED:CYCLE] not expanded (cycle)` | 経路上で既に呼んでいるメソッドへ戻る辺だった |
| `[UNREACHABLE] below a call pruned by a condition` | 条件分岐の静的解析で打ち切った呼び出しから先にしかない（[docs/branch-pruning.md](docs/branch-pruning.md) 参照） |
| `[EXCLUDED] excluded by exclude.packages` | `exclude.packages` で除外された |
| `[NOT_REACHED] no caller row was emitted` | そこへ至る呼び出し自体が出ていない（深さ制限・行数上限の先、起点から辿り着かない） |

行はソースの並び順（ソースフォルダ順 → ファイルの相対パス順 → 宣言行順）で出ます。
「よく呼ばれている共通処理」を探したいときは、`inDegree` 列でソート・フィルタしてください。

- **他から呼び出せる定義**だけを並べます。ラムダ式の合成メソッド（`lambda$…`）、static 初期化子
  （`<clinit>`）、無名クラス（`Outer$1`）のメソッドは出力しません。ラムダと `<clinit>` は呼び出せる
  メソッドではなく、無名クラスのメソッドはその場で親の定義を上書きした処理内容なので、
  呼び出し階層で読みます（[ラムダ式・メソッド参照](#ラムダ式メソッド参照)）。
  内部クラス・static なネストクラス・ローカルクラスのメソッドは名前を持つ定義なので出力します
- コンストラクタ（`<init>`）は出力しません（`call-hierarchy.csv` でも行にしていないため揃えています）
- jar の中のメソッドなど、ソースに宣言が無いものは出力しません。呼ばれている事実は `call-hierarchy.csv` に残ります
- `reachable` の起点は `call-hierarchy.csv` と同じで、`entry.packages` で指定したメソッドです。
  空欄のとき（全体モード）は「呼び出し元が無く、ソース上に本体を持つメソッド」と `FRAMEWORK_ENTRY` が起点になります

### Eclipse でソースコードへジャンプする
`call-hierarchy.csv` の行をコピーし、Eclipseの「Javaスタック・トレース・コンソール」に貼り付けると、
`(ファイル:行数)` の部分がハイパーリンクになり、ソースコードへ飛べます。

1. メニューから ウィンドウ(Window) ＞ ビューの表示(Show View) ＞ コンソール(Console) を選択
2. コンソールビュー右上（ツールバー）の「コンソールのオープン(Open Console)」ボタン
   （プラスの付いたモニターのアイコン）の横の「▼」をクリックし、
   「Javaスタック・トレース・コンソール(Java Stack Trace Console)」を選択
3. `call-hierarchy.csv`のテキストをそのコンソールに貼り付ける


### 注記

注記は先頭に大文字のタグが付きます。日本語の説明はその後ろに続くので、
タグで grep すれば種類ごとに拾えます。
解決方法そのものは `resolved-by` 列に出るため、注記に載るのは**その列に無いこと**
（打ち切りの理由、候補の件数とレシーバの由来、繋いだ契約）だけです。

| タグ | 意味 |
|---|---|
| `[UNEXPANDED:*]` | ここから先へ降りなかった。`grep '\[UNEXPANDED'` で辿り切れなかった箇所を一括で拾える |
| `[EXTERNAL]` | 呼び出し先が自プロジェクトの外。打ち切りではあるが性質が違うので `UNEXPANDED` には入れない |
| `[UNREACHABLE]` | この経路では実行されないと分かった呼び出し |
| `[RESOLVED:CALLBACK]` | 契約で繋いだ呼び出し。後ろに繋いだ契約が続く。どう特定したかは `resolved-by` 列で分かるので、注記に出る `[RESOLVED:*]` はこれだけ |

| 注記 | 意味 |
|---|---|
| `[UNEXPANDED:CYCLE] returns to a method already on this path` | この経路上で既に呼んでいるメソッドに戻る呼び出し。ここで打ち切る |
| `[UNEXPANDED:DEPTH] depth limit (N) reached` | `max.depth` に達した |
| `[UNEXPANDED:CHA] N candidates: {reason}` | 実装を1つに絞れなかった。候補は1件ずつ行になるが、その先へは降りない（候補数^深さで爆発するため）。理由は下表。候補のうち `exclude.packages` で除外したものは行にせず、`(K excluded by exclude.packages and not written as rows)` と数を書く（jar のインターフェースの宣言は「jar の中にも実装がありうる」候補として数に入るので、既定の `java.**` の除外でよく付く） |
| `[UNEXPANDED:REFLECTION] N candidates: matched by name because argument types are unknown` | `getMethod` の引数型（クラスリテラル）が揃わず、同名のメソッドを候補にした |
| `[UNEXPANDED:NO_IMPL] no implementation with a body in the source` | インターフェースや抽象メソッドの宣言はあるが、中身を書いたクラスがソース上に1つも無い。`[EXTERNAL]`（ソースが読めないだけ）とは違い、読めた上で見つからない状態なので、`source.folders` の設定漏れかデッドコードを疑う |
| `[UNEXPANDED:GENERATED] implementation is generated at compile time (フレームワーク名): FQN is…` | 実装がアノテーション処理でビルド時に生成される型への呼び出し（[docs/doma-generated-impl-qa.md](docs/doma-generated-impl-qa.md) 参照） |
| `[UNEXPANDED:LAMBDA] implemented by a lambda/method reference (which one runs is undetermined)` | その関数型インターフェースをラムダかメソッド参照が実装しているが、この呼び出し箇所にどれが渡ってくるかは特定できなかった（[ラムダ式・メソッド参照](#ラムダ式メソッド参照)参照） |
| `[EXTERNAL] no source to follow` | 呼び出し先がjar内などでソースが無く、そこから先を辿れない。型解決自体は成功しているので、呼び先が実在することは確か |
| `[EXTERNAL] type guessed from an import (unverified)` | クラスパス不足で型解決できず、`import` 文から型名を推定した。メソッドの実在やオーバーロードは未確認で、**推定が外れている可能性がある** |
| `[UNREACHABLE] not called on this path: condition '…' does not hold (…)` | 呼び出しを囲む条件が、この経路では成立しないと分かった（[docs/branch-pruning.md](docs/branch-pruning.md) 参照） |
| `[RESOLVED:CALLBACK] contract: Thread#start() calls run()` | 呼び出し先は jar の中だが、「渡した値のこのメソッドを呼び戻す」という契約で繋いだ（[docs/callback-contracts.md](docs/callback-contracts.md)）。jar の中を読んだわけではない |
| `[UNEXPANDED:CHA] N candidates: method reference to an overridable method contract: …` | 同じく契約で繋いだが、渡したのが上書きされうるメソッドへのメソッド参照（`this::hook` 等）で、動く実装を1つに決められなかった。候補を1件ずつ行にし、その先へは降りない（`resolved-by` は `UNEXPANDED:CALLBACK`） |
| `type resolution failed …` | 呼び出し先の型を特定できなかった行（後述）。注記ではなく専用の行 |
| `external-ref:EXACT` 等 | 被参照スキャンの行（後述）。同じく専用の行 |

1つの注記は最大2つのパーツからなり、両方付くときは ` / ` で繋がります。
前半が打ち切りの理由（`[UNEXPANDED:CYCLE]`・`[UNEXPANDED:DEPTH]`・`[EXTERNAL]`・`[UNREACHABLE]`）、
後半が絞り込みの結果（`[UNEXPANDED:CHA]` 等）です。

```
[UNEXPANDED:CYCLE] returns to a method already on this path / [UNEXPANDED:CHA] 5 candidates: field
```

### jar からの被参照メソッド

自分のコードを呼んでいる側のjarを config の `external.library.folders` に指定すると、
`call-hierarchy.csv` に追記されます。

```properties
external.library.folders=./lib
```

classファイルの命令列を読むため、「どのjar・どのクラスの**どのメソッドの何行目**から参照しているか」まで分かります。
`caller` 列は呼び出し階層の行と同じスタックトレース形式なので、Eclipse の Java スタック・トレース・コンソールに貼れば
（相手のソースがワークスペースにあれば）その行へ飛べます。起点も階層も無いので `root` 列には参照元の jar 名が入り、`resolved-by` は `EXTERNAL_USAGE:` で始まり、`level` は `1` です。
ラムダ式やメソッド参照（`Counter::bump`）からの参照も、それを書いた行として出ます。

```csv
caller,callee,resolved-by,level,root,call-hierarchy
at teamb.NightJob.run(NightJob.java:15),OrderService.findOrder,EXTERNAL_USAGE:EXACT,1,team-b-batch.jar,OrderService.findOrder,external-ref:EXACT
at teamb.NightJob.run(NightJob.java:14),OrderService.OrderService,EXTERNAL_USAGE:EXACT,1,team-b-batch.jar,OrderService.OrderService,external-ref:EXACT
at teamb.NoDebugJob.run(Unknown Source),OrderService.findOrder,EXTERNAL_USAGE:EXACT,1,team-b-batch.jar,OrderService.findOrder,external-ref:EXACT
```

行番号は相手の jar が行番号情報付きでビルドされている（`javac` の既定）ときだけ出ます。
`-g:none` でビルドされた jar は、JVM のスタックトレースと同じく `(Unknown Source)` になります（メソッド名までは出ます）。

`external.library.folders` に指定したフォルダに自プロジェクトのjarが混ざっていても、
それは「他リポジトリからの被参照」ではないので読み飛ばします。
除外した件数は実行ログに出ます。
`dist` を丸ごと指定しても、自分から自分への呼び出しが被参照として出ることはありません。


| 注記 | 意味 |
|---|---|
| `external-ref:EXACT` | そのクラスで宣言されているメソッド（暗黙のデフォルトコンストラクタを含む）への参照 |
| `external-ref:INHERITED` | 親から継承したメソッドへの参照。宣言している最も近い親（親クラスの連鎖を先に、次にインターフェース）のメソッドとして出る |
| `external-ref:IMPLICIT_CTOR` | 引数なしコンストラクタへの参照で、ソース上に一致する宣言が無いもの。暗黙のデフォルトコンストラクタは解析時に宣言として合成され `EXACT` で照合されるため、ここに来るのは「相手の jar をビルドした時点では引数なしで生成できたが、今のソースにはそのコンストラクタが無い」形、つまり版違いの可能性が高い。生成箇所として有用なので行として残す |

自分の型を参照しているのに一致するメソッドが無いもの（引数付きのコンストラクタを含む）は、
相手のjarが古い版に対してビルドされている可能性があります。件数のみ実行ログに出力されます。
非 static な内部クラスのコンストラクタは、バイトコード上は外側インスタンスが引数に付くため
ソースの宣言と一致せず、この件数に入ります。

---

## 具象クラスの解決

インターフェース型で宣言された呼び出しを、どの実装に解決したかを段階的に判定します。
先に確定した段で打ち切ります。
ここのラベルが、そのまま `call-hierarchy.csv` の `resolved-by` 列の後半になります
（1件に確定したら `RESOLVED:`、候補のままなら `UNEXPANDED:` が頭に付く）。

| 段 | ラベル | 判定 |
|---|---|---|
| 0 | `STATIC_BOUND:*` | private / static / final メソッド、finalクラス、コンストラクタ、super呼び出し。理由が後ろに付く（`STATIC_BOUND:PRIVATE` 等） |
| 1 | `NO_OVERRIDE` / `SINGLE_IMPL` | オーバーライド候補が1つに定まる |
| 1 | `NO_IMPL` | 本体を持つ実装がソース上に1つも無い（宣言のまま扱う） |
| 2 | `LOCAL_NEW` / `LOCAL_NEW_MULTI` | 同一メソッド内で `new` された型 |
| 3 | `CONTRACT` | 契約表に書いた「この宣言型（メソッド）はこの具象型」で決めた（[docs/callback-contracts.md](docs/callback-contracts.md)） |
| 3 | （拡張が返すラベル） | ファクトリ・DI設定・外部リスト等（[docs/instance-analysis-plugin.md](docs/instance-analysis-plugin.md)）。契約表の次に尋ねる |
| 4 | `DATAFLOW_NEW` / `DATAFLOW_FACTORY` | `new` された型、またはファクトリメソッドの戻り値から特定 |
| — | `DATAFLOW_PARAM` | 呼び出し元から渡された引数を経路上で追跡して特定（経路ごとに判定するため段の外） |
| — | `DATAFLOW_FIELD` | コンストラクタ注入されたフィールドを経路上で追跡して特定（同上） |
| — | `DATAFLOW_LAMBDA` | ラムダ式・メソッド参照から特定（同上。下記） |
| 5 | `SPRING_DI` / `SPRING_DI_QUALIFIER` | DI コンテナ（Spring）の Bean 定義で候補が1つに定まった。`SPRING_DI_QUALIFIER` は `@Qualifier` / `@Resource(name=...)` の Bean 名で定まった（[docs/spring-di-qa.md](docs/spring-di-qa.md)） |
| 6 | `CHA` | 候補が複数のまま（低確度） |
| — | `GENERATED_IMPL:名前` | 実装がコンパイル時のアノテーション処理で生成される型（`NO_IMPL` の特殊形） |
| — | `CALLBACK` | 「渡した値のこのメソッドを呼び戻す」という契約で jar の中を跨いで繋いだ（[docs/callback-contracts.md](docs/callback-contracts.md)）。渡したメソッド参照の実装を1つに決められず候補を並べたときは `UNEXPANDED:CALLBACK` |
| — | `REFLECTION` / `REFLECTION_INIT` | `Method.invoke` / `newInstance` をリフレクションで指定されたメソッド・コンストラクタに解決した／`Class.forName` によるクラス初期化（`<clinit>` へ繋ぐ） |
| — | `EXTERNAL_GUESS` | クラスパス不足で型解決できず、`import` から型名を推定した（**未検証**） |
| — | `LAMBDA` | ラムダ／メソッド参照による実装があり、どれが実行されるかは未特定。`resolved-by` 列でだけ使う言い換えで、必ず `UNEXPANDED:LAMBDA` の形で出る |

`CHA` のまま絞れない呼び出しは、解決の条件を外から与えると1件に絞れます。
出力フォルダの `contracts-suggested.txt` に、そのまま貼れる契約表のひな形が出ます
（[docs/callback-contracts.md](docs/callback-contracts.md)。条件が複雑なら
[docs/instance-analysis-plugin.md](docs/instance-analysis-plugin.md) の拡張）。

静的解析で絞れる条件・絞れない条件は [docs/static-analysis-limits.md](docs/static-analysis-limits.md) にまとめてあります。

---

## ラムダ式・メソッド参照

ラムダ式の本体は、javac に似せた名前（`lambda$囲みメソッド名$通し番号`）を付けた
**合成メソッド**として1つのノードにします（`methods.csv` には出しません。
インスタンスを通じて呼び出せるメソッドではないため）。通し番号は javac 21 と同じく型ごとに本体を読み終えた順
（入れ子は内側が先）で、static 初期化子・static フィールド（インターフェースのフィールドを含む）・
enum 定数の引数の中は `lambda$static$N` です。番号の振り方は javac の版で変わる（JDK 25 の javac は
囲みメソッド名ごと・外側が先）ほか、直列化可能なラムダや、javac がメソッド参照の一部（配列の `Type[]::new` など）を
内部でラムダに変換した場合もずれるので、番号まで一致するとは限りません
（[docs/lambda-expansion-qa.md](docs/lambda-expansion-qa.md) の Q13）。

```csv
at fx.lambda.Holder.viaField(Holder.java:30),Holder.lambda$new$0,RESOLVED:DATAFLOW_LAMBDA,1,Holder.viaField,Holder.lambda$new$0
at fx.lambda.Holder.lambda$new$0(Holder.java:27),OrderDaoImpl.describe,RESOLVED:DATAFLOW_FIELD,2,Holder.viaField,Holder.lambda$new$0,OrderDaoImpl.describe
```

ラムダを作った箇所からは、必ず「生成した」1本の辺が出ます。
どこで実行されるか分からないラムダでも、本体の中の呼び出しが階層から落ちないようにするためです。
実行箇所を特定できたときは、そちらからも同じノードに繋がります（`resolved-by` が `RESOLVED:DATAFLOW_LAMBDA`）。

実行箇所を特定できる形:

| 形 | 例 |
|---|---|
| ローカル変数に入れて呼ぶ | `Runnable r = () -> ...; r.run();` |
| 引数で渡した先で呼ぶ | `runIt(() -> ...)` の中の `r.run()` |
| フィールドに保持して呼ぶ | `private final Runnable task = () -> ...;` の `task.run()` |
| メソッド参照 | `Runnable r = this::helper; r.run();` → `helper` に繋がる |
| レシーバを束縛したメソッド参照 | `Runnable r = dao::describe; r.run();` → `dao` の具象型が分かればその実装（`OrderDaoImpl.describe`）に繋がる。分からなければ上書き候補（`UNEXPANDED:CHA`） |
| ラムダの戻り値に対する呼び出し | `Supplier<Dao> s = () -> new X(); s.get().describe();` → ラムダの `return` から `X.describe` に繋がる |
| ローカルのコレクションに詰めて拡張for文で回す | `jobs.add(() -> ...); for (Runnable j : jobs) j.run();` |

型名で書いたメソッド参照（`Consumer<Dao> c = Dao::describe;`）は、レシーバが呼び出し時の第1引数なので追わず、
上書き候補を全部出します（`UNEXPANDED:CHA`）。

特定できない形（`resolved-by` が `UNEXPANDED:LAMBDA` になります）:

- `list.forEach(Runnable::run)` のように、**jar の中**から呼ばれる形。`forEach` の中はソースが無いので辿れません
- フィールドのコレクションに詰める形、詰める場所と回す場所が別メソッドの形
- 同じ変数に複数のラムダが入りうる形（どれが実行されるか決められないので、絞りません）

特定できない場合でも、生成の辺があるので本体の中の呼び出しは階層に出ます。

ラムダが捕捉した囲みメソッドの引数（`(Dao dao) -> … () -> dao.describe()` の `dao`）の具象型は、
ラムダを作ったメソッドの段でだけ当てます。引数で渡した先から本体へ降りたときは、その先の引数は
捕捉した値ではないので絞りません（捕捉した値はラムダを作った時点で決まります）。

`new Thread(task).start()` や `executor.submit(task)` のように、**jar の中から呼び戻される**形は、
「`Thread#start()` は渡した `Runnable` の `run()` を呼ぶ」という契約表で繋ぎます
（`RESOLVED:CALLBACK`。[docs/callback-contracts.md](docs/callback-contracts.md)）。
渡したのが上書きされうるメソッドへのメソッド参照（`new Thread(this::hook).start()`）で、動く実装を
1つに決められないときは、上書き候補を全部出します（`UNEXPANDED:CALLBACK`）。
自前のフレームワーク分は `contracts.files` に表を書いて足せます。

---

## キャッシュ

解析結果のキャッシュは出力フォルダには置かず、**このツールのプロジェクトフォルダ**の
`.cache/<project.root のフォルダ名>_<絶対パスのハッシュ 8 桁>/` に作ります。
同じプロジェクトを指す設定ファイルは同じキャッシュを共有し、名前が同じでも場所が違うプロジェクト
（ブランチごとのチェックアウト等）は混ざりません。設定ファイルをどこに置いても、
どこから実行しても、キャッシュの場所は変わりません。

キャッシュは `analysis-cache.tsv` の 1 ファイルです。解析で分かった事実（呼び出し階層の構造と、
値の追跡に使う値）をソースファイル 1 つにつき 1 ブロックで持ち、ブロックごとに壊れていないかを確かめます。
壊れたブロックがあれば、そのファイルだけを解析し直します。何も変わっていなければキャッシュは書き直しません。
実行中は同じフォルダに一時ファイル（`*.tmp`）を作り、終わると消します。以前の版が作った `dataflow-cache.tsv` が
残っていれば、次の実行で消します。
同じキャッシュのフォルダを 2 つの実行（CLI と Eclipse・VS Code のプラグイン、CI のジョブなど）が同時に使うときは、
あとの実行が先の実行の終わりを待ちます（最長 30 分。環境変数 `JCHE_CACHE_LOCK_WAIT_SECONDS` で秒数を変えられます）。
そのための錠のファイル `analysis-cache.tsv.lock`（中身は空）がフォルダに残ります。

- 置き場所を変えるときは `cache.folder`、再利用しないときは `cache.enabled=false`
- 2 回目以降は変更されたファイルだけを解析し直します。依存 jar を足したときも、
  その jar の型を使っているファイルだけが対象です
- 大規模なコードベースで `OutOfMemoryError` にならないための作りと、差分更新が
  何を見て判断しているかは [docs/cache-design.md](docs/cache-design.md) にあります

---

## ライセンス

Copyright 2026 Inoue Kazuhiro ([@instreest](https://github.com/instreest)). SPDX-License-Identifier: Apache-2.0

---

# English

[日本語](#java-call-hierarchy-exporter) | **English**

## What this tool does

It extracts the method call hierarchy of a whole Java project in one pass and writes it to CSV files.

Eclipse's "Call Hierarchy" view cannot expand everything recursively in one go, so this tool writes the
whole thing out as CSV instead. It is a command line tool: Eclipse is never started, and Eclipse JDT is
used only as the analysis engine. Open the resulting CSV in Excel and filter by the callee method to get
the impact surface of the feature you are about to change.

## Documentation

| What you want | Where |
|---|---|
| How to use it, how to start the tool | [Getting started](#getting-started) (this file) |
| How to read the output CSV | [Output files](#output-files) (this file) |
| What each config item means | the comments in [config/config.properties](config/config.properties) |
| Design notes (what was hard and what was decided, per feature), and the spec for reimplementation | [docs/README.md](docs/README.md) |

---

## Getting started

You use a launcher script and a config file.

The launcher checks whether JDK 25 and the dependencies are present, and downloads them automatically
after asking you first (about 165MB over the network, about 500MB once unpacked).

1. Edit the config file — set `project.root` (the folder of the project to analyze) in
   [`config/config.properties`](config/config.properties).

2. Run it — pass the config file to the launcher in the repository root.

     ```bat
     rem Windows
     .\java-call-hierarchy-exporter.cmd config\config.properties
     ```

     ```bash
     # Linux / macOS / Git Bash
     ./java-call-hierarchy-exporter.sh config/config.properties
     ```

3. Look at the result — open the CSV files it wrote ([Output files](#output-files)).

### Pleiades / Eclipse environment (for isolated networks and the like)

If Pleiades or Eclipse is installed, collect the jars you need from its JDT Core into a `lib` folder and
use those. The version part of each file name depends on the Eclipse version, so copy with a wildcard.

```bat
rem Make java-call-hierarchy-exporter your current directory
rem Rewrite the next two lines for your environment
set ECLIPSE_HOME=C:\pleiades\2026-06\eclipse
set JAVA_HOME=C:\pleiades\2026-06\java\17

rem Collect the jars needed to run
mkdir lib
for %P in (org.apache.xerces org.eclipse.core.contenttype org.eclipse.core.jobs org.eclipse.core.resources org.eclipse.core.runtime org.eclipse.equinox.common org.eclipse.equinox.preferences org.eclipse.jdt.core.compiler.batch org.eclipse.jdt.core org.eclipse.osgi org.osgi.service.prefs) ^
do copy "%ECLIPSE_HOME%\plugins\%P_*.jar" lib\

rem Compile (the classes under src\jche are compiled along with it)
"%JAVA_HOME%\bin\javac" -classpath lib\* -sourcepath src -d bin src\jche\CallHierarchyExporter.java -encoding UTF-8

rem Run
"%JAVA_HOME%\bin\java" -classpath bin;lib\* jche.CallHierarchyExporter config\config.properties
```

### Running it from GitHub Actions

Calling [`action.yml`](action.yml) in the repository root from your own workflow with `uses:` analyzes the
source in the repository and uploads the CSV files as an artifact, once you tell it what to analyze.
The full specification is in [docs/github-actions.md](docs/github-actions.md).

The following shape is recommended and can be copied as is (manual runs only, and it works with read
permission alone).

```yaml
name: call hierarchy

on:
  workflow_dispatch:

permissions:
  contents: read

jobs:
  export:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v5

      # Fetch the dependency jars into the local repository first
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: '17'
          cache: maven
      - run: mvn -B --no-transfer-progress dependency:go-offline

      - uses: instreest/java-call-hierarchy-exporter@main
        with:
          source-folders: src/main/java
          source-encoding: UTF-8
```

Things to know the first time you use it:

- Skipping the dependency fetch leaves gaps in the result (the job prints a warning)
- The dependency fetch queries the outside world through the runner's configuration (`settings.xml`,
  proxies and so on), so do not use it with `pull_request_target`, and check how that configuration is
  handled on self-hosted runners
- On a private repository, the artifact counts against your storage allowance. For repeated runs, set
  something like `artifact-retention-days: 7`
  ([storage and retention](docs/github-actions.md#保存量と保持期間))
- The details, and how to skip the dependency fetch, are in [docs/github-actions.md](docs/github-actions.md)

---

## Output files

Every run creates a folder named **`<analysis start time>_<name of the project.root folder>`** next to the
config file and puts everything in it.

```
config/
├── config.properties             the config file (the default; copy it per analyzed project)
└── 20260907-163000_myapp/        one output folder per run
    ├── call-hierarchy.csv        the call hierarchy
    ├── methods.csv               every method in the source
    ├── config.properties         a copy of the config file used for this run (under the name you passed)
    ├── run.log                   the run log, the same content as standard output (UTF-8; includes the dependency jars collected from the build files, and who asked for each)
    ├── warnings.txt              what to check and how to fix it (UTF-8; only when there were warnings or errors)
    └── contracts-suggested.txt   a contract table template for narrowing the unresolved calls to one (UTF-8; only when some call could not be narrowed)
```

The CSV files are UTF-8 with a BOM, so Excel opens them directly.

**If there is a `warnings.txt`, open it first.** This tool expects to analyze sources that build and whose dependency jars are all resolved.
When that is not the case (a wrong setting, missing dependency jars, compile errors, etc.), the analysis still finishes, but the CSV files have gaps.
Only then is `warnings.txt` created, saying what happened, what it affects and what to do.
`run.log` is created on every run as the record of what happened (what ran with which settings and where things were saved).

### `call-hierarchy.csv` — the call hierarchy

```csv
caller,callee,resolved-by,level,root,call-hierarchy
at jp.co.example.action.OrderAction.execute(OrderAction.java:50),OrderService.findOrder,RESOLVED:NO_OVERRIDE,1,OrderAction.execute,OrderService.findOrder
at jp.co.example.service.OrderService.findOrder(OrderService.java:25),OrderDaoImpl.selectById,RESOLVED:SPRING_DI,2,OrderAction.execute,OrderService.findOrder,OrderDaoImpl.selectById
```

| Column | Content |
|---|---|
| `caller` | The caller, in the same format as a Java stack trace. It points at the **call site** line |
| `callee` | The callee, as **ClassName.methodName** (no arguments). Usable as an Excel filter |
| `resolved-by` | How the callee was pinned down, or how the candidates were collected when it could not be narrowed (see below). It describes one `caller` -> `callee` call, so it sits next to `callee` |
| `level` | The depth from the entry point (the entry point is `0`, what it calls is `1`). It always matches the number of nodes listed in `call-hierarchy` |
| `root` | The entry method, as ClassName.methodName. Usable as an Excel filter |
| `call-hierarchy` | The path from the entry point, one node per column (**variable length**) |

`resolved-by` is "a prefix (how certain it is) plus the label of the resolution step (how it was done)",
and **every row has one**.

| Prefix | Meaning |
|---|---|
| `RESOLVED:` | The callee was pinned down to one. The second half says [which step decided it](#resolving-concrete-classes) (`RESOLVED:DATAFLOW_FIELD` and the like) |
| `UNEXPANDED:` | It could not be narrowed to one, so candidates remain. The second half says how they were collected (`UNEXPANDED:CHA` and the like). There is a row per candidate, but nothing below them is followed |
| `UNRESOLVED:` | A row for a call whose callee type could not be determined: `UNRESOLVED:BINDING_FAILED` (incomplete classpath, a dynamic call and so on) and `UNRESOLVED:OUTSIDE_METHOD` (a call from outside a method body). The `root` column is `(unresolved)` |
| `EXTERNAL_USAGE:` | A row of the external reference scan (`EXTERNAL_USAGE:EXACT` / `INHERITED` / `IMPLICIT_CTOR`; see [Methods referenced from external jars](#methods-referenced-from-external-jars)) |

The second half is the label of the resolution step itself, with one exception. A call to a functional
interface that a lambda or method reference implements becomes `UNEXPANDED:LAMBDA` even when there is a
single implementation in the source (that is, even when the label is a definite one such as
`SINGLE_IMPL`), because which one runs is still undetermined.

In Excel you can filter on `resolved-by` for "rows starting with `UNEXPANDED:`" = **the calls that could
not be followed to the end**, or on `level` for "3 or less" = **near the entry point**.

A constructor call itself never becomes a row.
Method calls made from inside a constructor are written as rows.

Rows are ordered by the class of the root method (source folder order, then fully qualified class name,
then declaration line), and within that by the call order from the root method (depth first).
A call with several candidate concrete classes gets one row per candidate, ordered as the implementation
of the declared type itself first, then subtypes (direct subtypes in fully qualified class name order).
The `type resolution failed ...` rows at the end come in source order (source folder order, then relative
file path, then call order).
When a note applies, it appears as the **last element** of `call-hierarchy` ([Notes](#notes)).
How it was resolved is already in the `resolved-by` column, so a note only carries **what that column
cannot say**: why the walk stopped, the number of candidates and where the receiver came from, and the
contract that connected the call.

### `methods.csv` — every method in the source and how it is called

Where `call-hierarchy.csv` expands the paths from the entry points, this one lists the methods in the
source, one per row. Its size is decided by the number of methods, not the number of paths, so it does
not blow up. Use it to get an overview of "which methods are never called" and "which shared methods are
called a lot".

```csv
method,declaringType,typeKind,file,line,hasBody,inDegree,outDegree,role,reachable,unresolvedCalls,unresolvedCause,inHierarchy,absentCause
OrderAction.execute(),jp.co.example.action.OrderAction,C,src/jp/co/example/action/OrderAction.java,45,1,0,1,ENTRY_CANDIDATE,1,0,,1,
OrderService.findOrder(String),jp.co.example.service.OrderService,C,src/jp/co/example/service/OrderService.java,20,1,1,1,NORMAL,1,1,[UNEXPANDED:CHA] field,1,
OrderDao.selectById(long),jp.co.example.dao.OrderDao,I,src/jp/co/example/dao/OrderDao.java,8,0,0,0,ISOLATED,0,0,,0,[NOT_REACHED] no caller row was emitted
OrderDaoImpl.selectById(long),jp.co.example.dao.OrderDaoImpl,C,src/jp/co/example/dao/OrderDaoImpl.java,15,1,1,0,LEAF,1,0,,1,
```

| Column | Content |
|---|---|
| `method` | **SimpleClassName.methodName(short argument types)**. Arguments are included so that overloads can be told apart. It falls back to fully qualified arguments only when the short names collide |
| `declaringType` | The fully qualified name of the declaring class. Usable as an Excel filter |
| `typeKind` | `C`=concrete class / `A`=abstract class / `I`=interface |
| `file` | The file it is declared in, relative to `project.root` |
| `line` | The declaration line |
| `hasBody` | `1` if it has a body, `0` if not (an interface or abstract method declaration) |
| `inDegree` | How many places call this method |
| `outDegree` | How many calls this method makes |
| `role` | A classification by whether it has callers and callees (table below) |
| `reachable` | `1` if it can be reached by following calls from an entry point, `0` if not |
| `unresolvedCalls` | How many calls inside this method could not be narrowed to a single concrete class |
| `unresolvedCause` | Why (table below). Several are joined with `;` |
| `inHierarchy` | `1` if it appeared in at least one row of `call-hierarchy.csv`, `0` if not |
| `absentCause` | Why it did not appear (table below). Empty when `inHierarchy` is `1` |

| role | Meaning |
|---|---|
| `FRAMEWORK_ENTRY` | An entry point a framework is known to call, by contract (`main`, a servlet's `doGet`, `@Scheduled`, `@GetMapping`, `@Test` and so on; [docs/callback-contracts.md](docs/callback-contracts.md)). In whole-project mode it is an entry point even when the source also calls it |
| `ENTRY_CANDIDATE` | It has no caller and does not match a contract above. Screen entry points, batch jobs, dead code, tests and reflection-only methods are all mixed in here, so it needs sorting out |
| `ISOLATED` | It has neither callers nor callees. A strong suspect for dead code |
| `LEAF` | It has no callees. A leaf |
| `NORMAL` | Anything else |

`unresolvedCause` is decided by where the receiver of the unresolved call came from.
It tells you what to look at next.
The tags are the same as the [notes](#notes) in `call-hierarchy.csv`, so you can take a call you found in
the list and `grep` for it on the hierarchy side.

| unresolvedCause | Meaning |
|---|---|
| `[UNEXPANDED:CHA] return value (factory method etc.)` | The receiver is the return value of another method. Teaching the tool about the factory with a [plugin](docs/instance-analysis-plugin.md) can narrow it down |
| `[UNEXPANDED:CHA] parameter (passed in from outside the method)` | The receiver is an argument passed in by the caller |
| `[UNEXPANDED:CHA] field` | The receiver is a field. If it is injected by DI, a [plugin](docs/instance-analysis-plugin.md) can narrow it down |
| `[UNEXPANDED:CHA] local variable` | The receiver is a local variable (a `new` inside the same method is already tracked; this is what is left) |
| `[UNEXPANDED:CHA] own class (this)` / `type name (unbound method reference)` / `receiver unknown` | Respectively `this` or an implicit receiver, a method reference written with a type name (`Dao::describe`, whose receiver is the first argument at the call), and things like array elements or cast expressions |
| `[UNEXPANDED:NO_IMPL] no implementation with a body in the source` | No class in the source writes the body |
| `[UNEXPANDED:GENERATED] implementation is generated at compile time (framework)` | A type whose implementation is generated at build time by annotation processing (see [docs/doma-generated-impl-qa.md](docs/doma-generated-impl-qa.md)) |
| `[UNEXPANDED:LAMBDA] implemented by a lambda/method reference` | A lambda or method reference implements that functional interface |

`absentCause` is why the method never appeared in `call-hierarchy.csv`.
A subtree that disappeared from the hierarchy because of pruning is visible only here.

| absentCause | Meaning |
|---|---|
| `[UNEXPANDED:CHA] not expanded (CHA candidate)` | The implementation could not be narrowed to one, so the candidates became rows but nothing below them was followed |
| `[UNEXPANDED:CYCLE] not expanded (cycle)` | The edge goes back to a method already on the path |
| `[UNREACHABLE] below a call pruned by a condition` | It exists only below a call that the static analysis of the conditions pruned (see [docs/branch-pruning.md](docs/branch-pruning.md)) |
| `[EXCLUDED] excluded by exclude.packages` | Excluded by `exclude.packages` |
| `[NOT_REACHED] no caller row was emitted` | No call reaching it was emitted at all (beyond the depth or row limit, or not reachable from an entry point) |

Rows come in source order (source folder order, then relative file path, then declaration line).
To look for "shared methods that are called a lot", sort or filter on the `inDegree` column.

- Only definitions **that something else can call** are listed. The synthetic methods of lambdas
  (`lambda$...`), static initializers (`<clinit>`) and the methods of anonymous classes (`Outer$1`) are
  not written. Lambdas and `<clinit>` are not callable methods, and a method of an anonymous class is a
  body that overrides its parent's definition on the spot, so you read them in the call hierarchy
  ([Lambdas and method references](#lambdas-and-method-references)). The methods of inner classes, static
  nested classes and local classes are named definitions, so they are written
- Constructors (`<init>`) are not written (they are not rows in `call-hierarchy.csv` either, so the two
  stay aligned)
- Anything without a declaration in the source, such as a method inside a jar, is not written. The fact
  that it is called remains in `call-hierarchy.csv`
- The entry points for `reachable` are the same as for `call-hierarchy.csv`: the methods named by
  `entry.packages`. When that is empty (whole-project mode), the entry points are "methods with no caller
  that have a body in the source" plus everything marked `FRAMEWORK_ENTRY`

### Jumping to the source in Eclipse

Copy a row of `call-hierarchy.csv` and paste it into Eclipse's "Java Stack Trace Console": the
`(file:line)` part becomes a hyperlink to the source.

1. Choose Window > Show View > Console from the menu
2. Click the `▼` next to the "Open Console" button (the monitor icon with a plus) in the top right of the
   Console view toolbar, and choose "Java Stack Trace Console"
3. Paste the text of `call-hierarchy.csv` into that console

### Notes

A note starts with an upper case tag. The explanation follows it, so you can pick out a kind of note by
grepping for its tag.

| Tag | Meaning |
|---|---|
| `[UNEXPANDED:*]` | Nothing below this was followed. `grep '\[UNEXPANDED'` finds every place the walk stopped |
| `[EXTERNAL]` | The callee is outside your own project. It is a stop too, but a different kind, so it is not under `UNEXPANDED` |
| `[UNREACHABLE]` | A call that was shown not to run on this path |
| `[RESOLVED:CALLBACK]` | A call connected by a contract; the contract itself follows it. How a call was resolved is in the `resolved-by` column, so this is the only `[RESOLVED:*]` that still appears as a note |

| Note | Meaning |
|---|---|
| `[UNEXPANDED:CYCLE] returns to a method already on this path` | A call back to a method already on this path. It stops here |
| `[UNEXPANDED:DEPTH] depth limit (N) reached` | `max.depth` was reached |
| `[UNEXPANDED:CHA] N candidates: {reason}` | The implementation could not be narrowed to one. Each candidate becomes a row, but nothing below them is followed (it would explode as candidates^depth). The reason is in the table below. Candidates excluded by `exclude.packages` are not written as rows, and their number is written as `(K excluded by exclude.packages and not written as rows)` (the declaration in a jar interface counts as a candidate because the jar may also implement it, so this often appears with the default `java.**` exclusion) |
| `[UNEXPANDED:REFLECTION] N candidates: matched by name because argument types are unknown` | The argument types of `getMethod` (class literals) were not all available, so methods with the same name were taken as candidates |
| `[UNEXPANDED:NO_IMPL] no implementation with a body in the source` | There is an interface or abstract method declaration, but no class in the source writes the body. Unlike `[EXTERNAL]` (where the source simply cannot be read), the source was read and nothing was found, so suspect a missing `source.folders` entry or dead code |
| `[UNEXPANDED:GENERATED] implementation is generated at compile time (framework): FQN is...` | A call into a type whose implementation is generated at build time by annotation processing (see [docs/doma-generated-impl-qa.md](docs/doma-generated-impl-qa.md)) |
| `[UNEXPANDED:LAMBDA] implemented by a lambda/method reference (which one runs is undetermined)` | A lambda or method reference implements that functional interface, but which one arrives at this call site could not be determined (see [Lambdas and method references](#lambdas-and-method-references)) |
| `[EXTERNAL] no source to follow` | The callee is inside a jar or similar, with no source, so nothing below it can be followed. Type resolution itself succeeded, so the callee certainly exists |
| `[EXTERNAL] type guessed from an import (unverified)` | The classpath was incomplete so the type could not be resolved, and the type name was guessed from an `import`. Neither the method's existence nor the overload was checked, so **the guess may be wrong** |
| `[UNREACHABLE] not called on this path: condition '...' does not hold (...)` | The condition around the call was shown not to hold on this path (see [docs/branch-pruning.md](docs/branch-pruning.md)) |
| `[RESOLVED:CALLBACK] contract: Thread#start() calls run()` | The callee is inside a jar, but it was connected by the contract "it calls this method on the value you passed" ([docs/callback-contracts.md](docs/callback-contracts.md)). The inside of the jar was not read |
| `[UNEXPANDED:CHA] N candidates: method reference to an overridable method contract: ...` | Also connected by a contract, but what was passed is a method reference to a method that can be overridden (such as `this::hook`) and the implementation that runs could not be narrowed to one. Each candidate becomes a row and nothing below it is followed (`resolved-by` is `UNEXPANDED:CALLBACK`) |
| `type resolution failed ...` | A row for a call whose callee type could not be determined (see below). Not a note but a row of its own |
| `external-ref:EXACT` and the like | A row of the external reference scan (see below). Also a row of its own |

A note has at most two parts, joined with ` / ` when both apply.
The first is why the walk stopped (`[UNEXPANDED:CYCLE]`, `[UNEXPANDED:DEPTH]`, `[EXTERNAL]`,
`[UNREACHABLE]`) and the second is the result of narrowing (`[UNEXPANDED:CHA]` and so on).

```
[UNEXPANDED:CYCLE] returns to a method already on this path / [UNEXPANDED:CHA] 5 candidates: field
```

### Methods referenced from external jars

Point `external.library.folders` in the config at the jars of the side that calls your code, and the
references are appended to `call-hierarchy.csv`.

```properties
external.library.folders=./lib
```

Because the instruction stream of the class files is read, you learn **which jar, which class, which
method and which line** the reference comes from. The `caller` column uses the same stack trace format as
the hierarchy rows, so pasting it into Eclipse's Java Stack Trace Console jumps to that line (if the other
side's source is in the workspace). There is no entry point and no hierarchy here, so the `root` column
holds the name of the referencing jar, `resolved-by` starts with `EXTERNAL_USAGE:`, and `level` is `1`. References from a lambda or a method reference (`Counter::bump`)
appear as the line that wrote them.

```csv
caller,callee,resolved-by,level,root,call-hierarchy
at teamb.NightJob.run(NightJob.java:15),OrderService.findOrder,EXTERNAL_USAGE:EXACT,1,team-b-batch.jar,OrderService.findOrder,external-ref:EXACT
at teamb.NightJob.run(NightJob.java:14),OrderService.OrderService,EXTERNAL_USAGE:EXACT,1,team-b-batch.jar,OrderService.OrderService,external-ref:EXACT
at teamb.NoDebugJob.run(Unknown Source),OrderService.findOrder,EXTERNAL_USAGE:EXACT,1,team-b-batch.jar,OrderService.findOrder,external-ref:EXACT
```

Line numbers appear only when the other jar was built with line number information (the default for
`javac`). A jar built with `-g:none` shows `(Unknown Source)`, just like a JVM stack trace (the method
name still appears).

If a jar of your own project ends up in a folder named by `external.library.folders`, it is skipped,
because that is not "a reference from another repository".
How many were skipped appears in the run log.
Pointing at a whole `dist` folder never turns your own calls into external references.

| Note | Meaning |
|---|---|
| `external-ref:EXACT` | A reference to a method declared by that class (including an implicit default constructor) |
| `external-ref:INHERITED` | A reference to a method inherited from a parent. It appears as the method of the nearest declaring parent (the chain of superclasses first, then interfaces) |
| `external-ref:IMPLICIT_CTOR` | A reference to a no-argument constructor with no matching declaration in the source. An implicit default constructor is synthesized as a declaration during the analysis and matches as `EXACT`, so what lands here is "it could be created with no arguments when the other jar was built, but today's source has no such constructor" — most likely a version mismatch. It is useful as a creation site, so it is kept as a row |

References to your own types with no matching method (including constructors with arguments) suggest that
the other jar was built against an older version. Only the count appears in the run log.
The constructor of a non-static inner class takes the outer instance as an argument in bytecode, so it
does not match the source declaration and is counted here.

---

## Resolving concrete classes

For a call declared through an interface type, the tool decides step by step which implementation it
resolved to. It stops at the first step that decides.
The label here becomes the second half of the `resolved-by` column of `call-hierarchy.csv`
(`RESOLVED:` is prefixed once it is pinned down to one, `UNEXPANDED:` while candidates remain).

| Step | Label | Decision |
|---|---|---|
| 0 | `STATIC_BOUND:*` | private / static / final methods, final classes, constructors, super calls. The reason follows it (`STATIC_BOUND:PRIVATE` and the like) |
| 1 | `NO_OVERRIDE` / `SINGLE_IMPL` | The override candidates narrow to one |
| 1 | `NO_IMPL` | No implementation with a body exists in the source (it is left as the declaration) |
| 2 | `LOCAL_NEW` / `LOCAL_NEW_MULTI` | A type `new`-ed inside the same method |
| 3 | `CONTRACT` | Decided by a contract table row saying "this declared type (method) is this concrete type" ([docs/callback-contracts.md](docs/callback-contracts.md)) |
| 3 | (the label the extension returns) | A factory, a DI configuration, an external list and so on ([docs/instance-analysis-plugin.md](docs/instance-analysis-plugin.md)). Asked after the contract table |
| 4 | `DATAFLOW_NEW` / `DATAFLOW_FACTORY` | Determined from a `new`-ed type or from the return value of a factory method |
| — | `DATAFLOW_PARAM` | Determined by tracking an argument passed in by the caller along the path (outside the steps, because it is decided per path) |
| — | `DATAFLOW_FIELD` | Determined by tracking a constructor-injected field along the path (same) |
| — | `DATAFLOW_LAMBDA` | Determined from a lambda or method reference (same; see below) |
| 5 | `SPRING_DI` / `SPRING_DI_QUALIFIER` | The bean definitions of the DI container (Spring) narrowed it to one. `SPRING_DI_QUALIFIER` means the bean name from `@Qualifier` / `@Resource(name=...)` decided it ([docs/spring-di-qa.md](docs/spring-di-qa.md)) |
| 6 | `CHA` | Several candidates remain (low confidence) |
| — | `GENERATED_IMPL:name` | A type whose implementation is generated at compile time by annotation processing (a special case of `NO_IMPL`) |
| — | `CALLBACK` | Connected across the inside of a jar by the contract "it calls this method on the value you passed" ([docs/callback-contracts.md](docs/callback-contracts.md)). When the implementation behind a method reference that was passed could not be narrowed to one and the candidates are listed, it is `UNEXPANDED:CALLBACK` |
| — | `REFLECTION` / `REFLECTION_INIT` | `Method.invoke` / `newInstance` resolved to the method or constructor named through reflection / class initialization through `Class.forName` (connected to `<clinit>`) |
| — | `EXTERNAL_GUESS` | The classpath was incomplete so the type could not be resolved, and the type name was guessed from an `import` (**unverified**) |
| — | `LAMBDA` | A lambda or method reference implements it and which one runs is undetermined. This is a rewording used only in the `resolved-by` column, and it always appears as `UNEXPANDED:LAMBDA` |

Calls that stay at `CHA` can be narrowed to one by supplying the resolution conditions from outside.
The output folder holds a `contracts-suggested.txt` with a contract table template you can paste as is
([docs/callback-contracts.md](docs/callback-contracts.md); for complex conditions, the extensions in
[docs/instance-analysis-plugin.md](docs/instance-analysis-plugin.md)).

What static analysis can and cannot narrow down is written up in
[docs/static-analysis-limits.md](docs/static-analysis-limits.md).

---

## Lambdas and method references

The body of a lambda becomes one node, a **synthetic method** with a name modeled on javac's
(`lambda$enclosingMethod$serial`). It is not listed in `methods.csv`, because it is not a method you can
call through an instance. The serial is assigned the way javac 21 does it, per type and in the order the
bodies are finished (an inner lambda comes before its outer one), and a lambda inside a static initializer,
a static field (including an interface field) or an enum constant's arguments is `lambda$static$N`.
The serials do not always match javac: the numbering scheme changes between javac versions (the JDK 25
javac counts per enclosing method name and numbers the outer lambda first), and serializable lambdas or
method references that javac turns into lambdas internally (such as an array `Type[]::new`) also shift them
([docs/lambda-expansion-qa.md](docs/lambda-expansion-qa.md), Q13).

```csv
at fx.lambda.Holder.viaField(Holder.java:30),Holder.lambda$new$0,RESOLVED:DATAFLOW_LAMBDA,1,Holder.viaField,Holder.lambda$new$0
at fx.lambda.Holder.lambda$new$0(Holder.java:27),OrderDaoImpl.describe,RESOLVED:DATAFLOW_FIELD,2,Holder.viaField,Holder.lambda$new$0,OrderDaoImpl.describe
```

There is always one "created it" edge out of the place that wrote the lambda. That is so the calls inside
the body never drop out of the hierarchy, even for a lambda whose execution site is unknown.
When the execution site is determined, that site connects to the same node as well
(`resolved-by` is `RESOLVED:DATAFLOW_LAMBDA`).

Shapes where the execution site can be determined:

| Shape | Example |
|---|---|
| Put it in a local variable and call it | `Runnable r = () -> ...; r.run();` |
| Pass it as an argument and call it there | `r.run()` inside `runIt(() -> ...)` |
| Hold it in a field and call it | `task.run()` for `private final Runnable task = () -> ...;` |
| A method reference | `Runnable r = this::helper; r.run();` connects to `helper` |
| A method reference with a bound receiver | `Runnable r = dao::describe; r.run();` connects to the implementation for the concrete type of `dao` (`OrderDaoImpl.describe`) when it is known, otherwise to the override candidates (`UNEXPANDED:CHA`) |
| A call on what a lambda returns | `Supplier<Dao> s = () -> new X(); s.get().describe();` connects to `X.describe` through the lambda's `return` |
| Put it in a local collection and iterate with an enhanced for | `jobs.add(() -> ...); for (Runnable j : jobs) j.run();` |

A method reference written with a type name (`Consumer<Dao> c = Dao::describe;`) is not followed, because its
receiver is the first argument at the call; all override candidates are written (`UNEXPANDED:CHA`).

Shapes where it cannot (`resolved-by` becomes `UNEXPANDED:LAMBDA`):

- Shapes called **from inside a jar**, such as `list.forEach(Runnable::run)`. The inside of `forEach` has
  no source, so it cannot be followed
- Putting it in a field collection, or filling and iterating in different methods
- Shapes where several lambdas can end up in the same variable (which one runs cannot be decided, so
  nothing is narrowed)

Even when it cannot be determined, the "created it" edge means the calls inside the body still appear in
the hierarchy.

The concrete type of an enclosing method's parameter that a lambda captured (`dao` in
`(Dao dao) -> … () -> dao.describe()`) is applied only at the level of the method that created the lambda.
When the body is entered from the method the lambda was passed to, that method's arguments are not the
captured values, so nothing is narrowed there (captured values are fixed when the lambda is created).

Shapes **called back from inside a jar**, such as `new Thread(task).start()` or `executor.submit(task)`,
are connected through a contract table saying "`Thread#start()` calls `run()` on the `Runnable` you
passed" (`RESOLVED:CALLBACK`; [docs/callback-contracts.md](docs/callback-contracts.md)).
When what you pass is a method reference to a method that can be overridden (`new Thread(this::hook).start()`)
and the implementation that runs cannot be narrowed to one, all override candidates are written
(`UNEXPANDED:CALLBACK`).
Add your own frameworks by writing a table in `contracts.files`.

---

## Cache

The analysis cache does not live in the output folder. It is created under **the tool's own project
folder**, at `.cache/<name of the project.root folder>_<8 hex digits of the absolute path>/`.
Config files pointing at the same project share the same cache, and projects with the same name in
different places (checkouts per branch, for instance) do not get mixed up. Wherever you put the config
file and wherever you run from, the cache location does not change.

The cache is a single file, `analysis-cache.tsv`. It holds the facts the analysis found (the structure of
the call hierarchy and the values used for value tracking), one block per source file, and each block is
checked for damage. If a block is damaged, only that file is analyzed again. When nothing has changed, the
cache is not rewritten. While running, the tool creates temporary files (`*.tmp`) in the same folder and deletes
them when it finishes. A `dataflow-cache.tsv` left by an older version is deleted on the next run.
When two runs use the same cache folder at the same time (the CLI and the Eclipse or VS Code plugin, CI jobs, and so on),
the later run waits until the earlier one finishes (at most 30 minutes; set the environment variable
`JCHE_CACHE_LOCK_WAIT_SECONDS` to change the number of seconds). The lock file used for this,
`analysis-cache.tsv.lock` (empty), stays in the folder.

- Use `cache.folder` to move it, `cache.enabled=false` to stop reusing it
- From the second run on, only the changed files are analyzed again. When you add a dependency jar, only
  the files that use types from that jar are affected
- How it is built so that a large code base does not hit `OutOfMemoryError`, and what the differential
  update looks at, are in [docs/cache-design.md](docs/cache-design.md)

---

## License

Copyright 2026 Inoue Kazuhiro ([@instreest](https://github.com/instreest)). SPDX-License-Identifier: Apache-2.0
