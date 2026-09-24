// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cache;

/**
 * キャッシュファイル（{@code analysis-cache.tsv}）の形式（タブ区切り。外部ライブラリ不要でデバッグしやすい）。
 *
 * <h2>キャッシュは 1 ファイル</h2>
 * 呼び出し階層の構造（宣言・呼び出し・型階層）と、データフローの値（値グラフ・戻り値の出所・
 * フィールドへの代入・条件分岐）を、ソースファイル 1 つにつき 1 ブロックとして同じファイルに持つ。
 * 以前は 2 ファイル（{@code analysis-cache.tsv} / {@code dataflow-cache.tsv}）に分け、世代の印と
 * ブロックの突き合わせで対を保っていたが、値も呼び出し階層の出力に効く（具象クラスの解決と
 * 条件分岐の打ち切り）ので分けても片方だけでは足りず、対を保つための仕組み（世代・ブロックの突き合わせ・
 * 呼び出し箇所の値の結びつけ）だけが残っていた。1 ファイルにしてそれらを無くした
 * （{@code docs/cache-unification-qa.md}）。旧形式の {@code dataflow-cache.tsv} が残っていれば
 * {@code jche.analysis.CacheUpdater} が消す。
 *
 * <h2>原則: キャッシュは「ASTから分かった事実」だけを持ち、判断は読む側でする</h2>
 * <pre>
 *   事実 … ASTとバインディングから機械的に読み取れ、設定・出力形式・解決アルゴリズムに
 *          依存しない情報（宣言・修飾子・呼び出し箇所・代入・出所 など）
 *   判断 … フィルタ・要約・推定・しきい値・文言・「使うか使わないか」の決定
 * </pre>
 * 判断をキャッシュに焼き込むと、出力や解決の方針を変えるたびに全件再解析になる。
 * 事実だけを持てば、読み手（jche.graph / jche.report）を変えるだけで済む。
 * 見分ける問いは「この値を変えたくなるのは、出力や解決の方針を変えるときか、
 * Javaの意味論が変わるときか」。前者なら判断であり、読み手に置く。
 *
 * <h2>ファイルの並び</h2>
 * <pre>
 *   ヘッダ行   {@link #VERSION} source=… enc=… jdk=… jdt=…（{@link #headerFor}）
 *   L 行       依存 jar（クラスパス順）
 *   T 行       ソース一覧の指紋
 *   ブロック   ソースファイル 1 つにつき 1 つ（F 行から次の F 行・Z 行の手前まで）
 *   Z 行       ブロック数（最終行）
 * </pre>
 * 行は必ず {@code '\n'} で終える（OS の改行に合わせない。どの OS で書いても同じバイト列になる）。
 * 読むときは CRLF も受け付ける。
 *
 * <h2>行の種別と列</h2>
 * 各行の列の並びは、その行を表す record の {@code toRow()} / {@code fromRow()} が定義する。
 * ブロックの中の行は<b>この順に並ぶ</b>（読み手がこの並びに依存している。理由は各行の説明）。
 * <pre>
 *   T  ソース一覧の指紋                                       解析対象のソースファイル一覧（パス・サイズ・
 *                                                          内容ハッシュ）のハッシュ。L 行の直後に1行。
 *                                                          中断した実行からの引き継ぎ（{@link #sourcesRow}）でだけ使う
 *   L  jarのパス  指紋  パッケージ(カンマ区切り)            {@link LibraryFact}。ヘッダ行の直後に
 *                                                          クラスパス順で並ぶ（並び自体も意味を持つ。
 *                                                          JDT は同名クラスを先勝ちで解決するため）。
 *                                                          指紋は中に入っているクラスの一覧
 *                                                          （{@link jche.analysis.LibraryDiff}）。読めなければ空
 *   F  相対パス  サイズ  エラー数  内容ハッシュ  構文エラー数  未解決数  crc
 *                                                          ブロックの先頭。エラー数は JDT が報告したエラーの件数
 *                                                          （解決が不完全な印）。内容ハッシュは
 *                                                          {@link jche.util.FileHash}（読めなければ空）。
 *                                                          未解決数は使える候補の無い U 行の数
 *                                                          （{@link UnresolvedCallFact#hasUsableCandidate}）。
 *                                                          crc はブロックの残りの行の検査値（下の「ブロックの検査値」）
 *   I  依存する型（カンマ区切り）  型の形の指紋  解決できなかった名前（カンマ区切り）
 *                                                          必ず F 行の直後。差分更新（{@link jche.analysis.CacheUpdater}）が
 *                                                          使う 3 列。
 *                                                          依存する型は、このファイルのバインディング解決が参照した型の
 *                                                          FQN、ソースに書かれた型の名前、ラムダの目標の型とその親、
 *                                                          解決できなかった呼び出し・フィールド参照の受け手と実引数の型
 *                                                          （型変数は上限の消去）、呼び出し先の throws の型、拡張 for 文・
 *                                                          switch のセレクタ・throw の式の型、アノテーションの型、
 *                                                          switch の case に書いた型とその親、import 文の型（オンデマンド
 *                                                          import は "pkg.*"）。型解決に失敗したブロックでは、これらの型の
 *                                                          推移的な親も載せる。自分が宣言する型は含まない。これらの型を
 *                                                          宣言するファイルが変わっていたら再解析する。
 *                                                          型の形の指紋は、宣言する型の、継承したものを含むメンバーの
 *                                                          署名（可変長引数か・throws の型と検査例外かを含む）と親型の
 *                                                          ハッシュの頭 16 文字（jche.analysis.TypeShape）。私的メンバーは、
 *                                                          宣言した型の親のメンバーと名前が当たるものだけ入れる。
 *                                                          解析し直した結果これが変わったら、この型を使う側も解析し直す。
 *                                                          解決できなかった名前は、型解決に失敗したブロック（エラーか
 *                                                          BINDING_FAILED の U 行がある）でだけ書く。エラーの引数に
 *                                                          現れた点区切りの識別子で、拾えなければ {@link #ANY_NAME}。
 *                                                          変わった型（新しい型を含む）に当たるブロックを解析し直す
 *   S  番号  pkg  typeFqn  method  paramSig                   ブロック内のメソッドの記号表（{@link SymbolTable}）。
 *                                                          番号は 0 から詰めて振る。下の「記号」はこの番号
 *   N  番号  kind  value  recv  args  argCount  staticRecv    {@link ValueNode}。値グラフのノード。
 *                                                          番号はブロック内の 0 始まりの連番で、recv と args は
 *                                                          同じブロックの、自分より前のノードを指す。
 *                                                          キャッシュの値（戻り値・代入・条件・呼び出し箇所）は
 *                                                          どれもここのノードを番号で指す（下の「値はノードで持つ」）
 *   G  ガード番号  op  subject  text  値1  値2 …              {@link Guard.Atom}。条件分岐の表。アトム 1 つにつき 1 行。
 *                                                          ガード番号はブロック内の 0 始まりで、C 行・U 行が初めて
 *                                                          使った順。1 つのガードのアトムは同じ番号で続けて並ぶ。
 *                                                          subject はノード番号（種別 A か V）。値は後ろの列に 1 つずつ
 *                                                          （EQ / NE は 1 つ、IN / NI は 1 つ以上）で、定数の値そのもの。
 *                                                          case の無い switch の default は条件にしない（必ず通る）
 *   R  記号  node                                            {@link ReturnFact}。戻り値のノード（-1 は「追跡できない」）。
 *                                                          D 行より前に置く（読み手はここでメソッドを ID 化するので、
 *                                                          以前の形式と同じ ID の順になる。jche.graph.CallGraphBuilder 参照）
 *   H  typeFqn  kind(I=IF/A=抽象/C=具象)  親型(カンマ区切り)  pkg  アノテーション
 *                                                          {@link TypeFact}
 *   D  記号  declLine  hasBody(1/0)  mods  アノテーション  endLine  [returnType]
 *                                                          returnType はアノテーションの付いたメソッドにだけ書く
 *                                                          （宣言した戻り値の消去型。DI の &#64;Bean が使う）。
 *                                                          {@link MethodDeclFact}。AST を訪ねた順に置く（同じ
 *                                                          ソースからは必ず同じ並び）。読み手はブロックの中で何番目の
 *                                                          D 行かを「宣言の順番」として、同じ行に並ぶ宣言の前後を
 *                                                          決める（jche.graph.MethodTable#compareDeclarationOrder）
 *   O  記号  上書き先のキー(;区切り)                          {@link OverrideFact}。D 行より後
 *   V  typeFqn  fieldName  mods  declType  アノテーション      {@link FieldDeclFact}
 *   C  呼び出し元の記号  呼び出し先の記号  callLine  calleeMods  recvKind  lambdaDepth  qualifier
 *      recv  args  guard  hints                              {@link CallEdgeFact} と、その呼び出し箇所の値
 *                                                          （{@link CallSiteValues.Row}）
 *   U  line  呼び出し元の記号  expr  reason  candidate  recvKind  lambdaDepth  recv  args  guard  hints
 *                                                          {@link UnresolvedCallFact} と、その呼び出し箇所の値。
 *                                                          C 行と U 行はソース上の順のまま混ざって並ぶ
 *   M  line  呼び出し元の記号  ifaceTypeFqn#method(paramSig)  kind
 *                                                          {@link FunctionalImplFact}
 *   A  line  呼び出し元の記号  ownerTypeFqn  fieldName  access  mods  lambdaDepth
 *                                                          {@link FieldAccessFact}（今の読み手は使わない）
 *   K  typeFqn  name  種別(V=値/H=ハッシュ)  値                 {@link ConstantFact}
 *   J  typeFqn  fieldName  site  node                         {@link FieldAssignFact}（node は代入された値。-1 は「追跡できない」）
 *   Z  ブロック数                                              最終行。ここまで書き終えた印
 *                                                          （{@link #trailerFor}）。これが無い・数が合わない
 *                                                          キャッシュは途中で切れているとみなして捨てる
 * </pre>
 * 呼び出し元が特定できない U 行（{@link UnresolvedCallFact#OUTSIDE_METHOD}）の記号は {@code -1}。
 * recv はノード番号（無ければ {@code -1}）、args は {@code 位置=ノード番号} のカンマ区切り、
 * guard は G 行のガード番号（無ければ {@code -1}）、hints はレシーバの変数に new だけが代入されている
 * ときの、その型の FQN のカンマ区切り（無ければ空。{@link CallSiteValues.Row#hints}）。
 *
 * <h2>値はノードで持つ</h2>
 * 値（戻り値の R 行・代入の J 行・条件の G 行の subject・呼び出し箇所の recv と args）は、どれも N 行の
 * ノードを番号で指す。以前は R 行・J 行・条件が出所の文字列（{@link Origin}）を持っていて、文字列の側には
 * 上限（実引数は 1 段・レシーバは 3 段・文字列は 64 文字で識別子の形だけ）があった。値グラフは上限なしで
 * 同じ式を 1 ノードにするので、どの値も同じ表し方・同じ細かさになる。読み手（jche.graph.CallGraphBuilder）は
 * ノードを値の表（jche.graph.ValueStore）に取り込んで読む（戻り値は丸ごと、代入はノードの頭 {@code 種別:値} の
 * 葉だけ、条件の subject も頭の葉だけ）。出所の文字列には組み直さない。
 *
 * <h2>記号表（S 行）</h2>
 * メソッドを指す列（宣言・上書き・戻り値・呼び出し元・呼び出し先）は、4 列（pkg・typeFqn・名前・引数）を
 * 繰り返す代わりに、ブロックの S 行の番号で指す。番号はブロックの中だけで通じるので、ブロックを
 * まるごと書き写す差分更新でも参照が壊れない。番号は行を書く順（R・D・O・C/U・M・A）に初めて現れた順に振る。
 * 同じ解析結果からは同じ番号になる。人が読むときは {@link CacheDump} で 4 列に戻した形を出せる。
 * ノードの番号（N 行）とガードの番号（G 行）も同じく、ブロックの中だけで通じる。
 *
 * <h2>列の符号化（1 つの規則）</h2>
 * どの列も {@link #joinRow} が {@link #escape} で符号化して書き、読むときは {@link #columnsOf}
 * （{@link CacheReader#columns}）が {@link #unescape} で戻す。値そのものは失わない
 * （SQL の文字列や改行を含む値もそのまま持てる）。record の {@code toRow} は生の値を渡し、
 * {@code fromRow} は戻した値を受け取る。値を<b>拾わない</b>判断（{@link #hasControlChar}）は
 * 事実を作る側の判断で、符号化とは別である。
 *
 * <h2>ブロックの検査値（F 行の crc）</h2>
 * F 行の次の行から、次の F 行・Z 行の手前までの各行を UTF-8 にし、{@code '\n'} を付けて並べた
 * バイト列の CRC32（{@link BlockChecksum}）。書き手はブロックをメモリ上で組んでから検査値を求めて書く。
 * 差分更新（パス1）はブロックごとに検査値を計算し直し、合わなければそのブロックだけを無効にして
 * 解析し直す（ほかのブロックは再利用する）。行の書き換え・化けのほか、記号やノードの番号が
 * ブロックの外を指す壊れ方もこれで捕まえる。それでも読み手は番号の範囲を確かめる
 * （{@link SymbolTable#refsInRange}。呼び出しを黙って落とさないため）。
 *
 * <h2>読み手の責務（キャッシュに入れない判断）</h2>
 * <ul>
 *   <li>静的束縛の判定（calleeMods → 種別）            … jche.graph.BindKind</li>
 *   <li>ガードが「この経路では成立しない」と言い切れるかの判定 … jche.graph.GuardEvaluator</li>
 *   <li>戻り値の集約（追跡できない return が1つでもあれば不定） … jche.graph.DataflowResolver</li>
 *   <li>コンストラクタ注入フィールドの判定（private/final、全コンストラクタで代入、出所が一致）
 *                                                      … jche.graph.FieldFacts</li>
 *   <li>import 推定（U の candidate）をエッジとして採用するか … jche.graph.CallGraphBuilder</li>
 *   <li>ラムダ内の呼び出しの計上先                     … jche.graph.CallGraphBuilder（現状は囲みメソッド）</li>
 *   <li>上書き関係（O行）をどう候補引きに使うか       … jche.graph.OverrideIndex / jche.graph.CallGraph</li>
 *   <li>未解決の理由コードの文言                       … jche.report.UnresolvedReport</li>
 *   <li>どのアノテーションがDIの印か・値をどう解釈するか … jche.graph.SpringBeans</li>
 *   <li>どのアノテーションが「実装はコンパイル時生成」を意味するか … jche.framework.GeneratedImpl</li>
 * </ul>
 *
 * <h2>同一性（何をもって「同じ」とみなすか）</h2>
 * ソースファイル（F行）も依存 jar（L行）もソース一覧（T行）も、<b>パスと中身</b>だけで見る。
 * 更新時刻は記録も参照もしない。更新時刻は中身と関係なく変わる（git のチェックアウト、コピー、
 * CI のたびに作り直されるワークスペース）ので、当てにすると「中身は同じなのにキャッシュを捨てる」
 * が起きる。逆に、バージョン管理が更新時刻を復元する設定だと「中身が違うのに再利用する」も
 * 起きうる。どちらも中身を見れば起きない（docs/cache-identity-qa.md）。
 *
 * <h2>差分更新と依存</h2>
 * 再利用の判定は「F行のサイズと内容ハッシュが一致し、ブロックの検査値が合う」に加えて
 * 「I行の型を宣言するファイルがどれも変わっていない」。
 * 呼び出し先・フィールドの所有型・修飾子・親型はバインディング解決の
 * 結果であり、別のファイルを変えると変わりうるため（{@link jche.analysis.CacheUpdater} 参照）。
 * ただしコンパイル時定数（{@code static final} の値と注釈のメンバの既定値）だけは、
 * 使う側のファイルに値そのものが焼き込まれるため、参照した型を1段辿るだけでは足りない。
 * 宣言している側に値を K 行として残しておき、解析し直して値が変わっていたら、その型を参照する
 * ファイルも解析し直す（{@link jche.analysis.CacheUpdater} の「定数の連鎖」）。
 * 依存 jar も同じ理由で解決結果を左右するので、L行と突き合わせて追加・変更・削除を検知し、
 * その jar のパッケージの型を参照するファイル（I行）と、型解決に失敗していたファイル
 * （F行のエラー数、U行の BINDING_FAILED）を解析し直す。
 * L行の<b>並び順</b>も見る。jar の集合が同じでも、並びが変われば同名クラスの解決先が
 * 変わりうるため（{@link jche.analysis.LibraryDiff} の「並び順」）。
 * 実行中の JDK もブートクラスパスとして解決に加わるため、ヘッダ行に含めて丸ごと突き合わせる。
 * 拡張（{@code plugin.folders}）はグラフを組むときにだけ動いてキャッシュには何も書かないので、
 * ヘッダ行には入れない。拡張を足しても外しても、キャッシュはそのまま再利用できる。
 *
 * <h2>バージョン（{@link #VERSION}）を上げる基準: 迷ったら上げる</h2>
 * 書き手の変更でキャッシュに入る事実が変わりうるなら上げる。列や意味の変更に限らず、収集範囲・
 * 値の正規化・書き手が作る文字列（{@link Guard} の text）の変更も含む。上げると利用者は 1 回だけ
 * 全件解析になるが、上げ忘れると再利用したファイルだけが古い事実のまま残り、「静かに違う結果」になる。
 * 前者は安全側の費用なので、迷ったら上げる。
 * 上げ忘れは {@code test/cacheversion/run.sh} が捕まえる（決まった題材の事実の指紋を記録と比べる）。
 * 上げたら {@code bash test/cacheversion/run.sh --update} で記録を更新する。
 * 読み手だけの変更（解決ラベル、CSVの列、フィルタ、注記の文言）では事実が変わらないので上げなくてよい。
 * JDT の版と実行 JDK のメジャー版はヘッダ行の鍵（{@link #headerFor}）に入っているので、
 * それらを変えるだけなら版は上げなくてよい。
 *
 * <h2>事実の収集範囲（書き手の打ち切り。変えたらバージョンを上げる）</h2>
 * <ul>
 *   <li>値グラフ（N 行）の入れ子には段数の上限が無い。1つの式を1ノードとして持ち、
 *       参照はノード番号で行うので、大きさが式の数に比例し、深さに依存しないため。
 *       読み手はここから値の表（{@link jche.graph.ValueStore}）に取り込む。戻り値（R 行）・代入（J 行）・
 *       条件の subject（G 行）も同じノードで持つ</li>
 *   <li>外側スコープの変数の出所は、final または実質 final のときだけ持ち込む。
 *       匿名・ローカルクラスのフィールド初期化子（J 行）も、囲むメソッドの変数は捕捉した変数として読む</li>
 *   <li>ローカル変数の出所の先読みは、表が変わらなくなるまで繰り返す（ループの中で後ろの代入が
 *       前の行で写した変数に届く）。上限の回数（8 回）を使い切ったら、その先読みで書いた変数はすべて U。
 *       複合代入・{@code ++} / {@code --} で書き換わる変数と、宣言が値を受け取る変数
 *       （{@code catch} の引数・拡張 for の変数・パターンの変数）に別の値が代入されたものは U</li>
 *   <li>値として使う式は、括弧と値を変えないキャストだけを剥がす。値を変えうるキャストの式は、
 *       コンパイル時定数なら変換後の値、そうでなければ U。浮動小数の定数は持たない。
 *       数値リテラルの値は表記からではなく JDT の評価から取る（{@code 0x80000000} は {@code -2147483648}）</li>
 *   <li>条件（G 行）の {@code equals} は、比べる相手の静的な型が {@code String}・定数と同じ列挙型・
 *       定数を箱詰めした型（浮動小数を除く）のときだけアトムにする</li>
 *   <li>条件の subject は、値グラフのノードが引数（A）か定数（V）のときだけアトムにする。
 *       文字列のコンパイル時定数（ノードは L）は、64 文字以内で制御文字を含まなければ V のノードにする。
 *       期待値（G 行の値）はコンパイル時定数の値そのもの（64 文字以内で制御文字を含まないもの）</li>
 *   <li>new の証拠（C 行・U 行の hints）は、代入がすべて new のローカル変数にだけ持つ（引数・フィールド・
 *       拡張 for の変数には持たない）。呼び出し元とレシーバの変数で、同じファイルの中だけで結びつける</li>
 *   <li>値グラフの深さの上限（安全策）は、式の木の中での深さで判定する（同じ式は 1 つのノード）</li>
 *   <li>値グラフの文字列リテラル・定数の値には長さと内容の上限が無い。
 *       SQL やログ文言もそのまま持ち、行形式を壊す文字は {@link #escape} で符号化する</li>
 *   <li>戻り値の宣言型（ラムダは関数型インターフェースのメソッドの戻り値の型）がプリミティブ・void・配列・
 *       String のメソッドの return は記録しない。return の式の型では決めないので、1 つのメソッドの
 *       return は全部記録するか、全部しないかのどちらか</li>
 *   <li>フィールドへの代入は、その型自身のメソッド・コンストラクタ本体とフィールド初期化子から拾う
 *       （インスタンス初期化ブロックと内部クラスからの代入は拾わない）</li>
 *   <li>コンストラクタ呼び出しは new / this(...) / super(...) を C 行にする。
 *       ソースに呼び出し式が無いが JLS が「呼ぶ」と定める呼び出し（暗黙の super()、拡張 for 文の
 *       iterator() / hasNext() / next()、try-with-resources の close()、レコードパターンのアクセサ）も C 行にする</li>
 *   <li>N 行に「ソースに書いたときのレシーバの型」を持つ。
 *       {@code DaoFactory.get(...)} の {@code get} が親で宣言されていると、メソッドキーは
 *       親になる。利用者が契約表や拡張で指定するのはソースに書いてある型なので、
 *       違うときだけ書かれた型も残す（{@link jche.graph.FactoryCalls}）</li>
 *   <li>C行・U行に guard（呼び出し箇所を囲む条件分岐。{@link Guard}。中身は G 行）を持ち、
 *       コンパイル時定数の値を定数のノード（{@link Origin#CONST}）として記録する。
 *       「その経路では呼ばれない」と言い切れる呼び出しを読み手が見分けるため</li>
 *   <li>H 行の親型は、jar の型を経由して到達するソース上の親型も含める。
 *       jar の基底クラスがソースのインターフェースを実装している構成で、その子を CHA の候補に入れるため</li>
 *   <li>H 行・D 行・V 行にアノテーションを持つ（{@link AnnotationTokens}）。選別はせず、
 *       付いているものを宣言順に全部残す。値は単一メンバと value / name の文字列だけ。
 *       どのアノテーションに意味があるかは読み手の判断
 *       （{@link jche.graph.SpringBeans} / {@link jche.framework.GeneratedImpl}）</li>
 *   <li>行形式を壊す値（タブ・改行を含む文字列定数、複数行の注釈の値）は K 行・アノテーションの
 *       事実としては拾わない（値ではなくハッシュにする・持たない）。値グラフは符号化して全部持つ</li>
 * </ul>
 *
 * H行は「単一実装ショートカット」と「CHA」に必須。これが無いと
 * インターフェース・抽象クラスの実装クラスを特定できない。
 * D行のhasBodyは、インターフェースの抽象メソッド（本体なし）と
 * デフォルトメソッド（本体あり）を区別するために必要。
 */
