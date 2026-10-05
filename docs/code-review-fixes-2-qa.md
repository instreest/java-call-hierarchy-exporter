# コードレビュー（2 回目）で見つかった問題への対応 — 実装時の QA 一覧

リポジトリ全体の 2 回目のコードレビューで挙がった指摘を、作業の塊ごと（A: 出力に出す・出さない、B: 安全性、
C: 解析・キャッシュ、D: 選択・解決・jar からの被参照、E: 設定・CLI・サーバー、F: 文書の古い記述、G: リポジトリの整備と検査の題材）に
分けて対応し、1 本のブランチに取り込んだ。迷ったこと・結論・却下した案を Q&A の形で残す。
1 回目は [code-review-fixes-qa.md](code-review-fixes-qa.md)。

対応の要点:

- **繋げなかった呼び出しを黙って落とさない。** リフレクションの相手が決まらない呼び出しは `UNEXPANDED:REFLECTION`、
  ライブラリ呼び出し規則は当たったが渡した値を辿れない呼び出しは `UNEXPANDED:CALLBACK` の行として
  `call-hierarchy.csv` に出す（除外パッケージの呼び出し先でも）。`methods.csv` の `unresolvedCalls` にも数え、
  件数を warnings.txt に載せる（Q1・Q2）
- **`methods.csv` の見え方を直す。** 起点は、その起点から 1 行でも書いたなら `inHierarchy=1`（Q3）。
  最終列に `externalRefs`（jar からの被参照の行数）を足し、jar からだけ呼ばれるメソッドも `inHierarchy=1` にする（Q4）
- **対処の要る状態は warnings.txt に出す。** `max.depth` で降りなかった件数、`max.rows` で止めた印の行、起点が 0 件、
  クラスフォルダを `external.library.folders` に指したとき、jar からの被参照で結び付かなかった参照（Q5）
- **record の暗黙の正準コンストラクタ**を、正準でないコンストラクタだけ書いた record にも合成する。事実が変わるので形式を
  `jche-cache-v46` に上げた（Q6）
- **コンストラクタと `<clinit>` は継承で結び付けない**（JVMS 5.4.3.3）。jar からの被参照の `IMPLICIT_CTOR` が正しく届く（Q7）
- **Gradle の lockfile** は、クラスパスの構成（`compileClasspath` / `runtimeClasspath`）の行があるときだけ使う（Q8）
- **設定ファイルの知らない項目**は警告して止めない。`Config.KNOWN_KEYS` を唯一の一覧にし、綴りの近い項目名を添える（Q9）
- **1 ファイル版のキャッシュの版**に `-single` を付け、本体のキャッシュと混ざらないようにする（Q10）
- **VSCode / Eclipse プラグインの安全性**: 設定の適用範囲をマシン単位に、信頼していないワークスペースでは解析の設定を
  読まない、自動で拾った設定ファイルが Java の拡張を動かすときは一度確かめる（Q11）
- **CSV の数式**: `=` `+` `-` `@` で始まるセルに `'` を前置して Excel の数式評価を防ぐ（Q12）
- そのほか: module-info.java で JDT が止まったときの失敗の範囲、`BlockWriter` の二重計上、型階層の集合化、
  壊れた class・切れた jar の読み飛ばし、拡張のクラスローダの後始末、`1.0.0-SNAPSHOT` と `1.0` の順序（Q13）
- 検査の題材: DI を切る（`nodi`）・独自注釈を Bean の印にする（`diannot`）・同梱の規則を切る（`nobuiltin`）の回帰ケース、
  Kotlin DSL（`build.gradle.kts`）のサブプロジェクト、Dependabot・`.editorconfig`・`.gitignore` の `bin/` `lib/` の場所（Q14）

## Q1. リフレクションの相手が決まらない呼び出しを、どの行として出すか（★A）

`Method.invoke` / `Constructor.newInstance` / `Class.newInstance` の相手を値の追跡で決められなかったとき、以前は
`RESOLVED:STATIC_BOUND` として jar のメソッド（`Method.invoke` そのもの）への行を書くか、除外パッケージなら行を書かずに
落としていた。影響調査で「ここから先は分からない」が見えないのは、このツールの方針（呼び出しを静かに落とさない）に反する。

