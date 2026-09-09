// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.ConditionalExpression;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.SwitchExpression;
import org.eclipse.jdt.core.dom.SwitchStatement;

import jche.cache.Guard;
import jche.cache.Origin;

/**
 * 呼び出し箇所を囲む条件分岐を、判定できる形（{@link Guard} のアトム）で集める。
 *
 * 呼び出しのASTノードから外側へ親を辿り、通り道の {@code if} / {@code ?:} /
 * {@code &&} / {@code ||} / アロー形式の {@code switch} から
 * 「この呼び出しに到達するには何が成立していなければならないか」を取り出す。
 *
 * <h2>安全側の方針（分からない条件は落とす）</h2>
 * 取り出すのは「引数か定数」と「定数」の比較だけ。それ以外（メソッドの戻り値、
 * フィールドの状態、コレクションの中身…）は条件そのものを記録しない。
 * 記録しなければ読み手は打ち切りの判断をしないので、<b>誤って階層を消すことはない</b>。
 * 逆に、判定できる条件が1つでもあれば、それが成立しない経路では打ち切れる。
 *
 * <h2>意図的に見ないもの</h2>
 * <ul>
 *   <li>ラムダ式・匿名クラス・ローカルクラスの<b>外側</b>の条件 …
 *       その本体は生成箇所と同じタイミングで動くとは限らないため、境界で止める</li>
 *   <li>コロン形式（{@code case X:}）の switch … フォールスルーがあり、
 *       「どの case を通ってきたか」を1つに決められない。アロー形式だけ見る</li>
 *   <li>ループの条件・例外・早期 return … 「呼ばれない」ことの証明にはならない
 *       （到達しない場合があるだけ）ので扱わない</li>
 * </ul>
 */
final class GuardCollector {

    /** 1つの呼び出しに付けるアトムの上限（深く入れ子になった条件で行が伸びるのを防ぐ） */
    private static final int MAX_ATOMS = 8;

    private final OriginTracker origins;

    GuardCollector(OriginTracker origins) {
        this.origins = origins;
    }

    /** 呼び出しノードを囲む条件。判定できる条件が無ければ空文字 */
    String guardOf(ASTNode call) {
        List<String> atoms = new ArrayList<>(2);
        ASTNode child = call;
        ASTNode parent = call.getParent();
        while (parent != null && atoms.size() < MAX_ATOMS) {
            if (parent instanceof BodyDeclaration || parent instanceof LambdaExpression) {
                break;   // メソッド・初期化子・ラムダの境界で止める
            }
            collectFrom(parent, child, atoms);
            child = parent;
            parent = parent.getParent();
        }
        return atoms.isEmpty() ? "" : Guard.join(atoms);
    }

    /** 親ノード1つぶんの条件を足す（child は今いる枝） */
    private void collectFrom(ASTNode parent, ASTNode child, List<String> atoms) {
        if (parent instanceof IfStatement n) {
            if (child == n.getThenStatement()) {
                addCondition(n.getExpression(), true, atoms);
            } else if (child == n.getElseStatement()) {
                addCondition(n.getExpression(), false, atoms);
            }
        } else if (parent instanceof ConditionalExpression n) {
            if (child == n.getThenExpression()) {
                addCondition(n.getExpression(), true, atoms);
            } else if (child == n.getElseExpression()) {
                addCondition(n.getExpression(), false, atoms);
            }
        } else if (parent instanceof InfixExpression n) {
            collectFromInfix(n, child, atoms);
        } else if (parent instanceof SwitchStatement n) {
            addSwitchCase(n.getExpression(), n.statements(), child, atoms);
        } else if (parent instanceof SwitchExpression n) {
            addSwitchCase(n.getExpression(), n.statements(), child, atoms);
        }
    }

