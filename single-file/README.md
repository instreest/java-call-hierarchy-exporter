# single-file — お試し版（1ファイル）

本体（`src/jche` 配下・123 ファイル）の中心機能だけを、**既定パッケージの 1 ファイル**
[`CallHierarchyExporterSingle.java`](CallHierarchyExporterSingle.java) にまとめたものです。
このファイルと Eclipse JDT Core の jar だけでビルドして動きます。

仕様の出典は [docs/prompt-A-minimal.md](../docs/prompt-A-minimal.md)（出力の契約・既定値・解決の段）。

## 入っているもの

- ソースの AST 解析（Eclipse JDT `ASTParser`、バインディング解決あり、100 ファイルずつ）
- メソッドを int の ID に内部化した呼び出しグラフ
- 具象クラスの解決の段（段0 `STATIC_BOUND` / 段1 `SINGLE_IMPL`・`NO_IMPL` / 段2 `CHA`）
- `call-hierarchy.csv` … 深さ優先で 1 行ずつ出力。注記は `call-hierarchy` 列の最後の要素
  （`[UNEXPANDED:CYCLE]` / `[UNEXPANDED:DEPTH]` / `[UNEXPANDED:CHA] N candidates: {reason}` /
  `[UNEXPANDED:NO_IMPL]`。タグは本体と同じ）。
  型解決に失敗した呼び出しは `root` を `(unresolved)` にして末尾に出す
- `methods.csv` … 全メソッド 1 行ずつ（`role` / `reachable` / 解決後の `inDegree`）
- BOM 付き UTF-8、実行ごとの出力フォルダ、`run.log`、決定的な行順

## 入っていないもの（本体を参照）

解析結果キャッシュと差分解析、データフロー解析、条件分岐の打ち切り、`call-conditions.csv`、
被参照スキャン、`pom.xml` / `build.gradle` からの依存 jar 解決、Spring / Doma の解決、
ラムダ式の合成メソッド化（本体の呼び出しは囲みメソッドに計上したまま）、
プラグイン差し込み口、対話モード、サーバーモード、GitHub Actions 連携。

## 設定

| キー | 既定 |
|---|---|
| `project.root` | **必須**。解析対象プロジェクトのフォルダ |
| `source.folders` | 空ならルートから推定（`src/main/java`、`src` など） |
| `library.folders` | 空でも解析は続く（型解決失敗として記録） |
| `source.encoding` | `UTF-8` |
| `source.level` | JDT が対応する最大値 |
| `output.folder` | `.`（設定ファイルと同じフォルダ） |
| `exclude.callees` | `java.**,javax.**` |
| `entry.points` | 空なら「解決後の呼び出し元が 0 で本体があるメソッド」全部 |
| `max.depth` / `max.rows` | `50` / `5000000` |

パターン書式は `pkg.*` / `pkg.**` / `pkg.Class` / `pkg.Class#method`。

## ビルドと実行

```bash
# 依存 jar を lib/ に集めてある場合（README の Pleiades/Eclipse 環境の手順と同じ jar）
javac -encoding UTF-8 -cp "lib/*" -d bin single-file/CallHierarchyExporterSingle.java
java  -cp "bin:lib/*" CallHierarchyExporterSingle config/config.properties   # Windows は ; 区切り
```

JBang なら jar を集めずに直接実行できます。

```bash
jbang single-file/CallHierarchyExporterSingle.java config/config.properties
```
