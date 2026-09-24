package fx.resource;

/** 接続の出し入れ。close() の実装から呼ばれる先 */
final class ConnPool {

    private ConnPool() {
    }

    static void release() {
    }

    static void commit() {
    }

    static Conn open() {
        return new TxConn();
    }
}
