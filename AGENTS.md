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
| `src/jche/CallHierarchyExporter.java` | 解析のエントリポイント（`//DEPS` と `//JAVA` の JBang ヘッダを持つ） |
| `src/jche/Jche.java` | 起動コマンドのエントリポイント。引数があれば対話なしで解析し、無ければ対話モードに入る |
| `src/jche/` | 本体。`config`（設定・ビルドファイル読み取り。Gradle は `GradleBuild` / `GradleSettings` / `GradleLockfile` / `GradleScripts`）、`analysis`（AST 訪問・キャッシュ更新。`FactVisitor` が `TypeContextTracker` / `CallSiteRecorder` / `FieldAccessRecorder` / `OriginTracker`（＋上限の無い値グラフを作る `ValueGraph`） / `FieldFactCollector` / `LambdaNames`（ラムダの合成メソッドの名前を先に配る）に分担）、`graph`（呼び出しグラフ・具象クラス解決。`OriginRenderer` が dataflow キャッシュの値グラフを出所の文字列へ組み直す）、`dataflow`（データフローの事実をグラフ全体から一括で確定）、`report`（CSV 出力）、`cli`（対話モード。画面は `App` / `ConfigWizard` / `EnvironmentSettingsScreen` / `StatusScreen`）、`extension` / `builtin`（プラグイン）、`external`（jar からの被参照）、`cache`（行形式の record と `CacheReader`）、`framework`、`util` |
| `java-call-hierarchy-exporter.sh` / `.cmd` | リポジトリ直下の起動コマンド。引数なしで対話モード、設定ファイルを渡すと何も尋ねずに解析だけ行う（`docs/cli-noninteractive-qa.md`）。ネットワークからの取得（JBang / JDK / 依存 jar）だけは必ず確認する（`docs/network-download-confirm-qa.md`） |
| `jbangw/` | JBang 本家のラッパースクリプトをそのまま同梱（MIT）。JBang のインストール不要 |
| `config/` | 設定ファイル置き場。`config.properties` がひな形兼既定 |
| `src/jche/util/Messages*.java` | 利用者に見せる文言。英語が既定で、日本語（`MessagesJa`）を重ねる。CSV のセルはここを通さず英語で固定（`docs/nls-qa.md`） |
| `action.yml` / `.github/action/` | 同じ解析を CI で動かす複合アクション |
| `eclipse-plugin/` | Eclipse プラグイン。解析は別プロセス（`--server`）に任せ、画面だけを持つ（`docs/out-of-process-analysis-design.md`）。画面の文言は英語が既定で、日本語は `messages_ja.properties` に置く（`docs/eclipse-plugin-nls-qa.md`） |
| `vscode-plugin/` | VSCode プラグイン（TypeScript、esbuild で1ファイルに束ねる）。同じ `--server` を子プロセスとして使う。`src/server/` と `src/config.ts` は `vscode` に触らない層で、Node だけで検査できる（`docs/vscode-plugin-design.md`）。画面の文言は英語が既定で、日本語は `src/messages.ja.ts`。`package.json` の寄与は `package.nls*.json`（`docs/nls-qa.md` の Q15） |
| `test/` | 回帰テストと検査スクリプト（後述） |
| `docs/README.md` | `docs/` の索引。使い方の詳細（`cli.md`、`build-tool-classpath.md`、`instance-analysis-plugin.md`、`github-actions.md`）、設計の説明（`cache-design.md`）、設計の記録、再実装用の仕様に分かれる |
| `docs/*-qa.md` | 機能ごとの「実装時に迷ったこと・困ったことと結論」を Q&A 形式で残した記録 |
| `docs/prompt-*.md` / `docs/feature-difficulty.md` | このツールを別環境で再実装するための仕様プロンプトと難易度表 |

## 動かす

```bash
./java-call-hierarchy-exporter.sh                            # 対話モード
./java-call-hierarchy-exporter.sh config/config.properties   # 対話なし（引数あり）
./jbangw/jbang src/jche/CallHierarchyExporter.java config/config.properties   # 起動コマンドを通さない場合
```

