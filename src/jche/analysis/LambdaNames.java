// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.Initializer;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;

import jche.cache.MethodRef;

/**
 * ラムダ式の本体を持つ合成メソッドの名前を、ファイル単位で先に決めておく表。
 *
 * <h2>なぜ先に決めるのか</h2>
 * 名前を「見つけた順の通し番号」で決めると、決める場所が2つあって食い違う。
 * {@link OriginTracker#scanOrigins} はメソッドに入る前に本体を先読みして
 * {@code Supplier<X> s = () -> ...} の出所を作り、{@link FactVisitor} は
 * そのあとの本走査でラムダの宣言（D行）を作る。同じラムダに違う名前が付くと、
 * 出所が指すメソッドと宣言したメソッドが別物になってしまう。
 *
 * そこでコンパイル単位を1度だけ先に走査し、ラムダ式のノードそのもの（同一性）を
 * 鍵にして名前を配る。走査順＝ソースの並び順なので、同じソースからは必ず同じ名前になる
 * （差分更新でファイルを解析し直しても変わらない）。
 *
 * <h2>名前の形</h2>
 * javac が付けるものに合わせて {@code lambda$囲みメソッド名$通し番号}。
 * 実際のスタックトレースに現れる名前と同じ形なので、読み手が結び付けられる。
 * 通し番号は型ごとに振る（オーバーロードで囲みメソッド名が同じでも重ならないため）。
 */
final class LambdaNames {

    /** ラムダ式のノード -> 合成メソッド。同一性で引くので IdentityHashMap */
    private final Map<LambdaExpression, MethodRef> names = new IdentityHashMap<>();

    /**
     * コンパイル単位を1度走査して、全てのラムダ式に名前を配る。
     *
     * @param names バインディングから名前を作る係（型の記録もここを通す）
     */
    LambdaNames(CompilationUnit cu, BindingNames names) {
        cu.accept(new Collector(names));
    }

    /** そのラムダ式の合成メソッド。名前を決められなかったラムダは null */
    MethodRef of(LambdaExpression node) {
        return names.get(node);
    }

    private final class Collector extends ASTVisitor {

        private final BindingNames binding;
        /** 型ごとの通し番号 */
        private final Map<String, Integer> counts = new HashMap<>();

        Collector(BindingNames binding) {
            this.binding = binding;
        }

        @Override
        public boolean visit(LambdaExpression node) {
            ITypeBinding fnType = node.resolveTypeBinding();
            IMethodBinding sam = (fnType == null) ? null : fnType.getFunctionalInterfaceMethod();
            MethodRef samRef = (sam == null) ? null : binding.toRef(sam);
            MethodRef enclosing = enclosingOf(node);
            if (samRef == null || enclosing == null) {
                // 関数型インターフェースか囲みメソッドを特定できないラムダには名前を付けない。
                // 呼び出しは従来どおり囲みメソッドに計上される（取りこぼさない側に倒す）
                return true;
            }
            int index = counts.merge(enclosing.typeFqn(), 1, Integer::sum) - 1;
            names.put(node, new MethodRef(enclosing.pkg(), enclosing.typeFqn(),
                    "lambda$" + baseNameOf(enclosing) + "$" + index, samRef.paramSig()));
            return true;
        }

        /**
         * そのラムダを囲んでいるメソッド。
         *
         * メソッドの中なら そのメソッド、フィールド初期化子やインスタンス初期化ブロックの中なら
         * 「そのクラスのコンストラクタ」、static 初期化子の中なら {@code <clinit>} を表す
         * {@link MethodRef}。名前を決めるためだけに使うので、初期化子が実際には
         * 複数のコンストラクタに複製されることは、ここでは考えなくてよい
         * （呼び出し元としての複製は {@link FactVisitor} が別に扱う）。
         */
        private MethodRef enclosingOf(ASTNode node) {
            boolean staticContext = false;
            for (ASTNode n = node.getParent(); n != null; n = n.getParent()) {
                if (n instanceof MethodDeclaration method) {
                    return binding.toRef(method.resolveBinding());
                }
                if (n instanceof Initializer init) {
                    staticContext = Modifier.isStatic(init.getModifiers());
                } else if (n instanceof FieldDeclaration field) {
                    staticContext = Modifier.isStatic(field.getModifiers());
                } else if (n instanceof AnonymousClassDeclaration anon) {
                    return initializerRefOf(anon.resolveBinding(), staticContext);
                } else if (n instanceof AbstractTypeDeclaration type) {
                    return initializerRefOf(type.resolveBinding(), staticContext);
                }
            }
            return null;
        }

        /** 初期化子の中のラムダのための、囲みメソッド相当の {@link MethodRef} */
        private MethodRef initializerRefOf(ITypeBinding type, boolean staticContext) {
            String fqn = binding.declTypeName(type);
            if (fqn == null || fqn.isEmpty()) {
                return null;
            }
            String pkg = (type.getPackage() == null) ? "" : type.getPackage().getName();
            return new MethodRef(pkg, fqn,
                    staticContext ? MethodRef.STATIC_INITIALIZER : MethodRef.CONSTRUCTOR, "");
        }
    }

    /** 合成メソッドの名前に使う囲みメソッド名。javac に合わせて &lt;init&gt; は new にする */
    static String baseNameOf(MethodRef enclosing) {
        if (MethodRef.CONSTRUCTOR.equals(enclosing.name())) {
            return "new";
        }
        if (MethodRef.STATIC_INITIALIZER.equals(enclosing.name())) {
            return "static";
        }
        return enclosing.name();
    }

}
