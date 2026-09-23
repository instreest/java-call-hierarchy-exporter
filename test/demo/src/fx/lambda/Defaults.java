package fx.lambda;

import fx.dao.OrderDaoImpl;

/**
 * インターフェースのフィールドの中のラムダ。フィールドは static と書かなくても static（JLS 9.3）で、
 * 初期化は {@code <clinit>} で走るので、合成メソッドは {@code lambda$static$0} になる（javac と同じ）
 */
public interface Defaults {

    Runnable DEFAULT = () -> new OrderDaoImpl().describe();
}
