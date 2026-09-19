package fx.lambda;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import fx.dao.Dao;
import fx.dao.OrderDaoImpl;

/**
 * jar の中（JDK）を経由して自分のコードへ戻ってくる呼び出しを試すための題材（#136）。
 * {@code Thread#start()} や {@code ExecutorService#submit} は jar の中だが、
 * 「渡した Runnable の run を呼ぶ」という契約で繋ぐ。
 */
public class Starter {

    private final Dao dao = new OrderDaoImpl();

    /** Runnable を実装したクラスを Thread で起動する */
    public void viaThread() {
        new Thread(new Job(dao)).start();
    }

    /** Thread を継承して run を上書きする */
    public void viaSubclass() {
        new Worker(dao).start();
    }

    /** ラムダを ExecutorService に渡す */
    public void viaExecutor() {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        pool.submit(() -> dao.describe());
        pool.shutdown();
    }

    /** ローカル変数の Runnable を Thread に渡す（値の追跡と契約の組み合わせ） */
    public void viaVariable() {
        Runnable task = new Job(dao);
        new Thread(task).start();
    }

    static class Job implements Runnable {
        private final Dao dao;

        Job(Dao dao) {
            this.dao = dao;
        }

        @Override
        public void run() {
            dao.findById(3L);
        }
    }

    static class Worker extends Thread {
        private final Dao dao;

        Worker(Dao dao) {
            this.dao = dao;
        }

        @Override
        public void run() {
            dao.describe();
        }
    }
}
