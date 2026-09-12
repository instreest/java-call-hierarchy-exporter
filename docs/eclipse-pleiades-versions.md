# Eclipse / JDT Core / Java の版の対応表

このプラグインが「どの Eclipse に入るか」を判断するための一覧。
`eclipse-plugin/META-INF/MANIFEST.MF` の下限（`org.eclipse.jdt.core;bundle-version` と
`Bundle-RequiredExecutionEnvironment`）を決めるときの根拠でもある。

**このプラグインの動作条件（2つとも満たすこと）**

| 条件 | 値 | 根拠 |
|---|---|---|
| JDT Core | **3.27.0 以上**（Eclipse 2021-09 以降） | 3.26.0 では `AST.getJLSLatest()` が無くコンパイルできない（実測） |
| Eclipse を動かす JDK | **17 以上** | 本体のソースが record・sealed・switch 式を使うため。`--release 17` でコンパイルしている |

Eclipse の版が古くても、**Java 17 以上で起動していれば入る**。逆に Eclipse が新しくても
Java 11 で起動していると入らない。起動 JDK は `eclipse.ini` の `-vm` で変えられる（README 参照）。

---

## 一覧

「JDT Core」は Maven Central の `org.eclipse.jdt:org.eclipse.jdt.core` の公開日から、
「プラットフォームの BREE」は同じく Maven Central の `org.eclipse.platform:org.eclipse.ui.workbench`
の MANIFEST（`Bundle-RequiredExecutionEnvironment`）から、この作業中に実測した値である。
Eclipse の版と時期の対応は `download.eclipse.org` のリリース（例 `R-4.40-202606010713`）で確認した。

| Eclipse | 時期 | JDT Core | プラットフォームの BREE | このプラグイン |
|---|---|---|---|---|
| 4.17 | 2020-09 | 3.23.0 | JavaSE-11 | ✗ JDT が古い |
| 4.18 | 2020-12 | 3.24.0 | JavaSE-11 | ✗ JDT が古い |
| 4.19 | 2021-03 | 3.25.0 | JavaSE-11 | ✗ JDT が古い |
| 4.20 | 2021-06 | 3.26.0 | JavaSE-11 | ✗ JDT が古い（あと1版） |
| **4.21** | **2021-09** | **3.27.0** | JavaSE-11 | **○ Java 17 以上で起動すれば入る** |
| 4.22 | 2021-12 | 3.28.0 | JavaSE-11 | ○ 同上 |
| 4.23 | 2022-03 | 3.29.0 | JavaSE-11 | ○ 同上 |
| 4.24 | 2022-06 | 3.30.0 | JavaSE-11 | ○ 同上 |
| 4.25 | 2022-09 | 3.31.0 | JavaSE-11 | ○ 同上 |
| 4.26 | 2022-12 | 3.32.0 | JavaSE-11 | ○ 同上 |
| 4.27 | 2023-03 | 3.33.0 | JavaSE-11 | ○ 同上（IDE 製品としては Java 17 必須と告知された版） |
| 4.28 | 2023-06 | 3.34.0 | **JavaSE-17** | ◎ 以降は Eclipse 自体が Java 17 以上 |
| 4.29 | 2023-09 | 3.35.0 | JavaSE-17 | ◎ |
| 4.30 | 2023-12 | 3.36.0 | JavaSE-17 | ◎ |
| 4.31 | 2024-03 | 3.37.0 | JavaSE-17 | ◎ |
| 4.32 | 2024-06 | 3.38.0 | JavaSE-17 | ◎ |
| 4.33 | 2024-09 | 3.39.0 | JavaSE-17 | ◎ |
| 4.34 | 2024-12 | 3.40.0 | JavaSE-17 | ◎ |
| 4.35 | 2025-03 | 3.41.0 | JavaSE-17 | ◎ |
| 4.36 | 2025-06 | 3.42.0 | JavaSE-17 | ◎ |
| 4.37 | 2025-09 | 3.43.0 | JavaSE-17 | ◎ |
| 4.38 | 2025-12 | 3.44.0 | JavaSE-17 | ◎ |
| 4.39 | 2026-03 | 3.45.0 | JavaSE-17 | ◎ |
| 4.40 | 2026-06 | 3.46.0 | **JavaSE-21** | ◎ 以降は Eclipse 自体が Java 21 以上 |
| 4.41 | 2026-09 | 3.47.0 | （未確認） | ◎ |

JDT Core 自身の BREE も実測した。JavaSE-1.8（〜3.24.0）→ JavaSE-11（3.27.0〜3.33.0）→
JavaSE-17（3.34.0〜）と上がっている。プラットフォームの BREE と同じ推移である。

**解析できる Java の版は JDT の版で決まる**。ツールは `JavaCore.latestSupportedJavaVersion()` を
ログに出し、`source.level` がそれを超えるときは丸めた旨を表示する。古い Eclipse に入れた場合、
入るけれども新しい文法のソースは解析できない、という状態になりうる。

## Pleiades All in One との対応

Pleiades All in One は Eclipse と同じ「年-月」の版番号で出ており、**JDK を同梱している**
（Full Edition）。同梱 JDK は複数あり、`<インストール先>/java/<版>` に置かれ、
`eclipse.ini` の `-vm` でどれを使うかが決まる。したがって **Pleiades では、古い版でも
同梱の新しい JDK を `-vm` で指せば、このプラグインを入れられることが多い**。

| Pleiades | 対応する Eclipse | 同梱 JDK |
|---|---|---|
| 2021-09 〜 2022-12 | 4.21 〜 4.26 | Java 8 / 11 / 17 を同梱（要確認） |
| 2023-03 〜 | 4.27 〜 | Java 8 / 11 / 17 / 21 を同梱（[検索結果](https://willbrains.jp/pleiades_distros2023.html) による） |
| 2025-06 〜 | 4.36 〜 | Java 17 / 21 / 25 系（要確認） |

> **注意**: 同梱 JDK の詳細は、この作業環境からは公式サイト（willbrains.jp）へ接続できず、
> **一次情報で確認できていない**。上の表は検索結果と一般的な構成からの記載なので、
> 実際の版は各年のダウンロードページ（`https://willbrains.jp/pleiades_distros20XX.html`）で
> 確認してほしい。確認する場所は次の2つだけである。
>
> 1. `<インストール先>/java/` にどの版のフォルダがあるか
> 2. `<インストール先>/eclipse/eclipse.ini` の `-vm` がどれを指しているか（ここが 17 未満なら、
>    同梱の 17 以上のものへ書き換える）

Eclipse 本体の版は「ヘルプ → Eclipse IDE について」で、JDT の版は同ダイアログの
「インストール詳細 → プラグイン」で `org.eclipse.jdt.core` を探せば分かる。
プラグインを入れた後なら、ビューのバナーのツールチップにも「実行 JVM / JDT Core の版 /
解析できる Java の上限」が出る。

## Java 11 で動いている Eclipse について

「Eclipse を Java 11 で動かしたまま使いたい」は**対応しない**と判断した。
理由と代替手段は [eclipse-plugin-ui-qa.md](eclipse-plugin-ui-qa.md) の Q15 にある。
要点だけ言うと、`-vm` で 17 以上を指すのが正攻法で、それができない環境では
コマンドライン版（jbang は必要な JDK を自動取得する）を使ってほしい。
