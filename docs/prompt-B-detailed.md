# プロンプトB — 目的 ＋ 健全性（AST解析のはまりどころ ＋ テストケース）

> このファイルは、生成AIに `java-call-hierarchy-exporter` と同じ目的のツールを
> **健全に**（静かに漏れない・誤って絞らない・落ちない）再現させるためのプロンプトです。
> 参照実装と同じ機能一式の仕様に、JDT/AST解析で実際に踏んだ落とし穴の解説と、
> 期待出力つきのテストケースを加えた、それ自体で完結したプロンプトです。
> 機能の再現より「目的を満たす道具を自分で設計してほしい」場合は
> [prompt-A-minimal.md](prompt-A-minimal.md) を使ってください。
>
> テストケースの期待値は、このリポジトリの実装をテスト用プロジェクトに対して
> 実行して採取した実測値です。
>
> 機能ごとの実装難易度と、自分のプロジェクトで不要な機能を省くときの目安は
> [feature-difficulty.md](feature-difficulty.md) にまとめてあります。省く機能が決まったら、
> 該当する節と第3部の該当テストをこのプロンプトから削って渡してください。
>
> 以下の水平線から下を、そのまま生成AIに渡してください。

---

あなたはJavaの静的解析ツールを実装します。第1部の仕様を満たし、第2部の落とし穴を
すべて回避し、第3部のテストケースを通してください。第2部・第3部は「なぜそうするか」を
含めて書いてあります。理由に反しない範囲で実装方法は任せますが、**理由を読んだうえで
別の方法を取る場合は、同じ症状が起きないことをテストで示してください**。

---

# 第1部 仕様

## 1. 目的

レガシーなJavaプロジェクトを改修するとき、「このメソッドを直すと、どこまで影響するか」を
**機械的に、プロジェクト全体について一括で**洗い出したい。

EclipseのGUIの「呼び出し階層」ビューは、コピーすると階層が失われる・再帰的に一括出力
できない・ワークスペース全体を一度に処理できない、という制約がある。それをCSV出力で
置き換える。出力はExcelのフィルタとgrepで読む。

- **Eclipse IDEは起動しない**。通常のJavaアプリとして動かし、解析エンジンにだけ
  Eclipse JDT Core（`org.eclipse.jdt.core`）の `ASTParser` をスタンドアロンで使う
- 対象は数万ファイル規模のプロジェクトでも `OutOfMemoryError` にならないこと

## 2. 成果物と技術制約

- Java 17 以上で動く。エントリポイントは `src/jche/CallHierarchyExporter.java`（`jche` パッケージ）。
  本体は処理フェーズに対応するパッケージに分けてよい（参照実装は `jche.config` /
  `jche.cache`（事実のレコード。JDT に依存しない）/ `jche.analysis`（フェーズ1）/
  `jche.graph`（フェーズ2）/ `jche.report`（フェーズ3）/ `jche.external` / `jche.extension` /
  `jche.util` の 53 ファイル）。ビルドは
  `javac -classpath "lib/*" -sourcepath src -d bin -encoding UTF-8 src/jche/CallHierarchyExporter.java`
  の1コマンドで、`-Xlint:all -Werror` で警告ゼロ
- 依存は JDT Core とその推移的依存のjarのみ。テストフレームワーク・ロギング
  フレームワーク・バイトコード解析ライブラリ（ASM等）は使わない
- 起動: `java -classpath "bin:lib/*" CallHierarchyExporter <config.propertiesのパス>...`。
  設定ファイルは複数渡せ、渡した順に独立して処理する（1つが失敗しても残りは処理し、最後に設定ごとの
  OK / FAIL と出力フォルダの一覧を出す。1つでも失敗すれば終了コード 1）。
  引数省略時は作業ディレクトリの `config/config.properties` を使い、その旨を標準エラーに出す
- 出力は設定ファイルごとに `output.folder` の下の `<解析開始日時 yyyyMMdd-HHmmss>_<project.root のフォルダ名>/`
  に書く（同じ秒に同名ができれば `_2`, `_3` …）。中身は `call-hierarchy.csv`、`methods.csv`、渡した設定ファイルの
  複製（同じファイル名）、`run.log`（標準出力と同じ内容、UTF-8。設定ごとに経過時間を 0 から数え直す）。
  出力フォルダは解析の前に作り、失敗してもログと設定の複製が残るようにする
- キャッシュは出力フォルダに置かず、ツール自身のプロジェクトフォルダ（作業ディレクトリとその上位、次に実行中の
  クラスの置き場所とその上位から `src/jche/CallHierarchyExporter.java` を探す。無ければ警告して作業ディレクトリ）の
  `.cache/<project.root のフォルダ名>_<project.root の絶対パスの SHA-256 先頭8桁>/` に、解析対象
  プロジェクトごとのサイドカーとして置く。中身は **`analysis-cache.tsv` の 1 ファイル**で、呼び出し階層の構造と
  値の追跡のための値（文字列の長さに上限を設けない）を、ソースファイルごとのブロックに持つ（2.14）。
  同じ `project.root` を指す設定は同じキャッシュを共有する
- 依存jarの取得方法（JBang・Maven・手動）は問わない。参考: JDT Core 3.46.0 は
  JDK 17 以上で動き Java 26 まで解析できる。Maven で
  `org.eclipse.jdt:org.eclipse.jdt.core:3.46.0` の推移的依存をコピーすると 19 個の jar になる
- ツールを動かすJDKは、解析対象のソースが使うJDK APIの版以上にする（2.3）

## 3. 入力: 設定ファイル（`config.properties`、UTF-8。同梱の既定は `config/config.properties`）

相対パスの起点は項目ごとに違う。**設定ファイルの置き場所**を起点にするものと、
**解析対象プロジェクト（`project.root`）**を起点にするものを区別すること。
相対パスは起点フォルダの配下だけ指定できる（`..` で外へ出る指定は項目名付きのエラー。外を
指すときは絶対パス。`project.root` だけは起点そのものなので制限なし）。`source.folders` は
絶対パスで書いても `project.root` の配下でなければならない（キャッシュのキーと出力の `file` 列を
`project.root` からの相対パスにするため）。数値項目は空欄なら既定値、書式が誤っていれば
どの項目かが分かるエラーにする。

| キー | 既定 | 意味 | 相対起点 |
|---|---|---|---|
| `project.root` | 必須 | 解析対象プロジェクトのルート | 設定ファイル |
| `source.folders` | 空 | ソースフォルダ（カンマ区切り）。空なら Eclipse の `.classpath` の `kind="src"` を使う | `project.root` |
| `library.folders` | 空 | 依存jarを集めたフォルダ（カンマ区切り）。フォルダ直下の `*.jar` を全部使う。`.classpath` の `kind="lib"` があれば合算。jarを足す・差し替える・外すと、次回の実行でその jar のパッケージを参照しているファイルと型解決に失敗していたファイルだけが解析し直される。**空なら `pom.xml` / `build.gradle` を読んでローカルリポジトリから集める（5.8）** | `project.root` |
| `library.build.tool` | `auto` | `library.folders` が空のときに読むビルドファイルの種類。`auto`（`pom.xml` があれば Maven、`build.gradle` / `settings.gradle` があれば Gradle。両方あれば Eclipse の `.project` / `.classpath` の nature・コンテナで決め、無ければ Maven）/ `maven` / `gradle` / `none`（自動取得しない） | — |
| `library.repositories` | 空 | ローカルリポジトリ（カンマ区切り）。空なら Maven の `~/.m2/repository`（`~/.m2/settings.xml` の `localRepository` があればそこ）と Gradle の `GRADLE_USER_HOME`（既定 `~/.gradle`）の `caches/modules-2/files-2.1` のうち存在するもの。相対パスは配下の制限なし。先頭の `~/` はホーム | 設定ファイルのフォルダ |
| `source.encoding` | `UTF-8` | 解析対象ソースの文字コード（`MS932` 等） | — |
| `source.level` | 空 | 解析対象のJavaバージョン（JDTの準拠レベル）。空なら使用中のJDTが対応する最大値 | — |
| `external.library.folders` | 空 | 自分のコードを呼んでいる側の他チームjar（被参照スキャン用。ファイル／フォルダ、カンマ区切り）。フォルダはサブフォルダも含めて `*.jar` / `*.war` / `*.ear` を全部使う。FatJar（jar の中の jar）は中まで開く | `project.root` |
| `entry.packages` | 空 | 呼び出し階層の起点。空なら「呼び出し元が無いメソッド」を自動で起点にする（全体モード） | — |
| `exclude.packages` | 空 | 出力から除外する呼び出し先（書式は `entry.packages` と同じ）。既定の設定例は `java.**,javax.**` | — |
| `cache.enabled` | `true` | 解析結果のキャッシュを使い、変更の無いファイルの再解析を省く。変更されたファイルが宣言する型を参照しているファイルは、自身が変わっていなくても再解析する | — |
| `cache.folder` | 空 | キャッシュの置き場所の親。空ならツール自身のプロジェクトフォルダの `.cache/`。指定してもその下にプロジェクト別のフォルダ `<名前>_<ハッシュ>` を切る | 設定ファイル |
| `max.depth` | `50` | 呼び出し階層の深さ上限（0以下で無制限。ただし再帰の実効上限 512） | — |
| `max.rows` | `5000000` | 出力行数の上限（0以下で無制限）。達したら打ち切って警告 | — |
| `dataflow.enabled` | `true` | ファクトリの戻り値・引数・コンストラクタ注入から具象クラスを特定する解析と、リフレクション（`Class.forName` / `getMethod` / `Method.invoke` / `newInstance`）の解決を使う | — |
| `dataflow.max.depth` | `5` | 経路に依存する探索（引数で渡ってきたクラス名・リテラルを辿る）の段数。ファクトリの委譲は上限なく畳む | — |
| `output.encoding` | `UTF-8-BOM` | 出力CSVの文字コード。`MS932` も可。変換できない文字は `?` に置換（例外にしない） | — |
| `output.folder` | `.`（設定ファイルと同じフォルダ） | 出力先の親フォルダ。この下に実行ごとの `<解析開始日時>_<プロジェクト名>/` を作る。CSV のファイル名は `call-hierarchy.csv` / `methods.csv` に固定 | 設定ファイル |

旧項目 `output.csv` / `methods.csv` / `cache.folders` が残っていれば、新しい書き方を示す `IllegalArgumentException` で止める（黙って無視すると出力やキャッシュが別の場所にできて気づきにくい）。
| `resolver.candidate.providers` | 空 | 拡張クラスのFQN（5.3参照） | — |
| `plugin.folders` | 空 | 拡張クラスの置き場所（設定ファイルのフォルダ起点、カンマ区切り）。`.java` / `.class` / `.jar` | — |

`entry.packages` / `exclude.packages` のパターン書式:

```
jp.co.xxx.action.*                  そのパッケージ直下のクラス全部（パッケージ名の完全一致）
jp.co.xxx.action.**                 そのパッケージ配下（サブパッケージ含む）全部
jp.co.xxx.action.UserAction         クラス指定（内部クラスは Outer.Inner）
jp.co.xxx.action.UserAction#execute メソッド指定
```

## 4. 出力: 2つのCSV

どちらも既定は **BOM付きUTF-8、カンマ区切り**（Excelでダブルクリックして開ける）。
値にカンマ・タブ・ダブルクォート・改行を含む場合はダブルクォートで囲む
（`"` は `""` に）。

### 4.1 `call-hierarchy.csv` — 呼び出し階層

```csv
caller,callee,resolved-by,level,root,call-hierarchy
at jp.co.example.action.OrderAction.execute(OrderAction.java:50),OrderService.findOrder,RESOLVED:NO_OVERRIDE,1,OrderAction.execute,OrderService.findOrder
at jp.co.example.service.OrderService.findOrder(OrderService.java:25),OrderDaoImpl.selectById,RESOLVED:SPRING_DI,2,OrderAction.execute,OrderService.findOrder,OrderDaoImpl.selectById
```

| 列 | 内容 | この形にする目的 |
|---|---|---|
| `caller` | 呼び出し元。`at バイナリ名.メソッド名(ファイル名:行)` の**Javaスタックトレース形式**。行番号は**呼び出し箇所**の行 | Eclipseの「Javaスタック・トレース・コンソール」に貼ると `(ファイル:行)` がリンクになりソースへ飛べる。内部クラスは `Outer$Inner`、コンストラクタは `<init>` で書く（コンソールが解釈する形式に合わせる） |
| `callee` | 呼び出し先。**クラス単純名.メソッド名**（引数は付けない）。内部クラスは `Outer.Inner`、コンストラクタはクラス名 | Excelのフィルタで呼び出し先を選ぶための短い表記。引数を付けないのでオーバーロードは同じ表記にまとまる。行番号は混ぜない（フィルタの選択肢が散らばる） |
| `resolved-by` | 解決方法。`接頭辞 + 段のラベル` で、**全ての行に必ず入れる**（下表） | 「どう特定したか・なぜ絞れなかったか」をフィルタできるようにする。注記は可変長列の末尾にあるためフィルタに使えない。`caller` → `callee` の1本の辺の性質なので `callee` の隣に置く |
| `level` | 起点からの深さ。起点が `0`、その呼び出し先が `1`。`call-hierarchy` に並ぶノード数と必ず一致させる | 深さで絞り込める。可変長列がどこで終わるか（注記がどこから始まるか）も列の数から分かる。`root` / `call-hierarchy` と同じ「木のどこにあるか」の列なので3つ並べる |
| `root` | 起点メソッド。`クラス単純名.メソッド名` | フィルタ用の短い表記 |
| `call-hierarchy` | 起点の次のノードから現ノードまでを**1ノード1列**で展開（可変長・必ず最終列） | 階層をそのまま読む。ヘッダーとデータ行の列数は一致しなくてよい |

- 呼び出し1件につき1行。起点自身の行は出さない
- 引数型の略名（`methods.csv` の `method` 列で使う）は `java.lang.String`→`String`、
  `java.util.List`→`List`、内部クラス `fx.Outer.Inner`→`Inner`。ただし略した結果
  **別物が同じ表記になる組だけ**完全修飾に戻す（`save(java.util.List)` と
  `save(other.List)`）。判定は全メソッドを一度走査して作る
- **コンストラクタの呼び出し自体は行にしない**（`new` したことより「その中で何を呼ぶか」が
  知りたい）。経路には積むので、コンストラクタ内からの呼び出しは階層に
  `Sample.Sample` のような形でコンストラクタを含めて出す
- 注記が付く場合は `call-hierarchy` の**最後の要素**として追加する（独立した列にしない。
  可変長列の後ろに固定列を置くと階層が途中で切れる）
- **降りている行には注記を付けない**。全行に何か書くと、注記が付いた行を目で拾えなくなる

`resolved-by` の値（接頭辞が確度、後半が手法）:

| 値 | 条件 |
|---|---|
| `RESOLVED:{ラベル}` | 呼び出し先が1件に定まった。ラベルは解決の段のもの（`STATIC_BOUND:PRIVATE` / `NO_OVERRIDE` / `SINGLE_IMPL` / `LOCAL_NEW` / `CONTRACT` / `DATAFLOW_*` / `SPRING_DI*` / `CALLBACK` / `REFLECTION*` / `EXTERNAL_GUESS` / 拡張のラベル） |
| `UNEXPANDED:{ラベル}` | 1件に絞れなかった（`CHA` / `LOCAL_NEW_MULTI` / `REFLECTION` / `NO_IMPL` / `GENERATED_IMPL:{名}` / `CALLBACK`（契約で呼び戻すメソッド参照の候補を並べた）等、候補をどう集めたかのラベル） |
| `UNEXPANDED:LAMBDA` | 候補は1件だが、ラムダ／メソッド参照も同じインターフェースを実装しており未特定。ラベルをそのまま出すと確定に見えるのでこう言い換える |
| `UNRESOLVED:{理由コード}` | 型解決に失敗した行（`BINDING_FAILED` / `OUTSIDE_METHOD`）。`level` は `1` |
| `EXTERNAL_USAGE:{照合の種類}` | 被参照スキャンの行（`EXACT` / `INHERITED` / `IMPLICIT_CTOR`）。`level` は `1` |

判定順は下の注記の後半グループと同じにする。別々に判定すると、同じ行の列と注記が食い違う。

注記の判定順（前半グループは先に当たったもの1つ、後半グループは1つ、両方あれば ` / ` で連結）:

| 順 | 条件 | 出力 |
|---|---|---|
| 前半1 | この経路上に既に現れたメソッドへ戻る | `[UNEXPANDED:CYCLE] returns to a method already on this path` |
| 前半2 | import推定（`EXTERNAL_GUESS`） | `[EXTERNAL] type guessed from an import (unverified)` |
| 前半3 | 呼び出し先の宣言ファイルが無い | `[EXTERNAL] no source to follow` |
| 前半4 | 次の深さが `max.depth` に達する | `[UNEXPANDED:DEPTH] depth limit (N) reached` |
| 後半1 | 候補が複数で、ラベルが `REFLECTION`（`getMethod` の引数型が揃わず名前で照合） | `[UNEXPANDED:REFLECTION] N candidates: matched by name because argument types are unknown` |
| 後半2 | 候補が複数（上記以外） | `[UNEXPANDED:CHA] N candidates: {reason}`。行にしない候補があれば数を後ろに足す（上限で切った `(only the first N are written as rows)`、除外した `(K excluded by exclude.packages and not written as rows)`） |
| 後半3 | 候補は1件だが、ラムダ／メソッド参照も実装している | `[UNEXPANDED:LAMBDA] implemented by a lambda/method reference (which one runs is undetermined)` |
| 後半4 | 本体を持つ実装が皆無（`NO_IMPL`） | `[UNEXPANDED:NO_IMPL] no implementation with a body in the source` |
| 追加 | 呼び出し先が契約表（`Thread#start() -> c* : run()` 等）に載っていて、渡した値の具象型が分かる | 呼び出し先の行の次に、呼び戻される側を `[RESOLVED:CALLBACK] 契約: …` で1行足して降りる（jar の中は読まない。docs/callback-contracts.md） |
| 追加 | 同上だが、渡した値が上書きされうるメソッドへのメソッド参照で、動く実装を1つに決められない（参照先の宣言がソースにあるときだけ） | 上書き候補を1件ずつ `[UNEXPANDED:CHA] N candidates: method reference to an overridable method 契約: …`（`resolved-by` は `UNEXPANDED:CALLBACK`）で足し、その先へは降りない。参照先の宣言が jar の中（`Runnable::run`）なら全実装になるので足さない |

1件に確定した呼び出しの注記は付けない（解決方法は `resolved-by` 列に出る）。
注記に残る `[RESOLVED:*]` は、繋いだ契約という列に無い情報を持つ `[RESOLVED:CALLBACK] 契約: …` だけ。

注記は先頭に大文字のタグを置き、日本語の説明をその後ろに続ける。
`[UNEXPANDED:*]` は「ここから先へ降りなかった」ことを表し、タグだけで辿り切れなかった箇所を
grep で一括で拾えるようにする。`[EXTERNAL]`（呼び出し先が自プロジェクトの外）は打ち切りでは
あるが性質が違うので `UNEXPANDED` の配下には入れない。`[UNEXPANDED:NO_IMPL]`（読めた上で実装が
無い＝設定漏れかデッドコードの疑い）と `[EXTERNAL]`（ソースが読めないだけ）は別物なので
言い分ける。

`{理由}` はレシーバの由来: `return value (factory method etc.)` /
`parameter (passed in from outside the method)` / `field` / `local variable` /
`own class (this)` / `type name (unbound method reference)` / `receiver unknown`。
理由を由来で出すのは、**次に調べる場所が由来ごとに違う**から（戻り値ならファクトリの
`return`、引数なら呼び出し元、フィールドなら代入箇所とDI設定）。注記は失敗の報告ではなく
次の調査手順として書く。

