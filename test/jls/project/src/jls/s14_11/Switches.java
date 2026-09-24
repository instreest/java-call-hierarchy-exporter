package jls.s14_11;

/**
 * JLS SE 26 §14.11 switch 文 / §15.28 switch 式 / §14.11.1 ガード付きの case。
 *
 * <ul>
 *   <li>§14.11.1: {@code when} のガードは、パターンが一致したときに評価される式で、その中の呼び出しは
 *       switch を囲むメソッドの呼び出し</li>
 *   <li>§8.1.1.2 / §14.11.1.1: sealed なインターフェースの全ての許可された部分型を型パターンで
 *       網羅した switch は default を要らない。各 case の本体の呼び出しがそれぞれ辺になる</li>
 *   <li>§15.28: switch 式の各アームの呼び出しも辺になる。文字列の switch（§14.11.1）が javac の中で
 *       {@code hashCode()} と {@code equals()} に変換されることは、ソースの呼び出しには現れない</li>
 * </ul>
 */
public class Switches {
    int area(Shape s) {
        return switch (s) {
            case Circle c when Sink.isLarge(c.r()) -> Sink.large();
            case Circle c -> Sink.circle(c.r());
            case Square q -> Sink.square(q.side());
        };
    }

    void label(String kind) {
        switch (kind) {
            case "a" -> Sink.kindA();
            case "b" -> Sink.kindB();
            default -> {
            }
        }
    }
}

sealed interface Shape permits Circle, Square {
}

record Circle(int r) implements Shape {
}

record Square(int side) implements Shape {
}

class Sink {
    static boolean isLarge(int r) {
        return r > 10;
    }

    static int large() {
        return 100;
    }

    static int circle(int r) {
        return r;
    }

    static int square(int side) {
        return side;
    }

    static void kindA() {
    }

    static void kindB() {
    }
}
