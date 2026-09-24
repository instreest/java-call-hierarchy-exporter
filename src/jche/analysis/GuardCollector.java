// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.ConditionalExpression;
import org.eclipse.jdt.core.dom.DoStatement;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.SwitchExpression;
import org.eclipse.jdt.core.dom.SwitchStatement;
import org.eclipse.jdt.core.dom.WhileStatement;

import jche.cache.Guard;
import jche.cache.Origin;
import jche.cache.ValueNode;

/**
 * 呼び出し箇所を囲む条件分岐を、判定できる形（{@link Guard} のアトム）で集める。
 *
 * 呼び出しのASTノードから外側へ親を辿り、通り道の {@code if} / {@code ?:} /
 * {@code &&} / {@code ||} / アロー形式の {@code switch} から
 * 「この呼び出しに到達するには何が成立していなければならないか」を取り出す。
 *
 * <p>判定される式（subject）は値グラフのノード（{@link ValueNode}）で持つ（{@link #evaluableSubjectOf}）。
 * 比べる値（期待値）はコンパイル時定数の値そのもので、切り詰めない（{@link #constantValueOf}）。
 *
 * <h2>安全側の方針（分からない条件は落とす）</h2>
 * 取り出すのは「引数か定数」と「定数」の比較だけ。それ以外（メソッドの戻り値、
 * フィールドの状態、コレクションの中身…）は条件そのものを記録しない。
 * 記録しなければ読み手は打ち切りの判断をしないので、<b>誤って階層を消すことはない</b>。
 * 逆に、判定できる条件が1つでもあれば、それが成立しない経路では打ち切れる。
 *
 * <h2>2つのモード</h2>
 * <ul>
 *   <li><b>判定用</b>（既定）… 打ち切りに使える条件だけをアトムにする。キャッシュの G 行（C 行・U 行の
 *       guard 列が番号で指す）はこちら</li>
 *   <li><b>記録用</b>（{@code recordAll}）… 判定できない条件も {@link Guard#UNKNOWN} として残し、
 *       ループ・{@code catch}・コロン形式の {@code switch} も足す。
 *       「この呼び出しに効いている条件を漏れなく見たい」条件の調査（conditions.target）
 *       （{@link CallConditionScanner}）だけが使う。キャッシュには書かない</li>
 * </ul>
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
    /** 記録用モードの上限。キャッシュに入らないので、判定用より多く残してよい */
    private static final int MAX_ATOMS_RECORD_ALL = 32;
    private static final String STRING = "java.lang.String";

    private final OriginTracker origins;
    /** 判定できない条件も残すか（記録用モード） */
    private final boolean recordAll;
    private final int maxAtoms;

    GuardCollector(OriginTracker origins) {
        this(origins, false);
    }

    GuardCollector(OriginTracker origins, boolean recordAll) {
        this.origins = origins;
        this.recordAll = recordAll;
        this.maxAtoms = recordAll ? MAX_ATOMS_RECORD_ALL : MAX_ATOMS;
    }

    /** 記録用モードのときだけ、判定できない条件をアトムにする（subject は無し） */
    private void addUnknown(List<Guard.Atom> atoms, String text) {
        if (recordAll && atoms.size() < maxAtoms) {
            atoms.add(atom(Guard.UNKNOWN, ValueNode.NONE, List.of(), trim(text)));
        }
    }

    /** アトムを 1 つ作る。text は区切り文字を落として持つ（{@link Guard#clean}。読み手に渡す文字列の形を壊さない） */
    private static Guard.Atom atom(String op, int subject, List<String> values, String text) {
        return new Guard.Atom(op, subject, values, Guard.clean(text));
    }

    /** 呼び出しノードを囲む条件（アトムの論理積）。判定できる条件が無ければ空 */
    List<Guard.Atom> guardOf(ASTNode call) {
        List<Guard.Atom> atoms = new ArrayList<>(2);
        ASTNode child = call;
        ASTNode parent = call.getParent();
        while (parent != null && atoms.size() < maxAtoms) {
            if (parent instanceof BodyDeclaration || parent instanceof LambdaExpression) {
                break;   // メソッド・初期化子・ラムダの境界で止める
            }
            collectFrom(parent, child, atoms);
            child = parent;
            parent = parent.getParent();
        }
        if (recordAll && atoms.size() >= maxAtoms) {
            // 「条件が無い」と「記録を諦めた」を読み手が区別できるようにする
            atoms.add(atom(Guard.MORE, ValueNode.NONE, List.of(),
                    "no further conditions recorded (limit " + maxAtoms + ")"));
        }
        return atoms.isEmpty() ? List.of() : atoms;
    }

    /** 親ノード1つぶんの条件を足す（child は今いる枝） */
    private void collectFrom(ASTNode parent, ASTNode child, List<Guard.Atom> atoms) {
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
        } else if (parent instanceof WhileStatement n) {
            // 以下は「呼ばれないことの証明」には使えないが、到達に効く条件ではあるので記録用では残す
            addUnknown(atoms, "while (" + n.getExpression() + ")");
        } else if (parent instanceof DoStatement n) {
            addUnknown(atoms, "do { … } while (" + n.getExpression() + ")");
        } else if (parent instanceof ForStatement n) {
            Expression cond = n.getExpression();
            addUnknown(atoms, "for (…; " + ((cond == null) ? "" : cond.toString()) + "; …)");
        } else if (parent instanceof EnhancedForStatement n) {
            addUnknown(atoms, "for (" + n.getParameter().getName() + " : " + n.getExpression() + ")");
        } else if (parent instanceof CatchClause n) {
            addUnknown(atoms, "catch (" + n.getException().getType() + ")");
        }
    }

    /**
     * {@code a && b} の b、{@code a || b} の b は、a の値が決まってはじめて評価される。
     * 左側（と、それより前の被演算子）が成立していることを条件にする。
     */
    private void collectFromInfix(InfixExpression n, ASTNode child, List<Guard.Atom> atoms) {
        boolean and = n.getOperator() == InfixExpression.Operator.CONDITIONAL_AND;
        if (!and && n.getOperator() != InfixExpression.Operator.CONDITIONAL_OR) {
            return;
        }
        // && なら左が true、|| なら左が false でないと右へ進まない
        List<Object> operands = new ArrayList<>();
        operands.add(n.getLeftOperand());
        operands.add(n.getRightOperand());
        // extendedOperands() は型引数のない List を返すので、要素ごとに移す（未検査変換を避ける）
        for (Object extended : n.extendedOperands()) {
            operands.add(extended);
        }
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
                               List<Guard.Atom> atoms) {
        int subject = evaluableSubjectOf(selector);
        if (subject == ValueNode.NONE) {
            addUnknown(atoms, "switch (" + selector + ") branch");
            return;
        }
        List<String> allValues = new ArrayList<>();
        for (Object o : statements) {
            if (!(o instanceof SwitchCase sc)) {
                continue;
            }
            if (!sc.isSwitchLabeledRule()) {
                // コロン形式（フォールスルーがあるため判定はしないが、条件としては残す）
                addUnknown(atoms, "switch (" + selector + ") case (colon form)");
                return;
            }
            for (Object e : sc.expressions()) {
                String v = constantValueOf((Expression) e);
                if (v == null) {
                    addUnknown(atoms, "switch (" + selector + ") case");
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
            if (allValues.isEmpty()) {
                // case の無い switch の default は必ず通る（条件ではない）。値の無い NI を書くと、以前の文字列の形
                // （値を区切り文字で並べる）では「値が 1 つも無い」と「空文字の値が 1 つ」を見分けられず、
                // 空文字を渡した経路で default を「成立しない」と誤って判定していた
                return;
            }
            atoms.add(atom(Guard.NOT_IN, subject, allValues, "switch (" + sel + ") default"));
            return;
        }
        List<String> values = new ArrayList<>();
        for (Object e : owner.expressions()) {
            values.add(constantValueOf((Expression) e));
        }
        atoms.add(atom(values.size() == 1 ? Guard.EQ : Guard.IN, subject,
                values, "switch (" + sel + ") case " + String.join(", ", values)));
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
    private void addCondition(Expression cond, boolean expected, List<Guard.Atom> atoms) {
        if (atoms.size() >= maxAtoms) {
            return;
        }
        Expression e = OriginTracker.unwrapValue(cond);
        if (e == null) {
            // 値を変えうるキャストが挟まっている。判定はできないが、条件があることは残す
            addUnknown(atoms, expectedText(cond, expected));
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
            int before = atoms.size();
            if (op == InfixExpression.Operator.EQUALS || op == InfixExpression.Operator.NOT_EQUALS) {
                addComparison(in, expected, atoms);
            }
            if (atoms.size() == before) {
                addUnknown(atoms, expectedText(in, expected));
            }
            return;
        }
        if (e instanceof MethodInvocation mi) {
            int before = atoms.size();
            addEqualsCall(mi, expected, atoms);
            if (atoms.size() == before) {
                addUnknown(atoms, expectedText(mi, expected));
            }
            return;
        }
        // boolean の変数・引数・定数そのもの
        int subject = isBoolean(e) ? evaluableSubjectOf(e) : ValueNode.NONE;
        if (subject != ValueNode.NONE) {
            atoms.add(atom(Guard.EQ, subject, List.of(String.valueOf(expected)), trim(e.toString())));
        } else {
            addUnknown(atoms, expectedText(e, expected));
        }
    }

    /** 記録用モードの条件式のテキスト。否定の枝（else 側）だと分かるように書く */
    private static String expectedText(Expression e, boolean expected) {
        return expected ? e.toString() : "!(" + e + ")";
    }

    /**
     * {@code x == 定数} / {@code x != 定数}。
     *
     * 参照型どうしの {@code ==} は同一性の比較なので、一般には値が同じでも false になりうる。
     * それでも {@link #comparableByValue} が文字列を通しているのは、ここで値を畳めるのが
     * <b>コンパイル時定数だけ</b>だからである。コンパイル時定数の文字列はインターンされて
     * 同じインスタンスになる（JLS 3.10.5）ので、畳める場合に限り {@code ==} は値の比較と一致する。
     * 逆に言えば、畳める出所（{@code L} / {@code A} / {@code M}）を広げるときは、
     * この前提が崩れないかを必ず確かめること。
     *
     * 列挙定数は定数ごとに唯一のインスタンスなので {@code ==} で比較してよい（JLS 8.9）。
     * {@code null} との比較は、変数が null でないと言い切れないため扱わない。
     */
    private void addComparison(InfixExpression in, boolean expected, List<Guard.Atom> atoms) {
        Expression left = OriginTracker.unwrapValue(in.getLeftOperand());
        Expression right = OriginTracker.unwrapValue(in.getRightOperand());
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
        int node = evaluableSubjectOf(subject);
        if (node == ValueNode.NONE) {
            return;
        }
        boolean eq = (in.getOperator() == InfixExpression.Operator.EQUALS) == expected;
        atoms.add(atom(eq ? Guard.EQ : Guard.NE, node, List.of(value), trim(in.toString())));
    }

    /**
     * {@code s.equals("x")}。equals は値の比較なので、参照型でも判定できる。
     *
     * ただし、比べる相手（{@code subject}）と定数の型が揃うときだけ（{@link #equalsComparable}）
     */
    private void addEqualsCall(MethodInvocation mi, boolean expected, List<Guard.Atom> atoms) {
        if (!"equals".equals(mi.getName().getIdentifier()) || mi.arguments().size() != 1
                || !isObjectEquals(mi.resolveMethodBinding())) {
            return;
        }
        Expression recv = mi.getExpression();
        Expression arg = (Expression) mi.arguments().get(0);
        if (recv == null || arg == null) {
            return;
        }
        String value = constantValueOf(arg);
        Expression subject = recv;
        Expression constant = arg;
        if (value == null) {
            value = constantValueOf(recv);
            subject = arg;
            constant = recv;
        }
        if (value == null || !equalsComparable(subject, constant)) {
            return;
        }
        int node = evaluableSubjectOf(subject);
        if (node == ValueNode.NONE) {
            return;
        }
        atoms.add(atom(expected ? Guard.EQ : Guard.NE, node, List.of(value), trim(mi.toString())));
    }

    /**
     * 値の一致で判定してよい型か（浮動小数を除くプリミティブ・列挙型・文字列）。
     * 文字列を通してよい理由は {@link #addComparison} の説明にある（JLS 3.10.5 のインターン）。
     *
     * <h4>浮動小数は判定しない</h4>
     * 値の表記を文字列の一致で比べるので、{@code 1} と {@code 1.0} を同じ値と見られない。
     * 浮動小数の定数は出所にも値グラフにも持たない（{@link OriginTracker#constantOf}・{@link ValueGraph}）が、
     * 整数の実引数は {@code float} / {@code double} の引数へ暗黙に拡大される（JLS 5.3）。
     * {@code int} → {@code float} と {@code long} → {@code float} / {@code double} は精度を失いうる
     * （JLS 5.1.2）ので、{@code f(16777217)} の {@code f == 16777216} は真なのに、表記の比較では偽になる。
     * 比べる片方が浮動小数なら判定しない（{@code docs/jls-conformance-qa.md} の Q14）
     */
    private static boolean comparableByValue(Expression e) {
        ITypeBinding tb = e.resolveTypeBinding();
        if (tb == null || "float".equals(tb.getName()) || "double".equals(tb.getName())) {
            return false;
        }
        return tb.isPrimitive() || tb.isEnum() || STRING.equals(tb.getQualifiedName());
    }

    /** {@code equals(Object)} か（{@code Object#equals} とその上書き）。同じ名前の別の多重定義は中身が分からない */
    private static boolean isObjectEquals(IMethodBinding mb) {
        if (mb == null) {
            return false;
        }
        ITypeBinding[] params = mb.getParameterTypes();
        return params.length == 1 && "java.lang.Object".equals(params[0].getQualifiedName());
    }

    /**
     * {@code equals} の結果を、値の表記の一致で言い当てられる組み合わせか。
     *
     * 条件は値を<b>表記</b>（文字列）で比べるが、{@code equals} は実行時の型が違えば、表記が同じでも偽になる。
     * <pre>
     *     void check(Long id) { if (!id.equals(0)) hit(); }   // 0 は Integer に箱詰めされる
     *     check(0L);                                        // Long.equals(Integer) は常に偽。hit は必ず呼ばれる
     * </pre>
     * 表記ではどちらも {@code 0} なので「{@code !id.equals(0)} は成立しない」と判定し、{@code hit} を落としてしまう。
     * 同じことは {@code Object o} に {@code 5L} / {@code 'A'}（値は数値 65 で持つ）/ 拡大された {@code double} が
     * 入ってくる場合、{@code "5".equals(o)} に {@code 5} が入ってくる場合にも起きる。
     *
     * そこで、比べる相手の<b>静的な型</b>から実行時の型が 1 つに決まり、それが定数と同じ型のときだけ判定する。
     * <ul>
     *   <li>{@code String} の相手と文字列の定数（{@code String} は final）</li>
     *   <li>同じ列挙型の相手と列挙定数（{@code Enum#equals} は final で、同一性の比較）</li>
     *   <li>ボックス型の相手と、それに箱詰めされるプリミティブの定数（{@code Integer} と {@code int}、{@code Long} と
     *       {@code long} など。ボックス型はどれも final）。浮動小数（{@code Float} / {@code Double}）は除く
     *       （{@link #comparableByValue} と同じ理由）</li>
     * </ul>
     * {@code Object}・インターフェース・型変数・型の食い違う組み合わせは判定しない（条件を作らない＝打ち切らない）。
     * 型はキャストを剥がす前の式で見る（{@code (String) o} は {@code String}。実行時に違う型なら
     * キャストで例外になり、その先には進まない）
     */
    private static boolean equalsComparable(Expression subject, Expression constant) {
        ITypeBinding s = subject.resolveTypeBinding();
        ITypeBinding c = constant.resolveTypeBinding();
        if (s == null || c == null) {
            return false;
        }
        if (STRING.equals(s.getQualifiedName())) {
            return STRING.equals(c.getQualifiedName());
        }
        if (s.isEnum()) {
            return c.isEnum() && s.getErasure().isEqualTo(c.getErasure());
        }
        String box = boxOf(c);
        return box != null && box.equals(s.getQualifiedName());
    }

    /** プリミティブ型を箱詰めした型の名前。浮動小数とプリミティブ以外は null */
    private static String boxOf(ITypeBinding type) {
        if (!type.isPrimitive()) {
            return null;
        }
        return switch (type.getName()) {
            case "boolean" -> "java.lang.Boolean";
            case "byte" -> "java.lang.Byte";
            case "short" -> "java.lang.Short";
            case "char" -> "java.lang.Character";
            case "int" -> "java.lang.Integer";
            case "long" -> "java.lang.Long";
            default -> null;   // float / double は表記で比べない。void もここ
        };
    }

    private static boolean isBoolean(Expression e) {
        ITypeBinding tb = e.resolveTypeBinding();
        return tb != null && ("boolean".equals(tb.getName()) || "java.lang.Boolean".equals(tb.getQualifiedName()));
    }

    /**
     * その式の定数値（期待値）。定数でなければ null。
     *
     * 値そのものを返し、切り詰めない。以前は出所の文字列から値を取り出す {@link Origin#valueOf} を通していたので、
     * 最初の {@code |} で切れていた（{@code "a|b"} が {@code "a"}）。条件の値は G 行の列に 1 つずつ置くので、
     * どんな文字を含んでも区切りは崩れない。拾う範囲（64 文字以内・制御文字を含まない・浮動小数を除く）は
     * {@link OriginTracker#constantOf} のまま
     */
    private String constantValueOf(Expression e) {
        String constant = origins.constantOf(e);   // 「V:値」
        return (constant == null) ? null : constant.substring(constant.indexOf(':') + 1);
    }

    /**
     * 読み手が経路ごとに値を求められる式なら、その値グラフのノード。求められなければ {@link ValueNode#NONE}。
     *
     * 囲みメソッドの引数（A）と定数（V）のノードだけを通す。ローカル変数は値グラフを経由して
     * 代入元のノード（A / V）に畳まれる。文字列のコンパイル時定数は、値グラフではリテラル（L）のノードに
     * なるので、定数の値（{@link OriginTracker#constantOf}。64 文字以内で制御文字を含まないもの）を
     * V のノードにする（以前の出所の文字列でも、ここは {@code V:} の定数として拾っていた）。
     *
     * <h4>A にも V にもなりえない式のノードは作らない</h4>
     * 値グラフのノードは作ると消せない（番号で指される）。メソッド呼び出し・new・ラムダなどの式は
     * A にも V にもならないので、ノードを作らずに {@link ValueNode#NONE} を返す（{@link #mayBeParamOrConstant}）
     */
    private int evaluableSubjectOf(Expression ex) {
        // 値を変えうるキャストが挟まっていたら、その先の値を使ってはいけない。
        // 判定に使う式はすべてここを通るので、1 か所で止める（{@link OriginTracker#unwrapValue}）
        Expression e = OriginTracker.unwrapValue(ex);
        if (e == null) {
            return ValueNode.NONE;
        }
        if (!(e.resolveConstantExpressionValue() instanceof String) && mayBeParamOrConstant(e)) {
            int node = origins.nodeOf(e);
            char kind = origins.kindOfNode(node);
            if (kind == Origin.PARAM || kind == Origin.CONST) {
                return node;
            }
        }
        String constant = origins.constantOf(e);   // 「V:値」
        return (constant == null)
                ? ValueNode.NONE : origins.constantNodeOf(constant.substring(constant.indexOf(':') + 1));
    }

    /**
     * 値グラフのノードが引数（A）か定数（V）になりうる形の式か。コンパイル時定数・列挙定数と、
     * フィールドでない変数（引数・ローカル変数。値グラフで代入元に畳まれる）だけ。
     * ほかの形（メソッド呼び出し・new・定数でないフィールド・文字列の連結など）はノードの種別が M・T・F や
     * 「分からない」になるので、ノードを作るまでもない
     */
    private static boolean mayBeParamOrConstant(Expression e) {
        if (e.resolveConstantExpressionValue() != null) {
            return true;
        }
        IBinding b = null;
        if (e instanceof Name name) {
            b = name.resolveBinding();
        } else if (e instanceof FieldAccess fa) {
            b = fa.resolveFieldBinding();
        } else if (e instanceof SuperFieldAccess sfa) {
            b = sfa.resolveFieldBinding();
        }
        return b instanceof IVariableBinding vb && (!vb.isField() || vb.isEnumConstant());
    }

    /** 注記に出す条件式のテキスト。長い式は縮める */
    private static String trim(String text) {
        String t = Guard.clean(text).replaceAll("\\s+", " ").trim();
        return (t.length() <= Guard.MAX_TEXT) ? t : t.substring(0, Guard.MAX_TEXT) + "…";
    }
}
