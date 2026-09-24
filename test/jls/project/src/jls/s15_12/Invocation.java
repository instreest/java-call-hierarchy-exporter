package jls.s15_12;

/**
 * JLS SE 26 §15.12 メソッド呼び出し式。
 *
 * <h2>§15.12.2 コンパイル時の手順 2: 呼び出すメソッドのシグネチャの決定</h2>
 * どのオーバーロードが選ばれるかは javac と同じでなければならない（呼び出し先のキーが変わる）。
 * <ul>
 *   <li>§15.12.2.2（厳密な呼び出し）が §15.12.2.3（ボックス化を許す緩い呼び出し）より先:
 *       {@code pick(1)} は {@code pick(long)}（拡大変換）で、{@code pick(Integer)} ではない</li>
 *   <li>§15.12.2.3 が §15.12.2.4（可変長引数）より先: {@code box(1)} は {@code box(Integer)}</li>
 *   <li>§15.12.2.4: 可変長引数でしか当てはまらないとき {@code vararg(1, 2)} は {@code vararg(int...)}</li>
 *   <li>§15.12.2.5（最も特定的なメソッド）: {@code specific(null)} は {@code specific(String)}</li>
 *   <li>§15.12.2.5 / §18（型推論）: ジェネリックメソッド {@code <T extends Number> T id(T)} の呼び出し先は
 *       消去したシグネチャ {@code id(java.lang.Number)}</li>
 * </ul>
 *
 * <h2>§15.12.4 実行時の評価</h2>
 * <ul>
 *   <li>§15.12.4.1: static メソッドは対象の参照を持たない。インスタンスの式で修飾しても
 *       {@code Animal.kingdom()} が呼ばれる</li>
 *   <li>§15.12.4.4: {@code super.sound()} は上書きを探さない（仮想呼び出しではない）。
 *       {@code new Dog().sound()} で {@code Dog.sound()} に確定させ、その先を出力に出す</li>
 *   <li>§15.12.4.4: 仮想呼び出し {@code animal.sound()} は実行時のクラスから上書きを探すので、
 *       {@code Dog.sound()} も {@code Cat.sound()} も呼ばれうる</li>
 * </ul>
 */
public class Invocation {
    @SuppressWarnings("static-access")
    void run(Animal animal) {
        pick(1);
        box(1);
        vararg(1, 2);
        specific(null);
        Integer i = id(Integer.valueOf(1));
        animal.kingdom();
        animal.sound();
        new Dog().bark(i);
        new Dog().sound();
    }

    void pick(long v) {
    }

    void pick(Integer v) {
    }

    void box(Integer v) {
    }

    void box(int... v) {
    }

    void vararg(int... v) {
    }

    void specific(Object o) {
    }

    void specific(String s) {
    }

    <T extends Number> T id(T t) {
        return t;
    }
}

class Animal {
    static void kingdom() {
    }

    void sound() {
    }
}

class Dog extends Animal {
    @Override
    void sound() {
        super.sound();
    }

    void bark(Integer times) {
    }
}

class Cat extends Animal {
    @Override
    void sound() {
    }
}
