# 開発に参加する

このリポジトリを初めて触る人（と AI コーディングエージェント）向けの案内。
利用者向けの説明は [README.md](README.md)、設定項目は [config/jche.properties](config/jche.properties) のコメントにある。
内部の作りは [docs/architecture.md](docs/architecture.md)、用語は [docs/glossary.md](docs/glossary.md) にある。

## このリポジトリは何か

Java プロジェクト全体のメソッド呼び出し階層を、Eclipse JDT のコンパイラ（`org.eclipse.jdt.core`）で解析して
CSV（`call-hierarchy.csv` / `methods.csv`）に書き出すツール。Eclipse は起動せず、JBang で単体で動く。
目的は「改修時の影響調査で呼び出しを漏らさない」こと。迷ったら **呼び出しを静かに落とさない（安全側に倒す）** を優先する。

本体は **解決（`analysis`。JLS の判定を JDT に任せて事実を書く）→ キャッシュ（`cache`）→ 選択（`graph`。JVMS 5.4.6 の順で
実際に動く本体を選ぶ）** の 3 層で、各パッケージの `package-info.java` が層の責務と読む順を持つ。規則ごとの対応表と
健全性の点検表は `docs/resolution-selection-design.md`。

## 1. 5 分で回す

```bash
# 1. ツールを動かす JDK 25 を JBang で入れ、PATH の先頭に置く（初回は数百 MB を取得する）
bash jbangw/jbang jdk install 25
export PATH=$(bash jbangw/jbang jdk home 25)/bin:$PATH

# 2. 全ソースを CI と同じ引数で lint する（警告ゼロが前提）
CP=$(bash jbangw/jbang info classpath src/jche/CallHierarchyExporter.java | tr ':' '\n' | grep -v '/cache/jars/' | paste -sd:)
javac --release 17 -Xlint:all -Werror -Xdoclint:all,-missing -cp "$CP" -d build/classes -encoding UTF-8 $(find src -name '*.java')

# 3. 回帰テスト（test/demo などを解析して期待値の CSV と比べる）
bash test/regression/run.sh

# 4. 動かしてみる（対話モード / 対話なし）
./java-call-hierarchy-exporter.sh
./java-call-hierarchy-exporter.sh config/jche.properties
```

起動コマンドはネットワークからの取得（JBang / JDK / 依存 jar）の前に確認を出す（`n` か端末なしなら取得せず終了コード 3。
無人なら `JCHE_ALLOW_DOWNLOAD=yes`。`docs/network-download-confirm-qa.md`）。jbang を直接使えば確認なしで取得する。
設定ファイルは `project.root` だけ書けば動き、`source.folders` / `library.folders` / `source.encoding` は空欄なら
`project.root` の中身から決める（`src/jche/config/ProjectDetector.java`）。

## 2. Java の標準的な作法と違うところ（理由つき）

初見で「なぜこうなっているのか」と思う点。どれも理由があって選んでいるので、そのまま従う。