    /**
     * {@code a && b} の b、{@code a || b} の b は、a の値が決まってはじめて評価される。
     * 左側（と、それより前の被演算子）が成立していることを条件にする。
     */
    private void collectFromInfix(InfixExpression n, ASTNode child, List<String> atoms) {
        boolean and = n.getOperator() == InfixExpression.Operator.CONDITIONAL_AND;
        if (!and && n.getOperator() != InfixExpression.Operator.CONDITIONAL_OR) {
            return;
        }
        // && なら左が true、|| なら左が false でないと右へ進まない
        List<Object> operands = new ArrayList<>();
        operands.add(n.getLeftOperand());
        operands.add(n.getRightOperand());
        operands.addAll(n.extendedOperands());
        int index = operands.indexOf(child);
        for (int i = 0; i < index; i++) {
            addCondition((Expression) operands.get(i), and, atoms);
        }
    }

    /**
     * アロー形式の switch で、この枝に来るための条件。
     *
     * {@code case A, B ->} なら「選択子が A か B」、{@code default ->} なら
     * 「どの case とも一致しない」。コロン形式は扱わない（フォールスルーのため）。
     */
    private void addSwitchCase(Expression selector, List<?> statements, ASTNode child,
                               List<String> atoms) {
        String origin = evaluableOriginOf(selector);
        if (origin == null) {
            return;
        }
        List<String> allValues = new ArrayList<>();
        for (Object o : statements) {
            if (!(o instanceof SwitchCase sc)) {
                continue;
            }
            if (!sc.isSwitchLabeledRule()) {
                return;   // コロン形式（フォールスルーがあるため扱わない）
            }
            for (Object e : sc.expressions()) {
                String v = constantValueOf((Expression) e);
                if (v == null) {
                    return;   // 定数として読めない case ラベルがある
                }
                allValues.add(v);
            }
        }
        SwitchCase owner = lastCaseBefore(statements, child);
        if (owner == null) {
            return;
        }
        String sel = trim(selector.toString());
        if (owner.isDefault()) {
            atoms.add(Guard.atom(Guard.NOT_IN, origin, Guard.values(allValues),
                    "switch (" + sel + ") の default"));
            return;
        }
        List<String> values = new ArrayList<>();
        for (Object e : owner.expressions()) {
            values.add(constantValueOf((Expression) e));
        }
        atoms.add(Guard.atom(values.size() == 1 ? Guard.EQ : Guard.IN, origin,
                Guard.values(values), "switch (" + sel + ") の case " + String.join(", ", values)));
    }

    private static SwitchCase lastCaseBefore(List<?> statements, ASTNode child) {
        SwitchCase found = null;
        for (Object o : statements) {
            if (o == child) {
                return found;
            }
            if (o instanceof SwitchCase sc) {
                found = sc;
            }
        }
        return null;
    }

    /**
     * 条件式 1つを、期待する真偽値とともにアトムにする。
     *
     * 判定できる形でなければ何も足さない（＝その条件は読み手から見えない）。
     */
    private void addCondition(Expression cond, boolean expected, List<String> atoms) {
        Expression e = OriginTracker.unwrap(cond);
        if (e == null || atoms.size() >= MAX_ATOMS) {
            return;
        }
        if (e instanceof PrefixExpression p && p.getOperator() == PrefixExpression.Operator.NOT) {
            addCondition(p.getOperand(), !expected, atoms);
            return;
        }
        if (e instanceof InfixExpression in) {
            InfixExpression.Operator op = in.getOperator();
            if (op == InfixExpression.Operator.CONDITIONAL_AND && expected
                    || op == InfixExpression.Operator.CONDITIONAL_OR && !expected) {
                // 「かつ」で全体が true、「または」で全体が false のときだけ、
                // 各項がその値だと言い切れる（逆はどれか1つが決まるだけ）
                addCondition(in.getLeftOperand(), expected, atoms);
                addCondition(in.getRightOperand(), expected, atoms);
                for (Object x : in.extendedOperands()) {
                    addCondition((Expression) x, expected, atoms);
                }
                return;
            }
            if (op == InfixExpression.Operator.EQUALS || op == InfixExpression.Operator.NOT_EQUALS) {
                addComparison(in, expected, atoms);
            }
            return;
        }
        if (e instanceof MethodInvocation mi) {
            addEqualsCall(mi, expected, atoms);
            return;
        }
        // boolean の変数・引数・定数そのもの
        String origin = evaluableOriginOf(e);
        if (origin != null && isBoolean(e)) {
            atoms.add(Guard.atom(Guard.EQ, origin, String.valueOf(expected), trim(e.toString())));
        }
    }

