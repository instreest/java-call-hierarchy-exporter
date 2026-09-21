package fx.generic;

import java.util.function.Consumer;

/**
 * 型引数を具体化した関数型インターフェースの実装。
 *
 * 呼び戻しの契約表は「呼び戻されるメソッドのシグネチャ」だけを書き、それは消去済みの
 * {@code accept(java.lang.Object)} である。このクラスの {@code accept(Order)} は
 * それとキーもシグネチャも一致しないので、上書き関係（O 行）を見ていないと
 * 契約が当たらず、{@code RESOLVED:CALLBACK} の辺が静かに落ちる。
 */
public class OrderPrinter implements Consumer<Order> {

    @Override
    public void accept(Order order) {
        printed();
    }

    void printed() {
    }
}
