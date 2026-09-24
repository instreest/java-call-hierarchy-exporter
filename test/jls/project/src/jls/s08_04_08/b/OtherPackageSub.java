package jls.s08_04_08.b;

import jls.s08_04_08.a.PackageBase;

/**
 * JLS SE 26 §8.4.8.1: 別パッケージから同じシグネチャを宣言しても、パッケージアクセスのメソッドは
 * 上書きされない（{@link PackageBase} の説明を参照）。
 */
public class OtherPackageSub extends PackageBase {
    public void hook() {
    }
}
