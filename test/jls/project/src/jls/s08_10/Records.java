package jls.s08_10;

/**
 * JLS SE 26 §8.10 record クラス。
 *
 * <ul>
 *   <li>§8.10.4.2: コンパクトな正準コンストラクタ。本体の呼び出しは正準コンストラクタ
 *       {@code Range(int,int)} のものとして数える（引数の並びは record の成分と同じ）</li>
 *   <li>§8.10.4: 正準コンストラクタを書かない record には、成分と同じ引数の正準コンストラクタが
 *       暗黙に宣言される（{@code Pair(java.lang.String,int)}）</li>
 *   <li>§8.10.3: 明示的に宣言したアクセサ（{@code Range.low()}）を呼ぶ辺があること。
 *       書かなかったアクセサ（{@code Range.high()}）は暗黙に宣言されるメンバで、ソースに本体は無い</li>
 * </ul>
 */
public class Records {
    int run() {
        Range r = new Range(1, 2);
        Pair p = new Pair("a", 1);
        return r.low() + r.high() + p.count();
    }
}

record Range(int low, int high) {
    Range {
        Sink.validate(low, high);
    }

    @Override
    public int low() {
        Sink.accessLow();
        return low;
    }
}

record Pair(String name, int count) {
}

class Sink {
    static void validate(int low, int high) {
    }

    static void accessLow() {
    }
}
