// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.cache;

/**
 * 式の「出所」。データフロー解析で具象クラスを特定するための最小の表現。
 *
 * {@link RecvKind} が「絞れなかった理由の説明」なのに対し、こちらは
 * 「追跡するための材料」。書き手の中で1つの文字列に詰めて扱う（キャッシュへはこの形では書かず、
 * 値グラフのノードとして書く。下の「書き手の中だけで使う」）。
 * <pre>
 *   T:jp.co.xxx.UserDaoImpl       new された具象型（その場で確定）
 *   A:2                           囲みメソッドの3番目の引数（呼び出し元まで遡って初めて分かる）
 *   M:jp.co.xxx.Factory#create()  メソッドの戻り値（その宣言のreturnを見れば分かる）
 *   F:jp.co.xxx.Service#dao       フィールド変数
 *   L:jp.co.xxx.UserDaoImpl       文字列リテラル（またはコンパイル時定数）
 *   V:false                       コンパイル時定数の値（条件分岐の判定に使う）
 *   Z:jp.co.xxx.App#lambda$run$0()  ラムダ／メソッド参照が実装しているメソッド
 *                                 （レシーバを束縛したメソッド参照 dao::describe は |r=レシーバの出所 付き）
 *   E:0                           ラムダが捕捉した、囲みメソッドの1番目の引数
 *   C:0                           Class.forName(引数) で名前指定された型
 *   K:jp.co.xxx.UserDaoImpl       クラスオブジェクト（X.class）
 *   U                             追跡できない
 * </pre>
 * 種別ごとに「次にどこを見れば確定するか」が違うので、種別を分けている。
 *
 * <h2>実引数リスト</h2>
 * new やメソッド呼び出しの出所には、その呼び出しの実引数の出所も付ける。
 * コンストラクタ注入されたフィールドや、クラス名の文字列を受け取るファクトリを追うのに必要なため。
 * <pre>
 *   T:jp.co.Service|0=T:jp.co.UserDaoImpl
 *   M:jp.co.Factory#create(java.lang.String)|0=L:jp.co.UserDaoImpl
 *   M:java.lang.Class#getMethod(java.lang.String,java.lang.Class[])|n=2;0=L:run;1=K:long;r=K:jp.co.X
 * </pre>
 * '(' ではなく '|' で区切るのは、値の側（メソッドキー）が既に括弧を含んでいて、
 * 括弧だと対応の判定が必要になるから。'|' はFQNにもメソッドキーにも現れない。
 *
 * リストの要素は "位置=出所" のほか、次の2つ:
 * <pre>
 *   n=実引数の数     … 出所が分からず省いた引数と、引数が無いことを区別するため
 *   r=レシーバの出所 … メソッド呼び出しの受け手。invoke ← getMethod ← forName のような
 *                      連鎖を読み手が辿るため。入れ子の出所が自身の実引数リストを
 *                      持つ場合は {} で囲む
 *   s=書かれた型     … 呼び出しをソースに書いたときのレシーバの型。宣言元と違うときだけ
 * </pre>
 *
 * <h2>書き手の中だけで使う</h2>
 * この文字列を作るのは書き手（{@code jche.analysis.OriginTracker}）だけで、形には上限がある
 * （実引数は1段、レシーバは {@link #MAX_RECEIVER_DEPTH} 段。{@code {}} で囲んで入れ子にする）。
 * キャッシュの値（呼び出し箇所・{@code R} 行・{@code J} 行・条件）はもうこの形では書かず、値グラフのノードを指す
 * （{@code docs/cache-split-qa.md} の Q22、{@link CacheFormat} の「値はノードで持つ」）。
 * 書き手に残っているのは、ローカル変数の表で代入の食い違いを見る判定と、値グラフの葉の判定のため。
 *
 * <p>読み手はこの文字列を読まない。キャッシュの値グラフを値の表（{@code jche.graph.ValueStore}）に取り込み、
 * 種別・値・実引数・レシーバを列で引く。だから値が文法の区切り（{@code | ; { }}）を含んでも読み違えない
 * （以前は読み手も出所の文字列を組み直して解析していた。{@code docs/cache-unification-qa.md} の「読み手が値の表を読む」）。
 * このクラスに残るのは、種別の文字（読み手もノードの種別として使う）と、書き手が文字列を作り・頭を取るための
 * 道具だけ。人が読むための出力（{@link CacheDump}）も同じ文法で書く。
 */