**結論**: 解決の結果に `REFLECTION_UNKNOWN` のラベルを足し（`Resolution.REFLECTION_UNKNOWN`）、`call-hierarchy.csv` には
`resolved-by` が `UNEXPANDED:REFLECTION`、注記が
`[UNEXPANDED:REFLECTION] target unknown: class or method name could not be determined on this path` の行を書く。
呼び出し先が除外パッケージ（`java.**`）でも、この行だけは書く（除外は「jar の中を辿らない」であって「分からないことを隠す」ではない）。
`methods.csv` では `unresolvedCalls` に数え、`unresolvedCause` に同じタグを書く。件数は warnings.txt の
「途中で打ち切られた」の項目に載せる。

- `Class.forName` の失敗はこの扱いにしない。`forName` が繋ぐのは `<clinit>` だけで、static 初期化子の無いクラスへの
  リテラル名でも「相手なし」になるので、「分からない」と言うと嘘になる
- `methods.csv` の件数は経路に依存しない判定（`ctx=null`）で数える。`DaoFactory.byName` のように、階層では呼び出し元の経路で
  名前が決まって解決できる呼び出しも、`methods.csv` では 1 件の未解決になる。既存の CHA と `DATAFLOW_PARAM` の関係と同じ
- 注記の文言は「名前が実行時の値」ではなく「この経路では決められなかった」にした。同じ行は `dataflow.enabled=false` のときや、
  名前がメソッドの戻り値・文字列の演算から来るとき、名前の指す型がソースに無いときにも出るからである（README に理由を列挙）

却下した案: *候補を全部並べる*（名前の分からないメソッドの候補はソースの全メソッドなので意味が無い）。
*`RESOLVED:STATIC_BOUND` のまま注記だけ足す*（`RESOLVED:` で絞る読み手が「解決できた」と読む）。

## Q2. ライブラリ呼び出し規則は当たったが渡した値を辿れない呼び出しを、どう見せるか（★B）

`tasks.forEach(Runnable::run)` や、後からセッターで入れたフィールドを `new Thread(task).start()` に渡す形は、規則
（`Iterable#forEach` / `Thread#start`）は当たるのに、呼び戻される実装がソースに無い・値を辿れないので、以前は行が出なかった。

**結論**: 規則が当たった辺（`CallResolver#hasCallbackRule`）で呼び戻し先が 1 つも無く、本来の解決が 1 件（CHA の複数候補では
ない）なら、`UNEXPANDED:CALLBACK` の行を書き、注記に
`[UNEXPANDED:CALLBACK] rule matched but the passed value could not be traced to a method in the source` を付ける。

- `resolved-by` を `UNEXPANDED:CALLBACK` に差し替えるのは、呼び出し先にソースが無い（jar の `forEach` / `start`）ときだけ。
  呼び出し先がソース（test/demo の `Dispatcher.submit`）なら、その先へ降りるので `resolved-by` は元のまま（`UNEXPANDED:` は
  「この先が出ていない」の意味）で、注記だけ足す
- CHA の複数候補の辺には付けない。`UNEXPANDED:CHA` で既に「絞れていない」と見えている
- `CallbackRules` の呼び戻し先は辺ごとに 1 回だけ引き、`descendCallbacks` に渡す（以前は 2 回引いていた）

題材として `test/demo/src/fx/lambda/LateTask.java`（フィールドにセッターで入れた Runnable を `Thread#start` に渡す）を足した。

## Q3. 起点の `inHierarchy` を何で決めるか（★C）

`inHierarchy` は「`call-hierarchy.csv` に出たか」だが、起点（根）は callee 列に出ないので、以前は起点から 100 行書いても
`inHierarchy=0` / `[NOT_REACHED] no caller row was emitted` になっていた。起点を「出ていない」と読まれると、起点そのものの
影響調査を落とす。

**結論**: 起点は、その起点を根にした降下が 1 行でも書いたら `inHierarchy=1`（`StreamingTreeWalker#walkAll`）。1 行も書かなかった
起点（呼び出しを持たない `ISOLATED` な全体モードの起点、唯一の呼び出しがコンストラクタ呼び出しで行にならない `AppConfig.clock`）は
`[ENTRY_NO_ROWS] entry point with no call rows` にして `[NOT_REACHED]` と区別する。`max.rows` で止めたときは判定しない
（止めなければ書けたかもしれない）。

同じ回で、CHA の候補が 20 件を超えて行にしなかった候補には `[CHA_OVERFLOW] CHA candidate beyond the first 20 was not written as a row`
を付ける（以前は `[NOT_REACHED]` に混ざっていた）。`absentCause` の優先順位（バイト順）は
`PRUNED_SUBTREE < CHA_OVERFLOW < CYCLE < CHA < EXCLUDED < ENTRY_NO_ROWS`。

