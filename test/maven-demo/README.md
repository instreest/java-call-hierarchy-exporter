# test/maven-demo

回帰テスト `test/regression/maven` 用の、依存 jar を `library.folders` で指定しない Maven プロジェクトです。
`pom.xml` は `sample.deps:greeter`（版はプロパティ `${greeter.version}`）に依存します。

ツールは `library.folders` が空欄なので、ソースフォルダの上位（このフォルダ）の `pom.xml` を読み（Maven は実行しない）、
greeter の jar と POM をローカルリポジトリ（テストでは `library.repositories` で `test/localrepo` を指定）から探し、
greeter の POM から推移的な依存 `core`（版は greeter の親 POM の `dependencyManagement` で決まる）も集めます。
greeter の POM にある `test` スコープと `optional` の依存は集めません（jar はリポジトリにありません）。

`src/main/java/sample/app/` の `Service.run` が依存 jar の型を「戻り値で受けて次の呼び出しに使う」形で使っており、
jar が渡らなければ `Greeter.of` 以降の呼び出しが型解決に失敗します。`Formatter#wrap` は core（推移的な依存）の型なので、
POM を辿れていることも同じ出力で確かめられます。期待出力は `test/regression/maven/expected/` にあります。

`<repositories>` は Maven でこのプロジェクトを実際にビルドする人のためのもので、ツールは見ません。
