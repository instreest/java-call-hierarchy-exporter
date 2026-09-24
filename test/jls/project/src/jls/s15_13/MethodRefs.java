package jls.s15_13;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * JLS SE 26 §15.13 メソッド参照式。
 *
 * §15.13.3: メソッド参照を評価すると関数型インターフェースのインスタンスができ、その抽象メソッドを
 * 呼ぶと参照先のメソッドが呼ばれる。参照を書いた場所から参照先への辺にする（呼ばれる時点は後でも、
 * 参照先を変えたときの影響はここに及ぶ）。形は 5 通りある（§15.13.1）。
 * <ul>
 *   <li>{@code ClassName::staticMethod}</li>
 *   <li>{@code expression::instanceMethod}（束縛されたレシーバ）</li>
 *   <li>{@code ClassName::instanceMethod}（最初の引数がレシーバ）</li>
 *   <li>{@code super::instanceMethod}（仮想呼び出しではない。javac は合成メソッドを経由させる）</li>
 *   <li>{@code ClassName::new} と {@code ArrayType::new}（後者は配列の生成で、呼ぶメソッドは無い）</li>
 * </ul>
 */
public class MethodRefs extends Base {
    Object run(Helper helper) {
        Function<String, Integer> a = Helper::parse;
        Supplier<String> b = helper::name;
        BiFunction<Helper, String, String> c = Helper::greet;
        Runnable d = super::baseWork;
        Supplier<Helper> e = Helper::new;
        IntFunction<Helper[]> f = Helper[]::new;
        return new Object[] {a, b, c, d, e, f};
    }

    @Override
    void baseWork() {
    }
}

class Base {
    void baseWork() {
    }
}

class Helper {
    static Integer parse(String s) {
        return s.length();
    }

    String name() {
        return "h";
    }

    String greet(String who) {
        return who;
    }
}
