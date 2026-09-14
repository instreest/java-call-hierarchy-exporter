# docs/ の索引

このフォルダには 4 種類の文書がある。

- **使い方の詳細** … README に書くと長くなる、機能別の利用者向け説明
- **設計の説明** … 出力や性能の理由が分かるように、内部の作りを説明したもの
- **設計の記録（`*-qa.md`）** … 機能を足したり設計判断をしたときに「迷ったこと・結論・却下した案」を Q&A の形で残したもの。
  書き出しは Issue へのリンク → 対応の要点 → Q&A（[AGENTS.md](../AGENTS.md) の「ドキュメントの決まり」）
- **再実装用の仕様** … このツールを別環境で作り直すためのプロンプトと難易度表

## 使い方の詳細

| ファイル | 内容 |
|---|---|
| [cli.md](cli.md) | 起動コマンドの全仕様（引数・終了コード・`launcher.properties`・対話モードのメニュー）、複数の設定ファイルの扱い、ネットワークからの取得の確認、JBang の直接実行、Eclipse でソースを開く |
| [build-tool-classpath.md](build-tool-classpath.md) | `library.folders` を空欄にしたときに `pom.xml` / `build.gradle` を読んで依存 jar を集める仕組みと、読める宣言の範囲 |
| [instance-analysis-plugin.md](instance-analysis-plugin.md) | 具象クラスの解決条件を外から与える（対応表を書く / 拡張を自分で書く） |
| [eclipse-plugin-usage.md](eclipse-plugin-usage.md) | Eclipse プラグインとしての使い方（入れ方・呼び出し元階層ビューの操作・設定・サーバーモード）。解析は Eclipse とは別プロセス・別 JDK で走る |
| [call-conditions.md](call-conditions.md) | 呼び出しに効いている条件を通常の出力に追加で出す（設定ファイルの `conditions.target` → `call-conditions.csv`）。判定できない条件も含める |
| [github-actions.md](github-actions.md) | GitHub Actions からの使い方。参照する版、入力と出力、設定ファイルの渡し方、キャッシュの注意、Actions 以外の CI |

## 設計の説明

| ファイル | 内容 |
|---|---|
| [cache-design.md](cache-design.md) | 解析結果キャッシュの設計方針。事実だけを持つこと、差分更新、依存 jar と JDK の変更への追従 |
| [out-of-process-analysis-design.md](out-of-process-analysis-design.md) | Eclipse プラグインが解析を別プロセス（別 JDK・同梱の JDT）で行う仕組み。プロトコルと配布物の構成 |
| [eclipse-plugin-ui-design.md](eclipse-plugin-ui-design.md) | 呼び出し元階層ビューの画面設計（状態の見せ方・フィルタ・操作） |
| [eclipse-pleiades-versions.md](eclipse-pleiades-versions.md) | Eclipse / JDT Core / Java / Pleiades の版の対応表と、プラグインの動作条件 |
| [branch-pruning.md](branch-pruning.md) | 条件分岐による打ち切り（`branch.pruning.enabled`）。判定できる条件、打ち切りで階層から消えたメソッドを `methods.csv` で探す方法 |

## 設計の記録（Q&A）

