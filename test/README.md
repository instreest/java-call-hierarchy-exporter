# test/ の案内

このフォルダには 3 種類のものが同じ階層に並んでいる。

- **検査** … `run.sh` を持つフォルダ。ツールを動かして合否を出す。CI（`.github/workflows/smoke.yml`）はこれらを順に回す
- **題材** … 検査が解析対象にする小さな Java プロジェクト。動くプログラムとしての意味は無い。各フォルダの `README.md` に何を踏む題材かがある
- **道具** … 合否ではなく計測のためのもの（`profile/`）

どの検査も bash から動かす。前提は共通で、初回は JBang が JDK 25 と JDT の jar を取得する（数百 MB。
`bash jbangw/jbang jdk install 25`）。シェルは UTF-8 ロケール（`LANG=C.UTF-8`）で動かす。
ツールを動かす検査は `JCHE_LANG=en` を輸出して文言の言語を固定している。

## まず回すもの

| 順 | コマンド | 何を守るか |
|---|---|---|
| 1 | lint（下の「全ソースの lint」） | 警告ゼロでコンパイルできること。**JDK 25 の javac** で |
| 2 | `bash test/regression/run.sh` | 出力 CSV が期待値と一致すること |
| 3 | `bash test/incremental/run.sh` | 差分更新が全件解析と同じ結果になること（時間がかかる） |
| 4 | 触った場所に対応する検査（下の表の「触ったら回す」） | |

## 検査の一覧

| フォルダ | 目的（1 文） | 触ったら回す | 追加の前提 | CI のジョブ |
|---|---|---|---|---|
| `regression/` | `test/demo` 等を解析して出力 CSV を `expected*/` と比べる回帰テスト | `src/jche` のどこでも | — | regression（Windows 版 `run.cmd` は regression-windows） |
| `incremental/` | ソース・jar を書き換えたあとの差分更新が、キャッシュを消してからの全件解析と一致すること | `analysis` / `cache` | — | regression |
| `cacheversion/` | 事実が変わったのに `CacheFormat.VERSION` を上げ忘れていないこと（`facts.txt` と比べる） | `analysis` / `cache` | — | regression |
| `cachevalue/` | キャッシュの列の符号化の往復とハッシュ | `cache` | — | regression |
| `dataflow/` | 解決の決定性（処理順に依らない）と値の表の決まり | `graph` | — | regression |
| `pruning/` | 値の読み違いで呼び出しを黙って落とさないこと | `analysis`（値）/ `graph`（解決） | — | regression |
| `conditions/` | `conditions.target` の `call-conditions.csv` | `CallConditionScanner` / `report` | — | regression |
| `ctorbody/` | コンストラクタ本体（JLS 8.8.7）と暗黙の `super()` の読み取り | `analysis` | — | regression |
| `jls/` | JLS SE 26 の節ごとの期待値と javac 26 のバイトコードとの突き合わせ | `analysis` / `graph` | JDK 26（jbang が取得） | regression |
| `warnings/` | `warnings.txt` の有無と中身（正常時は作らない・対処が載る） | `Warnings` / `Log.warn` を出す場所 | — | regression |
| `contracts/` | 同梱の契約表（`JdkCallbacks` / `BundledFrameworkEntries`）が parse でき JDK に実在すること | 契約表 | — | regression |
| `single-file/` | 1 ファイル版がビルドでき（本体と同じ lint）、起動できること。本体との一致は見ない | `single-file/` | — | regression |
| `readme/` | README の日本語側と英語側の節の並び・形がそろい、アンカーが解決し、JDT の版が `//DEPS` と同じこと | `README.md`・docs から README へのリンク | — | jbangw |
| `cli/` | 起動コマンドと対話モード（メニューへの答えをパイプで流す） | `cli` / `.sh` | — | regression |
| `nls/` | 文言（英語が既定・日本語を重ねる）のキーの一致と、CSV が言語で変わらないこと | `Messages*` / 文言を足した場所 | — | regression |
| `server/` | サーバーモード（`--server`）のプロトコル | `server` | — | regression |
| `pom/` | `//DEPS` と `pom.xml` の依存の一致 | JDT の版 | — | pom |
| `jbangw/` | `jbangw/` が本家から黙って変わっていないこと | `jbangw/` | — | jbangw |
| `action/` | 複合アクションが依存 jar の警告を注釈とサマリに出すこと（jbang はスタブ） | `action.yml` / `.github/action/` | — | jbangw（action ジョブは複合アクションそのものを `uses: ./` で動かす） |
| `plugin/` | Eclipse プラグインの定義（`plugin.xml` 等）がそろっていること | `eclipse-plugin/` | — | eclipse-plugin |
| `plugin-api/` | Eclipse プラグインが下限の Eclipse 4.17 の jar と `--release 11` でコンパイルできること | `eclipse-plugin/` | Maven | eclipse-plugin |
| `plugin-config/` | Eclipse プラグインが自動生成した設定を解析側が同じ読み方で読み戻せること | `EclipseProjectConfig` / `ConfigFile` | — | eclipse-plugin |
| `plugin-nls/` | Eclipse プラグインの文言のキーの一致と切り替え | `eclipse-plugin/` の文言 | — | eclipse-plugin |
| `plugin-client/` | Eclipse プラグインのクライアント層（`jche.eclipse.server`）が子プロセスとやりとりできること | `eclipse-plugin/…/server` | — | eclipse-plugin |
| `vscode/` | VSCode プラグインの `vscode` に触らない層（型検査・子プロセス・木・文言） | `vscode-plugin/` | Node 22・npm | vscode-plugin |
| `vscode/package.sh` | VSCode プラグインの配布物（`.vsix`）の中身 | `vscode-plugin/` の配布 | Node 22・Maven・JDK 21 以上 | vscode-plugin |

