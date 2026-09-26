package jls.s14_20_03;

/**
 * JLS SE 26 §14.20.3 try-with-resources。
 *
 * §14.20.3 の通り、資源は初期化と逆の順に閉じられ、§14.20.3.1 の変換で {@code close()} が呼ばれる。ソースに
 * {@code close()} は書かれていないが、呼び出しは必ず起きる。資源の指定は、宣言
 * （{@code Resource first = ...}）と、実質的に final な変数の参照（{@code try (second)}）の 2 形がある。
 * 辺が無いと、{@code Resource.close()} を変えたときの影響が try-with-resources から辿れない。
 *
 * <p>{@code close()} は資源の型のメンバ（§8.4.8。親クラスから継承した実装は親インターフェースの宣言より勝つ）。
 * {@code TwrRes extends TwrMid implements AutoCloseable} の {@code close()} は 2 段上の {@code TwrBase.close()} で、
 * javac も {@code TwrRes.close} を呼んで TwrBase の本体が動く。1 段上の {@code AutoCloseable.close()}
 * （JDK の抽象メソッド）を呼び出し先や実装にすると、TwrBase.close() へ辿れない。
 */
public class TryWithResources {
    void run(Resource second) throws Exception {
        try (Resource first = new Resource(); second) {
            Sink.body();
        }
    }

    void inherited() throws Exception {
        try (TwrRes r = new TwrRes()) {
            Sink.body();
        }
    }
}

class TwrBase {
    public void close() {
        Sink.baseClosed();
    }
}

class TwrMid extends TwrBase {
}

class TwrRes extends TwrMid implements AutoCloseable {
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

    static void baseClosed() {
    }
}