| ファイル | Issue | 内容 |
|---|---|---|
| [build-tool-classpath-qa.md](build-tool-classpath-qa.md) | #44 | `pom.xml` / `build.gradle` を読んでローカルリポジトリから依存 jar を集める |
| [cache-dependency-jars-qa.md](cache-dependency-jars-qa.md) | #36 | 依存 jar を変えたときのキャッシュの差分更新。実行 JDK が変わると何が変わるか（Q20） |
| [deterministic-row-order-qa.md](deterministic-row-order-qa.md) | #43 | 出力の行順を環境に依存しない並びに固定する |
| [spring-di-qa.md](spring-di-qa.md) | #66 | Spring の Bean 定義（`@Autowired` / `@Qualifier` / `@Bean`）で候補を絞る |
| [doma-generated-impl-qa.md](doma-generated-impl-qa.md) | #73 | 実装がコンパイル時に生成される型（Doma の `@Dao`）を「実装なし」と言い分ける |
| [instance-analysis-plugin-qa.md](instance-analysis-plugin-qa.md) | #68 | インスタンス解析条件を外から与えるプラグイン（フェーズA / B、実行時コンパイル） |
| [fatjar-external-usage-qa.md](fatjar-external-usage-qa.md) | #35 | 被参照スキャンの FatJar / war / ear 対応 |
| [branch-pruning-qa.md](branch-pruning-qa.md) | #67 | 変数値と条件分岐の静的解析で、その経路では呼ばれない呼び出しを区別する |
| [call-conditions-qa.md](call-conditions-qa.md) | #67 | 呼び出しに効いている条件を `call-conditions.csv` に出す（モードにせず出力を1つ足す判断、キャッシュに載せない判断） |
| [dataflow-facts-qa.md](dataflow-facts-qa.md) | #80 | データフローの事実（ファクトリの戻り値）をフェーズ2bで一括確定し、`CallResolver.resolve` を処理順に依存しない純粋な関数にする |
| [code-review-fixes-qa.md](code-review-fixes-qa.md) | — | コードレビューで見つかった正確性・性能・構造の問題への対応（CHA の継承実装、解決結果のメモ化、クラス分割） |
| [cache-integrity-qa.md](cache-integrity-qa.md) | #97 #98 #99 #100 | キャッシュが静かに嘘をつく 4 件。行がタブ・改行で割れる／コンパイル時定数の値が古いまま残る／文字コードが鍵に入っていない／壊れたキャッシュの扱い |
| [multi-config-output-folder-qa.md](multi-config-output-folder-qa.md) | #60 | 複数の設定ファイルと実行ごとの出力フォルダ |
| [config-folder-qa.md](config-folder-qa.md) | #62 | 既定の設定ファイルを `config/` に置く。相対パスの起点 |
| [cli-app-qa.md](cli-app-qa.md) | #63 | 起動コマンドと対話モード。`.cmd` が MS932 でなければならない理由（Q15） |
| [cli-noninteractive-qa.md](cli-noninteractive-qa.md) | #83 | 引数ありのときは何も尋ねずに解析だけを行う（起動コマンドの初回の質問・知らないオプション・終了コード） |
| [network-download-confirm-qa.md](network-download-confirm-qa.md) | #86 | ネットワークからの取得（JBang 本体・JDK・依存 jar）の前に操作者の確認を必須にする（`--offline` での起動と目印ファイル、端末が無いときの扱い） |
| [eclipse-plugin-qa.md](eclipse-plugin-qa.md) | #49 | Eclipse プラグインとしてビルドできるようにする（Tycho を使わない判断、同梱物の構成） |
| [eclipse-plugin-ui-qa.md](eclipse-plugin-ui-qa.md) | #49 | 呼び出し元階層ビューの実装。別プロセス化、Java 8 対応、設定画面 |
| [eclipse-maven-qa.md](eclipse-maven-qa.md) | #39 | Eclipse（Pleiades）で開くための `pom.xml`。Gradle や jbang-eclipse を選ばなかった理由 |
| [github-actions-qa.md](github-actions-qa.md) | #48 | 複合アクションとしての設計。入力から設定ファイルを生成する判断 |
| [actions-analysis-cache-qa.md](actions-analysis-cache-qa.md) | #79 | GitHub Actions で解析キャッシュを実行間で引き継ぐ |
| [jpms-modularity-qa.md](jpms-modularity-qa.md) | — | JPMS でモジュール化するかの検討（結論: しない） |
| [samples-demo-move-qa.md](samples-demo-move-qa.md) | #51 | `samples/demo` を `test/demo` へ移した |
| [source-header-qa.md](source-header-qa.md) | #47 | ソースの著作権表示・ライセンス表記の簡略化 |
| [jbangw-readme-notice-qa.md](jbangw-readme-notice-qa.md) | #50 | 同梱した JBang ラッパーへのライセンス表記と README |
| [entrypoint-package-qa.md](entrypoint-package-qa.md) | — | 入口 2 つを既定パッケージから `jche` パッケージへ移した。`//SOURCES` の glob が直下に当たらない理由 |

## 再実装用の仕様

| ファイル | 内容 |
|---|---|
| [prompt-A-minimal.md](prompt-A-minimal.md) | 目的と出力の意味を厚く、実装手順は最小限にした再実装プロンプト |
| [prompt-B-detailed.md](prompt-B-detailed.md) | AST 解析のはまりどころとテストケースまで含めた再実装プロンプト |
| [feature-difficulty.md](feature-difficulty.md) | 機能ごとの実装難易度と、自分のプロジェクトでは省いてよい機能の目安 |
