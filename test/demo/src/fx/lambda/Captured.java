package fx.lambda;

import fx.dao.Dao;
import fx.dao.OrderDaoImpl;
import fx.dao.UserDaoImpl;

/**
 * ラムダが捕捉した囲みメソッドの引数を、どの段で当てるかを試すための題材。
 *
 * {@link #captureOuter} のラムダは自分の引数 {@code dao}（= {@code OrderDaoImpl}）を捕捉している。
 * そのラムダを {@link #runWithOther} に渡し、そこでは別の {@code Dao}（= {@code UserDaoImpl}）が
 * 第1引数に来る。捕捉した値はラムダを作った時点で決まる（JLS 15.27.2）ので、
 * {@code runWithOther} の段から本体へ降りたときに {@code runWithOther} の引数を当ててはいけない
 * （当てると {@code UserDaoImpl.describe} に誤って確定する）。
 * <ul>
 *   <li>生成の辺（{@code captureOuter → lambda$captureOuter$0}）の先では {@code OrderDaoImpl.describe} に確定する</li>
 *   <li>{@code runWithOther → lambda$captureOuter$0}（DATAFLOW_LAMBDA）の先では絞らず CHA のまま残す</li>
 * </ul>
 */
public class Captured {

    public void viaCapture() {
        captureOuter(new OrderDaoImpl());
    }

    private void captureOuter(Dao dao) {
        runWithOther(new UserDaoImpl(), () -> dao.describe());
    }

    private void runWithOther(Dao other, Runnable r) {
        r.run();
    }
}
