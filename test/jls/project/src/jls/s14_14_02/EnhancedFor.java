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
 *
 * <p>{@code iterator()} は式の型のメンバ（§8.4.8。親クラスから継承した実装は親インターフェースの宣言より勝つ）。
 * {@code ItColl extends ItMid implements Iterable<String>} の {@code iterator()} は 2 段上の {@code ItBase.iterator()}。
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

    int inherited(ItColl c) {
        int n = 0;
        for (String s : c) {
            n += s.length();
        }
        return n;
    }
}

class ItBase {
    public Iterator<String> iterator() {
        return java.util.Collections.emptyIterator();
    }
}

class ItMid extends ItBase {
}

class ItColl extends ItMid implements Iterable<String> {
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
