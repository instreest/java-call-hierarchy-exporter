# java-call-hierarchy-exporter

Javaプロジェクト全体のメソッド呼び出し階層を一括で抽出してCSVファイルに出力するツールです。

- 使い方・出力形式 … このファイル
- 設定項目 … [config/config.properties](config/config.properties)（コメントに全項目の説明）

---

## Quick start

### 1. 設定ファイルを編集する

`config/config.properties` の **`project.root`** **`source.folders`** **`library.folders`** **`source.encoding`** を書き換えます。  

### 2. 実行する

#### JBangによる実行

```bat
.\jbang src/CallHierarchyExporter.java config/config.properties
```

[JBang](https://www.jbang.dev) 本体・依存jar・このツールが必要とするJDK 17 は、
環境になければ初回実行時に自動で取得されます（`%userprofile%/.jbang/`配下に保存）。

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
出力先は設定ファイルからの相対パスなので、`config/config.properties` を指定した場合は
`config/output/` の下に出ます。

#### `call-hierarchy.csv` — 呼び出し元が無いメソッドを起点にした呼び出し階層

呼び出し元、呼び出し先、起点メソッド、呼び出し階層（複数）を1行としたCSVです。
呼び出し先ごとに1行出力します。フィルタすることで起点メソッドと呼び出し階層が一覧化できます。
呼び出し元はEclipseの「Javaスタック・トレース・コンソール」に貼り付けると、
`(ファイル:行数)` の部分がハイパーリンクになり、ソースコードへ飛べます。（後述）

```csv
caller,callee,root,call-hierarchy
at jp.co.example.action.OrderAction.execute(OrderAction.java:50),jp.co.example.service.OrderService.findOrder(String),OrderAction.execute,OrderService.findOrder
at jp.co.example.service.OrderService.findOrder(OrderService.java:25),jp.co.example.dao.OrderDaoImpl.selectById(long),OrderAction.execute,OrderService.findOrder,OrderDaoImpl.selectById
```

#### `methods.csv` — ソース上の全メソッドとその呼び出し状況

```csv
method,declaringType,typeKind,file,line,hasBody,inDegree,outDegree,role,reachable,unresolvedCalls,unresolvedCause
OrderAction.execute(),jp.co.example.action.OrderAction,C,OrderAction.java,45,1,0,1,ENTRY_CANDIDATE,1,0,
OrderService.findOrder(String),jp.co.example.service.OrderService,C,OrderService.java,20,1,1,1,NORMAL,1,1,フィールド変数
OrderDao.selectById(long),jp.co.example.dao.OrderDao,I,OrderDao.java,8,0,0,0,ISOLATED,0,0,
OrderDaoImpl.selectById(long),jp.co.example.dao.OrderDaoImpl,C,OrderDaoImpl.java,15,1,1,0,LEAF,1,0,
```

### **Eclipseでのソースコードジャンプ**
`call-hierarchy.csv` の行をコピーし、Eclipseの「Javaスタック・トレース・コンソール」に貼り付けると、
`(ファイル:行数)` の部分がハイパーリンクになり、ソースコードへ飛べます。

1. メニューから ウィンドウ(Window) ＞ ビューの表示(Show View) ＞ コンソール(Console) を選択
2. コンソールビュー右上（ツールバー）の「コンソールのオープン(Open Console)」ボタン
   （プラスの付いたモニターのアイコン）の横の「▼」をクリックし、
   「Javaスタック・トレース・コンソール(Java Stack Trace Console)」を選択
3. `call-hierarchy.csv`のテキストをそのコンソールに貼り付ける

---

## キャッシュファイル設計

大規模なコードベースでも `OutOfMemoryError` にならないよう、3点で対策しています。

1. **解析結果をヒープに溜めない** — 1ファイル解析するたびにキャッシュへ書き出して破棄
2. **エッジをオブジェクトで持たない** — メソッドをintのIDに内部化し、CSR形式のプリミティブ配列で保持
3. **ツリーを組み立てない** — 深さ優先で辿りながら1行ずつ書き出す

各フェーズの終わりにヒープ使用量が出るので、`-Xmx` の目安に使えます。
最大になるのはおそらくフェーズ2です。



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
完全修飾に戻します（`fn.Dao.save(java.util.List)`）。識別できることを優先するためです。

なお引数が2つ以上あると `callee` にカンマが入るため、その値はCSVの引用符で
囲まれて出ます（`"...findOrder(String,long)"`）。Excelや標準的なCSVパーサでは
そのまま1列として読めますが、`cut -d,` のような素朴な処理では分割されます。

**コンストラクタの呼び出し自体は行になりません。** `new` したこと自体より
「そのコンストラクタの中で何を呼んでいるか」が知りたいためです。
経路には残るので、コンストラクタ内からの呼び出しは
`call-hierarchy` 列にコンストラクタを含んだ形で出力されます。
`callee` や `call-hierarchy` でのコンストラクタの表示は `<init>` ではなく
**クラス名**です（`Sample.Sample`）。ソースに宣言が無い暗黙のデフォルト
コンストラクタも、補完される名前（＝クラス名）で出ます。
`caller` 列だけはEclipseのスタックトレース形式に合わせるため `<init>` のままです。

```csv
caller,callee,root,call-hierarchy
at jp.co.example.Sample.<init>(Sample.java:3),jp.co.example.Sample.init(),Sample.Sample,Sample.init
```

注記が付く場合は `call-hierarchy` の**最後の要素**として出ます。列を間に挟むと
可変長の階層が途中で切れてしまうためです。

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
| `型解決に失敗（…）` | 呼び出し先の型を特定できなかった行（後述） |
| `被参照:EXACT` 等 | 被参照スキャンの行（後述） |

絞り込めなかったときの「理由」は、**レシーバ（呼び出し先のインスタンス）がどこから
来たか**です。次にどこを調べれば具象クラスが分かるかが変わるため、この単位で出します。

| 理由 | 意味 | 次に見る場所 |
|---|---|---|
| `戻り値（ファクトリメソッド等）` | `getService().run()` のように、別のメソッドの戻り値に対する呼び出し | そのファクトリメソッドが何を `return` しているか |
| `引数（メソッド外から渡される）` | 引数で受け取ったインスタンスに対する呼び出し | このメソッドの呼び出し元が何を渡しているか |
| `フィールド変数` | フィールドに対する呼び出し。コンストラクタで確定しないもの（setter/DI設定で入る等） | フィールドの代入箇所・DI設定 |
| `ローカル変数` | ローカル変数に対する呼び出し。同一メソッド内の `new` では絞れなかった場合 | その変数への代入箇所 |
| `自クラス（this）` | レシーバ省略。自分自身かサブクラスのオーバーライド | サブクラスのオーバーライド |
| `型名（static）` | `static` 呼び出し。通常はここまで来ない | — |
| `レシーバ不明` | 上記のいずれにも当てはまらない式 | 呼び出し箇所のソース |



### `methods.csv` — ソース上の全メソッドとその呼び出し状況

| role | 意味 |
|---|---|
| `ENTRY_CANDIDATE` | 呼び出し元が無い。画面入口・デッドコード・テスト・リフレクション経由が混ざる |
| `ISOLATED` | 呼び出し元も呼び出し先も無い。デッドコードの疑いが濃い |
| `LEAF` | 呼び出し先が無い |
| `NORMAL` | 上記以外 |

「よく呼ばれている共通処理」を探したいときは、`inDegree` 列でソート・フィルタしてください。

`call-hierarchy.csv` と揃えて、**コンストラクタ（`<init>`）は出力しません**。

| 列 | 内容 |
|---|---|
| `unresolvedCalls` | このメソッドの中で、具象クラスを1つに絞れなかった呼び出しの件数 |
| `unresolvedCause` | その理由（上の「理由」表と同じ。複数ある場合は `;` 区切り） |

`unresolvedCalls` が0でないメソッドは、そのメソッド単体では具象クラスを絞れていません。
**呼び出し階層を追う前にこの列で穴のあるメソッドを把握しておくと、
「出ていないのは呼んでいないからなのか、絞れなかったからなのか」を取り違えずに済みます。**

ただし理由が `引数（メソッド外から渡される）` や `フィールド変数` の場合は、
`call-hierarchy.csv` 側では**解決できていることがあります**。
引数とフィールドの追跡は起点からの経路ごとに行うのに対し、
この列はメソッド単体で見た結果だからです
（誰が何を渡すか分からないメソッド、という意味になります）。

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
| `callee` | ソースに書かれていた式（メソッド名）。型が特定できていないのでクラス名も引数も付かない |
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
NightJob,jp.co.example.service.OrderService.findOrder(String),team-b-batch.jar,OrderService.findOrder,被参照:EXACT
NightJob,jp.co.example.service.OrderService.OrderService(),team-b-batch.jar,OrderService.OrderService,被参照:IMPLICIT_CTOR
```

| 列 | 内容 |
|---|---|
| `caller` | 参照している側のクラス。行番号もメソッドも分からないためスタックトレース形式にはならない |
| `callee` | 参照されている自分のメソッド。呼び出し階層の行と同じ表記 |
| `root` | 参照元のjar名（起点メソッドが無いため、代わりにjar名を入れる） |
| `call-hierarchy` | 短縮表記のメソッド名と、照合の種類を表す注記 |

**指定したフォルダに自プロジェクトのjar（自分のビルド成果物）が混ざっていても、
それは「他リポジトリからの被参照」ではないので読み飛ばします。**
除外した件数は実行ログに出ます。`build/libs` や `dist` を丸ごと指定しても、
自分から自分への呼び出しが被参照として出ることはありません。

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
| 4 | `DATAFLOW_NEW` / `DATAFLOW_FACTORY` | `new` された型、またはファクトリメソッドの戻り値から特定（後述） |
| — | `DATAFLOW_PARAM` | 呼び出し元から渡された引数から特定（後述。経路ごとに判定するため段の外） |
| — | `DATAFLOW_FIELD` | コンストラクタ注入されたフィールドから特定（同上） |
| 5 | `CHA` | 候補が複数のまま（低確度） |

**候補数は「サブクラス数」ではなく「そのメソッドをオーバーライドしている宣言の数」です。**
サブクラスが多くても、オーバーライドが1件なら候補は1件のままになります。

段5（`CHA`）になった呼び出しは、候補を列挙するだけでその先へは降りません
（候補数^深さで爆発するため）。`call-hierarchy` 列の最後に
`CHA候補N件（未展開）: 理由` と付きます。
