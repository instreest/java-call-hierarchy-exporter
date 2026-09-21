// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.util.List;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;

import jche.cache.CallEdgeFact;
import jche.cache.CallSiteValues;
import jche.cache.FileAnalysis;
import jche.cache.MethodRef;
import jche.cache.ModifierTokens;
import jche.cache.RecvKind;
import jche.cache.UnresolvedCallFact;

/**
 * 呼び出し箇所（C行・U行）を記録し、フェーズAの拡張に見せる。
 * 呼び出し元のスタックは {@link FactVisitor} が持ち、ここには「今の呼び出し元」を渡してもらう。
 */
final class CallSiteRecorder {

    private final CompilationUnit cu;
    private final FileAnalysis out;
    private final BindingNames names;
    private final GuardCollector guards;
    /** 鍵ごとの件数。同じ鍵が複数あるときの通し番号を振るため（{@link #addValues}） */
    private final java.util.Map<String, Integer> joinKeyCounts = new java.util.HashMap<>();
    /**
     * 単一型インポートの「単純名 -> FQN」。{@link #externalGuessRef} 用に、
     * このファイルで最初に必要になったときだけ作る（インポートが無いファイルでは作らない）
     */
    private java.util.Map<String, String> singleTypeImports;

    CallSiteRecorder(CompilationUnit cu, FileAnalysis out, BindingNames names,
                     GuardCollector guards) {
        this.cu = cu;
        this.out = out;
        this.names = names;
        this.guards = guards;
    }

    private int lineOf(ASTNode node) {
        return cu.getLineNumber(node.getStartPosition());
    }

    /**
     * 呼び出し箇所を1件記録する。
     *
     * 解決できれば C 行（呼び出し元ごとに1本）。解決できなければ U 行に、
     * 理由コードと import から推定した候補（{@link #externalGuessRef}）を事実として残す。
     * 候補をエッジとして採用するかは読み手（jche.graph.CallGraphBuilder）が決める。
     *
     * @param guessSource 候補を推定する元の呼び出し式。<b>解決に失敗したときだけ</b>使うので、
     *                    推定そのものもここまで遅らせる（呼び出しの大半は解決できるため、
     *                    先に求めておくと、使われない推定をすべての呼び出しぶん行うことになる）。
     *                    推定の対象でない形（new・super 呼び出し・メソッド参照）では null
     */
    void record(List<MethodRef> callers, int lambdaDepth, IMethodBinding binding, ASTNode node,
                String displayName, String calleeMods, String recvKey, char recvKind,
                MethodInvocation guessSource, CallValues values) {
        int line = lineOf(node);
        // 呼び出し箇所を囲む条件分岐（その経路で呼ばれないと言い切れるかは読み手が判断する）
        String guard = guards.guardOf(node);
        if (callers == null) {
            // 呼び出し元の型・コンストラクタ自体を特定できないケース
            // （型のバインディング解決に失敗した等）
            out.callSites.add(new UnresolvedCallFact(line, null, displayName,
                    UnresolvedCallFact.OUTSIDE_METHOD, "", recvKind, lambdaDepth));
            addValues(line, null, displayName, values, recvKey, guard);
            return;
        }
        MethodRef callee = names.toRef(binding);
        if (callee == null) {
            // 呼び出し先の型解決に失敗したケース。呼び出し元ごとに1件残す
            // （C行と同じく、初期化子の中なら根のコンストラクタそれぞれに属する）
            String externalGuess = (guessSource == null) ? null : externalGuessRef(guessSource);
            for (MethodRef caller : callers) {
                out.callSites.add(new UnresolvedCallFact(line, caller, displayName,
                        UnresolvedCallFact.BINDING_FAILED, externalGuess, recvKind, lambdaDepth));
                addValues(line, caller, displayName, values, recvKey, guard);
            }
            return;
        }
        // 呼び出し元が複数（インスタンス初期化子等）でも全件をエッジにする。
        // 実際にコンパイル後それぞれから1回ずつ呼ばれるため、これは近似ではない
        for (MethodRef caller : callers) {
            out.callSites.add(new CallEdgeFact(caller, callee, line, calleeMods,
                    recvKind, lambdaDepth));
            addValues(line, caller, displayName, values, recvKey, guard);
        }
    }

