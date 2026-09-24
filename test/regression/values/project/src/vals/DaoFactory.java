package vals;

// キーで実装を返すファクトリ。戻り値が 2 通りあるので、データフローでは 1 つに決まらない（契約表だけが決める）
public class DaoFactory {
    public static Dao get(String key) {
        if (key.isEmpty()) {
            return new UserDaoImpl();
        }
        return new OrderDaoImpl();
    }
}
