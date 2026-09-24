package jls.s08_01_03;

/**
 * JLS SE 26 §8.1.3 内部クラスと外側のインスタンス / §14.3 ローカルクラス宣言。
 *
 * 内部クラスのコンストラクタは、javac のバイナリでは外側のインスタンスを暗黙の先頭引数に取る
 * （MethodParameters で mandated）。ローカルクラスは取り込んだ変数も合成の引数にする（synthetic）。
 * どちらもソース上の引数だけでキーを作ること、{@code other.new Inner(2)} の形でも辺が張られることを見る。
 * ローカルクラスのバイナリ名は {@code Outer$1Local}（§13.1）で、正準名を持たない（§6.7）。
 */
public class Outer {
    class Inner {
        Inner(int v) {
            Sink.inner(v);
        }
    }

    static class Nested {
        Nested() {
            Sink.nested();
        }
    }

    void run(Outer other, int k) {
        new Inner(1);
        other.new Inner(2);
        new Nested();
        class Local {
            void go() {
                Sink.local(k);
            }
        }
        new Local().go();
    }
}

class Sink {
    static void inner(int v) {
    }

    static void nested() {
    }

    static void local(int v) {
    }
}
