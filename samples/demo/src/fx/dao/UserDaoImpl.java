package fx.dao;

import java.util.List;

public class UserDaoImpl extends AbstractDao {
    @Override
    protected Object load(long id) {
        audit("user " + id);
        return "user" + id;
    }

    @Override
    public String describe() { return "user-dao"; }

    public void save(List<String> rows) { rows.size(); }
    public void save(fx.other.List rows) { rows.count(); }
}
