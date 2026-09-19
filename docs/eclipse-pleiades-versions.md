# Eclipse / JDT Core / Java の版の対応表

> 使い方（入れ方・操作・設定・つまずきやすいところ）は
> [eclipse-plugin-usage.md](eclipse-plugin-usage.md) にまとめてある。

このプラグインが「どの Eclipse に入るか」を判断するための一覧。
`eclipse-plugin/META-INF/MANIFEST.MF` の下限（`org.eclipse.jdt.core;bundle-version` と
`Bundle-RequiredExecutionEnvironment`）を決めるときの根拠でもある。

**このプラグインの動作条件**

| 条件 | 値 | 根拠 |
|---|---|---|
| Eclipse | **4.17（2020-09）以降** | プラグインは Eclipse 4.17 相当の API と JDT 3.23.0 だけを使う（`test/plugin-api/run.sh` が検査） |
| Eclipse を動かす JDK | **11 以上** | `Bundle-RequiredExecutionEnvironment: JavaSE-11`。プラグインは `--release 11` でコンパイルしている |
| 解析に使う JDK（別プロセス） | **17 以上、推奨 25** | 解析本体は `--release 17`。プラグインが子プロセスとして起動する |

下限は 2026-09 に **4.6 / Java 8 から 4.17 / Java 11 へ上げた**。
Java 8 対応は「レガシーな解析対象に対応する」つもりで入れたものだったが、
解析対象の Java の版を決めるのは<b>同梱した JDT</b> であって、プラグインのバイトコードの版とは
まったく関係がない、と分かったためである（経緯と測った結果は
[eclipse-plugin-java-floor-qa.md](eclipse-plugin-java-floor-qa.md)）。

**解析は Eclipse の中では走らない**（[out-of-process-analysis-design.md](out-of-process-analysis-design.md)）。
JDT もプラグインに同梱したものを使うので、**Eclipse 側の JDT の版は解析能力に関係しない**。
下の一覧は「Eclipse 側にどの版の JDT が入っているか」を調べるためのもので、
プラグインが要求するのは `org.eclipse.jdt.core` 3.23.0 以上というモデル API の下限だけである。

---

## 一覧

「JDT Core」は Maven Central の `org.eclipse.jdt:org.eclipse.jdt.core` の公開日から、
「プラットフォームの BREE」は同じく Maven Central の `org.eclipse.platform:org.eclipse.ui.workbench`
の MANIFEST（`Bundle-RequiredExecutionEnvironment`）から、この作業中に実測した値である。
Eclipse の版と時期の対応は `download.eclipse.org` のリリース（例 `R-4.40-202606010713`）で確認した。

