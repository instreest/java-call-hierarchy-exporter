# VSCode プラグイン 設計案 — 呼び出し元階層ビュー

Eclipse プラグイン（[eclipse-plugin-usage.md](eclipse-plugin-usage.md)）と同じことを VSCode でもできるようにする、
という話の設計案。**まだ実装していない**（この文書は着手前の設計であり、決めきれていない点は §11 に残してある）。

前提として、解析はすでに**別プロセス・標準入出力のプロトコル**に切り出されている
（[out-of-process-analysis-design.md](out-of-process-analysis-design.md)、`jche.server.Protocol`）。
Eclipse プラグインは「子プロセスを起こして問い合わせ、返ってきた行を描くだけ」になっている。
つまり **VSCode 版で新しく書くのは画面と子プロセスの世話だけ**で、解析側はほぼ手を入れない。
この文書は、その「ほぼ」が何かと、Eclipse との違いから来る設計判断をまとめる。

**主ユースケース**は Eclipse 版と同じ。エディタでメソッドにカーソルを置き、そのメソッドの**呼び出し元**を階層で辿る。
呼び出し先方向と CSV 出力はその副産物として扱う。

---

## 1. 全体像

```
VSCode 拡張ホスト（Node.js / TypeScript）        子プロセス（JDK 25・同梱の JDT）
┌──────────────────────────────────┐        ┌────────────────────────────────┐
│ ツリービュー（呼び出し階層 (Exporter)）│  行指向 │ jche.CallHierarchyExporter --server │
│  ・カーソル位置 → メソッドの特定     │ ◀────▶ │  フェーズ1 解析 → キャッシュ     │
│  ・設定ファイルの用意（自動生成）     │ 標準入出力 │  フェーズ2 グラフ＋転置索引     │
│  ・フィルタ UI・CSV 出力の指示       │        │  フィルタ適用・木の切り出し      │
│  ・子プロセスの起動/監視/中止        │        │  CSV 出力                       │
└──────────────────────────────────┘        └────────────────────────────────┘
```

**拡張側に Java のコードは1行も置かない。** Eclipse 版は「Java 8 で書いたバンドル」だったが、
VSCode の拡張ホストは Node.js なので、そもそも Java を動かす場所がない。
これは制約ではなく好都合で、Eclipse 版で苦労した「プラグイン側から解析コードを追い出す」作業が最初から済んでいる。

使うプロトコルは**既存のものそのまま**（`HELLO` / `ANALYZE` / `STATUS` / `FIND` / `TREE` / `EXPORT` / `CANCEL` / `SHUTDOWN`）。
足すのは §4 の `AT` 1つだけで、既存の行の意味は変えないのでプロトコル版は 1 のまま据え置く
（**実装済み**。`jche.server.Server#at`）。

### 置き場所

このリポジトリに**同居させる**（別リポジトリにしない。§11）。`lib/` の版合わせと `AT` の追従を優先する。

```
vscode-plugin/
├── package.json          … 拡張の宣言（コマンド・ビュー・設定）
├── src/
│   ├── extension.ts      … 有効化・コマンド登録
│   ├── server/           … プロトコルクライアント（Eclipse の jche.eclipse.server と対になる）
│   │   ├── connection.ts … 1要求1応答の直列化、#P / #L の振り分け
│   │   ├── launcher.ts   … 子プロセスの起動・アイドル終了・異常終了からの作り直し
│   │   ├── javaLocator.ts… 解析に使う JDK を探す
│   │   └── jdkDownload.ts… 無ければ Adoptium から取得（確認のうえ）
│   ├── config.ts         … config.properties の探索と自動生成（§3）
│   ├── tree.ts           … TreeDataProvider（行の組み直し）
│   └── view.ts           … ビュー本体・フィルタ・状態表示
└── lib/                  … ビルド時に集める。jche-core.jar と jdt/*.jar（§9）
```

`src/server/` の4ファイルは Eclipse 版の `jche/eclipse/server/`（`ServerConnection` / `ServerLauncher` /
`JavaLocator` / `JdkDownload`、合わせて約 700 行）の移植である。**同じ規則を2つの言語で書くことになる**ので、
食い違うと「Eclipse では動くが VSCode では動かない」が起きる。これは §10 の検査で見張る。

---

## 2. なぜ既存の Call Hierarchy に相乗りしないのか

