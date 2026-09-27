# java-call-hierarchy-exporter
A tool that recursively extracts Java method call hierarchies across an entire project and exports them to CSV files. Powered by Eclipse JDT and runnable via JBang.
**Under active development — features may change without notice.**

**Japanese** | [English](#english)

## できること（ツール概要）

Javaプロジェクト全体のメソッド呼び出し階層を一括で解析し、CSVファイルに書き出すツールです。
解析結果をExcelで開いてフィルタすることで対象メソッドの影響範囲を洗い出すことができます。
**開発中であるため機能が予告なく変更される場合があります。**

| 知りたいこと | 場所 |
|---|---|
| 使い方・ツールの起動方法 | [Quick start](#quick-start)（このファイル） |
| 出力CSVファイルの読み方 | [出力ファイル](#出力ファイル)（このファイル） |
| 設定ファイルの項目内容 | [config/config.properties](config/config.properties) のコメント |

### ほかの手段との違い

| 手段 | 向いていること | このツールとの違い |
|---|---|---|
| IDE の呼び出し階層（Eclipse の Ctrl+Alt+H など） | 書きながら、1 つのメソッドの呼び出し元をその場で確かめる | 1 メソッドずつ画面で展開するので、プロジェクト全体の一覧として保存・絞り込み・共有ができない。インターフェース越しの呼び出しの実装を決める根拠も残らない |
| `grep` による文字列検索 | 手早く名前の出現箇所を探す | 同じ名前の別メソッドや、インターフェース・継承越しの呼び出しを区別できない |
| `jdeps` | jar・パッケージ・クラスの間の依存を調べる | メソッドの単位の呼び出しや、呼び出しの経路は分からない |

IDE の中で呼び出し元を辿りたいときは、同じ解析を画面から使える [Eclipse プラグイン](docs/eclipse-plugin-usage.md)・
[VSCode プラグイン](docs/vscode-plugin-usage.md)もあります。

## 動作条件と制約

### 動作条件

| 項目 | 条件 |
|---|---|
| OS | Windows、Linux |
| 事前に入れておくもの | なし。ツールを動かす JDK 25 と解析に使う Eclipse JDT（Eclipse の Java コンパイラ） は、初回に確認のうえ自動で取得します（通信量 約 165MB、ディスク 約 500MB）。ネットワークに出られない環境は[閉域ネットワークで使う](#ほかの使い方)を参照 |
| 解析できるソース | Java のソース（`.java`）。解析エンジンの Eclipse JDT が対応しているJavaバージョンに対応します。最新版3.46.0ではJava 8 ～ 26に対応します。 |
| ビルドの構成 | Eclipse の `.classpath`、Maven（`pom.xml`）、Gradle（`build.gradle`）において宣言的に記載されたソースフォルダと依存 jar を自動で見つけて解析します |
| 依存 jar | **手元に取得済みであること。** このツールは解析対象プロジェクトのビルドツールを実行せず、ネットワークからも取得しません。`~/.m2/repository` などのローカルリポジトリにある jar を使うので、事前に一度ビルドする（`mvn dependency:go-offline` など）か、コンパイル時および実行時の依存 jar をlibフォルダに保存してコンフィグ指定が必要となります。|

### 分からないこと（制約）

静的解析なので、実行してみないと決まらないことは分かりません。分からないときは呼び出しを消すのではなく、
候補を並べるか「辿れなかった」と書きます。

- **ソースの無いところ（jar の中）は辿りません。** JDK やフレームワークの中を経由して呼び戻される呼び出し
  （`new Thread(task).start()` → `task.run()` など）は、[契約表](docs/callback-contracts.md)に書かれたもの（同梱の分と、自分で足した分）だけを繋ぎます
- **実行時に決まる値は分かりません。** 設定ファイルや入力から作ったクラス名でのリフレクション、
  実行時の条件で変わる実装などは、候補を並べるに留まります（[docs/static-analysis-limits.md](docs/static-analysis-limits.md)）
- **コンパイルが通らない・依存 jar が足りないと、結果に抜けが出ます。** そのときは出力フォルダに
  `warnings.txt` ができ、何が足りないかを知らせます

---

## Quick start

1. **ツールを取得する** … このリポジトリを clone します（GitHub の「Code → Download ZIP」で展開しても構いません）。

     ```bash
     git clone https://github.com/instreest/java-call-hierarchy-exporter.git
     cd java-call-hierarchy-exporter
     ```

2. **設定ファイルに解析対象を書く** … [`config/config.properties`](config/config.properties) の `project.root` に、
   解析したいプロジェクトのフォルダを書きます。**必須なのはこの 1 行だけ**で、ソースフォルダ・依存 jar・
   文字コードは空欄のままなら `.classpath` 、 `pom.xml` 、 `build.gradle` から自動で読み取ります。

     ```properties
     # Windows でも区切りは / で書く（\ で書くなら \\ のように 2 つ重ねる）
     project.root=C:/work/myapp
     ```

   相対パスで書くときは、この設定ファイルのあるフォルダ（`config/`）が起点です。
   解析するプロジェクトごとに設定ファイルをコピーして増やす（`config/myapp.properties` など）と管理しやすくなります。

3. **実行する** … リポジトリ直下の起動コマンドに、設定ファイルを渡します。

     ```bat
     rem Windows
     .\java-call-hierarchy-exporter.cmd config\config.properties
     ```

     ```bash
     # Linux / macOS / Git Bash
     ./java-call-hierarchy-exporter.sh config/config.properties
     ```

   初回は JDK などの取得の確認が出るので、`y` で答えます（取得するものとサイズが表示されます）。
   初回は取得と全件の解析のぶん時間がかかりますが、2 回目からは変わったファイルだけを解析します。
   ZIP で取得して `.sh` に実行権限が無いときは、`bash java-call-hierarchy-exporter.sh …` で動かします。

4. **出力結果の `warnings.txt` を確認する** … 実行ごとに `config/<解析開始日時>_<プロジェクト名>/` のフォルダができます。
   その中に `warnings.txt` があれば、依存 jar の不足やコンパイルエラーなどで**結果に抜けがある**ということです。
   何が起きたかと直し方が書いてあるので、直してからもう一度実行してください。

5. **CSV を開く** … 同じフォルダの `call-hierarchy.csv` を Excel で開きます。
   読み方は次の[結果の読み方](#結果の読み方)にあります。

---

## 結果の読み方

### 影響範囲を調べる（基本の手順）

1. `call-hierarchy.csv` を Excel で開き、フィルタを付けます（データ → フィルター）
2. **`callee` 列**で、改修するメソッドを `クラス名.メソッド名` で選びます（引数は付きません。オーバーロードは同じ名前になります）
3. 残った行の **`root` 列**が、そのメソッドに届く入口（画面のアクション・バッチの `main`・API のハンドラなど）です。
   **`caller` 列**が直接の呼び出し箇所（ファイルと行）、**`call-hierarchy` 列**が入口からそこまでの経路です

インターフェース越しの呼び出しで実装を 1 つに決められなかったときも、候補の実装ごとに行が出るので、
`callee` を実装クラスの名前で絞れば見つかります。

### 1 行の読み方

```csv
caller,callee,resolved-by,level,root,call-hierarchy
at jp.co.example.action.OrderAction.execute(OrderAction.java:50),OrderService.findOrder,RESOLVED:NO_OVERRIDE,1,OrderAction.execute,OrderService.findOrder
at jp.co.example.service.OrderService.findOrder(OrderService.java:25),OrderDaoImpl.selectById,RESOLVED:SPRING_DI,2,OrderAction.execute,OrderService.findOrder,OrderDaoImpl.selectById
```

| 列 | 例（上の 2 行目） | 意味 |
|---|---|---|
| `caller` | `at jp.co.example.service.OrderService.findOrder(OrderService.java:25)` | 呼び出している場所。Java のスタックトレースと同じ形なので、Eclipse でソースに飛べます（[下記](#eclipse-でソースコードへジャンプする)） |
| `callee` | `OrderDaoImpl.selectById` | 呼ばれるメソッド |
| `resolved-by` | `RESOLVED:SPRING_DI` | 呼び出し先をどう決めたか。`RESOLVED:` なら 1 つに決まった、`UNEXPANDED:` なら決めきれず候補を並べた |
| `level` | `2` | 入口から何段目の呼び出しか |
| `root` | `OrderAction.execute` | 入口のメソッド |
| `call-hierarchy` | `OrderService.findOrder,OrderDaoImpl.selectById` | 入口の次から `callee` までの経路（1 段が 1 列）。最後の列に補足（注記）が付くことがあります |

### 辿り切れなかった呼び出しを確かめる

どの行も `resolved-by` 列の接頭辞で確かさが分かります。影響調査で目で確かめるべきなのは次の行です。

| `resolved-by` | 意味 | どうするか |
|---|---|---|
| `UNEXPANDED:…` | 実装を 1 つに決めきれず、候補を並べた。**候補から先へは辿っていません** | その候補より先の経路は、`caller` のメソッド名を `callee` 列でもう一度絞って上へ辿ります。絞り方を教えれば次から 1 つに決まります（出力フォルダの `contracts-suggested.txt`、[docs/callback-contracts.md](docs/callback-contracts.md)） |
| `UNRESOLVED:…` | 呼び出し先の型が分からなかった（多くは依存 jar の不足） | `warnings.txt` に従って依存 jar を揃えて実行し直します |

`call-hierarchy` 列の最後に付く注記（`[EXTERNAL] no source to follow` など）は、そこで辿るのをやめた理由です。
注記の一覧は[注記](#注記)にあります。

### Eclipse でソースコードへジャンプする
`call-hierarchy.csv` の行をコピーし、Eclipseの「Javaスタック・トレース・コンソール」に貼り付けると、
`(ファイル:行数)` の部分がハイパーリンクになり、ソースコードへ飛べます。

1. メニューから ウィンドウ(Window) ＞ ビューの表示(Show View) ＞ コンソール(Console) を選択
2. コンソールビュー右上（ツールバー）の「コンソールのオープン(Open Console)」ボタン
   （プラスの付いたモニターのアイコン）の横の「▼」をクリックし、
   「Javaスタック・トレース・コンソール(Java Stack Trace Console)」を選択
3. `call-hierarchy.csv`のテキストをそのコンソールに貼り付ける

### 用語

| 用語 | 意味 |
|---|---|
| 起点（`root`） | 呼び出しを辿り始めるメソッド。設定の `entry.packages` で指定します。空欄なら、ソースの中で呼び出し元の無いメソッドと、フレームワークが呼ぶと分かっているメソッド（`main`・`@GetMapping`・Servlet の `doGet` など）がすべて起点になります（全体モード） |
| 具象クラスの解決 | インターフェースや親クラスの型に対する呼び出しで、実際に動く実装クラスを決めること（[具象クラスの解決](#具象クラスの解決)） |
| CHA | Class Hierarchy Analysis。型の継承関係だけから実装の候補をすべて挙げる方法。ほかの方法で決めきれなかったときに使い、`UNEXPANDED:CHA` と書きます |
| レシーバ | `dao.find()` の `dao` のように、メソッドを呼ばれる側の値 |
| 契約表 | ソースの外（JDK・フレームワーク）の振る舞いや、実装クラスの対応を書いた表（[docs/callback-contracts.md](docs/callback-contracts.md)） |
| 注記 | `call-hierarchy` 列の最後に付く補足。大文字のタグで始まります（[注記](#注記)） |

---

## ほかの使い方

| やりたいこと | 方法 |
|---|---|
| 設定ファイルをメニューで作る・複数の設定をまとめて処理する | 起動コマンドを引数なしで実行する対話モード、または設定ファイルを複数渡す（[docs/cli.md](docs/cli.md)） |
| Eclipse の中で呼び出し元を辿る | [Eclipse プラグイン](docs/eclipse-plugin-usage.md) |
| VSCode の中で呼び出し元を辿る | [VSCode プラグイン](docs/vscode-plugin-usage.md) |
| GitHub Actions で解析して CSV をアーティファクトにする | リポジトリ直下の [`action.yml`](action.yml) を `uses:` で呼ぶ（[docs/github-actions.md](docs/github-actions.md)。そのまま写せるワークフローの例と、依存の取得・セキュリティ・保存量の注意） |
| 閉域ネットワークで使う（ネットワークから取得しない） | Pleiades / Eclipse に入っている JDK と JDT の jar で動かす（[docs/cli.md](docs/cli.md#閉域ネットワークで動かすpleiadeseclipse-の-jar-を使う)） |
| 自分のコードを呼んでいる、ほかのチームの jar を調べる | 設定の `external.library.folders`（[jar からの被参照メソッド](#jar-からの被参照メソッド)） |
| 決めきれなかった実装を 1 つに絞る | 契約表を書く（[docs/callback-contracts.md](docs/callback-contracts.md)）。条件が複雑なら拡張を書く（[docs/instance-analysis-plugin.md](docs/instance-analysis-plugin.md)） |
| 呼び出しに効いている `if` の条件も出す | 設定の `conditions.target`（[docs/call-conditions.md](docs/call-conditions.md)） |

設定項目の全体は [config/config.properties](config/config.properties) のコメントにあります。

---

## 出力ファイル

ここから下は、出力の各列と記号の詳しい説明です。

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

出力CSVファイルはUTF-8（BOM付き）なのでExcelで開けます。CSV の中身は、画面の表示言語に関わらず英語です。

`warnings.txt` は、ビルドが通り依存jarがすべて解決できている、というこのツールの前提が崩れているときだけできます。
載るのは、依存jarの不足・設定の指定先の欠け・コンパイルエラーのほか、パッケージの宣言がフォルダと合わないファイル（`source.folders` の指定が 1 段ずれているときに多い）、Java のパーサが途中で止まって解析できなかったファイル、途中で打ち切った出力、解析サーバー（Eclipse・VS Code のプラグイン）の実行中に同じ更新時刻のまま上書きされた依存jar などです。
`run.log` は実行ごとに必ずできる経過の記録（どの設定で何が動いたか、どこに何を保存したか）です。


### `call-hierarchy.csv` — 呼び出し階層

| 列 | 内容 |
|---|---|
| `caller` | 呼び出し元。Javaのスタックトレースと同じ形式。**呼び出し箇所**の行を指す |
| `callee` | 呼び出し先。**クラス名.メソッド名**（引数は付けない）。Excelのフィルタに使える |
| `resolved-by` | 呼び出し先をどう特定したか、絞れなかった場合は候補をどう集めたか（下表）。**どの行にも必ず入る** |
| `level` | 起点からの階層の深さ（起点が `0`、その呼び出し先が `1`）。`call-hierarchy` に並ぶノード数と必ず一致する |
| `root` | 起点メソッド。クラス名.メソッド名の形式でExcelのフィルタに使える |
| `call-hierarchy` | 起点からの呼び出し先を1ノード1列で展開（**可変長**）。注記が付く場合は最後の要素になる（[注記](#注記)） |

`resolved-by` は「接頭辞（確度）＋ 解決の段のラベル（手法）」の形です。

| 接頭辞 | 意味 |
|---|---|
| `RESOLVED:` | 呼び出し先を1件に確定した。後半が[どの段で決めたか](#具象クラスの解決)（`RESOLVED:DATAFLOW_FIELD` 等） |
| `UNEXPANDED:` | 1件に絞れず候補のまま。後半が候補の集め方（`UNEXPANDED:CHA` 等）。行は候補ごとに出るが、その先へは降りない |
| `UNRESOLVED:` | 呼び出し先の型を特定できなかった行。`UNRESOLVED:BINDING_FAILED`（クラスパス不足・動的呼び出し等）と `UNRESOLVED:OUTSIDE_METHOD`（メソッド本体の外からの呼び出し）。`root` 列は `(unresolved)` |
| `EXTERNAL_USAGE:` | jar からの被参照の行（`EXTERNAL_USAGE:EXACT` / `INHERITED` / `IMPLICIT_CTOR`。[jar からの被参照メソッド](#jar-からの被参照メソッド)） |

後半は解決の段のラベルそのものですが、1つだけ例外があります。ラムダ式・メソッド参照が実装している
関数型インターフェースの呼び出しは、ソース上の実装が1件でも（ラベルは `SINGLE_IMPL` 等の確定系でも）
どれが実行されるかは未特定なので `UNEXPANDED:LAMBDA` になります。

Excel では `resolved-by` で「`UNEXPANDED:` で始まる行だけ」＝**辿り切れなかった呼び出し**、
`level` で「3 以下」＝**起点の近く**、のように絞り込めます。

行の並びは毎回同じです。rootメソッドのクラス順（ソースフォルダ順 → 完全修飾クラス名順 → 宣言行順）、rootメソッドからの呼び出し順（深さ優先）で、
具象クラスの候補が複数ある呼び出しは候補ごとに 1 行、宣言型自身の実装 → 下位型（直接の下位型は完全修飾クラス名順）の順に出ます。
末尾の `type resolution failed …` の行はソースの並び順（ソースフォルダ順 → ファイルの相対パス順 → 呼び出し順）で出ます。


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

`unresolvedCause` は、絞れなかった呼び出しのレシーバがどこから来たかで決まり、次に何を調べればよいかの手がかりになります。
タグは `call-hierarchy.csv` の[注記](#注記)と同じなので、一覧で見つけた呼び出しをそのまま階層側で `grep` して追えます。

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

- **他から呼び出せる定義**だけを並べます。ラムダ式の本体（`lambda$…`）、static 初期化子（`<clinit>`）、
  無名クラス（`Outer$1`）のメソッドは出さず、呼び出し階層の側で読みます（[ラムダ式・メソッド参照](#ラムダ式メソッド参照)）。
  内部クラス・static なネストクラス・ローカルクラスのメソッドは出します
- コンストラクタ（`<init>`）は出しません（`call-hierarchy.csv` でも行にしていないため）
- jar の中のメソッドなど、ソースに宣言が無いものは出しません。呼ばれている事実は `call-hierarchy.csv` に残ります
- `reachable` の起点は `call-hierarchy.csv` と同じです（[用語](#用語)の「起点」）


### 注記

`call-hierarchy` 列の最後に付く補足です。どう解決したかは `resolved-by` 列にあるので、注記に載るのは
**その列に無いこと**（打ち切りの理由、候補の件数とレシーバの由来、繋いだ契約）だけです。
先頭に大文字のタグが付くので、タグで grep すれば種類ごとに拾えます。

| タグ | 意味 |
|---|---|
| `[UNEXPANDED:*]` | ここから先へ降りなかった。`grep '\[UNEXPANDED'` で辿り切れなかった箇所を一括で拾える |
| `[EXTERNAL]` | 呼び出し先が自プロジェクトの外。打ち切りではあるが性質が違うので `UNEXPANDED` には入れない |
| `[UNREACHABLE]` | この経路では実行されないと分かった呼び出し |
| `[RESOLVED:CALLBACK]` | 契約で繋いだ呼び出し。後ろに繋いだ契約が続く。注記に出る `[RESOLVED:*]` はこれだけ |

| 注記 | 意味 |
|---|---|
| `[UNEXPANDED:CYCLE] returns to a method already on this path` | この経路上で既に呼んでいるメソッドに戻る呼び出し。ここで打ち切る |
| `[UNEXPANDED:DEPTH] depth limit (N) reached` | `max.depth` に達した |
| `[UNEXPANDED:CHA] N candidates: {reason}` | 実装を1つに絞れなかった。候補は1件ずつ行になるが、その先へは降りない（候補数^深さで爆発するため）。理由は上の `unresolvedCause` の表と同じ。候補のうち `exclude.packages` で除外したものは行にせず、`(K excluded by exclude.packages and not written as rows)` と数を書く（jar のインターフェースの宣言は「jar の中にも実装がありうる」候補として数に入るので、既定の `java.**` の除外でよく付く） |
| `[UNEXPANDED:REFLECTION] N candidates: matched by name because argument types are unknown` | `getMethod` の引数型（クラスリテラル）が揃わず、同名のメソッドを候補にした |
| `[UNEXPANDED:NO_IMPL] no implementation with a body in the source` | インターフェースや抽象メソッドの宣言はあるが、中身を書いたクラスがソース上に1つも無い。`[EXTERNAL]`（ソースが読めないだけ）とは違い、読めた上で見つからない状態なので、`source.folders` の設定漏れかデッドコードを疑う |
| `[UNEXPANDED:GENERATED] implementation is generated at compile time (フレームワーク名): FQN is…` | 実装がアノテーション処理でビルド時に生成される型への呼び出し（[docs/doma-generated-impl-qa.md](docs/doma-generated-impl-qa.md) 参照） |
| `[UNEXPANDED:LAMBDA] implemented by a lambda/method reference (which one runs is undetermined)` | その関数型インターフェースをラムダかメソッド参照が実装しているが、この呼び出し箇所にどれが渡ってくるかは特定できなかった（[ラムダ式・メソッド参照](#ラムダ式メソッド参照)参照） |
| `[EXTERNAL] no source to follow` | 呼び出し先がjar内などでソースが無く、そこから先を辿れない。型解決自体は成功しているので、呼び先が実在することは確か |
| `[EXTERNAL] type guessed from an import (unverified)` | クラスパス不足で型解決できず、`import` 文から型名を推定した。メソッドの実在やオーバーロードは未確認で、**推定が外れている可能性がある** |
| `[UNREACHABLE] not called on this path: condition '…' does not hold (…)` | 呼び出しを囲む条件が、この経路では成立しないと分かった（[docs/branch-pruning.md](docs/branch-pruning.md) 参照） |
| `[RESOLVED:CALLBACK] contract: Thread#start() calls run()` | 呼び出し先は jar の中だが、「渡した値のこのメソッドを呼び戻す」という契約で繋いだ（[docs/callback-contracts.md](docs/callback-contracts.md)）。jar の中を読んだわけではない |
| `[UNEXPANDED:CHA] N candidates: method reference to an overridable method contract: …` | 同じく契約で繋いだが、渡したのが上書きされうるメソッドへのメソッド参照（`this::hook` 等）で、動く実装を1つに決められなかった。候補を1件ずつ行にし、その先へは降りない（`resolved-by` は `UNEXPANDED:CALLBACK`） |
| `type resolution failed …` | 呼び出し先の型を特定できなかった行（`resolved-by` が `UNRESOLVED:`）。注記ではなく専用の行 |
| `external-ref:EXACT` 等 | 被参照スキャンの行（[下記](#jar-からの被参照メソッド)）。同じく専用の行 |

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
| `external-ref:INHERITED` | 親から継承したメソッドへの参照。JVM がその参照を解決する宣言（親クラスの連鎖を先に、無ければインターフェースの宣言のうち最も特定的なもの。インターフェースの `static`・`private` は除く）のメソッドとして出る |
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
**合成メソッド**として1つのノードにします（`methods.csv` には出しません）。
static 初期化子・static フィールド・enum 定数の引数の中のラムダは `lambda$static$N` です。
通し番号はスタックトレースに出る javac の番号と一致するとは限りません（[docs/lambda-expansion-qa.md](docs/lambda-expansion-qa.md) の Q13）。

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

解析結果のキャッシュは出力フォルダには置かず、**このツールのフォルダ**の
`.cache/<project.root のフォルダ名>_<絶対パスのハッシュ 8 桁>/` に作ります。
2 回目からは、変わったファイルと、その変更で結果が変わりうるファイル（変わったファイルの型を使っているファイルなど）だけを
解析し直します。依存 jar やクラスフォルダ（兄弟モジュールの `target/classes` など）の変化も見ます。

- 同じプロジェクトを指す設定ファイルは同じキャッシュを共有し、名前が同じでも場所が違うプロジェクトは混ざりません
- 実行していないときなら消しても構いません（次の実行が全件の解析になります）。
  置き場所を変えるときは `cache.folder`、再利用しないときは `cache.enabled=false` をコンフィグで指定します。
- 同じキャッシュを 2 つの実行（CLI と Eclipse・VS Code のプラグイン、CI のジョブなど）が同時に使うと、
  あとの実行は先の実行の終わりを待ちます（最長 30 分。環境変数 `JCHE_CACHE_LOCK_WAIT_SECONDS` で秒数を変えられます）
- どのファイルを解析し直すかの決まりは [config/config.properties](config/config.properties) の `cache.enabled` のコメントに、
  作りは [docs/cache-design.md](docs/cache-design.md) にあります

---

## ドキュメント

| 知りたいこと | 場所 |
|---|---|
| 使い方・ツールの起動方法 | [Quick start](#quick-start)（このファイル）、起動コマンドの全仕様は [docs/cli.md](docs/cli.md) |
| 出力CSVファイルの読み方 | [結果の読み方](#結果の読み方)・[出力ファイル](#出力ファイル)（このファイル） |
| 設定ファイルの項目内容 | [config/config.properties](config/config.properties) のコメント |
| 機能別の詳しい使い方・設計の記録（機能ごとに迷った点と結論）・再実装用の仕様 | [docs/README.md](docs/README.md) |

---

## ライセンス

Copyright 2026 Inoue Kazuhiro ([@instreest](https://github.com/instreest)). SPDX-License-Identifier: Apache-2.0

---

# English

[Japanese](#java-call-hierarchy-exporter) | **English**

## What it does (overview)

This tool analyzes the method call hierarchy of a whole Java project in one pass and writes it to CSV files.
Open the result in Excel and filter it to find the impact surface of the method you are about to change.
**It is under development, so its features may change without notice.**

| What you want | Where |
|---|---|
| How to use it, how to start the tool | [Getting started](#getting-started) (this file) |
| How to read the output CSV | [Output files](#output-files) (this file) |
| What each config item means | the comments in [config/config.properties](config/config.properties) |

### How it compares with other tools

| Tool | Good for | How this tool differs |
|---|---|---|
| The IDE call hierarchy (Ctrl+Alt+H in Eclipse and the like) | Checking the callers of one method on the spot while you write code | It expands one method at a time on screen, so you cannot save, filter or share the whole project as a list. It also leaves no record of why a call through an interface went to a given implementation |
| Text search with `grep` | Quickly finding where a name appears | It cannot tell apart different methods with the same name, or calls through an interface or inheritance |
| `jdeps` | Dependencies between jars, packages and classes | It does not show calls between methods or the paths they take |

If you want to follow callers inside your IDE, the same analysis is also available from the
[Eclipse plugin](docs/eclipse-plugin-usage.md) and the [VSCode plugin](docs/vscode-plugin-usage.md)
(their documentation is in Japanese).

## Requirements and limitations

### Requirements

| Item | Requirement |
|---|---|
| OS | Windows, Linux |
| What to install first | Nothing. The JDK 25 that runs the tool and Eclipse JDT (Eclipse's Java compiler), which does the analysis, are downloaded on the first run, after asking you (about 165MB over the network, about 500MB on disk). For machines that cannot reach the network, see [Other ways to use it](#other-ways-to-use-it) |
| Sources it can analyze | Java sources (`.java`). The supported Java versions are those supported by Eclipse JDT, the analysis engine. The latest release, 3.46.0, supports Java 8 to 26 |
| Build setups | The source folders and dependency jars declared in Eclipse's `.classpath`, Maven (`pom.xml`) or Gradle (`build.gradle`) are found automatically and analyzed |
| Dependency jars | **They must already be on your machine.** The tool does not run the build tool of the project it analyzes and does not download them. It uses the jars in a local repository such as `~/.m2/repository`, so either build the project once first (`mvn dependency:go-offline` or similar), or save the compile-time and run-time dependency jars in a `lib` folder and point the config at it |

### What it cannot see

It is a static analysis, so anything that is only decided at run time is out of reach. In that case it does
not remove the call: it lists the candidates or says it could not follow the call.

- **It does not follow code with no source (the inside of jars).** Calls that come back to you through the JDK or a
  framework (`new Thread(task).start()` → `task.run()` and the like) are connected only when the
  [contract table](docs/callback-contracts.md) lists them (the bundled rows plus any you add)
- **It does not know values decided at run time.** Reflection with class names built from configuration or
  input, or an implementation chosen by a run-time condition, stay as lists of candidates
  ([docs/static-analysis-limits.md](docs/static-analysis-limits.md))
- **If the sources do not compile or dependency jars are missing, the result has gaps.** Then a
  `warnings.txt` appears in the output folder and says what is missing

---

## Getting started

1. **Get the tool** — clone this repository (or use "Code → Download ZIP" on GitHub and unpack it).

     ```bash
     git clone https://github.com/instreest/java-call-hierarchy-exporter.git
     cd java-call-hierarchy-exporter
     ```

2. **Write the project to analyze in the config file** — set `project.root` in
   [`config/config.properties`](config/config.properties) to the folder of the project you want to analyze.
   **This one line is all that is required.** Left empty, the source folders, dependency jars and encoding are
   read from `.classpath`, `pom.xml` or `build.gradle`.

     ```properties
     # Use / as the separator, even on Windows (to use \, write it twice, like \\)
     project.root=C:/work/myapp
     ```

   A relative path starts from the folder of the config file (`config/`).
   Copying the config file per analyzed project (`config/myapp.properties` and so on) keeps things tidy.

3. **Run it** — pass the config file to the launcher in the repository root.

     ```bat
     rem Windows
     .\java-call-hierarchy-exporter.cmd config\config.properties
     ```

     ```bash
     # Linux / macOS / Git Bash
     ./java-call-hierarchy-exporter.sh config/config.properties
     ```

   On the first run it asks before downloading the JDK and the rest; answer `y` (it shows what it will
   download and how large it is). The first run takes longer because of the download and the full analysis;
   from the second run on, only the changed files are analyzed.
   If you got the ZIP and the `.sh` is not executable, run it as `bash java-call-hierarchy-exporter.sh …`.

4. **Check the output for a `warnings.txt`** — every run creates a folder
   `config/<analysis start time>_<project name>/`. A `warnings.txt` in it means **the result has gaps**, because of
   missing dependency jars, compile errors and the like. It says what happened and how to fix it; fix that and
   run again.

5. **Open the CSV** — open `call-hierarchy.csv` in the same folder with Excel.
   How to read it is in [Reading the results](#reading-the-results) below.

---

## Reading the results

### Tracing the impact of a change

1. Open `call-hierarchy.csv` in Excel and turn on the filter (Data → Filter)
2. In the **`callee` column**, pick the method you are changing, as `ClassName.methodName` (there are no
   arguments, so overloads share one name)
3. The **`root` column** of the remaining rows lists the entry points that reach it (screen actions, a batch
   job's `main`, API handlers and so on). The **`caller` column** is the call site itself (file and line), and
   the **`call-hierarchy` column** is the path from the entry point

Even when a call through an interface could not be narrowed to one implementation, each candidate
implementation gets its own row, so filtering `callee` by the implementation class name finds it.

### Reading one row

```csv
caller,callee,resolved-by,level,root,call-hierarchy
at jp.co.example.action.OrderAction.execute(OrderAction.java:50),OrderService.findOrder,RESOLVED:NO_OVERRIDE,1,OrderAction.execute,OrderService.findOrder
at jp.co.example.service.OrderService.findOrder(OrderService.java:25),OrderDaoImpl.selectById,RESOLVED:SPRING_DI,2,OrderAction.execute,OrderService.findOrder,OrderDaoImpl.selectById
```

| Column | Example (the second row above) | Meaning |
|---|---|---|
| `caller` | `at jp.co.example.service.OrderService.findOrder(OrderService.java:25)` | Where the call is made. It has the same shape as a Java stack trace, so Eclipse can jump to the source ([below](#jumping-to-the-source-in-eclipse)) |
| `callee` | `OrderDaoImpl.selectById` | The method being called |
| `resolved-by` | `RESOLVED:SPRING_DI` | How the callee was decided. `RESOLVED:` means it was pinned down to one; `UNEXPANDED:` means it could not be, and the candidates are listed |
| `level` | `2` | How many calls away from the entry point it is |
| `root` | `OrderAction.execute` | The entry point method |
| `call-hierarchy` | `OrderService.findOrder,OrderDaoImpl.selectById` | The path from the step after the entry point to `callee` (one step per column). A note may follow in the last column |

### Checking the calls it could not follow

The prefix of the `resolved-by` column tells you how certain every row is. These are the rows to check by
eye during an impact analysis.

| `resolved-by` | Meaning | What to do |
|---|---|---|
| `UNEXPANDED:…` | The implementation could not be narrowed to one, so the candidates are listed. **Nothing below the candidates is followed** | To go further up that path, filter the `callee` column again by the method in `caller` and keep climbing. Once you tell the tool how to narrow it, the next run pins it down to one (`contracts-suggested.txt` in the output folder; [docs/callback-contracts.md](docs/callback-contracts.md)) |
| `UNRESOLVED:…` | The type of the callee could not be determined (usually missing dependency jars) | Follow `warnings.txt` to supply the jars, and run again |

A note at the end of the `call-hierarchy` column (such as `[EXTERNAL] no source to follow`) says why the walk
stopped there. All the notes are listed in [Notes](#notes).

### Jumping to the source in Eclipse

Copy a row of `call-hierarchy.csv` and paste it into Eclipse's "Java Stack Trace Console": the
`(file:line)` part becomes a hyperlink to the source.

1. Choose Window > Show View > Console from the menu
2. Click the `▼` next to the "Open Console" button (the monitor icon with a plus) in the top right of the
   Console view toolbar, and choose "Java Stack Trace Console"
3. Paste the text of `call-hierarchy.csv` into that console

### Glossary

| Term | Meaning |
|---|---|
| Entry point (`root`) | The method the walk starts from. Set it with `entry.packages` in the config. When that is empty, every method in the source with no caller, plus every method a framework is known to call (`main`, `@GetMapping`, a servlet's `doGet` and so on), is an entry point (whole-project mode) |
| Resolving concrete classes | For a call through an interface or parent class type, deciding which implementation class actually runs ([Resolving concrete classes](#resolving-concrete-classes)) |
| CHA | Class Hierarchy Analysis: listing every candidate implementation from the type hierarchy alone. Used when nothing else could decide, and written as `UNEXPANDED:CHA` |
| Receiver | The value a method is called on, such as `dao` in `dao.find()` |
| Contract table | A table describing what happens outside your source (the JDK, frameworks), or which implementation class to use ([docs/callback-contracts.md](docs/callback-contracts.md)) |
| Note | The remark at the end of the `call-hierarchy` column. It starts with an upper case tag ([Notes](#notes)) |

---

## Other ways to use it

The linked documents are in Japanese.

| What you want | How |
|---|---|
| Create a config file from a menu, or process several configs at once | The interactive mode (run the launcher with no arguments), or pass several config files ([docs/cli.md](docs/cli.md)) |
| Follow callers inside Eclipse | The [Eclipse plugin](docs/eclipse-plugin-usage.md) |
| Follow callers inside VSCode | The [VSCode plugin](docs/vscode-plugin-usage.md) |
| Analyze in GitHub Actions and get the CSV as an artifact | Call [`action.yml`](action.yml) in the repository root with `uses:` ([docs/github-actions.md](docs/github-actions.md): a workflow you can copy as is, and notes on fetching dependencies, security and storage) |
| Use it on an isolated network (no downloads) | Run it with the JDK and JDT jars that come with Pleiades / Eclipse ([docs/cli.md](docs/cli.md#閉域ネットワークで動かすpleiadeseclipse-の-jar-を使う)) |
| Find the jars of other teams that call your code | `external.library.folders` in the config ([Methods referenced from external jars](#methods-referenced-from-external-jars)) |
| Narrow an implementation it could not decide down to one | Write a contract table ([docs/callback-contracts.md](docs/callback-contracts.md)), or an extension for complex conditions ([docs/instance-analysis-plugin.md](docs/instance-analysis-plugin.md)) |
| Also output the `if` conditions that guard each call | `conditions.target` in the config ([docs/call-conditions.md](docs/call-conditions.md)) |

Every config item is described in the comments of [config/config.properties](config/config.properties).

---

## Output files

From here on is the detailed description of every column and symbol in the output.

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

The CSV files are UTF-8 with a BOM, so Excel opens them directly. The content of the CSV is in English
whatever the display language.

`warnings.txt` is created only when the tool's assumption — the sources build and all dependency jars are
resolved — does not hold.
Besides missing dependency jars, a path in the config file that does not exist and compile errors, it lists files whose package declaration does not match their folder (common when `source.folders` is one level off), files that the Java parser stopped on and could not analyze, output that stopped partway, and a dependency jar that was rewritten in place with the same modification time while the analysis server (the Eclipse or VS Code plugin) kept it open.
`run.log` is created on every run as the record of what happened (what ran with which settings and where things were saved).

### `call-hierarchy.csv` — the call hierarchy

| Column | Content |
|---|---|
| `caller` | The caller, in the same format as a Java stack trace. It points at the **call site** line |
| `callee` | The callee, as **ClassName.methodName** (no arguments). Usable as an Excel filter |
| `resolved-by` | How the callee was pinned down, or how the candidates were collected when it could not be narrowed (see below). **Every row has one** |
| `level` | The depth from the entry point (the entry point is `0`, what it calls is `1`). It always matches the number of nodes listed in `call-hierarchy` |
| `root` | The entry method, as ClassName.methodName. Usable as an Excel filter |
| `call-hierarchy` | The path from the entry point, one node per column (**variable length**). When a note applies, it is the last element ([Notes](#notes)) |

`resolved-by` is "a prefix (how certain it is) plus the label of the resolution step (how it was done)".

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

The row order is the same on every run. Rows are ordered by the class of the root method (source folder order,
then fully qualified class name, then declaration line), and within that by the call order from the root method
(depth first). A call with several candidate concrete classes gets one row per candidate, ordered as the
implementation of the declared type itself first, then subtypes (direct subtypes in fully qualified class name
order). The `type resolution failed ...` rows at the end come in source order (source folder order, then
relative file path, then call order).

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

`unresolvedCause` is decided by where the receiver of the unresolved call came from, and it tells you what
to look at next. The tags are the same as the [notes](#notes) in `call-hierarchy.csv`, so you can take a call
you found in the list and `grep` for it on the hierarchy side.

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

- Only definitions **that something else can call** are listed. Lambda bodies (`lambda$...`), static
  initializers (`<clinit>`) and the methods of anonymous classes (`Outer$1`) are not written; you read them in
  the call hierarchy ([Lambdas and method references](#lambdas-and-method-references)). The methods of inner
  classes, static nested classes and local classes are written
- Constructors (`<init>`) are not written (they are not rows in `call-hierarchy.csv` either)
- Anything without a declaration in the source, such as a method inside a jar, is not written. The fact
  that it is called remains in `call-hierarchy.csv`
- The entry points for `reachable` are the same as for `call-hierarchy.csv` (see "Entry point" in the
  [Glossary](#glossary))

### Notes

The remark at the end of the `call-hierarchy` column. How a call was resolved is already in the
`resolved-by` column, so a note only carries **what that column cannot say**: why the walk stopped, the number
of candidates and where the receiver came from, and the contract that connected the call.
A note starts with an upper case tag, so you can pick out a kind of note by grepping for its tag.

| Tag | Meaning |
|---|---|
| `[UNEXPANDED:*]` | Nothing below this was followed. `grep '\[UNEXPANDED'` finds every place the walk stopped |
| `[EXTERNAL]` | The callee is outside your own project. It is a stop too, but a different kind, so it is not under `UNEXPANDED` |
| `[UNREACHABLE]` | A call that was shown not to run on this path |
| `[RESOLVED:CALLBACK]` | A call connected by a contract; the contract itself follows it. This is the only `[RESOLVED:*]` that appears as a note |

| Note | Meaning |
|---|---|
| `[UNEXPANDED:CYCLE] returns to a method already on this path` | A call back to a method already on this path. It stops here |
| `[UNEXPANDED:DEPTH] depth limit (N) reached` | `max.depth` was reached |
| `[UNEXPANDED:CHA] N candidates: {reason}` | The implementation could not be narrowed to one. Each candidate becomes a row, but nothing below them is followed (it would explode as candidates^depth). The reason is the same as in the `unresolvedCause` table above. Candidates excluded by `exclude.packages` are not written as rows, and their number is written as `(K excluded by exclude.packages and not written as rows)` (the declaration in a jar interface counts as a candidate because the jar may also implement it, so this often appears with the default `java.**` exclusion) |
| `[UNEXPANDED:REFLECTION] N candidates: matched by name because argument types are unknown` | The argument types of `getMethod` (class literals) were not all available, so methods with the same name were taken as candidates |
| `[UNEXPANDED:NO_IMPL] no implementation with a body in the source` | There is an interface or abstract method declaration, but no class in the source writes the body. Unlike `[EXTERNAL]` (where the source simply cannot be read), the source was read and nothing was found, so suspect a missing `source.folders` entry or dead code |
| `[UNEXPANDED:GENERATED] implementation is generated at compile time (framework): FQN is...` | A call into a type whose implementation is generated at build time by annotation processing (see [docs/doma-generated-impl-qa.md](docs/doma-generated-impl-qa.md)) |
| `[UNEXPANDED:LAMBDA] implemented by a lambda/method reference (which one runs is undetermined)` | A lambda or method reference implements that functional interface, but which one arrives at this call site could not be determined (see [Lambdas and method references](#lambdas-and-method-references)) |
| `[EXTERNAL] no source to follow` | The callee is inside a jar or similar, with no source, so nothing below it can be followed. Type resolution itself succeeded, so the callee certainly exists |
| `[EXTERNAL] type guessed from an import (unverified)` | The classpath was incomplete so the type could not be resolved, and the type name was guessed from an `import`. Neither the method's existence nor the overload was checked, so **the guess may be wrong** |
| `[UNREACHABLE] not called on this path: condition '...' does not hold (...)` | The condition around the call was shown not to hold on this path (see [docs/branch-pruning.md](docs/branch-pruning.md)) |
| `[RESOLVED:CALLBACK] contract: Thread#start() calls run()` | The callee is inside a jar, but it was connected by the contract "it calls this method on the value you passed" ([docs/callback-contracts.md](docs/callback-contracts.md)). The inside of the jar was not read |
| `[UNEXPANDED:CHA] N candidates: method reference to an overridable method contract: ...` | Also connected by a contract, but what was passed is a method reference to a method that can be overridden (such as `this::hook`) and the implementation that runs could not be narrowed to one. Each candidate becomes a row and nothing below it is followed (`resolved-by` is `UNEXPANDED:CALLBACK`) |
| `type resolution failed ...` | A row for a call whose callee type could not be determined (`resolved-by` is `UNRESOLVED:`). Not a note but a row of its own |
| `external-ref:EXACT` and the like | A row of the external reference scan ([below](#methods-referenced-from-external-jars)). Also a row of its own |

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
| `external-ref:INHERITED` | A reference to a method inherited from a parent. It appears as the method the JVM resolves the reference to (the chain of superclasses first; otherwise the most specific interface declaration, excluding `static` and `private` interface methods) |
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
(`lambda$enclosingMethod$serial`). It is not listed in `methods.csv`.
A lambda inside a static initializer, a static field or an enum constant's arguments is `lambda$static$N`.
The serial does not always match the javac number you see in a stack trace
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

The analysis cache does not live in the output folder. It is created under **the tool's own folder**, at
`.cache/<name of the project.root folder>_<8 hex digits of the absolute path>/`.
From the second run on, only the changed files and the files whose results the change can affect (such as the
files that use the changed files' types) are analyzed again. Changes in dependency jars and class folders (a
sibling module's `target/classes` and the like) are tracked too.

- Config files pointing at the same project share the same cache, and projects with the same name in different
  places do not get mixed up
- You may delete it while nothing is running (the next run then analyzes everything).
  To move it, set `cache.folder` in the config; to stop reusing it, set `cache.enabled=false`
- When two runs use the same cache at the same time (the CLI and the Eclipse or VS Code plugin, CI jobs, and so
  on), the later run waits until the earlier one finishes (at most 30 minutes; set the environment variable
  `JCHE_CACHE_LOCK_WAIT_SECONDS` to change the number of seconds)
- The rules for which files are analyzed again are in the comment on `cache.enabled` in
  [config/config.properties](config/config.properties), and the design is in
  [docs/cache-design.md](docs/cache-design.md) (in Japanese)

---

## Documentation

| What you want | Where |
|---|---|
| How to use it, how to start the tool | [Getting started](#getting-started) (this file); the full launcher reference is [docs/cli.md](docs/cli.md) |
| How to read the output CSV | [Reading the results](#reading-the-results) and [Output files](#output-files) (this file) |
| What each config item means | the comments in [config/config.properties](config/config.properties) |
| Detailed guides per feature, design notes (what was hard and what was decided, per feature), and the spec for reimplementation | [docs/README.md](docs/README.md) (in Japanese) |

---

## License

Copyright 2026 Inoue Kazuhiro ([@instreest](https://github.com/instreest)). SPDX-License-Identifier: Apache-2.0
