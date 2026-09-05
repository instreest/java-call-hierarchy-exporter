# java-call-hierarchy-exporter

Javaプロジェクト全体のメソッド呼び出し階層を一括で抽出してCSVファイルに出力するツールです。

> **English:** Exports the whole-project method call hierarchy of a Java code base to CSV,
> using the Eclipse JDT compiler without launching Eclipse. Run
> `jbangw/jbang src/CallHierarchyExporter.java config/config.properties` (the first run downloads a JDK
> and the JDT jars), or compile against JDT jars copied from an Eclipse installation for offline
> use. Apache-2.0. Documentation is in Japanese.

- 使い方・出力形式 … このファイル
- 設定項目 … [config/config.properties](config/config.properties)（コメントに全項目の説明）

---

## Quick start

### 1. 設定ファイルを編集する

`config/config.properties` の **`project.root`** **`source.folders`** **`library.folders`** **`source.encoding`** を書き換えます。  

### 2. 実行する

#### JBangによる実行

JBang のラッパースクリプトを `jbangw/` に同梱しているので、JBang のインストールは不要です。

```bat
rem Windows（コマンドプロンプト）
.\jbangw\jbang.cmd src\CallHierarchyExporter.java config\config.properties
```

```bash
# Linux / macOS / Git Bash
./jbangw/jbang src/CallHierarchyExporter.java config/config.properties
```

このツールが必要とするJDK・依存jarは、実行環境になければ初回実行時に自動で取得されます（`%userprofile%/.jbang/`配下に保存）。

#### Pleiades/Eclipse環境（閉域ネットワーク等）

Eclipse(Pleiades)がインストールされていれば、そこに含まれるJDT Core一式から、
実行に必要なjarを `lib` フォルダに集めて使います。
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

### 出力されるファイル

| ファイル | 既定出力先 |
|---|---|
| 呼び出し階層リスト | `./output/call-hierarchy.csv` |
| メソッド全体リスト | `./output/methods.csv` |

出力はUTF-8（BOM付き）のCSVファイルなのでExcelで開けます。
出力先は設定ファイルからの相対パスで、例えば `config/config.properties` を指定した場合は
`config/output/` の下に出ます。

#### `call-hierarchy.csv` — 呼び出し元が無いメソッドを起点にした呼び出し階層

呼び出し元、呼び出し先、起点メソッド、呼び出し階層（複数）を出力したCSVファイルです。
呼び出し元ごとに1行出力します。フィルタすることで起点メソッドと呼び出し階層が一覧化できます。
出力ソート順は、rootのソースフォルダ → rootの完全修飾クラス名 → コード呼び出しの順序です。

```csv
caller,callee,root,call-hierarchy
at jp.co.example.action.OrderAction.execute(OrderAction.java:50),jp.co.example.service.OrderService.findOrder(String),OrderAction.execute,OrderService.findOrder
at jp.co.example.service.OrderService.findOrder(OrderService.java:25),jp.co.example.dao.OrderDaoImpl.selectById(long),OrderAction.execute,OrderService.findOrder,OrderDaoImpl.selectById
```

#### `methods.csv` — ソース上の全メソッドとその呼び出し状況

各クラスの宣言メソッドとその情報を一覧出力したCSVファイルです。
出力ソート順は、ソースフォルダ → 完全修飾クラス名 → 宣言行順の順序です。

```csv
method,declaringType,typeKind,file,line,hasBody,inDegree,outDegree,role,reachable,unresolvedCalls,unresolvedCause
OrderAction.execute(),jp.co.example.action.OrderAction,C,OrderAction.java,45,1,0,1,ENTRY_CANDIDATE,1,0,
OrderService.findOrder(String),jp.co.example.service.OrderService,C,OrderService.java,20,1,1,1,NORMAL,1,1,フィールド変数
OrderDao.selectById(long),jp.co.example.dao.OrderDao,I,OrderDao.java,8,0,0,0,ISOLATED,0,0,
OrderDaoImpl.selectById(long),jp.co.example.dao.OrderDaoImpl,C,OrderDaoImpl.java,15,1,1,0,LEAF,1,0,
```

---

## キャッシュファイル設計

大規模なコードベースでも `OutOfMemoryError` にならないよう、3点で対策しています。