## Q4. jar からの被参照を `methods.csv` にどう載せるか（★D）

jar からの被参照（`external.library.folders`）は `call-hierarchy.csv` の後ろに `EXTERNAL_USAGE` の行で出るが、
`methods.csv` はソースの辺しか見ないので、jar からだけ呼ばれるメソッドが `ISOLATED` / `inHierarchy=0` のままだった。
`methods.csv` だけで「使われていないメソッド」を拾う人が、jar から呼ばれるものを削ってしまう。

**結論**: `methods.csv` の最終列に `externalRefs`（そのメソッドに結び付いた被参照の行数。`EXACT` と `INHERITED` を数え、
`IMPLICIT_CTOR` は数えない）を足し、`externalRefs > 0` なら `inHierarchy=1`・`absentCause` 空にする。
`role` は変えない（`ISOLATED` はソースの辺だけで決める。`ISOLATED` かつ `externalRefs>=1` が「ソースからは呼ばれないが jar からは
呼ばれる」の読み方。[external-usage.md](external-usage.md) の「methods.csv との対応」）。

**列は最後に足す**: `methods.csv` の列番号で読む利用者（Excel の列・`cut -d, -f`）と検査を壊さないため。`call-hierarchy.csv` は
最終列が可変長なので `call-hierarchy` の左に足す決まり（`CONTRIBUTING.md` の 6 節）で、`methods.csv` は逆になる。
この決まりは `CONTRIBUTING.md` の 4 節のチェックリストに書いた。

結び付かなかった参照（jar の中の呼び出しがソースのどのメソッドにも当たらない。版のずれが典型）は、以前は
`Topic.DEPENDENCIES` の候補だったが、その項目の対処（jar を集める）は版ずれには合わないので `Topic.INCOMPLETE` に載せる。

検査の限界: test/demo で jar から参照される 9 メソッドはどれもソースからも呼ばれるので、`inHierarchy` が `0→1` に変わる形は
期待値には無い（README とコードで保証）。jar からだけ呼ばれるメソッドを題材に足すと `cacheversion` の記録も変わるので、この回では見送った。

## Q5. 「対処の要る状態」と「経過」の線引き

`CONTRIBUTING.md` の 6 節の決まり（`Log.warn` が 1 行でも出た実行では warnings.txt ができる。経過を `Log.warn` で出さない）に
照らして、次を warnings.txt に昇格した。

| 状態 | 以前 | 今 |
|---|---|---|
| `entry.packages` を書いたのに起点が 0 件 | run.log の `[INFO]` | `Topic.CONFIG`（`exporter.noEntries`） |
| `external.library.folders` にクラスフォルダ（`.class` はあるが jar が無い） | 黙って 0 件 | `Topic.CONFIG`（`external.classFolderNotScanned`） |
| jar からの被参照で結び付かなかった参照 | 3 行の `[INFO]` | `Topic.INCOMPLETE` の 1 行（件数と対処） |
| `max.depth` で降りなかった呼び出し | 行ごとの注記だけ | `Topic.INCOMPLETE` に件数。注記 `[UNEXPANDED:DEPTH]` は `outDegree>0` の行にだけ |
| `max.rows` で止めた | run.log と warnings.txt | 加えて CSV の最後に印の行 1 行（[output-walk-limit-qa.md](output-walk-limit-qa.md) の Q5） |
| 設定ファイルの知らない項目 | 黙って無視 | `Topic.CONFIG`（Q9） |
| ビルドファイルの読めない宣言（版の無い依存・無いカタログ・無い `files()`） | 黙って落とす | `Topic.DEPENDENCIES`（`config.deps.declarationProblems`）と明細 |
| Maven の有効でないプロファイルの `<dependencies>` | 黙って読まない | `Topic.DEPENDENCIES`（POM ごとに 1 回） |
| run.log に書けなくなった | 黙って捨てる | 標準出力に `[WARN]` を 1 回、以後は標準出力だけ |

run.log の注記にとどめたもの: Gradle の未ビルドの `project(':core')`・`settings.gradle` が project.root の外にある
（Maven の `reactorNotBuilt` と同じ扱い。ビルドすれば直るので「対処」ではなく「状態」）。

途中まで書いた `call-hierarchy.csv` を失敗時に残さないよう、`call-hierarchy.csv.tmp` に書いて `finish()` で改名する
（`CallHierarchyCsvWriter`）。失敗した実行の出力フォルダに本物の名前のファイルが残ると、途中までの結果を完全だと読んでしまう。