さらに、同じファイルに性質の違う2種類の行を追記する。`root` 列で区別できる。

- **型解決に失敗した呼び出し**: `caller` はスタックトレース形式、`callee` はソースに
  書かれたメソッド名、`root` = `(unresolved)`、階層列にメソッド名、末尾に理由
  `type resolution failed (missing classpath / dynamic call / etc.)`。**静かに消さないための行**
- **外部jarからの被参照**（`external.library.folders` 指定時）: `caller` = 参照している側の
  クラス名、`callee` = 自分のメソッド（callee列と同じ表記）、`root` = jar名（FatJar の中の jar なら
  `外側.jar!/BOOT-INF/lib/中.jar` のように jar URL と同じ `!/` 区切りで場所まで）、階層列に短縮表記、
  末尾に `external-ref:EXACT`（そのクラスで宣言されているメソッド。合成した暗黙のデフォルトコンストラクタを
  含む）/ `external-ref:INHERITED`（親から継承したメソッド。宣言している**最も近い親**のメソッドとして出す）/
  `external-ref:IMPLICIT_CTOR`（引数なしコンストラクタへの参照で、ソース上に一致する宣言が無いもの。
  版違いの可能性が高いが生成箇所として有用なので残す）

**行順は環境（OS・ファイルシステム・キャッシュの状態）に依存させない。**

| 並び | 何順か |
|---|---|
| 起点 | ソースフォルダの指定順 → 型FQN順 → 宣言行順 → ファイルの中の宣言の順番 → メソッドキーの文字列順 |
| 起点の呼び出し先 | ソース上の呼び出し順（深さ優先） |
| 複数候補の行 | 宣言型自身 → 下位型（直接の下位型はFQN順、そこから深さ優先） |
| `(unresolved)` の行 | ソースフォルダの指定順 → ファイルの相対パス順 → ファイル内の出現順 |
| 被参照の行 | jar のパス順 → jar の中のクラスの順 |
| `methods.csv` | ソースフォルダの指定順 → ファイルの相対パス順 → 宣言行順 → ファイルの中の宣言の順番 → メソッドキーの文字列順 |

ファイルの並びの鍵は `/` 区切りの相対パス文字列の `String#compareTo`（`Path#compareTo` は
Windows が大文字小文字を無視するなど OS で挙動が違う）。

### 4.2 `methods.csv` — ソース上の全メソッドと呼び出し状況

```csv
method,declaringType,typeKind,file,line,hasBody,inDegree,outDegree,role,reachable,unresolvedCalls,unresolvedCause
Service.exec(),fx.Service,C,src/fx/Service.java,10,1,1,2,NORMAL,1,1,フィールド変数
```

| 列 | 内容 |
|---|---|
| `method` | `クラス単純名.メソッド名(引数型略名)`（オーバーロードを見分ける。略名が衝突する組だけ完全修飾に戻す。4.1 の引数型略名の規則） |
| `declaringType` / `typeKind` | 完全修飾クラス名 / `I`=インターフェース, `A`=抽象クラス, `C`=具象 |
| `file` / `line` | 宣言ファイル（プロジェクトルート相対、`/` 区切り）と宣言行 |
| `hasBody` | 本体を持つか（IFの抽象メソッドとdefaultメソッドの区別） |
| `inDegree` | **具象クラスに解決した後の**被呼び出し数 |
| `outDegree` | 呼び出し数 |
| `role` | `FRAMEWORK_ENTRY`（契約でフレームワークが呼ぶと分かる入口。in に関係なく優先）/ `ISOLATED`（in=0かつout=0）/ `ENTRY_CANDIDATE`（in=0）/ `LEAF`（out=0）/ `NORMAL` |
| `reachable` | 起点集合から解決後のエッジで到達できるか |
| `unresolvedCalls` / `unresolvedCause` | このメソッド内で具象クラスを1つに絞れなかった呼び出しの件数と理由（`;` 区切りで重複排除。`[UNEXPANDED:NO_IMPL] no implementation with a body in the source` / `[UNEXPANDED:LAMBDA] implemented by a lambda/method reference` / レシーバ由来） |

`unresolvedCause` のタグは `call-hierarchy.csv` の注記と同じものを使う。同じ「絞れなかった」を
一覧と階層で別の名前で書くと、片方で見つけた呼び出しをもう片方で追えなくなる。
レシーバ由来は `[UNEXPANDED:CHA] {reason}` の形にする。

出すのは「他から呼び出せる定義」。ソースの無いメソッド（jar内）、`<init>`、合成した `<clinit>`、ラムダの合成メソッド（`lambda$…`）、匿名クラス（`Outer$1`）のメソッドは出さない（匿名クラスはその場で親の定義を上書きした処理内容で、呼び出し階層で読む）。内部クラス・static なネストクラス・ローカルクラス（`Outer$1Local`）は名前を持つ定義なので出す。
行順はソースの並び（ソースフォルダの指定順 → ファイルの相対パス順 → 宣言行順 → 同一行はファイルの中の宣言の順番
（そのファイルの何番目の宣言か）→ メソッドキーの文字列順）。
メソッドIDの順（キャッシュ上の出現順）で出すと、差分更新で解析し直したファイルが先頭へ移り、
実行のたびに並びが変わる。**同じ行に並ぶ宣言（1 行に書いたメソッド、同じ行のラムダ）の前後を ID で決めることも
しない**（ID はキャッシュのブロックの並びと、どの行が先に指したかで変わるので、全件解析と差分更新とで入れ替わる）。

## 5. 振る舞いの要点

### 5.1 3フェーズ構成

```
フェーズ1  ソース解析   .java（1ファイルずつ）→ キャッシュファイル（TSV。1 ファイル）
フェーズ2  グラフ構築   キャッシュ → メソッドをintのIDに内部化したCSR形式の呼び出しグラフ
フェーズ3  出力         methods.csv を書き、起点ごとに深さ優先で辿りながら call-hierarchy.csv を1行ずつ書く
```

**キャッシュは 1 ファイル**（2.14）。ソースファイル 1 つにつき 1 ブロックで、構造（宣言・呼び出し・型階層）と
値（値グラフ・戻り値・代入・条件）を同じブロックに持つ。構造と値を別のファイルに分けない（値も具象クラスの解決と
条件分岐の打ち切りを通じて出力に効くので、分けても片方だけでは出力が作れず、2 つを対に保つ仕組みだけが増える）。

差分判定は「パスとファイルサイズと内容のハッシュが一致」。**更新時刻は記録も参照もしない**
（中身と関係なく変わるので、当てにすると「中身が同じなのに解析し直す」と「中身が違うのに
再利用する」の両方が起きる。サイズを先に見るのは、違えば中身も違うと分かりファイルを
読まずに済むため）。キャッシュの1行目に形式バージョンと準拠レベル（と文字コード・実行 JDK・JDT の版）を書き、
不一致なら全体を破棄する。更新は旧キャッシュを先頭から読むストリーミングマージで、全件保持はしない。

**依存先の変更でも再解析する。** そのファイル自身が変わっていなくても、そのファイルの
バインディング解決が参照した型（呼び出し先・フィールドの所有型・親型・引数型・import の型）を
宣言するファイルが変わっていれば解析し直す。オーバーロードの追加やフィールドの改名で、
呼んでいる側の解決結果が変わるため。依存は1段で足りる（Aを解析し直してもAが宣言する型は
変わらないので、Aに依存するファイルへは波及しない）。ログには
`新規解析=N（うち依存先の変更による再解析=M）` と出す。

**依存 jar の変更も検知する。** キャッシュに解析時の依存 jar（パス・中身の指紋・含まれる
パッケージ）と、ファイルごとに JDT が報告したエラー数を残す。次回の実行で jar が追加・
差し替え・削除されていれば、その jar のパッケージの型を参照しているファイルと、前回型解決に
失敗していたファイルだけを解析し直す。実行する JDK が変わったときはキャッシュ全体を作り直す。
ログには `[cache] 依存jarの変更を検知: 追加=A 変更=B 削除=C（影響するパッケージ N 件）` と
`新規解析=N（うち依存先の変更による再解析=M、依存jarの変更による再解析=K）` を出す。

パースは 100 ファイルずつまとめて行う（1ファイルずつでは規模に対して超線形に遅くなる）。

**キャッシュには「ASTから分かった事実」だけを入れ、判断は読む側で行う。** 事実とは
宣言と修飾子、呼び出し箇所、フィールドへの代入、値の出所など、設定・出力形式・解決アルゴリズムに
依存しない情報。静的束縛かどうか、コンストラクタ注入と言い切れるか、import推定を呼び出し先として
採用するか、といった判断はキャッシュを読む側で行う。出力や解決の方針を変えてもキャッシュを
作り直さずに済ませるため。キャッシュの版は、書き手の変更で事実が変わりうるなら**迷わず上げる**（2.14）。

### 5.2 抽出する呼び出し

メソッド呼び出し、`super.m()`、`new`、`this(...)`/`super(...)`、**書かれていない暗黙の `super()`**、
enum定数の生成、メソッド参照4種（`obj::m` / `Type::m` / `super::m` / `Type::new`）。
ラムダ本体の呼び出しは、ラムダごとの合成メソッド（`lambda$…`）に帰属させる（第2部 2.9）。
フィールド初期化子・初期化ブロックは `static` なら `<clinit>`、インスタンスなら
「`this(...)` 委譲していない全コンストラクタ」に帰属させる（第2部 2.4）。

### 5.3 具象クラスの解決

段階的に判定し、先に確定した段で打ち切る。

| 段 | ラベル | 判定 |
|---|---|---|
| — | `REFLECTION` / `REFLECTION_INIT` | 呼び出し先が `Method.invoke` / `Class.newInstance` / `Constructor.newInstance` / `Class.forName` のとき、出所から実際に動くメソッドへ解決（下記）。段0より先に判定する（jar内のAPIなので静的束縛に見えるが、実際に動くのは名前で指定されたメソッド） |
| — | `EXTERNAL_GUESS` | import推定。型階層情報が無いので常に単一 |
| 0 | `STATIC_BOUND:{PRIVATE,STATIC,FINAL_METHOD,FINAL_CLASS,CTOR,SUPER}` | 仮想ディスパッチされない呼び出し |
| 1 | `NO_OVERRIDE` / `SINGLE_IMPL` / `NO_IMPL` | 宣言型自身（本体があれば）＋推移的サブタイプの同シグネチャ宣言を候補にし、1件なら確定。皆無なら `NO_IMPL` |
| 2 | `LOCAL_NEW` / `LOCAL_NEW_MULTI` | 同一メソッド内でレシーバ変数に代入された `new` の型（フロー非依存） |
| 3 | 拡張のラベル | `TypeCandidateProvider` が返した候補 |
| 4 | `DATAFLOW_NEW` / `DATAFLOW_FACTORY` | レシーバの出所が `new` された型、またはファクトリの戻り値（経路に依存しないのでメモ化できる） |
| — | `DATAFLOW_PARAM` / `DATAFLOW_FIELD` | 経路上の実引数／コンストラクタ注入フィールドから特定（経路依存。探索中に判定し、CHAで候補が複数のときだけ試す） |
| 5 | `CHA` | 候補が複数のまま |

**リフレクション**は `Class.forName` / `X.class` / `obj.getClass()` → `getMethod` /
`getDeclaredMethod` → `Method.invoke`、および `getConstructor` / `getDeclaredConstructor` →
`newInstance` の連鎖を、キャッシュに残した出所（レシーバの連鎖と実引数のリテラル）から辿る。

| 書き方 | 解決 |
|---|---|
| `Class.forName("a.B").getMethod("run", long.class).invoke(obj, 1L)` | `a.B.run(long)` |
| `B.class.getMethod("run")`、`obj.getClass().getMethod("run")`（obj の具象型が分かるとき） | `B.run()` |
| クラス名・メソッド名が `static final` 定数、または呼び出し元からリテラルで渡された引数 | 同上（経路ごとに解決） |
| `getMethod("run", types)` のように引数型が変数 | 同名で本体を持つメソッドを候補として列挙（未展開） |
| `invoke(obj, ...)` の第1引数の具象型が分かり、`getMethod` の受け手の型のサブタイプ | その型の実装を優先 |
| `Class.forName("a.B")` | `a.B` の `<clinit>`（あれば）。ラベル `REFLECTION_INIT` |
| `Class.forName("a.B").getDeclaredConstructor().newInstance()` | `a.B` のコンストラクタ。生成された型（`T:a.B`）は以降の呼び出しでも使われる |

解決できないもの: 設定ファイル・DB・アノテーションから来る名前、`Method` や `Class` を
フィールドや別メソッドの引数で受け渡す形。これらは `Method.invoke` のまま「ソースなし」に
なる（既定の `exclude.packages=java.**` では行にならない）。経路上の引数に依存する分
（名前やクラスが引数で渡ってくる形）は探索中にもう一度試す。

拡張ポイントは `TypeCandidateProvider` **1 つだけ**（宣言型・シグネチャ・証拠から具象型FQNの配列と
ラベルを返す。`appliesToStaticBound()` が true なら段0の呼び出しにも尋ねる）。設定ファイルの内容と
置き場所を `init()` で渡す。読み込み失敗は警告して続行。
実装クラスは `plugin.folders` に置く。`.java` があれば実行時にコンパイルし（`ToolProvider` の
javac にツール自身のクラスパスを渡す）、`.class` / `.jar` と合わせて URLClassLoader（親は本体の
クラスローダ）で読む。コンパイル失敗も警告して続行。
対応表だけで済む用途のために `jche.builtin.TypeMappingProvider`（properties の対応表を引く。
左辺は「宣言型」「宣言型#メソッド」「証拠の種別@値」の 3 通り。`:` は properties の区切り文字なので使わない）を同梱する。

拡張が動くのはグラフ構築時だけで、**キャッシュには何も書かない**。よって拡張やその設定を
キャッシュのヘッダ行に入れる必要はなく、拡張を足しても外してもキャッシュはそのまま再利用できる。
AST 走査中に利用者のコードを差し込む口は設けない（キャッシュの鍵が増え、読み口が
契約表・証拠・ひな形の 3 つとずれるため）。読み取る材料を増やすのはツール本体の仕事で、
「ファクトリの実引数の何をキーとして読むか」は 1 か所（後述の `FactoryCalls`）にまとめる。

渡す証拠（`Hint`）は `kind` と `value` の 2 つ組。レシーバがファクトリメソッドの戻り値なら、
値グラフから次を作って渡す。`FACTORY`＝ファクトリの `型FQN#メソッド名`（実装が親クラスにあるときは
ソースに書いた型と宣言元の両方）、`FACTORY_KEY`＝渡された文字列（コンパイル時定数は値まで評価）、
`FACTORY_CONST`＝渡された列挙定数の `型FQN.定数名`、`FACTORY_CLASS`＝渡された `Class` リテラルの型FQN。
同一メソッド内で `new` された型（`NEW`。書き手がファイルの中で結びつけて呼び出しの行に持つ。2.14）も同じリストに並べる。

### 5.4 探索

- 起点: `entry.packages` 一致でソース宣言のあるメソッド。未指定なら
  **解決後の入次数0**かつソースに本体があるメソッド全部。並びはどちらも (1) ソースフォルダの
  指定順 (2) 型FQN順 (3) 宣言行順 (4) ファイルの中の宣言の順番 (5) メソッドキーの文字列順
  （`entry.packages` に書いた順ではない。メソッドIDの順も使わない）
- 型階層の子型・親型リストはFQN順に整列する。CHA候補の並び（＝出力の行順、上限20件で
  打ち切るときにどの候補を載せるか）がキャッシュ上のブロック順に依存すると、
  差分更新後の出力が cold 実行と一致しなくなる
- 循環検出は経路単位（`[UNEXPANDED:CYCLE] returns to a method already on this path` を1行出して降りない）。グローバル訪問済み集合は持たない
- `exclude.packages` 一致ノードは行にしないが、その先は親に繋ぎ直して辿る。
  繋ぎ直すときはデータフローの環境（引数・コンストラクタ実引数）も除外ノードのものに差し替える。
  読み飛ばし中の除外メソッドも循環判定の祖先に含め、読み飛ばしの入れ子数にも深さ上限（512）を
  掛ける（除外パッケージ内の相互再帰でスタックオーバーフローにならないため）
- CHA候補が複数なら候補を上限20件まで列挙し、先へは降りない
- `max.depth` 到達で打ち切り、`max.rows` 到達で警告して全体を打ち切る
- ツリーを組み立てない。ヒープに載るのは現在の経路（深さぶんの配列）だけ

### 5.5 型解決できなかった呼び出し

1. レシーバが単純名で、そのファイルの単一型インポートと一致すればそのFQNを採用（未検証）
2. それも無理なら「型解決失敗」として記録し、件数をログに出し、`(unresolved)` 行を出す

### 5.6 被参照スキャン

外部jarのclassファイルを自前で読み、各メソッドの命令列（Code 属性）にある `invoke*` 命令の
参照先（Methodref / InterfaceMethodref）のうち自分の型を owner とするものを、呼び出し元
メソッド名と LineNumberTable の行番号付きで列挙する（2.12）。参照している側のclass自体が自プロジェクトの
型なら読み飛ばして件数をログに出す。一致するメソッドが無い参照は「相手が古い版に対して
ビルドされている可能性」として件数をログに出す（非 static な内部クラスのコンストラクタは
バイトコード上は外側インスタンスが引数に付くため一致せず、この件数に入る）。

FatJar（Spring Boot の `BOOT-INF/lib/*.jar`、war の `WEB-INF/lib/*.jar`、ear の中の war や jar）は
中の jar を取り出さずにストリームで開いて何段でも辿る（安全策として 8 段まで）。同じ jar が
複数の FatJar に入っていれば、それぞれの FatJar の行として別々に出す。FatJar の中の
自プロジェクト jar も読み飛ばす。ログに `jar=N jar内のjar=M` と出す。
`library.folders`（JDT に渡す依存 jar）の FatJar は対象外（JDT は中の jar を見ない）。

### 5.7 実行ログ

行頭に `[分:秒.ミリ秒s]`。設定の解決結果（実際に効いた準拠レベルを含む）、展開後の依存jar
一覧、フェーズごとの件数、各フェーズ終了時のヒープ使用量。進捗表示はフェーズ1だけ。
標準出力の文字コードは固定しない。

### 5.8 依存 jar の自動取得（`library.folders` が空のとき）

ビルドツール（`mvn` / `gradle`）は実行せず、ネットワークにも出ない。各ソースフォルダから `project.root` まで
上位へ辿って最初に見つかった `pom.xml` / `build.gradle(.kts)` / `settings.gradle(.kts)` のあるフォルダのビルドファイルを
読み（マルチモジュールならソースフォルダを持つモジュールごと）、依存の jar と POM をローカルリポジトリから探す。
推移的な依存はローカルにある POM を辿って集める（親 POM、`dependencyManagement`、BOM の import、`${...}`、exclusions、
推移的な test / provided / optional の除外。版の衝突は Maven なら近い方、Gradle なら高い方が勝つ）。
兄弟モジュール（Maven のリアクタ、Gradle の `project(':x')`）は `target/classes` / `build/classes` / Buildship の
`bin/main` と、そのビルドファイルの依存で解決する（`mvn install` 不要。未ビルドなら注記を出す）。
Gradle は宣言的な書き方（文字列の座標、map 形式、変数、版カタログ `libs.x.y`、`platform()`、`project()`、`files()`）だけ
読み、`gradle.lockfile` があればそれを使う。読めない宣言はログに出す。
集めた jar とクラスフォルダをそのまま JDT に渡す（jar はローカルリポジトリに置かれたまま）。無い jar は警告に座標と
要求元の経路を出し、無いまま解析を続ける。集めた一覧（パス・座標・要求元の連鎖）を実行ログ（`run.log`）に残す。

