// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cache;

import java.security.SecureRandom;

/**
 * キャッシュファイルの形式（タブ区切り。外部ライブラリ不要でデバッグしやすい）。
 *
 * <h2>キャッシュは 2 つに分かれている</h2>
 * <pre>
 *   analysis-cache.tsv   呼び出し階層を出すための事実（構造とバインディング）
 *   dataflow-cache.tsv   呼び出し階層には要らない、データフローのための事実
 * </pre>
 * 呼び出し階層（{@code call-hierarchy.csv} / {@code methods.csv}）は前者だけで出せる。
 * 後者はサイドカーの解析（値の追跡など）のためにあり、文字列の長さの上限を設けない。
 * 分けた理由と、どちらに何を置くかの基準は {@code docs/cache-split-qa.md} にある。
 *
 * <b>2 つは常に同じ実行で一緒に書かれ、片方だけが新しい状態は許さない。</b>
 * 両方のヘッダに同じ世代の印（{@link #GENERATION_PREFIX}）を書き、読むときに突き合わせる。
 * 食い違えば両方とも捨てて全件解析し直す（ブロック単位のズレを許すと、同じソースでも
 * 「どちらのキャッシュがどこまで新しいか」で出力が変わってしまう）。
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
 * <h2>行の種別と列</h2>
 * 各行の列の並びは、その行を表す record の {@code toRow()} / {@code fromRow()} が定義する。
 * <pre>
 *   T  ソース一覧の指紋                                       解析対象のソースファイル一覧（パス・サイズ・
 *                                                          内容ハッシュ）のハッシュ。L 行の直後に1行。
 *                                                          中断した実行からの引き継ぎ（{@link #sourcesRow}）でだけ使う
 *   L  jarのパス  指紋  パッケージ(カンマ区切り)            {@link LibraryFact}。ヘッダ行の直後に
 *                                                          クラスパス順で並ぶ（並び自体も意味を持つ。
 *                                                          JDT は同名クラスを先勝ちで解決するため）。
 *                                                          解析時の依存 jar。
 *                                                          指紋は中に入っているクラスの一覧
 *                                                          （{@link jche.analysis.LibraryDiff}）。読めなければ空
 *   F  相対パス  サイズ  エラー数  内容ハッシュ                （ファイルのブロックの先頭）。エラー数は
 *                                                          JDT が報告したエラーの件数（解決が不完全な印）。
 *                                                          内容ハッシュは {@link jche.util.FileHash}（読めなければ空）
 *   I  依存する型（カンマ区切り）                             このファイルのバインディング解決が参照した型の
 *                                                          FQNと、import 文の型（オンデマンド import は
 *                                                          "pkg.*"）。自分が宣言する型は含まない。差分更新時に、
 *                                                          これらの型を宣言するファイルが変わっていたら
 *                                                          再解析する（{@link jche.analysis.CacheUpdater} 参照）
 *   H  typeFqn  kind(I=IF/A=抽象/C=具象)  親型(カンマ区切り)  pkg  アノテーション
 *                                                          {@link TypeFact}。親型は直接の親と、
 *                                                          jar の型を経由して到達するソース上の親。
 *                                                          アノテーションは {@link AnnotationTokens}
 *   D  pkg  typeFqn  method  paramSig  declLine  hasBody(1/0)  mods  アノテーション
 *                                                             {@link MethodDeclFact}
 *   V  typeFqn  fieldName  mods  declType  アノテーション      {@link FieldDeclFact}
 *   K  typeFqn  name  種別(V=値/H=ハッシュ)  値                 {@link ConstantFact}。このファイルが宣言する
 *                                                          コンパイル時定数（static final の値と注釈の
 *                                                          メンバの既定値）。定数の値は使う側に焼き込まれる
 *                                                          ので、差分更新で「値が変わった」を知るために持つ
 *   J  typeFqn  fieldName  site  origin                       {@link FieldAssignFact}
 *   C  caller(4列)  callee(4列)  callLine  calleeMods  recvKey  recvKind  recvOrigin  argOrigins  lambda  guard
 *                                                             {@link CallEdgeFact}。guard は呼び出し箇所を
 *                                                             囲む条件分岐（{@link Guard}）
 *   R  pkg  typeFqn  method  paramSig  origin                  {@link ReturnFact}
 *   M  line  caller(4列)  ifaceTypeFqn#method(paramSig)  kind   {@link FunctionalImplFact}
 *   X  callerMethodキー  scopeKey  種別  値                     {@link HintFact}（フェーズAが拾った証拠）
 *   U  line  caller(4列)  expr  reason  candidate  recvKey  recvKind  recvOrigin  argOrigins  lambda  guard
 *                                                             {@link UnresolvedCallFact}
 *   Z  ブロック数                                              最終行。ここまで書き終えた印
 *                                                          （{@link #trailerFor}）。これが無い・数が合わない
 *                                                          キャッシュは途中で切れているとみなして捨てる
 * </pre>
 *
 * <h2>行の種別と列（dataflow-cache.tsv）</h2>
 * F 行と Z 行の意味と並びは analysis-cache.tsv と同じ（同じブロックが同じ順で並び、同じ数になる）。
 * <pre>
 *   F  相対パス  サイズ  エラー数  内容ハッシュ                （ファイルのブロックの先頭）
 *   A  line  caller(4列)  ownerTypeFqn  fieldName  access  mods  lambda   {@link FieldAccessFact}。
 *                                                          フィールドの参照箇所（読み取り・書き込み。
 *                                                          他の型のフィールドも含む）
 *   Z  ブロック数                                              最終行。analysis 側と同じ数でなければ
 *                                                          対になっていないとみなして両方を捨てる
 * </pre>
 * caller(4列) は pkg, typeFqn, method, paramSig（{@link MethodRef}）。
 * F行が現れるたびに、以降の行はそのファイルに属する。I行はF行の直後に置く。
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
 * 再利用の判定は「F行のサイズと内容ハッシュが一致する」に加えて
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
 * フェーズAの拡張（{@link jche.extension.CallSiteHintCollector}）はキャッシュに X 行を書くので、
 * その拡張とその設定・実装ファイルの指紋もヘッダ行に入れる（{@link jche.config.Config#hintPluginFingerprint}）。
 *
 * <h2>バージョン（{@link #VERSION} / {@link #DATAFLOW_VERSION}）を上げる基準</h2>
 * 事実の意味・列・収集範囲が変わったときだけ上げる（全件再解析になる）。
 * 読み手だけの変更（解決ラベル、CSVの列、フィルタ、文言）では上げない。
 * 2 つは独立に上げられる。データフロー側の事実を足すときは {@link #DATAFLOW_VERSION} だけを上げればよく、
 * 呼び出し階層の出力は変わらない（再解析は起きるが、出力とその期待値は動かない）。
 *
 * <h2>事実の収集範囲（書き手の打ち切り。変えたらバージョンを上げる）</h2>
 * <ul>
 *   <li>実引数の出所は入れ子にしない（1段のみ）。レシーバの出所は3段まで入れ子にする
 *       （{@link Origin#MAX_RECEIVER_DEPTH}。invoke ← getMethod ← forName / getClass の連鎖のため）</li>
 *   <li>外側スコープの変数の出所は、final または実質 final のときだけ持ち込む</li>
 *   <li>ローカル変数の出所の先読みは1回（後方で宣言された変数への別名付けは U）</li>
 *   <li>文字列リテラルの出所は、完全修飾クラス名の形か識別子の形（64文字以内）のものだけ
 *       （クラス名とメソッド名を追うため。ログ文言やSQLは残さない）</li>
 *   <li>プリミティブ・配列・String を返す return は記録しない</li>
 *   <li>フィールドへの代入は、その型自身のメソッド・コンストラクタ本体とフィールド初期化子から拾う
 *       （インスタンス初期化ブロックと内部クラスからの代入は拾わない）</li>
 *   <li>コンストラクタ呼び出しは new / this(...) / super(...) を C 行にする（v10 で super(...) を追加）。
 *       書かれていない暗黙の super() は拾わない</li>
 *   <li>v11 で L 行（依存 jar）とF行のエラー数、ヘッダの jdk を追加</li>
 *   <li>C行・U行に guard（呼び出し箇所を囲む条件分岐。{@link Guard}）を追加し、
 *       コンパイル時定数の値を出所（{@link Origin#CONST}）として記録するようにした（v14）。
 *       「その経路では呼ばれない」と言い切れる呼び出しを読み手が見分けるため</li>
 *   <li>H 行の親型は、jar の型を経由して到達するソース上の親型も含める（v12）。
 *       jar の基底クラスがソースのインターフェースを実装している構成で、その子を CHA の候補に入れるため</li>
 *   <li>H 行・D 行・V 行にアノテーションを持つ（v13。{@link AnnotationTokens}）。選別はせず、
 *       付いているものを宣言順に全部残す。値は単一メンバと value / name の文字列だけ。
 *       どのアノテーションに意味があるかは読み手の判断
 *       （{@link jche.graph.SpringBeans} / {@link jche.framework.GeneratedImpl}）</li>
 *   <li>F 行と L 行の末尾に内容ハッシュの列を足した（v13 のまま。事実の意味は変わらないので
 *       バージョンは上げていない）</li>
 *   <li>T 行（解析開始時のソース一覧の指紋）と Z 行（最後まで書き終えた印）を足した（v15）。
 *       T 行は中断した実行からの引き継ぎの判定に、Z 行は途中で切れたキャッシュを見分けるのに使う</li>
 *   <li>K 行（このファイルが宣言するコンパイル時定数の値）を足した（v15。{@link ConstantFact}）。
 *       定数の値は使う側のファイルに焼き込まれるので、差分更新で取りこぼさないよう宣言側にも残す。
 *       あわせて、行形式を壊す値（タブ・改行を含む文字列定数、複数行の注釈の値）は事実として
 *       拾わないことにした。以前はそのまま書いていたため行が割れ、以降の呼び出しが読めなくなっていた</li>
 *   <li>F 行から更新時刻の列を落とし、L 行を「パスと指紋」に置き換えた（v16）。
 *       同一性をパス・サイズ・内容で統一したため（上の「同一性」。docs/cache-identity-qa.md）</li>
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
     * analysis-cache.tsv の形式。変更した場合はここを上げる。旧キャッシュは自動的に破棄される。
     * 上げるのは「事実の意味・列・収集範囲」が変わったときだけ。
     * 読み手だけの変更（解決ラベル、CSVの列、フィルタ、文言）では上げない。
     *
     * v17 で A 行（フィールドの参照箇所）を dataflow-cache.tsv に移した
     */
    public static final String VERSION = "jche-cache-v17";

    /**
     * dataflow-cache.tsv の形式。analysis-cache.tsv とは独立に上げられる。
     * サイドカーのための事実を足すときはこちらだけを上げればよく、
     * 呼び出し階層の出力（{@link #VERSION} の側）は影響を受けない
     */
    public static final String DATAFLOW_VERSION = "jche-dataflow-v1";

    /**
     * ヘッダの最後に付ける世代の印。2 つのキャッシュが同じ実行で書かれたことを表す。
     * 形式の互換性（版・ソースレベル・文字コード・JDK）とは別の軸なので、
     * ヘッダの突き合わせではこの項目を外して比べる（{@link #compatibilityPartOf}）
     */
    public static final String GENERATION_PREFIX = "gen=";

    // 行の種別（各行の先頭1文字）
    public static final char ROW_SOURCES = 'T';
    public static final char ROW_LIBRARY = 'L';
    public static final char ROW_FILE = 'F';
    public static final char ROW_DEPENDENCIES = 'I';
    public static final char ROW_TYPE = 'H';
    public static final char ROW_METHOD_DECL = 'D';
    public static final char ROW_FIELD_DECL = 'V';
    public static final char ROW_CONSTANT = 'K';
    /** dataflow-cache.tsv 側の行（analysis-cache.tsv には書かない） */
    public static final char ROW_FIELD_ACCESS = 'A';
    public static final char ROW_FIELD_ASSIGN = 'J';
    public static final char ROW_CALL = 'C';
    public static final char ROW_RETURN = 'R';
    public static final char ROW_FUNCTIONAL_IMPL = 'M';
    public static final char ROW_HINT = 'X';
    public static final char ROW_UNRESOLVED = 'U';
    public static final char ROW_END = 'Z';

    private CacheFormat() {
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
     */
    public static String headerFor(String sourceLevel, String sourceEncoding,
                                   String hintPluginFingerprint) {
        String header = VERSION + SEP + "source=" + sourceLevel
                + SEP + "enc=" + sourceEncoding
                + SEP + "jdk=" + System.getProperty("java.specification.version", "?");
        // フェーズAの拡張を使っていないときは足さない。拡張を使わない利用者のキャッシュを、
        // この項目の追加だけで捨てさせないため
        return hintPluginFingerprint.isEmpty() ? header : header + SEP + "hints=" + hintPluginFingerprint;
    }

    /**
     * dataflow-cache.tsv の1行目（互換性の部分）。
     *
     * ソースレベル・文字コード・実行 JDK は analysis-cache.tsv と同じ理由で入れる
     * （同じソースでも解析結果が変わる）。フェーズAの拡張の指紋は入れない。
     * X 行は analysis-cache.tsv 側にあるため
     */
    public static String dataflowHeaderFor(String sourceLevel, String sourceEncoding) {
        return DATAFLOW_VERSION + SEP + "source=" + sourceLevel
                + SEP + "enc=" + sourceEncoding
                + SEP + "jdk=" + System.getProperty("java.specification.version", "?");
    }

    /**
     * ヘッダ行に世代の印を足す。2 つのキャッシュには同じ値を書く。
     *
     * @param header     {@link #headerFor} か {@link #dataflowHeaderFor} が返した互換性の部分
     * @param generation この実行の世代（{@link #newGeneration}）
     */
    public static String withGeneration(String header, String generation) {
        return header + SEP + GENERATION_PREFIX + generation;
    }

    /**
     * ヘッダ行から世代の印を外した部分。形式の互換性を突き合わせるのに使う。
     * 旧いキャッシュ（世代の印が無い）はそのまま返るので、版が違うとして捨てられる
     */
    public static String compatibilityPartOf(String header) {
        int at = header.lastIndexOf(SEP + GENERATION_PREFIX);
        return (at < 0) ? header : header.substring(0, at);
    }

    /**
     * ヘッダ行の世代の印。無ければ空文字。
     *
     * 空文字は「世代が分からない」であり、2 つのキャッシュの世代が両方とも空文字でも
     * 一致とはみなさない（{@code CacheUpdater} が明示的に弾く）
     */
    public static String generationOf(String header) {
        int at = header.lastIndexOf(SEP + GENERATION_PREFIX);
        return (at < 0) ? "" : header.substring(at + 1 + GENERATION_PREFIX.length());
    }

    /**
     * この実行の世代。2 つのキャッシュが同じ実行で書かれたことを表すだけなので、
     * 内容から導く必要はなく、重複しなければよい（乱数）。
     * 内容から導くと書き出す前に全体を読む必要があり、ストリーミングで書けなくなる
     */
    public static String newGeneration() {
        return String.format("%016x", new SecureRandom().nextLong());
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
     * 2 つのキャッシュそれぞれの最終行に書き、数が食い違えば対になっていないとみなす。
     */
    public static String trailerFor(long blocks) {
        return ROW_END + SEP + blocks;
    }

    /** 行の先頭1文字（種別）。空行なら '\0' */
    public static char rowTypeOf(String line) {
        return line.isEmpty() ? '\0' : line.charAt(0);
    }

    /** 1行をタブで分割する。末尾の空列も落とさない */
    public static String[] columnsOf(String line) {
        return line.split(SEP, -1);
    }

    /** 指定位置の列。無ければ空文字（後ろに列が足された旧形式を許容するため） */
    public static String columnAt(String[] cols, int index) {
        return (index < cols.length) ? cols[index] : "";
    }

    /**
     * タブ・改行が値に混ざると形式が壊れるため除去する。
     *
     * {@link Guard} のアトム区切り（{@code \u0001}〜{@code \u0003}）は行を壊さないので残す。
     */
    public static String clean(String s) {
        if (s == null) {
            return "";
        }
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    /**
     * dataflow-cache.tsv の値を、行形式を壊さない形に符号化する（逆は {@link #unescape}）。
     *
     * <h4>analysis 側の {@link #clean} との違い</h4>
     * analysis 側はタブ・改行を空白へ<b>置き換えて捨てる</b>。呼び出し階層の出力に使う値は
     * 識別子やクラス名で、制御文字が混ざるのは異常なケースだけなので、落として構わない。
     * dataflow 側は SQL やログ文言のような<b>長さも中身も選べない文字列を、そのまま持つ</b>のが目的なので、
     * 捨てるのではなく符号化する。だから 2 つのキャッシュで規則が違ってよく、
     * 分けたこと自体がこの違いを許している（{@code docs/cache-split-qa.md}）。
     *
     * <h4>規則</h4>
     * <pre>
     *   \      ->  \\
     *   タブ    ->  \t
     *   LF      ->  \n
     *   CR      ->  \r
     *   その他の制御文字（U+0000〜U+001F と U+007F）  ->  &#92;uXXXX（小文字の16進4桁）
     * </pre>
     * それ以外の文字はそのまま。非 ASCII は UTF-8 のまま書くので符号化しない。
     * {@link Guard} が区切りに使う {@code U+0001}〜{@code U+0003} も「その他の制御文字」として
     * 符号化されるので、ガードを dataflow 側へ移しても値と区切りが衝突しない。
     *
     * <p>符号化した結果にタブ・改行・制御文字は残らない（{@link #hasControlChar} が false になる）。
     * したがって {@link #joinRow} を通しても {@link #clean} に何も削られない。
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
        }
        return -1;
    }

    /**
     * {@link #escape} の逆。
     *
     * 規則にない並び（{@code \x} のような、符号化では作られない形）は、文字どおり
     * バックスラッシュと次の文字として返す。手で編集された・壊れたキャッシュでも例外にせず、
     * 読めるところまで読む（キャッシュが壊れていれば、どうせブロックの突き合わせで捨てられる）。
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
     * 行形式を壊す文字（タブ・改行のほか、{@link Guard} が区切りに使う制御文字）を含むか。
     *
     * 事実を<b>作る側</b>が「この値は持たない」と判断するために使う。書き出すときに
     * {@link #clean} で空白へ置き換えるだけだと、{@code '\t'} と {@code ' '} が
     * 同じ値になって条件の判定を誤りうるため、値そのものを拾わない方に倒す
     * （{@code jche.analysis.OriginTracker} / {@link AnnotationTokens}）。
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
     * 列の値は必ず {@link #clean} を通す。ここが行を書き出す唯一の入口なので、
     * 値を作る側の取りこぼし（ソース由来の文字列にタブや改行が混ざる）が
     * そのまま行の破壊にならないよう、最後の関所としてここで落とす。
     */
    public static String joinRow(String... cols) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols.length; i++) {
            if (i > 0) {
                sb.append(SEP);
            }
            sb.append(clean(cols[i]));
        }
        return sb.toString();
    }
}
