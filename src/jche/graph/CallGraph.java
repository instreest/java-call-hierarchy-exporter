// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import jche.cache.ModifierTokens;
import jche.extension.Hint;

/**
 * CSR（Compressed Sparse Row）形式の呼び出しグラフ。フェーズ2の中心となるデータ。
 *
 * offsets[callerId] .. offsets[callerId + 1] が、その呼び出し元のエッジ範囲。
 * その範囲の calleeIds[] / callLines[] が各エッジの内容。
 * エッジ1本あたり int 2個で済むため、オブジェクトで持つ場合に比べ桁違いに省メモリ。
 *
 * 構築は {@link CallGraphBuilder}（キャッシュを2回スキャン）。
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
     * エッジごとのレシーバ・実引数の出所（jche.cache.Origin）。
     * 値は originPool のインデックスで、-1 なら情報なし。
     *
     * 文字列の配列をエッジ数ぶん持つとメモリ設計が崩れるため、
     * 実体は共有プールに1つずつだけ置き、エッジ側は int で参照する
     * （出所の文字列は "A:0" や型名なので、実際には激しく重複する）。
     */
    int[] recvOriginIds;
    int[] argOriginIds;
    /** エッジごとの、呼び出し箇所を囲む条件分岐（jche.cache.Guard）。-1 なら条件なし */
    int[] guardIds;
    private final ArrayList<String> originPool = new ArrayList<>();
    private final HashMap<String, Integer> originPoolIndex = new HashMap<>();

    /** メソッドIDごとの「返しうる値の出所」。null は情報なし */
    String[][] returnOrigins;
    /** "typeFqn#fieldName" -> 出所。コンストラクタ注入されたフィールドだけが入る */
    final HashMap<String, String> fieldOrigins = new HashMap<>();
    private Set<String> typesWithInjectedFields;

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
     * 同じ証拠のリストを hintTable に 2 回載せないための逆引き（構築時だけ使う）。
     * 同じレシーバへの呼び出しが 1 メソッド内に複数あれば同じリストを共有する
     */
    private IdentityHashMap<List<Hint>, Integer> hintIndex = new IdentityHashMap<>();
    /**
     * callerKey + "|" + scopeKey -> 証拠のリスト。構築時だけ使い、{@link #finishBuild} で捨てる。
     * キーはメソッドキー＋バインディングキーの長い文字列で、ラムダや new のたびに増えるため、
     * 解析が終わるまで抱えているとエッジ配列より大きくなりうる
     */
    HashMap<String, List<Hint>> hintsByScope = new HashMap<>();

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

    /** エッジのレシーバの出所。無ければ null */
    public String recvOrigin(int edgeIndex) {
        int i = recvOriginIds[edgeIndex];
        return (i < 0) ? null : originPool.get(i);
    }

    /** エッジの実引数の出所（"位置=出所;..."）。無ければ null */
    public String argOrigins(int edgeIndex) {
        int i = argOriginIds[edgeIndex];
        return (i < 0) ? null : originPool.get(i);
    }

    /**
     * その呼び出しを囲む条件分岐（jche.cache.Guard）。無ければ null。
     *
     * 「その条件がこの経路で成立しないか」の判定は {@link GuardEvaluator} が行う。
     */
    public String guard(int edgeIndex) {
        int i = guardIds[edgeIndex];
        return (i < 0) ? null : originPool.get(i);
    }

    /** エッジに結び付いた証拠。無ければ空 */
    public List<Hint> hintsOf(int edgeIndex) {
        int i = edgeHint[edgeIndex];
        return (i < 0) ? List.of() : hintTable.get(i);
    }

    // --- メソッド・型の事実 ---

    /** そのメソッドの return が返しうる値の出所（R行）。無ければ null */
    public String[] returnOriginsOf(int methodId) {
        return (returnOrigins == null || methodId < 0 || methodId >= returnOrigins.length)
                ? null : returnOrigins[methodId];
    }

    /** コンストラクタ注入されたフィールド "typeFqn#fieldName" に必ず入る値の出所。無ければ null */
    public String fieldOrigin(String fieldKey) {
        return fieldOrigins.get(fieldKey);
    }

    /** その型がコンストラクタ注入されたフィールドを持つか */
    public boolean hasInjectedFields(String typeFqn) {
        if (typeFqn == null || fieldOrigins.isEmpty()) {
            return false;
        }
        if (typesWithInjectedFields == null) {
            typesWithInjectedFields = new HashSet<>();
            for (String key : fieldOrigins.keySet()) {
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

    /** 出所・条件の文字列を共有プールに入れてインデックスを返す。空なら -1 */
    private int internOrigin(String origin) {
        if (origin == null || origin.isEmpty()) {
            return -1;
        }
        Integer i = originPoolIndex.get(origin);
        if (i != null) {
            return i;
        }
        int id = originPool.size();
        originPool.add(origin);
        originPoolIndex.put(origin, id);
        return id;
    }

    /** エッジのレシーバ由来・証拠・出所を書き込む（C行とU行で共通） */
    void fillCallSite(int pos, String callerKey, String recvKey, char recvKind,
                      String recvOrigin, String argOrigins, String guard) {
        recvKinds[pos] = (byte) recvKind;
        // 呼び出し箇所（呼び出し元メソッド＋レシーバ）に紐づく証拠を引き当てる
        if (!recvKey.isEmpty()) {
            List<Hint> hints = hintsByScope.get(callerKey + "|" + recvKey);
            if (hints != null && !hints.isEmpty()) {
                Integer index = hintIndex.get(hints);
                if (index == null) {
                    hintTable.add(hints);
                    index = hintTable.size() - 1;
                    hintIndex.put(hints, index);
                }
                edgeHint[pos] = index;
            }
        }
        recvOriginIds[pos] = internOrigin(recvOrigin);
        argOriginIds[pos] = internOrigin(argOrigins);
        guardIds[pos] = internOrigin(guard);
    }

    /** 構築が終わったら、構築時にしか使わない索引を捨てる（エッジからは hintTable 経由で引ける） */
    void finishBuild() {
        hintsByScope = new HashMap<>();
        hintIndex = new IdentityHashMap<>();
        originPoolIndex.clear();
    }
}
