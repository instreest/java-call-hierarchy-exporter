package fx.dao;

public abstract class AbstractDao implements Dao {
    protected int calls;

    @Override
    public Object findById(long id) {
        calls++;
        return load(id);
    }

    protected abstract Object load(long id);

    public final void flush() {
        calls = 0;
    }

    static void audit(String msg) {
        System.out.println(msg);
    }
}
