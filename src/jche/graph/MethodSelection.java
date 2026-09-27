// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jche.cache.ModifierTokens;

/**
 * 選択（selection）: 受け手の実行時のクラスが分かったとして、その呼び出しで<b>実際に動く本体</b>を選ぶ
 * （JVMS 5.4.6 のメソッド選択のエミュレーション）。読み手（フェーズ2）の中で、この判断をする場所はここだけ。
 *
 * <h2>解決と選択の 2 段（このツールの構図）</h2>
 * <pre>
 *   解決（resolution）  javac が書くシンボリック参照（受け手の静的型 + 名前 + ディスクリプタ）を、
 *                       実在する宣言に結び付ける。JLS 15.12.1〜15.12.3（コンパイル時宣言）と JVMS 5.4.3.3 / 5.4.3.4。
 *                       このツールでは書き手（jche.analysis）が JDT の IMethodBinding に任せ、結果を
 *                       C 行の呼び出し先（宣言した型のキー）と qualifier（JLS 13.1 の修飾する型）として
 *                       キャッシュに残す。JVMS は「一度成功した解決は以後も同じ結果」と定める（5.4.3）ので、
 *                       キャッシュに置いてよいのはこちらである
 *   選択（selection）   受け手の実行時のクラス C と解決済みの宣言 mR から、動く本体を呼び出し 1 回ごとに決める
 *                       （JVMS 5.4.6。JLS では 15.12.4.4）。実行時の情報なのでキャッシュには無く、読み手が
 *                       H 行（親クラスの連鎖・継承した実装）・D 行（修飾子・本体の有無）・O 行（上書き）から
 *                       このクラスで毎回組み立てる。C の候補（どの実行時のクラスがありうるか）を数えるのは
 *                       {@link CallResolver} の段（CHA・LOCAL_NEW・値の追跡・DI・契約表）で、ここはその 1 つ 1 つの
 *                       C について本体を返すだけである
 * </pre>
 *
 * <h2>JVMS 5.4.6 の手順と、このクラスの対応</h2>
 * <ol>
 *   <li><b>mR が private なら mR そのもの</b>（ディスパッチしない）… ここには来ない。private・static・final・
 *       コンストラクタ・{@code super.m()} は {@link BindKind}（C 行の calleeMods から）が「静的束縛」と決め、
 *       {@link CallResolver} の段 0 で宣言のまま確定する（invokestatic / invokespecial に当たる呼び出し）。
 *       ただし {@link #implementationOf} をその型自身の private の宣言に対して引くことはあり、その場合は
 *       その宣言を返す（連鎖の先頭だけは private を飛ばさない）</li>
 *   <li><b>C とその親クラスの連鎖に mR を上書きできる宣言があればそれ</b>（JVMS 5.4.5 の「上書きできる」）
 *       … {@link #search} の前半。{@link TypeHierarchy#classChain}（H 行の 7 列目）を根まで順に見る。
 *       「上書きできる」の判定は 3 つの材料の和:
 *       <ul>
 *         <li>キー（{@code 型#名前(消去した引数型)}）が同じ宣言（{@link #declarationIn}）。JVMS が「名前とディスクリプタが同じ」
 *             と言う条件の、ソースの側の写し。パッケージアクセスの宣言は同じパッケージのものだけ
 *             （JVMS 5.4.5 / JLS 8.4.8.1。{@link #packageAccessOf}・{@link #overridesAcrossPackage}）</li>
 *         <li>型引数を具体化してキーの食い違う上書き（O 行。{@link OverrideIndex}）。JDT の
 *             {@code IMethodBinding.overrides}（JLS 8.4.8.1）の結果で、javac ならブリッジメソッドで
 *             ディスクリプタをそろえる形。ソース解析ではブリッジが見えないので、書き手が事実として残す</li>
 *         <li>親クラスから継承したメソッドが、型引数を置き換えた親インターフェースのメソッドを実装する組
 *             （H 行の 8 列目。{@link #inheritedImplementationIn}）。その型から見たときだけ成り立つので O 行には書けない</li>
 *       </ul>
 *       その型より上の private は飛ばす（継承されない。JLS 8.4.8）。本体の無い宣言（抽象）では止まらず親へ進む</li>
 *   <li><b>クラスの連鎖に無ければ、C の最も特定的な親インターフェースのメソッドのうち非 abstract のものがちょうど 1 つ</b>
 *       （JVMS 5.4.3.3 の maximally-specific）… {@link #search} の後半。{@link TypeHierarchy#superinterfaces} の宣言
 *       （private・static は継承されないので除く。JLS 9.4.1）を {@link TypeHierarchy#mostSpecific} で絞り、本体を持つものを採る。
 *       複数残る（JLS ではコンパイルエラーになる形か、jar の型の親が見えない形）ときは、ソースに本体のある宣言を jar の宣言より
 *       先にし、その中は近い順の先頭。JVM なら IncompatibleClassChangeError になる形なので、ここは「候補を落とさない側」の近似</li>
 * </ol>
 * 「名前や引数型の文字列比較で上書きを判定している箇所」は、この 2 段目のキーの照合そのものである。消去したキーが同じ
 * ＝ディスクリプタが同じなので JVMS の条件とは一致するが、JLS 8.4.8.1 のサブシグネチャ（型引数の置換）はキーに現れず、
 * O 行と H 行の 8 列目で補っている。漏れを疑うときは、この 3 つの材料のどれにも載らない形を探す
 * （docs/resolution-selection-design.md の対応表）。
 *
 * <h2>入口は 2 つ</h2>
 * 呼び出し先のキーが分かるなら {@link #implementationOf}、シグネチャしか分からない（契約表・リフレクション）なら
 * {@link #implementationOfSignature}。どちらも「継承」と「型引数の置換」の 2 つの軸を 1 つの探索で見るので、
 * 別の引き方を足すと片方を取りこぼす（docs/jls-conformance-qa.md の Q6・Q7・Q21）。
 *
 * <h2>ここで決めないこと</h2>
 * <ul>
 *   <li>受け手の実行時のクラスの候補（どの型がありうるか）… {@link CallResolver}</li>
 *   <li>静的束縛かどうか … {@link BindKind}</li>
 *   <li>解決（コンパイル時宣言）… 書き手（jche.analysis.BindingNames#toRef が JDT の getMethodDeclaration を使う）</li>
 *   <li>ラムダ・メソッド参照（invokedynamic）の本体 … {@link CallResolver} の functionalResolution と M 行</li>
 * </ul>
 */
