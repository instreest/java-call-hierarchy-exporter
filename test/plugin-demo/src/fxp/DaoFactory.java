package fxp;

/** キーで実装を切り替えるファクトリ。ソースだけ見ても戻り値の具象型は分からない */
public class DaoFactory {
    public static Dao get(String key) {
        return null;
    }

    /** キーを列挙型で受ける形。契約表には fxp.DaoKind.USER のように FQN で書く */
    public static Dao get(DaoKind kind) {
        return null;
    }
}
