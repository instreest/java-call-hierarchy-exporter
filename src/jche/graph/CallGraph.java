// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jche.cache.HintFact;
import jche.cache.ModifierTokens;
import jche.extension.Hint;

/**
 * CSR（Compressed Sparse Row）形式の呼び出しグラフ。フェーズ2の中心となるデータ。
 *
 * offsets[callerId] .. offsets[callerId + 1] が、その呼び出し元のエッジ範囲。
 * その範囲の calleeIds[] / callLines[] が各エッジの内容。
 * エッジ1本あたり int 2個で済むため、オブジェクトで持つ場合に比べ桁違いに省メモリ。
 *
 * 構築は {@link CallGraphBuilder}（キャッシュを 1 回スキャンし、エッジは一時ファイルを経て置く）。
 * 解決は {@link CallResolver} と {@link DataflowResolver} が、このクラスの事実を読んで行う。
 * 現在の出力は下流（呼び出し先）のみ使うため、逆引きCSRは構築していない。
 */
public final class CallGraph {

    final MethodTable methods = new MethodTable();
    final TypeHierarchy hierarchy = new TypeHierarchy();
    /** 上書き関係の逆引き（O行から。{@link OverrideIndex} 参照） */
    final OverrideIndex overrides = new OverrideIndex();
    /** 単純名 -> FQN の索引。{@link #typeNames()} で遅延して作る */
    private TypeNames typeNames;
    /** DIコンテナのBean定義（H行・V行・D行のアノテーションから） */
    SpringBeans beans = SpringBeans.DISABLED;

    // --- CSR（エッジ数ぶんの配列。CallGraphBuilder が埋める） ---
    int[] offsets;      // 長さ methods.size() + 1
    int[] calleeIds;    // 長さ = エッジ数
    int[] callLines;    // 長さ = エッジ数
    byte[] bindKinds;   // 長さ = エッジ数。束縛の種別（BindKind）
    byte[] recvKinds;   // 長さ = エッジ数。レシーバの由来（RecvKind）

    /**
     * エッジごとの、呼び出しを修飾する型（JLS 13.1。C 行の qualifier）。-1 なら宣言した型と同じ。
     * 値は値の表と同じ文字列の置き場（{@link StringPool}）の番号（型名は激しく重複するため）
     */
    int[] qualifierIds;
    /** "typeFqn" の集まり。コンストラクタ注入されたフィールドを持つ型（{@link #hasInjectedFields} が遅延して作る） */
    private Set<String> typesWithInjectedFields;

    // --- 値（読み手はここを読む。CallGraphBuilder が値の表へ取り込む） ---

    /**
     * 値の表（呼び出し箇所・戻り値・フィールドへの代入・条件の値）。値を読まない指定なら空。
     * 文字列の置き場（{@link ValueStore#strings}）は、修飾する型（{@link #qualifierOf}）も持つ
     */
    ValueStore values;
    /** 条件の表 */
    GuardTable guardTable;
    /** エッジごとのレシーバの参照（{@link ValueStore}）。無ければ {@link ValueStore#NONE} */
    int[] recvNodes;
    /** エッジごとの実引数の並びのノードの参照。無ければ {@link ValueStore#NONE} */
    int[] argsNodes;
    /** エッジごとの条件の番号（{@link GuardTable}）。無ければ {@link GuardTable#NONE} */
    int[] guardRefs;
    /** 戻り値の参照の範囲（{@code returnOff[メソッドID]} から {@code returnOff[メソッドID + 1]} の手前まで） */
    int[] returnOff = new int[1];
    /** 戻り値の参照（追跡できない return は {@link ValueStore#NONE}） */
    int[] returnRef = new int[0];
    /** "typeFqn#fieldName" -> 代入される値の頭（葉の参照）。コンストラクタ注入されたフィールドだけが入る */
    final HashMap<String, Integer> fieldHeads = new HashMap<>();
    /**
     * メソッドIDごとの「呼び出しがこの宣言の本体以外へ振り分けられうるか」のメモ
     * （0 = まだ調べていない、1 = 振り分けられない、2 = 振り分けられうる）。{@link #hasOverriders} が遅延して埋める
     */
    private byte[] overriddenMemo;

