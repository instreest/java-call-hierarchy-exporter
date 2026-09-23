package fx.lambda;

/**
 * 親の抽象メソッドを再宣言した関数型インターフェース（JLS 9.4.1.3）。
 * SAM は {@code StringHandler#handle(String)} だが、{@code Handler<String>} 型の変数への
 * {@code handle} 呼び出し（鍵は {@code Handler#handle(java.lang.Object)}）でも実行される。
 */
public interface StringHandler extends Handler<String> {
    @Override
    void handle(String value);
}
