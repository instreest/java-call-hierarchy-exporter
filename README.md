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

#### 必要なJava実行環境

このツールを**動かす**のに必要なJavaのバージョンは、使うJDT Coreの版で決まります。
解析**対象**のJavaバージョンとは別物です（対象側は `source.level` で指定します）。

| JDT Core | 実行に必要なJRE | 解析できるソースの上限 |
|---|---|---|
| 3.46.0（既定） | **17以上** | Java 26 |
| 3.33.0 | 11以上 | Java 19 |

手元のJDKが古い場合は、下の `set JAVA_HOME=` と、Gradleを使うなら
`gradle -PjdtVersion=3.33.0 …` を合わせて古い版に下げてください。

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
caller,callee,calleeSignature,root,call-hierarchy
at jp.co.example.action.OrderAction.execute(OrderAction.java:50),OrderService.findOrder,jp.co.example.service.OrderService.findOrder(java.lang.String),OrderAction.execute,OrderService.findOrder
at jp.co.example.service.OrderService.findOrder(OrderService.java:25),OrderDaoImpl.selectById,jp.co.example.dao.OrderDaoImpl.selectById(long),OrderAction.execute,OrderService.findOrder,OrderDaoImpl.selectById
```

#### `methods.csv` — ソース上の全メソッドとその呼び出し状況

```csv
method,declaringType,typeKind,file,line,hasBody,inDegree,outDegree,role,reachable,unresolvedCalls,unresolvedCause
OrderAction.execute,jp.co.example.action.OrderAction,C,OrderAction.java,45,1,0,1,ENTRY_CANDIDATE,1,0,
OrderService.findOrder,jp.co.example.service.OrderService,C,OrderService.java,20,1,1,1,NORMAL,1,1,フィールド変数
OrderDao.selectById,jp.co.example.dao.OrderDao,I,OrderDao.java,8,0,0,0,ISOLATED,0,0,
OrderDaoImpl.selectById,jp.co.example.dao.OrderDaoImpl,C,OrderDaoImpl.java,15,1,1,0,LEAF,1,0,
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
| `project.root` / `cache.folders` / `output.csv` / `methods.csv` | 設定ファイルが置かれているディレクトリ |
| `source.folders` / `library.folders` / `external.library.folders` | `project.root` |

