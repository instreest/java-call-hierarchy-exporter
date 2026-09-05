package fx.dao;

public final class DaoFactory {
    private DaoFactory() {}

    public static Dao create() {
        return newUserDao();
    }

    static Dao newUserDao() {
        return new UserDaoImpl();
    }

    public static Dao createEither(boolean flag) {
        if (flag) {
            return new UserDaoImpl();
        }
        return new OrderDaoImpl();
    }

    public static Dao byName(String className) throws Exception {
        return (Dao) Class.forName(className).getDeclaredConstructor().newInstance();
    }

    public static Dao passThrough(Dao dao) {
        return dao;
    }
}
