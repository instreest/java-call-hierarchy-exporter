# コードレビューで見つかった問題への対応 — 実装時の QA 一覧

リポジトリ全体のコードレビュー（規模の整理と改善点の分析）で挙がった指摘のうち、
正確性（A）、性能（B の 1・2）、構造・重複・ドキュメント（E）に対応した。
迷ったこと・結論・却下した案を Q&A の形で残す。

対応の要点:

- CHA の候補に「親クラスから継承した実装」を入れ、「本体の無い再宣言」を数えない（`CallResolver.resolveVirtual`）
- ファクトリの戻り値の解決を、エッジの処理順に依存しない形にした（`DataflowResolver.factoryReturnOrigin`）。
  `dataflow.max.depth` が文字どおり「そのメソッドから数えた委譲の段数」になった
- `CallResolver.resolve` の結果をエッジごとにメモ化し、構築後に不要な索引を捨てる（`CallGraph.finishBuild`）
- Gradle: 高い版が勝ったときに辿り直す、複数行の宣言を 1 行に結合する、`settings.gradle` を project.root の外で
  拾ったときに注記する。pom の `${...}` な `sourceEncoding` を使わない
- 細かい取りこぼし: CHA 候補 20 件超の注記、壊れた class での NPE、Bean 名の順序依存、`plugin-classes` の取り合い
- 大きいクラスの分割（`FactVisitor`、`GradleBuild`、`App`、`Config` のコンストラクタ）と重複の共通化
  （`CacheReader`、`UserHome`、`Names`、`Dependency` / `Exclusion`）
- README から GitHub Actions の詳細とキャッシュ設計を `docs/` へ移し、`docs/README.md` に索引を置いた

## Q1. サブタイプが親クラスから継承した実装を、CHA はどう扱うべきか

`interface I { void m(); }`、`abstract class Base { public void m() {} }`（`I` を実装していない）、
`class C extends Base implements I {}` のとき、`i.m()` で実際に動くのは `Base.m`。
従来の `resolveVirtual` は「`I` のサブタイプ `C` に `C#m()` の宣言があるか」だけを見ていたので候補が 0 件になり、
`実装なし（宣言のまま）` と出ていた。呼び出しが静かに落ちる典型で、このツールの方針に反する。

**結論**: サブタイプごとに `CallGraph.implementationIn(sub, sig)`（本体を持つ宣言まで親を辿る）で「そのサブタイプで
実際に動く実装」を引き、`addIfAbsent` で重複を除く。Spring の判定やデータフローの判定は以前からこの経路を使っていたので、
段1 だけが違っていた不整合も無くなる。

同じ変更で、サブインターフェースが本体なしで再宣言する形（`interface Sub2 extends Base2 { void m(); }`）も直る。
再宣言は候補に数えられていたため、実装が 1 件でも `CHA候補2件` のまま展開が止まっていた。
`implementationIn` は本体を持つ宣言しか返さないので、再宣言は自然に候補から外れる。

`test/demo/src/fx/inherit/` に両方の形を足し、期待出力で固定した。

## Q2. ファクトリの戻り値の解決が、エッジの処理順で変わっていたのはなぜか

`Chain.c1() → c2() → … → c7() → new UserDaoImpl()` の 7 段の委譲を `dataflow.max.depth=5` で解析すると、
期待出力では `Chain.c1().describe()` が `解決:DATAFLOW_FACTORY` になっていた。上限を超えているのに解決できたのは、
別の行の `Chain.c6().describe()` が先に評価されて `c6` / `c7` の結果がメモに入り、その後の `c1` の評価が
「c2 → … → c5 まで 4 段 + c6 のメモ」で上限内に収まったから。従来のコードはこれを意図的に許していた
（「深さ上限が原因の不明はメモに保存せず、次に浅い位置から評価されたときにやり直す」）が、
結果が評価順に依存するので、`methods.csv` の `inDegree`（先に数える）と `call-hierarchy.csv` の注記（後で決まる）が
食い違っていた。`resolve` をメモ化（Q3）すると、最初の評価結果が固定されるので、この順序依存が表に出た。

**結論**: 上限を「呼び出し元から数えた再帰の深さ」ではなく「そのメソッドから数えた委譲の段数」に掛ける。
各メソッドの結果は委譲先の結果と段数だけで決まり、どこから先に評価しても同じになる。
`c1` は 6 段なので不明（CHA で候補 2 件を並べる）、`c2` 以降は解決できる。README の
「委譲は `dataflow.max.depth` 段まで」という説明と一致する。期待出力の該当行は CHA に変わった（候補は落とさない）。

却下した案: 順序依存を残したまま「常に階層展開の後に methods.csv を作る」ように順番を固定する。
出力は揃うが、同じファクトリが別の場所からどう呼ばれているかで解決の可否が変わる不透明さは残る。

## Q3. `CallResolver.resolve` のメモ化でメモリは増えないか

結果は決定的なのに、入次数の集計・到達判定・`methods.csv`・階層の展開で同じエッジを何度も解き直していた。
階層の展開では同じエッジが経路の数だけ現れるので、大規模プロジェクトでは解決だけで時間の大半を使う。

