# 内部の作り（全体図）

初めてコードを読む人が 10 分で全体をつかむための 1 枚。ここで場所をつかんだら、
規則ごとの深い対応表は [resolution-selection-design.md](resolution-selection-design.md)、キャッシュの設計は
[cache-design.md](cache-design.md)、用語は [glossary.md](glossary.md)、3 層（`analysis` / `cache` / `graph`）の決まりと読む順は
`src/jche/{analysis,cache,graph}/package-info.java` へ進む（`package-info.java` があるのはこの 3 つだけ）。

## 1. 何をどう作っているか（3 フェーズと 3 層）

1 回の実行は 3 つのフェーズで進み、パッケージはフェーズに対応する。

```
  フェーズ1  解決       jche.analysis   ソースを JDT で解析し、ソースファイル 1 つにつき 1 ブロックの「事実」を
                                        キャッシュ（analysis-cache.tsv。jche.cache）に書く。JLS の判定は JDT に任せる
  フェーズ2  選択       jche.graph      キャッシュを 1 回読んで呼び出しグラフ（CSR）を組み、呼び出しごとに
                                        「受け手の型の候補」を絞り（CallResolver）、その型で「実際に動く本体」を選ぶ（MethodSelection）
  フェーズ3  出力       jche.report     起点ごとに深さ優先で辿りながら CSV を 1 行ずつ書く。木は組み立てない
```

「解決 → キャッシュ → 選択」は JVM の 2 段（JVMS 5.4.3 の解決・5.4.6 の選択）に対応させた 3 層でもある。
解決の結果は「一度決まれば同じ」なのでキャッシュに置け、選択は実行時の情報なので毎回読み手が組み立てる。
判断の基準は 1 つ、**呼び出しを静かに落とさない**（分からなければ候補を落とさない側に倒す）。

メモリの設計も 3 フェーズに対応する（`CallHierarchyExporter` のクラスコメント）: 解析結果をヒープに溜めない（1 ファイルずつ書いて捨てる）、
エッジをオブジェクトで持たない（メソッドを int の ID にして CSR 配列で持つ）、ツリーを組み立てない（経路の配列だけ）。

## 2. 1 回の実行が通る道

```
java-call-hierarchy-exporter.sh / .cmd            起動コマンド。JDK・JBang の用意、ネットワークからの取得の確認、JVM のオプション
  └ jche.Jche.main                                引数あり → 対話なし / 引数なし → 対話モード（jche.cli.App）
      └ jche.CallHierarchyExporter.runAll         設定ファイルごとに 1 回。出力フォルダ・run.log・warnings.txt を持つ
          ├ jche.config.Config                    設定の読み取り（ConfigFile）・空欄の自動判定（ProjectDetector）
          │   └ jche.config.ProjectLayout         ソースフォルダと依存 jar の確定。library.folders が空欄なら
          │                                       BuildFileClasspath が pom.xml / build.gradle を読んでローカルリポジトリから集める
          ├ jche.Exporter.analyze                 フェーズ1・2。Eclipse / VSCode の解析サーバー（jche.server.Server）もここを呼ぶ
          │   ├ [フェーズ1] jche.analysis.CacheUpdater      旧キャッシュを読みながら新キャッシュを書くストリーミングマージ。
          │   │               ├ StaleTypes / LibraryDiff    どのファイルを解析し直すか（変わったファイル・その型を使うファイル・jar の変化）
          │   │               ├ CallEdgeExtractor           JDT に一緒に渡すファイルの組（バッチ）を作り、createASTs を呼ぶ
          │   │               │   └ FactVisitor             AST を訪問して事実を集める（CallSiteRecorder・OriginTracker・
          │   │               │                             TypeContextTracker・FieldFactCollector・ImplicitCalls・…に分担）
          │   │               └ BlockWriter                 1 ファイル分の事実（jche.cache.FileAnalysis）を 1 ブロックとして書く
          │   ├ [フェーズ2] jche.graph.CallGraphBuilder     キャッシュを 1 回スキャンして CallGraph・MethodTable・TypeHierarchy・
          │   │                                             OverrideIndex・ValueStore・GuardTable を組む
          │   │             jche.dataflow.DataflowBuilder   フェーズ2b。ファクトリの戻り値など経路に依らない値をグラフ全体から一括で確定
          │   │             jche.graph.CallResolver         呼び出しごとに受け手の型の候補を絞る段（BindKind → 段 0〜6）。
          │   │               ├ DataflowResolver            値の表（ValueStore）と経路の値（Slot）から具象型を追う
          │   │               ├ SpringBeans / Contracts     DI の Bean 定義・契約表・拡張（jche.extension.TypeCandidateProvider）
          │   │               └ MethodSelection             候補の型ごとに実際に動く本体を選ぶ（JVMS 5.4.6。実装探索の入口はここだけ）
          │   └ jche.AnalysisSnapshot              グラフ・解決器・設定をひとそろいにした結果
          └ [フェーズ3] jche.report.StreamingTreeWalker      起点（jche.graph.EntryPoints）から深さ優先で辿り、
                          → CallHierarchyCsvWriter          call-hierarchy.csv を 1 行ずつ書く
                        jche.report.UnresolvedReport        型解決に失敗した呼び出しを同じ CSV の末尾に
                        jche.external.ExternalUsageScanner  外部 jar のクラスファイルを読み、被参照を同じ CSV に追記
                        jche.report.InventoryReport         methods.csv（メソッドの一覧と呼ばれ方）
                        jche.report.ContractSuggestions     絞れなかった呼び出しから契約表のひな形（contracts-suggested.txt）
```