初回は JDK 25 と JDT の jar を取得する（数百 MB）。起動コマンドは取得の前に確認を出す（`n` か端末なしなら取得せず終了コード 3。
無人なら `JCHE_ALLOW_DOWNLOAD=yes`。`docs/network-download-confirm-qa.md`）。jbang を直接使えば確認なしで取得する。
設定ファイルは `project.root` だけ書けば動き、
`source.folders` / `library.folders` / `source.encoding` は空欄なら `project.root` の中身から決める
（`src/jche/config/ProjectDetector.java`）。

## テスト（変更したら必ず通す）

CI（`.github/workflows/smoke.yml`）と同じものを手元で実行できる。すべて bash から。

| コマンド | 内容 |
|---|---|
| `bash test/regression/run.sh` | 回帰テスト。`test/demo` 等を解析して `expected*/` の CSV と比較。キャッシュ再利用・jar 増減・Maven / Gradle・プラグイン・複数設定の各ケース |
| `bash test/dataflow/run.sh` | 解決の決定性の検査。`test/demo` の全エッジを 3 通りの順で `CallResolver.resolve` して結果が一致すること |
| `bash test/conditions/run.sh` | `conditions.target` を書いたときに追加で出る `call-conditions.csv` の検査。判定可・判定不可の出し分けと、通常の出力が変わらないこと |
| `bash test/incremental/run.sh` | キャッシュの健全性の検査。ソースを書き換えたあとの差分更新の結果が、キャッシュを消してからの全件解析の結果（CSV とキャッシュ）と一致すること。文字コードの変更・形式の版が古い・壊れたキャッシュでは再利用せず捨てること。中断した実行から引き継ぐこと。期待値ファイルは持たない |
| `bash test/cli/run.sh` | 起動コマンドと対話モードの検査。メニューへの答えをパイプで流し込む |
| `bash test/ctorbody/run.sh` | コンストラクタ本体の読み取り（JLS 8.8.7）の検査。柔軟なコンストラクタ本体（JEP 513。`this(...)` の前に文を書ける）を「委譲していない」と取り違えないこと。この構文は Java 25 でしか書けないので `test/demo` には置かず、使い捨てのプロジェクトをその場で作る（`docs/jls-conformance-qa.md` の Q17） |
| `bash test/cachevalue/run.sh` | dataflow キャッシュの値の符号化（`escape` / `unescape`）が往復し、行を壊さないこと |
| `bash test/contracts/run.sh` | 同梱の契約表（`JdkCallbacks` / `BundledFrameworkEntries`）の検査。全行が parse でき、JDK の型は宣言元と呼び戻すメソッドが実在すること（実行中の JDK と照合） |
| `bash test/cachetail/run.sh` | キャッシュの最終行（`Z` 行）を末尾から読む部分の境界（改行の有無・CRLF・読む量の境目・多バイト文字） |
| `bash test/pom/run.sh` | `//DEPS` 行と `pom.xml` の依存が一致すること |
| `bash test/action/run.sh` | GitHub Actions の複合アクション（`.github/action/run.sh`）が、`run.log` の依存 jar の警告を表示言語（英語・日本語）に関わらず warning アノテーションとジョブサマリに出すこと。jbang はスタブに差し替えて解析は動かさない |
| `bash test/jbangw/run.sh` | `jbangw/` が本家から黙って変わっていないこと |
| `bash test/plugin-config/run.sh` | Eclipse プラグインが自動生成した設定（`EclipseProjectConfig#toFileText`）が、解析側と同じ読み方（`Properties#load`）でそのまま読み戻せること。Windows のパスのバックスラッシュを逃がし忘れると解析ごと失敗する |
| `bash test/plugin-api/run.sh` | Eclipse プラグインが下限の Eclipse（4.17 / 2020-09）の jar と `--release 11` でコンパイルできること。本番のビルドは新しい jar を使うので、この検査だけが下限を守る |
| `bash test/nls/run.sh` | ツール全体の文言（英語が既定、日本語は重ねる）の検査。`src/` に日本語のリテラルが残っていないこと、英語と日本語でキーと差し込みがそろうこと、起動コマンドの表がそろうこと、言語の決まり方（`JCHE_LANG` > `jche.lang` > `message.language` > OS）、そして**出力 CSV が言語で変わらないこと**（`docs/nls-qa.md`） |
| `bash test/plugin-nls/run.sh` | Eclipse プラグインの文言（英語が既定、日本語は重ねる）の検査。ソースに日本語のリテラルが残っていないこと、キーがそろうこと、`plugin.xml` の `%キー` があること、配布物に入ること、`osgi.nl` で切り替わり UTF-8 として読めること（`docs/eclipse-plugin-nls-qa.md`） |
| `bash test/server/run.sh` | サーバーモード（`--server`）のプロトコルの検査。`HELLO` → `ANALYZE` → `FIND` / `AT` → `TREE` → `EXPORT` と断り方 |
| `bash test/vscode/run.sh` | VSCode プラグインの `vscode` に触らない層の検査（Node 22 と npm が要る）。型検査、子プロセスとの一連のやりとり、木の組み直し、設定の用意、拡張本体を束ねられること、文言（英語と日本語でキーと差し込みがそろうこと・ソースに日本語が残っていないこと・`package.json` の `%キー%` が `package.nls*.json` とそろうこと） |
| `bash test/vscode/package.sh` | VSCode プラグインの配布物（`.vsix`）の検査（上に加えて Maven と JDK 21 以上が要る）。`lib/` が eclipse-plugin のビルドから集まり JDT の版が `//DEPS` と同じこと、入るもの（`package.nls*.json` を含む）・入らないもの |
| 全ソースの lint | `javac --release 17 -Xlint:all -Werror -Xdoclint:all,-missing`（smoke.yml の「Compile with all lint warnings as errors」と同じ引数）。**CI と同じ JDK 25 の javac で走らせる**（下記） |

