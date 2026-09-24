package pp;

/**
 * レシーバを束縛したメソッド参照を持つフィールド（r = dao::find）。フィールドの値は頭だけ（Z:メソッドキー）を
 * 覚える（J 行は頭だけを取り込む）。run() の r.run() がどう解決されるかを経路の記録に残す
 */
public class FieldRef {
    private final Dao dao = new BDao();
    private final Runnable r = dao::find;
    void run() { r.run(); }
    public static void main(String[] args) { new FieldRef().run(); }
}