    /**
     * ラムダ／メソッド参照が実装している関数型インターフェースのメソッドキー。
     * ここに載っているメソッドは「ソース上に見えている実装のほかに、
     * 展開できない実装がある」ことを意味する。
     * 書き手が、そのメソッドが上書きしている親インターフェースの宣言の鍵も書いてある
     * （{@code jche.analysis.FactVisitor#recordFunctionalImpl}）ので、ここは完全一致で引いてよい。
     */
    final Set<String> functionalImpls = new HashSet<>();

    /** エッジごとの証拠。-1 なら証拠なし。値は hintTable のインデックス */
    int[] edgeHint;
    private final ArrayList<List<Hint>> hintTable = new ArrayList<>();
    /**
     * 同じ証拠のリストを hintTable に 2 回載せないための逆引き（C 行・U 行の hints 列の文字列 -> インデックス。
     * 構築時だけ使い、{@link #finishBuild} で捨てる）。同じ変数への呼び出しが複数あれば同じリストを共有する
     */
    private HashMap<String, Integer> hintIndex = new HashMap<>();

    /**
     * 起点の並び替え用。ソースフォルダの順（プロジェクトルートからの相対パス。
     * 例: "src/main/java"）。main/test 等のソースフォルダが混在して出力されるのを避けるために使う。
     */
    List<String> sourceFolderOrder = List.of();

    CallGraph() {
    }

    public MethodTable methods() {
        return methods;
    }

    public TypeHierarchy hierarchy() {
        return hierarchy;
    }

    /**
     * 単純名で書かれた型名を FQN に直す道具。最初に要るときだけ作る
     * （契約表と拡張を使わない実行では作らない）
     */
    public TypeNames typeNames() {
        TypeNames local = typeNames;
        if (local == null) {
            local = new TypeNames(hierarchy);
            typeNames = local;
        }
        return local;
    }

    public SpringBeans beans() {
        return beans;
    }

    public int typeCount() {
        return hierarchy.size();
    }

    public int methodCount() {
        return methods.size();
    }

    public int edgeCount() {
        return calleeIds.length;
    }

    // --- エッジの参照 ---

    public int edgeStart(int callerId) {
        return offsets[callerId];
    }

    public int edgeEnd(int callerId) {
        return offsets[callerId + 1];
    }

    public int outDegree(int callerId) {
        return edgeEnd(callerId) - edgeStart(callerId);
    }

    /** 呼び出し先（宣言型のメソッド。解決前） */
    public int calleeOf(int edgeIndex) {
        return calleeIds[edgeIndex];
    }

    public int callLineOf(int edgeIndex) {
        return callLines[edgeIndex];
    }

    /**
     * {@code callerId} が、ラムダの合成メソッド {@code lambdaId} を<b>生成した</b>メソッドか。
     *
     * 生成の辺（囲みメソッド → 合成メソッド。{@code jche.analysis.FactVisitor#synthesizeLambda}）は
     * 呼び出し先が合成メソッドそのものになる唯一の辺なので、宣言どおりの呼び出し先に
     * その合成メソッドを持つ辺があるかで判定できる。{@code r.run()} のような関数型インターフェース
     * 経由の辺は、宣言どおりの呼び出し先が {@code Runnable#run()} なのでここには当たらない。
     *
     * 読み手は、ラムダが捕捉した引数（{@link jche.cache.Origin#CAPTURED}）を
     * 生成したメソッドの段でだけ当てるためにこれを使う。
     */
    public boolean createsLambda(int callerId, int lambdaId) {
        if (callerId < 0 || callerId + 1 >= offsets.length) {
            return false;
        }
        for (int e = offsets[callerId], end = offsets[callerId + 1]; e < end; e++) {
            if (calleeIds[e] == lambdaId) {
                return true;
            }
        }
        return false;
    }

    /** 束縛の種別（{@link BindKind}） */
    public char bindKindOf(int edgeIndex) {
        return (char) bindKinds[edgeIndex];
    }

    /** レシーバの由来（jche.cache.RecvKind） */
    public char recvKindOf(int edgeIndex) {
        return (char) recvKinds[edgeIndex];
    }

    /**
     * 呼び出しを修飾する型（JLS 13.1）。呼び出し先を宣言した型と同じなら null。
     * CHA の候補はこの型の部分型に限られる（{@link CallResolver} の段 1）
     */
    public String qualifierOf(int edgeIndex) {
        int i = qualifierIds[edgeIndex];
        return (i < 0) ? null : values.strings().get(i);
    }