public final class CacheFormat {

    public static final String SEP = "\t";

    /**
     * キャッシュの形式の版。変更した場合はここを上げる。旧キャッシュは自動的に破棄される
     * （上げる基準はクラスの説明「迷ったら上げる」）。
     *
     * <p>主な変更（詳しい経緯は docs/ の各 *-qa.md）:
     * <ul>
     *   <li>v16 同一性をパス・サイズ・内容ハッシュにした（更新時刻の列を落とした）</li>
     *   <li>v19 値の列を当時の dataflow-cache.tsv へ分けた</li>
     *   <li>v20 D 行に endLine を足した</li>
     *   <li>v21 ラムダ式を合成メソッドとして持つようにした</li>
     *   <li>v22 F 行に構文エラーの数を足した</li>
     *   <li>v24〜v26 M 行・ラムダの合成メソッドの名前の規則を JLS / javac に合わせた</li>
     *   <li>v27 インターフェースに暗黙のコンストラクタを合成しないようにした</li>
     *   <li>v28 JLS が「呼ぶ」と定める暗黙の呼び出しを C 行にした</li>
     *   <li>v29 C 行に呼び出しを修飾する型（qualifier）を足した</li>
     *   <li>v30 F 行の構文エラーの数から var の使い方の誤りを外した</li>
     *   <li>v31 2 ファイルを 1 ファイルにまとめ、値を呼び出し箇所の行に持たせ、メソッドを
     *       ブロック内の記号表（S 行）で指し、F 行に未解決数とブロックの検査値を足した
     *       （{@code docs/cache-unification-qa.md}）</li>
     *   <li>v32 値として使う式で、値を変えうるキャストを剥がさない（定数は JDT が畳んだ値）・浮動小数の定数を
     *       値グラフに持たない・複合代入と ++ / -- を書き換えとして数える・匿名／ローカルクラスのフィールドの
     *       代入（J 行）を囲むメソッドの枠から切り離す・new の証拠（X 行）を new だけが代入されるローカル変数に
     *       限る・同じ式を 1 つのノードにする・数値リテラルの値を JDT から取る・ローカル変数の先読みを
     *       表が変わらなくなるまで繰り返す・型の揃わない equals を条件にしない（{@code docs/value-safety-qa.md}）</li>
     *   <li>v33 値をすべて値グラフのノードで持つ（R 行・J 行はノード番号、条件は G 行の表を番号で指す、
     *       条件の期待値を切り詰めない）。new の証拠（X 行）と C 行・U 行の recvKey を無くし、
     *       証拠は C 行・U 行の hints に直接持つ。R 行を書くかどうかを return の式の型ではなく
     *       宣言の戻り値の型で決める（{@code docs/cache-unification-qa.md}）</li>
     *   <li>v34 I 行にソースに書かれた型の名前（戻り値・throws・ローカル変数などの型）とラムダの目標の型を
     *       足した。D 行にアノテーションの付いたメソッドの戻り値の型を足した。対になっていないサロゲートを
     *       符号化し、条件の文字列をサロゲートペアの途中で切らない（{@code docs/cache-unification-qa.md} の
     *       Q42〜Q49）</li>
     *   <li>v35 型の形（I 行の 2 列目）のメソッドに可変長引数か・throws の型と検査例外かを足し、親型のメンバーと
     *       名前の当たらない私的メンバーを外した。I 行に呼び出し先の throws の型、拡張 for 文・switch のセレクタ・
     *       throw の式・アノテーションの型、switch の case の型とその親、型解決に失敗したブロックでは参照した型の
     *       親を足した（{@code docs/cache-unification-qa.md} の Q51〜Q55）</li>
     * </ul>
     */
    public static final String VERSION = "jche-cache-v35";

