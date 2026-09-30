# AGENTS.md

AI コーディングエージェント（Claude Code、Codex、Copilot 等）向けの入口。

**作業を始める前に [CONTRIBUTING.md](CONTRIBUTING.md) を全部読む。** このリポジトリの構成・動かし方・テスト・
コードとドキュメントの決まり・Git の決まりは、人もエージェントも同じそのファイルにある（以前はここに書いていた）。
内部の作りは [docs/architecture.md](docs/architecture.md)、用語は [docs/glossary.md](docs/glossary.md)、
検査の一覧は [test/README.md](test/README.md)。

## このリポジトリは何か

Java プロジェクト全体のメソッド呼び出し階層を、Eclipse JDT のコンパイラで解析して CSV に書き出すツール。
目的は「改修時の影響調査で呼び出しを漏らさない」こと。迷ったら **呼び出しを静かに落とさない（安全側に倒す）** を優先する。
本体は **解決（`jche.analysis`）→ キャッシュ（`jche.cache`）→ 選択（`jche.graph`）** の 3 層。

## エージェントが特にやりがちな失敗

CONTRIBUTING.md に全部書いてあるが、静かに壊れる（テストが赤くならない・後で気づく）ものだけをここに再掲する。

- `src/jche` を直したのに `bash single-file/generate.sh` で 1 ファイル版を生成し直していない（`test/single-file` が落ちる）。
  `single-file/CallHierarchyExporterSingle.java` は生成物で、手で編集しない
- 書き手（`analysis` / `cache`）を直してキャッシュに入る事実が変わりうるのに `CacheFormat.VERSION` を上げていない
  （迷ったら上げる。上げたら `bash test/cacheversion/run.sh --update`）
- 利用者に見せる文言をソースに直接書いた。英語を `MessagesEn.java`、日本語を `MessagesJa.java` の同じキーに足す。
  出力 CSV のセルは言語に関わらず英語
- 期待値（`test/regression/*/expected*/`）を理由なく書き換えて通した。テストをスキップ・無効化して通した
- lint を JDK 25 より古い javac で回して「通った」と判断した（`dangling-doc-comments` は JDK 22 以降でしか出ない）
- README の日本語側だけ・英語側だけを直した（対訳。必ず両方）
- `java-call-hierarchy-exporter.cmd` を UTF-8 で保存した（MS932・CRLF のまま保存する）
- コメントに `Q番号` や `Issue #番号` だけを書いて結論を書かなかった（結論を 1 文で書き、参照は補助にする）
- 実装を探す順（親クラスの連鎖 → 最も特定的な親インターフェース）を 3 か所のうち 1 か所だけ直した
  （`MethodSelection#search`・`ImplicitCalls#findNoArgMethod`・`ExternalUsageScanner#inheritedFrom`）
- 事実を JDT に一緒に渡すファイルの組み方（バッチ）に依存させた。差分更新が全件解析と静かに食い違う（`test/incremental` で見る）
- `main` に直接 push した。作業ブランチで開発し PR でマージする。コミットメッセージは日本語で何を・なぜ
