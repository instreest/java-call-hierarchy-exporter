# 1 ファイル版（single-file）を本体の完全版にする — 実装時の QA 一覧

`single-file/CallHierarchyExporterSingle.java` を、本体（`src/jche`）の最新のソースコードから作る
**ビルド・実行できる 1 ファイル版（完全版）** に改めた。以前は中心機能だけを手で書き写した「お試し版」で、
本体に追従していなかった（`resolved-by` / `level` 列も無かった。[call-hierarchy-columns-qa.md](call-hierarchy-columns-qa.md) の Q7）。
迷ったこと・結論・却下した案を Q&A の形で残す。

対応の要点:

- 1 ファイル版は**手で書かず、本体から生成する**。生成器は `single-file/generator/MergeSources.java`
  （JDK 17 以上の `java` で直接動く。JDT は要らない）、入口は `bash single-file/generate.sh`
- 本体の各ファイルのトップレベル型を、パッケージ `jche` の外側のクラス `CallHierarchyExporterSingle` の
  入れ子（`static`）にする。本体のコードは 1 行も書き換えない（機械的な読み替えだけ）
- `bash test/single-file/run.sh` が、生成し直し忘れ（生成し直した結果とコミットの一致）・ビルド（本体と同じ lint）・
  起動（`--help`）・本体と同じ出力（回帰テストの whole / entry と、同梱の拡張の読み込み）を検査する。CI（`smoke.yml`）にも入れた
- `single-file/README.md` を「完全版」の説明に書き換えた（本体との違いは、利用者が Java で書く拡張が使えないことだけ）

---

## 作り方

### Q1. 手で書き写した「お試し版」を本体に追従させる案は採らなかったのか

採らなかった。お試し版は 1,200 行で、本体は 172 ファイル・約 4 万行ある。中心機能だけを写す形を保つ限り
「最新のソースコードの 1 ファイル版」にはならず、本体を直すたびに写し忘れる。
利用者の求めは「ビルド・実行できる完全版」なので、**本体そのものを 1 ファイルにする**ことにし、
それを人手でやると次に本体を直したときにまた古くなるので、**生成器**にした。
生成し直し忘れは検査（`test/single-file/run.sh`）が捕まえる。

### Q2. なぜ入れ子のクラスにするのか。トップレベルのまま並べる案は

Java は 1 ファイルに public なトップレベル型を 1 つしか置けない。本体の型はほとんど public なので、
トップレベルのまま並べるには `public` を外す必要があり、書き換えが広がる。
外側のクラスの **static な入れ子**にすれば、`public static final class Foo` のように `static` を 1 語足すだけで済み、
入れ子どうしは同じパッケージにいたときと同じく単純名で見える（package-private・private の壁は無くなる方向にしか変わらないので、
コンパイルは通る）。record・enum・interface は暗黙に static だが、同じ規則で `static` を付けても構文上問題ない。

前提は「単純名が全パッケージで重ならない」こと。今の本体は重なっていないことを生成器が検査し、
重なれば失敗する（黙って片方を隠さない）。将来重なったら、そのときに本体側で名前を変えるか、生成器で読み替えを足す。

### Q3. なぜ既定パッケージではなく `jche` パッケージなのか

最初は以前のお試し版と同じ既定パッケージにした。しかし本体には**入れ子の型の import**
（`import jche.analysis.CallEdgeExtractor.SourceFile;`、`import jche.server.FieldAccesses.Access;`）があり、
1 ファイルではこれを `import CallHierarchyExporterSingle.CallEdgeExtractor.SourceFile;` と書き直したいのに、
既定パッケージの型は import できない（JLS 7.5）。使っている側を `CallEdgeExtractor.SourceFile` に書き換える案は
ソースの機械的でない書き換えになるので却下し、1 ファイル版をパッケージ `jche` に置いて
`import jche.CallHierarchyExporterSingle.CallEdgeExtractor.SourceFile;` と書き直す形にした。
実行の指定が `java -cp … jche.CallHierarchyExporterSingle` になるだけで、`jbang single-file/….java` はそのまま動く
（jbang はパッケージ宣言のあるスクリプトも受け付ける）。

### Q4. 本体のコードを書き換える箇所はあるか

機械的な読み替えを除けば **1 か所**だけ。同梱の拡張の読み込み（`jche.config.Plugins`）は設定に書かれた名前を
`Class.forName` に渡すが、1 ファイル版では `jche.builtin.TypeMappingProvider` が
`jche.CallHierarchyExporterSingle$TypeMappingProvider` になっている。設定を本体と同じ名前で書けるように、
生成器がその 1 行を `Class.forName(bundledClassName(className), …)` に差し替え、外側のクラスに読み替えの
`bundledClassName` を持たせる。差し込み先の行が本体から無くなれば生成器は失敗するので、静かに効かなくなることはない。