1. **解析結果をヒープに溜めない** — 1ファイル解析するたびにキャッシュへ書き出して破棄
2. **エッジをオブジェクトで持たない** — メソッドをintのIDに内部化し、CSR形式のプリミティブ配列で保持
3. **ツリーを組み立てない** — 深さ優先で辿りながら1行ずつ書き出す

### キャッシュに入れるもの

キャッシュには「ASTから分かった事実」だけを入れ、判断は読む側で行います。
事実とは、宣言と修飾子、呼び出し箇所、フィールドへの代入、値の出所など、
設定や出力形式に依存しない情報です。静的束縛かどうか、コンストラクタ注入と言い切れるか、
import からの推定を呼び出し先として採用するか、といった判断はキャッシュを読む側で行うため、
出力や解決の方針を変えてもキャッシュを作り直さずに済みます。
キャッシュの版を上げるのは、事実の意味・列・収集範囲が変わったときだけです。

差分更新では、更新時刻とサイズが一致するファイルでも、そのファイルが参照している型
（キャッシュの `I` 行）を宣言するファイルが変わっていれば解析し直します。
呼び出し先やフィールドの所有型は他のファイルのバインディング解決に依存するためです。
フィールドの参照箇所（読み取り・書き込み、他の型のフィールドも含む）は `A` 行に残ります。
行の種別と列の意味は [src/jche/cache/CacheFormat.java](src/jche/cache/CacheFormat.java) のクラスコメントにあります。

### 依存 jar を変えたとき

キャッシュには解析時の依存 jar（パス・サイズ・更新時刻・含まれるパッケージ。`L` 行）も残します。
次回の実行で jar が追加・差し替え・削除されていれば、その jar のパッケージの型を参照している
ファイルと、前回型解決に失敗していたファイル（`F` 行のエラー数、`U` 行）だけを解析し直します。
「型解決できなかった呼び出しが N 件あります」と出たときに `library.folders` へ jar を足せば、
キャッシュを消さなくても次の実行で反映されます。
実行する JDK を変えたときはキャッシュ全体を作り直します（JDT は実行中の JVM の標準クラスも
解析対象のクラスパスに含めるため）。
設計上の判断と限界は [docs/cache-dependency-jars-qa.md](docs/cache-dependency-jars-qa.md) にまとめています。

## 出力ファイル

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

`callee` はこの1列でパッケージとオーバーロードを見分けられる形にしてあります。
引数の型はパッケージを落とした略名（`java.lang.String` → `String`）ですが、
略した結果 `java.util.List` と `other.List` のように**別物が同じ表記になる組だけ**は
完全修飾に戻します（`fn.Dao.save(java.util.List)`）。

なお引数が2つ以上あると `callee` にカンマが入るため、その値はCSVの引用符で
囲まれて出ます（`"...findOrder(String,long)"`）。Excelや標準的なCSVパーサでは
そのまま1列として読めますが、`cut -d,` のような素朴な処理では分割されます。

コンストラクタの呼び出し自体は行になりません。
コンストラクタ内からのメソッド呼び出しは行として出力されます。
コンストラクタ名はEclipseのスタックトレース形式に合わせるため `<init>` で出力されます。

```csv
caller,callee,root,call-hierarchy
at jp.co.example.Sample.<init>(Sample.java:3),jp.co.example.Sample.init(),Sample.Sample,Sample.init
```

#### 注記

注記が付く場合は `call-hierarchy` の**最後の要素**として出ます。