    /**
     * こちらで作ったメソッド（ラムダの合成メソッド）への辺を1本記録する。
     *
     * 呼び出し先がバインディングではなく合成した {@link MethodRef} なので
     * {@link #record} は通せないが、P 行との1対1（同じ数・同じ順）は
     * 呼び出し箇所の突き合わせの前提なので、値が無くても {@link #addValues} は必ず通す。
     */
    void recordSynthetic(List<MethodRef> callers, MethodRef callee, ASTNode node,
                         String calleeMods, char recvKind, int lambdaDepth) {
        if (callers == null) {
            // 呼び出し元を特定できないなら辺にしない。根の無い辺は階層に出ない
            // （特定できない場合の空リストは、下のループが0回になることで同じ結果になる）
            return;
        }
        recordSyntheticAt(callers, callee, lineOf(node), calleeMods, recvKind, lambdaDepth,
                guards.guardOf(node));
    }

    /**
     * ソースに対応するASTノードが無い辺を1本記録する（暗黙の {@code super()} など）。
     *
     * 行だけを渡すのは、合成した宣言（暗黙のデフォルトコンストラクタ）から張る辺には
     * 対応するノードが無いため。条件（guard）も持たない。コンストラクタ本体の先頭で
     * 必ず実行される呼び出しなので、囲む分岐はありえない（JLS 8.8.7）。
     */
    void recordSyntheticAt(List<MethodRef> callers, MethodRef callee, int line,
                           String calleeMods, char recvKind, int lambdaDepth, String guard) {
        if (callers == null) {
            return;
        }
        for (MethodRef caller : callers) {
            out.callSites.add(new CallEdgeFact(caller, callee, line, calleeMods,
                    recvKind, lambdaDepth));
            addValues(line, caller, callee.name(), CallValues.NONE, "", guard);
        }
    }

    /**
     * dataflow 側の P 行を1件積む。{@code out.callSites} に1行積むたびに必ず1件積むので、
     * 2 つのキャッシュの呼び出し箇所は同じ数・同じ順で並ぶ。
     *
     * 通し番号は「同じ鍵（行番号・呼び出し元・表示名）が既に何件あるか」。
     * {@code f(g(), g())} のようにまったく同じ鍵が並ぶ場合を読み手が区別できるようにする
     */
    private void addValues(int line, MethodRef caller, String displayName, CallValues values,
                           String recvKey, String guard) {
        CallSiteValues candidate = new CallSiteValues(line, caller, displayName, 0,
                values.recvNode(), values.argNodes(), recvKey, guard);
        int ordinal = joinKeyCounts.merge(candidate.joinKey(), 1, Integer::sum) - 1;
        out.callSiteValues.add(new CallSiteValues(line, caller, displayName, ordinal,
                values.recvNode(), values.argNodes(), recvKey, guard));
    }

    /**
     * バインディング解決が完全に失敗した場合の最後の手段。
     * レシーバの単純名が、このファイルの単一型インポート（{@code import a.b.C;}）と
     * 一致すれば、そのFQNを候補として返す。あくまでソース上のテキストからの
     * 推定であり、JDTによる検証済みの型解決ではない
     * （メンバの実在・オーバーロードの妥当性までは確認できない）。
     * ワイルドカードimport・static import・型不明のレシーバでは使わない。
     */
    private String externalGuessRef(MethodInvocation n) {
        if (!(n.getExpression() instanceof SimpleName recv)) {
            return null;
        }
        return singleTypeImports().get(recv.getIdentifier());
    }

