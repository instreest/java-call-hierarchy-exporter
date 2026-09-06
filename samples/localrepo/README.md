# samples/localrepo

回帰テスト（`test/regression/maven`、`test/regression/gradle`）のサンプルプロジェクト
（`samples/maven-demo`、`samples/gradle-demo`）が依存するライブラリ `sample.deps:greeter:1.0` を置いた、
Maven 形式のローカルリポジトリです。ネットワークに出ずに依存を解決させるためにあります。
ソースは `samples/localrepo-src/` にあります。

- `sample.deps.Greeter` … static ファクトリ `of(String)` と、戻り値を次の呼び出しに使わせる `greet()` / `formatter()`
- `sample.deps.Formatter` … `Greeter#formatter()` の戻り値の型
- `sample.deps.Handler` … サンプルのソース側（`sample.app.LogHandler`）が実装するインターフェース
- `sample.deps.AbstractHandler` … `Handler` を実装する基底クラス。`LogHandler` はこれを継承する

Maven はこのリポジトリから `~/.m2/repository/sample/deps/greeter/1.0/` へコピーして使い、Gradle は
ここのファイルをそのまま使います（ローカルのファイルリポジトリはキャッシュにコピーしない）。
ツールが受け取るクラスパスはそれぞれその場所を指します。

パッケージ名を `sample.lib` にしていないのは、リポジトリの `.gitignore` が `lib/` を無視する
（利用者が Eclipse から集めた JDT の jar を置く場所）ため、`sample/lib/` 配下が追跡されなくなるからです。

jar を作り直すとき（`samples/` で実行）:

```bash
javac --release 17 -d /tmp/greeter-classes -encoding UTF-8 localrepo-src/sample/deps/*.java
jar --create --file localrepo/sample/deps/greeter/1.0/greeter-1.0.jar -C /tmp/greeter-classes .
cd localrepo/sample/deps/greeter/1.0
for f in greeter-1.0.jar greeter-1.0.pom; do sha1sum "$f" | cut -d' ' -f1 > "$f.sha1"; md5sum "$f" | cut -d' ' -f1 > "$f.md5"; done
```

中身を変えたときは版（`1.0`）を上げて、`greeter-1.0.pom` と両サンプルの依存の指定も合わせて変えてください。
Maven はリリース版の jar を一度ローカルリポジトリに入れると取り直さないので、同じ版のまま中身を
変えると `~/.m2/repository` に残った古い jar が使われ続けます（GitHub Actions のキャッシュも同様）。
