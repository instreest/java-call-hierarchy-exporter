package jls.s12_04;

/**
 * JLS SE 26 §12.4 クラスとインターフェースの初期化 / §8.7 static 初期化子 / §9.3 インターフェースのフィールド。
 *
 * <ul>
 *   <li>§12.4.2: クラス変数の初期化子と static 初期化子は、テキストの順にクラスの初期化
 *       （{@code <clinit>}）として実行される</li>
 *   <li>§9.3: インターフェースのフィールドは暗黙に {@code public static final}。定数式でない初期化子は
 *       インターフェースの初期化（{@code <clinit>}）で実行される</li>
 *   <li>§15.29（定数式）: 定数式で初期化した {@code static final} は初期化子の呼び出しを持たない</li>
 * </ul>
 */
public class ClassInit {
    static final int CONSTANT = 3;
    static int first = Sink.first();

    static {
        Sink.staticBlock();
    }

    static int last = Sink.last();
}

interface Config {
    int SIZE = Sink.size();
}

class Sink {
    static int first() {
        return 1;
    }

    static void staticBlock() {
    }

    static int last() {
        return 2;
    }

    static int size() {
        return 4;
    }
}