    /**
     * {@code x == 定数} / {@code x != 定数}。
     *
     * 参照型どうしの {@code ==} は同一性の比較なので、値が同じでも false になりうる。
     * プリミティブと列挙型に限る（{@code null} との比較も、変数が null でないと
     * 言い切れないため扱わない）。
     */
    private void addComparison(InfixExpression in, boolean expected, List<String> atoms) {
        Expression left = OriginTracker.unwrap(in.getLeftOperand());
        Expression right = OriginTracker.unwrap(in.getRightOperand());
        if (left == null || right == null || !comparableByValue(left) || !comparableByValue(right)) {
            return;
        }
        String value = constantValueOf(right);
        Expression subject = left;
        if (value == null) {
            value = constantValueOf(left);
            subject = right;
        }
        if (value == null) {
            return;
        }
        String origin = evaluableOriginOf(subject);
        if (origin == null) {
            return;
        }
        boolean eq = (in.getOperator() == InfixExpression.Operator.EQUALS) == expected;
        atoms.add(Guard.atom(eq ? Guard.EQ : Guard.NE, origin, Guard.clean(value),
                trim(in.toString())));
    }

    /** {@code s.equals("x")}。equals は値の比較なので、参照型でも判定できる */
    private void addEqualsCall(MethodInvocation mi, boolean expected, List<String> atoms) {
        if (!"equals".equals(mi.getName().getIdentifier()) || mi.arguments().size() != 1) {
            return;
        }
        Expression recv = mi.getExpression();
        Expression arg = (Expression) mi.arguments().get(0);
        if (recv == null || arg == null) {
            return;
        }
        String value = constantValueOf(arg);
        Expression subject = recv;
        if (value == null) {
            value = constantValueOf(recv);
            subject = arg;
        }
        if (value == null) {
            return;
        }
        String origin = evaluableOriginOf(subject);
        if (origin == null) {
            return;
        }
        atoms.add(Guard.atom(expected ? Guard.EQ : Guard.NE, origin, Guard.clean(value),
                trim(mi.toString())));
    }

    /** 値の一致で判定してよい型か（プリミティブ・列挙型・文字列） */
    private static boolean comparableByValue(Expression e) {
        ITypeBinding tb = e.resolveTypeBinding();
        return tb != null && (tb.isPrimitive() || tb.isEnum()
                || "java.lang.String".equals(tb.getQualifiedName()));
    }

    private static boolean isBoolean(Expression e) {
        ITypeBinding tb = e.resolveTypeBinding();
        return tb != null && ("boolean".equals(tb.getName()) || "java.lang.Boolean".equals(tb.getQualifiedName()));
    }

    /** その式の定数値。定数でなければ null */
    private String constantValueOf(Expression e) {
        return Origin.constantValueOf(origins.constantOf(e));
    }

    /**
     * 読み手が経路ごとに値を求められる出所か。
     * 囲みメソッドの引数（A:）と定数（V:）だけを通す。
     * ローカル変数は出所の表を経由して A: / V: に畳まれる。
     */
    private String evaluableOriginOf(Expression e) {
        String origin = Origin.head(origins.originOf(e));
        char kind = Origin.kindOf(origin);
        if (kind == Origin.PARAM || kind == Origin.CONST) {
            return origin;
        }
        String constant = origins.constantOf(e);
        return (constant == null) ? null : Origin.head(constant);
    }

    /** 注記に出す条件式のテキスト。長い式は縮める */
    private static String trim(String text) {
        String t = Guard.clean(text).replaceAll("\\s+", " ").trim();
        return (t.length() <= Guard.MAX_TEXT) ? t : t.substring(0, Guard.MAX_TEXT) + "…";
    }
}
