# 出力ファイルの整理 — Q&A

出力フォルダのファイルが増えてきたので、役割の重なるものをまとめて減らす。
その 1 つ目として、ビルドファイルから集めた依存 jar の一覧（`resolved-classpath.txt`）を実行ログ（`run.log`）にまとめた。
関連: [build-tool-classpath-qa.md](build-tool-classpath-qa.md)（依存 jar の集め方）、
[multi-config-output-folder-qa.md](multi-config-output-folder-qa.md) の Q6（一覧の置き場所の前回の判断）。

## 対応の要点

- `BuildFileClasspath` は一覧をファイルに書かず、`Log.plain` で 1 行ずつログに書く。
  形は旧ファイルと同じタブ区切り（`path<TAB>coordinates<TAB>via`）で、先頭に字下げを付ける
- 出力フォルダに残るのは `call-hierarchy.csv` / `methods.csv` / 設定ファイルの複製 / `run.log`、
  条件付きで `call-conditions.csv` / `contracts-suggested.txt`
- 一覧を書けなかったときの警告（`config.deps.listingFailed`）と、ファイルの場所を添える文言
  （`config.deps.collected.listing`）は要らなくなったので消した

## Q&A

### Q1. 一覧は `run.log` だけに書くか、標準出力にも出すか

**標準出力にも出す（`Log.plain`）。** `run.log` は「標準出力と同じ内容」と README・設定ファイルのコメント・
cli.md で約束しており、ファイルにだけ書く口を作るとその約束が崩れる。
Eclipse / VSCode プラグインのコンソール（`Log.attachSink`）にも同じものが流れるので、
画面で依存 jar の不足に気づいたときにその場で要求元まで追える。

依存 jar が数百あると画面に数百行出るが、ビルドファイルから集めたとき（`library.folders` が空欄）だけで、
`library.folders` / `library.jars` を書いた場合は出ない。時刻の付かない字下げ行なので、前後のログとは見分けられる。

却下した案:

- **`Log` にファイル専用の口を足す** … 上の約束が崩れるうえ、プラグインのコンソールからは見えなくなる
- **件数が多いときだけ省略する** … 省略した部分を見たいときに見る場所が無くなる。記録としての役割を果たさない

### Q2. 依存 jar を取得（ダウンロード）しないのは現状のままでよいか

**よい。仕様として維持する。** 解析時の依存 jar の解決はローカルにあるものだけを使い、ネットワークには出ない。

- ビルドツール（`mvn` / `gradle`）を実行しない。`ProcessBuilder` / `Runtime.exec` は解析側のコードに無い
- `pom.xml` / `build.gradle` をこのツールが読み、jar と POM を `~/.m2/repository`（`~/.m2/settings.xml` の
  `localRepository` があればそこ）、`~/.gradle/caches`（`library.repositories` を書けばその場所）から**ファイルとして**探すだけ
  （`LocalRepositories`）。`settings.xml` のミラーやリモートリポジトリの設定は読まない
- XML の読み取り（`ProjectLayout.parseXml`）は DOCTYPE を拒否するので、外部実体の参照でネットワークに出ることもない
- 無い jar は数えて警告（`[WARN]`）とともに座標と要求元の経路をログに出し、その型を使う呼び出しは欠けたまま解析を続ける

解析のたびに社内リポジトリへ勝手に問い合わせないよう、安全側に倒した仕様である。
依存を揃えたいときは、利用者が Eclipse や `mvn dependency:go-offline` などで事前にローカルへ取得する。
起動コマンドがネットワークから取得するのは JBang・JDK・このツール自身の依存（JDT の jar）だけで、
それも必ず確認を出す（[network-download-confirm-qa.md](network-download-confirm-qa.md)）。