本体側の `Plugins` にこの読み替えを入れる案（生成器の差し込みが要らなくなる）は却下した。本体には
入れ子のクラス名という概念が無く、1 ファイル版の都合を本体に持ち込むことになる。

機械的な読み替えは次のとおり。

- `package` 行と `import jche.…` 行を捨てる。それ以外の `import` は全ファイルぶんを集めて先頭に置く。
  ワイルドカードの import は集められない（衝突の検査ができない）ので、あれば失敗させる。今の本体には無い
- コードの完全修飾名 `jche.…型` を単純名にする（`OriginTracker` の引数の `jche.cache.FileAnalysis` と Javadoc の `{@link}`）。
  文字列リテラルのある行は触らない（`"jche.properties"` のような文字列を壊さない。コメントの行は触る）
- JBang の指示行（`//DEPS` / `//JAVA`）は `src/jche/CallHierarchyExporter.java` のものを先頭に 1 組だけ置き、
  本体の中のものは捨てる。JBang は `//DEPS` をファイルのどこにあっても読むので、残すと二重になる。
  `//SOURCES` は 1 ファイルなので要らない。JDT の版は出典と自動で揃う（`test/pom/run.sh` の対象に足していないのはこのため。
  生成し直し忘れは `test/single-file/run.sh` が見る）
- 1 行目の著作権表示は先頭に 1 つだけ置く（`ProjectDetector.java` のように無いファイルもあるので、あれば捨てる）

### Q5. 何が本体と違うのか

- **利用者が Java で書く拡張（`plugin.folders`）は使えない。** 拡張は `import jche.extension.TypeCandidateProvider;` するが、
  1 ファイル版にその型は無い（`jche.CallHierarchyExporterSingle.TypeCandidateProvider`）。拡張を書く人は本体を使う。
  対応表（`plugin.mapping.files`）と契約表（`contracts.files`）は Java を書かないので、そのまま使える
- ツールのプロジェクトフォルダの探し方（`ToolRoot`）は本体と同じで、目印は `src/jche/CallHierarchyExporter.java`。
  リポジトリの外で 1 ファイル版だけを動かすと見つからず、作業ディレクトリを使う旨を警告する（`cache.folder` を書けば関係ない）。
  1 ファイル版のために目印を増やす案は、キャッシュの置き場所が版によって変わる原因になるので採らなかった
- ログのクラス名（拡張の読み込みの知らせなど）に `CallHierarchyExporterSingle$` が付く。CSV には出ない

### Q6. 検査は何を見ているのか

`bash test/single-file/run.sh`（CI の regression ジョブ）。

1. **生成し直し忘れ** … 生成器で作り直した結果と、コミットされている `single-file/CallHierarchyExporterSingle.java` が一致すること。
   本体を直したのに 1 ファイル版が古いまま、をここで止める
2. **ビルド** … 本体の lint と同じ引数（`--release 17 -Xlint:all -Werror -Xdoclint:all,-missing`。JDK 25 の javac）で通ること。
   入れ子にしたことで Javadoc の `{@link}` が解決できなくなっていないかも、doclint がここで見る
3. **起動** … `--help` が 0 で使い方を出し、知らないオプションは 2 で終わること（`jche.Jche` と同じ）
4. **本体と同じ出力** … 回帰テスト（`test/regression/run.sh`）の whole・entry を `JCHE_CMD` で 1 ファイル版に差し替えて通すこと。
   加えて、plugin ケースの 1・2 回目に当たる実行（拡張なし → 同梱の拡張 `jche.builtin.TypeMappingProvider`）で、
   拡張を読み込んだログが出て、出力が expected と一致すること（Q4 の読み替えの検査）。
   plugin ケースの残り（自前の拡張を実行時にコンパイルする）は 1 ファイル版では通らない（Q5）ので回さない

手元では whole・entry のほかに novalues・values も 1 ファイル版で通ることを確かめた（CI では時間を優先して 2 ケース）。

### Q7. 1 ファイル版のソースを直接編集してよいか

だめ。生成し直すと消える。直すのは本体（`src/jche`）で、直したら `bash single-file/generate.sh` を走らせてコミットする
（[AGENTS.md](../AGENTS.md) の「コードの決まり」）。ファイル冒頭にもその旨を書いてある。
