# jar の中から呼び戻される呼び出しの契約 — Q&A

Issue [#136](https://github.com/instreest/java-call-hierarchy-exporter/issues/136) の段階 1。
`Thread#start()` → `run()` のように、ソースの外（JDK）を経由して自分のコードへ戻ってくる呼び出しを、
契約表で繋いだ判断を残す。使い方は [callback-contracts.md](callback-contracts.md)。
関連: [lambda-expansion-qa.md](lambda-expansion-qa.md)（渡した値の具象型を決める仕組み）。

## 結論

- 契約表（`呼び出し先 -> 位置 : 呼び戻されるシグネチャ`）を持ち、呼び出し先が jar の中でも
  「渡した値のこのメソッドを呼ぶ」と分かるものは辺を足す（`[RESOLVED:CALLBACK]`）
- 呼び出し先自身の行（`[EXTERNAL]`）はそのまま残し、その**次**に呼び戻される側を並べる
- 呼び戻される側は `inDegree` に数え、到達判定にも入れる。`Thread` で起動する `Runnable` の
  `run` が `ENTRY_CANDIDATE` に混ざらないようにするため
- 渡した値の具象型が分からなければ辺を張らない。jar の型（`Runnable` そのもの）の全実装を
  候補に並べることはしない
- 段階 1 は JDK の同梱分だけ。設定ファイルで足す表とプラグインは段階 3

### Q1. 呼び出し先の行を消して、呼び戻される側に置き換えないのはなぜか

`start()` を呼んでいる事実は事実として残すため。契約は「jar の中でこう動くはず」という
人の記述であって、解析で確かめたものではない。呼び出し先の行を残しておけば、契約が
間違っていた（版で挙動が変わった等）ときにも、何を元に繋いだかが読める。

### Q2. なぜ通常の解決（`resolve`）の候補に混ぜず、別の候補として扱うのか

通常の解決は「呼び出し先の宣言に対してどの実装が動くか」を決めるもので、
候補はすべて呼び出し先と同じシグネチャを持つ。契約で繋ぐ先は**別のメソッド**
（`start()` に対して `run()`）なので、同じ配列に混ぜると `[UNEXPANDED:CHA] 候補N件` の
件数や `methods.csv` の `unresolvedCause` が意味を失う。`CallResolver.callbackTargets` として
分け、読み手（`StreamingTreeWalker` / `inDegrees` / `reachableFrom`）がそれぞれ足す。

### Q3. `inDegree` と到達判定に入れるのはなぜか

入れないと `Job.run` が「誰からも呼ばれていない」に見える。`methods.csv` の役割は
デッドコードの疑いを絞ることで、契約で呼ばれると分かっているものを候補に残すのは
役割に反する。経路に依らず決まる分（`new` した型・ラムダ）だけを数え、引数で渡ってきた
ものは経路ごとにしか決まらないので階層側でだけ繋ぐ（`DATAFLOW_PARAM` と同じ扱い）。

### Q4. `Thread(Runnable)` の位置を `c0` ではなく `c*` にしたのはなぜか

`Thread` のコンストラクタは `Thread(Runnable)` / `Thread(Runnable, String)` /
`Thread(ThreadGroup, Runnable)` / `Thread(ThreadGroup, Runnable, String, long)` と、
`Runnable` の位置がまちまち。位置を決め打ちすると版やオーバーロードで外れる。
`c*` は全実引数を試し、`run()` の実装を持つ型のものだけが辺になるので、
位置を知らなくても正しく繋がる（`String` や `ThreadGroup` には `run()` が無い）。

### Q5. `list.forEach(Runnable::run)` は繋がるのか

繋がらない。契約 `Iterable#forEach -> a0 : accept` で引数を見ると `Runnable::run` の
メソッド参照（`Z:java.lang.Runnable#run()`）だが、これは「要素の `run` を呼ぶ」であって
要素が何かは分からない。`Runnable#run` は jar のメソッドでソースが無いので辺にしない。
要素の具象型まで追うには、コレクションの要素の出所（#127 で足した `elements`）と
契約を組み合わせる必要があり、段階 1 では扱わない。

### Q6. 同梱の表を「呼び戻す」と言い切れるものに絞ったのはなぜか

契約は解析で確かめないので、間違いがそのまま出力に載る。「引数に関数型インターフェースを
取るメソッドは全部呼び戻す」という一般則にすると、保存するだけで呼ばない
（`addListener` 系でも実際は登録だけのもの、`Map#put` に渡した `Runnable` 等）ものまで
繋いでしまう。JDK の中で、仕様として呼ぶことが決まっているものに限る。
足りなければ表に行を足す。段階 3 で設定ファイルから足せるようにする。

### Q7. `Runnable` の実装がソースに増えると、他の出力も変わる

`test/demo` に `Job implements Runnable` を足したことで、`Runnable#run()` の CHA 候補が
0 件（`NO_IMPL`）から複数になった。その結果、`tasks.forEach(Runnable::run)` の
メソッド参照の行（`Runnable::run` そのものへの呼び出しとして記録している）に
`Job.run` が候補として並ぶ。これは CHA の従来どおりの振る舞いで、契約とは無関係。
あわせて、ラムダ／メソッド参照の解決（`DATAFLOW_LAMBDA`）を経路に依らない分だけ
`resolve` の段 1 より前に移した。候補数が 0 件でも複数でも、渡された値がラムダなら
実行されるのはその本体で、段 1 の候補数で結果が変わるのは筋が通らないため。
これで `Runnable r = this::helper; r.run();` の `helper` が `inDegree` に数えられるようになった。