`plugin*` のフォルダは **Eclipse プラグイン**の検査で、利用者が Java で書く拡張（`plugin.folders`）の検査ではない。
拡張の検査は `regression/plugin` ケース（題材は `plugin-demo/`）にある。

Windows 版の回帰テスト（`regression/run.cmd`。CI の regression-windows）が回すのはケースの一部
（whole / entry / novalues / maven / mavenmulti / gradle / jarchange / plugin / multi）だけで、残りのケースは `run.sh` でしか見ない。

## 題材の一覧

| フォルダ | 何の題材か | 使う検査 |
|---|---|---|
| `demo/` | 解決経路をひととおり踏む小さなプロジェクト（多実装・ファクトリ・DI・リフレクション・ラムダ・生成される実装・意図的なコンパイルエラー） | `regression`（whole / entry / novalues / jarchange / cacheblocks）・`dataflow`・`cacheversion`・CI の action ジョブ |
| `maven-demo/` | `pom.xml` から依存 jar を集める Maven プロジェクト | `regression/maven` |
| `maven-multi/` | マルチモジュールの Maven プロジェクト（兄弟モジュール・親の `dependencyManagement`） | `regression/mavenmulti` |
| `gradle-demo/` | マルチプロジェクトの Gradle ビルド（Buildship の `.classpath` も） | `regression/gradle` |
| `localrepo/` | 上の 3 つが依存 jar と POM を探すローカルリポジトリ（`library.repositories`） | `regression/maven` / `mavenmulti` / `gradle` |
| `localrepo-src/` | `localrepo/` の jar のソース（jar を作り直すときの元） | — |
| `plugin-demo/` | ソースを読むだけでは具象クラスが決まらない呼び出しだけを集めたプロジェクト（拡張・契約表の題材） | `regression/plugin`・`dataflow` |
| `jls/project/` | JLS の 1 節につき 1 ソース（パッケージ名が節番号） | `jls`・`dataflow`・`cacheversion` |
| `incremental/src/` | 差分更新の検査が書き換える題材 | `incremental`・`cacheversion` |
| `regression/values/project/` | 値が `\| ; { }` を含む題材 | `regression/values`・`dataflow` |

`ctorbody` / `pruning` / `warnings` は題材をその場で作って捨てる（`ctorbody` は Java 25 でしか書けない構文を含むため、`demo/` には置かない）。`conditions` と `cli` は `demo/` を対象にする。

## 道具

| フォルダ | 何をするか |
|---|---|
| `profile/` | 性能を測る（CI では動かさない）。測り方と結果の読み方は `docs/ast-analysis-performance-qa.md` の「計測のしかた」。解析結果が持ち続けるヒープは `RetainedHeap.java` |

## 検査の決まり

- 出力 CSV の期待値（`regression/*/expected*/`）を更新するときは、差分を確認したうえで最新の `output/*/` からコピーする。理由なく期待値を書き換えて通さない
- テストをスキップ・無効化して通すことはしない
- lint は **JDK 25 の javac** で走らせる。古い JDK では通ってしまう検査がある（`dangling-doc-comments`＝どの宣言にも付いていない javadoc は JDK 22 で入った。JDK 21 では警告が出ず、CI の `regression` と `vscode-plugin`（`eclipse-plugin/pom.xml` の `-Xlint:all -Werror` 経由）でだけ落ちる）。手元に無ければ `bash jbangw/jbang jdk install 25` で入れて `PATH` の先頭に置く: `export PATH=$(bash jbangw/jbang jdk home 25)/bin:$PATH`
- ツールを動かす検査スクリプトは `JCHE_LANG=en` を輸出して言語を固定する。既定の経路をそのまま検査でき、実行環境のロケールで照合する文字列が変わらなくなる。日本語への切り替えそのものは `nls/run.sh` が見る
- テストのシェルは UTF-8 ロケールで動かす（`LANG=C.UTF-8`）。ロケール未設定の環境では launcher.properties の日本語書き込みで落ちるうえ、日本語を選んだ実行の出力も扱うため

