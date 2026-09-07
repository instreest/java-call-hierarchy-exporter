package teamc;

import fx.dao.UserDaoImpl;
import fx.util.Counter;

/**
 * FatJar（Spring Boot 形式）の中の「アプリ自身のクラス」。extjars/team-c-boot.jar の
 * BOOT-INF/classes/ に入っている。同じ jar の BOOT-INF/lib/ には team-b-batch.jar（他チームの jar）と
 * demo-app.jar（自プロジェクトの jar。被参照には数えない）が入れ子で入っている。
 */
public class ReportJob {
    public void run() {
        new Counter(1).bump();                       // EXACT
        new UserDaoImpl().describe();                // 暗黙のデフォルトコンストラクタ + EXACT
        Counter.initialCount();                      // static メソッド（EXACT）
    }
}