## 6. 設計上の優先順位

1. **漏れないこと > 正確であること**。**絞れないことより、誤って1つに絞ることの方が有害**
2. **落ちないこと > 完全であること**。1ファイルの失敗で全体を止めない
3. **メモリが破綻しないこと**
4. **出力は決定的**であること

## 7. やらないこと

バイトコード解析（定数プール読みを除く）、呼び出し元の候補を集めて遡る推論、
DI設定・setter注入フィールドの解決、フレームワークのディスパッチ解決、実行時情報、GUI。

---

# 第2部 JDT/AST解析のはまりどころ（必ず回避すること）

以下は、参照実装の開発中に**実際に踏んだ**ものです。どれも例外にならず、
出力が静かに欠ける・誤って絞られる、という形で現れます。

## 2.1 ASTParser の初期化

```java
ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
parser.setKind(ASTParser.K_COMPILATION_UNIT);
parser.setCompilerOptions(options);                    // 2.2
parser.setResolveBindings(true);                       // 必須。型解決の本体
parser.setBindingsRecovery(true);                      // 一部解決できなくても諦めない
parser.setEnvironment(classpath, sourcepath, encodings, true);  // 2.3
parser.setUnitName("jp/co/xxx/Foo.java");              // 必須。ソースフォルダからの相対パス
parser.setSource(sourceChars);
CompilationUnit cu = (CompilationUnit) parser.createAST(null);
```

- **`setUnitName()` を省略するとバインディング解決が静かに失敗する**
- ASTは1ファイルごとに生成して捨てる。ヒープに残さない

## 2.2 コンパイラ準拠レベル（最頻出の罠）

`JavaCore.getOptions()` の既定は古いレベル。そのままでは generics・`<>`・ラムダ・enum・
default method が構文エラーになり、**大半の呼び出しが型解決に失敗する**。

```java
Map<String,String> options = JavaCore.getOptions();
JavaCore.setComplianceOptions(requested.isEmpty() ? JavaCore.latestSupportedJavaVersion() : requested, options);
String effective = options.get(JavaCore.COMPILER_SOURCE);   // ← 実際に効いた値
```

- 既定は**使用中のJDTが対応する最大値**。新しい言語機能の解釈を許可するだけなので、
  対象が古いJavaでも副作用はない
- **設定値は必ず読み戻す**。JDTは対応範囲外の値を例外にせず黙って丸める（`1.4` → `1.8`）。
  丸められたらログに出す。範囲外（`99` 等）は `JavaCore.isSupportedJavaVersion()` で
  起動時にエラーにする
- **準拠レベルをキャッシュのキーに含める**。同じソースでも解析結果が変わるため、
  ソースの中身だけ見ていると設定変更後に古い結果を再利用する

## 2.3 クラスパスとソースパス

- `setEnvironment(..., includeRunningVMBootclasspath=true)` でJDK標準クラスは実行中の
  JVMから解決される。**実行JDKは暗黙の依存jar**。実測（JDT 3.46.0、JDK 17 / 21 / 25）では、
  `var first = list.reversed().get(0); first.findById(1L);` のように JDK 21 以降の API の
  戻り値を `var` やメソッドチェーンで受けている箇所が、JDK 17 では `first` の型が分からず
  自プロジェクトのメソッド呼び出しごと「型解決失敗」になった（宣言型を書いた
  `Dao declared = ...` は影響を受けない）。実行JDKは解析対象が使うAPIの版以上にし、
  版はキャッシュのヘッダに入れて変われば全件作り直す
- パースは `ASTParser#createASTs` で 100 ファイルずつまとめて行う（`FileASTRequestor` で
  1ファイルずつ受け取る）。1ファイルずつ `createAST` すると、ファイル数に対して超線形に遅くなる
- `classpath` には**jarを1つずつ**渡す。フォルダを渡しても展開されない。`library.folders` の
  フォルダは直下の `*.jar` をファイル名順に列挙して展開し、**展開後の一覧をログに出す**
- Eclipse の `.classpath` は `kind="src"` / `kind="lib"` だけ読む。**`kind="con"`
  （Gradle/Mavenのコンテナ）は解決できない**。そういうプロジェクトは `library.folders` を空にして
  5.8 の自動取得に任せるか、`library.folders` に集めたフォルダを指定する。
- クラスパスには jar だけでなく**クラスフォルダ**（`target/classes` など）も渡せる。ソースパスの型が
  クラスパスより先に見つかるので、解析対象のソースの古い class がクラスフォルダにあってもソースが勝つ。
  他プロジェクト参照（`/` 始まりの `src`）は警告してスキップ。XML読み込みは DOCTYPE を禁止
- Eclipse の `plugins/*` をワイルドカードでクラスパスに入れると、無関係なjarのSPI登録で
  `ServiceConfigurationError` になる。必要なjarだけを集める

## 2.3a ビルドファイルの読み取りのはまりどころ（5.8）

- **親 POM は座標で照合する。** `relativePath`（既定 `../pom.xml`）にファイルがあっても、groupId / artifactId / version が
  一致しなければ親ではない（ローカルリポジトリの POM を探す）。`relativePath` を空にした親はディスク上を探さない。
  親の版が `${revision}` のときは子の `<properties>` で展開してから探す
- **管理の優先順位。** 自分の `dependencyManagement` → 親のもの → `scope=import` の BOM の順。BOM 自身も親と
  プロパティを持つので、BOM の実効 POM を作ってから取り込む（循環は「構築中」の印で止める）
- **直接の依存と推移的な依存で管理の効き方が違う。** 直接の依存は自分の版が優先で、無いときだけ管理の版。
  推移的な依存はルートの管理が版を上書きする（Maven 3）
- **推移的な依存では test / provided / optional を落とす。** 落とさないとテストライブラリが数十件混ざり、
  ローカルに無い jar の警告で埋まる
- **リアクタは `modules` から作る。** 実行ディレクトリから親の `relativePath` を辿ってディスク上の最上位の POM を
  見つけ、`modules` を再帰的に集める。依存先がリアクタにあれば jar ではなく `target/classes` と、その pom.xml の依存
- **Gradle のスクリプトは行単位で読む。** 先にブロックコメントと行コメントを除く（`//` は URL の `://` と区別する）。
  `buildscript { }` と `constraints { }` の中は依存ではない。サブプロジェクトにはルートの `allprojects { }` /
  `subprojects { }` の中の宣言も足す（ブロックは中括弧の対応で切り出し、文字列の中の括弧は数えない）
- **`${property("x")}` は文字列の中に引用符が入る。** 文字列リテラルを切り出す前にプロパティの値へ置き換えないと、
  内側の引用符で切り出しが止まる
- **版カタログの別名は区切りを同一視する。** `foo-bar` / `foo_bar` / `foo.bar` はどれも `libs.foo.bar`
- **ローカルリポジトリは 2 つの配置を両方試す。** Maven は `g/r/o/u/p/a/v/a-v.jar`、Gradle は
  `group/a/v/<ハッシュ>/a-v.jar`（グループはドット区切りのまま）。SNAPSHOT はタイムスタンプ付きの名前で保存されている

## 2.4 「今どのメソッドの中にいるか」の管理

呼び出し元の決定は、AST走査中の**スタック**で管理する。ここに4つの罠がある。

**(a) 匿名クラス・ローカルクラス**: その `MethodDeclaration` は囲みメソッドの内側にネストして
現れる。単一スロットで持つと内側を抜けた時点で囲みメソッドが失われ、
**匿名クラスより後ろの呼び出しが全部「メソッド外」に落ちる**。`visit` で push したら
`endVisit` で必ず pop する（JDTは `visit` が false を返しても `endVisit` を呼ぶ）。

**(b) フィールド初期化子・初期化ブロック**: `MethodDeclaration` ではないので、素朴に実装すると
「メソッド外」で捨てられる。コンパイラの畳み込みに合わせる。

| ソース上の位置 | 帰属先 |
|---|---|
| `static` フィールド初期化子 / `static {}` / enum定数の生成 | その型の `<clinit>` |
| インスタンスフィールド初期化子 / `{}` | **`this(...)` 委譲していない全コンストラクタ**（1対多） |

インスタンス側は該当する全コンストラクタに1本ずつエッジを張る（コンパイル後、実際に
それぞれから呼ばれるので近似ではない）。したがって「現在の呼び出し元」は**リスト**で持つ。
`<clinit>` の宣言はソースに無いので、その型で初めて必要になったときに1回だけ合成する
（常に合成するとノイズになる）。static かどうかは構文でなくバインディングで判定する
（インターフェースのフィールドは暗黙に static）。

**(c) 暗黙のデフォルトコンストラクタ**: 明示コンストラクタが無い型には `<init>()` が存在する。
型を訪問した時点で宣言を合成しないと、`new B()` の呼び出し先が「宣言の無いメソッド」になり
`[EXTERNAL] no source to follow` と誤表示される。record の暗黙の正準コンストラクタは
レコードコンポーネントを引数に取るので、`ITypeBinding.getDeclaredMethods()` の
コンストラクタを正として合成する。

**(d) ラムダ式**: `MethodDeclaration` ではないが、本体を持つ合成メソッドを作って
呼び出し元のスタックに積む。中の呼び出しはその合成メソッドに帰属する（2.9）。

**(e) `super(...)`**: `this(...)` と同じくコンストラクタ呼び出しの辺として記録する
（`SuperConstructorInvocation`）。これが無いと、サブクラスからしか生成されない親クラスの
コンストラクタが入次数0になる。
`this(...)` で委譲するコンストラクタを持つ型では、コンストラクタ引数由来のフィールド出所を
採用しない（安全側）。

**書かれていない暗黙の `super()` も同じ理由で辺にする。** JLS 8.8.7 のとおり、明示的コンストラクタ
呼び出しで始まらないコンストラクタの本体は暗黙に `super();` で始まり、コンストラクタを1つも
書いていない型（JLS 8.8.9）も同じ。ASTには現れないが実行される呼び出しなので、辺にしないと
「`super()` を書いていないサブクラス」からは親コンストラクタの中の処理が到達不能になる。
`super()` を書かないほうが普通なので、落とすと実害が大きい。
ただし親が `java.lang.Object` / `java.lang.Enum` / `java.lang.Record` のときは張らない
（コンパイラが与える親で、辿る先が無い）。匿名クラスの合成コンストラクタは**選ばれた親
コンストラクタと同じ引数**を取ってそのまま渡す（JLS 15.9.5.1）ので、引数なし固定にしない。
詳細は `docs/jls-conformance-qa.md` の Q8〜Q11。

**(f) `this(...)` 委譲の判定**: 本体の**先頭文**ではなく、トップレベルの文から最初の
`ConstructorInvocation` / `SuperConstructorInvocation` を探す。Java 25 で確定した柔軟な
コンストラクタ本体（JEP 513）により、`this(...)` の**前に文を書ける**ようになったため
（JLS 8.8.7）。先頭文だけを見ると委譲を取り違え、インスタンス初期化子を委譲側にも複製し、
D行の `delegating` も落として `FieldFacts` の安全弁を無効にしてしまう。

## 2.5 visit すべきASTノード

| ノード | 目的 | 落とすと |
|---|---|---|
| `TypeDeclaration` / `EnumDeclaration` / `RecordDeclaration` / `AnnotationTypeDeclaration` / `AnonymousClassDeclaration` | 型階層（種別 I/A/C と親型）と型コンテキスト | **enum/record はASTノードとして別物**。`TypeDeclaration` だけ見ていると、インターフェースを実装する enum が候補に入らず、他に実装が1つあると `SINGLE_IMPL` で**誤確定**する。enum のフィールド初期化子は「メソッド外」に落ちる |
| `EnumConstantDeclaration` | 定数の生成（`<clinit>` からのコンストラクタ呼び出し） | enum のコンストラクタが誰からも呼ばれていないように見える |
| `MethodDeclaration` | 宣言（`hasBody` を記録）と呼び出し元スタック | — |
| `FieldDeclaration` / `Initializer` | 2.4(b) | — |
| `MethodInvocation` / `SuperMethodInvocation` / `ClassInstanceCreation` / `ConstructorInvocation` | 呼び出し辺 | — |
| `ExpressionMethodReference` / `TypeMethodReference` / `SuperMethodReference` / `CreationReference` | メソッド参照を「囲みメソッドからの呼び出し」として記録 | `::` でしか参照されないメソッドが「呼ばれていない」ように見える。**`String[]::new` は配列生成で呼ぶメソッドが無いので辺にせず、型解決失敗にも数えない** |
| `LambdaExpression` | 関数型インターフェースのメソッドを「ラムダも実装している」と記録し、本体を持つ合成メソッドを作って積む（2.9） | — |
| `ReturnStatement` | 戻り値の出所（2.10） | — |
| `VariableDeclarationFragment` / `Assignment` | 段2の `new` 追跡。変数の同定は名前でなく `IVariableBinding.getKey()` | 同名変数がスコープ違いで誤解決 |
| `TypeLiteral` | `X.class` の出所（`K:`）。リフレクションの受け手・引数型 | `getMethod("run", long.class)` のシグネチャが決まらない |
| `SimpleName`（フィールドに解決されるもの） | フィールドの参照箇所（read / write / readwrite）。`a.b.c` の `c`、`this.x` の `x`、`super.x` の `x` はすべて SimpleName に行き着くので、ここだけ見れば重複なく拾える。宣言そのものと配列の `length` は除く | — |
| `ImportDeclaration` | 走査しない（`visit` で false）。import の型は依存（I行）として別に数える | import の中の名前を参照箇所と誤認する |

## 2.6 名前の正規化（静かに壊れる箇所）

| 用途 | 形式 | 例 |
|---|---|---|
| 内部ID・型階層の照合 | ソース上の正規名 | `jp.co.xxx.Outer.Inner` |
| `callee` 列 | 単純名.メソッド名 | `Outer.Inner.method` |
| `root` / `call-hierarchy` 列 | 単純名.メソッド名 | `Outer.Inner.method` |
| `caller` 列 | **バイナリ名** | `at jp.co.xxx.Outer$Inner.method(Foo.java:12)` |

- メソッドのキーは `typeFqn#name(消去済み引数型FQN,...)` の1本。ID化・CHA・出所・被参照の
  照合すべてに同じキーを使う。型は常に `getErasure()`、メソッドは `getMethodDeclaration()`
  を通す（実体化された型のままだと同じメソッドが別IDになる）
- **消去したキー1本ではオーバーライド関係を表現できない。** JLS 8.4.2 のオーバーライドは
  「同じシグネチャ**または**消去したシグネチャと同じ（サブシグネチャ）」なので、型引数を
  具体化した実装（`class UserRepo implements Repo<User>` の `save(User)`）は親
  （`Repo#save(java.lang.Object)`）とキーが一致しない。**上書きしているという事実**を
  別の行（O行）として残し、読み手が逆引きを作って候補引きで併せて見る。判定は
  `IMethodBinding.overrides`（JLS 8.4.8.1 の実装）に任せ、親型は型引数を**具体化したまま**辿る
  （先に消去すると置換が失われて判定できない）。シグネチャが自分と同じ上書きは書かない
  （キーの照合で引けるため）
- **匿名クラスは `getQualifiedName()` が空文字**。そこでスキップすると匿名クラスによる
  オーバーライドと中の呼び出しが丸ごと落ちる。`getQualifiedName()` → `getBinaryName()`
  （`Outer$1`）→ `getKey()`（空白を `_` に置換）の順にフォールバックする
- **デフォルトパッケージ**で「FQNからパッケージを除いた単純名」を `lastIndexOf('.')` で
  取ると、内部クラスの外側クラス名が消える（`Top.In` → `In`）。パッケージが空なら
  FQN全体をそのまま単純名にする
- コンストラクタは内部的に `<init>`。表示はクラス単純名（`Sample.Sample`）。ただし `caller`
  列だけは `<init>` のまま（スタックトレース・コンソールの形式）
- 引数略名の衝突判定（`methods.csv` の `method` 列）は「単純名ラベルが同じで、キーが違う」組を
  全メソッドから集める

## 2.7 静的束縛の判定軸

段0は「宣言型が具象クラスか」ではなく「仮想ディスパッチされるか」で判定する。
`private` / `static` / `final` メソッド、`final` クラスのメソッド、コンストラクタ、`super.m()` が
静的束縛。書き手（AST走査）はバインディングの `getMethodDeclaration()` の修飾子を語
（`public,static,finalclass,super` 等）として**事実のまま**キャッシュに残し、
静的束縛かどうかの判定は読み手（グラフ構築）が行う。判定順は
`<init>` → `super` → `private` → `static` → `final` → `finalclass` → 仮想。
理由（`PRIVATE` 等）をラベルに残し、後から監査できるようにする。

## 2.8 CHA（段1）と実装探索

- 候補数は「サブクラス数」ではなく「そのメソッドをオーバーライドしている宣言の数」
- 宣言型自身は本体を持つ場合だけ候補に入れる（IFの抽象メソッドは除外、default メソッドは
  含める）。D行の無いメソッド（jar内）は `hasBody` を true 扱いにして候補から落とさない
- 型階層は「親→子」に加えて「子→親」の向きも持つ。具象型が分かった後の実装探索は
  **その型から親へ幅優先で辿り、本体を持つ最初の宣言**を採る（`UserDao extends AbstractDao`
  で `select()` が親にしかない場合、型名だけで引くと見つからない）
- 実装探索の軸は**2つある**。「継承」（上記）と「型引数の置換」（2.6 の O行）で、
  どちらか一方だけでは落ちる。**1つの幅優先探索の各段で両方を見る**こと。
  段を分けて「キーの照合 → 駄目なら上書き」にすると、`class OrderStore extends AbstractStore<Order>`
  が `put` を具体化して上書きしている場合に**親の実装に先に当たって**しまい、
  「上書きは無い」と誤って結論する。同じ段では上書きを先に見る
- 候補引きの入口は**呼び出し先の分かり方で2つだけ**にする。キーが分かるなら
  `implementationOf(型FQN, 呼び出し先ID)`、シグネチャしか分からないなら
  `implementationOfSignature(型FQN, シグネチャ)`。どちらも同じ探索を呼び、上書きの引き方だけが違う。
  後者が要るのは、**呼び戻しの契約表とリフレクションは所有型を知らない**ため。
  契約は `java.lang.Thread#start() -> c* : run()` のようにシグネチャだけを名指しし、
  `run()` を宣言している `java.lang.Runnable` はどこにも現れない
- 入口をこれ以上増やさない。段1（CHA）・段2（`LOCAL_NEW`）・段3（契約表と拡張）・
  段4（dataflow）・段5（Spring DI）がそれぞれ別の関数を呼ぶ作りにすると、
  1か所だけ直したときに残りが静かに取りこぼす（`docs/inherited-impl-candidates-qa.md`、
  `docs/jls-conformance-qa.md` の Q7・Q21。実際に3度やった）
- 入次数は**解決後の候補**に対して数える。宣言型で数えるとIF経由でしか呼ばれない実装が
  すべて入次数0になり、真の入口と区別がつかない。リフレクションで解決した先（`<clinit>` や
  `invoke` の実体）にも入次数が付くので、`Class.forName("a.B")` があれば `a.B.<clinit>` は
  起点候補でなくなる
