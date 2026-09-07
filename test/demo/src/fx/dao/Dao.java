package fx.dao;

public interface Dao {
    Object findById(long id);
    default String name() { return describe(); }
    String describe();
}
