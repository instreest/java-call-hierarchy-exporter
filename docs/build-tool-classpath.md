# 依存 jar の自動取得（Maven / Gradle）

`library.folders` を空欄にしたときに、`pom.xml` / `build.gradle` を読んでローカルリポジトリから
依存 jar を集める仕組み。対応の範囲と実装時に迷った点は
[build-tool-classpath-qa.md](build-tool-classpath-qa.md) にある。

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

集めた一覧（パス・座標・要求元の連鎖）は実行ログ（出力フォルダの `run.log`）に残ります。
キャッシュの `L` 行にも同じパスが入るので、依存 jar を変えたときの差分更新（[cache-design.md](cache-design.md)）はそのまま効きます。

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
その場合は jar を集めたフォルダを `library.folders` に指定してください。

| 設定 | 意味 |
|---|---|
| `library.build.tool` | `auto`（既定）/ `maven` / `gradle` / `none`（自動取得しない） |
| `library.repositories` | ローカルリポジトリ（カンマ区切り）。空欄なら上記の既定。Maven 形式でも Gradle のキャッシュ形式でも可 |

うまくいかないとき:

- ローカルリポジトリに無い jar は警告に出て、無いまま解析が続きます（その型を使う呼び出しは型解決に失敗します）。
  Eclipse や Maven / Gradle で一度依存を取得（ビルド）すればローカルリポジトリに入ります。このツールはダウンロードしません
- 兄弟モジュールがビルドされていない（`target/classes` 等が無い）ときは、そのモジュールのソースも `source.folders` に
  含めてください。ソースから解決されます

## 依存 jar やクラスフォルダを自分で指定するとき

自動取得を使わずに依存を指定する項目は 2 つあり、フォルダの意味が違います。

| 設定 | 何を書くか | フォルダを書いたとき |
|---|---|---|
| `library.folders` | jar を集めたフォルダ（カンマ区切り） | 直下の `*.jar` をすべて使います |
| `library.jars` | jar（`.jmod` も可）かクラスフォルダを 1 件ずつ（カンマ区切り） | **クラスフォルダ**（`target/classes`、Eclipse の `bin` など）としてそのまま使います。中の jar は使いません |

- Eclipse の `.classpath` の `kind="lib"` は `library.jars` と同じ扱いです（jar かクラスフォルダの 1 件）
- `library.jars` に書いたフォルダが jar しか持たない（`.class` も `.java` も無い）ときは、jar を集めたフォルダの
  書き間違いかもしれないので警告します。中の jar を使いたいなら、そのフォルダを `library.folders` に書いてください
- 相対パスの起点は、`library.folders` は `project.root`、`library.jars` は**設定ファイルのフォルダ**です
  （`library.jars` は `~/.m2` などプロジェクトの外を指すのが普通なので、`project.root` の配下でなくてもかまいません）
- クラスフォルダの `.java` も読まれます。Java のパーサ（JDT）は、クラスフォルダに同じ名前の `.class` が無いか、
  `.java` のほうが新しければ `.java` を読みます。差分更新もこれに合わせて変化を見ます
- ソースフォルダ・クラスフォルダ（とその中のパッケージのフォルダ）がシンボリックリンクでも、リンクの先を読みます
  （祖先を指して輪になるリンクの先には入りません）

依存 jar やクラスフォルダを**解析の途中で**作り直す（兄弟モジュールの `mvn install`、IDE の自動ビルド）と、その実行の結果は
書き換えの前後の中身が混ざっているかもしれません。気づいたときは run.log に `[cache]` の行で知らせ、次の実行で、
その実行で解析したファイルとそれを使うファイルを解析し直します（利用者がすることは、もう一度実行することだけです）。
ただし、書き換えて戻すあいだに更新時刻も大きさも変わらなかった場合は見分けられません。

解析サーバー（Eclipse / VSCode のプラグイン）を動かしたまま、同じ jar を**同じ更新時刻のまま**上書きする
（`unzip -o`、`cp -p` など）と、Java の実行環境がプロセスの中で前の jar の中身を覚えていることがあります。そのときは
片付くまで少し待ち、それでも前の中身が見えていれば警告を出して、次の解析で解析し直します。警告が出たら、解析サーバー
（または IDE）を起動し直してから解析し直してください。
