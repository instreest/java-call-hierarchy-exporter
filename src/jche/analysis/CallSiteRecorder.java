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

import jche.cache.CacheFormat;
import jche.cache.CallEdgeFact;
import jche.cache.FileAnalysis;
import jche.cache.HintFact;
import jche.cache.MethodRef;
import jche.cache.ModifierTokens;
import jche.cache.RecvKind;
import jche.cache.UnresolvedCallFact;
import jche.extension.CallSiteHintCollector;
import jche.extension.HintKeys;
import jche.extension.HintSink;
import jche.util.Log;

/**
 * 呼び出し箇所（C行・U行）を記録し、フェーズAの拡張に見せる。
 * 呼び出し元のスタックは {@link FactVisitor} が持ち、ここには「今の呼び出し元」を渡してもらう。
 */
final class CallSiteRecorder {

    private final CompilationUnit cu;
    private final FileAnalysis out;
    private final BindingNames names;
    private final List<CallSiteHintCollector> collectors;

    CallSiteRecorder(CompilationUnit cu, FileAnalysis out, BindingNames names,
                     List<CallSiteHintCollector> collectors) {
        this.cu = cu;
        this.out = out;
        this.names = names;
        this.collectors = collectors;
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
     */
    void record(List<MethodRef> callers, int lambdaDepth, IMethodBinding binding, ASTNode node,
                String displayName, String calleeMods, String recvKey, char recvKind,
                String externalGuess, String recvOrigin, String argOrigins) {
        int line = lineOf(node);
        if (callers == null) {
            // 呼び出し元の型・コンストラクタ自体を特定できないケース
            // （型のバインディング解決に失敗した等）
            out.callSites.add(new UnresolvedCallFact(line, null, displayName,
                    UnresolvedCallFact.OUTSIDE_METHOD, "", recvKey, recvKind,
                    recvOrigin, argOrigins, lambdaDepth));
            return;
        }
        MethodRef callee = names.toRef(binding);
        if (callee == null) {
            // 呼び出し先の型解決に失敗したケース。呼び出し元ごとに1件残す
            // （C行と同じく、初期化子の中なら根のコンストラクタそれぞれに属する）
            for (MethodRef caller : callers) {
                out.callSites.add(new UnresolvedCallFact(line, caller, displayName,
                        UnresolvedCallFact.BINDING_FAILED, externalGuess, recvKey, recvKind,
                        recvOrigin, argOrigins, lambdaDepth));
            }
            return;
        }
        // 呼び出し元が複数（インスタンス初期化子等）でも全件をエッジにする。
        // 実際にコンパイル後それぞれから1回ずつ呼ばれるため、これは近似ではない
        for (MethodRef caller : callers) {
            out.callSites.add(new CallEdgeFact(caller, callee, line, calleeMods,
                    recvKey, recvKind, recvOrigin, argOrigins, lambdaDepth));
        }
    }

    /**
     * フェーズAの拡張に、この呼び出し箇所を見せる。
     *
     * 呼び出し元が複数（インスタンス初期化子等）ある場合は、その全員に対して
     * 見せる。一部にしか見せないと、その呼び出し元経由の解決だけ証拠を
     * 見つけられなくなるため。CallSiteHintCollector のインターフェースは
     * 呼び出し元1件を前提にしているため、呼び出し元ごとに1回ずつ呼ぶ。
     */
    void offerToHintCollectors(MethodInvocation n, List<MethodRef> callers) {
        if (callers == null || collectors.isEmpty()) {
            return;
        }
        for (MethodRef caller : callers) {
            String callerKey = caller.key();
            HintSink sink = (scopeKey, kind, value) -> {
                if (scopeKey == null || kind == null || value == null) {
                    return;
                }
                out.hints.add(new HintFact(callerKey, CacheFormat.clean(scopeKey),
                        CacheFormat.clean(kind), CacheFormat.clean(value)));
            };
            for (CallSiteHintCollector collector : collectors) {
                try {
                    collector.collect(n, cu, callerKey, sink);
                } catch (RuntimeException e) {
                    // 拡張の失敗で解析全体を止めない
                    Log.warn("hint collector 失敗: " + collector.getClass().getName() + " (" + e + ")");
                }
            }
        }
    }

    /**
     * バインディング解決が完全に失敗した場合の最後の手段。
     * レシーバの単純名が、このファイルの単一型インポート（{@code import a.b.C;}）と
     * 一致すれば、そのFQNを候補として返す。あくまでソース上のテキストからの
     * 推定であり、JDTによる検証済みの型解決ではない
     * （メンバの実在・オーバーロードの妥当性までは確認できない）。
     * ワイルドカードimport・static import・型不明のレシーバでは使わない。
     */
    String externalGuessRef(MethodInvocation n) {
        if (!(n.getExpression() instanceof SimpleName recv)) {
            return null;
        }
        String simple = recv.getIdentifier();
        for (Object o : cu.imports()) {
            ImportDeclaration imp = (ImportDeclaration) o;
            if (imp.isOnDemand() || imp.isStatic()) {
                continue;
            }
            String name = imp.getName().getFullyQualifiedName();
            if (name.equals(simple) || name.endsWith("." + simple)) {
                return name;
            }
        }
        return null;
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
