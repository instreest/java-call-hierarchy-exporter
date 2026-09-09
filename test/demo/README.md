# test/demo

回帰テスト（`test/regression/`）と動作確認のための小さな Java プロジェクトです。
インターフェースの多実装・ファクトリ・コンストラクタ注入・リフレクション・ラムダ・enum・record・
匿名クラス・循環・除外パッケージ・型解決失敗（存在しない import）など、このツールの解決経路を
ひととおり踏むように書いてあります。動くプログラムとしての意味はありません。

- `src/` … 解析対象のソース。`fx.app.Legacy` は存在しないライブラリを import しており、意図的にコンパイルできません
  - `src/fx/di/` … Spring による DI の解決（`SPRING_DI` / `SPRING_DI_QUALIFIER`）の確認用。
    フィールド注入・`@Qualifier` での指名・`@Bean` メソッドによる登録・Bean でない実装が候補から外れること・
    Bean が抽象基底クラスから実装を継承する形を含みます
  - `src/org/springframework/` … 上記が使う Spring の注釈のスタブ（本物の Spring には依存させないため、
    このプロジェクト自身に置いています）
- `ext-src/` … 「他チームの jar」の中身（`teamb.NightJob`、`teamc.ReportJob`、`teamd.WebJob`）。
  `external.library.folders` の被参照スキャンの入力
- `extjars/` … `ext-src/` をコンパイルして作った jar と、`src/` 自身をコンパイルした `demo-app.jar`
  （自プロジェクトの jar が混ざっていても被参照として数えないことの確認用）
  - `team-b-batch.jar` … 普通の jar（`teamb.NightJob`）
  - `team-c-boot.jar` … Spring Boot 形式の FatJar。`BOOT-INF/classes/` に `teamc.ReportJob`、
    `BOOT-INF/lib/` に `team-b-batch.jar` と `demo-app.jar` を無圧縮で入れ子にしている
    （中の jar を開けること、中の自プロジェクト jar を除外できることの確認用）
  - `team-d-app.ear` … ear → war → jar の 2 段の入れ子。`team-d-web.war` の `WEB-INF/classes/` に
    `teamd.WebJob`、`WEB-INF/lib/` に `team-b-batch.jar`（圧縮あり）
- `deps-src/` … `library.folders` に渡す依存 jar の元。`fx.app.Legacy` が import している `missing.lib` パッケージの型と、
  ソース側の `fx.dao.Dao` を実装する基底クラス `LibDao`（`fx.dao.LibBackedDao` がこれを継承する。jar があるときだけ
  `LibBackedDao` が `Dao` の実装として見え、`Dao#findById` の CHA 候補が 1 件増える）。
  パッケージ名どおりの `missing/lib/` に置くと `.gitignore` の `lib/` に掛かるので、フォルダを作らず直下に置いている
- `deps/` … `deps-src/` をコンパイルして作った `missing-lib.jar`。回帰テストの `jarchange` ケースが
  「依存 jar を足す・外す」をこのフォルダの有無で再現する。`whole` / `entry` ケースでは渡さないので、
  `Legacy` は型解決に失敗したまま（意図どおり）

jar を作り直すとき。`deps/missing-lib.jar` は `fx.dao.Dao` に依存するので先に作り、
`src/` 側は `Legacy.java` と `Main.java`（存在しない型を使う）を除いてコンパイルする:

```bash
cd test/demo
javac --release 17 -d /tmp/dao-classes -encoding UTF-8 src/fx/dao/Dao.java
javac --release 17 -cp /tmp/dao-classes -d /tmp/missing-classes -encoding UTF-8 deps-src/*.java
jar --create --file deps/missing-lib.jar -C /tmp/missing-classes .
find src -name '*.java' ! -name Legacy.java ! -name Main.java > /tmp/demo-sources.txt
javac -cp deps/missing-lib.jar -d /tmp/demo-classes -encoding UTF-8 @/tmp/demo-sources.txt
jar --create --file extjars/demo-app.jar -C /tmp/demo-classes .
javac -d /tmp/teamb-classes -cp /tmp/demo-classes -encoding UTF-8 ext-src/teamb/NightJob.java
jar --create --file extjars/team-b-batch.jar -C /tmp/teamb-classes .

# FatJar（Spring Boot 形式）。Spring Boot は中の jar を無圧縮で格納するので --no-compress で揃える
javac --release 17 -cp extjars/demo-app.jar -d /tmp/boot/BOOT-INF/classes -encoding UTF-8 ext-src/teamc/ReportJob.java
mkdir -p /tmp/boot/BOOT-INF/lib && cp extjars/team-b-batch.jar extjars/demo-app.jar /tmp/boot/BOOT-INF/lib/
jar --create --file extjars/team-c-boot.jar --no-compress -C /tmp/boot .

# ear → war → jar の 2 段の入れ子（こちらは圧縮あり）
javac --release 17 -cp extjars/demo-app.jar -d /tmp/war/WEB-INF/classes -encoding UTF-8 ext-src/teamd/WebJob.java
mkdir -p /tmp/war/WEB-INF/lib /tmp/ear && cp extjars/team-b-batch.jar /tmp/war/WEB-INF/lib/
jar --create --file /tmp/ear/team-d-web.war -C /tmp/war .
jar --create --file extjars/team-d-app.ear -C /tmp/ear .
```
