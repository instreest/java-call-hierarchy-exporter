# VSCode プラグインの使い方

> 設計は [vscode-plugin-design.md](vscode-plugin-design.md)、判断の記録は [vscode-plugin-qa.md](vscode-plugin-qa.md)。
> Eclipse 版は [eclipse-plugin-usage.md](eclipse-plugin-usage.md)。

Java プロジェクト全体の呼び出し階層を一括で解析し、改修の影響範囲を辿るための拡張。
解析は VSCode とは**別のプロセス・別の JDK**で走り、解析本体（`jche-core.jar`）と JDT 一式は拡張に同梱してある。
vscode-java（Red Hat の Java 拡張）は**要らない**（入っていても干渉しない）。

## 標準の「呼び出し階層」との違い

VSCode には標準の呼び出し階層（`Shift+Alt+H`、vscode-java が提供）がある。この拡張はそれの置き換えではなく、
Test Explorer や CodeQL のように「重い解析を明示的に走らせ、その結果を辿る」道具である。

| | 標準の呼び出し階層 | この拡張（影響調査） |
|---|---|---|
| いつ結果が出るか | 即座 | **先にプロジェクト全体を解析**してから（分単位のこともある） |
| 何を辿るか | 直接の呼び出し | インタフェース経由・データフロー経由・Spring の DI 経由の呼び出し元まで（解決の理由付き） |
| 結果の鮮度 | 常に最新 | 解析した時点のもの。古くなったら再解析する |
| 向いている場面 | 書きながら参照を確認する | 改修前に影響範囲を漏れなく洗い出す。CSV に出して Excel で絞る |

## 入れ方

1. `.vsix` を用意する … GitHub Actions の `smoke` ワークフローの成果物 `call-hierarchy-exporter-vsix`、
   または手元で作る（[ビルド](#ビルド)）
2. VSCode の 拡張機能 → `…` → 「VSIX からのインストール」で入れる
3. 解析に使う **JDK 17 以上**を用意する（CLI と結果を揃えるなら 25）。探す順は
   設定 `jche.javaHome` → 環境変数 `JAVA_HOME` → PATH の `java`

閉域環境でも動く。`.vsix` 1つに解析本体と JDT が入っており、拡張はネットワークに出ない
（JDK の自動取得は未実装。あるものを指してもらう）。

## 使い方

1. アクティビティバーの「影響調査」を開き、［解析する］を押す
   （または コマンドパレット → `Call Hierarchy Exporter: 解析する`）。
   進捗は右下の通知に出る。中止できる
2. 解析が終わったら、エディタで**メソッドの中**にカーソルを置き、右クリック →「呼び出し元を表示 (Exporter)」
3. 木のノードをクリックすると**呼び出している行**が開く（宣言ではない）。
   右クリックで「ここを起点にする」「宣言を開く」
4. ツールバーの ⇅ で呼び出し先方向に切り替える

木は一度に深さ 5（設定 `jche.depth`）まで取り寄せる。深さの上限で打ち切られた節点は、開いたときに続きを取り寄せる。

### 状態の見方

解析の状態は、Java のファイルを開いているときにエディタ右下の `{}`（Language Status）に出る。

| 表示 | 意味 | 次にすること |
|---|---|---|
| 未解析 | まだ解析していない | クリックで解析 |
| 解析中 フェーズ 120/5000 | 解析が走っている | クリックで中止 |
| 10:31:04 時点 | この時刻の結果を表示している | ソースを変えたらクリックで再解析 |
| 失敗 | 解析に失敗した | クリックでログ（出力チャネル「Call Hierarchy Exporter」） |

### 「呼び出し元を表示」で出るメッセージ

| メッセージ | 原因 | 対処 |
|---|---|---|
| まだ解析していません。解析しますか？ | 解析前 | ［解析する］ |
| このファイルは解析対象に入っていません | `source.folders` の外、除外パッケージ、解析後に増えたファイル | 設定を確認する。新しいファイルなら再解析 |
| メソッドの中にカーソルを置いてください | import 文や宣言部にカーソルがある | メソッドの本体に置く |

宣言の**開始行**しか持っていないため、メソッドの外（本体より後ろのフィールド宣言やクラスの末尾）を指すと
直前のメソッドが選ばれる。ビューの見出しに「`OrderService.save()`（38行目）の呼び出し元」と出るので、
そこで気づける（[#115](https://github.com/instreest/java-call-hierarchy-exporter/issues/115)）。

## 設定ファイル

ワークスペースフォルダ直下に `config.properties` があればそれを使う（複数の `*.properties` があれば選ばせ、覚える）。
無ければ **`project.root` だけの設定を自動生成**し、ソースフォルダ・依存 jar・文字コードはプロジェクトの中身
（`pom.xml` / `build.gradle` / フォルダ構成）から決める（[build-tool-classpath.md](build-tool-classpath.md)）。

細かく効かせたいとき（`entry.packages` を絞る、外部 jar の被参照を見る等）は、ツールバーの `…` →
「設定を config.properties に保存」で書き出して直す。項目の意味は [config/config.properties](../config/config.properties) のコメント。

解析キャッシュは VSCode のワークスペースストレージに置く（ワークスペースを消せば一緒に消える）。
2回目以降の解析は差分だけなので速い。

## 設定（`jche.*`）

| 設定 | 既定 | 意味 |
|---|---|---|
| `jche.javaHome` | 空 | 解析に使う JDK のフォルダ。空なら `JAVA_HOME` → PATH |
| `jche.libFolder` | 空 | 解析本体のフォルダ。空なら同梱の `lib/`。閉域で新しい JDT に差し替えるとき、または開発中に `eclipse-plugin/target/classes/lib` を指すとき |
| `jche.vmArguments` | `[]` | 子プロセスの JVM 引数（`-Xmx4g` など）。VSCode のヒープとは独立 |
| `jche.idleMinutes` | 10 | 問い合わせが無いままこの分数が過ぎたら子プロセスを終了する。0 で常駐 |
| `jche.configFile` | 空 | 解析に使う設定ファイル（フォルダからの相対）。無ければエラー（黙って自動生成に落とさない） |
| `jche.depth` | 5 | 一度に展開する深さ |

## ビルド

```bash
cd vscode-plugin
npm ci
npm run lib        # eclipse-plugin を mvn package して lib/ を集める（JDK 21 以上と Maven が要る）
npm run package    # dist/extension.js に束ねて call-hierarchy-exporter.vsix を作る
```

`lib/` の出どころは Eclipse プラグインのビルドと同じで、JDT の版は `//DEPS` 行が唯一の正
（`test/vscode/package.sh` が食い違いを検出する）。

開発中に実機で動かすには、VSCode で `vscode-plugin/` を開いて F5（拡張機能の開発ホスト）。
`lib/` が無ければ設定 `jche.libFolder` に `eclipse-plugin/target/classes/lib` を指す。

## 検査

| コマンド | 内容 | 要るもの |
|---|---|---|
| `bash test/vscode/run.sh` | `vscode` に触らない層。型検査、実際に子プロセスを起こしての一連のやりとり、木の組み直し、設定の用意、束ねられること | Node 22、npm、JDK 17 以上 |
| `bash test/vscode/package.sh` | `.vsix` の組み立て。`lib/` の版が `//DEPS` と同じこと、入るもの・入らないもの | 上に加えて Maven、JDK 21 以上 |

画面そのものの自動検査（VSCode 本体を落としてくる `@vscode/test-electron`）は採っていない。
CI の時間と閉域環境を考えての判断で、画面は実機で確かめる。