- 型階層の子型・親型リストはFQN順に整列してからCHA候補を作る（キャッシュ上の出現順に
  依存させない。差分更新後も cold 実行と同じ行順にするため）
- 型階層（H行）の親型には、直接の親に加えて**jar の型を経由して到達するソース上の親型**も
  書く。ソースの `interface Dao`、jar の `abstract class LibDao implements Dao`、ソースの
  `class LibBackedDao extends LibDao` という構成で、直接の親（`LibDao`）だけだと jar の型には
  H行が無いので `LibBackedDao → Dao` を辿れず、`LibBackedDao` が `Dao` の候補に入らない。
  ソース上の親型の先は、その型自身のH行が持つので辿らない

## 2.9 ラムダ／メソッド参照

- ラムダとメソッド参照が実装している関数型インターフェースのメソッドを「展開できない実装が
  ある」として記録する。書き手は箇所ごと（行・囲みメソッド・lambda / methodref / ctorref）の
  事実を残し、読み手は「ある／なし」だけを使う（件数を判定に使うとファイル単位の部分再利用で
  値がぶれる）。これが無いと、匿名クラスが1件あるだけで `SINGLE_IMPL` と判定し、
  **実際に動くラムダとは違う実装に決め打ち**する
- その記録は、SAM が上書きしている**親インターフェースの宣言の鍵でも**書く
  （`interface StringHandler extends Handler<String> { void handle(String v); }` のラムダは
  `StringHandler#handle(java.lang.String)` と `Handler#handle(java.lang.Object)` の両方）。
  親の型で受けた変数への呼び出しでも実行されるのはこのラムダであり、読み手は鍵の完全一致で
  引くため。シグネチャが同じ再宣言も含める。上書きの関係に無い2つの親から同じ抽象メソッドを
  継承した形（`interface Door extends Opener, Closer {}` で両方に `void act()`。JLS 9.8）も、
  ラムダは両方を実装するので両方の鍵で書く。JDT の `getFunctionalInterfaceMethod` は片方しか
  返さないので、ラムダの型の親インターフェースを辿り、SAM と上書き同等（`IMethodBinding.isSubsignature`）な
  抽象メソッドをすべて集める
- 候補の絞り込み自体は変えない。値として追えなかった呼び出しでは `[RESOLVED:SINGLE_IMPL]` と
  書く代わりに `[UNEXPANDED:LAMBDA] implemented by a lambda/method reference（…）` を出し、
  `unresolvedCalls` にも数える
- **ラムダ本体は合成メソッドにする**。名前は javac に似せた `lambda$囲みメソッド名$通し番号`、
  修飾子に `lambda` を付けた D 行を作り、本体の中の呼び出しはその合成メソッドに計上する。
  通し番号は型ごとに、javac 21 と同じく本体を読み終えた順（後行順。入れ子は内側が先）。
  static 初期化子・static フィールド（インターフェースのフィールドは書かなくても static）・
  enum 定数の引数の中は `lambda$static$N`（初期化は `<clinit>`）。static かどうかは書かれた修飾子
  ではなくバインディングで見る。番号の振り方は javac の版で変わる（JDK 25 の javac は囲みメソッド名ごと・
  外側が先）ので、番号の一致は保証しない。名前はファイル単位で
  **先に**配る（本体の先読みと本走査の2か所で決めると食い違うため）。
  式本体（`() -> new X()`）も `return 式;` と同じく R 行にする
- **囲みメソッドから合成メソッドへ「生成の辺」を必ず1本張る**。これが無いと、`forEach` のように
  `exclude.packages`（既定 `java.**`）で除外されるAPIに渡したラムダの本体が到達不能になり、
  出力から丸ごと消える（順序の正確さより取りこぼさないこと）
- ラムダ／メソッド参照は**値**としても追う。出所の種別 `Z`（実装しているメソッドのキー。
  ラムダなら合成メソッド、メソッド参照なら参照先そのもの）を持ち、ローカル変数・引数・
  フィールド・ローカルのコレクションの要素として呼び出し箇所まで流れてきたら
  `DATAFLOW_LAMBDA` で確定する
- メソッド参照の参照先は**コンパイル時宣言**（JLS 15.13.1）で、参照先が仮想メソッドなら実行時には
  レシーバの実行時クラスで仮想ディスパッチされる（JLS 15.13.3）。レシーバを束縛した形
  （`dao::describe`）は `Z` にレシーバの出所（`|r=`）を付け、読み手はその具象型での実装に繋ぐ。
  分からなければ上書き候補（1 件なら確定、複数なら CHA）。宣言のまま「確定」と書くと、
  本体の無い抽象メソッドが葉になる。ラムダの本体と静的束縛の参照先はそのまま確定。
  レシーバの出所は参照を書いたメソッドから見たものなので、経路の引数の環境は使わない
- `s.get().describe()` のように関数型インターフェースのメソッドの戻り値を受ける呼び出しは、
  レシーバがラムダ／メソッド参照と分かれば、その本体の R 行を戻り値の出所にする
- ラムダが捕捉した囲みメソッドの引数は、種別 `E`（captured）として持つ。`A`（引数）のまま
  持ち込むと合成メソッド自身の引数を誤って当てる。読み手は合成メソッドへ降りるとき、
  **今の段がそのラムダを生成したメソッドである場合だけ**（生成の辺を持つ）、その段の引数を
  捕捉した値として渡す。引数で渡した先（`runIt(Runnable r)` の `r.run()`）から降りる段では渡さない。
  捕捉した値はラムダを作った時点で決まる（JLS 15.27.2）ので、実行した側の引数を当てると
  別の値に誤って確定する
- 匿名クラスは実型（`fx.App$1`）として扱う。名前を持ち型階層に載るので、インターフェース経由の
  呼び出しから正しく辿れる

## 2.10 データフロー解析のはまりどころ

判断の軸は「絞れないことより、間違って絞ることの方が有害」。

**値（出所）の表現**: 式が「どこから来たか」を、1 つの式を 1 ノードとする**値グラフ**で持つ。
ノードは 1 文字の種別と値を持ち、`new` とメソッド呼び出しのノードは実引数のノード（位置ごと）・実引数の数・
レシーバのノード・ソースに書いたときのレシーバの型も持つ。参照はノード番号で行う。

| 種別と値（人が読むときの表記） | 意味 | 確定するタイミング |
|---|---|---|
| `T:fqn` | `new` された具象型 | その場 |
| `A:n` | 囲みメソッドの n 番目の引数 | 経路を降りて呼び出し元が決まったとき |
| `M:typeFqn#m(params)` | メソッドの戻り値 | そのメソッドの return を見たとき |
| `F:typeFqn#field` | フィールド | コンストラクタ注入の記録と経路上の生成箇所を見たとき |
| `L:値` | 文字列リテラル／コンパイル時定数（長さ・形の制限なし。SQL やログ文言もそのまま） | その場 |
| `V:値` | 条件分岐の判定に使うコンパイル時定数の値（真偽値・整数・文字・文字列・列挙定数の FQN） | その場 |
| `K:fqn` | クラスリテラル `X.class`（配列は `[]` 付き、プリミティブはそのまま） | その場 |
| `C:n` | `Class.forName(n番目の引数)` で名前指定された型 | その呼び出しの実引数を見たとき |
| `U` | 追跡できない | 決まらない |

人が読むときは、実引数・実引数の数・レシーバ・書かれた型を `|0=…;n=…;r=…;s=…` の形で並べ、入れ子は `{}` で囲んで書く
（参照実装の `CacheDump` の表記）。**入れ子の段数に上限は無い**（`invoke` ← `getMethod` ← `forName`/`getClass` の
連鎖を何段でも読み手が辿れる）。

```
M:java.lang.Class#getMethod(java.lang.String,java.lang.Class[])|n=2;0=L:run;1=K:long;r=K:jp.co.X
M:java.lang.Class#getDeclaredConstructor(java.lang.Class[])|n=0;r={M:java.lang.Class#forName(java.lang.String)|0=A:0;n=1}
```

**この表記を読み手の入力にしないこと。** 値グラフを文字列に展開すると、深さ d・引数 k で k^d に膨らむ
（同じ部分式を親ごとに書き直すため）。さらに区切り（`| ; { } =`）にエスケープが無いので、値そのものが区切りの文字を
含むと（`parse(x, ";")`、`"a|b"`、`get("USER|X")`）、読み手が途中で切って別の値に読み違え、**呼び出しを静かに
打ち切る・別の実装に繋ぐ**（参照実装で実際に起きた）。読み手はノードを**値の表**（ノードごとに種別・値・実引数・
レシーバ・書かれた型を列で持ち、値は文字列の置き場の番号で指す）に取り込んで番号で引く。値どうしは置き場の番号、
頭（種別と値）どうしは葉の参照で比べられるように、置き場は同じ中身を 2 つ持たず、葉は種別と値の組ごとに 1 つにする。
経路ごとに束縛する値（引数・コンストラクタの実引数・捕捉した値）も、文字列ではなく「種別と番号」の組で持つ。

**(a) 変数の出所は本体を先読みして作る。走査しながら作らない。**

```java
Dao d = new UserDao();
for (...) { d.select(); d = new OrderDao(); }   // 走査順だと d.select() の時点で UserDao に見える
```

同じ変数に出所の違う代入が複数あれば `U` に倒す。

**(b) 追跡できない `return` も `U` として明示的に記録する。** 「追跡できた return だけ」で
判定すると、`if (cache) return cache.get(); return new UserDao();` を `UserDao` に決め打ちする。
書き手は return を全部書き、「1つでも `U` があれば戻り値は不定」の集約は読み手が行う
（キャッシュは事実だけ）。戻り値の**宣言の型**（ラムダなら関数型インターフェースのメソッドの戻り値の型）が
primitive・void・配列・`String` のメソッドの return は記録しない（具象クラスの絞り込みに使えない。書き手の収集範囲）。
**return の式の型で決めないこと。** `Object key(boolean b) { if (b) return "x"; Object v = "y z"; return v; }` の
`return "x"` だけが抜け、読み手は残りの値を「必ずこれを返す」と読んで、その先の条件を誤って打ち切る。
宣言の型で決めれば、1 つのメソッドの return は全部記録するか、全部しないかのどちらかになる。

**(c) ラムダ式の中の `return` は囲みメソッドの戻り値ではない。** ラムダの入れ子深さを数え、
0 のときだけ記録する。匿名クラスのメソッドはラムダの中に現れうるので、`MethodDeclaration` に
入るとき深さを退避して 0 にし、抜けるとき復元する。

**(d) ファクトリの戻り値**: 「1つでも追跡できない return があれば特定しない」「複数の出所を
返すなら特定しない」。委譲（`return create();`）は上限なく畳み、循環は「決められない」で
確定する（解決より前にグラフ全体から一括で確定し、解決は事実を読むだけにする）。畳んだ結果が `C:n` / `A:n` なら、そのファクトリを呼んでいる箇所の実引数で埋める。
`C:n` で得た型名は**解析対象に存在するときだけ使う**（設定キー等をクラス名と誤認しない）。
対応する形は `Class.forName(x).newInstance()` と `getDeclaredConstructor()` /
`getConstructor()` を1段挟んだ形。この「連鎖ではなく生成される型を持つ」認識だけは書き手側の
判断として残す（値グラフを作る側と出所の文字列を作る側で違う判断をしないよう、1か所に置く）。
**戻り値の値（とそれを畳んだファクトリの事実）を呼び出しの戻り値として使うのは、呼び出しがその宣言の本体でしか
動かないときだけ**（static・private・final のメソッド、コンストラクタ、ラムダの本体、またはソース上の部分型のどれも
その宣言を上書きしていない）。`Base b; b.mode()` の `mode` を部分型が上書きしていれば、実際に動くのは上書きした本体かも
しれず、宣言の本体の値で条件を打ち切ったり実装を絞ったりすると呼び出しを落とす。上書きの有無は CHA と同じ引き方
（継承と型引数の置換）で調べる。`@Bean` メソッドだけは逆に、上書きした本体が返す型も Bean として登録する
（少なく登録すると、別の Bean へ誤って絞る）。返す具象型が値から決まらない `@Bean` メソッド（値を読まない指定では
すべて）は、宣言した戻り値の型とその部分型の「どれか」の Bean として数え、その型に触れる呼び出しは DI で絞らない。

**(e) 引数由来は経路依存なのでメモ化できない。** `rootA(){shared(new UserDao());}` と
`rootB(){shared(new OrderDao());}` で、`shared` の中の `dao.select()` は経路ごとに答えが違う。
探索の経路配列に「この深さのメソッドの各引数に何が渡ってきたか」を持ち、降りるたびに
呼び出し箇所の実引数の出所と1つ上の環境から次の環境を作る。呼び出し元を遡って集めない。
環境を作るのは「引数をレシーバに使うか次へ渡すメソッド」か「注入フィールドを持つ型の
メソッド」のときだけ（O(エッジ数) を1回で判定）。

**(f) コンストラクタ注入されたフィールド**: 書き手はフィールド宣言（修飾子・宣言型）と
「その型自身のフィールドへの代入1件ごと（代入箇所＝初期化子かメソッド／コンストラクタ、
値の出所）」を事実として残す。読み手が次の4条件を**全部**満たすフィールドだけ
「必ずこの出所の値が入る」と判定する（static フィールドは対象外）。

| # | 条件 | 崩れる例 |
|---|---|---|
| a | `private` または `final`（static でない自型のフィールド） | `Dao dao;` |
| b | 代入がコンストラクタ本体かフィールド初期化子の中だけ | setter |
| c | 初期化子を持つか、`this(...)` 委譲していない**全ての**コンストラクタで代入される | 引数なしコンストラクタが代入しない |
| d | それらの代入の出所が全て一致する | コンストラクタごとに別物 |

探索側では「今メソッドを実行しているオブジェクトのコンストラクタ実引数」と**その型**を経路に
持ち回る。レシーバが無い呼び出し（this）は同じオブジェクトなので引き継ぐ。
**親クラスのフィールドには当てない**（`super(...)` 経由の受け渡しは追跡していないので、
実引数の型とフィールドの宣言型が一致しなければ使わない）。

**(g) 匿名クラス・ローカルクラスが捕捉した変数**: 変数は今のスコープに無ければ外側の
メソッドのスコープへ辿る。安全なのは捕捉できる変数が final か実質的 final だと言語仕様が
保証しているから（`isEffectivelyFinal()` で明示的に確認する）。ただし**持ち込めるのは
フレームに依存しない出所（`T:` と `M:`）だけ**で、`A:`（今のメソッドの引数）と
`F:`（今のオブジェクトのフィールド）は落とす。持ち込むときは実引数リスト（`|0=A:0`）も
剥がす。剥がさないと解決時に匿名メソッド側の引数を当ててしまい、`DATAFLOW_FACTORY` で
**誤確定**して正しい実装の行が消える。

**(h) 除外ノードを飛ばすとき**、経路の環境（引数・コンストラクタ実引数）も除外ノードの
ものに差し替える。元のまま残すと除外メソッドの中の呼び出しに、その呼び出し元の引数を当てる。

**(i) リフレクションの解決（読み手の判断）**:
- 呼び出し先のキーが `java.lang.reflect.Method#invoke(java.lang.Object,java.lang.Object[])` /
  `java.lang.Class#forName(...)` / `java.lang.Class#newInstance()` /
  `java.lang.reflect.Constructor#newInstance(java.lang.Object[])` のエッジだけを対象にする
- `invoke`: レシーバの出所が `Class#getMethod` / `getDeclaredMethod` の戻り値で、その受け手
  （`r=`）のクラスと第1引数（メソッド名）が決まれば解決する。クラスは `K:`、
  `Class.forName(L:)`、`obj.getClass()`（`r=` の具象型）、経路上の引数、または `Class` を返す
  ソース上のメソッド（全 return が同じクラスのとき）から決める。名前は `L:`、経路上の引数、
  または `String` を返すメソッドから決める。`getMethod` の第2引数以降がすべて `K:` なら
  シグネチャで1件に、1つでも欠ければ（`n=` と突き合わせて判定）同名で本体を持つメソッドを
  その型から親へ辿って候補にする。`invoke` の第1引数の具象型が受け手のサブタイプなら
  その型の実装を優先する
- `Class.forName(名前)` は、その型に `<clinit>` があればそこへ繋ぐ（`REFLECTION_INIT`）
- `Class.newInstance()` は受け手の型の `<init>()` へ、`Constructor.newInstance` は
  `getConstructor` / `getDeclaredConstructor` の受け手の型と引数のクラスリテラルから
  `<init>(params)` へ繋ぐ
- 文字列から得た型名は**解析対象に存在するときだけ使う**
- 経路の引数環境には、具象型が決まらない引数でもリテラル（`L:`）・クラスリテラル（`K:`）なら
  「値」として渡す。`byName(obj, "run")` のように名前が引数で渡ってくる形を、経路ごとに
  解決するため。値が入っている引数は、具象型としては不明として扱う（`:` を含むかで区別）
- エッジ単位でまず試し（経路に依存しない分はメモ化される）、経路上の引数に依存する分は
  探索中にもう一度試す。`dataflow.enabled=false` ならリフレクションも解決しない

## 2.11 出力とエンコーディング

- CSVライタは `CharsetEncoder` を `CodingErrorAction.REPLACE` で組む。MS932 に変換できない
  文字（匿名クラスの内部キーに紛れる記号等）で例外にしない
- 既定は UTF-8 に BOM（`EF BB BF`）。無いと Excel が文字化けする
- 区切りはカンマ。タブにすると `.csv` を Excel がカンマ区切りとして開いて壊れる
- `callee` に引数が2つ以上あるとカンマが入るので、ダブルクォートで囲まれて出る
- 標準出力の文字コードは指定しない（`-Dfile.encoding` も `chcp` も使わない）。
  JDK 19 以降の `System.out` はコンソール自身の文字コードで書く
- キャッシュの値にタブ・改行が混ざると行の形式が壊れるので、どの列も同じ 1 つの規則で符号化する
  （除去すると値が変わる。2.14）

## 2.12 被参照スキャン（classファイルの定数プールと命令列）

- `long` / `double` 定数は**2スロット占有**する。インデックスを1つ飛ばさないと以降が全部ずれる
- 呼び出し箇所は各メソッドの Code 属性を歩いて求める。命令の長さはオペコードごとに固定で、
  可変長は `tableswitch` / `lookupswitch`（`pc+1` を 4 バイト境界に詰めてから表）と `wide`
  （次が `iinc` なら 6、それ以外は 4）だけ。未知のオペコードに当たったらそのメソッドの走査をやめる。
  行番号は LineNumberTable の「start_pc が命令のオフセット以下で最大」の項目（表は順不同なので並べ直す）
- `invokedynamic` の参照先は命令にも定数プールの InvokeDynamic にも無く、**BootstrapMethods 属性**
  （class の末尾）の引数の MethodHandle が指す Methodref にある。命令列を歩いた時点では仮の項目を置き、
  属性を読んでから置き換える（並びを保つため）。jar の中のラムダ本体（`lambda$run$0`）は
  囲みメソッド名 `run` に読み替える。相手の jar のラムダはソースが無く、こちらの
  合成メソッドとは別物なので、参照元としては囲みメソッドの行で見せる
- 命令列から辿れなかった定数プールの参照（`ldc` で MethodHandle 定数を積む形など、まず無い）は
  従来どおりクラス名だけを caller にして出す。呼び出しを静かに落とさないため
- `-g:none` の class は LineNumberTable も SourceFile も無い。caller は JVM のスタックトレースと同じ
  `at Class.method(Unknown Source)` にする（Eclipse のコンソールもこの形を受け付ける）
