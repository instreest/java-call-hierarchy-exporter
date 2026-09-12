# java-call-hierarchy-exporter

Javaプロジェクト全体のメソッド呼び出し階層を一括で抽出してCSVファイルに出力するツールです。

> **English:** It's exports the whole-project java method call hierarchy to CSV file.
> Apache-2.0. Documentation is in Japanese.

- 使い方・出力形式 … このファイル
- 設定項目 … [config/config.properties](config/config.properties)（コメントに全項目の説明）
- CI から使う … [GitHub Actions から使う](#github-actions-から使う)、詳細は [docs/github-actions.md](docs/github-actions.md)
- 設計の記録（機能ごとに迷った点と結論）… [docs/README.md](docs/README.md)

---

## Quick start

ツールを最小構成で試す手順です。初回は JDK と依存モジュールが要るので、ダウンロードしてよいかを尋ねます（[ネットワークアクセスの確認](#ネットワークアクセスの確認)）。

1. 設定ファイルを編集する … [`config/config.properties`](config/config.properties) の `project.root`（解析対象プロジェクトのフォルダ）をセットします。

2. 実行する … リポジトリ直下の起動コマンドに設定ファイルを引数で渡して実行します。何も尋ねずに解析だけを行います。

   ```bat
   rem Windows
   .\java-call-hierarchy-exporter.cmd config\config.properties
   ```

   ```bash
   # Linux / macOS / Git Bash
   ./java-call-hierarchy-exporter.sh config/config.properties
   ```

3. 結果を見る … `config/<解析開始日時>_<プロジェクト名>/` に `call-hierarchy.csv`（呼び出し階層）と
   `methods.csv`（メソッド一覧）ができます。UTF-8（BOM 付き）なのでそのまま Excel で開けます。

引数なしで起動すると、設定ファイルをメニューから選ぶ対話モードになります（[起動コマンド](#起動コマンド)）。
初回に JDK と JBang（合わせて数百 MB）を置く場所は、引数ありのときは尋ねずに **このプロジェクトの中（`.jbang/`）** にします。
変えるときは `launcher.properties`（リポジトリ直下）を編集するか、対話モードの「環境設定」から書き換えます。
手元に無いものを取りに行くときは、その前に必ず確認します（[ネットワークアクセスの確認](#ネットワークアクセスの確認)）。

### コマンドからの実行

起動コマンドを使わず jbang から直接動かすこともできます。実行Javaソースファイルと設定ファイルを引数として渡します。

```bash
./jbangw/jbang src/CallHierarchyExporter.java config/config.properties
```

### Pleiades/Eclipse環境（閉域ネットワーク等）

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
"%JAVA_HOME%\bin\javac" -classpath lib\* -sourcepath src -d bin src\CallHierarchyExporter.java -encoding UTF-8

rem 実行
"%JAVA_HOME%\bin\java" -classpath bin;lib\* CallHierarchyExporter config\config.properties
```


## 出力されるファイル

実行のたびに設定ファイルと同じフォルダ（コンフィグで変更可能）に
**`<解析開始日時>_<project.rootフォルダ名>`** のフォルダを作ってまとめます。

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

| ファイル | 内容 |
|---|---|
| `call-hierarchy.csv` | メソッド呼び出し階層リスト |
| `methods.csv` | メソッドリスト（ソース上の全メソッドとその呼び出し状況） |

出力CSVファイルはUTF-8（BOM付き）なのでExcelで開けます。
解析結果のキャッシュは出力フォルダには入りません（[キャッシュの置き場所](#キャッシュの置き場所)）。
各列の意味・行順・注記の詳細は [出力ファイル](#出力ファイル) にあります。

```csv
caller,callee,root,call-hierarchy
at jp.co.example.action.OrderAction.execute(OrderAction.java:50),jp.co.example.service.OrderService.findOrder(String),OrderAction.execute,OrderService.findOrder
at jp.co.example.service.OrderService.findOrder(OrderService.java:25),jp.co.example.dao.OrderDaoImpl.selectById(long),OrderAction.execute,OrderService.findOrder,OrderDaoImpl.selectById
```

```csv
method,declaringType,typeKind,file,line,hasBody,inDegree,outDegree,role,reachable,unresolvedCalls,unresolvedCause
OrderAction.execute(),jp.co.example.action.OrderAction,C,OrderAction.java,45,1,0,1,ENTRY_CANDIDATE,1,0,
OrderService.findOrder(String),jp.co.example.service.OrderService,C,OrderService.java,20,1,1,1,NORMAL,1,1,フィールド変数
OrderDao.selectById(long),jp.co.example.dao.OrderDao,I,OrderDao.java,8,0,0,0,ISOLATED,0,0,
OrderDaoImpl.selectById(long),jp.co.example.dao.OrderDaoImpl,C,OrderDaoImpl.java,15,1,1,0,LEAF,1,0,
```


## 実行方法の詳細

Quick start で使った起動コマンドの詳しい説明と、JBang を直接使う方法、閉域ネットワーク向けに
Eclipse（Pleiades）の jar でコンパイルして動かす方法、Eclipse でソースを開く方法です。

### 設定ファイル

既定の設定ファイル [`config/config.properties`](config/config.properties) で必須なのは **`project.root`** だけです。
次の項目は空欄のままなら `project.root` の中身から決めます（明示したいときだけ書き換えます）。

| 項目 | 空欄のときの決め方 |
|---|---|
| `source.folders` | `.classpath` の `kind="src"`、無ければ `src/main/java` → `src` → `<モジュール>/src/main/java` |
| `library.folders` | `pom.xml` / `build.gradle` を読んでローカルリポジトリ（`~/.m2/repository` 等）から自動取得（[依存 jar の自動取得](#依存-jar-の自動取得maven--gradle)）。ビルドファイルが無ければ `project.root` 直下の `lib` の `*.jar` |
| `source.encoding` | `pom.xml` の `project.build.sourceEncoding`、無ければ `UTF-8` |

起動コマンドの「設定ファイルを新しく作る」で、解析対象のフォルダを入力してこれらを埋めた設定ファイルを作ることもできます。
全項目の説明は設定ファイル内のコメントにあります。

### 起動コマンド

リポジトリ直下の `java-call-hierarchy-exporter.cmd`（Windows）/ `java-call-hierarchy-exporter.sh`（Linux / macOS / Git Bash）が
起動コマンドです。どのフォルダから実行してもかまいません。引数で振る舞いが変わります。

| 引数 | 動き |
|---|---|
| 設定ファイル（複数可） | 対話なしで解析する。メニューも質問も出ないので、バッチ・タスクスケジューラ・CI から呼べる。終了コードは、すべて成功なら 0、1 つでも失敗すれば 1、引数が誤っていれば 2 |
| なし | メニューで操作する対話モード |
| `--help` / `-h` | 使い方を表示する |

```bat
rem Windows（コマンドプロンプト・PowerShell）
java-call-hierarchy-exporter.cmd config\app-a.properties config\app-b.properties
java-call-hierarchy-exporter.cmd
```

```bash
# Linux / macOS / Git Bash
./java-call-hierarchy-exporter.sh config/app-a.properties config/app-b.properties
./java-call-hierarchy-exporter.sh
```

初回は、このツールが使う JDK と JBang（合わせて数百 MB）の置き場所が決まります。
**引数ありのとき**は何も尋ねず、**このプロジェクトの中（`.jbang/`）** にします。
**引数なし（対話モード）のとき**だけ、プロジェクトの中か **ユーザーのホーム（`~/.jbang`、JBang の既定）** かを尋ねます。
決まった内容は `launcher.properties`（リポジトリ直下。Git では追跡しない）に保存され、次回からは尋ねません
（標準入力が端末でないとき（パイプ・CI）は保存もせず、JBang の既定のまま動きます）。
プロジェクトの中に置くと他の環境を汚さず、フォルダごと消せば元に戻ります。
`java-call-hierarchy-exporter.cmd` だけは文字コードが MS932（Shift_JIS）です（コマンドプロンプトがバッチファイルを画面のコードページで読むため。
編集するときは MS932 のまま保存してください）。

対話モードのメニューは次のとおりです。設定ファイルを引数に渡したときは、この画面を通らずに解析だけを行います
（下記の jbang 直接実行と同じ結果になります）。

```
================================================================
 java-call-hierarchy-exporter — 対話モード
================================================================
 ツールのフォルダ : C:\work\java-call-hierarchy-exporter
 JDK / JBang      : C:\work\java-call-hierarchy-exporter\.jbang（このプロジェクトの中）
 実行中の JDK     : 25.0.1 (Eclipse Adoptium)  C:\work\java-call-hierarchy-exporter\.jbang\cache\jdks\25
 設定ファイル     : 2 件（config/）

 1) 解析を実行する
 2) 設定ファイルを新しく作る
 3) 環境設定（JDK / JBang の置き場所、ヒープ上限、jbang のオプション）
 4) 実行環境の状態を表示する
 q) 終了
