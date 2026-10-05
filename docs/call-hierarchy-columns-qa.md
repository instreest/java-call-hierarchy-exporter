# `resolved-by` / `depth` 列 — Q&A

`call-hierarchy.csv` の `call-hierarchy` 列（旧 `root` 列を含む）の左に、解決方法（`resolved-by`）と起点からの階層の深さ（`depth`）の
2 列を足した判断を残す。
関連: [note-tags-qa.md](note-tags-qa.md)（注記のタグ）、[callee-label-qa.md](callee-label-qa.md)（`callee` 列の表記）、
[prompt-B-detailed.md](prompt-B-detailed.md) 4.1（列の仕様）。

## 結論

- ヘッダーは `caller,callee,resolved-by,depth,call-hierarchy`（当初は `…,depth,root,call-hierarchy`。Q9 で `root` の見出しを `call-hierarchy` にした）
- `resolved-by` は `接頭辞 + 解決の段のラベル`。接頭辞が確度、後半が手法
- `depth` は起点を `0` とした深さ。**`call-hierarchy` 列に並ぶノードのうち、先頭の起点を除いた数と必ず一致する**

| 接頭辞 | 意味 | 例 |
|---|---|---|
| `RESOLVED:` | 呼び出し先を1件に確定した | `RESOLVED:STATIC_BOUND:PRIVATE`、`RESOLVED:DATAFLOW_FIELD` |
| `UNEXPANDED:` | 1件に絞れず候補のまま（その先へは降りない） | `UNEXPANDED:CHA`、`UNEXPANDED:GENERATED_IMPL:Doma` |
| `UNRESOLVED:` | 型解決に失敗した行 | `UNRESOLVED:BINDING_FAILED` |
| `EXTERNAL_USAGE:` | jar からの被参照の行 | `EXTERNAL_USAGE:EXACT` |

- 後半は `Resolution` のラベルそのもの。例外は `UNEXPANDED:LAMBDA` の 1 つだけ
- 列と完全に重複する裸の `[RESOLVED:*]` は注記から落とす。注記に残る `[RESOLVED:*]` は
  繋いだ規則を持つ `[RESOLVED:CALLBACK] 規則: …` だけ
- 新しい固定列は必ず `call-hierarchy` の左に入れる。`call-hierarchy` は可変長なので、後ろに足すと階層が途中で切れる

### Q1. なぜ注記だけでは足りなかったのか

**フィルタに使えないから**。注記は可変長の `call-hierarchy` 列の最後の要素として出るので、
Excel では列が行ごとにずれ、「解決できた行だけ」「CHA の行だけ」を選べない。
`grep` なら拾えるが、この CSV の主な読み口は Excel である。

もう 1 つ、**確定した呼び出しには注記が出ない**という問題があった。
`STATIC_BOUND:PRIVATE` も `NO_OVERRIDE` も注記なしなので、
「どう決まったか」を全行について見比べることが、そもそもできなかった。

### Q2. なぜ `call-hierarchy`（旧 `root`）の左なのか。`call-hierarchy` の後ろではいけないのか

`call-hierarchy` は 1 ノード 1 列で伸びる可変長の列で、行ごとに列数が違う。
その後ろに固定列を置くと、読み手は「どこで階層が終わって固定列が始まるか」を
行ごとに数えることになり、Excel の列単位のフィルタも効かない。
`caller` / `callee` / `root` はどれも行の**識別**に使う列なので、
解決方法と深さもその並びに入れるのが自然だった。

`depth` を足したことで、階層列の終わりが `5 + depth` 列目と計算できるようになった。
注記の有無で 1 列ずれていたのが、列数だけで判定できる。

### Q2-2. 2 列の並びを `resolved-by` → `depth` にしたのはなぜか

列のまとまりを意味と合わせるため。最初は `depth` → `resolved-by` の順で入れたが、
次の理由で入れ替えた（列自体を入れたのと同じ日で、利用者のフィルタが列位置に依存し始める前）。

- `resolved-by` は **`caller` → `callee` という 1 本の辺の性質**（なぜこの callee がここに出ているか）
  なので、`callee` の直後が自然
