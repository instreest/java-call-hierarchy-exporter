// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;

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
 */
final class TypeShape {

    /** 親型を辿る深さの上限（jar の型を経由しても十数段で足りる） */
    private static final int MAX_DEPTH = 64;

    private TypeShape() {
    }

    /** 型 {@code tb} の形の行を {@code out} に足す */
    static void collect(ITypeBinding tb, List<String> out) {
        if (tb == null) {
            return;
        }
        String owner = keyOf(tb) + ' ';
        out.add(owner + "T " + tb.getModifiers() + ' ' + kindOf(tb));
        walk(tb, owner, out, new HashSet<>(), 0);
    }

    private static void walk(ITypeBinding t, String owner, List<String> out, Set<String> seen, int depth) {
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
        for (IMethodBinding m : t.getDeclaredMethods()) {
            StringBuilder sb = new StringBuilder(owner).append("M ").append(m.getKey()).append(' ')
                    .append(m.getModifiers()).append(' ').append(keyOf(m.getReturnType()));
            for (ITypeBinding p : m.getParameterTypes()) {
                sb.append(' ').append(keyOf(p));
            }
            out.add(sb.toString());
        }
        for (IVariableBinding f : t.getDeclaredFields()) {
            out.add(owner + "F " + f.getKey() + ' ' + f.getModifiers() + ' ' + keyOf(f.getType()));
        }
        for (ITypeBinding member : t.getDeclaredTypes()) {
            out.add(owner + "N " + keyOf(member) + ' ' + member.getModifiers() + ' ' + kindOf(member));
        }
        walk(t.getSuperclass(), owner, out, seen, depth + 1);
        for (ITypeBinding i : t.getInterfaces()) {
            walk(i, owner, out, seen, depth + 1);
        }
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
