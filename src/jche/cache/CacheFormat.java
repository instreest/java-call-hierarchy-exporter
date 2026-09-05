/*
 * Copyright 2026 the java-call-hierarchy-exporter authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jche.cache;

/**
 * キャッシュファイルの形式（タブ区切り。外部ライブラリ不要でデバッグしやすい）。
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
 *   L  jarのパス  サイズ  更新時刻  パッケージ(カンマ区切り)    {@link LibraryFact}。ヘッダ行の直後に
 *                                                          クラスパス順で並ぶ。解析時の依存 jar
 *   F  相対パス  更新時刻  サイズ  エラー数                     （ファイルのブロックの先頭）。エラー数は
 *                                                          JDT が報告したエラーの件数（解決が不完全な印）
 *   I  依存する型（カンマ区切り）                             このファイルのバインディング解決が参照した型の
 *                                                          FQNと、import 文の型（オンデマンド import は
 *                                                          "pkg.*"）。自分が宣言する型は含まない。差分更新時に、
 *                                                          これらの型を宣言するファイルが変わっていたら
 *                                                          再解析する（{@link jche.analysis.CacheUpdater} 参照）
 *   H  typeFqn  kind(I=IF/A=抽象/C=具象)  親型(カンマ区切り)  pkg    {@link TypeFact}
 *   D  pkg  typeFqn  method  paramSig  declLine  hasBody(1/0)  mods   {@link MethodDeclFact}
 *   V  typeFqn  fieldName  mods  declType                    {@link FieldDeclFact}
 *   A  line  caller(4列)  ownerTypeFqn  fieldName  access  mods  lambda   {@link FieldAccessFact}
 *   J  typeFqn  fieldName  site  origin                       {@link FieldAssignFact}
 *   C  caller(4列)  callee(4列)  callLine  calleeMods  recvKey  recvKind  recvOrigin  argOrigins  lambda
 *                                                             {@link CallEdgeFact}
 *   R  pkg  typeFqn  method  paramSig  origin                  {@link ReturnFact}
 *   M  line  caller(4列)  ifaceTypeFqn#method(paramSig)  kind   {@link FunctionalImplFact}
 *   X  callerMethodキー  scopeKey  種別  値                     {@link HintFact}（フェーズAが拾った証拠）
 *   U  line  caller(4列)  expr  reason  candidate  recvKey  recvKind  recvOrigin  argOrigins  lambda
 *                                                             {@link UnresolvedCallFact}
 * </pre>
 * caller(4列) は pkg, typeFqn, method, paramSig（{@link MethodRef}）。
 * F行が現れるたびに、以降の行はそのファイルに属する。I行はF行の直後に置く。
 *
 * <h2>読み手の責務（キャッシュに入れない判断）</h2>
 * <ul>
 *   <li>静的束縛の判定（calleeMods → 種別）            … jche.graph.BindKind</li>
 *   <li>戻り値の集約（追跡できない return が1つでもあれば不定） … jche.graph.DataflowResolver</li>
 *   <li>コンストラクタ注入フィールドの判定（private/final、全コンストラクタで代入、出所が一致）
 *                                                      … jche.graph.FieldFacts</li>
 *   <li>import 推定（U の candidate）をエッジとして採用するか … jche.graph.CallGraphBuilder</li>
 *   <li>ラムダ内の呼び出しの計上先                     … jche.graph.CallGraphBuilder（現状は囲みメソッド）</li>
 *   <li>未解決の理由コードの文言                       … jche.report.UnresolvedReport</li>
 * </ul>
 *
 * <h2>差分更新と依存</h2>
 * 再利用の判定は「更新時刻とサイズが一致する」に加えて「I行の型を宣言するファイルが
 * どれも変わっていない」。呼び出し先・フィールドの所有型・修飾子・親型はバインディング解決の
 * 結果であり、別のファイルを変えると変わりうるため（{@link jche.analysis.CacheUpdater} 参照）。
 * 依存 jar も同じ理由で解決結果を左右するので、L行と突き合わせて追加・変更・削除を検知し、
 * その jar のパッケージの型を参照するファイル（I行）と、型解決に失敗していたファイル
 * （F行のエラー数、U行の BINDING_FAILED）を解析し直す。
 * 実行中の JDK もブートクラスパスとして解決に加わるため、ヘッダ行に含めて丸ごと突き合わせる。
 *
 * <h2>バージョン（{@link #VERSION}）を上げる基準</h2>
 * 事実の意味・列・収集範囲が変わったときだけ上げる（全件再解析になる）。
 * 読み手だけの変更（解決ラベル、CSVの列、フィルタ、文言）では上げない。
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
     * 形式を変更した場合はここを上げる。旧キャッシュは自動的に破棄される。
     * 上げるのは「事実の意味・列・収集範囲」が変わったときだけ。
     * 読み手だけの変更（解決ラベル、CSVの列、フィルタ、文言）では上げない
     */
    public static final String VERSION = "jche-cache-v11";

    // 行の種別（各行の先頭1文字）
    public static final char ROW_LIBRARY = 'L';
    public static final char ROW_FILE = 'F';
    public static final char ROW_DEPENDENCIES = 'I';
    public static final char ROW_TYPE = 'H';
    public static final char ROW_METHOD_DECL = 'D';
    public static final char ROW_FIELD_DECL = 'V';
    public static final char ROW_FIELD_ACCESS = 'A';
    public static final char ROW_FIELD_ASSIGN = 'J';
    public static final char ROW_CALL = 'C';
    public static final char ROW_RETURN = 'R';
    public static final char ROW_FUNCTIONAL_IMPL = 'M';
    public static final char ROW_HINT = 'X';
    public static final char ROW_UNRESOLVED = 'U';

    private CacheFormat() {
    }

    /**
     * キャッシュの1行目。形式のバージョンに加えてソースレベルと実行中の JDK も入れる。
     *
     * 同じソースでも、どの言語バージョンとして解析したかで結果が変わる
     * （古いレベルだと新しい構文が解析できず、呼び出しが抜ける）。
     * JDT は実行中の JVM のブートクラスパスを解析対象のクラスパスに含めるため、
     * JDK の版が変わると標準 API の解決結果も変わりうる。
     * 更新時刻とサイズだけを見ていると、設定や実行環境を変えたのに古い結果を
     * 再利用してしまうため、1行目に含めて丸ごと突き合わせる。
     */
    public static String headerFor(String sourceLevel) {
        return VERSION + SEP + "source=" + sourceLevel
                + SEP + "jdk=" + System.getProperty("java.specification.version", "?");
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

    /** タブ・改行が値に混ざると形式が壊れるため除去する */
    public static String clean(String s) {
        if (s == null) {
            return "";
        }
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    public static String joinRow(String... cols) {
        return String.join(SEP, cols);
    }
}
