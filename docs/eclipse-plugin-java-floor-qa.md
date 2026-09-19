# Eclipseプラグインの下限を Java 8 / Eclipse 4.6 から Java 11 / Eclipse 4.17 へ上げる — QA 一覧

> 使い方（入れ方・操作・設定・つまずきやすいところ）は
> [eclipse-plugin-usage.md](eclipse-plugin-usage.md)、版の対応表は
> [eclipse-pleiades-versions.md](eclipse-pleiades-versions.md) にまとめてある。

きっかけは利用者の言葉である。

> 今回はレガシープロジェクトも対象にしたい意図で Java 8 に対応するようにしましたが、
> これはソースコードの Java 8 準拠と実行環境の Eclipse の起動 Java バージョンとを
> 混同していたかもしれません。

そのとおりだった。**プラグインのバイトコードの版は、解析できる対象の Java の版とは何の関係も無い。**
測って確かめたうえで、下限を Java 11 / Eclipse 4.17（2020-09）へ上げた。

| 変えたところ | 前 | 後 |
|---|---|---|
| `META-INF/MANIFEST.MF` の BREE | `JavaSE-1.8` | `JavaSE-11` |
| `Require-Bundle` の版 | Eclipse 4.6 相当 | Eclipse 4.17 相当（jdt.core 3.23.0 ほか） |
| `eclipse-plugin/pom.xml` のプラグイン側 | `--release 8` | `--release 11` |
| `test/plugin-api/`（下限での検査） | 4.6 の jar / `--release 8` / クラスファイル版 52 | 4.17 の jar / `--release 11` / 版 55 |
| `Messages`（文言の読み込み） | properties を自前で UTF-8 として読む | `java.util.ResourceBundle` |

---

### Q1. 3 つの「Java の版」はどう違うのか

混ざりやすいので表にする。**効くところがまったく違う。**

| 軸 | 何が決めているか | レガシーな解析対象に効くか |
|---|---|---|
| ① 解析対象ソースの Java の版 | <b>同梱した JDT</b> と `source.level` | **これだけが効く** |
| ② Eclipse を動かす JVM の版 | `BREE` と `--release`（＝この文書の話） | 効かない |
| ③ 解析に使う JDK の版 | 17 以上。別プロセスで動く | 効かない |

①を実測した。同梱している JDT 3.46.0 が受け付ける `source.level` は
`1.1` から `26` まで（`JavaCore.getAllVersions()`）。つまり Java 1.4 や 6 のレガシープロジェクトは、
**プラグインを Java 21 でコンパイルしていても問題なく解析できる**。②は無関係である。

### Q2. では Java 8 対応は何を買っていたのか

「**2020 年 9 月より前の Eclipse に入れられる**」ことだけだった。しかも次の 2 点で、
それすら値打ちが薄い。

- **Pleiades はどの版でも Java 8 では動いていない。** Eclipse 実行用 JDK は
  2020 年版ですでに 11、2021 年以降は 17 / 21 である
  （[eclipse-pleiades-versions.md](eclipse-pleiades-versions.md)）。
  主な利用者が Pleiades なら、Java 8 対応の恩恵は**ゼロ**だった
- 恩恵があるのは「2020 年より古い素の Eclipse を使い続けている人」だけで、
  その人も**解析用に JDK 17 以上を別途用意する必要がある**（③）。
  環境がまるごと古いまま、という想定自体が成り立ちにくい

### Q3. Java 8 対応は何を払っていたのか

実際に踏んだものだけを挙げる。

- **文言の読み込みを自前で書く羽目になった。** Java 8 は properties を ISO-8859-1 として読む
  （UTF-8 になるのは Java 9 から）ので、`ResourceBundle` も `NLS` も使えず、日本語が化ける。
  下限を 11 にした今は標準の `ResourceBundle` に戻してある
  （[eclipse-plugin-nls-qa.md](eclipse-plugin-nls-qa.md) の Q3）
- `PlatformUI.getDialogSettingsProvider`（4.24 / 2022〜）が使えず、
  非推奨の `getDialogSettings()` を `@SuppressWarnings` 付きで呼んでいる
- `String#repeat`・`List.of`・`var`・`Files.readString` が使えない
  （`MethodKeys` に「Java 8 には String#repeat が無い」というコメントが残っていた）

### Q4. なぜ Java 11 で、17 ではないのか

17 にすると説明は一番きれいになる（解析に使う JDK の下限と揃う）。それでも 11 にした。

- Java 11 なら **Eclipse 2020-09 以降**が対象になる。5 年以上の窓があり、
  Pleiades も 2020 年版から全部入る
- 17 にすると **Eclipse 2023-06 以降**まで切り上がる。2021〜2022 年あたりで止まっている
  現場を落とす割に、得られるもの（使える API と言語機能）が 11 との差で大きくない
- Java 8 で払っていたもの（Q3）のうち、いちばん重かった properties の文字コードは
  **9 以上ならどれでも解決する**

### Q5. 下限の「Eclipse 4.17」と「Java 11」は同じことを言っているのか

厳密には違う。2 つは別の項目である。

| 項目 | 何を縛るか |
|---|---|
| `Bundle-RequiredExecutionEnvironment: JavaSE-11` | Eclipse を動かす **JVM** が 11 以上であること |
| `Require-Bundle` の版（jdt.core 3.23.0 ほか） | 呼んでよい **Eclipse の API** の下限 |

理屈のうえでは「Eclipse 4.6 を Java 11 で起動する」こともできるが、そこでは 4.17 の API が無い。
**両方を満たす最も古い構成が Eclipse 4.17（2020-09）**なので、そこを下限として書いている。

なお、実測すると `org.eclipse.ui.workbench` の BREE は 4.17 の時点ではまだ `JavaSE-1.8` で、
`JavaSE-11` に変わるのは 4.21（2021-09）である。**バンドルの BREE と、IDE 製品としての
必要 Java は別物**で、Eclipse IDE が製品として Java 11 を要求し始めたのが 2020-09 である。
こちらの下限は後者に合わせた。

### Q6. 下限を上げたことで、レガシープロジェクトの解析に影響は出るか

出ない。Q1 のとおり、解析対象の Java の版を決めるのは同梱した JDT だけである。

ただし**下限とは無関係に**、1.7 以下のソースには次の落とし穴がある
（[syntax-error-report-qa.md](syntax-error-report-qa.md)）。

- いまの JDT は `source.level` の 1.7 以下を受け付けず、**黙って 1.8 として解析する**
- すると Java 5 より前の `enum` / `assert` を識別子に使ったコードが構文エラーになり、
  そのファイルの呼び出しが丸ごと落ちる

これは「同梱の JDT を 3.38.0 以前に差し替える」ことで回避できる（測って確かめた窓は
JDT 3.28.0〜3.38.0。設定画面の「JDT の jar のフォルダ」で指定する）が、
判定機構を作り込むほどの需要は無いと判断し、**構文エラーを黙って落とさず報告する**ことだけを直した。

### Q7. 下限を変えたことを、どうやって守るか

`test/plugin-api/run.sh` が 4.17 の jar だけをクラスパスにして `--release 11` でコンパイルする。
本番のビルドは新しい Eclipse の jar を使うので、**この検査だけが「下限の Eclipse でも動く」を守っている**。
クラスファイルのメジャー版が 55（Java 11）であることも併せて見る。

`test/plugin/run.sh` は MANIFEST の BREE が `JavaSE-11` であることを見る。
下限を動かすときは、この 2 つと `eclipse-plugin/pom.xml` の `release`、
`test/plugin-api/pom.xml` の依存の版をまとめて揃える。
