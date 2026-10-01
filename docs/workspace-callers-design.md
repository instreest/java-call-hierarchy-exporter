# ワークスペースの他プロジェクトからの被参照 — 調査と機能仕様の案

> **状態: 設計案（未実装）。** 「被参照メソッドの検索に、jar の代わりに Eclipse のワークスペース内の他のプロジェクトを
> 対象にできないか」の調査と、機能仕様の検討の記録。Eclipse の Java 検索（ワークスペースを対象にメソッドの参照箇所を
> 探す）と呼び出し階層（`Ctrl+Alt+H`。ワークスペース全体から呼び出し元を辿る）を真似て、
> **呼び出し元を他のプロジェクトの起点まで詳しく辿れるようにする**のが狙い。
> 今の被参照の仕組みは [external-usage.md](external-usage.md)、別プロセスの解析とプラグインは
> [out-of-process-analysis-design.md](out-of-process-analysis-design.md) にある。

---

## 0. 結論（要約）

- **今の被参照（`external.library.folders`）は jar の class ファイルの命令列を読む独立した機能**で、出るのは
  「jar のどのクラスのどのメソッドの何行目から参照しているか」の **1 段（`depth` = 1）だけ**である。キャッシュにも
  メモリ上のグラフにも入らないので、その先（相手の jar の中の呼び出し元の呼び出し元 … 起点）は辿れず、
  Eclipse / VSCode のビューにも出ない（[eclipse-plugin-ui-qa.md](eclipse-plugin-ui-qa.md) の Q2）
- **解析の単位は `project.root` 1 つ**で、他のプロジェクトは「依存 jar / クラスフォルダ」としてしか見えない。
  `.classpath` の他プロジェクト参照（`/OtherProject`）は読み飛ばし、Eclipse プラグインは依存プロジェクトの出力フォルダ
  （`bin`）を `library.jars` に渡す。つまり **他のプロジェクトは「呼び出し先」としては解決できるが「呼び出し元」としては
  一切出ない**
- Eclipse の「ワークスペースを対象にした検索」に相当するものをこのツールで実現するには、**参照元のプロジェクトの
  ソースも同じ解析に入れる**のが本線である。そうすれば他のプロジェクトからの呼び出しは普通の呼び出しの辺になり、
  呼び出し階層・具象クラスの解決・DI の解決・差分更新・ビューのすべてがそのまま効く（jar の被参照のように別の仕組みを
  積み重ねない）
- **コードを変えなくても、プロジェクトが 1 つの親フォルダの下にあれば今日できる**（2 節。マルチモジュールと同じ形）。
  ただし設定を手で書く必要があり、無関係な階層まで出る・プロジェクトの `.classpath` を読まない、などの制約がある
- 提案する機能は 3 段で、本線は段階 1:
  - **段階 0**（小）: `external.library.folders` にクラスフォルダ（Eclipse の `bin`、`target/classes`）も受け付ける。
    jar に固めなくても今の被参照（1 段・行番号つき）が出る
  - **段階 1**（中〜大）: 設定 `workspace.projects` で参照元のプロジェクトを並べると、そのソースも一緒に解析し、
    `call-hierarchy.csv` の呼び出し階層が他のプロジェクトの起点まで伸びる。既定では「`project.root` のメソッドに届く
    経路」だけを出す（`workspace.scope=callers`）。`methods.csv` に `project` 列を足す
  - **段階 2**（中）: Eclipse / VSCode プラグインが、対象プロジェクトを参照しているワークスペースのプロジェクトを
    集めて `workspace.projects` を自動生成し、ビューの木が他のプロジェクトへ伸びる
- プロジェクトごとにクラスパスを分けて別々に解析し、グラフを結合する形（モデル B。3.1 節）は、
  依存 jar の版の衝突が実際に問題になるまで採らない

---

## 1. 現状の整理

### 1.1 jar からの被参照（`external.library.folders`）が何をしているか

