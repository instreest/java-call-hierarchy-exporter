package fx.resource;

/**
 * 起点。リソースの書き方ごとに、暗黙の close() が実装まで辿れること。
 *
 * 2 つ並べたリソースは宣言と逆の順（b → a）に閉じられる。
 */
public class ResourceMain {

    /** var で受けた new。型が確定しているので PooledConn.close に絞れる */
    public static void pooled() {
        try (var c = new PooledConn()) {
            c.query();
        }
    }

    /** 2 つのリソース。ファクトリの戻り値（TxConn）と new（PooledConn） */
    public static void both() {
        try (Conn a = new PooledConn(); Conn b = ConnPool.open()) {
            a.query();
            b.query();
        }
    }

    /** 既に持っている変数をリソースにする形（Java 9）。外から渡されるので候補は 2 つ */
    public static void existing(Conn c) {
        try (c) {
            c.query();
        }
    }
}
