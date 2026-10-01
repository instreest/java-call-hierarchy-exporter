# single-file — 1 ファイル版（完全版）

本体（`src/jche` 配下の全ソース）を、パッケージ `jche` の **1 ファイル**
[`CallHierarchyExporterSingle.java`](CallHierarchyExporterSingle.java) にまとめたものです
（利用者が書く拡張が import する API `jche.extension` の 4 ファイルだけは、本物のパッケージのまま
[`jche/extension/`](jche/extension/) に置きます）。
これらと Eclipse JDT Core の jar だけでビルドして動きます。設定ファイルの書き方・出力 CSV の読み方は
[README](../README.md) のとおりです。

**本体と同期を取らない場合があります。** 1 ファイル版はその目的に合わせて個別に更新するので、本体（`src/jche`）の
最新の変更が入っていないことや、本体と出力が違うことがあります。ビルドできること・起動できることは
`bash test/single-file/run.sh` が検査します（設計の記録は [docs/single-file-qa.md](../docs/single-file-qa.md)）。

## 本体との違い

同期している範囲では、違うのは**クラスの置き場所**です。本体の各ファイルのトップレベル型が、
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
# 依存 jar を lib/ に集めてある場合（docs/cli.md の「閉域ネットワークで動かす」と同じ jar）
javac -encoding UTF-8 -cp "lib/*" -d bin single-file/CallHierarchyExporterSingle.java single-file/jche/extension/*.java
java  -cp "bin:lib/*" jche.CallHierarchyExporterSingle config/jche.properties   # Windows は ; 区切り
```

JBang なら jar を集めずに直接実行できます（初回は JDK 25 と JDT の jar を取得します）。

```bash
jbang single-file/CallHierarchyExporterSingle.java config/jche.properties
```
