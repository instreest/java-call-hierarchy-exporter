# 解析を別プロセス（別 JDK・別 JDT）へ出す設計

方針変更の記録。[Issue #49](https://github.com/instreest/java-call-hierarchy-exporter/issues/49) の
続きとして、**プラグインからは解析を一切行わず、子プロセスに任せる**形に作り替える。
狙いは3つある。

1. 解析を **JDK 25**（と最新の JDT jar）で走らせる。Eclipse がどの JDK・どの JDT で動いていても影響されない
2. JDT jar は **Eclipse 同梱のものではなく、プラグインに同梱した最新版**を使う。解析できる Java の版が
   Eclipse の版に縛られなくなる
3. プラグイン側（Eclipse の中で動く部分）から解析コードを追い出し、**Java 8 以上で動く**ようにする

> **実装状況**（2026-09-12）: **S1 完了**（本体のサーバーモード）。
> `CallHierarchyExporter --server` で標準入出力のプロトコルを話し、`ANALYZE` / `FIND` / `TREE` /
> `EXPORT` / `CANCEL` / `SHUTDOWN` に応答する。木の切り出しと絞り込みもサーバー側に置いた
> （`jche.server`）。検査は `test/server/run.sh`（GitHub Actions の regression ジョブで実行）。
> S2 以降（プラグインのクライアント化・Java 8 化・設定画面）はこれから。

---

## 1. なぜ分けられるのか

JDT に依存しているのは解析の入口（フェーズ1）だけで、その成果物は**キャッシュファイル**
（`analysis-cache.tsv`）に全部入っている。フェーズ2・3（グラフ構築・解決・出力）は
キャッシュしか読まない。つまり境界はすでに存在する。

| 層 | JDT | 実行 JVM の影響 |
|---|---|---|
| フェーズ1 `jche.analysis` | **必要**（AST パース） | あり（動作中の JVM の標準クラスを解析対象に含める） |
| フェーズ2 `jche.graph` | 不要 | なし |
| フェーズ3 `jche.report` | 不要 | なし |
| 設定 `jche.config` | 定数のみ | なし |

一方、**画面（ツリー）はフェーズ2の結果（メモリ上のグラフ）を必要とする**。
ここが今回の分岐点で、次のどちらを採るかで全体が変わる。

| 案 | 子プロセスの担当 | Eclipse 側の担当 | プラグインの Java |
|---|---|---|---|
| A | フェーズ1のみ | フェーズ2・3 ＋ 画面 | **17 以上**（グラフのコードが Java 17 構文） |
| **B（採用）** | **フェーズ1〜3すべて＋問い合わせ応答** | **画面だけ** | **8 以上** |

要件「プラグインは Java 8 以上」を満たせるのは **B だけ**である。A では `jche.cache`（18 ファイル中
15 が record）や `jche.graph` が Eclipse 内に残り、Java 8 では動かない。

---

## 2. 役割分担（案B）

```
Eclipse（Java 8+、JDT は「モデル」だけ）        子プロセス（JDK 25、最新 JDT jar）
┌───────────────────────────────┐        ┌────────────────────────────────┐
│ 呼び出し階層ビュー             │  行指向 │ jche 一式（現行のまま Java 17）  │
│  ・メソッドの選択 → キー文字列 │ ◀────▶ │  フェーズ1 解析 → キャッシュ     │
│  ・設定の生成（クラスパス等）  │ 標準入出力 │  フェーズ2 グラフ＋転置索引     │
│  ・ツリー表示・フィルタUI      │        │  フィルタ適用・木の切り出し      │
│  ・子プロセスの起動/監視/中止  │        │  CSV 出力                        │
└───────────────────────────────┘        └────────────────────────────────┘
```

Eclipse 側に残る JDT の使い方は**モデル API だけ**になる。

| 用途 | 使う API | 初出 |
|---|---|---|
| 選択されたメソッド → キー | `IMethod`、`Signature`、`IType#resolveType` | JDT 3.x 初期 |
| 設定の自動生成 | `IJavaProject#getRawClasspath` / `#getResolvedClasspath` | 同上 |
| ファイル → コンパイル単位 | `JavaCore#create` | 同上 |

`JavaCore.latestSupportedJavaVersion()`（現在の下限 3.32 の理由のひとつ）は子プロセス側へ移すので、
**JDT の下限は 3.10 前後（Eclipse 4.4 世代）まで下げられる見込み**。Maven Central にも 3.10.0 がある。

---

## 3. プロトコル

外部ライブラリを増やしたくないので、**行指向のテキスト（UTF-8、TAB 区切り）**にする。
JSON は表現力が高いがパーサを自前で書くか依存を足すことになり、木の行を流すだけの用途には重い。
エスケープは既存の `jche.report.Csv` と同じ考え方（TAB・改行を `\t` `\n` に置換）。

```
→ HELLO 1                     プロトコル版の確認
← OK jche-server 1 jdt=3.46.0 jvm=25.0.3

→ ANALYZE /tmp/xxx.properties  設定ファイル（プラグインが生成して書き出す）
← #P ソース解析 120/5000       進捗（解析中は何行でも流れる）
← OK analyzed=5000 methods=48213 edges=91022 at=2026-09-12T10:31:04

→ FIND com.example.OrderService#save(com.example.Order)
← OK id=1234                  無ければ ← NG not-found

→ TREE 1234 callers depth=5 text=Order tests=0 guessed=1 dedupe=1
← R 0 <key> <label> <file> <line> <reason> <flags>
← R 1 ...
← OK rows=27 truncated=3

→ EXPORT 1234 callers /path/out.csv ...同じフィルタ
← OK rows=1832

→ CANCEL                      実行中の ANALYZE を中止（別スレッドで受ける）
→ SHUTDOWN
```

- **フィルタは子側で適用する**。画面側でやると `jche.graph` が Eclipse に必要になり、案B が崩れる
- ツリーは「根から深さ n までの行」をまとめて返す。1ノードずつ往復しない
  （深さ上限で打ち切った枝は、その節点を根にした `TREE` を改めて投げる）
- 進捗・ログは `#P` / `#L` 行で流し、プラグインはそれを進捗バーとコンソールへ出す

## 4. プロセスの扱い

- **プロジェクトごとに1つ常駐**。グラフをメモリに持ち続け、`TREE` に即答するため
- 起動は初回の解析要求時（Eclipse の起動は遅くしない）。一定時間アイドルなら終了（設定）
- 中止は `CANCEL`、応答しなければ `Process.destroy()`
- 異常終了したら、次の要求で作り直す。作り直せなければバナーにその旨を出す
- 標準エラーは「Call Hierarchy Exporter」コンソールへ。落ちた原因が見えるようにする

## 5. 配布とクラスパス

**解析に必要なものはすべてバンドルに同梱する**。Eclipse 側の JDT には一切頼らない
（頼ると「古い Eclipse では新しい Java を解析できない」という制約が戻ってくる）。

```
io.github.instreest.jche.eclipse_x.y.z.jar
├── jche/eclipse/*.class      ← Java 8 でコンパイル。Eclipse の中で動く
└── lib/
    ├── jche-core.jar         ← 解析本体。JDK 25 で動かす。Bundle-ClassPath には載せない
    └── jdt/*.jar             ← 最新の JDT 一式（13 jar・約 11 MB）。同上
```

`lib/` を **`Bundle-ClassPath` に入れない**のが肝心である。入れると Eclipse（Java 8 かもしれない）が
読もうとして落ちる。子プロセスを起動するときに `FileLocator` で実体パスを取り出し、`-cp` にだけ並べる。

### 同梱する JDT 一式

ビルド時に `maven-dependency-plugin` で Maven Central から集めて `lib/jdt/` に入れる。
版は `//DEPS` 行と同じ（＝CLI と同じ JDT）。`test/plugin/run.sh` で食い違いを検出する。

この 13 個で足りることは、実際にこのクラスパスだけで `test/demo` を解析し、
**CSV が期待値と完全一致する**ことを確認した（`jna`・`jna-platform`・`osgi.annotation`・
`core.commands`・`core.expressions`・`equinox.app` は不要。約 3.9 MB ぶん削れる）。

```
ecj, org.eclipse.jdt.core, org.eclipse.osgi, org.eclipse.text,
org.eclipse.core.contenttype, core.filesystem, core.jobs, core.resources, core.runtime,
org.eclipse.equinox.common, equinox.preferences, equinox.registry, org.osgi.service.prefs
```

- バンドルは 11〜12 MB 増える。閉域環境に zip 1つで持ち込める利点のほうが大きいと判断する
- 同梱する jar は **EPL-2.0**（このツール自身は Apache-2.0）。`about.html` / `NOTICE` に
  同梱物とそのライセンスを明記する（[license-header-qa.md](license-header-qa.md) の方針に合わせる）

### 解析に使う JDK

**JDK 25 で動かす**。`lib/jche-core.jar` 自体は `--release 17` でコンパイルする（実行は 25）。
解析結果に効くのは<b>動かす JVM の版</b>であってバイトコードの版ではないため、
ここを 17 にしておけば Eclipse から開いた Maven プロジェクト（m2e、`maven.compiler.release=17`）や
`test/pom` の構成と食い違わない。ソースの文法を 25 に上げたくなったら別途判断する。

| 設定項目 | 既定 | 備考 |
|---|---|---|
| 解析に使う JDK | **25**（無ければ取得を提案。設定で明示指定できる） | 17 以上なら動くが、CLI（`//JAVA 25`）と結果を揃えるため 25 を既定にする |
| JDT jar | **同梱の `lib/jdt/`** | 設定で別フォルダを指せば差し替え可（閉域で新しい JDT を入れたいとき） |
| 子プロセスの `-Xmx` | 未指定（JVM 既定） | 大きなプロジェクトはここで増やす。Eclipse のヒープとは独立 |
| アイドル終了 | 10 分 | 0 で常駐 |

JDK の探索順は「設定 → `JAVA_HOME` → Eclipse の登録済み JRE → `java` コマンド」を想定する
（登録済み JRE を見るなら `org.eclipse.jdt.launching` への依存が増えるので、そこは §9 の決め事）。

### 見つからないときは取得する

どこにも 25 が無ければ、**プラグインが取得する**。ただし黙って 200MB 近くを落とすのは乱暴なので、
最初の解析を始めるときに一度だけ確認する。

```
解析用の JDK 25 が見つかりません。
Adoptium (Eclipse Temurin) から取得しますか？（約 200MB、初回のみ）
   [取得する]  [JDK の場所を指定する…]  [やめる]
```

- 取得先は Adoptium の API（`https://api.adoptium.net/v3/binary/latest/25/ga/<os>/<arch>/jdk/hotspot/normal/eclipse`）。
  OS とアーキテクチャは実行中の Eclipse から決める
- 置き場所はプラグインの状態フォルダ（`<ワークスペース>/.metadata/.plugins/io.github.instreest.jche.eclipse/jdk/25/`）。
  ワークスペースを消せば一緒に消えるので、環境を汚さない
- 取得は Job（進捗つき・中止可）。展開まで終わったら設定に記録し、次回からは探索で見つかる
- **閉域環境では取得できない**ので、そのときは「JDK の場所を指定する…」で既にある JDK を指してもらう。
  ダイアログにその案内を出す。取得できないこと自体は失敗として扱わない
- 取得した JDK の版と出所はログに残す（何で解析したかを後から説明できるようにする）

## 6. Java 8 化の作業量（Eclipse 側）

現在のプラグインは 11 ファイル・約 2,800 行。案B では `jche.*` を参照している 9 ファイルが
「プロトコルクライアント」に置き換わるので、**むしろ小さくなる**見込み。

| ファイル | 案B での扱い |
|---|---|
| `CallersModel` / `CallNode` / `CallersInput` | 削除（フィルタと木は子側） |
| `AnalysisJob` / `AnalysisService` / `ProjectAnalysis` | プロセス管理＋クライアントに作り替え |
| `TreeCsvExporter` | `EXPORT` を投げるだけに縮小 |
| `CallHierarchyView` / ラベル・内容プロバイダ | 行データ（文字列）を描くだけに変更 |
| `MethodKeys` / `EclipseProjectConfig` / `ConfigSource` | ほぼそのまま（JDT モデルのみ） |
| 共通 | record → 素のクラス、switch 式 → 旧 switch、`instanceof` パターン → キャスト |

`Bundle-RequiredExecutionEnvironment` は `JavaSE-1.8` に下げる。
これにより **Eclipse 4.4（2014）〜 最新**まで導入できる見込みになる（Java 8 で動く Eclipse は 2020-06 まで）。

## 7. 得るもの・失うもの

**得るもの**

- 解析する JDK と JDT を Eclipse から独立させられる。**古い Eclipse でも最新 Java のソースを解析できる**
  （JDT を同梱するので、Eclipse 側の JDT の版は解析能力に関係しなくなる）
- Eclipse 側が Java 8 で動く。導入できる環境が一気に広がる
- 解析が Eclipse のヒープを食わない。OutOfMemory の切り分けも簡単（子プロセスの `-Xmx` を別に指定できる）
- 解析が固まっても Eclipse は無傷。最悪プロセスを殺せばよい

**失うもの・代償**

- プロセス間通信という層が増える（プロトコル・生存監視・異常系）
- 起動コストが 1〜2 秒。常駐で緩和するが、初回は必ず乗る
- メモリが二重（子プロセスにグラフ、Eclipse に表示ぶん）
- 大きな木は行の転送量が増える。深さと件数の上限で抑える
- デバッグが一段面倒（子プロセスのログを見る手当が要る）
- バンドルが 11〜12 MB 大きくなる（同梱する JDT 一式）。EPL-2.0 の同梱物の表示も要る

## 8. 段階

| 段 | 内容 | 検証 |
|---|---|---|
| ~~S1~~ **済** | 本体に **サーバーモード**を足す（`CallHierarchyExporter --server`）。標準入出力でプロトコルを話す。フェーズ1〜3とフィルタ・木の切り出しは既存コードを流用 | `test/server/run.sh` で `HELLO`→`ANALYZE`→`FIND`→`TREE`→`EXPORT` の一連と、断り方（未解析・不明メソッド・知らない要求）を自動検査 |
| S2 | プラグインを**クライアント化**（まだ Java 17 のまま）。プロセス管理・進捗・中止・再解析を移す | 既存の画面が同じように動くこと。解析が Eclipse の外で走っていることをログで確認 |
| S3 | **Java 8 化**。`jche.*` 参照を消し、BREE を 1.8 に、JDT の下限（モデル API 用）を下げる。ビルドを「Java 8 のバンドル」＋「`lib/jche-core.jar`（release 17・実行は JDK 25）」＋「`lib/jdt/` に同梱する JDT 一式」の3点に | 下限 JDT・Java 8 でのビルドを CI に追加。Eclipse 4.x 系での導入確認（手動） |
| S4 | JDK・JDT の**設定画面**（別 JDK、別 JDT jar フォルダ、`-Xmx`、アイドル終了） | 設定を変えて子プロセスの起動コマンドが変わることを確認 |
| S5 | 後片付け（不要になった in-process 経路の削除、ドキュメント更新） | 全テスト |

S1 は本体側だけで完結し、CLI にも「サーバーとして使える」という価値が残る。
S2 まで入れば Eclipse 側の JDK 依存は消え、S3 で Java 8 になる。

## 9. 決めておきたいこと

1. **プロトコルの形式** … TAB 区切りの行指向（提案）／JSON Lines（依存を足すか自前パーサ）
2. **通信路** … 標準入出力（提案。シンプル）／ローカルソケット（複数クライアント・切断耐性は上がる）
3. **木の返し方** … 深さぶんまとめて返す（提案）／1ノードずつ
4. **子プロセスの JDK の決め方** … 「設定 → JAVA_HOME → Eclipse の登録済み JRE → java コマンド」の
   順で探す（提案）。登録済み JRE を見るかどうかで `org.eclipse.jdt.launching` への依存が変わる
7. **同梱 JDT の版を上げる手順** … `//DEPS` 行を変えれば `lib/jdt/` も変わる形にする（提案）
5. **Java 8 化を S3 でまとめてやるか**、S2 の時点で同時にやるか
6. 既存の in-process 経路を**残すか消すか**（残すと二重メンテ、消すと後戻りできない）

現行の in-process 版はすでに動いているので、S1・S2 を入れてから比較し、
問題が無ければ S5 で消す、という順番を勧める。