jche>
```

| メニュー | 内容 |
|---|---|
| 1) 解析を実行する | `config/` にある設定ファイルの一覧から選んで解析する（番号をカンマ区切りで複数可。`v 番号` で内容を確認、`p` で一覧に無いパスを指定）。前回使った設定が既定で選ばれるので、2 回目からは Enter を 2 回で実行できる |
| 2) 設定ファイルを新しく作る | 解析対象のフォルダを入力すると、ソースフォルダや `pom.xml` の有無、文字コードを検出して既定値を埋め、`config/<名前>.properties` を作る。ひな形は `config/config.properties` なので全項目の説明コメントも写る。続けて解析もできる |
| 3) 環境設定 | JDK / JBang の置き場所、依存 jar の置き場所、ヒープ上限（`-Xmx`）、`jbang run` の追加オプション（`--offline` 等）、ダウンロードの確認（`JCHE_NETWORK`）。`launcher.properties` に保存し、その場で再起動して反映できる |
| 4) 実行環境の状態 | 実際に使っている JDK・JDT の jar・置き場所とその大きさ・解析キャッシュの一覧 |

`launcher.properties` の項目は次のとおりです（対話モードの「環境設定」で書き換えるほか、手で編集してもかまいません。
キーはそのまま環境変数になります）。

| キー | 意味 |
|---|---|
| `JBANG_DIR` | JBang 本体と JDK の置き場所。空欄なら `~/.jbang`。相対パスはリポジトリ直下が起点 |
| `JBANG_REPO` | 依存 jar（JDT）の置き場所。空欄なら `~/.m2/repository` |
| `JCHE_JAVA_OPTS` | 解析を動かす JVM のオプション（例: `-Xmx4g`） |
| `JCHE_JBANG_OPTS` | `jbang run` に足すオプション（例: `--offline`、`--java 21`） |
| `JCHE_NETWORK` | 手元に無いものを取りに行ってよいか。`ask`（空欄も同じ。足りないときだけ尋ねる）／ `allow`（尋ねずに許可）／ `deny`（禁止） |

#### ネットワークアクセスの確認

このツールが外に出るのは、手元に無いものを取りに行くときだけです。解析そのものはネットワークに出ません。

| 取りに行くもの | 取得先 | 置き場所 |
|---|---|---|
| JBang 本体 | GitHub | `JBANG_DIR`（既定 `~/.jbang`） |
| JDK | Adoptium | `JBANG_DIR` の中 |
| 依存 jar（JDT） | Maven Central | `JBANG_REPO`（既定 `~/.m2/repository`） |

起動コマンドは jbang を呼ぶ前に、この 3 つが手元にあるかを調べます。すべて揃っていれば何も尋ねず、
`jbang` に `--offline` を渡して外に出ないようにします。足りないものがあるときは、何を取りに行くのかを挙げて尋ね、
許可されなければ実行しません。

端末が無いとき（パイプ・CI）は尋ねようがないので取りに行きません。許可するときは `JCHE_NETWORK=allow` を設定してください。

```bash
JCHE_NETWORK=allow ./java-call-hierarchy-exporter.sh config/config.properties
```

閉域ネットワークなど、そもそも外に出したくないときは `JCHE_NETWORK=deny` にします。
JDK と依存 jar を先に用意しておく方法は [Pleiades/Eclipse環境（閉域ネットワーク等）](#pleiadeseclipse環境閉域ネットワーク等) にあります。

GitHub Actions から使うとき（`uses: instreest/java-call-hierarchy-exporter@...`）は尋ねません。
ワークフローに書くこと自体が許可にあたり、端末も無いためです。詳しくは [docs/network-confirm-qa.md](docs/network-confirm-qa.md) の Q6。

### JBangによる実行（対話なし）

JBang のラッパースクリプトを `jbangw/` に同梱しているので、JBang のインストールは不要です
（同梱スクリプトの出所・ライセンス（MIT）・当リポジトリでの修正点は [jbangw/README.md](jbangw/README.md) を参照）。

```bat
rem Windows（コマンドプロンプト）
.\jbangw\jbang.cmd src\CallHierarchyExporter.java config\config.properties
```

```bash
# Linux / macOS / Git Bash
./jbangw/jbang src/CallHierarchyExporter.java config/config.properties
```

このツールが必要とするJDK・依存jarは、実行環境になければ初回実行時に取得します（`%userprofile%/.jbang/`配下に保存。
上記の起動コマンドでプロジェクトの中を選んでいれば、`launcher.properties` の `JBANG_DIR` の場所）。
取りに行く前に必ず確認します（[ネットワークアクセスの確認](#ネットワークアクセスの確認)）。

設定ファイルは複数渡せます。渡した順に処理し、設定ファイルごとに別の出力フォルダができます
（[複数のプロジェクトをまとめて解析する](#複数のプロジェクトをまとめて解析する)）。

```bash
./jbangw/jbang src/CallHierarchyExporter.java config/app-a.properties config/app-b.properties
```

### Eclipse（Pleiades）でソースを開く

リポジトリ直下に `pom.xml` があるので、Eclipse 同梱の m2e（Maven 連携）で依存 jar を自動取得できます。

1. 「ファイル > インポート > Maven > 既存の Maven プロジェクト」で、このリポジトリのフォルダを選ぶ
2. 取り込み後、JDT Core 一式が Maven Central から `%userprofile%\.m2\repository` に取得され、ビルドパスに載る
3. `CallHierarchyExporter` を「Java アプリケーション」として実行するときは、実行構成の引数に
   `config/config.properties` を指定する（複数指定可）

`pom.xml` は Eclipse で開くためだけのもので、jbang での実行には使われません。依存の版は
`src/CallHierarchyExporter.java` の `//DEPS` 行と同じにしてあります（`test/pom/run.sh` が食い違いを検出）。
JDT の版を変えるときは両方を書き換えてください。`pom.xml` には実行 JDK の版（`//JAVA 25`）は書いておらず、
Eclipse はワークスペースに登録済みの JDK（17 以上）を使います。そのため Eclipse から実行した解析結果は
jbang 経由（JDK 25）と一部異なりうることに注意してください
（[docs/cache-dependency-jars-qa.md](docs/cache-dependency-jars-qa.md) の Q20）。
JBang 本家の Eclipse 連携プラグイン（jbang-eclipse）を入れると、この `pom.xml` とビルドパスを取り合って
どちらか一方が壊れ続けます。併用しないでください。
Gradle を選ばなかった理由を含め、実装時に迷った点は
[docs/eclipse-maven-qa.md](docs/eclipse-maven-qa.md) にあります。

