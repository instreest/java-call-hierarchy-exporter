// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.analysis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.Modifier;

import jche.cache.MethodRef;

/**
 * 解決（resolution）の側で残す、<b>上書き・実装の関係の事実</b>。JLS 8.4.8.1（上書き）・8.4.2（サブシグネチャ）・
 * 9.8（関数型インターフェースの上書き同等な抽象メソッド）の判定を JDT の {@code IMethodBinding} に任せ、
 * その結果だけをキャッシュの行にする。自前で名前や引数型の文字列を比べて上書きを判定しない
 * （比べるのは、JDT に尋ねる前の絞り込みと、「キーが同じなら書かなくてよい」の判断だけ）。
 *
 * <h2>読み手（選択。jche.graph.MethodSelection）との対応</h2>
 * <pre>
 *   {@link #overriddenKeysOf}           O 行   型引数を具体化してキーの食い違う上書き（IMethodBinding.overrides）
 *                                             → 選択の 2 段目（親クラスの連鎖）と 3 段目（親インターフェース）で
 *                                               「上書きできる宣言」の材料になる（OverrideIndex）
 *   {@link #inheritedImplementationsOf} H 行の 8 列目   親クラスから継承したメソッドが、型引数を置き換えた
 *                                             親インターフェースのメソッドを実装する組（isSubsignature）
 *                                             → 選択の 2 段目で、その型から見たときだけ成り立つ実装として引く
 *   {@link #functionalKeysOf}           M 行の鍵   ラムダ・メソッド参照が実装する抽象メソッドすべて（isSubsignature）
 *                                             → invokedynamic に当たる呼び出しの本体を、親の型で受けた呼び出しからも引く
 * </pre>
 * キーが同じ（消去した引数型が同じ＝ JVM のディスクリプタが同じ）上書きは、読み手がキーの照合で引けるので書かない。
 * javac がブリッジメソッドでディスクリプタをそろえる形（型引数の置換）だけを事実にする。
 *
 * <p>書く事実が変わる変更（絞り込みの条件・書く／書かないの判断）は、キャッシュの形式の版
 * （{@link jche.cache.CacheFormat#VERSION}）を上げる。
 */
final class OverrideFacts {

    private final BindingNames names;

    OverrideFacts(BindingNames names) {
        this.names = names;
    }

    /**
     * その宣言が上書きしている、親型の宣言のキー（{@code typeFqn#name(paramSig)}）。
     * シグネチャ（{@code name(paramSig)}）が自分と同じものは含めない
     * （キーの照合だけで引けるため）。無ければ空。
     *
     * <h4>なぜ要るのか</h4>
     * メソッドのキーは消去済みの引数型で作るが、JLS 8.4.2 のオーバーライドは
     * 「同じシグネチャ<b>または</b>消去したシグネチャと同じ（サブシグネチャ）」なので、
     * 型引数を具体化した実装（{@code class UserRepo implements Repo<User>} の
     * {@code save(User)}）は親（{@code Repo#save(java.lang.Object)}）とキーが一致しない。
     * 一致しないまま候補を引くと「実装が無い」や「別の実装1件に確定」になる
     * （{@code docs/jls-conformance-qa.md} の Q1。ジェネリックなメソッドの上書き、Issue #154）。
     *
     * <h4>判定は JDT に任せる</h4>
     * {@code IMethodBinding.overrides} は JLS 8.4.8.1 の実装なので、アクセス修飾子・
     * 静的メソッドの隠蔽・型変数の置換の扱いを自前で組み直さない。親型は<b>型引数を
     * 具体化したまま</b>（{@code Repo<User>}）辿る。消去してから辿ると置換が失われ、
     * 判定そのものが成り立たない。
     */
    List<String> overriddenKeysOf(IMethodBinding binding) {
        if (binding == null || binding.isConstructor()
                || Modifier.isStatic(binding.getModifiers())
                || Modifier.isPrivate(binding.getModifiers())) {
            // コンストラクタ・static・private は上書きされない（JLS 8.4.8.1）
            return List.of();
        }
        ITypeBinding declaring = binding.getDeclaringClass();
        if (declaring == null) {
            return List.of();
        }
        MethodRef self = names.toRef(binding);
        if (self == null) {
            return List.of();
        }
        String selfSignature = self.signature();
        List<String> keys = new ArrayList<>(1);
        Set<String> seen = new HashSet<>();
        for (ITypeBinding sup : BindingNames.supertypesOf(declaring)) {
            for (IMethodBinding candidate : sup.getDeclaredMethods()) {
                if (candidate.isConstructor()
                        || !candidate.getName().equals(binding.getName())
                        || candidate.getParameterTypes().length != binding.getParameterTypes().length
                        || !binding.overrides(candidate)) {
                    continue;
                }
                MethodRef ref = names.toRef(candidate);
                if (ref == null) {
                    continue;
                }
                // シグネチャ（name(paramSig)）が同じ上書きは書かない。読み手は候補を
                // 「型FQN + '#' + シグネチャ」で引き、その型から親へ辿るので、
                // シグネチャが同じならキーの照合だけで引ける。書いても嵩むだけである。
                // 書くのは型引数の置換でシグネチャが食い違う場合だけ
                String key = ref.key();
                if (!ref.signature().equals(selfSignature) && seen.add(key)) {
                    keys.add(key);
                }
            }
        }
        return keys;
    }

