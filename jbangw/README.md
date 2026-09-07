# jbangw — JBang ラッパースクリプト同梱フォルダ

このフォルダには [JBang](https://www.jbang.dev/)（[jbangdev/jbang](https://github.com/jbangdev/jbang)）の
ラッパースクリプトを同梱しています。`jbang wrapper install` が置くものと同じ 3 ファイルで、
これがあるおかげで JBang を各自の環境にインストールしなくても

```bat
rem Windows（コマンドプロンプト）
.\jbangw\jbang.cmd src\CallHierarchyExporter.java config\config.properties
```

```bash
# Linux / macOS / Git Bash
./jbangw/jbang src/CallHierarchyExporter.java config/config.properties
```

でツールを実行できます。初回実行時に JBang 本体・JDK・依存 jar が自動で取得されます
（`~/.jbang/` 配下に保存）。

## ファイル

| ファイル | 内容 |
| --- | --- |
| `jbang` | Linux / macOS / Git Bash 用のシェルスクリプト |
| `jbang.cmd` | Windows（コマンドプロンプト）用のバッチファイル |
| `jbang.ps1` | Windows（PowerShell）用のスクリプト。`jbang.cmd` からも委譲される |
| `LICENSE` | JBang の MIT ライセンス（この 3 ファイルに適用される） |
| `README.md` | このファイル |

## ライセンス

上記 3 スクリプトは JBang（Copyright (c) 2020 Max Rydahl Andersen）の一部であり、
**MIT License** で配布されています。全文は同じフォルダの [`LICENSE`](LICENSE) にあります。

リポジトリ本体（`src/`、`test/`、`config/` など）のライセンスは Apache License 2.0 で、
ルートの [`../LICENSE`](../LICENSE) が適用されます。**このフォルダの 3 スクリプトだけは
Apache-2.0 ではなく MIT** である点にご注意ください。

## 取得元

- リポジトリ: <https://github.com/jbangdev/jbang>
- パス: `src/main/scripts/jbang`, `src/main/scripts/jbang.cmd`, `src/main/scripts/jbang.ps1`
- 取り込み方法: 上記 3 ファイルをそのままコピー（このフォルダ用の書き換えはしていない）

取り直すときは、たとえば次のようにします。

```bash
for f in jbang jbang.cmd jbang.ps1; do
  curl -sLf -o "jbangw/$f" "https://raw.githubusercontent.com/jbangdev/jbang/main/src/main/scripts/$f"
done
chmod +x jbangw/jbang
```

## 当リポジトリでの修正点

> **現状の注意**: 直近の「本家から取り直し」（コミット `314c140`）で、下記の修正はいったん
> 巻き戻っています。`bash test/jbangw/run.sh` は現在 FAIL し、巻き戻った項目を列挙します。
> 修正の当て直しはこの Issue の範囲外なので、ここでは記録だけ残しています。

本家のスクリプトには、JDK の自動取得まわりに（主に Windows で）不具合が残っていたため、
コピーしたうえで次の修正を当てて取り込んでいます。修正はすべて
[`../test/jbangw/run.sh`](../test/jbangw/run.sh) が検査していて、本家から取り直して
差し替えたときに黙って巻き戻らないようになっています（各修正の背景は同スクリプトのコメントに詳しい）。

### `jbang.cmd`

- JDK 取得の委譲を、実際には何もしていない `jdk install` ではなく `version` にした。
  委譲先の `jbang.ps1` はコマンド実行前に自前で JDK を入れるので、必要なのはその副作用だけ
- 未設定が既定の `%JBANG_DEFAULT_JAVA_VERSION%` を `jdk install` の引数に渡していたのをやめた
  （バージョン引数なしの呼び出しになり必ず失敗していた）
- `jbang.ps1` への委譲を `powershell -Command` から `-File` にした。`-Command` はパスの引用が
  必要なうえ、スクリプトの終了コードを 0/1 に潰してしまう
- 委譲後に JDK が実際に入ったかを確認するようにした。入っていないと後段で
  「`'...\java.exe' is not recognized`」という分かりにくい形で落ちていた
- 括弧ブロック内のエラー伝播を `%ERRORLEVEL%` から遅延展開の `!ERRORLEVEL!` にした。
  前者はブロック解析時に展開されるため、失敗が終了コード 0 として扱われていた
- 委譲先 `jbang.cmd` の終了コードも呼び出し元へ返すようにした
- `%JBDIR%\currentjdk` の判定・実行を `javac` / `java` ではなく `javac.exe` / `java.exe` にした。
  Windows の実体は `.exe` で、`javac` では一致せず導入済みの JBang 管理 JDK が無視されていた

### `jbang.ps1`

- `currentjdk` の判定・実行を `javac.exe` / `java.exe` にした（`jbang.cmd` と同じ理由）
- JDK 取得のアーキテクチャを `x64` 決め打ちにせず実行環境から検出し、ネイティブバイナリ探索と
  同じ値を使うようにした。ARM64 の Windows で x64 の JDK を取得してしまっていた
- ディストリビューション判定の前に Java バージョンを数値へ変換するようにした
  （文字列のままでは `-ge 17` の比較が意図どおりに働かない）
- 展開後の `javac` 検査で終了コードを見るようにした。壊れた JDK をそのままキャッシュしていた
- JDK インストール失敗時に `break` で抜けず `exit 1` するようにした
- `Invoke-JBang` が jbang の終了コードを呼び出し元へ返すようにした（bash 版の
  `execute_jbang` と同じ挙動）。返していなかったため、PowerShell 経由で動かしたものの
  終了コードが失われていた

### `jbang`（bash）

- JDK 展開後の検査で、PATH 上の `javac` ではなく展開したばかりの
  `$TDIR/jdks/$javaVersion.tmp/bin/javac` を見るようにした
- その検査を、展開が成功したとき（`retval -eq 0`）に行うようにした

## 更新するときの手順

1. 上の「取得元」のコマンドで 3 ファイルを取り直す
2. `bash test/jbangw/run.sh` を実行する
3. NG が出た項目は、本家に取り込まれていない修正が巻き戻ったところなので、当て直す
4. 本家側に修正が取り込まれた（あるいは意図して挙動を変えた）項目は、`test/jbangw/run.sh` の
   該当項目と、このファイルの「当リポジトリでの修正点」を実際のスクリプトに合わせて更新する
