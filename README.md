# java-call-hierarchy-exporter

Eclipseプロジェクトを対象に、Javaのメソッド呼び出し階層を一括抽出してCSVに出力するツールです。
Eclipse IDE の起動は不要で、通常のJavaアプリとして動作します。

Eclipse標準の「呼び出し階層」ビューに対して、次の点を解決することを目的にしています。

- コピーすると階層構造が失われる
- 再帰的に一括でリスト化できない
- ワークスペース全体の呼び出し階層を作れない

## できること

| 機能 | 出力 |
|---|---|
| 指定した起点からの呼び出し階層 | `call-hierarchy.csv` |
| ソース上の全メソッドの呼び出し状況（全体モード） | `methods.csv` |
| 解決後の全呼び出し関係 | `edges.csv` |
| インターフェース経由の呼び出しの具象クラス解決 | `resolutions.csv` |
| 型解決できなかった呼び出しの記録 | `unresolved-calls.csv` |
| 他チーム・他リポジトリのjarからの被参照 | `external-usage.csv` |

出力はすべてUTF-8（BOM付き）のCSVが既定なので、Excelでそのまま開けます。
タブ区切りやMS932（Shift_JIS）への変更も可能です（[出力ファイル](#出力ファイル)参照）。

---

## Getting Started

このリポジトリを最小構成でとりあえず試す手順です。実行しなくても、下記のサンプルを見るだけで
出力のイメージがつかめるようにしています。各設定項目のカスタマイズ方法や出力ファイルの詳細な
意味・注意点は、このセクション以降にまとめています。

### 1. 設定ファイルを編集する

`config/config.properties` をコピーし、**`project.root` だけ**書き換えます。他の項目は
空・既定値のままで構いません。

```properties
# Eclipseプロジェクトのルート（.classpath / .project があるディレクトリ）
project.root=../../my-legacy-project
```

`entry.packages`（起点にするパッケージ）を空のままにすると「全体モード」になり、起点を
意識せずソース上の全メソッドの呼び出し状況を一括で出力します。**まず試すだけならこれが
一番手間のかからない方法です。**

### 2. 実行する

Gradleが使える環境かどうかで手順が変わります。

#### Gradleが使える場合

```bash
gradle run --args="config/config.properties"
```

jarのビルドや配置は不要です。依存の解決から実行まで、この1コマンドで完結します。

#### Gradleが使えない場合（Pleiades/Eclipse環境など）

Eclipse(Pleiades)がインストールされていれば、そこに含まれるJDT Core一式を使って、
Gradleもネットワーク接続も無しに実行できます。

**`plugins` フォルダをそのままクラスパスに指定しないでください。** EGit（Eclipseの
Git連携）が同梱するSSH関連jar（`org.apache.sshd.*` 等）が、無関係にもかかわらず
`java.nio.file.spi.FileSystemProvider` の実装として登録されており、クラスパスに
乗っただけで次のように起動時エラーになります。

```
Exception in thread "main" java.util.ServiceConfigurationError:
java.nio.file.spi.FileSystemProvider: Provider
org.apache.sshd.common.file.root.RootedFileSystemProvider could not be instantiated
```

そこで、`plugins` フォルダ全部ではなく、実行に必要なプラグインjarだけを
`lib` フォルダに集めて使います（元のEclipseインストールは変更しません）。
次の一覧は実機（Pleiades 2026-06）での実行時クラスロードログから確認したものです。
バージョン部分はEclipseのバージョンによって変わるためワイルドカードでコピーします。

```bash
# <Pleiadesのインストール先> は環境に合わせて書き換えてください
# 例: /c/pleiades/2026-06/eclipse

mkdir -p lib
for p in org.apache.xerces org.eclipse.core.contenttype org.eclipse.core.jobs \
         org.eclipse.core.resources org.eclipse.core.runtime org.eclipse.equinox.common \
         org.eclipse.equinox.preferences org.eclipse.jdt.core.compiler.batch \
         org.eclipse.jdt.core org.eclipse.osgi org.osgi.service.prefs; do
  cp <Pleiadesのインストール先>/plugins/${p}_*.jar lib/
done

# コンパイル（初回のみ）
javac -cp "lib/*" -d classes src/main/java/CallHierarchyExporter.java

# 実行
java -cp "classes:lib/*" CallHierarchyExporter config/config.properties
```

```bat
rem Windows（クラスパス区切りが ; になります）
mkdir lib
for %P in (org.apache.xerces org.eclipse.core.contenttype org.eclipse.core.jobs org.eclipse.core.resources org.eclipse.core.runtime org.eclipse.equinox.common org.eclipse.equinox.preferences org.eclipse.jdt.core.compiler.batch org.eclipse.jdt.core org.eclipse.osgi org.osgi.service.prefs) do copy "<Pleiadesのインストール先>\plugins\%P_*.jar" lib\

javac -cp "lib\*" -d classes src\main\java\CallHierarchyExporter.java

java -cp "classes;lib\*" CallHierarchyExporter config\config.properties
```

`java` / `javac` コマンド自体が見つからない場合は、Pleiadesに同梱のJRE
（`<Pleiadesのインストール先>/../java/<バージョン>/bin/java` 等）をフルパスで
指定してください。

上記の一覧はASTパース・キャッシュ更新・CSV出力の基本経路で確認したものです。
`entry.packages` を絞ったり `external.jars` ・ `resolver.hint.collectors` 等の
拡張機能を使ったりすると、別のクラスが必要になり `NoClassDefFoundError` が出る
ことがあります。その場合は、次のようにクラスロードログを取って実際に使われた
jarを確認し、足りないものを同様に `lib` へ追加してください。

```bat
"<Pleiadesのインストール先>\..\java\<バージョン>\bin\java" ^
     -Xlog:class+load=info:file=classload.log ^
     -cp "classes;lib\*" CallHierarchyExporter config\config.properties
```

```powershell
Get-Content classload.log |
  Select-String -Pattern 'source:\s*file:/*(.+\.jar)$' |
  ForEach-Object { Split-Path -Leaf $_.Matches[0].Groups[1].Value } |
  Sort-Object -Unique
```

出力に並んだjarの一覧が、そのときの実行で本当に必要だったプラグインです。
`plugins` フォルダを丸ごとクラスパスに乗せているわけではないので、EGitのSSH関連jar
のような無関係なプラグインが混入して `ServiceConfigurationError` になる心配もありません。

### 出力されるファイル

最小構成（`entry.packages` 未指定の全体モード）では、次の3ファイルが自動的に出力されます。
列の詳しい意味は後述の [出力ファイル](#出力ファイル) を参照してください。

#### `methods.csv` — ソース上の全メソッドと呼び出し状況

```csv
method,declaringType,typeKind,file,line,hasBody,inDegree,outDegree,role,reachable
OrderAction.execute,jp.co.xxx.action.OrderAction,C,OrderAction.java,45,1,0,1,ENTRY_CANDIDATE,1
OrderService.findOrder,jp.co.xxx.service.OrderService,C,OrderService.java,20,1,1,1,NORMAL,1
OrderDao.selectById,jp.co.xxx.dao.OrderDao,I,OrderDao.java,8,0,0,0,ISOLATED,0
OrderDaoImpl.selectById,jp.co.xxx.dao.OrderDaoImpl,C,OrderDaoImpl.java,15,1,1,0,LEAF,1
```

`role` 列を見るだけで、「呼び出し元が無い箇所（`ENTRY_CANDIDATE`）」「共通処理で改修時の
影響範囲が広い箇所（`HUB`）」などを一覧で仕分けできます。

#### `edges.csv` — 解決後の全呼び出し関係

```csv
caller,callee,callerFile,callLine,bindKind,resolution,candidateCount,declaredCallee
OrderAction.execute,OrderService.findOrder,OrderAction.java,50,V,NO_OVERRIDE,1,jp.co.xxx.service.OrderService#findOrder()
OrderService.findOrder,OrderDaoImpl.selectById,OrderService.java,25,V,SINGLE_IMPL,1,jp.co.xxx.dao.OrderDao#selectById()
```

インターフェース経由の呼び出し（`OrderDao#selectById()`）が、実装クラス
（`OrderDaoImpl.selectById`）に解決されていることが分かります。

#### `call-hierarchy.csv` — 呼び出し元が無いメソッドを起点にした呼び出し階層

`entry.auto=true`（既定）のため、`methods.csv` で `ENTRY_CANDIDATE` になったメソッドを
自動的に起点にして、`call-hierarchy.csv` も合わせて出力されます。

```csv
caller,callee,note,callHierarchy
,OrderAction.execute,,OrderAction.execute
at jp.co.xxx.action.OrderAction.execute(OrderAction.java:50),OrderService.findOrder,,OrderAction.execute,OrderService.findOrder
at jp.co.xxx.service.OrderService.findOrder(OrderService.java:25),OrderDaoImpl.selectById,解決:SINGLE_IMPL,OrderAction.execute,OrderService.findOrder,OrderDaoImpl.selectById
```

---

特定のパッケージだけを起点にしたい場合の設定や、各出力ファイルの列の意味・既知の限界などは
以降のセクションで説明します。

---

## 使い方

とりあえず試すだけなら [Getting Started](#getting-started) の手順（`project.root` のみ設定
する全体モード）で十分です。ここでは、特定のパッケージだけを起点にしたい場合など、設定を
作り込みたいときの詳細を説明します。

### 1. 設定ファイルを用意する

`config/config.properties` をコピーして編集します。
**相対パスは「設定ファイルが置かれているディレクトリ」を起点に解決されます**
（カレントディレクトリには依存しません）。

最低限の設定は次の2つです。

```properties
# Eclipseプロジェクトのルート（.classpath / .project があるディレクトリ）
project.root=../my-legacy-project

# 起点にするパッケージ（空にすると全体モード）
entry.packages=jp.co.xxx.action.*, jp.co.xxx.batch.**
```

`.classpath` から、ソースフォルダ（`kind="src"`）と依存jar（`kind="lib"`）を自動で読み取ります。

### 2. 実行する

処理の進捗は標準出力に出ます。

```
=== フェーズ1/3: ソース解析 ===
[main] Javaファイル数: 12000
[main] キャッシュ再利用 8500 件 / 新規解析対象 3500 件
[進捗] ソース解析 500/3500 (14.3%)  経過 42.1s  直近500件 42.1s  残り約 4分12s
...
[heap] フェーズ1完了: 使用 45MB / 上限 2048MB
```

「直近500件」の時間を見ると、特定の箇所で急に遅くなっていないかが分かります。

2回目以降は、更新されたファイルだけを解析し直します
（差分判定は最終更新時刻とファイルサイズ）。

---

## 出力ファイル

### 区切り文字・文字コードのカスタマイズ

すべての出力ファイル（`call-hierarchy.csv`・`methods.csv`・`edges.csv`・`resolutions.csv`・
`unresolved-calls.csv`・`external-usage.csv`・`external-unmatched.csv`）は、次の2つの設定で
形式を変更できます。

```properties
# 区切り文字。COMMA（既定・通常のCSV）か TAB。
output.delimiter=COMMA

# 文字コード。既定は UTF-8-BOM（BOM付きUTF-8）。
#   UTF-8-BOM … BOM付きUTF-8。既定。ExcelがUTF-8と正しく認識して開ける
#   UTF-8     … BOM無し。Excelで直接開くと文字化けする点に注意
#   MS932     … Shift_JIS。SJIS前提の既存ツールと連携したい場合
output.encoding=UTF-8-BOM
```

**タブ区切りにしたい場合**（`output.delimiter=TAB`）: フィールドにカンマを含むデータが
多く見づらい場合や、区切り文字の面で確実にExcelへ取り込みたい場合に使います。
**ダブルクリックでExcelに開かせたい場合は、出力先の拡張子を `.csv` のままにせず `.txt` に
してください**（`output.csv=./output/call-hierarchy.txt` のように指定）。`.csv` のまま
だとOSの「リスト区切り記号」設定に従ってカンマ区切りとして解釈されてしまい、タブ区切りに
なりません。

**既定はUTF-8（BOM付き）です**。BOMが付いているため、ExcelでダブルクリックしてもUTF-8と
正しく認識され、文字化けせずに開けます。BOM無しの `UTF-8` は、Excelで直接開くと文字化け
するため注意してください。

**MS932（Shift_JIS）に変更したい場合**（`output.encoding=MS932`）: 既存のExcelマクロや
社内ツールがSJIS前提で作られている場合などに使います。ただしMS932はJIS第一・第二水準外の
文字（一部の人名・機種依存文字など）を `?` に置換してしまう点に注意してください。

### `call-hierarchy.csv` — 呼び出し階層

```csv
caller,callee,note,callHierarchy
,OrderAction.execute,,OrderAction.execute
at jp.co.xxx.action.OrderAction.execute(OrderAction.java:50),OrderService.findOrder,,OrderAction.execute,OrderService.findOrder
at jp.co.xxx.service.OrderService.findOrder(OrderService.java:25),OrderDaoImpl.selectById,解決:SINGLE_IMPL,OrderAction.execute,OrderService.findOrder,OrderDaoImpl.selectById
```

| 列 | 内容 |
|---|---|
| `caller` | Javaのスタックトレースと同じ形式。**呼び出し箇所**の行を指す |
| `callee` | 呼び出し先。行番号を含まないのでExcelのフィルタに使える |
| `note` | 打ち切り理由や解決の由来（`[CYCLE]`、`CHA候補2件（未展開）` 等） |
| `callHierarchy` | 起点から現ノードまでを1ノード1列で展開（**可変長・最終列**） |

**Eclipseへのジャンプ**: `caller` 列の値をコピーし、Consoleビューのドロップダウンから
「Java Stack Trace Console」を選んで貼り付けると、クリックでソースへ飛べます。

**grep**: `callHierarchy` が最終列なので、行末マッチでそのメソッドに至る経路を抽出できます。

```bash
grep "OrderDaoImpl.selectById$" call-hierarchy.csv
```

> `callHierarchy` より後ろに列を追加しないでください。行末マッチが壊れます。
> 列を足す場合は `callHierarchy` より前に挿入します。

### `methods.csv` — 全メソッドの呼び出し状況（全体モードのみ）

`entry.packages` を空にすると全体モードになります。

| role | 意味 |
|---|---|
| `ENTRY_CANDIDATE` | 呼び出し元が無い。画面入口・デッドコード・テスト・リフレクション経由が混ざる |
| `ISOLATED` | 呼び出し元も呼び出し先も無い。デッドコードの疑いが濃い |
| `HUB` | 入次数が `hub.threshold` 以上。改修時の影響範囲が広い |
| `LEAF` | 呼び出し先が無い |

`reachable=0` の行は、起点候補から到達できないメソッドです。
相互再帰だけで閉じたクラスタ（デッドコードの塊）を見つけるのに使えます。

### `edges.csv` — 解決後の全呼び出し関係（全体モードのみ）

呼び出しルートを全部展開すると `分岐^深さ` で爆発しますが、
エッジ一覧はエッジ数に比例した線形サイズで収まり、
**任意の起点からのルートを後から再構成できます**。網羅を目指す場合はこちらが一次成果物です。

### `resolutions.csv` — 具象クラスの解決結果

```csv
declaredMethod,bindKind,label,candidateCount,candidates
jp.co.xxx.dao.OrderDao#selectById(),V,SINGLE_IMPL,1,jp.co.xxx.dao.impl.OrderDaoImpl
jp.co.xxx.dao.CommonDao#execute(),V,CHA,2,jp.co.xxx.dao.impl.UserDaoImpl / jp.co.xxx.dao.impl.ItemDaoImpl
```

`CHA` の行が「静的に絞りきれなかった箇所」です。`candidateCount` の降順に並べると、
拡張（後述）を作る価値が高い順になります。

### `unresolved-calls.csv` — 型解決できなかった呼び出し

クラスパス不足、リフレクション、フレームワーク経由の呼び出しなどが記録されます。
**最初の実行では、まずこの件数を確認してください。** 異常に多い場合は
`extra.classpath.entries` の設定漏れが疑われます。

### `external-usage.csv` — 他リポジトリからの被参照

自分のコードを呼んでいる側のjarを `external.jars` に指定すると出力されます。

```properties
external.jars=//shared/teamjars
```

```csv
method,declaringType,jar,referencingClass,matchKind
OrderService.findOrder,p.svc.OrderService,team-b-batch.jar,jp.teamb.NightJob,EXACT
Base.inherited,p.base.Base,team-b-batch.jar,jp.teamb.NightJob,INHERITED
OrderService.<init>,p.svc.OrderService,team-b-batch.jar,jp.teamb.NightJob,IMPLICIT_CTOR
```

classファイルの定数プールだけを読むため、外部ライブラリは不要です。
「どのjar・どのクラスが参照しているか」までが分かります
（呼び出し元メソッドまでは分かりません）。

> **これらのjarは自分の `.classpath` には現れません**。依存の向きが逆だからです。
> 共有フォルダやNexusから別途集めてください。

`external-unmatched.csv` には、自分の型を参照しているのにメソッドが一致しなかったものが出ます。
**相手のjarが古い版に対してビルドされている可能性があるため、
「使われていない」と判断する前にここを確認してください。**

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

CHAは記録と展開を分けています。

```properties
cha.record=true    # 候補をエッジ一覧に記録する（漏れ防止のため既定true）
cha.expand=false   # 呼び出し階層で降りる（候補数^深さで爆発するため既定false）
```

`cha.expand=false` のとき、CHA候補は `CHA候補N件（未展開）` という葉として1行だけ出力されます。
**「解決できなかった」事実が残るので、静かに消えることはありません。**

---

## 拡張ポイント

具象クラスの特定方法はプロジェクトごとに異なるため、差し込み口を2つ用意しています。
必要な情報が手に入るタイミングが2つに分かれているためです。

| フェーズ | インターフェース | 見えるもの |
|---|---|---|
| A（抽出時） | `CallSiteHintCollector` | AST・呼び出し箇所の文脈 |
| B（構築時） | `TypeCandidateProvider` | 型階層・全体像・外部ファイル |

ファクトリメソッドの例では、両方を使います。

```
フェーズA: DaoFactory.get("USER_DAO") の文字列リテラルを、
           その戻り値を受けているローカル変数に紐づけて記録
フェーズB: "USER_DAO" -> jp.co.xxx.dao.UserDaoImpl の対応表を引く
```

実装クラスのFQNを設定に書くと、リフレクションで読み込まれます。

```properties
resolver.hint.collectors=jp.co.xxx.FactoryKeyCollector
resolver.candidate.providers=jp.co.xxx.FactoryMapProvider
```

拡張は `init(Properties, Path)` で設定ファイルの内容と置き場所を受け取れるので、
独自の設定キー（対応表のパス等）を自由に追加できます。

`TypeCandidateProvider#appliesToStaticBound()` に `true` を返すと、
段0（静的束縛）と判定された呼び出しにも解決を差し込めます。
バイトコード織り込み（AspectJのCTW等）で前提が崩れる場合の逃げ道です。

---

## メモリ設計

大規模なコードベースでも `OutOfMemoryError` にならないよう、3点で対策しています。

1. **解析結果をヒープに溜めない** — 1ファイル解析するたびにキャッシュへ書き出して破棄
2. **エッジをオブジェクトで持たない** — メソッドをintのIDに内部化し、CSR形式のプリミティブ配列で保持
3. **ツリーを組み立てない** — 深さ優先で辿りながら1行ずつ書き出す

各フェーズの終わりにヒープ使用量が出るので、`-Xmx` の目安に使えます。
最大になるのは通常フェーズ2です。

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

## 必要環境

- JDK 11以上（このツール自身を動かすJVM。解析対象のJavaバージョンとは無関係です）
- Eclipse JDT Core

**JDT Coreは、新しい版ほど動かすのに新しいJDKを要求します。**
手元のJDKで動く版を選んでください。バージョンはビルド時に指定できます。

```
gradle -PjdtVersion=3.29.0 run --args="config/config.properties"
```

