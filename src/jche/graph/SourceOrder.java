// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
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
 *
 * 同じ行に宣言が並ぶ場合の前後も ID では決めない（{@link MethodTable#compareDeclarationOrder}）。
 * ID の振られ方もキャッシュ上の並びで変わるので、ID で決めると全件解析と差分更新とで前後が入れ替わる。
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
     *   4) 同じ行に複数ある場合（1 行に書いたメソッド、暗黙コンストラクタと {@code <clinit>} 等）は、
     *      ファイルの中の宣言の順番 → キーの文字列順（{@link MethodTable#compareDeclarationOrder}）
     * </pre>
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
                .thenComparing(methods::compareDeclarationOrder));
    }

    /**
     * 起点の並び（call-hierarchy.csv の行順）。
     * <pre>
     *   1) ソースフォルダの宣言順
     *   2) 型FQN順（'.'は英数字よりコード上小さいため、文字列比較だけで
     *      「パッケージ自身 -> そのサブパッケージ -> 次のパッケージ」の順になる）
     *   3) 同じ型内では、ソースファイル上の宣言行順
     *   4) 同じ行にある場合（1 行に書いたメソッド、同じ行のラムダ等）は、ファイルの中の宣言の順番 →
     *      キーの文字列順（{@link MethodTable#compareDeclarationOrder}）
     * </pre>
     */
    public static int[] sortedBySource(CallGraph g, IntArray hits) {
        MethodTable methods = g.methods;
        return sorted(hits, Comparator
                .<Integer>comparingInt(id -> g.sourceFolderIndexOf(methods.declFile(id)))
                .thenComparing(methods::typeFqn)
                .thenComparing(methods::compareDeclarationOrder));
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
