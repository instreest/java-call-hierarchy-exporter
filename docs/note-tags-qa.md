# 注記のタグと取捨選択 — Q&A

`call-hierarchy.csv` の注記と `methods.csv` の `unresolvedCause` / `absentCause` に
大文字のタグを付け、`NO_IMPL` の注記を階層側に戻した判断を残す。
関連: [callee-label-qa.md](callee-label-qa.md)（`NO_IMPL` を出さないと決めた当時の判断）、
[doma-generated-impl-qa.md](doma-generated-impl-qa.md)（`GENERATED_IMPL` を `NO_IMPL` と言い分ける）、
[branch-pruning-qa.md](branch-pruning-qa.md)（`[UNREACHABLE]` を出す条件）。

> **その後の変更**: 解決方法は [call-hierarchy-columns-qa.md](call-hierarchy-columns-qa.md) で
> `resolved-by` 列に移した。注記に残る `[RESOLVED:*]` は、繋いだ契約という列に無い情報を持つ
> `[RESOLVED:CALLBACK] 契約: …` だけになっている。タグの考え方（下の Q1・Q2・Q3）は今も有効で、
> 列の値もそのタグをそのまま使っている。

## 結論

- 注記は `[タグ] 日本語の説明` の形にする。タグで grep でき、説明は読めば分かる
- タグは 5 種類

| タグ | 意味 |
|---|---|
| `[UNEXPANDED:*]` | ここから先へ降りなかった（`CYCLE` / `DEPTH` / `CHA` / `REFLECTION` / `LAMBDA` / `GENERATED` / `NO_IMPL`） |
| `[EXTERNAL]` | 呼び出し先が自プロジェクトの外 |
| `[UNREACHABLE]` | 条件分岐の静的解析で、この経路では呼ばれないと分かった |
| `[RESOLVED:*]` | 具象クラスをどう特定したか |
| `[NOT_REACHED]` / `[EXCLUDED]` | `methods.csv` の `absentCause` だけで使う（そこへ至る呼び出しが出ていない／`exclude.packages` で除外） |

- 1つの注記は最大2パーツ（打ち切りの理由 / 絞り込みの結果）で、両方あれば ` / ` で繋ぐ
- 同じタグを `call-hierarchy.csv` と `methods.csv` の両方で使う
- `[UNEXPANDED:NO_IMPL]` を階層側に戻した（[callee-label-qa.md](callee-label-qa.md) の判断を取り消し）

### Q1. なぜ日本語の文言のままではいけなかったのか

種類ごとに拾えなかった。`grep '（未展開）'` で「辿り切れなかった箇所」は拾えるが、
「循環だけ」「深さ制限だけ」を出す・除くには文言を丸ごと書くしかなく、
文言を少しでも変えると利用者の grep が黙って空振りする。
タグなら `grep '\[UNEXPANDED'` で一括、`grep '\[UNEXPANDED:CHA\]'` で種類ごと、
`grep -v '\[RESOLVED'` で情報量の薄いものを落とす、が全部書ける。

説明文を消してタグだけにはしなかった。`[UNEXPANDED:CHA] 3 candidates: field` の
「フィールド変数」は**次に調べる場所**そのもので、これが注記の本体だからである。
タグは検索のための入口で、読むのは日本語の方。

### Q2. `UNEXPANDED` という語を選んだのはなぜか

`NOT_EXPANDED` は `grep NOT_EXPANDED` と `grep -v` の両方に `NOT` が現れて読みにくい。
`TRUNCATED` は「行が途中で切れた」と誤読される。
「展開しなかった」がそのまま伝わる語として `UNEXPANDED` を採った。

### Q3. `[EXTERNAL]` を `[UNEXPANDED:EXTERNAL]` にしなかったのはなぜか

外部ライブラリへの呼び出しは、打ち切りではあるが読み手の対処が違うから。
`[UNEXPANDED:*]` は「このリポジトリの中なのに辿れなかった」＝調べれば何か出てくる箇所で、
`[EXTERNAL]` は「そもそもこのリポジトリの外」＝基本は無視してよい箇所である。
影響調査では前者だけを数えたいことが多く、独立したタグにしておくと
`grep '\[UNEXPANDED'` がそのまま「自分たちのコードで辿れなかった件数」になる。

### Q4. `NO_IMPL` の注記を戻したのはなぜか

[callee-label-qa.md](callee-label-qa.md) では「インターフェースが多いプロジェクトでは
何百行にも付き、他の注記が埋もれる」ため出さないと決めていた。
埋もれるのは「注記を全部同じ土俵で目で拾う」ことが前提で、タグが付いた今は
`grep -v '\[UNEXPANDED:NO_IMPL\]'` で落とせる。前提が変わったので判断も変えた。

戻す側の理由もある。`NO_IMPL` は「ソースを読めた上で実装が見つからない」状態で、
`source.folders` の設定漏れかデッドコードを疑う手掛かりになる。
件数のわりに調べる価値が高い側を、件数を理由に消していた。

### Q5. `NO_IMPL` と `[EXTERNAL]` は同じ「実装が辿れない」ではないのか

