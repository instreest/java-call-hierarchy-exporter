package jls.s14_14_02;

import java.util.Iterator;

/**
 * JLS SE 26 §14.14.2 拡張 for 文。
 *
 * 式の型が {@code Iterable} の部分型なら、拡張 for 文は次の基本 for 文と同じ意味である。
 * <pre>
 *   for (I #i = Expression.iterator(); #i.hasNext(); ) { T x = (TargetType) #i.next(); Statement }
 * </pre>
 * ソースに書かれていなくても {@code iterator()}・{@code hasNext()}・{@code next()} は呼ばれる。
 * ここでは 3 つとも利用者のクラスの宣言にしてあるので、辺が無いと
 * 「{@code Countdown.iterator()} を変えたときの影響」が拡張 for 文から辿れない。
 * 配列に対する拡張 for 文は添字で回すので、メソッドは呼ばない。
 */
public class EnhancedFor {
    int run(Countdown countdown, int[] values) {
        int sum = 0;
        for (Integer i : countdown) {
            sum += i;
        }
        for (int v : values) {
            sum += v;
        }
        return sum;
    }
}

class Countdown implements Iterable<Integer> {
    @Override
    public CountIterator iterator() {
        return new CountIterator(3);
    }
}

class CountIterator implements Iterator<Integer> {
    private int left;

    CountIterator(int left) {
        this.left = left;
    }

    @Override
    public boolean hasNext() {
        return left > 0;
    }

    @Override
    public Integer next() {
        return left--;
    }
}