    /**
     * クラス {@code type} で、親クラスから継承したメソッドが親インターフェースのメソッドを実装していて、
     * 両者のキー（消去した引数型）が食い違うものの組（{@code 実装される側のキー>実装する側のキー}。名前順）。
     * {@link jche.cache.TypeFact#inheritedImpls()} に書く。
     *
     * <pre>
     *   interface Repo&lt;T&gt; { void save(T t); }                      Repo#save(java.lang.Object)
     *   class BaseRepo { public void save(User u) {...} }              BaseRepo#save(p.User)
     *   class UserRepo extends BaseRepo implements Repo&lt;User&gt; { }   ← BaseRepo.save が Repo.save を実装する
     * </pre>
     * BaseRepo は Repo を実装していないので、BaseRepo.save の O 行（{@link #overriddenKeysOf}）には現れない。
     * 実装の関係は UserRepo から見たときにだけ成り立ち（JLS 8.4.8.1。javac は UserRepo にブリッジを作る）、
     * {@code class Other extends BaseRepo implements Repo<Order>} では成り立たないので、型ごとの事実にする。
     * 無いと読み手は UserRepo の {@code save(Object)} の実装を見つけられず、{@code Repo<User> r; r.save(u)} を
     * 「実装なし」にして BaseRepo.save を落とす（default があれば default に決めてしまう）。
     *
     * <p>判定は JDT に任せる: 親インターフェースのメソッド（型引数を置き換えたもの。private・static は除く）ごとに、
     * {@code type} 自身が同じシグネチャ（{@code isSubsignature}）を宣言していなければ、親クラスを近い順に見て
     * 最初に {@code isSubsignature} の当たる public の宣言（static と public でないものを除く）を採る（クラスのメソッドが勝つ。JLS 8.4.8）。
     * キーが同じなら読み手はキーの照合で引けるので書かない。実装する側の型が実装される側のインターフェースを
     * 実装していれば、その宣言の O 行が同じことを言うので書かない。
     *
     * <h4>実装する側は public の宣言だけ</h4>
     * JLS 8.4.8 では、パッケージアクセスのメソッドは同じパッケージのサブクラスにしか継承されない。
     * {@code class UserRepo extends a.BaseRepo implements Repo<a.User>}（{@code a.BaseRepo.save(User)} が
     * パッケージアクセス、{@code UserRepo} は別のパッケージ）では {@code BaseRepo.save} は {@code UserRepo} のメンバーでなく、
     * 何も実装しない（javac はブリッジを作らず、{@code Repo.save} の default が動く。Issue #168）。
     * 一方、同じパッケージで継承されても、public でないメソッドが public なインターフェースのメソッドを実装することは
     * JLS 8.4.8.3（弱いアクセス権限）でコンパイルできない。つまりコンパイルできるコードでは、インターフェースのメソッドを
     * 実装する継承したメソッドは public のものに限る。そこで public でない宣言（static・private と同じく）は飛ばして
     * 親へ進む（同じシグネチャの public な宣言がさらに上にあれば、それが実装する。無ければ書かない）。
     * 「パッケージアクセスなら型からそのクラスまでの連鎖が同じパッケージにあるときだけ採る」と書き分ける必要は無い
     * （違いが出るのはコンパイルできないコードだけ）
     */
    List<String> inheritedImplementationsOf(ITypeBinding type) {
        if (type == null || type.isInterface() || type.getSuperclass() == null) {
            return List.of();
        }
        List<IMethodBinding> interfaceMethods = new ArrayList<>();
        ArrayDeque<ITypeBinding> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        for (ITypeBinding c = type; c != null && seen.add(BindingNames.keyOf(c)); c = c.getSuperclass()) {
            queue.addAll(java.util.Arrays.asList(c.getInterfaces()));
        }
        while (!queue.isEmpty()) {
            ITypeBinding i = queue.poll();
            if (!seen.add(BindingNames.keyOf(i))) {
                continue;
            }
            for (IMethodBinding m : i.getDeclaredMethods()) {
                if (!m.isConstructor() && !Modifier.isStatic(m.getModifiers())
                        && !Modifier.isPrivate(m.getModifiers())) {
                    interfaceMethods.add(m);
                }
            }
            queue.addAll(java.util.Arrays.asList(i.getInterfaces()));
        }
        java.util.TreeSet<String> out = new java.util.TreeSet<>();
        for (IMethodBinding mi : interfaceMethods) {
            if (subsignatureIn(type, mi) != null) {
                continue;   // その型自身の宣言が実装する（キーか O 行で引ける）
            }
            Set<String> classes = new HashSet<>();
            for (ITypeBinding sc = type.getSuperclass(); sc != null && classes.add(BindingNames.keyOf(sc));
                    sc = sc.getSuperclass()) {
                IMethodBinding mc = subsignatureIn(sc, mi);
                if (mc == null) {
                    continue;
                }
                if (!Modifier.isPublic(mc.getModifiers())) {
                    continue;   // public でない宣言はインターフェースのメソッドを実装できない（JLS 8.4.8・8.4.8.3。Issue #168）
                }
                MethodRef implemented = names.toRef(mi);
                MethodRef implementing = names.toRef(mc);
                if (implemented != null && implementing != null
                        && !implemented.signature().equals(implementing.signature())
                        && !isSupertypeOf(mi.getDeclaringClass(), mc.getDeclaringClass())) {
                    out.add(implemented.key() + ">" + implementing.key());
                }
                break;
            }
        }
        return out.isEmpty() ? List.of() : new ArrayList<>(out);
    }

