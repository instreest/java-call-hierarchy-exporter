# 実装QA — ビルド・実行環境（Issue #26 → #31）

ビルド・実行環境まわりで迷った点とその判断を残します。
Issue #26 で Gradle Wrapper による1コマンド化を行い、**Issue #31 で依存性解決を
JBang に置き換えました**。Gradle 時代の記録はこの改訂で置き換えていますが、
そこで踏んだ罠のうち今も効いているものは、対応する質問の中に引き継いでいます。

判断の軸は Gradle 時代から変わらず、**「利用者の環境を汚さない」** と
**「手元のJDKが何であっても同じ結果になる」** の2つです。前者はこのツールが
業務端末に一時的に置かれて使われることを想定しているため、後者は解析結果が
JDTを動かすJDKに左右されるためです。

---

## Q1. なぜ Gradle から JBang に替えたのか

理由は3つです（Issue #31）。

1. **JVMの版指定と自動取得が1行で済む。** Gradleでは toolchain の宣言
   （build.gradle）、リゾルバプラグイン（settings.gradle）、自動取得の既定
   （gradle.properties）、実行側への適用（`javaLauncher`）と4箇所に散っていた。
   JBangではソース冒頭の `//JAVA 25` だけで、コンパイルと実行の両方が固定される。
   Gradle時代に「コンパイルだけtoolchainで実行は別JVM」という罠を実際に踏んだ
   （旧Q3b）ことを考えると、**分けようがない**構造そのものが利点。
