package fx.app;

import fx.dao.Dao;
import fx.dao.Chain;
import fx.dao.DaoFactory;
import fx.dao.OrderDaoImpl;
import fx.dao.UserDaoImpl;
import fx.excluded.Helper;
import fx.excluded.Ping;
import fx.model.Color;
import fx.model.Derived;
import fx.model.Point;
import fx.model.Shape;
import fx.reflect.Invoker;
import fx.service.Notifier;
import fx.service.OrderService;
import fx.service.Repo;
import fx.service.Service;
import fx.util.Counter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public class Main {
    static int counter;

    static {
        counter = Counter.initialCount();
    }

    public static void main(String[] args) throws Exception {
        new Main().run(args);
    }

    void run(String[] args) throws Exception {
        Dao dao = DaoFactory.create();
        dao.findById(1L);
        DaoFactory.createEither(args.length > 0).findById(2L);
        DaoFactory.byName("fx.dao.OrderDaoImpl").findById(3L);
        DaoFactory.passThrough(new OrderDaoImpl("t")).findById(4L);

        Service svc = new OrderService(new UserDaoImpl());
        svc.execute();
        Service chain = new Notifier(new OrderDaoImpl(), new OrderService());
        chain.execute();

        useParam(new OrderDaoImpl());
        Dao local = new UserDaoImpl();
        local.findById(5L);
        Dao multi = new UserDaoImpl();
        multi.findById(6L);
        multi = new OrderDaoImpl();
        multi.findById(7L);
        multi.describe();
        noSource(null);
        noImpl(null);

        Helper.assist(dao);
        Ping.a(3);
        Chain.c1().describe();
        Chain.c6().describe();
        new Repo(new OrderDaoImpl()).execute();
        new Derived();
        new Invoker().byLiteral();
        shapes();
        lambdas(dao);
        new Counter(1).bump();
        new Counter().new Inner().touch();
        new Counter.Nested().touch(new Counter());
        new UserDaoImpl().save(new ArrayList<String>());
        new UserDaoImpl().save(new fx.other.List());
        anonymous(dao);
        Legacy.callRemote();
    }

    void noSource(org.w3c.dom.Node node) {
        node.getNodeName();
    }

    void noImpl(fx.model.Exporter exporter) {
        exporter.export(Point.origin());
    }

    void useParam(Dao d) {
        d.findById(8L);
        d.describe();
    }

    void shapes() {
        Shape s = Color.RED;
        s.area();
        Point p = Point.origin();
        p.area();
        Color.BLUE.code();
    }

    void lambdas(final Dao captured) {
        Function<Long, Object> f = id -> captured.findById(id);
        f.apply(9L);
        List<Runnable> tasks = new ArrayList<>();
        tasks.add(() -> captured.describe());
        tasks.add(UserDaoImpl::new);
        int[][] grid = new int[2][2];
        java.util.function.IntFunction<int[]> mk = int[]::new;
        mk.apply(grid.length);
        tasks.forEach(Runnable::run);
    }

    void anonymous(final Dao captured) {
        Service anon = new Service() {
            private final Dao inner = new OrderDaoImpl();

            @Override
            public void execute() {
                captured.findById(10L);
                inner.findById(11L);
                counter++;
            }
        };
        anon.execute();
        class LocalService implements Service {
            @Override
            public void execute() {
                captured.describe();
            }
        }
        new LocalService().execute();
    }
}
