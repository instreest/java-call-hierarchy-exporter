/*
 * Copyright 2026 the java-call-hierarchy-exporter authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jche.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
     */
    final Set<String> functionalImpls = new HashSet<>();

    /** エッジごとの証拠。-1 なら証拠なし。値は hintTable のインデックス */
    int[] edgeHint;
    private final ArrayList<List<Hint>> hintTable = new ArrayList<>();
    /** callerKey + "|" + scopeKey -> 証拠のリスト */
    final HashMap<String, List<Hint>> hintsByScope = new HashMap<>();

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
     * 具象型 typeFqn で、シグネチャ sig の実装を持つメソッドIDを返す。無ければ -1。
     *
     * その型自身に宣言が無くても、親クラスから継承していれば親の実装が動く。
     * 親を辿らないと「ファクトリが UserDaoImpl を返すと分かったのに、
     * selectById は AbstractDao で宣言されているので見つからない」となる。
     */
    public int implementationIn(String typeFqn, String sig) {
        if (typeFqn == null || typeFqn.isEmpty()) {
            return -1;
        }
        ArrayDeque<String> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(typeFqn);
        seen.add(typeFqn);
        while (!queue.isEmpty()) {
            String t = queue.poll();
            int id = methods.idOf(t + "#" + sig);
            if (id >= 0 && methods.hasBody(id)) {
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

    /** 出所の文字列を共有プールに入れてインデックスを返す。空なら -1 */
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
                      String recvOrigin, String argOrigins) {
        recvKinds[pos] = (byte) recvKind;
        // 呼び出し箇所（呼び出し元メソッド＋レシーバ）に紐づく証拠を引き当てる
        if (!recvKey.isEmpty()) {
            List<Hint> hints = hintsByScope.get(callerKey + "|" + recvKey);
            if (hints != null && !hints.isEmpty()) {
                hintTable.add(hints);
                edgeHint[pos] = hintTable.size() - 1;
            }
        }
        recvOriginIds[pos] = internOrigin(recvOrigin);
        argOriginIds[pos] = internOrigin(argOrigins);
    }
}