    // 行の種別（各行の先頭1文字）
    public static final char ROW_SOURCES = 'T';
    public static final char ROW_LIBRARY = 'L';
    public static final char ROW_FILE = 'F';
    public static final char ROW_DEPENDENCIES = 'I';
    /**
     * I 行の 3 列目（解決できなかった名前）で、名前を 1 つも拾えなかった印。どの新しい型にも当たるとみなす
     * （新しい型ができたら必ず解析し直す）
     */
    public static final String ANY_NAME = "*";
    /** ブロック内のメソッドの記号表（{@link SymbolTable}） */
    public static final char ROW_SYMBOL = 'S';
    /** 値グラフのノード（{@link ValueNode}） */
    public static final char ROW_VALUE_NODE = 'N';
    /** 条件分岐のアトム（{@link Guard.Atom}）。N 行の直後に並ぶ */
    public static final char ROW_GUARD = 'G';
    public static final char ROW_RETURN = 'R';
    public static final char ROW_TYPE = 'H';
    public static final char ROW_METHOD_DECL = 'D';
    public static final char ROW_OVERRIDE = 'O';
    public static final char ROW_FIELD_DECL = 'V';
    public static final char ROW_CALL = 'C';
    public static final char ROW_UNRESOLVED = 'U';
    public static final char ROW_FUNCTIONAL_IMPL = 'M';
    /** フィールドの参照箇所（今の読み手は使わない。{@link FieldAccessFact}） */
    public static final char ROW_FIELD_ACCESS = 'A';
    public static final char ROW_CONSTANT = 'K';
    public static final char ROW_FIELD_ASSIGN = 'J';
    public static final char ROW_END = 'Z';