| 項目 | 今の実装 |
|---|---|
| 入力 | jar / war / ear（FatJar の中の jar も）。`jche.external.ExternalUsageScanner#collectJars` がフォルダ配下の jar を集める |
| 読み方 | class ファイルの定数プールと命令列（`ClassFileRefs`）。`invoke*` と `invokedynamic`（ラムダ・メソッド参照）から参照先と行番号を拾う |
| 照合 | 参照先が **自分のソースの型**（H 行の型名 `graph.hierarchy().typeNames()`）なら、JVM の解決（`MethodSelection#resolvedDeclaration`）で宣言に結び付ける。自プロジェクトの class が混ざった jar は読み飛ばす |
| 出力 | `call-hierarchy.csv` に直接書く（`CallHierarchyCsvWriter#writeExternalUsageRow`）。`caller` はスタックトレース形式、`root` は jar 名、`depth` は 1、`resolved-by` は `EXTERNAL_USAGE:…`、`call-hierarchy` の末尾は `external-ref:EXACT / INHERITED / IMPLICIT_CTOR` |
| 持たないもの | キャッシュ（毎回 jar を読む）、メモリ上のグラフ（ビューに出ない）、相手側の呼び出し階層（jar の中の呼び出し元の呼び出し元は辿らない） |

「jar の中の呼び出し元の呼び出し元」を命令列から辿る拡張も理屈では書ける（相手の jar の全メソッドの呼び出しグラフを
命令列から組む）が、それは **ソース解析器をもう 1 つ（バイトコード版で）作る**ことで、具象クラスの解決・DI・
データフロー・契約表をすべて二重に持つことになる。相手のソースがワークスペースにあるなら、ソースを解析するほうが
同じ結果を 1 つの仕組みで得られる。

### 1.2 解析の単位が `project.root` 1 つであること

| 場所 | 今の決まり |
|---|---|
| `source.folders` / `library.folders` / `external.library.folders` | `project.root` からの相対で、**配下しか指定できない**（`Config#resolveAllUnderProject`） |
| `.classpath` の `kind="src"` で `/` 始まり（他プロジェクトへの参照） | 読み飛ばす（`ProjectLayout#readDotClasspath`。`config.layout.skipOtherProject`） |
| `.classpath` を読む場所 | `project.root` 直下だけ。ソースフォルダの中の別の `.classpath` は読まない |
| ビルドファイル（pom / gradle） | **ソースフォルダごと**に上位へ辿って読む（`BuildFileClasspath#candidateDirs`）。マルチモジュールはモジュールごとに読み、兄弟は `target/classes` とその pom で解決する（`MavenReactor`） |
| キャッシュの置き場所 | `project.root` のフォルダ名と絶対パスのハッシュで 1 つ（`Config#cacheDirOf`）。ヘッダ行にソースフォルダの一覧（`folders=`。相対パス）を持ち、足す・外すだけなら使い続ける |
| キャッシュ・出力の `file` 列 | `project.root` からの相対パス（`CacheFormat#fileRow`、`methods.csv` の `file`） |
| 出力・キャッシュのフォルダ名 | `project.root` のフォルダ名（`Config#projectNameOf`） |
| 解析サーバー（`--server`） | `ANALYZE` 1 回 = 設定ファイル 1 つ。`AT` のパスは「相対か、`project.root` 配下の絶対パス」 |
| Eclipse プラグイン | `IProject` ごとに `ProjectAnalysis` と子プロセスを 1 つ（`AnalysisService#byProject`）。設定は `EclipseProjectConfig` が `IJavaProject` から生成し、依存プロジェクト（`CPE_PROJECT`）は**出力フォルダを `library.jars` に**入れる。ソースが変わった印（⚠）はそのプロジェクトの資源変更だけを見る |
| VSCode プラグイン | ワークスペースフォルダごとにセッション 1 つ |

マルチモジュール（`test/maven-multi`。`core` と `app`）は **「1 つの `project.root` の下に複数のモジュールのソースフォルダを
並べる」** 形で既に扱えていて、`app` から `core` への呼び出しは普通の辺になる。ワークスペースの複数プロジェクトも
構造は同じ（別々のビルド単位のソースを 1 つの解析に入れる）なので、この延長で考えるのが自然である。

### 1.3 Eclipse の機能との対応

| Eclipse | 範囲 | このツールの今 | 足りないもの |
|---|---|---|---|
| 検索 → Java 検索 → 参照（ワークスペース） | ワークスペースの全プロジェクトから、そのメソッド（と上書き）の参照箇所を列挙 | `external.library.folders`（相手が jar のときだけ・1 段） | ソースのままの他プロジェクトを対象にできない |
| 呼び出し階層（`Ctrl+Alt+H`。ワークスペース） | 全プロジェクトを横断して呼び出し元を根まで展開 | `project.root` の中だけ | 他プロジェクトの呼び出し元と、その起点 |
| 依存プロジェクト（ビルド・パスの「プロジェクト」） | 参照先の型をソースから解決 | 出力フォルダ（`bin`）を class として解決 | 呼び出し元としては出ない |

