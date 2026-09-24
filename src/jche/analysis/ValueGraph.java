// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionMethodReference;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.MethodReference;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.TypeLiteral;

import jche.cache.FileAnalysis;
import jche.cache.MethodRef;
import jche.cache.Origin;
import jche.cache.ValueNode;

/**
 * 1ファイルぶんの値グラフ（{@link ValueNode}）を組み立てる。
 *
 * <h2>{@link OriginTracker} との関係</h2>
 * {@link OriginTracker} は同じ式から「上限付きの出所の文字列」を作る。
 * キャッシュの値（呼び出し箇所のレシーバ・実引数、戻り値の {@code R} 行、フィールドへの代入の {@code J} 行、
 * 条件の subject）を持つのは<b>こちらだけ</b>で、読み手は {@code jche.graph.OriginRenderer} が
 * 読む直前に出所の文字列へ組み直す（{@code docs/cache-split-qa.md} の Q21・Q22）。
 * 前者が残るのは、ローカル変数の表で代入の食い違いを見る判定と、この葉の判定のためだけ。
 *
 * <h2>外れた上限</h2>
 * <pre>
 *   実引数の入れ子   1段のみ            -> 無制限（f(g(h())) の h まで辿れる）
 *   レシーバの入れ子 3段まで            -> 無制限
 *   文字列リテラル   64文字以内で、かつ  -> 無制限。SQL・ログ文言もそのまま持つ
 *                    FQNか識別子の形
 *   定数の値         64文字以内で、かつ  -> 無制限。制御文字は符号化して持つ
 *                    制御文字を含まない
 * </pre>
 * 葉のうち、フィールド・引数の判定は {@link OriginTracker} の結果をそのまま使う
 * （そこはもともと上限に掛からず、同じ判断を2か所に書かないため）。
 * ローカル変数だけは別で、{@link OriginTracker#localNodeOf} でその代入元のノードを直に指す。
 * 出所の文字列に落とすと実引数リストが剥がれてしまい、
 * {@code Class.forName(NAME)} を受けたローカル変数からクラス名が辿れなくなるため。
 *
 * <h2>値として使う式の剥がし方</h2>
 * 括弧と値を変えないキャストだけを剥がす（{@link OriginTracker#unwrapValue}。条件の判定と同じ規則）。
 * 値を変えうるキャスト（{@code (byte) p}）の先は、コンパイル時定数なら JDT が畳んだ値
 * （{@code (byte)300} は 44）、そうでなければ「分からない」にする。浮動小数の定数も持たない
 * （条件の期待値とは表記が揃わず、{@code 1.0} と {@code 1} を別の値と見てしまうため）。
 *
 * <h2>大きさ</h2>
 * 同じ構造のノードは1つにまとめる（{@link ValueNode#dedupeKey}）。入れ子を展開しないので、
 * 大きさはソースの式の数に比例する。1ファイル分を組み立てたら、ブロックとして書き出して捨てる
 * （{@link FileAnalysis} の寿命と同じ）。
 *
 * 同じ式は、それを指す呼び出し箇所がいくつあっても 1 つのノードにする。そのために、
 * 式ごとに作ったノードを控えておき（{@link #built}）、深さの上限（{@link #HARD_CAP}）は
 * 辿り始めた位置からではなく<b>式の木の中での深さ</b>（{@link #depthInTree}）で判定する。
 * 辿り始めた位置から数えると、300 段の連鎖（{@code sb.append(…).append(…)…}）で呼び出し箇所ごとに
 * 違う段で打ち切られ、どれも別のノードになって 1 ファイルで 1 万行を超えていた。
 */
final class ValueGraph {

    /**
     * 式を辿る深さの上限。再帰なのでスタックを守るためだけに置く安全策で、
     * 意味のある上限ではない（実在のコードの式の深さはこれよりはるかに浅い）。
     * {@code jche.dataflow.DataflowBuilder.HARD_CAP} と同じ役割。
     *
     * 深さは式の木の中での深さ（{@link #depthInTree}）で数える。同じ式はどこから辿っても同じ段で
     * 打ち切られるので、打ち切ったノードも 1 つにまとまる。上限より深い式は「分からない」
     * （{@link ValueNode#NONE}）で、呼び出しを落とさない側に倒れる
     */
    private static final int HARD_CAP = 256;