## 各検査が何を見ているか（詳細）

各 `run.sh` の冒頭のコメントにも同じ説明がある。ここには「何を守っているか」を検査ごとにまとめる。

### `bash test/regression/run.sh`

回帰テスト。`test/demo` 等を解析して `expected*/` の CSV と比較。キャッシュ再利用・値を読まない指定（`novalues`。`dataflow.enabled=false`）・jar 増減・Maven / Gradle・プラグイン・キャッシュのブロックの整合（`cacheblocks`。1 ブロックの中身・F 行の件数（未解決数）の書き換えでは、全件ではなく、そのファイルとその型を使うファイルと、型解決に失敗しているファイル（必ず解析し直す。Q131）だけを解析し直す（3 回目は壊れたブロックのパッケージを中身の分からないパッケージにするので同じパッケージの 1 件も足して 4 件、8 回目は 3 件。Q138）。最終行のブロック数の書き換え・最終行の削除・先頭 8 KB より後ろの文字化け・先頭の行（T 行）の書き換えでは丸ごと作り直し、解析は失敗させない。どの実行のあとも検査値とブロック数が合う。型解決できなかった件数が再利用・一部の解析し直しでも変わらない）・複数設定の各ケース

### `bash test/dataflow/run.sh`

解決の決定性と値の表の検査。`test/demo` の全エッジを 3 通りの順で `CallResolver.resolve` して結果が一致すること（ResolveOrderCheck）。値の表を組む側を手で書き換えた行でたたく（StoreUnitCheck）。回帰テストの題材を解析して、組み上がった値の表が読み手の前提にしている決まり（子 < 親・項目の並び・葉と文字列の一意・頭は葉・型名が `:` を含まない など）を守ること（ValueStoreCheck）

### `bash test/conditions/run.sh`

`conditions.target` を書いたときに追加で出る `call-conditions.csv` の検査。判定可・判定不可の出し分けと、通常の出力が変わらないこと

### `bash test/incremental/run.sh`