| Eclipse | 時期 | JDT Core | プラットフォームの BREE | このプラグイン |
|---|---|---|---|---|
| 4.6 | 2016-06 | 3.12.x | JavaSE-1.8 | ✗ 2026-09 に対象外（Java 11 未満） |
| 4.16 | 2020-06 | 3.22.0 | JavaSE-1.8 | ✗ 同上 |
| **4.17** | **2020-09** | **3.23.0** | JavaSE-1.8（※） | **○ ここが下限** |
| 4.18 | 2020-12 | 3.24.0 | JavaSE-1.8（※） | ○ |
| 4.19 | 2021-03 | 3.25.0 | JavaSE-1.8（※） | ○ |
| 4.20 | 2021-06 | 3.26.0 | JavaSE-1.8（※） | ○ |
| 4.21 | 2021-09 | 3.27.0 | JavaSE-11 | ○ |
| 4.22 | 2021-12 | 3.28.0 | JavaSE-11 | ○ |
| 4.23 | 2022-03 | 3.29.0 | JavaSE-11 | ○ |
| 4.24 | 2022-06 | 3.30.0 | JavaSE-11 | ○ |
| 4.25 | 2022-09 | 3.31.0 | JavaSE-11 | ○ |
| 4.26 | 2022-12 | 3.32.0 | JavaSE-11 | ○ |
| 4.27 | 2023-03 | 3.33.0 | JavaSE-11 | ○（IDE 製品としては Java 17 必須と告知された版） |
| 4.28 | 2023-06 | 3.34.0 | **JavaSE-17** | ○ 以降は Eclipse 自体が Java 17 以上 |
| 4.29 | 2023-09 | 3.35.0 | JavaSE-17 | ○ |
| 4.30 | 2023-12 | 3.36.0 | JavaSE-17 | ○ |
| 4.31 | 2024-03 | 3.37.0 | JavaSE-17 | ○ |
| 4.32 | 2024-06 | 3.38.0 | JavaSE-17 | ○ |
| 4.33 | 2024-09 | 3.39.0 | JavaSE-17 | ○ |
| 4.34 | 2024-12 | 3.40.0 | JavaSE-17 | ○ |
| 4.35 | 2025-03 | 3.41.0 | JavaSE-17 | ○ |
| 4.36 | 2025-06 | 3.42.0 | JavaSE-17 | ○ |
| 4.37 | 2025-09 | 3.43.0 | JavaSE-17 | ○ |
| 4.38 | 2025-12 | 3.44.0 | JavaSE-17 | ○ |
| 4.39 | 2026-03 | 3.45.0 | JavaSE-17 | ○ |
| 4.40 | 2026-06 | 3.46.0 | **JavaSE-21** | ◎ 以降は Eclipse 自体が Java 21 以上 |
| 4.41 | 2026-09 | 3.47.0 | （未確認） | ○ |

※ **バンドルの BREE と、IDE 製品としての必要 Java は別物である。** この列は
`org.eclipse.ui.workbench` の MANIFEST を実測した値で、4.17〜4.20 ではまだ `JavaSE-1.8` のままだった
（`JavaSE-11` に変わるのは 4.21 / 2021-09 まで。4.28 / 2023-06 で `JavaSE-17`、4.40 / 2026-06 で `JavaSE-21`）。
一方 Eclipse IDE は製品として 2020-09 から Java 11 を要求している。
**このプラグインの下限 4.17 は後者（製品としての必要 Java が 11 になった版）に合わせたもの**で、
バンドルの BREE に合わせたものではない。

JDT Core 自身の BREE も実測した。JavaSE-1.8（〜3.24.0）→ JavaSE-11（3.27.0〜3.33.0）→
JavaSE-17（3.34.0〜）と上がっている。

**解析できる Java の版は JDT の版で決まる**。ツールは `JavaCore.latestSupportedJavaVersion()` を
ログに出し、`source.level` がそれを超えるときは丸めた旨を表示する。古い Eclipse に入れた場合、
入るけれども新しい文法のソースは解析できない、という状態になりうる。

## Pleiades All in One との対応

