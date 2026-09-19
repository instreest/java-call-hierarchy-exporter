// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodInvocation;
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
 * 呼び出し箇所の値（{@code P} 行）を持つのは<b>こちらだけ</b>で、読み手は
 * {@code jche.graph.OriginRenderer} が読む直前に出所の文字列へ組み直す
 * （{@code docs/cache-split-qa.md} の Q21・Q22）。
 * 前者が残るのは {@code R} 行・{@code J} 行・{@code X} 行と、この葉の判定のためだけ。
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
 * <h2>大きさ</h2>
 * 同じ構造のノードは1つにまとめる（{@link ValueNode#dedupeKey}）。入れ子を展開しないので、
 * 大きさはソースの式の数に比例する。1ファイル分を組み立てたら、ブロックとして書き出して捨てる
 * （{@link FileAnalysis} の寿命と同じ）。
 */
final class ValueGraph {

    /**
     * 式を辿る深さの上限。再帰なのでスタックを守るためだけに置く安全策で、
     * 意味のある上限ではない（実在のコードの式の深さはこれよりはるかに浅い）。
     * {@code jche.dataflow.DataflowBuilder.HARD_CAP} と同じ役割
     */
    private static final int HARD_CAP = 256;

    private final FileAnalysis out;
    private final BindingNames names;
    private final OriginTracker origins;
    /** 同じ構造のノードを1つにまとめる（{@link ValueNode#dedupeKey} -> 番号） */
    private final Map<String, Integer> dedupe = new HashMap<>();

    ValueGraph(FileAnalysis out, BindingNames names, OriginTracker origins) {
        this.out = out;
        this.names = names;
        this.origins = origins;
    }

    /**
     * 式のノード番号。追跡できなければ {@link ValueNode#NONE}。
     *
     * 「分からない」を {@link Origin#UNKNOWN} のノードとして残すことはしない。
     * 参照する側（P 行のレシーバ・実引数）が {@link ValueNode#NONE} を置くので、
     * 同じことを2通りで表さない
     */
    int nodeOf(Expression ex) {
        return nodeOf(ex, 0);
    }

    private int nodeOf(Expression ex, int depth) {
        Expression e = OriginTracker.unwrap(ex);
        if (e == null || depth > HARD_CAP) {
            return ValueNode.NONE;
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
        // 書く形）や static final の参照も 1 つの値として拾える。長さも制御文字も問わない
        Object constant = e.resolveConstantExpressionValue();
        if (constant instanceof String text) {
            // 文字列に評価される定数は、リテラルと同じ種別にする（1 つの式に 1 つのノード）
            return node(Origin.LITERAL, text, ValueNode.NONE, "", -1);
        }
        if (constant != null) {
            // 真偽値・数値・文字。表記は analysis 側の出所と同じに揃える
            // （2c で読み手を移すとき、ガードの判定値と突き合わせられるようにするため）
            String normalized = Origin.valueOf(origins.constantOf(e));
            return node(Origin.CONST,
                    (normalized == null || normalized.isEmpty()) ? String.valueOf(constant) : normalized,
                    ValueNode.NONE, "", -1);
        }
        String enumConstant = origins.enumConstantValueOf(e);
        if (enumConstant != null) {
            return node(Origin.CONST, enumConstant, ValueNode.NONE, "", -1);
        }
        // ラムダ／メソッド参照は、実際に動くメソッドを指すノードにする
        String functional = origins.functionalOriginOf(e);
        if (functional != null) {
            return node(Origin.FUNCTIONAL, Origin.valueOf(functional), ValueNode.NONE, "", -1);
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

    /** 実引数のノードを {@code 位置=番号} のカンマ区切りにする。追跡できない引数は載せない */
    String argsOf(List<?> args, int depth) {
        if (args == null || args.isEmpty() || depth > HARD_CAP) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (!(args.get(i) instanceof Expression arg)) {
                continue;
            }
            // 実引数も入れ子のまま辿る（analysis 側は1段で剥がしていた）
            int id = nodeOf(arg, depth + 1);
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

    /** ノードを1つ足す（同じ構造のものが既にあればその番号を返す） */
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
