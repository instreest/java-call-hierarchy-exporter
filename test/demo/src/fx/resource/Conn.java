package fx.resource;

/**
 * try-with-resources の暗黙の {@code close()} が辺になるかの確認用（JLS 14.20.3.1）。
 *
 * {@code close()} は本体を抜けるときにコンパイラが足す呼び出しで、ソースには現れない。
 * 辺にしていないと、実装の中の処理（プールへの返却・コミット）が呼ばれていないように見える。
 */
public interface Conn extends AutoCloseable {

    void query();

    @Override
    void close();
}