    /**
     * 単一型インポートを単純名で引ける表にして1回だけ作る。
     *
     * 以前は呼び出し1件ごとに import 文を先頭から走査し、比較のたびに {@code "." + 単純名} の
     * 文字列を作っていた。インポートも呼び出しも多いファイルでは、その掛け算ぶんの手間になる。
     * 同じ単純名のインポートが2つある（コンパイルの通らないソース）場合は、
     * 走査していたときと同じく<b>先に書かれている方</b>を残す。
     */
    private java.util.Map<String, String> singleTypeImports() {
        if (singleTypeImports != null) {
            return singleTypeImports;
        }
        java.util.Map<String, String> map = new java.util.HashMap<>();
        for (Object o : cu.imports()) {
            ImportDeclaration imp = (ImportDeclaration) o;
            if (imp.isOnDemand() || imp.isStatic()) {
                continue;
            }
            String name = imp.getName().getFullyQualifiedName();
            map.putIfAbsent(name.substring(name.lastIndexOf('.') + 1), name);
        }
        singleTypeImports = map;
        return map;
    }

    /**
     * 呼び出し先（宣言側）の修飾子（事実）。静的束縛かどうかの判定は
     * 読み手が行う（jche.graph.BindKind）。
     * 宣言クラスが final なら finalclass を足す（サブクラスを作れない＝オーバーライド不能）
     */
    static String targetModsOf(IMethodBinding b) {
        if (b == null) {
            return "";
        }
        IMethodBinding decl = b.getMethodDeclaration();
        if (decl == null) {
            decl = b;
        }
        String mods = BindingNames.modifiersOf(decl.getModifiers());
        ITypeBinding owner = decl.getDeclaringClass();
        if (owner != null && Modifier.isFinal(owner.getModifiers())) {
            mods = ModifierTokens.with(mods, ModifierTokens.FINAL_CLASS);
        }
        return mods;
    }

    /** super.m() / super::m の呼び出し先。super 経由である事実を足す */
    static String superMods(IMethodBinding b) {
        return ModifierTokens.with(targetModsOf(b), ModifierTokens.SUPER);
    }

    /**
     * レシーバの識別キー。
     * ローカル変数なら変数のバインディングキー、そうでなければ "@開始位置"。
     * 後者にしておくと、変数を介さない呼び出し
     * （DaoFactory.get("X").execute(...) など）にも拡張が証拠を結び付けられる。
     */
    static String recvKeyOf(Expression ex) {
        return HintKeys.ofReceiver(ex);
    }

    /**
     * レシーバがどこから来たかを判定する（{@link RecvKind}）。
     *
     * CHAで実装を絞れなかったときに「なぜ絞れないのか」を出力へ載せるため。
     * 例: 戻り値ならファクトリメソッド、引数ならメソッド外から渡されている、
     * という具合に、利用者が次に何を調べるべきかが変わる。
     */
    static char recvKindOf(Expression ex) {
        if (ex == null) {
            return RecvKind.THIS;
        }
        if (ex instanceof MethodInvocation) {
            return RecvKind.RETURN;
        }
        if (ex instanceof ClassInstanceCreation) {
            return RecvKind.LOCAL;   // new した直後に呼ぶ形。型は確定している
        }
        if (ex instanceof FieldAccess) {
            return RecvKind.FIELD;
        }
        if (ex instanceof SimpleName || ex instanceof QualifiedName) {
            IBinding b = (ex instanceof SimpleName sn) ? sn.resolveBinding()
                    : ((QualifiedName) ex).resolveBinding();
            if (b instanceof ITypeBinding) {
                return RecvKind.TYPE;
            }
            if (b instanceof IVariableBinding vb) {
                if (vb.isField()) {
                    return RecvKind.FIELD;
                }
                if (vb.isParameter()) {
                    return RecvKind.PARAM;
                }
                return RecvKind.LOCAL;
            }
        }
        return RecvKind.OTHER;
    }
}
