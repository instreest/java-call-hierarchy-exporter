package fx.lambda;

/** 型引数を持つ関数型インターフェース。{@link StringHandler} が抽象メソッドを再宣言する */
public interface Handler<T> {
    void handle(T value);
}
