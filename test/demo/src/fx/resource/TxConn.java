package fx.resource;

/** トランザクション付きの接続。閉じるとコミットする */
public class TxConn implements Conn {

    @Override
    public void query() {
    }

    @Override
    public void close() {
        ConnPool.commit();
    }
}
