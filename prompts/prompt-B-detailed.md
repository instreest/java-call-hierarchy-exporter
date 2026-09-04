# プロンプトB — 目的 ＋ 健全性（AST解析のはまりどころ ＋ テストケース）

> このファイルは、生成AIに `java-call-hierarchy-exporter` と同じ目的のツールを
> **健全に**（静かに漏れない・誤って絞らない・落ちない）再現させるためのプロンプトです。
> [prompt-A-minimal.md](prompt-A-minimal.md) の仕様に、JDT/AST解析で実際に踏んだ
> 落とし穴の解説と、期待出力つきのテストケースを加えてあります。
>
> テストケースの期待値は、このリポジトリの実装をテスト用プロジェクトに対して
> 実行して採取した実測値です。
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

- Java 17 以上で動く。単一ソースファイル `src/CallHierarchyExporter.java`
  （デフォルトパッケージ、`static` な入れ子クラスで構成）。ビルドは
  `javac -cp "lib/*" -d bin -encoding UTF-8 src/CallHierarchyExporter.java` の1コマンド
- 依存は JDT Core とその推移的依存のjarのみ。テストフレームワーク・ロギング
  フレームワーク・バイトコード解析ライブラリ（ASM等）は使わない
- 起動: `java -cp "bin:lib/*" CallHierarchyExporter <config.propertiesのパス>`。
  引数省略時は `config/config.properties` を使い、その旨を標準エラーに出す
- 依存jarの取得方法（JBang・Maven・手動）は問わない。参考: JDT Core 3.46.0 は
  JDK 17 以上で動き Java 26 まで解析できる。Maven で
  `org.eclipse.jdt:org.eclipse.jdt.core:3.46.0` の推移的依存をコピーすると 19 個の jar になる

## 3. 入力: 設定ファイル（`config.properties`、UTF-8）

相対パスの起点は項目ごとに違う。**設定ファイルの置き場所**を起点にするものと、
**解析対象プロジェクト（`project.root`）**を起点にするものを区別すること。

