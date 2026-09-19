package fxp;

/**
 * 拡張の効き目を見るための入口。
 *
 * factoryCall … ファクトリのキー "USER_DAO" から UserDaoImpl に絞れるか（フェーズA＋B）
 * chainedCall … 変数に受けずに続けて呼ぶ形でも絞れるか（scopeKey が "@位置" になる経路）
 * injected    … DI 設定由来の「宣言型 -> 具象型」の対応表だけで絞れるか（フェーズBのみ）
 * inheritedImpl … 拡張が返した型が find() を親から継承している場合でも絞れるか（Issue #131）
 * enumKey     … キーが列挙定数でも絞れるか（契約表の C-3。引用符なしの FQN で書く）
 * inheritedFactory … ファクトリの実装が親クラスにあるとき、ソースに書いた子クラスの名前で指定できるか
 * otherFactory … その指定が、同じ親を持つ別の子クラス経由の呼び出しまで巻き込まないか
 * viaParam    … キーが呼び出し元から引数で渡ってくる形でも、経路が分かれば絞れるか
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

    public void inheritedImpl() {
        Dao dao = DaoFactory.get("REPORT_DAO");
        dao.find();
    }

    public void enumKey() {
        Dao dao = DaoFactory.get(DaoKind.ORDER);
        dao.find();
    }

    public void inheritedFactory() {
        Dao dao = ChildDaoFactory.pick("ORDER_DAO");
        dao.find();
    }

    public void otherFactory() {
        Dao dao = OtherDaoFactory.pick("ORDER_DAO");
        dao.find();
    }

    public void viaParam() {
        byKey("ORDER_DAO");
    }

    /** キーは呼び出し元でしか分からない。ここだけを見ても絞れない */
    private void byKey(String key) {
        Dao dao = DaoFactory.get(key);
        dao.find();
    }
}
