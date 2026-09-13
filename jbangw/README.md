# jbangw — JBang ラッパースクリプト同梱フォルダ

このフォルダには [JBang](https://www.jbang.dev/)（[jbangdev/jbang](https://github.com/jbangdev/jbang)）の
ラッパースクリプトを同梱しています。`jbang wrapper install` が置くものと同じ 3 ファイルで、
これがあるおかげで JBang を各自の環境にインストールしなくても

```bat
rem Windows（コマンドプロンプト）
.\jbangw\jbang.cmd src\jche\CallHierarchyExporter.java config.properties
```

```bash
# Linux / macOS / Git Bash
./jbangw/jbang src/jche/CallHierarchyExporter.java config.properties
```

でツールを実行できます。初回実行時に JBang 本体・JDK・依存 jar が確認なしで自動的に取得されます
（`~/.jbang/` 配下に保存）。取得の前に確認してほしいときはリポジトリ直下の起動コマンド
（`java-call-hierarchy-exporter.sh` / `.cmd`）を使ってください（README の「ネットワークからの取得の確認」）。

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

## 本家との差分

3 ファイルは本家 main とバイト単位で同一で、当リポジトリ独自の修正は当てていません。
[`../test/jbangw/run.sh`](../test/jbangw/run.sh) がこの状態を基準に検査します。

なお CI（`.github/workflows/smoke.yml`）の「jbang.cmd propagates a failing exit code」は、
本家の `jbang.cmd` が委譲先の終了コードを返さないため通りません。`continue-on-error: true` で
警告にとどめています。

## 更新するときの手順

1. 上の「取得元」のコマンドで 3 ファイルを取り直す
2. `bash test/jbangw/run.sh` を実行する
3. NG が出た項目は、本家側で内容が変わったところ。変更が妥当かを確かめたうえで、
   `test/jbangw/run.sh` の該当項目を新しい内容に合わせて更新する
