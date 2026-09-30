# 用語集

コード・文書・キャッシュで説明なしに使っている語を 1 か所に集めた。日本語の語 → コード上の名前 → 意味の順。
全体図は [architecture.md](architecture.md)、規則ごとの対応表は [resolution-selection-design.md](resolution-selection-design.md)。

## 1. 構図

| 用語 | コード上の名前 | 意味 |
|---|---|---|
| 解決（resolution） | `jche.analysis`。「書き手」「フェーズ1」とも呼ぶ | 呼び出し式 → コンパイル時宣言（JLS 15.12.1〜15.12.3、JVMS 5.4.3.3）。JDT の `IMethodBinding` に任せ、結果を事実としてキャッシュに書く |
| 選択（selection） | `jche.graph`。「読み手」「フェーズ2」とも呼ぶ。`MethodSelection` | 受け手の実行時の型 C と解決済みの宣言から、実際に動く本体を選ぶ（JVMS 5.4.6、JLS 15.12.4.4） |
| 書き手 / 読み手 | `jche.analysis` / `jche.graph`・`jche.report` | キャッシュを書く側 / 読む側。互いを参照せず `jche.cache` だけを共有する |
| 事実 / 判断 | `CacheFormat` の「原則」 | AST とバインディングから機械的に読み取れ、設定・出力形式・解決の方針に依らない情報 / フィルタ・推定・しきい値・文言など。事実だけをキャッシュに置き、判断は読み手でする |
| フェーズ1 / 2 / 2b / 3 | `Exporter#analyzeSources` / `buildGraph` / `jche.dataflow.DataflowBuilder` / `CallHierarchyExporter#writeReports` | 解析とキャッシュ更新 / グラフの構築 / 経路に依らない値の一括確定 / CSV の出力 |
| 安全側に倒す・多すぎる側に倒す | — | 分からないときは候補を落とさず、絞れなかったことが分かる形（`UNEXPANDED:`）で出す。このツールの唯一の判断基準 |
| 静かに落とす | — | 出力に出ないうえ、エラーにもならない欠け。最もしてはいけないこと |

## 2. 解決の段（`resolved-by` 列の語彙）

`call-hierarchy.csv` の `resolved-by` 列は「接頭辞 + ラベル」で、ラベルは `jche.graph.Resolution` の定数。
候補が 1 件に定まれば `RESOLVED:`、候補のままなら `UNEXPANDED:`、型解決に失敗していれば `UNRESOLVED:`。

| 用語 | コード上の名前 | 意味 |
|---|---|---|
| 段 0（静的束縛） | `BindKind`、`STATIC_BOUND:*` | private・static・final・コンストラクタ・`super` 呼び出し。仮想ディスパッチされないので宣言のまま確定 |
| 段 1 | `NO_OVERRIDE` / `SINGLE_IMPL` / `NO_IMPL` | 上書きの候補が 1 つ / 本体を持つ実装がソースに無い |
| 段 2 | `LOCAL_NEW` / `LOCAL_NEW_MULTI` | 同じメソッドの中で `new` された型 |
| 段 3 | `CONTRACT`、拡張のラベル | 契約表（種類 C）・利用者の拡張が返した具象型 |
| 段 4 | `DATAFLOW_NEW` / `DATAFLOW_FACTORY` | 値の追跡。`new` された型・ファクトリの戻り値 |
| 経路ごと | `DATAFLOW_PARAM` / `DATAFLOW_FIELD` / `DATAFLOW_DECLARED_TYPE` / `DATAFLOW_LAMBDA` | 呼び出し元から渡された引数・コンストラクタ注入されたフィールド・宣言の型の上限・ラムダを経路上で追った結果。段の外（経路ごとに判定） |
| 段 5 | `SPRING_DI` / `SPRING_DI_QUALIFIER` | DI コンテナ（Spring）の Bean 定義で 1 つに定まった |
| 段 6 | `CHA` | Class Hierarchy Analysis。型階層上の部分型をすべて候補にする（最も広い。低確度） |
| CHA の起点 | `CallResolver#usableQualifier`、C 行の qualifier | 候補を数え始める型。呼び出しを修飾する型（JLS 13.1）で、宣言した型ではない |
| その他 | `CALLBACK` / `REFLECTION` / `EXTERNAL_GUESS` / `GENERATED_IMPL:*` / `LAMBDA` | 契約で jar の中を跨いで繋いだ / リフレクション / import からの推定（未検証） / 生成される実装 / ラムダの実装（常に `UNEXPANDED:LAMBDA`） |

## 3. 解決と選択の材料