public final class MethodSelection {

    private final MethodTable methods;
    private final TypeHierarchy hierarchy;
    private final OverrideIndex overrides;
    /**
     * メソッドIDごとの「呼び出しがこの宣言の本体以外へ振り分けられうるか」のメモ
     * （0 = まだ調べていない、1 = 振り分けられない、2 = 振り分けられうる）。{@link #hasOverriders} が遅延して埋める
     */
    private byte[] overriddenMemo;

    MethodSelection(MethodTable methods, TypeHierarchy hierarchy, OverrideIndex overrides) {
        this.methods = methods;
        this.hierarchy = hierarchy;
        this.overrides = overrides;
    }

    /**
     * その具象型で、呼び出し先 {@code calleeId} として実際に動く実装。無ければ -1。
     *
     * <h4>2 つの軸で探す</h4>
     * <ol>
     *   <li><b>継承</b> … キーが同じ宣言を、その型から親へ辿って探す。
     *       {@code UserDao extends AbstractDao} で {@code select()} が親にしか無い形</li>
     *   <li><b>型引数の置換</b> … キーが食い違う上書きを O行（{@link OverrideIndex}）から引く。
     *       {@code class UserRepo implements Repo<User>} の {@code save(User)} が
     *       {@code Repo#save(java.lang.Object)} を上書きしている形</li>
     * </ol>
     * どちらか一方だけを見ると、もう一方の形の実装が候補から落ちる。落ちた結果が
     * 「実装なし（NO_IMPL）」や、実装が他に1つあるときの「別の実装に確定（SINGLE_IMPL）」になる。
     *
     * 呼び出し先の<b>キーが分かっている</b>ときの入口。段1のCHA・段2のLOCAL_NEW・
     * 段3の契約と拡張・段4・段5はすべてここを通す。キーを持たない引き方は
     * {@link #implementationOfSignature} を使う。
     */
    public int implementationOf(String typeFqn, int calleeId) {
        return search(typeFqn, methods.key(calleeId), methods.signature(calleeId),
                overrides.overridersOf(methods.key(calleeId)), packageAccessOf(calleeId));
    }