## Q6. record の暗黙の正準コンストラクタを、どこまで合成するか（形式 v46）

record は、正準コンストラクタを書かなければコンパイラが合成する。以前は「コンストラクタを 1 つも書いていない record」にだけ
D 行（宣言）を合成していたので、正準でないコンストラクタだけ書いた `record Only(int x) { Only() { this(Sink.defaultX()); } }` では
`this(...)` の相手が無く、正準コンストラクタの呼び出しが落ちていた。

**結論**: 書かれたコンストラクタのバインディングの鍵を集め、`tb.isRecord()` でバインディング上のコンストラクタのうち書かれていない
ものを合成する（`TypeContextTracker#synthesizeImplicitCanonicalConstructor`）。合成の中身は暗黙のコンストラクタと同じ
（`synthesizeConstructor` に共通化）。**事実が変わる（D 行が増える）ので `CacheFormat.VERSION` を `jche-cache-v46` に上げた。**
検査は `test/jls` の 8.10.4（4 行）。回帰テストの題材には現れない。

同じ回の書き手のほかの変更（呼び出しごとの正規表現の組み立てをやめる、親型の求め直しのメモ化、`BlockWriter` の二重計上）は
事実を変えない。`test/cacheversion` の記録で `incremental` / `jls` の指紋が v46 の記録と一致することで確かめた
（`demo` の指紋だけは題材の追加で変わる）。

## Q7. コンストラクタと `<clinit>` を継承で結び付けないのはなぜか

jar からの被参照で、`new eu.CSub()`（jar の `CSub` は引数なしコンストラクタを持たない版違い）を解決すると、
`resolvedDeclaration` が親クラスの連鎖を辿って `CBase.CBase()` に `INHERITED` で結び付けていた。JVMS 5.4.3.3 では
`<init>` と `<clinit>` は継承されないので、これは嘘の行で、しかも呼び出し側の `IMPLICIT_CTOR` の分岐に届かなかった。

**結論**: `MethodSelection#resolvedDeclaration` の入口で、シグネチャが `<init>` / `<clinit>` なら所有者の型でだけ引く
（`isInitializerSignature`）。`lookupRef` ではなく解決の入口に置いたので、どの呼び出し元でも同じ規則になる。
検査は `test/pruning` の `Client.newSub -> CSub.CSub()（IMPLICIT_CTOR）`。

同じ回に、`MethodSelection#search` が先頭の型（`i == 0`）でも private を飛ばしていた件を直し（呼び出し先そのもの、または
private でないものだけ採る）、親インターフェースの段を `mostSpecificInterfaceDeclaration` に共通化した。
実装を探す順の写しは `MethodSelection#search` / `MethodSelection#resolvedDeclaration` / `ImplicitCalls#findNoArgMethod` の 3 つで、
`ExternalUsageScanner` は自前の写し（`inheritedFrom`）を持たない。

## Q8. Gradle の lockfile は、いつ使うか

`gradle.lockfile` があると以前は常に build.gradle の宣言より優先していたが、lockfile に `compileClasspath` / `runtimeClasspath` の
行が無い（別の構成だけ固定している・`empty=` だけ）とき、依存が空になって jar が 1 つも集まらなかった。

**結論**: クラスパスの構成の行（または `empty=` にその構成名）を読めたときだけ lockfile を「見つかった」とし、
それ以外は build.gradle の宣言を使って `Topic.DEPENDENCIES` に載せる（`config.gradle.lockfileNoClasspath`）。
宣言を捨てて空になるより、宣言を使うほうが安全側。検査は `test/warnings` の `lockempty` / `locked`。

同じ回に、POM の `${env.X}` は展開しない（値が run.log / warnings.txt に載って CI の成果物に残る）、`pom.xml` の文字コードは
XML として読む（正規表現で注釈やプロファイルの中に当たらない）、`1.0.0-SNAPSHOT < 1.0` にした（`Versions#tokens` が
修飾子の直前の `0` を捨てる）。

## Q9. 設定ファイルの知らない項目をどう扱うか

結論は [config-file-format-qa.md](config-file-format-qa.md) の Q11。ここでは取り込み時の判断だけ残す。

- `Config.KNOWN_KEYS` は `config/jche.properties` に並ぶ項目と同じ 31 項目。改称（`contracts.*` → `call.rules.*`）と
  `workspace.projects` / `workspace.scope` は、取り込み先（1f46f73）に合わせて取り込み時に足した。
  **設定の項目を足したら `KNOWN_KEYS` と `config/jche.properties` の両方に足す**（4 節のチェックリスト）