- ツールを動かす検査スクリプトは `JCHE_LANG=en` を輸出して言語を固定する。既定の経路をそのまま検査でき、
  実行環境のロケールで照合する文字列が変わらなくなる。日本語への切り替えそのものは `test/nls/run.sh` が見る
- テストのシェルは UTF-8 ロケールで動かす（`LANG=C.UTF-8`）。ロケール未設定の環境では launcher.properties の
  日本語書き込みで落ちるうえ、日本語を選んだ実行の出力も扱うため
- lint は **JDK 25 の javac** で走らせる。古い JDK では通ってしまう検査がある
  （`dangling-doc-comments`＝どの宣言にも付いていない javadoc は JDK 22 で入った。JDK 21 では警告が出ず、
  CI の `regression` と `vscode-plugin`（`eclipse-plugin/pom.xml` の `-Xlint:all -Werror` 経由）でだけ落ちる）。
  手元に無ければ `bash jbangw/jbang jdk install 25` で入れて `PATH` の先頭に置く:
  `export PATH=$(bash jbangw/jbang jdk home 25)/bin:$PATH`
- 出力 CSV の期待値（`expected*/`）を更新するときは、差分を確認したうえで最新の `output/*/` からコピーする。
  理由なく期待値を書き換えて通さない
- テストをスキップ・無効化して通すことはしない
- `test/profile/` は合否の検査ではなく**性能を測るための道具**（CI では動かさない）。
  測り方と結果の読み方は `docs/ast-analysis-performance-qa.md` の「計測のしかた」

## コードの決まり

- Java 17 の言語機能で書く（`--release 17` でコンパイルする。実行 JDK は 25）。警告ゼロが前提
- 文字コードは UTF-8。例外は `java-call-hierarchy-exporter.cmd` だけ MS932・CRLF（`.gitattributes` で `-text`）。
  編集するときは MS932 のまま保存する。この制約の理由は `docs/cli-app-qa.md` の Q15
- **コメント・ドキュメントは日本語**。読む相手がこのリポジトリを触る人だからである
- **利用者に見せる文言（画面・ログ・エラー）は英語が既定**で、日本語は重ねる。
  ソースに文字列を直接書かず `Messages.get("キー")` / `Messages.format("キー", 値…)` で引き、
  英語を `src/jche/util/MessagesEn.java`、日本語を `MessagesJa.java` の**同じ分野・同じ並び・同じキー**に足す。
  起動コマンド（`.sh` / `.cmd`）は `msg <キー> [値…]`。ログは利用者が次に何をすればよいか分かる書き方にする
