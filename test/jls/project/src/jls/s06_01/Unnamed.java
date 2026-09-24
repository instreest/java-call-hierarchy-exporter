package jls.s06_01;

import java.util.function.BiFunction;

/**
 * JLS SE 26 §6.1 宣言（無名の変数 {@code _}）/ §14.30.1 何にでも一致するパターン。
 *
 * 無名の変数は、局所変数・例外の仮引数・ラムダの仮引数・パターンの成分に使える。
 * 名前が無いだけで、初期化子やラムダ本体の呼び出しは普通に実行される。
 */
public class Unnamed {
    int run(Object o) {
        int _ = Sink.sideEffect();
        try {
            Sink.mayThrow();
        } catch (IllegalStateException _) {
            Sink.handled();
        }
        BiFunction<Integer, Integer, Integer> first = (a, _) -> Sink.pickFirst(a);
        if (o instanceof Pair(var left, _)) {
            return left + first.apply(1, 2);
        }
        return 0;
    }
}

record Pair(int left, int right) {
}

class Sink {
    static int sideEffect() {
        return 0;
    }

    static void mayThrow() {
    }

    static void handled() {
    }

    static int pickFirst(int a) {
        return a;
    }
}
