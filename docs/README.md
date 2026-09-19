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
| [instance-analysis-plugin.md](instance-analysis-plugin.md) | 具象クラスの解決条件を外から与える（対応表を書く / 拡張を自分で書く）。拡張に渡る証拠、ファクトリごとの場合分け、書いた対応表が効いているかの確かめ方 |
| [eclipse-plugin-usage.md](eclipse-plugin-usage.md) | Eclipse プラグインとしての使い方（入れ方・呼び出し元階層ビューの操作・設定・サーバーモード）。解析は Eclipse とは別プロセス・別 JDK で走る |
| [vscode-plugin-usage.md](vscode-plugin-usage.md) | VSCode プラグインとしての使い方（入れ方・標準の呼び出し階層との違い・状態の見方・設定・ビルド）。解析は Eclipse 版と同じ子プロセスで走る |
| [callback-contracts.md](callback-contracts.md) | ソースの外（JDK・フレームワーク）との契約表。jar の中から呼び戻される呼び出しを繋ぐ（`Thread#start()` → `run()` 等）ことと、フレームワークが呼ぶ入口を `FRAMEWORK_ENTRY` に仕分けること。具象クラスを 1 件に絞ること（`=>`。ファクトリのキーでの絞り込みを含む）と、絞れなかった呼び出しから出るひな形。自前のフレームワーク分を `contracts.files` / 拡張で足す方法と、書いた契約が効いているかの確かめ方 |
| [call-conditions.md](call-conditions.md) | 呼び出しに効いている条件を通常の出力に追加で出す（設定ファイルの `conditions.target` → `call-conditions.csv`）。判定できない条件も含める |
| [static-analysis-limits.md](static-analysis-limits.md) | 静的解析で具象クラスが決まる条件と決まらない条件（しきい値）。文字列からクラス名を算出するファクトリを例に、追える出所・追えない出所と、健全側に倒す方針 |
| [github-actions.md](github-actions.md) | GitHub Actions からの使い方。参照する版、入力と出力、設定ファイルの渡し方、キャッシュの注意、セキュリティ上の注意（`pull_request_target`、self-hosted ランナー、依存取得の省略）、Actions 以外の CI |

## 設計の説明

| ファイル | 内容 |
|---|---|
| [cache-design.md](cache-design.md) | 解析結果キャッシュの設計方針。2 つに分けた置き場所、事実だけを持つこと、差分更新、依存 jar と JDK の変更への追従 |
| [out-of-process-analysis-design.md](out-of-process-analysis-design.md) | Eclipse プラグインが解析を別プロセス（別 JDK・同梱の JDT）で行う仕組み。プロトコルと配布物の構成 |
| [eclipse-plugin-ui-design.md](eclipse-plugin-ui-design.md) | 呼び出し元階層ビューの画面設計（状態の見せ方・フィルタ・操作） |
| [vscode-plugin-design.md](vscode-plugin-design.md) | VSCode プラグインの設計案（未実装）。既存のサーバープロトコルの再利用、設定の自動生成、カーソル位置からメソッドを引く `AT` の追加 |
| [eclipse-pleiades-versions.md](eclipse-pleiades-versions.md) | Eclipse / JDT Core / Java / Pleiades の版の対応表と、プラグインの動作条件 |
| [branch-pruning.md](branch-pruning.md) | 条件分岐による打ち切り（`branch.pruning.enabled`）。判定できる条件、打ち切りで階層から消えたメソッドを `methods.csv` で探す方法 |
| [contracts-unification-design.md](contracts-unification-design.md) | 解決条件の指定を契約表に一本化する設計（実装済み）。具象クラスの対応を契約表の 1 行で書く「種類 C」、証拠を dataflow キャッシュから引けること、旧来の `resolver.*` / `plugin.*` との互換 |

## 設計の記録（Q&A）