Eclipse の検索は JDT のワークスペース索引（プロジェクトごとのクラスパスを持つ）を使う。このツールは Eclipse の外の
別プロセスで JDT をスタンドアロンで動かすので、その索引は使えない。真似るべきは「索引」ではなく
**「ワークスペースを 1 つの解析の範囲にする」という考え方**である。

Eclipse の検索とは結果の意味も少し違う。Eclipse の「参照」は宣言への**静的な参照**（指定で上書きも含める）を列挙する。
このツールは呼び出しを**実際に動く本体（具象クラス）に解決**して並べ、インターフェース・DI・ファクトリ越しの
呼び出しも実装まで辿る。他プロジェクトを入れても、この違いはそのまま（Eclipse より多くの経路が出る）。

---

## 2. コードを変えずに今できること（回避策）

### 2.1 共通の親フォルダを `project.root` にする

Eclipse の標準の配置（ワークスペースのフォルダ直下にプロジェクトを置く）なら、ワークスペースのフォルダを
`project.root` にし、各プロジェクトのソースフォルダを並べれば、**今の版でも他プロジェクトからの呼び出し元が普通の辺として
出る**（`test/maven-multi` と同じ形）。

```properties
# 自分（app-core）と、それを呼んでいる app-batch / app-web が C:\work\ws の直下にある場合
project.root=C:\work\ws
source.folders=app-core/src/main/java,app-batch/src/main/java,app-web/src/main/java
# library.folders を空欄にすると、ソースフォルダごとに上位の pom.xml / build.gradle を読んで依存 jar を集める。
# Eclipse だけで管理しているプロジェクトなら、各プロジェクトの .classpath は読まないので library.jars に 1 件ずつ書く
library.folders=
entry.packages=
```

得られるもの:

- `app-batch` の `NightJob.run` → `app-core` の `OrderService.findOrder` が通常の行で出て、`root` は `app-batch` の起点になる
- 具象クラスの解決（CHA・データフロー・Spring の DI）が**プロジェクトを跨いで**効く。`app-batch` の `@Configuration` が
  `app-core` のインターフェースに実装を注入する形も解決できる
- 差分更新・`warnings.txt`・jar の被参照（`external.library.folders`）もそのまま
- Eclipse プラグインでも、この設定ファイルをビューの［解析に使う設定…］で選べば使える。木の行のファイルは
  `ANALYZE` の応答の `root` と相対パスから絶対パスに戻して開く（`EditorOpener#open` → `getFileForLocation`）ので、
  ワークスペースのどのプロジェクトのファイルでも開ける

制約（この回避策が設計案の出発点になる理由）:

| 制約 | 中身 |
|---|---|
| 配置 | 全プロジェクトが **1 つの親フォルダの配下**にないと書けない（別の場所にインポートしたプロジェクト・別ドライブは不可） |
| プロジェクトの `.classpath` | `project.root` 直下の `.classpath` しか読まないので、Eclipse だけで管理したプロジェクト（pom / gradle の無いもの）の依存 jar は `library.jars` に手で書く |
| 名前 | 出力フォルダ名・キャッシュのフォルダ名が親フォルダ名（`ws`）になる |
| 出力の量 | 全体モード（`entry.packages` 空欄）では、他のプロジェクトの「自分に届かない」階層もすべて起点として出る。欲しいのは「自分のメソッドに届く経路」だけなのに、他プロジェクトの全体の階層が混ざる |
| 同じ型の二重宣言 | 同じ完全修飾名の型が 2 つのプロジェクトにあると（同じライブラリのコピー、同じプロジェクトの別ブランチ）、`graph.duplicateType` の警告が出て、どちらが読まれるかが並びで決まる |
| プラグイン | `IProject` 1 つから設定を生成する仕組みなので、自動生成では作れない。⚠（解析後に変わったファイル）はビューで選んだプロジェクトの変更しか見ない |
| ビルドの単位 | 依存 jar は全プロジェクトのクラスパスの**和集合**を 1 つにして JDT に渡す。同じライブラリの版が違えば先のものが勝つ（マルチモジュールでも同じ） |

### 2.2 他のプロジェクトを jar にして `external.library.folders` に置く

