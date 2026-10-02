# 被参照スキャンで呼び出し元メソッドと行番号まで出す — 実装時の QA 一覧

「外部 jar からの呼び出し元についての情報をもう少し正確にしたい」への対応で、迷ったこと・困ったことと、
その結論を Q&A の形で残す。依存ライブラリは増やさず、解析キャッシュの形式も変えない。

対応の要点:

- `ClassFileRefs` が定数プールに加えて各メソッドの命令列（Code 属性）を歩き、`invoke*` 命令の参照先を
  「呼び出し元メソッド名・行番号（LineNumberTable）」付きで返す。ラムダ式・メソッド参照（`invokedynamic`）は
  BootstrapMethods 属性の MethodHandle 引数を辿る
- `call-hierarchy.csv` の被参照の行は、`caller` 列が呼び出し階層の行と同じスタックトレース形式
  `at teamb.NightJob.run(NightJob.java:15)` になる。行番号情報の無い class は `(Unknown Source)`
- 命令列から辿れなかった定数プールの参照は、従来どおりクラス名だけを `caller` にして残す（落とさない）
- 回帰テストに、ラムダ・メソッド参照と `-g:none` の class を入れた `team-e-lambda.jar` を加える

---

## 設計

### Q1. なぜ ASM や BCEL を使わなかったか

ASM を足せば命令列の走査は数十行で済む。だがこのツールの依存は JDT の 1 本だけで、
Pleiades 同梱の JDT jar を集めれば閉域ネットワークでも `javac` → 実行できる（README の
「Pleiades/Eclipse環境」）。ASM は Eclipse に同梱されていないので、その手順が成立しなくなる。
`//DEPS` 行・`pom.xml`・Eclipse プラグインの同梱物・オフライン手順の全部に波及する変更になる。

必要なのは「`invoke*` 命令のオペランドと、そのオフセットの行番号」だけで、スタックの中身や
値の追跡は要らない。命令の長さの表（可変長は 3 つだけ）があれば前へ進めるので、自前で書いた。
`ClassFileRefs` は既に定数プールを全タグ読んでいたので、その延長で足りた。

### Q2. 命令列を歩くのに「命令の長さ」以外の解釈は要るか

要らない。参照先は `invokevirtual` / `invokespecial` / `invokestatic` / `invokeinterface` のオペランド
（定数プール索引）にそのまま入っている。命令を順に読んで、これらに当たったら索引を拾うだけ。
分岐先へ飛ぶ必要も無い（全命令を先頭から順に見れば全部の呼び出し箇所を通る）。

可変長は `tableswitch` / `lookupswitch`（次のオフセットを 4 バイト境界に詰めてから表が続く）と
`wide`（次のオペコードが `iinc` なら 6 バイト、それ以外は 4 バイト）の 3 つだけ。
それ以外はオペコードで長さが決まる（JVMS 第 6 章の表を `OPERAND_BYTES` に写した）。

未知のオペコード（`breakpoint`、`impdep1` / `impdep2`、または壊れた class）に当たったら、
その先の解釈は信用できないのでそのメソッドの走査をやめる。拾えなかった参照は定数プール側に
残るので、Q5 の経路でクラス単位の粒度で出る。

### Q3. ラムダ式とメソッド参照はどう拾うか

`Runnable r = counter::bump;` は `invokevirtual` にならず `invokedynamic` になる。
`invokedynamic` のオペランドは定数プールの InvokeDynamic を指し、そこには
「BootstrapMethods 属性の何番目か」と呼び出し名・型しか無い。実際に指しているメソッド
（`Counter.bump`）は、BootstrapMethods 属性の引数に MethodHandle として入っている
（LambdaMetafactory の `implMethod`）。

BootstrapMethods 属性は class の末尾（メソッドの後）にあるので、命令列を歩いた時点では
参照先が分からない。並びを「メソッドの宣言順 → 命令のオフセット順」に保ちたいので、
歩いた時点では callee が null の仮の項目を置いておき、属性を読んでから同じ位置で置き換える。