- ディスクリプタから作る引数型は内部クラスが `Outer$Inner`、JDT側は `Outer.Inner`。owner
  だけ読み替えて引数を放置すると**内部クラスを引数に取るオーバーロードだけ**未照合に落ちる。
  まず生の形で引き、外れたら `$`→`.` に直した形で引く（生の形が先。`$` を含むクラス名を
  誤って読み替えないため）
- 完全一致で無ければ継承を考慮して親を探す（呼び出し側は子クラスを owner に記録する）。
  「シグネチャが一致する宣言のうち owner を子孫に持つもの」を先着で選ぶと、親クラスと
  インターフェースの両方に宣言がある場合の結果がメソッドIDの並びに依存し、JDK の版で
  回帰テストが食い違った。owner から**親クラスの連鎖を先に、次にインターフェース**を幅優先で
  辿り、最初に見つかった宣言を返す（JVM のメソッド解決と同じ順）
- 暗黙のデフォルトコンストラクタは解析時に宣言として合成されているので `EXACT` で照合される。
  `IMPLICIT_CTOR` は「引数なし `<init>` への参照で、ソース上に一致する宣言が無いもの」に限る
  （相手の jar をビルドした時点では引数なしで生成できたが今は無い＝版違いの可能性）。
  それ以外の不一致（引数付きコンストラクタを含む）は未照合として数える
- 自プロジェクトの型のclassは読み飛ばす（参照先でなく**参照している側**で判定）
- **FatJar**: jar エントリ名が `.jar` / `.war` / `.ear` で終われば、`JarFile.getInputStream(entry)` を
  `ZipInputStream` で包んで中を順に読む（テンポラリに取り出さない。ローカルヘッダを順に読むので
  ファイルでなくても開け、STORED でも DEFLATED でも読める）。最上位の jar は中央ディレクトリから
  読める `JarFile` のままにし、両者の合流点として「1エントリを処理する」関数を置く。
  **中の `ZipInputStream` を閉じてはいけない**（包んでいる外側のストリームまで閉じて走査が止まる）。
  中の jar が壊れていればその jar だけ警告して読み飛ばす。入れ子は 8 段まで。
  class の型名はエントリのパスでなく class ファイル自身の `this_class` から取るので、
  `BOOT-INF/classes/` の接頭辞が混ざらない。`.zip` は受け付けない
- フォルダ指定で集めた jar は `TreeSet` でパス順に揃える（`Files.walk` の順はファイルシステム依存）。
  複数の jar が行を出すようになると、並びの違いが表面化する

## 2.13 メモリ設計（後付けできない）

1. 解析結果をヒープに溜めない。1ファイル解析するたびにキャッシュへ書いて破棄
2. エッジをオブジェクトで持たない。メソッドを int の ID に内部化し、CSR
   （`offsets[caller]..offsets[caller+1]` / `calleeIds[e]` / `callLines[e]` 等）で持つ。
   キャッシュは 1 回だけ読み、ID化と本数の数え上げをしながら、エッジ 1 本ごとに固定長の記録を
   一時ファイルへ書く。読み終えたら数えた本数からちょうどの長さの配列を作り、一時ファイルを読み直して
   `cursor[caller]++` の位置に置く（エッジの一覧をヒープで並べ替えると、1 本あたりの山が CSR より大きくなる）。
   値は値の表（2.10）に置き、エッジ側は int で参照
3. ツリーを組み立てない。深さ優先で辿りながら1行ずつ書く
4. キャッシュを読み直さないための索引や中間データ（差分更新の依存、エッジの記録、`(unresolved)` の節に出す行）は、
   ヒープではなくキャッシュと同じフォルダの一時ファイルに置く。名前は実行ごとに変え（同じフォルダを使う別の実行と
   取り違えない）、`finally` と JVM の終了フック（Ctrl+C・SIGTERM では `finally` が動かない）で消し、それでも
   残ったもの（SIGKILL）は次の実行の最初に消す。CI がキャッシュのフォルダを丸ごと保存するので、残すと大きな
   ゴミを保存する
5. `max.depth` が 0 以下でも再帰の実効上限（512）を設ける

## 2.13a 除外パッケージの読み飛ばしと再帰

除外ノードを親に繋ぎ直す処理は再帰で書くと、除外パッケージの中だけで相互再帰している
（`Ping.ping()` → `Pong.pong()` → `Ping.ping()`、両方とも除外）とき、経路配列には除外ノードが
載らないので循環判定に掛からず、`StackOverflowError` になる。読み飛ばし中の除外メソッドを
「経路からは見えないが祖先ではある」スタックに積んで循環判定に含め、読み飛ばしの入れ子数にも
深さ上限を掛ける（上限に当たったら警告してその先は辿らない）。

## 2.13b 行順の決定性（環境と履歴に依存させない）

- `.java` ファイルの列挙は `Files.walk` の順（ファイルシステム依存。ext4 と NTFS で違う）を
  そのまま使わず、ソースフォルダの宣言順 → 相対パス（`/` 区切り）の文字列順に固定する。
  鍵は `Path#compareTo` ではなく文字列（`Path#compareTo` は Windows で大文字小文字を無視する）
- 木の部分は「起点の並び」「1メソッドのエッジがそのファイルのブロック内の出現順」「CHA候補の
  FQN順」で決まっているので、ファイル順に影響されない。影響されるのは `(unresolved)` の節で、
  キャッシュのブロック順（差分更新で解析し直したファイルが先頭へ移る）のまま出すと、
  ファイルを1つ直すたびに行順が入れ替わる
- `(unresolved)` の節は、全件ためて sort しない（クラスパスが足りないと呼び出し箇所の数割になる）。
  グラフを組むためにキャッシュを読むとき（2.13）、節に出す U 行だけを一時ファイルへブロックごとにまとめて書き、
  行のあるブロックの位置だけを覚える。節を書くときに F 行の相対パスを出力順に並べ、その順にブロックの行を
  位置を指定して読む。キャッシュを読み直さず、ヒープに行を溜めない

## 2.14 キャッシュ形式（参考。同等の情報を持てば形式は自由）

**原則: キャッシュは「ASTから分かった事実」だけを持ち、判断は読む側でする。**
事実とは、ASTとバインディングから機械的に読み取れ、設定・出力形式・解決アルゴリズムに
依存しない情報（宣言・修飾子・呼び出し箇所・代入・出所）。判断とは、フィルタ・要約・推定・
しきい値・文言・「使うか使わないか」の決定。判断を焼き込むと、出力や解決の方針を変えるたびに
全件再解析になる。見分ける問いは「この値を変えたくなるのは、出力や解決の方針を変えるときか、
Javaの意味論が変わるときか」。前者なら判断であり、読み手に置く。

読み手の責務（キャッシュに入れない判断）: 静的束縛の判定（修飾子 → 種別）、戻り値の集約
（追跡できない return が1つでもあれば不定）、コンストラクタ注入フィールドの判定、import推定を
エッジとして採用するか、ラムダ内の呼び出しの計上先、未解決の理由コードの文言、
値グラフ（N 行）をどう読むか（値の表に取り込んで番号で引く。2.10）。

### キャッシュは 1 ファイル

`analysis-cache.tsv` の 1 ファイルに、ソースファイル 1 つにつき 1 ブロックで、構造（型階層・宣言・呼び出し）と
値（値グラフ・戻り値・フィールドへの代入・条件・定数）の両方を持つ。値には**長さの上限を設けない**。

**構造と値を別のファイルに分けないこと。** 「呼び出し階層を出すのに読むか」で分けたくなるが、値も具象クラスの解決
（`DATAFLOW_*`・`REFLECTION`・`@Bean`）と条件分岐の打ち切り（`[UNREACHABLE]`）を通じて出力に効くので、分けても
片方だけでは出力が作れない。参照実装は一度 2 ファイルに分け、対を保つための仕組み（両方のヘッダの世代の印、最終行の
ブロック数の突き合わせ、片方にしか無いブロックを落とすパス、2 つを歩調を合わせて読む読み手、呼び出し箇所の値を
自己記述の鍵で呼び出しの行に結びつける仕組み、一時ファイルと退避ファイルが 2 つずつ）だけが増え、
退避ファイルの片方のヘッダを確かめ忘れる不具合も出た。1 ファイルに戻してそれらをすべて消した。
片方だけを作り直してパースを節約することもできない（どちらを作るにもそのファイルのパースが要り、パースすれば両方の事実ができる）。

### ヘッダと行の形

タブ区切り。行は OS によらず `\n` で終える（`BufferedWriter.newLine()` を使わない。どの OS で書いても同じバイト列に
なり、ブロックの検査値も同じになり、ブロックをバイトの範囲で書き写せる）。読むときは CRLF も受け付ける。

1 行目は `<形式バージョン><TAB>source=<準拠レベル><TAB>enc=<ソースの文字コード><TAB>jdk=<java.specification.version><TAB>jdt=<JDTの版><TAB>folders=<ソースフォルダの並びの指紋>`。
ソースフォルダの並びの指紋は、project.root からの相対パスを並びのまま改行でつないだもののハッシュ（同じ名前の型が
2 つのソースフォルダにあると JDT はソースパスの先勝ちで解決するので、並びを入れ替えれば同じソースでも解決先が変わる）。
これが「キャッシュを作ったときの前提」の鍵で、1 つでも違えば丸ごと捨てて全件解析し直す。JDT の版は
AST の jar（`org.eclipse.jdt.core`）と型解決をするコンパイラの jar（`ecj`）の Bundle-Version を `+` でつないだもの
（どちらもバインディング解決・JLS の読み方・条件の説明の文字列を左右する）。実行時に MANIFEST から読み、
読めなければ `?` と書いて、**`?` を含む鍵は一致しないとみなす**（分からないまま古い事実を再利用しない）。

ファイルの並び:

```
ヘッダ行
L 行（解析時の依存 jar。クラスパス順）
T 行（ソース一覧の指紋、先頭の行の検査値）
ブロック（ソースファイル 1 つにつき 1 つ。F 行から次の F 行・Z 行の手前まで）
Z 行（ブロック数。最終行）
```

**列の符号化は全列に同じ 1 つの規則。** 行形式を壊す文字をバックスラッシュで符号化する（`\\` `\t` `\n` `\r`、
その他の制御文字と**対になっていないサロゲート**は `\uXXXX`。キャッシュは書けない文字で例外にする UTF-8 の書き手で
書くので、`"\uD800"` のような文字列や途中で切った絵文字をそのまま渡すと解析ごと失敗する。置換文字に潰すと別の値と
等しくなって条件を読み違える）。行を組む関数と列を分ける関数の 2 か所だけで符号化・復号し、行を表す record は
生の値を渡し・受け取る。列ごとに違う規則（「ここは除去、ここは符号化」）を混ぜると、書き手と読み手で取り違えたときに
値が静かに変わる。**この符号化は「往復すること」「符号化後にタブ・改行が残らないこと」「符号化後を UTF-8 に書けること」を
テストで確かめる。**
対象はソースに現れる形（SQL・書式文字列・Windows のパス）だけでなく、符号化の形をした文字列
（`A` という中身の文字列）や途中で切れた符号、サロゲートペアまで含める。
なお、値を**拾わない**判断（タブ・改行を含む定数の値は K 行ではハッシュにする、複数行の注釈の値は持たない）は
事実を作る側の判断で、符号化とは別。

ブロックの中の行は次の順に並べる（読み手は 1 ブロックを前から 1 回読むだけで済むよう、指される行を先に置く）。

```
F  相対パス  サイズ  エラー数  内容ハッシュ  構文エラー数  未解決数  crc
   エラー数は JDT が報告したエラーの件数（解決が不完全だった印。jar 追加時の再解析の条件に使う）。
   未解決数は使える候補の無い U 行の数（再利用したファイルの件数を U 行を読まずに数える）。
   crc は F 行（crc の列を空にした形。最後のタブまで）とこのブロックの残りの行（F 行の次から、次の F 行・Z 行の
   手前まで）の、各行に \n を付けたバイト列の CRC32。F 行を入れるのは、書き写すときに F 行の件数（エラー数・未解決数）
   から数えるため。T 行の最後の列も同じ手順で、ヘッダ行・L 行と最後の列を空にした T 行の CRC32（合わなければ丸ごと捨てる）。
   内容ハッシュが空の F 行は、解析のあいだにソースが書き換えられた印でもある（次の実行で必ず解析し直す）
I  依存する型（カンマ区切り）  型の形の指紋  解決できなかった名前（カンマ区切り）
   必ず F 行の直後。下の「差分更新」が使う 3 列。
   依存する型は、このファイルのバインディング解決が参照した型の FQN、ソースに書かれた型の名前（SimpleType などの節。
   戻り値・throws・ローカル変数・キャストの型）、ラムダ・メソッド参照の目標の関数型インターフェースとその親、
   呼び出しの受け手と実引数の式の型・修飾された名前の左側の型（解決できなかったものも。型変数・捕捉された型変数は
   上限の消去）、import 文の型（オンデマンド import は "pkg.*"）。自分が宣言する型は含まない。
   型の形の指紋は、宣言する型の、継承したものを含むメンバーの署名（JDT のキー・修飾子・戻り値と引数の型）・
   親型・型引数を並べ替えてハッシュにした頭 16 文字（java.* の型のメンバーは辿らない。無い型を復元したものには印を付ける）。
   解決できなかった名前は、型解決に失敗したブロック（エラーか BINDING_FAILED の U 行がある）でだけ書き、
   エラーの引数（IProblem.getArguments）に現れた点区切りの識別子（Foo・org.missing・q.Bar）。拾えなければ "*"
S  番号  pkg  typeFqn  method  paramSig
   ブロック内のメソッドの記号表。番号は 0 から詰め、行を書く順に初めて現れた順に振る。
   以下の「記号」はこの番号（呼び出し元が特定できない U 行は -1）
N  番号  kind  value  recv  args  argCount  staticRecv
   値グラフのノード 1 件（2.10）。1 つの式を 1 ノードとして 1 回だけ書き、参照はノード番号で行う。
   番号はブロック内の 0 始まりの連番で、recv（ノード番号。無ければ -1）と args（"位置=ノード番号" のカンマ区切り）は
   同じブロックの自分より前のノードを指す。入れ子を展開しないので深さの上限が要らない。
   staticRecv はソースに書いたときのレシーバの型（宣言元と違うときだけ。契約表・拡張が「書いてある型」で指定できるように）
G  ガード番号  op  subject  text  値1  値2 …
   呼び出し箇所を囲む条件のアトム 1 つにつき 1 行（EQ / NE は値 1 つ、IN / NI は 1 つ以上）。
   subject はノード番号（引数 A か定数 V のノードだけ）。値はコンパイル時定数の値そのもので、1 列に 1 つずつ置く
   （列の中に区切り文字の小さな形式を作らない。「値が無い」と「空文字の値が 1 つ」を見分けられなくなる）。
   ガード番号はブロック内で、1 つのガードのアトムは同じ番号で続けて並ぶ。case の無い switch の default は条件にしない
R  記号  node
   return 1 件ごとの値のノード（-1 は追跡できない）。全部書く（2.10 (b)）
H  typeFqn  kind(I/A/C)  親型をカンマ区切り  pkg  アノテーション
   親型は直接の親と、jar の型を経由して到達するソース上の親（2.8）
D  記号  declLine  hasBody(1/0)  mods  アノテーション  endLine  [returnType]
   returnType はアノテーションの付いたメソッドにだけ書く、宣言した戻り値の型の消去（無ければ列ごと省く）。
   DI の @Bean で、具象クラスを決められないときに「宣言した戻り値の型かその部分型」として数えるのに使う（2.10）。
   mods: public/protected/private/static/final/abstract/default に加えて
         implicit（暗黙のコンストラクタを合成した）、delegating（本体の先頭が this(...) 委譲）。
   AST を訪ねた順に置く（読み手は「ブロックの何番目の D 行か」を同じ行に並ぶ宣言の前後に使う。2.13b）
O  記号  上書き先のキー（; 区切り）
V  typeFqn  fieldName  mods  declType  アノテーション
C  呼び出し元の記号  呼び出し先の記号  callLine  calleeMods  recvKind(M/P/F/L/T/S/O)  lambda  qualifier
   recv  args  guard  hints
   calleeMods: 呼び出し先の修飾子。D の語彙に加えて finalclass（宣言クラスが final）、super
   lambda: 呼び出し箇所を囲む「合成メソッドにできなかったラムダ」の深さ（合成した本体の中は 0）
   qualifier: 呼び出しを修飾する型（JLS 13.1。CHA の候補を数え始める型）
   recv / args: レシーバ・実引数のノード番号。guard: G 行のガード番号（無ければ -1）
   hints: レシーバの変数に new だけが代入されているときの、その型の FQN（カンマ区切り）
U  line  呼び出し元の記号  expr  reason  candidate  recvKind  lambda  recv  args  guard  hints
   reason: BINDING_FAILED / OUTSIDE_METHOD（呼び出し元を特定できない）
   candidate: レシーバの単純名と一致する単一型 import のFQN（無ければ空）
M  line  呼び出し元の記号  ifaceTypeFqn#method(paramSig)  kind
   ラムダ／メソッド参照の1箇所。kind: lambda / methodref / ctorref
A  line  呼び出し元の記号  ownerTypeFqn  fieldName  access  mods  lambda
   フィールドの参照箇所1件（他の型のフィールドも含む）。access: read / write / readwrite
K  typeFqn  name  種別(V=値/H=ハッシュ)  値
   このファイルが宣言するコンパイル時定数。値は使う側に焼き込まれるので、差分更新で
   「値が変わった」を知るために宣言側にも残す
J  typeFqn  fieldName  site  node
   その型自身のフィールドへの代入1件（site は "<field>"=初期化子 か "name(paramSig)"。node は代入した値）
```

**番号（記号・ノード・ガード）はブロックの中だけで通じる。** 差分更新はブロックをそのまま書き写すので、ファイル全体で
1 つの表にすると、1 ファイルを解析し直すたびに書き写すブロックの番号を振り直すことになる。同じ解析結果からは
同じ番号になる（決定的）。**「番号がブロックごとに 0 から詰まっている」「参照が同じブロックの範囲に収まっている」を
テストで確かめる**（崩れるとブロックを書き写した瞬間に参照がずれる）。人が目で追うときのために、記号を 4 列
（pkg・typeFqn・名前・引数）に戻し、値のノードを 2.10 の表記にして書き出す道具を用意する。読み手は番号が
ブロックの外を指す行に出会ったら、その行・その値を使わず、1 度だけ「キャッシュのフォルダを消して実行し直して」と警告する。

**呼び出し箇所の値は、呼び出しの行（C / U）の列に直接持つ。** 値を別の行に分けて位置や鍵で結びつけると、
並びを変えた瞬間や、同じ鍵が同じ行に並ぶ箇所（`f(g(), g())`、ダイヤモンドの `new`）で静かにずれる。
new の証拠（hints）も、書き手がファイルの中で「呼び出し元＋レシーバの変数」で結びつけた結果を呼び出しの行に持つ
（変数のキーはそのファイルの宣言を指すので、結びつけはファイルの中で閉じる）。証拠を持つのは、代入が**すべて**
`new` のローカル変数だけ（引数・フィールド・拡張 for の変数には持たない）。

`J` 行は順番に注意が要る。フィールドへの代入は同じブロックの `V` 行（フィールド宣言）・`D` 行（コンストラクタ）と組で
判定するので、読み手はブロックを読み終えてから渡す。先に渡すと宣言が未登録で捨てられ、
**コンストラクタ注入の解決が静かに消える**。

C行とU行はソース上の順のまま1つの列として書く（読み手が import推定の候補をエッジにしたとき、
元の呼び出しの並びが保たれる）。型解決に失敗した呼び出しがインスタンス初期化子の中にあれば、
C行と同じく根のコンストラクタごとに1行になる。