キャッシュの健全性の検査。ソースを書き換えたあとの差分更新の結果が、キャッシュを消してからの全件解析の結果（CSV とキャッシュ）と一致すること（I 行に載らない依存: 無かった型を後から足す・祖父母の型の変更（部分型のパスが親より前に並ぶ場合も）・ラムダの目標の型の親・同じパッケージの型による import の隠蔽・宣言にだけ書いた型を消す・式の型にだけ現れる型のメンバーや親の変更・祖父母の型のメソッドを可変長引数にする・拡張 for 文の式の型を Iterable にする・switch のセレクタの列挙型に定数を足す・例外の型を検査例外にする・引数やローカル変数のアノテーションの型を消す・見えなかった型を public にする・同じパッケージの型を jar に足す・sealed の permits に部分型を足す・親に親の親のメンバーを隠す私的メンバーを足す・型解決に失敗している利用者の参照した型の親に私的メンバーを足す・`java.*` の親型の上のメンバー（`Map.Entry`・`AbstractMap.SimpleEntry`）を隠す私的な入れ子の型を足す／消す・親のメソッドの本体だけを変える・名前の当たらない私的メンバーを親に足す、も含む。親が変われば部分型をすべて変わった型にするので、これらは件数ではなく全件解析との一致だけを見る。型解決に失敗していたブロックは名前を照合せず必ず解析し直し、無かった型を足したときは同じパッケージのブロックだけを足して解析し直すこと、無名クラスだけを足しても全件にならないこと、入れ子の型を足す・親を付け替える（型階層が変わる）と残りをすべて解析し直すこと（安全網）、Doma の `@Dao` だけのプロジェクトでエラー数が 0 で差分更新が全件にならないことは件数まで見る（`docs/cache-unification-qa.md` の Q131）。選ばれなかったオーバーロードの引数の型を消す（new・継承・単純名・static import・super）・ラムダを渡す呼び出しの候補の関数型インターフェースの形を変える。依存 jar が無いとき、式に書いた完全修飾名の事実がバッチの組み方に依らないこと・完全修飾名の途中のパッケージに型を足す／消すと解析し直すこと。同じパッケージに足した型が中身の変わっていないファイルの宣言の型（引数・フィールド・戻り値）を隠す・jar の型の親や親の親（別の jar）にメソッドを足す・型引数にだけ現れる型の親を変える・jar のパッケージと同じ名前の型をソースに足す、も全件解析と同じこと。単純名から作られた無い型の名前が `$` や補助文字を含んでもバッチの組み方に依らず、別パッケージの宣言と実装で同じ名前になること・無い入れ子の型（`Template.Inner`）を無名パッケージの本物の入れ子の型と取り違えないこと（H 行の親は `?.` 付き）。`docs/cache-unification-qa.md` の Q42〜Q47・Q50〜Q55・Q65・Q77〜Q80・Q83〜Q87）。別のファイルから private でないフィールドへ書く側のファイルを足す・書き換える・消しても全件解析と同じで、DI の結論が書き手に合わせて変わること（`docs/spring-di-qa.md` の Q15）。文字コードの変更・形式の版や JDT の版が違う・途中で切れた・読めないキャッシュでは再利用せず捨てること。1 ブロックの中身だけが壊れていれば（検査値が合わない）全件ではなくそのファイル（と依存するファイル）だけを解析し直すこと。中断した実行から引き継ぐこと（検査値の合わないブロックは引き継がない）。以前の形式が残した `dataflow-cache.tsv` を消すこと。キャッシュの行の並びと記号・値グラフの番号の検査。同じキャッシュのフォルダを使う実行が錠で 1 つずつになること（待つ・待ちきれずに失敗する・2 つを同時に始めても両方が全件解析と同じ）と、書き終えていないキャッシュからグラフを組まないこと（`tools/`）。解析のあいだに書き換えて戻したソースを次の実行で解析し直すこと。同じクラスが 2 つのソースフォルダにあっても差分更新が全件解析と同じで、アノテーションの付いた `package-info.java` が 2 つのソースフォルダにあっても同じこと、ソースフォルダの並びを入れ替えたら再利用しないこと、並びを変えずにフォルダを足す・外すなら再利用して全件解析と同じになること（入れ子のフォルダを足したら再利用しない）、同じ名前のファイルの組の片方を消す・そのフォルダを外すと残ったほうを解析し直すこと、ソースフォルダを足して JDT がクラスパスを受け付けなくなったら旧キャッシュを使わないこと、名前に `\` を含むフォルダを入れ子のフォルダと取り違えず、旧キャッシュも中断した実行の一時ファイルも使わないこと（Linux でだけ見る）、受け手の中でスタックが溢れたファイルも失敗として数えること（`docs/cache-unification-qa.md` の Q56〜Q58・Q61・Q64・Q66・Q67・Q69・Q71・Q73・Q75）。次も全件解析と同じこと: 暗黙の `super()` の呼び出し先の throws と候補の引数の型・継承したメソッドの戻り値と throws・関数型・親型の型引数・型引数の上限・内部クラスの囲む型の型引数・深く入れ子になった型引数・拡張 for の要素・レコードの成分・候補の関数型と throws にだけ現れる型の親を変える（jar の形も）、sealed の許した部分型（入れ子も）・`@Repeatable` の入れ物・`@Target` や `java.*` のアノテーションを隠す型・対になっていないサロゲートだけが違う定数、中間のクラスが親をやめる／持つ・親の親や親インターフェースの親だけを変えて継承した実装（H 行の 8 列目）が変わる・try-with-resources の資源の型の中間のクラスに `close()` を足す、メンバーを持ち込む import（static オンデマンド・入れ子の型）の jar の型とその親の jar・オンデマンド import したパッケージの唯一の下のパッケージを消す・`package-info.java` の import を隠す型・型と同じ名前のパッケージ（jar も）ができる／無くなる・jar の無名パッケージのクラス・jar や解析に失敗するファイルが自分のパッケージに足した型が完全修飾名の頭を隠す・解析に失敗するファイルを書き換える／消す、jar の変化とソースの型・オンデマンド import のパッケージ・コンパイルエラーのファイルの変化が重なる、ソースフォルダ・パッケージのフォルダ・クラスフォルダがシンボリックリンク・クラスフォルダの `.java`（`.class` との新しさの入れ替わりも）・jmod・jar の同じ名前の 2 つのエントリの順・`library.jars` と `.classpath` の `kind="lib"` のクラスフォルダ。JDT に一緒に渡すファイルの組み方（バッチ）に依らないこと（依存 jar が無いときの単純名から作られた無い型の名前（`?.Template`。無名パッケージの本物の型と重ならない）・後ろのファイルの型を先に解決させない・注釈の型のエラーを 1 回だけ数える・注釈の既定値が jar の無いクラスに当たる・依存 jar に無いクラスでの JDT の打ち切り（原因の型を完全修飾名で書いた形、同じ原因で多くのファイルが止まっても止まるのは 1 回）・名前の違うファイルで宣言した record とその後ろの型・jar のクラスが参照するソースの入れ子の型（後から足すも）・ソースのモジュールの import）。解析のあいだにソースを消して戻す・足して消す、依存 jar・クラスフォルダを書き換えて戻す（パス0 で読めなかった jar が読める中身になる・JDK の前の目次を解き放てない）と、次の実行がその実行で解析したファイルを解析し直すこと（`tools/` の EditDuringRunCheck・ClasspathSwapDuringRunCheck・StaleSharedViewCheck）。どのファイルも渡さないうちに一括解析が溢れたら関係の無いファイルを単独にせず半分に分けること、文言の無い例外で失敗したファイルの理由に例外の名前を添えること（SinkOverflowCheck）（`docs/cache-unification-qa.md` の「v42 の穴探し（形式 v43）」）。入れ子の型の解析し直しが同じ名前のファイルの組を分けないこと、どのファイルも返さないうちに溢れたバッチで別のファイルの溢れを理由に失敗にしないこと、名前でたどれない経路（jar のシグネチャ）で届く名前の違うファイルの型も全件解析と同じこと（同 Q142・Q140）。JDT（`ZipFile`）の読めない壊れた jar（圧縮方式・暗号化の印・コメント長・コメントの UTF-8）を警告して、同じ目次の正しい jar に直すと解析し直すこと（`docs/cache-dependency-jars-qa.md` の Q22）。解析し直す順を旧キャッシュのブロックの順に依らせないこと（戻り値の型が無いパッケージを参照するメソッド・無い型を引数に持つ候補と継承した候補。Q137）。`package-info.java` に宣言した解析に失敗するクラスを変える・消す、同じ実行で消したファイルの壊れたブロックの H 行を信じない、メソッドの型変数の上限にだけ現れる型の親を変える（Q138）。期待値ファイルは持たない

### `bash test/cacheversion/run.sh`

キャッシュの形式の版の上げ忘れの検査。決まった題材（`test/demo`・`test/demo` を依存 jar ありで・`test/incremental`・`test/jls/project`）を全件解析したキャッシュの事実（ブロックと L 行）の指紋を `test/cacheversion/facts.txt` と比べ、版・題材・環境が同じなのに事実が変わっていれば落とす。版を上げたら・題材を変えたら `--update` で記録を更新する

### `bash test/cli/run.sh`

起動コマンドと対話モードの検査。メニューへの答えをパイプで流し込む

### `bash test/ctorbody/run.sh`

コンストラクタ本体の読み取り（JLS 8.8.7）の検査。柔軟なコンストラクタ本体（JEP 513。`this(...)` の前に文を書ける）を「委譲していない」と取り違えないこと。インターフェースとアノテーション型に暗黙のコンストラクタを合成しないこと（JLS 8.8.9。Q25）。暗黙の `super()` と匿名コンストラクタの呼び出し先に javac の選ぶコンストラクタが入ること（最も特殊な可変長引数・見えない引数なしのもの・ジェネリックなコンストラクタ。候補すべてに辺を張るので、javac との突き合わせ（`test/jls`）には置かない）。この構文は Java 25 でしか書けないので `test/demo` には置かず、使い捨てのプロジェクトをその場で作る（`docs/jls-conformance-qa.md` の Q17）

### `bash test/jls/run.sh`

Java 言語仕様（JLS SE 26）への適合と javac との整合の検査。`test/jls/project/src/` の各ソースが JLS の 1 つの節に対応し（パッケージ名が節番号。`jls.s14_14_02` = §14.14.2）、節ごとの期待値（`test/jls/expect.tsv`。1 行 1 テストで節番号と説明を持つ）を出力とキャッシュに当てる。あわせて同じソースを JDK 26 の javac（`--release 26`）でコンパイルし、型・宣言・呼び出し・ラムダ・ブリッジ（O 行か、親クラスから継承した実装なら H 行の継承した実装）をキャッシュの事実と突き合わせる（`test/jls/JlsCheck.java`。JDK 26 は jbang が取得）。実装の探し方（§8.4.8 の親クラスの連鎖が勝つ・§8.4.8.1 の継承した実装・別パッケージのパッケージアクセスのメソッドは継承した実装にしない（§8.4.8。間に別パッケージのクラスが挟まる形も）・§9.4.1 の最も特定的な `default`・§15.12.4.4）と暗黙の `iterator()`・`close()` の宣言の節もある。新しい構文の読み取りを直したら節を足す。突き合わせの解決（`JlsCheck#resolve`）は親インターフェースの段で maximally-specific に絞り、JDT のコンパイル時宣言が上書きされた親インターフェースの宣言になる形（`class C implements I1, I2`）だけを INFO に記録する。キーの同じ上書きが O 行に無いことは `nooverride`（`docs/jls-conformance-test-qa.md` の Q21）

