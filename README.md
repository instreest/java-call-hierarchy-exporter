# java-call-hierarchy-exporter
A tool that recursively extracts Java method call hierarchies across an entire project and exports them to CSV files. Powered by Eclipse JDT and runnable via JBang.

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

```yaml
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

依存の取得を省くと解析結果が欠けます。詳細は [docs/github-actions.md](docs/github-actions.md) を参照してください。

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
at jp.co.example.action.OrderAction.execute(OrderAction.java:50),OrderService.findOrder,OrderAction.execute,OrderService.findOrder
at jp.co.example.service.OrderService.findOrder(OrderService.java:25),OrderDaoImpl.selectById,OrderAction.execute,OrderService.findOrder,OrderDaoImpl.selectById
```

| 列 | 内容 |
|---|---|
| `caller` | 呼び出し元。Javaのスタックトレースと同じ形式。**呼び出し箇所**の行を指す |
| `callee` | 呼び出し先。**クラス名.メソッド名**（引数は付けない）。Excelのフィルタに使える |
| `root` | 起点メソッド。クラス名.メソッド名の形式でExcelのフィルタに使える |
| `call-hierarchy` | 起点からの呼び出し先を1ノード1列で展開（**可変長**） |

コンストラクタの呼び出し自体は行になりません。
コンストラクタ内からのメソッド呼び出しは行として出力されます。