事実の収集範囲（書き手の打ち切り）: **値グラフ（N 行）の入れ子には段数の
上限が無く、文字列・定数の値にも長さと内容の上限が無い**（1つの式を1ノードとして持ち、参照は
ノード番号で行うので、大きさが式の数に比例し深さに依存しないため。同じ式は 1 つのノードにする）／
外側スコープの変数は final か実質 final のときだけ／ローカル変数の先読みは表が変わらなくなるまで繰り返す
（上限の回数を使い切ったら書いた変数は U）／値として使う式は括弧と値を変えないキャストだけを剥がす
（値を変えうるキャストはコンパイル時定数なら変換後の値、そうでなければ U。浮動小数の定数は持たない。数値は
表記ではなく JDT が評価した値）／戻り値の宣言の型がプリミティブ・void・配列・String のメソッドの
return は記録しない／フィールドへの代入はその型自身のメソッド・コンストラクタ本体とフィールド
初期化子から拾う（インスタンス初期化ブロックと内部クラスからの代入は拾わない。匿名・ローカルクラスの
フィールド初期化子は囲むメソッドの枠から切り離して読む）。

**値グラフの葉でローカル変数を扱うときは、頭だけに落とさずノード番号で指すこと。**
頭だけにすると実引数リストが剥がれ、`Class.forName(NAME)` を受けたローカル変数から
クラス名が辿れなくなって**リフレクションの解決が静かに消える**。変数の表（バインディングキー →
値）と並べて、同じキーでノード番号の表も持つ。ただし**外側のメソッドから捕捉した変数は
ノードで持ち込まない**（入れ子の中の `A:`（引数）は別のフレームへ持ち込むと別物を指すため、
頭だけにする）。

**壊れたキャッシュ**: 途中で切れたもの（Z 行が無い・数が合わない）と UTF-8 として読めないもの（厳密に復号し、
不正なバイトを置き換えない。型名が静かに化けて再利用されるため）は丸ごと捨てて全件解析する。行の形を保ったまま
中身が変わったブロック（書き換え・化け）は F 行の crc が合わないことで見分け、**そのブロックのファイルだけ**を
解析し直す（ほかのブロックは再利用する）。

**差分更新**（旧キャッシュを先頭から丸ごと読むのはパス1 の 1 回だけ）:
0. 旧キャッシュのヘッダとL行（解析時の依存 jar）を読み、今回のクラスパスと突き合わせる。
   追加・変更・削除された jar のパッケージを「変わったパッケージ」として集める。jar は1回の走査で
   指紋とパッケージ一覧の両方を作り、指紋が旧L行と一致すればパッケージ一覧も旧L行から引き継ぐ。
   削除された jar はもう開けないので旧L行から取る（これがL行にパッケージ一覧を残す理由）
1. 旧キャッシュを読み、サイズと内容ハッシュが一致し、crc も合うファイル（有効）を覚える。無効・消滅した
   ファイルのブロックが宣言していた型（H行）を「変わった型」として集める。jar が追加・変更されて
   いれば、**型解決に失敗していたファイル（F行のエラー数 ≠ 0、U行の BINDING_FAILED）も有効から
   外す**（前回その型が無かったのだから I行に正しいFQNが入っているとは限らない。バインディング
   回復は同じパッケージにあると推定した名前を残す）。定数・親型の連鎖のために各ブロックの K 行の指紋と I 行の
   型の形の指紋を、新しい型のために型解決に失敗していたブロックの I 行の解決できなかった名前を覚える。
   有効なブロックのファイル上の位置（バイト）と F 行の件数は配列に、依存（I 行）は一時ファイルの索引に書く
   （ヒープに溜めない。2.13）
   （解析するファイルが 1 つも無く、先頭の行も同じなら、書き直しても同じバイト列になるので、
   旧キャッシュに触らずに終える。件数は書き直したときと同じに数える）
2. 変更・追加されたファイル（1で外したファイルを含む）を解析して新キャッシュへ書く。そのファイルが
   宣言する型も「変わった型」に加える（改名・追加に備える。jar 追加で引数型の解決が変わった
   宣言を呼ぶ側にも波及させる）。変更・追加されたファイルが宣言する型のうち、1 で無効になったブロックの H 行に
   無かったものは「新しい型」として覚える
3. 依存の索引を読み、有効なブロックのうち I行が「変わった型」か「変わったパッケージ」に触れるものを再解析に回す
   （`pkg.*` はそのパッケージの型が1つでも変われば触れているとみなす。パッケージの照合は I行の FQN の "." 区切りの
   前方部分を全部試す）。新しい型（名前を持つ型。無名・ローカルの型は名前で参照できないので当たらない）があれば、
   **型解決に失敗していた有効なブロックのうち、I 行の解決できなかった名前が新しい型に当たるもの**（名前の点区切りの
   どれかが新しい型の単純名か、名前が新しい型の FQN の頭の部分。"*" は何にでも当たる。無い型の名前は依存には
   残らない）と、**新しいトップレベルの型と同じパッケージで、I 行の型名にその単純名を含むブロック**（同じパッケージの
   型はオンデマンド import と `java.lang` を隠す。JLS 6.4.1）も回す
4. 再解析に回したファイルを解析して追記する。そのファイルが宣言する定数（K 行）の値か、**I 行の型の形の指紋**が
   旧キャッシュと違っていたら、宣言する型を「変わった型」に加えて 3 へ戻る（定数の値は使う側に焼き込まれるので、
   依存を 1 段辿るだけでは足りない。親の親の変化は子の I 行に載らないので、継承したものを含むメンバーが変わった型を
   連鎖させて型階層を下へ運ぶ。判定はそのファイルの解析結果と旧キャッシュの比較だけで決まり、同じ周回の中で
   どの順に解析したかに依らない。親のメソッドの本体・コメントだけの変更では形が変わらないので連鎖しない）
5. 最後まで有効だったブロックを、旧キャッシュでの順のまま、バイトの範囲で書き写す（行に戻さない）

書き終えたら一時ファイルから本物に差し替える。中断した実行の一時ファイルは次の実行で退避し、ソース一覧（T 行）と
依存 jar が当時と同じなら、最後のブロック以外で crc の合うブロックをパースの代わりに書き写す。

**同じキャッシュのフォルダを使う実行は 1 つずつにする**（OS のファイルの錠。`analysis-cache.tsv.lock`）。キャッシュ本体の
一時ファイルと退避ファイルは引き継ぎのために決まった名前なので、2 つの実行（CLI とプラグインの解析サーバー、CI の並列
ジョブ）が重なると、片方がもう片方の書きかけを引き継ぎのつもりで奪って本物に差し替え、呼び出しの 1 本も無い CSV が
「成功」として出る（参照実装で実際に起きた）。錠は差分更新からグラフの構築まで持ち、取れなければ上限つきで待ってから
理由を言って失敗する。グラフを組む側も、最後まで書き終えたキャッシュか（形式の版・最終行のブロック数・最終行の後ろに
行が無い）を確かめて、違えば組まずに止める。差分更新は旧キャッシュを 1 回だけ開き、パス 5 の書き写しまで同じものを読む
（名前で開き直すと、差し替えられた別のファイルの、パス 1 で覚えた位置とは関係の無いバイトを写す）。

**解析のあいだに書き換えられたソースを黙って再利用しない。** 内容ハッシュは解析の前に取り、JDT はあとでファイルを読む
（解析するファイルのほかに、参照している型のソースもソースパスから読む）。解析したファイルは読んだ直後にハッシュを
取り直し、前と違えば F 行の内容ハッシュを空にする。書き終えたら全ソースが実行の始めの大きさ・更新時刻のままかを見て、
変わったファイルのブロックも内容ハッシュを空にする（空のハッシュはどの中身とも一致しないので、次の実行で解析し直し、
宣言する型を「変わった型」にして依存するファイルも解析し直す）。

**同じ名前の型を宣言するファイルは同じバッチで解析する。** JDT は同じバッチの 2 つ目に「型が重複している」エラーを出して
その型を捨て、別々のバッチならどちらも読む。バッチの組み方で事実が変わると、差分更新と全件解析が食い違う。ソースフォルダ
からの相対パスが同じファイル（`src1/p/Dup.java` と `src2/p/Dup.java`）は必ず同じバッチにソースフォルダの並びの順で並べ、
片方を解析するときはもう片方も解析し直す（`package-info.java`・`module-info.java` は除く）。名前の違うファイルで同じ型を
宣言している場合は、グラフを組むときに同じメソッドキーが 2 つのファイルで宣言されていたら警告し、先に並ぶソースフォルダの
宣言を使う（後から読んだほうを勝たせると、キャッシュのブロックの並びで宣言の場所が変わる）。

**1 ファイルの失敗で設定ごと止めない。** JDT の再帰が深すぎる式（数千段つないだメソッド呼び出し）では
`StackOverflowError` が出る。一括パースの途中なら残りを 1 ファイルずつ解析し、溢れたファイルだけを失敗として案内する。

影響範囲を型ではなくパッケージで持つのは、jar の版を差し替えると型の増減があり「旧版にだけ
あった型」は新しい jar からは分からないため。jar の同一性は、末尾の目次（セントラルディレクトリ）
から取った「エントリ名・サイズ・CRC」の一覧を並べ替えたもののハッシュで見る。jar 本体を展開も
読み込みもしないので、数十MBの jar が百本あってもエントリ数に比例するだけ。クラスフォルダは
`.class` の「相対パス・サイズ・内容ハッシュ」の一覧から同じように作る。
クラスパスの並び順も見る。JDT は同名クラスを先勝ちで解決するので、jar の集合が同じでも
並びが変われば解決先が変わりうる。効くのは「同じパッケージが 2 つ以上の jar にある」ときだけなので、
パッケージごとに「そのパッケージを含む jar の並び」を作って旧L行のものと突き合わせ、
違うパッケージだけを「変わったパッケージ」に入れる（突き合わせるのは新旧に共通する jar だけ。
そうしないと jar を 1 本足しただけで並び替えと数えてしまう）。
ヒープ常駐は「ソースファイルの一覧＋サイズ」「有効なブロックの位置と件数」と
「変わった型・パッケージの集合」だけ。

**形式バージョンは、書き手の変更で事実が変わりうるなら迷わず上げる。** 列や意味の変更に限らず、収集範囲・値の正規化・
書き手が作る文字列（条件の説明の文言など）の変更も含む。上げ忘れると古いキャッシュが互換とみなされ、再利用した
ファイルだけが古い事実のまま残り、壊れ方が「エラー」ではなく「静かに違う結果」になる（参照実装で実際に起きた。
`recvKey` を `recvKind` として読んでいた）。上げたときの費用は 1 回の全件解析だけなので、迷ったら上げる。
読み手だけの変更（解決の方針・CSV の列・注記の文言）では上げない。JDT と JDK の版は鍵に入っているので、それらを
変えるだけなら上げない。**上げ忘れを検査で捕まえる**: 決まった題材を全件解析したキャッシュのブロックの中身
（パスの順に並べる）の指紋を、版・環境・題材の指紋と一緒に記録しておき、版・環境・題材が同じなのに事実の指紋が
変われば落とす。あわせて、**ヘッダの版だけを書き換えたキャッシュを再利用せず捨てることをテストで確かめる**。

なお、この節が書いているのはキャッシュの骨格（1 ファイルにする理由、ブロックの形、差分更新の考え方とパス）で、
最新の一覧と列の意味は `src/jche/cache/CacheFormat.java` のクラスコメントが正。

---

# 第3部 テストケース

## 3.1 テスト用プロジェクト

次のファイルを `fixture/src` 以下に置く。**行番号が期待値に含まれるので、内容を変えずに
置くこと**（先頭行が `package` 行、インデント4スペース）。

`fixture/src/fx/Dao.java`
```java
package fx;

public interface Dao {
    void select();
}
```

`fixture/src/fx/AbstractDao.java`
```java
package fx;

public abstract class AbstractDao implements Dao {
    public void select() {
        log();
    }

    void log() {
    }
}
```

`fixture/src/fx/UserDao.java`
```java
package fx;

public class UserDao extends AbstractDao {
}
```

`fixture/src/fx/OrderDao.java`
```java
package fx;

public class OrderDao implements Dao {
    public void select() {
    }
}
```

`fixture/src/fx/MemoDao.java`
```java
package fx;

public class MemoDao implements Dao {
    public void select() {
    }
}
```

`fixture/src/fx/Factory.java`
```java
package fx;

public class Factory {
    static Dao create() {
        return new UserDao();
    }

    static Dao delegate() {
        return create();
    }

    static Dao either(boolean flag) {
        if (flag) {
            return new UserDao();
        }
        return new OrderDao();
    }

    static Dao byName(String className) throws Exception {
        return (Dao) Class.forName(className).newInstance();
    }

    static Dao byNameModern(String className) throws Exception {
        return (Dao) Class.forName(className).getDeclaredConstructor().newInstance();
    }
}
```

`fixture/src/fx/Names.java`
```java
package fx;

public final class Names {
    public static final String ORDER_DAO = "fx.OrderDao";
}
```

`fixture/src/fx/Service.java`
```java
package fx;

public class Service {
    private final Dao dao;

    Service(Dao dao) {
        this.dao = dao;
    }

    void exec() {
        dao.select();
        helper();
    }

    private void helper() {
        dao.select();
    }
}
```

`fixture/src/fx/SetterService.java`
```java
package fx;

public class SetterService {
    private Dao dao;

    void setDao(Dao d) {
        this.dao = d;
    }

    void exec() {
        dao.select();
    }
}
```

`fixture/src/fx/PartialService.java`
```java
package fx;

public class PartialService {
    private final Dao dao;

    PartialService() {
        this.dao = null;
    }

    PartialService(Dao dao) {
        this.dao = dao;
    }

    void exec() {
        dao.select();
    }
}
```

`fixture/src/fx/Base.java`
```java
package fx;

public class Base {
    private final Dao dao;

    Base(Dao dao) {
        this.dao = dao;
    }

    void baseExec() {
        dao.select();
    }
}
```

`fixture/src/fx/Sub.java`
```java
package fx;

public class Sub extends Base {
    Sub(Dao dao) {
        super(dao);
    }
}
```

`fixture/src/fx/Handler.java`
```java
package fx;

public interface Handler {
    void handle(String s);
}
```

`fixture/src/fx/Shape.java`
```java
package fx;

public interface Shape {
    double area();
}
```

`fixture/src/fx/Circle.java`
```java
package fx;

public class Circle implements Shape {
    public double area() {
        return 3.14;
    }
}
```

`fixture/src/fx/Unit.java`
```java
package fx;

public enum Unit implements Shape {
    ONE(Helper.ratio());

    private static final double BASE = Helper.ratio();
    private final double r;

    Unit(double r) {
        this.r = r;
    }

    public double area() {
        return r * BASE;
    }
}
```

`fixture/src/fx/Helper.java`
```java
package fx;

public class Helper {
    static double ratio() {
        return 1.0;
    }

    static void validate(int x) {
    }
}
```

`fixture/src/fx/Registry.java`
```java
package fx;

public class Registry {
    static {
        Helper.ratio();
    }
}
```

`fixture/src/fx/Point.java`
```java
package fx;

public record Point(int x, int y) {
    public Point {
        Helper.validate(x);
    }
}
```

`fixture/src/fx/Sample.java`
```java
package fx;

public class Sample {
    private static final long START = compute();
    private int x = init();
    static {
        staticBlockCall();
    }
    {
        instanceBlockCall();
    }

    Sample() {
    }

    Sample(int y) {
    }

    Sample(String s) {
        this(0);
    }

    static long compute() {
        return 1L;
    }

    int init() {
        return 2;
    }

    static void staticBlockCall() {
    }

    void instanceBlockCall() {
    }
}
```

`fixture/src/fx/NoCtor.java`
```java
package fx;

public class NoCtor {
    void hello() {
    }
}
```

`fixture/src/fx/Outer.java`
```java
package fx;

public class Outer {
    public static class Inner {
        void innerMethod() {
            Helper.ratio();
        }
    }
}
```

`fixture/src/fx/Repo.java`
```java
package fx;

public class Repo {
    public void save(String s) {
    }

    public void save(long l) {
    }

    public void save(java.util.List<String> xs) {
    }

    public void save(other.List xs) {
    }

    public void save(String[] arr) {
    }

    public void save(Outer.Inner in) {
    }

    public void save(String s, long l) {
    }
}
```

`fixture/src/other/List.java`
```java
package other;

public class List {
}
```

`fixture/src/fx/internal/Bridge.java`
```java
package fx.internal;

import fx.Helper;

public class Bridge {
    public static void through() {
        Helper.validate(0);
    }
}
```

`fixture/src/fx/internal/Ping.java`
```java
package fx.internal;

import fx.Helper;

public class Ping {
    public static void ping() {
        Pong.pong();
        Helper.validate(1);
    }
}
```

`fixture/src/fx/internal/Pong.java`
```java
package fx.internal;

public class Pong {
    public static void pong() {
        Ping.ping();
    }
}
```

`fixture/src/fx/UsesLib.java`（クラスパスに無いライブラリを参照する。意図的にコンパイル不能）
```java
package fx;

import org.apache.commons.lang3.StringUtils;
import org.foo.*;

public class UsesLib {
    void guess() {
        StringUtils.isEmpty("x");
    }

    void fail() {
        Unknown.call();
    }
}
```

`fixture/src/Top.java`（デフォルトパッケージ）
```java
public class Top {
    static class In {
        void m() {
            fx.Helper.ratio();
        }
    }

    void go() {
        new In().m();
    }
}
```