### `bash test/pruning/run.sh`

値の読み違いで呼び出しを黙って落とさないことの検査。値を変えうるキャスト・浮動小数・16 進のリテラル・複合代入と `++`・ループの中で写した変数・型の揃わない `equals`・引数やフィールドへの `new`（`LOCAL_NEW`。`new` した型とそれ以外の型の両方が出ること）・匿名／ローカルクラスのフィールド初期化子・返す具象型の決まらない `@Bean` メソッド（ファクトリ・引数・フィールド・条件演算子。値を読まない指定でも）・フィールドへの書き込み（内部クラス・static な入れ子のクラス・`++` と複合代入・初期化ブロック・初期化子のラムダ・条件の中や `return` の後ろのコンストラクタの書き込み・書き換えた引数）・別のインスタンスのフィールド（`other.dao`。コンストラクタ実引数から来た値や、捕捉した引数を使うラムダ）や値を追えないレシーバ（拡張 for・パターンの変数・配列の要素）・拡張 for の要素（コンストラクタの実引数・`addAll`・渡した先で詰める・別名・再代入・引数のコレクション・`listIterator().add`・`list::add`）・型名で書いたメソッド参照の実引数の位置・リフレクションの `invoke`（親から継承した多重定義・private・static）・DI の段 5（Bean でないクラスの引数とフィールド・利用者が渡した値・ソースが引数でない値を入れるフィールド（別の型（子クラス・内部クラス・ほかのパッケージの型）が private でないフィールドに書くものも）。値を読まない指定でも）・`super.f` への書き込み・フレームワークが書くフィールド（`@Autowired(required = false)` などの注釈の付いたフィールドの初期化子・`@ConfigurationProperties` の型のフィールド）で、`[UNREACHABLE]` や絞り込みが誤って付かないこと（`docs/value-safety-qa.md` の Q17〜Q23・Q25〜Q28、`docs/spring-di-qa.md` の Q15・Q16）。経路で渡された値の宣言の型（具象クラスの型で宣言したフィールド・引数）で候補を絞ること（`DATAFLOW_DECLARED_TYPE`。引数の受け渡し・引数をそのまま返すメソッド・上限を広げるキャスト・コンストラクタ実引数経由でも。インターフェース型のフィールドなら絞らず、上限の部分型が複数なら CHA のまま候補が減り、段 5 の結論が上限と矛盾すれば経路の事実を採る。`docs/declared-type-narrowing-qa.md`）。対になっていないサロゲートの文字列と、絵文字で切れる条件式を解析できること。文字リテラル `'\s'`（ローカル変数・比較・`case`）のファイルを解析でき、その値で打ち切ること。実際に動く実装を JVM の順で選ぶこと（try-with-resources の `close()`・拡張 for の `iterator()`・JDK のインターフェースの型で呼んでも親クラスから継承した実装へ、親クラスの private を飛ばして `default` へ。`default` の戻り値で絞らない: 親クラスの実装・型引数の置換つきの継承した実装・親クラスが jar のクラスのとき・default を抽象として宣言し直した関数型インターフェースにラムダを渡したとき（ラムダの本体へ繋ぎ、追えない受け手でも絞らない）・jar のインターフェースが親のソースのインターフェースの `default` を上書きしているとき）、jar のクラスを経由した部分型（`class JlList extends ArrayList<String>` の `size()` を `List<String>` / `ArrayList<String>` 型の変数で呼ぶ）を CHA の候補に入れること、暗黙の `close()`・`iterator()` を継承されない宣言（境界のクラスの private・別のパッケージの親クラスのパッケージアクセス）につながず、交差型のキャストの拡張 for 文の `iterator()` を記録すること、フレームワークの入口の契約（`super 型#シグネチャ`）が型引数を具体化してシグネチャの食い違う上書き・親クラスから継承した実装にも当たること（`methods.csv` の `role`）、外部の jar からの被参照を JVM が解決する宣言に結びつける（上書きされた親インターフェース・static のインターフェースメソッドにしない。javac がブリッジを作る形＝型引数を具体化した上書き・親クラスから継承した実装は、ブリッジのある型の宣言に）こと。`equals` の条件は `Object#equals` の上書き（JDT の `overrides`）だけを判定し、`equals(String)` の多重定義・インターフェースが宣言し直した `equals(Object)`・2 引数の static な `equals` は判定しないこと。別パッケージの上書きの判定が親クラスの連鎖だけを見ること（同じパッケージのインターフェースを途中の上書きに数えない・同じパッケージの jar のクラスの宣言し直しを経由した上書きを残す。#174）。継承されない static（別パッケージのパッケージアクセス）を default より先に選ばないこと・型引数の置換を挟んだ別パッケージの推移的な上書き（O 行に無い）を採ること（#175）。インターフェースのダイヤモンドの `X.super.m()` / `super.m()` / `super::m` が最も特定的な default に届くこと・jar のインターフェースが宣言し直しうるソースの default の戻り値で絞らないこと・record の暗黙のアクセサと `Enum` の final メソッドが default に負けないこと（#177）。仕組みごと（真偽値・int・文字列 / ボックス型 / 列挙型の `equals`・enum の `switch`・書き換えない別名・`new` だけのローカル変数・コンストラクタで受け取るフィールド・`this.m()` からの引き継ぎ・new した空のリストに `add` した要素・static メソッドの参照・Bean のコンストラクタで受け取るフィールドと `@Autowired` のメソッドの引数・別のインスタンスのフィールドでもどのインスタンスでも同じ値（初期化子の `new`・コンストラクタで入れるラムダ）・フレームワークが書かないフィールドの初期化子（`java.lang` の注釈だけのフィールド・ステレオタイプの Bean の注釈の無いフィールド）・よその型が別のフィールドにだけ書く Bean のフィールド・`Objects.requireNonNull` で包んだコンストラクタ注入・値を読まない指定で型の当たらないフィールドにだけ `new` を入れる Bean）の対照は打ち切られる・絞られること。ケースは `case_` を 1 回呼べば足せる（同じクラスの別の行は `expect_`）。使い捨てのプロジェクトをその場で作る（`docs/value-safety-qa.md`）