- `plugin.*` は拡張が読むので警告しない。拡張（`resolver.candidate.providers` / `call.rules.providers`）を書いた設定では、
  綴りの近い項目があるものだけ警告する（拡張の独自項目に警告を出さないため）
- 警告は `Warnings.begin` の後でないと warnings.txt に載らないので、`Config` の構築ではなく `CallHierarchyExporter#runOne` /
  `Server#analyze` が `warnUnknownKeys()` を呼ぶ

却下した案: *知らない項目をエラーにする*（古い設定ファイルで実行が止まる。警告で十分に気づける）。

## Q10. 1 ファイル版のキャッシュの版に `-single` を付けるのはなぜか

1 ファイル版（`single-file/`）は本体と同期を取らない場合がある（[single-file-qa.md](single-file-qa.md) の Q8）が、
キャッシュの版の文字列が本体と同じ `jche-cache-v45` だと、同じキャッシュのフォルダを本体と 1 ファイル版で使ったとき
互換とみなして読んでしまう。事実の作り方が違えば「静かに違う結果」になる。

**結論**: 1 ファイル版の `VERSION` は `jche-cache-v45-single`。本体の v46 と混ざらず、1 ファイル版同士では互換のまま。
1 ファイル版の README に本体との差（改称未反映・`workspace.projects` 無し・基にしたコミット）を具体的に書き、
`test/single-file/run.sh` が本体との差のコミット数を表示する（合否ではなく案内）。

## Q11. プラグインの設定の適用範囲と、拡張を動かす前の確認

- **設定の適用範囲**: VSCode の `jche.javaHome` / `jche.libFolder` / `jche.vmArguments` は、リポジトリの `.vscode/settings.json` から
  任意のパスを実行されないよう `scope: machine` にした。`jche.configFile` / `jche.autoAnalyze` は信頼していないワークスペースでは
  読まない（`capabilities.untrustedWorkspaces`）
- **拡張の確認**: `plugin.folders` / `resolver.candidate.providers` / `call.rules.providers` は、解析対象のリポジトリに置かれた Java を
  利用者の PC でコンパイルして実行する項目。リポジトリを開いただけでそれが走らないよう、**自動で拾った設定ファイル**がこれらを
  書いていたら、解析の前に一度だけ確かめる（VSCode: `session.ts` の `allowedToRunExtensions`。Eclipse: `ProjectAnalysis#extensionsAllowed`）。
  答えはフォルダ／プロジェクトごとに覚え、「設定ファイルを選ぶ」で問い直す。利用者が明示したファイル（`jche.configFile` /
  Eclipse の解析の設定ダイアログ）は問わない。信頼していないワークスペースでは問わずに断る。設定ファイルの読み方は本体の
  `ConfigFile` と同じ規則（注釈・行末の `\`・字下げの続き・BOM）を両プラグインに写した
- Eclipse の答えの置き場所は、プロジェクト単位の設定（`ProjectScope`）ではなくプラグイン自身の `IPreferenceStore` の
  `analysis.extensionsAllowed.<project>`。`ProjectScope` は Eclipse 4.17 の下限の classpath（`test/plugin-api`）に無い
- **JDK の取得**は、リダイレクト先も含めて `https://` 以外を拒む

GitHub Actions の `config` 入力も同じ理由で、fork からの `pull_request` では信頼していないリポジトリの `jche.properties` を
そのまま使わない（[github-actions.md](github-actions.md) の「セキュリティ上の注意」）。smoke ワークフローは `permissions: contents: read`、
`concurrency`、`timeout-minutes` を付け、キャッシュの鍵は `//DEPS` と `//JAVA` の行だけから作る。

## Q12. CSV の数式をどう防ぐか

`=` `+` `-` `@` で始まるセル（条件の式 `= full`、注記、識別子）を Excel が数式として評価する。
**結論**: `Csv#esc` で先頭が `= + - @` のセルに `'` を前置し、引用符で囲む（`"'= full"`）。読み手は `'` を見て文字列と分かり、
Excel はそのまま文字列として表示する。回帰テストの題材には該当するセルが無く（`call-conditions.csv` の `= full` だけ。`test/conditions` の
期待を直した）、キャッシュには入らない（読み手だけの変更）。

