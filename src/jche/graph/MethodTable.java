// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import jche.cache.MethodRef;

/**
 * メソッドを int の ID に内部化する表。
 *
 * 保持するのはメソッドごとに文字列2本（キーとパッケージ名）と、
 * 宣言ファイル・宣言行・ファイルの中の宣言の順番など。型名・メソッド名はキーから切り出せるので持たない。
 * キー形式: typeFqn#methodName(paramSig)
 *
 * <p>ID は初めて ID 化した順に振られる。その順はキャッシュ上のブロックの並び（差分更新で解析し直した
 * ファイルが先頭へ移る）と、どの行が先にそのメソッドを指したかで変わるので、<b>出力の並びや
 * 「どれを採るか」の決め手に ID を使わない</b>。同じ宣言行に並ぶものの前後は
 * {@link #compareDeclarationOrder} で決める（{@code docs/deterministic-row-order-qa.md} の Q14）。
 */
public final class MethodTable {

    private final HashMap<String, Integer> idByKey = new HashMap<>(1 << 16);
    private final ArrayList<String> keys = new ArrayList<>();
    private final ArrayList<String> pkgs = new ArrayList<>();

    /** 宣言情報（ソースがあるメソッドのみ設定される） */
    private final ArrayList<String> declFiles = new ArrayList<>();
    private final IntArray declLines = new IntArray(1 << 16);
    /** 宣言の終了行。分からなければ declLine と同じ値（＝その行だけの範囲） */
    private final IntArray declEndLines = new IntArray(1 << 16);
    /**
     * ファイルの中での宣言の順番（ブロックの D 行の並びの位置。0 始まり）。ソースが無ければ -1。
     * D 行は AST を訪ねた順に並ぶので、同じソースからは必ず同じ値になる。同じ行に書かれた
     * メソッドどうしはソースの並び、入れ子（メソッドとその中のラムダ・匿名クラス）は外側が先
     */
    private final IntArray declOrdinals = new IntArray(1 << 16);
    /**
     * 本体を持つか。既定はtrue（＝候補になりうる）。
     * D行が無いメソッド（jar内のメソッド等）はソースが無く展開もできないため、
     * 安全側に倒して候補から落とさない。
     */
    private final ArrayList<Boolean> hasBody = new ArrayList<>();
    /** 宣言に付いていたアノテーション（{@link jche.cache.AnnotationTokens}）。無ければ空 */
    private final ArrayList<String> annotations = new ArrayList<>();
    /** 宣言の修飾子（{@link jche.cache.ModifierTokens}）。無ければ空 */
    private final ArrayList<String> mods = new ArrayList<>();

    /**
     * ラムダ式の本体を持つ合成メソッド（D行の修飾子に lambda が付いたもの）。
     * 名前（{@code lambda$...}）で見分けないのは、同じ名前のメソッドを
     * 人が書くこともできるため。事実（修飾子）で持つ
     */
    private final java.util.BitSet lambdaBodies = new java.util.BitSet();

    /** 引数型略名が衝突しているラベル。初回の displayLabel() で一度だけ作る */
    private Set<String> ambiguous;

    public int intern(MethodRef ref) {
        return intern(ref.pkg(), ref.typeFqn(), ref.name(), ref.paramSig());
    }

    public int intern(String pkg, String typeFqn, String method, String params) {
        String key = typeFqn + "#" + method + "(" + params + ")";
        Integer id = idByKey.get(key);
        if (id != null) {
            return id;
        }
        int newId = keys.size();
        idByKey.put(key, newId);
        keys.add(key);
        pkgs.add(pkg == null ? "" : pkg);
        declFiles.add(null);
        declLines.add(-1);
        declEndLines.add(-1);
        declOrdinals.add(-1);
        hasBody.add(Boolean.TRUE);
        annotations.add("");
        mods.add("");
        return newId;
    }

    /** キーからIDを引く。未登録なら -1 */
    public int idOf(String key) {
        Integer id = idByKey.get(key);
        return (id == null) ? -1 : id;
    }

    /**
     * 宣言を記録する（D 行から）。同じキーの宣言が複数のファイルにあれば後から記録したものが勝つ。
     * 宣言の順番も同じ呼び出しで記録するので、ファイル・行・順番は必ず同じ宣言のものになる
     *
     * @param ordinal ファイルの中での宣言の順番（ブロックの D 行の並びの位置）
     */
    public void setDeclaration(int id, String file, int line, int endLine, boolean body, int ordinal) {
        declFiles.set(id, file);
        declLines.set(id, line);
        declEndLines.set(id, Math.max(line, endLine));
        declOrdinals.set(id, ordinal);
        hasBody.set(id, body);
    }