違う。**どこを探したか**が違う。

| | `[UNEXPANDED:NO_IMPL]` | `[EXTERNAL]` |
|---|---|---|
| 呼び先の宣言 | 自分のソースの中にある | 自分のソースの外（jar 等） |
| 実装の中身 | どこにも見つからない | jar の中に存在する（読めないだけ） |
| 異常か | 調査対象。設定漏れかデッドコードの疑い | 正常。ライブラリを使えば必ず出る |

一言でいえば `[EXTERNAL]` は「読めないだけで、ある」、`NO_IMPL` は「探したが、無い」。
前者は当たり前に大量に出るノイズ、後者は少数で調査価値がある、という非対称がある。
[doma-generated-impl-qa.md](doma-generated-impl-qa.md) の `GENERATED_IMPL` は
`NO_IMPL` のうち「無い理由が分かっている」ものを切り出したもので、三者は別物として残す。

### Q6. `ソースなし` と `import推定` を同じ `[EXTERNAL]` にまとめたのはなぜか

読み手の対処が同じだから。どちらも「ここから先はこのリポジトリには無いので、
無視するかそのライブラリを別途調べる」で、タグを分けても選択の役に立たない。

ただし確度は違う。`ソースが無いため辿れない` は JDT の型解決に成功していて呼び先が
実在することは確実だが、`import から型名を推定（未検証）` は型解決に**失敗**していて、
`import` 文からの推測にすぎない（メソッドの実在もオーバーロードも未確認で、外れている
可能性がある）。そこで**タグは 1 つ・説明文で言い分ける**形にした。
`grep '\[EXTERNAL\]'` で一括、`grep '未検証'` で確度の低い方だけ、が両立する。
完全に同じ文言にしてしまうと、クラスパスを整備すれば消せる誤りを見つける手段が無くなる。

### Q7. ラムダ／メソッド参照は実装が分かるのだから、階層に出せないのか

現状の作りでは出せない。理由は 2 つある。

1つ目は情報が残っていないこと。キャッシュの M 行（`FunctionalImplFact`）は出現行と囲みメソッドを
持っているが、グラフに読み込む時点で「その関数型インターフェースのメソッドキーの集合」
（`CallGraph.functionalImpls`）に潰しており、どこにあるラムダかは残らない。

2つ目が本質で、**その呼び出し箇所で実行されるのがどのラムダかは決まらない**。
`list.forEach(f)` の `f` に何が入っているかは、ラムダが値として呼び出し箇所まで
流れてくる経路を追わないと分からないが、`Origin`（`new`・引数・戻り値・フィールド・リテラル等）に
ラムダ／メソッド参照の種別が無く、データフローの追跡対象になっていない。
さらにラムダ本体の呼び出しは囲みメソッドに計上していて（`FactVisitor.recordFunctionalImpl`）、
ラムダ自体がグラフのノードになっていない。

出せるようにするには (a) `Origin` にラムダ種別を足す、(b) ラムダを合成メソッドのノードにする、
(c) データフローで引数として追跡する、の 3 点が要る。注記の整理とは規模が違うので分けた。

**その後**: #127 でこの 3 点を実装し、ラムダ本体まで辿れるようになった
（[lambda-expansion-qa.md](lambda-expansion-qa.md)）。フィールド保持とコレクション経由も
追えるようにしたが、`forEach` のように jar の中から呼ばれる形は引き続き辿れない。
辿れない形では下の注記がそのまま残る。

そのため注記は「実装がある」ではなく
`[UNEXPANDED:LAMBDA] implemented by a lambda/method reference (which one runs is undetermined)` と、
特定できていないことを明示する文言にしてある。

### Q8. `methods.csv` の列まで同じタグに揃えたのはなぜか

片方で見つけたものをもう片方で追えるようにするため。
`methods.csv` で穴のあるメソッドを絞ってから `call-hierarchy.csv` で経路を追う、が
この 2 つの CSV の想定した使い方で、同じ「絞れなかった」を
`field` と `[UNEXPANDED:CHA] 3 candidates: field` のように別の名前で書くと、
一覧で見つけた呼び出しを階層側で grep できない。
`absentCause` の `[EXCLUDED]` / `[NOT_REACHED]` だけは階層側に対応する注記が無い
（除外されたメソッドと、そこへ至る呼び出しが出ていないメソッドは行自体が出ない）ので、
`methods.csv` 専用のタグとして足した。

### Q9. 注記を設定で取捨選択できるようにしないのか

今回はしていない。まず「読み手が grep で選べる」ところまでを揃えた。
出力の量が問題になるようなら `notes=off|minimal|all` のような一括レベル指定
（`minimal` は打ち切り系＋`[UNEXPANDED:CHA]`＋`[EXTERNAL]` の未検証のみ）を足すのが次の手になる。
設定を増やす前に、タグで落とせるかどうかを実プロジェクトで確かめる方が先と判断した。
現時点で設定から消せるのは、注記の種類ではなく機能そのもの
（`branch.pruning.enabled` / `dataflow.enabled` / `spring.di.enabled` / `max.depth` /
`external.library.folders`）である。