相手の `bin` / `target/classes` を `jar` コマンドで固めれば、今の被参照（1 段・行番号つき。Eclipse は既定で行番号情報を
付けてコンパイルする）が出る。固める手間が要り、1 段で止まる。段階 0（3.6 節）はこの手間を無くすだけのもの。

---

## 3. 機能仕様の案

### 3.1 方針: 「ワークスペース」を解析の単位の拡張として扱う

2 つのモデルを比べた。

| | モデル A: 1 つの解析に複数プロジェクトのソースを入れる（採用） | モデル B: プロジェクトごとに別々に解析してグラフを結合する |
|---|---|---|
| JDT の環境 | 1 つ（ソースパスは全プロジェクト、クラスパスは和集合） | プロジェクトごと（Eclipse と同じく、クラスパスを分けられる） |
| キャッシュ | 1 ファイル（今のまま。ソースフォルダが増えるだけ） | プロジェクトごとに 1 ファイル。読み手が複数のキャッシュを突き合わせる層を新しく持つ |
| 型の同一性 | JDT が 1 つの世界として解決する（同じ型は 1 つ） | 完全修飾名で結合する。プロジェクトごとに解決した事実（候補・継承・値グラフ）が食い違いうる |
| 既存の仕組み | 解析・差分更新・バッチ・選択・DI・契約表・サーバー・ビューがそのまま効く | 結合の層の分だけ新しいコードと検査が要る |
| 弱点 | 依存 jar の版の衝突（和集合）。プロジェクト間で同じ型を二重に宣言できない | 大きい。プロジェクト間の呼び出しの辺を結合時に作り直す必要がある |
| 前例 | マルチモジュール（`test/maven-multi`） | 無い |

**モデル A を採る。** マルチモジュールと同じ構造で、足すのは「`project.root` の外のプロジェクトも
ソースフォルダの供給元にする」ことと「出す範囲の絞り方」である。版の衝突は、実際に問題になった題材が出てから
モデル B（または衝突する jar だけを別環境で読む形）を検討する。

### 3.2 設定

```properties
# --- ワークスペースの他のプロジェクト ---------------------------------
# project.root のメソッドを呼んでいる、ワークスペースの他のプロジェクト（カンマ区切り）。
# そのソースも一緒に解析し、呼び出し階層をそのプロジェクトの起点まで伸ばす。
# 値はプロジェクトのフォルダ（この設定ファイルのフォルダからの相対パス、または絶対パス。project.root の外でよい）か、
# そのプロジェクトの設定ファイル（source.folders / library.* / source.encoding をそこから読む）。
# フォルダなら project.root と同じ決め方で中身を見る（.classpath の kind="src" / "lib"、pom.xml / build.gradle、
# 無ければ src/main/java → src）。
workspace.projects=

# 他のプロジェクトのメソッドをどこまで出すか。
#   callers … project.root のメソッドに届く経路だけ（既定）。他のプロジェクトの、自分に関係ない階層は出さない
#   all     … すべて出す（project.root とまったく同じ扱い。ソースフォルダを並べたのと同じ）
workspace.scope=callers
```

- 相対パスの起点は `project.root` と同じく**設定ファイルのフォルダ**で、配下の制限は掛けない（プロジェクトの外を指すのが
  普通なので。`library.jars` と同じ理由）
- 値が設定ファイルなら、そのファイルの `project.root` / `source.folders` / `library.folders` / `library.jars` /
  `source.encoding` だけを使う（`entry.packages` や出力の項目は読まない）。Eclipse プラグインが各プロジェクトに
  保存した `config/jche.properties` をそのまま指せる
- `.classpath` の `/OtherProject` 参照は、`project.root` と `workspace.projects` の**フォルダ名**で引けるなら
  そのプロジェクトに結び付ける（ソースで解決するので読み飛ばさなくてよい）。引けなければ今までどおり警告して読み飛ばす
- 項目名は `external.*`（jar の被参照）と分けた。jar の被参照は「ソースの無い相手」、`workspace.*` は「ソースのある相手」で、
  出るものの形（1 段の `external-ref` 行か、通常の呼び出し階層か）が違う

### 3.3 解析（フェーズ1）

- **ソースフォルダの並び**: `project.root` のフォルダを先に、次に `workspace.projects` を設定に書いた順に、各プロジェクトの
  中は今の決め方の順。出力の行順はこの並びで決まる（環境に依存しない。[deterministic-row-order-qa.md](deterministic-row-order-qa.md)）
