package pp;

/**
 * ラムダが捕捉した引数（E）。
 * <ul>
 *   <li>outer … 捕捉したラムダをラムダの中で呼ぶ（DataflowResolver#functionalOriginOf の CAPTURED）</li>
 *   <li>outer2 … 捕捉した引数を、ラムダの中から別のメソッドの実引数に渡す（DataflowBuilder の usesParameters が
 *       実引数の中の E を見て、囲みメソッドにも経路の引数を渡すこと）</li>
 *   <li>outer3 … 捕捉したラムダを、ラムダの中から別のメソッドの実引数に渡す（DataflowResolver#valueOriginOf の
 *       CAPTURED が、捕捉したラムダを値として次のフレームへ渡すこと）</li>
 * </ul>
 */
public class Captured {
    static void outer(Runnable r) {
        Runnable w = () -> r.run();
        w.run();
    }
    static void outer2(Dao d) {
        Runnable w = () -> use(d);
        w.run();
    }
    static void outer3(Runnable r) {
        Runnable w = () -> relay(r);
        w.run();
    }
    static void relay(Runnable x) { x.run(); }
    static void use(Dao x) { x.find(); }
    static void hit() { System.out.println("hit"); }
    static void hit3() { System.out.println("hit3"); }
    public static void main(String[] args) {
        outer(() -> hit());
        outer2(new BDao());
        outer3(() -> hit3());
    }
}
