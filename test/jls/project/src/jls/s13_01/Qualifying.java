package jls.s13_01;

/**
 * JLS SE 26 §13.1 呼び出しを修飾する型（qualifying class or interface）と §15.12.4.4 実行時の実装の選び方。
 *
 * javac は、メソッドを宣言した型ではなく<b>修飾する型</b>でメソッドを参照する（§13.1）。
 * <ul>
 *   <li>{@code 式.m()} / {@code 式::m} … 式のコンパイル時の型の消去（型変数なら境界の消去）</li>
 *   <li>{@code m()}（単純名）… m をメンバに持つ、最も内側の囲む型</li>
 * </ul>
 * 実行時に動くのは受け手の実行時のクラスから探した実装で（§15.12.4.4）、そのクラスは修飾する型の
 * 部分型である。したがって宣言した型の部分型でも、修飾する型の部分型でないクラスの実装は動かない。
 * <ul>
 *   <li>{@code plain.greet()}: {@code greet} は {@code Greeter} のデフォルトメソッド。動きうるのは
 *       {@code Plain} が継承した {@code Greeter.greet} と {@code PlainChild.greet}。{@code Polite.greet} は動かない</li>
 *   <li>{@code mid.work()}: {@code work} は {@code Base} で宣言。動きうるのは {@code Mid.work} と
 *       {@code Leaf.work}。{@code Base.work} と {@code Other.work} は動かない</li>
 *   <li>{@code LeafUser.call()} の {@code work()}: 修飾する型は囲む型 {@code LeafUser}。動くのは
 *       継承した {@code Mid.work} だけ</li>
 *   <li>{@code <T extends Mid> t.work()}: 型変数の消去は {@code Mid}</li>
 *   <li>{@code mid::work}: {@code Mid}（§15.13.3 も同じ実装の選び方）</li>
 * </ul>
 */
public class Qualifying {
    void viaInterfaceDefault(Plain plain) {
        plain.greet();
    }

    void viaSubclass(Mid mid) {
        mid.work();
    }

    <T extends Mid> void viaTypeVariable(T t) {
        t.work();
    }

    Runnable viaMethodRef(Mid mid) {
        return mid::work;
    }
}

class LeafUser extends Mid {
    void call() {
        work();
    }
}

interface Greeter {
    default void greet() {
        Sink.greeterDefault();
    }
}

class Plain implements Greeter {
}

class PlainChild extends Plain {
    @Override
    public void greet() {
        Sink.plainChild();
    }
}

class Polite implements Greeter {
    @Override
    public void greet() {
        Sink.polite();
    }
}

class Base {
    void work() {
        Sink.base();
    }
}

class Mid extends Base {
    @Override
    void work() {
        Sink.mid();
    }
}

class Leaf extends Mid {
    @Override
    void work() {
        Sink.leaf();
    }
}

class Other extends Base {
    @Override
    void work() {
        Sink.other();
    }
}

class Sink {
    static void greeterDefault() {
    }

    static void plainChild() {
    }

    static void polite() {
    }

    static void base() {
    }

    static void mid() {
    }

    static void leaf() {
    }

    static void other() {
    }
}