- **パスの形**: キャッシュ（F 行・ヘッダ行の `folders=`）と出力（`methods.csv` の `file`、サーバーの R 行の `file`）の
  パスは `project.root` からの相対のまま、外のプロジェクトは `..` を含む形にする（`../app-batch/src/main/java/...`）。
  相対にできない（Windows の別ドライブ）ときだけ絶対パス。ラベルを付ける形（`app-batch:src/...`）は、F 行の読み手・
  `AT` のパスの照合・プラグインの `EditorOpener` の 3 か所の約束を変えるので採らない。`..` なら「`root` と連結して
  正規化する」今の読み方のまま通る
- **キャッシュ**: 1 ファイルのまま。`workspace.projects` を足す・外すのはソースフォルダを足す・外すのと同じなので、
  ヘッダ行の `folders=` の今の規則（並びが同じで入れ子が無ければ再利用）がそのまま効く。パスの形が増えるので
  `CacheFormat.VERSION` を上げる（`test/cacheversion/run.sh --update`）
- **クラスパス**: 各プロジェクトのクラスパスの和集合。並びは `project.root` → 各プロジェクトの順で、重複は先のものを残す。
  同じライブラリの版が違えば先のものが勝ち、その旨をログに出す（マルチモジュールと同じ限界）。
  **解析に入れたプロジェクトの出力フォルダ**（`app-core` の `bin` が `app-batch` の `.classpath` に入っている形）は、
  ソースを正とするのでクラスパスから外す。同じ型がソースにもクラスフォルダにもあるときに JDT がどちらを採るかは
  `MavenReactor` が兄弟の `target/classes` を足す今の形と同じ状況なので、その扱いにそろえる（実装時に確かめる項目）
- **同じ型の二重宣言**: 今の `graph.duplicateType` の警告がそのまま効く。ワークスペースでは起きやすい（同じ共通ライブラリの
  コピー、同じプロジェクトの別ブランチ）ので、警告の文言に「`workspace.projects` から片方を外す」を足す
- **文字コード・準拠レベル**: プロジェクトごとに違いうる。JDT に渡す環境は 1 つなので、`source.encoding` は
  **ソースフォルダごと**に渡し（`ASTParser#setEnvironment` の `encodings` は配列）、準拠レベルは最も高いものを採る
  （新しい文法を許すだけで結果は変わらない）
- **バッチ**: 事実をバッチに依らせない決まり（`CallEdgeExtractor#analyzeBatch`）は変えない。プロジェクトを跨ぐ型の参照も
  I 行（参照した型）で追えるので、差分更新はそのまま

### 3.4 選択と出力（フェーズ2・3）

- **起点**: 全体モード（`entry.packages` 空欄）の起点は「呼び出し元の無いメソッド」のまま。`app-batch` からしか呼ばれない
  `app-core` のメソッドは起点でなくなり、その行は `app-batch` の起点から始まる階層の中に出る。これが「他のプロジェクトの
  起点まで追える」の中身である
- **`workspace.scope=callers`（既定）**: 他のプロジェクトのメソッドは、**そこから `project.root` のメソッドに届く経路の上に
  あるときだけ**出す。実装は、`project.root` の全メソッドを根にした**呼び出し元の向きの到達集合**を 1 回求め
  （`CallResolver#reachableFrom` の逆向き。ビューの絞り込みと同じ考え方。[eclipse-plugin-ui-qa.md](eclipse-plugin-ui-qa.md) の Q4）、
  `StreamingTreeWalker` が他プロジェクトのノードへ降りるときに集合に無ければ降りない、起点の選択（`EntryPoints`）で
  集合に無い他プロジェクトの起点を落とす、の 2 か所で使う。経路の上の全ノードは定義上集合に入るので、
  届く経路は途中で切れない。**解決（CHA・データフロー・DI）には一切使わない**（絞るのは出す行だけ。解決は全体で行う）
- **`workspace.scope=all`**: 絞らない。2.1 の回避策と同じ結果
- **`call-hierarchy.csv`**: 列は変えない。他プロジェクトの行も通常の行（`caller` はスタックトレース形式、`root` は起点）。
  どのプロジェクトの行かは `methods.csv` の `project` 列で引く。`root` の左に `project` 列を足す案は、
  回帰テストの期待値がすべて変わるので、必要になってから別に判断する（[call-hierarchy-columns-qa.md](call-hierarchy-columns-qa.md)）
