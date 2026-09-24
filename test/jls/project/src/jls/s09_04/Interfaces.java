package jls.s09_04;

/**
 * JLS SE 26 §9.4 インターフェースのメソッド宣言と §15.12 の呼び出し。
 *
 * <ul>
 *   <li>§9.4.1 / §8.4.8: デフォルトメソッドはそれを上書きしないクラスに継承される。
 *       {@code Plain} 型のレシーバで {@code greet()} を呼ぶと、宣言は {@code Greeter.greet()}</li>
 *   <li>§15.12.1 / §15.12.4.4: {@code Greeter.super.greet()} は親インターフェースのデフォルトメソッドを
 *       名指しで呼ぶ。仮想呼び出しではない</li>
 *   <li>§9.4（private メソッド）: インターフェースの private メソッドは上書きされない</li>
 *   <li>§9.4 / §8.4.8: インターフェースの static メソッドは継承されず、型名で呼ぶ</li>
 * </ul>
 */
public class Interfaces {
    void run(Plain plain, Polite polite) {
        plain.greet();
        polite.greet();
        Greeter.util();
    }
}

interface Greeter {
    default void greet() {
        helper();
    }

    private void helper() {
        Sink.privateHelper();
    }

    static void util() {
        Sink.staticUtil();
    }
}

class Plain implements Greeter {
}

class Polite implements Greeter {
    @Override
    public void greet() {
        Sink.polite();
        Greeter.super.greet();
    }
}

class Sink {
    static void privateHelper() {
    }

    static void staticUtil() {
    }

    static void polite() {
    }
}