VSCode には標準の「呼び出し階層」ビュー（`vscode.CallHierarchyProvider`）があり、
Java では [vscode-java](https://github.com/redhat-developer/vscode-java)（JDT LS）が提供している。
そこへ相乗りする案は**採らない**。理由は Eclipse 版 §9 と同じで、加えて VSCode 固有のものがある。

| 案 | 採らない理由 |
|---|---|
| `CallHierarchyProvider` を java に登録する | 同じ言語に複数のプロバイダが登録されると、VSCode はどれを使うか利用者に選ばせない。vscode-java と奪い合いになり、「どちらの結果を見ているのか」が分からなくなる |
| 同上（続き） | このツールの結果は**重くて古くなる**。標準ビューには「12 ファイルが変更されています」「この枝は確度が低い（dataflow で推定）」を出す場所が無い。状態を言えないまま古い結果を見せるのが一番危ない |
| CSV を書いて読み直して表示する | フィルタのたびにファイル入出力が要る。CSV は成果物であって UI のデータ源にしない（Eclipse 版と同じ判断） |

したがって**独立したツリービューを1つ増やす**。vscode-java が入っていても入っていなくても同じように動く
（入っていれば §4 の精度が上がる、という関係にする）。

---

## 2.5 VSCode の作法との折り合い

「裏で一括解析して、その結果を見せる」は VSCode 的に無作法ではないか、という懸念への答え。
**やり方の細部を守れば無作法ではない**、というのが結論である。

### 前例は多い

重い一括解析を持つ拡張はむしろ多数派である。

- **vscode-java（JDT LS）自身**が、開いた直後に "Importing projects…" でワークスペース全体をビルドし、
  結果をメモリに持ち続ける。やっていることはこの設計とほぼ同じ
- rust-analyzer / clangd / C# Dev Kit も同様（全体を索引し、古くなったら作り直す）
- Test Explorer・CodeQL・カバレッジ系は「重い処理を明示的に走らせ、結果をツリーで見せる」そのもの

VSCode の作法は「軽くなければならない」ではなく、**「重さを隠さず、利用者の許可のもとでやる」**である。

### 無作法とされるのはどこか

| 嫌われる作り | この設計での扱い |
|---|---|
| 起動しただけで重い処理が始まる | `activationEvents` はコマンドとビューだけ。`onLanguage:java` では起こさない（§8） |
| UI をブロックする・モーダルな進捗 | 出さない。`withProgress` の `Window` / ビュー上の細い進捗のみ（§5） |
| 数百 MB を黙って落とす | 必ず一度確認する（§8）。初回の案内は `contributes.walkthroughs` に載せる |
| 状態を独自の場所に出す | **下の1点目で直した** |
| 標準機能と同名の別物を置く | **下の2点目で直した** |

### 1. 状態は Language Status Item に出す

「解析済み／古い／未解析」を `TreeView#message` だけに書くのは自前の作法である。
VSCode にはこの用途の標準の置き場所がある（`vscode.languages.createLanguageStatusItem`。
エディタ右下の `{}` に出る、vscode-java の "Java: Ready" と同じ場所）。

```
影響調査: 10:31:04 時点（12 ファイル変更）  [再解析]
```

- Java のファイルを開いているときだけ出る。ビューを閉じていても状態が見える
- `severity` で ⚠ / ✖ を表す。`command` に［再解析］を付ける
- `TreeView#message` は**補助**に降格し、絞り込み中の件数など「そのビュー固有の話」だけを書く

### 2. 「言語機能」ではなく「解析ツール」として名乗る

これが本質的な答えである。標準の呼び出し階層（`Shift+Alt+H`）と張り合う位置に置くと
「同じものの劣化版が2つある」に見える。Test Explorer や CodeQL と同じ
**明示的に走らせるレポートツール**として置けば、重いのは当たり前として受け入れられる。

- ビュー名は「呼び出し階層 (Exporter)」ではなく **「影響調査 (Call Hierarchy Exporter)」**にする（決定。§11）。
  このツールの目的（改修時の影響調査で呼び出しを漏らさない）にも、そのほうが正確である
- 置き場所は Explorer の中ではなく**独自のビューコンテナ**（アクティビティバーのアイコン1つ）。
  常時見えるものではなく、調べたいときに開くもの、という位置づけを形でも示す
- 既定のキーバインドは**割り当てない**。`Shift+Alt+H`（標準）と紛らわしいものは特に避ける
- コマンド名は **`影響調査: …`** で揃え（category）、コマンドパレットでもエディタの右クリックでも標準機能と見分けられるようにする。
  右クリックの項目には category が出ないので、そこに出すコマンド（呼び出し元を表示）だけは title 自体を「影響調査: 呼び出し元を表示」にする。
  Language Status の文言も同じ前置きにする
- 標準の呼び出し階層との**違いの説明は拡張の画面で完結させる**。`vscode-plugin/README.md`（Marketplace のページ）と、
  未解析のときのビュー（`viewsWelcome`）に書く。起動時の通知は出さない（§11）

### 3. そのほか標準の型に寄せるもの

| やること | 標準の部品 |
|---|---|
| 解析の進捗 | `withProgress`（自動なら `Window`、手動なら `Notification`）。`CancellationToken` を必ず繋ぐ |
| ログ | `window.createOutputChannel`（`LogOutputChannel` にして `jche.trace` で詳細度を変える） |
| 初回セットアップ（JDK・設定） | `contributes.walkthroughs`。「JDK が無い」をエラーで知らせるより案内に載せる |
| 設定の書式 | `jche.*` で揃え、`markdownDescription` に既定値と効き目を書く |

**自動再解析を既定 OFF にした判断（§7）は、この観点からも正しい。**
「保存したら裏で数十秒走り出す」は VSCode で最も嫌われる挙動である。

---

## 3. 解析の設定をどう用意するか

Eclipse 版は `EclipseProjectConfig` が `IJavaProject#getResolvedClasspath` からソースフォルダと依存 jar を
その場で組み立てていた。VSCode にはそれに当たるモデルが**標準では無い**。3案を比べる。

| 案 | 中身 | 判定 |
|---|---|---|
| **A（採用）** | `config.properties` を探し、無ければ **`project.root` だけ書いた最小の設定を生成**して、残りは本体の `ProjectDetector` に決めさせる | vscode-java に依存しない。閉域でも動く。`pom.xml` / `build.gradle` を読む仕組み（[build-tool-classpath.md](build-tool-classpath.md)）が既にあるので、Maven / Gradle プロジェクトはこれで足りる |
| B | vscode-java の内部コマンド（`java.project.getClasspaths` 等）でクラスパスを取る | 精度は上がるが、vscode-java 必須になり、公開 API でないコマンドに寄りかかることになる。版が上がると黙って壊れる |
| C | 利用者に必ず `config.properties` を書かせる | 最初の1回の敷居が高い。Eclipse 版が自動生成を持っているのに VSCode 版だけ手書きを求めるのは筋が通らない |

**A を既定にし、B は「あれば使う」任意の上乗せ**にする（vscode-java が有効で、かつ設定
`jche.useJavaExtensionClasspath` が true のときだけ問い合わせ、失敗したら黙って A に戻る）。

設定ファイルの探索順は Eclipse 版の `ConfigSource` に合わせる。

1. 設定 `jche.configFile` で明示されたファイル
2. ワークスペースフォルダ直下の `config.properties`（複数あれば QuickPick で選ばせ、選択をフォルダごとに覚える）
3. どれも無ければ自動生成（`project.root=.` だけ。書き出し先は拡張のストレージ）

自動生成した内容は「設定を `config.properties` に保存」コマンドでワークスペースへ書き出せる。
`entry.packages` を絞る、外部 jar の被参照を見る、といった細かい調整はそこから手で直す、という流れも Eclipse 版と同じ。

---

## 4. カーソル位置のメソッドをどう特定するか（`AT` を足す）

ここが Eclipse 版との**最大の違い**である。Eclipse 版は `IMethod` から
`型FQN#メソッド名(引数型,…)` のキーを組み立てていた（`MethodKeys`）。消去型・型変数・内部クラスの
綴り合わせが要る繊細な処理で、寄せ切れないぶんはサーバー側の `findLoosely`（型・名前・引数の数で一意なら採る）が救っていた。

VSCode でこれを再現しようとすると、次のどちらかになる。

| 案 | 中身 | 問題 |
|---|---|---|
| ドキュメントシンボルから組み立てる | `vscode.executeDocumentSymbolProvider` の結果（`OrderService.save(Order)` のような表示用の文字列）を解析する | 表示用の文字列は**プロバイダの都合で変わる**。完全修飾もされていない。import を自前で解いて FQN にする、つまり簡易パーサを拡張側に持つことになる |
| vscode-java に解決させる | 上と同じだが JDT LS 前提 | 依存が増える。B 案と同じ弱点 |
| **`AT` をサーバーに足す（採用）** | 「ファイルと行」を送り、**サーバーが自分の解析結果から囲みメソッドを引く** | 拡張側の仕事が「相対パスと行番号を送る」だけになる。綴り合わせの問題が丸ごと消える |

サーバーはすでに全メソッドの宣言位置（`MethodTable#declFile` / `#declLine`）を持っている。
**同じファイルの、行番号が指定行以下で最大のもの**を選べば囲みメソッドが出る。

```
→ AT  src/main/java/com/example/OrderService.java  42
← OK  how=at  key=com.example.OrderService#save(com.example.Order)  label=…  file=…  line=38  callers=7
```

断り方は4つに分けてある。呼び出し側が次にすることを選べるようにするためである。

| 応答 | 意味 | 画面での扱い |
|---|---|---|
| `NG not-analyzed` | まだ `ANALYZE` していない | ［解析する］を出す |
| `NG file-not-analyzed` | そのファイルが解析結果に無い（`source.folders` の外・除外・新規ファイル） | 「このファイルは解析対象に入っていません」＋［再解析］ |
| `NG not-found` | ファイルはあるが、その行を囲むメソッドが無い（import 文や宣言部） | 「メソッドの中にカーソルを置いてください」 |
| `NG bad-line …` | 行番号が壊れている | 拡張の不具合。ログへ |

- パスは `project.root` からの相対（区切りは `/`）。**ルート配下の絶対パスでも受ける**ので、
  拡張は `Uri#fsPath` をそのまま渡してよい（サーバー側が相対に直す）
- 行番号は **1 始まり**。VSCode の `Position#line` は 0 始まりなので、拡張側で +1 する
- 宣言の**終了行を持っていない**ので「メソッドの外（フィールド宣言やクラスの末尾）にカーソルがある」ときも
  直前のメソッドを返してしまう。ここは割り切る。返した位置（`line=`）を画面に出し、
  「`OrderService#save` の呼び出し元」と見出しに書くことで、利用者が誤りに気づける形にする
- 引くための索引（ファイル → メソッドID の一覧）は最初の `AT` のときに作って持ち回る
- `AT` を知らない古いサーバーは `NG unknown-command` を返す。拡張はそれを見てシンボルからの組み立て（第1案）に落とす…
  ことは**しない**。同梱の jar と拡張は同じ版で配るので、食い違いは起きない。起きたらエラーとして出す

なお「終了行を持たないこと」自体は、この設計とは切り離して**別途 Issue で扱う**（[#115](https://github.com/instreest/java-call-hierarchy-exporter/issues/115)）。
`MethodTable` に終了行を足すのはキャッシュの形式変更であり、古いキャッシュを捨てる経路が要る
（[cache-dependency-jars-qa.md](cache-dependency-jars-qa.md)）。VSCode 版の着手をそれに待たせない。

これは Eclipse 版にも効く（将来 `MethodKeys` を `AT` に寄せれば、あの繊細な綴り合わせを消せる）。
ただし今回は VSCode 側だけで使い、Eclipse 版はそのままにする。二重に壊す危険を冒さない。

---

## 5. 画面

VSCode にはバナーの置き場所が無い（Eclipse 版 §2 の1行バナーに当たるものが無い）ので、
状態は **`TreeView#message` と タイトル と 通知** の3つに割り振る。

```
┌ 呼び出し階層 (EXPORTER) ────────────────── 🔍 ⇅ ⟳ ▽ ⧉ ┐
│ ⚠ 12 ファイルが変更されています（10:31:04 時点の結果）    │ ← TreeView#message
│                                                        │
│ ⬤ OrderService.save(Order)                             │
│  └ ⬤ OrderFacade.register(OrderForm)   OrderFacade.java:88 │
│      ├ ⬤ OrderController.post(…)       OrderController.java:41 │
│      └ ⚠ BatchJob.run()                BatchJob.java:23 │
│  ◈ ScheduledTask.execute()             dataflow: FACTORY│
│  ↻ OrderService.saveAll(…)（再帰）                       │
└────────────────────────────────────────────────────────┘
```

状態は **Language Status Item を主**（§2.5）、ビューの中の表示を従として出す。

| 状態 | 見せ方 |
|---|---|
| 未解析 | `viewsWelcome`（ビューの中央に説明と［解析する］ボタン） |
| 解析中 | タイトルに `— 解析中…`、`withProgress`（`location: ViewId`＝ビュー上部の細い進捗バー）。`#P` 行を進捗に流す。2回目以降は**前回の木を出したまま** |
| 最新 | ステータスに「10:31:04 時点」。ビューの `message` には件数（「表示 27 件 / 全 143 件」）|
| 要再解析 | ステータスに ⚠ と［再解析］。該当ノードのアイコンに ⚠ を重ね、ツールチップに変更時刻 |
| 失敗 | ステータスに ✖ と理由、通知に［ログを開く］。直前の結果は消さない |

- ログ（`#L` 行と子プロセスの標準エラー）は **OutputChannel「Call Hierarchy Exporter」**へ。Eclipse 版のコンソールと同じ役割
- 行のアイコンは `ThemeIcon` で代用する（⬤=`symbol-method`、◈=`symbol-method` ＋ `problemsWarningIcon` の色、
  ↻=`refresh`、◇=`circle-outline`、⚠=`warning`）。絵文字は使わない（テーマとの相性とアクセシビリティのため）
- ダブルクリックで**呼び出している行**を開く（宣言ではない）。Eclipse 版と同じ。宣言へは右クリック →「宣言を開く」
- **古いことを理由にグレーアウトしない**（Eclipse 版と同じ判断）

---

## 6. フィルタ

フィルタは**再解析を起こさない**。条件を `TREE` の引数に足して投げ直すだけで、解析は走らない。
この体感（解析は重いがフィルタは軽い）が設計の要なので、VSCode 側でも崩さない。

VSCode のビューにはフィルタバーを置けないので、次のように散らす。

| 項目 | 置き場所 |
|---|---|
| 絞り込み文字列 | ビュータイトルの 🔍（QuickPick の入力欄）。適用中は `message` に「絞り込み: Order」と出して、効いていることを見えるようにする |
| 深さ | ▽ の QuickPick（既定 5） |
| 方向（呼び出し元 ⇄ 呼び出し先） | ⇅（トグル。`when` 節でアイコンを差し替える） |
| テストを含む／確度で絞る／重複を畳む | ▽ の QuickPick（複数選択） |
| 除外パッケージ | 設定 `jche.exclude`（チップ UI は作らない。VSCode に合う部品が無い） |

フィルタの状態はワークスペースごとに `workspaceState` へ保存する（次回も同じ）。
⧉（CSV 出力）は `EXPORT` を投げるだけ。**いま見えている木をそのまま**書き出す。

---

## 7. 変更の検知と再解析

```
FileSystemWatcher("**/*.java")   ─► dirty: Set<string> ─── 3秒静止 ──► ANALYZE
onDidSaveTextDocument                （足すだけ）          debounce
```

- Eclipse の `POST_BUILD` に当たるものが VSCode には無いので、**保存とファイル変更**を起点にする。
  タイピング中は走らせない（`onDidChangeTextDocument` は見ない）
- 監視対象は設定ファイルの `source.folders` 配下だけ。`config.properties` 自身が変わったら差分ではなく**全部作り直す**
- 自動再解析は既定 **OFF**（`jche.autoAnalyze`。決定。§11）。Eclipse 版は ON だが、VSCode は軽い編集に使われることが多く、
  裏で数十秒の解析が始まるのは驚きが大きい。まず ⚠ で知らせて、⟳ を押してもらう
- 中止は `CANCEL` → 応答しなければ `kill()`。Eclipse 版と同じ
- 差分解析はキャッシュ任せ（[cache-design.md](cache-design.md)）。1ファイル直しただけなら数秒で終わる

---

## 8. 子プロセスの世話

Eclipse 版の `ServerLauncher` / `ServerConnection` の規則をそのまま持ってくる。

- **ワークスペースフォルダごとに1つ常駐**。マルチルートなら複数立つ。グラフをメモリに持ち続けて `TREE` に即答する
- 起動は初回の解析要求時（VSCode の起動を遅くしない。`activationEvents` は `onCommand` と
  `onView:jcheCallHierarchy` にとどめ、`onLanguage:java` では起こさない）
- アイドルで終了（既定 10 分、0 で常駐）
- 要求は**直列**。1要求1応答という約束（`Protocol`）を守るため、拡張側で待ち行列にする。
  `#P` / `#L` は応答の区切りに数えない
- 異常終了したら次の要求で作り直す。作り直せなければ `message` にその旨を出す
- 拡張の deactivate で `CANCEL` → `SHUTDOWN`（この順。`SHUTDOWN` は実行中の解析を止めない）

### 解析に使う JDK

Eclipse 版と同じ規則。**設定 → `JAVA_HOME` → 取得済み → PATH の `java`** の順に探す
（Eclipse 版にある「Eclipse の JVM」に当たるものは無い）。17 未満は選ばない。
無ければ Adoptium から取得する（約 200MB、**必ず一度確認する**。閉域では「JDK の場所を指定する」を案内する）。
取得先は拡張のグローバルストレージ（`context.globalStorageUri/jdk/25/`）。
vscode-java の `java.jdt.ls.java.home` を見るかどうかは §11 の宿題。

---

## 9. 配布物

`.vsix` に解析に必要なものを全部入れる（Eclipse 版と同じ判断。閉域環境に1ファイルで持ち込める）。

```
jche-vscode-x.y.z.vsix
├── dist/extension.js     … esbuild で1ファイルに束ねた拡張本体
└── lib/
    ├── jche-core.jar     … 解析本体（--release 17 でコンパイル、実行は JDK 25）
    └── jdt/*.jar         … JDT 一式 13 個・約 11 MB
```

- `lib/` は Eclipse プラグインのビルド（`eclipse-plugin/pom.xml` の `maven-dependency-plugin`）と
  **同じ版を同じ手順で集める**。`//DEPS` 行が唯一の出どころであり続けるようにする
- 同梱する jar は EPL-2.0（このツール自身は Apache-2.0）。`NOTICE` に明記する
- VSIX は 12〜13 MB になる。Marketplace の上限には余裕がある

---

## 10. 検査

`test/vscode/run.sh` を足す。VSCode 本体を落としてくる検査（`@vscode/test-electron`）は
CI の時間と閉域環境を考えると割に合わないので、**`vscode` モジュールに触らない層だけ**を Node で検査する。

| 何を | どうやって |
|---|---|
| プロトコルクライアント | 実際に `--server` を起こして `HELLO` → `ANALYZE` → `AT` → `TREE` → `EXPORT` を通す（Eclipse 版の `test/plugin-client/run.sh` と対になる） |
| 設定の自動生成 | `test/demo` に対して生成した設定で解析が通ること |
| JDK の選び方・取得先 URL | ネットワーク無しで、Windows / Linux / macOS の組み立てを検査（Eclipse 版と**同じ期待値**を使い、2言語の実装がずれたら落ちるようにする） |
| `AT` の境界 | 行がメソッドの外にあるとき、同じ行に複数の宣言があるとき、ファイルが解析結果に無いとき |
| VSIX の中身 | `lib/jdt/` の版が `//DEPS` と一致すること（`test/pom/run.sh` と同じ考え方） |

サーバー側に足す `AT` は `test/server/run.sh` にも1ケース足す。

---

## 11. 決めたこと・残っている宿題

設計案の段階で迷っていた点は、次のとおり決まった。

| 論点 | 結論 |
|---|---|
| vscode-java との関係の説明場所 | **拡張の画面で説明する**。`vscode-plugin/README.md`（Marketplace のページ）に「標準の呼び出し階層との違い」の節を置き、未解析のときのビュー（`viewsWelcome`）にも1行で書く。**起動時の通知は出さない**（1回きりでも、頼んでいない通知は嫌われる） |
| ビューの名前 | **「影響調査 (Call Hierarchy Exporter)」**。VSCode では標準機能と紛れないことを優先する（§2.5）。Eclipse 版の名前を揃えるかは別途 Issue で検討する（[#116](https://github.com/instreest/java-call-hierarchy-exporter/issues/116)） |
| 自動再解析の既定 | **OFF**（§7）。Eclipse 版（ON）と既定が違ってよい。VSCode は軽い編集に使われるため |
| リポジトリを分けるか | **同居させる**（`vscode-plugin/`）。`lib/` の版合わせと `AT` の追従を優先する。`npm` のビルドがこのリポジトリに入ることは受け入れる |
| `AT` の精度（メソッドの終了行） | 設計としては §4 の割り切りで進める。**終了行を持つかどうかは別途 Issue で扱う（[#115](https://github.com/instreest/java-call-hierarchy-exporter/issues/115)）**（キャッシュの形式変更を伴うため、この設計とは切り離す） |

Issue に切り出したもの … [#115](https://github.com/instreest/java-call-hierarchy-exporter/issues/115)（メソッドの終了行を持つか）、
[#116](https://github.com/instreest/java-call-hierarchy-exporter/issues/116)（Eclipse 版のビュー名を揃えるか）。

残っている宿題。

- vscode-java の `java.jdt.ls.java.home` を JDK の探索順（§8）に入れるか

### 木の転送量 — 測ってから決める

呼び出し元の木は深さに対して掛け算で増える（分岐 10・深さ 5 で 11 万行）。歯止めは既に入っていて、
`TreeFilters` の `maxDepth=5` / `maxRows=20000` / `dedupe=true` により**最悪でも 2 万行**である。
心配なのは量そのものではなく、**それが一度に届くこと**である。1 行 150〜250 バイトなので 2 万行で約 4 MB、
VSCode の拡張ホストは 1 本のスレッドなので、これを分解している間はビューが固まって見える。

Eclipse 版は同じ作りで足りているため、**先に複雑にはしない**。§12 の M3 は一括のまま作り、
実プロジェクトで `rows=` と行が届くまでの時間を測ってから決める。判断の目安を先に決めておく。

| 実測 | 判断 |
|---|---|
| 1 回の `TREE` が 5,000 行・1 MB・300 ms 未満に収まる | **一括のまま。何もしない** |
| それを超えることが実用上ある | 折衷案（深さ 2 まで一括、以降は節点を開いたときに `TREE` を投げ直す）へ |
| 2 万行の上限に日常的に当たる | `maxDepth` / `maxRows` の既定を見直し、絞り込みを促す UI を強くする |

遅延取得は新しい仕組みではない。深さ上限で切った枝には既に `truncated` の印が付いており、
「その節点を根にして `TREE` を投げ直す」経路は Eclipse 版で動いている（§3 のプロトコル）。
つまりこれは**既にあるものをどこまで使うか**の選択であって、作り直しではない。

上限に当たったときは**黙って切らない**。「20,000 件で打ち切りました。絞り込みか深さを使ってください」と、
次に何をすればよいかまで出す（「呼び出しを静かに落とさない」の方針どおり）。

## 12. 段階

| 段 | 内容 |
|---|---|
| ~~M1~~ **済** | サーバーに `AT` を足した（`jche.server.Server#at`。`test/server/run.sh` が検査）|
| ~~M2~~ **済** | プロトコルクライアント（`vscode-plugin/src/server/`）＋設定の用意（`src/config.ts`）。`vscode` に触らない層。`test/vscode/run.sh` が Node だけで検査する（CI の `vscode-plugin` ジョブ）|
| ~~M3~~ **済** | ツリービュー（`view.ts`）・カーソルからの起動（`AT`）・Language Status Item（`status.ts`）・出力チャネル・`viewsWelcome`・設定の選択と保存。木は**一括転送のまま**で、打ち切った節点だけ開いたときに取り寄せる。§11 の目安で測るのはこれから。**実機（VSCode）での動作確認はまだ**で、M5 で `lib/` を同梱してから行う |
| ~~M4~~ **済** | フィルタ一式（`filters.ts`、`workspaceState` に保存）・CSV 出力（同じ条件で `EXPORT`）・変更検知（`FileSystemWatcher`、Language Status に ⚠ と件数、該当節点に ⚠）・自動再解析（既定 OFF、3 秒静止）・JDK の取得（無ければ確認のうえ Adoptium から。`jche.jdkDownload` で提案自体を止められる）|
| ~~M5~~ **済**（M4 より先に実施） | 配布。`scripts/collect-lib.sh` が eclipse-plugin のビルドから `lib/` を集め（出どころを1つにする）、`npm run package` で `.vsix` を作る。`test/vscode/package.sh` が中身と JDT の版を検査し、CI が成果物として上げる。使い方は [vscode-plugin-usage.md](vscode-plugin-usage.md) |

M1 は本体側だけで終わり、Eclipse 版にも将来効く。M2 まで入れば「動くかどうか」は VSCode 無しで確かめられる。
