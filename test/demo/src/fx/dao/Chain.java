package fx.dao;

/** 7段の委譲チェーン。dataflow.max.depth（既定5）を超えるファクトリの委譲 */
public final class Chain {
    private Chain() {}

    public static Dao c1() { return c2(); }
    public static Dao c2() { return c3(); }
    public static Dao c3() { return c4(); }
    public static Dao c4() { return c5(); }
    public static Dao c5() { return c6(); }
    public static Dao c6() { return c7(); }
    public static Dao c7() { return new UserDaoImpl(); }
}
