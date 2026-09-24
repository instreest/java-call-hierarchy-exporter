package pp;

/** Child の親。コンストラクタで受け取ったフィールド dao を持つ */
public class Parent {
    protected final Dao dao;
    public Parent(Dao dao) { this.dao = dao; }
    public void go() { dao.find(); }
}