ラムダ本体（`() -> c.bump()`）はコンパイラが同じクラスに合成する `lambda$run$0` メソッドに
なり、その中の `invokevirtual` として拾える。呼び出し元メソッド名は `lambda$run$0` だが、
ソース解析側は「ラムダ内の呼び出しは囲みメソッドに計上する」ので、それに揃えて `run` に
読み替える（`lambda$` と最後の `$` の間を取る）。行番号はラムダ本体の行なので、貼り付けて飛ぶ先は正しい。

文字列結合（`"a" + x`）も `invokedynamic`（StringConcatFactory）だが、引数に MethodHandle は
無いので何も足さない。仮の項目はそのまま消える。

### Q4. 行番号情報が無い class（`-g:none`）はどう出すか

LineNumberTable が無ければ行は分からず、SourceFile 属性も無いのでファイル名も分からない。
呼び出し元メソッド名までは分かる（メソッドテーブルは必ずある）ので、JVM のスタックトレースと
同じ `at teame.NoDebugJob.run(Unknown Source)` にする。Eclipse の Java スタック・トレース・
コンソールもこの形を受け付け、クラス名とメソッド名で飛ぶ。

「行番号が無い」ことを別の注記で示す案（`external-ref:EXACT/NOLINE` 等）は採らなかった。
注記の列は照合の種類を表す列で、行番号の有無は caller 列を見れば分かる。
jar ごとに行番号の質が違うことは README に書く。

### Q5. 命令列から辿れなかった定数プールの参照はどうするか

理屈の上では、Methodref を `ldc` で MethodHandle 定数として積む形や、Code 属性の無いメソッドの
アノテーション属性から参照する形がありうる（実在の配布物ではまず現れない）。
定数プールの Methodref / InterfaceMethodref のうち命令列から辿れなかったものを `unlocatedRefs` に
残し、従来どおりクラス名だけを caller にして出す。「呼び出しを静かに落とさない」の原則どおり。
これがあるので、Q2 で走査を途中でやめても参照そのものは失われない。

### Q6. 出力の粒度が変わって件数が増えたのはなぜか

対応前は定数プールの項目 1 つが 1 行だった。定数プールでは同じ参照先は 1 項目にまとまるので、
`new Counter()` を 2 か所で書いても 1 行だった。対応後は呼び出し箇所ごとに 1 行になる
（`test/demo` の `NightJob` は 20 行目と 21 行目の `new Counter()` が別々に出て、49 件 → 52 件）。
呼び出し階層の行が「呼び出し 1 件につき 1 行」なのと同じ粒度になった。

### Q7. 解析キャッシュへの影響は無いか

無い。被参照スキャンの結果は `analysis-cache.tsv` には入らず、`call-hierarchy.csv` に直接書く独立した
機能で、jar を読むのも実行のたび。`CacheFormat.VERSION` は上げていないので、既存利用者のキャッシュは
そのまま使われる。

## テスト

### Q8. 既存の jar を作り直さずにどう検査したか

`invokedynamic` と `-g:none` の経路は既存の `team-b-batch.jar` 等では通らない。既存の jar を作り直すと
FatJar / ear の入れ子も作り直しになるので、新しい `team-e-lambda.jar` を 1 つ足した。
`teame.LambdaJob`（メソッド参照とラムダ、既定の `-g`）と `teame.NoDebugJob`（`-g:none`）を同じ jar に入れ、
回帰テストの `whole` / `entry` の期待値に次の 4 行が加わる:

```
at teame.LambdaJob.run(LambdaJob.java:13),fx.util.Counter.Counter(int),...
at teame.LambdaJob.run(LambdaJob.java:14),fx.util.Counter.bump(),...        ← メソッド参照 c::bump
at teame.LambdaJob.run(LambdaJob.java:15),fx.util.Counter.bump(),...        ← ラムダ () -> c.bump()（lambda$run$0 を run に読み替え）
at teame.NoDebugJob.run(Unknown Source),fx.util.Counter.initialCount(),...
```

