# Changelog

このファイルは Marketplace の「Changelog」タブに出ます。

## 0.1.0（未公開）

初版。

- 影響調査ビュー（「影響調査 (Call Hierarchy Exporter)」）。エディタでメソッドの中にカーソルを置き、右クリック →「呼び出し元を表示 (Exporter)」で呼び出し元の階層を辿る
- 解析は VSCode とは別のプロセス・別の JDK で走る。解析本体（Eclipse JDT）は拡張に同梱。vscode-java は不要
- 設定ファイル（`config.properties`）が無くても `project.root` だけの設定を自動生成して解析できる（ソースフォルダ・依存 jar は `pom.xml` / `build.gradle` / フォルダ構成から）
- 解析の状態（未解析／解析中／何時の結果か／変更されたファイル数）はエディタ右下の `{}`（Language Status）に出る
- 絞り込み（文字列・深さ・テスト・推定・`exclude.packages`・重複）。再解析は起こさない
- いま見えている木を同じ条件で CSV に出す
- 呼び出し先方向への切り替え、「ここを起点にする」「宣言を開く」
- ソースの保存・追加・削除を数えて ⚠ で知らせる。自動再解析は既定 OFF（`jche.autoAnalyze`）
- 解析用の JDK が無ければ、確認のうえ Adoptium（Temurin 25）から取得できる。閉域では場所の指定で
