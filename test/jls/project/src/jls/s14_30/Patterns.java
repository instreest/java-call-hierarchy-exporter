package jls.s14_30;

/**
 * JLS SE 26 §14.30 パターン。
 *
 * <ul>
 *   <li>§14.30.2: 値がレコードパターンに一致するかは、各成分の値を<b>アクセサメソッドを呼んで</b>
 *       取り出して判定する。{@code Point(int x, int y)} は {@code Point.x()} と {@code Point.y()} を呼ぶ。
 *       アクセサを明示的に宣言していれば、その本体が実行される</li>
 *   <li>§14.30.1: 入れ子のレコードパターン（{@code Line(Point(var x, var y), Point end)}）も同じ</li>
 * </ul>
 */
public class Patterns {
    int run(Object o) {
        if (o instanceof Point(int x, int y)) {
            return x + y;
        }
        if (o instanceof Line(Point(var x, var y), Point end)) {
            return x + y + end.hashCode();
        }
        return 0;
    }
}

record Point(int x, int y) {
    @Override
    public int x() {
        Sink.readX();
        return x;
    }
}

record Line(Point start, Point end) {
}

class Sink {
    static void readX() {
    }
}