### `bash test/warnings/run.sh`

確認してほしいことの案内（出力フォルダの `warnings.txt`）の検査。正常な状態では作らないこと、依存 jar の不足・ローカルリポジトリの欠け・設定の指定先の欠け・コンパイルエラー・実行の失敗で作り該当の項目が載ること、コンパイルエラー・構文エラーのファイルの一覧（上限 20 件）がパスの順で、差分更新でも全件解析と同じこと、`warnings.txt` の有無が `run.log` の `[WARN]` / `[ERROR]` の有無と一致すること。網羅していない switch 式を構文エラーに数えないこと、入れ子の深すぎる式で JDT のスタックが溢れてもそのファイルだけの失敗にすること、名前の違う 2 つのファイルで同じ型を宣言していれば警告すること（2 つの宣言に同じメソッドが無くても。直し方の文言が同じフォルダの 2 つのファイルにも通じること。3 つのファイルでも挙げる組が差分更新と全件解析で同じこと）。パッケージの宣言がフォルダと合わないファイルを全件解析でも差分更新でも同じ行で警告すること、jar のクラスが参照するクラスが無くても一括解析が落ちないこと、1 件ずつの指定（`library.jars`）のフォルダが jar しか持たなければ警告し、jar も入った本当のクラスフォルダなら警告しないこと、案内の相対パスの起点が設定の読み方と合うこと、1 つの JVM で設定を続けて処理しても各設定の `warnings.txt` がその設定だけのときと同じ（拡張の警告・言語）こと、行を書かずに続けて通ったノードも `max.rows` で打ち切って載せ、行を書き進めている探索は止めないこと。1 つの悪いファイル（添えるだけで JDT が溢れる副次クラスの連なり・JDT の `AssertionError`）でほかのファイルを失敗させず、そのファイルだけを理由とともに載せること（全件解析と差分更新。`docs/cache-unification-qa.md` の Q141・Q139）。使い捨てのプロジェクトをその場で作る