- **出力 CSV のセルは言語に関わらず英語**（注記・`unresolvedCause` / `absentCause`・`call-conditions.csv`・
  被参照の行・プラグインの `EXPORT`）。人向けの文章ではなく、期待値との比較・Excel のフィルタ・
  他のツールへの受け渡しに使う出力のデータだからである。注記にカンマを入れない
  （セルが引用符で囲まれ、行末の grep が効かなくなる）。`docs/nls-qa.md` の Q6。
  同じ出力フォルダでも `contracts-suggested.txt` は**人が読んで選ぶ案内文**なので表示言語に合わせる
  （`docs/nls-qa.md` の Q16）
- キャッシュに焼き込まれる文字列（`Guard` の `text` のように**書き手が作る**もの）を変えるときは、
  文言の変更でもキャッシュの版を上げる。上げないと、再利用したファイルだけ古い言語の注記が出て
  同じ CSV に 2 つの言語が混ざる（`docs/nls-qa.md` の Q7）
- Eclipse プラグイン（`eclipse-plugin/src-ui`）は **Java 11** の言語機能で書く（`--release 11`）。
  下限は Eclipse 4.17（2020-09）／Java 11 で、`test/plugin-api/run.sh` がその版の jar だけで
  コンパイルして検査する（`docs/eclipse-plugin-java-floor-qa.md`）
- Eclipse プラグインの画面の文言は**置き場所が別**（`--release 11` の別コンパイル単位なので表を共有できない）。
  英語を `eclipse-plugin/src-ui/jche/eclipse/messages.properties` に、日本語を `messages_ja.properties` に足す
  （plugin.xml とバンドルの名前は `plugin*.properties`）。引き方は `jche.util.Messages` とそろえてある。
  日本語のリテラルが残っていないことは `test/plugin-nls/run.sh` が検出する
  （`docs/eclipse-plugin-nls-qa.md` / `docs/nls-qa.md` の Q4）
- VSCode プラグインも置き場所が別で、コードの文言は `vscode-plugin/src/messages.en.ts` / `messages.ja.ts` に
  `t('キー')` で引く（`vscode.l10n` は使わない。`vscode` に触らない層から引けないため）。
  `package.json` の寄与（ビュー名・コマンドの見出し・設定の説明）だけは VSCode 本体が読むので
  `"%キー%"` と書き、`package.nls.json` / `package.nls.ja.json` に足す。
  検査は `test/vscode/run.sh`（`test/messages.test.ts`）（`docs/nls-qa.md` の Q15）
- JDT の版を上げるときは `src/jche/CallHierarchyExporter.java` と `src/jche/Jche.java` の `//DEPS` 行、`pom.xml` の 3 か所を揃える
  （`test/pom/run.sh` が検出する）
- 両エントリポイントの `//SOURCES` は `*.java **/*.java`（スクリプトのあるフォルダ＝`src/jche/` からの相対）。
  `**` は区切り文字をまたぐが 0 階層は含まないため、`**/*.java` だけでは同じフォルダ直下のファイルに当たらない
  （`cannot find symbol` になる）。直下ぶんの `*.java` を必ず併記する（`docs/entrypoint-package-qa.md`）
- 出力の行順は環境に依存しない決定的な並びを保つ（`docs/deterministic-row-order-qa.md`）。ソート順を変えると期待値が全部変わる
- `call-hierarchy.csv` に固定列を足すときは `root` の左に入れる。最終列の `call-hierarchy` は可変長なので、
  後ろに足すと階層が途中で切れる。順は `caller,callee,resolved-by,level,root,call-hierarchy` で、
  `resolved-by` は注記と同じ判定から作り、`level` は「`call-hierarchy` 列のノード数」と一致させる
  （`docs/call-hierarchy-columns-qa.md`）
- キャッシュの形式や鍵を変えるときは、古いキャッシュを安全に捨てる経路を用意する（`docs/cache-dependency-jars-qa.md`）
- キャッシュは 2 ファイル（`analysis-cache.tsv` = 呼び出し階層用 / `dataflow-cache.tsv` = サイドカー用）で、
  **常に対で書き、対でしか再利用しない**。行を足すときは「呼び出し階層の出力に使うか」でどちらに置くかを決める
  （`docs/cache-split-qa.md`）。片方だけを書く・片方だけを再利用する作りにしない
