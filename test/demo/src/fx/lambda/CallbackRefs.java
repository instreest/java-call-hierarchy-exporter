package fx.lambda;

import java.util.List;

import fx.dao.Dao;
import fx.dao.OrderDaoImpl;

/**
 * 呼び戻しの契約（{@code Thread#start()}・{@code Iterable#forEach}）に、上書き可能なメソッドへの
 * メソッド参照を渡す形（JLS 15.13.3）。実際に動くのはレシーバの実行時クラスの実装なので、
 * 参照先の宣言だけに確定してはいけない。
 * <ul>
 *   <li>{@link #viaThread} … {@code this::hook}。{@link LoudCallbackRefs} が上書きしているので、
 *       両方の {@code hook} を候補として出す</li>
 *   <li>{@link #viaForEach} … {@code Dao::describe}。抽象メソッドを葉にせず、実装を全部出す</li>
 *   <li>{@link #viaBoundField} … {@code dao::describe}。レシーバの具象型が分かるので、その実装に確定する</li>
 * </ul>
 */
public class CallbackRefs {

    private final Dao dao = new OrderDaoImpl();

    protected void hook() {
        dao.findById(20L);
    }

    public void viaThread() {
        new Thread(this::hook).start();
    }

    public void viaForEach(List<Dao> daos) {
        daos.forEach(Dao::describe);
    }

    public void viaBoundField() {
        new Thread(dao::describe).start();
    }
}

/** {@link CallbackRefs#hook} を上書きする */
class LoudCallbackRefs extends CallbackRefs {

    @Override
    protected void hook() {
        loud();
    }

    void loud() {
    }
}
