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
| [cache-design.md](cache-design.md) | 解析結果キャッシュの設計方針。1 ファイルの形（ブロック・記号表・値グラフ・条件の表・ブロックの検査値）、事実だけを持つこと、差分更新、何も変わらないときに書き直さないこと、読む回数と一時ファイル、依存 jar と JDK / JDT の変更への追従 |
| [out-of-process-analysis-design.md](out-of-process-analysis-design.md) | Eclipse プラグインが解析を別プロセス（別 JDK・同梱の JDT）で行う仕組み。プロトコルと配布物の構成 |
| [eclipse-plugin-ui-design.md](eclipse-plugin-ui-design.md) | 呼び出し元階層ビューの画面設計（状態の見せ方・フィルタ・操作）。いまの画面との差は冒頭の追補を見る |
| [eclipse-plugin-features.md](eclipse-plugin-features.md) | Eclipse プラグインの機能一覧（入口・実装・値打ち・費用）。何を残し何をやめるかを決めるための棚卸し |
| [vscode-plugin-design.md](vscode-plugin-design.md) | VSCode プラグインの設計案（未実装）。既存のサーバープロトコルの再利用、設定の自動生成、カーソル位置からメソッドを引く `AT` の追加 |
| [eclipse-pleiades-versions.md](eclipse-pleiades-versions.md) | Eclipse / JDT Core / Java / Pleiades の版の対応表と、プラグインの動作条件 |
| [branch-pruning.md](branch-pruning.md) | 条件分岐による打ち切り（`branch.pruning.enabled`）。判定できる条件、打ち切りで階層から消えたメソッドを `methods.csv` で探す方法 |
| [contracts-unification-design.md](contracts-unification-design.md) | 解決条件の指定を契約表に一本化する設計（実装済み）。具象クラスの対応を契約表の 1 行で書く「種類 C」、証拠をキャッシュの値グラフから引けること、旧来の `resolver.*` / `plugin.*` との互換 |

## 設計の記録（Q&A）