| 用語 | コード上の名前 | 意味 |
|---|---|---|
| コンパイル時宣言 | `BindingNames#toRef`（JDT の `getMethodDeclaration`） | javac が選ぶ宣言。C 行の呼び出し先 |
| キー | `型FQN#名前(消去した引数型)` | メソッドを指す文字列。JVMS の「名前とディスクリプタ」のソース側の写し |
| 記号（S 行の番号） | `SymbolTable`、`MethodRef` | ブロックの中でメソッドを指す番号。4 列（pkg・型・名前・引数）を繰り返さないため。ブロックの中だけで通じる |
| 上書き（O 行） | `OverrideFacts`、`OverrideIndex`、JDT の `IMethodBinding.overrides` | 型引数を具体化してキーの食い違う上書き（javac ならブリッジメソッドを作る形） |
| 親クラスの連鎖（H 行の 7 列目） | `TypeHierarchy#classChain` | 直接の親クラスから根まで。実装探索の 1 段目の順 |
| 継承した実装（H 行の 8 列目） | `MethodSelection#inheritedImplementationIn` | 親クラスから継承したメソッドが、型引数を置き換えた親インターフェースのメソッドを実装する組 |
| 最も特定的（maximally-specific） | `TypeHierarchy#mostSpecific` | 親インターフェースの宣言のうち、より下位のもの。`default` の選び方（JLS 9.4.1） |
| 契約表 | `Contracts`、`TypeContracts`（種類 C）、`CallbackContracts`（種類 A）、`FrameworkEntries`（種類 B） | ソースの外（JDK・フレームワーク）との約束を書いた表。呼び戻し・入口・具象型の対応（[callback-contracts.md](callback-contracts.md)） |
| 拡張（extension） | `jche.extension.TypeCandidateProvider`、設定の `plugin.folders`、`jche.config.Plugins` | 利用者が Java で書く差し込み口。**IDE のプラグイン（`eclipse-plugin/`・`vscode-plugin/`）とは別物** |
| 証拠（hint） | `jche.extension.Hint`、`HintFact` | 拡張に渡す、呼び出し箇所の局所的な材料（ファクトリのキーなど） |
| 起点（エントリ） | `EntryPoints`、設定の `entry.packages` | 呼び出し階層を辿り始めるメソッド。全体モードでは誰からも呼ばれていないメソッド |
| 被参照 | `jche.external.ExternalUsageScanner`、`EXTERNAL_USAGE:*` | 外部 jar のクラスファイルから自分のメソッドが参照されている箇所 |

## 4. 値の追跡

| 用語 | コード上の名前 | 意味 |
|---|---|---|
| 出所（origin） | `Origin`、`OriginTracker` | 式の値がどこから来たか（`new`・引数・フィールド・戻り値・定数…）の最小の表現 |
| 値グラフ（N 行） | `ValueGraph`（書き手）、`ValueNode` | 1 ファイル分の値の出所をノードで持つグラフ。戻り値（R 行）・代入（J 行）・呼び出し箇所の値はノード番号で指す |
| 値の表 | `ValueStore`（読み手）、`ValueStoreBuilder`、`StringPool` | 値グラフをグラフ全体で通じる番号の列にしたもの。文字列に組み直さない |
| 枠（slot） | `Slot`、`DataflowContext` | 経路の環境の 1 つの枠に入る、型付きの値（`long` 1 つ） |
| 条件・ガード（G 行） | `Guard`、`GuardCollector`、`GuardTable`、`GuardEvaluator` | 呼び出し箇所を囲む条件分岐。この経路では成立しないと判定できれば打ち切る（`branch.pruning.enabled`） |
| 打ち切り | `[UNREACHABLE]`、`prunedCalls` | 条件が成立しない経路の呼び出しを階層から外すこと。誤って付けないことを `test/pruning` が守る |
| 合成メソッド | `LambdaNames`、`lambda$囲みメソッド名$番号` | ラムダ式の本体を 1 つのノードにした、javac に似せた名前のメソッド |

## 5. キャッシュと差分更新

| 用語 | コード上の名前 | 意味 |
|---|---|---|
| ブロック | `FileAnalysis`、F 行から次の F 行の手前 | ソースファイル 1 つにつき 1 つ。構造と値の両方を持つ |
| 全件解析 / 差分更新 | `CacheUpdater` | キャッシュを捨てて全部解析する / 変わったファイルと、その型を使うファイルだけ解析し直す。結果が一致することを `test/incremental` が守る |
| 依存する型（I 行） | `BindingNames#typeNameOf` ほか、`StaleTypes` | そのファイルが結果を左右されうる型の一覧。これらを宣言するファイルが変わったら解析し直す |
| 自分の宣言の指紋（I 行の最後の列） | `TypeContextTracker` | 中身が変わっていないファイルの宣言（親型・メンバー）が変わったかを見る |
| 指紋 | `FileHash`、`LibraryDiff`、T 行・L 行 | 変化を見るためのハッシュ（ソースの内容・jar の目次・ソース一覧） |
| 検査値（crc） | `BlockChecksum`、F 行の最後の列・T 行の最後の列 | 壊れていないかを見る CRC32。合わないブロックはそのファイルだけ解析し直す |
| バッチ | `CallEdgeExtractor#analyzeBatch` | JDT に一緒に渡すファイルの組。事実はこれに依らせない |
| 添えるファイル | `CallEdgeExtractor`、`ProjectScan` | 事実は集めないが、解決のためにバッチに同梱するファイル（名前の違うファイルで宣言した型・jar が参照する入れ子の型） |
| 錠 | `CacheLock`、`analysis-cache.tsv.lock` | 同じキャッシュのフォルダを使う実行を 1 つずつにする |
| 一時ファイル | `TempFiles`、`*.tmp` | キャッシュのフォルダに置く、1 回の実行の中だけの索引・中間データ。ヒープに置かない |
| 形式の版 | `CacheFormat.VERSION`（`jche-cache-vNN`） | ヘッダ行の先頭。違えば丸ごと作り直す。書き手が作る事実が変わりうるなら上げる |
| 中身の分からないパッケージ | `CacheUpdater`（解析に失敗したファイルの扱い） | 解析に失敗したファイルの置き場所のパッケージ。同じパッケージのファイルは名前を照合せず解析し直す |
| `?.Template` | `BindingNames#qualifiedNameOf` | 依存 jar が無いときに JDT が単純名から作った無い型の書き方。`?` は Java の名前に使えないので本物の型と重ならない |