- `depth` / `root` / `call-hierarchy` は 3 つとも **「この行が木のどこにあるか」** の列。
  並べておくと、左から「誰が・誰を・どう特定したか ｜ どの経路の何段目か・その経路」と読める
- 「`depth` = `call-hierarchy` 列のノード数」という不変条件も、隣り合っているほうが目で確かめやすい

`depth` は 1〜2 文字の細い列なので左端寄りだと常に視界に入る、という反対意見もあったが、
上の 3 点を採った。

### Q3. なぜ解決方法を `Resolution.label()` そのままにしなかったのか

**ラベルだけでは誤読させる行があるから**。ラムダ式・メソッド参照が実装している
関数型インターフェースの呼び出しは、ソース上の実装が 1 件ならラベルが `SINGLE_IMPL` になるが、
実際に走るのは渡されたラムダかもしれず、どれが実行されるかは特定できていない。
ラベルをそのまま出すと「1 件に確定した」と読めてしまうので、この 1 ケースだけ
`UNEXPANDED:LAMBDA` と言い換える（注記 `[UNEXPANDED:LAMBDA] …` と同じ判定）。

逆に、候補が複数のときの後半はラベルのままにした（`UNEXPANDED:CHA` /
`UNEXPANDED:LOCAL_NEW_MULTI` / `UNEXPANDED:CALL_RULE` …）。
注記は候補が複数ならどれも `[UNEXPANDED:CHA]` と書くが、列では
「何を根拠に候補を集めたか」が分かるほうが、次に何を与えれば絞れるかの判断に使える。

列と注記は同じ判定順（`StreamingTreeWalker#resolvedBy` と `#noteFor`）から作る。
片方だけを直すと、同じ行の列と注記が食い違う。

### Q4. 接頭辞を付けず `CHA` や `DATAFLOW_FIELD` だけにしなかったのはなぜか

確度（1 件に確定したのか、候補のままなのか）が、影響調査で最初に知りたいことだから。
接頭辞を付けておけば「`UNEXPANDED:` で始まる行」＝**辿り切れなかった呼び出し**が
1 回のフィルタで出る。ラベルを覚えていなくても選べる。

確度と手法を 2 列に分ける案（`resolution-kind` + `resolution`）も出たが、
`root` までのセル数が増えるほど CSV は読みにくくなるので、1 列にまとめた。

### Q5. 型解決に失敗した行と被参照の行の `depth` を空欄にしなかったのはなぜか

「`depth` = `call-hierarchy` 列のノード数」をどの種類の行でも保つため。
これらの行も階層列に 1 つだけ要素（式／呼ばれた側）を置くので `depth` は `1` になる。
空欄にすると、列数から階層の終わりを計算する読み方がこの行だけ通らなくなり、
Excel の数値フィルタにも空欄が混ざる。

これらの行は `root` 列（`(unresolved)` / jar 名）と `resolved-by` の接頭辞で
呼び出し階層の行と見分けられるので、`depth` に別の意味を持たせる必要は無かった。

### Q6. 注記から `[RESOLVED:*]` を落として困らないか

落としたのは**裸の `[RESOLVED:{ラベル}]`**、つまり列と情報が完全に重複するものだけ。
`grep '\[RESOLVED'` を使っていた読み手は `grep 'RESOLVED:'`（列側）に書き換えれば、
今までより多くの行（注記の出ていなかった確定行）が拾える。

注記に残したものは、どれも列に無い情報を持っている。

| 残した注記 | 列に無い情報 |
|---|---|
| `[UNEXPANDED:CHA] N candidates: {reason}` | 候補の件数、レシーバの由来（次に調べる場所） |
| `[UNEXPANDED:GENERATED] …: FQN is…` | 生成される実装の FQN |
| `[RESOLVED:CALLBACK] rule: …` | 繋いだ規則の本文 |
| `[UNREACHABLE] …condition '…' does not hold (…)` | 条件式と、この経路で分かっている値 |
| `[UNEXPANDED:CYCLE]` / `[UNEXPANDED:DEPTH]` / `[EXTERNAL]` | 打ち切りの理由（解決方法とは別の軸なので列には入れない） |

### Q7. 再実装用のプロンプトとお試し版（single-file）はどうしたか

