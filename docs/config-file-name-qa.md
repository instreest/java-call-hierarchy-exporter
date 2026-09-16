# 設定ファイルの名前（`config.properties` と `jche.properties`）— Q&A

「`config.properties` というファイル名を変えたほうが良いか」の検討と、その結論を残す。
関連: [config-folder-qa.md](config-folder-qa.md)（`config/` に置く判断）、
[eclipse-plugin-ui-qa.md](eclipse-plugin-ui-qa.md) Q14（プラグインが設定ファイルを必須にしない仕組み）。

## 結論

- 本体に同梱する既定の設定ファイルは `config/config.properties` のまま。改名しない
- 読む側では `jche.properties` も既定の名前として受け付ける（`config/config.properties` が無ければ `config/jche.properties`）
- Eclipse プラグインが**利用者のプロジェクトに書き出す**ファイルだけ `config/jche.properties` に変えた
- プラグインが設定ファイルを自動で拾うときは、このツールの項目を持つファイルだけを使う

### Q1. なぜ本体の `config.properties` は改名しないのか

`config/` フォルダの下に置く前提（`CallHierarchyExporter.DEFAULT_CONFIG`、`ConfigCatalog.CONFIGS_DIR_NAME`）なので、
フォルダ名で用途が分かる。`ConfigCatalog.scan` は `config/` 配下の `*.properties` をすべて拾う設計で、
`config.properties` は「一覧の先頭に出る既定・ウィザードのひな形」という位置づけにすぎない。ここを
`jche.properties` にすると、利用者が作る `projA.properties` との並びがかえって不揃いになる。

改名のコストも見合わない。`config.properties` という文字列は（docs を除いても）40 以上のファイルに出る。
`test/regression/*/config.properties` は回帰テストの実行パスそのもので、README・`docs/github-actions.md`・
`action.yml` 周辺・プラグインまで波及する。得られるのは可読性のわずかな向上だけ。

### Q2. では何が問題だったのか

Eclipse プラグインが読み書きする先が**解析対象である利用者のプロジェクト**であること。
`config.properties` は Spring や社内アプリの設定として既に存在することが珍しくないので、

- 無関係な `config.properties` をこのツールの設定として自動採用してしまう（誤検出）
- 「設定を保存」が、利用者にとって意味のある名前の場所へ書きに行く

の 2 つが起きうる。ここだけは実害があるので直した。

### Q3. 自動採用の誤検出をどう防いだか

名前で除くのではなく、**中身で見分ける**ことにした（`ProjectAnalysis.looksLikeJcheConfig`）。
`project.root` / `source.folders` / `entry.packages` のどれかがあれば、このツールの設定として自動で使う。
無ければ使わず、プロジェクト構成からの自動生成（`EclipseProjectConfig`）に落ちる。

名前だけで `config.properties` を候補から外さなかったのは、既にその名前で置いている利用者がいるため。
読む順は `config/jche.properties` → `config/config.properties` → `jche.properties` → `config.properties`。

ビューの「使う設定ファイルを選ぶ…」で利用者が明示したファイルは、この判定を通さずそのまま使う。
明示した意図のほうが、こちらの推測より強い。

読めないとき（文字コードが壊れている等）は「らしくない」と判断する。誤って別物を掴んで妙な解析結果を出すより、
自動生成の設定で動くほうが害が小さい。

### Q4. 本体側で `jche.properties` を「追加候補」にとどめた理由

`config.properties` を読まなくすると、既存の利用者の環境と `test/regression/` が一斉に壊れる。
足すだけなら壊れるものが無い。`ConfigCatalog.DEFAULT_CONFIG_NAMES` に順序付きで持ち、
一覧の並び・ウィザードのひな形・引数省略時の既定の 3 か所が同じ順を見る。

### Q5. 過去の QA ドキュメントの `config/config.properties` という記述は書き換えるか

書き換えない（[multi-config-output-folder-qa.md](multi-config-output-folder-qa.md) Q21 と同じ判断）。
QA ドキュメントはそのときの判断の記録で、現在の仕様書ではない。
なお同 Q15 には「既定を `config.properties`（作業ディレクトリ）にした」とあるが、現在の既定は
`config/config.properties` である（[config-folder-qa.md](config-folder-qa.md) の置き場所に戻っている）。
実行時に参照されるパスは README・`config/config.properties`・`test/regression/` にしか無い。