---

## 依存 jar の自動取得（Maven / Gradle）

依存 jar は `library.folders` に「集めたフォルダ」を指定するのが基本ですが、**`library.folders` を空欄にすると**、
Maven / Gradle のプロジェクトではビルドファイルを読んで依存 jar を自動で集めます。
ビルドツール（`mvn` / `gradle`）は実行せず、ネットワークにも出ません。`library.folders` に指定がある場合は自動取得しません。

1. 各ソースフォルダから `project.root` まで上位へ辿り、最初に見つかった `pom.xml` / `build.gradle(.kts)` /
   `settings.gradle(.kts)` のあるフォルダをプロジェクトとみなします（マルチモジュールなら、ソースフォルダを持つ
   モジュールごと）。両方のビルドファイルがあるときは Eclipse の `.project`（m2e / Buildship の nature）と
   `.classpath` でどちらとして開かれているかを見て、それも無ければ Maven を使います（`library.build.tool` で切り替え可）
2. ビルドファイルから直接の依存を読み、jar と POM を**ローカルリポジトリ**から探します。既定は Maven の
   `~/.m2/repository`（`~/.m2/settings.xml` の `localRepository` があればそこ）と Gradle の
   `~/.gradle/caches/modules-2/files-2.1` で、Eclipse の m2e / Buildship が依存を取得した場所と同じです。
   別の場所は `library.repositories` で指定します
