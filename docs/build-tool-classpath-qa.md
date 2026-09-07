# 依存 jar の自動取得（ビルドファイルとローカルリポジトリから）— 実装時の QA 一覧

[Issue #44](https://github.com/instreest/java-call-hierarchy-exporter/issues/44)
「解析対象プロジェクトの依存性を自動取得したい」への対応で、迷ったこと・困ったことと、
その結論を Q&A の形で残す。

対応の要点:

- `library.folders` が空欄のとき、各ソースフォルダの上位（project.root まで）にある `pom.xml` / `build.gradle(.kts)` を
  このツールが読み、依存の jar と POM をローカルリポジトリ（`~/.m2/repository`、`~/.gradle/caches/modules-2/files-2.1`。
  設定 `library.repositories` で変更可）から探す。ビルドツールは実行せず、ネットワークにも出ない
- 推移的な依存は、ローカルにある POM を辿って集める。親 POM、`dependencyManagement`、BOM の import、`${...}`、
  exclusions、optional / test / provided の除外を Maven の規則で扱う。版の衝突は Maven なら近い方、Gradle なら高い方が勝つ
- マルチモジュールの兄弟モジュール（Maven のリアクタ、Gradle の `project(':x')`）は、そのクラスフォルダ
  （`target/classes`、`build/classes`、Buildship の `bin/main`）と、そのビルドファイルの依存で解決する
- Gradle のビルドファイルは宣言的な書き方（文字列の座標、map 形式、変数、版カタログ、`platform()`、`project()`、
  `files()`）を読む。`gradle.lockfile` があればそれを使う
- 集めた jar とクラスフォルダはそのまま JDT に渡し、キャッシュの `L` 行で変更を検知する（クラスフォルダは
  `.class` の数と最新の更新時刻で）
- 設定に `library.build.tool`（auto / maven / gradle / none）と `library.repositories` を足した
- 回帰テストに `maven` / `mavenmulti` / `gradle` の 3 ケースを加えた（依存は `test/localrepo` から取るので、
  ビルドツールもネットワークも要らない）

---

## 経緯

### Q1. 最初は Maven / Gradle を実行する方式で作った。なぜ変えたか

最初の実装は、`mvn dependency:build-classpath` と、初期化スクリプトを付けた `gradle` を実行して
「ビルドツールが解決したクラスパス」を受け取るものだった。推移的な依存も版の決定もビルドツールに任せられ、
結果は確実である。しかし「`mvn` / `gradle` が実行できる環境」が前提になる。Pleiades（Eclipse）だけで開発していて
コマンド行の Maven が無い、閉域ネットワークで `maven-dependency-plugin` を取れない、といった環境では動かない。
依頼者からこの前提を無くしたいと指示があり、ビルドファイルをこのツールが読む方式に作り直した。

作り直しにあたって残したのは、JDT へクラスフォルダを渡してよいことの確認（Q13）と、キャッシュの `L` 行での
クラスフォルダの扱い、`.project` / `.classpath` によるビルドツールの判定（Q11）、ビルドファイルを探す規則（Q12）である。
実行方式の名残（`library.maven.args` / `library.gradle.args`、CI への Gradle の導入）は取り除いた。

### Q2. Issue の「推移的依存関係の取得などはせず、pom や gradle によって解決された jar のクラスパスおよび保管先の Jar ファイルを利用する」をどう読んだか

「取得」はダウンロードのことと読み、このツールは何もダウンロードしない。使うのは Eclipse や Maven / Gradle が
すでに解決してローカルリポジトリ（保管先）に置いた jar だけである。ただし「推移的な依存は見ない」とは読まなかった。
Spring Boot のように直接の依存（`spring-boot-starter-web` など）がクラスをほとんど含まない構成では、実体
（`spring-web`、`spring-context`）はすべて推移的な依存にあり、直接の依存だけでは型解決がほぼできない。
推移的な依存の情報はローカルリポジトリの POM に全部あるので、ダウンロードせずに辿れる。
この読み方は依頼者に確認して進めた。

---

## 設計

### Q3. Maven の解決規則をどこまで再現したか

Maven の解決器を作り直すのではなく、「ローカルにある POM だけで、Maven が出すのと同じクラスパスに近いもの」を
目標にした。やること・やらないことは次のとおり。

| 扱う | 扱わない |
|---|---|
| 親 POM の連鎖（`relativePath` のファイル → ローカルリポジトリ） | `settings.xml` のプロファイル・プロパティ、コマンド行の `-D` |
| プロパティの継承と `${...}` の展開（`project.*` / `pom.*` / `parent.*`、システムプロパティ、`env.*`） | `activeByDefault` 以外のプロファイル（JDK や OS で有効になるもの） |
| `dependencyManagement` の継承と、`scope=import` の BOM（再帰的に） | 版の範囲の厳密な意味論（ローカルにある版から選ぶだけ） |
| 直接の依存はスコープを問わず含める（テストコードも解析対象になりうる） | 依存の `<type>` が war / ear / aar のもの（クラスパスに載らない） |
| 推移的な依存の test / provided / optional は含めない | `relocation` の 2 段以上の連鎖 |
| exclusions（`*` のワイルドカードも）を経路に沿って引き継ぐ | 同じ groupId:artifactId の複数の版を同時に載せること |
| ルートの `dependencyManagement` で推移的な依存の版を上書き（Maven 3 の動き） | |
| 版の衝突は幅優先で先に見つけた方（Maven の「近い方が勝つ」） | |
| リアクタのモジュールは `target/classes` と、その pom.xml の依存 | |
| `system` スコープの `systemPath`、`test-jar` / `ejb-client` の分類子 | |

扱わないものは、企業のプロジェクトで頻度が低いか、扱っても結果がほとんど変わらないもので、
どれも「jar が 1 つ足りない」以上の壊れ方はしない。足りない jar は警告に出る（Q14）。

### Q4. 版の衝突をどう決めるか

Maven は「依存の木で近い方が勝つ」（同じ深さなら先に宣言した方）、Gradle は「高い版が勝つ」で、
どちらのビルドファイルを読んだかで切り替える。Maven の方は幅優先で辿れば「先に見つけた方」がそのまま近い方になる。
Gradle の方は、後から高い版が出てきたら差し替えて、その版の依存を辿り直す。差し替え前の版の依存は
そのまま残す（過剰に含めることはあっても、欠けることは無い。型解決には余分な jar は害が少ない）。

### Q5. 実効 POM を作るときに困ったこと

- **親 POM の探し方。** `relativePath`（既定 `../pom.xml`）にファイルがあっても、それが本当に親とは限らない
  （別のプロジェクトの POM のこともある）。Maven と同じく座標が一致するときだけ使い、違えばローカルリポジトリの
  POM を探す。`relativePath` を空にした POM（親をリポジトリから取る指定）はディスク上を探さない
- **`${revision}` のような CI 向けの版。** 親の座標が `${revision}` で書かれていることがある。子の
  `<properties>` で展開してから探す
- **BOM の import。** BOM 自身も親を持ちプロパティで版を書くので、BOM の実効 POM を作ってから
  その `dependencyManagement` を取り込む。自分の管理の方が優先で、import は後ろに足す。BOM が BOM を import
  する連鎖は再帰で、循環は「構築中」の印で止める
- **同じ POM を何度も読まない。** ファイルと座標の両方で覚える。Spring Boot の BOM は 1 つで数百件の管理を持ち、
  それを依存の数だけ読み直すと遅くなる

### Q6. リアクタ（兄弟モジュール）をどう扱うか

マルチモジュールの `app` が `core` に依存しているとき、Maven ならローカルリポジトリに `mvn install` した jar を
探すが、Eclipse だけで開発している環境にはそれが無い。そこで、実行ディレクトリの pom.xml から親の `relativePath` を
辿ってディスク上の最上位の POM を見つけ、その `modules` を再帰的に集めてリアクタ（同じビルドのモジュール一覧）を作る。
依存先がリアクタの中にあれば、jar の代わりに `target/classes`（あれば）を使い、推移的な依存はそのモジュールの
pom.xml から辿る。`target/classes` が無ければ注記を出す。そのモジュールのソースも解析対象に含めていれば、
型はソースから解決されるので問題無い（回帰テストの `mavenmulti` がこの形）。

Gradle の `project(':x')` も同じ考え方で、`settings.gradle` の `include` からプロジェクトのディレクトリを求め、
`build/classes/java/main`（kotlin / groovy / scala も）、Buildship が使う `bin/main`、素の Eclipse の `bin`、
無ければ `build/libs` の jar の順に探し、そのプロジェクトのビルドファイルの依存も集める。

### Q7. Gradle のビルドファイルはプログラムである。どこまで読むか

Groovy / Kotlin のスクリプトなので、一般には実行しないと依存は決まらない。読めるのは宣言的な書き方だけと割り切り、
実務で見かける形を優先して対応した。

| 書き方 | 対応 |
|---|---|
| `implementation 'g:a:v'`、`implementation("g:a:v")`、`api "g:a:$ver"`、`'g:a:v:classifier'`、`@ext` | 読む |
| `implementation group: 'g', name: 'a', version: 'v'`、`(group = "g", name = "a", version = "v")` | 読む |
| `$var` / `${var}` / `${project.var}` / `${property("var")}` | `gradle.properties`（`~/.gradle`、ルート、プロジェクト）と、ビルドファイルの `ext { x = '..' }`、`ext.x = '..'`、`def` / `val` / `var x = '..'`、`extra["x"] = ".."`、`val x by extra("..")` の文字列代入で埋める |
| 版カタログ `libs.foo.bar`、`libs.bundles.x`、settings の `from(files("..."))` | `gradle/libs.versions.toml` の `[versions]` `[libraries]` `[bundles]`（`version.ref`、`strictly` / `require` / `prefer`）を読む |
| `platform("g:a:v")` / `enforcedPlatform(...)` | BOM として、版の無い依存の版を決める |
| `project(':x')`、`projects.x`（型安全アクセサ） | 他プロジェクト（Q6） |
| `files('a.jar')`、`fileTree('lib')` | そのファイル・フォルダの jar |
| `gradle.lockfile`、`gradle/dependency-locks/*.lockfile` | あれば、解決済みの全依存（推移的なものも）をそのまま使う。最も確実 |
| 版の無い依存で BOM にも無いもの | ローカルにある最も新しい版（Gradle の解決結果に最も近い）。注記に出す |
| ソースセット付きの構成名（`integrationTestImplementation` 等）、`compileOnly`、`runtimeOnly` | 読む。`classpath`（buildscript）、`kapt`、`ksp`、`annotationProcessor` は読まない |
| `buildscript { }`、`constraints { }` の中 | 読まない（依存ではない） |
| ループや条件で組み立てた座標、プラグインが足す依存、`buildSrc` の規約プラグイン | 読めない。行が依存の宣言の形なら「読めない依存の宣言」として注記に出す |

ルートの `build.gradle` の `allprojects { }` / `subprojects { }` の中の宣言は、サブプロジェクトにも足す
（ルート自身には `subprojects` の分は足さない）。ブロックは中括弧の対応で切り出す（文字列の中の括弧は数えない）。
コメントは先に除く（`//` は URL の `://` と区別する）。

`${property("x")}` のように文字列の中に引用符が入る形では、文字列リテラルの切り出しが内側の引用符で止まる。
先にその形だけをプロパティの値に置き換えてから切り出すようにした（実際に引っかかって直した）。

### Q8. Gradle の依存が Maven の POM で辿れるのはなぜか

Gradle も Maven リポジトリから取った依存は `~/.gradle/caches/modules-2/files-2.1/` に jar と一緒に POM を保存する
（Gradle Module Metadata を使うライブラリでも POM は保存される）。だから推移的な依存の辿り方は Maven と共通にでき、
違うのは版の衝突の決め方（Q4）だけである。Gradle のキャッシュは `グループ/アーティファクト/版/<ハッシュ>/ファイル` の
配置で、グループはドット区切りのままのフォルダ名。ローカルリポジトリの探索は、ルートごとに Maven の配置と
Gradle の配置の両方を試す。

### Q9. ローカルリポジトリの場所はどう決めるか

既定は次の 2 つのうち存在するもの。

- Maven … `~/.m2/settings.xml` の `<localRepository>`（`${user.home}` は展開する）、無ければ `~/.m2/repository`。
  Eclipse の m2e も同じ場所に取得する
- Gradle … `GRADLE_USER_HOME`（無ければ `~/.gradle`）の `caches/modules-2/files-2.1`。Buildship も同じ

`library.repositories` で置き換えられる。カンマ区切りで複数書け、Maven 形式のフォルダ（jar を集めたもの、
社内の複製など）でもよい。相対パスは設定ファイルのフォルダ起点で、他の項目と違って配下の制限は掛けない
（リポジトリは設定ファイルやプロジェクトの外にあるのが普通）。先頭の `~/` はホームに展開する。
回帰テストはこれで `test/localrepo` を指す。

SNAPSHOT はダウンロード時に `artifact-1.0-20240101.123456-3.jar` のようにタイムスタンプ付きで保存されるので、
その形も探して最も新しいものを取る。範囲（`[1.0,2.0)`）や動的な版（`1.+`、`latest.release`）は、
ローカルにある版の一覧から合う最も新しいものを選ぶ（Q10）。

### Q10. 版の比較

Maven の `ComparableVersion` は長い規則を持つ。ローカルにある版から選ぶだけなので、簡略版にした。
`.` `-` `_` と数字・英字の境目で区切り、数字は数値として、修飾子は alpha < beta < milestone < rc < snapshot <
（無し）< sp の順で比べる。末尾の `0` は無いのと同じ（`1.0` = `1.0.0`）。範囲は単一の区間だけ扱い、
`[1,2),[3,4)` のような組は最初の区間だけ見る。

### Q11. `.project` ファイルは何に使うか

`.project` に依存の情報は無い（あるのは nature とビルダーの名前だけ）。使えるのは「Eclipse がこのプロジェクトを
Maven（m2e の nature）と Gradle（Buildship の nature）のどちらとして開いているか」で、`.classpath` の `kind="con"`
（`org.eclipse.m2e.MAVEN2_CLASSPATH_CONTAINER` / `org.eclipse.buildship.core.gradleclasspathcontainer`）も同じことを表す。
`pom.xml` と `build.gradle` の両方があるプロジェクトでどちらを読むかを決める手掛かりにする。両方あって手掛かりも
無ければ Maven を優先し、`library.build.tool=gradle` で切り替えられる。片方しか無ければ手掛かりに関係なくそれを読む。

### Q12. どのビルドファイルを読むか

各ソースフォルダから project.root まで上位へ辿り、最初にビルドファイルが見つかったディレクトリのものを読む
（重複は除く）。project.root がマルチモジュールのアグリゲータなら、ソースフォルダを持つモジュールごとに読むことになり、
アグリゲータ自身の POM は読まない（依存を持たない）。project.root を複数プロジェクトを束ねたフォルダにして
`source.folders=app/src/main/java,lib/src/main/java` と書く使い方でも、それぞれのビルドファイルが見つかる。
（回帰テストの `mavenmulti` がこの形。`maven` / `gradle` は project.root 自身にビルドファイルがある形）
`.classpath` から取ったソースフォルダも同じ扱い。

### Q13. クラスフォルダ（`target/classes`、`build/classes/java/main`）を JDT に渡してよいか

渡す。心配したのは「解析対象のソースの古い class がクラスフォルダにあると、ソースより class が優先されないか」で、
JDT で確かめた。`setEnvironment` のクラスパスは「実行 JVM のブートクラスパス → ソースパス → クラスパス」の順に
並び、先に見つかった方が使われる。`B.java`（`run()` あり）をソースパスに、`run()` の無い古い `B.class` を
クラスフォルダに置いて `b.run()` を解決すると、ソースの `B#run` に解決された。ソースパスから `B.java` を外すと
`run()` は未解決になる（古い class が使われる）。つまり解析対象のソースがある型はソースが勝ち、無い型だけ
クラスフォルダから補われる。

`ProjectLayout.classpathArray()` は、設定と `.classpath` から来たフォルダを「jar を集めたフォルダ」として直下の
`*.jar` に展開する。ビルドファイルから来たフォルダは「クラスフォルダ」なので展開せずそのまま渡す。2 つの意味の
フォルダを同じリストに混ぜると区別できないので、`resolvedClasspath` を別に持った。

キャッシュの `L` 行では、フォルダの「サイズ」を `.class` ファイルの数、「更新時刻」を最も新しい `.class` の更新時刻とした。
フォルダ自身の更新時刻は中のファイルの変更を反映しないので、毎回中を歩く。`target/classes` の class を `touch` すると
「変更=1」として検知され、それを参照するファイルだけが解析し直されることを確認した。

### Q14. ローカルリポジトリに無い jar はどうするか

ダウンロードはしない（Issue の方針、閉域ネットワーク）。警告に座標と要求元の経路（`g:a:v ← g:a:v ← pom.xml`）を出し、
無いまま解析を続ける。Eclipse や Maven / Gradle で一度依存を取得すれば入る旨と、`library.repositories` の案内を添える。
POM が無い（推移的な依存を辿れない）、版が決まらない（変数が展開できない、範囲に合う版が無い）も同じ形で数えて出す。
止めないのは、`library.folders` のフォルダが無いときも警告して続ける既存の方針に合わせたもので、出力は出る
（型解決の失敗が多くなる）ので何が起きたかは分かる。

### Q15. 設定キー

| キー | 目的 |
|---|---|
| `library.build.tool` | `pom.xml` と `build.gradle` の両方があるときの切り替え（Q11）と、自動取得を止める `none` |
| `library.repositories` | ローカルリポジトリの場所（Q9）。回帰テストでも使う |

Issue が求めたのは「`library.folders` が空欄なら自動取得、指定があれば試みない」だけで、それは
キー無しで実現している。実行方式のときにあった `library.maven.args` / `library.gradle.args` は不要になったので消した。

### Q16. 集めた一覧をファイルに残す

`cache.folders/resolved-classpath.txt` に、パス・座標・要求元の連鎖をタブ区切りで書く。
「なぜこの jar が入っているのか」「なぜ入っていないのか」を後から追えるようにするためで、
ログにも同じ内容の要約（直接依存 N 件 → jar M 件、辿った依存 K 件、無い jar の一覧）を出す。

### Q17. 毎回ビルドファイルを読み直すのか

読み直す。実測は数十ミリ秒から 0.1 秒程度（POM の数に比例。Spring Boot の BOM を含めても 1 秒は掛からない）で、
`pom.xml` の更新時刻で結果を再利用する仕組みは、親 POM やカタログの変更を取りこぼすので入れなかった。

---

## テスト

### Q18. サンプルのローカルリポジトリ

`test/localrepo` に Maven 形式で 4 つのアーティファクトを置いた（ソースは `test/localrepo-src`）。

| アーティファクト | 何を確かめるか |
|---|---|
| `sample.deps:parent:1.0`（POM のみ） | 親 POM の継承、プロパティ（`core.version`）、`dependencyManagement` |
| `sample.deps:core:1.0`（`Formatter`） | greeter の推移的な依存。版は greeter の POM に無く、親の管理で決まる |
| `sample.deps:greeter:1.0`（`Greeter` 等） | 直接の依存。`test` スコープと `optional` の依存（jar は無い）が使う側に伝わらないこと |
| `sample.deps:util:1.0`（`Strings`） | Gradle の版カタログと、Maven の `dependencyManagement`（`maven-multi`）からの参照 |

`Greeter#formatter()` の戻り値が core の `Formatter` なので、`Formatter#wrap` が `ソースなし（展開不可）` の行として
出力に出れば「POM を辿って推移的な依存を集めた」ことの確認になる。回帰テストのログ検査でも、`greeter-1.0.jar` と
`core-1.0.jar` が依存 jar の一覧（ASCII 部分）に出ることを見る。

パッケージ名は最初 `sample.lib` にしたが、リポジトリの `.gitignore` が `lib/` を無視するため
`test/localrepo/sample/lib/` が追跡されず、`sample.deps` に改めた
（[cache-dependency-jars-qa.md](cache-dependency-jars-qa.md) の Q16 と同じ罠）。

### Q19. 3 つのケースで通す経路

| ケース | 経路 |
|---|---|
| `maven` | 単一モジュール。ソースフォルダ `src/main/java` の上位で `pom.xml` を見つける（Q12）。版はプロパティ `${greeter.version}` |
| `mavenmulti` | project.root がアグリゲータ。`core` / `app` の pom.xml をそれぞれ読み、`app` の兄弟 `core` への依存（`${project.version}`）をリアクタで解決する（Q6）。`util` の版はアグリゲータの `dependencyManagement`。`core` は未ビルドなので注記が出るが、ソースを解析対象に含めているので型はソースから解決される |
| `gradle` | project.root が Buildship の `.project` / `.classpath` を持つ `app`。`source.folders` も空欄にして `.classpath` からソースフォルダを取る。`settings.gradle` は上位。`project(':core')` から `core/build.gradle` の `$greeterVersion`（`gradle.properties`）を、版カタログから `util` を集める |

いずれも `library.repositories=../../../test/localrepo`（設定ファイルからの相対）で、ビルドツールもネットワークも
`~/.m2` も要らない。Windows の `run.cmd` にも同じケースを足した（ビルドツールの実行が無くなったので、
Windows 固有の懸念は無くなった）。

### Q20. CI

実行方式のときに入れた「JDK 25 で動く Gradle を固定して入れる」ステップと、ビルドツールが無ければ失敗させる
環境変数は不要になったので外し、元の形に戻した。

### Q21. 実在のライブラリで確かめたこと

この作業環境の `~/.m2/repository` と `~/.gradle/caches` には、実行方式を試したときに取得した gson、commons-lang3、
junit、slf4j などが残っていた。それを使って、Maven のマルチモジュール（兄弟の `target/classes` と、その依存の
commons-lang3、gson → error_prone_annotations の推移的な依存）、Gradle のマルチプロジェクト（`java` / `java-library`、
`compileOnly`、`testImplementation` → junit → hamcrest）、Kotlin DSL（`libs.gson`、`libs.bundles.logging`、map 形式、
`${property("x")}`、`platform(...)`）、`gradle.lockfile`、両ビルドファイルの併存と `.project` による判定、
ローカルに無い依存の警告、を確認した。jbang の JDK 25 の取得先（foojay）は遮断されていたので、
`bash jbangw/jbang run --java 21 …` で JDK 21 で動かした（[fatjar-external-usage-qa.md](fatjar-external-usage-qa.md) の Q18 と同じ）。

---

## 限界（対応しないと決めたこと）

- ダウンロードはしない。ローカルリポジトリに無い jar は無いまま（Q14）
- Gradle は宣言的な書き方だけ読む（Q7）。プラグインや規約プラグインが足す依存、条件やループで組み立てた座標は読めない。
  確実にしたければ `gradle.lockfile` を置く（Gradle の dependency locking）か、従来どおり `library.folders` を使う
- Android、Kotlin Multiplatform のようにソースセットの構成が Java と違うビルドは対象外
- Maven のプロファイルは `activeByDefault` だけ。`settings.xml` のプロファイル・プロパティ・ミラーは見ない（Q3）
- 版の範囲・動的な版は「ローカルにある版から選ぶ」だけで、Maven / Gradle の厳密な選び方とは違うことがある（Q10）
- 同じ groupId:artifactId は 1 つの版しか載せない。Gradle で高い版に差し替えたときの古い版の推移的な依存は残る（Q4）
- Ant / Ivy、Eclipse のユーザーライブラリ、`.classpath` の `kind="var"` は従来どおり対象外。`library.folders` で指定する