    /**
     * C 行・U 行で、呼び出し箇所の値（{@link CallSiteValues.Row}）が始まる列。
     * recv・args・guard・hints の順に並ぶ
     */
    public static final int CALL_VALUES_COLUMN = 8;

    private static final int[] SYMBOLS_CALL = {1, 2};
    private static final int[] SYMBOLS_AT_1 = {1};
    private static final int[] SYMBOLS_AT_2 = {2};
    private static final int[] NONE = {};

    private CacheFormat() {
    }

    /**
     * その種別の行で、記号（S 行の番号）を持つ列の位置（昇順）。記号を持たない種別なら空。
     *
     * <p>記号の列はどれも「メソッドを 4 列（pkg・typeFqn・名前・引数）で書いていた位置」にある。
     * 範囲の検査（{@link SymbolTable#refsInRange}）と、4 列に戻して見せる {@link CacheDump} が使う。
     * 行ごとに通るので配列を作り直さずに返す（呼び出し側は書き換えない）
     */
    static int[] symbolColumnsOf(char rowType) {
        return switch (rowType) {
            case ROW_CALL -> SYMBOLS_CALL;
            case ROW_METHOD_DECL, ROW_OVERRIDE, ROW_RETURN -> SYMBOLS_AT_1;
            case ROW_UNRESOLVED, ROW_FUNCTIONAL_IMPL, ROW_FIELD_ACCESS -> SYMBOLS_AT_2;
            default -> NONE;
        };
    }