    /**
     * そのメソッドを呼び出し先（ソースに書かれた呼び出しの静的なキー）とする呼び出しが、実行時に
     * この宣言の本体とは<b>別の本体へ振り分けられうる</b>か。
     *
     * <p>メソッドの return の値（R 行。{@link CallGraph#returnAt}）を「その呼び出しの戻り値」として使ってよいのは、
     * 呼び出しがこの宣言の本体でしか動かないときだけ。{@code Base b; b.mode()} の {@code mode} を
     * 部分型が上書きしていれば、実際に動くのは部分型の本体かもしれず、Base の return の値を当てると
     * 呼ばれる呼び出しを [UNREACHABLE] にしたり、違う具象クラスへ絞ったりして、呼び出しを黙って落とす。
     *
     * <p>振り分けられない（false）のは次のどちらか。
     * <ul>
     *   <li>静的に束縛される: static・private・final のメソッド、コンストラクタ、static 初期化子、
     *       ラムダの本体。static と private は、部分型が同じシグネチャを宣言しても上書きではない
     *       （隠蔽か別のメソッド。JLS 8.4.8）ので、部分型を調べる前に決める</li>
     *   <li>宣言した型のソース上の部分型のどれから引いても、実際に動く実装がこの宣言のまま
     *       （部分型が上書きしていない。final クラスは部分型を持たないのでここに入る）で、
     *       部分型からこの宣言までの間に jar のクラスが挟まらない（{@link #passesBinaryClass}。
     *       {@code class Impl extends lib.Holder<Dao> implements Fac} では、Fac の default より Holder の
     *       見えない宣言が勝ちうる）</li>
     * </ul>
     * ソースに宣言の無いメソッド（jar の中）は、部分型を漏れなく数えられないので「振り分けられうる」とする
     * （分からないものは使わない側に倒す）。{@code super.m()} の形も区別できないので仮想の呼び出しとして扱う
     * （使わない側に倒れるだけで、呼び出しを落とすことはない）
     */
    public boolean hasOverriders(int methodId) {
        if (methodId < 0 || methodId >= methods.size()) {
            return true;
        }
        byte[] memo = overriddenMemo;
        if (memo == null || memo.length != methods.size()) {
            memo = new byte[methods.size()];
            overriddenMemo = memo;
        }
        if (memo[methodId] == 0) {
            memo[methodId] = (byte) (dispatchesElsewhere(methodId) ? 2 : 1);
        }
        return memo[methodId] == 2;
    }

