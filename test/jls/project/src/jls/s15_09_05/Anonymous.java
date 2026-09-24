package jls.s15_09_05;

/**
 * JLS SE 26 §15.9.5 匿名クラスの宣言。
 *
 * §15.9.5.1: 匿名クラスは暗黙のコンストラクタ（匿名コンストラクタ）を持ち、それは生成式の実引数を
 * そのまま渡して親クラスのコンストラクタを呼ぶ。{@code new Task(5) { ... }} では匿名コンストラクタが
 * {@code Task(int)} を呼ぶので、{@code Task(int)} の中の呼び出しも生成式から辿れる。
 * 匿名クラスのバイナリ名は {@code Anonymous$1}（§13.1）。
 */
public class Anonymous {
    Task make() {
        return new Task(5) {
            @Override
            void perform() {
                Sink.anonymousPerform();
            }
        };
    }
}

abstract class Task {
    Task(int weight) {
        Sink.taskInit(weight);
    }

    abstract void perform();
}

class Sink {
    static void taskInit(int weight) {
    }

    static void anonymousPerform() {
    }
}
