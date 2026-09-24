package pp;

/**
 * レシーバを束縛したメソッド参照を実引数で渡す（d::find）。渡した先の r.run() は、束縛したレシーバ（r=）の
 * 具象型 CDao の find に解決する（DataflowResolver#valueOriginOf の FUNCTIONAL は r= を落とさずに渡す）
 */
public class BoundRef {
    static void runIt(Runnable r) { r.run(); }
    public static void main(String[] args) {
        Dao d = new CDao();
        runIt(d::find);
    }
}