Eclipse / VSCode のプラグインは `jche.server.Server`（`--server`）を子プロセスとして起動し、`Exporter.analyze` の結果を
メモリに持ったまま `FIND` / `AT` / `TREE` / `EXPORT` の要求に応える（プロトコルは `jche.server.Protocol`。
[out-of-process-analysis-design.md](out-of-process-analysis-design.md)）。

## 3. 入口は 3 つ

名前が似ているので役割を表にしておく。

| クラス | 誰が呼ぶか | やること | やらないこと |
|---|---|---|---|
| `jche.Jche` | 起動コマンド（`.sh` / `.cmd`） | 引数の解釈。設定ファイルがあれば `CallHierarchyExporter.runAll`、無ければ対話モード（`jche.cli.App`） | 解析そのもの |
| `jche.CallHierarchyExporter` | `Jche`・jbang での直接実行・GitHub Actions | 設定ファイルごとに出力フォルダを作り、`Exporter.analyze` → フェーズ3（CSV）を書く。`//DEPS` / `//JAVA` の JBang ヘッダを持つ | 対話・サーバー |
| `jche.Exporter` | `CallHierarchyExporter`・`jche.server.Server` | フェーズ1（解析・キャッシュ）とフェーズ2（グラフ・解決）。結果を `AnalysisSnapshot` として返す | CSV・出力フォルダ・ログファイル |

## 4. パッケージの依存の向き

```
                     cli ──┐
   Jche ─▶ CallHierarchyExporter ─▶ Exporter ─▶ analysis ─▶ cache ◀─ graph ◀─ dataflow
                                       │            │                   ▲         ▲
                          server ──────┘            └──── config        │         │
                                                                     report    external
   extension（利用者の拡張が import する唯一の公開 API。java.* 以外に依存しない）◀─ graph / builtin
   framework（アノテーション処理で実装が生成される型の定義。GeneratedImpl）◀─ graph / report
   util（Log・Messages・Warnings・FileTree など。どこからでも）
```

- `analysis`（書き手）と `graph`（読み手）は互いを参照せず、`cache` の record と形式（`CacheFormat`）だけを共有する
- `framework` は生成される実装（Doma の `@Dao` など）の定義だけを持ち、`graph`（解決の段）と `report`（注記）が読む
- `dataflow` は `graph` の上に乗るフェーズ 2b。`report` と `external` は `graph` を読むだけ
- `extension` は利用者が書く拡張がコンパイル時に依存する API なので、互換を保ち `java.*` 以外を import しない
  （1 ファイル版でもここだけは本物のパッケージのまま写す）

## 5. パッケージと主要クラス

