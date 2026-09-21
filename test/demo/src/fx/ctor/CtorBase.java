package fx.ctor;

/**
 * 書かれていない {@code super()} が辺になるかの確認用（JLS 8.8.7 / 8.8.9）。
 *
 * このコンストラクタの中の {@link #prepare()} は、{@code super()} を書いていない
 * サブクラスからも実行される。辺にしていないと、そのサブクラス経由の経路が
 * 影響調査から丸ごと抜ける。
 */
public class CtorBase {

    public CtorBase() {
        prepare();
    }

    void prepare() {
    }
}