`library.folders` が不足していると型解決に失敗し、その呼び出しが
`call-hierarchy.csv` から丸ごと抜け落ちます。失敗件数は実行ログに出るので、
**初回は必ずこの件数を確認してください**（[既知の限界](#既知の限界)参照）。

### 2. 実行する

処理の進捗は標準出力に出ます。件数単位の進捗が出るのはフェーズ1（ソース解析）だけです。
以降のフェーズは1件あたりが十分速いため、フェーズごとの集計だけを出します。

```
[00:00.033s] === フェーズ1/3: ソース解析 ===
[00:00.037s] Javaファイル数: 900
[00:00.047s] ソース解析 500/900 （直近500件: 1s）
[00:00.057s] ソース解析 900/900 （直近400件: 1s）
[00:00.457s] ソース解析: 再利用=900 新規解析=0 失敗=0
[00:00.457s] === フェーズ1完了: [heap] 使用 45MB / 上限 2048MB
```

起動時のログには、**どのJavaバージョンとして解析するか**も出ます。
`source.level` を指定していなければ、クラスパスに入っているJDTが対応する最大値です。

```
[00:00.026s] ソース文字コード: MS932
[00:00.031s] ソースレベル: 26（source.level 未指定のため、JDTが対応する最大値） / このJDTの対応上限: 26
```

ソース解析に時間がかかるため解析結果のキャッシュを作成します。
２回目以降はキャッシュを利用し、差異のあるファイルだけを解析し直します。
差分判定は最終更新時刻とファイルサイズによります。
なお `source.level` を変えるとキャッシュは破棄されます
（同じソースでも、どの言語バージョンとして解析したかで結果が変わるため）。

#### 解析対象のJavaバージョン（`source.level`）

通常は**指定しなくて構いません。** 未指定なら、JDTが対応する最大値で解析します。
新しい言語機能を許可するだけなので、対象コードが実際にそれを使うかどうかには
影響しません。

指定が要るのは、新しい版では意味が変わる書き方を含む古いコードを解析するときです
（`var` / `record` / `sealed` / `yield` などが識別子として使われている場合）。

```properties
source.level=1.8
```

指定した値がJDTの対応範囲外なら起動時にエラーになります。
また、新しいJDTは **1.7以下の指定を黙って 1.8 に引き上げる**ため、
その場合はログにその旨が出ます。それより古いレベルが必要なら、
古い版のJDT（`-PjdtVersion=3.33.0` など）を使ってください。

---

## 出力ファイル

### `call-hierarchy.csv` — 呼び出し階層

```csv
caller,callee,calleeSignature,root,call-hierarchy
at jp.co.example.action.OrderAction.execute(OrderAction.java:50),OrderService.findOrder,jp.co.example.service.OrderService.findOrder(java.lang.String),OrderAction.execute,OrderService.findOrder
at jp.co.example.service.OrderService.findOrder(OrderService.java:25),OrderDaoImpl.selectById,jp.co.example.dao.OrderDaoImpl.selectById(long),OrderAction.execute,OrderService.findOrder,OrderDaoImpl.selectById
```

| 列 | 内容 |
|---|---|
| `caller` | 呼び出し元。Javaのスタックトレースと同じ形式。**呼び出し箇所**の行を指す |
| `callee` | 呼び出し先。クラス名.メソッド名の形式でExcelのフィルタに使える |
| `calleeSignature` | 呼び出し先の**完全修飾クラス名**と引数リスト。同名クラスやオーバーロードを区別する |
| `root` | 起点メソッド。クラス名.メソッド名の形式でExcelのフィルタに使える |
| `call-hierarchy` | 起点からの呼び出し先を1ノード1列で展開（**可変長**） |

`callee` は短い名前なので、別パッケージの同名クラスやオーバーロードが混ざります。
`calleeSignature` はそれを一意に特定できる形（完全修飾クラス名＋引数の型）で出すので、
**フィルタは `callee`、特定は `calleeSignature`** という使い分けができます。

**コンストラクタの呼び出し自体は行になりません。** `new` したこと自体より
「そのコンストラクタの中で何を呼んでいるか」が知りたいためです。
経路には残るので、コンストラクタ内からの呼び出しは
`call-hierarchy` 列にコンストラクタを含んだ形で出力されます。
`callee` や `call-hierarchy` でのコンストラクタの表示は `<init>` ではなく
**クラス名**です（`Sample.Sample`）。ソースに宣言が無い暗黙のデフォルト
コンストラクタも、補完される名前（＝クラス名）で出ます。
`caller` 列だけはEclipseのスタックトレース形式に合わせるため `<init>` のままです。

```csv
caller,callee,calleeSignature,root,call-hierarchy
at jp.co.example.Sample.<init>(Sample.java:3),Sample.init,jp.co.example.Sample.init(),Sample.Sample,Sample.init
```

注記が付く場合は `call-hierarchy` の**最後の要素**として出ます。列を間に挟むと
可変長の階層が途中で切れてしまうためです。

| 注記 | 意味 |
|---|---|
| `[CYCLE]` | この経路上で既に呼んでいるメソッドに戻る呼び出し。ここで打ち切る |
| `深さ制限(N)のため打ち切り` | `max.depth` に達した |
| `CHA候補N件（未展開）: 理由` | 実装を1つに絞れなかった。候補数^深さで爆発するため展開しない。理由は下表 |
| `実装なし（宣言のまま）: 理由` | 本体を持つ実装がソース上に1つも無い。宣言のまま出しているだけ |
| `ソースなし（展開不可）` | 呼び出し先がjar内などでソースが無く、そこから先を辿れない |
| `外部ライブラリ（import推定・未検証）` | クラスパス不足で型解決できず、`import` 文から型名を推定した |
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
caller,callee,calleeSignature,root,call-hierarchy
at jp.co.example.Foo.bar(Foo.java:42),getOptions,,(型解決失敗),getOptions,型解決に失敗（クラスパス不足・動的呼び出し等の可能性）
```

| 列 | 内容 |
|---|---|
| `caller` | 呼び出し元。呼び出し箇所の行が分かるのでEclipseからジャンプできる |
| `callee` | ソースに書かれていた式（メソッド名）。型が特定できていないのでクラス名は付かない |
| `calleeSignature` | 空。型が特定できていないため出せない |
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
caller,callee,calleeSignature,root,call-hierarchy
NightJob,OrderService.findOrder,jp.co.example.service.OrderService.findOrder(java.lang.String),team-b-batch.jar,OrderService.findOrder,被参照:EXACT
NightJob,OrderService.OrderService,jp.co.example.service.OrderService.OrderService(),team-b-batch.jar,OrderService.OrderService,被参照:IMPLICIT_CTOR
```

| 列 | 内容 |
|---|---|
| `caller` | 参照している側のクラス。行番号もメソッドも分からないためスタックトレース形式にはならない |
| `callee` | 参照されている自分のメソッド |
| `calleeSignature` | 参照されている自分のメソッドの完全修飾クラス名と引数リスト |
| `root` | 参照元のjar名（起点メソッドが無いため、代わりにjar名を入れる） |
| `call-hierarchy` | `callee` と、照合の種類を表す注記 |

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
| 4 | `DATAFLOW_FACTORY` | ファクトリメソッドの戻り値から特定（後述） |
| — | `DATAFLOW_PARAM` | 呼び出し元から渡された引数から特定（後述。経路ごとに判定するため段の外） |
| — | `DATAFLOW_FIELD` | コンストラクタ注入されたフィールドから特定（同上） |
| 5 | `CHA` | 候補が複数のまま（低確度） |

**候補数は「サブクラス数」ではなく「そのメソッドをオーバーライドしている宣言の数」です。**
サブクラスが多くても、オーバーライドが1件なら候補は1件のままになります。

段5（`CHA`）になった呼び出しは、候補を列挙するだけでその先へは降りません
（候補数^深さで爆発するため）。`call-hierarchy` 列の最後に
`CHA候補N件（未展開）: 理由` と付きます。

### データフローによる特定

CHAで候補が複数になった呼び出しのうち、次の3つは値の流れを追って1つに絞ります。

```java
// 1. ファクトリメソッドの戻り値（DATAFLOW_FACTORY）
DaoFactory.createUser().select();     // createUser() の return new UserDao() から UserDao.select と判定

// 2. 呼び出し元から渡された引数（DATAFLOW_PARAM）
void root()          { useDao(new UserDao()); }
void useDao(Dao dao) { dao.select(); }   // この経路では UserDao.select と判定

// 3. コンストラクタ注入されたフィールド（DATAFLOW_FIELD）
class Service {
    private final Dao dao;
    Service(Dao dao) { this.dao = dao; }
    void exec() { dao.select(); }        // この経路では UserDao.select と判定
}
new Service(new UserDao()).exec();
```

ファクトリは、**クラス名の文字列を受け取ってリフレクションで生成する形**にも
対応しています。`static final String` の定数を渡す書き方も追えます。

```java
static Dao create(String className) {
    return (Dao) Class.forName(className).newInstance();          // getDeclaredConstructor() を挟む形も可
}
create("jp.co.example.dao.UserDaoImpl").select();                  // -> UserDaoImpl.select
create(Names.USER_DAO).select();                                   // 定数フィールドでも同じ
```

引数とフィールドの追跡は**起点からの経路ごと**に行います。同じメソッドでも、
別の起点から別の具象クラスを渡されていれば、そちらはそちらで判定されます。
逆に、呼び出し元の候補を遡って集めるようなことはしません
（実際には通らない経路の型が混ざるため）。

次の場合は**絞らずに `CHA候補N件（未展開）` のまま**出します。
「候補が多くて絞れなかった」は追加調査で済みますが、
「1つに絞ったが実は違った」は調査対象の取りこぼしになるためです。

- ファクトリが複数の型を返しうる／追跡できない `return` を1つでも含む
- クラス名が実行時にしか決まらない（`Class.forName(System.getProperty(...))` など）
- ループや分岐でローカル変数が別の型に再代入される
- フィールドが setter やDI設定で入る、代入しないコンストラクタがある、
  `private` でも `final` でもない、親クラスで宣言されている
- 起点メソッドの引数、起点オブジェクトの生成箇所（経路の中に渡し元がない）

コンストラクタ注入されたフィールドを追う条件は、
**「このフィールドには必ずこれが入る」と言い切れること**です。
具体的には (a)`private` または `final`、(b) 代入がコンストラクタ本体か
フィールド初期化子の中だけ、(c) 初期化子を持つか `this(...)`委譲していない
全てのコンストラクタで代入される、(d) それらの出所が一致する、の4つを
全て満たす場合だけです。

`dataflow.enabled=false` で、この特定を丸ごと止められます
（従来どおりの出力に戻ります）。実装上の判断は
[docs/QA.md](docs/QA.md)、仕組みは [docs/DESIGN.md](docs/DESIGN.md) を参照してください。

### プロジェクト固有の解決を差し込む

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
