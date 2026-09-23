package fx.lambda;

/**
 * ソース上の唯一の {@link Opener} 実装クラス。
 * {@link Door} のラムダの M 行が {@code Opener#act()} の鍵でも書かれていないと、
 * {@code Opener} 型の変数への呼び出しが {@code SINGLE_IMPL} でこのクラスに決め打ちされる。
 */
public class PlainOpener implements Opener {

    @Override
    public void act() {
        opened();
    }

    void opened() {
    }
}