3. 推移的な依存は、ローカルリポジトリにある POM を辿って集めます（親 POM、`dependencyManagement`、BOM の
   import、`${...}`、exclusions、optional / test / provided の除外を Maven と同じ規則で扱います。版の衝突は
   Maven なら近い方、Gradle なら高い方が勝ちます）
4. マルチモジュールの兄弟モジュール（Maven のリアクタ、Gradle の `project(':x')`）は、その `target/classes` /
   `build/classes` / Buildship の `bin/main` と、そのビルドファイルの依存で解決します。`mvn install` は要りません
5. 集めた jar とクラスフォルダをそのまま JDT に渡します。jar はローカルリポジトリに置かれたままで、コピーしません

集めた一覧（パス・座標・要求元の連鎖）は出力フォルダの `resolved-classpath.txt` に残ります。
キャッシュの `L` 行にも同じパスが入るので、依存 jar を変えたときの差分更新（[docs/cache-design.md](docs/cache-design.md)）はそのまま効きます。

Gradle のビルドファイルはプログラムなので、読めるのは宣言的な書き方だけです。

| 読める | 例 |
|---|---|
| 文字列の座標 | `implementation 'g:a:v'`、`implementation("g:a:v")`、`api "g:a:$ver"` |
| map 形式 | `implementation group: 'g', name: 'a', version: 'v'`、`(group = "g", name = "a", version = "v")` |
| 変数 | `gradle.properties`、`ext { }`、`def` / `val` の文字列代入、`${property('x')}` |
| 版カタログ | `libs.foo.bar`、`libs.bundles.x`（`gradle/libs.versions.toml`、settings の `from(files(...))`） |
| BOM | `platform('g:a:v')` / `enforcedPlatform(...)`（版の無い依存の版を決める） |
| 他プロジェクト | `project(':x')`、`projects.x` |
| ファイル | `files('lib/a.jar')`、`fileTree('lib')` |
| ロックファイル | `gradle.lockfile`（あれば解決済みの依存をそのまま使う） |