行順は、rootメソッドのクラス順（ソースフォルダ順 → 完全修飾クラス名順 → 宣言行順）、rootメソッドからの呼び出し順（深さ優先）です。
具象クラスの候補が複数ある呼び出しは候補ごとに 1 行で、宣言型自身の実装 → 下位型（直接の下位型は完全修飾クラス名順）の順に出ます。
末尾の `型解決に失敗（…）` の行はソースの並び順（ソースフォルダ順 → ファイルの相対パス順 → 呼び出し順）で出ます。
注記が付く場合は `call-hierarchy` の**最後の要素**として出ます。 （[注記](#注記)）


### `methods.csv` — ソース上の全メソッドとその呼び出し状況

`call-hierarchy.csv` が起点からの経路を展開するのに対し、こちらはソース上のメソッドを 1 行ずつ並べた一覧です。
経路の数ではなくメソッドの数で決まるので大きくなりません。
「誰からも呼ばれていないのはどれか」「よく呼ばれている共通処理はどれか」を俯瞰するのに使います。

```csv
method,declaringType,typeKind,file,line,hasBody,inDegree,outDegree,role,reachable,unresolvedCalls,unresolvedCause,inHierarchy,absentCause
OrderAction.execute(),jp.co.example.action.OrderAction,C,src/jp/co/example/action/OrderAction.java,45,1,0,1,ENTRY_CANDIDATE,1,0,,1,
OrderService.findOrder(String),jp.co.example.service.OrderService,C,src/jp/co/example/service/OrderService.java,20,1,1,1,NORMAL,1,1,[UNEXPANDED:CHA] フィールド変数,1,
OrderDao.selectById(long),jp.co.example.dao.OrderDao,I,src/jp/co/example/dao/OrderDao.java,8,0,0,0,ISOLATED,0,0,,0,[NOT_REACHED] 上流が未出力
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
| `ENTRY_CANDIDATE` | 呼び出し元が無い。画面入口・バッチ・デッドコード・テスト・リフレクション経由が混ざるので仕分けが要る |
| `ISOLATED` | 呼び出し元も呼び出し先も無い。デッドコードの疑いが濃い |
| `LEAF` | 呼び出し先が無い。末端処理 |
| `NORMAL` | 上記以外 |

`unresolvedCause` は、絞れなかった呼び出しのレシーバ（呼び出しの受け手）がどこから来たかで決まります。
次に何を調べればよいかの手がかりになります。
タグは `call-hierarchy.csv` の[注記](#注記)と同じものを使っているので、
一覧で見つけた呼び出しをそのまま階層側で `grep` して追えます。

| unresolvedCause | 意味 |
|---|---|
| `[UNEXPANDED:CHA] 戻り値（ファクトリメソッド等）` | レシーバが他のメソッドの戻り値。ファクトリの実装を[プラグイン](docs/instance-analysis-plugin.md)で教えると絞れることがある |
| `[UNEXPANDED:CHA] 引数（メソッド外から渡される）` | レシーバが呼び出し元から渡された引数 |
| `[UNEXPANDED:CHA] フィールド変数` | レシーバがフィールド。DI で注入される形なら[プラグイン](docs/instance-analysis-plugin.md)で絞れる |
| `[UNEXPANDED:CHA] ローカル変数` | レシーバがローカル変数（同一メソッド内の `new` は追跡済みで、それでも絞れなかったもの） |
| `[UNEXPANDED:CHA] 自クラス（this）` / `型名（static）` / `レシーバ不明` | それぞれ `this`・暗黙のレシーバ、static 呼び出し、配列要素やキャスト式など |
| `[UNEXPANDED:NO_IMPL] 本体を持つ実装がソース上に無い` | 中身を書いたクラスがソース上に1つも無い |
| `[UNEXPANDED:GENERATED] 実装はコンパイル時生成（名前）` | 実装がアノテーション処理でビルド時に生成される型（[docs/doma-generated-impl-qa.md](docs/doma-generated-impl-qa.md) 参照） |
| `[UNEXPANDED:LAMBDA] ラムダ/メソッド参照による実装あり` | その関数型インターフェースをラムダかメソッド参照が実装している |

`absentCause` は、そのメソッドが `call-hierarchy.csv` に1行も出なかった理由です。
打ち切りで階層から消えた部分木は、ここでしか見えません。

| absentCause | 意味 |
|---|---|
| `[UNEXPANDED:CHA] 候補のため展開されなかった` | 実装を1つに絞れず、候補として行にはなるがその先へ降りなかった |
| `[UNEXPANDED:CYCLE] 循環のため展開されなかった` | 経路上で既に呼んでいるメソッドへ戻る辺だった |
| `[UNREACHABLE] 条件分岐で打ち切った先` | 条件分岐の静的解析で打ち切った呼び出しから先にしかない（[docs/branch-pruning.md](docs/branch-pruning.md) 参照） |
| `[EXCLUDED] exclude.packages で除外` | `exclude.packages` で除外された |
| `[NOT_REACHED] 上流が未出力` | そこへ至る呼び出し自体が出ていない（深さ制限・行数上限の先、起点から辿り着かない） |

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
  空欄のとき（全体モード）は「呼び出し元が無く、ソース上に本体を持つメソッド」が起点になります

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

| タグ | 意味 |
|---|---|
| `[UNEXPANDED:*]` | ここから先へ降りなかった。`grep '\[UNEXPANDED'` で辿り切れなかった箇所を一括で拾える |
| `[EXTERNAL]` | 呼び出し先が自プロジェクトの外。打ち切りではあるが性質が違うので `UNEXPANDED` には入れない |
| `[UNREACHABLE]` | この経路では実行されないと分かった呼び出し |
| `[RESOLVED:*]` | 具象クラスをどう特定したか。読み飛ばすなら `grep -v '\[RESOLVED'` |

| 注記 | 意味 |
|---|---|
| `[UNEXPANDED:CYCLE] 経路上で既に呼んでいるメソッドへ戻る` | この経路上で既に呼んでいるメソッドに戻る呼び出し。ここで打ち切る |
| `[UNEXPANDED:DEPTH] 深さ制限(N)に達した` | `max.depth` に達した |
| `[UNEXPANDED:CHA] 候補N件: 理由` | 実装を1つに絞れなかった。候補は1件ずつ行になるが、その先へは降りない（候補数^深さで爆発するため）。理由は下表 |
| `[UNEXPANDED:REFLECTION] 候補N件: 引数型が不明なため名前で照合` | `getMethod` の引数型（クラスリテラル）が揃わず、同名のメソッドを候補にした |
| `[UNEXPANDED:NO_IMPL] 本体を持つ実装がソース上に無い` | インターフェースや抽象メソッドの宣言はあるが、中身を書いたクラスがソース上に1つも無い。`[EXTERNAL]`（ソースが読めないだけ）とは違い、読めた上で見つからない状態なので、`source.folders` の設定漏れかデッドコードを疑う |
| `[UNEXPANDED:GENERATED] 実装はコンパイル時生成（名前）: FQN は…` | 実装がアノテーション処理でビルド時に生成される型への呼び出し（[docs/doma-generated-impl-qa.md](docs/doma-generated-impl-qa.md) 参照） |
| `[UNEXPANDED:LAMBDA] ラムダ/メソッド参照による実装あり（どれが実行されるかは未特定）` | その関数型インターフェースをラムダかメソッド参照が実装しているが、この呼び出し箇所にどれが渡ってくるかは特定できなかった（[ラムダ式・メソッド参照](#ラムダ式メソッド参照)参照） |
| `[EXTERNAL] ソースが無いため辿れない` | 呼び出し先がjar内などでソースが無く、そこから先を辿れない。型解決自体は成功しているので、呼び先が実在することは確か |
| `[EXTERNAL] import から型名を推定（未検証）` | クラスパス不足で型解決できず、`import` 文から型名を推定した。メソッドの実在やオーバーロードは未確認で、**推定が外れている可能性がある** |
| `[UNREACHABLE] この経路では呼ばれない: 条件「…」が成立しない（…）` | 呼び出しを囲む条件が、この経路では成立しないと分かった（[docs/branch-pruning.md](docs/branch-pruning.md) 参照） |
| `[RESOLVED:DATAFLOW_NEW]` | `new` された具象型から特定した（捕捉された変数を含む） |
| `[RESOLVED:DATAFLOW_FACTORY]` | ファクトリメソッドの戻り値から具象クラスを特定した |
| `[RESOLVED:DATAFLOW_PARAM]` | 呼び出し元から渡された引数を経路上で追跡して特定した |
| `[RESOLVED:DATAFLOW_FIELD]` | コンストラクタ注入されたフィールドを経路上で追跡して特定した |
| `[RESOLVED:DATAFLOW_LAMBDA]` | ラムダ式かメソッド参照が、その関数型インターフェースの実装としてこの呼び出し箇所まで渡ってきたと特定した（[ラムダ式・メソッド参照](#ラムダ式メソッド参照)参照） |
| `[RESOLVED:SPRING_DI]` | DI コンテナ（Spring）の Bean 定義で候補が1つに定まった（[docs/spring-di-qa.md](docs/spring-di-qa.md) 参照） |
| `[RESOLVED:SPRING_DI_QUALIFIER]` | `@Qualifier` / `@Resource(name=...)` で指定された Bean 名で1つに定まった（同上） |
| `[RESOLVED:ラベル]` | インターフェース等から具象クラスに解決した（[具象クラスの解決](#具象クラスの解決)参照） |
| `[RESOLVED:REFLECTION]` | `Method.invoke` / `newInstance` を、リフレクションで指定されたメソッド・コンストラクタに解決した |
| `[RESOLVED:REFLECTION_INIT]` | `Class.forName` によるクラス初期化。そのクラスの static 初期化子（`<clinit>`）へ繋ぐ |
| `型解決に失敗（…）` | 呼び出し先の型を特定できなかった行（後述）。注記ではなく専用の行 |
| `被参照:EXACT` 等 | 被参照スキャンの行（後述）。同じく専用の行 |

1つの注記は最大2つのパーツからなり、両方付くときは ` / ` で繋がります。
前半が打ち切りの理由（`[UNEXPANDED:CYCLE]`・`[UNEXPANDED:DEPTH]`・`[EXTERNAL]`・`[UNREACHABLE]`）、
後半が絞り込みの結果（`[UNEXPANDED:CHA]`・`[RESOLVED:*]` 等）です。

```
[UNEXPANDED:CYCLE] 経路上で既に呼んでいるメソッドへ戻る / [UNEXPANDED:CHA] 候補5件: フィールド変数
```

### jar からの被参照メソッド

自分のコードを呼んでいる側のjarを config の `external.library.folders` に指定すると、
`call-hierarchy.csv` に追記されます。

```properties
external.library.folders=./lib
```

classファイルの命令列を読むため、「どのjar・どのクラスの**どのメソッドの何行目**から参照しているか」まで分かります。
`caller` 列は呼び出し階層の行と同じスタックトレース形式なので、Eclipse の Java スタック・トレース・コンソールに貼れば
（相手のソースがワークスペースにあれば）その行へ飛べます。起点も階層も無いので `root` 列には参照元の jar 名が入ります。
ラムダ式やメソッド参照（`Counter::bump`）からの参照も、それを書いた行として出ます。

```csv
caller,callee,root,call-hierarchy
at teamb.NightJob.run(NightJob.java:15),OrderService.findOrder,team-b-batch.jar,OrderService.findOrder,被参照:EXACT
at teamb.NightJob.run(NightJob.java:14),OrderService.OrderService,team-b-batch.jar,OrderService.OrderService,被参照:EXACT
at teamb.NoDebugJob.run(Unknown Source),OrderService.findOrder,team-b-batch.jar,OrderService.findOrder,被参照:EXACT
```

行番号は相手の jar が行番号情報付きでビルドされている（`javac` の既定）ときだけ出ます。
`-g:none` でビルドされた jar は、JVM のスタックトレースと同じく `(Unknown Source)` になります（メソッド名までは出ます）。

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
| 3 | （拡張が返すラベル） | ファクトリ・DI設定・外部リスト等（[docs/instance-analysis-plugin.md](docs/instance-analysis-plugin.md)） |
| 4 | `DATAFLOW_NEW` / `DATAFLOW_FACTORY` | `new` された型、またはファクトリメソッドの戻り値から特定（[注記の表](#注記)） |
| — | `DATAFLOW_PARAM` | 呼び出し元から渡された引数から特定（経路ごとに判定するため段の外） |
| — | `DATAFLOW_FIELD` | コンストラクタ注入されたフィールドから特定（同上） |
| — | `DATAFLOW_LAMBDA` | ラムダ式・メソッド参照から特定（同上。下記） |
| 5 | `SPRING_DI` / `SPRING_DI_QUALIFIER` | DI コンテナ（Spring）の Bean 定義で候補を絞った（[注記の表](#注記)） |
| 6 | `CHA` | 候補が複数のまま（低確度） |
| — | `GENERATED_IMPL:名前` | 実装がコンパイル時のアノテーション処理で生成される型（`NO_IMPL` の特殊形） |

`CHA` のまま絞れない呼び出しは、解決の条件を外から与えると1件に絞れます
（[docs/instance-analysis-plugin.md](docs/instance-analysis-plugin.md)）。

静的解析で絞れる条件・絞れない条件は [docs/static-analysis-limits.md](docs/static-analysis-limits.md) にまとめてあります。

---

## ラムダ式・メソッド参照

ラムダ式の本体は、javac と同じ名前（`lambda$囲みメソッド名$通し番号`）を付けた
**合成メソッド**として1つのノードにします（`methods.csv` には出しません。
インスタンスを通じて呼び出せるメソッドではないため）。

```csv
at fx.lambda.Holder.viaField(Holder.java:30),Holder.lambda$new$0,Holder.viaField,Holder.lambda$new$0,[RESOLVED:DATAFLOW_LAMBDA]
at fx.lambda.Holder.lambda$new$0(Holder.java:27),OrderDaoImpl.describe,Holder.viaField,Holder.lambda$new$0,OrderDaoImpl.describe,[RESOLVED:DATAFLOW_FIELD]
```

ラムダを作った箇所からは、必ず「生成した」1本の辺が出ます。
どこで実行されるか分からないラムダでも、本体の中の呼び出しが階層から落ちないようにするためです。
実行箇所を特定できたときは、そちらからも同じノードに繋がります（`[RESOLVED:DATAFLOW_LAMBDA]`）。

実行箇所を特定できる形:

| 形 | 例 |
|---|---|
| ローカル変数に入れて呼ぶ | `Runnable r = () -> ...; r.run();` |
| 引数で渡した先で呼ぶ | `runIt(() -> ...)` の中の `r.run()` |
| フィールドに保持して呼ぶ | `private final Runnable task = () -> ...;` の `task.run()` |
| メソッド参照 | `Runnable r = this::helper; r.run();` → `helper` に繋がる |
| ローカルのコレクションに詰めて拡張for文で回す | `jobs.add(() -> ...); for (Runnable j : jobs) j.run();` |

特定できない形（`[UNEXPANDED:LAMBDA]` が付きます）:

- `list.forEach(Runnable::run)` のように、**jar の中**から呼ばれる形。`forEach` の中はソースが無いので辿れません
- フィールドのコレクションに詰める形、詰める場所と回す場所が別メソッドの形
- 同じ変数に複数のラムダが入りうる形（どれが実行されるか決められないので、絞りません）

特定できない場合でも、生成の辺があるので本体の中の呼び出しは階層に出ます。

---

## キャッシュ

解析結果のキャッシュは出力フォルダには置かず、**このツールのプロジェクトフォルダ**の
`.cache/<project.root のフォルダ名>_<絶対パスのハッシュ 8 桁>/` に作ります。
同じプロジェクトを指す設定ファイルは同じキャッシュを共有し、名前が同じでも場所が違うプロジェクト
（ブランチごとのチェックアウト等）は混ざりません。設定ファイルをどこに置いても、
どこから実行しても、キャッシュの場所は変わりません。

ファイルは 2 つで、常に対で作られます。片方だけを消しても、次の実行で両方が作り直されます。

| ファイル | 中身 |
|---|---|
| `analysis-cache.tsv` | 呼び出し階層を出すための事実（構造とバインディング） |
| `dataflow-cache.tsv` | 呼び出し階層の出力には使わない、値の追跡のための事実 |

- 置き場所を変えるときは `cache.folder`、再利用しないときは `cache.enabled=false`
- 2 回目以降は変更されたファイルだけを解析し直します。依存 jar を足したときも、
  その jar の型を使っているファイルだけが対象です
- 大規模なコードベースで `OutOfMemoryError` にならないための作りと、差分更新が
  何を見て判断しているかは [docs/cache-design.md](docs/cache-design.md) にあります

---

## ライセンス

Copyright 2026 Inoue Kazuhiro ([@instreest](https://github.com/instreest)). SPDX-License-Identifier: Apache-2.0
