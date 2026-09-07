package fx.service;

import fx.dao.Dao;
import fx.dao.UserDaoImpl;

/** this(...) 委譲コンストラクタを持つ型。new Repo(x) の実引数は根コンストラクタの引数位置と一致しない */
public class Repo implements Service {
    private final Dao dao;
    private final String name;

    public Repo(Dao dao, String name) {
        this.dao = dao;
        this.name = name;
    }

    public Repo(Dao other) {
        this(new UserDaoImpl(), "repo");
        other.describe();
    }

    @Override
    public void execute() {
        dao.describe();
        name.length();
    }
}