### `bash test/cachevalue/run.sh`

キャッシュの列の符号化（`joinRow` の `escape` / `CacheReader` の `unescape`。全列に同じ 1 つの規則）が往復し、行を壊さず、UTF-8 に書けること（対になっていないサロゲートも `\uXXXX` にする）。ヘッダ行のソースフォルダの一覧（`folders=`。`CacheFormat#foldersOf`）も往復すること。文字列のハッシュ（`FileHash.ofText`。長い定数の K 行・自分の宣言の指紋）が、対になっていないサロゲートだけが違う文字列を区別し、正しい文字列では UTF-8 のハッシュと同じこと

### `bash test/single-file/run.sh`

1 ファイル版（`single-file/`）の検査。本体と同じ lint の引数で警告ゼロでコンパイルできること、`--help` が 0・知らないオプションが 2 で終わること。本体と同期を取らない場合があるので、本体との一致は見ない（`docs/single-file-qa.md` の Q8）

### `bash test/readme/run.sh`

README の日本語側と英語側の節の並び・節ごとの形（表の行・コード・箇条・リンク先）がそろうこと、README の中と docs/ から README へのアンカーが解決すること、README の JDT の版が `//DEPS` と同じこと。ファイルの中身を読むだけなので JDK もネットワークも要らない

