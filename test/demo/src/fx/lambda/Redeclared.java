package fx.lambda;

import fx.dao.Dao;
import fx.dao.OrderDaoImpl;

/**
 * 再宣言した関数型インターフェース（{@link StringHandler}）のラムダを、
 * 親の型（{@code Handler<String>}）で受けて呼ぶ形。
 * {@link #dispatch} の {@code h.handle("x")} は {@code lambda$viaSuperInterface$0} に繋がるべきで、
 * {@link LoggingHandler} に確定してはいけない。
 */
public class Redeclared {

    private final Dao dao = new OrderDaoImpl();

    public void viaSuperInterface() {
        StringHandler h = value -> dao.describe();
        dispatch(h);
    }

    private void dispatch(Handler<String> h) {
        h.handle("x");
    }
}
