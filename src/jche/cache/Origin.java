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
 * 式の「出所」。データフロー解析で具象クラスを特定するための最小の表現。
 *
 * {@link RecvKind} が「絞れなかった理由の説明」なのに対し、こちらは
 * 「追跡するための材料」。1つの文字列に詰めてキャッシュへ書き出す。
 * <pre>
 *   T:jp.co.xxx.UserDaoImpl       new された具象型（その場で確定）
 *   A:2                           囲みメソッドの3番目の引数（呼び出し元まで遡って初めて分かる）
 *   M:jp.co.xxx.Factory#create()  メソッドの戻り値（その宣言のreturnを見れば分かる）
 *   F:jp.co.xxx.Service#dao       フィールド変数
 *   L:jp.co.xxx.UserDaoImpl       文字列リテラル（またはコンパイル時定数）
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
 *                      連鎖を読み手が辿るため、MAX_RECEIVER_DEPTH 段まで入れ子にする。
 *                      入れ子の出所が自身の実引数リストを持つ場合は {} で囲む
 * </pre>
 * 実引数の出所は入れ子にしない（付いていたら剥がす）。段数を増やすほど
 * 「どの推測が結論に効いたか」が追えなくなるうえ、文字列も長くなる。
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
    /** 追跡できない。「分からない」を明示的に持つのが重要 */
    public static final char UNKNOWN = 'U';

    public static final String UNKNOWN_S = "U";

    /** 実引数リストの区切り */
    public static final char ARGS = '|';
    public static final String RECEIVER = "r";
    public static final String ARG_COUNT = "n";
    /** レシーバの出所を何段まで入れ子にするか（invoke ← getMethod ← forName/getClass で3段） */
    public static final int MAX_RECEIVER_DEPTH = 3;

    private Origin() {
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

    /** "T:jp.co.X|0=..." の "0=..." の部分。無ければ null */
    public static String argsOf(String origin) {
        int bar = (origin == null) ? -1 : indexAtTop(origin, ARGS, 0);
        return (bar < 0) ? null : origin.substring(bar + 1);
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

    /** "0=T:jp.co.X;2=A:1" から指定位置の出所を取り出す。無ければ null */
    public static String argAt(String args, int index) {
        return entryAt(args, String.valueOf(index));
    }

    /** メソッド呼び出しの出所から、そのレシーバの出所（r=...）。無ければ null */
    public static String receiverOf(String origin) {
        return entryAt(argsOf(origin), RECEIVER);
    }

    /** メソッド呼び出しの出所から、実引数の数（n=...）。無ければ -1 */
    public static int argCountOf(String origin) {
        String n = entryAt(argsOf(origin), ARG_COUNT);
        if (n == null) {
            return -1;
        }
        try {
            return Integer.parseInt(n);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 実引数リストからキー（位置・r・n）の値を取り出す。{} で囲まれていれば外す。無ければ null */
    public static String entryAt(String args, String key) {
        if (args == null || args.isEmpty()) {
            return null;
        }
        String prefix = key + "=";
        int start = 0;
        while (start <= args.length()) {
            int end = indexAtTop(args, ';', start);
            if (end < 0) {
                end = args.length();
            }
            String entry = args.substring(start, end);
            if (entry.startsWith(prefix)) {
                String v = entry.substring(prefix.length());
                if (v.length() >= 2 && v.charAt(0) == '{' && v.charAt(v.length() - 1) == '}') {
                    v = v.substring(1, v.length() - 1);
                }
                return v;
            }
            if (end >= args.length()) {
                break;
            }
            start = end + 1;
        }
        return null;
    }
}