`fixture/src/fx/App.java`
```java
package fx;

import java.util.ArrayList;
import java.util.function.Supplier;

public class App {

    // --- factory (path independent) ---
    void viaFactory() {
        Factory.create().select();
    }

    void viaLocalVar() {
        Dao d = Factory.create();
        d.select();
    }

    void viaDelegate() {
        Factory.delegate().select();
    }

    void viaEither() {
        Factory.either(true).select();
    }

    void viaByName() throws Exception {
        Factory.byName("fx.OrderDao").select();
    }

    void viaByNameModern() throws Exception {
        Factory.byNameModern("fx.OrderDao").select();
    }

    void viaConstant() throws Exception {
        Factory.byName(Names.ORDER_DAO).select();
    }

    void viaRuntimeName() throws Exception {
        Factory.byName(System.getProperty("dao")).select();
    }

    void viaMissingType() throws Exception {
        Factory.byName("fx.NoSuchDao").select();
    }

    // --- local new (stage 2) ---
    void viaLocalNew() {
        Dao d = new OrderDao();
        d.select();
    }

    void viaLoop() {
        Dao d = new OrderDao();
        for (int i = 0; i < 2; i++) {
            d.select();
            d = new MemoDao();
        }
    }

    // --- parameter (path dependent) ---
    void rootA() {
        shared(new UserDao());
    }

    void rootB() {
        shared(new OrderDao());
    }

    void shared(Dao dao) {
        dao.select();
        passThrough(dao);
    }

    void passThrough(Dao dao) {
        dao.select();
    }

    // --- constructor injected field ---
    void viaService() {
        new Service(new OrderDao()).exec();
    }

    void viaSetter() {
        SetterService s = new SetterService();
        s.setDao(new OrderDao());
        s.exec();
    }

    void viaPartial() {
        new PartialService(new OrderDao()).exec();
    }

    void viaSub() {
        new Sub(new OrderDao()).baseExec();
    }

    // --- capture in anonymous class ---
    void viaCapture() {
        final Dao dao = new OrderDao();
        run(new Runnable() {
            public void run() {
                dao.select();
            }
        });
    }

    void viaCaptureParam(Dao dao) {
        run(new Runnable() {
            public void run() {
                dao.select();
            }
        });
    }

    void run(Runnable r) {
        r.run();
    }

    // --- cycles ---
    void cycles() {
        selfRec(1);
        mutualA();
    }

    void selfRec(int n) {
        if (n > 0) {
            selfRec(n - 1);
        }
    }

    void mutualA() {
        mutualB();
    }

    void mutualB() {
        mutualA();
    }

    // --- lambda / method ref ---
    void viaLambda() {
        Handler h = s -> Helper.validate(s.length());
        h.handle("x");
    }

    void viaAnonHandler() {
        Handler h = new Handler() {
            public void handle(String s) {
                Helper.validate(1);
            }
        };
        h.handle("y");
    }

    void viaMethodRef() {
        Repo repo = new Repo();
        Handler h = repo::save;
        Supplier<NoCtor> s = NoCtor::new;
        java.util.function.IntFunction<String[]> arr = String[]::new;
        h.handle("z");
        s.get();
        arr.apply(1);
    }

    // --- overloads ---
    void overloads() {
        Repo repo = new Repo();
        repo.save("a");
        repo.save(1L);
        repo.save(new ArrayList<String>());
        repo.save(new other.List());
        repo.save(new String[0]);
        repo.save(new Outer.Inner());
        repo.save("a", 1L);
    }

    // --- shapes (enum in CHA) ---
    void viaShape(Shape s) {
        s.area();
    }

    // --- misc ---
    void viaNoCtor() {
        new NoCtor().hello();
    }

    void viaRecord() {
        new Point(1, 2);
    }

    void viaInner() {
        new Outer.Inner().innerMethod();
    }

    void afterAnon() {
        run(new Runnable() {
            public void run() {
            }
        });
        Helper.validate(2);
    }

    void viaBridge() {
        fx.internal.Bridge.through();
    }

    void viaExclude() {
        new ArrayList<String>().add("x");
        StringBuilder sb = new StringBuilder();
        sb.append("a").toString();
    }

    // --- reflection ---
    void viaReflectForName() throws Exception {
        Class.forName("fx.Repo").getMethod("save", long.class).invoke(new Repo(), 1L);
    }

    void viaReflectClassLiteral() throws Exception {
        Repo.class.getMethod("save", String.class).invoke(new Repo(), "a");
    }

    void viaReflectGetClass() throws Exception {
        Repo repo = new Repo();
        repo.getClass().getMethod("save", String.class).invoke(repo, "a");
    }

    void viaReflectNameArg() throws Exception {
        invokeByName(new Repo(), "save");
    }

    void invokeByName(Object target, String name) throws Exception {
        target.getClass().getMethod(name, long.class).invoke(target, 1L);
    }

    void viaReflectUnknownTypes() throws Exception {
        Class<?>[] types = new Class<?>[] { String.class };
        Repo.class.getMethod("save", types).invoke(new Repo(), "a");
    }

    void viaReflectInit() throws Exception {
        Class.forName("fx.Registry");
    }

    void viaReflectCtor() throws Exception {
        ((Dao) Class.forName("fx.OrderDao").getDeclaredConstructor().newInstance()).select();
    }

    void viaReflectRuntime() throws Exception {
        Class.forName(System.getProperty("cls")).getMethod("save", long.class).invoke(null, 1L);
    }

    // --- excluded mutual recursion ---
    void viaPingPong() {
        fx.internal.Ping.ping();
    }
}
```

`fixture/config/config.properties`（出力は `fixture/config/output/<日時>_fixture/` にできる。以下「出力」はその最新フォルダの CSV を指す）
```properties
project.root=..
source.folders=src
library.folders=
source.encoding=UTF-8
external.library.folders=
source.level=
entry.packages=
exclude.packages=java.**,javax.**,fx.internal.**
cache.enabled=true
cache.folder=./.cache
max.depth=50
max.rows=5000000
dataflow.enabled=true
dataflow.max.depth=5
output.encoding=UTF-8-BOM
output.folder=./output
```

実行: `cd fixture && java -cp "<bin>:<lib>/*" CallHierarchyExporter config/config.properties`

### 被参照スキャンと依存 jar 用の jar（T42・T43 で使う）

`fixture/ext-src/ext/Caller.java`（他チームの jar の中身。自プロジェクトの public API だけを呼ぶ）
```java
package ext;
public class Caller {
    void run() {
        new fx.Repo().save("x");
        new fx.Repo().save(1L);
        new fx.UserDao().select();
        new fx.OrderDao();
    }
}
```

`fixture/deps-src/org/apache/commons/lang3/StringUtils.java`（`UsesLib` が import している型のスタブ）
```java
package org.apache.commons.lang3;
public class StringUtils { public static boolean isEmpty(CharSequence s) { return s == null; } }
```

作り方（`fixture/` で実行。`Helper` の package-private メソッドを外から呼ぶ `Top` /
`fx.internal` は除いてコンパイルする）:
```bash
javac --release 17 -d /tmp/stub deps-src/org/apache/commons/lang3/StringUtils.java
jar --create --file deps/commons-lang3-stub.jar -C /tmp/stub .
javac --release 17 -d /tmp/fxcls -encoding UTF-8 src/fx/Dao.java src/fx/AbstractDao.java src/fx/UserDao.java \
      src/fx/OrderDao.java src/fx/Repo.java src/fx/NoCtor.java src/fx/Service.java src/fx/Outer.java \
      src/fx/Helper.java src/other/List.java
jar --create --file /tmp/fixture-app.jar -C /tmp/fxcls .
javac --release 17 -cp /tmp/fxcls -d /tmp/callercls ext-src/ext/Caller.java
jar --create --file extjars/ext-caller.jar -C /tmp/callercls .
mkdir -p /tmp/boot/BOOT-INF/lib && cp extjars/ext-caller.jar /tmp/fixture-app.jar /tmp/boot/BOOT-INF/lib/
jar --create --file extjars/app-boot.jar --no-compress -C /tmp/boot .   # Spring Boot 形式の FatJar
```

`fixture/config/ext.properties` は `config.properties` の `external.library.folders=extjars` 版、
`fixture/config/ext-deps.properties` はさらに `library.folders=deps` にした版。

## 3.2 全体の期待値（既定設定）

| 項目 | 期待値 |
|---|---|
| ログ「Javaファイル数」 | 30 |
| ログ「ソース解析」（初回） | `再利用=0 新規解析=30 失敗=0` |
| ログ「型解決できなかった呼び出し」 | 1 件（`UsesLib.fail` の `Unknown.call()`）。警告文が出る |
| ログ「エントリポイント数」 | 65（`Base.<init>(Dao)` は `Sub` の `super(dao)` から呼ばれるので起点ではない） |
| ログ「データフローで具象クラスを特定」 | new から 2 件 / ファクトリの戻り値から 6 件 / 引数から 7 件 / フィールドから 2 件 |
| ログ「リフレクション（…）の呼び出し先を特定」 | 10 件 |
| `call-hierarchy.csv` の行数 | ヘッダー含め 124 行 |
| ファイル先頭 | UTF-8 BOM（`EF BB BF`） |
| `call-hierarchy.csv` に `<init>` が現れる列 | `caller` 列だけ |
| `methods.csv` に `<init>` を含む行 | 0 行（`<clinit>` は出る） |

以下、各ケースの期待行は `call-hierarchy.csv` の `caller` / `callee` と `root` 以降を書いたもので、
間の `resolved-by` / `level` の 2 列（4.1）は紙面の都合で省いている。
ただし 1 件に確定した行だけは、どの段で決まったかが期待値そのものなので、行末に
`[RESOLVED:{ラベル}]` を付けて示す（**実際の出力ではこれは注記ではなく `resolved-by` 列に入る**。
`[UNEXPANDED:*]` や `[EXTERNAL]` の注記は実際の出力どおり行末に出る）。
この順・この文言で含まれること。
起点の並びは 5.4 の順（このフィクスチャでは `Top.go` → `fx.App` のメソッドが宣言順 →
`fx.Sample` → `fx.Unit` → `fx.UsesLib`）、同じ呼び出しの複数候補の行はFQN順
（`fx.AbstractDao` → `fx.MemoDao` → `fx.OrderDao`）。同一入力の実行間で行順まで一致すること。

## 3.3 ケース別の期待値

### T01 ファクトリの戻り値（経路非依存、継承した実装の探索）

`create()` は `UserDao` を返し、`select()` は親 `AbstractDao` にしかない。
```
at fx.App.viaFactory(App.java:10),AbstractDao.select,App.viaFactory,AbstractDao.select,[RESOLVED:DATAFLOW_FACTORY]
at fx.AbstractDao.select(AbstractDao.java:5),AbstractDao.log,App.viaFactory,AbstractDao.select,AbstractDao.log
at fx.App.viaFactory(App.java:10),Factory.create,App.viaFactory,Factory.create
```
検証観点: 段2.8 の親探索。`[RESOLVED:DATAFLOW_FACTORY]` の行の先へ**降りている**（`log` の行がある）。

### T02 ローカル変数で受けたファクトリ戻り値
```
at fx.App.viaLocalVar(App.java:15),AbstractDao.select,App.viaLocalVar,AbstractDao.select,[RESOLVED:DATAFLOW_FACTORY]
```

### T03 委譲するファクトリ（`return create();`）
```
at fx.App.viaDelegate(App.java:19),AbstractDao.select,App.viaDelegate,AbstractDao.select,[RESOLVED:DATAFLOW_FACTORY]
at fx.Factory.delegate(Factory.java:9),Factory.create,App.viaDelegate,Factory.delegate,Factory.create
```

### T04 2つの型を返しうるファクトリは絞らない
```
at fx.App.viaEither(App.java:23),AbstractDao.select,App.viaEither,AbstractDao.select,[UNEXPANDED:CHA] 3 candidates: return value (factory method etc.)
at fx.App.viaEither(App.java:23),MemoDao.select,App.viaEither,MemoDao.select,[UNEXPANDED:CHA] 3 candidates: return value (factory method etc.)
at fx.App.viaEither(App.java:23),OrderDao.select,App.viaEither,OrderDao.select,[UNEXPANDED:CHA] 3 candidates: return value (factory method etc.)
```
検証観点: 候補は `Dao.select()` をオーバーライドしている宣言3件（`AbstractDao` / `MemoDao` /
`OrderDao`、この順＝FQN順）。`UserDao` は宣言を持たないので候補に**入らない**。候補行の先へは
降りない（`AbstractDao.log` の行が `App.viaEither` を root に持たない）。

### T05 `Class.forName(文字列).newInstance()` 形式のファクトリ
```
at fx.App.viaByName(App.java:27),OrderDao.select,App.viaByName,OrderDao.select,[RESOLVED:DATAFLOW_FACTORY]
at fx.App.viaByNameModern(App.java:31),OrderDao.select,App.viaByNameModern,OrderDao.select,[RESOLVED:DATAFLOW_FACTORY]
at fx.App.viaConstant(App.java:35),OrderDao.select,App.viaConstant,OrderDao.select,[RESOLVED:DATAFLOW_FACTORY]
```
検証観点: `getDeclaredConstructor()` を挟む形と `static final String` 定数でも解決する。

### T06 実行時に決まる文字列・存在しない型名では絞らない
```
at fx.App.viaRuntimeName(App.java:39),OrderDao.select,App.viaRuntimeName,OrderDao.select,[UNEXPANDED:CHA] 3 candidates: return value (factory method etc.)
at fx.App.viaMissingType(App.java:43),OrderDao.select,App.viaMissingType,OrderDao.select,[UNEXPANDED:CHA] 3 candidates: return value (factory method etc.)
```
（それぞれ `MemoDao.select` / `AbstractDao.select` の候補行も同様に出る）

### T07 同一メソッド内の `new`（段2）
```
at fx.App.viaLocalNew(App.java:49),OrderDao.select,App.viaLocalNew,OrderDao.select,[RESOLVED:LOCAL_NEW]
```

### T08 ループ内で再代入される変数は候補集合のまま
```
at fx.App.viaLoop(App.java:55),OrderDao.select,App.viaLoop,OrderDao.select,[UNEXPANDED:CHA] 2 candidates: local variable
at fx.App.viaLoop(App.java:55),MemoDao.select,App.viaLoop,MemoDao.select,[UNEXPANDED:CHA] 2 candidates: local variable
```
検証観点: 3件のCHA候補が `new` された2型に**狭まる**が、1件には**絞らない**。

### T09 引数由来（経路依存）— 同じ呼び出し箇所が経路ごとに違う実装に解決される
```
at fx.App.rootA(App.java:62),App.shared,App.rootA,App.shared
at fx.App.shared(App.java:70),AbstractDao.select,App.rootA,App.shared,AbstractDao.select,[RESOLVED:DATAFLOW_PARAM]
at fx.App.passThrough(App.java:75),AbstractDao.select,App.rootA,App.shared,App.passThrough,AbstractDao.select,[RESOLVED:DATAFLOW_PARAM]
at fx.App.rootB(App.java:66),App.shared,App.rootB,App.shared
at fx.App.shared(App.java:70),OrderDao.select,App.rootB,App.shared,OrderDao.select,[RESOLVED:DATAFLOW_PARAM]
at fx.App.passThrough(App.java:75),OrderDao.select,App.rootB,App.shared,App.passThrough,OrderDao.select,[RESOLVED:DATAFLOW_PARAM]
```
検証観点: `shared(App.java:70)` の行が root ごとに**別の callee** を持つ。2段受け渡し
（`passThrough`）でも伝わる。`methods.csv` の `App.shared(Dao)` は
`unresolvedCalls=1, unresolvedCause=引数（メソッド外から渡される）`（経路非依存の集計では絞れない）。

### T10 コンストラクタ注入されたフィールド
```
at fx.Service.exec(Service.java:11),OrderDao.select,App.viaService,Service.exec,OrderDao.select,[RESOLVED:DATAFLOW_FIELD]
at fx.Service.helper(Service.java:16),OrderDao.select,App.viaService,Service.exec,Service.helper,OrderDao.select,[RESOLVED:DATAFLOW_FIELD]
```
検証観点: `this` への呼び出し（`helper`）の先でも解決される。

### T11 setter注入・一部のコンストラクタしか代入しない・親クラスのフィールドは絞らない
```
at fx.SetterService.exec(SetterService.java:11),OrderDao.select,App.viaSetter,SetterService.exec,OrderDao.select,[UNEXPANDED:CHA] 3 candidates: field
at fx.PartialService.exec(PartialService.java:15),OrderDao.select,App.viaPartial,PartialService.exec,OrderDao.select,[UNEXPANDED:CHA] 3 candidates: field
at fx.Base.baseExec(Base.java:11),OrderDao.select,App.viaSub,Base.baseExec,OrderDao.select,[UNEXPANDED:CHA] 3 candidates: field
```
（各3候補のうち1行を示す。`MemoDao` / `AbstractDao` の行も出る）

### T12 匿名クラスが捕捉した変数
```
at fx.App.run(App.java:116),App$1.run,App.viaCapture,App.run,App$1.run,[RESOLVED:DATAFLOW_PARAM]
at fx.App$1.run(App.java:102),OrderDao.select,App.viaCapture,App.run,App$1.run,OrderDao.select,[RESOLVED:DATAFLOW_NEW]
at fx.App$2.run(App.java:110),OrderDao.select,App.viaCaptureParam,App.run,App$2.run,OrderDao.select,[UNEXPANDED:CHA] 3 candidates: parameter (passed in from outside the method)
```
検証観点: `new` 由来（`T:`）の捕捉は解決し、囲みメソッドの**引数**（`A:`）の捕捉は解決しない。
匿名クラスの型名は `fx.App$1` / `fx.App$2`（宣言順）。`Runnable` は `java.**` だが、`App$1.run`
はプロジェクトの型なので除外されない。

### T13 循環
```
at fx.App.selfRec(App.java:127),App.selfRec,App.cycles,App.selfRec,App.selfRec,[UNEXPANDED:CYCLE] returns to a method already on this path
at fx.App.mutualB(App.java:136),App.mutualA,App.cycles,App.mutualA,App.mutualB,App.mutualA,[UNEXPANDED:CYCLE] returns to a method already on this path
```
検証観点: 無限ループしない。`[UNEXPANDED:CYCLE] returns to a method already on this path` 行の先へ降りない。`methods.csv` で `selfRec` /
`mutualA` / `mutualB` は `NORMAL`, `reachable=1`。

### T14 ラムダ／匿名クラス／メソッド参照
```
at fx.App.viaLambda(App.java:141),App.lambda$viaLambda$0,App.viaLambda,App.lambda$viaLambda$0
at fx.App.lambda$viaLambda$0(App.java:141),Helper.validate,App.viaLambda,App.lambda$viaLambda$0,Helper.validate
at fx.App.viaLambda(App.java:142),App.lambda$viaLambda$0,App.viaLambda,App.lambda$viaLambda$0
at fx.App.lambda$viaLambda$0(App.java:141),Helper.validate,App.viaLambda,App.lambda$viaLambda$0,Helper.validate
at fx.App.viaAnonHandler(App.java:151),App$3.handle,App.viaAnonHandler,App$3.handle,[UNEXPANDED:LAMBDA] implemented by a lambda/method reference (which one runs is undetermined)
at fx.App$3.handle(App.java:148),Helper.validate,App.viaAnonHandler,App$3.handle,Helper.validate
at fx.App.viaMethodRef(App.java:156),Repo.save,App.viaMethodRef,Repo.save
```
検証観点:
- ラムダ本体の `Helper.validate` は合成メソッド `App.lambda$viaLambda$0` からの呼び出し（141行）。
  `viaLambda` からは生成の辺（141行、`resolved-by` は `RESOLVED:STATIC_BOUND:PRIVATE`）と、
  `h.handle("x")` を値として追った辺（142行、`RESOLVED:DATAFLOW_LAMBDA`）の 2 本が同じ合成メソッドに繋がる
- `viaAnonHandler` の `h.handle` は唯一のソース上実装 `App$3.handle` に繋がるが、
  ラムダも同じインターフェースを実装しているので `[RESOLVED:SINGLE_IMPL]` と**書かない**
- `repo::save` が `Repo.save(String)` への辺になる（156行）
- `NoCtor::new` はコンストラクタなので行にならない。`String[]::new` は辺にならず、
  型解決失敗の件数（1件）にも**含まれない**
- `methods.csv` の `App.viaMethodRef()` は `outDegree=6`（save, NoCtor.<init>, handle, get, apply, Repo.<init>）、
  `unresolvedCalls=3`（handle / get / apply の3件が「[UNEXPANDED:LAMBDA] implemented by a lambda/method reference」）

### T15 オーバーロードと引数型略名の衝突
```
at fx.App.overloads(App.java:167),Repo.save,App.overloads,Repo.save
at fx.App.overloads(App.java:168),Repo.save,App.overloads,Repo.save
at fx.App.overloads(App.java:169),Repo.save,App.overloads,Repo.save
at fx.App.overloads(App.java:170),Repo.save,App.overloads,Repo.save
at fx.App.overloads(App.java:171),Repo.save,App.overloads,Repo.save
at fx.App.overloads(App.java:172),Repo.save,App.overloads,Repo.save
at fx.App.overloads(App.java:173),Repo.save,App.overloads,Repo.save
```
検証観点: `callee` 列は引数を付けないので、7つのオーバーロードが同じ `Repo.save` になり、
行は呼び出し箇所（`caller` の行番号）だけで見分ける。引数型の略名と衝突時の完全修飾化は
`methods.csv` の `method` 列に残る（`Repo.save(java.util.List)` / `"Repo.save(String,long)"`）。

