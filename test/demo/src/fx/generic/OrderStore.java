package fx.generic;

/** 親の {@code put(T)}（キーは {@code put(java.lang.Object)}）を具体化して上書きする */
public class OrderStore extends AbstractStore<Order> {

    @Override
    public void put(Order item) {
        kept();
    }

    void kept() {
    }
}
