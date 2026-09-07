# test/localrepo

回帰テスト（`test/regression/maven`、`mavenmulti`、`gradle`）のサンプルプロジェクトが依存するライブラリを置いた、
Maven 形式のローカルリポジトリです。テストの設定では `library.repositories` でここを指定し、ネットワークにも
`~/.m2/repository` にも依存せずに「ビルドファイルを読んでローカルリポジトリから依存 jar を集める」経路を通します。
ソースは `test/localrepo-src/` にアーティファクトごとに置いてあります。

| アーティファクト | 中身 | 何を確かめるためのものか |
|---|---|---|
| `sample.deps:parent:1.0`（POM のみ） | プロパティ `core.version` / `util.version` と `dependencyManagement` | 親 POM の継承、プロパティの展開、管理側の版 |
| `sample.deps:core:1.0` | `Formatter` | greeter の推移的な依存。greeter の POM に版は書かれておらず、親の管理で決まる |
| `sample.deps:greeter:1.0` | `Greeter`（`formatter()` の戻り値が core の `Formatter`）、`Handler`、`AbstractHandler` | サンプルが直接依存するライブラリ。`test` スコープの `testlib` と `optional` の `opt`（どちらも jar は無い）を宣言しており、使う側に伝わらないことの確認 |
| `sample.deps:util:1.0` | `Strings` | Gradle の版カタログ（`libs.sample.util`）と Maven の `dependencyManagement`（`test/maven-multi`）からの参照 |

Maven の配置（`グループ/アーティファクト/版/アーティファクト-版.jar` と `.pom`）なので、Maven や Gradle が
このフォルダを `<repository>` / `maven { url }` として使うこともできます（サンプルの `pom.xml` / `build.gradle` に
書いてあります。ツール自身はその設定を見ません）。`.sha1` / `.md5` はそのときの検証用です。

パッケージ名を `sample.lib` にしていないのは、リポジトリの `.gitignore` が `lib/` を無視する
（利用者が Eclipse から集めた JDT の jar を置く場所）ため、`sample/lib/` 配下が追跡されなくなるからです。

jar を作り直すとき（リポジトリのルートで実行。greeter は core を参照するので core を先に）:

```bash
R=test/localrepo/sample/deps; T=/tmp/localrepo-build; rm -rf $T; mkdir -p $T/core $T/greeter $T/util
javac --release 17 -d $T/core -encoding UTF-8 test/localrepo-src/core/sample/deps/*.java
javac --release 17 -cp $T/core -d $T/greeter -encoding UTF-8 test/localrepo-src/greeter/sample/deps/*.java
javac --release 17 -d $T/util -encoding UTF-8 test/localrepo-src/util/sample/deps/util/*.java
for a in core greeter util; do jar --create --file $R/$a/1.0/$a-1.0.jar -C $T/$a .; done
for a in parent core greeter util; do (cd $R/$a/1.0 && for f in *.jar *.pom; do [ -f "$f" ] || continue;
  sha1sum "$f" | cut -d' ' -f1 > "$f.sha1"; md5sum "$f" | cut -d' ' -f1 > "$f.md5"; done); done
```

中身を変えたときは版（`1.0`）を上げて、POM とサンプルの依存の指定も合わせて変えてください。
Maven や Gradle でサンプルをビルドした人の `~/.m2/repository` には同じ版の古い jar が残り、
取り直されないためです（このツール自身は `library.repositories` で指定した場所だけを見ます）。