## 6. キャッシュの行種別

`analysis-cache.tsv` はタブ区切りで、行頭 1 文字が種別。列の定義はその行の record の `toRow` / `fromRow`、並びと意味の正本は
`jche.cache.CacheFormat` のクラスコメント。ブロックの中の並びは `F I S N G R H D O V C/U M A K J`。
「n 列目」は行種別の文字を 1 列目に数える。目で読むときは `jche.cache.CacheDump`。

| 行 | record | 何の行か |
|---|---|---|
| ヘッダ | `CacheFormat#headerFor` | 形式の版・ソースレベル・文字コード・実行 JDK・JDT の版・ソースフォルダの一覧。どれかが違えば丸ごと作り直す |
| L | `LibraryFact` | 依存 jar（クラスフォルダ）。クラスパス順。指紋とパッケージの一覧 |
| T | `CacheFormat#sourcesRow` | ソース一覧の指紋と、先頭の行の検査値。中断した実行からの引き継ぎに使う |
| F | `FileAnalysis`（ブロックの先頭） | 相対パス・サイズ・エラー数・内容ハッシュ・構文エラー数・未解決数・検査値 |
| I | — | 依存する型の一覧と、自分の宣言の指紋。差分更新が使う |
| S | `SymbolTable`、`MethodRef` | ブロック内のメソッドの記号表 |
| N | `ValueNode` | 値グラフのノード |
| G | `Guard.Atom` | 条件分岐の表 |
| R | `ReturnFact` | メソッドが返しうる値（ノード番号） |
| H | `TypeFact` | 型。種別（I/A/C）・親型・パッケージ・アノテーション・親クラスの連鎖（7 列目）・継承した実装（8 列目）・jar の型の推移的な親型（9 列目） |
| D | `MethodDeclFact` | メソッド宣言。宣言行・本体の有無・修飾子・アノテーション・終了行・戻り値の型 |
| O | `OverrideFact` | 上書き先のキー |
| V | `FieldDeclFact` | フィールド宣言 |
| C | `CallEdgeFact`、`CallSite` | 解決できた呼び出し。呼び出し元・先の記号・行・呼び出し先の修飾子・レシーバの由来（`RecvKind`）・修飾する型・値・ガード・証拠 |
| U | `UnresolvedCallFact` | 型解決に失敗した呼び出し。式・理由・候補 |
| M | `FunctionalImplFact` | ラムダ・メソッド参照が実装する抽象メソッドの鍵 |
| A | `FieldAccessFact` | フィールドの参照箇所（読み・書き） |
| K | `ConstantFact` | このファイルが宣言するコンパイル時定数 |
| J | `FieldAssignFact` | フィールドへの代入 |
| Z | — | ブロック数。最後まで書き終えた印 |

## 7. 出力

| 用語 | 場所 | 意味 |
|---|---|---|
| `call-hierarchy.csv` | `CallHierarchyCsvWriter` | 起点からの経路を 1 行ずつ。列は `caller,callee,resolved-by,level,root,call-hierarchy`（最後は可変長） |
| `methods.csv` | `InventoryReport` | ソース上の全メソッドの一覧と呼ばれ方（`inDegree`・`role`・`reachable`・`absentCause`…） |
| 注記 | `call-hierarchy` 列の最後の要素、`[UNEXPANDED:*]` などのタグ | 列に無いこと（打ち切りの理由・候補の件数・繋いだ契約）だけを載せる。英語で固定 |
| `warnings.txt` | `jche.util.Warnings` | 確認してほしいことと対処。`Log.warn` / `Log.error` が 1 行でも出た実行でだけできる |
| `contracts-suggested.txt` | `ContractSuggestions` | 絞れなかった呼び出しを 1 件に絞るための契約表のひな形。人が読む案内なので表示言語に合わせる |
| `run.log` | `jche.util.Log` | 標準出力と同じ内容の実行ログ |
| `call-conditions.csv` | `CallConditionsReport` | `conditions.target` を書いたときだけ追加で出る、呼び出しに効いている条件の一覧 |