既存の jar から出る行は、caller が `teamb.NightJob` → `at teamb.NightJob.run(NightJob.java:14)` のように
変わるだけで、参照先・jar 名・注記は同じ（Q6 の 3 行が増える）。被参照以外の行に差分が無いことを
確認したうえで期待値を更新した。

## 結び先の探し方

### Q9. `INHERITED` の結び先は「解決の宣言」か「選択の本体」か。探索を `MethodSelection` に寄せた

Issue [#186](https://github.com/instreest/java-call-hierarchy-exporter/issues/186)。

- **何が問題だったか**。jar からの被参照を継承したメソッドに結び付ける `ExternalUsageScanner#inheritedFrom` は、
  `MethodSelection#search` と同じ順（親クラスの連鎖 → 最も特定的な親インターフェース）を**自前で**持っていた。
  CONTRIBUTING.md は「実装探索は `MethodSelection` の入口だけを通す」と決めているのに 2 つ目の実装があり、
  O 行（型引数を具体化した上書き）と H 行の 8 列目（親クラスから継承した実装）を見なかった。
  `class UserRepo extends AbsRepo<User> { void save(User) }` への参照 `UserRepo.save(java.lang.Object)` は
  `AbsRepo.save` に、`class UserRepo2 extends BaseRepo implements Repo<User>`（`BaseRepo` が `save(User)` を持つ）への
  参照 `UserRepo2.save(java.lang.Object)` は `Repo.save` に結び付いていた
- **どちらを出すか**。README（`external-ref:INHERITED` は「JVM がその参照を解決する宣言」）と `test/jls` の突き合わせ
  （抽象の `I1.m` に結び付く）のとおり、**解決（JVMS 5.4.3.3）の宣言**を出す約束を保つ。被参照の行は「jar のこの命令が
  ソースのどの宣言を指すか」であり、そこから実際に動く本体（部分型の上書き）へ降りるのは、その宣言を呼び出し先とする
  通常の解決（CHA）の仕事。`implementationOfSignature`（選択。本体を持つ宣言まで降りる）に置き換えると、抽象の宣言への
  参照が別の型の default に結び付き、出力の意味が変わる
- **ブリッジは解決の側の話**。O 行と H 行の 8 列目はどちらも javac がその型にブリッジメソッド（`save(java.lang.Object)`）を
  作る形なので、JVM の解決はその型で止まる。ソースにはブリッジが見えないので、「その型の O 行の上書き」「その型の
  H 行の継承した実装」として引くのが解決の写しになる。参照の側は、上書きの無い版の型に対してコンパイルされていると
  ブリッジのディスクリプタで参照してくる（javac は宣言が見えれば `save(User)` を書く）ので、実際に出るのは
  jar どうしの版がずれた形が主だが、結び先が違えば行の意味（どの宣言が使われているか）が変わる
- **直し方**。`MethodSelection` に解決の入口 `resolvedDeclaration(型FQN, シグネチャ)` を 1 つ足し、`inheritedFrom` を消して
  `lookupRef` をそれに置き換えた。各段でブリッジの形（O 行・H 行の 8 列目。`search` と同じ材料と同じ補助メソッド）を先に、
  次にキーの同じ宣言を見る。選択と違う点は解決の規定どおり: 連鎖では抽象の宣言でも止まる（本体の有無を見ない）、
  private も飛ばさない（アクセスの検査は解決の後）、パッケージアクセスも見ない（ディスクリプタの一致だけ。
  上書きの可否は選択の話）。インターフェースの段は従来どおり最も特定的な宣言のうち本体のあるものを先に、無ければ抽象の宣言
- **検査**。`test/pruning` の外部 jar の題材に `Client.callRepo`（O 行）と `Client.callRepo2`（H 行の 8 列目）を足した。
  参照の側は `extstub` の版（上書き・実装の無い `UserRepo` / `UserRepo2`）に対してコンパイルし、ディスクリプタを
  `save(java.lang.Object)` にする
