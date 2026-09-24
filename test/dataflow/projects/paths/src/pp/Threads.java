package pp;

/**
 * Thread#start() の呼び戻し（同梱の契約 {@code Thread#start() -> c* : run()}。コンストラクタの実引数の
 * どれでもよい）。Runnable を 2 番目の実引数で渡すので、最初の実引数（ThreadGroup）だけを見ると
 * ラムダを見逃す（CallbackContracts#collectArgs の「どれでも」）
 */
public class Threads {
    static void hit() { System.out.println("thread"); }
    public static void main(String[] args) {
        new Thread(new ThreadGroup("g"), () -> hit()).start();
    }
}
