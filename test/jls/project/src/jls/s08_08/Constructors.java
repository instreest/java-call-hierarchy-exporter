package jls.s08_08;

/**
 * JLS SE 26 §8.8 コンストラクタ宣言と §12.5 インスタンスの生成。
 *
 * <ul>
 *   <li>§8.8.7: 明示的コンストラクタ呼び出しで始まらない本体は、暗黙に {@code super();} を呼ぶ
 *       （{@code ImplicitSuper()}）</li>
 *   <li>§8.8.7（柔軟なコンストラクタ本体）: {@code super(...)} の前に文（プロローグ）を書ける。
 *       明示的な {@code super(w)} があるので暗黙の {@code super()} は無い（{@code Prologue(int)}）</li>
 *   <li>§8.8.7.1 / §12.5: {@code this(...)} で委譲するコンストラクタは、インスタンス初期化子と
 *       インスタンス変数の初期化子を実行しない。実行するのは委譲先（{@code Fields()}）だけ</li>
 *   <li>§8.8.9: コンストラクタを書かないクラスにはデフォルトコンストラクタが暗黙に宣言され、
 *       それは親クラスの引数なしコンストラクタを呼ぶ（{@code DefaultCtor}）</li>
 * </ul>
 */
public class Constructors {
    void run() {
        new ImplicitSuper();
        new Prologue(1);
        new Fields(2);
        new DefaultCtor();
    }
}

class Parent {
    Parent() {
        Sink.parentNoArg();
    }

    Parent(int v) {
        Sink.parentInt(v);
    }
}

class ImplicitSuper extends Parent {
    ImplicitSuper() {
        Sink.body();
    }
}

class Prologue extends Parent {
    Prologue(int v) {
        int w = Sink.check(v);
        super(w);
    }
}

class Fields {
    private final int a = Sink.fieldInit();

    {
        Sink.instanceInit();
    }

    Fields() {
        Sink.body();
    }

    Fields(int v) {
        this();
        Sink.delegated(v + a);
    }
}

class DefaultCtor extends Parent {
}

class Sink {
    static void parentNoArg() {
    }

    static void parentInt(int v) {
    }

    static void body() {
    }

    static int check(int v) {
        return v;
    }

    static int fieldInit() {
        return 0;
    }

    static void instanceInit() {
    }

    static void delegated(int v) {
    }
}
