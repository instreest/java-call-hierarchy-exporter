# test/workspace-demo

回帰テスト `test/regression/workspace`（ワークスペースの他のプロジェクトのキャッシュの結合。`workspace.projects`）用の、
Eclipse だけで管理した形（`pom.xml` の無い `.classpath` だけのプロジェクト）の小さなプロジェクトです。
`test/demo` を**相手の jar**（`../demo/extjars/demo-app.jar`。`.classpath` の `kind="lib"`）として解決し、
`test/demo` のメソッドを呼ぶ側（参照元）になります。

- `src/teamw/BatchMain.java` … 起点。`BatchJob.run()` と `Unrelated.standalone()` を呼ぶ
- `src/teamw/BatchJob.java` … `test/demo` のメソッド（`UserDaoImpl`・`Counter`・`Notifier`・`OrderService`）を呼ぶ。
  `ext-src/teamb/NightJob` と同じ呼び出しに、自分のプロジェクトの `Dao` の実装（`RemoteDao`）を `OrderService` に渡す形を足してある
- `src/teamw/RemoteDao.java` … `test/demo` のインターフェース `fx.dao.Dao` の実装。`test/demo` の `Dao#findById` の CHA の候補に
  入る（依存先の側から見た「実装が他のプロジェクトにある」形）
- `src/teamw/AsyncMain.java` / `AsyncJob.java` … `Thread#start()` → `run()` のライブラリ呼び出し規則でしか `test/demo` に届かない起点。
  `workspace.scope=callers` の到達の判定が規則の辺も数えること（数えないと、この起点が静かに消える）
- `src/teamw/Unrelated.java` / `Helper.java` … `test/demo` に届かない階層。`workspace.scope=callers`（既定）では CSV に出ず、
  `all` では出る
- `config/jche.properties` … このプロジェクトを自分の `project.root` として解析する設定。回帰テストの
  `config-file.properties` が `workspace.projects` にこのファイルを指す（設定ファイルの形。フォルダの形は
  `.classpath` から同じ内容を決める）
