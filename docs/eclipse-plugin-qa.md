# Eclipseプラグイン化 — 実装時の QA 一覧

[Issue #49](https://github.com/instreest/java-call-hierarchy-exporter/issues/49)
「Eclipseプラグインを作成したい」への対応で、迷ったこと・困ったことと、その結論を Q&A の形で残す。

対応の要点:

- `eclipse-plugin/` に PDE のプラグインプロジェクトを置いた。解析本体のソースは複製せず、
  リポジトリ直下の `src/` をリンクフォルダとして取り込む
- 画面に足したのはコマンド1つだけ。設定ファイル（`*.properties`）を右クリック →
  「呼び出し階層をCSVに出力」で、コマンドラインと同じ処理が走る
- Eclipse が無くてもバンドル jar をビルドできる（`mvn -f eclipse-plugin/pom.xml package`）。
  GitHub Actions の `eclipse-plugin` ジョブがこれを毎回実行する
- 解析本体には手を入れていない。入口を `jche.Exporter.run(...)` に切り出しただけで、
  出力は CLI と 1 バイトも変わらない（`test/regression` の期待値そのまま）

「なぜ Tycho を使わないのか」は Q7、「CLI との二重実装をどう避けたか」は Q3・Q4 にある。

---

## 設計

### Q1. プラグインをリポジトリのどこに置くか（直下か、サブフォルダか）

`eclipse-plugin/` というサブフォルダにした。

リポジトリ直下は既に「m2e で開く Maven プロジェクト」である（[eclipse-maven-qa.md](eclipse-maven-qa.md)）。
同じフォルダに PDE のプラグインプロジェクトを重ねると、クラスパスの出所が
m2e（`pom.xml` の依存）と PDE（`MANIFEST.MF` の `Require-Bundle`）の 2 つになり、
同じ JDT が二重にビルドパスへ載る。どちらが勝つかは環境依存で、
「Eclipse で開くと壊れている」という一番避けたい形になりやすい。

サブフォルダに分ければ、直下は今までどおり m2e で開けるまま、プラグインは
「インポート > 既存プロジェクトをワークスペースへ」で別プロジェクトとして開ける。

### Q2. 解析本体のソースを `eclipse-plugin/` へ複製しなかったのはなぜか

複製はいずれ必ずずれるから。`eclipse-plugin/.project` に**リンクフォルダ**を定義して、
リポジトリ直下の `src/` をプラグインプロジェクトの中に見せている。

```xml
<link>
  <name>src</name>
  <type>2</type>
  <locationURI>PARENT-1-PROJECT_LOC/src</locationURI>
</link>
```

`PARENT-1-PROJECT_LOC` は「プロジェクトの場所の 1 つ上」で、プロジェクトを
どこへ置いても（ワークスペースの外に置いても）リポジトリ内の相対関係だけで解決される。
`build.properties` の `source.. = src/,src-ui/` はこのリンクフォルダをそのまま指す。
PDE はリンクフォルダをソースフォルダとして扱えるので、これで CLI と同じソースが
バンドルへ入る。Maven 側（Q7）も同じ考えで `../src` をソースに足している。

### Q3. なぜエントリポイントを `jche.Exporter` に切り出したのか

**名前付きパッケージのクラスから、既定パッケージのクラスは参照できない**（Java 言語仕様）。

`CallHierarchyExporter` は jbang で `jbang src/CallHierarchyExporter.java` と書けるように
既定パッケージ（パッケージ宣言なし）に置いてある。プラグインのハンドラは
`jche.eclipse` パッケージなので、そこから `CallHierarchyExporter.run(...)` を
呼ぶ手段が無い（import が書けない。既定パッケージは import できない）。

逃げ道はリフレクション（`Class.forName("CallHierarchyExporter")`）だが、
コンパイル時に検査されない呼び出しをプラグインの中心に置くのは割に合わない。
そこで本体を `jche.Exporter` に移し、`CallHierarchyExporter` は
「引数を解釈して `Exporter.run(...)` を呼び、終了コードを返す」だけにした。
jbang の使い方（`//SOURCES jche/**/*.java` で一緒にコンパイルされる）も、
`java -cp bin CallHierarchyExporter` の使い方も変わらない。

### Q4. プラグイン側に解析のコードをどれだけ持たせるか

ゼロにした。`eclipse-plugin/src-ui/` にあるのは 2 クラスだけである。

- `ExportCallHierarchyHandler` … 選択された `*.properties` を集めて `Exporter.run(...)` に渡す
- `ExporterConsole` … ログを流すコンソール

JDT の AST を Eclipse の `IJavaProject` から取る（`ICompilationUnit` を使う）道もあり、
そちらは「Eclipse が既に持っているビルドパス」をそのまま使えるので設定ファイルが要らない。
しかし解析経路が CLI とプラグインで別物になり、同じプロジェクトを解析したのに
結果が違うという事故が起きる。このツールの値打ちは「CSV の中身」なので、
経路は 1 本に保つことを優先した。設定ファイルを書く手間は残るが、
その設定ファイルはワークスペースに置いたものをそのまま選べる。

### Q5. Eclipse ではログがどこに出るのか

`Log` は標準出力に書くが、Eclipse を GUI から起動したときの標準出力は利用者から見えない。
そこで `Log` に「1 行ずつ受け取る受け口」を足し（`Log.attachSink`）、
プラグインは「Call Hierarchy Exporter」コンソールへ流している。CLI 側の挙動は変えていない
（受け口を付けなければ今までどおり標準出力だけ）。

出力フォルダの `run.log` は CLI と同じく常に書かれるので、コンソールを閉じても記録は残る。

### Q6. キャッシュはどこにできるのか

プラグインの状態フォルダ（`<ワークスペース>/.metadata/.plugins/io.github.instreest.jche.eclipse/.cache/`）。

CLI の `ToolRoot` は `src/CallHierarchyExporter.java` を目印にツールのフォルダを探すが、
プラグインは jar の中で動くので目印が見つからない。何もしないと作業ディレクトリ、
つまり **Eclipse のインストール先**にキャッシュを作ってしまう（書き込めないことも多い）。
そのため `Exporter.run(configPaths, cacheRoot)` という置き場所を渡せる版を足し、
プラグインは `Platform.getStateLocation(...)` を渡している。

CLI と Eclipse では実行 JDK も違いうるので、どのみちキャッシュは共有できない
（[cache-dependency-jars-qa.md](cache-dependency-jars-qa.md) の Q7）。分けるのが正しい。

### Q7. Tycho を使わなかったのはなぜか

「Eclipse 無しでビルドできること」を CI で毎回確かめたかったが、Tycho はそのために
p2 リポジトリ（`download.eclipse.org`）から数百 MB のターゲットプラットフォームを
取ってくる。閉域ネットワークでの利用を前提にしているこのツール（README の Pleiades 手順）と
相性が悪く、CI も重くなる。

代わりに、ふつうの `maven-jar-plugin` に**PDE と同じ `META-INF/MANIFEST.MF`** を渡して
バンドル jar を組み立てている。OSGi のメタデータは MANIFEST.MF が唯一の正で、
pom.xml は「コンパイルに要る API をどこから取るか」しか決めない。
できた jar は Eclipse の `dropins/` に置けばそのまま動く。

Tycho が要るのは、機能（feature）や p2 更新サイトを作る段になってからで、
そのときは pom の `packaging` を `eclipse-plugin` に変えることになる。

### Q8. `org.eclipse.platform` の依存解決が失敗したのはなぜか

`org.eclipse.platform:*` の POM は Maven Central に存在しない成果物
（`com.sun.jna:com.sun.jna`、`javax.annotation:javax.annotation-api` のバージョン範囲）を
参照していて、そのままでは `No versions available` で止まる。

必要なのはコンパイル用の API だけ（実行時は Eclipse 本体のバンドルを使う）なので、
各依存で推移的な依存をすべて除外し、要るバンドルを 1 つずつ明示した。
`<scope>provided</scope>` にしてあるので、できた jar にはこれらは入らない。

### Q9. `Bundle-Version` に `.qualifier` を付けなかったのはなぜか

PDE の作法では `1.0.0.qualifier` と書き、エクスポート時に日付へ置き換える。
しかし `maven-jar-plugin` は MANIFEST.MF をそのまま入れるので、Maven でビルドすると
`1.0.0.qualifier` という文字列がそのまま残る（`manifestEntries` で上書きしようとしたが、
`manifestFile` の値が勝って効かなかった）。

ビルド経路が 2 つあるのに版の表記が食い違うのが一番まずいので、`1.0.0` の固定値にした。
版を上げるときは `META-INF/MANIFEST.MF` と `eclipse-plugin/pom.xml` の両方を書き換える。
食い違いは `test/plugin/run.sh` が検出する。

### Q10. メニューをどこに出すか

`popup:org.eclipse.ui.popup.any` に 1 つだけ。`visibleWhen` で
「選択がすべて `*.properties`」のときに限って出るようにしてある。

プロジェクトを右クリックしたら出す、という案もあったが、そのときは
どの設定ファイルを使うか（`config.properties` を探すのか、複数あればどれか）を
プラグインが決めることになる。CLI では利用者が明示している選択なので、
プラグインでも同じく明示させる形にした。複数選択すれば、コマンドラインに
設定ファイルを並べたときと同じく順に処理される。

---

## 検査

### Q11. 何を自動で検査しているか

- `test/plugin/run.sh` … JDT の版が `//DEPS` 行・直下の `pom.xml`・`eclipse-plugin/pom.xml` で
  一致すること、`Bundle-Version` と pom の版が一致すること、`Bundle-SymbolicName` を
  plugin.xml とハンドラが同じ綴りで使っていること、plugin.xml が指すハンドラのクラスが
  実在すること、`build.properties` の `source..` のフォルダが実在すること。
  ファイルを読むだけなので JDK もネットワークも要らない
- GitHub Actions の `eclipse-plugin` ジョブ … 上に加えて `mvn package` で実際にバンドル jar を作る

画面操作（右クリックからコマンドを起動して結果を見る）は自動化していない。
SWT を動かすテスト（SWTBot）を入れると CI に GUI 環境が要り、得られるものに対して重すぎる。
解析そのものは CLI と同じコードなので、`test/regression` が引き続きその責任を持つ。

### Q12. 既存の出力が変わっていないことをどう確かめたか

`jche.Exporter` への切り出しの前後で `test/regression/whole` を実行し、
`call-hierarchy.csv` が期待値と完全一致することを確認した。
処理の順序も出力先も変えておらず、変えたのは「どのクラスにコードがあるか」だけである。
