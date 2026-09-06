# 被参照スキャンの FatJar 対応 — 実装時の QA 一覧

[Issue #35](https://github.com/instreest/java-call-hierarchy-exporter/issues/35)
「被参照メソッドの解析で FatJar に対応したい」への対応で、迷ったこと・困ったことと、
その結論を Q&A の形で残す。

対応の要点:

- `external.library.folders` の jar の中に jar（`BOOT-INF/lib/*.jar`、`WEB-INF/lib/*.jar`、ear の中の war 等）が
  あれば、取り出さずにストリームのまま開いて同じ走査をする。何段の入れ子でも辿る（安全策として 8 段まで）
- 出力の `root` 列（jar 名）は `外側.jar!/BOOT-INF/lib/中.jar` のように、jar URL と同じ `!/` 区切りで
  入っていた場所まで書く
- `.war` / `.ear` もファイル・フォルダ指定の走査対象に加える
- フォルダ指定で集めた jar の並びをパス順に揃える（複数の jar が行を出すようになったため）
- 回帰テストに Spring Boot 形式の FatJar（`team-c-boot.jar`）と ear → war → jar の 2 段入れ子
  （`team-d-app.ear`）を加える

---

## 設計

### Q1. 「FatJar」とは何を指すと解釈したか

Issue には「FatJar 内の各 Jar を解析できる」とだけあり、形式の指定は無い。
実務で「FatJar」と呼ばれるものは 2 種類ある。

1. **入れ子型** … jar の中に依存 jar をそのまま入れる。Spring Boot の実行可能 jar
   （`BOOT-INF/classes/` に自分のクラス、`BOOT-INF/lib/*.jar` に依存 jar）が代表。
   war（`WEB-INF/classes/` と `WEB-INF/lib/*.jar`）や ear（中に war と jar）も構造は同じ
2. **展開型（shade / uber jar）** … 依存 jar の中身を全部展開して 1 つの jar に平らに詰める

対応前のスキャナは「jar の直下から `.class` で終わるエントリを読む」だけだった。
このため 2 の展開型は最初から読めており（パスは見ていないので `BOOT-INF/classes/` 直下の class も同様）、
読めていなかったのは 1 の「jar の中の jar」である。「各 Jar を解析できる」という Issue の表現もこれを指すので、
対応は「jar エントリを見つけたら中を開く」に絞った。

### Q2. 中の jar をテンポラリに取り出してから `JarFile` で開かなかったのはなぜか

`JarFile` はファイルパスにしか開けないので、取り出せば既存の `scanJar` をそのまま流用できる。
しかし取り出す方式は、

- テンポラリファイルの置き場所と後始末（異常終了時の消し忘れ、閉域環境の書き込み制限）が要る
- FatJar は数十〜数百 MB あるので、走査のたびに同じ量を書き戻すことになる
- 「読むだけ」のスキャナが書き込みを始めると、実行環境に対する要求が変わる

ので採らず、`JarFile.getInputStream(entry)` で得たストリームを `ZipInputStream` で先頭から順に読む方式にした。
`ZipInputStream` は中央ディレクトリを使わずローカルヘッダを順に読むので、ファイルでなくても開ける。
Spring Boot は中の jar を無圧縮（STORED）で格納する（起動時に同じくストリームで読むため）が、
`ZipInputStream` は圧縮（DEFLATED）されていても読める。回帰テストは両方を含む（Q11）。

### Q3. 最上位の jar も `ZipInputStream` に統一しなかったのはなぜか

統一すれば「エントリ名とストリーム」を受け取る 1 本の再帰で済み、コードは短くなる。
だが `JarFile` は中央ディレクトリから読むので、ローカルヘッダにサイズが無い（データ記述子を使う）
エントリや、末尾に余分なデータが付いた jar でも確実に読める。最上位の jar はファイルとして
手元にあるのだから、より確実な `JarFile` で読める利点を捨てる理由が無い。
中の jar だけをストリームで読み、両者の合流点として「1 エントリを処理する」`scanEntry` を
置いた。最上位からも中からも同じ `scanEntry` に入るので、class の処理と自プロジェクト除外は 1 か所にある。

### Q4. 入れ子の深さに上限は要るか

ear → war → jar で 2 段が実在の最大なので、実用上は上限が無くても困らない。
それでも 8 段で打ち切るのは、jar が自分自身のコピーを含むような壊れた入力や、
悪意は無くても誤って再帰的にパッケージされた配布物で、いつまでも潜り続けないための安全策である。
上限に当たった jar は警告を出して読み飛ばす。2 段で足りるものに 8 を与えているので、
正常な入力で当たることは無い。

### Q5. 出力の jar 名（`root` 列）はどう書くか

候補は 3 つあった。

| 候補 | 例 | 問題 |
|---|---|---|
| 最上位の jar 名だけ | `team-c-boot.jar` | 同じ FatJar の中のどのモジュールが呼んでいるかが分からない |
| 中の jar 名だけ | `team-b-batch.jar` | どの配布物に入っていたかが分からない。複数の FatJar に同じ jar が入っていると見分けられない |
| 場所まで連ねる | `team-c-boot.jar!/BOOT-INF/lib/team-b-batch.jar` | 長い |

影響調査の答えは「どの配布物のどのモジュールに影響するか」なので、3 つ目を採った。
区切りは Java の jar URL（`jar:file:app.jar!/BOOT-INF/lib/x.jar`）と同じ `!/` にした。
見慣れた形で、Excel のフィルタで `!/` の有無や前方一致で「FatJar の中身だけ」を絞り込める。
既存の「class 解析に失敗」の警告は `jar!entry` と書いていたので、同じ `!/` に揃えた。

### Q6. 同じ jar が複数の FatJar に入っているとき、行をまとめるか

まとめない。Q5 のとおり「どの配布物に影響するか」が答えなので、FatJar ごとに別の行として出す。
回帰テストの期待出力でも `team-b-batch.jar` の被参照が、単体・`team-c-boot.jar` の中・`team-d-app.ear` の中で
3 回出ている。行数は増えるが、`root` 列でフィルタすれば配布物ごとに読める。
「同じメソッドが何個の配布物から呼ばれているか」の集計（ログの「自分のメソッド N 個」）は
メソッド単位なので、重複しても増えない。

### Q7. FatJar の中に自プロジェクトの jar が入っている場合は

たとえば自分たちのライブラリ jar が、他チームのアプリの `BOOT-INF/lib/` に入っている形。
これは実務で最も多い FatJar の使い方で、この Issue の主目的でもある。
自プロジェクトの除外はクラス単位（`H` 行の型名との照合）で行っているので、
中の jar から出てきたクラスにもそのまま効く。除外件数（ログの「自プロジェクトクラスを除外」）には
中の jar のぶんも数えられる。回帰テストの `team-c-boot.jar` は `BOOT-INF/lib/demo-app.jar` を
含めて、これを確認している。

### Q8. `.war` / `.ear` も受け付けるのはやりすぎではないか

Issue は「FatJar」だが、war と ear は「jar の中に jar が入っている」という構造がまったく同じで、
中を開く仕組みも `ZipInputStream` で共通なので、拡張子を 2 つ足すだけで対応できる。
逆に「`.jar` だけ」に絞ると、`app.ear` を指定した利用者が「なぜ読まれないのか」で迷う。
コストがほぼ無く、迷いを減らせるので受け付けることにした。
中のエントリについても同じ 3 拡張子を「中の jar」として扱う。
`.zip` は受け付けない。配布物として zip を使う場合は中身の規則が無く、
何でも開くと `lib.zip` のようなリソースまで走査して遅くなるためである。

### Q9. `BOOT-INF/classes/` や `WEB-INF/classes/` の class は特別扱いが要るか

要らない。スキャナはエントリのパスを見ず「`.class` で終わるか」だけで判定しているので、
どの階層にあっても読める。class 名は class ファイル自身の `this_class` から取るので、
エントリのパスにある `BOOT-INF/classes/` の接頭辞が型名に混ざることも無い。

### Q10. Spring Boot のローダー（`org/springframework/boot/loader/*.class`）はどうなるか

普通の class として読まれる。自プロジェクトの型を参照していないので行にはならず、
「クラス数」に数えられるだけである。読み飛ばす特別扱いは入れなかった。
入れると「Spring Boot の版が変わってパスが変わった」ときに黙って読み方が変わる。
数十クラスを余分に読むコストは無視できる。

---

## 実装

### Q11. `ZipInputStream` を閉じてよいか

閉じてはいけない。`ZipInputStream.close()` は包んでいる下のストリームまで閉じる。
中の jar は `JarFile.getInputStream(entry)` のストリーム（あるいはさらに外側の `ZipInputStream`）を
包んでいるので、閉じると外側の走査が途中で止まる。
中の jar を読み終えたら閉じずに戻り、外側の `try-with-resources` に任せる。
`ClassFileRefs.parse` も受け取ったストリームを閉じない（`DataInputStream` で包むだけ）ので、
`ZipInputStream` の 1 エントリを読ませてもエントリ境界で EOF になり、次のエントリへ進める。

### Q12. 中の jar が zip として壊れていたら

その jar だけ警告して読み飛ばし、外側の他のエントリは続ける。
`ZipInputStream` は `ZipException` を投げるので、それを中の jar の単位で受ける。
class 1 件の解析失敗を「その class だけスキップ」にしている既存の方針と同じ粒度である。
外側の jar 自体が壊れている場合は従来どおり例外で止まる（指定した入力そのものの問題なので隠さない）。

### Q13. 複数の jar から行が出るようになって、出力の順が変わらないか

変わりうることに気づいた。`external.library.folders` にフォルダを指定したときの jar の並びは
`Files.walk` の順で、これはファイルシステム依存（Linux の ext4 と Windows の NTFS で違う）。
対応前は行を出す jar が回帰テストで 1 つだけだったので表面化しなかった。
`TreeSet` でパス順に揃え、どの環境でも同じ順で出るようにした。
利用者が複数のフォルダを指定した順は保たれない（全体をパス順にする）が、
`root` 列でフィルタして読む列なので、並びの意味は薄い。

### Q14. class でも jar でもないエントリを開いていないか

対応前は「ディレクトリでなく `.class` で終わる」以外を `continue` していたので開いていなかった。
処理を `scanEntry` に集めた際、判定も一緒に移すと、リソースや `MANIFEST.MF` ごとに
`getInputStream` を開いてから捨てることになる。開くだけなら安いが、無駄なので
最上位の走査では開く前に「class か jar か」で振るいにかける（`isScanTarget`）。
中の jar の走査は `ZipInputStream` が順に読むので、開く・開かないの区別は無く、
`scanEntry` が該当しないエントリを素通りさせる。

### Q15. 進捗ログの集計はどう見せるか

`jar=4 jar内のjar=4 クラス=…` のように、最上位の jar と中の jar を別に数える。
最初は「jar=4（うち jar 内の jar=4）」と書いたが、「うち」では最上位に含まれるように読めて
誤りなので改めた。中の jar が無ければ「jar内のjar」の項目自体を出さず、従来のログと同じ形にする。

---

## テスト

### Q16. サンプルの FatJar をどう作るか

`samples/demo/extjars/` に 2 つ加えた。作り方は `samples/demo/README.md` にある。

- `team-c-boot.jar` … Spring Boot 形式。`BOOT-INF/classes/teamc/ReportJob.class` と
  `BOOT-INF/lib/{team-b-batch.jar, demo-app.jar}`。Spring Boot に合わせて無圧縮（`jar --no-compress`）。
  `demo-app.jar` を入れているのは Q7 の除外の確認のため
- `team-d-app.ear` … `team-d-web.war` を含み、その中に `WEB-INF/classes/teamd/WebJob.class` と
  `WEB-INF/lib/team-b-batch.jar`。2 段の入れ子と、圧縮ありの中の jar を確認する

Spring Boot のローダーは入れていない。走査には影響しない（Q10）し、サイズだけ増えるため。
`team-b-batch.jar` を単体でも中でも使い回しているのは、同じ jar が「どこにあったか」で
`root` 列だけが変わることを期待出力で見せるためである（Q5、Q6）。

### Q17. 期待出力はどう変わったか

`whole` と `entry` の `call-hierarchy.csv` に被参照の行が 35 行増えた。既存の行は変わっていない。
`methods.csv` は変わらない（被参照は `methods.csv` の列には入らない）。
`jarchange` は `external.library.folders` を指定しないので変わらない。

### Q18. 回帰テストの実行環境で困ったこと

`src/CallHierarchyExporter.java` は `//JAVA 25` を指定しており、JBang は無ければ JDK 25 を
foojay（Disco API）から取得する。今回の作業環境ではそこへの接続が遮断されていて取得できず、
`jbang run --java 21 …` で手元の JDK 21 を使って回帰テストを通した
（`JCHE_CMD="bash jbangw/jbang run --java 21 src/CallHierarchyExporter.java" bash test/regression/run.sh`）。
`samples/demo` は JDK の版で結果が変わる API を使っていないので、期待出力は JDK 25 と同じである
（版による違いは [cache-dependency-jars-qa.md](cache-dependency-jars-qa.md) の Q20）。
閉域環境で同じ状況になったときの回避策として記しておく。

---

## 限界（対応しないと決めたこと）

- **`library.folders`（JDT に渡す依存 jar）の FatJar** … 依存 jar として FatJar を指定しても、
  JDT は中の jar を見ない（クラスパスの仕様）。この Issue は被参照スキャンの話なので対象外。
  必要なら中の jar を取り出して `library.folders` に置く
- **マルチリリース jar（`META-INF/versions/N/`）** … 版ごとの class が別エントリとして入っており、
  それぞれ走査されるので、同じクラスの被参照が複数回出ることがある。実行時にどの版が選ばれるかは
  JDK の版で決まり、静的には確定できないので、全部出す方を安全側とした。対応前からの挙動で今回も変えていない
- **中の jar の圧縮形式** … `ZipInputStream` が扱える STORED / DEFLATED のみ。それ以外の圧縮
  （bzip2、LZMA 等。Java の標準では作れない）は `ZipException` となり、その jar だけ読み飛ばす
- **`.zip` の走査** … 受け付けない（Q8）
