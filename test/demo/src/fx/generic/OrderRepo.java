package fx.generic;

/** 型引数を具体化した実装。キーは {@code save(fx.generic.Order)} で、親とは一致しない */
public class OrderRepo implements Repo<Order> {

    @Override
    public void save(Order item) {
        stored();
    }

    void stored() {
    }
}