    /** 値の表（呼び出し箇所・戻り値・フィールドへの代入・条件の値。{@link ValueStore}） */
    public ValueStore values() {
        return values;
    }

    /** 条件の表（{@link GuardTable}） */
    public GuardTable guards() {
        return guardTable;
    }

    /** エッジのレシーバの参照（{@link ValueStore}）。無ければ {@link ValueStore#NONE} */
    public int recvNode(int edgeIndex) {
        return recvNodes[edgeIndex];
    }

    /** エッジの実引数の並びのノードの参照（{@link ValueStore#ARG_LIST}）。無ければ {@link ValueStore#NONE} */
    public int argsNode(int edgeIndex) {
        return argsNodes[edgeIndex];
    }

    /** エッジを囲む条件の番号（{@link GuardTable}）。無ければ {@link GuardTable#NONE} */
    public int guardOf(int edgeIndex) {
        return guardRefs[edgeIndex];
    }

    /** エッジに結び付いた証拠。無ければ空 */
    public List<Hint> hintsOf(int edgeIndex) {
        int i = edgeHint[edgeIndex];
        return (i < 0) ? List.of() : hintTable.get(i);
    }

    // --- メソッド・型の事実 ---

    /**
     * そのメソッドの return が返しうる値の数（R 行。同じ参照は 1 つにまとめてある）。
     * 追跡できない return も {@link ValueStore#NONE} として数える。R 行が無ければ 0
     */
    public int returnCount(int methodId) {
        return (methodId < 0 || methodId + 1 >= returnOff.length)
                ? 0 : returnOff[methodId + 1] - returnOff[methodId];
    }

    /** そのメソッドの k 番目の戻り値の参照（ファイル上で初めて現れた順）。追跡できなければ {@link ValueStore#NONE} */
    public int returnAt(int methodId, int k) {
        return returnRef[returnOff[methodId] + k];
    }

    /** コンストラクタ注入されたフィールド "typeFqn#fieldName" に必ず入る値の頭（葉の参照）。無ければ {@link ValueStore#NONE} */
    public int fieldHead(String fieldKey) {
        Integer head = fieldHeads.get(fieldKey);
        return (head == null) ? ValueStore.NONE : head;
    }

    /** その型がコンストラクタ注入されたフィールドを持つか（{@link #fieldHead} に載っているフィールドがあるか） */
    public boolean hasInjectedFields(String typeFqn) {
        if (typeFqn == null || fieldHeads.isEmpty()) {
            return false;
        }
        if (typesWithInjectedFields == null) {
            typesWithInjectedFields = new HashSet<>();
            for (String key : fieldHeads.keySet()) {
                typesWithInjectedFields.add(key.substring(0, key.indexOf('#')));
            }
        }
        return typesWithInjectedFields.contains(typeFqn);
    }

    /**
     * その呼び出し先が「ラムダ／メソッド参照でも実装されているメソッド」か。
     *
     * true のとき、ソース上に見えている実装のほかに展開できない実装がある。
     * 候補が1件に見えても、それが実際に動く唯一の実装とは限らない。
     */
    public boolean hasFunctionalImpl(int calleeId) {
        return !functionalImpls.isEmpty()
                && functionalImpls.contains(methods.typeFqn(calleeId) + "#" + methods.signature(calleeId));
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
        return search(typeFqn, methods.signature(calleeId),
                overrides.overridersOf(methods.key(calleeId)), packageAccessOf(calleeId));
    }