    public boolean hasBody(int id) {
        return hasBody.get(id);
    }

    /** 宣言のアノテーションと修飾子を記録する（D 行から） */
    public void setDeclarationDetails(int id, String annotationTokens, String modifierTokens) {
        annotations.set(id, (annotationTokens == null) ? "" : annotationTokens);
        mods.set(id, (modifierTokens == null) ? "" : modifierTokens);
    }

    /** 宣言に付いていたアノテーション。無ければ空文字列 */
    public String annotations(int id) {
        return annotations.get(id);
    }

    /** 宣言の修飾子。無ければ空文字列 */
    public String mods(int id) {
        return mods.get(id);
    }

    /** ラムダ式の本体を持つ合成メソッドだと記録する */
    public void markLambdaBody(int id) {
        lambdaBodies.set(id);
    }

    /**
     * 匿名クラス（{@code Outer$1}。入れ子なら {@code Outer$1$2}、その中のローカルクラスは
     * {@code Outer$1$1Local}）のメソッドか。
     *
     * 匿名クラスのメソッドは「その場で親の定義を上書きした処理内容」であって、
     * 他から呼び出せる定義ではないので methods.csv には出さない（呼び出し階層には出る）。
     * 内部クラス・static なネストクラス・ローカルクラス（{@code Outer$1Local}）は
     * 名前を持つ定義なので出す
     */
    public boolean isInAnonymousType(int id) {
        String fqn = typeFqn(id);
        int at = fqn.indexOf('$');
        while (at >= 0 && at + 1 < fqn.length()) {
            int end = at + 1;
            while (end < fqn.length() && Character.isDigit(fqn.charAt(end))) {
                end++;
            }
            // "$" の直後が数字だけで、そこで名前が終わるか次の "$" に続くなら匿名クラス
            if (end > at + 1 && (end == fqn.length() || fqn.charAt(end) == '$')) {
                return true;
            }
            at = fqn.indexOf('$', at + 1);
        }
        return false;
    }

    /**
     * ラムダ式の本体を持つ合成メソッドか。
     * 捕捉した変数を解決するため、読み手はこのメソッドへ降りるときだけ
     * 生成箇所のフレームの引数を渡す
     */
    public boolean isLambdaBody(int id) {
        return lambdaBodies.get(id);
    }

    /** ソース上に宣言があるか（jar内のメソッドには無い） */
    public boolean hasSource(int id) {
        return declFiles.get(id) != null;
    }

    public int size() {
        return keys.size();
    }

    /** キー全体（typeFqn#methodName(paramSig)） */
    public String key(int id) {
        return keys.get(id);
    }

    /** キーのうち "#" 以降（methodName(paramSig)）。同名同引数の照合に使う */
    public String signature(int id) {
        String k = keys.get(id);
        return k.substring(k.indexOf('#') + 1);
    }

    /**
     * キーのうち括弧内（完全修飾の引数型をカンマ区切りにしたもの）。
     *
     * 開き括弧は "#" より後ろから探す。型名に括弧が混ざる可能性があるのは
     * typeNameOf() が最終手段でJDT内部キーを使った場合だけだが、そこで
     * 引数リストの切り出しがずれると別メソッドと同一視されてしまう。
     */
    private String rawParams(int id) {
        String k = keys.get(id);
        return k.substring(k.indexOf('(', k.indexOf('#')) + 1, k.lastIndexOf(')'));
    }

    public String pkg(int id) {
        return pkgs.get(id);
    }

    public String typeFqn(int id) {
        String k = keys.get(id);
        return k.substring(0, k.indexOf('#'));
    }

    public String methodName(int id) {
        String k = keys.get(id);
        return k.substring(k.indexOf('#') + 1, k.indexOf('('));
    }

    public boolean isConstructor(int id) {
        return MethodRef.CONSTRUCTOR.equals(methodName(id));
    }

    /** static 初期化子（合成した {@code <clinit>}）か */
    public boolean isStaticInitializer(int id) {
        return MethodRef.STATIC_INITIALIZER.equals(methodName(id));
    }

