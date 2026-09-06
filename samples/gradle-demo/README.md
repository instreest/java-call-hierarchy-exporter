# samples/gradle-demo

回帰テスト `test/regression/gradle` 用のマルチプロジェクト Gradle ビルドです。ツールは Gradle を実行せず、
これらのファイルを読んで依存を集めます。

- `settings.gradle` … `include 'core', 'app'`
- `build.gradle` … `subprojects { apply plugin: 'java-library' ... }`（依存の宣言は無い）
- `gradle.properties` … `greeterVersion=1.0`
- `gradle/libs.versions.toml` … 版カタログ。`sample-util`（`libs.sample.util`）
- `core/build.gradle` … `api "sample.deps:greeter:$greeterVersion"`（変数を `gradle.properties` で埋める形）
- `app/build.gradle` … `implementation project(':core')` と `implementation libs.sample.util`（版カタログ）
- `app/.project`、`app/.classpath` … Eclipse（Buildship）で開いたときの形。`.project` の nature で Gradle の
  プロジェクトと分かり、`.classpath` の `kind="src"` からソースフォルダが分かる

テストの `project.root` は `app` で、`source.folders` は空欄（`.classpath` から取る）です。
ツールは `app/build.gradle` を読み、`project(':core')` から `core/build.gradle` の依存 greeter を、
greeter の POM から推移的な依存 `core`（`sample.deps:core`）を、版カタログから `util` を集めます。
jar と POM はローカルリポジトリ（テストでは `library.repositories` で `samples/localrepo` を指定）から探します。

`core` プロジェクトのクラス自体は、Eclipse（Buildship）や Gradle でビルドした出力（`bin/main`、`build/classes/java/main`）が
あるときだけ解決できます。このサンプルではビルドしていないのでログに注記が出ます。`app` のソースは `core` のクラスを使いません。

`src/main/java/sample/app/` のソースは `samples/maven-demo` のものに `Strings.upper`（util）の呼び出しを足したものです
（説明は `samples/maven-demo/README.md`）。