    private final FileAnalysis out;
    private final BindingNames names;
    private final OriginTracker origins;
    /** 同じ構造のノードを1つにまとめる（{@link ValueNode#dedupeKey} -> 番号） */
    private final Map<String, Integer> dedupe = new HashMap<>();
    /**
     * 式（AST のノードそのもの）-> 作ったノードの番号。同じ式を呼び出し箇所ごとに辿り直さない。
     *
     * 式の値はローカル変数の表（{@link OriginTracker.Scope}）に依存するので、表が変わるとき
     * （スコープの出入り）に捨てる（{@link #forgetExpressions}）。本体の先読み（表を作っている途中）と
     * 本体の走査（表ができた後）で同じ控えを使うと、先読みの途中の値を後で使ってしまうため
     */
    private final Map<Expression, Integer> built = new IdentityHashMap<>();

    ValueGraph(FileAnalysis out, BindingNames names, OriginTracker origins) {
        this.out = out;
        this.names = names;
        this.origins = origins;
    }

    /**
     * 式のノード番号。追跡できなければ {@link ValueNode#NONE}。
     *
     * 「分からない」を {@link Origin#UNKNOWN} のノードとして残すことはしない。
     * 参照する側（C 行・U 行のレシーバ・実引数）が {@link ValueNode#NONE} を置くので、
     * 同じことを2通りで表さない
     */
    int nodeOf(Expression ex) {
        if (ex == null) {
            return ValueNode.NONE;
        }
        Integer known = built.get(ex);
        return (known != null) ? known : nodeOf(ex, depthInTree(ex));
    }

    /** 式ごとの控え（{@link #built}）を捨てる。スコープが変わるたびに {@link OriginTracker} が呼ぶ */
    void forgetExpressions() {
        built.clear();
    }

    /**
     * 式の木の中での深さ。{@link #nodeOf} が辿る辺（レシーバ・実引数・束縛したレシーバ）だけを
     * 1 段と数え、括弧とキャストは数えない。それ以外の親（演算子・代入・文など）で止まる
     * （{@link #nodeOf} はそこから下へ辿らないので、そこが木の根になる）。
     *
     * 辿る辺で数え方が一致していればよく、辿らない辺（{@code outer.new Inner()} の outer など）を
     * 数えても、その式は根からは辿られないので食い違わない。上限を超えたところで数えるのをやめる
     */
    private static int depthInTree(Expression ex) {
        int depth = 0;
        ASTNode parent = ex.getParent();
        while (parent != null && depth <= HARD_CAP) {
            if (parent instanceof MethodInvocation || parent instanceof ClassInstanceCreation
                    || parent instanceof ExpressionMethodReference) {
                depth++;
            } else if (!(parent instanceof ParenthesizedExpression || parent instanceof CastExpression)) {
                break;
            }
            parent = parent.getParent();
        }
        return depth;
    }

    private int nodeOf(Expression ex, int depth) {
        if (ex == null || depth > HARD_CAP) {
            return ValueNode.NONE;
        }
        Integer known = built.get(ex);
        if (known != null) {
            return known;
        }
        int id = build(ex, depth);
        built.put(ex, id);
        return id;
    }