**結論**: エッジごとに `int[]`（候補）と `String`（ラベル）の参照を持つ。候補が 1 件の結果はメソッド ID ごとに
共有した `int[1]` を使い、ラベルは定数か `labelPool` で共有するので、エッジ数ぶんの配列や文字列を新たに抱えない。
CSR 設計（エッジあたり int 数個）は崩さない。

あわせて、構築時にしか使わない `hintsByScope`（メソッドキー＋バインディングキーの長い文字列）と
`originPoolIndex` を `CallGraph.finishBuild` で捨て、同じ証拠のリストを `hintTable` に 2 回載せないようにした。

## Q4. Gradle の「高い版が勝つ」を、幅優先の途中で差し替えてよいか

従来は幅優先で高い版に出会ったときにその場で差し替えていたが、低い版の POM から既に辿った推移的な依存
（キューに積んだもの・処理済みのもの）はそのまま残る。高い版の POM の依存とは違いうる。

**結論**: 高い版に出会ったらその版を「勝った版」として記録し、最初から辿り直す（不動点まで。上限 20 回）。
版は上がる一方で有限なので止まる。Maven（近い方が勝つ）は 1 回で終わる。

## Q5. `settings.gradle` の探索を project.root で打ち切るべきか

レビューでは「ファイルシステムのルートまで探すのは無関係な上位フォルダを拾いうる」と指摘された。
打ち切って回帰テストを回すと gradle ケースが壊れた。project.root にサブプロジェクト（`app/`）を指定し、
`settings.gradle` はその親にある構成は普通にあり、回帰テストがまさにその形だった。

**結論**: Gradle 自身と同じく上位へ探す（打ち切らない）。ただし project.root の外で見つかったときは
注記に残し、意図しない上位フォルダを拾っていれば利用者が気づけるようにする。

## Q6. 複数行にまたがる依存の宣言をどこまで読むか

`implementation(\n  "g:a:v"\n)` のような書き方は、行単位の正規表現では「読めない宣言」になっていた。

**結論**: 走査の前に、丸括弧が閉じていない行を次の行と結合する（`GradleScripts.joinLogicalLines`。文字列の中の括弧は数えない）。
波括弧は結合しない。`implementation("g:a") { version { strictly "1.0" } }` のブロックは宣言そのものではなく、
結合すると 1 行が長くなるだけで得るものが無い（`strictly` の版は読まない。版の無い依存としてローカルの最新版を使う）。

## Q7. 大きいクラスをどう割ったか

- `FactVisitor`（1,058 行）→ 型のスタックと合成メソッド（`TypeContextTracker`）、呼び出し箇所の記録と拡張への引き渡し
  （`CallSiteRecorder`）、フィールド参照の A 行（`FieldAccessRecorder`）を分け、`FactVisitor` は AST の訪問と
  呼び出し元のスタックだけを持つ（621 行）
- `GradleBuild`（744 行）→ テキストの小道具（`GradleScripts`）、ロックファイル（`GradleLockfile`）、
  settings / gradle.properties / 版カタログ（`GradleSettings`）を分けた（477 行）
- `App`（525 行）→ 環境設定画面（`EnvironmentSettingsScreen`）と状態表示（`StatusScreen`）を分けた（264 行）
- `Config` のコンストラクタ → リポジトリ・拡張フォルダ・キャッシュフォルダ・文字コードの決定を private メソッドに出した。
  文字コード名が不正なときは、他の項目と同じく「どの項目か」が分かる例外にした

分割は責務の境界で切り、動きは変えていない（回帰テストの期待出力は Q1・Q2 の分だけが変わった）。

## Q8. 重複していたものをどこにまとめたか

| 重複 | まとめ先 |
|---|---|
| キャッシュの逐次読み（ヘッダを飛ばし、行種別で分岐）5 箇所 | `jche.cache.CacheReader` |
| `~` の展開（`Config` と `ConfigWizard`） | `jche.util.UserHome` |
| 単純名の切り出し、`parseInt` のフォールバック | `jche.util.Names` |
| 再解析の理由を表す定数（`BlockWriter` と `StaleTypes` で別々） | `CacheUpdater.Reason`（enum） |
| Gradle 側が `MavenPom.Dependency` を流用 | `jche.config.Dependency` / `Exclusion`（Maven / Gradle に中立な型） |

## Q9. README から何を docs へ移したか

README は 864 行あり、`call-hierarchy.csv` / `methods.csv` の説明が「出力されるファイル」と「出力ファイル」の
2 か所にあった。GitHub Actions の節（264 行）とキャッシュ設計の節は利用者の大半には要らない。

**結論**: Actions の詳細を `docs/github-actions.md`、キャッシュ設計を `docs/cache-design.md` へ移し、README には
最小の例と要約だけを残した（588 行）。出力ファイルの説明は前半を見本だけにし、列の意味・行順・注記は後半に一本化した。
README 内で行き先の無かったリンク（Spring・コンパイル時生成・リフレクションの節。以前の整理で消えていた）は
対応する docs へ向け直した。`docs/README.md` に全文書の索引を置き、どこからも参照されていなかった 5 本もそこから辿れる。
