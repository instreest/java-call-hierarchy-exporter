# 入口ファイルを `jche` パッケージへ移した記録 Q&A

`CallHierarchyExporter` と `Jche`（2 つの入口）は既定パッケージ（パッケージ宣言なし、`src/` 直下）に置いていた。
これを `jche` パッケージ（`src/jche/` 直下）へ移した。きっかけは JBang の `//SOURCES` の取りこぼし。

## Q1. `//SOURCES jche/**/*.java` で `cannot find symbol` になったのはなぜか

`**` は「区切り文字をまたぐ」が「0 階層」は含まないため。`jche/**/*.java` は `**` の後ろの `/` を必ず要求するので、
`jche/Foo.java`（直下）には当たらず、`jche/graph/Foo.java`（下の階層）にだけ当たる。
JBang の癖ではなく Java の `PathMatcher`（`glob:`）の仕様で、`jche/` 直下に `.java` を置いた時点で必ず起きる。

対処は直下ぶんを併記すること。現在の両入口は次の形（スクリプト自身のあるフォルダ＝`src/jche/` からの相対）。

```java
//SOURCES *.java **/*.java
```

`*.java` はスクリプト自身も含むが、JBang（0.141.0）はこれを問題なく扱う。
`jbang build --fresh src/jche/Jche.java` / 同 `CallHierarchyExporter.java` の両方で確認した。
`//SOURCES ../../jche/**/*.java` のように**上の階層へ出てから戻る** glob だけは `StackOverflowError` になる
（`docs/jpms-modularity-qa.md` の Q12）。

## Q2. なぜ既定パッケージをやめたのか

- 無名パッケージの型は **import できない**。そのため `jche.cli.App` は解析処理を直接呼べず、
  `BiFunction<List<Path>, ToolRoot, Integer>` をコンストラクタで受け取る回避策を入れていた。
  移動後は `CallHierarchyExporter.runAll` を直接呼べるので、この引数ごと消した
- 名前付きモジュールに無名パッケージは置けない。将来 JPMS を検討するときの前提が 1 つ減る
  （`docs/jpms-modularity-qa.md` の Q11 の表から 1 行消えた）
- 既定パッケージにしていた元の動機は「コマンドプロンプトを短くする」ことだったが、
  今は起動コマンド（`java-call-hierarchy-exporter.sh` / `.cmd`）から動かすので、動機そのものが無くなっていた

クラス名は変えていない（`jche.CallHierarchyExporter` / `jche.Jche`）。

## Q3. 移動で何を直したか

`//SOURCES` の相対パスは**スクリプトファイルのあるフォルダが起点**なので、入口の移動に合わせて書き換えが要る。
それ以外は、パスとクラス名を指す箇所をすべて追随させた。

| 直した場所 | 内容 |
| --- | --- |
| `src/jche/CallHierarchyExporter.java` / `src/jche/Jche.java` | `package jche;` を足し、`//SOURCES` を `*.java **/*.java` にする |
| `src/jche/cli/App.java` | `runner`（`BiFunction`）を廃し、`CallHierarchyExporter.runAll` を直接呼ぶ |
| `src/jche/config/ToolRoot.java` | プロジェクトフォルダの目印 `MARKER` を `src/jche/CallHierarchyExporter.java` にする |
| `java-call-hierarchy-exporter.sh` / `.cmd` | `jbang run` に渡すスクリプトのパス |
| `.github/`（`smoke.yml`・`action/*.sh`）、`test/*/run.sh`、`pom.xml`、`config/config.properties` | パスの参照 |
| `README.md`（Pleiades 手順） | `java -classpath bin;lib\* jche.CallHierarchyExporter`（FQCN になる）。`javac -sourcepath src` はそのまま |

## Q4. 検査

- `jbang build --fresh` を両入口で（`//SOURCES` の解決そのものの確認）
- `bash test/regression/run.sh` / `test/dataflow/run.sh` / `test/cli/run.sh` / `test/pom/run.sh` / `test/jbangw/run.sh`
- `javac --release 17 -Xlint:all -Werror -Xdoclint:all,-missing`（`smoke.yml` と同じ引数）
- README の Pleiades 手順と同じ `javac -sourcepath src -d bin src/jche/CallHierarchyExporter.java`
