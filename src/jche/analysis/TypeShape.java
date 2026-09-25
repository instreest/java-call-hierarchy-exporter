// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.Modifier;

/**
 * 型の「形」。その型を使う側の解決結果が依存するもの（継承したものを含むメンバーの署名・親型・型引数）を
 * 行にしたもの。ファイルの型の形をまとめた指紋を I 行に書き、差分更新の「親型の連鎖」に使う
 * （{@link CacheUpdater}。docs/cache-unification-qa.md の Q44）。
 *
 * <p>ソースが変わっていないのに解析し直したファイルの形が前回と違えば、そのファイルの型を使う側
 * （I 行にはこの型しか無い）の解決結果も変わりうる。親に抽象メソッド・オーバーロードを足した、
 * 親の親型を変えた、jar の親型の版を変えた、といった変化はここに現れる。逆に、親のメソッドの本体・
 * コメントだけの変更では形は変わらない（連鎖しない）。連鎖するかはファイルごとの形の比較だけで決まるので、
 * 同じ周回の中でどの順に解析し直しても結果は変わらない。
 *
 * <p>行は JDT のバインディングのキー（型引数・引数型・戻り値型を含む）と修飾子で作る。行の並びには
 * 意味が無い（指紋は並べ替えてから取る）。{@code java.*} の型のメンバーは辿らない（JDK の版はキャッシュの鍵に
 * 入っていて、変われば全件解析になる）。型の名前（キー）は行に入れる。
 *
 * <h2>メソッドの行に入れるもの</h2>
 * キー・修飾子・戻り値と引数の型に加えて、可変長引数か（{@code m(int[])} と {@code m(int...)} はキーが同じだが、
 * {@code d.m(1, 2)} を呼べるかが変わる。JLS 15.12.2.4）と、throws の型とそれが検査例外か（呼び出し側の
 * 「例外を処理していない」エラーが変わる。JLS 11.2）を入れる（docs/cache-unification-qa.md の Q52）。
 *
 * <h2>私的メンバーは、親のメンバーと名前が当たるものだけ入れる（docs/cache-unification-qa.md の Q51）</h2>
 * 私的メンバーは継承されず（JLS 8.2）、宣言したトップレベルのクラスの外からは参照できない（6.6.1）。そのクラスの
 * 外にいる、この型を使う側の解決結果は、私的メンバーがあってもなくても同じである。例外は、私的メンバーが
 * <b>自分の親型のメンバーを隠す・継承を止める</b>場合で、私的フィールドは親の同じ名前のフィールドを隠し（8.3）、
 * 私的な入れ子の型は親の同じ名前の型を隠し（8.5）、私的メソッドは親の同じシグネチャのメソッドの継承を止める
 * （8.4.8・9.4.1）。どれも部分型から親のメンバーが見えなくなる（{@code c.f} が {@code G.f} からエラーに変わる）。
 * そこで、私的メンバーは、それを宣言した型の親型（推移的に。{@code java.*} の親型とその上も含む）のどれかに、
 * 私的でない同じ名前のメンバー（種類は問わない）があるときだけ行に入れる。私的なコンストラクタは入れない
 * （コンストラクタは継承されず、暗黙の {@code super()} は直接の親のものを呼ぶ。直接の子は親を参照しているので、
 * 親が変われば解析し直す）。
 *
 * <p>判定は私的メンバーを宣言した型とその親型だけで決まり、どの型の形を作っているか（{@code owner}）に依らない。
 * そのため、子・孫の形にも同じ行が入るか入らないかのどちらかで、親の私的メンバーだけを変えても、解析し直さない
 * 子孫の I 行の指紋が古いまま残ることはない。型解決に失敗しているファイルは、JDT がエラーを回復するときに
 * 見えない私的メンバーにもバインディングを返すので、この指紋ではなく依存の側で拾う
 * （{@link BindingNames#noteAncestorsOfNamedTypes}）。
 */
final class TypeShape {

    /** 親型を辿る深さの上限（jar の型を経由しても十数段で足りる） */
    private static final int MAX_DEPTH = 64;

