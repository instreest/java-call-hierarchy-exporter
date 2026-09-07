# 著作権表示・ライセンス表記の簡略化 — 実装時の QA 一覧

[Issue #47](https://github.com/instreest/java-call-hierarchy-exporter/issues/47)
「ソースコード毎の著作権表示・ライセンス表記を簡略化する」への対応で、調べたこと・迷ったことと、
その結論を Q&A の形で残す。

対応の要点:

- 各 .java ファイルの 15 行のライセンスヘッダを、SPDX 短識別子を使った **1 行** に置き換える
  ```java
  // Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
  ```
- 著作権者を `the java-call-hierarchy-exporter authors` から `Inoue Kazuhiro` に変える
- ライセンス全文は `LICENSE` に一本化し、著作権者は README の「ライセンス」節に書く
- `samples/` 以下（解析対象のサンプルコード）には付けない。対象は `src/` と `test/` の 58 ファイル

---

## 調査

### Q1. 他のプロジェクトはどう書いているか

代表的な書き方を 4 通りに整理した。

| 案 | 例 | 行数 | 備考 |
|---|---|---|---|
| A: Apache 全文ヘッダ | Apache 系プロジェクト全般、Spring、Guava | 13〜15 | Apache License の Appendix が推奨する形。現状これ |
| B: SPDX 2 行 | Linux カーネル、Kubernetes、curl、多数の Rust/Go プロジェクト | 2 | `// Copyright ...` と `// SPDX-License-Identifier: ...` を別行に置く |
| C: SPDX 1 行 | 小〜中規模の OSS でよくある | 1 | B を 1 行にまとめたもの |
| D: ヘッダなし | SQLite、多くの小規模ライブラリ | 0 | LICENSE ファイルだけに任せる |

SPDX 短識別子（`SPDX-License-Identifier:`）は SPDX 仕様が定める機械可読なライセンス表記で、
GitHub / FOSSology / ScanCode / reuse.software など主要なライセンススキャナが解釈する。
Apache 全文ヘッダより情報量が減るわけではなく、「どのライセンスか」は同じ精度で伝わる。

### Q2. どの案を採ったか

Issue の「1 行の案もあると助かります」に沿って **C（1 行）** を採った。

```java
// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
```

B（2 行）も同じくらい一般的で、機械可読性も同じ。1 行案を採ったのは、
このリポジトリのファイルが小さいものを含み、`package` 宣言までの距離が短いほうが読みやすいため。
なお `SPDX-License-Identifier:` はスキャナが行単位ではなくタグ単位で拾うので、
著作権表示と同じ行に置いても認識される。

### Q3. ヘッダを消してしまう（D 案）のは駄目か

法的には LICENSE ファイルだけでも Apache-2.0 の配布条件は満たせる。ただし
Apache-2.0 の 4 条は「派生物の各ファイルに含まれる著作権表示を保持すること」を求めるので、
ファイル単位で切り出されて再配布されたときに出所が追えなくなる。1 行なら実質コストがないため残した。

---

## 実装

### Q4. 置換はどう行ったか

`/*` で始まり `Copyright 2026 the java-call-hierarchy-exporter authors` を 2 行目に持つ
ブロックコメントだけを、正規表現で先頭から 1 個だけ取り除いて 1 行に差し替えた。
「Apache License」という文字列を含む行を消す、といった行単位の置換にはしていない。
本文中にライセンスの話が出てくるコメントを巻き込む恐れがあるため。

### Q5. `samples/` 以下にも付けるか

付けない。`samples/demo/` は解析器の入力データ（テスト用の疑似アプリ）であって、
ツールの実装ではない。ヘッダを足すと、サンプルの行数・行位置を期待値に持つ回帰テストに
影響が出るうえ、読み手にとってもノイズになる。リポジトリ全体のライセンスは LICENSE と
README で及ぶ。この判断は README のライセンス節にも書いた。

### Q6. 著作権年は更新するか

しない。`2026` のままにした。年を毎年書き換える運用は、更新漏れがそのまま
「その年に更新がなかった」ように見えてしまい、実益に対して手間が大きい。
ベルヌ条約下では著作権表示自体が権利の発生要件ではないので、年の正確さに依存する部分はない。
必要になったら `2026-20xx` の範囲表記に切り替えるのが簡単。

### Q7. 名前の表記は `inoue kazuhiro` か `Inoue Kazuhiro` か

Issue の本文は「inoue Kazuhiro」「inoue kazuhiro」と揺れていた。著作権表示は人名なので、
一般的な英語表記の慣習に合わせて `Inoue Kazuhiro`（姓・名とも先頭大文字）に統一した。
LICENSE / NOTICE / README / 各ファイルのヘッダで同じ綴りを使っている。

### Q7-2. GitHub のアカウント名も併記するか

併記する。ヘッダは `Inoue Kazuhiro (instreest)` の形にした。著作権表示の書式に決まりはなく、
権利者が特定できればよいので、本名にアカウント名を括弧で添える書き方は一般的。
`@instreest` と `@` を付ける案もあったが、ファイルヘッダでは `@` がメールアドレスや他 SNS の
ハンドルに見えることがあるため、括弧のみにした。README のライセンス節では
`[@instreest](https://github.com/instreest)` とリンク付きで書き、照合先をはっきりさせている。

### Q8. LICENSE の末尾（Appendix）の `[yyyy] [name of copyright owner]` は埋めるか

埋めない。あそこは「このライセンスを使う人向けのテンプレート」であって、
Apache License 2.0 の本文の一部。書き換えるとライセンス全文が原文と一致しなくなり、
スキャナが「改変された Apache-2.0」と判定することがある。
実際の著作権者は `NOTICE` に書いた。

### Q9. `NOTICE` ファイルは置くか

置かない。Apache-2.0 が NOTICE を必須にするのは、既に NOTICE を持つ成果物を再配布する場合だけで、
このリポジトリに義務はない。ファイル毎のヘッダを 1 行に削ったぶん著作権者をまとめて書く場所は要るが、
それは README の「ライセンス」節で足りる。ライセンス関係のファイルを増やすほうが、
このくらいの規模のツールには大げさ。

---

## 確認

### Q10. 回帰テストへの影響はあるか

ない。変更したのは `src/` と `test/` の Java ファイルの先頭コメントだけで、
解析対象は `samples/` 以下（今回さわっていない）。出力 CSV に載る行番号は
解析対象ファイルの行番号なので動かない。