2. **設定ファイルが減る。** build.gradle / settings.gradle / gradle.properties /
   gradle/wrapper/* が消え、増えたのはソース冒頭の3行と jbangw / jbang-catalog.json。
   依存の宣言（`//DEPS`）がソースと同じファイルにあるので、乖離しようがない。
3. **javac / java 直接実行と干渉しない。** `//DEPS` も `//JAVA` も javac には
   ただのコメント。ロックダウンされた端末向けの javac 経路（README）は
   一切変更なしで生き続ける。単一ファイル・デフォルトパッケージという
   このツールの前提（DESIGN.md §3）と、JBangの「1ファイル＝1スクリプト」は
   相性がよい。

## Q2. jbang本体をどう配るか — jarはコミットせず、SHA-256固定で取得する

`jbang wrapper install` が作る公式ラッパーは `.jbang/jbang.jar`（約9.5MB）を
リポジトリにコミットする方式です。gradle-wrapper.jar（43KB）と同じ発想ですが、
**9.5MBは版を上げるたびに履歴へ積まれる**ため、コミットはやめました。

代わりに `jbangw` が初回実行時に **Maven Central から取得し、スクリプトに
埋め込んだ SHA-256 と照合してから使います**。一致しなければ削除して失敗します。
取得先がMaven Centralなのは、どのみち依存jar（JDT）の解決で到達できる必要がある
ホストだからで、jbang本体のためだけに新しい到達先が増えるわけではありません。
社内ミラーを使う場合は環境変数 `JBANGW_JAR_URL` でURLごと差し替えられます。

なお公式のラッパースクリプトを同梱できなかった事情もあります。`wrapper install` は
「インストール済みのjbangの起動スクリプト」をコピーする作りで、この開発環境では
jbang配布zip（www.jbang.dev / GitHub releases）への到達が遮断されており入手
できませんでした。`jbangw` / `jbangw.cmd` は実行規約（Q3）に合わせて書き起こした
ものです。

## Q3. jbang.jar は「コマンドラインを出力して255で終わる」

ラッパーを自作して分かった、jbangの実行規約です。`java -jar jbang.jar run script.java`
は **スクリプトを実行しません**。実行すべき java コマンドライン（クラスパス展開済み）を
標準出力に書き、**終了コード255**で終わります。それを受けてシェル側が `eval exec` する
のが公式スクリプトの動きで、`jbangw` も同じことをしています。

- sh側: `set -e` のままだと255で即終了してしまうため、この呼び出しだけ `set +e` で囲む
  （実際に踏みました）
- cmd側: コマンドラインはクラスパスを含み4KBを超える（実測）ため、環境変数
  （上限8191文字）で受けず、一時cmdファイルに書いて `call` する

255以外で終わった場合（`version` 等のサブコマンドや失敗）は、出力をそのまま表示して
その終了コードで終わります。

## Q4. 取得物の置き場所 — JBANG_DIR と JBANG_REPO の両方を固定する

「利用者の環境を汚さない」ために、`jbangw` は未設定の場合に限り

| 環境変数 | 既定 | 入るもの |
|---|---|---|
| `JBANG_DIR` | `<プロジェクト>/.jbang` | jbang本体・ビルド済みjar・取得したJDK（`cache/jdks/`）|
| `JBANG_REPO` | `$JBANG_DIR/repository` | 依存jar（Maven形式のローカルリポジトリ）|

を設定します。**`JBANG_REPO` を忘れると依存jarだけ `~/.m2` 相当に落ちて、
「プロジェクト内で完結」が崩れます**（実測で確認）。jbang本体のjarも
`$JBANG_DIR` の下に置くので、外から共有キャッシュを指された場合に
プロジェクト内へ二重に置くことはありません（これも当初はプロジェクト直下
固定にしていて、外部 `JBANG_DIR` 指定時に `.jbang/` が余計に作られる形で発覚）。

ファイル名は `jbang-cli-<版>-all.jar` と版を含めます。`jbangw` の
`JBANG_VERSION` を上げたときに、古いjarを黙って使い続けないためです。

## Q5. 「1コマンド」の下限 — jbangを起動するJavaは消せない

Gradle時代（旧Q5）と同じ結論です。jbang.jar を起動するのに Java 8 以上が
1つ必要で、これはどうやっても消えません。公式のjbangスクリプトは「Javaが
無ければJDKをダウンロードする」ところまでやりますが、`jbangw` では
**明確なエラーメッセージを出して止まる**方に倒しました。ダウンロード処理を
シェルとバッチの両方に持つと監査すべき面が増えるうえ、この開発環境では
その経路（foojay.io）を検証できないためです（Q7）。

ツール本体が使う **JDK 25 はこれとは別**で、`//JAVA 25` により jbang が
解決します。手元のJDK（JAVA_HOME / PATH）が25ならそれを使い、違えば
インストール済みJDKから25を探し、無ければ取得します。boot用のJavaが17でも、
実行は25で行われることを実測で確認しました（jbangが `/usr/lib/jvm` の
17 / 21 / 25 を検出し、25を選ぶところまで確認）。

## Q6. WindowsのUTF-8化 — 「文字コードを固定するな」の罠との折り合い

Issue #31 の要件に「Windows上で実行した場合にターミナルのエンコーディングを
UTF-8とする」があります。一方で DESIGN.md 11章には Gradle 時代に踏んだ
**「標準出力の文字コードを固定すると、Windowsコンソール（MS932）と食い違って
ログだけ文字化けする」** という罠が記録されています。

矛盾しないように、**両側を同時にUTF-8へ揃えます**。

- `jbangw.cmd` が `chcp 65001` でターミナル側をUTF-8にする
- ソースの `//JAVA_OPTIONS -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8`
  （JDK 19+）で出力側もUTF-8にする

片方だけだと化けます。旧来の罠は「出力だけUTF-8に固定して、ターミナルが
MS932のまま」という片側だけの状態のことで、今回は両側を揃えるので起きません。
CSV等のファイル入出力はコード側で常に明示的な文字コードを使っており、
この指定の影響を受けません（`output.encoding` / `source.encoding` はそのまま）。

副作用として、`jbangw.cmd` を実行したコンソールはコードページ65001のまま
残ります（意図的に戻していません。戻すと、続けて `type` したCSVやログが
化けるため）。MS932前提の他ツールへ続けてパイプする場合は注意が要るため、
READMEに明記しました。なお `chcp` はLinux/macには存在しないため sh 側では
何もしません（Linuxの端末は通常UTF-8）。

## Q7. この環境で検証できなかったこと

| 項目 | 結果 |
|---|---|
| jbangw が jbang本体を取得し、SHA-256照合して使う | 確認済み（初回取得〜実行まで） |
| `//DEPS` で JDT 3.46.0 と推移的依存19jarが解決される | 確認済み（`.jbang/repository` に配置） |
| Quick start コマンドがそのまま動く | 確認済み（`--args=` 形式・素のパス形式の両方） |
| boot JDK 17 でも実行JVMは25になる | 確認済み（インストール済みJDKの検出・選択まで） |
| 出力が決定的（ウォーム2回・コールド・javac経路と一致） | 確認済み |
| javac / java 直接実行が影響を受けない | 確認済み（`-Xlint:all` 警告0件のまま） |
| **JDKが1つも無い／25が無い場合の自動取得** | **未確認**。取得元 `api.foojay.io` が開発コンテナで遮断されているため（Gradle時代と同じ）。リクエストが飛びエラーが返る「配線」までは確認 |
| `jbangw.cmd`（Windows実機） | **未確認**。Linux環境のため。`%~dp0` の末尾 `\`、遅延展開、8191文字制限は机上で確認済み |

## Q8. `jbangw CallHierarchyExporter.java` — src/ プレフィックスを消す方法

Issueの To be は `jbangw CallHierarchyExporter.java` で、実ファイルは
`src/CallHierarchyExporter.java` にあります。ソースを直下へ動かすことも
考えましたが、`source.folders=./src` を前提にした設定・ドキュメント・
自己解析の構図が全部動くため、**jbangのカタログ（`jbang-catalog.json`）で
別名を張る**方にしました。

```json
{ "aliases": { "CallHierarchyExporter.java": { "script-ref": "src/CallHierarchyExporter.java" } } }
```

`.java` で終わる別名が有効かは仕様上不明だったので実測で確認しました
（jbangは実在ファイルを先に探し、無ければカタログを引く。どちらの経路でも
同じ結果になることを確認済み）。`jbangw src/CallHierarchyExporter.java` の
直接指定も従来どおり使えます。

`--args="…"` は jbang にとって未知のオプションではなく**プログラム引数**として
そのまま `main()` に届くため、`main()` 側で先頭の `--args=` を剥がしています。
gradlew 時代のコマンド形との見た目の連続性のためで、素のパス指定も同じ結果に
なります（両形式で出力一致を確認）。

## Q9. `copyLibs` の代替 — lib/ は find で集める

Gradleの `copyLibs` タスクは廃止しました（Issue #31 の「build.gradleのタスクは
移行不要」）。`lib/` フォルダが要るのは javac 経路と自己解析（`library.folders=./lib`）
だけで、`jbangw` で一度実行した後なら

```bash
find .jbang/repository -name "*.jar" -exec cp {} lib/ \;
```

で同じものが集まります（`.jbang/repository` はMaven形式で入れ子のため、
`library.folders` に直接指定しても直下しか見ない仕様上、拾われません）。

**同梱設定のまま Quick start を実行すると、初回は型解決失敗が220件出ます**
（`lib/` が無いため）。これはGradle時代の旧提案2と同じ既知事象で、上の1コマンドで
`lib/` を作って再実行すると0件になります。恒久的な解決案として「自分の実行時
クラスパス（java.class.path）を解析クラスパスに自動で足す」ことも考えられますが、
実行経路（jbangw / javac）によって解析クラスパスが変わる＝**「どの経路で実行しても
同じ結果」という保証が壊れる**ため、設定キーとして切るかどうかの判断が必要です。
提案に留めます。

## Q10. jbangの版は jbangw に固定し、無断で上がらないようにした

`jbangw` は `JBANG_VERSION=0.132.1` と、そのjarの SHA-256 を持ちます。
「latestを取る」方式にしなかったのは、jbang本体の挙動が変わると
実行コマンドの組み立て（Q3の規約を含む）ごと変わりうるためです。
版を上げるときは jbangw / jbangw.cmd の2箇所（版とハッシュ）を同時に
書き換えます。JDTの版（`//DEPS`）とは独立です。