    /**
     * そのメソッドを呼び出し先（ソースに書かれた呼び出しの静的なキー）とする呼び出しが、実行時に
     * この宣言の本体とは<b>別の本体へ振り分けられうる</b>か。
     *
     * <p>メソッドの return の値（R 行。{@link #returnAt}）を「その呼び出しの戻り値」として使ってよいのは、
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
     *       （部分型が上書きしていない。final クラスは部分型を持たないのでここに入る）</li>
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
            if (implementationOf(sub, methodId) != methodId) {
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
        return search(typeFqn, sig, overrides.overridersOfSignature(sig), null);
    }

    /**
     * その型から親へ幅優先で辿り、最初に見つかった本体を持つ実装を返す。無ければ -1。
     *
     * <h4>2 つの軸を同じ探索の中で見る</h4>
     * キーの照合を先に通して駄目なら上書きを見る、では正しくない。
     * {@code class OrderStore extends AbstractStore<Order>} が {@code put} を具体化して
     * 上書きしている場合、キーの照合だけで辿ると<b>親の実装</b>に先に当たってしまい、
     * 「上書きは無い」と結論してしまう。実際に動くのは、その型から親へ辿って
     * <b>最初に見つかる実装</b>なので、各段で両方の軸を見る。
     *
     * @param overriders その呼び出し先を上書きしているメソッド。無ければ null
     *                   （その場合はキーの照合だけになる＝ジェネリクスを使わない大多数）
     * @param packageAccess 呼び出し先がパッケージアクセスなら、その宣言のパッケージ。別パッケージの
     *                   同じシグネチャの宣言は上書きではないので飛ばして親へ進む（JLS 8.4.8.1）。
     *                   O 行の上書きは JDT の判定（{@code IMethodBinding.overrides}）なので、ここでは見ない
     */
    private int search(String typeFqn, String sig, IntArray overriders, String packageAccess) {
        if (typeFqn == null || typeFqn.isEmpty()) {
            return -1;
        }
        ArrayDeque<String> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(typeFqn);
        seen.add(typeFqn);
        while (!queue.isEmpty()) {
            String t = queue.poll();
            // 上書きを先に見る。シグネチャが同じ上書きは O行に書かないので、ここで当たるのは
            // 「型引数を具体化した上書き」だけであり、親から継承した同シグネチャの宣言より
            // こちらが優先される（実際に動くのは、より近い型の上書きのほう）
            if (overriders != null) {
                int overriding = declaredAmong(overriders, t);
                if (overriding >= 0) {
                    return overriding;
                }
            }
            int id = methods.idOf(t + "#" + sig);
            if (id >= 0 && methods.hasBody(id)
                    && (packageAccess == null || packageAccess.equals(methods.pkg(id))
                        || overridesAcrossPackage(id, sig, packageAccess))) {
                return id;
            }
            for (String sup : hierarchy.directSupertypes(t)) {
                if (seen.add(sup)) {
                    queue.add(sup);
                }
            }
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

    /** declFile が属するソースフォルダの、ソースフォルダ順のインデックス。不明なら最大値 */
    public int sourceFolderIndexOf(String declFile) {
        if (declFile == null) {
            return Integer.MAX_VALUE;
        }
        String norm = declFile.replace('\\', '/');
        int bestIndex = Integer.MAX_VALUE;
        int bestLen = -1;
        for (int i = 0; i < sourceFolderOrder.size(); i++) {
            String prefix = sourceFolderOrder.get(i);
            boolean matches = prefix.isEmpty() || norm.equals(prefix) || norm.startsWith(prefix + "/");
            if (matches && prefix.length() > bestLen) {
                bestLen = prefix.length();
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    // --- 構築時にだけ使う ---

    /**
     * 構築時: C 行・U 行の hints 列（new された型の FQN のカンマ区切り。書き手が同じファイルの中で
     * 呼び出し元とレシーバの変数を結びつけたもの）を証拠のリストにして、そのインデックスを返す。空なら -1。
     * エッジの配列はまだ無いので、番号にしておき配置のときに置く（{@link #edgeHint}）
     */
    int internHints(String hints) {
        if (hints == null || hints.isEmpty()) {
            return -1;
        }
        Integer index = hintIndex.get(hints);
        if (index != null) {
            return index;
        }
        List<Hint> list = new ArrayList<>(2);
        for (String type : hints.split(",")) {
            Hint h = new Hint(HintFact.KIND_NEW, type);
            if (!type.isEmpty() && !list.contains(h)) {
                list.add(h);
            }
        }
        if (list.isEmpty()) {
            return -1;
        }
        hintTable.add(List.copyOf(list));
        int id = hintTable.size() - 1;
        hintIndex.put(hints, id);
        return id;
    }

    /** 構築が終わったら、構築時にしか使わない索引を捨てる（エッジからは hintTable 経由で引ける） */
    void finishBuild() {
        hintIndex = new HashMap<>();
    }
}
