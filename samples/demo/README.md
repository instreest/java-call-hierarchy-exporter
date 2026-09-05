# samples/demo

回帰テスト（`test/regression/`）と動作確認のための小さな Java プロジェクトです。
インターフェースの多実装・ファクトリ・コンストラクタ注入・リフレクション・ラムダ・enum・record・
匿名クラス・循環・除外パッケージ・型解決失敗（存在しない import）など、このツールの解決経路を
ひととおり踏むように書いてあります。動くプログラムとしての意味はありません。

- `src/` … 解析対象のソース。`fx.app.Legacy` は存在しないライブラリを import しており、意図的にコンパイルできません
- `ext-src/` … 「他チームの jar」の中身（`teamb.NightJob`）。`external.library.folders` の被参照スキャンの入力
- `extjars/` … `ext-src/` をコンパイルして作った `team-b-batch.jar` と、`src/` 自身をコンパイルした `demo-app.jar`
  （自プロジェクトの jar が混ざっていても被参照として数えないことの確認用）
- `deps-src/` … `fx.app.Legacy` が import している `missing.lib` パッケージの中身。`library.folders` に渡す依存 jar の元。
  パッケージ名どおりの `missing/lib/` に置くと `.gitignore` の `lib/` に掛かるので、フォルダを作らず直下に置いている
- `deps/` … `deps-src/` をコンパイルして作った `missing-lib.jar`。回帰テストの `jarchange` ケースが
  「依存 jar を足す・外す」をこのフォルダの有無で再現する。`whole` / `entry` ケースでは渡さないので、
  `Legacy` は型解決に失敗したまま（意図どおり）

jar を作り直すとき（`Legacy.java` と `Main.java` はコンパイルできないので除く）:

```bash
cd samples/demo
find src -name '*.java' ! -name Legacy.java ! -name Main.java > /tmp/demo-sources.txt
javac -d /tmp/demo-classes -encoding UTF-8 @/tmp/demo-sources.txt
jar --create --file extjars/demo-app.jar -C /tmp/demo-classes .
javac -d /tmp/teamb-classes -cp /tmp/demo-classes -encoding UTF-8 ext-src/teamb/NightJob.java
jar --create --file extjars/team-b-batch.jar -C /tmp/teamb-classes .
javac --release 17 -d /tmp/missing-classes -encoding UTF-8 deps-src/*.java
jar --create --file deps/missing-lib.jar -C /tmp/missing-classes .
```
