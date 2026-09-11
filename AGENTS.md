# AGENTS.md

AI コーディングエージェント（Claude Code、Codex、Copilot 等）と、初めてこのリポジトリを触る人向けの案内。
利用者向けの説明は [README.md](README.md)、設定項目は [config/config.properties](config/config.properties) のコメントにある。

## このリポジトリは何か

Java プロジェクト全体のメソッド呼び出し階層を、Eclipse JDT のコンパイラ（`org.eclipse.jdt.core`）で解析して
CSV（`call-hierarchy.csv` / `methods.csv`）に書き出すツール。Eclipse は起動せず、JBang で単体で動く。
目的は「改修時の影響調査で呼び出しを漏らさない」こと。迷ったら **呼び出しを静かに落とさない（安全側に倒す）** を優先する。

## 構成

| 場所 | 役割 |
|---|---|
| `src/CallHierarchyExporter.java` | 解析のエントリポイント（`//DEPS` と `//JAVA` の JBang ヘッダを持つ） |
| `src/Jche.java` | 対話モードのエントリポイント。起動コマンドから呼ばれる |
| `src/jche/` | 本体。`config`（設定・ビルドファイル読み取り）、`analysis`（AST 訪問・キャッシュ更新）、`graph`（呼び出しグラフ・具象クラス解決）、`dataflow`（データフローの事実をグラフ全体から一括で確定）、`report`（CSV 出力）、`cli`（対話モード）、`extension` / `builtin`（プラグイン）、`external`（jar からの被参照）、`cache`、`framework`、`util` |
| `java-call-hierarchy-exporter.sh` / `.cmd` | リポジトリ直下の起動コマンド。引数なしで対話モード、設定ファイルを渡すと対話なし |
| `jbangw/` | JBang 本家のラッパースクリプトをそのまま同梱（MIT）。JBang のインストール不要 |
| `config/` | 設定ファイル置き場。`config.properties` がひな形兼既定 |
| `action.yml` / `.github/action/` | 同じ解析を CI で動かす複合アクション |
| `test/` | 回帰テストと検査スクリプト（後述） |
| `docs/*-qa.md` | 機能ごとの「実装時に迷ったこと・困ったことと結論」を Q&A 形式で残した記録 |
| `docs/prompt-*.md` / `docs/feature-difficulty.md` | このツールを別環境で再実装するための仕様プロンプトと難易度表 |

## 動かす

```bash
./java-call-hierarchy-exporter.sh                       # 対話モード
./jbangw/jbang src/CallHierarchyExporter.java config/config.properties   # 対話なし
```

初回は JDK 25 と JDT の jar を自動取得する（数百 MB）。設定ファイルは `project.root` だけ書けば動き、
`source.folders` / `library.folders` / `source.encoding` は空欄なら `project.root` の中身から決める
（`src/jche/config/ProjectDetector.java`）。

## テスト（変更したら必ず通す）

CI（`.github/workflows/smoke.yml`）と同じものを手元で実行できる。すべて bash から。

| コマンド | 内容 |
|---|---|
| `bash test/regression/run.sh` | 回帰テスト。`test/demo` 等を解析して `expected*/` の CSV と比較。キャッシュ再利用・jar 増減・Maven / Gradle・プラグイン・複数設定の各ケース |
| `bash test/dataflow/run.sh` | 解決の決定性の検査。`test/demo` の全エッジを 3 通りの順で `CallResolver.resolve` して結果が一致すること |
| `bash test/cli/run.sh` | 起動コマンドと対話モードの検査。メニューへの答えをパイプで流し込む |
| `bash test/pom/run.sh` | `//DEPS` 行と `pom.xml` の依存が一致すること |
| `bash test/jbangw/run.sh` | `jbangw/` が本家から黙って変わっていないこと |
| 全ソースの lint | `javac --release 17 -Xlint:all -Werror -Xdoclint:all,-missing`（smoke.yml の「Compile with all lint warnings as errors」と同じ引数） |

- テストのシェルは UTF-8 ロケールで動かす（`LANG=C.UTF-8`）。ロケール未設定の環境では launcher.properties の日本語書き込みで落ちる
- 出力 CSV の期待値（`expected*/`）を更新するときは、差分を確認したうえで最新の `output/*/` からコピーする。
  理由なく期待値を書き換えて通さない
- テストをスキップ・無効化して通すことはしない

## コードの決まり

- Java 17 の言語機能で書く（`--release 17` でコンパイルする。実行 JDK は 25）。警告ゼロが前提
- 文字コードは UTF-8。例外は `java-call-hierarchy-exporter.cmd` だけ MS932・CRLF（`.gitattributes` で `-text`）。
  編集するときは MS932 のまま保存する。この制約の理由は `docs/cli-app-qa.md` の Q15
- コメント・ログ・ドキュメントは日本語。ログのメッセージは利用者が次に何をすればよいか分かる書き方にする
- JDT の版を上げるときは `src/CallHierarchyExporter.java` と `src/Jche.java` の `//DEPS` 行、`pom.xml` の 3 か所を揃える
  （`test/pom/run.sh` が検出する）
- 出力の行順は環境に依存しない決定的な並びを保つ（`docs/deterministic-row-order-qa.md`）。ソート順を変えると期待値が全部変わる
- キャッシュの形式や鍵を変えるときは、古いキャッシュを安全に捨てる経路を用意する（`docs/cache-dependency-jars-qa.md`）
- 相対パスの起点は項目ごとに決まっている（`config/config.properties` 冒頭のコメント）。起点の外へ出る相対パスはエラーにする

## ドキュメントの決まり

- 機能を足したり設計判断をしたときは `docs/<機能>-qa.md` に「迷ったこと・結論・却下した案」を Q&A で残す。
  既存ファイルの書き出しに倣う（Issue へのリンク → 対応の要点 → Q&A）
- `docs/` のファイル名に `license`、`licence`、`copyright`、`copying`、`patents` を使わない。
  GitHub がルート・`.github/`・`docs/` のこれらの名前をライセンスファイルとみなし、README 横の License 欄に並べてしまう
- README の Quick start は「動かして CSV を見るまでの最小手順」だけにし、詳細は後続のセクションに書く
- 起動コマンドの名前を参照する箇所は多い（src、docs、test、workflows、`.gitattributes`、`.gitignore`）。
  改名したら `grep -rn` で旧名が残っていないことを確認する

## Git

- 作業ブランチで開発し、PR でマージする。`main` に直接 push しない
- コミットメッセージは日本語で、何を・なぜ変えたかを書く
- `launcher.properties`、`.jbang/`、`.cache/`、`config/<日時>_<プロジェクト名>/` は環境ごとの生成物なのでコミットしない（`.gitignore` 済み）