読めない宣言（プラグインが足す依存、ループや条件で組み立てた座標など）はログに「読めない依存の宣言」として出ます。
その場合は従来どおり jar を集めたフォルダを `library.folders` に指定してください。

| 設定 | 意味 |
|---|---|
| `library.build.tool` | `auto`（既定）/ `maven` / `gradle` / `none`（自動取得しない） |
| `library.repositories` | ローカルリポジトリ（カンマ区切り）。空欄なら上記の既定。Maven 形式でも Gradle のキャッシュ形式でも可 |

うまくいかないとき:

- ローカルリポジトリに無い jar は警告に出て、無いまま解析が続きます（その型を使う呼び出しは型解決に失敗します）。
  Eclipse や Maven / Gradle で一度依存を取得（ビルド）すればローカルリポジトリに入ります。このツールはダウンロードしません
- 兄弟モジュールがビルドされていない（`target/classes` 等が無い）ときは、そのモジュールのソースも `source.folders` に
  含めてください。ソースから解決されます
- 対応の範囲と判断は [docs/build-tool-classpath-qa.md](docs/build-tool-classpath-qa.md) にまとめています

---

## インスタンス解析条件を外から与える（プラグイン）

DI コンテナで注入されるフィールドや、キーで実装を切り替えるファクトリメソッドは、ソースを読むだけでは
具象クラスが決まりません。既定ではインターフェースの実装を全部候補に挙げる（CHA）ため、
呼び出し階層が実装の数だけ枝分かれします。解決の条件を外から与えると、1 件に絞れます。

Spring の `@Autowired` などは注釈から自動で解決するので、設定は要りません
（[docs/spring-di-qa.md](docs/spring-di-qa.md)）。ここで扱うのは、**注釈からは分からない**もの
── XML や独自形式の DI 設定ファイル、キーで実装を切り替えるファクトリ、社内フレームワークの仕掛けです。
拡張は Spring の判定より先に効くので、自動の解決を上書きすることもできます。

解決は 2 つの段階に分かれています。必要な情報が手に入るタイミングが違うためです。

| 段階 | いつ | すること | 例 |
| --- | --- | --- | --- |
| フェーズA | ソースを読みながら | 呼び出し箇所の手がかりを拾う | `DaoFactory.get("USER_DAO")` の `"USER_DAO"` |
| フェーズB | グラフを組み立てるとき | 手がかりと宣言型から具象クラスを決める | `"USER_DAO"` → `jp.co.xxx.dao.UserDaoImpl` |

### 1. 対応表を書くだけで済ませる（同梱の実装を使う）

多くの場合、条件は「この宣言型（またはこの手がかり）のときは、この具象クラス」という対応表に落ちます。
その形なら Java を書く必要はありません。

