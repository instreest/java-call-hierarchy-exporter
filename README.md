# java-call-hierarchy-exporter

Javaプロジェクト全体のメソッド呼び出し階層を一括で抽出してCSVファイルに出力するツールです。

> **English:** It's exports the whole-project java method call hierarchy to CSV file.
> Apache-2.0. Documentation is in Japanese.

- 使い方・出力形式 … このファイル
- 設定項目 … [config/config.properties](config/config.properties)（コメントに全項目の説明）
- CI から使う … [GitHub Actions から使う](#github-actions-から使う)

---

## Quick start

つツールを最小構成で試す手順です。初回は JDK と 依存モジュール のキャッシュファイル格納場所をダウンロードします。

1. 設定ファイルをコピーして編集する … [`config/config.properties`](config/config.properties) をコピーして、
   `project.root`（解析対象プロジェクトのフォルダ）をセットします。

2. 実行する … リポジトリ直下の起動コマンドを実行し、メニューで `1) 解析を実行する` を選びます。
   初回は JDK と JBang のキャッシュファイル格納場所を尋ねられるので、「このプロジェクトフォルダ内」を選んでください。

   ```bat
   rem Windows（コマンドプロンプト。エクスプローラーからダブルクリックでも可）
   .¥java-call-hierarchy-exporter.cmd
   ```

   ```bash
   # Linux / macOS / Git Bash
   ./java-call-hierarchy-exporter.sh
   ```

3. 結果を見る … `config/<解析開始日時>_<プロジェクト名>/` に `call-hierarchy.csv`（呼び出し階層）と
   `methods.csv`（メソッド一覧）ができます。UTF-8（BOM 付き）なのでそのまま Excel で開けます。

### コマンドからの実行
設定ファイルを引数に渡します（JBang は `jbangw/` に同梱）。

```bash
./jbangw/jbang src/CallHierarchyExporter.java config/config_yours.properties
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

出力は実行のたびに、設定ファイルの `output.folder`（既定 `.` ＝設定ファイルと同じフォルダ。
相対パスの起点は設定ファイルのフォルダ）の下に
**`<解析開始日時>_<プロジェクト名>`** のフォルダを作ってまとめます。プロジェクト名は `project.root` の
フォルダ名です。いつ・どのプロジェクトを解析した結果かがフォルダ名だけで分かり、前回の結果は上書きされません。

```
config/
├── config.properties             設定ファイル（既定。解析対象ごとに増やせる）
└── 20260907-163000_myapp/        実行ごとの出力フォルダ
    ├── call-hierarchy.csv        呼び出し階層リスト
    ├── methods.csv               メソッド全体リスト
    ├── config.properties         この実行に使った設定ファイルの複製（渡したファイル名のまま）
    ├── run.log                   標準出力と同じ内容の実行ログ（UTF-8）
    └── resolved-classpath.txt    ビルドファイルから依存 jar を集めたときだけ。集めた jar の一覧と要求元
```

| ファイル | 内容 |
|---|---|
| `call-hierarchy.csv` | 呼び出し階層リスト |
| `methods.csv` | メソッド全体リスト（ソース上の全メソッドとその呼び出し状況） |

CSV はUTF-8（BOM付き）なのでExcelで開けます。ファイル名は固定です。
例えば既定の `config/config.properties` を指定した場合は `config/20260907-163000_myapp/` のように出ます。
同じ秒に同じプロジェクトを解析すると `_2`, `_3` … が付きます。

解析結果のキャッシュは出力フォルダには入りません（[キャッシュの置き場所](#キャッシュの置き場所)）。
各列の意味と注記の詳細は [出力ファイル](#出力ファイル) にあります。

### `call-hierarchy.csv` — 呼び出し元が無いメソッドを起点にした呼び出し階層

呼び出し元、呼び出し先、起点メソッド、呼び出し階層（複数）を出力したCSVファイルです。
呼び出し元ごとに1行出力します。フィルタすることで起点メソッドと呼び出し階層が一覧化できます。
出力ソート順は、rootのソースフォルダ → rootの完全修飾クラス名 → rootの宣言行 → コード呼び出しの順序です。

```csv
caller,callee,root,call-hierarchy
at jp.co.example.action.OrderAction.execute(OrderAction.java:50),jp.co.example.service.OrderService.findOrder(String),OrderAction.execute,OrderService.findOrder
at jp.co.example.service.OrderService.findOrder(OrderService.java:25),jp.co.example.dao.OrderDaoImpl.selectById(long),OrderAction.execute,OrderService.findOrder,OrderDaoImpl.selectById
```

### `methods.csv` — ソース上の全メソッドとその呼び出し状況

各クラスの宣言メソッドとその情報を一覧出力したCSVファイルです。
出力ソート順は、ソースフォルダ → ファイルの相対パス → 宣言行順の順序です。

```csv
method,declaringType,typeKind,file,line,hasBody,inDegree,outDegree,role,reachable,unresolvedCalls,unresolvedCause
OrderAction.execute(),jp.co.example.action.OrderAction,C,OrderAction.java,45,1,0,1,ENTRY_CANDIDATE,1,0,
OrderService.findOrder(String),jp.co.example.service.OrderService,C,OrderService.java,20,1,1,1,NORMAL,1,1,フィールド変数
OrderDao.selectById(long),jp.co.example.dao.OrderDao,I,OrderDao.java,8,0,0,0,ISOLATED,0,0,
OrderDaoImpl.selectById(long),jp.co.example.dao.OrderDaoImpl,C,OrderDaoImpl.java,15,1,1,0,LEAF,1,0,
```



---

## 実行方法の詳細

Quick start で使った起動コマンドの詳しい説明と、JBang を直接使う方法、閉域ネットワーク向けに
Eclipse（Pleiades）の jar でコンパイルして動かす方法、Eclipse でソースを開く方法です。

### 設定ファイル

既定の設定ファイル [`config/config.properties`](config/config.properties) の
**`project.root`** **`source.folders`** **`library.folders`** **`source.encoding`** を書き換えます
（次の起動コマンドの「設定ファイルを新しく作る」で、解析対象のフォルダを入力してこれらを埋めた設定ファイルを作ることもできます）。  
Maven / Gradle のプロジェクトなら `library.folders` は空欄でよく、`pom.xml` / `build.gradle` を読んで
ローカルリポジトリ（`~/.m2/repository` 等）にある依存 jar を自動で使います
（[依存 jar の自動取得](#依存-jar-の自動取得maven--gradle)）。全項目の説明は設定ファイル内のコメントにあります。

### 起動コマンド（対話モード）

リポジトリ直下の `jche.cmd`（Windows）/ `jche.sh`（Linux / macOS / Git Bash）を実行すると、
メニューで操作する対話モードが立ち上がります。どのフォルダから実行してもかまいません。

```bat
rem Windows（コマンドプロンプト。エクスプローラーからダブルクリックでも可）
jche.cmd
```

```bash
# Linux / macOS / Git Bash
./jche.sh
```

初回は、このツールが使う JDK と JBang（合わせて数百 MB）を **このプロジェクトの中（`.jbang/`）** に置くか
**ユーザーのホーム（`~/.jbang`、JBang の既定）** に置くかを尋ねます。選んだ内容は `launcher.properties`
（リポジトリ直下。Git では追跡しない）に保存され、次回からは尋ねません。
プロジェクトの中を選ぶと他の環境を汚さず、フォルダごと消せば元に戻ります。
`jche.cmd` だけは文字コードが MS932（Shift_JIS）です（コマンドプロンプトがバッチファイルを画面のコードページで読むため。
編集するときは MS932 のまま保存してください）。

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
| 3) 環境設定 | JDK / JBang の置き場所、依存 jar の置き場所、ヒープ上限（`-Xmx`）、`jbang run` の追加オプション（`--offline` 等）。`launcher.properties` に保存し、その場で再起動して反映できる |
| 4) 実行環境の状態 | 実際に使っている JDK・JDT の jar・置き場所とその大きさ・解析キャッシュの一覧 |

設定ファイルを引数に渡すと対話なしで解析します（下記の jbang 直接実行と同じ。バッチやタスクスケジューラ向け）。

```bat
jche.cmd config\app-a.properties config\app-b.properties
```

`launcher.properties` の項目は次のとおりです（対話モードの「環境設定」で書き換えるほか、手で編集してもかまいません。
キーはそのまま環境変数になります）。

| キー | 意味 |
|---|---|
| `JBANG_DIR` | JBang 本体と JDK の置き場所。空欄なら `~/.jbang`。相対パスはリポジトリ直下が起点 |
| `JBANG_REPO` | 依存 jar（JDT）の置き場所。空欄なら `~/.m2/repository` |
| `JCHE_JAVA_OPTS` | 解析を動かす JVM のオプション（例: `-Xmx4g`） |
| `JCHE_JBANG_OPTS` | `jbang run` に足すオプション（例: `--offline`、`--java 21`） |

起動コマンドの設計で迷った点は [docs/cli-app-qa.md](docs/cli-app-qa.md) にあります。

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

このツールが必要とするJDK・依存jarは、実行環境になければ初回実行時に自動で取得されます（`%userprofile%/.jbang/`配下に保存。
上記の起動コマンドでプロジェクトの中を選んでいれば、`launcher.properties` の `JBANG_DIR` の場所）。

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
キャッシュの `L` 行にも同じパスが入るので、[依存 jar を変えたとき](#依存-jar-を変えたとき)の差分更新はそのまま効きます。

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
（[Spring による DI の解決](#spring-による-di-の解決)）。ここで扱うのは、**注釈からは分からない**もの
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
name: call hierarchy

on:
  workflow_dispatch:
  push:

jobs:
  export:
    runs-on: ubuntu-latest
    steps:
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

出力は `call-hierarchy` という名前のアーティファクト（`upload-artifact` 入力で切れます）に入ります。
中身は通常の実行と同じ `<解析開始日時>_<プロジェクト名>/` フォルダです
（[出力されるファイル](#出力されるファイル)）。ジョブのサマリには出力フォルダと CSV の行数が出ます。

### 参照する版の指定

`uses:` の `@` の後ろには Git の参照（ブランチ・タグ・コミット SHA）を書きます。
**タグは必須ではありません**。リリースタグを付けていない間はブランチ名で参照できます。

| 書き方 | 意味 |
| --- | --- |
| `@main` | 既定ブランチの最新。タグを運用しない場合はこれ。ツール側の変更がそのまま次回の実行に入る |
| `@0123456789abcdef...`（40 桁のコミット SHA） | その時点のコードに固定する。ブランチが進んでも動きが変わらない。再現性が要る場合はこちら |
| `@v1` などのタグ | タグを打った場合。タグを動かすことで利用者側を書き換えずに版を切り替えられる |

同じリポジトリの中のワークフローからは、参照そのものが要りません（`uses: ./`。
このリポジトリの `.github/workflows/smoke.yml` の `action` ジョブがその形です）。

社内の複製やフォークを使う場合、あるいは取得元を明示したい場合は、`actions/checkout` で
ツールを別フォルダへ取り出してからローカル参照する書き方もできます。

```yaml
      - uses: actions/checkout@v5            # 解析対象（自分のリポジトリ）

      - uses: actions/checkout@v5            # ツール本体
        with:
          repository: instreest/java-call-hierarchy-exporter
          ref: main                          # ブランチ・タグ・コミット SHA
          path: .jche-tool

      - uses: ./.jche-tool
        with:
          source-folders: src/main/java
```

この形では `path:` に取り出したフォルダがアクションの場所になり、解析対象は
ワークスペース（1 つ目のチェックアウト）のままです。プライベートリポジトリから取り出す場合は
2 つ目の `actions/checkout` に `token:` が要ります。

### 入力

設定ファイルを自分で用意しない場合は、次の入力から設定ファイルを 1 つ生成します。
意味は同名の設定項目（[config/config.properties](config/config.properties) のコメント）と同じです。

| 入力 | 既定値 | 対応する設定項目 |
| --- | --- | --- |
| `project-root` | `.`（ワークスペース） | `project.root` |
| `source-folders` | `src/main/java` | `source.folders` |
| `source-encoding` | `UTF-8` | `source.encoding` |
| `source-level` | （空欄） | `source.level` |
| `library-folders` | （空欄。ビルドファイルから自動取得） | `library.folders` |
| `library-build-tool` | `auto` | `library.build.tool` |
| `library-repositories` | （空欄。`~/.m2/repository` 等） | `library.repositories` |
| `external-library-folders` | （空欄） | `external.library.folders` |
| `entry-packages` | （空欄。呼び出し元が無いメソッドが起点） | `entry.packages` |
| `exclude-packages` | `java.**,javax.**` | `exclude.packages` |
| `extra-config` | （空欄） | 上に無い項目を「1 行 1 項目」で追記する |
| `output-folder` | `call-hierarchy-output` | `output.folder` |

`project-root` と `output-folder` はワークスペースからの相対パス（または絶対パス）、
`source-folders` などは `project-root` からの相対パスです。

実行環境と出力の入力は次のとおりです。

| 入力 | 既定値 | 内容 |
| --- | --- | --- |
| `config` | （空欄） | 用意済みの設定ファイルを使う。改行またはカンマ区切りで複数渡せる。指定すると上の生成用の入力は使わない |
| `java-version` | `25` | ツール自身を動かす JDK。空欄にすると `actions/setup-java` を飛ばす（自分で用意する場合） |
| `java-distribution` | `temurin` | `actions/setup-java` の `distribution` |
| `cache` | `true` | JBang 本体と JDT の jar をワークフロー実行間でキャッシュする |
| `upload-artifact` | `true` | 出力フォルダをアーティファクトにする |
| `artifact-name` | `call-hierarchy` | アーティファクトの名前 |
| `artifact-retention-days` | （空欄） | アーティファクトの保持日数 |

### 設定ファイルを渡す（`config` 入力）

入力で表せない項目まで細かく指定したいときや、手元と CI で同じ設定を使いたいときは、
設定ファイルをリポジトリに置いて `config` で渡します。**`config` を指定すると、上の生成用の入力
（`project-root` / `source-folders` … と `output-folder`）は使われません。** 設定ファイルの内容がそのまま効きます。

```yaml
      - uses: actions/checkout@v5

      - uses: instreest/java-call-hierarchy-exporter@main
        with:
          config: ci/call-hierarchy.properties
```

パスは**ワークスペース（チェックアウト先）からの相対パス**か絶対パスです。
無いファイルを指定するとその場で失敗します（`::error::config に指定された設定ファイルがありません`）。

#### 設定ファイルの書き方（CI 向けの例）

**相対パスの起点は「その設定ファイルが置かれているフォルダ」**です（`project.root` / `output.folder` /
`cache.folder`）。`source.folders` などは `project.root` からの相対です。
リポジトリ直下に `ci/call-hierarchy.properties` を置くなら、`project.root` は 1 つ上（`..`）になります。

```properties
# ci/call-hierarchy.properties（このファイルのフォルダが相対パスの起点）
project.root=..
source.folders=src/main/java,src/generated/java
source.encoding=UTF-8

# 空欄にすると pom.xml / build.gradle を読んで ~/.m2 等から依存 jar を集める
# （ワークフロー側で先に mvn -B dependency:go-offline を実行しておくこと）
library.folders=
library.build.tool=auto

# jar をリポジトリに同梱している場合は、集めたフォルダを project.root からの相対で指定する
#library.folders=libs

entry.packages=
exclude.packages=java.**,javax.**,org.springframework.**

# 出力先。既定は「この設定ファイルと同じフォルダ」。相対パスはこのファイルのフォルダの配下だけ
# 指定できる（.. で外へ出るとエラー。外へ出すときは絶対パス）
output.folder=output
output.encoding=UTF-8-BOM
max.rows=5000000

# キャッシュの置き場所は空欄のままでよい。アクションのフォルダの下に作られ、
# 解析対象リポジトリのチェックアウトは汚れない
cache.folder=
```

設定できる項目の一覧と意味は [config/config.properties](config/config.properties) のコメントにあります。
手元で `./jche.sh ci/call-hierarchy.properties` と実行したときと同じ設定なので、CI で出た結果を手元で再現できます。

**出力先だけは注意**してください。`output.folder` の既定は「設定ファイルと同じフォルダ」なので、
指定しないと解析対象リポジトリのチェックアウトの中（設定ファイルの隣）に出力フォルダができます。
相対パスは設定ファイルのフォルダの配下しか指せない（`..` で外へ出るとエラーになります）ので、
上の例のように配下へ出すか、リポジトリの外に出したい場合は絶対パス（Linux のランナーなら
`/tmp/call-hierarchy-output` など）を書きます。リポジトリ内に出すなら、その場所を `.gitignore` に
足しておくと `git diff --exit-code` のような検査と併用できます。

#### 複数渡す

改行区切り（`|`）またはカンマ区切りで複数渡せます。渡した順に 1 つずつ処理し、
設定ファイルごとに別の出力フォルダができます（[複数のプロジェクトをまとめて解析する](#複数のプロジェクトをまとめて解析する)）。

```yaml
      - uses: instreest/java-call-hierarchy-exporter@main
        id: export
        with:
          config: |
            ci/app-a.properties
            ci/app-b.properties
            ci/batch.properties
```

- 1 つが失敗しても残りは処理し、最後にまとめて報告します。1 つでも失敗すればステップは失敗します
  （出来ているところまではアーティファクトに入ります）
- `output-dirs` 出力に、成功した設定の出力フォルダが 1 行に 1 つ並びます。
  `output-dir` / `csv` / `methods-csv` / `run-log` は**最初の設定**のものです
- アーティファクトは 1 つにまとまります（それぞれの出力フォルダが共通の親からの構造で入ります）
- どの設定で走ったかは、各出力フォルダに入る設定ファイルの複製で分かります

### 出力

| 出力 | 内容 |
| --- | --- |
| `output-dir` | 最初の設定ファイルの出力フォルダ（絶対パス） |
| `output-dirs` | すべての出力フォルダ（1 行に 1 つ） |
| `csv` | `call-hierarchy.csv` のパス（最初の出力フォルダのもの） |
| `methods-csv` | `methods.csv` のパス（最初の出力フォルダのもの） |
| `run-log` | `run.log` のパス（最初の出力フォルダのもの） |

後続のステップで CSV を読むときは、`${{ }}` を `run:` に直接書かず環境変数を経由するのが安全です。

```yaml
      - uses: instreest/java-call-hierarchy-exporter@main
        id: export
        with:
          source-folders: src/main/java

      - name: 行数を数える
        env:
          CSV: ${{ steps.export.outputs.csv }}
        run: wc -l "$CSV"
```

### 使うときの注意

- **依存 jar** … `library-folders` を空欄にすると `pom.xml` / `build.gradle` を読んでローカルリポジトリから
  依存 jar を集めますが、このツールはネットワークに出ません。ランナーの `~/.m2/repository` は空なので、
  先に `mvn -B dependency:go-offline`（Gradle なら依存を取得するタスク）を実行しておいてください。
  取得しないまま実行しても解析自体は動きますが、jar の型が解決できず呼び出しが欠けます
- **JDK** … ツール自身は JDK 25 で動きます（`//JAVA 25`）。`java-version` を空欄にして自分で用意する場合も
  25 を入れてください。解析対象のビルドに別の JDK が要る場合は、そのステップで別途セットアップします
- **キャッシュ** … `cache: true` のとき、JBang 本体（`~/.jbang`）と JDT の jar（`~/.m2/repository/org/eclipse`）を
  ワークフロー実行間でキャッシュします。解析結果のキャッシュ（`.cache/`）は毎回作り直しになります。
  `actions/checkout` はファイルの更新時刻をチェックアウト時刻にするため、差分判定（更新時刻とサイズ）が
  必ず「変更あり」になり、持ち越しても再利用されないためです
- **作業ツリー** … 生成した設定ファイルは `RUNNER_TEMP` に、解析キャッシュはアクション自身のフォルダに
  作るので、解析対象リポジトリのチェックアウトには出力フォルダ以外を作りません
  （既定は `call-hierarchy-output/`。`.gitignore` に足しておくと `git diff --exit-code` 等と併用できます）

### Actions 以外の CI から使うとき

環境変数 `JCHE_OUTPUT_DIR_FILE` にファイルのパスを渡して実行すると、設定ファイルごとの出力フォルダの
絶対パスを、成功した順に 1 行ずつ UTF-8 でそのファイルに書きます。実行ごとに変わる出力フォルダ名
（`<解析開始日時>_<プロジェクト名>`）を、ログを読まずに受け取れます（`action.yml` もこれを使っています）。

```bash
JCHE_OUTPUT_DIR_FILE=out-dirs.txt ./jbangw/jbang src/CallHierarchyExporter.java config/config.properties
cat out-dirs.txt   # /path/to/config/20260907-163000_myapp
```

起動コマンド（`./jche.sh a.properties`、Windows は `jche.cmd`）から実行したときも同じです
（対話モードで解析した場合も書き出します）。

### このリポジトリ自身での使用例

このリポジトリも、自分のソース（`src/`）をこのアクションで解析しています
（[.github/workflows/call-hierarchy.yml](.github/workflows/call-hierarchy.yml)。`main` への push と手動実行）。
依存 jar は `pom.xml` から自動で集めるので、その前に `mvn -B dependency:go-offline` を置いてあります。
そのまま写して使える最小の形なので、書き方に迷ったらこのファイルを見てください。

実装時に迷った点は [docs/github-actions-qa.md](docs/github-actions-qa.md) にまとめています。

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
CHA の候補（インターフェースの実装クラス）はキャッシュせず、毎回 `H` 行から計算するので、
jar の追加で実装クラスが増えた場合も、呼び出し側のファイルを解析し直さずに反映されます
（jar の基底クラスがソースのインターフェースを実装している構成では、その子クラスの `H` 行に
インターフェースも親として記録します）。
実行する JDK を変えたときはキャッシュ全体を作り直します（JDT は実行中の JVM の標準クラスも
解析対象のクラスパスに含めるため。何が変わるかは下記 docs の Q20）。
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
| `実装はコンパイル時生成（名前）: FQN はアノテーション処理で生成されるためソース上に無い` | 実装がアノテーション処理でビルド時に生成される型への呼び出し（[コンパイル時生成の実装](#コンパイル時生成の実装)参照） |
| `ラムダ/メソッド参照の実装あり（未展開・本体は定義元メソッドに計上）` | その関数型インターフェースをラムダかメソッド参照も実装している。展開できないので候補には数えていない |
| `ソースなし（展開不可）` | 呼び出し先がjar内などでソースが無く、そこから先を辿れない |
| `外部ライブラリ（import推定・未検証）` | クラスパス不足で型解決できず、`import` 文から型名を推定した |
| `解決:DATAFLOW_NEW` | `new` された具象型から特定した（捕捉された変数を含む） |
| `解決:DATAFLOW_FACTORY` | ファクトリメソッドの戻り値から具象クラスを特定した |
| `解決:DATAFLOW_PARAM` | 呼び出し元から渡された引数を経路上で追跡して特定した |
| `解決:DATAFLOW_FIELD` | コンストラクタ注入されたフィールドを経路上で追跡して特定した |
| `解決:SPRING_DI` | DI コンテナ（Spring）の Bean 定義で候補が1つに定まった（[Spring による DI の解決](#spring-による-di-の解決)参照） |
| `解決:SPRING_DI_QUALIFIER` | `@Qualifier` / `@Resource(name=...)` で指定された Bean 名で1つに定まった（同上） |
| `解決:ラベル` | インターフェース等から具象クラスに解決した（[具象クラスの解決](#具象クラスの解決)参照） |
| `解決:REFLECTION` | `Method.invoke` / `newInstance` を、リフレクションで指定されたメソッド・コンストラクタに解決した（[リフレクション](#リフレクション)参照） |
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