| キー | 既定 | 意味 | 相対起点 |
|---|---|---|---|
| `project.root` | 必須 | 解析対象プロジェクトのルート | 設定ファイル |
| `source.folders` | 空 | ソースフォルダ（カンマ区切り）。空なら Eclipse の `.classpath` の `kind="src"` を使う | `project.root` |
| `library.folders` | 空 | 依存jarを集めたフォルダ（カンマ区切り）。フォルダ直下の `*.jar` を全部使う。`.classpath` の `kind="lib"` があれば合算 | `project.root` |
| `source.encoding` | `UTF-8` | 解析対象ソースの文字コード（`MS932` 等） | — |
| `source.level` | 空 | 解析対象のJavaバージョン（JDTの準拠レベル）。空なら使用中のJDTが対応する最大値 | — |
| `external.library.folders` | 空 | 自分のコードを呼んでいる側の他チームjar（被参照スキャン用。ファイル／フォルダ、カンマ区切り） | `project.root` |
| `entry.packages` | 空 | 呼び出し階層の起点。空なら「呼び出し元が無いメソッド」を自動で起点にする（全体モード） | — |
| `exclude.packages` | 空 | 出力から除外する呼び出し先（書式は `entry.packages` と同じ）。既定の設定例は `java.**,javax.**` | — |
| `cache.enabled` | `true` | 解析結果のキャッシュを使い、変更の無いファイルの再解析を省く。変更されたファイルが宣言する型を参照しているファイルは、自身が変わっていなくても再解析する | — |
| `cache.folders` | `./.cache` | キャッシュの置き場所 | 設定ファイル |
| `max.depth` | `50` | 呼び出し階層の深さ上限（0以下で無制限。ただし再帰の実効上限 512） | — |
| `max.rows` | `5000000` | 出力行数の上限（0以下で無制限）。達したら打ち切って警告 | — |
| `dataflow.enabled` | `true` | ファクトリの戻り値・引数・コンストラクタ注入から具象クラスを特定する解析と、リフレクション（`Class.forName` / `getMethod` / `Method.invoke` / `newInstance`）の解決を使う | — |
| `dataflow.max.depth` | `5` | ファクトリの委譲（`return create();`）を辿る段数 | — |
| `output.encoding` | `UTF-8-BOM` | 出力CSVの文字コード。`MS932` も可。変換できない文字は `?` に置換（例外にしない） | — |
| `output.csv` | `./output/call-hierarchy.csv` | 呼び出し階層の出力先 | 設定ファイル |
| `methods.csv` | `./output/methods.csv` | メソッド一覧の出力先 | 設定ファイル |
| `resolver.hint.collectors` / `resolver.candidate.providers` | 空 | 拡張クラスのFQN（5.3参照）。設定例には載せない | — |

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
caller,callee,root,call-hierarchy
at jp.co.example.action.OrderAction.execute(OrderAction.java:50),jp.co.example.service.OrderService.findOrder(String),OrderAction.execute,OrderService.findOrder
at jp.co.example.service.OrderService.findOrder(OrderService.java:25),jp.co.example.dao.OrderDaoImpl.selectById(long),OrderAction.execute,OrderService.findOrder,OrderDaoImpl.selectById
```

| 列 | 内容 | この形にする目的 |
|---|---|---|
| `caller` | 呼び出し元。`at バイナリ名.メソッド名(ファイル名:行)` の**Javaスタックトレース形式**。行番号は**呼び出し箇所**の行 | Eclipseの「Javaスタック・トレース・コンソール」に貼ると `(ファイル:行)` がリンクになりソースへ飛べる。内部クラスは `Outer$Inner`、コンストラクタは `<init>` で書く（コンソールが解釈する形式に合わせる） |
| `callee` | 呼び出し先。**完全修飾クラス名.メソッド名(引数型の略名)**。内部クラスは `Outer.Inner`、コンストラクタはクラス名 | Excelのフィルタで、パッケージ違いの同名クラスとオーバーロードをこの1列で見分ける。行番号は混ぜない（フィルタの選択肢が散らばる） |
| `root` | 起点メソッド。`クラス単純名.メソッド名` | フィルタ用の短い表記 |
| `call-hierarchy` | 起点の次のノードから現ノードまでを**1ノード1列**で展開（可変長・必ず最終列） | 階層をそのまま読む。ヘッダーとデータ行の列数は一致しなくてよい |

- 呼び出し1件につき1行。起点自身の行は出さない
- 引数型の略名は `java.lang.String`→`String`、`java.util.List`→`List`、内部クラス
  `fx.Outer.Inner`→`Inner`。ただし略した結果**別物が同じ表記になる組だけ**完全修飾に戻す
  （`save(java.util.List)` と `save(other.List)`）。判定は全メソッドを一度走査して作る
- **コンストラクタの呼び出し自体は行にしない**（`new` したことより「その中で何を呼ぶか」が
  知りたい）。経路には積むので、コンストラクタ内からの呼び出しは階層に
  `Sample.Sample` のような形でコンストラクタを含めて出す
- 注記が付く場合は `call-hierarchy` の**最後の要素**として追加する（独立した列にしない。
  可変長列の後ろに固定列を置くと階層が途中で切れる）
- **降りている行には注記を付けない**。全行に何か書くと、注記が付いた行を目で拾えなくなる

注記の判定順（前半グループは先に当たったもの1つ、後半グループは1つ、両方あれば ` / ` で連結）:

| 順 | 条件 | 出力 |
|---|---|---|
| 前半1 | この経路上に既に現れたメソッドへ戻る | `[CYCLE]` |
| 前半2 | import推定（`EXTERNAL_GUESS`） | `外部ライブラリ（import推定・未検証）` |
| 前半3 | 呼び出し先の宣言ファイルが無い | `ソースなし（展開不可）` |
| 前半4 | 次の深さが `max.depth` に達する | `深さ制限(N)のため打ち切り` |
| 後半1 | 候補が複数で、ラベルが `REFLECTION`（`getMethod` の引数型が揃わず名前で照合） | `リフレクション候補N件（未展開）: 引数型が不明なため名前で照合` |
| 後半2 | 候補が複数（上記以外） | `CHA候補N件（未展開）: {理由}` |
| 後半3 | 候補は1件だが、ラムダ／メソッド参照も実装している | `ラムダ/メソッド参照の実装あり（未展開・本体は定義元メソッドに計上）` |
| 後半4 | 本体を持つ実装が皆無（`NO_IMPL`） | `実装なし（宣言のまま）: {理由}` |
| 後半5 | 解決先が宣言型と違う（リフレクションで解決した `REFLECTION` / `REFLECTION_INIT` を含む）、**または** データフローで決めた（宣言型と同じでも出す） | `解決:{ラベル}` |

`{理由}` はレシーバの由来: `戻り値（ファクトリメソッド等）` / `引数（メソッド外から渡される）` /
`フィールド変数` / `ローカル変数` / `自クラス（this）` / `型名（static）` / `レシーバ不明`。
理由を由来で出すのは、**次に調べる場所が由来ごとに違う**から（戻り値ならファクトリの
`return`、引数なら呼び出し元、フィールドなら代入箇所とDI設定）。注記は失敗の報告ではなく
次の調査手順として書く。

さらに、同じファイルに性質の違う2種類の行を追記する。`root` 列で区別できる。

- **型解決に失敗した呼び出し**: `caller` はスタックトレース形式、`callee` はソースに
  書かれたメソッド名、`root` = `(型解決失敗)`、階層列にメソッド名、末尾に理由
  `型解決に失敗（クラスパス不足・動的呼び出し等の可能性）`。**静かに消さないための行**
- **外部jarからの被参照**（`external.library.folders` 指定時）: `caller` = 参照している側の
  クラス名、`callee` = 自分のメソッド（callee列と同じ表記）、`root` = jar名、階層列に短縮表記、
  末尾に `被参照:EXACT` / `被参照:INHERITED` / `被参照:IMPLICIT_CTOR`

### 4.2 `methods.csv` — ソース上の全メソッドと呼び出し状況

```csv
method,declaringType,typeKind,file,line,hasBody,inDegree,outDegree,role,reachable,unresolvedCalls,unresolvedCause
Service.exec(),fx.Service,C,src/fx/Service.java,10,1,1,2,NORMAL,1,1,フィールド変数
```

| 列 | 内容 |
|---|---|
| `method` | `クラス単純名.メソッド名(引数型略名)`（オーバーロードを見分ける。衝突時の完全修飾化は callee 列と同じ） |
| `declaringType` / `typeKind` | 完全修飾クラス名 / `I`=インターフェース, `A`=抽象クラス, `C`=具象 |
| `file` / `line` | 宣言ファイル（プロジェクトルート相対、`/` 区切り）と宣言行 |
| `hasBody` | 本体を持つか（IFの抽象メソッドとdefaultメソッドの区別） |
| `inDegree` | **具象クラスに解決した後の**被呼び出し数 |
| `outDegree` | 呼び出し数 |
| `role` | `ISOLATED`（in=0かつout=0）/ `ENTRY_CANDIDATE`（in=0）/ `LEAF`（out=0）/ `NORMAL` |
| `reachable` | 起点集合から解決後のエッジで到達できるか |
| `unresolvedCalls` / `unresolvedCause` | このメソッド内で具象クラスを1つに絞れなかった呼び出しの件数と理由（`;` 区切りで重複排除。`実装なし（宣言のまま）` / `ラムダ/メソッド参照の実装あり` / レシーバ由来） |

ソースの無いメソッド（jar内）と `<init>` は出力しない。合成した `<clinit>` は出す。
行順はソースの並び（ソースフォルダの指定順 → ファイルの相対パス順 → 宣言行順 → 同一行はID順）。
メソッドIDの順（キャッシュ上の出現順）で出すと、差分更新で解析し直したファイルが末尾へ移り、
実行のたびに並びが変わる。

## 5. 振る舞いの要点

### 5.1 3フェーズ構成

```
フェーズ1  ソース解析   .java（1ファイルずつ）→ キャッシュファイル（TSV）
フェーズ2  グラフ構築   キャッシュ → メソッドをintのIDに内部化したCSR形式の呼び出しグラフ
フェーズ3  出力         methods.csv を書き、起点ごとに深さ優先で辿りながら call-hierarchy.csv を1行ずつ書く
```

差分判定は「最終更新時刻とファイルサイズの両方が一致」。キャッシュの1行目に形式バージョンと
準拠レベルを書き、不一致なら全体を破棄する。更新は旧キャッシュを先頭から読むストリーミング
マージで、ランダムアクセスも全件保持もしない。

**依存先の変更でも再解析する。** 更新時刻とサイズが一致するファイルでも、そのファイルの
バインディング解決が参照した型（呼び出し先・フィールドの所有型・親型・引数型・import の型）を
宣言するファイルが変わっていれば解析し直す。オーバーロードの追加やフィールドの改名で、
呼んでいる側の解決結果が変わるため。依存は1段で足りる（Aを解析し直してもAが宣言する型は
変わらないので、Aに依存するファイルへは波及しない）。ログには
`新規解析=N（うち依存先の変更による再解析=M）` と出す。

**キャッシュには「ASTから分かった事実」だけを入れ、判断は読む側で行う。** 事実とは
宣言と修飾子、呼び出し箇所、フィールドへの代入、値の出所など、設定・出力形式・解決アルゴリズムに
依存しない情報。静的束縛かどうか、コンストラクタ注入と言い切れるか、import推定を呼び出し先として
採用するか、といった判断はキャッシュを読む側で行う。出力や解決の方針を変えてもキャッシュを
作り直さずに済ませるため。キャッシュの版を上げるのは事実の意味・列・収集範囲が変わったときだけ。

### 5.2 抽出する呼び出し

メソッド呼び出し、`super.m()`、`new`、`this(...)`/`super(...)`、enum定数の生成、
メソッド参照4種（`obj::m` / `Type::m` / `super::m` / `Type::new`）。
ラムダ本体の呼び出しは囲みメソッドに帰属させる。
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

拡張ポイント: `CallSiteHintCollector`（AST走査中に呼び出し箇所の証拠をキャッシュに残す）と
`TypeCandidateProvider`（宣言型・シグネチャ・証拠から具象型FQNの配列とラベルを返す。
`appliesToStaticBound()` が true なら段0の呼び出しにも尋ねる）。設定ファイルの内容と
置き場所を `init()` で渡す。読み込み失敗は警告して続行。

### 5.4 探索

- 起点: `entry.packages` 一致でソース宣言のあるメソッド。未指定なら
  **解決後の入次数0**かつソースに本体があるメソッド全部。並びはどちらも (1) ソースフォルダの
  指定順 (2) 型FQN順 (3) 宣言行順 (4) ID順（`entry.packages` に書いた順ではない）
- 型階層の子型・親型リストはFQN順に整列する。CHA候補の並び（＝出力の行順、上限20件で
  打ち切るときにどの候補を載せるか）がキャッシュ上のブロック順に依存すると、
  差分更新後の出力が cold 実行と一致しなくなる
- 循環検出は経路単位（`[CYCLE]` を1行出して降りない）。グローバル訪問済み集合は持たない
- `exclude.packages` 一致ノードは行にしないが、その先は親に繋ぎ直して辿る。
  繋ぎ直すときはデータフローの環境（引数・コンストラクタ実引数）も除外ノードのものに差し替える
- CHA候補が複数なら候補を上限20件まで列挙し、先へは降りない
- `max.depth` 到達で打ち切り、`max.rows` 到達で警告して全体を打ち切る
- ツリーを組み立てない。ヒープに載るのは現在の経路（深さぶんの配列）だけ

### 5.5 型解決できなかった呼び出し

1. レシーバが単純名で、そのファイルの単一型インポートと一致すればそのFQNを採用（未検証）
2. それも無理なら「型解決失敗」として記録し、件数をログに出し、`(型解決失敗)` 行を出す

### 5.6 被参照スキャン

外部jarのclassファイルの定数プールだけを自前で読み、Methodref / InterfaceMethodref の
うち自分の型を owner とするものを列挙する。参照している側のclass自体が自プロジェクトの
型なら読み飛ばして件数をログに出す。一致するメソッドが無い参照は「相手が古い版に対して
ビルドされている可能性」として件数をログに出す。

### 5.7 実行ログ

行頭に `[分:秒.ミリ秒s]`。設定の解決結果（実際に効いた準拠レベルを含む）、展開後の依存jar
一覧、フェーズごとの件数、各フェーズ終了時のヒープ使用量。進捗表示はフェーズ1だけ。
標準出力の文字コードは固定しない。

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
  更新時刻とサイズだけ見ていると設定変更後に古い結果を再利用する

## 2.3 クラスパスとソースパス

- `setEnvironment(..., includeRunningVMBootclasspath=true)` でJDK標準クラスは実行中の
  JVMから解決される。ツール自身を動かすJDKが変わると解析結果が変わりうる
- `classpath` には**jarを1つずつ**渡す。フォルダを渡しても展開されない。`library.folders` の
  フォルダは直下の `*.jar` をファイル名順に列挙して展開し、**展開後の一覧をログに出す**
- Eclipse の `.classpath` は `kind="src"` / `kind="lib"` だけ読む。**`kind="con"`
  （Gradle/Mavenのコンテナ）は解決できない**ので、そういうプロジェクトは `library.folders` 必須。
  他プロジェクト参照（`/` 始まりの `src`）は警告してスキップ。XML読み込みは DOCTYPE を禁止
- Eclipse の `plugins/*` をワイルドカードでクラスパスに入れると、無関係なjarのSPI登録で
  `ServiceConfigurationError` になる。必要なjarだけを集める

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
`ソースなし（展開不可）` と誤表示される。record の暗黙の正準コンストラクタは
レコードコンポーネントを引数に取るので、`ITypeBinding.getDeclaredMethods()` の
コンストラクタを正として合成する。

**(d) ラムダ式**: `MethodDeclaration` ではないので、中の呼び出しは自動的に囲みメソッドに
帰属する。これはソース上の見え方と一致するので特別扱いしない（2.9 も参照）。

## 2.5 visit すべきASTノード

| ノード | 目的 | 落とすと |
|---|---|---|
| `TypeDeclaration` / `EnumDeclaration` / `RecordDeclaration` / `AnnotationTypeDeclaration` / `AnonymousClassDeclaration` | 型階層（種別 I/A/C と親型）と型コンテキスト | **enum/record はASTノードとして別物**。`TypeDeclaration` だけ見ていると、インターフェースを実装する enum が候補に入らず、他に実装が1つあると `SINGLE_IMPL` で**誤確定**する。enum のフィールド初期化子は「メソッド外」に落ちる |
| `EnumConstantDeclaration` | 定数の生成（`<clinit>` からのコンストラクタ呼び出し） | enum のコンストラクタが誰からも呼ばれていないように見える |
| `MethodDeclaration` | 宣言（`hasBody` を記録）と呼び出し元スタック | — |
| `FieldDeclaration` / `Initializer` | 2.4(b) | — |
| `MethodInvocation` / `SuperMethodInvocation` / `ClassInstanceCreation` / `ConstructorInvocation` | 呼び出し辺 | — |
| `ExpressionMethodReference` / `TypeMethodReference` / `SuperMethodReference` / `CreationReference` | メソッド参照を「囲みメソッドからの呼び出し」として記録 | `::` でしか参照されないメソッドが「呼ばれていない」ように見える。**`String[]::new` は配列生成で呼ぶメソッドが無いので辺にせず、型解決失敗にも数えない** |
| `LambdaExpression` | 関数型インターフェースのメソッドを「ラムダも実装している」と記録（2.9）。入れ子深さを数える | — |
| `ReturnStatement` | 戻り値の出所（2.10） | — |
| `VariableDeclarationFragment` / `Assignment` | 段2の `new` 追跡。変数の同定は名前でなく `IVariableBinding.getKey()` | 同名変数がスコープ違いで誤解決 |
| `TypeLiteral` | `X.class` の出所（`K:`）。リフレクションの受け手・引数型 | `getMethod("run", long.class)` のシグネチャが決まらない |
| `SimpleName`（フィールドに解決されるもの） | フィールドの参照箇所（read / write / readwrite）。`a.b.c` の `c`、`this.x` の `x`、`super.x` の `x` はすべて SimpleName に行き着くので、ここだけ見れば重複なく拾える。宣言そのものと配列の `length` は除く | — |
| `ImportDeclaration` | 走査しない（`visit` で false）。import の型は依存（I行）として別に数える | import の中の名前を参照箇所と誤認する |

## 2.6 名前の正規化（静かに壊れる箇所）

| 用途 | 形式 | 例 |
|---|---|---|
| 内部ID・型階層の照合 | ソース上の正規名 | `jp.co.xxx.Outer.Inner` |
| `callee` 列 | FQN.メソッド名(引数略名) | `jp.co.xxx.Outer.Inner.method(String)` |
| `root` / `call-hierarchy` 列 | 単純名.メソッド名 | `Outer.Inner.method` |
| `caller` 列 | **バイナリ名** | `at jp.co.xxx.Outer$Inner.method(Foo.java:12)` |

- メソッドのキーは `typeFqn#name(消去済み引数型FQN,...)` の1本。ID化・CHA・出所・被参照の
  照合すべてに同じキーを使う。型は常に `getErasure()`、メソッドは `getMethodDeclaration()`
  を通す（実体化された型のままだと同じメソッドが別IDになる）
- **匿名クラスは `getQualifiedName()` が空文字**。そこでスキップすると匿名クラスによる
  オーバーライドと中の呼び出しが丸ごと落ちる。`getQualifiedName()` → `getBinaryName()`
  （`Outer$1`）→ `getKey()`（空白を `_` に置換）の順にフォールバックする
- **デフォルトパッケージ**で「FQNからパッケージを除いた単純名」を `lastIndexOf('.')` で
  取ると、内部クラスの外側クラス名が消える（`Top.In` → `In`）。パッケージが空なら
  FQN全体をそのまま単純名にする
- コンストラクタは内部的に `<init>`。表示はクラス単純名（`Sample.Sample`）。ただし `caller`
  列だけは `<init>` のまま（スタックトレース・コンソールの形式）
- 引数略名の衝突判定は「単純名ラベルが同じで、キーが違う」組を全メソッドから集める

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
- 参照実装の段2（`LOCAL_NEW`）は `型#シグネチャ` の完全一致で引き、親を辿らない。
  これは既知の制約で、継承した実装を段2で取りこぼす（その場合は段4以降で拾われる）。
  親を辿る実装にしてもよいが、第3部のテストは完全一致でも通るように作ってある
- 入次数は**解決後の候補**に対して数える。宣言型で数えるとIF経由でしか呼ばれない実装が
  すべて入次数0になり、真の入口と区別がつかない。リフレクションで解決した先（`<clinit>` や
  `invoke` の実体）にも入次数が付くので、`Class.forName("a.B")` があれば `a.B.<clinit>` は
  起点候補でなくなる
- 型階層の子型・親型リストはFQN順に整列してからCHA候補を作る（キャッシュ上の出現順に
  依存させない。差分更新後も cold 実行と同じ行順にするため）

## 2.9 ラムダ／メソッド参照

- ラムダとメソッド参照が実装している関数型インターフェースのメソッドを「展開できない実装が
  ある」として記録する。書き手は箇所ごと（行・囲みメソッド・lambda / methodref / ctorref）の
  事実を残し、読み手は「ある／なし」だけを使う（件数を判定に使うとファイル単位の部分再利用で
  値がぶれる）。これが無いと、匿名クラスが1件あるだけで `SINGLE_IMPL` と判定し、
  **実際に動くラムダとは違う実装に決め打ち**する
- 候補の絞り込み自体は変えない。`解決:SINGLE_IMPL` と書く代わりに
  `ラムダ/メソッド参照の実装あり（未展開・…）` を出し、`unresolvedCalls` にも数える
- **ラムダ本体の呼び出しを合成メソッドへ付け替えない**。付け替えると `forEach` のように
  `exclude.packages`（既定 `java.**`）で除外されるAPIに渡したラムダの本体が到達不能になり、
  出力から丸ごと消える。囲みメソッドに計上したままにする（順序の正確さより取りこぼさないこと）
- 匿名クラスは実型（`fx.App$1`）として扱う。名前を持ち型階層に載るので、インターフェース経由の
  呼び出しから正しく辿れる

## 2.10 データフロー解析のはまりどころ

判断の軸は「絞れないことより、間違って絞ることの方が有害」。

**出所（Origin）の表現**: 式が「どこから来たか」を1文字の種別と値で持つ。

| 表記 | 意味 | 確定するタイミング |
|---|---|---|
| `T:fqn` | `new` された具象型 | その場 |
| `A:n` | 囲みメソッドの n 番目の引数 | 経路を降りて呼び出し元が決まったとき |
| `M:typeFqn#m(params)` | メソッドの戻り値 | そのメソッドの return を見たとき |
| `F:typeFqn#field` | フィールド | コンストラクタ注入の記録と経路上の生成箇所を見たとき |
| `L:値` | 文字列リテラル／コンパイル時定数。完全修飾クラス名の形か、識別子1つの形（メソッド名等。64文字以内） | その場 |
| `K:fqn` | クラスリテラル `X.class`（配列は `[]` 付き、プリミティブはそのまま） | その場 |
| `C:n` | `Class.forName(n番目の引数)` で名前指定された型 | その呼び出しの実引数を見たとき |
| `U` | 追跡できない | 決まらない |

`new` とメソッド呼び出しの出所には実引数の出所も付ける（`T:fx.Service|0=T:fx.OrderDao`）。
メソッド呼び出しの出所にはさらに、実引数の数 `n=`（出所が分からず省いた引数と、引数が
無いことを区別する）と、レシーバの出所 `r=` を付ける。レシーバは**3段まで入れ子**にする
（`invoke` ← `getMethod` ← `forName`/`getClass` の連鎖を読み手が辿るため）。入れ子の出所が
実引数リストを持つ場合は `{}` で囲む。

```
M:java.lang.Class#getMethod(java.lang.String,java.lang.Class[])|n=2;0=L:run;1=K:long;r=K:jp.co.X
M:java.lang.Class#getDeclaredConstructor(java.lang.Class[])|n=0;r={M:java.lang.Class#forName(java.lang.String)|0=A:0;n=1}
```

実引数の出所は**入れ子にしない**（`{}` の中にさらにリストは持たせない）。
追跡できない引数は載せない。区切りは `|` と `;`（値の側が括弧を含むため、括弧だと対応の
判定が要る）。解析関数は `{}` の深さを数えて区切りを探す。

**(a) 変数の出所は本体を先読みして作る。走査しながら作らない。**

```java
Dao d = new UserDao();
for (...) { d.select(); d = new OrderDao(); }   // 走査順だと d.select() の時点で UserDao に見える
```

同じ変数に出所の違う代入が複数あれば `U` に倒す。

**(b) 追跡できない `return` も `U` として明示的に記録する。** 「追跡できた return だけ」で
判定すると、`if (cache) return cache.get(); return new UserDao();` を `UserDao` に決め打ちする。
書き手は return を全部書き、「1つでも `U` があれば戻り値は不定」の集約は読み手が行う
（キャッシュは事実だけ）。戻り値が primitive・配列・`String` の return は記録しない
（具象クラスの絞り込みに使えない。書き手の収集範囲）。

**(c) ラムダ式の中の `return` は囲みメソッドの戻り値ではない。** ラムダの入れ子深さを数え、
0 のときだけ記録する。匿名クラスのメソッドはラムダの中に現れうるので、`MethodDeclaration` に
入るとき深さを退避して 0 にし、抜けるとき復元する。

**(d) ファクトリの戻り値**: 「1つでも追跡できない return があれば特定しない」「複数の出所を
返すなら特定しない」。委譲（`return create();`）は `dataflow.max.depth` 段まで辿り、循環は
打ち切る。畳んだ結果が `C:n` / `A:n` なら、そのファクトリを呼んでいる箇所の実引数で埋める。
`C:n` で得た型名は**解析対象に存在するときだけ使う**（設定キー等をクラス名と誤認しない）。
対応する形は `Class.forName(x).newInstance()` と `getDeclaredConstructor()` /
`getConstructor()` を1段挟んだ形。文字列リテラルは「ドットを含み、各要素が識別子で、最後の
要素が英大文字始まり」（クラス名）か「識別子1つ、64文字以内」（メソッド名・フィールド名）の
形だけ記録する（ログ文言やSQLでキャッシュを埋めない）。

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
- キャッシュの値にタブ・改行が混ざると形式が壊れるので書き出す前に除去する

## 2.12 被参照スキャン（classファイルの定数プール）

- `long` / `double` 定数は**2スロット占有**する。インデックスを1つ飛ばさないと以降が全部ずれる
- ディスクリプタから作る引数型は内部クラスが `Outer$Inner`、JDT側は `Outer.Inner`。owner
  だけ読み替えて引数を放置すると**内部クラスを引数に取るオーバーロードだけ**未照合に落ちる。
  まず生の形で引き、外れたら `$`→`.` に直した形で引く（生の形が先。`$` を含むクラス名を
  誤って読み替えないため）
- 完全一致で無ければ継承を考慮して親を探す（呼び出し側は子クラスを owner に記録する）
- `<init>` が一致しないものは暗黙のデフォルトコンストラクタとして `IMPLICIT_CTOR` で出す
- 自プロジェクトの型のclassは読み飛ばす（参照先でなく**参照している側**で判定）

## 2.13 メモリ設計（後付けできない）

1. 解析結果をヒープに溜めない。1ファイル解析するたびにキャッシュへ書いて破棄
2. エッジをオブジェクトで持たない。メソッドを int の ID に内部化し、CSR
   （`offsets[caller]..offsets[caller+1]` / `calleeIds[e]` / `callLines[e]` 等）で持つ。
   キャッシュを2回スキャン（1回目でID化と本数カウント、2回目で流し込み）。
   出所の文字列は共有プールに置き、エッジ側は int で参照
3. ツリーを組み立てない。深さ優先で辿りながら1行ずつ書く
4. `max.depth` が 0 以下でも再帰の実効上限（512）を設ける

## 2.14 キャッシュ形式（参考。同等の情報を持てば形式は自由）

**原則: キャッシュは「ASTから分かった事実」だけを持ち、判断は読む側でする。**
事実とは、ASTとバインディングから機械的に読み取れ、設定・出力形式・解決アルゴリズムに
依存しない情報（宣言・修飾子・呼び出し箇所・代入・出所）。判断とは、フィルタ・要約・推定・
しきい値・文言・「使うか使わないか」の決定。判断を焼き込むと、出力や解決の方針を変えるたびに
全件再解析になる。見分ける問いは「この値を変えたくなるのは、出力や解決の方針を変えるときか、
Javaの意味論が変わるときか」。前者なら判断であり、読み手に置く。

読み手の責務（キャッシュに入れない判断）: 静的束縛の判定（修飾子 → 種別）、戻り値の集約
（追跡できない return が1つでもあれば不定）、コンストラクタ注入フィールドの判定、import推定を
エッジとして採用するか、ラムダ内の呼び出しの計上先、未解決の理由コードの文言。

タブ区切り。1行目 `jche-cache-v9<TAB>source=<準拠レベル>`。`F` 行が現れるたび以降の行は
そのファイルに属する。値にタブ・改行が混ざると形式が壊れるので書き出す前に除去する。

```
F  相対パス  更新時刻  サイズ
I  依存する型（カンマ区切り）     このファイルのバインディング解決が参照した型のFQNと import 文の型
                                （オンデマンド import は "pkg.*"）。自分が宣言する型は含まない。
                                F行の直後に置く（差分更新でブロックを読み進める前に依存を判定する）
H  typeFqn  kind(I/A/C)  親型をカンマ区切り  pkg
D  pkg  typeFqn  method  paramSig  declLine  hasBody(1/0)  mods
   mods: public/protected/private/static/final/abstract/default に加えて
         implicit（暗黙のコンストラクタを合成した）、delegating（本体の先頭が this(...) 委譲）
V  typeFqn  fieldName  mods  declType                 （フィールド宣言。declType は宣言型のFQN）
A  line  callerPkg callerType callerMethod callerParams  ownerTypeFqn  fieldName  access  mods  lambda
   フィールドの参照箇所1件（他の型のフィールドも含む）。access: read / write / readwrite
J  typeFqn  fieldName  site  origin                   （その型自身のフィールドへの代入1件。
                                                        site は "<field>"=初期化子 か "name(paramSig)"）
C  callerPkg callerType callerMethod callerParams  calleePkg calleeType calleeMethod calleeParams
   callLine  calleeMods  recvKey  recvKind(M/P/F/L/T/S/O)  recvOrigin  argOrigins  lambda
   calleeMods: 呼び出し先の修飾子。D の語彙に加えて finalclass（宣言クラスが final）、super
   lambda: 呼び出し箇所を囲むラムダの深さ（現在の読み手は使わないが事実として残す）
U  line  callerPkg callerType callerMethod callerParams  expr  reason  candidate
   recvKey  recvKind  recvOrigin  argOrigins  lambda
   reason: BINDING_FAILED / OUTSIDE_METHOD（呼び出し元を特定できない。caller は空）
   candidate: レシーバの単純名と一致する単一型 import のFQN（無ければ空）
R  pkg  typeFqn  method  paramSig  origin            （return 1件ごと。追跡できなければ U。全部書く）
M  line  callerPkg callerType callerMethod callerParams  ifaceTypeFqn#method(paramSig)  kind
   ラムダ／メソッド参照の1箇所。kind: lambda / methodref / ctorref
X  callerMethodキー  scopeKey  種別  値              （拡張が拾った証拠）
```

C行とU行はソース上の順のまま1つの列として書く（読み手が import推定の候補をエッジにしたとき、
元の呼び出しの並びが保たれる）。型解決に失敗した呼び出しがインスタンス初期化子の中にあれば、
C行と同じく根のコンストラクタごとに1行になる。

事実の収集範囲（書き手の打ち切り。変えたら版を上げる）: 実引数の出所は1段のみ／レシーバの
出所は3段まで／外側スコープの変数は final か実質 final のときだけ／ローカル変数の先読みは1回／
文字列リテラルは完全修飾クラス名か識別子（64文字以内）の形だけ／プリミティブ・配列・String を
返す return は記録しない／フィールドへの代入はその型自身のメソッド・コンストラクタ本体と
フィールド初期化子から拾う（インスタンス初期化ブロックと内部クラスからの代入は拾わない）。

**差分更新の4パス**:
1. 旧キャッシュを読み、更新時刻とサイズが一致するファイル（有効）を覚える。無効・消滅した
   ファイルのブロックが宣言していた型（H行）を「変わった型」として集める
2. 変更・追加されたファイルを解析して新キャッシュへ書く。そのファイルが宣言する型も
   「変わった型」に加える（改名・追加に備える）
3. 旧キャッシュをもう一度読み、有効なブロックのうち I行が「変わった型」に触れないものだけ
   書き写す（`pkg.*` はそのパッケージの型が1つでも変われば触れているとみなす）。
   触れるものは再解析に回す
4. 再解析に回したファイルを解析して追記する

ヒープ常駐は「ソースファイルの一覧＋更新時刻・サイズ」と「変わった型の集合」だけ。

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
}
```

`fixture/config/config.properties`
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
cache.folders=./.cache
max.depth=50
max.rows=5000000
dataflow.enabled=true
dataflow.max.depth=5
output.encoding=UTF-8-BOM
output.csv=./output/call-hierarchy.csv
methods.csv=./output/methods.csv
```

実行: `cd fixture && java -cp "<bin>:<lib>/*" CallHierarchyExporter config/config.properties`

## 3.2 全体の期待値（既定設定）

| 項目 | 期待値 |
|---|---|
| ログ「Javaファイル数」 | 28 |
| ログ「ソース解析」（初回） | `再利用=0 新規解析=28 失敗=0` |
| ログ「型解決できなかった呼び出し」 | 1 件（`UsesLib.fail` の `Unknown.call()`）。警告文が出る |
| ログ「エントリポイント数」 | 63 |
| ログ「データフローで具象クラスを特定」 | new から 2 件 / ファクトリの戻り値から 6 件 / 引数から 7 件 / フィールドから 2 件 |
| ログ「リフレクション（…）の呼び出し先を特定」 | 10 件 |
| `call-hierarchy.csv` の行数 | ヘッダー含め 123 行 |
| ファイル先頭 | UTF-8 BOM（`EF BB BF`） |
| `call-hierarchy.csv` に `<init>` が現れる列 | `caller` 列だけ |
| `methods.csv` に `<init>` を含む行 | 0 行（`<clinit>` は出る） |

以下、各ケースの期待行は `call-hierarchy.csv` に**この文字列のまま**含まれること。
起点の並びは 5.4 の順（このフィクスチャでは `Top.go` → `fx.App` のメソッドが宣言順 →
`fx.Sample` → `fx.Unit` → `fx.UsesLib`）、同じ呼び出しの複数候補の行はFQN順
（`fx.AbstractDao` → `fx.MemoDao` → `fx.OrderDao`）。同一入力の実行間で行順まで一致すること。

## 3.3 ケース別の期待値

### T01 ファクトリの戻り値（経路非依存、継承した実装の探索）

`create()` は `UserDao` を返し、`select()` は親 `AbstractDao` にしかない。
```
at fx.App.viaFactory(App.java:10),fx.AbstractDao.select(),App.viaFactory,AbstractDao.select,解決:DATAFLOW_FACTORY
at fx.AbstractDao.select(AbstractDao.java:5),fx.AbstractDao.log(),App.viaFactory,AbstractDao.select,AbstractDao.log
at fx.App.viaFactory(App.java:10),fx.Factory.create(),App.viaFactory,Factory.create
```
検証観点: 段2.8 の親探索。`解決:DATAFLOW_FACTORY` の行の先へ**降りている**（`log` の行がある）。

### T02 ローカル変数で受けたファクトリ戻り値
```
at fx.App.viaLocalVar(App.java:15),fx.AbstractDao.select(),App.viaLocalVar,AbstractDao.select,解決:DATAFLOW_FACTORY
```

### T03 委譲するファクトリ（`return create();`）
```
at fx.App.viaDelegate(App.java:19),fx.AbstractDao.select(),App.viaDelegate,AbstractDao.select,解決:DATAFLOW_FACTORY
at fx.Factory.delegate(Factory.java:9),fx.Factory.create(),App.viaDelegate,Factory.delegate,Factory.create
```

### T04 2つの型を返しうるファクトリは絞らない
```
at fx.App.viaEither(App.java:23),fx.AbstractDao.select(),App.viaEither,AbstractDao.select,CHA候補3件（未展開）: 戻り値（ファクトリメソッド等）
at fx.App.viaEither(App.java:23),fx.MemoDao.select(),App.viaEither,MemoDao.select,CHA候補3件（未展開）: 戻り値（ファクトリメソッド等）
at fx.App.viaEither(App.java:23),fx.OrderDao.select(),App.viaEither,OrderDao.select,CHA候補3件（未展開）: 戻り値（ファクトリメソッド等）
```
検証観点: 候補は `Dao.select()` をオーバーライドしている宣言3件（`AbstractDao` / `MemoDao` /
`OrderDao`、この順＝FQN順）。`UserDao` は宣言を持たないので候補に**入らない**。候補行の先へは
降りない（`AbstractDao.log` の行が `App.viaEither` を root に持たない）。

### T05 `Class.forName(文字列).newInstance()` 形式のファクトリ
```
at fx.App.viaByName(App.java:27),fx.OrderDao.select(),App.viaByName,OrderDao.select,解決:DATAFLOW_FACTORY
at fx.App.viaByNameModern(App.java:31),fx.OrderDao.select(),App.viaByNameModern,OrderDao.select,解決:DATAFLOW_FACTORY
at fx.App.viaConstant(App.java:35),fx.OrderDao.select(),App.viaConstant,OrderDao.select,解決:DATAFLOW_FACTORY
```
検証観点: `getDeclaredConstructor()` を挟む形と `static final String` 定数でも解決する。

### T06 実行時に決まる文字列・存在しない型名では絞らない
```
at fx.App.viaRuntimeName(App.java:39),fx.OrderDao.select(),App.viaRuntimeName,OrderDao.select,CHA候補3件（未展開）: 戻り値（ファクトリメソッド等）
at fx.App.viaMissingType(App.java:43),fx.OrderDao.select(),App.viaMissingType,OrderDao.select,CHA候補3件（未展開）: 戻り値（ファクトリメソッド等）
```
（それぞれ `MemoDao.select` / `AbstractDao.select` の候補行も同様に出る）

### T07 同一メソッド内の `new`（段2）
```
at fx.App.viaLocalNew(App.java:49),fx.OrderDao.select(),App.viaLocalNew,OrderDao.select,解決:LOCAL_NEW
```

### T08 ループ内で再代入される変数は候補集合のまま
```
at fx.App.viaLoop(App.java:55),fx.OrderDao.select(),App.viaLoop,OrderDao.select,CHA候補2件（未展開）: ローカル変数
at fx.App.viaLoop(App.java:55),fx.MemoDao.select(),App.viaLoop,MemoDao.select,CHA候補2件（未展開）: ローカル変数
```
検証観点: 3件のCHA候補が `new` された2型に**狭まる**が、1件には**絞らない**。

### T09 引数由来（経路依存）— 同じ呼び出し箇所が経路ごとに違う実装に解決される
```
at fx.App.rootA(App.java:62),fx.App.shared(Dao),App.rootA,App.shared
at fx.App.shared(App.java:70),fx.AbstractDao.select(),App.rootA,App.shared,AbstractDao.select,解決:DATAFLOW_PARAM
at fx.App.passThrough(App.java:75),fx.AbstractDao.select(),App.rootA,App.shared,App.passThrough,AbstractDao.select,解決:DATAFLOW_PARAM
at fx.App.rootB(App.java:66),fx.App.shared(Dao),App.rootB,App.shared
at fx.App.shared(App.java:70),fx.OrderDao.select(),App.rootB,App.shared,OrderDao.select,解決:DATAFLOW_PARAM
at fx.App.passThrough(App.java:75),fx.OrderDao.select(),App.rootB,App.shared,App.passThrough,OrderDao.select,解決:DATAFLOW_PARAM
```
検証観点: `shared(App.java:70)` の行が root ごとに**別の callee** を持つ。2段受け渡し
（`passThrough`）でも伝わる。`methods.csv` の `App.shared(Dao)` は
`unresolvedCalls=1, unresolvedCause=引数（メソッド外から渡される）`（経路非依存の集計では絞れない）。

### T10 コンストラクタ注入されたフィールド
```
at fx.Service.exec(Service.java:11),fx.OrderDao.select(),App.viaService,Service.exec,OrderDao.select,解決:DATAFLOW_FIELD
at fx.Service.helper(Service.java:16),fx.OrderDao.select(),App.viaService,Service.exec,Service.helper,OrderDao.select,解決:DATAFLOW_FIELD
```
検証観点: `this` への呼び出し（`helper`）の先でも解決される。

### T11 setter注入・一部のコンストラクタしか代入しない・親クラスのフィールドは絞らない
```
at fx.SetterService.exec(SetterService.java:11),fx.OrderDao.select(),App.viaSetter,SetterService.exec,OrderDao.select,CHA候補3件（未展開）: フィールド変数
at fx.PartialService.exec(PartialService.java:15),fx.OrderDao.select(),App.viaPartial,PartialService.exec,OrderDao.select,CHA候補3件（未展開）: フィールド変数
at fx.Base.baseExec(Base.java:11),fx.OrderDao.select(),App.viaSub,Base.baseExec,OrderDao.select,CHA候補3件（未展開）: フィールド変数
```
（各3候補のうち1行を示す。`MemoDao` / `AbstractDao` の行も出る）

### T12 匿名クラスが捕捉した変数
```
at fx.App.run(App.java:116),fx.App$1.run(),App.viaCapture,App.run,App$1.run,解決:DATAFLOW_PARAM
at fx.App$1.run(App.java:102),fx.OrderDao.select(),App.viaCapture,App.run,App$1.run,OrderDao.select,解決:DATAFLOW_NEW
at fx.App$2.run(App.java:110),fx.OrderDao.select(),App.viaCaptureParam,App.run,App$2.run,OrderDao.select,CHA候補3件（未展開）: 引数（メソッド外から渡される）
```
検証観点: `new` 由来（`T:`）の捕捉は解決し、囲みメソッドの**引数**（`A:`）の捕捉は解決しない。
匿名クラスの型名は `fx.App$1` / `fx.App$2`（宣言順）。`Runnable` は `java.**` だが、`App$1.run`
はプロジェクトの型なので除外されない。

### T13 循環
```
at fx.App.selfRec(App.java:127),fx.App.selfRec(int),App.cycles,App.selfRec,App.selfRec,[CYCLE]
at fx.App.mutualB(App.java:136),fx.App.mutualA(),App.cycles,App.mutualA,App.mutualB,App.mutualA,[CYCLE]
```
検証観点: 無限ループしない。`[CYCLE]` 行の先へ降りない。`methods.csv` で `selfRec` /
`mutualA` / `mutualB` は `NORMAL`, `reachable=1`。

### T14 ラムダ／匿名クラス／メソッド参照
```
at fx.App.viaLambda(App.java:141),fx.Helper.validate(int),App.viaLambda,Helper.validate
at fx.App.viaLambda(App.java:142),fx.App$3.handle(String),App.viaLambda,App$3.handle,ラムダ/メソッド参照の実装あり（未展開・本体は定義元メソッドに計上）
at fx.App.viaAnonHandler(App.java:151),fx.App$3.handle(String),App.viaAnonHandler,App$3.handle,ラムダ/メソッド参照の実装あり（未展開・本体は定義元メソッドに計上）
at fx.App$3.handle(App.java:148),fx.Helper.validate(int),App.viaAnonHandler,App$3.handle,Helper.validate
at fx.App.viaMethodRef(App.java:156),fx.Repo.save(String),App.viaMethodRef,Repo.save
```
検証観点:
- ラムダ本体の `Helper.validate` は `viaLambda`（囲みメソッド）からの呼び出し（141行）
- `h.handle` は唯一のソース上実装 `App$3.handle` に繋がるが、`解決:SINGLE_IMPL` と**書かない**
- `repo::save` が `Repo.save(String)` への辺になる（156行）
- `NoCtor::new` はコンストラクタなので行にならない。`String[]::new` は辺にならず、
  型解決失敗の件数（1件）にも**含まれない**
- `methods.csv` の `App.viaMethodRef()` は `outDegree=6`（save, NoCtor.<init>, handle, get, apply, Repo.<init>）、
  `unresolvedCalls=3`（handle / get / apply の3件が「ラムダ/メソッド参照の実装あり」）

### T15 オーバーロードと引数型略名の衝突
```
at fx.App.overloads(App.java:167),fx.Repo.save(String),App.overloads,Repo.save
at fx.App.overloads(App.java:168),fx.Repo.save(long),App.overloads,Repo.save
at fx.App.overloads(App.java:169),fx.Repo.save(java.util.List),App.overloads,Repo.save
at fx.App.overloads(App.java:170),fx.Repo.save(other.List),App.overloads,Repo.save
at fx.App.overloads(App.java:171),fx.Repo.save(String[]),App.overloads,Repo.save
at fx.App.overloads(App.java:172),fx.Repo.save(Inner),App.overloads,Repo.save
at fx.App.overloads(App.java:173),"fx.Repo.save(String,long)",App.overloads,Repo.save
```
検証観点: 衝突した `List` の組だけ完全修飾。内部クラス引数は `Inner`。引数2つはクォートされる。
`methods.csv` の `method` 列も同じ表記（`Repo.save(java.util.List)` / `"Repo.save(String,long)"`）。

### T16 enum がCHAの候補に入る
```
at fx.App.viaShape(App.java:178),fx.Circle.area(),App.viaShape,Circle.area,CHA候補2件（未展開）: 引数（メソッド外から渡される）
at fx.App.viaShape(App.java:178),fx.Unit.area(),App.viaShape,Unit.area,CHA候補2件（未展開）: 引数（メソッド外から渡される）
```
検証観点: `Circle` だけを見て `SINGLE_IMPL` に**しない**。

### T17 初期化子の帰属（`<clinit>` と複数の `<init>`）
```
at fx.Sample.<clinit>(Sample.java:4),fx.Sample.compute(),Sample.<clinit>,Sample.compute
at fx.Sample.<clinit>(Sample.java:7),fx.Sample.staticBlockCall(),Sample.<clinit>,Sample.staticBlockCall
at fx.Sample.<init>(Sample.java:5),fx.Sample.init(),Sample.Sample,Sample.init
at fx.Sample.<init>(Sample.java:10),fx.Sample.instanceBlockCall(),Sample.Sample,Sample.instanceBlockCall
at fx.Sample.<init>(Sample.java:5),fx.Sample.init(),Sample.Sample,Sample.Sample,Sample.init
at fx.Sample.<init>(Sample.java:10),fx.Sample.instanceBlockCall(),Sample.Sample,Sample.Sample,Sample.instanceBlockCall
```
検証観点: `init` / `instanceBlockCall` は `Sample()` と `Sample(int)` の2つから呼ばれる
（`Sample(String)` は `this(0)` 委譲なので複製されず、`Sample(String)` → `Sample(int)` → `init`
の経路として出る）。`methods.csv` で `Sample.init()` と `Sample.instanceBlockCall()` は `inDegree=2`、
`Sample.<clinit>()` は `ENTRY_CANDIDATE` で `outDegree=2`。

### T18 enum定数の生成と static 初期化子
```
at fx.Unit.<clinit>(Unit.java:4),fx.Helper.ratio(),Unit.<clinit>,Helper.ratio
at fx.Unit.<clinit>(Unit.java:6),fx.Helper.ratio(),Unit.<clinit>,Helper.ratio
```
検証観点: 定数の引数（4行）と static フィールド（6行）がどちらも `<clinit>` に帰属し、
「メソッド本体の外」の型解決失敗にならない。

### T19 暗黙のデフォルトコンストラクタ・record
```
at fx.App.viaNoCtor(App.java:183),fx.NoCtor.hello(),App.viaNoCtor,NoCtor.hello
at fx.Point.<init>(Point.java:5),fx.Helper.validate(int),App.viaRecord,Point.Point,Helper.validate
```
検証観点: `NoCtor.hello` に `ソースなし` 注記が付かない。record のコンパクトコンストラクタ内の
呼び出しが `Point.Point` を含む経路で出る。

### T20 内部クラスの名前（`$` と `.`）、デフォルトパッケージ
```
at fx.App.viaInner(App.java:191),fx.Outer.Inner.innerMethod(),App.viaInner,Outer.Inner.innerMethod
at fx.Outer$Inner.innerMethod(Outer.java:6),fx.Helper.ratio(),App.viaInner,Outer.Inner.innerMethod,Helper.ratio
at Top.go(Top.java:9),Top.In.m(),Top.go,Top.In.m
at Top$In.m(Top.java:4),fx.Helper.ratio(),Top.go,Top.In.m,Helper.ratio
```
検証観点: `caller` は `Outer$Inner`、`callee` と階層列は `Outer.Inner`。デフォルトパッケージでも
`Top.In.m`（`In.m` になっていない）。

### T21 匿名クラスより後ろの呼び出し
```
at fx.App.afterAnon(App.java:199),fx.Helper.validate(int),App.afterAnon,Helper.validate
```
検証観点: 匿名クラスを抜けた後の呼び出し（199行）が囲みメソッドに帰属する。

### T22 除外パッケージの繋ぎ直し
```
at fx.internal.Bridge.through(Bridge.java:7),fx.Helper.validate(int),App.viaBridge,Helper.validate
```
検証観点: `Bridge.through` の行は出ないが、その先の `Helper.validate` が `App.viaBridge` の
直下として出る。`caller` は実際の呼び出し元 `Bridge.through`。`viaExclude` を root とする行は
**0 行**（`java.**` のみを呼ぶ）。

### T23 import推定と型解決失敗
```
at fx.UsesLib.guess(UsesLib.java:8),org.apache.commons.lang3.StringUtils.isEmpty(),UsesLib.guess,StringUtils.isEmpty,外部ライブラリ（import推定・未検証）
at fx.UsesLib.fail(UsesLib.java:12),call,(型解決失敗),call,型解決に失敗（クラスパス不足・動的呼び出し等の可能性）
```
検証観点: 単一型インポートは推定して残す。ワイルドカードインポートは `(型解決失敗)` 行になり、
ログの件数（1件）と同数出る。

### T24 `methods.csv` の抜粋と行順
```
Shape.area(),fx.Shape,I,src/fx/Shape.java,4,0,0,0,ISOLATED,0,0,
Dao.select(),fx.Dao,I,src/fx/Dao.java,4,0,0,0,ISOLATED,0,0,
OrderDao.select(),fx.OrderDao,C,src/fx/OrderDao.java,4,1,18,0,LEAF,1,0,
AbstractDao.select(),fx.AbstractDao,A,src/fx/AbstractDao.java,4,1,14,1,NORMAL,1,0,
Service.exec(),fx.Service,C,src/fx/Service.java,10,1,1,2,NORMAL,1,1,フィールド変数
App.viaEither(),fx.App,C,src/fx/App.java,22,1,0,2,ENTRY_CANDIDATE,1,1,戻り値（ファクトリメソッド等）
App.viaLambda(),fx.App,C,src/fx/App.java,140,1,0,3,ENTRY_CANDIDATE,1,1,ラムダ/メソッド参照の実装あり
App$3.handle(String),fx.App$3,C,src/fx/App.java,147,1,3,1,NORMAL,1,0,
Unit.<clinit>(),fx.Unit,C,src/fx/Unit.java,3,1,0,3,ENTRY_CANDIDATE,1,0,
Registry.<clinit>(),fx.Registry,C,src/fx/Registry.java,3,1,1,1,NORMAL,1,0,
```
検証観点: インターフェースの抽象メソッドは `hasBody=0`、入次数は解決後の実装側に付く
（`OrderDao.select()` の `inDegree=18`）。`Registry.<clinit>()` はリフレクション経由で
呼ばれているので `NORMAL`（起点候補ではない）。ログの集計は
`メソッド=94 起点候補=43 孤立=5 末端=21 未到達=3 未解決の呼び出しを含む=18（コンストラクタ 37 個は出力対象外）`。

行順はソースの並び。ヘッダーの次の行から順に
`Top.In.m()` → `Top.go()` → `AbstractDao.select()` → `AbstractDao.log()` → `App.viaFactory()` → …
と続き、`fx.App` の内部の匿名クラス（`App$1.run()` 等）はそのファイルの宣言行の位置に並ぶ。
最後の行は `Bridge.through()`（`src/fx/internal/Bridge.java`）。

## 3.4 設定を変えた実行

| ケース | 変更 | 期待値 |
|---|---|---|
| T25 キャッシュ再利用 | 同じ設定で2回目 | ログ `再利用=28 新規解析=0 失敗=0`。`call-hierarchy.csv` が1回目と**バイト単位で一致** |
| T26 差分再解析 | `App.java` を touch して3回目 | ログ `再利用=27 新規解析=1`。出力は1回目と一致 |
| T27 深さ制限 | `max.depth=2` | `at fx.App.shared(App.java:70),fx.AbstractDao.select(),App.rootA,App.shared,AbstractDao.select,深さ制限(2)のため打ち切り / 解決:DATAFLOW_PARAM`（前半と後半の注記が ` / ` で連結） |
| T28 起点指定 | `entry.packages=fx.App#rootB,fx.App#rootA` | ログ `エントリポイント数: 2`。出力は T09 の10行＋T23 の `(型解決失敗)` 行＝ヘッダー含め 11 行。**`rootA` の行が `rootB` より先**（設定に書いた順ではなくソースの宣言順） |
| T29 行数上限 | `max.rows=3` | ログ `[WARN] 出力行数の上限(3)に達したため打ち切りました`。階層の行は3行で止まる |
| T30 データフロー無効 | `dataflow.enabled=false` | T01 が `CHA候補3件（未展開）: 戻り値（ファクトリメソッド等）` の3行に、T09 が `CHA候補3件（未展開）: 引数（メソッド外から渡される）` になる。T33〜T39 のリフレクション行は消え、T40 が `CHA候補3件（未展開）: レシーバ不明` の3行になる。行数は増える（134行） |
| T31 準拠レベル範囲外 | `source.level=99` | 起動時に `IllegalArgumentException`。指定できる値の一覧を含む |
| T32 準拠レベルの丸め | `source.level=1.4` | ログ `ソースレベル: 1.8（source.level=1.4 の指定による）` と `※ source.level=1.4 はこのJDTでは扱えないため 1.8 として解析します。`。既存キャッシュを破棄した旨が出る |
| T41 依存先の変更による再解析 | キャッシュがある状態で `Dao.java` の末尾に空行とコメント行を追加して実行 | ログ `再利用=18 新規解析=10（うち依存先の変更による再解析=9） 失敗=0`（`Dao` を参照する9ファイルが再解析される）。両CSVは変更前と**バイト単位で一致**（CHA候補の行順も変わらない） |

## 3.4a リフレクション

### T33 `Class.forName(リテラル).getMethod(名前, クラスリテラル).invoke(...)`
```
at fx.App.viaReflectForName(App.java:214),fx.Repo.save(long),App.viaReflectForName,Repo.save,解決:REFLECTION
```

### T34 クラスリテラル・`getClass()` から
```
at fx.App.viaReflectClassLiteral(App.java:218),fx.Repo.save(String),App.viaReflectClassLiteral,Repo.save,解決:REFLECTION
at fx.App.viaReflectGetClass(App.java:223),fx.Repo.save(String),App.viaReflectGetClass,Repo.save,解決:REFLECTION
```
検証観点: `repo.getClass()` はローカル変数 `repo` の出所（`T:fx.Repo`）から決まる。

### T35 メソッド名と受け手が引数で渡ってくる形（経路依存）
```
at fx.App.viaReflectNameArg(App.java:227),"fx.App.invokeByName(Object,String)",App.viaReflectNameArg,App.invokeByName
at fx.App.invokeByName(App.java:231),fx.Repo.save(long),App.viaReflectNameArg,App.invokeByName,Repo.save,解決:REFLECTION
```
検証観点: `invokeByName` 単体では決まらない（`target` も `name` も引数）。`viaReflectNameArg`
から渡された `new Repo()` と `"save"` を経路で持ち回って解決する。

### T36 引数型が変数のときは名前で照合し、候補を列挙する
```
at fx.App.viaReflectUnknownTypes(App.java:236),fx.Repo.save(String),App.viaReflectUnknownTypes,Repo.save,リフレクション候補7件（未展開）: 引数型が不明なため名前で照合
at fx.App.viaReflectUnknownTypes(App.java:236),fx.Repo.save(long),App.viaReflectUnknownTypes,Repo.save,リフレクション候補7件（未展開）: 引数型が不明なため名前で照合
at fx.App.viaReflectUnknownTypes(App.java:236),"fx.Repo.save(String,long)",App.viaReflectUnknownTypes,Repo.save,リフレクション候補7件（未展開）: 引数型が不明なため名前で照合
```
（`Repo.save` の7オーバーロード全部が候補行になる。`methods.csv` の `App.viaReflectUnknownTypes()`
は `unresolvedCalls=1, unresolvedCause=戻り値（ファクトリメソッド等）`）

### T37 `Class.forName` によるクラス初期化
```
at fx.App.viaReflectInit(App.java:240),fx.Registry.<clinit>(),App.viaReflectInit,Registry.<clinit>,解決:REFLECTION_INIT
at fx.Registry.<clinit>(Registry.java:5),fx.Helper.ratio(),App.viaReflectInit,Registry.<clinit>,Helper.ratio
```
検証観点: `<clinit>` の先へ降りる。`Registry.<clinit>` は入次数1になり起点候補から外れる（T24）。

### T38 `getDeclaredConstructor().newInstance()` で生成した型が以降の呼び出しに使われる
```
at fx.App.viaReflectCtor(App.java:244),fx.OrderDao.select(),App.viaReflectCtor,OrderDao.select,解決:DATAFLOW_NEW
```

### T39 実行時に決まるクラス名は解決しない
`App.viaReflectRuntime` を root とする行は **0 行**（`invoke` は `java.**` で除外され、解決も
されないので何も出ない）。

### T40 データフロー無効時のリフレクション
`dataflow.enabled=false` にすると T33〜T38 の `解決:REFLECTION*` 行と T36 の候補行は消え、
T38 は `CHA候補3件（未展開）: レシーバ不明` の3行になる（T30）。

## 3.5 自己解析

ツール自身のソースを、JDTのjarを `library.folders` に指定して解析する。

- 型解決の失敗が **0件**
- `library.folders` を空にすると失敗件数が0でなくなり、警告が出て、`(型解決失敗)` 行が同数出る
- 依存jarフォルダを1つ指定するだけで、展開後のjar数がログに出る
- 出力の `caller` 列をEclipseの「Javaスタック・トレース・コンソール」に貼るとソースへジャンプできる
