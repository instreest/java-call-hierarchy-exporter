package fx.resource;

/** プールから借りた接続。閉じるとプールへ返す */
public class PooledConn implements Conn {

    @Override
    public void query() {
    }

    @Override
    public void close() {
        ConnPool.release();
    }
}