    /** クラスの単純名（内部クラスは Outer.Inner の形を保つ） */
    public String simpleTypeName(int id) {
        String t = typeFqn(id);
        String p = pkgs.get(id);
        if (p.isEmpty()) {
            // パッケージが無いので、typeFqn全体がそのままクラスの入れ子構造を表す
            // （lastIndexOf('.')で末尾だけ切り出すと、デフォルトパッケージ上の
            //   内部クラスで外側のクラス名が失われてしまう）
            return t;
        }
        if (t.startsWith(p + ".")) {
            return t.substring(p.length() + 1);
        }
        int i = t.lastIndexOf('.');
        return (i >= 0) ? t.substring(i + 1) : t;
    }

    /**
     * 表示用のメソッド名。
     *
     * コンストラクタは内部的には {@code <init>} だが、ソース上の名前はクラスの単純名。
     * 利用者が読むのはソースなので、表示は単純名に寄せる。
     * 暗黙のデフォルトコンストラクタも、補完されるとクラス名になるので同じ扱い。
     */
    public String displayMethodName(int id) {
        String m = methodName(id);
        if (!MethodRef.CONSTRUCTOR.equals(m)) {
            return m;
        }
        String simple = simpleTypeName(id);
        int dot = simple.lastIndexOf('.');
        return (dot >= 0) ? simple.substring(dot + 1) : simple;
    }

    /** 単純クラス名.表示用メソッド名。call-hierarchy 列と root 列の表記 */
    public String shortLabel(int id) {
        return simpleTypeName(id) + "." + displayMethodName(id);
    }

    /**
     * 単純クラス名 + 表示用メソッド名 + 引数型略名。オーバーロードを識別できる短い表記。
     * 略名が衝突している場合は、別物が同じ表記になるのを避けるため完全修飾の引数に戻す。
     */
    public String shortLabelWithParams(int id) {
        String params = ambiguousLabels().contains(plainDisplayLabel(id))
                ? rawParams(id) : shortParams(id);
        return simpleTypeName(id) + "." + displayMethodName(id) + "(" + params + ")";
    }

    /** 完全修飾クラス名 + 表示用メソッド名 + 完全修飾の引数リスト */
    public String fullSignature(int id) {
        return typeFqn(id) + "." + displayMethodName(id) + "(" + rawParams(id) + ")";
    }

    /**
     * サーバーモード（Eclipse プラグイン）へ返すメソッドの表記。
     * 完全修飾クラス名 + 表示用メソッド名 + 引数型略名。
     *
     * 引数型を略名にするのは読みやすさのためだが、略した結果
     * java.util.List と other.List のように別物が同じ表記になることがある。
     * 「メソッドを識別できる表記にする」のが目的なのでそれでは本末転倒であり、
     * 衝突した組だけ完全修飾の引数リストに戻す（下の ambiguousLabels()）。
     */
    public String displayLabel(int id) {
        String label = plainDisplayLabel(id);
        return ambiguousLabels().contains(label) ? fullSignature(id) : label;
    }

    private String plainDisplayLabel(int id) {
        return typeFqn(id) + "." + displayMethodName(id) + "(" + shortParams(id) + ")";
    }

    /**
     * 引数型略名が衝突しているラベルの集合。
     *
     * 一度だけ全メソッドを走査して作る。走査用のSetは作業後に捨て、
     * 残すのは衝突したラベルだけ（通常は0件）なので、常時のメモリは増えない。
     * キーは重複しないので、同じラベルが2回出た時点で必ず引数が違う。
     */
    private Set<String> ambiguousLabels() {
        if (ambiguous != null) {
            return ambiguous;
        }
        Set<String> seen = new HashSet<>(keys.size() * 2);
        Set<String> dup = new LinkedHashSet<>();
        for (int id = 0; id < keys.size(); id++) {
            String label = plainDisplayLabel(id);
            if (!seen.add(label)) {
                dup.add(label);
            }
        }
        ambiguous = dup;
        return ambiguous;
    }

    /** 完全修飾の引数リストを、型ごとに略名へ置き換えたもの */
    private String shortParams(int id) {
        String raw = rawParams(id);
        if (raw.isEmpty()) {
            return "";
        }
        // 引数型は toRef() で消去済み（getErasure）なので、ジェネリクスの
        // 山括弧が入ることはない。よってカンマで素直に分割できる
        StringBuilder sb = new StringBuilder(raw.length());
        int start = 0;
        while (start <= raw.length()) {
            int comma = raw.indexOf(',', start);
            int end = (comma < 0) ? raw.length() : comma;
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(simpleParamName(raw.substring(start, end)));
            if (comma < 0) {
                break;
            }
            start = comma + 1;
        }
        return sb.toString();
    }

