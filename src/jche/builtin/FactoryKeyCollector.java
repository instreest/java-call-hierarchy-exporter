// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.builtin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.StringLiteral;

import jche.extension.CallSiteHintCollector;
import jche.extension.HintKeys;
import jche.extension.HintSink;
import jche.util.Log;
import jche.util.Names;

/**
 * 同梱のフェーズA拡張: ファクトリメソッドに渡された文字列キーを証拠として残す。
 *
 * <pre>
 *   Dao dao = DaoFactory.get("USER_DAO");   // ← "USER_DAO" を dao に結び付けて残す
 *   dao.find();                              // ← ここの解決で {@link TypeMappingProvider} が引く
 * </pre>
 *
 * 設定:
 * <pre>
 *   resolver.hint.collectors=jche.builtin.FactoryKeyCollector
 *   plugin.factory.methods=jp.co.xxx.DaoFactory#get, jp.co.xxx.ServiceLocator#lookup
 *   # 証拠の種別（省略時 FACTORY_KEY）。対応表の左辺は「種別@値」になる
 *   plugin.factory.hint.kind=FACTORY_KEY
 * </pre>
 *
 * キーに使うのは、実引数のうち最初の文字列リテラル。定数（{@code Keys.USER_DAO}）で
 * 渡している場合は、その定数の単純名を使う。定数の値までは追わないので、対応表の
 * 左辺も同じ単純名で書く（{@code FACTORY_KEY@USER_DAO}）。
 */
public final class FactoryKeyCollector implements CallSiteHintCollector {

    /** 対象のファクトリメソッド（型FQN#メソッド名、カンマ区切り）。型は省略できる（メソッド名だけで照合） */
    public static final String KEY_METHODS = "plugin.factory.methods";
    /** 残す証拠の種別 */
    public static final String KEY_KIND = "plugin.factory.hint.kind";

    private static final String DEFAULT_KIND = "FACTORY_KEY";

    /** @param typeFqn 空文字ならメソッド名だけで照合する */
    private record Target(String typeFqn, String methodName) {
    }

    private final List<Target> targets = new ArrayList<>();
    private String kind = DEFAULT_KIND;

    @Override
    public void init(Properties config, Path configDir) {
        this.kind = config.getProperty(KEY_KIND, DEFAULT_KIND).trim();
        if (this.kind.isEmpty()) {
            this.kind = DEFAULT_KIND;
        }
        for (String one : config.getProperty(KEY_METHODS, "").split(",")) {
            String t = one.trim();
            if (t.isEmpty()) {
                continue;
            }
            int hash = t.lastIndexOf('#');
            if (hash < 0) {
                targets.add(new Target("", t));
            } else {
                targets.add(new Target(t.substring(0, hash).trim(), t.substring(hash + 1).trim()));
            }
        }
        if (targets.isEmpty()) {
            Log.warn(getClass().getSimpleName() + ": " + KEY_METHODS + " が空欄です（何も拾いません）");
        } else {
            Log.info("[plugin] " + getClass().getSimpleName() + ": 対象 " + targets.size() + " 件");
        }
    }

    @Override
    public void collect(MethodInvocation node, CompilationUnit cu, String callerMethodKey, HintSink sink) {
        if (targets.isEmpty() || !matches(node)) {
            return;
        }
        String key = keyArgumentOf(node);
        if (key == null) {
            return;
        }
        // 戻り値を変数で受けているならその変数、受けずにそのまま呼んでいるなら式そのものに結び付ける
        String scopeKey = HintKeys.ofAssignedVariable(node);
        if (scopeKey.isEmpty()) {
            scopeKey = HintKeys.ofPosition(node);
        }
        sink.add(scopeKey, kind, key);
    }

    private boolean matches(MethodInvocation node) {
        String methodName = node.getName().getIdentifier();
        String declaringType = declaringTypeOf(node);
        for (Target target : targets) {
            if (!target.methodName().equals(methodName)) {
                continue;
            }
            if (target.typeFqn().isEmpty()) {
                return true;
            }
            if (declaringType != null && declaringType.equals(target.typeFqn())) {
                return true;
            }
            // バインディングが解決できない場合の保険。レシーバの見た目（DaoFactory.get(...) の
            // 「DaoFactory」）が対象の単純名と一致すれば拾う。解決できているときは上で判定済み
            if (declaringType == null && node.getExpression() instanceof SimpleName recv
                    && recv.getIdentifier().equals(Names.simpleOf(target.typeFqn()))) {
                return true;
            }
        }
        return false;
    }

    /** 呼び出し先を宣言している型のFQN。解決できなければ null */
    private static String declaringTypeOf(MethodInvocation node) {
        IMethodBinding binding = node.resolveMethodBinding();
        if (binding == null) {
            return null;
        }
        ITypeBinding type = binding.getDeclaringClass();
        return (type == null) ? null : type.getErasure().getQualifiedName();
    }

    /** 実引数のうち最初の文字列リテラル。無ければ最初の単純名（定数）。どちらも無ければ null */
    private static String keyArgumentOf(MethodInvocation node) {
        List<?> args = node.arguments();
        for (Object o : args) {
            if (o instanceof StringLiteral literal) {
                return literal.getLiteralValue();
            }
        }
        for (Object o : args) {
            if (o instanceof SimpleName name) {
                return name.getIdentifier();
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + targets;
    }

}
