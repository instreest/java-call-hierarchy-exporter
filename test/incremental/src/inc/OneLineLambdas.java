// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package inc;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 1 行に書いたメソッドと、その中の 2 つのラムダ。起点（entry.packages に inc.OneLineLambdas）として
 * 同じ型・同じ宣言行の同着になる。戻り値の出所（R 行）を持つのは 2 つ目のラムダだけ（1 つ目は boolean を
 * 返すので記録しない）なので、読み手は 2 つ目のラムダを先に ID 化する。差分更新で呼び出し側
 * （OneLineUser）のブロックが先頭へ移ると、そこから呼ばれる pair が先に ID 化される。
 * 同着を ID で決めていると、全件解析と差分更新で call-hierarchy.csv の起点の前後が入れ替わる
 */
public class OneLineLambdas {

    void pair() { use(() -> ready(), () -> make()); }

    static void use(BooleanSupplier check, Supplier<Object> make) {
    }

    static boolean ready() {
        return true;
    }

    static Object make() {
        return new Object();
    }
}
