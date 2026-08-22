# java-call-hierarchy-exporter

Eclipseプロジェクトを対象に、Javaのメソッド呼び出し階層を一括抽出してファイルに出力するツールです
（既定はタブ区切りのTSV）。
Eclipse IDE の起動は不要で、通常のJavaアプリとして動作します。

Eclipse標準の「呼び出し階層」ビューに対して、次の点を解決することを目的にしています。

- コピーすると階層構造が失われる
- 再帰的に一括でリスト化できない
- ワークスペース全体の呼び出し階層を作れない

## できること

| 機能 | 出力 |
|---|---|
| 指定した起点からの呼び出し階層 | `call-hierarchy.tsv` |
| ソース上の全メソッドの呼び出し状況（全体モード） | `methods.tsv` |
| 解決後の全呼び出し関係 | `edges.tsv` |
| インターフェース経由の呼び出しの具象クラス解決 | `resolutions.tsv` |
| 型解決できなかった呼び出しの記録 | `unresolved-calls.tsv` |
| 他チーム・他リポジトリのjarからの被参照 | `external-usage.tsv` |

出力は既定でタブ区切り（TSV）です。grepした行をそのままExcelに貼り付けてもセルに分割されます。
文字コードは既定でMS932（Shift_JIS）なので、Excelでそのまま開けます。
カンマ区切り（CSV）やUTF-8（BOM付き）への変更も可能です（[出力ファイル](#出力ファイル)参照）。

---

## Getting Started

このリポジトリを最小構成でとりあえず試す手順です。実行しなくても、下記のサンプルを見るだけで
出力のイメージがつかめるようにしています。各設定項目のカスタマイズ方法や出力ファイルの詳細な
意味・注意点は、このセクション以降にまとめています。

### 1. ビルドする

```bash
gradle dist
```

`build/dist/` に、実行に必要な一式（jar・依存jar・設定サンプル・実行スクリプト）がまとまります。

### 2. 設定ファイルを用意する

`config/config.properties` をコピーし、**`project.root` だけ**書き換えます。他の項目は
空・既定値のままで構いません。

```properties
# Eclipseプロジェクトのルート（.classpath / .project があるディレクトリ）
project.root=../../my-legacy-project
```

`entry.packages`（起点にするパッケージ）を空のままにすると「全体モード」になり、起点を
意識せずソース上の全メソッドの呼び出し状況を一括で出力します。**まず試すだけならこれが
一番手間のかからない方法です。**

### 3. 実行する

```bash
./run.sh config/config.properties
```

### 4. 出力されるファイル

最小構成（`entry.packages` 未指定の全体モード）では、次の3ファイルが自動的に出力されます。
列の詳しい意味は後述の [出力ファイル](#出力ファイル) を参照してください。

#### `methods.tsv` — ソース上の全メソッドと呼び出し状況

タブ区切りです（下記は分かりやすさのため列を揃えて表示しています）。

```tsv
method	declaringType	typeKind	file	line	hasBody	inDegree	outDegree	role	reachable
OrderAction.execute	jp.co.xxx.action.OrderAction	C	OrderAction.java	45	1	0	1	ENTRY_CANDIDATE	1
OrderService.findOrder	jp.co.xxx.service.OrderService	C	OrderService.java	20	1	1	1	NORMAL	1
OrderDao.selectById	jp.co.xxx.dao.OrderDao	I	OrderDao.java	8	0	0	0	ISOLATED	0
OrderDaoImpl.selectById	jp.co.xxx.dao.OrderDaoImpl	C	OrderDaoImpl.java	15	1	1	0	LEAF	1
```

`role` 列を見るだけで、「呼び出し元が無い箇所（`ENTRY_CANDIDATE`）」「共通処理で改修時の
影響範囲が広い箇所（`HUB`）」などを一覧で仕分けできます。

#### `edges.tsv` — 解決後の全呼び出し関係

```tsv
caller	callee	callerFile	callLine	bindKind	resolution	candidateCount	declaredCallee
OrderAction.execute	OrderService.findOrder	OrderAction.java	50	V	NO_OVERRIDE	1	jp.co.xxx.service.OrderService#findOrder()
OrderService.findOrder	OrderDaoImpl.selectById	OrderService.java	25	V	SINGLE_IMPL	1	jp.co.xxx.dao.OrderDao#selectById()
```

インターフェース経由の呼び出し（`OrderDao#selectById()`）が、実装クラス
（`OrderDaoImpl.selectById`）に解決されていることが分かります。

#### `call-hierarchy.tsv` — 呼び出し元が無いメソッドを起点にした呼び出し階層

`entry.auto=true`（既定）のため、`methods.tsv` で `ENTRY_CANDIDATE` になったメソッドを
自動的に起点にして、`call-hierarchy.tsv` も合わせて出力されます。

```tsv
caller	callee	note	callHierarchy
	OrderAction.execute		OrderAction.execute
at jp.co.xxx.action.OrderAction.execute(OrderAction.java:50)	OrderService.findOrder		OrderAction.execute	OrderService.findOrder
at jp.co.xxx.service.OrderService.findOrder(OrderService.java:25)	OrderDaoImpl.selectById	解決:SINGLE_IMPL	OrderAction.execute	OrderService.findOrder	OrderDaoImpl.selectById
```

---

特定のパッケージだけを起点にしたい場合の設定や、各出力ファイルの列の意味・既知の限界などは
以降のセクションで説明します。

---

## 必要環境

- JDK 11以上（このツール自身を動かすJVM。解析対象のJavaバージョンとは無関係です）
- Eclipse JDT Core

**JDT Coreは、新しい版ほど動かすのに新しいJDKを要求します。**
手元のJDKで動く版を選んでください。バージョンはビルド時に指定できます。

```
gradle -PjdtVersion=3.29.0 dist
```

---

## ビルド（Gradle）

```bash
gradle dist
```

`build/dist/` に、実行に必要な一式が出力されます。

```
build/dist/
├── java-call-hierarchy-exporter-0.1.0.jar
├── lib/                         依存jar一式
├── config/config.properties     設定サンプル
├── run.sh / run.bat
└── build.xml
```

社内Nexus等を使う場合は `build.gradle` の `repositories` を書き換えてください。

---

## Ant・コマンドラインで実行する

**Gradleは依存jarを集めるためだけに使い、実行はAntやコマンドラインで行う**という使い方を想定しています。

### 1. 依存jarを集める

```bash
gradle copyDeps
```

`build/dist/lib/` に依存jarが集まります。ネットワークが使える環境で一度実行しておけば、
以降はこのフォルダごと持ち込むだけで動きます。

クラスパス文字列がほしい場合はこちらです。

```bash
gradle printClasspath
```

### 2-a. コマンドラインから実行

```bash
# Linux / macOS
java -Xmx2g -Dfile.encoding=UTF-8 \
     -cp "java-call-hierarchy-exporter-0.1.0.jar:lib/*" \
     CallHierarchyExporter config/config.properties

# Windows（クラスパス区切りが ; になります）
java -Xmx2g -Dfile.encoding=UTF-8 ^
     -cp "java-call-hierarchy-exporter-0.1.0.jar;lib\*" ^
     CallHierarchyExporter config\config.properties
```

同梱の `run.sh` / `run.bat` は上記をラップしたものです。

```bash
./run.sh config/config.properties
JAVA_OPTS="-Xmx8g" ./run.sh config/config.properties   # ヒープを増やす場合
```

### 2-b. Antから実行

同梱の `build.xml` を使います。`lib.dir` に、Gradleで集めた依存jarの場所を指定してください。

```bash
ant -Dlib.dir=build/dist/lib dist
ant -Dlib.dir=build/dist/lib run -Dconfig.file=config/config.properties
```

既存プロジェクトの `build.xml` に取り込む場合は、`<path id="classpath">` の中身を
既存の `path refid` に差し替えるか、`<import>` して既存定義を参照してください。

> **注意**: Antの `<javac>` は `debug` の既定値が `false` です。この場合
> `-g:none` 相当となり**行番号情報が失われ**、出力CSVからソース行が消えます。
> 同梱の `build.xml` では `debug="true"` を明示しています。

### Gradleを使わない場合

社内からMaven Centralにも社内Nexusにもアクセスできない場合、
Pleiades（Eclipse）のインストールフォルダから直接jarをコピーしても動きます。

`plugins/` 配下から `org.eclipse.jdt.core_*.jar` を中心に `lib/` へコピーし、
ビルド時に `NoClassDefFoundError` が出たら、そのクラスを含むプラグインjarを
追加でコピーする、という手順になります。

> この方法で必要になるjarの正確な一覧は環境によって変わります。
> 試行錯誤が前提の手段だと考えてください。

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

すべての出力ファイル（`call-hierarchy.tsv`・`methods.tsv`・`edges.tsv`・`resolutions.tsv`・
`unresolved-calls.tsv`・`external-usage.tsv`・`external-unmatched.tsv`）は、次の2つの設定で
形式を変更できます。

```properties
# 区切り文字。既定は TAB（タブ区切り）。
output.delimiter=TAB

# 文字コード。既定は MS932（Shift_JIS）。
#   UTF-8-BOM … BOM付きUTF-8。ExcelがUTF-8と正しく認識して開ける
#   UTF-8     … BOM無し。Excelで直接開くと文字化けする点に注意
output.encoding=MS932
```

**既定はタブ区切り（TSV）です**。grepなどで抽出した行をそのままコピーしてExcelに
貼り付けると、タブの位置で自動的にセルへ分割されます。フィールドにカンマを含む
データ（Javaの引数リストや日本語の説明文など）が多くても、区切り文字と衝突しないため
確実に列を分けられます。

**カンマ区切り（CSV）に戻したい場合**（`output.delimiter=COMMA`）: 出力先の拡張子も
`.tsv` から `.csv` に変更してください（`output.csv=./output/call-hierarchy.csv` の
ように指定）。拡張子と中身の区切り文字が食い違うと紛らわしいためです。
また、ダブルクリックでExcelに開かせる場合、`.csv` はOSの「リスト区切り記号」設定に
従ってカンマ区切りとして解釈される点に注意してください。

**UTF-8で開きたい場合**（`output.encoding=UTF-8-BOM`）: 既定のMS932はJIS第一・第二水準外の
文字（一部の人名・機種依存文字など）を `?` に置換してしまいますが、UTF-8ならその制約が
ありません。`UTF-8-BOM` を指定するとファイル先頭にBOMが付き、Excelでダブルクリックしても
文字化けせずに開けます（BOM無しの `UTF-8` は、Excelで直接開くと文字化けするため非推奨です）。

### `call-hierarchy.tsv` — 呼び出し階層

```tsv
caller	callee	note	callHierarchy
	OrderAction.execute		OrderAction.execute
at jp.co.xxx.action.OrderAction.execute(OrderAction.java:50)	OrderService.findOrder		OrderAction.execute	OrderService.findOrder
at jp.co.xxx.service.OrderService.findOrder(OrderService.java:25)	OrderDaoImpl.selectById	解決:SINGLE_IMPL	OrderAction.execute	OrderService.findOrder	OrderDaoImpl.selectById
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
grep "OrderDaoImpl.selectById$" call-hierarchy.tsv
```

> `callHierarchy` より後ろに列を追加しないでください。行末マッチが壊れます。
> 列を足す場合は `callHierarchy` より前に挿入します。

### `methods.tsv` — 全メソッドの呼び出し状況（全体モードのみ）

`entry.packages` を空にすると全体モードになります。

| role | 意味 |
|---|---|
| `ENTRY_CANDIDATE` | 呼び出し元が無い。画面入口・デッドコード・テスト・リフレクション経由が混ざる |
| `ISOLATED` | 呼び出し元も呼び出し先も無い。デッドコードの疑いが濃い |
| `HUB` | 入次数が `hub.threshold` 以上。改修時の影響範囲が広い |
| `LEAF` | 呼び出し先が無い |

`reachable=0` の行は、起点候補から到達できないメソッドです。
相互再帰だけで閉じたクラスタ（デッドコードの塊）を見つけるのに使えます。

### `edges.tsv` — 解決後の全呼び出し関係（全体モードのみ）

呼び出しルートを全部展開すると `分岐^深さ` で爆発しますが、
エッジ一覧はエッジ数に比例した線形サイズで収まり、
**任意の起点からのルートを後から再構成できます**。網羅を目指す場合はこちらが一次成果物です。

### `resolutions.tsv` — 具象クラスの解決結果

```tsv
declaredMethod	bindKind	label	candidateCount	candidates
jp.co.xxx.dao.OrderDao#selectById()	V	SINGLE_IMPL	1	jp.co.xxx.dao.impl.OrderDaoImpl
jp.co.xxx.dao.CommonDao#execute()	V	CHA	2	jp.co.xxx.dao.impl.UserDaoImpl / jp.co.xxx.dao.impl.ItemDaoImpl
```

`CHA` の行が「静的に絞りきれなかった箇所」です。`candidateCount` の降順に並べると、
拡張（後述）を作る価値が高い順になります。

### `unresolved-calls.tsv` — 型解決できなかった呼び出し

クラスパス不足、リフレクション、フレームワーク経由の呼び出しなどが記録されます。
**最初の実行では、まずこの件数を確認してください。** 異常に多い場合は
`extra.classpath.entries` の設定漏れが疑われます。

### `external-usage.tsv` — 他リポジトリからの被参照

自分のコードを呼んでいる側のjarを `external.jars` に指定すると出力されます。

```properties
external.jars=//shared/teamjars
```

```tsv
method	declaringType	jar	referencingClass	matchKind
OrderService.findOrder	p.svc.OrderService	team-b-batch.jar	jp.teamb.NightJob	EXACT
Base.inherited	p.base.Base	team-b-batch.jar	jp.teamb.NightJob	INHERITED
OrderService.<init>	p.svc.OrderService	team-b-batch.jar	jp.teamb.NightJob	IMPLICIT_CTOR
```

classファイルの定数プールだけを読むため、外部ライブラリは不要です。
「どのjar・どのクラスが参照しているか」までが分かります
（呼び出し元メソッドまでは分かりません）。

> **これらのjarは自分の `.classpath` には現れません**。依存の向きが逆だからです。
> 共有フォルダやNexusから別途集めてください。

`external-unmatched.tsv` には、自分の型を参照しているのにメソッドが一致しなかったものが出ます。
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
| オーバーロード | `call-hierarchy.tsv` の表示上は同名で並びます。厳密に区別する場合は `edges.tsv` の `declaredCallee` 列を参照してください |

---

## 検証状況

同梱の検証クラスは次で実行できます。

```bash
gradle verify
```

外部ライブラリを使わず、`main()` を持つクラスを順に実行してOK/NGを集計します。

> **重要**: 開発時の検証は、JDT APIのスタブを自作して行っています。
> **実際のJDT jarに対するコンパイル・実行の確認はできていません。**
> 特に `ASTParser.setEnvironment()` + `setUnitName()` によるバインディング解決が
> 実環境で効くかは、最初に確認してください。
> `unresolved-calls.tsv` が異常に多い場合、ここが効いていない可能性が高いです。

---

## ライセンス

[Apache License 2.0](LICENSE)

```
Copyright 2026 the java-call-hierarchy-exporter authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0
```

### コントリビューションについて

コントリビューションは Apache-2.0 の条件で受け入れます
（Apache-2.0 第5条により、別段の意思表示がない限りそのように扱われます）。

### 公開前の確認事項

業務時間中や業務課題のために作成したものである場合、**著作権が勤務先に帰属する
可能性があります**。ライセンスを選ぶ主体が自分でないことになるため、
公開の可否そのものを先に確認しておくことをおすすめします。
