package fx.lambda;

/**
 * ソース上の唯一の {@link Handler} 実装クラス。
 * ラムダの M 行が親の宣言の鍵でも書かれていないと、{@code Handler<String>} 型の変数への
 * 呼び出しが {@code SINGLE_IMPL} でこのクラスに決め打ちされる。
 */
public class LoggingHandler implements StringHandler {

    @Override
    public void handle(String value) {
        logged();
    }

    void logged() {
    }
}