### T16 enum がCHAの候補に入る
```
at fx.App.viaShape(App.java:178),Circle.area,App.viaShape,Circle.area,[UNEXPANDED:CHA] 2 candidates: parameter (passed in from outside the method)
at fx.App.viaShape(App.java:178),Unit.area,App.viaShape,Unit.area,[UNEXPANDED:CHA] 2 candidates: parameter (passed in from outside the method)
```
検証観点: `Circle` だけを見て `SINGLE_IMPL` に**しない**。

### T17 初期化子の帰属（`<clinit>` と複数の `<init>`）
```
at fx.Sample.<clinit>(Sample.java:4),Sample.compute,Sample.<clinit>,Sample.compute
at fx.Sample.<clinit>(Sample.java:7),Sample.staticBlockCall,Sample.<clinit>,Sample.staticBlockCall
at fx.Sample.<init>(Sample.java:5),Sample.init,Sample.Sample,Sample.init
at fx.Sample.<init>(Sample.java:10),Sample.instanceBlockCall,Sample.Sample,Sample.instanceBlockCall
at fx.Sample.<init>(Sample.java:5),Sample.init,Sample.Sample,Sample.Sample,Sample.init
at fx.Sample.<init>(Sample.java:10),Sample.instanceBlockCall,Sample.Sample,Sample.Sample,Sample.instanceBlockCall
```
検証観点: `init` / `instanceBlockCall` は `Sample()` と `Sample(int)` の2つから呼ばれる
（`Sample(String)` は `this(0)` 委譲なので複製されず、`Sample(String)` → `Sample(int)` → `init`
の経路として出る）。`methods.csv` で `Sample.init()` と `Sample.instanceBlockCall()` は `inDegree=2`、
`Sample.<clinit>()` は `ENTRY_CANDIDATE` で `outDegree=2`。

### T18 enum定数の生成と static 初期化子
```
at fx.Unit.<clinit>(Unit.java:4),Helper.ratio,Unit.<clinit>,Helper.ratio
at fx.Unit.<clinit>(Unit.java:6),Helper.ratio,Unit.<clinit>,Helper.ratio
```
検証観点: 定数の引数（4行）と static フィールド（6行）がどちらも `<clinit>` に帰属し、
「メソッド本体の外」の型解決失敗にならない。

### T19 暗黙のデフォルトコンストラクタ・record
```
at fx.App.viaNoCtor(App.java:183),NoCtor.hello,App.viaNoCtor,NoCtor.hello
at fx.Point.<init>(Point.java:5),Helper.validate,App.viaRecord,Point.Point,Helper.validate
```
検証観点: `NoCtor.hello` に `ソースなし` 注記が付かない。record のコンパクトコンストラクタ内の
呼び出しが `Point.Point` を含む経路で出る。

### T20 内部クラスの名前（`$` と `.`）、デフォルトパッケージ
```
at fx.App.viaInner(App.java:191),Outer.Inner.innerMethod,App.viaInner,Outer.Inner.innerMethod
at fx.Outer$Inner.innerMethod(Outer.java:6),Helper.ratio,App.viaInner,Outer.Inner.innerMethod,Helper.ratio
at Top.go(Top.java:9),Top.In.m,Top.go,Top.In.m
at Top$In.m(Top.java:4),Helper.ratio,Top.go,Top.In.m,Helper.ratio
```
検証観点: `caller` は `Outer$Inner`、`callee` と階層列は `Outer.Inner`。デフォルトパッケージでも
`Top.In.m`（`In.m` になっていない）。

### T21 匿名クラスより後ろの呼び出し
```
at fx.App.afterAnon(App.java:199),Helper.validate,App.afterAnon,Helper.validate
```
検証観点: 匿名クラスを抜けた後の呼び出し（199行）が囲みメソッドに帰属する。

### T22 除外パッケージの繋ぎ直し
```
at fx.internal.Bridge.through(Bridge.java:7),Helper.validate,App.viaBridge,Helper.validate
```
検証観点: `Bridge.through` の行は出ないが、その先の `Helper.validate` が `App.viaBridge` の
直下として出る。`caller` は実際の呼び出し元 `Bridge.through`。`viaExclude` を root とする行は
**0 行**（`java.**` のみを呼ぶ）。

### T22a 除外パッケージ内の相互再帰
```
at fx.internal.Ping.ping(Ping.java:8),Helper.validate,App.viaPingPong,Helper.validate
```
検証観点: `Ping.ping` → `Pong.pong` → `Ping.ping` はどちらも除外パッケージ。スタックオーバーフロー
せず終了し、`Helper.validate` の行が**1行だけ**出る（2周目は循環として打ち切られる）。

### T23 import推定と型解決失敗
```
at fx.UsesLib.guess(UsesLib.java:8),StringUtils.isEmpty,UsesLib.guess,StringUtils.isEmpty,[EXTERNAL] type guessed from an import (unverified)
at fx.UsesLib.fail(UsesLib.java:12),call,(unresolved),call,type resolution failed (missing classpath / dynamic call / etc.)
```
検証観点: 単一型インポートは推定して残す。ワイルドカードインポートは `(unresolved)` 行になり、
ログの件数（1件）と同数出る。

### T24 `methods.csv` の抜粋と行順
```
Shape.area(),fx.Shape,I,src/fx/Shape.java,4,0,0,0,ISOLATED,0,0,
Dao.select(),fx.Dao,I,src/fx/Dao.java,4,0,0,0,ISOLATED,0,0,
OrderDao.select(),fx.OrderDao,C,src/fx/OrderDao.java,4,1,18,0,LEAF,1,0,
AbstractDao.select(),fx.AbstractDao,A,src/fx/AbstractDao.java,4,1,14,1,NORMAL,1,0,
Service.exec(),fx.Service,C,src/fx/Service.java,10,1,1,2,NORMAL,1,1,フィールド変数
App.viaEither(),fx.App,C,src/fx/App.java,22,1,0,2,ENTRY_CANDIDATE,1,1,戻り値（ファクトリメソッド等）
App.viaLambda(),fx.App,C,src/fx/App.java,140,1,0,3,ENTRY_CANDIDATE,1,1,[UNEXPANDED:LAMBDA] implemented by a lambda/method reference
App$3.handle(String),fx.App$3,C,src/fx/App.java,147,1,3,1,NORMAL,1,0,
Unit.<clinit>(),fx.Unit,C,src/fx/Unit.java,3,1,0,3,ENTRY_CANDIDATE,1,0,
Registry.<clinit>(),fx.Registry,C,src/fx/Registry.java,3,1,1,1,NORMAL,1,0,
```
検証観点: インターフェースの抽象メソッドは `hasBody=0`、入次数は解決後の実装側に付く
（`OrderDao.select()` の `inDegree=18`）。`Registry.<clinit>()` はリフレクション経由で
呼ばれているので `NORMAL`（起点候補ではない）。ログの集計は
`メソッド=97 起点候補=44 孤立=5 末端=21 未到達=3 未解決の呼び出しを含む=18（コンストラクタ 39 個は出力対象外）`。

行順はソースの並び。ヘッダーの次の行から順に
`Top.In.m()` → `Top.go()` → `AbstractDao.select()` → `AbstractDao.log()` → `App.viaFactory()` → …
と続き、`fx.App` の内部の匿名クラス（`App$1.run()` 等）はそのファイルの宣言行の位置に並ぶ。
最後の3行は `Bridge.through()` → `Ping.ping()` → `Pong.pong()`（`src/fx/internal/` の相対パス順）。

## 3.4 設定を変えた実行

| ケース | 変更 | 期待値 |
|---|---|---|
| T25 キャッシュ再利用 | 同じ設定で2回目 | ログ `再利用=30 新規解析=0 失敗=0`。`call-hierarchy.csv` が1回目と**バイト単位で一致** |
| T26 差分再解析 | `App.java` を touch して3回目 | ログ `再利用=29 新規解析=1`。出力は1回目と一致 |
| T27 深さ制限 | `max.depth=2` | `at fx.App.shared(App.java:70),fx.AbstractDao.select(),App.rootA,App.shared,AbstractDao.select,[UNEXPANDED:DEPTH] depth limit (2) reached / [RESOLVED:DATAFLOW_PARAM]`（前半と後半の注記が ` / ` で連結） |
| T28 起点指定 | `entry.packages=fx.App#rootB,fx.App#rootA` | ログ `エントリポイント数: 2`。出力は T09 の10行＋T23 の `(unresolved)` 行＝ヘッダー含め 11 行。**`rootA` の行が `rootB` より先**（設定に書いた順ではなくソースの宣言順） |
| T29 行数上限 | `max.rows=3` | ログ `[WARN] 出力行数の上限(3)に達したため打ち切りました`。階層の行は3行で止まる |
| T30 データフロー無効 | `dataflow.enabled=false` | T01 が `[UNEXPANDED:CHA] 3 candidates: return value (factory method etc.)` の3行に、T09 が `[UNEXPANDED:CHA] 3 candidates: parameter (passed in from outside the method)` になる。T33〜T38 のリフレクション行は消え、T38 が `[UNEXPANDED:CHA] 3 candidates: receiver unknown` の3行になる。行数は増える（135行） |
| T31 準拠レベル範囲外 | `source.level=99` | 起動時に `IllegalArgumentException`。指定できる値の一覧を含む |
| T32 準拠レベルの丸め | `source.level=1.4` | ログ `ソースレベル: 1.8（source.level=1.4 の指定による）` と `※ source.level=1.4 はこのJDTでは扱えないため 1.8 として解析します。`。既存キャッシュを破棄した旨が出る |
| T41 依存先の変更による再解析 | キャッシュがある状態で `Dao.java` の末尾に空行とコメント行を追加して実行 | ログ `再利用=20 新規解析=10（うち依存先の変更による再解析=9） 失敗=0`（`Dao` を参照する9ファイルが再解析される）。両CSVは変更前と**バイト単位で一致**（CHA候補の行順も変わらない） |
| T42 依存 jar の追加・削除 | `ext.properties` で1回実行してキャッシュを作り、`ext-deps.properties`（`library.folders=deps`）で2回目、`ext.properties` で3回目 | 2回目のログ `[cache] 依存jarの変更を検知: 追加=1 変更=0 削除=0（影響するパッケージ 1 件）…` と `再利用=24 新規解析=6（うち依存先の変更による再解析=2、依存jarの変更による再解析=4）`。T23 の import推定の行が `at fx.UsesLib.guess(UsesLib.java:8),org.apache.commons.lang3.StringUtils.isEmpty(CharSequence),UsesLib.guess,StringUtils.isEmpty,[EXTERNAL] no source to follow` に変わる（jar で解決できたので `callee` に引数型が付き、注記が変わる）。`(unresolved)` の行（`Unknown.call()`）は残る。3回目のログ `削除=1` と `再利用=29 新規解析=1（うち依存jarの変更による再解析=1）`、出力は1回目と**バイト単位で一致** |
| T44 設定の検証 | `cache.folder=../x` | 起動時に `IllegalArgumentException`。メッセージに項目名 `cache.folder`、値 `../x`、「相対パスは設定ファイルのフォルダの配下だけ指定できます。外を指す場合は絶対パスで書いてください」の趣旨を含む |
| | `max.depth=abc` | 起動時に `IllegalArgumentException`。メッセージに項目名 `max.depth` と値 `abc` を含む |
| | `max.depth=`（空欄） | 既定値 50 で動き、出力は既定設定と一致 |
| | `output.csv=./x.csv`（旧項目） | 起動時に `IllegalArgumentException`。メッセージに `output.csv` と、`output.folder` で指定しファイル名は固定になった旨を含む |
| T45 複数の設定ファイル | `config/config.properties no-such.properties config/ext.properties` を 1 回で渡す | 1つ目と3つ目はそれぞれの出力フォルダに CSV・設定の複製・`run.log` ができ、内容は個別に実行したときと一致。2つ目は `FAIL` として一覧に出て、終了コードは 1。ログの末尾に `OK` / `FAIL` / `OK` の 3 行の一覧 |

## 3.4b 被参照スキャン（`ext.properties`）

### T43 通常の jar と FatJar、EXACT / INHERITED、自プロジェクト jar の除外

ログ: `外部jar: 2 件`、`jar=2 jar内のjar=2 クラス=2 被参照=12件（自分のメソッド 6 個） 暗黙コンストラクタ=0 未照合=0 自プロジェクトクラスを除外=11`。
`call-hierarchy.csv` は 136 行で、末尾に次の 12 行がこの順で出る（`app-boot.jar` が `ext-caller.jar` より
パス順で先。同じ `ext.Caller` が FatJar の中と単体の両方から別々の行として出る）。

```
ext.Caller,fx.Repo.Repo(),app-boot.jar!/BOOT-INF/lib/ext-caller.jar,Repo.Repo,external-ref:EXACT
ext.Caller,fx.Repo.save(String),app-boot.jar!/BOOT-INF/lib/ext-caller.jar,Repo.save,external-ref:EXACT
ext.Caller,fx.Repo.save(long),app-boot.jar!/BOOT-INF/lib/ext-caller.jar,Repo.save,external-ref:EXACT
ext.Caller,fx.UserDao.UserDao(),app-boot.jar!/BOOT-INF/lib/ext-caller.jar,UserDao.UserDao,external-ref:EXACT
ext.Caller,fx.AbstractDao.select(),app-boot.jar!/BOOT-INF/lib/ext-caller.jar,AbstractDao.select,external-ref:INHERITED
ext.Caller,fx.OrderDao.OrderDao(),app-boot.jar!/BOOT-INF/lib/ext-caller.jar,OrderDao.OrderDao,external-ref:EXACT
ext.Caller,fx.Repo.Repo(),ext-caller.jar,Repo.Repo,external-ref:EXACT
ext.Caller,fx.Repo.save(String),ext-caller.jar,Repo.save,external-ref:EXACT
ext.Caller,fx.Repo.save(long),ext-caller.jar,Repo.save,external-ref:EXACT
ext.Caller,fx.UserDao.UserDao(),ext-caller.jar,UserDao.UserDao,external-ref:EXACT
ext.Caller,fx.AbstractDao.select(),ext-caller.jar,AbstractDao.select,external-ref:INHERITED
ext.Caller,fx.OrderDao.OrderDao(),ext-caller.jar,OrderDao.OrderDao,external-ref:EXACT
```
検証観点:
- 暗黙のデフォルトコンストラクタ（`Repo()` / `UserDao()` / `OrderDao()`）は `EXACT`
- `new fx.UserDao().select()` の owner は `fx.UserDao` だが宣言は親にしかないので、
  `fx.AbstractDao.select()` の `INHERITED`
- `BOOT-INF/lib/fixture-app.jar`（自プロジェクトのクラス 11 個）は読み飛ばされ、行にならない
- `dataflow.enabled` や差分更新に関係なく、2回目の実行でも同じ 12 行

## 3.4c リポジトリの回帰テスト

参照実装のリポジトリには `samples/demo/`（27 ファイルの解析対象、他チームの jar・Spring Boot 形式の
FatJar・ear→war→jar の入れ子・依存 jar のスタブ）と `test/regression/`（`whole` / `entry` /
`jarchange` の 3 ケースの設定と期待出力）がある。これらが手に入る場合は、第3部のフィクスチャに
加えてそちらも通すこと。`jarchange` は依存 jar 無し → 有り → 無し を同じキャッシュで実行し、
jar の追加で `OrderService.execute` の `dao.findById` が `CHA候補2件` → `3件`（jar の基底クラスを
継承する `LibBackedDao` が候補に加わる。2.8）になり、`OrderService.java` 自体はキャッシュから
読まれたままであることを確認する。期待出力はツールと同じ JDK 25 で生成されている。

## 3.4a リフレクション

### T33 `Class.forName(リテラル).getMethod(名前, クラスリテラル).invoke(...)`
```
at fx.App.viaReflectForName(App.java:214),Repo.save,App.viaReflectForName,Repo.save,[RESOLVED:REFLECTION]
```

### T34 クラスリテラル・`getClass()` から
```
at fx.App.viaReflectClassLiteral(App.java:218),Repo.save,App.viaReflectClassLiteral,Repo.save,[RESOLVED:REFLECTION]
at fx.App.viaReflectGetClass(App.java:223),Repo.save,App.viaReflectGetClass,Repo.save,[RESOLVED:REFLECTION]
```
検証観点: `repo.getClass()` はローカル変数 `repo` の出所（`T:fx.Repo`）から決まる。

### T35 メソッド名と受け手が引数で渡ってくる形（経路依存）
```
at fx.App.viaReflectNameArg(App.java:227),App.invokeByName,App.viaReflectNameArg,App.invokeByName
at fx.App.invokeByName(App.java:231),Repo.save,App.viaReflectNameArg,App.invokeByName,Repo.save,[RESOLVED:REFLECTION]
```
検証観点: `invokeByName` 単体では決まらない（`target` も `name` も引数）。`viaReflectNameArg`
から渡された `new Repo()` と `"save"` を経路で持ち回って解決する。

### T36 引数型が変数のときは名前で照合し、候補を列挙する
```
at fx.App.viaReflectUnknownTypes(App.java:236),Repo.save,App.viaReflectUnknownTypes,Repo.save,[UNEXPANDED:REFLECTION] 7 candidates: matched by name because argument types are unknown
at fx.App.viaReflectUnknownTypes(App.java:236),Repo.save,App.viaReflectUnknownTypes,Repo.save,[UNEXPANDED:REFLECTION] 7 candidates: matched by name because argument types are unknown
at fx.App.viaReflectUnknownTypes(App.java:236),Repo.save,App.viaReflectUnknownTypes,Repo.save,[UNEXPANDED:REFLECTION] 7 candidates: matched by name because argument types are unknown
```
（`Repo.save` の7オーバーロード全部が候補行になる。`methods.csv` の `App.viaReflectUnknownTypes()`
は `unresolvedCalls=1, unresolvedCause=戻り値（ファクトリメソッド等）`）

### T37 `Class.forName` によるクラス初期化
```
at fx.App.viaReflectInit(App.java:240),Registry.<clinit>,App.viaReflectInit,Registry.<clinit>,[RESOLVED:REFLECTION_INIT]
at fx.Registry.<clinit>(Registry.java:5),Helper.ratio,App.viaReflectInit,Registry.<clinit>,Helper.ratio
```
検証観点: `<clinit>` の先へ降りる。`Registry.<clinit>` は入次数1になり起点候補から外れる（T24）。

### T38 `getDeclaredConstructor().newInstance()` で生成した型が以降の呼び出しに使われる
```
at fx.App.viaReflectCtor(App.java:244),OrderDao.select,App.viaReflectCtor,OrderDao.select,[RESOLVED:DATAFLOW_NEW]
```

### T39 実行時に決まるクラス名は解決しない
`App.viaReflectRuntime` を root とする行は **0 行**（`invoke` は `java.**` で除外され、解決も
されないので何も出ない）。

### T40 データフロー無効時のリフレクション
`dataflow.enabled=false` にすると T33〜T38 の `[RESOLVED:REFLECTION]*` 行と T36 の候補行は消え、
T38 は `[UNEXPANDED:CHA] 3 candidates: receiver unknown` の3行になる（T30）。

## 3.5 自己解析

ツール自身のソースを、JDTのjarを `library.folders` に指定して解析する。

- 型解決の失敗が **0件**
- `library.folders` を空にすると失敗件数が0でなくなり、警告が出て、`(unresolved)` 行が同数出る
- 依存jarフォルダを1つ指定するだけで、展開後のjar数がログに出る
- 出力の `caller` 列をEclipseの「Javaスタック・トレース・コンソール」に貼るとソースへジャンプできる