    /** 式 1 つぶんのノードを作る（{@link #nodeOf} が控えを引いた後で呼ぶ） */
    private int build(Expression ex, int depth) {
        Expression e = OriginTracker.unwrapValue(ex);
        if (e == null) {
            // 値を変えうるキャスト（(byte) p）。その先の値は、この式の値ではない。
            // コンパイル時定数なら JDT が型変換まで済ませて畳んだ値（(byte)300 は 44）だけを持つ
            return constantNode(ex.resolveConstantExpressionValue());
        }
        if (e instanceof ClassInstanceCreation cic) {
            String type = names.createdTypeOf(cic);
            if (type == null) {
                return ValueNode.NONE;
            }
            return node(Origin.NEW, type, ValueNode.NONE,
                    argsOf(cic.arguments(), depth), cic.arguments().size());
        }
        if (e instanceof MethodInvocation mi) {
            // Class.forName(x).newInstance() 系は、連鎖そのものではなく
            // 「生成される型」を持つ。この認識は OriginTracker と同じものを使う
            // （2 か所で違う判断をしないため）
            String reflected = origins.reflectiveOriginOf(mi);
            if (reflected != null) {
                return node(Origin.kindOf(reflected), Origin.valueOf(reflected),
                        ValueNode.NONE, "", -1);
            }
            MethodRef ref = names.toRef(mi.resolveMethodBinding());
            if (ref == null) {
                return ValueNode.NONE;
            }
            // レシーバの段数に上限を設けない。invoke <- getMethod <- forName のような
            // 連鎖も、何段でもそのまま辿れる
            int recv = (mi.getExpression() == null)
                    ? ValueNode.NONE : nodeOf(mi.getExpression(), depth + 1);
            return node(Origin.RETURN, ref.key(), recv,
                    argsOf(mi.arguments(), depth), mi.arguments().size(),
                    staticReceiverOf(mi, ref.typeFqn()));
        }
        if (e instanceof StringLiteral literal) {
            // 長さも形も問わない。SQL やログ文言もそのまま持つ（analysis 側は捨てていた）
            return node(Origin.LITERAL, literal.getLiteralValue(), ValueNode.NONE, "", -1);
        }
        if (e instanceof TypeLiteral typeLiteral) {
            String n = names.declTypeName(typeLiteral.getType().resolveBinding());
            return (n == null || n.isEmpty())
                    ? ValueNode.NONE : node(Origin.CLASS, n, ValueNode.NONE, "", -1);
        }
        // コンパイル時定数。JDT に評価させるので、リテラルの連結（SQL を "…\n" + "…" と
        // 書く形）や static final の参照も 1 つの値として拾える。長さも制御文字も問わない。
        // 評価するのは元の式（剥がしたのは値を変えないキャストだけなので、値は同じ）
        Object constant = ex.resolveConstantExpressionValue();
        if (constant != null) {
            return constantNode(constant);
        }
        String enumConstant = origins.enumConstantValueOf(e);
        if (enumConstant != null) {
            return node(Origin.CONST, enumConstant, ValueNode.NONE, "", -1);
        }
        // ラムダ／メソッド参照は、実際に動くメソッドを指すノードにする。
        // レシーバを束縛したメソッド参照（dao::describe）は、そのレシーバをノードで持つ
        // （読み手が参照先の実装をレシーバの具象型から引くため。OriginTracker.functionalOriginOf）
        String functional = origins.functionalOriginOf(e);
        if (functional != null) {
            Expression receiver = (e instanceof MethodReference ref)
                    ? OriginTracker.boundReceiverOf(ref) : null;
            int recv = (receiver == null) ? ValueNode.NONE : nodeOf(receiver, depth + 1);
            return node(Origin.FUNCTIONAL, Origin.valueOf(functional), recv, "", -1);
        }
        // ローカル変数は、その代入元の式から作ったノードをそのまま指す。
        // 出所の文字列（上限付き）と違い、入れ子をノードの参照で保てる
        int local = origins.localNodeOf(e);
        if (local != ValueNode.NONE) {
            return local;
        }
        // 引数・フィールド・捕捉した変数などの葉。判定は OriginTracker の結果をそのまま使う
        String leaf = Origin.head(origins.originOf(e));
        if (leaf == null || Origin.isUnknown(leaf)) {
            return ValueNode.NONE;
        }
        return node(Origin.kindOf(leaf), Origin.valueOf(leaf), ValueNode.NONE, "", -1);
    }