| ファイル | Issue | 内容 |
|---|---|---|
| [build-tool-classpath-qa.md](build-tool-classpath-qa.md) | #44 | `pom.xml` / `build.gradle` を読んでローカルリポジトリから依存 jar を集める |
| [cache-dependency-jars-qa.md](cache-dependency-jars-qa.md) | #36 | 依存 jar を変えたときのキャッシュの差分更新。実行 JDK が変わると何が変わるか（Q20） |
| [deterministic-row-order-qa.md](deterministic-row-order-qa.md) | #43 | 出力の行順を環境に依存しない並びに固定する。同じ行に並ぶ宣言の前後を ID でなく宣言の順番で決める（Q14〜） |
| [spring-di-qa.md](spring-di-qa.md) | #66 | Spring の Bean 定義（`@Autowired` / `@Qualifier` / `@Bean`）で候補を絞る。絞るのはレシーバが注入点（注入の注釈の付いたフィールド・メソッドの引数、Bean のコンストラクタの引数と static でないフィールド）のときだけで、経路で具象型が分かればそちらを採る（PR #167 の見直しで改めた）。ソースが引数でない値を入れるフィールド（初期化子・`new` の代入）は注入点にしない（4 回目のレビュー）。別の型（子クラス・内部クラス・別のファイルの型）から private でないフィールドへの書き込みも数え、値を読まない指定でも型で当たりを付けて外す（5 回目のレビュー） |
| [doma-generated-impl-qa.md](doma-generated-impl-qa.md) | #73 | 実装がコンパイル時に生成される型（Doma の `@Dao`）を「実装なし」と言い分ける |
| [instance-analysis-plugin-qa.md](instance-analysis-plugin-qa.md) | #68 | インスタンス解析条件を外から与えるプラグイン（実行時コンパイル）。引かれなかった対応表の行の知らせ方、ファクトリのキーをツールが渡すようにした判断、証拠採取の拡張を廃止して読み口をツール内に一本化した判断（Q28）、`Class` リテラルのキー（Q29） |
| [fatjar-external-usage-qa.md](fatjar-external-usage-qa.md) | #35 | 被参照スキャンの FatJar / war / ear 対応 |
| [external-usage-callsite-qa.md](external-usage-callsite-qa.md) | — | 被参照スキャンで呼び出し元メソッドと行番号まで出す（class の命令列を自前で読む） |
| [cache-split-qa.md](cache-split-qa.md) | — | （経緯の記録。今の形は cache-unification-qa.md）キャッシュを呼び出し階層用とデータフロー用の 2 つに分け、対でしか再利用しないようにした。値グラフ（N 行）と値の符号化の規則を入れた |
| [cache-unification-qa.md](cache-unification-qa.md) | — | キャッシュを 1 ファイルにまとめる（分けた基準が成り立たなかった理由、ブロックの行の並び、ブロック内の記号表、ブロックごとの検査値、1 つの符号化の規則、`\n` の行末）。JDT の版を鍵に入れる・版は「迷ったら上げる」と上げ忘れの検査。読む回数を減らす（一時ファイルの索引とエッジの記録、バイトの範囲での書き写し、何も変わらなければ書き直さない、一時ファイルの後始末、ヒープを優先して却下・見送りにした案）。値をすべて値グラフのノードで持つ（R・J 行はノード番号、条件は G 行の表、new の証拠は C・U 行の hints 列、R 行を宣言の戻り値の型で書く、上書きされうるメソッドの戻り値は使わない）。読み手が値の表（`ValueStore`・`GuardTable`・`StringPool`、経路の値は `Slot`）を読む（`\| ; { }` を含む値の読み違い、切り替えの確かめ方、ヒープの計測、一時ファイルへの写像を見送った理由）。前後の数字と残っている限界。差分更新が I 行で見落としていた依存（形式 v34。無かった型の追加とエラーの名前での絞り込み、同じパッケージの型による import の隠蔽、型の形の指紋による親型の連鎖、ラムダの目標の型、宣言にだけ書いた型、式の型にだけ現れる型）、warnings.txt のエラーのファイルの選び方、対になっていないサロゲートの符号化。3 回目のレビュー（52453ae の見直し。形式 v36）の差分更新の依存（型の形に入れる私的メンバーを親のメンバーと名前の当たるものに絞った検証、可変長引数・throws・拡張 for・switch・throw・アノテーション・sealed の依存、見えなかった型を public にしたとき、jar が同じパッケージに足した型）と、キャッシュの健全性と一括解析の頑健さ（同じキャッシュのフォルダを使う実行の錠、書き終えていないキャッシュからグラフを組まない、解析のあいだに書き換えられたソース、ソースフォルダの並びの鍵、F 行と先頭の行の検査値、依存 jar のある版の検査の題材、同じクラスが 2 つのソースフォルダにあるときのバッチ、スタックの溢れ、網羅していない switch 式）。解決できなかった名前にファイルのパスを拾わない（形式 v37）。最後のレビュー（形式 v38）: 私的メンバーの名前の当たりを `java.*` の親型の上まで見る、`package-info.java` も同じ名前の組にする、ソースフォルダを足した・外しただけならキャッシュを使い続ける、名前の違う 2 つのファイルが同じ型を宣言するときの限界と警告の文言、受け手の中のスタックの溢れ。4 回目のレビュー（形式 v39）: 同じ名前のファイルの組の片方を消したら残ったほうを解析し直す、同じ型の宣言の重なりを型の宣言でも見つけて警告する（直し方の文言も）、JDT が受け付けない設定では旧キャッシュを使わない、版の検査の題材に `super.f` の書き込みと別のインスタンスの読み取りを足す。5 回目のレビュー（形式 v40）: パスの鍵を要素ごとにつなぎ、名前に `\` を含むフォルダを入れ子のフォルダと取り違えない（中断からの引き継ぎも）。型の形をやめる（形式 v41）: 変わった型の部分型をすべて変わった型にする（H 行から作る部分型の索引）、依存を「すべての式と型の節の型」の 1 つの決まりにする、依存 jar が無いときの事実をバッチの組み方に依らせない、ソースフォルダを足す・外すときの確かめ、費用の計測と却下した案。型の形をやめたあとのレビュー（形式 v42）: 自分の宣言の指紋（中身の変わっていないファイルの宣言が変わったら連鎖）、jar の変化で解析し直したファイルの型を変わった型にする、型引数と型引数を付けたままの呼び出しの候補を数える、jar の型の親型を数える、パッケージと同じ名前の型、費用と残る限界 |
| [branch-pruning-qa.md](branch-pruning-qa.md) | #67 | 変数値と条件分岐の静的解析で、その経路では呼ばれない呼び出しを区別する |
| [call-conditions-qa.md](call-conditions-qa.md) | #67 | 呼び出しに効いている条件を `call-conditions.csv` に出す（モードにせず出力を1つ足す判断、キャッシュに載せない判断） |
| [dataflow-facts-qa.md](dataflow-facts-qa.md) | #80 | データフローの事実（ファクトリの戻り値）をフェーズ2bで一括確定し、`CallResolver.resolve` を処理順に依存しない純粋な関数にする |
| [callback-contracts-qa.md](callback-contracts-qa.md) | #136 | ソースの外との契約表（呼び戻しの辺、フレームワークの入口、設定ファイル・拡張で足す形）。辺の足し方、`inDegree` への効かせ方、広い候補を出さない判断、`role` に優先させる判断、当たらなかった行の知らせ方、列挙定数のキーの書き方、ひな形の出し方と注記、単純名の扱い、ファクトリが親クラスにある場合の指定、経路ごとに決まるキーの扱い |
| [jls-conformance-qa.md](jls-conformance-qa.md) | #154-#157 | AST の読み取りが Java 言語仕様と食い違って呼び出しが静かに落ちていた 4 件。ジェネリックなオーバーライドの照合（O 行と `implementationOf`）、暗黙の `super()`、値を変えうるキャストと char の定数、柔軟なコンストラクタ本体（JEP 513）、インターフェースに暗黙のコンストラクタを合成しない（JLS 8.8.9） |
| [value-safety-qa.md](value-safety-qa.md) | — | 書き手が値を読み違えて呼び出しが静かに落ちていた件（形式 v32。値を変えうるキャスト、浮動小数、複合代入と `++`、引数・フィールドへの `new` と `LOCAL_NEW`、匿名クラスのフィールド初期化子、16 進のリテラル、ループの中で写した変数（先読みを表が変わらなくなるまで繰り返す）、型の揃わない `equals`）。値グラフの同じ式を 1 つのノードにする（300 段の連鎖で N 行 12,079 → 513）。まだ残っているもの。返す具象型の決まらない `@Bean` メソッドで DI の絞り込みが呼び出しを落としていた件（値を読まない指定を含む）。3 回目のレビュー（形式 v35）で見つかった残り: フィールドへの書き込みの取りこぼし（入れ子・外側のクラス、`++`、初期化ブロック、条件の中の書き込みは site を `?` に）、別のインスタンスのフィールドへのコンストラクタ実引数、拡張 for の要素、型名で書いたメソッド参照の実引数の位置、リフレクションの `invoke` の引き先、DI の段 5 を注入点だけに。別のインスタンスのフィールドを別の種別（`O:`）にして、どのインスタンスでも同じ値（初期化子やコンストラクタの `new`・捕捉した引数を使わないラムダ）では再び絞る（形式 v38）。4 回目のレビュー（形式 v39）: `super.f` への書き込みを拾う、注釈の付いたフィールド・ステレオタイプ以外の注釈の付いた型のフィールド（フレームワークが書く）は値を決めない。5 回目のレビュー（形式 v40）: `Objects.requireNonNull(d)` の書き込みを d として読む。検査は `test/pruning` |
| [jls-conformance-test-qa.md](jls-conformance-test-qa.md) | — | Java 言語仕様（SE 26）の節ごとの検査と javac 26 のバイトコードとの突き合わせ（`test/jls`）。見つかった食い違いの修正（拡張 for 文・try-with-resources・レコードパターンが呼ぶメソッド、コンパクトなソースファイルのクラス、インスタンスの main、パッケージアクセスの上書き、呼び出しを修飾する型から引く CHA）と、引用した節番号の原文との照合 |
| [inherited-impl-candidates-qa.md](inherited-impl-candidates-qa.md) | #131 | 段2（`LOCAL_NEW`）と段3（拡張）が、親から継承した実装を候補にできていなかった件。`implementationIn` への統一と、採用できなかった候補の警告 |
| [lambda-expansion-qa.md](lambda-expansion-qa.md) | #127 | ラムダ式の本体を合成メソッド（`lambda$...`）にして、関数型インターフェース経由の呼び出しを本体まで辿る。生成の辺を残す判断、捕捉した変数（`E:`）の扱い（生成したメソッドの段でだけ当てる）、追える形と追えない形、親インターフェースの型で受けた呼び出しにラムダを当てる M 行の鍵、メソッド参照の参照先が仮想メソッドのときの実装への繋ぎ方、javac 21 に合わせた通し番号（javac の版で振り方が違うこと）、式本体の戻り値、2 つの親から同じ抽象メソッドを継承した関数型インターフェース、呼び戻しの契約に渡したメソッド参照、インターフェースのフィールドの中のラムダの名前 |
| [call-hierarchy-columns-qa.md](call-hierarchy-columns-qa.md) | — | `call-hierarchy.csv` に `level`（起点からの深さ）と `resolved-by`（解決方法）の 2 列を `root` の左に足す。値の語彙、ラベルをそのまま出さない 1 ケース、注記から落としたもの |
| [note-tags-qa.md](note-tags-qa.md) | — | 注記に grep 用のタグ（`[UNEXPANDED:*]` / `[EXTERNAL]` / `[UNREACHABLE]` / `[RESOLVED:*]`）を付け、`methods.csv` の列とも揃える。`NO_IMPL` を階層に戻した判断、ラムダを展開できない理由、除外した CHA の候補の数を注記に書く |
| [code-review-fixes-qa.md](code-review-fixes-qa.md) | — | コードレビューで見つかった正確性・性能・構造の問題への対応（CHA の継承実装、解決結果のメモ化、クラス分割） |
| [excluded-entry-promotion-qa.md](excluded-entry-promotion-qa.md) | #121 | 除外した呼び出し先の具象を絞れないと、実装側が入次数 0 になって起点に昇格する件。`java.lang.Object` を型階層に載せない判断と、利用者側の回避策 |
| [ast-analysis-performance-qa.md](ast-analysis-performance-qa.md) | — | AST 解析の性能とメモリ使用量の改善（何を計測して直したか、バッチのサイズ・並列化・仮想スレッドを採らなかった理由と実測値） |
| [cache-identity-qa.md](cache-identity-qa.md) | #102 #103 #104 | キャッシュの「同じファイルか」の判定をパス・サイズ・内容の指紋に統一する（更新時刻をやめる）。クラスパスの並び順の変化を検知する。サーバーモードの `SHUTDOWN` が実行中の解析を中止していた件 |
| [cache-integrity-qa.md](cache-integrity-qa.md) | #97 #98 #99 #100 #101 | キャッシュが静かに嘘をつく 4 件（行がタブ・改行で割れる／コンパイル時定数の値が古いまま残る／文字コードが鍵に入っていない／壊れたキャッシュの扱い）と、中断した実行からの引き継ぎ |
| [multi-config-output-folder-qa.md](multi-config-output-folder-qa.md) | #60 | 複数の設定ファイルと実行ごとの出力フォルダ |
| [output-files-simplify-qa.md](output-files-simplify-qa.md) | — | 出力ファイルの役割の整理。`resolved-classpath.txt` を `run.log` にまとめる判断、依存 jar を取得しない（ネットワークに出ない）仕様の確認、想定どおりに動かなかった実行でだけ出す `warnings.txt`（何を出したら作るか、典型の項目、ビルドが通っていないことの判定） |
| [config-folder-qa.md](config-folder-qa.md) | #62 | 既定の設定ファイルを `config/` に置く。相対パスの起点 |
| [config-file-name-qa.md](config-file-name-qa.md) | — | 設定ファイルの名前。本体は `config.properties` のまま、プラグインが利用者のプロジェクトに書くのは `jche.properties`（誤検出を中身で防ぐ） |
| [cli-app-qa.md](cli-app-qa.md) | #63 | 起動コマンドと対話モード。`.cmd` が MS932 でなければならない理由（Q15） |
| [cli-noninteractive-qa.md](cli-noninteractive-qa.md) | #83 | 引数ありのときは何も尋ねずに解析だけを行う（起動コマンドの初回の質問・知らないオプション・終了コード） |
| [network-download-confirm-qa.md](network-download-confirm-qa.md) | #86 | ネットワークからの取得（JBang 本体・JDK・依存 jar）の前に操作者の確認を必須にする（`--offline` での起動と目印ファイル、端末が無いときの扱い） |
| [eclipse-plugin-qa.md](eclipse-plugin-qa.md) | #49 | Eclipse プラグインとしてビルドできるようにする（Tycho を使わない判断、同梱物の構成） |
| [eclipse-plugin-ui-qa.md](eclipse-plugin-ui-qa.md) | #49 | 呼び出し元階層ビューの実装。別プロセス化、Java 8 対応、設定画面 |
| [eclipse-plugin-progress-log-qa.md](eclipse-plugin-progress-log-qa.md) | — | 解析が返ってこないときに何が起きていたかを残す（子プロセスの標準エラーを読まずに止まる不具合、ログのファイル出力、無通知区間への進捗の追加、応答が無いときの止め方） |
| [eclipse-plugin-ui-simplify-qa.md](eclipse-plugin-ui-simplify-qa.md) | — | 解析結果を「捨てるまで持つ」形にする（自動再解析・アイドル終了・設定画面の巻き添えをやめる）、画面を対象バーと木だけに削ぎ落とす、ボタンをアイコンにする、深さとフィルタの廃止、［リセット］とキャッシュの物理削除の分け方、JDT 差し替えと CSV 一括出力の廃止 |
| [eclipse-plugin-folders-qa.md](eclipse-plugin-folders-qa.md) | — | ビューを開いただけでは解析を始められない問題、自動生成した設定の project.root が作業フォルダを指していた不具合、キャッシュ・ログ・出力の置き場所、向きのボタン分け、字下げ付きのコピー |
| [eclipse-plugin-nls-qa.md](eclipse-plugin-nls-qa.md) | — | プラグインの文言を英語（既定）と日本語で出し分ける。Pleiades が訳してくれない理由、NLS を使わない理由、OS の言語へ落ちないようにする理由、訳し忘れの検査 |
| [nls-qa.md](nls-qa.md) | — | ツール全体（解析ログ・対話モード・起動コマンド）を英語（既定）と日本語で出し分ける。文言を properties ではなく Java の表に置いた理由、言語の優先順位、**出力 CSV を訳さない**判断、文言なのにキャッシュの版を上げた 1 件、プラグインと解析ログの言語をそろえる仕組み、検査が何も見ていなかった件 |
| [eclipse-plugin-java-floor-qa.md](eclipse-plugin-java-floor-qa.md) | — | プラグインの下限を Java 8 / Eclipse 4.6 から Java 11 / Eclipse 4.17 へ上げる。「解析対象の Java の版」と「Eclipse を動かす Java の版」を取り違えていた話、Java 8 対応が何を買って何を払っていたか |
| [syntax-error-report-qa.md](syntax-error-report-qa.md) | — | 構文エラーで読めなかったファイルを黙って落とさず報告する。型解決のエラーと分けて数える理由、キャッシュの F 行に持たせた理由（2回目以降も言い続けるため）、`var` の使い方の誤りを構文エラーに数えない理由（switch 式の検査（網羅していない・default が無い・switch 式の外への break など）も同じ。形式 v36。cache-unification-qa.md の Q63） |
| [vscode-plugin-qa.md](vscode-plugin-qa.md) | — | VSCode プラグインの設計判断（VSCode の作法との折り合い、標準の呼び出し階層に相乗りしない理由、`AT` を足した理由と断り方、`.vsix` を手動で作るワークフロー） |
| [eclipse-maven-qa.md](eclipse-maven-qa.md) | #39 | Eclipse（Pleiades）で開くための `pom.xml`。Gradle や jbang-eclipse を選ばなかった理由 |
| [github-actions-qa.md](github-actions-qa.md) | #48 | 複合アクションとしての設計。入力から設定ファイルを生成する判断。依存 jar の警告を英語・日本語どちらのログからも拾う（Q31） |
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
