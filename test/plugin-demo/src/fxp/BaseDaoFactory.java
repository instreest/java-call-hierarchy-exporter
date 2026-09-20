package fxp;

/** ファクトリの実装は親クラス側にある。呼び出し側が書くのは子クラスの名前 */
public class BaseDaoFactory {
    public static Dao pick(String key) {
        return null;
    }
}
