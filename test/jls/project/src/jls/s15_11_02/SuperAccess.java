package jls.s15_11_02;

/**
 * JLS SE 26 §15.11.2 {@code super} を使ったフィールドアクセスと §15.11.1 一次式によるフィールドアクセス。
 *
 * <ul>
 *   <li>§15.11.2 / §15.26.1: {@code super.worker = …} は、親クラスのフィールド {@code worker} への代入である。
 *       入れ子の子クラスは外側の親クラスの private なフィールドにこの形で書ける（§6.6.1）。
 *       初期化子の {@code new SlowWorker()} だけが入るフィールドではない（{@code Faster}）</li>
 *   <li>§15.11.1: {@code other.worker} は別のインスタンスのフィールドの読み取り（{@code peer}）</li>
 * </ul>
 * キャッシュの版の上げ忘れの検査（test/cacheversion）の題材でもある。super で修飾した書き込み（J 行）と、
 * 別のインスタンスのフィールドの読み取り（値グラフの O: のノード）を事実に含める（docs/cache-unification-qa.md の Q74）。
 */
public class SuperAccess {
    private Worker worker = new SlowWorker();

    void run() {
        worker.work();
    }

    void peer(SuperAccess other) {
        other.worker.work();
    }

    static class Faster extends SuperAccess {
        Faster() {
            super.worker = new FastWorker();
        }
    }
}

interface Worker {
    void work();
}

class SlowWorker implements Worker {
    public void work() {
    }
}

class FastWorker implements Worker {
    public void work() {
    }
}
