# Call Hierarchy Exporter for VSCode

Java プロジェクト**全体**のメソッド呼び出し階層を一括で解析し、改修の影響範囲を辿るための拡張です。
解析エンジンは [java-call-hierarchy-exporter](https://github.com/instreest/java-call-hierarchy-exporter)（Eclipse JDT）で、
VSCode とは別のプロセス・別の JDK で走ります。

## 標準の「呼び出し階層」との違い

VSCode には標準の呼び出し階層（`Shift+Alt+H`、vscode-java が提供）があります。この拡張はそれの置き換えではありません。

| | 標準の呼び出し階層 | この拡張（影響調査） |
|---|---|---|
| いつ結果が出るか | 即座（開いているファイルの索引から） | **先にプロジェクト全体を解析**してから（分単位のこともある） |
| 何を辿るか | 直接の呼び出し | 直接の呼び出しに加えて、**インタフェース経由・データフロー経由・Spring の DI 経由**の呼び出し元（解決の理由付き） |
| 結果の鮮度 | 常に最新 | 解析した時点のもの。古くなったら再解析する |
| 向いている場面 | 書きながら参照を確認する | 改修前に「どこまで影響するか」を漏れなく洗い出す。CSV に出して Excel で絞る |

「重い解析を明示的に走らせ、その結果を辿る」道具なので、Test Explorer や CodeQL に近い使い方になります。
Java のファイルを開いただけでは何も始まりません。

## 使い方

1. アクティビティバーの「影響調査」を開き、［解析する］を押す
   （または コマンドパレット → `Call Hierarchy Exporter: 解析する`）
2. 解析が終わったら、エディタでメソッドの中にカーソルを置き、右クリック →「呼び出し元を表示 (Exporter)」
3. 木のノードをクリックすると**呼び出している行**が開く。右クリックで「ここを起点にする」「宣言を開く」
4. ⇅ で呼び出し先方向に切り替える

解析の状態（未解析／解析中／何時の結果か）は、Java のファイルを開いているときにエディタ右下の `{}` に出ます。

## 設定ファイル

ワークスペースフォルダ直下に `config.properties` があればそれを使います。無ければ `project.root` だけの設定を
自動生成し、ソースフォルダ・依存 jar・文字コードはプロジェクトの中身（`pom.xml` / `build.gradle` / フォルダ構成）から決めます。
細かく効かせたいときは「設定を config.properties に保存」で書き出して直してください。
項目の意味はツール同梱の [config/config.properties](https://github.com/instreest/java-call-hierarchy-exporter/blob/main/config/config.properties) のコメントにあります。

## 要件

- 解析に使う **JDK 17 以上**（CLI と結果を揃えるなら 25）。設定 `jche.javaHome` → `JAVA_HOME` → PATH の順に探します
- 解析本体（`lib/jche-core.jar` と `lib/jdt/*.jar`）は拡張に同梱します。閉域で新しい JDT に差し替えるときは `jche.libFolder`

## 開発

```bash
cd vscode-plugin
npm ci
npm test            # 型検査 → 検査（vscode に触らない層を Node だけで）
npm run lib         # eclipse-plugin を mvn package して lib/ を集める（JDK 21 以上と Maven）
npm run package     # dist/extension.js に束ねて call-hierarchy-exporter.vsix を作る
```

手元で動かすときは VSCode でこのフォルダを開いて F5（`.vscode/launch.json`。「test/demo を開く」の構成もあります）。
`lib/` を集めていなければ、`eclipse-plugin/target/classes/lib` を設定 `jche.libFolder` に指定します。
設計は [docs/vscode-plugin-design.md](../docs/vscode-plugin-design.md)、判断の記録は
[docs/vscode-plugin-qa.md](../docs/vscode-plugin-qa.md) にあります。
