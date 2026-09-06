# samples/maven-demo

回帰テスト `test/regression/maven` 用の、依存 jar を `library.folders` で指定しない Maven プロジェクトです。
`pom.xml` は `samples/localrepo`（ファイルリポジトリ）の `sample.deps:greeter:1.0` に依存します。

ツールは `library.folders` が空欄なので、`project.root`（テストでは `samples`）に `pom.xml` が無いことを見て
ソースフォルダの上位（このフォルダ）の `pom.xml` を見つけ、

```
mvn -B -q -fae org.apache.maven.plugins:maven-dependency-plugin:build-classpath -Dmdep.outputFile=...
```

を実行して、Maven が解決したクラスパス（`~/.m2/repository/sample/deps/greeter/1.0/greeter-1.0.jar`）を
そのまま使います。`mvnw` は置いていないので PATH 上の `mvn` が要ります（無ければテストは SKIP）。

`src/main/java/sample/app/` のソースは `samples/gradle-demo` と同じです。`Service.run` が依存 jar の型を
「戻り値で受けて次の呼び出しに使う」形で使っており、jar が渡らなければ `Greeter.of` 以降の呼び出しが
型解決に失敗します。期待出力（`test/regression/maven/expected/`）にはそれらが `ソースなし（展開不可）` の
行として入っているので、jar が渡ったことは出力の比較で確かめられます。