```properties
# config/config.properties
resolver.hint.collectors=jche.builtin.FactoryKeyCollector
plugin.factory.methods=jp.co.xxx.DaoFactory#get
resolver.candidate.providers=jche.builtin.TypeMappingProvider
plugin.mapping.files=mapping.properties
```

```properties
# config/mapping.properties（UTF-8）
# 宣言型 -> 具象型（DI 設定から機械的に書き出せる形）
jp.co.xxx.dao.UserDao = jp.co.xxx.dao.UserDaoImpl
# 手がかり -> 具象型（区切りは @。properties では : と = が区切り文字なので使えない）
FACTORY_KEY@USER_DAO = jp.co.xxx.dao.UserDaoImpl
```

### 2. 自分で書く（設定ファイルの形式が独自、条件が複雑な場合）

`plugin.folders` のフォルダに `.java` を置くだけです。**実行時にコンパイルされる**ので、
Maven や Gradle でのビルドも jar 作りも要りません（すでに `.class` / `.jar` があるなら、それを置いても構いません）。

```properties
plugin.folders=plugins
resolver.candidate.providers=jp.co.xxx.MyDiProvider
```

```java
// config/plugins/MyDiProvider.java
package jp.co.xxx;

public class MyDiProvider implements jche.extension.TypeCandidateProvider {
    // init(Properties, Path) で独自形式の DI 設定ファイルを読み、
    // candidates(...) で具象クラスの FQN を返す
}
```

実装するインターフェースは `jche.extension.CallSiteHintCollector`（フェーズA）と
`jche.extension.TypeCandidateProvider`（フェーズB）です。動く例は
[test/regression/plugin/](test/regression/plugin/)（設定・対応表・自前の拡張・期待出力）にあります。

- 具象クラスを拡張が決めた行は、`call-hierarchy.csv` の最終列に `解決:<ラベル>`（同梱の実装なら `MAPPING`）が付きます
- フェーズAの拡張はキャッシュに手がかりを書くので、拡張やその設定・実装ファイルを変えると、
  キャッシュは自動的に捨てられて全件解析し直しになります（変え忘れによる古い結果の混入を防ぐため）
- 拡張の読み込み・コンパイルに失敗しても解析は止まりません。警告を出して拡張なしで続けます
- 設計上の判断と、実装時に迷った点は [docs/instance-analysis-plugin-qa.md](docs/instance-analysis-plugin-qa.md) にまとめています

---

## 複数のプロジェクトをまとめて解析する

