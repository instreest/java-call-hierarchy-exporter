# Eclipseプラグインの使い方

このツールは **Eclipse プラグインとしても使える**。パッケージ・エクスプローラーやエディタから
メソッドを選び、その**呼び出し元を階層で辿る**のが主な使い方である。

Eclipse を使わない通常の利用（コマンドラインで CSV を出す）はリポジトリ直下の
[README.md](../README.md) を参照のこと。**プラグインは付加的な使い方**なので、
その説明はこの文書にまとめてある。

- 設計 … [eclipse-plugin-ui-design.md](eclipse-plugin-ui-design.md)（画面）、
  [out-of-process-analysis-design.md](out-of-process-analysis-design.md)（別プロセスでの解析）
- 実装時に迷った点 … [eclipse-plugin-qa.md](eclipse-plugin-qa.md)、[eclipse-plugin-ui-qa.md](eclipse-plugin-ui-qa.md)
- 版の対応表 … [eclipse-pleiades-versions.md](eclipse-pleiades-versions.md)

---

## 1. 動作条件

**解析は Eclipse の中では走らない。** プラグインは同梱した解析本体（`lib/jche-core.jar`）と
JDT 一式（`lib/jdt/*.jar`）を、別の JDK で子プロセスとして起動し、結果だけを受け取る。
そのため **Eclipse 側の JDT や JDK の版は、解析できる Java の版に影響しない**。

| | 条件 |
|---|---|
| Eclipse | **4.6（2016年、Neon）以降** |
| Eclipse を動かす JDK | **8 以上**（プラグインは Java 8 でコンパイルしている） |
| 解析に使う JDK | **17 以上、推奨 25**。「設定 → JAVA_HOME → 取得済み → Eclipse を動かしている JVM → PATH の java」の順に探す |

Pleiades なら、同梱の JDK がそのまま解析にも使える（2023 以降は 17・21、2025 以降は 25）。
見つからなければ設定画面から取得できる（§4）。

## 2. 入れる

### 方法A: jar を作って Eclipse に入れる（おすすめ）

必要なもの: Git、JDK 17 以上、Maven、ネットワーク（初回だけ依存の取得に使う）。

```bash
git clone https://github.com/instreest/java-call-hierarchy-exporter.git
cd java-call-hierarchy-exporter
mvn -f eclipse-plugin/pom.xml package
# -> eclipse-plugin/target/io.github.instreest.jche.eclipse-1.0.0.jar（約 12MB）
```

Eclipse を終了してから、その jar を Eclipse インストール先の `dropins` フォルダへコピーし、
初回だけ `-clean` を付けて起動する。

```bat
rem Windows（Pleiades の例。パスは環境に合わせる）
copy eclipse-plugin\target\io.github.instreest.jche.eclipse-1.0.0.jar C:\pleiades\2026-06\eclipse\dropins\
C:\pleiades\2026-06\eclipse\eclipse.exe -clean
```

入ったかどうかは「ヘルプ → Eclipse IDE について → インストール詳細 → プラグイン」で
`io.github.instreest.jche.eclipse` を探せば分かる。

### 方法A': GitHub Actions で作らせて jar を持ち帰る

手元に Maven やネットワークが無いときは、このリポジトリの
[`eclipse-plugin-jar`](../.github/workflows/eclipse-plugin-jar.yml) ワークフローを使う。
GitHub の「Actions → eclipse-plugin-jar → Run workflow」で手動実行すると、
方法Aと同じ jar が `eclipse-plugin-jar` アーティファクト（zip）として付く。
展開して出てきた jar を、方法Aと同じように `dropins` へ置く。

保持日数は実行時に指定できる（既定 7 日）。

### 方法B: Eclipse（PDE）で開いて開発する

PDE（プラグイン開発環境）入りの Eclipse が要る。

1. 先に `mvn -f eclipse-plugin/pom.xml package` を実行し、`eclipse-plugin/target/classes/lib/` を
   `eclipse-plugin/lib/` へコピーする（解析本体と JDT はここから同梱される）
2. 「ファイル → インポート → 一般 → 既存プロジェクトをワークスペースへ」で `eclipse-plugin` を選ぶ
3. 試すときは「実行 → 実行構成 → Eclipse アプリケーション」、配布物を作るときは
   「ファイル → エクスポート → デプロイ可能なプラグインおよびフラグメント」

PDE がコンパイルするのは `src-ui/`（プラグイン）だけである。解析本体（リポジトリ直下の `src/`）は
Java 17 でコンパイルして `lib/jche-core.jar` に収めるものなので、PDE のビルドには含めない。

## 3. 使う

1. 解析したいメソッドにカーソルを置く（またはパッケージ・エクスプローラー／アウトラインで選ぶ）
2. 右クリック →「**影響調査: 呼び出し元階層を表示**」（`Ctrl+Alt+Shift+H`）
3. 「影響調査 (Call Hierarchy Exporter)」ビューが開く。まだ解析していなければバナーの［解析する］から始める
4. 解析は別プロセスで走る（Eclipse の操作は止まらない。［中止］もできる）。終わると呼び出し元が
   ツリーで出る。ダブルクリックでその**呼び出している行**へ飛べる

設定ファイルは**無くてよい**。Java プロジェクトなら、ソースフォルダ・依存 jar・文字コード・
コンパイラー準拠レベルをプロジェクトの構成から自動で組み立てる。

