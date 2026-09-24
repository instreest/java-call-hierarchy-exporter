package jls.s08_04_08;

/**
 * JLS SE 26 §8.4.8 継承・オーバーライド・隠蔽。
 *
 * <ul>
 *   <li>§8.4.8.1 / §8.4.2: 型引数を具体化した上書き（{@code Store<T>.put(T)} を {@code TextStore.put(String)}
 *       が上書きする）。消去したシグネチャは違うが上書きであり、javac はブリッジメソッド
 *       {@code put(Object)} を作る。O 行に上書き先が残ること、親の型で呼んだときに候補へ入ること</li>
 *   <li>§8.4.8.2: static メソッドは上書きではなく隠蔽。{@code Base.describe()} は
 *       {@code Derived.describe()} へは行かない（§15.12.4.1 の通り、対象の参照を持たない）</li>
 *   <li>§8.4.8: private メソッドは継承されないので上書きされない。{@code Base.secret()} の呼び出しは
 *       {@code Derived.secret()} へは行かない</li>
 * </ul>
 */
public class Overriding {
    void run(Store<String> store, Base base) {
        store.put("x");
        Base.describe();
        base.template();
    }
}

class Store<T> {
    void put(T item) {
        Sink.generic();
    }
}

class TextStore extends Store<String> {
    @Override
    void put(String item) {
        Sink.text();
    }
}

class Base {
    static void describe() {
        Sink.baseStatic();
    }

    private void secret() {
        Sink.basePrivate();
    }

    void template() {
        secret();
    }
}

class Derived extends Base {
    static void describe() {
        Sink.derivedStatic();
    }

    @SuppressWarnings("unused")
    private void secret() {
        Sink.derivedPrivate();
    }
}

class Sink {
    static void generic() {
    }

    static void text() {
    }

    static void baseStatic() {
    }

    static void derivedStatic() {
    }

    static void basePrivate() {
    }

    static void derivedPrivate() {
    }
}
