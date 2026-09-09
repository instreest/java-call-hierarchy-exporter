# 起動コマンドと対話モード（CLI アプリ）— 実装時の QA 一覧

[Issue #63](https://github.com/instreest/java-call-hierarchy-exporter/issues/63)
「CLI アプリを作成したい」の対応で、迷ったこと・困ったことと、その結論を Q&A の形で残す。

対応の要点:

- リポジトリ直下に起動コマンド `jche.sh`（bash）/ `jche.cmd`（Windows）を置いた。どこから実行してもよく、
  引数なしなら対話モード、設定ファイルを引数に渡せば従来どおり対話なしで解析する
- 対話モードは `src/Jche.java`（`jche.cli` パッケージ）。メニューは 4 つ:
  解析の実行（設定ファイルの一覧から選ぶ）/ 設定ファイルの作成ウィザード / 環境設定 / 実行環境の状態
- JDK / JBang の置き場所（`JBANG_DIR`）・依存 jar の置き場所（`JBANG_REPO`）・ヒープ上限・`jbang run` の追加オプションは
  `launcher.properties`（リポジトリ直下、Git では追跡しない）に持ち、起動コマンドが環境変数にして jbang を呼ぶ。
  初回は置き場所を尋ね、既定は「このプロジェクトの中（`.jbang/`）」
- 環境設定を変えたら、その場で再起動して反映できる（アプリが目印ファイルを置き、起動コマンドがやり直す）
- 解析の処理は `CallHierarchyExporter.runAll` に切り出し、対話モードからも jbang 直接実行からも同じものを呼ぶ

Issue の Requirements は空欄だったので、To be の文（「JDK ダウンロード先などの環境変数を制御」「ユーザー確認をはさんで
プロジェクトフォルダ内にインストール先を変更」「どのコンフィグで実行するかを選択」）を要件として読み、
それに「設定ファイルを作る」を加えた（Q12）。Claude Code のような自由入力のチャット UI にはしていない（Q1）。

---

## 全体の形

### Q1. 「ClaudeCode のような CLI アプリ」をどういう形にしたか

番号で選ぶメニュー方式の対話プログラムにした。自由入力のプロンプトや画面全体を描き直す TUI（カーソル移動・色）ではない。

- このツールの操作は「設定を選ぶ → 実行する → 結果の場所を見る」で、選択肢は数個しかない。番号メニューなら
  説明を読みながら選べて、次に何を入力すべきか迷わない
- 画面制御をする外部ライブラリ（JLine 等）を入れると依存が JDT 以外に増える。このツールは閉域ネットワークで
  Eclipse 同梱の jar だけからでも動かせることを保っており（README の Pleiades の節）、依存を足すとその経路が壊れる。
  Windows のコマンドプロンプトでの端末制御は不具合の温床でもある
- 標準入出力しか使わないので、答えをパイプで流し込めば自動テストができる（Q18）

「ClaudeCode のような」は、コマンド 1 つで立ち上がりその中で操作が完結する、という意味に取った。

### Q2. なぜシェルスクリプト（bash / cmd）と Java の 2 段構えにしたか。全部 Java にできないか

JDK と JBang の置き場所は、**Java が起動する前に**決まらなければならないため。

このツールは jbang が取得した JDK 25 で動く。その JDK をどこに置くか（`JBANG_DIR`）は、jbang のラッパースクリプト
（`jbangw/jbang`）が JDK を取得するときに読む環境変数で決まる。つまり「置き場所を選ぶ」Java プログラムを動かすには
すでに JDK が要り、鶏と卵になる。Issue の「JDK ダウンロード先などの環境変数を制御」は、Java の中からは
原理的にできない。

そこで役割を分けた。

| 役割 | 担当 |
|---|---|
| `launcher.properties` を読んで環境変数にし、jbang を呼ぶ。初回に置き場所を尋ねる。再起動 | 起動コマンド（`jche.sh` / `jche.cmd`） |
| メニュー、設定ファイルの選択と作成、環境設定の**書き換え**、状態表示、解析の実行 | Java（`src/Jche.java`、`jche.cli`） |

Java 側は `launcher.properties` を書き換えるだけで、効くのは次の起動から。その隙間を「その場で再起動」で埋めた（Q5）。
シェル側は必要最小限（初回の 2 択と、ファイルの読み込み）にとどめ、判断や表示はできるだけ Java に寄せた。
bash と cmd で同じ内容を 2 回書くことになるため、シェル側が増えるほど食い違いの元になる。

### Q3. 対話モードの入口を `CallHierarchyExporter.java` に足さず、別の `src/Jche.java` にした理由

`CallHierarchyExporter.java` の「引数なしで動かすと作業ディレクトリの `config/config.properties` を読む」挙動を変えないため
（[docs/config-folder-qa.md](config-folder-qa.md) で決めた既定）。
引数なしを対話モードに変えると、それに頼っているバッチが黙って止まって入力待ちになる。

代わりに `main` の中身を `runAll(configPaths, toolRoot)` に切り出し、`System.exit` を `main` にだけ残した。
`Jche.java` は引数があれば `runAll` を呼んで終わり、無ければ対話モードに入る。解析の処理は 1 つだけで、
対話モードから動かしても jbang で直接動かしても同じ結果になる（回帰テストは両方の経路で通した。Q19）。

jbang はスクリプトごとに `//DEPS` `//JAVA` を読むので、`Jche.java` にも同じ行がある。二重管理になるが、
`test/pom/run.sh` に「2 つのスクリプトの `//DEPS` と `//JAVA` が同じであること」の検査を足して、
片方だけ書き換えたときに CI で止まるようにした。

### Q4. 解析をサブプロセスではなく同じ JVM の中で動かして問題ないか

同じ JVM で動かした。サブプロセス（もう一度 jbang を起動）にすると、JVM の起動と jbang の依存解決が
毎回かかり、対話モードで設定を選んで実行するたびに数秒待たされる。

同じ JVM で繰り返し動かすには、前の実行の状態が残らないことが要る。確かめたのは次の点。

- `jche.util.Log` の静的な状態は「経過時間の基準点」と「複写先のファイル」だけで、どちらも設定ごとに
  `resetClock()` / `attachFile()` / `detachFile()` で付け替えている（複数設定の連続処理で既に前提になっていた。
  multi-config の Q14）
- それ以外に `static` で書き換えられるフィールドは無い（`grep` で確認）
- `OutOfMemoryError` になった場合は `runAll` が捕まえてログに出し、対話モードに戻る。JVM が不安定に
  なっていることはあるが、対話モードでの次の操作は「終了」か「ヒープ上限を上げて再起動」なので実害は小さい

ヒープ上限を対話モードから変えられるようにしたのは、この「同じ JVM」の制約の裏返しでもある。
解析中に足りないと分かっても、その JVM の上限は変えられない。設定を書いて再起動する（Q8）。

---

## 起動コマンドと launcher.properties

### Q5. 環境設定を変えたあと、どうやって反映するか（再起動の仕組み）

アプリが目印ファイル `.cache/launcher.restart` を置いて終了し、起動コマンドが「終了後に目印があればもう一度
起動する」ループを回す。

- 終了コードで伝える方法は採れない。現在の `jbangw/jbang.cmd`（本家そのまま）は jbang 本体の終了コードを
  呼び出し元へ返さない既知の問題があり（[jbangw/README.md](../jbangw/README.md)、`smoke.yml` の
  「known to fail」）、Windows では終了コードが常に 0 になる。ファイルなら OS を問わず確実に伝わる
- 目印の置き場所を `.cache/` にしたのは、ツール自身の作業ファイル（解析キャッシュ）と同じ場所で、
  Git で追跡されず、`ToolRoot` が決めるプロジェクトフォルダの下だから。「前回使った設定」の記録
  `.cache/recent-configs.txt` も同じ理由でここ
- 再起動のたびに前回の環境変数が残らないよう、bash はサブシェル `( load_settings; exec jbang … )`、
  cmd は `setlocal` / `endlocal` で囲んでいる。`JBANG_DIR` を空欄に戻したのに前回の値が残る、という事故を防ぐ
- 再起動できるのは起動コマンド経由のときだけ。jbang で `Jche.java` を直接動かしたときは環境変数 `JCHE_ROOT`
  が無いので、「次回 jche.sh から起動したときに効く」と表示して終わる

Java 側から起動コマンドを子プロセスとして起動し直す方法（`ProcessBuilder` で `jche.sh` を呼ぶ）は、親の JVM が
残ったまま子が動く形になり、Windows では親が使っている JDK フォルダを子が消せない・置き換えられないといった
問題を生むのでやめた。「終了して、外側がやり直す」が一番単純で壊れにくい。

### Q6. 初回の「置き場所を選ぶ」問いを、Java ではなくシェル側で出す理由と、既定を「プロジェクトの中」にした理由

Q2 のとおり、問いに答える前に JDK を取得してしまっては意味が無いので、シェル側で出すしかない。
出すのは標準入力が端末のときだけ。パイプや CI（標準入力が端末でない）では尋ねずに JBang の既定（`~/.jbang`）で進む。
問いは 2 択（プロジェクトの中 / ホーム）だけにし、細かい指定はアプリの「環境設定」に任せた。

既定を「プロジェクトの中」にしたのは、Issue の「プロジェクトフォルダ内にインストール先を変更する」を、
このツールの利用者（社内のプロジェクトで、他のツールと環境を共有したくない）にとって望ましい側と読んだから。
プロジェクトの中なら、`.jbang/` を消せば取得したものは全部消え、他の JBang スクリプトや Maven の設定に影響しない。
既に JBang を使っている人は 2 を選べば今までどおり共有できる。

`jche.cmd` の文字コードは MS932 にしてある（Q15）。

### Q7. `launcher.properties` の書式をなぜ「キー＝環境変数名」の素朴な `KEY=VALUE` にしたか

読む側が bash の `read` と cmd の `for /f` だから。`java.util.Properties` の書式（`\` のエスケープ、`:` 区切り、
Unicode エスケープ）をシェルで正しく解釈するのは無理があり、Windows のパス `C:\work` を `C:\\work` と書かせる
のも不親切。素朴な `KEY=VALUE` なら、bash は `IFS='=' read`、cmd は `for /f "tokens=1,* delims=="` で読める。

キーをそのまま環境変数名（`JBANG_DIR` / `JBANG_REPO` / `JCHE_JAVA_OPTS` / `JCHE_JBANG_OPTS`）にしたのは、
「このファイルは環境変数を決めている」ことが見て分かり、シェル側でキーごとの対応表を持たなくて済むから。
cmd では `set "%%A=%%B"` の 1 行で全部の行を環境変数にできる。知らないキー（利用者が `JBANG_JDK_VENDOR` や
`HTTPS_PROXY` を足した場合）もそのまま環境変数になり、Java 側が書き換えるときも保持する。

相対パスはリポジトリ直下が起点で、起動コマンドが絶対パスにしてから渡す。jbang は作業ディレクトリに依らず
`JBANG_DIR` を見るので、相対のまま渡すとどこから実行したかで場所が変わってしまう。

Git では追跡しない（`.gitignore`）。パス・ヒープ上限は環境ごとのもので、共有するとかえって壊れる。

### Q8. ヒープ上限を JVM に渡す方法。`JDK_JAVA_OPTIONS` ではなく `jbang run -R` にした理由

`jbang run -R-Xmx4g …` の形（`-R` = `--runtime-option`。解析を動かす JVM にそのまま渡る）にした。

`JDK_JAVA_OPTIONS` 環境変数でも JDK 9 以降の `java` は拾うが、jbang 自身の JVM（依存解決をするだけ）にも同じ
上限が掛かり、しかも `java` が「NOTE: Picked up JDK_JAVA_OPTIONS」と毎回標準エラーに出す。
`//JAVA_OPTIONS` 指示行はスクリプトに固定で書くものなので、利用者ごとに変えられない。
`JBANG_JAVA_OPTIONS` は jbang 自身の JVM 向けで、スクリプトの JVM には渡らない。

`JCHE_JAVA_OPTS` は `-Xmx` に限らず空白区切りで複数書ける（`-Xmx4g -Xss2m`）。起動コマンドが 1 つずつ `-R` を付ける。
対話モードの「ヒープ上限」は、そのうち `-Xmx` だけを書き換えて他は保つ。

### Q9. `jbang run` の追加オプション（`JCHE_JBANG_OPTS`）を設定に入れた理由

閉域ネットワーク向けの `--offline`（取得済みの JDK と jar だけで動き、ネットワークに出ない）と、
JDK 25 を取得できない環境で手元の JDK を使う `--java 21`（Q19）のため。どちらも jbang のオプションなので、
起動コマンドが `jbang run` の直後に置く。自由記述にしたのは、jbang のオプションを 1 つずつメニューにすると
きりが無く、使うのは上の 2 つがほとんどだから。メニューではこの 2 つを例として示す。

### Q10. 依存 jar の置き場所（`JBANG_REPO`）も切り替え対象にした理由

JDK と JBang 本体は `JBANG_DIR` の下に入るが、依存 jar（JDT 一式、約 15 MB）は jbang が Maven のローカル
リポジトリ（`~/.m2/repository`）に置く。「プロジェクトの中」を選んだのにホームの `.m2` に書かれては
「他の環境を汚さない」にならない。jbang は環境変数 `JBANG_REPO` でこの場所を変えられる（`dev.jbang.Settings`
が読む）ので、置き場所を「プロジェクトの中」にしたときは `JBANG_REPO=.jbang/repository` も一緒に設定する
（確認つき。Eclipse の m2e と共有したい人は既定のままにできる）。

### Q11. 置き場所を変えたとき、古い場所に取得済みのものはどうするか

消さない。新しい場所には次回の起動で取得し直す（ネットワークが要ることを表示する）。

- ホームの `~/.jbang` は他の JBang スクリプトと共有しているかもしれないので、このツールが消してはいけない
- プロジェクトの中の `.jbang/` はこのツール専用なので消してよいが、**実行中の JVM がその中の JDK を使っている**。
  Windows では使用中のファイルは消せないし、Linux でも動作中の JVM の下を消すのは不健全。
  「終了後に手で消してよい」とパスと大きさを表示するにとどめた。起動コマンド側で消す仕組み（目印ファイル）も
  考えたが、数百 MB のフォルダを黙って消す処理をシェルに持たせたくなかった

---

## 対話モードの中身

### Q12. 「設定ファイルを新しく作る」を入れた理由と、尋ねる項目の範囲

Issue の To be には無いが、「どのコンフィグで実行するかを選択可能」にするには選べる設定ファイルが複数ある
状態が要り、その作り方が「`config/config.properties` をコピーしてエディタで書き換える」のままでは、アプリの中で操作が
完結しない。README の Quick start が「書き換える」と言っている 4 項目（`project.root` / `source.folders` /
`library.folders` / `source.encoding`）と、全体モードか起点指定かを決める `entry.packages` だけを尋ね、
残りはひな形（`config/config.properties`）の既定値のまま写す。

- ひな形の行を置き換える方式にしたのは、全項目の説明コメントが新しいファイルにも残るから。
  `Properties.store()` で書くとコメントが全部消え、あとで `exclude.packages` を直したいときに
  `config/config.properties` を見に行くことになる
- `project.root` を入力すると、`src/main/java` / `src` / `<モジュール>/src/main/java` の有無、`pom.xml` /
  `build.gradle` の有無、`pom.xml` の `project.build.sourceEncoding` を見て既定値を埋める。
  当てにならなければ入力で上書きできる
- 書き先は `config/<名前>.properties`（既定の設定ファイルと同じフォルダ。[Issue #62](https://github.com/instreest/java-call-hierarchy-exporter/issues/62)
  で「設定ファイルとその出力は `config/` にまとめる」と決まっている）。相対パスの起点はその設定ファイルのフォルダなので、
  `project.root` は config/ からの相対（上位へ 2 段以内で書けるとき）か絶対パスで書く。区切りは常に `/`。`.properties` では
  `\` がエスケープなので、Windows のパスをそのまま書くと壊れる（`Config` は `Properties.load` で読む）
- 作ったファイルは Git の追跡対象になりうる（`config/config.properties` と同じ扱い。`.gitignore` は `config/` 直下の
  フォルダ＝実行ごとの出力だけを除外している）。絶対パスを含む個人用の設定を共有したくなければ、コミットしなければよい

### Q13. 設定ファイルの一覧はどこを探すか

`config/` の下（サブフォルダ含む）。既定の設定ファイル `config/config.properties` がここにあり、Issue #62 で
実行ごとの出力もここにできる（`config/<解析開始日時>_<プロジェクト名>/`）と決まっている。出力フォルダの中には
設定ファイルの複製が入るので、フォルダ名がその形のものの下は一覧から除く（複製を選んで実行すると、
その出力フォルダの中にさらに出力フォルダができて紛らわしい）。
`test/` の下にも設定ファイルはあるが、回帰テスト用なので一覧に出さない。一覧に無い場所は `p` でパスを入力する。

一覧には `project.root` の値を添えて出す。ファイル名だけでは「どのプロジェクト向けか」が分からないため。
前回使った設定は `.cache/recent-configs.txt` に残し、一覧で「← 前回」を付けたうえで既定の選択にする。
2 回目からは Enter → Enter で同じ解析が走る。

### Q14. 標準入力をどう読むか。`System.console()` を使わなかった理由

`System.in` を `stdin.encoding`（JDK 25 で追加。無ければ `native.encoding`）の文字コードで読む。
Windows のコマンドプロンプトでは MS932 になるので、日本語を含むパスの入力もそのまま読める。

`System.console()` は使わない。JDK 22 以降は標準入力が端末でなくても `Console` が返ることがあり
（既定の実装が JLine ベースのものに変わった）、端末とパイプで挙動が変わる。パイプで流し込むテスト（Q18）と
端末で同じコードを通したい。行編集の恩恵（履歴、カーソル移動）は捨てた。

出力は `System.out` に任せる（コンソールの文字コードで書く）。ツール全体の方針
（`src/CallHierarchyExporter.java` の冒頭: UTF-8 に固定しない、`chcp` もしない）と同じ。

### Q15. `jche.cmd` の文字コードを MS932 にした理由と、`launcher.properties` の文字コード

このリポジトリのファイルは UTF-8 で保存されているが、`jche.cmd` だけは MS932（Shift_JIS、CRLF）にした。
cmd はバッチファイルをコンソールのコードページ（日本語 Windows では MS932）として読むので、UTF-8 のままだと
初回の問い（利用者が画面で読んで答える）の日本語が化ける。`chcp 65001` で切り替える方法は、日本語 Windows で
画面が消えるので採らない（`src/CallHierarchyExporter.java` の冒頭と同じ判断）。
`test/regression/run.cmd` は UTF-8 のままだが、あれは CI のログに出るだけで人が画面で読むものではない。

最初は「画面の文言だけ ASCII の英語にする」で逃げていたが、利用者の指摘で MS932 に改めた。
編集するときは MS932 のまま保存すること（エディタが UTF-8 で保存し直すと化ける。`.gitattributes` で
`-text` にしてあるので Git が改行や文字コードを触ることはない）。
MS932 では 2 バイト目が `\` `^` `|` になる文字（「ソ」「ポ」「表」等）があり、cmd の行では意図しない
区切りに読まれうるので、`jche.cmd` の日本語からは避けている。

`launcher.properties` は Java 側が `native.encoding`（cmd では MS932、Linux では UTF-8）で読み書きする。
cmd の `for /f` はファイルをコンソールのコードページで読むので、Java が UTF-8 で書くと日本語を含むパス
（`C:\Users\太郎\…`）が壊れる。`jche.cmd` が初回に書くファイルも MS932 になるので、Java 側と揃う。
bash（`jche.sh`）が書く初回のファイルは UTF-8（スクリプトの文字コード）だが、Git Bash では Java が MS932 として
読むのでコメント行が化けて見える。キーと値は ASCII なので実害は無く、Java 側が書き換えるときにコメントは付け直す。

### Q16. Git Bash でのパスの扱い

Git Bash（MSYS）では `$ROOT` が `/c/work/...` の形になる。この値を環境変数 `JBANG_DIR` として Java に渡すと、
Java は `C:\c\work\...` と解釈してしまう（MSYS の自動変換はコマンドライン引数には効くが、環境変数の値には効かない）。
`jche.sh` は `cygpath -m` があればそれで `C:/work/...` に変換してから渡す。

### Q17. ツールのプロジェクトフォルダをどう伝えるか。起動コマンドが `cd` しない理由

起動コマンドは自分のあるフォルダを環境変数 `JCHE_ROOT` で渡し、`Jche.java` は `ToolRoot.at()` でそれを使う。
`cd` してから jbang を呼ぶ方法もあるが、対話なしの解析で引数に渡した設定ファイルの相対パスが、利用者の作業
ディレクトリではなくツールのフォルダ起点になってしまう（`..\tool\jche.cmd myproj.properties` が動かない）。
作業ディレクトリは触らず、必要な情報だけ渡す。

`JCHE_ROOT` が無いとき（jbang で `Jche.java` を直接動かしたとき）は従来の `ToolRoot.locate()`（作業ディレクトリと
その上位から `src/CallHierarchyExporter.java` を探す）に戻る。

---

## テスト

### Q18. 対話モードをどう自動テストするか

`test/cli/run.sh`。メニューへの答えを `printf '1\np\n…\n' | ./jche.sh` のようにパイプで流し込む。
標準入力が端末でないので、起動コマンドの初回の問いは出ず、アプリは入力が尽きたら静かに終わる
（`Terminal.EndOfInput` を最上位で 1 回だけ捕まえる）。見るのは `--help`、対話なしの解析と終了コード、
状態表示、ウィザードが作ったファイルの中身（`project.root=../test/demo`、コメントが残っていること）、
パス指定での解析と `recent-configs.txt`、ヒープ上限の変更 → `launcher.properties` の書き換え → 再起動 → 次の起動で
反映（状態表示に `512 MB`）。

引っかかったのは再起動の直後。再起動前の JVM が `BufferedReader` でパイプの残りまで読み込んでしまうので、
再起動後の JVM は入力が尽きた状態で始まり、すぐ終わる。「再起動後の反映」は別の起動で確かめるようにした。
端末では起きない（端末は 1 行ずつしか渡さない）。

`launcher.properties` は利用者のものなので、テストは退避して最後に戻す（`trap`）。`config/cli-test.properties` と
その出力、`.cache/recent-configs.txt` も消す。ログの照合は ASCII だけ（標準出力の文字コードは端末に依るため。
回帰テストと同じ方針）。ただし起動コマンド自身（bash）が出す「設定を反映するため再起動します」は
スクリプトの文字コード（UTF-8）で出るので日本語で照合できる。

ヒープ上限の照合値を最初 `777m` にしていたら、`Runtime.maxMemory()` は `778 MB` と出た（JVM の丸め）。
`512m` なら `512 MB` になるのでそちらにした。

Windows の `jche.cmd` はこの環境では動かせないので、CI（`smoke.yml` の `regression-windows`）に
「対話なしの解析で出力フォルダができる」「パイプで `4` を流し込むと状態表示が出て、`launcher.properties` の
`-Xmx640m` が反映されている」の 2 点を足した。`jche.cmd` の分岐（`%~dp0`、`for /f`、遅延展開の回避、
`timeout` による端末判定、`setlocal` での環境の隔離）はここでしか通らない。

### Q19. この作業環境での確認

jbang が JDK 25 を取得できない環境（JDK の配布サイトへの接続が拒否される。Maven Central と GitHub には届く）
だったので、`launcher.properties` に `JCHE_JBANG_OPTS=--java 21` を書き、手元の JDK 21 で動かした。
このオプション自体が今回足した仕組みなので、ちょうどその確認にもなった。

- `test/regression/run.sh` を `JCHE_CMD="$PWD/jche.sh"`（起動コマンドの対話なし経路）と、従来どおりの
  `jbang run --java 21 src/CallHierarchyExporter.java` の両方で実行し、全ケース（`multi` を含む）PASS
- `JCHE_TEST_JBANG_OPTS="--java 21" bash test/cli/run.sh` が PASS
- `JBANG_DIR=.jbang` / `JBANG_REPO=.jbang/repository` で起動し、JBang 本体が `.jbang/bin/` に、JDT の jar が
  `.jbang/repository/org/eclipse/jdt/` に入ることを確認（JDK は取得できないので `--java 21` のまま）
- `javac --release 17 -Xlint:all -Werror -Xdoclint:all,-missing` が通る（`Console.isTerminal()` のような
  JDK 22 以降の API は使っていない。`pom.xml` の release も 17 のまま）
- Windows の `jche.cmd` は CI に委ねる（Q18）

### Q20. やらなかったこと

- **取得済みの JDK / JBang の削除**をアプリから行うこと（Q11）
- **設定ファイルの編集**をアプリの中で行うこと。項目が多く、エディタで開くほうが速い。`v 番号` で内容を確認し、
  変更するときのパスを表示するにとどめた
- **出力フォルダをエクスプローラーで開く**こと（`Desktop.open`）。GUI の無い環境や SSH 越しでは動かず、
  失敗の見え方が環境で変わる。出力フォルダのパスはログに出るので、そこからコピーする
- **色付け・画面消去**（Q1）
- **配布用の zip 作成**やインストーラ。リポジトリを clone（または zip でダウンロード）して `jche.cmd` を
  実行するのが配布形態で、それ以上は要らないと判断した

---

## 追記: MS932 で保存し直したときに `jche.cmd` が壊れていた

（#48 の作業中に、`main` の `regression-windows` が赤いことから見つかったもの。修正は #48 の PR に含めた）

### Q21. 何が起きたか

`jche.cmd` を MS932 で保存し直したコミット（`3b3e0b2`）で、英語版と日本語版が混ざったファイルが入っていた。
結果として Windows の CI が次で止まっていた。

```
The system cannot find the batch label specified - main
```

壊れ方は 3 つ。

- **`:main` のラベルが無い**。`goto :main` は 4 か所あるのに飛び先が無い（`:main` の行が `:load_settings` に化けていた）
- `:main` の本体にあるはずの `if exist "%RESTART%" del /q "%RESTART%"` / `setlocal` / `call :load_settings` の 3 行が欠落
- 英語版の `:first_run_prompt` / `:write_settings` / `:load_settings` が残り、後ろ 2 つは二重定義。
  日本語版の `first_run_prompt` の本体はラベルを失って、ファイル前半に素の命令として置かれていた

改名前（`1403f79`）の構造（`:main` → `:first_run_prompt` → `:write_settings` → `:load_settings` → `:set_one` →
`:absolutize`）に戻し、画面に出る文言だけ日本語にして、MS932・CRLF で保存し直した。

### Q22. 同じ壊れ方を次に検出する方法

`test/cli/run.sh` の最後に、`jche.cmd` を**読むだけ**の検査を足した（cmd.exe が要らないので Linux の CI で毎回通る）。

- `goto` / `call` の飛び先が全てラベルとして存在するか（今回の `:main` はここで落ちる）
- ラベルの二重定義が無いか（今回の `:write_settings` / `:load_settings`）
- CRLF で保存されているか
- MS932 で保存されているか（CP932 として読んだときに、実際に入っているはずの日本語の文が読めるか。
  UTF-8 で保存し直すと、CP932 として読めても中身が化けるので、文字列の一致で見る）

実際に動かす検査（`regression-windows` の `jche.cmd` の 2 ステップ）は Windows でしか通らないので、
「壊れていないこと」の検査だけを Linux 側に置いて、気づくまでの時間を縮める狙い。

### Q23. 検査そのものにも誤りがあった（`if exist` とワイルドカード）

`jche.cmd` を直したあとも `regression-windows` は `FAIL: no output folder` で落ちた。
解析は成功してログにも出力フォルダが出ているのに、検査側が見つけられていない。

```bat
rem これは「出力があっても」常に偽になる
if not exist test\regression\entry\output\*_demo\call-hierarchy.csv (echo FAIL: no output folder & exit /b 1)
```

cmd の `if exist` は、ワイルドカードを**最後のファイル名の位置でしか**解釈しない。
途中のフォルダ名に `*` を書いた条件は常に偽になる（エラーにもならないので気づきにくい）。
出力フォルダ名は `<日時>_demo` で実行のたびに変わるため、フォルダは `for /d` で走査し、
その中のファイルを `if exist` で見るように直した。

```bat
set "FOUND="
for /d %%D in (test\regression\entry\output\*_demo) do if exist "%%D\call-hierarchy.csv" set "FOUND=1"
if not defined FOUND (echo FAIL: no output folder & exit /b 1)
```

`test/regression/run.cmd` の `:expectsidecar`（`.cache\demo_*` を探す）は最初から `for /d` で書かれており、
同じ形になった。

