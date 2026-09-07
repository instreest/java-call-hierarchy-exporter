package fx.service;

import fx.dao.Dao;
import fx.dao.DaoFactory;
import fx.dao.UserDaoImpl;
import java.util.function.Supplier;

public class OrderService implements Service {
    private final Dao dao;
    private Dao mutable;
    private final Dao fixed = new UserDaoImpl();
    private static Dao shared = DaoFactory.create();

    public OrderService(Dao dao) {
        this.dao = dao;
    }

    public OrderService() {
        this(DaoFactory.create());
    }

    @Override
    public void execute() {
        dao.findById(10L);
        fixed.findById(11L);
        shared.findById(12L);
        helper();
        Supplier<Object> s = () -> dao.findById(13L);
        s.get();
        Runnable r = this::helper;
        r.run();
        Runnable r2 = OrderService::staticHelper;
        r2.run();
        setMutable(new UserDaoImpl());
        mutable.findById(14L);
    }

    private void helper() {
        dao.describe();
        recurse(3);
    }

    private static void staticHelper() {
        shared.describe();
    }

    void setMutable(Dao d) {
        this.mutable = d;
    }

    private int recurse(int n) {
        return n <= 0 ? 0 : recurse(n - 1);
    }
}
