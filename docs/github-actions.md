# GitHub Actions から使う

リポジトリ直下の [`action.yml`](../action.yml) を `uses:` で呼ぶときの使い方。実装時の判断は
[github-actions-qa.md](github-actions-qa.md) と [actions-analysis-cache-qa.md](actions-analysis-cache-qa.md) にある。

リポジトリ直下の [`action.yml`](../action.yml) が GitHub Actions のアクションです。
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
（[出力されるファイル](../README.md#出力されるファイル)）。ジョブのサマリには出力フォルダと CSV の行数が出ます。

## 参照する版の指定

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

## 入力

設定ファイルを自分で用意しない場合は、次の入力から設定ファイルを 1 つ生成します。
意味は同名の設定項目（[config/config.properties](../config/config.properties) のコメント）と同じです。

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
| `analysis-cache` | `true` | AST 解析結果のキャッシュ（`.cache/`）をワークフロー実行間で引き継ぐ。前回から変わっていないソースは解析を飛ばす |
| `upload-artifact` | `true` | 出力フォルダをアーティファクトにする |
| `artifact-name` | `call-hierarchy` | アーティファクトの名前 |
| `artifact-retention-days` | （空欄） | アーティファクトの保持日数 |

## 設定ファイルを渡す（`config` 入力）

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

### 設定ファイルの書き方（CI 向けの例）

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
# 解析対象リポジトリのチェックアウトは汚れない。空欄にしておくと analysis-cache 入力で
# ワークフロー実行間に引き継がれる（別の場所を指定すると引き継ぎの対象から外れる）
cache.folder=
```

設定できる項目の一覧と意味は [config/config.properties](../config/config.properties) のコメントにあります。
手元で `./java-call-hierarchy-exporter.sh ci/call-hierarchy.properties` と実行したときと同じ設定なので、CI で出た結果を手元で再現できます。

**出力先だけは注意**してください。`output.folder` の既定は「設定ファイルと同じフォルダ」なので、
指定しないと解析対象リポジトリのチェックアウトの中（設定ファイルの隣）に出力フォルダができます。
相対パスは設定ファイルのフォルダの配下しか指せない（`..` で外へ出るとエラーになります）ので、
上の例のように配下へ出すか、リポジトリの外に出したい場合は絶対パス（Linux のランナーなら
`/tmp/call-hierarchy-output` など）を書きます。リポジトリ内に出すなら、その場所を `.gitignore` に
足しておくと `git diff --exit-code` のような検査と併用できます。

### 複数渡す

改行区切り（`|`）またはカンマ区切りで複数渡せます。渡した順に 1 つずつ処理し、
設定ファイルごとに別の出力フォルダができます（[複数のプロジェクトをまとめて解析する](../README.md#複数のプロジェクトをまとめて解析する)）。

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

## 出力

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

## 使うときの注意

- **依存 jar** … `library-folders` を空欄にすると `pom.xml` / `build.gradle` を読んでローカルリポジトリから
  依存 jar を集めますが、このツールはネットワークに出ません。ランナーの `~/.m2/repository` は空なので、
  先に `mvn -B dependency:go-offline`（Gradle なら依存を取得するタスク）を実行しておいてください。
  取得しないまま実行しても解析自体は動きますが、jar の型が解決できず呼び出しが欠けます
- **JDK** … ツール自身は JDK 25 で動きます（`//JAVA 25`）。`java-version` を空欄にして自分で用意する場合も
  25 を入れてください。解析対象のビルドに別の JDK が要る場合は、そのステップで別途セットアップします
- **キャッシュ** … `cache: true` のとき、JBang 本体（`~/.jbang`）と JDT の jar（`~/.m2/repository/org/eclipse`）を
  ワークフロー実行間でキャッシュします。`analysis-cache: true`（既定）のときは、AST 解析結果のキャッシュ（`.cache/`）も
  前回の実行から引き継ぎ、変わっていないソースの解析を飛ばします。`actions/checkout` はファイルの更新時刻を
  チェックアウト時刻にしますが、更新時刻だけが違うファイルはサイズと内容ハッシュで突き合わせるので、
  中身が同じなら再利用されます。キャッシュは実行ごとに新しいエントリとして保存され（`jche-analysis-<OS>-…`）、
  復元は「同じ設定ファイルの最新」→「同じ OS の最新」の順に前方一致で探します。古いエントリは GitHub が
  容量（リポジトリごとに 10 GB）と期限（7 日間使われないもの）で消します。
  `config` 入力で設定ファイルを渡す場合は `cache.folder` を空欄のままにしてください（別の場所を指定すると、
  引き継ぎの対象から外れます）。同じジョブの中でこのアクションを 2 回以上呼ぶときは、2 回目以降はその場の `.cache/` を
  そのまま使います
- **作業ツリー** … 生成した設定ファイルは `RUNNER_TEMP` に、解析キャッシュはアクション自身のフォルダに
  作るので、解析対象リポジトリのチェックアウトには出力フォルダ以外を作りません
  （既定は `call-hierarchy-output/`。`.gitignore` に足しておくと `git diff --exit-code` 等と併用できます）

## Actions 以外の CI から使うとき

環境変数 `JCHE_OUTPUT_DIR_FILE` にファイルのパスを渡して実行すると、設定ファイルごとの出力フォルダの
絶対パスを、成功した順に 1 行ずつ UTF-8 でそのファイルに書きます。実行ごとに変わる出力フォルダ名
（`<解析開始日時>_<プロジェクト名>`）を、ログを読まずに受け取れます（`action.yml` もこれを使っています）。

```bash
JCHE_OUTPUT_DIR_FILE=out-dirs.txt ./jbangw/jbang src/CallHierarchyExporter.java config/config.properties
cat out-dirs.txt   # /path/to/config/20260907-163000_myapp
```

起動コマンド（`./java-call-hierarchy-exporter.sh a.properties`、Windows は `java-call-hierarchy-exporter.cmd`）から実行したときも同じです
（対話モードで解析した場合も書き出します）。

## このリポジトリ自身での使用例

このリポジトリも、自分のソース（`src/`）をこのアクションで解析しています
（[.github/workflows/call-hierarchy.yml](../.github/workflows/call-hierarchy.yml)。`main` への push と手動実行）。
依存 jar は `pom.xml` から自動で集めるので、その前に `mvn -B dependency:go-offline` を置いてあります。
そのまま写して使える最小の形なので、書き方に迷ったらこのファイルを見てください。

実装時に迷った点は [docs/github-actions-qa.md](github-actions-qa.md) と、解析キャッシュの引き継ぎについては
[docs/actions-analysis-cache-qa.md](actions-analysis-cache-qa.md) にまとめています。
