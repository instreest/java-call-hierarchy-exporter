package teamb;

import fx.dao.OrderDaoImpl;
import fx.dao.UserDaoImpl;
import fx.service.Notifier;
import fx.util.Counter;

/**
 * 「他チームの jar」の中身。samples/demo のクラスを参照している。
 * extjars/team-b-batch.jar はこのファイルをコンパイルして作ったもの。
 */
public class NightJob {
    public void run() {
        UserDaoImpl u = new UserDaoImpl();          // 暗黙のデフォルトコンストラクタ（EXACT で照合される）
        u.findById(1L);                              // 親クラス AbstractDao から継承（INHERITED）
        u.describe();                                // EXACT
        u.flush();                                   // 継承した final メソッド（INHERITED）
        new OrderDaoImpl("t").describe();            // 引数付きコンストラクタ（EXACT）
        new Counter(3).bump();
        new Counter().new Inner().touch();           // 内部クラスのコンストラクタは外側インスタンスが引数に付くため未照合
        new Counter.Nested().touch(new Counter());   // static な内部クラスの暗黙コンストラクタ（EXACT）
        new Notifier(u, null).execute();
    }
}