却下した案: *タブや空白を前置する*（grep と期待値の比較で見えない差になる）。*数式を評価しない読み手に合わせて何もしない*
（Excel で開く人が多い出力なので、安全側に倒す）。

## Q13. そのほかの判断

- **module-info.java で JDT が止まったとき**、`Batch#alone` が `List.of()` の不変リストに `removeAll` して
  `UnsupportedOperationException` になり、1 ファイルの失敗で済むはずが設定ごと失敗していた。`withContext` でないバッチでも
  可変リストにする。再現は module-info.java の深い入れ子の注釈値で `StackOverflowError` を起こす形（`test/warnings` の `deepmod`。
  この環境では module-info.java が型を解決できないので、JDT の静かな中断を module-info.java で決定的に起こす形は作れなかった）
- **`BlockWriter#failed`** は、ブロックを書いた後の受け手の失敗で `parsed` と `failed` の両方に数えていた。書き終えたブロックは
  数え直さないが、警告とパッケージの不透明化は残す（安全側）
- **型階層の組み立て**は集合で重複を O(1) に判定し、同じ型の 2 つの宣言の種別・パッケージ・注釈は綴りの小さいほうを採る
- **jar からの被参照**は、自分の型の判定をバイナリ名の索引（`$` と `.` の違いを吸収）にし、`code_length` の不正・負の `skip`・
  切れた入れ子 jar を `IOException` で読み飛ばす。`lambda$new$N` は `<init>`、`lambda$static$N` は `<clinit>` の呼び出し元にする
- **拡張のクラスローダ**は使い終えたら閉じ（`PluginClassLoaders#close`）、拡張の `LinkageError` でも止まらない。
  解析サーバーの待ち行列は無制限にして `CANCEL` を必ず読む
- **Server の `normalizePath`** は文字列ではなく `Path` で比べる（Windows の大文字小文字）。ワークスペースの他プロジェクト配下の
  絶対パスを相手の綴りにする処理（1f46f73）はそのまま、同じ `Path` の比較で行う（取り込み時の判断）
- **Q 番号だけの参照**には docs のファイル名と見出しの語を添え、結論を 1 文で書く（`CONTRIBUTING.md` の 6 節）

## Q14. 検査の題材をどう足したか

- `test/regression` に `nodi`（`spring.di.enabled=false`）・`diannot`（`spring.di.bean.annotations=Audited`）・
  `nobuiltin`（`call.rules.builtin=false`）を足した。どれも whole と同じ題材で、期待値の差がその設定の分だけであることを
  取り込み時に確かめた（nodi: fx.di の `SPRING_DI` の行が CHA の候補に戻る。diannot: `Billing.bill` が `AuditedLedger` に絞られる。
  nobuiltin: `Thread#start` / `Iterable#forEach` の呼び戻しと同梱の入口が消え、自前の規則だけ効く）
- nobuiltin の「自前の規則が効く」は、ラムダの合成メソッドの行が出なくなった（[lambda-collapse-qa.md](lambda-collapse-qa.md)）ので、
  `Dispatcher#submit` に渡したラムダの本体の呼び出しが `Jobs.custom` の直下に出ることと、ログの
  `custom rows 2/2 … / bundled 0 row(s)` で見る
- `test/gradle-demo` に Kotlin DSL のサブプロジェクト `kts/`（`build.gradle.kts`）を足し、`util` の依存はそこだけが宣言する
  （`.kts` を読めなければ jar が集まらず、期待値との比較で落ちる）。`settings.gradle.kts` は、設定ファイルがビルドのルートに
  1 つしか置けず、Groovy 側の検査を失うので足していない
- `.gitignore` の `bin/` `lib/` を `/bin/` `/lib/` `/vscode-plugin/lib/` `/test/*/*/bin/` に限定した（題材のパッケージ名に
  `lib` を使えない制約を外す。追跡済みのファイルは変わらない）
- `test/cli/run.sh` は、SIGKILL された前回の `launcher.properties` の退避が残っていれば開発者の本物として残す
  （起動コマンドが専用のルートを受け取る口を持たないので、退避・復元の方式のまま堅くした）

取り込み時の順序: C → D → E → F → B → G の順に `--no-ff` で取り込み、衝突は両側の意図を残して解消した（`MessagesEn.java` の
キーの追加と言い回し、`Server#normalizePath`、`vscode-plugin/src/session.ts`、`test/regression/run.sh`）。
取り込みのたびに lint（JDK 25、`-Xlint:all -Werror`）を通し、最後に全検査を回した。
