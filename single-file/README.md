# single-file — 1 ファイル版（完全版）

本体（`src/jche` 配下の全ソース）を、パッケージ `jche` の **1 ファイル**
[`CallHierarchyExporterSingle.java`](CallHierarchyExporterSingle.java) にまとめたものです
（利用者が書く拡張が import する API `jche.extension` の 4 ファイルだけは、本物のパッケージのまま
[`jche/extension/`](jche/extension/) に置きます）。
これらと Eclipse JDT Core の jar だけでビルドして動き、**機能は本体と同じ**です
（キャッシュと差分更新・データフロー解析と条件分岐の打ち切り・`call-conditions.csv`・被参照スキャン・
`pom.xml` / `build.gradle` からの依存 jar 解決・Spring / Doma の解決・ラムダの合成メソッド・契約表・
対話モード・サーバーモード）。設定ファイルの書き方・出力 CSV の読み方は [README](../README.md) のとおりです。

このファイルは**手で書いたものではなく**、[`generate.sh`](generate.sh) が `src/jche` から機械的に生成します
（生成器は [`generator/MergeSources.java`](generator/MergeSources.java)）。本体を直したら生成し直してコミットします。
生成し直し忘れ・ビルドできない・本体と出力が違う、は `bash test/single-file/run.sh` が検出します
（設計の記録は [docs/single-file-qa.md](../docs/single-file-qa.md)）。

## 本体との違い

機能は同じで、違うのは**クラスの置き場所**だけです。本体の各ファイルのトップレベル型が、
外側のクラス `jche.CallHierarchyExporterSingle` の入れ子（`static`）になっています。そのため:

| こと | 1 ファイル版では |
|---|---|
| 同梱の拡張（`resolver.candidate.providers=jche.builtin.TypeMappingProvider`） | 本体と同じ名前で書けます。入れ子のクラス名（`jche.CallHierarchyExporterSingle$TypeMappingProvider`）に読み替えます |
| 利用者が Java で書く拡張（`plugin.folders` の `.java` / `.class` / `.jar`） | 本体と同じに使えます。拡張が import する `jche.extension.*` は入れ子にせず、`jche/extension/` に本物のパッケージのまま置いてあるためです（ビルドのときに一緒に渡します。JBang は `//SOURCES` で拾います） |
| ツールのプロジェクトフォルダ（キャッシュの既定の置き場所 `.cache/`） | 本体と同じで、作業ディレクトリかその上位に `src/jche/CallHierarchyExporter.java` があるフォルダ、または環境変数 `JCHE_ROOT`。見つからなければ作業ディレクトリを使い、その旨を警告します。`cache.folder` を書けば関係ありません |
| 起動コマンド（`java-call-hierarchy-exporter.sh` / `.cmd`）と `jbangw/` | 使いません。下の手順で直接動かします |

## ビルドと実行

引数に設定ファイルを渡すと対話なしで解析し、引数が無ければ対話モードに入ります（本体の `jche.Jche` と同じ。
`--help` で使い方、`--server` でサーバーモード）。

```bash
# 依存 jar を lib/ に集めてある場合（README の Pleiades/Eclipse 環境の手順と同じ jar）
javac -encoding UTF-8 -cp "lib/*" -d bin single-file/CallHierarchyExporterSingle.java single-file/jche/extension/*.java
java  -cp "bin:lib/*" jche.CallHierarchyExporterSingle config/jche.properties   # Windows は ; 区切り
```

JBang なら jar を集めずに直接実行できます（初回は JDK 25 と JDT の jar を取得します）。

```bash
jbang single-file/CallHierarchyExporterSingle.java config/jche.properties
```

## 生成し直す

```bash
bash single-file/generate.sh        # src/jche から CallHierarchyExporterSingle.java と jche/extension/ を作り直す（JDK 17 以上）
bash test/single-file/run.sh        # 生成し直し忘れ・ビルド・起動・回帰テスト（whole / entry / plugin）との一致を検査する
```

生成器がすること（本体のソースは書き換えません）:

- `package` 行と `import jche.…` 行を捨て、それ以外の `import` を先頭に集める（単純名の衝突が無いことを検査する）。
  入れ子の型の import（`jche.analysis.CallEdgeExtractor.SourceFile` など）だけは、外側のクラス経由の import に書き直す
- 各ファイルの唯一のトップレベル型に `static` を付けて、外側のクラスの入れ子にする。
  `jche.extension` の 4 ファイルだけは入れ子にせず `jche/extension/` にそのまま写す（Javadoc の `{@link jche.…}` は `{@code}` にする）。
  この 4 ファイルが `java.*` 以外に依存し始めたら失敗する
- コードに書かれた完全修飾名 `jche.パッケージ.型` を単純名にする（文字列リテラルのある行は触らない）
- JBang の指示行（`//DEPS` / `//JAVA`）は `src/jche/CallHierarchyExporter.java` のものを先頭に 1 組だけ置く
- 同梱の拡張の読み込み（`jche.config.Plugins`）にだけ、`jche.builtin.X` を入れ子のクラス名に読み替える 1 行を差し込む

期待した形でないソース（トップレベル型が 1 つでない・単純名が重なる・差し込み先が無い）に当たると、
黙って壊れたファイルを作らずに失敗します。
