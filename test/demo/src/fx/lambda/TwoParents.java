package fx.lambda;

import fx.dao.Dao;
import fx.dao.OrderDaoImpl;

/**
 * 2 つの親を持つ関数型インターフェース（{@link Door}）のラムダを、JDT が SAM に選ばなかった側の
 * 親の型で受けて呼ぶ形。{@link #viaOpener} と {@link #viaCloser} の先はどちらもラムダの本体に繋がり、
 * {@link #openAny} の {@code o.act()} は {@link PlainOpener} に確定せず {@code UNEXPANDED:LAMBDA} になるべき。
 */
public class TwoParents {

    private final Dao dao = new OrderDaoImpl();

    public void viaDoor() {
        Door d = () -> dao.describe();
        viaOpener(d);
        viaCloser(d);
    }

    private void viaOpener(Opener o) {
        o.act();
    }

    private void viaCloser(Closer c) {
        c.act();
    }

    public void openAny(Opener o) {
        o.act();
    }
}
