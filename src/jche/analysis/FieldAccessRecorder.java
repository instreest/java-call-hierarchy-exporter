// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.util.List;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SuperFieldAccess;

import jche.cache.FieldAccessFact;
import jche.cache.FileAnalysis;
import jche.cache.MethodRef;

/** フィールドの参照箇所（A行）を記録する。{@link FactVisitor} の SimpleName の訪問から呼ばれる */
final class FieldAccessRecorder {

    private final FileAnalysis out;
    private final BindingNames names;

    FieldAccessRecorder(FileAnalysis out, BindingNames names) {
        this.out = out;
        this.names = names;
    }

    /**
     * フィールドの参照箇所（A行）。
     *
     * 参照はどんな形でも最終的に SimpleName に行き着く（a.b.c の c、this.x の x、
     * super.x の x）ので、SimpleName だけを見れば重複なく拾える。
     * 宣言そのもの（フィールド宣言の名前）は除く。配列の length のように
     * 型に属さないものも除く。
     */
    void record(SimpleName node, int line, List<MethodRef> callers, int lambdaDepth) {
        if (node.isDeclaration()) {
            return;
        }
        if (!(node.resolveBinding() instanceof IVariableBinding vb) || !vb.isField()) {
            return;
        }
        ITypeBinding owner = vb.getDeclaringClass();
        if (owner == null) {
            return;
        }
        String ownerFqn = names.typeNameOf(BindingNames.erasureOf(owner));
        if (ownerFqn == null) {
            return;
        }
        String access = accessKindOf(node);
        String mods = BindingNames.modifiersOf(vb.getModifiers());
        if (callers == null) {
            out.fieldAccesses.add(new FieldAccessFact(line, null, ownerFqn, vb.getName(),
                    access, mods, lambdaDepth));
            return;
        }
        // 囲みメソッドごとに1件（初期化子の中なら根のコンストラクタそれぞれ）
        for (MethodRef caller : callers) {
            out.fieldAccesses.add(new FieldAccessFact(line, caller, ownerFqn, vb.getName(),
                    access, mods, lambdaDepth));
        }
    }

    /** read / write / readwrite。代入の左辺なら write、複合代入と ++/-- なら readwrite */
    private static String accessKindOf(SimpleName name) {
        ASTNode expr = name;
        ASTNode parent = name.getParent();
        // a.b / this.b / super.b の b なら、参照式はその親
        if ((parent instanceof QualifiedName qn && qn.getName() == name)
                || (parent instanceof FieldAccess fa && fa.getName() == name)
                || (parent instanceof SuperFieldAccess sfa && sfa.getName() == name)) {
            expr = parent;
            parent = expr.getParent();
        }
        if (parent instanceof Assignment assignment && assignment.getLeftHandSide() == expr) {
            return (assignment.getOperator() == Assignment.Operator.ASSIGN)
                    ? FieldAccessFact.WRITE : FieldAccessFact.READ_WRITE;
        }
        if (parent instanceof PostfixExpression) {
            return FieldAccessFact.READ_WRITE;
        }
        if (parent instanceof PrefixExpression prefix) {
            PrefixExpression.Operator op = prefix.getOperator();
            return (op == PrefixExpression.Operator.INCREMENT
                    || op == PrefixExpression.Operator.DECREMENT)
                    ? FieldAccessFact.READ_WRITE : FieldAccessFact.READ;
        }
        return FieldAccessFact.READ;
    }
}
