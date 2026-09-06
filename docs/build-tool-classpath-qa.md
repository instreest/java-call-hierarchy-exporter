# 依存 jar の自動取得（Maven / Gradle）— 実装時の QA 一覧

[Issue #44](https://github.com/instreest/java-call-hierarchy-exporter/issues/44)
「解析対象プロジェクトの依存性を自動取得したい」への対応で、迷ったこと・困ったことと、
その結論を Q&A の形で残す。

対応の要点:

- `library.folders` が空欄のとき、project.root（無ければ各ソースフォルダの上位）の `pom.xml` /
  `build.gradle(.kts)` を探し、Maven（`mvnw` か `mvn`）/ Gradle（`gradlew` か `gradle`）を実行して、
  ビルドツールが解決したコンパイル時のクラスパスをそのまま使う。`library.folders` に指定があれば何もしない
- jar は `~/.m2/repository` や `~/.gradle/caches` に置かれたままのパスで受け取る。コピーしない。
  依存の解決（推移的依存の展開・版の決定・取得）はこのツールでは行わない
- クラスパスに来るクラスフォルダ（`target/classes`、`build/classes/java/main`）もそのまま JDT に渡し、
  キャッシュの `L` 行で jar と同じように変更を検知する
- 設定に `library.build.tool`（auto / maven / gradle / none）と `library.maven.args` / `library.gradle.args`
  （オフライン実行やプロファイル指定などの追加引数）を足した
- 回帰テストに `maven` / `gradle` の 2 ケース（`samples/maven-demo`、`samples/gradle-demo`）を加えた

---

## 設計

### Q1. Issue の「推移的依存関係の取得などはせず、pom や gradle によって解決された jar のクラスパスおよび保管先の Jar ファイルを利用する」をどう解釈したか

2 通りに読めた。

1. `pom.xml` / `build.gradle` を自前で読み、書いてある依存（直接の依存だけ）をローカルリポジトリの
   jar に対応付ける。推移的な依存は追わない
2. ビルドツール自身に「解決済みのクラスパス」を出させ、それをそのまま使う。推移的な依存の展開や
   版の決定はビルドツールの仕事で、このツールは何も解決しない

2 を採った。「推移的依存関係の取得などはせず」は「このツールが依存解決器を持たない」の意味に取り、
「pom や gradle によって解決された jar のクラスパス」はビルドツールが実際に使うクラスパス、
「保管先の Jar ファイル」はローカルリポジトリ（`~/.m2/repository`、`~/.gradle/caches`）の jar を
コピーせずそのまま使うこと、と読んだ。
1 では役に立たない理由は Q2。

### Q2. ビルドファイルを自前で読む方式（直接の依存だけをローカルリポジトリに対応付ける）を採らなかった理由

型解決に要るのは直接の依存だけではない。呼び出したメソッドの戻り値の型、継承した基底クラスの親、
引数の型が推移的な依存の jar にあることは普通で、そこが欠けると `var` やメソッドチェーンの先の呼び出しが
「型解決に失敗」になる（[cache-dependency-jars-qa.md](cache-dependency-jars-qa.md) の Q20 の 2 と同じ形）。
Spring Boot では直接の依存（`spring-boot-starter-web` など）はクラスをほとんど含まない空の jar で、
実体（`spring-web`、`spring-context`）はすべて推移的な依存にある。直接の依存だけでは最も一般的な
構成でほぼ何も解決できない。

さらに、`pom.xml` を読むだけでも、親 POM の連鎖、`dependencyManagement`、BOM の import、
プロパティ展開、プロファイルを扱わないと版が決まらない。これを作るのは Maven の解決器を
作り直すことで、Issue が「せず」と書いたことそのものになる。
Gradle は `build.gradle` がプログラムなので、読んで依存を取り出すこと自体ができない。

### Q3. Maven からクラスパスをどう受け取るか

`maven-dependency-plugin` の `build-classpath` ゴールで、解決済みのクラスパスをファイルに書かせる。

```
mvn -B -q -fae org.apache.maven.plugins:maven-dependency-plugin:build-classpath
    -Dmdep.outputFile=<cache.folders>/build-classpath/maven/${project.groupId}-${project.artifactId}.txt
```

迷った点:

- **モジュールごとの出力。** `-Dmdep.outputFile` に 1 つのファイルを指定すると、マルチモジュールでは
  モジュールごとに上書きされ、最後のモジュールの分しか残らない（実際に確認した）。プラグインには
  `appendOutput` があるが、コマンド行のプロパティからは指定できない（`-Dmdep.appendOutput=true` は無視された）。
  そこで出力ファイル名に `${project.groupId}-${project.artifactId}` を含めた。Maven はプラグインの
  パラメータの値に含まれる `${...}` をモジュールごとに展開するので、モジュールの数だけファイルができる。
  packaging が `pom` のモジュールは空のファイルを書く。ファイルの末尾に改行は無い
- **標準出力を読む案。** `-q` を外せば `[INFO] Dependencies classpath:` の次の行にクラスパスが出るので、
  それを読む手もある。ログの書式に依存すること、Windows の標準出力の文字コードが JDK の版と
  コンソールの状態で変わることから、ファイルにした
- **プラグインの指定。** `dependency:build-classpath` と短く書くと、プレフィックス `dependency` を
  解決するためにグループのメタデータを取りに行く。`org.apache.maven.plugins:maven-dependency-plugin:build-classpath`
  とグループとアーティファクトを明示し、版は書かない。版は Maven 自身の super POM の
  `pluginManagement` にある（Maven 3.9.11 では 3.7.0）ので、Maven の版が決まれば決まる。
  版を固定すると、閉域ネットワークでその版だけ無い、という状況を作る
- **`-fae`。** 兄弟モジュールが未インストールで一部のモジュールが解決できないとき（Q5）に、
  残りのモジュールを続けて、取れた分を使うため。終了コードは 0 にならないので、
  取れた分があれば警告付きで使い、無ければ失敗として扱う

### Q4. Gradle からクラスパスをどう受け取るか

Gradle にはクラスパスを出力する組み込みのタスクが無い。初期化スクリプト（`--init-script`）で
全プロジェクトにタスク `jcheCompileClasspath` を登録し、`java-base` プラグインが適用されたプロジェクトの
各ソースセットについて `compileClasspath` 構成を解決して、1 行 1 エントリでファイルに追記させる。
スクリプトは毎回 `cache.folders/build-classpath/jche-classpath.init.gradle` に書き直す（出力先を埋め込むため）。

```
gradle -q --init-script <cache.folders>/build-classpath/jche-classpath.init.gradle jcheCompileClasspath
```

迷った点:

- **`compileClasspath` を使う。** JDT に要るのはコンパイラが見るものと同じで、`runtimeClasspath`
  （実行時だけの依存）は要らない。`compileOnly`（Maven の `provided` 相当）は `compileClasspath` に入る。
  `test` ソースセットの分も入れるので、テストコードを解析対象にしても依存が揃う
- **解決できない依存があっても止めない。** `configuration.incoming.artifactView { lenient(true) }` で、
  解決できたものだけを書く。壊れた依存が 1 つあるだけで何も取れなくなるのを避ける。
  古い Gradle で `lenient` が無い場合は通常の解決に落とす
- **構成キャッシュ（configuration cache）への対応。** タスクの実行時（`doLast`）に `Project` に触ると、
  構成キャッシュを有効にしたビルドで失敗する。解決は設定時に済ませて文字列の集合だけを持ち、
  `doLast` はそれをファイルに書くだけにした。`--configuration-cache` 付きで 2 回動くことを確認した
- **`java-base` で引っかける。** `java` / `java-library` / `application` / `war` / Kotlin JVM のどれも
  `java-base` を適用し、`sourceSets` はそこで定義される。Android のプロジェクトは `java-base` を
  適用せず、`compileClasspath` もバリアントごとなので、対象外（タスクが見つからないエラーになる）
- **Groovy で書く。** ビルドが Kotlin DSL（`build.gradle.kts`）でも、初期化スクリプトは Groovy で書ける。
  スクリプトは ASCII だけで書き、出力先のパスに非 ASCII が含まれる場合は `\uXXXX` に逃がす
  （スクリプトの文字コードに依存しないため）
- **動作確認。** Gradle 8.14.3 と 9.7.1（この時点の最新）で、`java` と `java-library`、
  マルチプロジェクト（`project(':core')` 依存）、構成キャッシュ有効、解決できない依存あり、を確認した

### Q5. マルチモジュールの Maven で「兄弟モジュールが見つからない」と失敗する

`mvn dependency:build-classpath` のようにライフサイクルのフェーズを伴わない実行では、Maven 3 は
リアクタの中の兄弟モジュール（`app` が依存する `core`）を `core/target/classes` からは解決しない
（`compile` フェーズをその実行で通ったときだけ使う）。`mvn install` されていなければ
「Could not find artifact」で失敗する。Eclipse（m2e）だけでビルドしている環境では、まさにこの状態になる。

対処は 3 段にした。

1. `-fae` で続行し、解決できたモジュールの分は使う（Q3）。失敗したモジュールの依存が足りないことは
   警告に出す
2. `library.maven.args=compile,-Dmaven.main.skip=true` を設定すれば、`compile` フェーズを
   （コンパイラは飛ばして）通すので、兄弟モジュールが `target/classes`（クラスフォルダ、Q6）として
   解決される。動作確認した
3. `mvn install` を一度しておく、または `mvn dependency:copy-dependencies` で集めて `library.folders` に指定する

2 を既定にしなかったのは、`compile` フェーズまで進めるとその前のフェーズに結び付いたプラグイン
（`generate-sources` のコード生成、`frontend-maven-plugin` の npm 実行など）が全部動くからで、
プロジェクトによっては遅く、失敗もする。ゴールだけの実行は何のプラグインも動かさないので予測できる。
Maven 4 ではリアクタの解決が改善されているので、この問題は将来小さくなる。

### Q6. クラスフォルダ（`target/classes`、`build/classes/java/main`）をクラスパスに入れてよいか

入れる。Gradle の `java-library` プラグインは `project(':core')` 依存を jar ではなく
`core/build/classes/java/main` に解決するし、Maven も Q5 の 2 の形では `target/classes` を返す。
これを捨てると、解析対象に含めていない兄弟モジュールの型が解決できない。

心配したのは「解析対象のソースの古い class がクラスフォルダにあると、ソースより class が優先されないか」
である。JDT で確かめた。`setEnvironment` のクラスパスは「実行 JVM のブートクラスパス → ソースパス →
クラスパス」の順に並び、先に見つかった方が使われる。`B.java`（`run()` あり）をソースパスに、
`run()` の無い古い `B.class` をクラスフォルダに置いて `b.run()` を解決すると、ソースの `B#run` に
解決された。ソースパスから `B.java` を外すと `run()` は未解決になる（クラスフォルダの古い class が使われる）。
つまり、解析対象のソースがある型はソースが勝ち、無い型だけクラスフォルダから補われる。

`ProjectLayout.classpathArray()` は、設定と `.classpath` から来たフォルダを「jar を集めたフォルダ」として
直下の `*.jar` に展開する。ビルドツールから来たフォルダは「クラスフォルダ」なので展開せずそのまま渡す。
2 つの意味のフォルダを同じリストに混ぜると区別できないので、`resolvedClasspath` を別に持った。

キャッシュの `L` 行では、フォルダの「サイズ」を `.class` ファイルの数、「更新時刻」を最も新しい
`.class` の更新時刻とした。フォルダ自身の更新時刻は中のファイルの変更を反映しないので、毎回中を歩く。
`core/target/classes` の class を `touch` すると「変更=1」として検知され、それを参照するファイルだけが
解析し直されることを確認した。存在しないフォルダ（ビルドしていないモジュールの出力先）は
除外してログに出す。

### Q7. 「保管先の jar」はコピーせずそのまま使ってよいか

そのまま使う。ビルドツールが返すパスは `~/.m2/repository/...`、`~/.gradle/caches/modules-2/files-2.1/...`
で、キャッシュの `L` 行にはその絶対パスが入る（project.root の外なので。同 Q8 の考え方）。
2 回目の実行で同じパス・サイズ・更新時刻なら再利用され、ローカルリポジトリの jar は版ごとに
別のパスなので、版を上げれば「削除＋追加」として検知される。
Gradle でローカルのファイルリポジトリ（`maven { url = uri('../localrepo') }`）から取った jar は、
キャッシュにコピーされずリポジトリのファイルそのものが返る。これもそのまま使う。

### Q8. `.project` ファイルは何に使うか

`.project` に依存の情報は無い（あるのは nature とビルダーの名前だけ）。使えるのは
「Eclipse がこのプロジェクトを Maven（m2e の nature）と Gradle（Buildship の nature）のどちらとして
開いているか」で、`.classpath` の `kind="con"`（`org.eclipse.m2e.MAVEN2_CLASSPATH_CONTAINER` /
`org.eclipse.buildship.core.gradleclasspathcontainer`）も同じことを表す。

そこで、`pom.xml` と `build.gradle` の両方があるプロジェクトでどちらを使うかを決めるときの
手掛かりに使う。`.project` の nature を先に見て、無ければ `.classpath` のコンテナを見る。
両方のビルドファイルがあって手掛かりも無ければ Maven を優先し、`library.build.tool=gradle` で
切り替えられるようにした。片方のビルドファイルしか無ければ、Eclipse の手掛かりに関係なくそれを使う。

### Q9. project.root にビルドファイルが無いときは

各ソースフォルダから上位へ辿り、最初に `pom.xml` / `build.gradle` が見つかったディレクトリで
ビルドツールを実行する（重複は除く）。project.root を複数プロジェクトを束ねたフォルダ
（Eclipse のワークスペースのような形）にして `source.folders=app/src/main/java,lib/src/main/java` と
書く使い方があるためで、回帰テストの `maven` ケースはこの経路を通す（`project.root=samples`、
`source.folders=maven-demo/src/main/java`）。

project.root にビルドファイルがあれば、そこだけで実行する。マルチモジュールのルートなら
モジュールもまとめて解決されるので、モジュールごとに実行し直す必要は無い。

### Q10. 既定でネットワークに出てよいか

出る。ビルドツールを普通に実行するので、ローカルリポジトリに無いもの（初回の `maven-dependency-plugin`、
未取得の依存、SNAPSHOT の更新確認）はネットワークから取りに行く。利用者が普段ビルドしているのと
同じ動きで、社内ミラーの設定（`settings.xml`、`init.gradle`）もそのまま効く。

閉域ネットワークで取りに行けないときは、`library.maven.args=-o` / `library.gradle.args=--offline` で
オフライン実行にする。既定をオフラインにしなかったのは、一度もビルドしていない環境で
「取れるものも取れない」失敗になるためで、オンラインで失敗する場合はビルドツールのエラーがそのまま
画面に出るので原因は分かる。

Maven は `maven-dependency-plugin` が無いと動かず、これは通常のビルド（`mvn package`）では
ローカルリポジトリに入らない。ミラーの無い閉域ネットワークではここで止まる。その場合は従来どおり
jar を集めたフォルダを `library.folders` に指定する。警告にその旨と
`mvn dependency:copy-dependencies -DoutputDirectory=lib` を出す。

### Q11. ラッパー（`mvnw` / `gradlew`）と PATH 上のコマンドのどちらを使うか。無いときは

ラッパーがあればそれを優先する。プロジェクトが版を固定しているものなので、それで解決するのが
そのプロジェクトの正しいクラスパスになる。無ければ PATH 上の `mvn` / `gradle`。

ラッパーの探し方は、実行ディレクトリから上位へ辿る。Maven は `pom.xml` のあるディレクトリが続く限り
（マルチモジュールの親を辿る。ラッパーはルートにある）、Gradle は `settings.gradle(.kts)` か
`gradlew` が見つかるまで（Gradle 自身が settings を探すのと同じ動き）。
実行権限が落ちているラッパー（Windows で checkout したリポジトリを Linux で使うとき）は `sh` 経由で動かす。

どちらも無ければ警告を出し、依存 jar 無しで解析を続ける。止めないのは、`library.folders` の
フォルダが無いときも警告して続ける既存の方針に合わせたもので、出力は出る（型解決の失敗が多くなる）ので
何が起きたかは分かる。

### Q12. Windows での起動

`mvnw.cmd` / `gradlew.bat` / PATH 上の `mvn.cmd` はバッチファイルなので `cmd.exe /c` 経由で起動する。
このとき先頭のトークンを引用符で囲むと `cmd.exe` が引用符を落として誤解釈する（パスに空白があるときの
古典的な問題）。ラッパーは実行ディレクトリ自身かその上位にあるので、実行ディレクトリからの相対パスは
`..\..\mvnw.cmd` のように `..` だけでできており空白を含まない。それを渡す。
引数（`--init-script` のパス、`-Dmdep.outputFile=...`）に空白があっても、先頭でなければ問題無い。

Windows の実機では確認していない（この作業環境は Linux）。`test/regression/run.cmd` にも
`maven` / `gradle` のケースを足したが、同じく未検証である。

### Q13. ビルドツールを動かす JDK

ビルドツールは `JAVA_HOME` か PATH 上の `java` で動く。jbang 経由でこのツールを動かすと
`JAVA_HOME` が無いことがあるので、無ければこのツールを動かしている JDK（`java.home`）を渡す。
設定されていれば触らない。ツールの JDK（25）で動かない古い Gradle を使うプロジェクトでは、
利用者の `JAVA_HOME`（たとえば 17）がそのまま効くのが正しいためである。

### Q14. ビルドツールの出力はどう扱うか。書かせたファイルの文字コードは

標準出力・標準エラーはそのまま画面に流す（`inheritIO`）。失敗の理由（依存が見つからない、
ネットワークに出られない）をビルドツール自身のメッセージで見せるためで、ツール側で言い換えない。
成功時は `-q` でほぼ何も出ない。

書かせたファイルの文字コードは版と JDK で違う（Maven のプラグインは JDK 18 以降なら UTF-8、
それより前ならプラットフォームの既定。日本語 Windows では MS932）。UTF-8 として正しく読めればそれを使い、
読めなければ実行環境のネイティブ文字コード（`native.encoding`）で読む。Gradle の初期化スクリプトは
UTF-8 で書くよう指定している。ファイルは `cache.folders/build-classpath/` に残し、
何が渡ったかを後から確認できるようにした。

### Q15. 毎回ビルドツールを動かすのか

動かす。実測は Maven で 1.7〜2 秒、Gradle でデーモンが温まっていれば 1 秒台、冷えていれば 6〜10 秒。
解析全体に比べれば小さく、`pom.xml` の更新時刻で結果を再利用する仕組みは、親 POM や
`settings.xml`、版カタログの変更を取りこぼすので入れなかった。
毎回動かすのが遅いプロジェクトでは、`mvn dependency:copy-dependencies` で集めたフォルダを
`library.folders` に指定すれば従来どおりビルドツール無しで動く。

### Q16. 設定キーを 3 つ足したこと

Issue が求めたのは「`library.folders` が空欄なら自動取得、指定があれば試みない」だけで、それは
キー無しで実現している。足したのは次の 3 つで、いずれも空欄のままで Issue の動きになる。

| キー | 目的 |
|---|---|
| `library.build.tool` | `pom.xml` と `build.gradle` の両方があるときの切り替え（Q8）と、自動取得を止める `none` |
| `library.maven.args` | オフライン実行（`-o`）、`settings.xml` やプロファイルの指定、マルチモジュールの回避策（Q5） |
| `library.gradle.args` | オフライン実行（`--offline`）など |

引数はカンマ区切りにした。空白区切りの方がコマンド行を写しやすいが、空白を含むパス
（`-s C:\Users\山田 太郎\settings.xml`）が書けない。他のリスト項目（`source.folders` 等）と同じ区切りでもある。

### Q17. Gradle のデーモンを残してよいか

Gradle の既定どおり（残す）。`--no-daemon` を付けると毎回冷えた起動になり、Eclipse（Buildship）を
使っている環境ではすでにデーモンが動いているので、揃えた方が速い。残したくなければ
`library.gradle.args=--no-daemon` で指定できる。

### Q18. クラスパスに来る jar 以外のもの

`.jar` と `.zip`（JDT はどちらも読める）とディレクトリ（クラスフォルダ）だけを使う。存在しないパスは
「ビルドしていないモジュールの出力先」として件数とパスをログに出して除く。`.pom` などそれ以外の
ファイルも除いてログに出す。同じパスが複数のソースセットやモジュールから来ても 1 件にする。

---

## テスト

### Q19. サンプルの依存 jar をどこから取らせるか

ネットワーク無しで解決させたいので、Maven 形式のローカルリポジトリ `samples/localrepo` を置き、
`sample.deps:greeter:1.0`（ソースは `samples/localrepo-src`）をそこから取らせる。
`samples/maven-demo/pom.xml` は `file://${project.basedir}/../localrepo` を `<repository>` に、
`samples/gradle-demo/build.gradle` は `maven { url = uri('../localrepo') }` を書く。
Maven はここから `~/.m2/repository` へコピーして使い、Gradle はここのファイルをそのまま使う。
どちらも「ビルドツールが返したパスをそのまま使う」ことの確認になる。
`.sha1` / `.md5` も置いて、チェックサムの警告が出ないようにした。

jar の中身を変えるときは版を上げる必要がある（Maven はリリース版を取り直さないので、
`~/.m2/repository` の古い jar が使われ続ける）。`samples/localrepo/README.md` に書いた。

パッケージ名は最初 `sample.lib` にしたが、リポジトリの `.gitignore` が `lib/` を無視するため
`samples/localrepo/sample/lib/` が追跡されず、`sample.deps` に改めた
（[cache-dependency-jars-qa.md](cache-dependency-jars-qa.md) の Q16 と同じ罠）。

### Q20. 2 つのサンプルで何を分けて確かめるか

ソース（`sample.app`）は同じで、`Service.run` が依存 jar の型を「戻り値で受けて次の呼び出しに使う」
形で使う。jar が渡らなければ `Greeter.of` 以降の 5 つの呼び出しが「型解決に失敗」になるので、
期待出力にそれらが `ソースなし（展開不可）` の行として入っていることが「jar が渡った」ことの確認になる。
`call-hierarchy.csv` は 2 ケースで完全に同じになり、`methods.csv` は `file` 列（project.root からの相対パス）だけ違う。

| ケース | 通る経路 |
|---|---|
| `maven` | project.root（`samples`）にビルドファイルが無く、ソースフォルダの上位で `pom.xml` を見つける（Q9）。`mvn` は PATH から |
| `gradle` | project.root に `build.gradle` があり、Buildship の `.project` / `.classpath` もある。`source.folders` も空欄にして `.classpath` からソースフォルダを取る。`gradle` は PATH から |

ログの検査は、ビルドツールが実際に呼ばれたことをコマンド行の ASCII 部分
（`maven-dependency-plugin:build-classpath`、`jcheCompileClasspath`）で確かめるだけにした。
2 回目の実行ではキャッシュが再利用されること（`再利用=4`）を他のケースと同じ方法で見る。

`mvn` / `gradle` が PATH に無い環境では、この 2 ケースは SKIP にした（他のケースは従来どおり動く）。
CI では `JCHE_REQUIRE_BUILD_TOOLS=1` を渡して、無ければ FAIL にする（黙って飛ばされるのを防ぐ）。

### Q21. CI で Gradle の版を固定した理由

このツールは JDK 25 で動き、CI ではビルドツールも同じ `JAVA_HOME`（25）で動く。Gradle は動かす JDK の版に
上限があり（古い版は JDK 25 では動かない）、runner に入っている Gradle の版はイメージの更新で動く。
そこで `smoke.yml` で 9.7.1（この時点の最新。初期化スクリプトの動作を確認した版）を
`services.gradle.org` から取って PATH に足す。Maven は runner 同梱の `mvn`（3.9 系。JDK 25 で動く）を使う。
`actions/cache` のキーにサンプルの `pom.xml` を足して、`maven-dependency-plugin` もキャッシュに入るようにした。

サンプルに `gradlew`（ラッパー）を置く案は採らなかった。`gradle-wrapper.jar` というバイナリを
リポジトリに足すことになり、閉域ネットワークでは配布物の取得で必ず失敗するため。
ラッパーの経路は、この作業環境で `mvnw` / `gradlew` を置いたプロジェクトを作って確認した。

### Q22. サンプルを書いていて気づいた既存の仕様

`Service` のフィールド `handler` は jar のインターフェース `Handler` 型で、実装はソース側の
`LogHandler`（jar の `AbstractHandler` を継承）である。jar があれば `handler.handle(text)` は
`sample.deps.Handler.handle(String)` への呼び出しとして解決されるが、ソース側の `LogHandler.handle` までは
辿らない（`ソースなし（展開不可）`）。CHA の候補はキャッシュの `H` 行（ソースの型の親型）から作るので、
jar で宣言されたインターフェースのメソッドの呼び出しは jar のメソッドとして出る。
`Runnable#run` のような JDK のインターフェースと同じ扱いで、この Issue の範囲外なのでそのままにし、
サンプルのコメントに書いた。

### Q23. 作業環境で困ったこと

- jbang の JDK 25 の取得先（foojay）が遮断されていたので、`bash jbangw/jbang run --java 21 …` で JDK 21 で
  動かした（[fatjar-external-usage-qa.md](fatjar-external-usage-qa.md) の Q18 と同じ）。期待出力は
  JDK の版で変わる API を使っていないので JDK 25 と同じになる
- Maven の `appendOutput`（Q3）と Gradle の `project()` 依存が何に解決されるか（Q6）は、
  文書だけでは確信が持てず、小さなプロジェクトを作って実際に動かして決めた。JDT のソースパスと
  クラスパスの優先順位（Q6）も同様

---

## 限界（対応しないと決めたこと）

- Android、Kotlin Multiplatform など `java-base` の `sourceSets` を使わないビルドは対象外（Q4）
- Ant / Ivy、Eclipse のユーザーライブラリ、`.classpath` の `kind="var"` は従来どおり対象外。`library.folders` で指定する
- コマンド行の `mvn` / `gradle` もラッパーも無い環境（Pleiades だけで Maven を使っている等）では自動取得できない。
  警告と手順（`mvn dependency:copy-dependencies`）を出す（Q11）
- 受け取るのはコンパイル時のクラスパスだけ。実行時だけの依存（`runtimeOnly`、Maven の `runtime` スコープの
  推移的な依存）は入らない。型解決には要らない
- Windows での起動は設計はしたが実機では未確認（Q12）
- ビルドツールの実行結果は再利用しない。毎回動かす（Q15）