    /** 型 {@code t} が宣言する、{@code mi} の subsignature（JLS 8.4.2）のインスタンスメソッド（private を除く）。無ければ null */
    private static IMethodBinding subsignatureIn(ITypeBinding t, IMethodBinding mi) {
        for (IMethodBinding m : t.getDeclaredMethods()) {
            if (!m.isConstructor() && m.getName().equals(mi.getName())
                    && m.getParameterTypes().length == mi.getParameterTypes().length
                    && !Modifier.isStatic(m.getModifiers()) && !Modifier.isPrivate(m.getModifiers())
                    && isSubsignature(m, mi)) {
                return m;
            }
        }
        return null;
    }

    /**
     * {@code m} が {@code mi} の subsignature か（JLS 8.4.2）。JDT の {@code isSubsignature} は、{@code m} を宣言した型が
     * パラメータ化された型（{@code GenBase<String>} の {@code save(String)}）だと型引数を置き換える前の宣言
     * （{@code save(T)}）に戻して比べるので、置き換えた引数の型がそろう（同じシグネチャ）ことは先に自分で見る。
     * 型引数を持つメソッドは JDT の判定だけにする
     */
    private static boolean isSubsignature(IMethodBinding m, IMethodBinding mi) {
        if (m.getTypeParameters().length == 0 && mi.getTypeParameters().length == 0) {
            ITypeBinding[] a = m.getParameterTypes();
            ITypeBinding[] b = mi.getParameterTypes();
            boolean same = true;
            for (int k = 0; k < a.length && same; k++) {
                same = a[k].isEqualTo(b[k]);
            }
            if (same) {
                return true;
            }
        }
        return m.isSubsignature(mi);
    }

