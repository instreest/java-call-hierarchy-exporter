# 起動コマンドと実行方法

リポジトリ直下の起動コマンドの全仕様と、JBang を直接使う方法、閉域ネットワークでの動かし方。
最小の手順は [README の Quick start](../README.md#quick-start) にある。
実装時に迷った点は [cli-app-qa.md](cli-app-qa.md) と [cli-noninteractive-qa.md](cli-noninteractive-qa.md) にある。


## 設定ファイル

既定の設定ファイル [`config/config.properties`](../config/config.properties) で必須なのは **`project.root`** だけです。
次の項目は空欄のままなら `project.root` の中身から決めます（明示したいときだけ書き換えます）。

| 項目 | 空欄のときの決め方 |
|---|---|
| `source.folders` | `.classpath` の `kind="src"`、無ければ `src/main/java` → `src` → `<モジュール>/src/main/java` |
| `library.folders` | `pom.xml` / `build.gradle` を読んでローカルリポジトリ（`~/.m2/repository` 等）から自動取得（[依存 jar の自動取得](build-tool-classpath.md)）。ビルドファイルが無ければ `project.root` 直下の `lib` の `*.jar` |
| `source.encoding` | `pom.xml` の `project.build.sourceEncoding`、無ければ `UTF-8` |

起動コマンドの「設定ファイルを新しく作る」で、解析対象のフォルダを入力してこれらを埋めた設定ファイルを作ることもできます。
全項目の説明は設定ファイル内のコメントにあります。

## 起動コマンド

リポジトリ直下の `java-call-hierarchy-exporter.cmd`（Windows）/ `java-call-hierarchy-exporter.sh`（Linux / macOS / Git Bash）が
起動コマンドです。どのフォルダから実行してもかまいません。引数で振る舞いが変わります。

| 引数 | 動き |
|---|---|
| 設定ファイル（複数可） | 対話なしで解析する。メニューも質問も出ないので、バッチ・タスクスケジューラ・CI から呼べる（例外は下記の[ネットワークからの取得の確認](#ネットワークからの取得の確認)）。終了コードは、すべて成功なら 0、1 つでも失敗すれば 1、引数が誤っていれば 2、取得を取りやめたら 3 |
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
| 3) 環境設定 | JDK / JBang の置き場所、依存 jar の置き場所、ヒープ上限（`-Xmx`）、`jbang run` の追加オプション（`--offline` 等）。`launcher.properties` に保存し、その場で再起動して反映できる |
| 4) 実行環境の状態 | 実際に使っている JDK・JDT の jar・置き場所とその大きさ・解析キャッシュの一覧 |

`launcher.properties` の項目は次のとおりです（対話モードの「環境設定」で書き換えるほか、手で編集してもかまいません。
キーはそのまま環境変数になります）。

| キー | 意味 |
|---|---|
| `JBANG_DIR` | JBang 本体と JDK の置き場所。空欄なら `~/.jbang`。相対パスはリポジトリ直下が起点 |
| `JBANG_REPO` | 依存 jar（JDT）の置き場所。空欄なら `~/.m2/repository` |
| `JCHE_JAVA_OPTS` | 解析を動かす JVM のオプション（例: `-Xmx4g`） |
| `JCHE_JBANG_OPTS` | `jbang run` に足すオプション（例: `--offline`、`--java 21`） |
| `JCHE_ALLOW_DOWNLOAD` | ネットワークからの取得（JBang 本体・JDK・依存 jar）を尋ねずに行うなら `yes`、行わないなら `no`。空欄なら毎回尋ねる（下記） |

## ネットワークからの取得の確認

このツールが動くには JBang 本体・JDK 25・依存 jar（JDT ほか）が要り、無ければネットワークから取得します
（取得元は GitHub（JBang）、api.foojay.io（JDK）、Maven Central（依存 jar））。
起動コマンドは**取得の前に必ず操作者に確認**し、取得するものとそのサイズを示します。`n` なら何も取得せずに
終了コード 3 で終わります。引数あり（対話なし）でもこの確認だけは出ます。
取得済みのものだけで起動できるときは、ネットワークに出ず、確認も出ません。

