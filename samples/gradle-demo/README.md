# samples/gradle-demo

回帰テスト `test/regression/gradle` 用の、依存 jar を `library.folders` で指定しない Gradle プロジェクトです。
`build.gradle` は `samples/localrepo`（ファイルリポジトリ）の `sample.deps:greeter:1.0` に依存します。

Eclipse（Buildship）で開いたときの形にしてあり、`.project`（Gradle の nature）と `.classpath`
（`kind="src"` のソースフォルダと Gradle のクラスパス・コンテナ）を含みます。テストの設定では
`source.folders` も空欄にして、ソースフォルダを `.classpath` から取る経路を通します。

ツールは `library.folders` が空欄なので、`project.root`（このフォルダ）の `build.gradle` を見つけ、
初期化スクリプト（`.cache/build-classpath/jche-classpath.init.gradle`）を付けて

```
gradle -q --init-script ... jcheCompileClasspath
```

を実行し、Gradle が解決した `compileClasspath`（ローカルのファイルリポジトリなので
`samples/localrepo/sample/deps/greeter/1.0/greeter-1.0.jar` そのもの）をそのまま使います。
`gradlew` は置いていないので PATH 上の `gradle` が要ります（無ければテストは SKIP）。
GitHub Actions では実行 JDK（25）に対応する版を固定して入れます（`.github/workflows/smoke.yml`）。

`src/main/java/sample/app/` のソースは `samples/maven-demo` と同じです（説明はそちらの README）。
