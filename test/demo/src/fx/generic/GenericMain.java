package fx.generic;

/** 起点。引数で受けた宣言型に対して呼ぶので、CHA の候補がそのまま行に出る */
public class GenericMain {
    public static void run(Repo<Order> repo, AbstractStore<Order> store) {
        repo.save(new Order());   // 候補: OrderRepo.save と RawRepo.save の 2 件
        store.put(new Order());   // 候補: AbstractStore.put（本体あり）と OrderStore.put の 2 件
    }
}