Pleiades All in One（Full Edition）は **Eclipse 実行用の JDK を同梱**しており、
`eclipse.ini` の `-vm` がその JDK を指している。開発用には複数バージョンの JDK が
`<インストール先>/java/<版>` に入っている。以下は
[公式のダウンロードページ](https://willbrains.jp/)（各年の `pleiades_distros<年>.html`）の
「Eclipse 実行用 JDK」と「JDK」の記載から写したもの。

| Pleiades | ベースの Eclipse | Eclipse 実行用 JDK | 同梱 JDK（ベンダー） | 本プラグイン |
|---|---|---|---|---|
| 2020 | 2020-03 〜 2020-12 | **11** | 6u48、7u80、8u202、11.0.9、15.0.1（11・15 は [AdoptOpenJDK](https://adoptopenjdk.net/) へのリンクあり） | ○（2020-09 以降のベース） |
| 2021 | 2021-03 / 2021-09 / 2021-12 | **17** | 6u48、7u80、8u202、11.0.13、17.0.1（11・17 は [Adoptium](https://adoptium.net/) へのリンクあり） | ○ |
| 2022 | 2022-03 〜 2022-12 | **17** | 6u48、7u80、8.0.362、11.0.18、17.0.6（8 以降は [Adoptium](https://adoptium.net/releases.html) へのリンクあり） | ○ |
| 2023 | 2023-03 〜 2023-12 | **17** | **Adoptium** 8u402、11.0.21、17.0.10、21.0.2 | ○ |
| 2024 | 2024-03 〜 2024-12 | **21** | **Adoptium** 8u432、11.0.25、17.0.13、21.0.5 | ○ |
| 2025 | 2025-03 〜 2025-12 | **21** | 8u462、11.0.28、17.0.16、21.0.8、25.0.0（ベンダーの明記なし） | ○ |
| 2026 | 2026-03 / 2026-06 | **21** | 8u492、11.0.31、17.0.19、21.0.11、25.0.3（ベンダーの明記なし） | ○ |

同梱 JDK のベンダーは、ページ上の表記をそのまま写した。2023・2024 は「Adoptium」と明記されており、
2020〜2022 は版の並びにベンダーのサイトへのリンクが張ってある（2020 は AdoptOpenJDK、
2021・2022 は Adoptium）。**2025・2026 のページにはベンダーの記載が無い**。
AdoptOpenJDK は 2021 年に Eclipse Adoptium（配布物の名前は Temurin）へ移管されているので、
2021 以降は Temurin とみてよいが、2025・2026 について一次情報で確認できたわけではない。
なお Java 6・7 は 2023 で同梱から削除された（容量削減）。

つまり **Pleiades はどの版でもこのプラグインが入る**（2020 以降を確認。Eclipse を動かす JDK は
2020 年版ですでに 11、2021 年以降は 17 / 21 で、下限の Java 11 をどの版も満たしている。
下限を Java 8 にしていても Pleiades 利用者には何の得も無かった、ということでもある）。同梱 JDK の一覧は、むしろ<b>解析に使う JDK</b>（17 以上、推奨 25）を選ぶときに見てほしい。
2023 以降なら 17 と 21 が、2025 以降なら 25 が同梱されている。

注意点:

- 各年のページに載っているスペックは、**その年の最終版**（例: 2022 のページなら 2022-12 ベース）のものである
- **Standard Edition には JDK が付かない**（`jre/` も無い）。自分で JDK 17 以上を用意し、
  `eclipse.ini` の `-vm` で指す必要がある
- Java / Ultimate の Full Edition は `jre/` を持たず、`-vm` が `java/<LTSの版>` を指している。
  それ以外の Full Edition は `-vm` が `jre/` を指す
- 2023 で Java 6 / 7 の同梱が削除され、Java 21 が追加された。2024 以降は非 LTS の JDK は
  同梱されず、「Java 22/23/24/26 Support」プラグインだけが入る（解析対象のソースが
  非 LTS の新しい文法を使う場合、**JDT が対応していれば解析はできる**。同梱 JDK の有無とは別の話）

手元の環境を確認する場所は次の3つである。

1. `<インストール先>/java/` … どの版の JDK が入っているか
2. `<インストール先>/eclipse/eclipse.ini` の `-vm` … Eclipse がどの JDK で動いているか（17 未満なら書き換える）
3. ヘルプ → Eclipse IDE について → インストール詳細 → プラグイン … `org.eclipse.jdt.core` の版

プラグインを入れた後なら、ビューのバナーのツールチップに「実行 JVM / JDT Core の版 /
解析できる Java の上限」が出る。

## 古い Java で動いている Eclipse について

**Eclipse 側は Java 11 以上であればよい**（解析は別プロセスの新しい JDK で走るため）。
かつては「Eclipse も Java 17 以上」が条件だったが、解析を別プロセスへ出したことでその制約は無くなった
（経緯は [eclipse-plugin-ui-qa.md](eclipse-plugin-ui-qa.md) の Q15 と
[out-of-process-analysis-design.md](out-of-process-analysis-design.md)）。
そのあと一度 Java 8 まで下げたが、得られるものが無いと分かったので 11 に戻した
（[eclipse-plugin-java-floor-qa.md](eclipse-plugin-java-floor-qa.md)）。

残る条件は「解析に使う JDK（17 以上、推奨 25）がどこかにあること」だけで、
Pleiades なら 2023 以降は同梱の JDK がそのまま使える。
