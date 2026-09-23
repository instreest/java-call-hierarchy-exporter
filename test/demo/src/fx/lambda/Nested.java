package fx.lambda;

import fx.dao.Dao;
import fx.dao.OrderDaoImpl;

/**
 * 合成メソッドの名前が javac と同じになることを試すための題材。
 * <ul>
 *   <li>{@link #viaNested} … 入れ子のラムダ。javac は本体を読み終えた順に番号を振るので、
 *       内側が {@code lambda$viaNested$0}、外側が {@code lambda$viaNested$1}</li>
 *   <li>{@link Mode} … enum 定数の引数の中のラムダは {@code <clinit>} で作られるので
 *       {@code lambda$static$0}（{@code lambda$new$0} ではない）</li>
 * </ul>
 */
public class Nested {

    private final Dao dao = new OrderDaoImpl();

    public void viaNested() {
        Runnable outer = () -> {
            Runnable inner = () -> dao.describe();
            inner.run();
        };
        outer.run();
    }

    enum Mode {
        QUIET(() -> { });

        Mode(Runnable onEnter) {
            onEnter.run();
        }
    }
}