| したいこと | 操作 |
|---|---|
| 絞り込む | 上の検索欄に型名・メソッド名（**解析は走らない。すぐ効く**）。深さも隣で変えられる |
| 細かい条件 | ［フィルタ…］でテストの除外、推測による解決の除外、`exclude.packages` の適用、呼び出し元の重複の扱い |
| 向きを変える | ツールバーの「呼び出し先を見る」 |
| 解析し直す | ツールバーの「再解析」。ソースを変えると「⚠ n ファイルが変更されています」とバナーに出る |
| 自動で解析し直す | ツールバーの「自動再解析」（既定 ON）。ビルド後に静止してから裏で走る |
| 見えている木を CSV に | ［CSV出力］。フィルタ後の内容がそのまま出る |
| 設定を細かく決める | ビューのメニュー（▽）→「設定を config/jche.properties に保存」。自動生成した内容が保存され、`entry.packages` などを手で足せる |
| 設定ファイルを選ぶ | ビューのメニュー（▽）→「使う設定ファイルを選ぶ…」 |

設定の優先順位は **①ビューで選んだ設定ファイル → ②プロジェクトの `config/jche.properties`
（無ければ `config/config.properties`、直下の `jche.properties`、直下の `config.properties` の順）→
③プロジェクト構成からの自動生成**。どの設定で解析したかはバナーのツールチップに出る。

②で自動的に使うのは、このツールの項目（`project.root` / `source.folders` / `entry.packages` のどれか）を
持つファイルだけ。`config.properties` は解析対象のプロジェクトが自前の設定に使っていることがあり、
それを取り違えないためである（別物だった場合は③の自動生成で動く。ビューで明示的に選んだファイルは
この判定を通さず、そのまま使う）。保存するときの名前を `jche.properties` にしているのも同じ理由。

解析中も前回の結果は消えない（バナーだけが「更新中」に変わる）。解析後に変わったファイルの行には
⚠ が付き、内容が古い可能性があることが行単位で分かる。
キャッシュはワークスペースの `.metadata/.plugins/io.github.instreest.jche.eclipse/` の下。

## 4. 設定（ウィンドウ > 設定 > 影響調査 (Call Hierarchy Exporter)）

決められるのは「解析をどう走らせるか」だけである（何を解析するかは設定ファイルの役目）。

| 設定 | 既定 | 用途 |
|---|---|---|
| 解析に使う JDK | 自動（25 を優先して 17 以上） | CLI と結果を揃えたいとき。**見つからなければ［JDK 25 を取得…］で Adoptium から取得できる**（約 200MB、確認してから実行。閉域では場所を指定する） |
| JDT の jar のフォルダ | 同梱のもの | 閉域で新しい JDT を別に置いて使いたいとき |
| 解析プロセスの JVM 引数 | 無し | `-Xmx4g` など。Eclipse 自身のメモリとは別枠 |
| 使われないときに終了する | 10 分 | 解析プロセスは結果をメモリに持って常駐する。0 にすると終了しない |

## 5. つまずきやすいところ

| 症状 | 対処 |
|---|---|
| メニューに出てこない | Eclipse を `-clean` 付きで起動し直す。それでも出なければ「ウィンドウ → ビューの表示 → その他」で「影響調査 (Call Hierarchy Exporter)」を探す |
| 「解析に使う JDK が見つかりません」 | 設定画面で場所を指定するか、［JDK 25 を取得…］で取得する |
| 解析が失敗する | 「Call Hierarchy Exporter」コンソールに子プロセスの出力がそのまま出る。設定ファイルの誤り（`project.root` など）が多い |
| バナーに「解析できません」 | Java プロジェクトでなく、設定ファイルも無い状態。`config/jche.properties` を置くか、Java プロジェクトとして開く |
| 新しい文法のソースが解析されない | 同梱の JDT の対応上限を超えている。バナーのツールチップに「解析できる Java」が出る |

## 6. サーバーモード（プラグインが使っている経路）

プラグインは、解析本体を `--server` 付きで起動して標準入出力でやりとりしている。
同じことは手でもできる（CLI 利用者が自前の道具から使いたいときのため）。

```bash
printf 'HELLO\t1\nANALYZE\t/path/config/config.properties\nTREE\tcom.example.Foo#bar()\tcallers\tdepth=3\nSHUTDOWN\n' \
  | java -cp "lib/*:bin" jche.CallHierarchyExporter --server /tmp/jche-cache
```

要求と応答は TAB 区切りの1行で、`ANALYZE`（解析）・`FIND`（メソッドの確認）・
`AT`（ファイルと行から、その位置を囲むメソッドを引く）・`TREE`（木の切り出し）・
`EXPORT`（CSV 出力）・`CANCEL`（解析の中止）・`SHUTDOWN`（積んだ要求を処理し終えてから終わる）がある。
上の例のようにまとめて流し込んでよく、**末尾の `SHUTDOWN` が先に読まれても `ANALYZE` は完走する**。
実行中の解析を打ち切りたいときは `CANCEL` を送る。解析中は `#P` 行で進捗が、
`#L` 行でログが流れる。**フィルタ（深さ・文字列・テスト除外など）はサーバー側で効く**ので、
絞り込みのたびに解析し直すことはない。詳しい仕様は `src/jche/server/Protocol.java` のコメントにある。

## 7. テスト

プラグインまわりには4つの検査がある（いずれも GitHub Actions で実行）。

```bash
bash test/plugin/run.sh          # 版・ID・クラスの実在、解析本体がバンドルに混ざっていないこと
bash test/plugin-api/run.sh      # 古い Eclipse（4.6 相当）の jar と --release 8 でコンパイルできること
bash test/plugin-client/run.sh   # 子プロセスを実際に起動して、プロトコルと木の組み直しを確認
bash test/server/run.sh          # サーバーモードの応答（ANALYZE / FIND / TREE / EXPORT ほか）
```

画面操作そのもの（SWT）の自動テストは無い。解析結果の正しさは `test/regression` が受け持つ。
