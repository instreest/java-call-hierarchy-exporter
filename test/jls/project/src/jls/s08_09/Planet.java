package jls.s08_09;

/**
 * JLS SE 26 §8.9 enum クラス。
 *
 * <ul>
 *   <li>§8.9.1: 本体を持つ enum 定数（{@code MARS}）は、その enum を親に持つ匿名クラスのインスタンス。
 *       javac のバイナリ名は {@code Planet$1}（§13.1）。匿名クラスの暗黙のコンストラクタは
 *       {@code Planet(String)} を呼ぶ（§15.9.5.1）</li>
 *   <li>§8.9.2 / §12.4.2: enum 定数はクラスの初期化（{@code <clinit>}）で生成される</li>
 *   <li>§8.9.3: {@code values()} と {@code valueOf(String)} は暗黙に宣言されるメンバで、ソースに本体は無い</li>
 *   <li>§8.4.8.1: 定数の本体の {@code weight()} は {@code Planet.weight()} を上書きする。
 *       {@code Planet} 型で呼ぶと、両方が候補になる</li>
 * </ul>
 */
public enum Planet {
    EARTH("e"),
    MARS("m") {
        @Override
        void weight() {
            Sink.marsWeight();
        }
    };

    Planet(String code) {
        Sink.register(code);
    }

    void weight() {
        Sink.defaultWeight();
    }

    static void run(String name) {
        Planet.valueOf(name).weight();
        for (Planet p : Planet.values()) {
            p.weight();
        }
    }
}

class Sink {
    static void register(String code) {
    }

    static void marsWeight() {
    }

    static void defaultWeight() {
    }
}
