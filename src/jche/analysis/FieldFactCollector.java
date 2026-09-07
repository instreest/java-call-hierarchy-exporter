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
package jche.analysis;

import java.util.List;

import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import jche.cache.FieldAssignFact;
import jche.cache.FieldDeclFact;
import jche.cache.FileAnalysis;
import jche.cache.MethodRef;
import jche.cache.Origin;

/**
 * 1つの型について、フィールドの宣言（V行）と、そのフィールドへの代入（J行）を拾う。
 *
 * ここでは事実だけを拾う。「このフィールドには必ずコンストラクタの何番目の
 * 引数が入る」と言い切れるかどうか（private/final か、全コンストラクタで
 * 代入されているか、出所が一致するか）は読み手 jche.graph.FieldFacts が判定する。
 *
 * 拾う範囲は、その型自身のフィールド初期化子と、その型自身のメソッド・
 * コンストラクタ本体の中の代入（インスタンス初期化ブロックと内部クラスからの
 * 代入は拾わない。範囲を変えるときはキャッシュのバージョンを上げる）。
 */
final class FieldFactCollector {

    private final FileAnalysis out;
    private final BindingNames names;
    private final OriginTracker origins;

    FieldFactCollector(FileAnalysis out, BindingNames names, OriginTracker origins) {
        this.out = out;
        this.names = names;
        this.origins = origins;
    }

    void collect(ITypeBinding typeBinding, List<?> bodyDeclarations) {
        if (typeBinding == null || bodyDeclarations.isEmpty()) {
            return;
        }
        String typeFqn = names.typeNameOf(BindingNames.erasureOf(typeBinding));
        if (typeFqn == null) {
            return;
        }
        for (Object o : bodyDeclarations) {
            if (o instanceof FieldDeclaration fd) {
                scanFieldDeclaration(fd, typeFqn);
            } else if (o instanceof MethodDeclaration md) {
                scanAssignments(md, typeFqn, siteOf(md));
            }
        }
    }

    /** J行の site。メソッド／コンストラクタの "name(paramSig)" */
    private String siteOf(MethodDeclaration md) {
        MethodRef ref = names.toRef(md.resolveBinding());
        return (ref == null) ? "?" : ref.signature();
    }

    /** フィールド宣言（V行）と、初期化子があればその代入（J行）を拾う */
    private void scanFieldDeclaration(FieldDeclaration fd, String typeFqn) {
        for (Object f : fd.fragments()) {
            if (!(f instanceof VariableDeclarationFragment frag)) {
                continue;
            }
            IVariableBinding vb = frag.resolveBinding();
            if (vb == null || !isOwnField(vb, typeFqn)) {
                continue;
            }
            out.fieldDecls.add(new FieldDeclFact(typeFqn, vb.getName(),
                    BindingNames.modifiersOf(vb.getModifiers()), names.declTypeName(vb.getType())));
            if (frag.getInitializer() != null) {
                out.fieldAssigns.add(new FieldAssignFact(typeFqn, vb.getName(),
                        FieldAssignFact.SITE_INITIALIZER,
                        Origin.head(origins.originOf(frag.getInitializer()))));
            }
        }
    }

    /** メソッド本体の中の、この型自身のフィールドへの代入を拾う（J行） */
    private void scanAssignments(MethodDeclaration md, String typeFqn, String site) {
        Block body = md.getBody();
        if (body == null) {
            return;
        }
        origins.enterScope(origins.paramScopeOf(md));
        try {
            body.accept(new ASTVisitor() {
                @Override
                public boolean visit(Assignment n) {
                    IVariableBinding vb = assignedFieldOf(n.getLeftHandSide());
                    if (vb == null || !isOwnField(vb, typeFqn)) {
                        return true;
                    }
                    out.fieldAssigns.add(new FieldAssignFact(typeFqn, vb.getName(), site,
                            Origin.head(origins.originOf(n.getRightHandSide()))));
                    return true;
                }
            });
        } finally {
            origins.leaveScope();
        }
    }

    /** 代入先がフィールドなら、そのバインディング */
    private static IVariableBinding assignedFieldOf(Expression lhs) {
        Expression e = OriginTracker.unwrap(lhs);
        if (e instanceof FieldAccess fa) {
            return fa.resolveFieldBinding();
        }
        IBinding b = null;
        if (e instanceof SimpleName sn) {
            b = sn.resolveBinding();
        } else if (e instanceof QualifiedName qn) {
            b = qn.resolveBinding();
        }
        return (b instanceof IVariableBinding vb && vb.isField()) ? vb : null;
    }

    /** この型自身が宣言しているフィールドか（他の型のフィールドへの代入は、この型の事実ではない） */
    private boolean isOwnField(IVariableBinding vb, String typeFqn) {
        if (!vb.isField()) {
            return false;
        }
        ITypeBinding owner = vb.getDeclaringClass();
        if (owner == null) {
            return false;
        }
        return typeFqn.equals(names.typeNameOf(BindingNames.erasureOf(owner)));
    }
}