    /**
     * 引数型1つぶんの略名。java.lang.String → String、java.lang.String[] → String[]。
     *
     * 内部クラス（fn.Outer.Inner）は末尾だけを取って Inner になる。名前だけでは
     * どこまでがパッケージでどこからが外側クラスか決められないため（大文字小文字の
     * 慣習に頼ると、その慣習に従っていないコードで誤る）。
     * これで別物が同じ表記になった場合は displayLabel() が完全修飾に戻す。
     */
    private static String simpleParamName(String fq) {
        int arr = fq.indexOf('[');
        String base = (arr < 0) ? fq : fq.substring(0, arr);
        String suffix = (arr < 0) ? "" : fq.substring(arr);
        int dot = base.lastIndexOf('.');
        return ((dot >= 0) ? base.substring(dot + 1) : base) + suffix;
    }

    /** 宣言ファイル（プロジェクトルートからの相対パス）。ソースが無ければ null */
    public String declFile(int id) {
        return declFiles.get(id);
    }

    public int declLine(int id) {
        return declLines.get(id);
    }

    /**
     * ファイルの中での宣言の順番（ブロックの D 行の並びの位置。0 始まり）。ソースが無ければ -1。
     * 同じ行に宣言が並ぶとき（1 行に書いたメソッド、同じ行のラムダ、暗黙のコンストラクタと
     * {@code <clinit>}）の前後を決める
     */
    public int declOrdinal(int id) {
        return declOrdinals.get(id);
    }

    /**
     * 宣言の位置の前後。宣言行 → ファイルの中の宣言の順番 → キーの文字列の順に比べる。
     *
     * <p>同じ行に並ぶ宣言の前後を ID で決めない。ID の振られ方はキャッシュ上のブロックの並びで変わり
     * （戻り値の R 行を持つメソッドは D 行より先に ID 化される。別のファイルから先に呼ばれていれば、
     * そのファイルのブロックで ID 化される）、全件解析と差分更新とで前後が入れ替わる。
     * 宣言の順番はブロックの中で閉じているので、どちらの実行でも同じ値になる。
     * キーは、宣言の順番まで同じとき（別々のファイルの宣言を比べたとき）の最後の決め手
     *
     * @return a が前なら負、後なら正、同じメソッドなら 0
     */
    public int compareDeclarationOrder(int a, int b) {
        int c = Integer.compare(declLines.get(a), declLines.get(b));
        if (c == 0) {
            c = Integer.compare(declOrdinals.get(a), declOrdinals.get(b));
        }
        return (c != 0) ? c : keys.get(a).compareTo(keys.get(b));
    }

    /**
     * 宣言の終了行（本体の閉じ括弧の行）。ソースが無ければ -1。
     * 暗黙のコンストラクタや {@code <clinit>} のように本体が書かれていないものは宣言行と同じ値。
     */
    public int declEndLine(int id) {
        return declEndLines.get(id);
    }

    /**
     * 指定のファイル・行を囲むメソッド。無ければ -1。
     *
     * 入れ子（内部クラス・匿名クラスのメソッド）では、範囲が最も狭いものを採る。
     * 終了行を持っているので、メソッドの外（フィールド宣言や空行）にある行では
     * 直前のメソッドではなく「見つからない」を返す。
     * 範囲の広さが同じもの（1 行に書いたメソッドとその中のラムダ等）は、宣言の位置が先のもの
     * （{@link #compareDeclarationOrder}。同じ行なら先に宣言したもの。入れ子なら外側）を採る。ID では決めない
     *
     * @param file プロジェクトルートからの相対パス（{@link #declFile}と同じ綴り）
     * @param line 1 始まりの行番号
     */
    public int enclosingMethod(String file, int line) {
        int best = -1;
        int bestWidth = Integer.MAX_VALUE;
        for (int id = 0; id < keys.size(); id++) {
            String f = declFiles.get(id);
            if (f == null || !f.equals(file)) {
                continue;
            }
            int start = declLines.get(id);
            int end = declEndLines.get(id);
            if (start < 0 || line < start || line > end) {
                continue;
            }
            int width = end - start;
            if (width < bestWidth
                    || (width == bestWidth && best >= 0 && compareDeclarationOrder(id, best) < 0)) {
                bestWidth = width;
                best = id;
            }
        }
        return best;
    }
}