設定ファイルを引数に複数渡すと、渡した順に 1 つずつ処理します。設定ファイルは互いに独立で、
それぞれの `output.folder` の下に `<解析開始日時>_<プロジェクト名>` のフォルダができます
（[出力されるファイル](#出力されるファイル)）。

```bash
./jbangw/jbang src/CallHierarchyExporter.java config/app-a.properties config/app-b.properties config/batch.properties
```

- 1 つの設定が失敗（設定ファイルが無い、`project.root` が無い等）しても、残りの設定は処理します。
  失敗した設定のエラーとスタックトレースは標準出力（と、出力フォルダを作れていればその `run.log`）に出ます
- 最後に設定ごとの結果（`OK` と出力フォルダ、または `FAIL` と原因）を一覧で出します。
  1 つでも失敗があれば終了コードは 1 です
- ログの経過時間 `[分:秒]` は設定ごとに 0 から数え直します

同じプロジェクトを指す設定ファイルが複数あっても（起点 `entry.packages` だけ違う等）、
キャッシュは `project.root` ごとに 1 つを共有するので、2 つ目以降の解析はキャッシュの再利用だけで済みます。

## GitHub Actions から使う

リポジトリ直下の [`action.yml`](action.yml) が GitHub Actions のアクションです。
利用者のワークフローから `uses:` で呼ぶと、解析対象のリポジトリを解析して CSV を出力し、
アーティファクトとしてアップロードします。JBang も JDK も設定ファイルもアクションの中で用意するので、
ワークフローに書くのは解析対象の指定だけです。

```yaml
      - uses: actions/checkout@v5
      # library-folders を空欄にして依存 jar を自動で集める場合は、先にローカルリポジトリへ
      # 依存を取得しておく（このツールはネットワークに出ないため）
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

参照する版の書き方、入力と出力の一覧、用意済みの設定ファイルを渡す方法、キャッシュや作業ツリーの注意、
Actions 以外の CI から使うとき（`JCHE_OUTPUT_DIR_FILE`）は [docs/github-actions.md](docs/github-actions.md) にあります。
このリポジトリ自身も [.github/workflows/call-hierarchy.yml](.github/workflows/call-hierarchy.yml) で
自分のソースをこのアクションで解析しています（そのまま写して使える最小の形です）。

## キャッシュの置き場所

解析結果のキャッシュは出力フォルダには置かず、解析対象プロジェクトごとの「サイドカー」として
**このツールのプロジェクトフォルダ**（`src/CallHierarchyExporter.java` のあるフォルダ）の `.cache/` の下に作ります。

```
java-call-hierarchy-exporter/
└── .cache/
    ├── myapp_3f2a9c1e/analysis-cache.tsv      project.root=.../myapp
    └── batch_b71e0d44/analysis-cache.tsv      project.root=.../batch
```

フォルダ名は `<project.root のフォルダ名>_<project.root の絶対パスの SHA-256 先頭 8 桁>` です。
同じプロジェクトを指す設定ファイルは同じキャッシュを共有し、名前が同じでも場所が違うプロジェクト
（ブランチごとのチェックアウト等）は混ざりません。設定ファイルをどこに置いても、どこから実行しても、
キャッシュの場所は変わりません。

ツールのプロジェクトフォルダは、作業ディレクトリとその上位（次に、実行中のクラスの置き場所とその上位）から
`src/CallHierarchyExporter.java` を探して決めます。README の手順どおりリポジトリ直下で実行すれば見つかります。
見つからないときは警告を出して作業ディレクトリの `.cache/` に作ります。

`cache.folder` を指定すると、そのフォルダ（設定ファイルからの相対パス、または絶対パス）の下に
同じ形のプロジェクト別フォルダを作ります。回帰テストのようにケースごとにキャッシュを分けたいときに使います。
`cache.enabled=false` でもフェーズ 2 が読むためにキャッシュファイル自体は同じ場所に書かれます（再利用はしない）。

## キャッシュファイル設計

大規模なコードベースでも `OutOfMemoryError` にならないよう、解析結果はヒープに溜めずにキャッシュへ書き出し、
エッジは int の配列（CSR 形式）で持ち、ツリーは組み立てずに 1 行ずつ書き出します。
キャッシュには「AST から分かった事実」だけを入れ、判断は読む側で行うので、出力や解決の方針を変えても
キャッシュを作り直さずに済みます。差分更新の仕組み（他のファイルの変更・依存 jar の変更・実行 JDK の変更への追従）を含む
詳細は [docs/cache-design.md](docs/cache-design.md) にあります。

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

コンストラクタの呼び出し自体は行になりません。
コンストラクタ内からのメソッド呼び出しは行として出力されます。

行順は、rootメソッドのクラス順（ソースフォルダ順 → 完全修飾クラス名順 → 宣言行順）、rootメソッドからの呼び出し順（深さ優先）です。
具象クラスの候補が複数ある呼び出しは
候補ごとに 1 行で、宣言型自身の実装 → 下位型（直接の下位型は完全修飾クラス名順）の順に出ます。
末尾の `型解決に失敗（…）` の行はソースの並び順（ソースフォルダ順 → ファイルの相対パス順 → 呼び出し順）で出ます。

#### 注記

注記が付く場合は `call-hierarchy` の**最後の要素**として出ます。

| 注記 | 意味 |
|---|---|
| `[CYCLE]` | この経路上で既に呼んでいるメソッドに戻る呼び出し。ここで打ち切る |
| `深さ制限(N)のため打ち切り` | `max.depth` に達した |
| `CHA候補N件（未展開）: 理由` | 実装を1つに絞れなかった。候補は1件ずつ行になるが、その先へは降りない（候補数^深さで爆発するため）。理由は下表 |
| `実装なし（宣言のまま）: 理由` | 本体を持つ実装がソース上に1つも無い。宣言のまま出しているだけ |
| `実装はコンパイル時生成（名前）: FQN はアノテーション処理で生成されるためソース上に無い` | 実装がアノテーション処理でビルド時に生成される型への呼び出し（[docs/doma-generated-impl-qa.md](docs/doma-generated-impl-qa.md) 参照） |
| `ラムダ/メソッド参照の実装あり（未展開・本体は定義元メソッドに計上）` | その関数型インターフェースをラムダかメソッド参照も実装している。展開できないので候補には数えていない |
| `ソースなし（展開不可）` | 呼び出し先がjar内などでソースが無く、そこから先を辿れない |
| `外部ライブラリ（import推定・未検証）` | クラスパス不足で型解決できず、`import` 文から型名を推定した |
| `解決:DATAFLOW_NEW` | `new` された具象型から特定した（捕捉された変数を含む） |
| `解決:DATAFLOW_FACTORY` | ファクトリメソッドの戻り値から具象クラスを特定した |
| `解決:DATAFLOW_PARAM` | 呼び出し元から渡された引数を経路上で追跡して特定した |
| `解決:DATAFLOW_FIELD` | コンストラクタ注入されたフィールドを経路上で追跡して特定した |
| `解決:SPRING_DI` | DI コンテナ（Spring）の Bean 定義で候補が1つに定まった（[docs/spring-di-qa.md](docs/spring-di-qa.md) 参照） |
| `解決:SPRING_DI_QUALIFIER` | `@Qualifier` / `@Resource(name=...)` で指定された Bean 名で1つに定まった（同上） |
| `解決:ラベル` | インターフェース等から具象クラスに解決した（[具象クラスの解決](#具象クラスの解決)参照） |
| `解決:REFLECTION` | `Method.invoke` / `newInstance` を、リフレクションで指定されたメソッド・コンストラクタに解決した |
| `解決:REFLECTION_INIT` | `Class.forName` によるクラス初期化。そのクラスの static 初期化子（`<clinit>`）へ繋ぐ |
| `リフレクション候補N件（未展開）: 引数型が不明なため名前で照合` | `getMethod` の引数型（クラスリテラル）が揃わず、同名のメソッドを候補にした |
| `型解決に失敗（…）` | 呼び出し先の型を特定できなかった行（後述） |
| `被参照:EXACT` 等 | 被参照スキャンの行（後述） |


### `methods.csv` — ソース上の全メソッドとその呼び出し状況

| 列 | 内容 |
|---|---|
| `unresolvedCalls` | このメソッドの中で、具象クラスを1つに絞れなかった呼び出しの件数 |
| `unresolvedCause` | その理由（上の「理由」表と同じ。`実装なし（宣言のまま）` と `実装はコンパイル時生成（名前）` も入る。複数ある場合は `;` 区切り） |

| role | 意味 |
|---|---|
| `ENTRY_CANDIDATE` | 呼び出し元が無い。画面入口・デッドコード・テスト・リフレクション経由が混ざる |
| `ISOLATED` | 呼び出し元も呼び出し先も無い。デッドコードの疑いが濃い |
| `LEAF` | 呼び出し先が無い |
| `NORMAL` | 上記以外 |

行はソースの並び順（ソースフォルダ順 → ファイルの相対パス順 → 宣言行順）で出ます。
「よく呼ばれている共通処理」を探したいときは、`inDegree` 列でソート・フィルタしてください。
コンストラクタ（`<init>`）は出力しません。


#### **Eclipseでのソースコードジャンプ**
`call-hierarchy.csv` の行をコピーし、Eclipseの「Javaスタック・トレース・コンソール」に貼り付けると、
`(ファイル:行数)` の部分がハイパーリンクになり、ソースコードへ飛べます。

1. メニューから ウィンドウ(Window) ＞ ビューの表示(Show View) ＞ コンソール(Console) を選択
2. コンソールビュー右上（ツールバー）の「コンソールのオープン(Open Console)」ボタン
   （プラスの付いたモニターのアイコン）の横の「▼」をクリックし、
   「Javaスタック・トレース・コンソール(Java Stack Trace Console)」を選択
3. `call-hierarchy.csv`のテキストをそのコンソールに貼り付ける


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
| 5 | `SPRING_DI` / `SPRING_DI_QUALIFIER` | DI コンテナ（Spring）の Bean 定義で候補を絞った（後述） |
| 6 | `CHA` | 候補が複数のまま（低確度） |
| — | `GENERATED_IMPL:名前` | 実装がコンパイル時のアノテーション処理で生成される型（`NO_IMPL` の特殊形。後述） |


---

## ライセンス

Copyright 2026 Inoue Kazuhiro ([@instreest](https://github.com/instreest)). SPDX-License-Identifier: Apache-2.0

