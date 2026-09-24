package jls.s08_04_08.a;

/**
 * JLS SE 26 §8.4.8.1: パッケージアクセスのメソッドは、別パッケージのサブクラスからは上書きできない。
 *
 * {@code jls.s08_04_08.b.OtherPackageSub.hook()} は同じ名前・同じシグネチャだが、
 * {@code PackageBase.hook()} にアクセスできない場所で宣言されているので上書きではない。
 * {@code run()} の中の {@code hook()} は仮想呼び出しだが、{@code OtherPackageSub.hook()} は動かない
 * （同じパッケージで public に広げた {@link Widened} を経由する {@code WidenedSub.hook()} は動く）。
 */
public class PackageBase {
    public void run() {
        hook();
    }

    void hook() {
    }
}
