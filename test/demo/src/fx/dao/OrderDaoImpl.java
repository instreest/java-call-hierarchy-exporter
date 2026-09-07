package fx.dao;

public class OrderDaoImpl extends AbstractDao {
    private final String table;

    public OrderDaoImpl() { this("orders"); }

    public OrderDaoImpl(String table) { this.table = table; }

    @Override
    protected Object load(long id) {
        return table + id;
    }

    @Override
    public Object findById(long id) {
        Object o = super.findById(id);
        return o;
    }

    @Override
    public String describe() { return "order-dao:" + table; }
}