- **`methods.csv`**: 末尾に `project` 列（`project.root` のフォルダ名、または `workspace.projects` のフォルダ名）を足す。
  `file` は 3.3 の形。`scope=callers` のとき他プロジェクトのメソッドは到達集合にあるものだけを載せ、
  `inHierarchy` / `absentCause` の意味は変えない
- **jar の被参照（`external.library.folders`）**: 「自分の型」は解析した全ソースの型のままにする。`app-batch` の jar が
  `dist` に混ざっていても、そのクラスは解析したソースの型なので「他リポジトリからの被参照」としては数えない（今の自己除外と同じ）
- **`warnings.txt`**: 依存 jar の不足・コンパイルエラーの一覧はファイルのパス単位なので、`..` の形で載るだけ

### 3.5 解析サーバーとプラグイン

- **サーバー**（`--server`）: `ANALYZE` は設定ファイルを受けるだけなので変えない。`AT` / `FIELDAT` の「`project.root` 配下の
  絶対パス」を「解析したどのソースフォルダの配下でも」に広げる。R 行の `file` は 3.3 の形で、プラグインは今までどおり
  `root` と連結して開く
- **Eclipse プラグイン**（段階 2）:
  - `EclipseProjectConfig` が、ワークスペースの開いている Java プロジェクトのうち**解決済みクラスパスに対象プロジェクトへの
    `CPE_PROJECT` を持つもの**（推移的に辿って固定点まで。A ← B ← C の C も）を `workspace.projects` に書く。
    `source.encoding` と準拠レベルはプロジェクトごとに取る
  - 対象バーに［参照元のプロジェクトも解析する］のチェック（既定オフ。解析の量と子プロセスのメモリが増えるので、
    利用者が選ぶ）と、オンのときの「参照元 N プロジェクト」の表示。［解析に使う設定…］で一覧が見える
  - ⚠（解析後に変わったファイル）は、`workspace.projects` に入れたプロジェクトの資源変更も見る
    （`AnalysisService#resourceChanged` の振り分けを「そのプロジェクトを解析に含む `ProjectAnalysis` すべて」にする）
  - 「カーソル位置のメソッド」は、ビューで選んだ対象プロジェクトの解析結果から引く。カーソルのファイルが
    他のプロジェクトでも、その解析に含まれていれば引ける（`FIND` はキー、`AT` はパス）
  - ビューの木は変えなくてよい。他プロジェクトの呼び出し元は通常の行として出て、ダブルクリックでそのプロジェクトの
    ファイルが開く（`getFileForLocation` はワークスペースのどのプロジェクトのファイルでも引ける）
- **VSCode プラグイン**: マルチルートのワークスペースの他のフォルダを `workspace.projects` にする設定
  （`jche.workspaceProjects`。既定オフ）。参照関係は Eclipse のように分からないので、利用者が選ぶ
- **メモリ**: 子プロセス 1 つが全プロジェクトのグラフを持つ。大きなワークスペースでは `-Xmx` を上げる案内を設定画面のツールチップに足す

### 3.6 段階 0: クラスフォルダの被参照（小さく先に出せる）

`external.library.folders` に**クラスフォルダ**（`.class` を直接（または配下に）含むフォルダ。Eclipse の `bin`、Maven の
`target/classes`）を書いたとき、jar と同じ走査をする。今は jar しか集めないので、`bin` を指すと黙って 0 件になる。

- `ExternalUsageScanner#collectJars` で、フォルダに jar が無ければ `.class` を探し、あればそのフォルダを 1 つの擬似 jar として
  走査する（jar も `.class` も両方あれば両方）。`root` 列は設定に書いたフォルダのパス（`../app-batch/bin`）
- 自プロジェクトの class の自己除外・`INHERITED` の結び先・行番号（Eclipse の既定は行番号情報つき）は jar と同じ
- 出るのは今までどおり 1 段の `external-ref` 行。ソースを解析に入れたくない（遠い・大きい・ビルドの単位が違う）相手に向く
- 変更は `ExternalUsageScanner` と `config/jche.properties` のコメント、`external-usage.md`、回帰テストに
  クラスフォルダのケース（`test/demo/ext-src` をコンパイルしたフォルダ）を足すだけ

### 3.7 対応しないこと・限界

