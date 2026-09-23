package fx.lambda;

/** {@link Door} の親の1つ。{@link Closer} と同じ抽象メソッドを持つが、互いに上書きの関係は無い */
public interface Opener {
    void act();
}
