package jls.s08_04_08.a;

/**
 * JLS SE 26 §8.4.8.1: 同じパッケージのサブクラスは、パッケージアクセスのメソッドを上書きでき、
 * そのとき public に広げてよい（§8.4.8.3）。広げた宣言は別パッケージのサブクラスからも上書きできるので、
 * {@code jls.s08_04_08.b.WidenedSub.hook()} は、この宣言を経由して推移的に
 * {@link PackageBase#hook()} も上書きする。{@code PackageBase.run()} の {@code hook()} から届く。
 */
public class Widened extends PackageBase {
    @Override
    public void hook() {
    }
}