- プロジェクトごとのクラスパスの分離（モデル B）。版の衝突は先のものが勝つ
- ワークスペースに無い（手元にチェックアウトしていない）プロジェクト。閉じたプロジェクトは `workspace.projects` から外す
- 同じ完全修飾名の型を 2 つのプロジェクトに持つ形。警告して、並びで先のものを読む
- JSP・XML の Bean 定義・SQL のマッピングからの参照（今と同じ。[README の制約](../README.md#分からないこと制約)）
- Eclipse の検索が出す「上書きを含める」「読み取り／書き込み」の指定の再現。このツールは具象クラスへの解決と
  フィールドの呼び出し元で代える

---

## 4. 影響範囲（実装するときに触る場所）

| 層 | 場所 | 変更 |
|---|---|---|
| 設定 | `Config`、`ProjectLayout`、`config/jche.properties` | `workspace.projects` / `workspace.scope` の読み取り。プロジェクトごとの `.classpath` / ビルドファイルの読み（`ProjectLayout` をプロジェクト単位に切り出す）。`/OtherProject` の結び付け |
| 解析 | `CacheFormat`（F 行・`folders=` のパスの形）、`CallEdgeExtractor`（ソースフォルダごとの文字コード）、`ProjectScan` | `VERSION` を上げる。`test/cacheversion/run.sh --update` |
| 選択・出力 | `EntryPoints`、`StreamingTreeWalker`、`InventoryReport`、`CallResolver`（逆向きの到達集合） | `scope=callers` の絞り込み。`methods.csv` の `project` 列 |
| 被参照 | `ExternalUsageScanner` | 段階 0 のクラスフォルダ。自己除外の型の集合は変えない |
| サーバー | `Server#at` / `fieldAt` | パスの受け付けを全ソースフォルダへ |
| Eclipse プラグイン | `EclipseProjectConfig`、`ConfigDialog`、`CallHierarchyView`（対象バー）、`AnalysisService`、`messages*.properties` | 参照元の収集・チェック・⚠ の振り分け。`--release 11` の範囲で書く |
| VSCode プラグイン | `src/config.ts`、`package.json` / `package.nls*.json`、`messages.*.ts` | `jche.workspaceProjects` |
| 文言 | `MessagesEn` / `MessagesJa` | ログ（和集合の版の衝突、`/OtherProject` の結び付け）、警告の文言の追記 |
| 1 ファイル版 | `bash single-file/generate.sh` | 生成し直してコミット |
| README | 日本語・英語の両方 | 「ほかの使い方」に 1 行、出力のリファレンスの `methods.csv` に `project` 列、制約に版の衝突 |
| docs | この文書を実装の記録（`workspace-callers-qa.md`）に書き換える。`external-usage.md` に段階 0 | |

検査:

| 検査 | 足すもの |
|---|---|
| `test/regression` | `workspace` ケース: `test/demo` を `project.root`、`test/demo/ext-src` 相当のソースを別フォルダ（`test/workspace-demo/`）の参照元プロジェクトにして、`scope=callers` と `all` の期待値。既存ケースの期待値は変えない（`methods.csv` の `project` 列だけ全ケースで増える） |
| `test/incremental` | `workspace.projects` を足す・外す・中のファイルを書き換えたときの差分更新が全件解析と同じこと。`..` のパスの F 行の往復 |
| `test/server` | `AT` に他プロジェクトのパス（相対 `..` と絶対）を渡せること |
| `test/plugin-config` | 生成した設定（`workspace.projects` 入り）が同じ読み手で読み戻せること |
| `test/warnings` | 二重宣言の警告の文言。版の衝突のログ |
| `test/nls` / `test/plugin-nls` / `test/vscode` | 文言のキーのそろい |
| `test/readme` | 両言語の節のそろい |
| `test/cacheversion` | 版を上げた記録 |
| `test/single-file` | 生成し直し |

---

## 5. 段階と規模の目安

| 段階 | 内容 | 規模 | 利用者に見えるもの |
|---|---|---|---|
| 0 | `external.library.folders` にクラスフォルダ | 小（`ExternalUsageScanner` に数十行＋検査） | jar に固めずに `bin` から 1 段の被参照（行番号つき） |
| 1 | `workspace.projects` / `workspace.scope`、パスの形、`methods.csv` の `project` 列、サーバーの `AT` | 中〜大（設定・キャッシュの版・出力・検査が一式） | CLI と設定ファイルで、他プロジェクトの起点まで伸びた呼び出し階層 |
| 2 | Eclipse / VSCode プラグインの自動生成とチェック | 中（プラグイン 2 つ） | 設定を書かずにビューの木が他プロジェクトへ伸びる |
| 3 | モデル B（プロジェクトごとのクラスパス） | 大 | 版の衝突がある題材でだけ違いが出る。要るときだけ |

段階 0 は段階 1 と独立していて、先に出せる。段階 1 は 2.1 の回避策で結果の形を先に確かめられる
（`scope=all` の出力は回避策と同じになる約束）。

---

## 6. 迷ったこと（Q&A）

### Q1. jar の被参照（`external.library.folders`）の延長として「ソースも読む被参照」にしないのはなぜか

被参照の行は「1 段・起点無し・`root` は参照元の名前」という独立した形で、呼び出し階層とは意味が違う。
欲しいのは「他のプロジェクトの起点まで辿る」ことで、それは呼び出し階層そのものである。ソースを読んで 1 段だけ出すのは、
解析器をもう 1 つ持つのに結果は 1 段という最悪の組み合わせになる。jar の被参照は「ソースの無い相手」の道具として残し、
ソースのある相手は解析の単位に入れる。

### Q2. `project.root` を複数にする（`project.roots=`）形にしないのはなぜか

`project.root` は相対パスの起点・キャッシュと出力のフォルダ名・`file` 列の起点・サーバーの `AT` の起点で、
「主役」が 1 つであることに多くの約束が乗っている。複数にするとそのすべてに「どの root か」の列が要る。
ワークスペースの用途でも主役は 1 つ（影響調査の対象のプロジェクト）で、他のプロジェクトは
「そこへ届く呼び出し元」として見たいので、主役を `project.root` のまま、参照元を `workspace.projects` に分けるほうが
意味も実装も素直である。`scope=callers` の既定もこの非対称から自然に出る。

### Q3. `workspace.scope` の既定を `callers` にするのはなぜか

Eclipse の参照検索・呼び出し階層は「このメソッドに届く経路」だけを見せる。全体モードで他プロジェクトの階層を全部出すと、
`app-batch` の自分に関係ない全起点の階層が CSV に混ざり、影響調査の答えが薄まる。`all` も残すのは、
「ワークスペースをまとめて 1 つとして棚卸しする」用途（2.1 の回避策で既にできること）を塞がないため。

### Q4. 絞り込みは「呼び出しを静かに落とす」にならないか

ならない。落とすのは「他のプロジェクトのメソッドで、そこから `project.root` のどのメソッドにも届かないもの」だけで、
それは定義上 `project.root` の影響調査の答えに含まれない。経路の上のノードはすべて到達集合に入るので、届く経路は
途中で切れない。解決（CHA・データフロー・DI・契約表）には集合を使わず、全体で行う。絞るのは出す行だけである。

### Q5. `file` 列を `..` を含む相対パスにし、ラベル付き（`app-batch:src/...`）にしないのはなぜか

F 行の読み手・`AT` のパスの照合・プラグインの `EditorOpener` は「`project.root` と連結して正規化する」の 1 つの約束で動いている。
`..` はこの約束のまま通る。ラベルは 3 か所に新しい文法を持ち込み、1 ファイル版や VSCode 側にも同じ読み手が要る。
別ドライブで相対にできないときだけ絶対パスにするのは、Eclipse プラグインの生成する設定が元から絶対パスなのと同じ割り切り。

### Q6. 推移的な参照元（A ← B ← C）はどうするか

`scope=callers` のとき、C の起点から B を経て A に届く経路は答えの一部なので、C も入れる。Eclipse プラグインは
`CPE_PROJECT` を固定点まで辿って集める。CLI では利用者が `workspace.projects` に並べる（並べなければ B の起点で止まるだけで、
B までの経路は出る）。

### Q7. なぜモデル B（プロジェクトごとの解析）を先に選ばなかったか

Eclipse と同じクラスパスの分離ができるのは魅力だが、プロジェクトを跨ぐ辺・継承・値グラフを**結合する層**を新しく作ることになり、
差分更新・バッチ・選択の健全性の検査をその層にも持たなければならない。マルチモジュールの前例（和集合のクラスパス）で
実務の多くは足りる見込みなので、版の衝突が実際に問題になった題材が出てから、衝突する jar だけを別に読む形を含めて検討する。