```
java-call-hierarchy-exporter: ネットワークからの取得が必要です
  取得するもの : JBang 本体（約 15MB）、ツールを動かす JDK 25（約 135MB）、依存 jar（JDT ほか。約 15MB）
  通信量の目安 : 約 165MB（置き場所は約 485MB 増える。JDK は展開したものとアーカイブの両方が残るため）
                 実測に基づく目安。すでに手元にあるものは取得しないので、実際はこれ以下になる
  取得元       : github.com（JBang 本体）、api.foojay.io（JDK。実体は Adoptium の github.com）、Maven Central（依存 jar）
  置き場所     : C:\work\java-call-hierarchy-exporter\.jbang（JBang 本体・JDK）、C:\work\java-call-hierarchy-exporter\.jbang\repository（依存 jar）
ネットワークにアクセスして取得しますか？ [y/N]:
```

- 一覧とサイズは、そのとき手元に無いものだけを並べます（JBang 本体があれば JDK と依存 jar の 2 行になり、
  合計も 約 150MB になります）。サイズはあらかじめ実測した目安で、**サーバーに問い合わせることはしません**
  （サイズを尋ねること自体がネットワークアクセスになるため）。通信量と置き場所の量を分けているのは、
  JDK が展開後とアーカイブの両方残り、ディスクの増え方が通信量の 3 倍以上になるためです

- 確認は、起動コマンドが `jbang run --offline`（取得済みのものだけで動かす）で起動を試み、JDK や依存 jar
  （推移的な依存を含む）が足りずに起動できなかったときに出ます。JBang 本体とそれを動かす JDK は置き場所を見て、
  無ければ起動を試みる前に確認します。JBang 自身の更新確認（新しい版の問い合わせ）もしません
- 端末が無いとき（パイプ・CI・タスクスケジューラ）は確認できないので、取得せずに終了コード 3 で終わります。
  無人で動かす環境で取得してよいと決めてあるなら、`launcher.properties`（または環境変数）で
  `JCHE_ALLOW_DOWNLOAD=yes` にすると尋ねずに取得します。`no` にすると端末があっても取得しません（閉域ネットワーク向け。
  `JCHE_JBANG_OPTS` に `--offline` を書いた場合も同じ）。対話モードの「環境設定」の 5) でも切り替えられます
- 取得せずに動かすには、先に JDK と jar を用意します（[閉域ネットワークで動かす](#閉域ネットワークで動かすpleiadeseclipse-の-jar-を使う)）
- 起動コマンドを通さず `jbangw/jbang` を直接使う場合（[JBangによる実行](#jbangによる実行対話なし)）と
  [GitHub Actions](../README.md#github-actions-から使う) では、この確認は出ず、自動で取得します。
  実装時に迷った点は [network-download-confirm-qa.md](network-download-confirm-qa.md) にあります

## JBangによる実行（対話なし）

JBang のラッパースクリプトを `jbangw/` に同梱しているので、JBang のインストールは不要です
（出所とライセンス（MIT）は [jbangw/README.md](../jbangw/README.md) を参照）。

```bat
rem Windows（コマンドプロンプト）
.\jbangw\jbang.cmd src\CallHierarchyExporter.java config\config.properties
```

```bash
# Linux / macOS / Git Bash
./jbangw/jbang src/CallHierarchyExporter.java config/config.properties
```

このツールが必要とするJDK・依存jarは、実行環境になければ初回実行時に**確認なしで**自動で取得されます（`%userprofile%/.jbang/`配下に保存。
上記の起動コマンドでプロジェクトの中を選んでいれば、`launcher.properties` の `JBANG_DIR` の場所）。
取得の前に確認してほしい場合は起動コマンドを使ってください（[ネットワークからの取得の確認](#ネットワークからの取得の確認)）。

設定ファイルは複数渡せます。渡した順に処理し、設定ファイルごとに別の出力フォルダができます
（[複数のプロジェクトをまとめて解析する](../README.md#複数のプロジェクトをまとめて解析する)）。

```bash
./jbangw/jbang src/CallHierarchyExporter.java config/app-a.properties config/app-b.properties
```

## 閉域ネットワークで動かす（Pleiades/Eclipse の jar を使う）

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

## Eclipse（Pleiades）でソースを開く

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
（[cache-dependency-jars-qa.md](cache-dependency-jars-qa.md) の Q20）。
JBang 本家の Eclipse 連携プラグイン（jbang-eclipse）を入れると、この `pom.xml` とビルドパスを取り合って
どちらか一方が壊れ続けます。併用しないでください。
Gradle を選ばなかった理由を含め、実装時に迷った点は
[eclipse-maven-qa.md](eclipse-maven-qa.md) にあります。

---
