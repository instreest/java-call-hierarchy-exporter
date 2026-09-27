package jls.s14_20_03;

/**
 * JLS SE 26 §14.20.3.1 の {@code close()} は、資源の型のメンバ（§8.4.8・§15.12.1）の呼び出しである。
 *
 * 資源の型 ChainRes は、親の親 ChainBase のクラスのメソッド {@code close()} と、直接のインターフェース ChainApi の
 * 既定のメソッド {@code close()} を持つ。クラスのメソッドが選ばれる（§8.4.8。ChainBase.close() が ChainApi.close() を
 * 実装する）ので、呼び出し先は ChainBase.close()（javac の所有型 ChainRes から引く宣言も同じ）。近い順に親クラスと
 * インターフェースを混ぜて探すと、1 段目のインターフェース ChainApi の既定のメソッドを先に拾ってしまう。
 * （型変数の資源で親クラスの private な close() を拾わないことは test/pruning の MemberClose で見る。javac は型変数の
 * 資源を AutoCloseable に変換して呼ぶので、ここの javac との突き合わせには置けない）
 */
public class MemberClose {
    static void viaClassChain(ChainRes r) throws Exception {
        try (r) {
            Sink.body();
        }
    }
}

class ChainBase implements AutoCloseable {
    @Override
    public void close() {
        Sink.closed();
    }
}

class ChainMid extends ChainBase {
}

interface ChainApi extends AutoCloseable {
    @Override
    default void close() {
        Sink.closed();
    }
}

class ChainRes extends ChainMid implements ChainApi {
}