public final class Origin {

    /** new された具象型。値はFQN */
    public static final char NEW = 'T';
    /** 囲みメソッドの引数。値は0始まりの引数位置 */
    public static final char PARAM = 'A';
    /** メソッドの戻り値。値はメソッドキー（typeFqn#method(params)） */
    public static final char RETURN = 'M';
    /** フィールド変数。値は typeFqn#fieldName */
    public static final char FIELD = 'F';
    /** 文字列リテラル（またはコンパイル時定数）。値はその文字列 */
    public static final char LITERAL = 'L';
    /** Class.forName(引数) で名前指定された型。値は0始まりの引数位置 */
    public static final char REFLECT = 'C';
    /** クラスオブジェクト（X.class）。値は型のFQN（配列は "[]" 付き、プリミティブはそのまま） */
    public static final char CLASS = 'K';
    /**
     * コンパイル時定数の値（真偽値・数値・文字・文字列・列挙定数の名前）。値はその表記そのもの。
     *
     * {@link #LITERAL} がクラス名・メソッド名を追うための「識別子の形の文字列」なのに対し、
     * こちらは「条件分岐の判定に使える値」。{@code if (flag)} の flag に何が渡ったかを
     * 経路ごとに突き合わせるために持つ（jche.graph.GuardEvaluator）。
     */
    public static final char CONST = 'V';
    /**
     * 関数型インターフェースの実装として渡された、ラムダ式かメソッド参照。
     * 値は実際に動くメソッドのキー（{@code typeFqn#name(paramSig)}）。
     *
     * ラムダなら本体を持つ合成メソッド、メソッド参照なら参照先のメソッドそのもの。
     * 具象「型」ではなく具象「メソッド」が決まる唯一の出所なので、
     * 読み手はここだけ型を経由せずにメソッドIDを引く（jche.graph.DataflowResolver）。
     */
    public static final char FUNCTIONAL = 'Z';
    /**
     * ラムダ式が捕捉した、囲みメソッドの引数。値は0始まりの引数位置。
     *
     * ラムダの本体は合成メソッド（{@code lambda$...}）に計上するので、その中から見ると
     * 捕捉した変数は「自分の引数」ではない。{@link #PARAM} のまま持ち込むと合成メソッド自身の
     * 引数を誤って当てるため、種別を分けて「1つ外のフレームの引数」だと分かるようにする。
     * 読み手は、ラムダを生成した箇所の辺を降りるときに、そのフレームの引数を
     * 捕捉した値として渡す（jche.report.StreamingTreeWalker）。
     */
    public static final char CAPTURED = 'E';
    /** 追跡できない。「分からない」を明示的に持つのが重要 */
    public static final char UNKNOWN = 'U';

    public static final String UNKNOWN_S = "U";

    /** 実引数リストの区切り */
    public static final char ARGS = '|';
    public static final String RECEIVER = "r";
    public static final String ARG_COUNT = "n";
    /**
     * 呼び出しを<b>ソースに書いたときのレシーバの型</b>（FQN）。宣言元と違うときだけ付く。
     *
     * {@code DaoFactory.get(...)} の {@code get} が親の {@code BaseFactory} で宣言されていると、
     * メソッドキーは親になる。契約表や拡張で「ソースに書いてある型」を指定できるように、
     * 書かれた型も持つ（{@link jche.graph.FactoryCalls}）
     */
    public static final String STATIC_RECV = "s";
    /**
     * 書き手（{@code jche.analysis.OriginTracker}）が出所の文字列の中でレシーバの出所を何段まで入れ子にするか
     * （invoke ← getMethod ← forName/getClass で3段）。キャッシュへ書く値グラフ（{@code jche.analysis.ValueGraph}）
     * はこの上限に掛からない
     */
    public static final int MAX_RECEIVER_DEPTH = 3;