- **解析器が何を読み取るかは、外から差し替えさせない。** 利用者が Java を書ける差し込み口は
  `jche.extension.TypeCandidateProvider`（読み取った材料の解釈）だけで、AST 走査中に割り込む口は置かない
  （`docs/instance-analysis-plugin-qa.md` の Q28）。ファクトリの実引数の何をキーとして読むかを増やすときは
  `jche.graph.FactoryCalls#readsOf` に足し、対になる 3 か所（契約表の読み書き `TypeContracts`、
  証拠の種別 `jche.extension.Hint`、ひな形 `ContractSuggestions`）も揃える
- 具象型からの実装探索は `jche.graph.CallGraph` の 2 つの入口だけを通す。
  呼び出し先のキーが分かるなら `implementationOf(型FQN, 呼び出し先ID)`、
  シグネチャしか分からないなら（契約表・リフレクション）`implementationOfSignature(型FQN, シグネチャ)`。
  どちらも「継承」と「型引数の置換」の 2 つの軸を 1 つの探索で見る作りなので、
  別の引き方を足すと片方を取りこぼす（`docs/jls-conformance-qa.md` の Q6・Q7・Q21）
- AST の読み取りは Java 言語仕様に合わせる。オーバーライドの判定・暗黙のコンストラクタ呼び出し・
  定数の畳み込みは、自前で近似せず JDT のバインディング（`IMethodBinding.overrides` など）に任せ、
  分からないものは「判定しない」に倒す（`docs/jls-conformance-qa.md`、
  `docs/static-analysis-limits.md` の 7 節）
- 解決の結果はエッジの処理順に依存させない。`CallResolver.resolve` はメモ化されるので、最初の評価と後の評価で答えが変わる
  作りにすると出力が食い違う（`docs/code-review-fixes-qa.md` の Q2）
- 相対パスの起点は項目ごとに決まっている（`config/config.properties` 冒頭のコメント）。起点の外へ出る相対パスはエラーにする

## ドキュメントの決まり

- 機能を足したり設計判断をしたときは `docs/<機能>-qa.md` に「迷ったこと・結論・却下した案」を Q&A で残す。
  既存ファイルの書き出しに倣う（Issue へのリンク → 対応の要点 → Q&A）。`docs/README.md` の索引にも 1 行足す
- 利用者向けの長い説明（GitHub Actions の入力一覧など）は README ではなく `docs/<機能>.md` に置き、README からは要約とリンクだけにする
- `docs/` のファイル名に `license`、`licence`、`copyright`、`copying`、`patents` を使わない。
  GitHub がルート・`.github/`・`docs/` のこれらの名前をライセンスファイルとみなし、README 横の License 欄に並べてしまう
- README は「何のためのツールか」「Quick start」「出力 CSV の読み方」に絞る。それ以外の利用者向けの説明は `docs/<機能>.md` に置き、README からは 1 行の要約とリンクだけにする
- README は日本語が先、その下に同じ内容の英語（`# English` 以降）を置く**対訳**である。
  片方だけ直すと黙って食い違うので、**必ず両方を直す**。見出しは英語側でも重複しない語にする
  （GitHub のアンカーに `-1` が付いて、リンクが並べ替えで静かに壊れるのを避けるため）。
  `docs/` は日本語のままで、英語にするのは README だけ（`docs/nls-qa.md` の Q12）
- 起動コマンドの名前を参照する箇所は多い（src、docs、test、workflows、`.gitattributes`、`.gitignore`）。
  改名したら `grep -rn` で旧名が残っていないことを確認する

## Git

- 作業ブランチで開発し、PR でマージする。`main` に直接 push しない
- コミットメッセージは日本語で、何を・なぜ変えたかを書く
- `launcher.properties`、`.jbang/`、`.cache/`、`config/<日時>_<プロジェクト名>/` は環境ごとの生成物なのでコミットしない（`.gitignore` 済み）