[prompt-B-detailed.md](prompt-B-detailed.md)（本体の仕様）と [prompt-A-minimal.md](prompt-A-minimal.md)
（目的から設計させる版）は 2 列を含む形に更新した。prompt-A には列の仕様だけでなく、
**なぜその列が要るのか**（行ごとに「1 件に確定したのか／根拠は何か／何段目か」を、注記の文面を
読まずに固定列だけで判別できるようにする）を目的の節に書いた。設計を任せる版なので、
形だけ写されて意図が失われると、また注記に埋め込む作りに戻るため。

prompt-B の 3.3 にあるケース別の期待行は、`resolved-by` / `depth` の 2 列を省いた表記のままにし、
その読み方を断り書きにした。1 件に確定した行だけは、どの段で決まったかが期待値そのものなので
行末に `[RESOLVED:{ラベル}]` を残している（実際の出力では列に入る）。

`single-file/`（お試し版）は追従させていない。あの版の `resolve()` は候補の配列だけを返し、
段のラベルを持たない作りで、列を足すには解決の戻り値を変える改修が要る。
「中心機能だけの 1 ファイル」という位置づけを優先し、
[single-file/README.md](../single-file/README.md) に「この列は入っていない」と明記した。

> その後、`single-file/` は本体の全ソースから生成する完全版になり、この 2 列も本体と同じに出る
> （[single-file-qa.md](single-file-qa.md)）。

### Q8. 列名を `level` から `depth` に変えたのはなぜか。ほかの名前は考えたか

列の意味は「この行が起点（`root`）から何段目にいるか」で、木の根からの距離である。
`level` は「重要度」「ログの水準」「段階」のようにも読め、深さだと一目で分からなかった。
木の用語では根からの距離を depth と呼ぶのが一般的で、書き手のコードもすでに `depth` という名前で
この値を持っていた（`CallHierarchyCsvWriter#writeRow` の引数）ので、列名を中身に合わせた。

- 却下: `distance`（何と何の距離かが列名だけでは分からない）、`hops`（辺の数としては正しいが一般的でない）、
  `root-depth`（`root` 列と並ぶので前置きは要らない）
- 変えたのは列名だけで、値（起点が `0`、その呼び出し先が `1`、`call-hierarchy` 列のノード数と一致）と列の位置は同じ。
  列名で `level` を参照していた利用者の Excel のフィルタや外部のスクリプトは直す必要がある

### Q9. `root` 列の見出しを `call-hierarchy` にしたのはなぜか

**起点は階層の先頭のノードで、別の概念の列ではないから。** 以前は起点（`root`）と、起点の次から現ノードまでの
`call-hierarchy` を別の見出しの列にしていたが、Excel で読むと「入口」と「そこからの経路」が離れた 2 つの列に見え、
経路は起点から始まるという実際の構造が見出しに出ていなかった。

- 変えたのは**ヘッダーだけ**。データ行の列（`…,depth,起点,ノード1,ノード2,…`）と列の位置・値は同じで、
  見出し `root` の列が `call-hierarchy` になり、ヘッダー末尾の旧 `call-hierarchy` は無くなった
  （2 つ並べると同じ見出しが重なる。可変長の列は先頭の 1 列にだけ見出しが付き、ヘッダーとデータ行の列数が
  一致しないのは前からの仕様）。ヘッダーは `caller,callee,resolved-by,depth,call-hierarchy` になった
- 不変条件は「`depth` = `call-hierarchy` 列のノード数」から**「先頭の起点を除いた数」**に言い換えた。
  値は変わらない。階層列の終わりが `5 + depth` 列目という計算も同じ
- 型解決に失敗した行（`(unresolved)`）と被参照の行（jar 名）で起点の列に別の値を入れるのも同じ。
  その列は `call-hierarchy` の先頭の列として読む
- 見出し名の `root` で列を指していた利用者の Excel のフィルタ・外部スクリプトは `call-hierarchy` に直す。
  列の位置は変わらないので、列番号で読んでいる側は影響を受けない
- 却下: 見出しを `root` と `call-hierarchy` のままにして README の説明だけ直す案
  （見出しが読み手の最初の手がかりなので、そこで食い違うと説明を読まない人に伝わらない）