| 注記 | 意味 |
|---|---|
| `[CYCLE]` | この経路上で既に呼んでいるメソッドに戻る呼び出し。ここで打ち切る |
| `深さ制限(N)のため打ち切り` | `max.depth` に達した |
| `CHA候補N件（未展開）: 理由` | 実装を1つに絞れなかった。候補は1件ずつ行になるが、その先へは降りない（候補数^深さで爆発するため）。理由は下表 |
| `実装なし（宣言のまま）: 理由` | 本体を持つ実装がソース上に1つも無い。宣言のまま出しているだけ |
| `ラムダ/メソッド参照の実装あり（未展開・本体は定義元メソッドに計上）` | その関数型インターフェースをラムダかメソッド参照も実装している。展開できないので候補には数えていない |
| `ソースなし（展開不可）` | 呼び出し先がjar内などでソースが無く、そこから先を辿れない |
| `外部ライブラリ（import推定・未検証）` | クラスパス不足で型解決できず、`import` 文から型名を推定した |
| `解決:DATAFLOW_NEW` | `new` された具象型から特定した（捕捉された変数を含む） |
| `解決:DATAFLOW_FACTORY` | ファクトリメソッドの戻り値から具象クラスを特定した |
| `解決:DATAFLOW_PARAM` | 呼び出し元から渡された引数を経路上で追跡して特定した |
| `解決:DATAFLOW_FIELD` | コンストラクタ注入されたフィールドを経路上で追跡して特定した |
| `解決:ラベル` | インターフェース等から具象クラスに解決した（[具象クラスの解決](#具象クラスの解決)参照） |
| `解決:REFLECTION` | `Method.invoke` / `newInstance` を、リフレクションで指定されたメソッド・コンストラクタに解決した（[リフレクション](#リフレクション)参照） |
| `解決:REFLECTION_INIT` | `Class.forName` によるクラス初期化。そのクラスの static 初期化子（`<clinit>`）へ繋ぐ |
| `リフレクション候補N件（未展開）: 引数型が不明なため名前で照合` | `getMethod` の引数型（クラスリテラル）が揃わず、同名のメソッドを候補にした |
| `型解決に失敗（…）` | 呼び出し先の型を特定できなかった行（後述） |
| `被参照:EXACT` 等 | 被参照スキャンの行（後述） |

#### **Eclipseでのソースコードジャンプ**
`call-hierarchy.csv` の行をコピーし、Eclipseの「Javaスタック・トレース・コンソール」に貼り付けると、
`(ファイル:行数)` の部分がハイパーリンクになり、ソースコードへ飛べます。

1. メニューから ウィンドウ(Window) ＞ ビューの表示(Show View) ＞ コンソール(Console) を選択
2. コンソールビュー右上（ツールバー）の「コンソールのオープン(Open Console)」ボタン
   （プラスの付いたモニターのアイコン）の横の「▼」をクリックし、
   「Javaスタック・トレース・コンソール(Java Stack Trace Console)」を選択
3. `call-hierarchy.csv`のテキストをそのコンソールに貼り付ける

### `methods.csv` — ソース上の全メソッドとその呼び出し状況

| 列 | 内容 |
|---|---|
| `unresolvedCalls` | このメソッドの中で、具象クラスを1つに絞れなかった呼び出しの件数 |
| `unresolvedCause` | その理由（上の「理由」表と同じ。複数ある場合は `;` 区切り） |

| role | 意味 |
|---|---|
| `ENTRY_CANDIDATE` | 呼び出し元が無い。画面入口・デッドコード・テスト・リフレクション経由が混ざる |
| `ISOLATED` | 呼び出し元も呼び出し先も無い。デッドコードの疑いが濃い |
| `LEAF` | 呼び出し先が無い |
| `NORMAL` | 上記以外 |

行はソースの並び順（ソースフォルダ順 → ファイルの相対パス順 → 宣言行順）で出ます。
「よく呼ばれている共通処理」を探したいときは、`inDegree` 列でソート・フィルタしてください。
コンストラクタ（`<init>`）は出力しません。

### jarファイルからの被参照メソッド

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
| 4 | `DATAFLOW_NEW` / `DATAFLOW_FACTORY` | `new` された型、またはファクトリメソッドの戻り値から特定（後述） |
| — | `DATAFLOW_PARAM` | 呼び出し元から渡された引数から特定（後述。経路ごとに判定するため段の外） |
| — | `DATAFLOW_FIELD` | コンストラクタ注入されたフィールドから特定（同上） |
| 5 | `CHA` | 候補が複数のまま（低確度） |

### リフレクション

`Class.forName` / `X.class` / `obj.getClass()` → `getMethod` / `getDeclaredMethod` → `Method.invoke`、
および `getConstructor` → `newInstance` の連鎖を、キャッシュに記録した出所（レシーバの連鎖と
実引数のリテラル）から辿り、実際に動くメソッドへ解決します。

| 書き方 | 解決 |
|---|---|
| `Class.forName("a.B").getMethod("run", long.class).invoke(obj, 1L)` | `a.B.run(long)` |
| `B.class.getMethod("run")`、`obj.getClass().getMethod("run")`（obj の具象型が分かるとき） | `B.run()` |
| クラス名・メソッド名が `static final` 定数、または呼び出し元からリテラルで渡された引数 | 同上（経路ごとに解決） |
| `getMethod("run", types)` のように引数型が変数 | 同名のメソッドを候補として列挙（未展開） |
| `Class.forName("a.B")` | `a.B` の static 初期化子（あれば） |
| `Class.forName("a.B").getDeclaredConstructor().newInstance()` | `a.B` のコンストラクタ。生成された型は以降の呼び出しでも使われる |

解決できないもの: 設定ファイル・DB・アノテーションから来る名前、`Method` や `Class` を
フィールドや別メソッドの引数で受け渡す形。これらは `Method.invoke` のまま「ソースなし」の行になります。

---

## ソースの構成

`src/CallHierarchyExporter.java` がエントリポイント（JBang の指示行と `main`）で、
本体は `src/jche/` 配下のパッケージに分かれています。パッケージは処理のフェーズに対応します。

| パッケージ | 役割 | 主なクラス |
|---|---|---|
| `jche.config` | 設定ファイルとプロジェクト構成の読み取り | `Config`, `ProjectLayout`, `PackagePattern` |
| `jche.cache` | キャッシュの形式と「事実」のレコード。JDT に依存しない | `CacheFormat`, `Origin`, `MethodRef`, `*Fact` |
| `jche.analysis` | フェーズ1: AST を走査して事実を集め、キャッシュを差分更新する | `CacheUpdater`, `CallEdgeExtractor`, `FactVisitor`, `OriginTracker` |
| `jche.graph` | フェーズ2: CSR 形式の呼び出しグラフと、具象クラスの解決 | `CallGraphBuilder`, `CallGraph`, `CallResolver`, `DataflowResolver` |
| `jche.report` | フェーズ3: 深さ優先で辿りながら CSV を 1 行ずつ書く | `StreamingTreeWalker`, `CallHierarchyCsvWriter`, `InventoryReport` |
| `jche.external` | 外部 jar の定数プールから被参照を拾う | `ExternalUsageScanner`, `ClassFileRefs` |
| `jche.extension` | 利用者がプロジェクト固有の解決手法を差し込む拡張ポイント | `CallSiteHintCollector`, `TypeCandidateProvider` |
| `jche.util` | ログと進捗表示 | `Log`, `Progress` |

読む順番は `CallHierarchyExporter.main` → `jche.analysis.CacheUpdater` → `jche.graph.CallGraphBuilder`
→ `jche.graph.CallResolver` → `jche.report.StreamingTreeWalker` が処理の流れどおりです。
キャッシュに何を入れ、何を入れないかの原則は `jche.cache.CacheFormat` のクラスコメントにあります。

---

## テスト

`samples/demo/` の小さなプロジェクトを解析し、出力 CSV が `test/regression/*/expected*/` と
一致することを確認する回帰テストがあります。全体モード（`whole`）と `entry.packages` 指定（`entry`）の
2 ケースを、それぞれキャッシュ無し・キャッシュ再利用の 2 回ずつ実行します。
`jarchange` ケースは、依存 jar 無し → 有り → 無し の順に同じキャッシュで実行し、
jar の追加・削除が影響するファイルの再解析だけで出力に反映されることを確認します。

```bash
bash test/regression/run.sh        # Linux / macOS / Git Bash（jbang 経由で実行）
test\regression\run.cmd            # Windows のコマンドプロンプト
```

GitHub Actions（`.github/workflows/smoke.yml`）でも push ごとに、`-Xlint:all -Werror` での
コンパイルとこの回帰テストを実行します。

出力の形式や解決の挙動を意図して変えたときは、`test/regression/*/output/` の差分を確認したうえで
`expected*/` にコピーして更新してください。期待出力はツールと同じ JDK 25 で生成するのが原則です
（JDT は実行中の JVM のブートクラスパスを解析対象に含めるため、JDK の版で結果が変わりうる）。
