package teamd;

import fx.dao.OrderDaoImpl;

/**
 * ear → war → jar と 2 段に入れ子になった配布物の中のクラス。
 * extjars/team-d-app.ear の中の team-d-web.war の WEB-INF/classes/ に入っている。
 * 同じ war の WEB-INF/lib/ には team-b-batch.jar が入れ子で入っている。
 */
public class WebJob {
    public void run() {
        new OrderDaoImpl("w").flush();               // 引数付きコンストラクタ（EXACT）+ 継承した final メソッド（INHERITED）
    }
}
