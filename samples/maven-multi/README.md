# samples/maven-multi

回帰テスト `test/regression/mavenmulti` 用のマルチモジュール Maven プロジェクトです。

- `pom.xml` … アグリゲータ（`sample:multi-parent`）。`modules` に `core` と `app`、`dependencyManagement` に `util` の版
- `core/` … `sample.deps:greeter` に依存する。`Core.hello` が greeter を使う
- `app/` … 兄弟モジュール `core`（版は `${project.version}`）と、版を書かない `util`（親の管理で決まる）に依存する。
  `Main` は `Core`（ソースから解決）、`Greeter`（`app/pom.xml` には書いていない。core 経由の推移的な依存）、
  `Strings`（util）を使う

ツールは `library.folders` が空欄なので、各ソースフォルダの上位の `pom.xml`（`core/pom.xml`、`app/pom.xml`）を読みます。
親の `relativePath` を辿ってアグリゲータを見つけ、`modules` からリアクタ（同じビルドのモジュール一覧）を作るので、
`app` の `core` への依存は「`core/target/classes` と `core/pom.xml` の依存」で解決されます（`mvn install` は要りません）。
このサンプルでは `core` はビルドしていないので `target/classes` は無く、ログにその旨の注記が出ますが、
`core` のソースも解析対象なので型はソースから解決されます。