    /**
     * {@code java.lang.Object} の public・protected なメソッドの名前。インターフェースは {@code Object} を親に
     * 持たないが、その public メソッドに当たるメンバーを暗黙に宣言する（JLS 9.2）ので、{@link #namesAbove} が数える
     */
    private static final Set<String> OBJECT_METHOD_NAMES = Set.of("getClass", "hashCode", "equals", "clone",
            "toString", "notify", "notifyAll", "wait", "finalize");

    private TypeShape() {
    }

    /** 型 {@code tb} の形の行を {@code out} に足す */
    static void collect(ITypeBinding tb, List<String> out) {
        if (tb == null) {
            return;
        }
        String owner = keyOf(tb) + ' ';
        out.add(owner + "T " + tb.getModifiers() + ' ' + kindOf(tb));
        walk(tb, owner, out, new HashSet<>(), new HashMap<>(), 0);
    }

    private static void walk(ITypeBinding t, String owner, List<String> out, Set<String> seen,
                             Map<String, Set<String>> namesAbove, int depth) {
        if (t == null || depth > MAX_DEPTH || !seen.add(keyOf(t))) {
            return;
        }
        out.add(owner + "S " + keyOf(t) + ' ' + t.getModifiers() + ' ' + kindOf(t));
        if (isJdk(t)) {
            return;
        }
        for (ITypeBinding p : t.getTypeParameters()) {
            StringBuilder sb = new StringBuilder(owner).append("P ").append(keyOf(t)).append(' ').append(keyOf(p));
            for (ITypeBinding b : p.getTypeBounds()) {
                sb.append(' ').append(keyOf(b));
            }
            out.add(sb.toString());
        }
        Set<String> above = null;   // 親型の私的でないメンバーの名前。私的メンバーがあるときだけ求める
        for (IMethodBinding m : t.getDeclaredMethods()) {
            if (Modifier.isPrivate(m.getModifiers())) {
                if (m.isConstructor()) {
                    continue;
                }
                above = (above != null) ? above : namesAbove(t, namesAbove, 0);
                if (!above.contains(m.getName())) {
                    continue;
                }
            }
            StringBuilder sb = new StringBuilder(owner).append("M ").append(m.getKey()).append(' ')
                    .append(m.getModifiers()).append(' ').append(keyOf(m.getReturnType()))
                    .append(m.isVarargs() ? " V" : " -");
            for (ITypeBinding p : m.getParameterTypes()) {
                sb.append(' ').append(keyOf(p));
            }
            for (ITypeBinding e : m.getExceptionTypes()) {
                sb.append(" throws ").append(keyOf(e)).append(isUnchecked(e) ? " U" : " K");
            }
            out.add(sb.toString());
        }
        for (IVariableBinding f : t.getDeclaredFields()) {
            if (Modifier.isPrivate(f.getModifiers())) {
                above = (above != null) ? above : namesAbove(t, namesAbove, 0);
                if (!above.contains(f.getName())) {
                    continue;
                }
            }
            out.add(owner + "F " + f.getKey() + ' ' + f.getModifiers() + ' ' + keyOf(f.getType()));
        }
        for (ITypeBinding member : t.getDeclaredTypes()) {
            if (Modifier.isPrivate(member.getModifiers())) {
                above = (above != null) ? above : namesAbove(t, namesAbove, 0);
                if (!above.contains(member.getName())) {
                    continue;
                }
            }
            out.add(owner + "N " + keyOf(member) + ' ' + member.getModifiers() + ' ' + kindOf(member));
        }
        walk(t.getSuperclass(), owner, out, seen, namesAbove, depth + 1);
        for (ITypeBinding i : t.getInterfaces()) {
            walk(i, owner, out, seen, namesAbove, depth + 1);
        }
    }

