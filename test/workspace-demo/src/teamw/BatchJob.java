package teamw;

import fx.dao.Dao;
import fx.dao.UserDaoImpl;
import fx.service.Notifier;
import fx.service.OrderService;
import fx.util.Counter;

/**
 * test/demo（相手の jar demo-app.jar として解決する）のメソッドを呼ぶ側。ext-src/teamb/NightJob と同じ呼び出しに、
 * 自分のプロジェクトの実装（RemoteDao）を test/demo に渡す形を足してある
 */
public class BatchJob {
    public void run() {
        UserDaoImpl u = new UserDaoImpl();
        u.findById(1L);                               // 親クラス AbstractDao から継承
        u.describe();
        new Counter(3).bump();
        new Notifier(u, null).execute();
        Dao remote = new RemoteDao();
        new OrderService(remote).execute();           // 自分のプロジェクトの Dao の実装を渡す
        Helper.log(remote.describe());
    }
}