    /**
     * コンパイル時定数の値のノード。文字列はリテラルと同じ種別、真偽値・整数・文字は
     * 出所の文字列と同じ表記（{@link OriginTracker#constantText}。文字は数値）。
     * 浮動小数は持たない（{@link ValueNode#NONE}）。条件の期待値は書かれたとおりの表記
     * （{@code x == 1} の {@code "1"}）なので、{@code 1.0} と突き合わせると別の値に見えて、
     * 実際には通る経路を「呼ばれない」と判定してしまう（{@link OriginTracker#constantOf} と同じ方針）
     */
    private int constantNode(Object constant) {
        if (constant instanceof String text) {
            // 文字列に評価される定数は、リテラルと同じ種別にする（1 つの式に 1 つのノード）
            return node(Origin.LITERAL, text, ValueNode.NONE, "", -1);
        }
        String text = OriginTracker.constantText(constant);
        return (text == null) ? ValueNode.NONE : node(Origin.CONST, text, ValueNode.NONE, "", -1);
    }

    /** 定数の値（{@link Origin#CONST}）のノード。条件の subject（文字列の定数）に使う（{@link GuardCollector}） */
    int constantValueNode(String value) {
        return node(Origin.CONST, value, ValueNode.NONE, "", -1);
    }

    /** ノードの種別。番号が範囲外（{@link ValueNode#NONE} を含む）なら {@link Origin#UNKNOWN} */
    char kindOf(int id) {
        return (id >= 0 && id < out.valueNodes.size()) ? out.valueNodes.get(id).kind() : Origin.UNKNOWN;
    }

    /**
     * 呼び出し箇所の実引数のノードを {@code 位置=番号} のカンマ区切りにする。追跡できない引数は載せない。
     * 各実引数の深さは式の木から求める（{@link #nodeOf(Expression)}）
     */
    String argsOf(List<?> args) {
        return argsOf(args, -1);
    }

    /** @param depth 実引数を持つ式の深さ。負なら実引数ごとに式の木から求める */
    private String argsOf(List<?> args, int depth) {
        if (args == null || args.isEmpty() || depth > HARD_CAP) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (!(args.get(i) instanceof Expression arg)) {
                continue;
            }
            // 実引数も入れ子のまま辿る（analysis 側は1段で剥がしていた）
            int id = (depth < 0) ? nodeOf(arg) : nodeOf(arg, depth + 1);
            if (id == ValueNode.NONE) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(ValueNode.ARG_SEP);
            }
            sb.append(i).append('=').append(id);
        }
        return sb.toString();
    }

    /**
     * 呼び出しを<b>ソースに書いたときのレシーバの型</b>。宣言元と同じか、分からなければ空文字。
     *
     * {@code DaoFactory.get(...)} の {@code get} が親の {@code BaseFactory} で宣言されていると、
     * メソッドキーは親になる。利用者が契約表や拡張で指定するのはソースに書いてある型なので、
     * 違うときだけ書かれた型も残す（同じなら持たない。キャッシュを無駄に太らせないため）。
     */
    private String staticReceiverOf(MethodInvocation mi, String declaringFqn) {
        Expression receiver = mi.getExpression();
        if (receiver == null) {
            return "";   // 修飾なしの呼び出し。書かれた型は無い
        }
        ITypeBinding type = receiver.resolveTypeBinding();
        if (type == null) {
            return "";
        }
        String written = names.typeNameOf(BindingNames.erasureOf(type));
        return (written == null || written.isEmpty() || written.equals(declaringFqn)) ? "" : written;
    }

    /** ノードを1つ足す（同じ構造のものが既にあればその番号を返す） */
    private int node(char kind, String value, int recv, String args, int argCount) {
        return node(kind, value, recv, args, argCount, "");
    }

    private int node(char kind, String value, int recv, String args, int argCount,
                     String staticRecv) {
        ValueNode candidate = new ValueNode(out.valueNodes.size(), kind,
                (value == null) ? "" : value, recv, args, argCount, staticRecv);
        Integer existing = dedupe.get(candidate.dedupeKey());
        if (existing != null) {
            return existing;
        }
        out.valueNodes.add(candidate);
        dedupe.put(candidate.dedupeKey(), candidate.id());
        return candidate.id();
    }
}
