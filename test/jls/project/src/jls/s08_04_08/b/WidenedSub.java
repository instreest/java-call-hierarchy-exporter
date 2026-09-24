package jls.s08_04_08.b;

import jls.s08_04_08.a.Widened;

/**
 * JLS SE 26 §8.4.8.1: {@link Widened#hook()}（public）を上書きし、それを経由して
 * {@code PackageBase.hook()}（パッケージアクセス）も推移的に上書きする。
 * {@link OtherPackageSub} と違い、こちらは {@code PackageBase.run()} から呼ばれうる。
 */
public class WidenedSub extends Widened {
    @Override
    public void hook() {
    }
}
