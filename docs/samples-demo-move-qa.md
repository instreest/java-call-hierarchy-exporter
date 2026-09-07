# samples/demo を test/demo へ移動した際の QA 一覧

Issue #51「samples/demo は test フォルダ内に移動したい」の対応で、迷った点と決めたことをまとめる。

## Q1. 移動先のフォルダ名は何にするか
`test/demo` にした。`test/regression/`（回帰テストの実行スクリプトと期待出力）と並ぶ位置に、
解析対象のサンプルプロジェクトを置く形になる。`test/regression/demo` も検討したが、
demo は regression 専用ではなく手元の動作確認にも使うため、`test/` 直下にした。

## Q2. 参照している箇所をどう洗い出したか
`grep -rn samples` でリポジトリ全体を確認し、次を更新した。

- `test/regression/*/config*.properties` の `project.root`（`../../../samples/demo` → `../../demo`）
- `test/regression/run.sh` / `run.cmd` の説明コメント
- `README.md` のテストの節
- `.github/workflows/smoke.yml` のステップ名
- `test/demo/README.md`（見出しと `cd` の例）、`test/demo/ext-src/teamb/NightJob.java` のコメント

## Q3. `project.root` の相対パスの起点は
`Config.resolveFromConfigDir()` により「設定ファイルが置かれているディレクトリ」が起点。
設定ファイルは `test/regression/<case>/` にあるので、`../../demo` が `test/demo` を指す。
`jarchange` の `config-before` / `config-after` も同じ場所なので同じ値でよい。

## Q4. `.gitignore` の変更は必要か
不要。無視しているのは `/test/regression/*/output/` などの実行結果であり、
移動したのは解析対象のソース側なので影響しない。`samples/` を指す記述も無かった。

## Q5. 過去の QA ドキュメント内の `samples/demo` の記述は書き換えるか
書き換えていない。`docs/*-qa.md` は当時の作業記録であり、実行時に参照されるパスでもないため、
当時の記述のまま残すほうが履歴として正しいと判断した。

## Q6. 回帰テストで確認できたか
この作業環境では JDK 25 を取得できず（`jbang` が `No suitable JDK was found for requested version: 25`
で停止、手元は JDK 21）、`bash test/regression/run.sh` を通して実行することはできなかった。
パスの妥当性は Q3 の解決規則と、`grep` で `samples` の参照が残っていないことで確認している。
実際の実行確認は JDK 25 が入る CI（`.github/workflows/smoke.yml`）に委ねる。
