package fxp;

/**
 * 拡張の効き目を見るための入口。
 *
 * factoryCall … ファクトリのキー "USER_DAO" から UserDaoImpl に絞れるか（フェーズA＋B）
 * chainedCall … 変数に受けずに続けて呼ぶ形でも絞れるか（scopeKey が "@位置" になる経路）
 * injected    … DI 設定由来の「宣言型 -> 具象型」の対応表だけで絞れるか（フェーズBのみ）
 */
public class App {

    private Service service;

    public void factoryCall() {
        Dao dao = DaoFactory.get("USER_DAO");
        dao.find();
    }

    public void chainedCall() {
        DaoFactory.get("ORDER_DAO").find();
    }

    public void injected() {
        service.run();
    }
}