| こうなっている | 理由 |
|---|---|
| ビルドは JBang（`src/jche/CallHierarchyExporter.java` の `//DEPS` / `//JAVA` / `//SOURCES` 行）。Maven / Gradle でビルドしない | 利用者が JDK も JBang も入れずに起動コマンド 1 つで動かせるようにするため。`pom.xml` は Eclipse（m2e）でこのリポジトリを開いて依存をビルドパスに載せるためだけにある（`docs/eclipse-maven-qa.md`）。`mvn test` は何もしない |
| ソースは `src/jche` 直下（`src/main/java` ではない） | JBang の `//SOURCES` がスクリプトのあるフォルダからの相対で、`jche.config.ToolRoot` と 1 ファイル版の生成器もこの置き場所を目印にしている（`docs/entrypoint-package-qa.md`） |
| テストは JUnit ではなく bash の `run.sh` と自前の `*Check.java` | 主な検査が「題材プロジェクトを解析して、期待値の CSV や全件解析の結果と比べる」形で、JUnit にしても速くも読みやすくもならない。依存ゼロ・実行 JDK 固定（JDK 25。JDT が実行 JVM のブートクラスパスを解析に使うので、版が違うと結果が変わる）の利点も保てる。一覧は [test/README.md](test/README.md) |
| 実行 JDK は 25 に固定、言語機能は Java 17（`--release 17`） | 上と同じ理由で実行 JDK を固定する。言語レベルは古い環境（Pleiades 同梱の JDK 17）でもコンパイルできる下限 |
| `jche.config.Config` は 47 個の `public final` フィールドで getter が無い | 設定ファイルを読んだ結果を持つ読み取り専用の不変の値オブジェクトで、読む側が包み直す値は無いので getter を置いていない。項目の意味の正本は `config/jche.properties` のコメントで、`Config` には複製しない |
| コメント・文書は日本語、利用者に見せる文言は英語が既定 | 読む相手（このリポジトリを触る人）と、使う相手（画面・ログ・CSV を読む人）が違う。文言の置き場所は下の「コードの決まり」 |
| `java-call-hierarchy-exporter.cmd` だけ MS932・CRLF | cmd が画面のコードページで読むため（`docs/cli-app-qa.md` の Q15）。編集するときも MS932 のまま保存する |
| `single-file/CallHierarchyExporterSingle.java`（4 万行）がコミットされている | 本体の全ソースから生成した 1 ファイル版で、jar を集めて `javac` 1 回で動かす環境のためにある。手で編集せず、`src/jche` を直したら `bash single-file/generate.sh` で生成し直す（`docs/single-file-qa.md`） |
| パッケージ名が `jche`（逆 DNS でない） | JBang の単体ツールとして短い名前を選んだ。利用者の拡張が import する `jche.extension` の互換のため変えない |

## 3. どこに何があるか

