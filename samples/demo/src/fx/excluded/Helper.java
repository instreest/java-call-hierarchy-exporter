package fx.excluded;

import fx.dao.Dao;
import fx.dao.UserDaoImpl;

public class Helper {
    public static void assist(Dao dao) {
        dao.describe();
        new UserDaoImpl().flush();
    }
}
