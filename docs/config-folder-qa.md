# 既定の設定ファイルを config/ に置く — 実装時の QA 一覧

[Issue #62](https://github.com/instreest/java-call-hierarchy-exporter/issues/62)
「コンフィグは config/config.properties のように格納したい」の対応で、迷ったこと・困ったことと、その結論を
Q&A の形で残す。

対応の要点:

- 既定の設定ファイルをリポジトリ直下の `config.properties` から `config/config.properties` へ移した（`git mv`）
- 引数を省略したときの既定値（`CallHierarchyExporter.DEFAULT_CONFIG`）を `config/config.properties` に変えた
- 出力の既定はこれまでどおり `output.folder=./output`（設定ファイルと同じフォルダの `output/`）。
  既定の設定ファイルなら `config/output/<解析開始日時>_<プロジェクト名>/` にできる
- `config/config.properties` の `project.root` の既定値は、1 段深くなったぶん `../java-call-hierarchy-exporter` から
  `../../java-call-hierarchy-exporter` に変えた
- `.gitignore` の `/output/` を `/config/output/` に変えた（キャッシュの `/.cache/` は場所が変わらないのでそのまま）

---

## 置き場所

### Q1. なぜ [Issue #60](https://github.com/instreest/java-call-hierarchy-exporter/issues/60) で直下へ移したものを config/ へ戻すのか

#60 では「既定のコンフィグはプロジェクト root 直下に」という要望に従って
`config/config.properties` → `config.properties` へ移した（[multi-config-output-folder-qa.md](multi-config-output-folder-qa.md) の Q15）。
今回はその逆で、設定ファイルとその出力をリポジトリ直下に散らかさず `config/` にまとめたい、という要望。
実装としては「既定値の文字列」と「設定ファイル内の相対パスの深さ」だけの違いなので、素直に戻した。
どちらが正しいという話ではなく、複数プロジェクトぶんの設定ファイル（`config/app-a.properties` …）と
その出力（`config/output/`）が 1 つのフォルダに収まるほうが、リポジトリ直下が読みやすい。

`config/` の下に置くと、リポジトリ直下に生えるのは実行時には `.cache/` だけになる。

### Q2. 既定値をパス区切り `/` 込みの `"config/config.properties"` にして Windows で動くか

動く。`Paths.get("config/config.properties")` は Windows でも `/` を区切りとして解釈する（`java.nio.file` の
既定の `FileSystem` は `/` を常に受け付ける）。表示（`System.err` の「既定値の「…」で実行します。」や
ログの `設定: …`）は `Path#toString` を通るので、Windows では `config\config.properties` と出る。
定数を `Paths.get("config", "config.properties")` に分ける手もあるが、メッセージに出す文字列としては
1 本の相対パスのほうが読みやすいので、そのままにした。

### Q3. 旧い場所（リポジトリ直下の `config.properties`）を読む後方互換は入れるか

入れていない。「引数を省略したときにどのファイルを読むか」だけの話で、引数で明示すれば今までどおり
どこに置いた設定ファイルでも読める。既定値のフォールバックを増やすと「どちらが読まれたか」が
実行するまで分からなくなり、`設定: <パス>` のログを毎回出している意図（どのファイルで動いたかを
はっきりさせる）と食い違う。手元に旧い場所の設定ファイルが残っている利用者は、`config/` へ移すか、
引数でそのパスを渡せばよい。

### Q4. `project.root` の既定値を `../../java-call-hierarchy-exporter` にしたのはなぜか

`project.root` の相対パスの起点は「設定ファイルが置かれているフォルダ」なので、設定ファイルが 1 段深くなると
`..` が 1 つ増える。同梱の設定ファイルは「このツール自身を解析する」例になっているので、
`config/` から見た自リポジトリは `../../java-call-hierarchy-exporter`。
実際に引数なしで動かし、`プロジェクトルート: <リポジトリ>`、
`出力フォルダ: <リポジトリ>/config/output/<日時>_java-call-hierarchy-exporter` になることを確認した。

### Q5. 出力の既定はコードを変えたのか

変えていない。`output.folder` の既定値は `./output` のままで、相対パスの起点が「設定ファイルのフォルダ」
（`Config.resolveUnderConfigDir`）なので、設定ファイルを `config/` へ移した時点で出力は自動的に
`config/output/` になる。Issue の To be「出力フォルダは既定でコンフィグファイルと同じフォルダ内に作成する」は
#60 の時点で満たしている挙動で、今回はその挙動のままファイルの場所だけが動いた形。

### Q6. キャッシュの場所は変わるか

変わらない。キャッシュは設定ファイルの場所ではなく、ツール自身のプロジェクトフォルダ（`ToolRoot` が
`src/CallHierarchyExporter.java` を目印に特定する）の `.cache/<プロジェクト名>_<ハッシュ>/` に置く
（[multi-config-output-folder-qa.md](multi-config-output-folder-qa.md) の Q9）。
設定ファイルを `config/` へ移しても `.cache/` はリポジトリ直下のままなので、`.gitignore` の `/.cache/` は
そのままでよい。`/output/` だけを `/config/output/` に変えた。

---

## 回帰テスト

### Q7. 「引数を省略したときに `config/config.properties` を読む」を回帰テストに足すか

足していない。回帰テスト（`test/regression/`）は `test/demo` などの小さなプロジェクトを、ケースごとの
設定ファイルを**引数で明示して**解析する形になっている。既定値の経路を検査するには「作業ディレクトリを
リポジトリ直下にして引数なしで起動する」ことになり、同梱の `config/config.properties` が指すのは
このツール自身（ソース 71 ファイル）なので、ケース 1 つぶんとしては重い。しかも出力先が
`config/output/`、キャッシュがリポジトリ直下の `.cache/java-call-hierarchy-exporter_<ハッシュ>/` と
他のケースから独立した場所になり、後片付けの対象が増える。

代わりに、この作業内では実際に引数なしで起動して以下を目視で確認した。

- 標準エラーに「設定ファイル（config.properties）のパスが指定されていません。/ 既定値の
  「config/config.properties」で実行します。」が出る
- `設定: <リポジトリ>/config/config.properties` が読まれる
- 出力が `<リポジトリ>/config/output/<日時>_java-call-hierarchy-exporter/` にできて、
  `call-hierarchy.csv` / `methods.csv` / 設定ファイルの複製 / `run.log` が揃う

引数ありの経路（回帰テストが見ている経路）は設定ファイルの場所に依存しないので、全ケース PASS のままである
ことも確認した。

### Q8. この作業環境での確認

jbang が JDK を取得できない環境なので（[multi-config-output-folder-qa.md](multi-config-output-folder-qa.md) の Q19 と同じ）、
`pom.xml`（Eclipse 用）で依存を解決してコンパイルし、`JCHE_CMD="java -cp target/classes:<JDTのjar> CallHierarchyExporter"`
で `bash test/regression/run.sh` を動かした。全ケース PASS。
`javac --release 17 -Xlint:all -Werror -Xdoclint:all,-missing` も通る。`bash test/pom/run.sh` も PASS。
Windows の `test\regression\run.cmd` はこの環境では動かせないので CI（`.github/workflows/smoke.yml`）に委ねるが、
今回の変更は回帰テストが渡す設定ファイルのパス（ケースごとの `whole/config.properties` 等）には触れていない。

---

## 文書

### Q9. 書き換えた文書と、書き換えなかった文書

書き換えたのは、実行時に参照されるパスが載っているもの。

- `README.md` … Quick start の編集対象、実行例（jbang / `java -cp` / Eclipse の実行構成）、
  出力フォルダの図（`config/output/<日時>_<プロジェクト名>/`）、複数設定の例（`config/app-a.properties` …）
- `config/config.properties` … 先頭の説明、`project.root` の既定値、`output.folder` の既定の説明
- `src/CallHierarchyExporter.java` の使い方コメントと `DEFAULT_CONFIG`、`src/jche/config/Config.java` の
  クラスコメント（設定項目の一覧は `config/config.properties` にある、という参照）
- `prompts/prompt-A-minimal.md` / `prompts/prompt-B-detailed.md` … 「引数省略時の既定」の 1 行と
  設定ファイルの節の見出し。プロンプト B の fixture は元から `config/config.properties` を使う形なので変更なし
- `.gitignore` … `/output/` → `/config/output/`

書き換えていないのは過去の QA ドキュメント（`docs/*-qa.md`）。当時の作業記録なので、そのときの
記述のまま残す（[samples-demo-move-qa.md](samples-demo-move-qa.md) の Q5、
[multi-config-output-folder-qa.md](multi-config-output-folder-qa.md) の Q21 と同じ判断）。
今回のように前の結論を戻す変更があると過去の記録と食い違って見えるが、それぞれの Issue の時点で
何をどう決めたかが追えるほうが値打ちがある。現在の挙動は README とこのファイルが示す。
