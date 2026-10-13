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
    /** 選択（JVMS 5.4.6 の手順で、具象型ごとに実際に動く本体を選ぶ）。実装探索の入口はここだけ */
    private final MethodSelection selection = new MethodSelection(methods, hierarchy, overrides);
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
     * "typeFqn#fieldName" -> 宣言の型（V 行の消去型の FQN。文字列の置き場の番号）。参照型（基本型・String・配列でない）の
     * フィールドだけで、値を読まない指定では作らない。そのフィールドの値を実引数として渡したとき、実行時の型の上限
     * （{@link Slot#BOUND}）にする材料（{@link DataflowResolver#bindArgs}）
     */
    final HashMap<String, Integer> fieldDeclTypes = new HashMap<>();
    /**
     * ソースが引数でない値を入れる、参照型の static でないフィールド（"typeFqn#fieldName"）。
     * DI（段 5）はこれを注入点にしない（{@link FieldFacts}。別のファイルからの書き込みも含む。
     * 値を読まない指定でも J 行の種別の列から作る）
     */
    final HashSet<String> ownValuedFields = new HashSet<>();
    /**
     * 値を読まない指定でだけ作る: フィールドを宣言した型 -> その型の {@link #ownValuedFields} のフィールドの宣言の型
     * （宣言の型の分からない、よその書き込みだけのフィールドは {@link FieldFacts#ANY_TYPE}）。
     * レシーバがどのフィールドかが分からないときの粗い判定に使う（{@link #mayReadOwnValuedField}）
     */
    final HashMap<String, Set<String>> ownValuedTypes = new HashMap<>();
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
    /**
     * ワークスペースの他のプロジェクトのファイルのパスの前置き（{@code ../app-batch/}。jche.WorkspaceProject の prefix）。
     * 並びは設定の {@code workspace.projects} の順で、i 番目はプロジェクト番号 i+1（0 はこの実行の project.root）
     */
    List<String> workspacePrefixes = List.of();

    CallGraph() {
        // ラムダ・メソッド参照が実装し直しているメソッド（M 行）は、部分型の宣言を見るだけでは「別の本体へ振り分けられうる」
        // と分からないので、選択に M 行の判定を渡す（Issue #176）
        selection.functionalImpls(this::hasFunctionalImpl);
    }

    public MethodTable methods() {
        return methods;
    }

    public TypeHierarchy hierarchy() {
        return hierarchy;
    }

    /**
     * 選択（JVMS 5.4.6）: 具象型で実際に動く本体を選ぶ。{@link MethodSelection#implementationOf} と
     * {@link MethodSelection#implementationOfSignature} が実装探索の 2 つの入口
     */
    public MethodSelection selection() {
        return selection;
    }

    /**
     * 単純名で書かれた型名を FQN に直す道具。最初に要るときだけ作る
     * （ライブラリ呼び出し規則と拡張を使わない実行では作らない）
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

    /**
     * そのエッジを持つ呼び出し元のメソッドID。エッジは呼び出し元ごとに並んでいるので、区切り（offsets）を
     * 二分探索で引く（エッジごとの表を持たない。ヒープを増やさないため）。範囲外なら -1
     */
    public int callerOf(int edgeIndex) {
        if (edgeIndex < 0 || offsets.length < 2 || edgeIndex >= offsets[offsets.length - 1]) {
            return -1;
        }
        int lo = 0;
        int hi = offsets.length - 2;
        // offsets[id] <= edgeIndex < offsets[id + 1] を満たす id を探す（空の区切りは飛ばす）
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (offsets[mid] <= edgeIndex) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
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

    /** ソースがそのフィールドに引数でない値を入れるか（{@link #ownValuedFields}） */
    public boolean isOwnValued(String fieldKey) {
        return ownValuedFields.contains(fieldKey);
    }

    /**
     * フィールド "typeFqn#fieldName" の宣言の型（消去型の FQN。{@link #fieldDeclTypes}）。
     * 参照型でない・宣言が無い・値を読まない指定なら null
     */
    public String fieldDeclType(String fieldKey) {
        Integer id = fieldDeclTypes.get(fieldKey);
        return (id == null) ? null : values.strings().get(id);
    }

    /**
     * 値を読まない指定で、{@code callerType} のメソッドが {@code this} のフィールド（修飾の無い名前・{@code this.f}・
     * 外側のインスタンスの {@code f}）として読む、型が {@code receiverType} に当たるフィールドに、
     * {@link #ownValuedFields} のものがありうるか。
     *
     * <p>レシーバがどのフィールドかは値の表にしか無いので、読みうるフィールドを型で絞る。見る型は、呼び出しを書いた型と
     * その親、外側の型（入れ子・ローカル・匿名の型の外側）とその親。フィールドの宣言の型と {@code receiverType}
     * （呼び出しを修飾する型。無ければ呼び出し先を宣言した型）が同じか、どちらかがもう片方の部分型なら当たる。
     * {@code receiverType} が {@code java.lang.Object}（{@code toString()} など。修飾する型が残らない）なら、
     * どのフィールドにも当たる。宣言の型の分からないもの（{@link FieldFacts#ANY_TYPE}）もどれにも当たる。
     * 同じ型の注入のフィールドと並んでいると、そちらの呼び出しも「ありうる」になる（絞らない側。
     * docs/spring-di-qa.md の Q16）
     */
    public boolean mayReadOwnValuedField(String callerType, String receiverType) {
        if (callerType == null || ownValuedTypes.isEmpty()) {
            return false;
        }
        ArrayDeque<String> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        for (String t = callerType; t != null && seen.add(t); t = enclosingTypeOf(t)) {
            queue.add(t);
        }
        while (!queue.isEmpty()) {
            String t = queue.poll();
            Set<String> declTypes = ownValuedTypes.get(t);
            if (declTypes != null) {
                for (String d : declTypes) {
                    if (d.equals(FieldFacts.ANY_TYPE) || receiverType == null || "java.lang.Object".equals(receiverType)
                            || d.equals(receiverType) || hierarchy.isSubtypeOf(d, receiverType)
                            || hierarchy.isSubtypeOf(receiverType, d)) {
                        return true;
                    }
                }
            }
            for (String s : hierarchy.directSupertypes(t)) {
                if (seen.add(s)) {
                    queue.add(s);
                }
            }
        }
        return false;
    }

    /**
     * 外側の型の名前。入れ子の型は {@code p.Outer.Inner}、ローカル・匿名の型は {@code p.Outer$1Local}・{@code p.Outer$1}
     * （jche.analysis.BindingNames#typeNameOf）。名前を切り詰めた形がソース上の型（H 行）でなければ null（パッケージ）
     */
    private String enclosingTypeOf(String type) {
        int cut = Math.max(type.lastIndexOf('.'), type.lastIndexOf('$'));
        if (cut <= 0) {
            return null;
        }
        String outer = type.substring(0, cut);
        return hierarchy.contains(outer) ? outer : null;
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
     * そのメソッドを宣言したプロジェクトの番号。0 はこの実行の project.root、1 以降は設定の {@code workspace.projects} の
     * 順（{@link #workspaceCount} まで）。ソースの無いメソッド（jar の中）は -1
     */
    public int projectOf(int methodId) {
        return projectIndexOf(methods.declFile(methodId));
    }

    /** ファイルのパス（{@code jche.config.ProjectLayout#relativeOf} の綴り）が属するプロジェクトの番号。null なら -1 */
    public int projectIndexOf(String declFile) {
        if (declFile == null) {
            return -1;
        }
        for (int i = 0; i < workspacePrefixes.size(); i++) {
            if (declFile.startsWith(workspacePrefixes.get(i))) {
                return i + 1;
            }
        }
        return 0;
    }

    /** ワークスペースの他のプロジェクトの数 */
    public int workspaceCount() {
        return workspacePrefixes.size();
    }

    /**
     * declFile が属するソースフォルダの、ソースフォルダ順のインデックス。不明なら最大値。
     * どちらも {@code jche.config.ProjectLayout#relativeOf} の綴り（区切りは {@code /}。名前の中の {@code \} は
     * そのまま）なので、綴りを直さずに比べる
     */
    public int sourceFolderIndexOf(String declFile) {
        if (declFile == null) {
            return Integer.MAX_VALUE;
        }
        int bestIndex = Integer.MAX_VALUE;
        int bestLen = -1;
        for (int i = 0; i < sourceFolderOrder.size(); i++) {
            String prefix = sourceFolderOrder.get(i);
            boolean matches = prefix.isEmpty() || declFile.equals(prefix) || declFile.startsWith(prefix + "/");
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
