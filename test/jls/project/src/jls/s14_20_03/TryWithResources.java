package jls.s14_20_03;

/**
 * JLS SE 26 §14.20.3 try-with-resources。
 *
 * §14.20.3 の通り、資源は初期化と逆の順に閉じられ、§14.20.3.1 の変換で {@code close()} が呼ばれる。ソースに
 * {@code close()} は書かれていないが、呼び出しは必ず起きる。資源の指定は、宣言
 * （{@code Resource first = ...}）と、実質的に final な変数の参照（{@code try (second)}）の 2 形がある。
 * 辺が無いと、{@code Resource.close()} を変えたときの影響が try-with-resources から辿れない。
 */
public class TryWithResources {
    void run(Resource second) throws Exception {
        try (Resource first = new Resource(); second) {
            Sink.body();
        }
    }
}

class Resource implements AutoCloseable {
    @Override
    public void close() {
        Sink.closed();
    }
}

class Sink {
    static void body() {
    }

    static void closed() {
    }
}