    /**
     * F 行（{@code F パス サイズ エラー数 ハッシュ 構文エラー数 未解決数 crc}）を作る。
     *
     * @param unresolved 使える候補の無い U 行の数（{@link UnresolvedCallFact#hasUsableCandidate}）
     * @param crc        ブロックの検査値（{@link BlockChecksum#hex}）
     */
    public static String fileRow(String relativePath, long size, int errors, String hash, int syntaxErrors,
                                 int unresolved, String crc) {
        return joinRow(String.valueOf(ROW_FILE), relativePath, String.valueOf(size), String.valueOf(errors),
                hash, String.valueOf(syntaxErrors), String.valueOf(unresolved), crc);
    }

    /**
     * F 行の構文エラー数。
     *
     * <p>列が無ければ 0 とみなす。{@link #VERSION} を上げてあるので古いキャッシュは
     * そもそも読まないが、読み手を列の有無に依存させない
     */
    public static int syntaxErrorsOf(String[] fileRow) {
        return intColumn(fileRow, 5);
    }

    /**
     * F 行のエラー数（コンパイルエラーの総数。構文エラーを含む）。
     * 0 でなければ、そのファイルは「ビルドが通らない」状態で解析されている（warnings.txt に載せる）
     */
    public static int errorsOf(String[] fileRow) {
        return intColumn(fileRow, 3);
    }