    private boolean dispatchesElsewhere(int methodId) {
        if (!methods.hasSource(methodId)) {
            return true;
        }
        if (methods.isConstructor(methodId) || methods.isStaticInitializer(methodId)
                || methods.isLambdaBody(methodId)) {
            return false;
        }
        String mods = methods.mods(methodId);
        if (ModifierTokens.has(mods, "static") || ModifierTokens.has(mods, "private")
                || ModifierTokens.has(mods, "final")) {
            return false;
        }
        // CHA（CallResolver の段 1）と同じく、部分型ごとに実際に動く実装を implementationOf で引く。
        // 上書きの判定を別に書くと、継承と型引数の置換のどちらかの形を取りこぼす。
        // 部分型から引けない（-1）ことは型階層が揃っていれば起きないが、起きたら別の本体があるとみなす
        for (String sub : hierarchy.transitiveSubtypes(methods.typeFqn(methodId))) {
            if (implementationOf(sub, methodId) != methodId || passesBinaryClass(sub, methodId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 型 {@code type} から実装 {@code implId} を探す道のり（{@link #search} の順）に、ソースの無いクラス（jar・JDK の
     * クラス。{@link TypeHierarchy#classChain} に並ぶ H 行の無い型）が挟まるか。
     *
     * <p>挟まれば、実際に動く実装はそのクラスの宣言かもしれない。jar のクラスのメソッドは、ソースのどこかが
     * それを呼び出し先にしていない限りメソッドの表に無く、{@link #search} は見えないまま通り過ぎる。
     * {@code class Impl extends lib.Holder<Dao> implements Fac} で {@code Holder} の {@code create()} が動くのに、
     * 見えるのは {@code Fac} の default の {@code create()} だけ、という形である（クラスのメソッドが勝つ。JLS 8.4.8）。
     * 見つけた実装を「その型で動く本体」として、その return の値で呼び出しを絞ってはいけない（{@link #hasOverriders}・
     * {@code DataflowResolver} のメソッド参照の束縛したレシーバ）。候補に並べるのは構わない（多すぎる側）。
     * 見つけた実装の型より上にある jar のクラスは、その型の宣言を上書きできないので数えない。
     * 親インターフェースの default（連鎖に無い型の宣言）なら、連鎖の jar のクラスをすべて数える
     */
    public boolean passesBinaryClass(String type, int implId) {
        if (type == null || implId < 0 || implId >= methods.size()) {
            return false;
        }
        String declaring = methods.typeFqn(implId);
        for (String t : hierarchy.classChain(type)) {
            if (t.equals(declaring)) {
                return false;
            }
            if (!hierarchy.contains(t)) {
                return true;
            }
        }
        return false;
    }

    /**
     * そのメソッドを呼び出し先とする呼び出しが実行時に動きうる、この宣言<b>以外</b>の本体
     * （宣言した型のソース上の部分型それぞれで実際に動く実装のうち、この宣言でないもの。
     * 引き方は {@link #hasOverriders} と同じ）。無ければ空。静的に束縛されるかどうかは見ない
     */
    public IntArray overridingImplementations(int methodId) {
        IntArray out = new IntArray(2);
        if (methodId < 0 || methodId >= methods.size()) {
            return out;
        }
        for (String sub : hierarchy.transitiveSubtypes(methods.typeFqn(methodId))) {
            int id = implementationOf(sub, methodId);
            if (id >= 0 && id != methodId) {
                out.addIfAbsent(id);
            }
        }
        return out;
    }

    /**
     * 呼び出し先がパッケージアクセス（public / protected / private のどれでもない）の、ソースにある
     * 宣言なら、そのパッケージ。そうでなければ null。
     *
     * パッケージアクセスのメソッドは、同じパッケージで宣言されたメソッドからしか上書きされない
     * （JLS 8.4.8.1）。別パッケージのサブクラスが同じシグネチャを宣言しても、それは別のメソッドで、
     * 親の型で呼んだときには動かない。修飾子は D 行からしか分からないので、jar のメソッドには使わない
     * （分からないものは「判定しない」に倒す）。
     */
    private String packageAccessOf(int calleeId) {
        if (!methods.hasSource(calleeId)) {
            return null;
        }
        String mods = methods.mods(calleeId);
        if (ModifierTokens.has(mods, "public") || ModifierTokens.has(mods, "protected")
                || ModifierTokens.has(mods, "private")) {
            return null;
        }
        return methods.pkg(calleeId);
    }

    /**
     * 別パッケージの宣言 {@code id} が、パッケージ {@code pkg} のパッケージアクセスのメソッドを
     * 上書きしているか（JLS 8.4.8.1）。
     *
     * 直接は上書きできないが、推移的には上書きしうる。{@code pkg} の中の中間の型が同じシグネチャを
     * public か protected で宣言し直していれば、その宣言は元のメソッドを上書きしていて
     * （同じパッケージなので）、別パッケージの宣言はその中間の宣言を上書きできる。
     * 親型をすべて見て、そういう中間の宣言が 1 つでもあれば上書きとみなす（取りこぼさない側に倒す）。
     */
    private boolean overridesAcrossPackage(int id, String sig, String pkg) {
        ArrayDeque<String> queue = new ArrayDeque<>(hierarchy.directSupertypes(methods.typeFqn(id)));
        Set<String> seen = new HashSet<>(queue);
        while (!queue.isEmpty()) {
            String t = queue.poll();
            int mid = methods.idOf(t + "#" + sig);
            if (mid >= 0 && pkg.equals(methods.pkg(mid))) {
                String mods = methods.mods(mid);
                if (ModifierTokens.has(mods, "public") || ModifierTokens.has(mods, "protected")) {
                    return true;
                }
            }
            for (String sup : hierarchy.directSupertypes(t)) {
                if (seen.add(sup)) {
                    queue.add(sup);
                }
            }
        }
        return false;
    }

    /**
     * その具象型で、シグネチャ {@code sig} として実際に動く実装。無ければ -1。
     *
     * 呼び出し先のキー（宣言している型）が分からず、<b>シグネチャだけが分かっている</b>
     * ときの入口。使うのは 2 か所で、どちらも構造上それしか分からない。
     * <ul>
     *   <li>呼び戻しの契約表（{@link CallbackContracts}）… 契約は
     *       {@code java.lang.Thread#start() -> c* : run()} のように
     *       「呼び戻されるメソッドのシグネチャ」だけを書く。{@code run()} を宣言している型
     *       （{@code java.lang.Runnable}）は契約のどこにも現れないので、キーは作れない</li>
     *   <li>リフレクション（{@link DataflowResolver}）… {@code Method.invoke} の実引数から
     *       名前と引数型を組み立てるので、宣言している型は分からない</li>
     * </ul>
     * そのため上書きの引きもシグネチャで行う（{@link OverrideIndex#overridersOfSignature}）。
     * キーで引く場合と違い、同じシグネチャに消去される別々のジェネリック型を 1 つの型が
     * 両方とも上書きしていると、どちらが選ばれるかは決まらない。ただしこれは
     * キーの照合（{@code 型#シグネチャ}）が元から持っている曖昧さと同じで、
     * 契約表の仕組みがシグネチャで名指しする以上、ここで新たに生じるものではない。
     */
    public int implementationOfSignature(String typeFqn, String sig) {
        return search(typeFqn, null, sig, overrides.overridersOfSignature(sig), null);
    }

    /**
     * その型で実際に動く実装を、JVM がメソッドを選ぶのと同じ順（JVMS 5.4.6。JLS 8.4.8 の継承の決まり）で探す。無ければ -1。
     *
     * <ol>
     *   <li><b>親クラスの連鎖</b>（{@link TypeHierarchy#classChain}）… その型から親クラスへ根まで順に見て、最初に
     *       本体を持つ宣言を採る。クラスのメソッドは、親インターフェースの default メソッドより常に勝つ
     *       （{@code class Impl extends Mid implements Api} で {@code Mid} の親 {@code Base} の {@code m()} が
     *       {@code Api} の default の {@code m()} より先）。その型より上の private メソッドは上書きも継承もされないので
     *       飛ばす（JLS 8.4.8。その型自身の宣言は、private の呼び出し先を引くときのために飛ばさない）。
     *       親クラスの static メソッドは飛ばさない。インスタンスメソッドと同じシグネチャの static メソッドを継承する
     *       クラスはコンパイルできない（JLS 8.4.8.2）ので仮想呼び出しでは当たらず、当たるのはリフレクション
     *       （{@code Class.getMethod} は親クラスの public な static メソッドも返す）でだけ</li>
     *   <li><b>親インターフェース</b>（{@link TypeHierarchy#superinterfaces}）… 連鎖に無ければ、連鎖の型が実装する
     *       インターフェースすべての宣言（private と static は継承されないので除く。JLS 9.4.1）のうち、ほかの宣言の型の
     *       真の親型で宣言したものを除いた「最も特定的な」宣言（JVMS 5.4.3.3）から、本体を持つものを採る。
     *       {@code interface I2 extends I1} の両方に default があれば、I1 が先に並んでいても I2 のもの。
     *       最も特定的な宣言が複数残る（JLS ではコンパイルエラーになる形か、jar の型の親が分からず関係が見えない形）
     *       ときは、ソースに本体のある宣言を jar の宣言（本体の有無が分からず、抽象のこともある）より先にし、
     *       その中は近い順（同じ深さは名前順）の先頭。抽象の宣言も「最も特定的」の判定には加える（本体の無い宣言で
     *       default を宣言し直した形は、実行時にもその default を選ばない）</li>
     * </ol>
     * 親型を名前順の幅優先で混ぜて辿ると、名前や深さの違いで親インターフェースの default や jar のインターフェースの
     * メソッド（ソースが無い）がクラスのメソッドより先に当たり、実際に動く実装が呼び出しの先から消える。
     *
     * <h4>2 つの軸を各段で見る</h4>
     * キーの照合を先に通して駄目なら上書きを見る、では正しくない。
     * {@code class OrderStore extends AbstractStore<Order>} が {@code put} を具体化して
     * 上書きしている場合、キーの照合だけで辿ると<b>親の実装</b>に先に当たってしまい、
     * 「上書きは無い」と結論してしまう。各段（型）で両方の軸を見る。
     *
     * <h4>継承した実装</h4>
     * 各段では、その型の H 行の「継承した実装」（{@link TypeHierarchy#inheritedImplementations}）も見る。
     * {@code class UserRepo extends BaseRepo implements Repo<User>} で {@code BaseRepo.save(User)} が
     * {@code Repo#save(java.lang.Object)} を実装する形は、キーも O 行も当たらない（その型から見たときだけの関係）。
     *
     * @param calleeKey 呼び出し先のキー（継承した実装を引く）。null ならシグネチャで引く
     * @param overriders その呼び出し先を上書きしているメソッド。無ければ null
     *                   （その場合はキーの照合だけになる＝ジェネリクスを使わない大多数）
     * @param packageAccess 呼び出し先がパッケージアクセスなら、その宣言のパッケージ。別パッケージの
     *                   同じシグネチャの宣言は上書きではないので飛ばして親へ進む（JLS 8.4.8.1）。
     *                   O 行の上書きは JDT の判定（{@code IMethodBinding.overrides}）なので、ここでは見ない
     */
    private int search(String typeFqn, String calleeKey, String sig, IntArray overriders, String packageAccess) {
        if (typeFqn == null || typeFqn.isEmpty()) {
            return -1;
        }
        List<String> chain = hierarchy.classChain(typeFqn);
        for (int i = 0; i < chain.size(); i++) {
            int id = declarationIn(chain.get(i), sig, overriders, packageAccess);
            if (id >= 0 && methods.hasBody(id)
                    && (i == 0 || !ModifierTokens.has(methods.mods(id), "private"))) {
                return id;
            }
            int inherited = inheritedImplementationIn(chain.get(i), calleeKey, sig);
            if (inherited >= 0 && methods.hasBody(inherited)) {
                return inherited;
            }
        }
        List<String> declaring = new ArrayList<>();
        IntArray found = new IntArray(2);
        for (String t : hierarchy.superinterfaces(typeFqn)) {
            int id = declarationIn(t, sig, overriders, packageAccess);
            if (id >= 0 && !ModifierTokens.has(methods.mods(id), "private")
                    && !ModifierTokens.has(methods.mods(id), "static")) {
                declaring.add(t);
                found.add(id);
            }
        }
        if (found.size() == 0) {
            return -1;
        }
        List<String> specific = hierarchy.mostSpecific(declaring);
        int fallback = -1;
        for (int i = 0; i < found.size(); i++) {
            int id = found.get(i);
            if (!specific.contains(declaring.get(i)) || !methods.hasBody(id)) {
                continue;
            }
            if (methods.hasSource(id)) {
                return id;
            }
            if (fallback < 0) {
                fallback = id;
            }
        }
        return fallback;
    }

    /**
     * 型 {@code t} の H 行が持つ「継承した実装」のうち、呼び出し先（キー。null ならシグネチャ {@code sig}）を
     * 実装するもの。無ければ -1
     */
    private int inheritedImplementationIn(String t, String calleeKey, String sig) {
        for (String pair : hierarchy.inheritedImplementations(t)) {
            int gt = pair.indexOf('>');
            if (gt < 0) {
                continue;
            }
            String implemented = pair.substring(0, gt);
            boolean hit = (calleeKey != null) ? implemented.equals(calleeKey)
                    : implemented.substring(implemented.indexOf('#') + 1).equals(sig);
            if (hit) {
                int id = methods.idOf(pair.substring(gt + 1));
                if (id >= 0) {
                    return id;
                }
            }
        }
        return -1;
    }

    /**
     * 型 {@code t} が宣言する、呼び出し先の実装になりうる宣言（本体の有無は問わない）。無ければ -1。
     * 上書き（型引数を具体化したもの。O 行）を先に見る。シグネチャが同じ上書きは O 行に書かないので、ここで
     * 当たるのは「型引数を具体化した上書き」だけ
     */
    private int declarationIn(String t, String sig, IntArray overriders, String packageAccess) {
        if (overriders != null) {
            int overriding = declaredAmong(overriders, t);
            if (overriding >= 0) {
                return overriding;
            }
        }
        int id = methods.idOf(t + "#" + sig);
        if (id >= 0 && (packageAccess == null || packageAccess.equals(methods.pkg(id))
                || overridesAcrossPackage(id, sig, packageAccess))) {
            return id;
        }
        return -1;
    }

    /** その型が宣言している上書きメソッド。無ければ -1 */
    private int declaredAmong(IntArray overriders, String typeFqn) {
        for (int i = 0; i < overriders.size(); i++) {
            int id = overriders.get(i);
            if (methods.hasBody(id) && typeFqn.equals(methods.typeFqn(id))) {
                return id;
            }
        }
        return -1;
    }

}