    /** {@link #isNameShaped} が名前の形とみなす文字列の長さの上限 */
    public static final int MAX_NAME_LENGTH = 64;

    private Origin() {
    }

    /**
     * 文字列が「完全修飾クラス名の形」か「識別子の形」か（{@link #MAX_NAME_LENGTH} 文字以内）。
     *
     * クラス名は「ドットを含み、各要素が識別子で、最後の要素が英大文字で始まる」、
     * メソッド名・フィールド名は「識別子1つ」。書き手（{@code jche.analysis.OriginTracker}）が
     * 文字列リテラルを出所（{@link #LITERAL}）として残すのはこの形のものだけで、
     * この形の値は出所の文法の区切り（{@code | ; { } =}）を含まない
     */
    public static boolean isNameShaped(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_NAME_LENGTH) {
            return false;
        }
        if (value.indexOf('.') < 0) {
            // 識別子の形。getMethod("run") のようにメソッド名として渡されるもの
            if (!Character.isJavaIdentifierStart(value.charAt(0))) {
                return false;
            }
            for (int i = 1; i < value.length(); i++) {
                if (!Character.isJavaIdentifierPart(value.charAt(i))) {
                    return false;
                }
            }
            return true;
        }
        int last = 0;
        for (int i = 0; i <= value.length(); i++) {
            if (i < value.length() && value.charAt(i) != '.') {
                char c = value.charAt(i);
                if (!Character.isJavaIdentifierPart(c) && c != '$') {
                    return false;
                }
                continue;
            }
            if (i == last) {
                return false;   // 空の要素（先頭・末尾・連続するドット）
            }
            if (!Character.isJavaIdentifierStart(value.charAt(last))) {
                return false;
            }
            last = i + 1;
        }
        int dot = value.lastIndexOf('.');
        return Character.isUpperCase(value.charAt(dot + 1));
    }

    public static boolean isUnknown(String origin) {
        return origin == null || origin.isEmpty() || origin.charAt(0) == UNKNOWN;
    }

    public static char kindOf(String origin) {
        return isUnknown(origin) ? UNKNOWN : origin.charAt(0);
    }

    /** 入れ子の境界（{...}）の外側で最初に現れる文字の位置。無ければ -1 */
    private static int indexAtTop(String s, char ch, int from) {
        int depth = 0;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                if (depth > 0) {
                    depth--;
                }
            } else if (c == ch && depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** "T:jp.co.X|0=..." の "jp.co.X" の部分（実引数リストは含まない） */
    public static String valueOf(String origin) {
        int i = (origin == null) ? -1 : origin.indexOf(':');
        if (i < 0) {
            return "";
        }
        int bar = indexAtTop(origin, ARGS, i);
        return (bar < 0) ? origin.substring(i + 1) : origin.substring(i + 1, bar);
    }

    /** 実引数リストを落とした形。引数の出所を入れ子にしないために使う */
    public static String head(String origin) {
        int bar = (origin == null) ? -1 : indexAtTop(origin, ARGS, 0);
        return (bar < 0) ? origin : origin.substring(0, bar);
    }

    public static String of(char kind, String value) {
        return kind + ":" + value;
    }

    public static String of(char kind, String value, String args) {
        return (args == null || args.isEmpty())
                ? of(kind, value) : (kind + ":" + value + ARGS + args);
    }

    /** リストの要素として入れ子にする形。自身が実引数リストを持つなら {} で囲む */
    public static String nest(String origin) {
        return (origin.indexOf(ARGS) < 0) ? origin : "{" + origin + "}";
    }
}