    /**
     * {@code t} の親型（推移的に。{@code t} 自身は含まない）が宣言する、私的でないメンバー（メソッド・フィールド・
     * 入れ子の型。コンストラクタは継承されないので除く）の名前。種類はまたいで見る（JLS の隠蔽は同じ種類どうしだが、
     * 名前の分類（6.5.2）の扱いを JDT の実装に頼らず、広めに取る）。
     *
     * <p>{@code java.*} の親型でも止めず、その上（JDK の親型・インターフェース）まで辿る。私的メンバーが隠すのは
     * 直接の親のメンバーに限らない。{@code Registry extends HashMap<String, Object>} の私的な入れ子の型 {@code Entry} は、
     * {@code HashMap} ではなく {@code Map} が宣言する {@code Map.Entry} を隠す（JLS 8.5）。以前は最初の {@code java.*} の
     * 親型のメンバーの名前だけを数えてその上を辿らず、この {@code Entry} を形から外していたので、部分型の利用者の
     * {@code ((Entry) o).getKey()} が {@code Map.Entry} のまま残った（docs/cache-unification-qa.md の Q65）。
     * JDK の型のメンバーを形の行にしない（{@link #walk}）のは JDK の版がキャッシュの鍵に入っているからで、名前の
     * 当たりを調べるのはそれとは別の話である。
     *
     * <p>インターフェースは {@code Object} を親に持たないが、{@code Object} の public メソッドに当たるメンバーを
     * 暗黙に宣言する（JLS 9.2）ので、その名前（{@link #OBJECT_METHOD_NAMES}）も数える
     */
    private static Set<String> namesAbove(ITypeBinding t, Map<String, Set<String>> memo, int depth) {
        String key = keyOf(t);
        Set<String> known = memo.get(key);
        if (known != null) {
            return known;
        }
        Set<String> names = new HashSet<>();
        memo.put(key, names);   // 循環（コンパイルエラー）でも止まるように、先に置く
        if (depth > MAX_DEPTH) {
            return names;
        }
        if (t.isInterface()) {
            names.addAll(OBJECT_METHOD_NAMES);
        }
        List<ITypeBinding> parents = new ArrayList<>();
        if (t.getSuperclass() != null) {
            parents.add(t.getSuperclass());
        }
        parents.addAll(List.of(t.getInterfaces()));
        for (ITypeBinding s : parents) {
            for (IMethodBinding m : s.getDeclaredMethods()) {
                if (!m.isConstructor() && !Modifier.isPrivate(m.getModifiers())) {
                    names.add(m.getName());
                }
            }
            for (IVariableBinding f : s.getDeclaredFields()) {
                if (!Modifier.isPrivate(f.getModifiers())) {
                    names.add(f.getName());
                }
            }
            for (ITypeBinding member : s.getDeclaredTypes()) {
                if (!Modifier.isPrivate(member.getModifiers())) {
                    names.add(member.getName());
                }
            }
            names.addAll(namesAbove(s, memo, depth + 1));
        }
        return names;
    }

    /**
     * 非検査例外（{@code RuntimeException} か {@code Error} の部分型。JLS 11.1.1）か。型変数は消去（最初の上限）で見る。
     * 親を辿れない（無い型を復元した）ものは検査例外とみなす（どちらでも、変わったときに形が変われば足りる）
     */
    private static boolean isUnchecked(ITypeBinding e) {
        ITypeBinding t = BindingNames.erasureOf(e);
        for (int depth = 0; t != null && depth <= MAX_DEPTH; depth++) {
            String name = t.getQualifiedName();
            if ("java.lang.RuntimeException".equals(name) || "java.lang.Error".equals(name)) {
                return true;
            }
            t = t.getSuperclass();
        }
        return false;
    }

    /** バインディングのキー。解決できずに復元した型（無い型）には印を付ける（あとで型ができたら形が変わるように） */
    private static String keyOf(ITypeBinding t) {
        if (t == null) {
            return "-";
        }
        String key = t.getKey();
        if (key == null) {
            key = t.getQualifiedName();
        }
        return t.isRecovered() ? key + "!R" : key;
    }

    private static char kindOf(ITypeBinding t) {
        if (t.isAnnotation()) {
            return 'A';
        }
        if (t.isInterface()) {
            return 'I';
        }
        if (t.isEnum()) {
            return 'E';
        }
        return t.isRecord() ? 'R' : 'C';
    }

    /** JDK の型か（{@code java.*}。メンバーは JDK の版で決まる） */
    private static boolean isJdk(ITypeBinding t) {
        ITypeBinding e = t.getErasure();
        if (e == null || e.getPackage() == null) {
            return false;
        }
        String p = e.getPackage().getName();
        return p.equals("java") || p.startsWith("java.");
    }
}