| 場所 | 役割 |
|---|---|
| `src/jche/Jche.java` | 起動コマンドのエントリポイント。引数があれば対話なしで解析し、無ければ対話モードに入る |
| `src/jche/CallHierarchyExporter.java` | 解析のエントリポイント（`//DEPS` と `//JAVA` の JBang ヘッダを持つ）。設定ファイルごとに出力フォルダを作り CSV を書く |
| `src/jche/Exporter.java` | フェーズ1（解析・キャッシュ）とフェーズ2（グラフ・解決）。CLI と Eclipse / VSCode の解析サーバーが共有する |
| `src/jche/<パッケージ>/` | 本体。パッケージの責務と主要クラスは [docs/architecture.md](docs/architecture.md)。`analysis` / `cache` / `graph` は `package-info.java` に「決まり」と「読む順」がある |
| `src/jche/util/Messages*.java` | 利用者に見せる文言。英語が既定で、日本語（`MessagesJa`）を重ねる。CSV のセルはここを通さず英語で固定（`docs/nls-qa.md`） |
| `java-call-hierarchy-exporter.sh` / `.cmd` | リポジトリ直下の起動コマンド。引数なしで対話モード、設定ファイルを渡すと何も尋ねずに解析だけ行う（`docs/cli-noninteractive-qa.md`）。ネットワークからの取得（JBang / JDK / 依存 jar）だけは必ず確認する（`docs/network-download-confirm-qa.md`） |
| `jbangw/` | JBang 本家のラッパースクリプトをそのまま同梱（MIT）。JBang のインストール不要 |
| `single-file/` | 本体の全ソースを 1 ファイル `CallHierarchyExporterSingle.java`（パッケージ `jche`。各型を外側のクラスの入れ子にした完全版）にしたもの。利用者の拡張が import する `jche.extension` だけは本物のパッケージのまま `jche/extension/` に写す。**手で編集せず**、`bash single-file/generate.sh`（生成器 `generator/MergeSources.java`）で `src/jche` から生成する（`docs/single-file-qa.md`） |
| `config/` | 設定ファイル置き場。`jche.properties` がひな形兼既定（以前の名前 `config.properties` も読む）。読み方は `src/jche/config/ConfigFile.java`（`Properties#load` ではない。バックスラッシュはそのまま、値の続きは行末の `\`（次の行が `項目=` なら捨てる）か字下げ。`docs/config-file-format-qa.md`） |
| `action.yml` / `.github/action/` | 同じ解析を CI で動かす複合アクション |
| `eclipse-plugin/` | Eclipse プラグイン。解析は別プロセス（`--server`）に任せ、画面だけを持つ（`docs/out-of-process-analysis-design.md`）。画面の文言は英語が既定で、日本語は `messages_ja.properties` に置く（`docs/eclipse-plugin-nls-qa.md`） |
| `vscode-plugin/` | VSCode プラグイン（TypeScript、esbuild で 1 ファイルに束ねる）。同じ `--server` を子プロセスとして使う。`src/server/` と `src/config.ts` は `vscode` に触らない層で、Node だけで検査できる（`docs/vscode-plugin-design.md`）。画面の文言は英語が既定で、日本語は `src/messages.ja.ts`。`package.json` の寄与は `package.nls*.json`（`docs/nls-qa.md` の Q15） |
| `test/` | 検査（`run.sh`）・題材プロジェクト・計測の道具。一覧と分類は [test/README.md](test/README.md) |
| `docs/README.md` | `docs/` の索引。最初に読む順、使い方の詳細、設計の説明、設計の記録（Q&A）、再実装用の仕様に分かれる |
| `docs/*-qa.md` | 機能ごとの「実装時に迷ったこと・困ったことと結論」を Q&A 形式で残した記録。**経緯の記録であって現状の仕様の正本ではない** |
| `docs/prompt-*.md` / `docs/feature-difficulty.md` | このツールを別環境で再実装するための仕様プロンプトと難易度表 |

「利用者拡張」と「IDE のプラグイン」は別物で、どちらも `plugin` の語が付いていることに注意する。
設定の `plugin.folders`・`jche.config.Plugins`・`test/plugin-demo`・`test/regression/plugin` は利用者が Java で書く拡張
（`jche.extension.TypeCandidateProvider`）のもの、`eclipse-plugin/`・`test/plugin`・`test/plugin-*` は Eclipse プラグインのもの。

## 4. 変更の種類ごとのチェックリスト

| 触ったもの | 忘れずにすること |
|---|---|
| `src/jche` のどこでも | `bash single-file/generate.sh` で 1 ファイル版を生成し直してコミットする（忘れは `test/single-file/run.sh` が捕まえる）。lint（JDK 25）・`test/regression` |
| 書き手（`analysis` / `cache`）でキャッシュに入る事実が変わりうる | `CacheFormat.VERSION` を上げる（迷ったら上げる）→ `bash test/cacheversion/run.sh --update` で `facts.txt` を更新。`test/incremental` を回す |
| 差分更新が見る依存を足した | `test/incremental` に全件解析との一致の検査を足す |
| 利用者に見せる文言を足した | `MessagesEn.java` と `MessagesJa.java` の同じ分野・同じ並び・同じキーに足す（`test/nls`）。起動コマンドは `msg <キー>`。Eclipse / VSCode プラグインは置き場所が別（下記） |
| 実装を探す順（親クラスの連鎖 → 最も特定的な親インターフェース）を変えた | 3 か所を同時に直す: `MethodSelection#search`・`ImplicitCalls#findNoArgMethod`・`ExternalUsageScanner#inheritedFrom`。正本は `docs/resolution-selection-design.md` の 4 節 |
| JDT の版を上げた | `CallHierarchyExporter.java` と `Jche.java` の `//DEPS`、`pom.xml` の 3 か所（`test/pom`）。`bash test/cacheversion/run.sh --update` で記録だけ合わせる |
| 機能を足した・設計判断をした | `docs/<機能>-qa.md` に Q&A を残し、`docs/README.md` の索引に 1 行足す |
| README を直した | 日本語と英語（`# English` 以降）の両方を直す |
| 起動コマンドを改名した | `grep -rn` で旧名が残っていないことを確認する（src、docs、test、workflows、`.gitattributes`、`.gitignore`） |

## 5. テスト（変更したら必ず通す）

CI（`.github/workflows/smoke.yml`）と同じものを手元で実行できる。すべて bash から。
**一覧・分類・各検査が何を守っているかは [test/README.md](test/README.md)。** ここでは決まりだけ。

- lint は **JDK 25 の javac** で `javac --release 17 -Xlint:all -Werror -Xdoclint:all,-missing`（smoke.yml の「Compile with all lint warnings as errors」と同じ引数）。
  古い JDK では通ってしまう検査がある（`dangling-doc-comments` は JDK 22 で入った）
- ツールを動かす検査スクリプトは `JCHE_LANG=en` を輸出して言語を固定する。日本語への切り替えそのものは `test/nls/run.sh` が見る
- テストのシェルは UTF-8 ロケールで動かす（`LANG=C.UTF-8`）
- 出力 CSV の期待値（`expected*/`）を更新するときは、差分を確認したうえで最新の `output/*/` からコピーする。
  理由なく期待値を書き換えて通さない
- テストをスキップ・無効化して通すことはしない
- `test/profile/` は合否の検査ではなく**性能を測るための道具**（CI では動かさない）。
  測り方と結果の読み方は `docs/ast-analysis-performance-qa.md` の「計測のしかた」
  （解析結果が持ち続けるヒープは `RetainedHeap.java`。`docs/cache-unification-qa.md` の Q17）

## 6. コードの決まり

- Java 17 の言語機能で書く（`--release 17` でコンパイルする。実行 JDK は 25）。警告ゼロが前提
- 文字コードは UTF-8。例外は `java-call-hierarchy-exporter.cmd` だけ MS932・CRLF（`.gitattributes` で `-text`）。
  編集するときは MS932 のまま保存する。この制約の理由は `docs/cli-app-qa.md` の Q15
- **コメント・ドキュメントは日本語**。読む相手がこのリポジトリを触る人だからである
- コメントに `docs/…-qa.md の Q番号` や `Issue #番号` を書くときは、参照だけで済ませず**結論を 1 文で自己完結して書き、参照は「詳しい経緯」の補助にする**。
  Q 番号は見出しの語を添える（`docs/cache-unification-qa.md の Q56（同じフォルダの錠）`）。既存の参照は触ったときに直す
- **利用者に見せる文言（画面・ログ・エラー）は英語が既定**で、日本語は重ねる。
  ソースに文字列を直接書かず `Messages.get("キー")` / `Messages.format("キー", 値…)` で引き、
  英語を `src/jche/util/MessagesEn.java`、日本語を `MessagesJa.java` の**同じ分野・同じ並び・同じキー**に足す。
  起動コマンド（`.sh` / `.cmd`）は `msg <キー> [値…]`。ログは利用者が次に何をすればよいか分かる書き方にする
- **利用者が対処すべきことは `Log.warn`（失敗は `Log.error`）で出す。** それが 1 行でも出た実行では、
  出力フォルダに `warnings.txt`（確認してほしいことの案内）ができ、その行が載る。逆に、対処の要らない
  経過を `Log.warn` で出さない（ファイルが出る＝想定と違う状態、という約束が崩れる）。
  初心者がつまずく典型（設定の指定先の欠け・依存 jar の不足・コンパイルエラー・打ち切り・失敗）に当たる警告は
  `jche.util.Warnings.warn(Topic, …)` で出し、対処の説明の付いた項目に載せる（`docs/output-files-simplify-qa.md` の Q3〜）
- **出力 CSV のセルは言語に関わらず英語**（注記・`unresolvedCause` / `absentCause`・`call-conditions.csv`・
  被参照の行・プラグインの `EXPORT`）。人向けの文章ではなく、期待値との比較・Excel のフィルタ・
  他のツールへの受け渡しに使う出力のデータだからである。注記にカンマを入れない
  （セルが引用符で囲まれ、行末の grep が効かなくなる）。`docs/nls-qa.md` の Q6。
  同じ出力フォルダでも `contracts-suggested.txt` は**人が読んで選ぶ案内文**なので表示言語に合わせる
  （`docs/nls-qa.md` の Q16）
- **キャッシュの形式の版（`CacheFormat.VERSION`）は迷ったら上げる。上げ忘れは `test/cacheversion/run.sh` が捕まえる。**
  書き手（`src/jche/analysis`・`src/jche/cache`）の変更でキャッシュに入る事実が変わりうるなら、列や意味の変更で
  なくても上げる（収集範囲・値の正規化・`Guard` の `text` のように**書き手が作る文字列**の文言も含む）。
  上げ忘れると、再利用したファイルだけが古い事実のまま残り、壊れ方が「エラー」ではなく「静かに違う結果」になる
  （`docs/cache-split-qa.md` の Q22、`docs/nls-qa.md` の Q7）。上げると利用者は 1 回だけ全件解析になるが、
  それは安全側の費用として受け入れる。上げたら `bash test/cacheversion/run.sh --update` で記録（`facts.txt`）を更新する。
  読み手だけの変更（解決の方針・CSV の列・フィルタ・注記の文言）では事実が変わらないので上げなくてよい
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
  （`test/pom/run.sh` が検出する）。JDT の版と実行 JDK のメジャー版はキャッシュの鍵（ヘッダ行の `jdt=` / `jdk=`）に
  入っていて、変われば古いキャッシュは自動で捨てられるので、形式の版は上げなくてよい
  （`bash test/cacheversion/run.sh --update` で記録だけ合わせる）
- **`src/jche` を直したら `bash single-file/generate.sh` で 1 ファイル版を生成し直してコミットする。** `single-file/CallHierarchyExporterSingle.java` は
  生成物で、手で編集しない（生成し直すと消える）。生成し直し忘れは `test/single-file/run.sh` が検出する（`docs/single-file-qa.md`）
- 両エントリポイントの `//SOURCES` は `*.java **/*.java`（スクリプトのあるフォルダ＝`src/jche/` からの相対）。
  `**` は区切り文字をまたぐが 0 階層は含まないため、`**/*.java` だけでは同じフォルダ直下のファイルに当たらない
  （`cannot find symbol` になる）。直下ぶんの `*.java` を必ず併記する（`docs/entrypoint-package-qa.md`）
- 出力の行順は環境に依存しない決定的な並びを保つ（`docs/deterministic-row-order-qa.md`）。ソート順を変えると期待値が全部変わる
- `call-hierarchy.csv` に固定列を足すときは `root` の左に入れる。最終列の `call-hierarchy` は可変長なので、
  後ろに足すと階層が途中で切れる。順は `caller,callee,resolved-by,level,root,call-hierarchy` で、
  `resolved-by` は注記と同じ判定から作り、`level` は「`call-hierarchy` 列のノード数」と一致させる
  （`docs/call-hierarchy-columns-qa.md`）
- キャッシュの形式や鍵を変えるときは、古いキャッシュを安全に捨てる経路を用意する（`docs/cache-dependency-jars-qa.md`）
- **キャッシュは 1 系統（1 ファイル `analysis-cache.tsv`）で、ファイルを分けない。** ソースファイル 1 つにつき
  1 ブロックで、ブロックの中に構造（型・宣言・呼び出し）と値（値グラフ・戻り値・代入・定数）の両方を持つ
  （`jche.cache.CacheFormat`）。以前は「呼び出し階層の出力に使うか」で 2 ファイルに分けていたが、値も具象クラスの
  解決と打ち切りを通じて出力に効くので基準として成り立たず、対の整合（世代の印・ブロックの突き合わせ・表示名での
  結びつけ）の仕組みだけが増えた（`docs/cache-unification-qa.md`、経緯は `docs/cache-split-qa.md`）。
  値を読まない指定（`dataflow.enabled=false`）は「値の行・列を読まない」で表し、ファイルは分けない。
  ブロックの中の行の並び・記号表（S 行）の番号の振り方・F 行の検査値（crc）は読み手が依存しているので、
  行を足すときは `CacheFormat` の並びに合わせて書き手と読み手の両方を直す。
  メソッドを指す列は S 行の番号で、目で追うときは `jche.cache.CacheDump` で 4 列に戻した形を見る。
  値（戻り値・代入・条件・呼び出し箇所の値）はどれも値グラフ（N 行）のノードを番号で指し、読み手は値の表
  （`jche.graph.ValueStore`）を番号で引く。出所の文字列（`Origin` の文法）に組み直して読む形に戻さない
  （値が `| ; { }` を含むと読み違える。`docs/cache-unification-qa.md` の Q11）
- 同じキャッシュのフォルダを使う実行は、フォルダの錠（`jche.cache.CacheLock`。`analysis-cache.tsv.lock`）で 1 つずつにしてある。
  錠は `jche.Exporter` がフェーズ1 からフェーズ2 のグラフの構築まで持つ。キャッシュを読む・書く処理を足すときは、
  この錠の内側に置く（外で読むと、書きかけのキャッシュを読みうる。`docs/cache-unification-qa.md` の Q56）
- キャッシュを読み直さないための索引や中間データは、ヒープではなくキャッシュのフォルダの一時ファイルに置き
  （利用者の優先順位はヒープが先）、`jche.cache.TempFiles` で作る（一意の名前・終了フック・次の実行の掃除。
  GitHub Actions はフォルダを丸ごと保存するので残さない。`docs/cache-unification-qa.md` の Q32・Q36）
- **解析器が何を読み取るかは、外から差し替えさせない。** 利用者が Java を書ける差し込み口は
  `jche.extension.TypeCandidateProvider`（読み取った材料の解釈）だけで、AST 走査中に割り込む口は置かない
  （`docs/instance-analysis-plugin-qa.md` の Q28）。ファクトリの実引数の何をキーとして読むかを増やすときは
  `jche.graph.FactoryCalls#readsOf` に足し、対になる 3 か所（契約表の読み書き `TypeContracts`、
  証拠の種別 `jche.extension.Hint`、ひな形 `ContractSuggestions`）も揃える
- 具象型からの実装探索（選択。JVMS 5.4.6）は `jche.graph.MethodSelection`（`graph.selection()`）の 2 つの入口だけを通す。
  呼び出し先のキーが分かるなら `implementationOf(型FQN, 呼び出し先ID)`、
  シグネチャしか分からないなら（契約表・リフレクション）`implementationOfSignature(型FQN, シグネチャ)`。
  jar からの被参照（`ExternalUsageScanner`）が出す「JVM の解決（JVMS 5.4.3.3）が結び付ける宣言」は、選択ではなく解決なので
  `resolvedDeclaration(型FQN, シグネチャ)`（同じクラスの 3 つ目の入口。ブリッジの形＝ O 行・H 行の 8 列目も見る）を通す。
  どちらも「継承」と「型引数の置換」の 2 つの軸を 1 つの探索で見る作りなので、
  別の引き方を足すと片方を取りこぼす（`docs/jls-conformance-qa.md` の Q6・Q7・Q21）。
  CHA の候補を数え始める型は、呼び出し先を宣言した型ではなく呼び出しを修飾する型（JLS 13.1。C 行の
  qualifier。`CallResolver#usableQualifier`）で、jar の型なら宣言した型に倒す（`docs/jls-conformance-test-qa.md` の Q18）。
  実装を探す順は JVM と同じ（JLS 8.4.8・9.4.1、JVMS 5.4.6）: 親クラスの連鎖を根まで先に見て（その型より上の private は飛ばす）、
  無ければ親インターフェースの宣言（private・static を除く）のうち最も特定的なもの。クラスのメソッドは `default` に常に勝つ。
  親型の一覧（`TypeHierarchy#directSupertypes`）は名前順で親クラスとインターフェースを区別しないので、その順に辿って実装を
  探さない（`TypeHierarchy#classChain`・`superinterfaces` を使う。親クラスの連鎖は H 行の 7 列目、親クラスから継承した
  メソッドによるインターフェースの実装は 8 列目。`docs/jls-conformance-qa.md`）
- **実装を探す順の写しは 3 か所にあり、順を変えるときは同時に直す。** 正本は `docs/resolution-selection-design.md` の 4 節で、
  `jche.graph.MethodSelection#search`（選択）のほかに、`jche.analysis.ImplicitCalls#findNoArgMethod`（拡張 for の `iterator()`・
  try-with-resources の `close()`。JDT のバインディングを材料にする解決の層なので `MethodSelection` に寄せられない）と
  `jche.external.ExternalUsageScanner#inheritedFrom`（jar からの被参照）が同じ順を持つ。3 つのクラス javadoc が互いを指す
  （`docs/resolution-selection-qa.md` の Q10。Issue #189）
- AST の読み取りは Java 言語仕様に合わせる。オーバーライドの判定・暗黙のコンストラクタ呼び出し・
  定数の畳み込みは、自前で近似せず JDT のバインディング（`IMethodBinding.overrides` など）に任せ、
  分からないものは「判定しない」に倒す（`docs/jls-conformance-qa.md`、
  `docs/static-analysis-limits.md` の 7 節）
- **事実は JDT に一緒に渡すファイルの組み方（バッチ）に依存させない。** 全件解析（100 件ずつ）と差分更新（変わった
  ファイルだけ）とで一緒に渡すファイルが違うので、依ると差分更新が全件解析と静かに食い違う。事実は、解析するファイルを
  JDT がすべて解決し終えてから（最後の `acceptAST` の中で）集め、受け取ったその場でバインディングに問い合わせない（後ろの
  ファイルの型を先に解決させる）。バッチによって見えたり見えなかったりする型のファイル（名前の違うファイルで宣言した
  型・jar が参照するソースの入れ子の型）は事実を集めない「添えるファイル」として後ろに渡し、`module-info.java` は別の
  バッチにする。JDT が途中で止まったら、残りを 1 ファイルずつ自前で読み直さず、関わるファイルを添える・半分に分ける・
  脇に置いて 1 つだけで解析する（どれも同じ `createASTs`）。入口は `jche.analysis.CallEdgeExtractor#analyzeBatch`
  （`docs/cache-design.md` の「JDT に一緒に渡すファイル（バッチ）」、`docs/cache-unification-qa.md` の「v42 の穴探し（形式 v43）」）
- ソースの一覧・依存 jar とクラスフォルダの指紋は、JDT と同じ読み方で作る（シンボリックリンクをたどる `jche.util.FileTree`、
  クラスフォルダの `.java`、jar の目次はファイルのバイトから読む `ZipDirectory`）。ずれると差分更新が変化を見落とす
- 解決の結果はエッジの処理順に依存させない。`CallResolver.resolve` はメモ化されるので、最初の評価と後の評価で答えが変わる
  作りにすると出力が食い違う（`docs/code-review-fixes-qa.md` の Q2）
- 相対パスの起点は項目ごとに決まっている（`config/jche.properties` 冒頭のコメント）。起点の外へ出る相対パスはエラーにする

## 7. ドキュメントの決まり

- 機能を足したり設計判断をしたときは `docs/<機能>-qa.md` に「迷ったこと・結論・却下した案」を Q&A で残す。
  既存ファイルの書き出しに倣う（Issue へのリンク → 対応の要点 → Q&A）。`docs/README.md` の索引にも 1 行足す
- `docs/*-qa.md` は経緯の記録で、現状の仕様の正本ではない。仕様は「使い方の詳細」「設計の説明」の文書とコード（Javadoc）に置く。
  Q&A の結論が今の仕様になったら、仕様側の文書にも書く
- 内部の作りの全体図は `docs/architecture.md`、用語は `docs/glossary.md` に置く。パッケージを足した・クラスの役割を変えたら
  `architecture.md` の表と、そのパッケージの `package-info.java` を直す
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

## 8. Git

- 作業ブランチで開発し、PR でマージする。`main` に直接 push しない
- コミットメッセージは日本語で、何を・なぜ変えたかを書く
- `launcher.properties`、`.jbang/`、`.cache/`、`config/<日時>_<プロジェクト名>/` は環境ごとの生成物なのでコミットしない（`.gitignore` 済み）
