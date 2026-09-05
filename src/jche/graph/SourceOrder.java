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

import java.util.Arrays;
import java.util.Comparator;

/**
 * メソッドIDの並びをソースの並び順にする。
 *
 * メソッドIDの順（キャッシュ上の出現順）で出すと、差分更新で解析し直した
 * ファイルがキャッシュの末尾へ移ったり、先に呼び出し先として現れたメソッドが
 * 別ファイルの位置に混ざったりして、実行のたびに並びが変わりうる。
 * ソースの並びに揃えれば、出力は実行のたびに変わらず、同じ型の中では
 * ソースコードの記載順になる。
 */
public final class SourceOrder {

    private SourceOrder() {
    }

    /**
     * ソース上に宣言のある全メソッド（methods.csv の行順）。
     * <pre>
     *   1) ソースフォルダの宣言順（main/testの混在を防ぐ）
     *   2) ファイルの相対パス順（＝パッケージ順。同じファイルの内部クラス・匿名クラスも
     *      そのファイルの位置に並ぶ）
     *   3) 宣言行順
     * </pre>
     * 同じ行に複数ある場合（暗黙コンストラクタと {@code <clinit>} 等）はID順で安定させる。
     */
    public static int[] declaredMethodsInSourceOrder(CallGraph g) {
        MethodTable methods = g.methods;
        IntArray hits = new IntArray(1 << 12);
        for (int id = 0; id < methods.size(); id++) {
            if (methods.hasSource(id)) {
                hits.add(id);
            }
        }
        return sorted(hits, Comparator
                .<Integer>comparingInt(id -> g.sourceFolderIndexOf(methods.declFile(id)))
                .thenComparing(methods::declFile)
                .thenComparingInt(methods::declLine)
                .thenComparingInt(id -> id));
    }

    /**
     * 起点の並び（call-hierarchy.csv の行順）。
     * <pre>
     *   1) ソースフォルダの宣言順
     *   2) 型FQN順（'.'は英数字よりコード上小さいため、文字列比較だけで
     *      「パッケージ自身 -> そのサブパッケージ -> 次のパッケージ」の順になる）
     *   3) 同じ型内では、ソースファイル上の宣言順
     *   4) ID順（同じ行にある場合の安定化）
     * </pre>
     */
    public static int[] sortedBySource(CallGraph g, IntArray hits) {
        MethodTable methods = g.methods;
        return sorted(hits, Comparator
                .<Integer>comparingInt(id -> g.sourceFolderIndexOf(methods.declFile(id)))
                .thenComparing(methods::typeFqn)
                .thenComparingInt(methods::declLine)
                .thenComparingInt(id -> id));
    }

    private static int[] sorted(IntArray hits, Comparator<Integer> order) {
        Integer[] boxed = new Integer[hits.size()];
        for (int i = 0; i < boxed.length; i++) {
            boxed[i] = hits.get(i);
        }
        Arrays.sort(boxed, order);
        int[] result = new int[boxed.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = boxed[i];
        }
        return result;
    }
}
