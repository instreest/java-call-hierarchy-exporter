# キャッシュの再解析の判定を Souther でモデル化する — QA 一覧

Issue なし（試作）。キャッシュを再利用するか・どのファイルを解析し直すかの判定を、
業務ルール向けの言語 [Souther](https://github.com/souther-lang/souther) で仕様モデルとして書き起こし、
Souther の `souther examples`（例がモデルのどこまでを網羅しているかの測定）に掛けた記録。

対応の要点:

- モデルは [`docs/souther/cache_reanalysis.sou`](souther/cache_reanalysis.sou)。本体のコードではなく、
  `CacheUpdater` の判定の規則だけを写した**仕様**で、本体からは参照しない
- 判定は 2 つの behavior に分けた
  - `decideCache`: キャッシュ全体を丸ごと捨てるか（6 つの理由）・そのまま残すか・差分更新に進むか
  - `decideBlock`: 旧キャッシュの 1 ブロック（1 ファイル）を捨てるか・書き写すか・解析し直すか（8 つの理由）
- 例は 24 行（`decideCache` が 11 行、`decideBlock` が 13 行）。すべてコンパイル時に評価されて成り立ち、
  出力の場合分けは 8/8・10/10、分岐は 16/16・22/22 を網羅した
- 測り方（Souther 0.3.0、JDK 25）:

  ```bash
  souther examples docs/souther/cache_reanalysis.sou            # 網羅の報告
  souther examples docs/souther/cache_reanalysis.sou --strict   # 欠けがあれば失敗で終わる
  ```

## モデルと本体の対応

| モデル | 本体 |
|---|---|
| `NoCache` / `Unreadable` / `Incompatible` / `HeadDamaged` | `CacheUpdater#readOldLibraries` が null を返す経路（`analysis.cache.incompatible`・`headDamaged`・`unreadable`） |
| `EnvironmentRejected` | `CacheUpdater#environmentStillAccepted`（`analysis.cache.environmentRejected`） |
| `Truncated` | `CacheUpdater#scanOldCache` の最終行（Z 行）の突き合わせ（`analysis.cache.truncated`） |
| `KeepAsIs` | `CacheUpdater#canKeepAsIs` |
| `Dropped` / `ContentChanged` | `CacheUpdater#isValidBlock`（今のソースに無い・サイズかハッシュが違う） |
| `BlockDamaged` | F 行の検査値が合わない（`finishOldBlock` の `intact`） |
| `PairDeleted` | `SameUnitFiles#pairedWithDeleted` |
| `HierarchyChanged` | `CacheUpdater#analyzeAllIfHierarchyChanged` |
| `PackageCollision` | `StaleTypes#collidesWithChangedPackage`（パス3 の最初の周回） |
| `BindingFailed` | パス3 の `old.unresolvedTypes`（Q131。名前を照合せず必ず解析し直す） |
| `BySource` / `ByLibrary` | `StaleTypes#touches` の `BY_SOURCE` / `BY_LIBRARY` |

## Q&A

### Q1. なぜ本体を Souther で書き直すのではなく、別に仕様として置くのか

Souther の生成物は Java 25 のクラスファイルに固定されている。一方、本体は `--release 17`、Eclipse プラグインは
`--release 11` でコンパイルするので、本体から参照できない。また、判定の入力（ハッシュ・I 行・型階層）は
ファイル I/O と JDT から来るもので、副作用を持たない Souther の範囲の外にある。

**結論**: 判定の規則だけを真偽値の入力に抽象化して写し、「規則として何が決まっているか」を例で固定する。
本体との一致は例を手で本体の検査（`test/incremental` 等）に対応させて保つ。

### Q2. 判定の順（guard の並び）は本体と同じか

同じにした。順が意味を持つ行を例に入れてある。

- 「無いキャッシュは読めるかを問わない」: 存在しないことが最優先
- 「ソースと jar の両方に触れればソースを理由にする」: `StaleTypes#touches` がソースの側を先に見る
- 「何も変わらない実行では型解決の失敗も再利用」「依存に触れても何も変わらない実行なら再利用」:
  `reanalyzeDependents` が `stale.isEmpty()` なら何もせずに返る
- 自分の変化（`ContentChanged` / `BlockDamaged` / `PairDeleted`）は、実行全体に変化があるかより先に見る（パス2）

本体の実際の処理は集合（`valid`）を周回で減らしていく形だが、1 ブロックが最終的にどの理由で解析し直されるかは、
この順の最初に当たる条件で決まる。

**却下した案**: パス3・4 の不動点（宣言の連鎖）もモデルに入れる。周回ごとの状態を持つ必要があり、
可変状態を持たない Souther では再帰で書くことになる。1 ブロックの判定から外れるので、今回は
`depsTouchSource` に「最後の周回までに触れた」の意味で畳み込んだ。

### Q3. `souther examples` の報告で `adequacy: undetermined` になるのはなぜか

入力をすべて `Bool` にしたためである。Souther の同値分割・決定の網羅は、比較（`value >= 0` など）や
和型の場合分けから「規則が入力をどう分けるか」を読み取る作りで、`Bool` のフィールドをそのまま条件にすると
「比較でも比較の組み合わせでもない条件」として測定の外になる（`rules compose 0 of them`）。
場合分け（partition 18/18・20/20）と分岐（16/16・22/22）は測れている。

`== true` / `== false` の比較に書き換えても、真偽値には境界の線が引けないとして同じく測定の外になり、
報告がかえって長くなったので採らなかった。

**今後の案**: 各条件を和型にする（例: `data Content = SameContent | ContentChanged`）と、`match` の場合分けとして
Souther が読める。ただし `guard` は `Bool` しか取れないので、判定の本体が `match` の入れ子になる。
次に試すならここから。

### Q4. 何が分かったか

- 丸ごと捨てる経路は 6 つ、ブロックの判定は 10 通り（捨てる・書き写すを含む）で、どの出力にも例がある
  （`signature ... verified 8/8`・`10/10`）
- 本体の順（何も変わらない実行では型解決の失敗も依存も見ない）を、コードを読まずに例で確かめられる
- 本体と食い違う箇所は、今回書いた範囲では見つからなかった。ただし例は手で写したものであり、
  本体と自動で突き合わせてはいない（突き合わせは `test/incremental/run.sh` が全件解析との一致で見ている）

### Q5. 再解析の限界をどう表したか（`soundness`）

判定の入力は「判定から見えたもの」だけなので、限界（見えないもの）は表せない。そこで、「本当はどうだったか」
（`Truth`: 全件解析と同じにするには解析し直す必要があったか・判定の入力に現れない事情 `Hazard`）を別の入力にし、
`decideBlock` の結果と比べる behavior `soundness` を足した。結果は 5 つ。

| 結果 | 意味 | 例に固定した限界 |
|---|---|---|
| `Sound` | 判定が本当と合う | — |
| `Overcautious { because }` | 解析し直したが要らなかった（安全側の費用） | 型階層の変化で全件（Q77 の安全網）・型解決に失敗したブロック（Q131）・jar はパッケージの粒度・親の型へのコメント（Q77）と中身の分からないパッケージ（Q138）・検査値だけが壊れたブロック |
| `KnownMiss { cause }` | 見えずに古い事実を再利用した | 同じ刻みで書き換えて戻す（Q57）・中断の引き継ぎ（Q57）・`.java` の無いフォルダ（Q80・Q91） |
| `KnownDivergence { cause }` | 解析し直してもバッチの組み方で全件解析と違いうる | パッケージの宣言とフォルダの食い違い（Q117）・名前の違うファイルの同じ型（Q68）・依存 jar に無いクラス（Q129）・循環した継承（Q129）・createASTs の分かれ方（Q137）・パッケージと同じ名前の型（Q87） |
| `Unsound` | 文書に無い穴 | 「見える入力がすべて静かなのに解析し直す必要があった」の 1 行（形だけ） |

例は 22 行。最初に書いた 19 行では、`souther examples` が網羅の欠けを 4 つ `!` で挙げた（消えたファイル・組の片方の削除・
パッケージの衝突が `soundness` の例に無く、`Dropped` の枝を通る行が無い）。3 行足して、場合分け 32/32・分岐 12/12・
出力 5/5 になった。`Reanalyze` を和型にして `match` で場合分けしたので、決定の網羅も一部（8 規則のうち 4）測れている（Q3 の見込みどおり）。

`Unsound` の例は、`Hazard` が `NoHazard` なのに見逃した場合の形を示すだけで、本当にそういう題材があるとは言っていない。
新しい穴が見つかったら、`Hazard` に 1 つ足し、Q を書き、この表と例に足す。直した穴は `Hazard` から外し、
その例を `Sound` に書き換える（例を直さないとコンパイルが通らないので、文書とモデルの食い違いが目に見える）。

**表せないもの**: 限界がなぜ起きるか（JDT がバッチの中で先に作ったものを使い回す・処理の順で循環を断つ、など）と、
いつ起きるか（刻みの中の書き換え・錠の待ち）は、真偽値と場合分けにしか落ちない。`Hazard` はその「名札」である。

**却下した案**: `KnownDivergence` を「解析し直したときだけ」にする。バッチ依存は全件解析の側の結果もファイルの並びで
変わりうる（100 件の切れ目）ので、判定が何を選んでも違いうる。判定の結果に依らず `KnownDivergence` にした。
