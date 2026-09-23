package fx.lambda;

/**
 * 2 つの親から上書き同等な抽象メソッドを継承した関数型インターフェース（JLS 9.8）。
 * このラムダは {@code Opener#act()} と {@code Closer#act()} の両方を実装するが、
 * JDT の {@code getFunctionalInterfaceMethod} が返すのは片方だけ
 */
public interface Door extends Opener, Closer {
}
