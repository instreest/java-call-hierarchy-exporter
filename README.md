# java-call-hierarchy-exporter

Javaプロジェクトのメソッド呼び出し階層を一括抽出してCSVに出力するツールです。

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
"%JAVA_HOME%\bin\javac" -cp "lib\*" -d bin -encoding UTF-8 src\main\java\CallHierarchyExporter.java

rem 実行
"%JAVA_HOME%\bin\java" -cp "bin;lib\*" CallHierarchyExporter config\config.properties
```

### 出力されるファイル

| ファイル | 既定出力先 |
|---|---|
| 呼び出し階層リスト | `./config/output/call-hierarchy.csv` |
| メソッド全体リスト | `./config/output/methods.csv` |

出力はすべてUTF-8（BOM付き）のCSVで、Excelでそのまま開けます。

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

`config/config.properties` をコピーして編集します。
**相対パスは「設定ファイルが置かれているディレクトリ」を起点に解決されます**

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
| `HUB` | 入次数が `hub.threshold` 以上。改修時の影響範囲が広い |
| `LEAF` | 呼び出し先が無い |

### 他リポジトリからの被参照

自分のコードを呼んでいる側のjarを `external.library.folders` に指定すると出力されます。

```properties
external.library.folders=./lib
```

classファイルの定数プールだけを読むため、「どのjar・どのクラスが参照しているか」までが分かります。
呼び出し元メソッドまでは分かりません。

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
| 定数のインライン展開 | `public static final` の定数は呼び出し側に埋め込まれるため、被参照スキャンで検出できません |
| オーバーロード | `call-hierarchy.csv` の表示上は同名で並びます。厳密に区別する場合は `edges.csv` の `declaredCallee` 列を参照してください |

---