| ファイル | Issue | 内容 |
|---|---|---|
| [build-tool-classpath-qa.md](build-tool-classpath-qa.md) | #44 | `pom.xml` / `build.gradle` を読んでローカルリポジトリから依存 jar を集める |
| [cache-dependency-jars-qa.md](cache-dependency-jars-qa.md) | #36 | 依存 jar を変えたときのキャッシュの差分更新。実行 JDK が変わると何が変わるか（Q20） |
| [deterministic-row-order-qa.md](deterministic-row-order-qa.md) | #43 | 出力の行順を環境に依存しない並びに固定する |
| [spring-di-qa.md](spring-di-qa.md) | #66 | Spring の Bean 定義（`@Autowired` / `@Qualifier` / `@Bean`）で候補を絞る |
| [doma-generated-impl-qa.md](doma-generated-impl-qa.md) | #73 | 実装がコンパイル時に生成される型（Doma の `@Dao`）を「実装なし」と言い分ける |
| [instance-analysis-plugin-qa.md](instance-analysis-plugin-qa.md) | #68 | インスタンス解析条件を外から与えるプラグイン（フェーズA / B、実行時コンパイル）。引かれなかった対応表の行の知らせ方、フェーズAを報告しない理由、ファクトリのキーをツールが渡すようにした判断 |
| [fatjar-external-usage-qa.md](fatjar-external-usage-qa.md) | #35 | 被参照スキャンの FatJar / war / ear 対応 |
| [external-usage-callsite-qa.md](external-usage-callsite-qa.md) | — | 被参照スキャンで呼び出し元メソッドと行番号まで出す（class の命令列を自前で読む） |
| [cache-split-qa.md](cache-split-qa.md) | — | キャッシュを呼び出し階層用とデータフロー用の 2 つに分け、対でしか再利用しないようにする |
| [branch-pruning-qa.md](branch-pruning-qa.md) | #67 | 変数値と条件分岐の静的解析で、その経路では呼ばれない呼び出しを区別する |
| [call-conditions-qa.md](call-conditions-qa.md) | #67 | 呼び出しに効いている条件を `call-conditions.csv` に出す（モードにせず出力を1つ足す判断、キャッシュに載せない判断） |
| [dataflow-facts-qa.md](dataflow-facts-qa.md) | #80 | データフローの事実（ファクトリの戻り値）をフェーズ2bで一括確定し、`CallResolver.resolve` を処理順に依存しない純粋な関数にする |
| [callback-contracts-qa.md](callback-contracts-qa.md) | #136 | ソースの外との契約表（呼び戻しの辺、フレームワークの入口、設定ファイル・拡張で足す形）。辺の足し方、`inDegree` への効かせ方、広い候補を出さない判断、`role` に優先させる判断、当たらなかった行の知らせ方、列挙定数のキーの書き方、ひな形の出し方 |
| [inherited-impl-candidates-qa.md](inherited-impl-candidates-qa.md) | #131 | 段2（`LOCAL_NEW`）と段3（拡張）が、親から継承した実装を候補にできていなかった件。`implementationIn` への統一と、採用できなかった候補の警告 |
| [lambda-expansion-qa.md](lambda-expansion-qa.md) | #127 | ラムダ式の本体を合成メソッド（`lambda$...`）にして、関数型インターフェース経由の呼び出しを本体まで辿る。生成の辺を残す判断、捕捉した変数（`E:`）の扱い、追える形と追えない形 |
| [note-tags-qa.md](note-tags-qa.md) | — | 注記に grep 用のタグ（`[UNEXPANDED:*]` / `[EXTERNAL]` / `[UNREACHABLE]` / `[RESOLVED:*]`）を付け、`methods.csv` の列とも揃える。`NO_IMPL` を階層に戻した判断、ラムダを展開できない理由 |
| [code-review-fixes-qa.md](code-review-fixes-qa.md) | — | コードレビューで見つかった正確性・性能・構造の問題への対応（CHA の継承実装、解決結果のメモ化、クラス分割） |
| [excluded-entry-promotion-qa.md](excluded-entry-promotion-qa.md) | #121 | 除外した呼び出し先の具象を絞れないと、実装側が入次数 0 になって起点に昇格する件。`java.lang.Object` を型階層に載せない判断と、利用者側の回避策 |
| [ast-analysis-performance-qa.md](ast-analysis-performance-qa.md) | — | AST 解析の性能とメモリ使用量の改善（何を計測して直したか、バッチのサイズ・並列化・仮想スレッドを採らなかった理由と実測値） |
| [cache-identity-qa.md](cache-identity-qa.md) | #102 #103 #104 | キャッシュの「同じファイルか」の判定をパス・サイズ・内容の指紋に統一する（更新時刻をやめる）。クラスパスの並び順の変化を検知する。サーバーモードの `SHUTDOWN` が実行中の解析を中止していた件 |
| [cache-integrity-qa.md](cache-integrity-qa.md) | #97 #98 #99 #100 #101 | キャッシュが静かに嘘をつく 4 件（行がタブ・改行で割れる／コンパイル時定数の値が古いまま残る／文字コードが鍵に入っていない／壊れたキャッシュの扱い）と、中断した実行からの引き継ぎ |
| [multi-config-output-folder-qa.md](multi-config-output-folder-qa.md) | #60 | 複数の設定ファイルと実行ごとの出力フォルダ |
| [config-folder-qa.md](config-folder-qa.md) | #62 | 既定の設定ファイルを `config/` に置く。相対パスの起点 |
| [config-file-name-qa.md](config-file-name-qa.md) | — | 設定ファイルの名前。本体は `config.properties` のまま、プラグインが利用者のプロジェクトに書くのは `jche.properties`（誤検出を中身で防ぐ） |
| [cli-app-qa.md](cli-app-qa.md) | #63 | 起動コマンドと対話モード。`.cmd` が MS932 でなければならない理由（Q15） |
| [cli-noninteractive-qa.md](cli-noninteractive-qa.md) | #83 | 引数ありのときは何も尋ねずに解析だけを行う（起動コマンドの初回の質問・知らないオプション・終了コード） |
| [network-download-confirm-qa.md](network-download-confirm-qa.md) | #86 | ネットワークからの取得（JBang 本体・JDK・依存 jar）の前に操作者の確認を必須にする（`--offline` での起動と目印ファイル、端末が無いときの扱い） |
| [eclipse-plugin-qa.md](eclipse-plugin-qa.md) | #49 | Eclipse プラグインとしてビルドできるようにする（Tycho を使わない判断、同梱物の構成） |
| [eclipse-plugin-ui-qa.md](eclipse-plugin-ui-qa.md) | #49 | 呼び出し元階層ビューの実装。別プロセス化、Java 8 対応、設定画面 |
| [vscode-plugin-qa.md](vscode-plugin-qa.md) | — | VSCode プラグインの設計判断（VSCode の作法との折り合い、標準の呼び出し階層に相乗りしない理由、`AT` を足した理由と断り方、`.vsix` を手動で作るワークフロー） |
| [eclipse-maven-qa.md](eclipse-maven-qa.md) | #39 | Eclipse（Pleiades）で開くための `pom.xml`。Gradle や jbang-eclipse を選ばなかった理由 |
| [github-actions-qa.md](github-actions-qa.md) | #48 | 複合アクションとしての設計。入力から設定ファイルを生成する判断 |
| [actions-analysis-cache-qa.md](actions-analysis-cache-qa.md) | #79 | GitHub Actions で解析キャッシュを実行間で引き継ぐ |
| [jpms-modularity-qa.md](jpms-modularity-qa.md) | — | JPMS でモジュール化するかの検討（結論: しない） |
| [samples-demo-move-qa.md](samples-demo-move-qa.md) | #51 | `samples/demo` を `test/demo` へ移した |
| [source-header-qa.md](source-header-qa.md) | #47 | ソースの著作権表示・ライセンス表記の簡略化 |
| [jbangw-readme-notice-qa.md](jbangw-readme-notice-qa.md) | #50 | 同梱した JBang ラッパーへのライセンス表記と README |
| [method-decl-range-qa.md](method-decl-range-qa.md) | #115 | メソッドの宣言範囲（終了行）をキャッシュと `MethodTable` に持つ。カーソル位置から囲むメソッドを引く `AT`（近似をやめる判断、`methods.csv` に出さない判断） |
| [callee-label-qa.md](callee-label-qa.md) | — | `call-hierarchy.csv` の `callee` 列を「クラス名.メソッド名」だけにし、`NO_IMPL` の注記を出さなくした |
| [entrypoint-package-qa.md](entrypoint-package-qa.md) | — | 入口 2 つを既定パッケージから `jche` パッケージへ移した。`//SOURCES` の glob が直下に当たらない理由 |

## 再実装用の仕様

| ファイル | 内容 |
|---|---|
| [prompt-A-minimal.md](prompt-A-minimal.md) | 目的・出力の契約・後から直しやすい作りだけを渡し、設計は生成AIに任せるプロンプト（機能の再現は狙わない） |
| [prompt-B-detailed.md](prompt-B-detailed.md) | AST 解析のはまりどころとテストケースまで含めた再実装プロンプト |
| [feature-difficulty.md](feature-difficulty.md) | 機能ごとの実装難易度と、自分のプロジェクトでは省いてよい機能の目安 |