    /** {@code sup} が {@code sub} の親型（消去して比べる）か */
    private static boolean isSupertypeOf(ITypeBinding sup, ITypeBinding sub) {
        if (sup == null || sub == null) {
            return false;
        }
        String want = BindingNames.keyOf(sup.getErasure());
        for (ITypeBinding t : BindingNames.supertypesOf(sub)) {
            if (BindingNames.keyOf(t.getErasure()).equals(want)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 関数型インターフェース {@code fnType} のラムダ／メソッド参照が実装するメソッドの鍵すべて。
     *
     * JLS 9.8 では、関数型インターフェースの抽象メソッドは1つとは限らない。親から継承した
     * 抽象メソッドのうち、互いに上書き同等（シグネチャが一方の subsignature）なものはまとめて
     * 1つの関数型を成し、ラムダはその<b>すべて</b>を実装する。JDT の
     * {@code getFunctionalInterfaceMethod} はそのうちの1つしか返さないので、残りをここで集める。
     * <pre>
     *   interface A { void go(); }  interface B { void go(); }  interface C extends A, B {}
     *   C c = () -> ...;   // A#go() と B#go() の両方を実装する（SAM は片方だけ）
     *
     *   interface StrFoo extends Foo&lt;String&gt; { void accept(String s); }
     *   // StrFoo#accept(java.lang.String) と Foo#accept(java.lang.Object) の両方
     * </pre>
     * 読み手（M 行）は鍵の完全一致で引くので、親の宣言の鍵が無いと、親の型で受けた変数への
     * 呼び出しでラムダが見えず、別の実装1件に誤って確定する（docs/lambda-expansion-qa.md の Q11・Q15）。
     * 親型は型引数を具体化したまま辿り、上書き同等かの判定は
     * {@code IMethodBinding.isSubsignature}（JLS 8.4.2）に任せる。
     *
     * <p>目標の型は、ラムダ・メソッド参照の式の型として I 行に載る（{@link FactVisitor#preVisit2}）。その親
     * （{@code interface Door extends Opener} の Opener）を変えると返す鍵が変わるが、それは差分更新が部分型の側で
     * 拾う（Door は Opener の部分型なので、Opener が変われば Door も変わった型になる。docs/cache-unification-qa.md の Q77）。
     *
     * @return 先頭は SAM 自身の鍵。SAM の鍵を作れなければ空
     */
    List<String> functionalKeysOf(ITypeBinding fnType, IMethodBinding sam) {
        MethodRef self = names.toRef(sam);
        if (self == null) {
            return List.of();
        }
        List<String> keys = new ArrayList<>(2);
        keys.add(self.key());
        if (fnType == null) {
            return keys;
        }
        // 交差型（(Runnable & Serializable) () -> ...）は、各成分とその親を見る
        List<ITypeBinding> roots = fnType.isIntersectionType()
                ? List.of(fnType.getTypeBounds()) : List.of(fnType);
        Set<String> seenTypes = new HashSet<>();
        for (ITypeBinding root : roots) {
            List<ITypeBinding> types = new ArrayList<>();
            types.add(root);
            types.addAll(BindingNames.supertypesOf(root));
            for (ITypeBinding type : types) {
                if (!type.isInterface() || !seenTypes.add(BindingNames.keyOf(type))) {
                    continue;
                }
                for (IMethodBinding candidate : type.getDeclaredMethods()) {
                    int mods = candidate.getModifiers();
                    if (!Modifier.isAbstract(mods) || Modifier.isStatic(mods)
                            || !candidate.getName().equals(sam.getName())
                            || candidate.getParameterTypes().length != sam.getParameterTypes().length
                            || !(sam.isSubsignature(candidate) || candidate.isSubsignature(sam))) {
                        continue;
                    }
                    MethodRef ref = names.toRef(candidate);
                    if (ref != null && !keys.contains(ref.key())) {
                        keys.add(ref.key());
                    }
                }
            }
        }
        return keys;
    }
}
