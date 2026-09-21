package fx.generic;

import java.util.List;

/** 起点。引数で受けた宣言型に対して呼ぶので、CHA の候補がそのまま行に出る */
public class GenericMain {
    public static void run(Repo<Order> repo, AbstractStore<Order> store, List<Order> orders) {
        repo.save(new Order());   // 候補: OrderRepo.save と RawRepo.save の 2 件
        store.put(new Order());   // 候補: AbstractStore.put（本体あり）と OrderStore.put の 2 件

        // 呼び戻しの契約（Iterable#forEach(Consumer) -> a0 : accept(java.lang.Object)）。
        // 契約はシグネチャだけを名指しするので、型引数を具体化した OrderPrinter#accept(Order) は
        // 上書き関係（O 行）を見ないと当たらない。RawPrinter は消去形と一致するので当たる
        orders.forEach(new OrderPrinter());
        orders.forEach(new RawPrinter());
    }
}