| パッケージ | 責務 | まず読むクラス |
|---|---|---|
| `jche` | 入口 3 つと結果の入れ物、ワークスペースの他プロジェクト（`workspace.projects`。相手の設定でフェーズ1 を走らせ、相手のキャッシュをフェーズ2 で名前で結合する） | `Jche`、`CallHierarchyExporter`、`Exporter`、`AnalysisSnapshot`、`WorkspaceProject` |
| `jche.config` | 設定ファイルの読み取り、`project.root` からの自動判定、ビルドファイル（Maven / Gradle）から依存 jar を集める | `Config`、`ConfigFile`、`ProjectDetector`、`ProjectLayout`、`BuildFileClasspath`（→ `MavenBuild` / `GradleBuild`）、`ToolRoot`、`Plugins` |
| `jche.analysis` | **解決**。JDT で AST を訪問し事実を集め、差分更新でキャッシュを書き直す | `CacheUpdater`、`StaleTypes`、`CallEdgeExtractor`、`FactVisitor`、`BindingNames`、`OverrideFacts`、`CallSiteRecorder`、`OriginTracker`、`ImplicitCalls`、`LibraryDiff`、`BlockWriter` |
| `jche.cache` | **キャッシュ**。`analysis-cache.tsv` の形式と、行 1 つ 1 つの record。錠と一時ファイル | `CacheFormat`（形式の正本）、`FileAnalysis`、`CacheReader`、`CacheLock`、`TempFiles`、`CacheDump`（目で読める形に戻す道具） |
| `jche.graph` | **選択**。キャッシュからグラフを組み、受け手の型の候補を絞り、動く本体を選ぶ | `CallGraphBuilder`、`CallGraph`、`MethodTable`、`TypeHierarchy`、`BindKind`、`CallResolver`、`Resolution`（ラベルの語彙）、`MethodSelection`、`DataflowResolver`、`ValueStore`、`SpringBeans`、`Contracts`、`EntryPoints` |
| `jche.dataflow` | フェーズ 2b。経路に依らない値（ファクトリの戻り値の畳み込みなど）をグラフ全体から一括で確定する | `DataflowBuilder`、`DataflowFacts` |
| `jche.report` | **出力**。CSV を 1 行ずつ書く | `StreamingTreeWalker`、`CallHierarchyCsvWriter`、`ResolvedBy`、`InventoryReport`、`UnresolvedReport`、`ContractSuggestions`、`CallConditionsReport` |
| `jche.external` | 外部 jar のクラスファイルを読んで、自分のメソッドの被参照を出す | `ExternalUsageScanner`、`ClassFileRefs` |
| `jche.extension` | 利用者が Java で書く拡張の API（公開 API。互換を保つ） | `TypeCandidateProvider`、`Hint`、`ContractProvider`、`UsageReporter` |
| `jche.builtin` | 同梱の拡張（対応表ファイルで具象型を返す） | `TypeMappingProvider` |
| `jche.framework` | アノテーション処理で実装が生成される型（Doma の `@Dao` など）の定義 | `GeneratedImpl` |
| `jche.cli` | 対話モードの画面と、起動コマンドの設定（`launcher.properties`） | `App`、`ConfigCatalog`、`ConfigWizard`、`LauncherSettings`、`Terminal` |
| `jche.server` | サーバーモード（`--server`）。プラグインの子プロセス | `Server`、`Protocol`、`CallTree`、`FieldTree` |
| `jche.util` | 横断の道具。ログ・文言・警告・ファイル | `Log`、`Messages`（`MessagesEn` / `MessagesJa`）、`Warnings`、`FileTree`、`FileHash`、`RunControl`、`HeapWatch` |

## 6. デバッグの最初の一手

- **出力がおかしい**: `call-hierarchy.csv` の `resolved-by` 列（`RESOLVED:` / `UNEXPANDED:` / `UNRESOLVED:`）でどの段が決めたかが分かる。
  ラベルの意味は `jche.graph.Resolution` と README の「具象クラスの解決」
- **キャッシュを目で読みたい**: `jche.cache.CacheDump` が記号（S 行の番号）を 4 列に戻して出す。行種別は [glossary.md](glossary.md) の表と `CacheFormat` のクラスコメント
- **差分更新が全件解析と違う**: `test/incremental/run.sh` の形（書き換え → 差分更新 → キャッシュを消して全件 → 比較）で再現する。
  事実がバッチ（JDT に一緒に渡すファイルの組）に依っていないかを疑う（[cache-design.md](cache-design.md)）
- **警告の意味**: 出力フォルダの `warnings.txt` に対処が書いてある。出す側は `jche.util.Warnings`
- **性能**: `test/profile/`（[ast-analysis-performance-qa.md](ast-analysis-performance-qa.md) の「計測のしかた」）