### `bash test/contracts/run.sh`

同梱の契約表（`JdkCallbacks` / `BundledFrameworkEntries`）の検査。全行が parse でき、JDK の型は宣言元と呼び戻すメソッドが実在すること（実行中の JDK と照合）

### `bash test/pom/run.sh`

`//DEPS` 行と `pom.xml` の依存が一致すること

### `bash test/action/run.sh`

GitHub Actions の複合アクション（`.github/action/run.sh`）が、`run.log` の依存 jar の警告を表示言語（英語・日本語）に関わらず warning アノテーションとジョブサマリに出すこと。jbang はスタブに差し替えて解析は動かさない

### `bash test/jbangw/run.sh`

`jbangw/` が本家から黙って変わっていないこと

### `bash test/plugin-config/run.sh`

Eclipse プラグインが自動生成した設定（`EclipseProjectConfig#toFileText`）が、解析側と同じ読み方（`jche.config.ConfigFile`。バックスラッシュはそのまま）でそのまま読み戻せること。書く側が逃がす・逃がさないで読み手と食い違うと、Windows のパスが壊れる

### `bash test/plugin-api/run.sh`

Eclipse プラグインが下限の Eclipse（4.17 / 2020-09）の jar と `--release 11` でコンパイルできること。本番のビルドは新しい jar を使うので、この検査だけが下限を守る

### `bash test/nls/run.sh`

ツール全体の文言（英語が既定、日本語は重ねる）の検査。`src/` に日本語のリテラルが残っていないこと、英語と日本語でキーと差し込みがそろうこと、起動コマンドの表がそろうこと、言語の決まり方（`JCHE_LANG` > `jche.lang` > `message.language` > OS。1 つの JVM で設定を続けて読んでも、空欄の設定は前の設定の言語を引き継がない）、そして**出力 CSV が言語で変わらないこと**（`docs/nls-qa.md`）

### `bash test/plugin-nls/run.sh`

Eclipse プラグインの文言（英語が既定、日本語は重ねる）の検査。ソースに日本語のリテラルが残っていないこと、キーがそろうこと、`plugin.xml` の `%キー` があること、配布物に入ること、`osgi.nl` で切り替わり UTF-8 として読めること（`docs/eclipse-plugin-nls-qa.md`）

### `bash test/server/run.sh`

サーバーモード（`--server`）のプロトコルの検査。`HELLO` → `ANALYZE` → `FIND` / `AT` → `TREE` → `EXPORT` と断り方。`CANCEL` がそれまでに読んだ `ANALYZE`（まだ始まっていないものも）を止め、後の `ANALYZE` には効かないこと、拡張（`plugin.folders`）を直したら次の `ANALYZE` から効くこと、同じ更新時刻のまま（同じ inode で）上書きした jar を次の `ANALYZE` が読むこと（更新時刻を進めてから元に戻す上書き・シンボリックリンクの別のパスから読む形も）

### `bash test/vscode/run.sh`

VSCode プラグインの `vscode` に触らない層の検査（Node 22 と npm が要る）。型検査、子プロセスとの一連のやりとり、木の組み直し、設定の用意、拡張本体を束ねられること、文言（英語と日本語でキーと差し込みがそろうこと・ソースに日本語が残っていないこと・`package.json` の `%キー%` が `package.nls*.json` とそろうこと）

### `bash test/vscode/package.sh`

VSCode プラグインの配布物（`.vsix`）の検査（上に加えて Maven と JDK 21 以上が要る）。`lib/` が eclipse-plugin のビルドから集まり JDT の版が `//DEPS` と同じこと、入るもの（`package.nls*.json` を含む）・入らないもの

### 全ソースの lint

`javac --release 17 -Xlint:all -Werror -Xdoclint:all,-missing`（smoke.yml の「Compile with all lint warnings as errors」と同じ引数）。**CI と同じ JDK 25 の javac で走らせる**（下記）

### `bash test/plugin/run.sh`

Eclipse プラグイン（`eclipse-plugin/`）の定義のうち、複数の場所に同じことを書いている箇所が食い違っていないこと。ファイルの中身を読むだけなので JDK も Maven も要らない。

### `bash test/plugin-client/run.sh`

Eclipse プラグインのクライアント層（`eclipse-plugin/src-ui/jche/eclipse/server`。子プロセスを起動し、プロトコルで話し、返ってきた行を木に組み直すところ）が Eclipse 無しで動くこと。あわせて `--release 11` でコンパイルできること。
