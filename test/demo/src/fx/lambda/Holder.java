package fx.lambda;

import java.util.ArrayList;
import java.util.List;

import fx.dao.Dao;
import fx.dao.OrderDaoImpl;

/**
 * ラムダ／メソッド参照が「どこを経由して呼ばれるか」を試すための題材。
 *
 * 追える形（合成メソッド lambda$... へ繋がる）:
 * <ul>
 *   <li>フィールドに保持したラムダ（{@link #viaField}）</li>
 *   <li>引数で渡したラムダ（{@link #viaParam} → {@link #runIt}）</li>
 *   <li>コレクションに詰めて拡張for文で回す（{@link #viaCollection}）</li>
 * </ul>
 * 追えない形（注記 [UNEXPANDED:LAMBDA] のまま残る）:
 * <ul>
 *   <li>{@link #viaForEach} … forEach の中の呼び出しは jar の中なので辿れない</li>
 * </ul>
 */
public class Holder {

    private final Dao dao = new OrderDaoImpl();
    /** フィールドに保持したラムダ。初期化子で1つに定まる */
    private final Runnable task = () -> dao.describe();

    public void viaField() {
        task.run();
    }

    public void viaParam() {
        runIt(() -> dao.findById(1L));
    }

    private void runIt(Runnable r) {
        r.run();
    }

    public void viaCollection() {
        List<Runnable> jobs = new ArrayList<>();
        jobs.add(() -> dao.describe());
        for (Runnable job : jobs) {
            job.run();
        }
    }

    public void viaForEach() {
        List<Runnable> jobs = new ArrayList<>();
        jobs.add(() -> dao.findById(2L));
        jobs.forEach(Runnable::run);
    }
}