    /**
     * F 行の未解決数（使える候補の無い U 行の数）。ブロックを書き写すときに U 行を読まずに数えるため
     */
    public static int unresolvedOf(String[] fileRow) {
        return intColumn(fileRow, 6);
    }

    /** F 行に書かれたブロックの検査値。無ければ空文字（どの計算結果とも一致しない） */
    public static String crcOf(String[] fileRow) {
        return columnAt(fileRow, 7);
    }

    private static int intColumn(String[] row, int index) {
        if (row == null || row.length <= index) {
            return 0;
        }
        try {
            return Integer.parseInt(row[index].trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * キャッシュの1行目。形式のバージョンに加えて、ソースレベル・ソースの文字コード・
     * 実行中の JDK も入れる。
     *
     * 同じソースでも、どの言語バージョンとして解析したかで結果が変わる
     * （古いレベルだと新しい構文が解析できず、呼び出しが抜ける）。
     * 文字コードも同じで、違う文字コードで読めば文字列リテラルの値が変わり、
     * 構文解析そのものが通らないこともある。しかも {@code source.encoding} が空欄なら
     * {@code pom.xml} の {@code project.build.sourceEncoding} から決まるので、
     * <b>.java を1行も触らずに</b>解釈が変わることがある。
     * JDT は実行中の JVM のブートクラスパスを解析対象のクラスパスに含めるため、
     * JDK の版が変わると標準 API の解決結果も変わりうる。
     * ソースの中身だけを見ていると、設定や実行環境を変えたのに古い結果を
     * 再利用してしまうため、1行目に含めて丸ごと突き合わせる。
     *
     * <p>解析に使う JDT の版（{@code jdt=}。{@code jche.analysis.JdtVersion}）も入れる。
     * バインディングの解決・JLS の解釈・ガードの条件式のテキスト（JDT の AST の文字列化）は
     * JDT の版で変わりうるのに、以前は鍵に入っておらず、JDT を上げても古い事実を再利用していた。
     * 版が分からない（{@code ?}）ときは、鍵が一致しないとみなす（{@link CacheReader#headerMatches}）。
     *
     * @param jdtVersion {@code jche.analysis.JdtVersion#current()}。この層は JDT に依存しないので
     *                   呼び出し側から渡す
     */
    public static String headerFor(String sourceLevel, String sourceEncoding, String jdtVersion) {
        return VERSION + SEP + "source=" + sourceLevel
                + SEP + "enc=" + sourceEncoding
                + SEP + "jdk=" + System.getProperty("java.specification.version", "?")
                + SEP + "jdt=" + jdtVersion;
    }

    /**
     * 解析対象のソースファイル一覧の指紋（相対パス・サイズ・内容ハッシュ）。
     *
     * 中断した前回の実行から解析結果を引き継いでよいかの判定だけに使う
     * （{@link jche.analysis.CacheUpdater} の「中断した実行からの引き継ぎ」）。
     * 引き継ぐブロックは「そのファイルの内容」だけでなく「他のファイルの内容」にも依存する
     * （呼び出し先・親型・コンパイル時定数の値はバインディング解決の結果なので）。
     * 1ファイルぶんが一致していても、他のファイルが変わっていればそのブロックは古い。
     * ソース一覧が丸ごと同じときだけ引き継ぐ、という判定にこれを使う。
     *
     * <p>中身は差分更新の同一性（F行）と同じ「パス・サイズ・内容ハッシュ」。更新時刻は入れない
     * （上の「同一性」）。
     *
     * <p>差分更新（F行の同一性、I行の依存）には使わない。あちらは
     * 「変わったファイルとその依存元だけを解析し直す」ので、丸ごと一致している必要はない。
     */
    public static String sourcesRow(String fingerprint) {
        return joinRow(String.valueOf(ROW_SOURCES), fingerprint);
    }

    /**
     * キャッシュの最終行。ここまで書き終えたことの印と、書いたブロック（F行）の数。
     *
     * キャッシュは一時ファイルへ書いてから移すので、このツール自身が半端なファイルを
     * 残すことはない。それでも印を置くのは、外から壊れたファイルが来る経路があるため
     * （GitHub Actions のキャッシュの復元、コピーの失敗、ディスクの異常）。
     * 途中で切れたキャッシュは、切れた場所より前のブロックが「サイズも内容ハッシュも一致する」
     * ように見えてしまうので、印が無ければ丸ごと捨てて全件解析し直す。
     * 数まで見るのは、途中のブロックが抜けた場合も気づけるようにするため。
     */
    public static String trailerFor(long blocks) {
        return ROW_END + SEP + blocks;
    }

    /** 行の先頭1文字（種別）。空行なら '\0' */
    public static char rowTypeOf(String line) {
        return line.isEmpty() ? '\0' : line.charAt(0);
    }

    /**
     * 1行をタブで分割し、各列を {@link #unescape} で戻す。末尾の空列も落とさない。
     *
     * {@code String.split} を使わず自前で分けるのは、キャッシュを読む経路で
     * 行の数だけ通るため。区切りの数を先に数えて配列を1つだけ作れば、
     * 途中の可変長リストとその作り直しが要らない。
     * 符号化されていない列（バックスラッシュを含まない列。ほとんどがそう）は作り直さない
     */
    public static String[] columnsOf(String line) {
        char sep = SEP.charAt(0);
        int count = 1;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == sep) {
                count++;
            }
        }
        String[] cols = new String[count];
        int at = 0;
        int from = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == sep) {
                cols[at++] = unescape(line.substring(from, i));
                from = i + 1;
            }
        }
        cols[at] = unescape(line.substring(from));
        return cols;
    }

    /** 指定位置の列。無ければ空文字（後ろに列が足された旧形式を許容するため） */
    public static String columnAt(String[] cols, int index) {
        return (index < cols.length) ? cols[index] : "";
    }

    /**
     * 値を、行形式を壊さない形に符号化する（逆は {@link #unescape}）。{@link #joinRow} がすべての列に通す。
     *
     * <h4>規則</h4>
     * <pre>
     *   \      ->  \\
     *   タブ    ->  \t
     *   LF      ->  \n
     *   CR      ->  \r
     *   その他の制御文字（U+0000〜U+001F と U+007F）  ->  &#92;uXXXX（小文字の16進4桁）
     *   対になっていないサロゲート（U+D800〜U+DFFF）    ->  &#92;uXXXX（同上）
     * </pre>
     * それ以外の文字はそのまま。非 ASCII は UTF-8 のまま書くので符号化しない（対になったサロゲートは
     * 補助文字 1 つとして UTF-8 に書ける）。対になっていないサロゲートは UTF-8 に書けない（キャッシュの
     * 書き手は符号化できない文字で例外にする）ので、ソースの "&#92;uD800" のような文字列リテラルや、
     * 途中で切った絵文字を持つ値があっても行を書けるように符号化する。{@link #unescape} は
     * &#92;uXXXX を同じ char に戻すので、値は変わらない。
     * {@link Guard} が区切りに使う {@code U+0001}〜{@code U+0003} も「その他の制御文字」として
     * 符号化されるので、値と区切りが衝突しない。
     *
     * <p>符号化した結果にタブ・改行・制御文字は残らない（{@link #hasControlChar} が false になる）。
     */
    public static String escape(String s) {
        if (s == null) {
            return "";
        }
        int at = indexOfEscapable(s);
        if (at < 0) {
            return s;   // 変換の要らない値（ほとんどはこちら）は作り直さない
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        sb.append(s, 0, at);
        for (int i = at; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isSurrogate(c)) {
                if (isPaired(s, i)) {
                    sb.append(c).append(s.charAt(i + 1));   // 補助文字はそのまま（UTF-8 に書ける）
                    i++;
                } else {
                    sb.append("\\u").append(String.format("%04x", (int) c));
                }
                continue;
            }
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> {
                    if (c < ' ' || c == '\u007f') {
                        sb.append("\\u").append(String.format("%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** 符号化が要る最初の文字の位置。無ければ -1 */
    private static int indexOfEscapable(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c < ' ' || c == '\u007f') {
                return i;
            }
            if (Character.isSurrogate(c)) {
                if (!isPaired(s, i)) {
                    return i;
                }
                i++;   // 対になった下位サロゲートは飛ばす
            }
        }
        return -1;
    }

    /** {@code s.charAt(i)} が上位サロゲートで、直後に下位サロゲートが続くか（対になったサロゲートの先頭か） */
    private static boolean isPaired(String s, int i) {
        return Character.isHighSurrogate(s.charAt(i)) && i + 1 < s.length()
                && Character.isLowSurrogate(s.charAt(i + 1));
    }

    /**
     * {@link #escape} の逆。
     *
     * 規則にない並び（{@code \x} のような、符号化では作られない形）は、文字どおり
     * バックスラッシュと次の文字として返す。手で編集された・壊れたキャッシュでも例外にせず、
     * 読めるところまで読む（キャッシュが壊れていれば、ブロックの検査値で捨てられる）。
     */
    public static String unescape(String s) {
        if (s == null) {
            return "";
        }
        int at = s.indexOf('\\');
        if (at < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        sb.append(s, 0, at);
        for (int i = at; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char next = s.charAt(i + 1);
            switch (next) {
                case '\\' -> {
                    sb.append('\\');
                    i++;
                }
                case 't' -> {
                    sb.append('\t');
                    i++;
                }
                case 'n' -> {
                    sb.append('\n');
                    i++;
                }
                case 'r' -> {
                    sb.append('\r');
                    i++;
                }
                case 'u' -> {
                    int cp = hex4(s, i + 2);
                    if (cp < 0) {
                        // バックスラッシュ u に続く16進4桁が無い。文字どおりに扱う
                        sb.append(c);
                    } else {
                        sb.append((char) cp);
                        i += 5;
                    }
                }
                default -> sb.append(c);   // 規則にない並び。バックスラッシュをそのまま置く
            }
        }
        return sb.toString();
    }

    /** s の位置 at から16進4桁を読む。読めなければ -1 */
    private static int hex4(String s, int at) {
        if (at + 4 > s.length()) {
            return -1;
        }
        int v = 0;
        for (int i = at; i < at + 4; i++) {
            int d = Character.digit(s.charAt(i), 16);
            if (d < 0) {
                return -1;
            }
            v = (v << 4) | d;
        }
        return v;
    }

    /**
     * 行形式を壊しうる文字（タブ・改行のほか、{@link Guard} が区切りに使う制御文字）を含むか。
     *
     * 事実を<b>作る側</b>が「この値は持たない」と判断するために使う。符号化（{@link #escape}）すれば
     * 行は壊れないが、K 行の値やアノテーションの値のように「持たない」と決めてある事実では、
     * 値そのものを拾わない方に倒す（{@code jche.analysis.OriginTracker} / {@link AnnotationTokens} /
     * {@link ConstantFact}）。
     */
    public static boolean hasControlChar(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < ' ' || c == '\u007f') {
                return true;
            }
        }
        return false;
    }

    /**
     * 列を並べて1行にする。
     *
     * 列の値は必ず {@link #escape} で符号化する。ここが行を書き出す唯一の入口なので、
     * 値を作る側がどんな文字を渡しても行は壊れず、値も失われない（読むときに {@link #columnsOf} が戻す）。
     */
    public static String joinRow(String... cols) {
        // 行の長さを先に見積もっておく（継ぎ足しのたびに内部の配列を作り直さないため）
        int capacity = cols.length;
        for (String col : cols) {
            if (col != null) {
                capacity += col.length();
            }
        }
        StringBuilder sb = new StringBuilder(capacity);
        for (int i = 0; i < cols.length; i++) {
            if (i > 0) {
                sb.append(SEP);
            }
            sb.append(escape(cols[i]));
        }
        return sb.toString();
    }
}
